package com.universium.webcraft.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class webcraftInstanceMetadata {
    private static final String MODULE_ID = "webcraft";

    private final String moduleId;
    private final String instanceId;
    private final long processId;
    private final String host;
    private final int port;
    private final boolean https;
    private final long startedAtMillis;

    public webcraftInstanceMetadata(String instanceId, long processId, String host, int port, boolean https, long startedAtMillis) {
        this.moduleId = MODULE_ID;
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.processId = processId;
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.https = https;
        this.startedAtMillis = startedAtMillis;
    }

    public String moduleId() {
        return moduleId;
    }

    public String instanceId() {
        return instanceId;
    }

    public long processId() {
        return processId;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean https() {
        return https;
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public static webcraftInstanceMetadata current(String host, int port, boolean https, long startedAtMillis) {
        return new webcraftInstanceMetadata(UUID.randomUUID().toString(), ProcessHandle.current().pid(), host, port, https, startedAtMillis);
    }

    public static webcraftInstanceMetadata read(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            Map<String, String> values = new HashMap<>();
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                values.put(key, value);
            }
            String moduleId = values.getOrDefault("moduleId", "");
            if (!MODULE_ID.equals(moduleId)) {
                return null;
            }
            String instanceId = values.getOrDefault("instanceId", "");
            String host = values.getOrDefault("host", "127.0.0.1");
            long processId = parseLong(values.get("processId"), -1L);
            int port = (int) parseLong(values.get("port"), -1L);
            boolean https = Boolean.parseBoolean(values.getOrDefault("https", "false"));
            long startedAtMillis = parseLong(values.get("startedAtMillis"), 0L);
            if (instanceId.isBlank() || processId < 0 || port < 0) {
                return null;
            }
            return new webcraftInstanceMetadata(instanceId, processId, host, port, https, startedAtMillis);
        } catch (IOException exception) {
            return null;
        }
    }

    public void write(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, toFileContent(), StandardCharsets.UTF_8);
    }

    public boolean matches(String host, int port, boolean https) {
        return this.host.equals(host) && this.port == port && this.https == https;
    }

    public String toFileContent() {
        return ""
                + "moduleId=" + moduleId + '\n'
                + "instanceId=" + instanceId + '\n'
                + "processId=" + processId + '\n'
                + "host=" + host + '\n'
                + "port=" + port + '\n'
                + "https=" + https + '\n'
                + "startedAtMillis=" + startedAtMillis + '\n'
                + "startedAt=" + Instant.ofEpochMilli(startedAtMillis).toString() + '\n';
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
