package com.universium.webcraft.runtime;

import com.universium.webcraft.config.webcraftConfig;
import com.universium.webcraft.config.webcraftRuntimeConfig;
import com.universium.webcraft.config.webcraftRuntimeConfigLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class webcraftBootstrap {
    private final Path configPath;
    private final webcraftPlayerDataProvider playerDataProvider;
    private final long startedAtMillis;

    public webcraftBootstrap(Path configPath, webcraftPlayerDataProvider playerDataProvider, long startedAtMillis) {
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.playerDataProvider = Objects.requireNonNull(playerDataProvider, "playerDataProvider");
        this.startedAtMillis = startedAtMillis;
    }

    public webcraftHttpServer buildServer() throws IOException {
        webcraftRuntimeConfig runtimeConfig = new webcraftRuntimeConfigLoader().load(resolveConfigPath());
        webcraftRouteBinder binder = new webcraftRouteBinder(handler -> resolveHandler(runtimeConfig.config(), handler));
        List<webcraftRouteDefinition> routes = runtimeConfig.routes().stream().map(binder::bind).toList();
        webcraftRouteDispatcher dispatcher = new webcraftRouteDispatcher(runtimeConfig.config(), playerDataProvider, startedAtMillis, routes);
        webcraftResourcePackRegistry resourcePackRegistry = new webcraftResourcePackRegistry(runtimeConfig.config());
        return new webcraftHttpServer(runtimeConfig.config(), dispatcher, resourcePackRegistry);
    }

    private Path resolveConfigPath() throws IOException {
        if (Files.exists(configPath)) {
            return configPath;
        }

        if (configPath.getParent() != null) {
            Files.createDirectories(configPath.getParent());
        }

        try (InputStream inputStream = webcraftBootstrap.class.getResourceAsStream("/webcraft-server.toml")) {
            if (inputStream != null) {
                Files.writeString(configPath, new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
                return configPath;
            }
        }

        Files.writeString(configPath, defaultConfig());
        return configPath;
    }

    private static String defaultConfig() {
        return """
                [server]
                enabled = true
                auto_port = true
                kill_existing_on_start = true
                host = "127.0.0.1"
                port = 8087
                public_url = "http://127.0.0.1:8087"
                minecraft_version = "1.21.1"
                neoforge_build = 224
                server_name = "WebCraft Dev Server"
                minecraft_server_name = "WebCraft Dev Server"
                minecraft_server_address = "127.0.0.1:25565"
                generate_resources_at_start = true
                resource_packs_web_order = []

                [frontend]
                mode = "built_in"
                theme = "default"

                [logging]
                access_enabled = true
                error_enabled = true
                access_file = "logs/webcraft-access.log"
                error_file = "logs/webcraft-error.log"

                [route."GET /server-info"]
                type = "java"
                ref = "webcraft.serverInfo"

                [route."GET /player-list"]
                type = "java"
                ref = "webcraft.playerNames"

                [route."GET /player-list/online"]
                type = "java"
                ref = "webcraft.playerOnlineNames"

                [route."GET /player-info/{playerName}"]
                type = "java"
                ref = "webcraft.playerInfo"

                [route."GET /resourcepacks"]
                type = "java"
                ref = "webcraft.resourcePackManifest"
                """;
    }

    private webcraftRouteHandler resolveHandler(webcraftConfig config, com.universium.webcraft.config.webcraftRouteConfig.HandlerConfig handler) {
        return switch (handler.ref()) {
            case "webcraft.serverInfo" -> request -> serverInfo(config);
            case "webcraft.playerNames" -> request -> playerNames(request);
            case "webcraft.playerOnlineNames" -> request -> playerOnlineNames();
            case "webcraft.playerInfo" -> request -> playerInfo(resolvePlayerName(request));
            case "webcraft.resourcePackManifest" -> request -> resourcePackManifest(config);
            case "webcraft.playerInfoFallback" -> request -> new webcraftResponse(200, "application/json", "{\"success\":false,\"error\":\"fallback\"}");
            case "webcraft.unauthorized" -> request -> new webcraftResponse(401, "application/json", "{\"success\":false,\"error\":\"unauthorized\"}");
            case "webcraft.forbidden" -> request -> new webcraftResponse(403, "application/json", "{\"success\":false,\"error\":\"forbidden\"}");
            default -> request -> new webcraftResponse(500, "application/json", "{\"success\":false,\"error\":\"unknown_handler\"}");
        };
    }

    private webcraftResponse resourcePackManifest(webcraftConfig config) {
        webcraftResourcePackRegistry registry = new webcraftResourcePackRegistry(config);
        return webcraftResponse.json(registry.toManifestJson());
    }

    private webcraftResponse serverInfo(webcraftConfig config) {
        long uptimeSeconds = Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000L);
        String minecraftVersion = config.server().minecraftVersion();
        if (minecraftVersion == null || minecraftVersion.isBlank() || "unknown".equalsIgnoreCase(minecraftVersion)) {
            minecraftVersion = "1.21.1";
        }
        int totalPlayersEver = Math.max(0, playerDataProvider.getPlayerNames().size());
        String json = "{"
                + "\"success\":true,"
                + "\"data\":{"
                + "\"serverName\":\"" + config.server().serverName() + "\","
                + "\"online\":" + config.server().enabled() + ","
                + "\"minecraftVersion\":\"" + minecraftVersion + "\","
                + "\"neoforgeBuild\":" + config.server().neoforgeBuild() + ","
                + "\"host\":\"" + config.server().host() + "\","
                + "\"port\":" + config.server().port() + ","
                + "\"publicUrl\":\"" + config.server().publicUrl() + "\","
                + "\"uptimeSeconds\":" + uptimeSeconds + ","
                + "\"totalPlayersEver\":" + totalPlayersEver
                + "}"
                + "}";
        return webcraftResponse.json(json);
    }

    private webcraftResponse playerInfo(String playerName) {
        try {
            if (playerName == null || playerName.isBlank()) {
                return new webcraftResponse(400, "application/json", "{\"success\":false,\"error\":\"invalid_player_name\"}");
            }
            String json = new com.universium.webcraft.playerinfo.PlayerInfoModule().render(playerDataProvider.getPlayerInfo(playerName)).toJson();
            return webcraftResponse.json("{\"success\":true,\"data\":" + json + "}");
        } catch (Exception exception) {
            return new webcraftResponse(400, "application/json", "{\"success\":false,\"error\":\"invalid_player_name\"}");
        }
    }

    private webcraftResponse playerNames(webcraftRequest request) {
        try {
            List<String> names = playerDataProvider.getPlayerNames();
            String online = request.queryParams().get("online");
            if ("1".equalsIgnoreCase(online) || "true".equalsIgnoreCase(online)) {
                names = playerDataProvider.getOnlinePlayerNames();
            }
            String json = names.stream().map(this::quoteJson).reduce((left, right) -> left + "," + right).map(values -> "[" + values + "]").orElse("[]");
            return webcraftResponse.json("{\"success\":true,\"data\":" + json + "}");
        } catch (Exception exception) {
            return new webcraftResponse(500, "application/json", "{\"success\":false,\"error\":\"player_list_unavailable\"}");
        }
    }

    private webcraftResponse playerOnlineNames() {
        try {
            List<String> names = playerDataProvider.getOnlinePlayerNames();
            String json = names.stream().map(this::quoteJson).reduce((left, right) -> left + "," + right).map(values -> "[" + values + "]").orElse("[]");
            return webcraftResponse.json("{\"success\":true,\"data\":" + json + "}");
        } catch (Exception exception) {
            return new webcraftResponse(500, "application/json", "{\"success\":false,\"error\":\"player_list_unavailable\"}");
        }
    }

    private String resolvePlayerName(webcraftRequest request) {
        String playerName = request.queryParams().get("player");
        if (playerName != null && !playerName.isBlank()) {
            return playerName;
        }
        String path = request.path();
        if (path.startsWith("/player-info/")) {
            String playerPart = path.substring("/player-info/".length());
            if (playerPart.endsWith("/")) {
                playerPart = playerPart.substring(0, playerPart.length() - 1);
            }
            return playerPart;
        }
        return null;
    }

    private String quoteJson(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

