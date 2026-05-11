package com.universium.webcraft.runtime;

import com.universium.webcraft.config.webcraftRouteConfig;

import java.util.List;
import java.util.Map;

public final class webcraftRouteBinder {
    private final webcraftHandlerResolver resolver;

    public webcraftRouteBinder(webcraftHandlerResolver resolver) {
        this.resolver = resolver;
    }

    public webcraftRouteDefinition bind(webcraftRouteConfig routeConfig) {
        List<webcraftRouteMiddleware> middleware = routeConfig.middleware().stream()
                .map(resolver::resolve)
                .map(handler -> (webcraftRouteMiddleware) (request, next) -> {
                    webcraftResponse response = handler.handle(request);
                    if (response.status() >= 400) {
                        return response;
                    }
                    return next.proceed(request);
                })
                .toList();

        webcraftRouteHandler handler = resolver.resolve(new webcraftRouteConfig.HandlerConfig(routeConfig.type(), routeConfig.ref()));
        webcraftRouteHandler fallback = resolver.resolve(routeConfig.fallback());
        Map<Integer, webcraftRouteHandler> errorHandlers = routeConfig.errorHandlers().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> resolver.resolve(entry.getValue())));
        return new webcraftRouteDefinition(routeConfig.method(), routeConfig.path(), routeConfig.match(), middleware, handler, fallback, errorHandlers);
    }
}

