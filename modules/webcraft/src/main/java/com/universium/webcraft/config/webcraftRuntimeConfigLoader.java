package com.universium.webcraft.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class webcraftRuntimeConfigLoader {
    public webcraftRuntimeConfig load(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    public webcraftRuntimeConfig parse(String toml) {
        webcraftConfigLoader configLoader = new webcraftConfigLoader();
        webcraftConfig config = configLoader.parse(toml);
        List<webcraftRouteConfig> routes = parseRoutes(toml);
        return new webcraftRuntimeConfig(config, routes);
    }

    private List<webcraftRouteConfig> parseRoutes(String toml) {
        List<webcraftRouteConfig> routes = new ArrayList<>();
        Map<String, String> current = null;
        Map<String, String> currentMiddleware = null;
        Map<Integer, webcraftRouteConfig.HandlerConfig> errorHandlers = null;
        List<webcraftRouteConfig.HandlerConfig> middleware = null;
        webcraftRouteConfig.HandlerConfig fallback = null;
        String currentRouteKey = null;

        for (String rawLine : toml.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[[route.") && line.endsWith("]]")) {
                if (currentMiddleware != null && currentRouteKey != null) {
                    middleware.add(toHandler(currentMiddleware));
                }
                currentMiddleware = new LinkedHashMap<>();
                continue;
            }
            if (line.startsWith("[route.") && line.endsWith("]")) {
                if (current != null && currentRouteKey != null) {
                    if (currentMiddleware != null) {
                        middleware.add(toHandler(currentMiddleware));
                        currentMiddleware = null;
                    }
                    routes.add(buildRoute(currentRouteKey, current, middleware, fallback, errorHandlers));
                }
                current = new LinkedHashMap<>();
                middleware = new ArrayList<>();
                errorHandlers = new LinkedHashMap<>();
                fallback = new webcraftRouteConfig.HandlerConfig("java", "webcraft.defaultFallback");
                currentRouteKey = line.substring("[route.".length(), line.length() - 1).replace("\"", "");
                if (currentRouteKey.endsWith(".fallback")) {
                    currentRouteKey = currentRouteKey.substring(0, currentRouteKey.length() - ".fallback".length());
                }
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String value = stripQuotes(line.substring(equals + 1).trim());
            if (currentMiddleware != null) {
                currentMiddleware.put(key, value);
                continue;
            }
            if (current != null) {
                if (key.startsWith("errors.\"")) {
                    int status = Integer.parseInt(key.substring("errors.\"".length(), key.length() - 1));
                    errorHandlers.put(status, new webcraftRouteConfig.HandlerConfig(value, value));
                } else if ("fallback.type".equals(key) || "fallback.ref".equals(key)) {
                    String fallbackType = "java";
                    String fallbackRef = "webcraft.defaultFallback";
                    if ("fallback.type".equals(key)) {
                        fallbackType = value;
                        fallback = new webcraftRouteConfig.HandlerConfig(fallbackType, fallbackRef);
                    } else {
                        fallbackRef = value;
                        fallback = new webcraftRouteConfig.HandlerConfig(fallbackType, fallbackRef);
                    }
                } else {
                    current.put(key, value);
                }
            }
        }

        if (current != null && currentRouteKey != null) {
            if (currentMiddleware != null) {
                middleware.add(toHandler(currentMiddleware));
            }
            routes.add(buildRoute(currentRouteKey, current, middleware, fallback, errorHandlers));
        }
        return routes;
    }

    private static webcraftRouteConfig buildRoute(
            String routeKey,
            Map<String, String> values,
            List<webcraftRouteConfig.HandlerConfig> middleware,
            webcraftRouteConfig.HandlerConfig fallback,
            Map<Integer, webcraftRouteConfig.HandlerConfig> errors
    ) {
        String method = routeKey.substring(0, routeKey.indexOf(' ')).trim();
        String path = routeKey.substring(routeKey.indexOf(' ') + 1).trim();
        String type = values.getOrDefault("type", "java");
        String ref = values.getOrDefault("ref", "webcraft.default");
        String match = values.getOrDefault("match", "exact");
        return new webcraftRouteConfig(method, path, match, type, ref, middleware, fallback, errors);
    }

    private static webcraftRouteConfig.HandlerConfig toHandler(Map<String, String> values) {
        return new webcraftRouteConfig.HandlerConfig(values.getOrDefault("type", "java"), values.getOrDefault("ref", "webcraft.defaultMiddleware"));
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}

