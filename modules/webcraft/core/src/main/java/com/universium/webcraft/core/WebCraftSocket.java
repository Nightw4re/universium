package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.logging.LogUtils;
import com.universium.webcraft.api.WebCraftChanges;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

final class WebCraftSocket extends SimpleChannelInboundHandler<WebSocketFrame> {
    static final int MAX_COMMAND_BYTES = 8192;
    private static final int MAX_RESULT_BYTES = 24 * 1024 * 1024;
    private static final int MAX_BASELINE_BYTES = 128 * 1024;
    private static final int MAX_PENDING = 16;
    private static final int MAX_SUBSCRIPTIONS = 16;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43,128}");
    private static final Logger LOGGER = LogUtils.getLogger();

    private final WebCraftRoutes routes;
    private final Executor workers;
    private final Semaphore output;
    private final WebCraftRoutes.Scope scope;
    private final List<String> authorization;
    private final InetAddress peer;

    // The channel event loop owns the queue, subscriptions and writes. Changes may come from any thread.
    private final ArrayDeque<Command> commands = new ArrayDeque<>();
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    private final AtomicBoolean wakeScheduled = new AtomicBoolean();
    private boolean busy;
    private boolean closing;
    private boolean preferUpdates;
    private int baselineBytes;

    WebCraftSocket(WebCraftRoutes routes, Executor workers, Semaphore output, WebCraftRoutes.Scope scope,
                   List<String> authorization, InetAddress peer) {
        this.routes = routes;
        this.workers = workers;
        this.output = output;
        this.scope = scope;
        this.authorization = authorization;
        this.peer = peer;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) {
        if (closing) return;
        switch (frame) {
            case TextWebSocketFrame text -> {
                if (commands.size() >= MAX_PENDING) {
                    close(context, 1013, "too_many_commands");
                    return;
                }
                try {
                    commands.add(Command.parse(text.text()));
                } catch (IOException | IllegalStateException exception) {
                    close(context, 1007, "invalid_command");
                    return;
                }
                pump(context);
            }
            case CloseWebSocketFrame close -> {
                closing = true;
                context.writeAndFlush(close.retain()).addListener(ChannelFutureListener.CLOSE);
            }
            case PingWebSocketFrame ping -> {
                if (context.channel().isWritable()) {
                    context.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
                }
            }
            case PongWebSocketFrame ignored -> {}
            default -> close(context, 1003, "text_commands_required");
        }
    }

    private void pump(ChannelHandlerContext context) {
        if (closing || busy || !context.channel().isActive()) return;
        if (preferUpdates || commands.isEmpty()) {
            var waiting = subscriptions.values().iterator();
            while (waiting.hasNext()) {
                Subscription subscription = waiting.next();
                if (subscription.dirty.getAndSet(false)) {
                    waiting.remove();
                    subscriptions.put(subscription.id, subscription);
                    busy = true;
                    preferUpdates = false;
                    runRead(context, subscription.id, subscription.request, false, subscription.delta, subscription);
                    return;
                }
            }
        }
        Command command = commands.poll();
        if (command != null) {
            busy = true;
            preferUpdates = true;
            handle(context, command);
        }
    }

    private void handle(ChannelHandlerContext context, Command command) {
        String id = command.id;
        if ("unsubscribe".equals(command.op)) {
            if (command.path != null || command.token != null || command.updates != null) {
                sendError(context, id, 400, "invalid_request");
            } else {
                var removed = subscriptions.remove(id);
                if (removed == null) {
                    sendError(context, id, 404, "subscription_not_found");
                } else {
                    baselineBytes -= size(removed.baseline);
                    removed.close();
                    send(context, id, "unsubscribed", null);
                }
            }
            return;
        }
        boolean subscribe = "subscribe".equals(command.op);
        boolean delta = command.updates != null;
        if ((!subscribe && !"get".equals(command.op)) || command.path == null
                || (delta && (!subscribe || !"delta".equals(command.updates)))) {
            sendError(context, id, 400, "invalid_request");
            return;
        }
        if (subscriptions.containsKey(id)) {
            sendError(context, id, 409, "subscription_id_in_use");
            return;
        }
        if (subscribe && subscriptions.size() >= MAX_SUBSCRIPTIONS) {
            sendError(context, id, 429, "subscription_limit");
            return;
        }
        List<String> bearer = authorization;
        if (command.token != null) {
            if (!authorization.isEmpty() || scope != WebCraftRoutes.Scope.PUBLIC_SOCKET || !TOKEN.matcher(command.token).matches()) {
                sendError(context, id, 400, "invalid_credentials");
                return;
            }
            bearer = List.of("Bearer " + command.token);
        }
        WebCraftRoutes.Request request;
        try {
            request = new WebCraftRoutes.Request(WebCraftRoutes.target(command.path), "GET", peer, bearer, scope);
        } catch (IllegalArgumentException exception) {
            sendError(context, id, 400, "invalid_path");
            return;
        }
        runRead(context, id, request, subscribe, delta, null);
    }

    private void runRead(ChannelHandlerContext context, String id, WebCraftRoutes.Request request,
                         boolean subscribe, boolean delta, Subscription update) {
        byte[] baseline = update == null ? null : update.baseline;
        int baselineLimit = delta ? MAX_BASELINE_BYTES - baselineBytes + size(baseline) : 0;
        try {
            workers.execute(() -> {
                if (!context.channel().isActive()) return;
                Payload payload;
                try {
                    payload = Payload.encode(routes.dispatch(request, subscribe), subscribe || update != null,
                            baseline, baselineLimit);
                } catch (RuntimeException exception) {
                    LOGGER.error("WebCraft could not encode an API response", exception);
                    payload = Payload.error(500, "internal_error");
                }
                Payload ready = payload;
                context.executor().execute(() -> completeRead(context, id, request, delta, update, ready));
            });
        } catch (RejectedExecutionException exception) {
            if (update == null) {
                sendError(context, id, 503, "server_busy");
            } else {
                // Keep the connection's work serialized while waiting for worker capacity.
                context.executor().schedule(() -> {
                    update.dirty.set(true);
                    finished(context);
                }, 100, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void completeRead(ChannelHandlerContext context, String id, WebCraftRoutes.Request request, boolean delta,
                              Subscription update, Payload payload) {
        if (closing || !context.channel().isActive()) return;
        if (update != null) {
            var patch = payload.patch;
            if (Arrays.equals(update.lastDigest, payload.digest) || (patch != null && patch.unchanged())) {
                finished(context);
            } else if (patch != null && patch.bytes() != null) {
                send(context, id, "patch", "patch", patch.bytes(), () -> recordSent(update, payload));
            } else {
                send(context, id, "update", "response", payload.bytes, () -> recordSent(update, payload));
            }
        } else if (!payload.signals.isEmpty()) {
            var subscription = new Subscription(id, request, delta);
            subscriptions.put(id, subscription);
            for (WebCraftChanges changes : payload.signals) {
                subscription.registrations.add(changes.listen(() -> wake(context, subscription)));
            }
            // Recheck once after attaching listeners to cover publication during the initial read.
            subscription.dirty.set(true);
            send(context, id, "snapshot", "response", payload.bytes, () -> recordSent(subscription, payload));
        } else {
            send(context, id, "response", payload.bytes);
        }
    }

    private void recordSent(Subscription subscription, Payload payload) {
        baselineBytes += size(payload.baseline) - size(subscription.baseline);
        subscription.baseline = payload.baseline;
        subscription.lastDigest = payload.digest;
    }

    private static int size(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }

    private void wake(ChannelHandlerContext context, Subscription subscription) {
        subscription.dirty.set(true);
        if (wakeScheduled.compareAndSet(false, true)) {
            try {
                context.executor().execute(() -> {
                    wakeScheduled.set(false);
                    pump(context);
                });
            } catch (RejectedExecutionException ignored) {
                // The event loop is shutting down.
            }
        }
    }

    private void sendError(ChannelHandlerContext context, String id, int status, String code) {
        send(context, id, "response", Payload.error(status, code).bytes);
    }

    private void send(ChannelHandlerContext context, String id, String type, byte[] payload) {
        send(context, id, type, "response", payload, () -> {});
    }

    private void send(ChannelHandlerContext context, String id, String type, String field, byte[] payload, Runnable written) {
        String envelope = "{\"id\":\"" + id + "\",\"type\":\"" + type + "\"";
        byte[] prefix = (envelope + (payload == null ? "}" : ",\"" + field + "\":")).getBytes(StandardCharsets.US_ASCII);
        int size = prefix.length + (payload == null ? 0 : payload.length + 1);
        if (!output.tryAcquire(size)) {
            close(context, 1013, "output_busy");
            return;
        }
        var buffer = payload == null ? Unpooled.wrappedBuffer(prefix)
                : Unpooled.wrappedBuffer(prefix, payload, new byte[]{'}'});
        context.writeAndFlush(new TextWebSocketFrame(buffer)).addListener(future -> {
            output.release(size);
            if (future.isSuccess()) {
                written.run();
                finished(context);
            } else {
                context.close();
            }
        });
    }

    private void finished(ChannelHandlerContext context) {
        busy = false;
        // Yield to socket reads between queued replies, even when a write completed synchronously.
        context.executor().execute(() -> pump(context));
    }

    private void close(ChannelHandlerContext context, int status, String reason) {
        closing = true;
        context.writeAndFlush(new CloseWebSocketFrame(status, reason)).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof IdleStateEvent idle) {
            if (idle.state() == IdleState.READER_IDLE || !context.channel().isWritable()) context.close();
            else if (!closing) context.writeAndFlush(new PingWebSocketFrame());
        } else {
            context.fireUserEventTriggered(event);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        closing = true;
        commands.clear();
        subscriptions.values().forEach(Subscription::close);
        subscriptions.clear();
        baselineBytes = 0;
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable exception) {
        context.close();
    }

    private record Command(String id, String op, String path, String token, String updates) {
        private static Command parse(String text) throws IOException {
            try (var reader = new JsonReader(new StringReader(text))) {
                var fields = new HashMap<String, String>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!List.of("id", "op", "path", "token", "updates").contains(key) || reader.peek() != JsonToken.STRING
                            || fields.putIfAbsent(key, reader.nextString()) != null) {
                        throw new IOException("Invalid WebSocket command field");
                    }
                }
                reader.endObject();
                String id = fields.get("id");
                if (reader.peek() != JsonToken.END_DOCUMENT || id == null || !ID.matcher(id).matches()) {
                    throw new IOException("Invalid WebSocket command ID");
                }
                return new Command(id, fields.get("op"), fields.get("path"), fields.get("token"), fields.get("updates"));
            }
        }
    }

    private record Payload(byte[] bytes, byte[] digest, List<WebCraftChanges> signals,
                           byte[] baseline, WebCraftSubscriptionPatch patch) {
        static Payload encode(WebCraftRoutes.Result result, boolean compare, byte[] previous, int baselineLimit) {
            var response = result.response();
            var json = new JsonObject();
            json.addProperty("status", response.status());
            if (response.image() == null) {
                json.addProperty("contentType", WebCraftRoutes.JSON);
                json.add("body", response.body());
            } else {
                json.addProperty("contentType", response.imageContentType());
                json.addProperty("base64", Base64.getEncoder().encodeToString(response.image()));
            }
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_RESULT_BYTES) {
                return encode(new WebCraftRoutes.Result(Response.error(413, "response_too_large")), compare, null, 0);
            }
            if (bytes.length <= baselineLimit && response.status() / 100 == 2
                    && response.image() == null && response.body() instanceof JsonObject body) {
                var patch = previous == null || Arrays.equals(previous, bytes) ? null
                        : WebCraftSubscriptionPatch.between(previous, response.status(), body, bytes.length);
                return new Payload(bytes, digest(bytes), result.signals(), bytes, patch);
            }
            return new Payload(bytes, compare ? digest(bytes) : null, result.signals(), null, null);
        }

        static Payload error(int status, String code) {
            return encode(new WebCraftRoutes.Result(Response.error(status, code)), false, null, 0);
        }

        private static byte[] digest(byte[] bytes) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(bytes);
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static final class Subscription {
        final String id;
        final WebCraftRoutes.Request request;
        final boolean delta;
        final List<WebCraftChanges.Registration> registrations = new ArrayList<>(2);
        final AtomicBoolean dirty = new AtomicBoolean();
        byte[] lastDigest;
        byte[] baseline;

        Subscription(String id, WebCraftRoutes.Request request, boolean delta) {
            this.id = id;
            this.request = request;
            this.delta = delta;
        }

        void close() {
            registrations.forEach(WebCraftChanges.Registration::close);
        }
    }
}
