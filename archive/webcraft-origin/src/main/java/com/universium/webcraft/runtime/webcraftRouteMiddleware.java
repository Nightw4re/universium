package com.universium.webcraft.runtime;

@FunctionalInterface
public interface webcraftRouteMiddleware {
    webcraftResponse handle(webcraftRequest request, webcraftRouteMiddlewareChain next) throws Exception;
}

