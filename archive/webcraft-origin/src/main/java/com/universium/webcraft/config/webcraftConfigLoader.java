package com.universium.webcraft.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

public final class webcraftConfigLoader {
    public webcraftConfig load(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return parse(content);
    }

    public webcraftConfig parse(String toml) {
        Map<String, Map<String, String>> sections = new HashMap<>();
        String currentSection = "";
        sections.put(currentSection, new HashMap<>());

        for (String rawLine : toml.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                sections.computeIfAbsent(currentSection, key -> new HashMap<>());
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String value = stripQuotes(line.substring(equals + 1).trim());
            sections.get(currentSection).put(key, value);
        }

        Map<String, String> server = sections.getOrDefault("server", Map.of());
        Map<String, String> frontend = sections.getOrDefault("frontend", Map.of());
        Map<String, String> logging = sections.getOrDefault("logging", Map.of());

        return new webcraftConfig(
                new webcraftConfig.Server(
                        getBoolean(server, "enabled", true),
                        getBoolean(server, "auto_port", true),
                        getBoolean(server, "kill_existing_on_start", true),
                        getBoolean(server, "use_https", false),
                        server.getOrDefault("host", "127.0.0.1"),
                        getInt(server, "port", 8080),
                        server.getOrDefault("public_url", "http://127.0.0.1:8080"),
                        server.getOrDefault("minecraft_version", "unknown"),
                        getInt(server, "neoforge_build", 224),
                        server.getOrDefault("server_name", "Universium"),
                        server.getOrDefault("minecraft_server_name", "Universium Dev Server"),
                        server.getOrDefault("minecraft_server_address", "127.0.0.1:25565"),
                        server.getOrDefault("keystore_path", ""),
                        server.getOrDefault("keystore_password", ""),
                        server.getOrDefault("keystore_type", "JKS"),
                        server.getOrDefault("webroot_dir", "../webroot"),
                        server.getOrDefault("webroot_index", "index.html"),
                        getBoolean(server, "http_bootstrap_async", true),
                        getBoolean(server, "generate_resources_at_start", true),
                        getBoolean(server, "resources_warmup_async", true),
                        getInt(server, "resources_warmup_batch_size", 32),
                        getInt(server, "resources_warmup_pause_ms", 10),
                        getInt(server, "resources_cache_max_age_seconds", 604800),
                        server.getOrDefault("resource_packs_dir", "../resourcepacks"),
                        server.getOrDefault("resource_packs_list", "resourcepacks.txt"),
                        getStringList(server, "resource_packs_web_order", List.of())
                ),
                new webcraftConfig.Frontend(
                        frontend.getOrDefault("mode", "built_in"),
                        frontend.getOrDefault("theme", "default")
                ),
                new webcraftConfig.Logging(
                        getBoolean(logging, "access_enabled", true),
                        getBoolean(logging, "error_enabled", true),
                        logging.getOrDefault("access_file", "logs/webcraft-access.log"),
                        logging.getOrDefault("error_file", "logs/webcraft-error.log")
                )
        );
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int getInt(Map<String, String> section, String key, int fallback) {
        String value = section.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    private static boolean getBoolean(Map<String, String> section, String key, boolean fallback) {
        String value = section.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.toLowerCase(Locale.ROOT));
    }

    private static List<String> getStringList(Map<String, String> section, String key, List<String> fallback) {
        String value = section.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return fallback;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String token : inner.split(",")) {
            String entry = stripQuotes(token.trim());
            if (!entry.isBlank()) {
                values.add(entry);
            }
        }
        return List.copyOf(values);
    }
}

