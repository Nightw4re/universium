package com.universium.webcraft.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class webcraftRoutePipelineTest {
    @Test
    void middlewareCanShortCircuit() {
        webcraftRouteDefinition route = new webcraftRouteDefinition(
                "GET",
                "/example",
                "exact",
                List.of((request, next) -> new webcraftResponse(401, "application/json", "{\"success\":false,\"error\":\"blocked\"}")),
                request -> new webcraftResponse(200, "application/json", "{}"),
                request -> new webcraftResponse(200, "application/json", "{\"fallback\":true}"),
                Map.of(401, request -> new webcraftResponse(401, "application/json", "{\"error\":\"unauthorized\"}"))
        );

        webcraftResponse response = new webcraftRoutePipeline().execute(route, new webcraftRequest("GET", "/example", Map.of(), Map.of()));

        assertEquals(401, response.status());
        assertEquals("{\"success\":false,\"error\":\"blocked\"}", response.body());
    }

    @Test
    void fallbackIsUsedWhenHandlerFails() {
        webcraftRouteDefinition route = new webcraftRouteDefinition(
                "GET",
                "/example",
                "exact",
                List.of(),
                request -> { throw new IllegalStateException("missing target"); },
                request -> new webcraftResponse(200, "application/json", "{\"fallback\":true}"),
                Map.of()
        );

        webcraftResponse response = new webcraftRoutePipeline().execute(route, new webcraftRequest("GET", "/example", Map.of(), Map.of()));

        assertEquals(200, response.status());
        assertEquals("{\"fallback\":true}", response.body());
    }

    @Test
    void statusHandlerHandlesUnauthorized() {
        webcraftRouteDefinition route = new webcraftRouteDefinition(
                "GET",
                "/example",
                "exact",
                List.of(),
                request -> { throw new webcraftRouteException(401, "unauthorized"); },
                request -> new webcraftResponse(200, "application/json", "{\"fallback\":true}"),
                Map.of(401, request -> new webcraftResponse(401, "application/json", "{\"error\":\"unauthorized\"}"))
        );

        webcraftResponse response = new webcraftRoutePipeline().execute(route, new webcraftRequest("GET", "/example", Map.of(), Map.of()));

        assertEquals(401, response.status());
        assertEquals("{\"error\":\"unauthorized\"}", response.body());
    }
}

