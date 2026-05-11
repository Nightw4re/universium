package com.universium.webcraft.runtime;

public interface webcraftRouteMiddlewareChain {
    webcraftResponse proceed(webcraftRequest request) throws Exception;
}

