package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.universium.webcraft.api.WebCraftReadRoute;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    @Test
    void servesExplicitPublicImagesWithoutOpeningPrivateRoutes() throws Exception {
        byte[] image = {1, 2, 3};
        WebCraftReadRoute.PublicRoute icon = query -> {
            assertEquals("id=minecraft%3Astone", query);
            return WebCraftReadRoute.Response.png(image);
        };
        try (var server = listener(Map.of(MODULE, Map.of("icon", icon, "loadout", (game, viewer) -> {
            throw new AssertionError("Private route called without a viewer");
        })), null);
             var client = HttpClient.newHttpClient()) {
            var path = LOADOUT.replace("loadout", "icon") + "?id=minecraft%3Astone";
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.address().getPort() + path)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
            assertArrayEquals(image, response.body());
            assertEquals(403, send(client, server, LOADOUT, "GET").statusCode());
            assertEquals(405, send(client, server, path, "POST").statusCode());
        }
    }

    @Test
    void servesOnlyPublishedServerInfoAndUpdatesPlayerCountWithoutAViewer() throws Exception {
        var info = new ServerInfoRoute(" Copycraft ", "", true,
                Optional.of(new ModpackMetadata.Identity("All the Mods 10", "8.1")), false, true);
        try (var server = listener(Map.of(), null, info, false);
             var client = HttpClient.newHttpClient()) {
            var response = send(client, server, ServerInfoRoute.PATH, "GET");
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(JsonParser.parseString("""
                    {"serverName":"Copycraft","modpack":{"version":"8.1"},"playersOnline":0}
                    """), parse(response));
            info.updatePlayerCount(3);
            assertEquals(3, parse(send(client, server, ServerInfoRoute.PATH, "GET")).get("playersOnline").getAsInt());
            assertEquals(400, send(client, server, ServerInfoRoute.PATH + "?x=1", "GET").statusCode());
            assertEquals(405, send(client, server, ServerInfoRoute.PATH, "POST").statusCode());
            assertEquals(404, send(client, server, ServerInfoRoute.PATH + "/", "GET").statusCode());
        }
    }

    @Test
    void omitsUnavailableMetadataAndShowsOnlyEnabledRoutesInSwagger() throws Exception {
        var info = new ServerInfoRoute("", "play.example.com:25565", false, Optional.empty(), true, true);
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
            var html = send(client, server, ApiDocumentation.UI_PATH, "GET");
            assertEquals(200, html.statusCode());
            assertTrue(html.body().contains("swagger-ui-bundle.js"));
            assertEquals(200, send(client, server, ApiDocumentation.UI_PATH + "/swagger-ui-bundle.js", "GET").statusCode());
            assertEquals(200, send(client, server, ApiDocumentation.UI_PATH + "/swagger-ui.css", "GET").statusCode());
            assertEquals(400, send(client, server, ApiDocumentation.UI_PATH + "?url=example", "GET").statusCode());
            assertEquals(405, send(client, server, ApiDocumentation.SPEC_PATH, "POST").statusCode());
        }
    }

    private static WebCraftHttpServer listener(Map<ResourceLocation, Map<String, WebCraftReadRoute>> modules,
                                               WebCraftHttpServer.ViewerAccess viewer) throws IOException {
        return listener(modules, viewer, null, false);
    }

    private static WebCraftHttpServer listener(Map<ResourceLocation, Map<String, WebCraftReadRoute>> modules,
                                               WebCraftHttpServer.ViewerAccess viewer, ServerInfoRoute info,
                                               boolean docs) throws IOException {
        return new WebCraftHttpServer(new InetSocketAddress("127.0.0.1", 0), null, modules, viewer, info, docs);
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
