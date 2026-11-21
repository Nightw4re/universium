package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.universium.webcraft.api.WebCraftReadRoute;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class InternalModuleRouteTest {
    private static final ResourceLocation INTERNAL_MODULE = ResourceLocation.parse("test:internal");
    private static final ResourceLocation MIXED_MODULE = ResourceLocation.parse("test:mixed");
    private static final String INTERNAL_ROUTE = "/api/v1/internal/modules/test/internal/data";
    private static final String TOKEN = "M".repeat(43);
    private static final ApiTransport.Policy POLICY = new ApiTransport.Policy(ApiTransport.HTTP,
            Map.of(INTERNAL_ROUTE, ApiTransport.BOTH));

    @Test
    void requiresTheMachineTokenAndKeepsInternalRoutesOutOfDiscoveryAndSwagger() throws Exception {
        var calls = new AtomicInteger();
        var modules = modules(calls);
        try (var listener = listener(modules, token());
             var locked = listener(modules, null);
             var client = HttpClient.newHttpClient()) {
            assertEquals(404, get(client, listener, INTERNAL_ROUTE.replace("/internal/", "/"), TOKEN).statusCode());
            assertEquals(401, get(client, listener, INTERNAL_ROUTE, null).statusCode());
            assertEquals(401, get(client, listener, INTERNAL_ROUTE, "V".repeat(43)).statusCode());
            assertEquals(401, get(client, listener, INTERNAL_ROUTE, "W".repeat(43)).statusCode());
            assertEquals(403, get(client, locked, INTERNAL_ROUTE, TOKEN).statusCode());
            assertEquals(0, calls.get());
            var discovery = parse(get(client, listener, WebCraftRoutes.MODULES_PATH, null));
            assertEquals(JsonParser.parseString("""
                    {"modules":[{"id":"test:mixed","routes":{"public":"/api/v1/modules/test/mixed/public"}}]}
                    """), discovery);
            var paths = parse(get(client, listener, ApiDocumentation.SPEC_PATH, null)).getAsJsonObject("paths");
            assertFalse(paths.has(INTERNAL_ROUTE));
            assertFalse(paths.has("/api/v1/internal/modules/test/mixed/data"));

            var allowed = get(client, listener, INTERNAL_ROUTE + "?value=a%2Bb", TOKEN);
            assertEquals(200, allowed.statusCode());
            assertEquals("value=a%2Bb", parse(allowed).get("query").getAsString());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void sharesLoopbackAndLifecycleChecksAndAllowsOnlyRestrictedOneShotSockets() throws Exception {
        var calls = new AtomicInteger();
        var status = new ServerStatusRoute();
        var feed = new WebCraftHttpServer.Feed(ServerStatusRoute.PATH, FeedExposure.PUBLIC, status, false, ApiTransport.INHERIT);
        var routes = new WebCraftRoutes(null, modules(calls), null, List.of(feed), token(), POLICY);
        var target = URI.create(INTERNAL_ROUTE + "?value=one");
        var auth = List.of("Bearer " + TOKEN);
        var loopback = InetAddress.getByName("127.0.0.1");
        var remote = InetAddress.getByName("192.0.2.10");
        var restricted = new WebCraftRoutes.Request(target, "GET", loopback, auth, WebCraftRoutes.Scope.RESTRICTED_SOCKET);

        assertEquals(404, routes.dispatch(new WebCraftRoutes.Request(target, "GET", loopback, auth,
                WebCraftRoutes.Scope.PUBLIC_SOCKET), false).response().status());
        assertEquals(403, routes.dispatch(new WebCraftRoutes.Request(target, "GET", remote, auth,
                WebCraftRoutes.Scope.HTTP), false).response().status());
        assertEquals(503, routes.dispatch(restricted, false).response().status());
        assertEquals(0, calls.get());
        status.markReady();
        assertEquals(405, routes.dispatch(restricted, true).response().status());
        assertEquals(200, routes.dispatch(restricted, false).response().status());
        assertEquals(1, calls.get());
    }

    private static Map<ResourceLocation, Map<String, WebCraftReadRoute>> modules(AtomicInteger calls) {
        WebCraftReadRoute.InternalRoute internal = (server, rawQuery) -> {
            calls.incrementAndGet();
            var response = new JsonObject();
            response.addProperty("query", rawQuery);
            return WebCraftReadRoute.Response.ok(response);
        };
        WebCraftReadRoute.PublicRoute publicRoute = query -> WebCraftReadRoute.Response.ok(new JsonObject());
        return Map.of(INTERNAL_MODULE, Map.of("data", internal),
                MIXED_MODULE, Map.of("public", publicRoute, "data", internal));
    }

    private static WebCraftHttpServer listener(Map<ResourceLocation, Map<String, WebCraftReadRoute>> modules,
                                               WebCraftHttpServer.BearerToken token) throws Exception {
        return new WebCraftHttpServer(new InetSocketAddress("127.0.0.1", 0), null, modules,
                new WebCraftHttpServer.ViewerAccess(new UUID(0, 1), "V".repeat(43)),
                List.of(), token, true, POLICY, List.of());
    }

    private static WebCraftHttpServer.BearerToken token() {
        return new WebCraftHttpServer.BearerToken(TOKEN, "TEST");
    }

    private static HttpResponse<String> get(HttpClient client, WebCraftHttpServer listener,
                                            String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listener.address().getPort() + path))
                .timeout(Duration.ofSeconds(3));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject parse(HttpResponse<String> response) {
        assertEquals(200, response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
