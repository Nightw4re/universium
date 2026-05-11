package com.universium.webcraft.runtime;

@FunctionalInterface
public interface webcraftRouteHandler {
    webcraftResponse handle(webcraftRequest request) throws Exception;
}

