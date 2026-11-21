package com.universium.webcraft.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

enum ApiTransport {
    INHERIT, HTTP, WEBSOCKET, BOTH;

    boolean http() {
        return this == HTTP || this == BOTH;
    }

    boolean websocket() {
        return this == WEBSOCKET || this == BOTH;
    }

    record Policy(ApiTransport defaultMode, Map<String, ApiTransport> overrides) {
        Policy {
            if (defaultMode == INHERIT) throw new IllegalArgumentException("apiTransport requires HTTP, WEBSOCKET or BOTH");
            overrides = Map.copyOf(overrides);
        }

        static Policy from(ApiTransport defaultMode, List<? extends String> entries) {
            var overrides = new HashMap<String, ApiTransport>();
            for (String entry : entries) {
                if (!entry.matches("/api/v1(?:/[a-z0-9_.-]+)+=(HTTP|WEBSOCKET|BOTH)")) {
                    throw new IllegalArgumentException("routeTransports requires exact /api/v1 paths and HTTP, WEBSOCKET or BOTH");
                }
                int separator = entry.indexOf('=');
                if (overrides.putIfAbsent(entry.substring(0, separator),
                        ApiTransport.valueOf(entry.substring(separator + 1))) != null) {
                    throw new IllegalArgumentException("Duplicate path in routeTransports: " + entry.substring(0, separator));
                }
            }
            return new Policy(defaultMode, overrides);
        }

        ApiTransport resolve(String path, ApiTransport feedMode) {
            return overrides.getOrDefault(path, feedMode == INHERIT ? defaultMode : feedMode);
        }
    }
}
