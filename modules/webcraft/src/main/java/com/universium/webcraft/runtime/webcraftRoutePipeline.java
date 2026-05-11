package com.universium.webcraft.runtime;

import java.util.List;
import java.util.Map;

public final class webcraftRoutePipeline {
    public webcraftResponse execute(webcraftRouteDefinition route, webcraftRequest request) {
        return executeMiddleware(route.middleware(), 0, route, request);
    }

    private webcraftResponse executeMiddleware(List<webcraftRouteMiddleware> middleware, int index, webcraftRouteDefinition route, webcraftRequest request) {
        try {
            if (index >= middleware.size()) {
                return executeHandler(route, request);
            }
            webcraftRouteMiddleware current = middleware.get(index);
            return current.handle(request, nextRequest -> executeMiddleware(middleware, index + 1, route, nextRequest));
        } catch (webcraftRouteException exception) {
            return handleStatus(route, request, exception.status(), exception.getMessage());
        } catch (Exception exception) {
            return handleStatus(route, request, 500, exception.getMessage() == null ? "internal_error" : exception.getMessage());
        }
    }

    private webcraftResponse executeHandler(webcraftRouteDefinition route, webcraftRequest request) {
        try {
            return route.handler().handle(request);
        } catch (webcraftRouteException exception) {
            return handleStatus(route, request, exception.status(), exception.getMessage());
        } catch (Exception exception) {
            return handleFallback(route, request, exception);
        }
    }

    private webcraftResponse handleFallback(webcraftRouteDefinition route, webcraftRequest request, Exception cause) {
        try {
            return route.fallback().handle(request);
        } catch (Exception ignored) {
            return handleStatus(route, request, 500, cause.getMessage() == null ? "internal_error" : cause.getMessage());
        }
    }

    private webcraftResponse handleStatus(webcraftRouteDefinition route, webcraftRequest request, int status, String message) {
        webcraftRouteHandler handler = route.errorHandlers().get(status);
        if (handler != null) {
            try {
                return handler.handle(request);
            } catch (Exception ignored) {
                return errorResponse(status, message);
            }
        }
        return errorResponse(status, message);
    }

    private static webcraftResponse errorResponse(int status, String message) {
        return new webcraftResponse(status, "application/json", "{\"success\":false,\"error\":" + quote(message) + "}");
    }

    private static String quote(String value) {
        String safe = value == null ? "internal_error" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

