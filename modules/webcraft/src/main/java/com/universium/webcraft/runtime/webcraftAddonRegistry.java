package com.universium.webcraft.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class webcraftAddonRegistry {
    private static final CopyOnWriteArrayList<webcraftRouteDefinition> ADDON_ROUTES = new CopyOnWriteArrayList<>();

    private webcraftAddonRegistry() {
    }

    public static void registerRoute(webcraftRouteDefinition route) {
        ADDON_ROUTES.add(Objects.requireNonNull(route, "route"));
    }

    public static List<webcraftRouteDefinition> snapshotRoutes() {
        return List.copyOf(ADDON_ROUTES);
    }

    public static void clear() {
        ADDON_ROUTES.clear();
    }
}
