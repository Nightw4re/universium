package com.universium.webcraft.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record webcraftRouteConfig(
        String method,
        String path,
        String match,
        String type,
        String ref,
        List<HandlerConfig> middleware,
        HandlerConfig fallback,
        Map<Integer, HandlerConfig> errorHandlers
) {
    public webcraftRouteConfig {
        method = Objects.requireNonNull(method, "method");
        path = Objects.requireNonNull(path, "path");
        match = match == null ? "exact" : match;
        type = Objects.requireNonNull(type, "type");
        ref = Objects.requireNonNull(ref, "ref");
        middleware = List.copyOf(Objects.requireNonNull(middleware, "middleware"));
        fallback = Objects.requireNonNull(fallback, "fallback");
        errorHandlers = Map.copyOf(Objects.requireNonNull(errorHandlers, "errorHandlers"));
    }

    public record HandlerConfig(String type, String ref) {
        public HandlerConfig {
            type = Objects.requireNonNull(type, "type");
            ref = Objects.requireNonNull(ref, "ref");
        }
    }
}


