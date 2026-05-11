package com.universium.webcraft.runtime;

import com.universium.webcraft.config.webcraftConfig;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class webcraftRouteDispatcher {
    private final webcraftConfig config;
    private final webcraftPlayerDataProvider playerDataProvider;
    private final long startedAtMillis;
    private final webcraftRoutePipeline pipeline = new webcraftRoutePipeline();
    private final List<webcraftRouteDefinition> routes;
    private volatile int runtimePort;

    public webcraftRouteDispatcher(webcraftConfig config, webcraftPlayerDataProvider playerDataProvider, long startedAtMillis) {
        this(config, playerDataProvider, startedAtMillis, defaultRoutes(config, playerDataProvider, startedAtMillis));
    }

    public webcraftRouteDispatcher(webcraftConfig config, webcraftPlayerDataProvider playerDataProvider, long startedAtMillis, List<webcraftRouteDefinition> routes) {
        this.config = Objects.requireNonNull(config, "config");
        this.playerDataProvider = Objects.requireNonNull(playerDataProvider, "playerDataProvider");
        this.startedAtMillis = startedAtMillis;
        this.routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
        this.runtimePort = config.server().port();
    }

    public void setRuntimePort(int runtimePort) {
        this.runtimePort = runtimePort;
    }

    public webcraftResponse dispatch(webcraftRequest request) {
        if (!"GET".equalsIgnoreCase(request.method())) {
            return errorResponse(405, "method_not_allowed");
        }
        webcraftRequest effectiveRequest = normalizeApiRequest(request);
        if ("/resourcepacks".equals(effectiveRequest.path()) || "/resourcepacks.json".equals(effectiveRequest.path())) {
            return resourcePackManifest();
        }
        for (webcraftRouteDefinition route : routes) {
            if (route.matches(effectiveRequest.method(), effectiveRequest.path())) {
                webcraftResponse response = pipeline.execute(route, effectiveRequest);
                if (!(response.status() == 404 && effectiveRequest.path().startsWith("/player-info/"))) {
                    return response;
                }
            }
        }
        if (effectiveRequest.path().startsWith("/player-info/")) {
            return executePlayerInfoPath(effectiveRequest);
        }
        return errorResponse(404, "not_found");
    }

    public static List<webcraftRouteDefinition> defaultRoutes(webcraftConfig config, webcraftPlayerDataProvider playerDataProvider, long startedAtMillis) {
        webcraftRouteDispatcher dispatcher = new webcraftRouteDispatcher(config, playerDataProvider, startedAtMillis, List.of());
        return List.of(
                new webcraftRouteDefinition(
                        "GET",
                        "/server-info",
                        "exact",
                        List.of(),
                        request -> dispatcher.serverInfo(),
                        request -> dispatcher.serverInfo(),
                        Map.of()
                ),
                new webcraftRouteDefinition(
                        "GET",
                        "/player-list",
                        "exact",
                        List.of(),
                        request -> dispatcher.playerNames(request),
                        request -> dispatcher.errorResponse(404, "not_found"),
                        Map.of()
                ),
                new webcraftRouteDefinition(
                        "GET",
                        "/player-list/online",
                        "exact",
                        List.of(),
                        request -> dispatcher.playerOnlineNames(),
                        request -> dispatcher.errorResponse(404, "not_found"),
                        Map.of()
                ),
                new webcraftRouteDefinition(
                        "GET",
                        "/player-info/{playerName}",
                        "prefix",
                        List.of(),
                        request -> dispatcher.playerInfoByQuery(request.queryParams(), request.path()),
                        request -> dispatcher.errorResponse(404, "not_found"),
                        Map.of(
                                401, request -> dispatcher.errorResponse(401, "unauthorized"),
                                403, request -> dispatcher.errorResponse(403, "forbidden")
                        )
                )
        );
    }

    private webcraftResponse serverInfo() {
        long uptimeSeconds = Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000L);
        String minecraftVersion = config.server().minecraftVersion();
        if (minecraftVersion == null || minecraftVersion.isBlank() || "unknown".equalsIgnoreCase(minecraftVersion)) {
            minecraftVersion = "1.21.1";
        }
        int resourcePackCount = new webcraftResourcePackRegistry(config).packCount();
        String json = "{"
                + "\"success\":true,"
                + "\"data\":{"
                + "\"serverName\":" + quote(config.server().serverName()) + ","
                + "\"online\":" + config.server().enabled() + ","
                + "\"minecraftVersion\":" + quote(minecraftVersion) + ","
                + "\"neoforgeBuild\":" + config.server().neoforgeBuild() + ","
                + "\"host\":" + quote(config.server().host()) + ","
                + "\"port\":" + runtimePort + ","
                + "\"publicUrl\":" + quote(config.server().publicUrl()) + ","
                + "\"uptimeSeconds\":" + uptimeSeconds + ","
                + "\"resourcePackCount\":" + resourcePackCount
                + "}"
                + "}";
        return webcraftResponse.json(json);
    }

    private webcraftResponse resourcePackManifest() {
        return webcraftResponse.json(new webcraftResourcePackRegistry(config).manifestJson());
    }

    private webcraftResponse executePlayerInfoPath(webcraftRequest request) {
        String playerPart = request.path().substring("/player-info/".length());
        if (playerPart.endsWith("/")) {
            playerPart = playerPart.substring(0, playerPart.length() - 1);
        }
        return playerInfo(playerPart);
    }

    private webcraftResponse playerInfoByQuery(Map<String, String> queryParams, String path) {
        return path.startsWith("/player-info/") ? executePlayerInfoPath(new webcraftRequest("GET", path, queryParams, Map.of())) : errorResponse(400, "invalid_player_name");
    }

    private webcraftRequest normalizeApiRequest(webcraftRequest request) {
        String path = request.path();
        if (path == null || !path.startsWith("/api")) {
            return request;
        }
        String normalized = path.substring("/api".length());
        if (normalized.isBlank()) {
            normalized = "/";
        }
        return new webcraftRequest(request.method(), normalized, request.queryParams(), request.pathParams());
    }

    private webcraftResponse playerNames(webcraftRequest request) {
        try {
            List<String> names = playerDataProvider.getPlayerNames();
            String json = quoteList(names);
            return webcraftResponse.json("{\"success\":true,\"data\":" + json + "}");
        } catch (Exception exception) {
            return errorResponse(500, "player_list_unavailable");
        }
    }

    private webcraftResponse playerOnlineNames() {
        try {
            List<String> names = playerDataProvider.getOnlinePlayerNames();
            String json = quoteList(names);
            return webcraftResponse.json("{\"success\":true,\"data\":" + json + "}");
        } catch (Exception exception) {
            return errorResponse(500, "player_list_unavailable");
        }
    }

    private webcraftResponse playerInfo(String playerName) {
        try {
            if (playerName == null || playerName.isBlank()) {
                return errorResponse(400, "invalid_player_name");
            }
            String json = new com.universium.webcraft.playerinfo.PlayerInfoModule().render(playerDataProvider.getPlayerInfo(playerName)).toJson();
            return webcraftResponse.json("{\"success\":true,\"data\":" + json + "}");
        } catch (IllegalArgumentException exception) {
            return errorResponse(400, "invalid_player_name");
        }
    }

    private webcraftResponse errorResponse(int status, String code) {
        return new webcraftResponse(status, "application/json", "{\"success\":false,\"error\":" + quote(code) + "}");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String quoteList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream().map(value -> quote(value == null ? "" : value)).reduce((left, right) -> left + "," + right).map(items -> "[" + items + "]").orElse("[]");
    }
}

