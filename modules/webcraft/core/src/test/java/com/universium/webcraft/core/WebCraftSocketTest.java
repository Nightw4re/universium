package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.universium.webcraft.api.WebCraftChanges;
import com.universium.webcraft.api.WebCraftLiveRoute;
import com.universium.webcraft.api.WebCraftReadRoute;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class WebCraftSocketTest {
    private static final String DISCOVERY = WebCraftRoutes.MODULES_PATH;
    private static final ResourceLocation MODULE = ResourceLocation.parse("test:test");
    private static final String ROUTES = DISCOVERY + "/test/test/";
    private static final String VIEWER_TOKEN = "V".repeat(43);
    private static final String FEED_TOKEN = "F".repeat(43);
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void socketOnlyServesDiscoveryAuthorizedReadsAndOriginalImageBytes() throws Exception {
        var reads = new AtomicInteger();
        byte[] image = {1, 2, 3, 4};
        var modules = Map.of(MODULE, Map.<String, WebCraftReadRoute>of(
                "private", (server, viewer) -> {
                    assertEquals(VIEWER, viewer);
                    reads.incrementAndGet();
                    return Response.ok(new JsonObject());
                },
                "image", (WebCraftReadRoute.PublicRoute) query -> "hash=a%2Bb".equals(query)
                        ? new Response(200, new JsonObject(), image, "image/webp")
                        : Response.error(400, "invalid_hash")));
        try (var server = start(ApiTransport.WEBSOCKET, List.of(), modules, List.of());
             var client = HttpClient.newHttpClient();
             var browser = connect(client, server, false, "http://" + authority(server), null)) {
            var rest = http(client, server, DISCOVERY);
            assertEquals(405, rest.statusCode());
            assertEquals("websocket", rest.headers().firstValue("X-WebCraft-Transport").orElseThrow());
            var spec = JsonParser.parseString(http(client, server, ApiDocumentation.SPEC_PATH).body()).getAsJsonObject();
            assertTrue(spec.getAsJsonObject("paths").isEmpty());

            var discovery = browser.get(DISCOVERY, null);
            var document = response(discovery, 200).getAsJsonObject("body");
            assertEquals(ROUTES + "image", document.getAsJsonArray("modules").get(0).getAsJsonObject()
                    .getAsJsonObject("routes").get("image").getAsString());
            assertEquals(2, document.getAsJsonArray("websocketOnly").size());
            assertEquals("unsupported_query", error(browser.get(DISCOVERY + "?", null), 400));
            assertEquals("unauthorized", error(browser.get(ROUTES + "private", null), 401));
            assertEquals("not_found", error(browser.get(ROUTES + "%70rivate", VIEWER_TOKEN), 404));
            assertEquals("unsupported_query", error(browser.get(ROUTES + "private?player=someone", VIEWER_TOKEN), 400));
            assertEquals(0, reads.get());
            response(browser.get(ROUTES + "private", VIEWER_TOKEN), 200);
            assertEquals(1, reads.get());
            var binary = response(browser.get(ROUTES + "image?hash=a%2Bb", null), 200);
            assertEquals("image/webp", binary.get("contentType").getAsString());
            assertArrayEquals(image, Base64.getDecoder().decode(binary.get("base64").getAsString()));
            assertEquals("subscriptions_unsupported", error(browser.command("static", "subscribe", ROUTES + "image", null), 405));
        }
    }

    @Test
    void resolvesExactRouteThenFeedThenGlobalAndKeepsSwaggerHttpOnly() throws Exception {
        var info = new ServerInfoRoute("", "");
        var publicInfo = new WebCraftHttpServer.Feed(ServerInfoRoute.PATH, FeedExposure.PUBLIC, info, false, ApiTransport.HTTP);
        var status = new ServerStatusRoute();
        status.markReady();
        var publicStatus = new WebCraftHttpServer.Feed(ServerStatusRoute.PATH, FeedExposure.PUBLIC, status, false, ApiTransport.BOTH);
        try (var server = start(ApiTransport.WEBSOCKET, List.of(ServerStatusRoute.PATH + "=WEBSOCKET"),
                Map.of(), List.of(publicInfo, publicStatus));
             var client = HttpClient.newHttpClient();
             var socket = connect(client, server, false, "https://frontend.example", null)) {
            assertEquals(405, http(client, server, DISCOVERY).statusCode());
            assertEquals(405, http(client, server, ServerStatusRoute.PATH).statusCode());
            assertEquals(200, http(client, server, ServerInfoRoute.PATH).statusCode());
            response(socket.get(ServerStatusRoute.PATH, null), 200);
            assertEquals("transport_disabled", error(socket.get(ServerInfoRoute.PATH, null), 405));
            assertEquals(404, http(client, server, "/api/v1/internal/server-status").statusCode());
            var paths = JsonParser.parseString(http(client, server, ApiDocumentation.SPEC_PATH).body()).getAsJsonObject()
                    .getAsJsonObject("paths");
            assertEquals(List.of(ServerInfoRoute.PATH), List.copyOf(paths.keySet()));
        }
        try (var server = start(ApiTransport.HTTP, List.of(), Map.of(), List.of());
             var client = HttpClient.newHttpClient()) {
            assertEquals(200, http(client, server, DISCOVERY).statusCode());
            rejectUpgrade(client, server, false, null, 404);
        }
        assertThrows(IllegalArgumentException.class, () -> ApiTransport.Policy.from(ApiTransport.HTTP,
                List.of(DISCOVERY + "=BOTH", DISCOVERY + "=WEBSOCKET")));
    }

    @Test
    void confinesInternalAndLoopbackFeedsToRestrictedScopeAndChecksOriginAndToken() throws Exception {
        var online = new PlayerListRoute();
        var roster = new PlayerListRoute();
        var history = new PlayerHistoryRoute();
        var feeds = List.of(new WebCraftHttpServer.Feed(PlayerListRoute.ONLINE_PATH, FeedExposure.INTERNAL, online, true, ApiTransport.INHERIT),
                new WebCraftHttpServer.Feed(PlayerListRoute.ROSTER_PATH, FeedExposure.INTERNAL, roster, false, ApiTransport.INHERIT),
                new WebCraftHttpServer.Feed(PlayerHistoryRoute.PATH, FeedExposure.INTERNAL, history, false, ApiTransport.INHERIT),
                new WebCraftHttpServer.Feed(ServerInfoRoute.PATH, FeedExposure.PUBLIC, new ServerInfoRoute("", ""), true, ApiTransport.INHERIT));
        try (var server = start(ApiTransport.BOTH, List.of(), Map.of(), feeds);
             var client = HttpClient.newHttpClient();
             var browser = connect(client, server, false, "https://frontend.example", null);
             var machine = connect(client, server, true, null, FEED_TOKEN);
             var unauthorized = connect(client, server, true, null, VIEWER_TOKEN)) {
            rejectUpgrade(client, server, false, "https://evil.example", 403);
            rejectUpgrade(client, server, true, "https://frontend.example", 403);
            for (var feed : feeds) {
                assertEquals("not_found", error(browser.get(feed.path(), FEED_TOKEN), 404));
                response(machine.command("s" + feeds.indexOf(feed), "subscribe", feed.path(), null), 200);
            }
            assertEquals("not_found", error(machine.get(DISCOVERY, null), 404));
            assertEquals("unauthorized", error(unauthorized.get(feeds.getFirst().path(), null), 401));
            assertEquals("invalid_credentials", error(unauthorized.get(feeds.getFirst().path(), FEED_TOKEN), 400));

            online.publish(JsonParser.parseString("[{\"name\":\"Current\",\"pingMillis\":17}]").getAsJsonArray());
            assertPlayerUpdate(machine, "s0", "Current");
            roster.publish(JsonParser.parseString("[{\"name\":\"Saved\"}]").getAsJsonArray());
            assertPlayerUpdate(machine, "s1", "Saved");
            history.publish(List.of(new PlayerHistoryData.Player(VIEWER, "Seen", 125)));
            assertPlayerUpdate(machine, "s2", "Seen");
        }
    }

    @Test
    void sendsOnlyChangedSnapshotsAndClearsSubscriptions() throws Exception {
        var status = new ServerStatusRoute();
        var info = new ServerInfoRoute("Example", "");
        var addon = new LiveAddon();
        var feeds = List.of(new WebCraftHttpServer.Feed(ServerStatusRoute.PATH, FeedExposure.PUBLIC, status, false, ApiTransport.INHERIT),
                new WebCraftHttpServer.Feed(ServerInfoRoute.PATH, FeedExposure.PUBLIC, info, false, ApiTransport.INHERIT));
        try (var server = start(ApiTransport.BOTH, List.of(), Map.of(MODULE, Map.of("live", addon)), feeds);
             var client = HttpClient.newHttpClient();
             var socket = connect(client, server, false, null, null)) {
            assertEquals("snapshot", type(socket.command("status", "subscribe", ServerStatusRoute.PATH, null)));
            assertEquals("server_unavailable", error(socket.get(ServerInfoRoute.PATH, null), 503));
            status.markReady();
            assertEquals("ready", response(socket.next(), 200).getAsJsonObject("body").get("status").getAsString());
            var infoSnapshot = socket.command("info", "subscribe", ServerInfoRoute.PATH + "?iconFormat=webp", null);
            assertEquals("snapshot", type(infoSnapshot));
            assertEquals(JsonParser.parseString("""
                    {"serverName":"Example"}
                    """), response(infoSnapshot, 200).get("body"));
            assertEquals("snapshot", type(socket.command("addon", "subscribe", ROUTES + "live", null)));
            assertEquals("subscription_id_in_use", error(socket.command("addon", "get", DISCOVERY, null), 409));
            addon.changes.changed();
            assertNull(socket.messages.poll(200, TimeUnit.MILLISECONDS));
            addon.value = 1;
            addon.changes.changed();
            var updated = socket.next();
            assertEquals("update", type(updated));
            assertEquals(1, response(updated, 200).get("body").getAsInt());
            var fields = new JsonObject();
            fields.addProperty("playersOnline", 5);
            fields.addProperty("worldAgeDays", 0);
            fields.addProperty("worldAgeMinutes", 5);
            info.publish(fields, "png", "webp");
            var infoUpdate = socket.next();
            assertEquals("update", type(infoUpdate));
            assertEquals(JsonParser.parseString("""
                    {"serverName":"Example","playersOnline":5,"worldAgeDays":0,"worldAgeMinutes":5,"serverIcon":"webp"}
                    """), response(infoUpdate, 200).get("body"));
            var nextFields = fields.deepCopy();
            nextFields.addProperty("worldAgeMinutes", 6);
            info.publish(nextFields, "png", "webp");
            var nextInfoUpdate = socket.next();
            assertEquals("update", type(nextInfoUpdate));
            assertEquals(JsonParser.parseString("""
                    {"serverName":"Example","playersOnline":5,"worldAgeDays":0,"worldAgeMinutes":6,"serverIcon":"webp"}
                    """), response(nextInfoUpdate, 200).get("body"));
            assertEquals("unsubscribed", type(socket.command("addon", "unsubscribe", null, null)));
            assertEquals("subscription_not_found", error(socket.command("addon", "unsubscribe", null, null), 404));
            addon.value = 2;
            addon.changes.changed();
            assertNull(socket.messages.poll(200, TimeUnit.MILLISECONDS));
            status.markStopping();
            assertEquals("stopping", response(socket.next(), 200).getAsJsonObject("body").get("status").getAsString());
            assertEquals("server_unavailable", error(socket.next(), 503));
        }
    }

    @Test
    void advertisesDeltaOnPublicAndRestrictedUpgrades() throws Exception {
        var feed = new WebCraftHttpServer.Feed(PlayerHistoryRoute.PATH, FeedExposure.INTERNAL,
                new PlayerHistoryRoute(), false, ApiTransport.INHERIT);
        try (var server = start(ApiTransport.BOTH, List.of(), Map.of(), List.of(feed))) {
            assertDeltaUpgrade(server, WebCraftHttpServer.SOCKET_PATH, "");
            assertDeltaUpgrade(server, WebCraftHttpServer.RESTRICTED_SOCKET_PATH,
                    "Authorization: Bearer " + FEED_TOKEN + "\r\n");
        }
    }

    @Test
    void deltaUptimeOmitsUnchangedIconAndLegacyStillGetsTheFullUpdate() throws Exception {
        var info = new ServerInfoRoute("Example", "");
        var feed = new WebCraftHttpServer.Feed(ServerInfoRoute.PATH, FeedExposure.PUBLIC,
                info, false, ApiTransport.INHERIT);
        String icon = "data:image/png;base64," + "A".repeat(6_000);
        info.publish(json("{\"uptimeSeconds\":10}"), icon, null);
        try (var server = start(ApiTransport.BOTH, List.of(), Map.of(), List.of(feed));
             var client = HttpClient.newHttpClient();
             var legacy = connect(client, server, false, null, null);
             var delta = connect(client, server, false, null, null)) {
            var initial = delta.command("info", "subscribe", ServerInfoRoute.PATH, null, "delta");
            assertEquals("snapshot", type(initial));
            assertEquals(icon, response(initial, 200).getAsJsonObject("body").get("serverIcon").getAsString());
            assertEquals(initial, legacy.command("info", "subscribe", ServerInfoRoute.PATH, null));

            info.publish(json("{\"uptimeSeconds\":11}"), icon, null);
            var patch = delta.next();
            assertEquals(json("""
                    {"id":"info","type":"patch","patch":{"set":{"uptimeSeconds":11},"remove":[]}}
                    """), patch);
            var update = legacy.next();
            assertEquals("update", type(update));
            assertEquals(json("{\"serverName\":\"Example\",\"serverIcon\":\"" + icon + "\",\"uptimeSeconds\":11}"),
                    response(update, 200).getAsJsonObject("body"));
            assertTrue(patch.toString().length() < 100);
            assertTrue(update.toString().length() > 6_000);
        }
    }

    @Test
    void patchesSetAndRemoveKeysAndRebuildFromFullUpdatesAndReconnect() throws Exception {
        var initial = json("""
                {"removed":false,"number":9007199254740992,"array":[1,2],"nested":{"a":1,"b":2}}
                """);
        var addon = new LiveResponse(Response.ok(initial));
        try (var server = start(ApiTransport.BOTH, List.of(), Map.of(MODULE, Map.of("live", addon)), List.of());
             var client = HttpClient.newHttpClient()) {
            var current = json("""
                    {"number":9007199254740993,"array":[3],"nullable":null,"nested":{"b":2,"a":1}}
                    """);
            try (var delta = connect(client, server, false, null, null)) {
                String path = ROUTES + "live";
                assertEquals("invalid_request", error(delta.command("get", "get", path, null, "delta"), 400));
                assertEquals("invalid_request", error(delta.command("live", "subscribe", path, null, "other"), 400));
                assertEquals("snapshot", type(delta.command("live", "subscribe", path, null, "delta")));
                assertEquals("invalid_request", error(delta.command("live", "unsubscribe", null, null, "delta"), 400));

                addon.publish(Response.ok(current));
                assertEquals(json("""
                        {"set":{"number":9007199254740993,"array":[3],"nullable":null},"remove":["removed"]}
                        """), delta.next().getAsJsonObject("patch"));
                addon.publish(Response.ok(json("""
                        {"nullable":null,"number":9007199254740993,"array":[3],"nested":{"a":1,"b":2}}
                        """)));
                assertNull(delta.messages.poll(200, TimeUnit.MILLISECONDS));

                addon.publish(new Response(201, current));
                var changedStatus = delta.next();
                assertEquals("update", type(changedStatus));
                assertEquals(current, response(changedStatus, 201).get("body"));
                addon.publish(Response.png(new byte[]{1, 2, 3}));
                var binary = delta.next();
                assertEquals("update", type(binary));
                assertEquals("AQID", response(binary, 200).get("base64").getAsString());

                addon.publish(Response.ok(current));
                var restored = delta.next();
                assertEquals("update", type(restored));
                assertEquals(current, response(restored, 200).get("body"));
                var next = current.deepCopy();
                next.addProperty("count", 1);
                addon.publish(Response.ok(next));
                assertEquals(json("{\"set\":{\"count\":1},\"remove\":[]}"), delta.next().getAsJsonObject("patch"));
                current = next;
            }
            try (var reconnected = connect(client, server, false, null, null)) {
                var full = reconnected.command("live", "subscribe", ROUTES + "live", null, "delta");
                assertEquals("snapshot", type(full));
                assertEquals(current, response(full, 200).get("body"));
            }
        }
    }

    @Test
    void sendsFullUpdatesWhenTheBaselineOrPatchWouldBeTooLarge() throws Exception {
        var initial = new JsonObject();
        initial.addProperty("icon", "A".repeat(128 * 1024));
        initial.addProperty("uptime", 10);
        var addon = new LiveResponse(Response.ok(initial));
        try (var server = start(ApiTransport.BOTH, List.of(), Map.of(MODULE, Map.of("live", addon)), List.of());
             var client = HttpClient.newHttpClient();
             var delta = connect(client, server, false, null, null)) {
            assertEquals("snapshot", type(delta.command("live", "subscribe", ROUTES + "live", null, "delta")));
            var oversized = initial.deepCopy();
            oversized.addProperty("uptime", 11);
            addon.publish(Response.ok(oversized));
            assertEquals("update", type(delta.next()));
            var body = new JsonObject();
            body.addProperty("removable_" + "a".repeat(100), true);
            addon.publish(Response.ok(body));
            assertEquals("update", type(delta.next()));
            addon.publish(Response.ok(new JsonObject()));
            var removal = delta.next();
            assertEquals("update", type(removal));
            assertEquals(new JsonObject(), response(removal, 200).get("body"));
        }
    }

    @Test
    void releasesSocketCapacityWhenSubscribedClientsDisconnect() throws Exception {
        var status = new ServerStatusRoute();
        var feeds = List.of(new WebCraftHttpServer.Feed(ServerStatusRoute.PATH, FeedExposure.PUBLIC, status, false, ApiTransport.INHERIT));
        try (var server = start(ApiTransport.BOTH, List.of(), Map.of(), feeds);
             var client = HttpClient.newHttpClient()) {
            for (int attempt = 0; attempt < 50; attempt++) {
                try (var socket = connect(client, server, false, null, null)) {
                    assertEquals("snapshot", type(socket.command("status", "subscribe", ServerStatusRoute.PATH, null)));
                    socket.socket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
                    assertEquals(WebSocket.NORMAL_CLOSURE, socket.closed.get(3, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void preservesOrderedHttpResponsesOnAReusedConnection() throws Exception {
        try (var server = start(ApiTransport.BOTH, List.of(), Map.of(), List.of());
             var socket = new Socket("127.0.0.1", server.address().getPort())) {
            socket.setSoTimeout(3000);
            String requests = "GET " + DISCOVERY + " HTTP/1.1\r\nHost: " + authority(server) + "\r\n\r\n"
                    + "GET /api/v1/missing HTTP/1.1\r\nHost: " + authority(server) + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(requests.getBytes(StandardCharsets.US_ASCII));
            String replies = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(replies.startsWith("HTTP/1.1 200"));
            assertTrue(replies.contains("{\"modules\":[]}HTTP/1.1 404"));
            assertTrue(replies.endsWith("{\"error\":\"not_found\"}"));
        }
    }

    private static void assertPlayerUpdate(Client client, String id, String name) throws InterruptedException {
        var update = client.next();
        assertEquals("update", type(update));
        assertEquals(id, update.get("id").getAsString());
        assertEquals(name, response(update, 200).getAsJsonObject("body").getAsJsonArray("players").get(0)
                .getAsJsonObject().get("name").getAsString());
    }

    private static WebCraftHttpServer start(ApiTransport transport, List<String> overrides,
                                           Map<ResourceLocation, Map<String, WebCraftReadRoute>> modules,
                                           List<WebCraftHttpServer.Feed> feeds) throws Exception {
        return new WebCraftHttpServer(new InetSocketAddress("127.0.0.1", 0), null, modules,
                new WebCraftHttpServer.ViewerAccess(VIEWER, VIEWER_TOKEN), feeds,
                new WebCraftHttpServer.BearerToken(FEED_TOKEN, "TEST"), true,
                ApiTransport.Policy.from(transport, overrides), List.of("https://frontend.example"));
    }

    private static HttpResponse<String> http(HttpClient client, WebCraftHttpServer server, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://" + authority(server) + path)).timeout(Duration.ofSeconds(3)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String authority(WebCraftHttpServer server) {
        return "127.0.0.1:" + server.address().getPort();
    }

    private static void assertDeltaUpgrade(WebCraftHttpServer server, String path, String extraHeaders) throws Exception {
        try (var socket = new Socket("127.0.0.1", server.address().getPort())) {
            socket.setSoTimeout(3_000);
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + authority(server) + "\r\n"
                    + "Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + extraHeaders + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertEquals("HTTP/1.1 101 Switching Protocols", reader.readLine());
            String capability = null;
            for (String line; (line = reader.readLine()) != null && !line.isEmpty(); ) {
                if (line.toLowerCase(Locale.ROOT).startsWith("x-webcraft-subscription-updates:")) {
                    capability = line.substring(line.indexOf(':') + 1).strip();
                }
            }
            assertEquals("delta", capability);
        }
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static Client connect(HttpClient client, WebCraftHttpServer server, boolean restricted, String origin, String token) {
        var listener = new Client();
        var builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3));
        if (origin != null) builder.header("Origin", origin);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        String path = restricted ? WebCraftHttpServer.RESTRICTED_SOCKET_PATH : WebCraftHttpServer.SOCKET_PATH;
        listener.socket = builder.buildAsync(URI.create("ws://" + authority(server) + path), listener).join();
        return listener;
    }

    private static void rejectUpgrade(HttpClient client, WebCraftHttpServer server, boolean restricted, String origin, int status) {
        var exception = assertThrows(CompletionException.class, () -> connect(client, server, restricted, origin, null));
        assertEquals(status, assertInstanceOf(WebSocketHandshakeException.class, exception.getCause()).getResponse().statusCode());
    }

    private static JsonObject response(JsonObject message, int code) {
        var response = message.getAsJsonObject("response");
        assertEquals(code, response.get("status").getAsInt(), message.toString());
        return response;
    }

    private static String error(JsonObject message, int code) {
        return response(message, code).getAsJsonObject("body").get("error").getAsString();
    }

    private static String type(JsonObject message) {
        return message.get("type").getAsString();
    }

    private static final class Client implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder fragments = new StringBuilder();
        WebSocket socket;
        int ids;

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                messages.add(JsonParser.parseString(fragments.toString()).getAsJsonObject());
                fragments.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(status);
            return null;
        }

        JsonObject get(String path, String token) throws InterruptedException {
            return command("get" + ids++, "get", path, token);
        }

        JsonObject command(String id, String op, String path, String token) throws InterruptedException {
            return command(id, op, path, token, null);
        }

        JsonObject command(String id, String op, String path, String token, String updates) throws InterruptedException {
            var message = new JsonObject();
            message.addProperty("id", id);
            message.addProperty("op", op);
            if (path != null) message.addProperty("path", path);
            if (token != null) message.addProperty("token", token);
            if (updates != null) message.addProperty("updates", updates);
            socket.sendText(message.toString(), true).join();
            var reply = next();
            assertEquals(id, reply.get("id").getAsString(), reply.toString());
            return reply;
        }

        JsonObject next() throws InterruptedException {
            var message = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(message, "Expected a WebSocket message");
            return message;
        }

        @Override
        public void close() {
            socket.abort();
        }
    }

    private static final class LiveResponse implements WebCraftLiveRoute.Public {
        private final WebCraftChanges changes = new WebCraftChanges();
        private volatile Response response;

        LiveResponse(Response response) {
            this.response = response;
        }

        void publish(Response next) {
            response = next;
            changes.changed();
        }

        @Override
        public WebCraftChanges changes() {
            return changes;
        }

        @Override
        public Response readPublic(String rawQuery) {
            return response;
        }
    }

    private static final class LiveAddon implements WebCraftLiveRoute.Public {
        final WebCraftChanges changes = new WebCraftChanges();
        volatile int value;

        @Override
        public WebCraftChanges changes() {
            return changes;
        }

        @Override
        public Response readPublic(String rawQuery) {
            return Response.ok(new com.google.gson.JsonPrimitive(value));
        }
    }
}
