package com.universium.webcraft.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.universium.webcraft.TestFixtures.config;
import static com.universium.webcraft.TestFixtures.samplePlayer;
import static org.junit.jupiter.api.Assertions.assertTrue;

class webcraftAddonRegistryTest {
    @AfterEach
    void clearRegistry() {
        webcraftAddonRegistry.clear();
    }

    @Test
    void dispatchesAddonRouteBeforeCoreFallback() {
        webcraftAddonRegistry.registerRoute(new webcraftRouteDefinition(
                "GET",
                "/marketplace/hello",
                "exact",
                java.util.List.of(),
                request -> webcraftResponse.json("{\"success\":true,\"data\":{\"message\":\"hello from addon\"}}"),
                request -> webcraftResponse.json("{\"success\":true,\"data\":{\"message\":\"hello from addon\"}}"),
                java.util.Map.of()
        ));

        webcraftRouteDispatcher dispatcher = new webcraftRouteDispatcher(config(8087), playerName -> samplePlayer(playerName), 1_000L);
        webcraftResponse response = dispatcher.dispatch(new webcraftRequest("GET", "/api/marketplace/hello", java.util.Map.of(), java.util.Map.of()));

        assertTrue(response.body().contains("hello from addon"));
    }
}
