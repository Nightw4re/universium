package com.universium.webcraft.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record webcraftRouteDefinition(
        String method,
        String path,
        String match,
        List<webcraftRouteMiddleware> middleware,
        webcraftRouteHandler handler,
        webcraftRouteHandler fallback,
        Map<Integer, webcraftRouteHandler> errorHandlers
) {
    public webcraftRouteDefinition {
        method = Objects.requireNonNull(method, "method");
        path = Objects.requireNonNull(path, "path");
        match = match == null ? "exact" : match;
        middleware = List.copyOf(Objects.requireNonNull(middleware, "middleware"));
        handler = Objects.requireNonNull(handler, "handler");
        fallback = Objects.requireNonNull(fallback, "fallback");
        errorHandlers = Map.copyOf(Objects.requireNonNull(errorHandlers, "errorHandlers"));
    }

    public boolean matches(String requestMethod, String requestPath) {
        if (!method.equalsIgnoreCase(requestMethod)) {
            return false;
        }
        if ("prefix".equalsIgnoreCase(match)) {
            return requestPath.startsWith(path);
        }
        return path.equals(requestPath);
    }
}

