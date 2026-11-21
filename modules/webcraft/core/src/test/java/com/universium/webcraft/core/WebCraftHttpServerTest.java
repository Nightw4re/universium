package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.universium.webcraft.api.WebCraftPreviewPack.Icon;
import com.universium.webcraft.api.WebCraftReadRoute;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WebCraftHttpServerTest {
    private static final ResourceLocation MODULE = ResourceLocation.parse("webcraft_equipment:equipment");
    private static final String DISCOVERY = "/api/v1/modules";
    private static final String LOADOUT = DISCOVERY + "/webcraft_equipment/equipment/loadout";
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TOKEN = "T".repeat(43);
    private static final String PNG_ICON = "data:image/png;base64,iVBORw0K";
    private static final String WEBP_ICON = "data:image/webp;base64,UklGRhwAAABXRUJQVlA4TBAAAAAvAAAAAAfQ0v5H/wMR0f8A";

    @Test
    void discoversOnlyEnabledModulesAndNeverCallsPrivateRouteWithoutAuthorization() throws Exception {
        var calls = new AtomicInteger();
        WebCraftReadRoute route = (server, viewer) -> {
            assertEquals(VIEWER, viewer);
            calls.incrementAndGet();
            JsonObject result = new JsonObject();
            result.addProperty("player", "TestViewer");
            return WebCraftReadRoute.Response.ok(result);
        };
        try (var listener = listener(Map.of(MODULE, Map.of("loadout", route)), new WebCraftHttpServer.ViewerAccess(VIEWER, TOKEN));
             var client = HttpClient.newHttpClient()) {
            var discovery = send(client, listener, DISCOVERY, "GET");
            assertEquals(200, discovery.statusCode());
            var module = parse(discovery).getAsJsonArray("modules").get(0).getAsJsonObject();
            assertEquals(MODULE.toString(), module.get("id").getAsString());
            assertEquals(LOADOUT, module.getAsJsonObject("routes").get("loadout").getAsString());

            assertEquals(404, send(client, listener, LOADOUT + "/extra", "GET", TOKEN).statusCode());
            assertEquals(404, send(client, listener, LOADOUT.replace("loadout", "%6coadout"), "GET", TOKEN).statusCode());
            assertEquals(400, send(client, listener, LOADOUT + "?player=OtherPlayer", "GET", TOKEN).statusCode());
            assertEquals(405, send(client, listener, LOADOUT, "POST", TOKEN).statusCode());
            assertEquals(401, send(client, listener, LOADOUT, "GET", "W".repeat(43)).statusCode());
            assertEquals(401, send(client, listener, LOADOUT, "GET", TOKEN, TOKEN).statusCode());
            var anonymous = send(client, listener, LOADOUT, "GET");
            assertEquals(401, anonymous.statusCode());
            assertEquals("Bearer", anonymous.headers().firstValue("WWW-Authenticate").orElseThrow());
            assertEquals(0, calls.get());

            var authorized = send(client, listener, LOADOUT, "GET", TOKEN);
            assertEquals(200, authorized.statusCode());
            assertEquals("no-store", authorized.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("TestViewer", parse(authorized).get("player").getAsString());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void keepsRoutesLockedWithoutAnExplicitViewerAndServesNoRoutesForDisabledModules() throws Exception {
        try (var locked = listener(Map.of(MODULE, Map.of("loadout", (server, viewer) -> {
            throw new AssertionError("Locked routes must never run");
        })), null);
             var disabled = listener(Map.of(), new WebCraftHttpServer.ViewerAccess(VIEWER, TOKEN));
             var client = HttpClient.newHttpClient()) {
            var rejected = send(client, locked, LOADOUT, "GET", TOKEN);
            assertEquals(403, rejected.statusCode());
            assertEquals("viewer_access_disabled", parse(rejected).get("error").getAsString());
            assertEquals(0, parse(send(client, disabled, DISCOVERY, "GET")).getAsJsonArray("modules").size());
            assertEquals(404, send(client, disabled, LOADOUT, "GET", TOKEN).statusCode());
            assertEquals(404, send(client, disabled, ServerInfoRoute.PATH, "GET").statusCode());
            assertEquals(404, send(client, disabled, ApiDocumentation.SPEC_PATH, "GET").statusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "image/webp"})
    void servesExplicitPublicImagesWithoutOpeningPrivateRoutes(String contentType) throws Exception {
        byte[] image = {1, 2, 3};
        WebCraftReadRoute.PublicRoute icon = query -> {
            assertEquals("id=minecraft%3Astone", query);
            return WebCraftReadRoute.Response.icon(new Icon(image, contentType));
        };
        try (var server = listener(Map.of(MODULE, Map.of("icon", icon, "loadout", (game, viewer) -> {
            throw new AssertionError("Private route called without a viewer");
        })), null);
             var client = HttpClient.newHttpClient()) {
            var path = LOADOUT.replace("loadout", "icon") + "?id=minecraft%3Astone";
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.address().getPort() + path)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals(contentType, response.headers().firstValue("Content-Type").orElseThrow());
            assertArrayEquals(image, response.body());
            assertEquals(403, send(client, server, LOADOUT, "GET").statusCode());
            assertEquals(405, send(client, server, path, "POST").statusCode());
        }
    }

    @Test
    void servesOnlyPublishedServerInfoAndUpdatesPlayerCountWithoutAViewer() throws Exception {
        var info = new ServerInfoRoute(" Copycraft ", "");
        info.initializeModpack(Optional.of(new ModpackMetadata.Identity("All the Mods 10", "8.1")), false, true);
        info.publish(JsonParser.parseString("""
                {"playersOnline":0,"maxPlayers":20,"motd":"Welcome","uptimeSeconds":7,
                 "worldAgeDays":2,"worldAgeMinutes":2885,"worldTimeTicks":18000}
                """).getAsJsonObject(), null, null);
        try (var server = listener(Map.of(), null, info, false);
             var client = HttpClient.newHttpClient()) {
            var response = send(client, server, ServerInfoRoute.PATH, "GET");
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(JsonParser.parseString("""
                    {"serverName":"Copycraft","modpack":{"version":"8.1"},"playersOnline":0,"maxPlayers":20,"motd":"Welcome",
                     "uptimeSeconds":7,"worldAgeDays":2,"worldAgeMinutes":2885,"worldTimeTicks":18000}
                    """), parse(response));
            var fields = JsonParser.parseString("""
                    {"playersOnline":3,"maxPlayers":20,"totalUptimeSeconds":67}
                    """).getAsJsonObject();
            info.publish(fields, PNG_ICON, null);
            assertEquals(JsonParser.parseString("""
                    {"serverName":"Copycraft","modpack":{"version":"8.1"},"playersOnline":3,"maxPlayers":20,
                     "serverIcon":"data:image/png;base64,iVBORw0K","totalUptimeSeconds":67}
                    """), parse(send(client, server, ServerInfoRoute.PATH, "GET")));
            var webpRequest = ServerInfoRoute.PATH + "?iconFormat=webp";
            var fallback = send(client, server, webpRequest, "GET");
            assertEquals(200, fallback.statusCode());
            assertEquals(PNG_ICON, parse(fallback).get("serverIcon").getAsString());
            info.publish(fields, PNG_ICON, WEBP_ICON);
            var preferred = send(client, server, webpRequest, "GET");
            assertEquals(200, preferred.statusCode());
            assertEquals(WEBP_ICON, parse(preferred).get("serverIcon").getAsString());
            assertEquals(PNG_ICON, parse(send(client, server, ServerInfoRoute.PATH, "GET")).get("serverIcon").getAsString());
            info.publish(fields, null, null);
            assertFalse(parse(send(client, server, webpRequest, "GET")).has("serverIcon"));
            assertEquals(400, send(client, server, ServerInfoRoute.PATH + "?x=1", "GET").statusCode());
            assertEquals(405, send(client, server, ServerInfoRoute.PATH, "POST").statusCode());
            assertEquals(404, send(client, server, ServerInfoRoute.PATH + "/", "GET").statusCode());
        }
    }

    @Test
    void omitsUnavailableMetadataAndShowsOnlyEnabledRoutesInSwagger() throws Exception {
        var info = new ServerInfoRoute("", "play.example.com:25565");
        info.initializeModpack(Optional.empty(), true, true);
        try (var server = listener(Map.of(MODULE, Map.of("loadout", (game, viewer) -> null)), null, info, true);
             var noInfo = listener(Map.of(), null, null, true);
             var client = HttpClient.newHttpClient()) {
            assertEquals(JsonParser.parseString("""
                    {"joinAddress":"play.example.com:25565"}
                    """), parse(send(client, server, ServerInfoRoute.PATH, "GET")));

            var paths = parse(send(client, server, ApiDocumentation.SPEC_PATH, "GET")).getAsJsonObject("paths");
            assertTrue(paths.has(ServerInfoRoute.PATH));
            assertTrue(paths.getAsJsonObject(LOADOUT).getAsJsonObject("get").has("security"));
            assertFalse(parse(send(client, noInfo, ApiDocumentation.SPEC_PATH, "GET"))
                    .getAsJsonObject("paths").has(ServerInfoRoute.PATH));
            assertEquals(404, send(client, noInfo, ServerInfoRoute.PATH, "GET").statusCode());
            var html = send(client, server, ApiDocumentation.UI_PATH, "GET");
            assertEquals(200, html.statusCode());
            assertTrue(html.body().contains("swagger-ui-bundle.js"));
            assertEquals(200, send(client, server, ApiDocumentation.UI_PATH + "/swagger-ui-bundle.js", "GET").statusCode());
            assertEquals(200, send(client, server, ApiDocumentation.UI_PATH + "/swagger-ui.css", "GET").statusCode());
            assertEquals(400, send(client, server, ApiDocumentation.UI_PATH + "?url=example", "GET").statusCode());
            assertEquals(405, send(client, server, ApiDocumentation.SPEC_PATH, "POST").statusCode());
        }
    }

    @Test
    void pollsPublicStatusWhileGameplayRoutesAreUnavailableDuringStartupAndShutdown() throws Exception {
        var status = new ServerStatusRoute();
        var feed = new WebCraftHttpServer.Feed(ServerStatusRoute.PATH, FeedExposure.PUBLIC, status, false, ApiTransport.INHERIT);
        try (var server = listener(Map.of(), null, List.of(feed), null, true);
             var client = HttpClient.newHttpClient()) {
            var starting = send(client, server, feed.path(), "GET");
            assertEquals(200, starting.statusCode());
            assertEquals("no-store", starting.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("starting", parse(starting).get("status").getAsString());
            assertEquals(503, send(client, server, DISCOVERY, "GET").statusCode());
            assertEquals(400, send(client, server, feed.path() + "?wait=1", "GET").statusCode());
            assertEquals(405, send(client, server, feed.path(), "POST").statusCode());
            assertEquals(404, send(client, server, feed.path() + "/", "GET").statusCode());

            status.markReady();
            assertEquals("ready", parse(send(client, server, feed.path(), "GET")).get("status").getAsString());
            assertEquals(200, send(client, server, DISCOVERY, "GET").statusCode());
            assertTrue(parse(send(client, server, ApiDocumentation.SPEC_PATH, "GET"))
                    .getAsJsonObject("paths").has(feed.path()));

            status.markStopping();
            status.markReady();
            assertEquals("stopping", parse(send(client, server, feed.path(), "GET")).get("status").getAsString());
            assertEquals(503, send(client, server, DISCOVERY, "GET").statusCode());
        }
    }

    @Test
    void internalFeedsUseTheirOwnTokenAndHaveNoPublicAliasOrSwaggerPath() throws Exception {
        var machineToken = "M".repeat(43);
        var status = new ServerStatusRoute();
        var info = new ServerInfoRoute("Copycraft", "");
        info.publish(new JsonObject(), PNG_ICON, WEBP_ICON);
        var feeds = List.of(
                new WebCraftHttpServer.Feed(ServerStatusRoute.PATH, FeedExposure.INTERNAL, status, false, ApiTransport.INHERIT),
                new WebCraftHttpServer.Feed(ServerInfoRoute.PATH, FeedExposure.INTERNAL, info, false, ApiTransport.INHERIT));
        var viewer = new WebCraftHttpServer.ViewerAccess(VIEWER, TOKEN);
        try (var server = listener(Map.of(), viewer, feeds, new WebCraftHttpServer.BearerToken(machineToken, "TEST"), true);
             var locked = listener(Map.of(), viewer, feeds, null, false);
             var client = HttpClient.newHttpClient()) {
            var paths = parse(send(client, server, ApiDocumentation.SPEC_PATH, "GET")).getAsJsonObject("paths");
            for (var feed : feeds) {
                assertEquals(404, send(client, server, feed.publicPath(), "GET").statusCode());
                assertEquals(401, send(client, server, feed.path(), "GET").statusCode());
                assertEquals(401, send(client, server, feed.path(), "GET", TOKEN).statusCode());
                assertEquals(403, send(client, locked, feed.path(), "GET", machineToken).statusCode());
                assertFalse(paths.has(feed.publicPath()));
                assertFalse(paths.has(feed.path()));
            }
            assertEquals("starting", parse(send(client, server, feeds.getFirst().path(), "GET", machineToken))
                    .get("status").getAsString());
            assertEquals(503, send(client, server, feeds.getLast().path(), "GET", machineToken).statusCode());
            status.markReady();
            assertEquals("Copycraft", parse(send(client, server, feeds.getLast().path(), "GET", machineToken))
                    .get("serverName").getAsString());
            var webpRequest = feeds.getLast().path() + "?iconFormat=webp";
            assertEquals(401, send(client, server, webpRequest, "GET").statusCode());
            var preferred = send(client, server, webpRequest, "GET", machineToken);
            assertEquals(200, preferred.statusCode());
            assertEquals(WEBP_ICON, parse(preferred).get("serverIcon").getAsString());
        }
    }

    @Test
    void servesOnlineAndRosterSnapshotsOnlyToTheInternalToken() throws Exception {
        var online = new PlayerListRoute();
        var roster = new PlayerListRoute();
        var feeds = List.of(
                new WebCraftHttpServer.Feed(PlayerListRoute.ONLINE_PATH, FeedExposure.INTERNAL, online, true, ApiTransport.INHERIT),
                new WebCraftHttpServer.Feed(PlayerListRoute.ROSTER_PATH, FeedExposure.INTERNAL, roster, true, ApiTransport.INHERIT));
        try (var disabled = listener(Map.of(), null, List.of(), null, true);
             var locked = listener(Map.of(), null, feeds, null, true);
             var server = listener(Map.of(), null, feeds, new WebCraftHttpServer.BearerToken(TOKEN, "TEST"), true);
             var client = HttpClient.newHttpClient()) {
            for (var feed : feeds) {
                assertTrue(feed.permitsAddress(InetAddress.getByName("127.0.0.1")));
                assertTrue(feed.permitsAddress(InetAddress.getByName("::1")));
                assertFalse(feed.permitsAddress(InetAddress.getByName("192.0.2.10")));
                assertEquals(404, send(client, disabled, feed.path(), "GET", TOKEN).statusCode());
                assertEquals(404, send(client, server, feed.publicPath(), "GET", TOKEN).statusCode());
                assertEquals(403, send(client, locked, feed.path(), "GET", TOKEN).statusCode());
                assertEquals(401, send(client, server, feed.path(), "GET").statusCode());
                assertEquals(401, send(client, server, feed.path(), "GET", "W".repeat(43)).statusCode());
                assertEquals(400, send(client, server, feed.path() + "?q=player", "GET", TOKEN).statusCode());
                assertEquals(JsonParser.parseString("{\"players\":[]}"), parse(send(client, server, feed.path(), "GET", TOKEN)));
            }
            online.publish(JsonParser.parseString("[{\"name\":\"CurrentPlayer\",\"pingMillis\":36}]").getAsJsonArray());
            roster.publish(JsonParser.parseString("[{\"uuid\":\"" + VIEWER + "\",\"name\":null}]").getAsJsonArray());
            assertEquals(JsonParser.parseString("{\"players\":[{\"name\":\"CurrentPlayer\",\"pingMillis\":36}]}"),
                    parse(send(client, server, feeds.getFirst().path(), "GET", TOKEN)));
            assertEquals(JsonParser.parseString("{\"players\":[{\"uuid\":\"" + VIEWER + "\",\"name\":null}]}"),
                    parse(send(client, server, feeds.getLast().path(), "GET", TOKEN)));
        }
    }

    @Test
    void servesHistoricalPlayersOnlyThroughTheAuthenticatedInternalFeed() throws Exception {
        var history = new PlayerHistoryRoute();
        var feed = new WebCraftHttpServer.Feed(PlayerHistoryRoute.PATH, FeedExposure.INTERNAL, history, false, ApiTransport.INHERIT);
        try (var server = listener(Map.of(), null, List.of(feed), new WebCraftHttpServer.BearerToken(TOKEN, "TEST"), false);
             var client = HttpClient.newHttpClient()) {
            assertEquals(404, send(client, server, "/api/v1/players", "GET", TOKEN).statusCode());
            assertEquals(401, send(client, server, "/api/v1/internal/players", "GET").statusCode());
            assertEquals(400, send(client, server, feed.path() + "?uuid=x", "GET", TOKEN).statusCode());
            assertEquals(JsonParser.parseString("{\"players\":[]}"), parse(send(client, server, feed.path(), "GET", TOKEN)));

            history.publish(List.of(new PlayerHistoryData.Player(VIEWER, "KnownPlayer", 125)));
            var response = send(client, server, feed.path(), "GET", TOKEN);
            assertEquals(200, response.statusCode());
            assertEquals(JsonParser.parseString("""
                    {"players":[{"uuid":"00000000-0000-0000-0000-000000000001","name":"KnownPlayer","lastSeenAt":125}]}
                    """), parse(response));
        }
    }

    private static WebCraftHttpServer listener(Map<ResourceLocation, Map<String, WebCraftReadRoute>> modules,
                                               WebCraftHttpServer.ViewerAccess viewer) throws IOException {
        return listener(modules, viewer, null, false);
    }

    private static WebCraftHttpServer listener(Map<ResourceLocation, Map<String, WebCraftReadRoute>> modules,
                                               WebCraftHttpServer.ViewerAccess viewer, ServerInfoRoute info,
                                               boolean docs) throws IOException {
        var feeds = info == null ? List.<WebCraftHttpServer.Feed>of()
                : List.of(new WebCraftHttpServer.Feed(ServerInfoRoute.PATH, FeedExposure.PUBLIC, info, false, ApiTransport.INHERIT));
        return listener(modules, viewer, feeds, null, docs);
    }

    private static WebCraftHttpServer listener(Map<ResourceLocation, Map<String, WebCraftReadRoute>> modules,
                                               WebCraftHttpServer.ViewerAccess viewer, List<WebCraftHttpServer.Feed> feeds,
                                               WebCraftHttpServer.BearerToken feedToken, boolean docs) throws IOException {
        return new WebCraftHttpServer(new InetSocketAddress("127.0.0.1", 0), null, modules,
                viewer, feeds, feedToken, docs, new ApiTransport.Policy(ApiTransport.HTTP, Map.of()), List.of());
    }

    private static HttpResponse<String> send(HttpClient client, WebCraftHttpServer listener, String path,
                                             String method, String... tokens) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listener.address().getPort() + path))
                .timeout(Duration.ofSeconds(4)).method(method, HttpRequest.BodyPublishers.noBody());
        for (String token : tokens) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject parse(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
