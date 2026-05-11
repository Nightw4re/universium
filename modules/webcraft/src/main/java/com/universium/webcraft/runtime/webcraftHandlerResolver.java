package com.universium.webcraft.runtime;

import com.universium.webcraft.config.webcraftRouteConfig;

public interface webcraftHandlerResolver {
    webcraftRouteHandler resolve(webcraftRouteConfig.HandlerConfig handler);
}

