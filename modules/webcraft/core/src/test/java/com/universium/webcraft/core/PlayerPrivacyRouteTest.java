package com.universium.webcraft.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PlayerPrivacyRouteTest {
    private static final String TOKEN = "i".repeat(43);
    private static final String PATH = "/api/v1/internal/players/privacy?player=12345678-1234-1234-1234-123456789abc";

    @Test
    void storesValidatedPreferencesThroughTheAuthenticatedLoopbackHttpRoute(@TempDir Path world) throws Exception {
        var local = InetAddress.getByName("127.0.0.1");
        var external = InetAddress.getByName("192.0.2.2");
        var routes = routes(world);
        var valid = "{\"hidden\":[\"inventory\",\"playTime\"]}";
        assertEquals(403, request(routes, external, TOKEN, "PUT", valid).status());
        assertEquals(401, request(routes, local, "j".repeat(43), "PUT", valid).status());
        assertEquals(400, request(routes, local, TOKEN, "PUT", "{\"hidden\":[\"unknown\"]}").status());
        assertEquals(200, request(routes, local, TOKEN, "PUT", valid).status());
        assertEquals(JsonParser.parseString(valid), request(routes(world), local, TOKEN, "GET", null).body());
        assertEquals(200, request(routes, local, TOKEN, "PUT", "{\"hidden\":[]}").status());
        assertEquals(JsonParser.parseString("{\"hidden\":[]}"), request(routes(world), local, TOKEN, "GET", null).body());
    }

    private static WebCraftRoutes routes(Path world) {
        return new WebCraftRoutes(null, Map.of(), null,
                List.of(new WebCraftHttpServer.Feed(PlayerPrivacyRoute.PATH, FeedExposure.INTERNAL,
                        new PlayerPrivacyRoute(world), true, ApiTransport.HTTP)),
                new WebCraftHttpServer.BearerToken(TOKEN, "test token"),
                new ApiTransport.Policy(ApiTransport.BOTH, Map.of()));
    }

    private static com.universium.webcraft.api.WebCraftReadRoute.Response request(WebCraftRoutes routes, InetAddress peer,
            String token, String method, String body) {
        return routes.dispatch(new WebCraftRoutes.Request(WebCraftRoutes.target(PATH), method, peer,
                List.of("Bearer " + token), WebCraftRoutes.Scope.HTTP, body), false).response();
    }
}
