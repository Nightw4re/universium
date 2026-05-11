package com.universium.webcraft.config;

import java.util.List;
import java.util.Objects;

public record webcraftRuntimeConfig(
        webcraftConfig config,
        List<webcraftRouteConfig> routes
) {
    public webcraftRuntimeConfig {
        config = Objects.requireNonNull(config, "config");
        routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
    }
}

