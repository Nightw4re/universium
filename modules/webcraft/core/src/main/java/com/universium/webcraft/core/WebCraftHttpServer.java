package com.universium.webcraft.core;

import com.universium.webcraft.api.WebCraftReadRoute;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import com.mojang.logging.LogUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.universium.webcraft.core.WebCraftRoutes.JSON;
import static com.universium.webcraft.core.WebCraftRoutes.Scope;

final class WebCraftHttpServer implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SOCKET_PATH = "/api/v1/ws";
    static final String RESTRICTED_SOCKET_PATH = "/api/v1/internal/ws";
    private static final int MAX_HTTP_QUEUE = 8;
    private static final int OUTPUT_LIMIT = 64 * 1024 * 1024;
    private static final String DOCS_CSP = "default-src 'none'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
            + "font-src 'self' data:; frame-ancestors 'none'; base-uri 'none'";

    private final WebCraftRoutes routes;
    private final Map<String, ApiDocumentation.Asset> docs;
    private final WebCraftOrigins origins;
    private final ExecutorService workers;
    private final NioEventLoopGroup io;
    private final DefaultChannelGroup channels;
    private final Channel listener;
    private final Semaphore connections = new Semaphore(128);
    private final Semaphore sockets = new Semaphore(48);
    private final Semaphore outputBudget = new Semaphore(OUTPUT_LIMIT);
    private final boolean publicSocket;
    private final boolean restrictedSocket;

    WebCraftHttpServer(InetSocketAddress address, MinecraftServer server,
                       Map<ResourceLocation, Map<String, WebCraftReadRoute>> modules, ViewerAccess access,
                       List<Feed> feeds, BearerToken feedToken, boolean docsEnabled,
                       ApiTransport.Policy transports, List<? extends String> browserOrigins) throws IOException {
        routes = new WebCraftRoutes(server, modules, access, feeds, feedToken, transports);
        docs = docsEnabled ? ApiDocumentation.load(routes.documentedRoutes()) : Map.of();
        origins = new WebCraftOrigins(browserOrigins);
        publicSocket = routes.supportsSocket(Scope.PUBLIC_SOCKET);
        restrictedSocket = routes.supportsSocket(Scope.RESTRICTED_SOCKET);
        workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                Thread.ofVirtual().name("webcraft-api-", 0).factory());
        io = new NioEventLoopGroup(2, Thread.ofPlatform().daemon().name("webcraft-io-", 0).factory());
        channels = new DefaultChannelGroup(io.next(), true);
        try {
            var bound = new ServerBootstrap().group(io)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 32)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            if (!connections.tryAcquire()) {
                                channel.close();
                                return;
                            }
                            channels.add(channel);
                            channel.closeFuture().addListener(ignored -> connections.release());
                            channel.pipeline().addLast("idle", new IdleStateHandler(0, 0, 15))
                                    .addLast(new WriteTimeoutHandler(15))
                                    .addLast(new HttpServerCodec(4096, 8192, 8192))
                                    .addLast(new HttpObjectAggregator(8192))
                                    .addLast(new HttpRequests());
                        }
                    }).bind(address).awaitUninterruptibly();
            if (!bound.isSuccess()) throw new IOException("Could not bind WebCraft API", bound.cause());
            listener = bound.channel();
            channels.add(listener);
        } catch (IOException | RuntimeException exception) {
            shutdown();
            throw exception;
        }
    }

    private final class HttpRequests extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final ArrayDeque<HttpCall> pending = new ArrayDeque<>();
        private boolean busy;

        @Override
        protected void channelRead0(ChannelHandlerContext context, FullHttpRequest incoming) {
            URI target;
            try {
                if (!incoming.decoderResult().isSuccess()) throw new IllegalArgumentException("Invalid HTTP request");
                target = WebCraftRoutes.target(incoming.uri());
            } catch (IllegalArgumentException exception) {
                if (busy) {
                    context.close();
                } else {
                    busy = true;
                    error(context, 400, "invalid_request", false);
                }
                return;
            }
            String path = target.getRawPath();
            String method = incoming.method().name();
            if (SOCKET_PATH.equals(path) || RESTRICTED_SOCKET_PATH.equals(path)) {
                if (busy) {
                    context.close();
                } else {
                    busy = true;
                    upgrade(context, incoming, target, "HEAD".equals(method));
                }
                return;
            }
            if (pending.size() >= MAX_HTTP_QUEUE) {
                context.close();
                return;
            }
            var request = new WebCraftRoutes.Request(target, method, peer(context).getAddress(),
                    incoming.headers().getAll(HttpHeaderNames.AUTHORIZATION), Scope.HTTP);
            pending.add(new HttpCall(request, HttpUtil.isKeepAlive(incoming)));
            processNext(context);
        }

        private void processNext(ChannelHandlerContext context) {
            if (busy || !context.channel().isActive()) return;
            HttpCall call = pending.poll();
            if (call == null) return;
            busy = true;
            var request = call.request();
            URI target = request.target();
            String path = target.getRawPath();
            var asset = docs.get(path);
            if (asset != null) {
                if (!"GET".equals(request.method())) {
                    reply(context, call, Response.error(405, "method_not_allowed"), Map.of("Allow", "GET"));
                } else if (target.getRawQuery() != null) {
                    reply(context, call, Response.error(400, "unsupported_query"), Map.of());
                } else {
                    reply(context, call, 200, asset.contentType(), asset.body(), Map.of("Content-Security-Policy", DOCS_CSP));
                }
                return;
            }
            try {
                workers.execute(() -> {
                    if (!context.channel().isActive()) return;
                    var result = routes.dispatch(request, false);
                    try {
                        reply(context, call, result.response(), result.headers());
                    } catch (RuntimeException exception) {
                        LOGGER.error("WebCraft could not encode an HTTP response", exception);
                        reply(context, call, Response.error(500, "internal_error"), Map.of());
                    }
                });
            } catch (RejectedExecutionException exception) {
                reply(context, call, Response.error(503, "server_busy"), Map.of());
            }
        }

        private void reply(ChannelHandlerContext context, HttpCall call, Response response, Map<String, String> headers) {
            reply(context, call, response.status(), response.image() == null ? JSON : response.imageContentType(),
                    body(response), headers);
        }

        private void reply(ChannelHandlerContext context, HttpCall call, int status, String mime,
                           byte[] body, Map<String, String> headers) {
            boolean head = "HEAD".equals(call.request().method());
            int permits = head ? 0 : Math.min(body.length, OUTPUT_LIMIT);
            if (!outputBudget.tryAcquire(permits)) {
                status = 503;
                mime = JSON;
                body = body(Response.error(status, "output_busy"));
                headers = Map.of();
                permits = 0;
            }
            int reserved = permits;
            ChannelFuture written;
            try {
                written = write(context, status, mime, body, headers, head, call.keepAlive());
            } catch (RuntimeException exception) {
                outputBudget.release(reserved);
                throw exception;
            }
            written.addListener(result -> {
                outputBudget.release(reserved);
                if (call.keepAlive() && result.isSuccess()) {
                    busy = false;
                    processNext(context);
                }
            });
        }

        private void upgrade(ChannelHandlerContext context, FullHttpRequest request, URI target, boolean head) {
            boolean restricted = RESTRICTED_SOCKET_PATH.equals(target.getRawPath());
            if (!(restricted ? restrictedSocket : publicSocket)) {
                error(context, 404, "not_found", head);
                return;
            }
            if (!request.method().name().equals("GET")) {
                write(context, 405, JSON, body(Response.error(405, "method_not_allowed")), Map.of("Allow", "GET"), head, false);
                return;
            }
            if (target.getRawQuery() != null || request.content().isReadable()) {
                error(context, 400, "invalid_upgrade", false);
                return;
            }
            var originHeaders = request.headers().getAll(HttpHeaderNames.ORIGIN);
            if (restricted ? !originHeaders.isEmpty()
                    : !origins.permits(originHeaders, request.headers().getAll(HttpHeaderNames.HOST))) {
                error(context, 403, "origin_forbidden", false);
                return;
            }
            if (!request.headers().containsValue(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true)
                    || !request.headers().containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true)) {
                write(context, 426, JSON, body(Response.error(426, "upgrade_required")),
                        Map.of("Upgrade", "websocket", "Sec-WebSocket-Version", "13"), false, false);
                return;
            }
            if (!"13".equals(request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_VERSION))) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(context.channel())
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (!sockets.tryAcquire()) {
                error(context, 503, "server_busy", false);
                return;
            }
            context.channel().closeFuture().addListener(ignored -> sockets.release());
            var decoder = WebSocketDecoderConfig.newBuilder().maxFramePayloadLength(WebCraftSocket.MAX_COMMAND_BYTES).build();
            var factory = new WebSocketServerHandshakerFactory("ws://localhost" + target.getRawPath(), null, decoder);
            var handshaker = factory.newHandshaker(request);
            var session = new WebCraftSocket(routes, workers, outputBudget,
                    restricted ? Scope.RESTRICTED_SOCKET : Scope.PUBLIC_SOCKET,
                    request.headers().getAll(HttpHeaderNames.AUTHORIZATION), peer(context).getAddress());
            // The handshaker removes the HTTP codec and aggregator. Install the frame handlers before
            // a client can send commands, including when data arrives with the upgrade request.
            var headers = new DefaultHttpHeaders().set("X-WebCraft-Subscription-Updates", "delta");
            var upgraded = handshaker.handshake(context.channel(), request, headers, context.newPromise());
            context.pipeline().replace("idle", "idle", new IdleStateHandler(75, 25, 0));
            context.pipeline().addBefore(context.name(), "utf8", new Utf8FrameValidator());
            context.pipeline().addBefore(context.name(), "frames", new WebSocketFrameAggregator(WebCraftSocket.MAX_COMMAND_BYTES));
            context.pipeline().replace(this, "socket", session);
            upgraded.addListener(future -> {
                if (!future.isSuccess()) context.close();
            });
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext context, Object event) {
            if (event instanceof IdleStateEvent) context.close();
            else context.fireUserEventTriggered(event);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable exception) {
            context.close();
        }
    }

    private record HttpCall(WebCraftRoutes.Request request, boolean keepAlive) {}

    private static InetSocketAddress peer(ChannelHandlerContext context) {
        return (InetSocketAddress) context.channel().remoteAddress();
    }

    private static byte[] body(Response response) {
        return response.image() == null ? response.body().toString().getBytes(StandardCharsets.UTF_8) : response.image();
    }

    private static void error(ChannelHandlerContext context, int status, String code, boolean head) {
        write(context, status, JSON, body(Response.error(status, code)), Map.of(), head, false);
    }

    private static ChannelFuture write(ChannelHandlerContext context, int status, String contentType, byte[] body,
                                       Map<String, String> extraHeaders, boolean head, boolean keepAlive) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
                head ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body));
        var headers = response.headers();
        headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        headers.set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        HttpUtil.setKeepAlive(response, keepAlive);
        headers.set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE);
        headers.set("X-Content-Type-Options", "nosniff");
        extraHeaders.forEach(headers::set);
        return context.writeAndFlush(response).addListener(keepAlive
                ? ChannelFutureListener.CLOSE_ON_FAILURE : ChannelFutureListener.CLOSE);
    }

    InetSocketAddress address() {
        return (InetSocketAddress) listener.localAddress();
    }

    @Override
    public void close() {
        shutdown();
    }

    private void shutdown() {
        workers.shutdownNow();
        channels.close().awaitUninterruptibly();
        io.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    record Feed(String publicPath, FeedExposure exposure, WebCraftReadRoute.PublicRoute handler,
                boolean loopbackOnly, ApiTransport transport) {
        boolean permitsAddress(InetAddress address) {
            return !loopbackOnly || address.isLoopbackAddress();
        }

        String path() {
            return exposure == FeedExposure.PUBLIC ? publicPath : "/api/v1/internal" + publicPath.substring("/api/v1".length());
        }
    }

    static final class BearerToken {
        private final byte[] expectedAuthorization;

        BearerToken(String token, String variable) {
            if (token == null || !token.matches("[A-Za-z0-9_-]{43,128}")) {
                throw new IllegalArgumentException(variable + " requires 43–128 URL-safe characters.");
            }
            expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
        }

        boolean permits(List<String> authorization) {
            return authorization.size() == 1
                    && MessageDigest.isEqual(expectedAuthorization, authorization.getFirst().getBytes(StandardCharsets.UTF_8));
        }
    }

    record ViewerAccess(UUID viewer, BearerToken token) {
        ViewerAccess(UUID viewer, String token) {
            this(viewer, new BearerToken(token, "WEBCRAFT_VIEWER_TOKEN"));
        }
    }
}
