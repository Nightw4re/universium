package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class PlayerRoster {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<UUID, String> players = new TreeMap<>();
    private final Map<UUID, Integer> playTimeMinutes = new HashMap<>();

    PlayerRoster(Path world, Path profileCache, List<PlayerHistoryData.Player> history) {
        var names = cachedNames(profileCache);
        scan(world.resolve("playerdata"), names, ".dat", ".dat_old");
        var stats = world.resolve("stats");
        scan(stats, names, ".json");
        scan(world.resolve("advancements"), names, ".json");
        history.forEach(player -> recordJoin(player.uuid(), player.name()));
        players.keySet().forEach(uuid -> {
            var minutes = readPlayTime(stats.resolve(uuid + ".json"));
            if (minutes != null) playTimeMinutes.put(uuid, minutes);
        });
    }

    void recordJoin(UUID uuid, String name) {
        if (name.isBlank()) {
            players.putIfAbsent(uuid, null);
        } else {
            players.put(uuid, name);
        }
    }

    NamedPlayer findByName(String name) {
        NamedPlayer match = null;
        for (var player : players.entrySet()) {
            if (name.equalsIgnoreCase(player.getValue())) {
                if (match != null) return null;
                match = new NamedPlayer(player.getKey(), player.getValue());
            }
        }
        return match;
    }

    record NamedPlayer(UUID uuid, String name) {}

    void recordPlayTime(UUID uuid, int ticks) {
        playTimeMinutes.put(uuid, Math.max(0, ticks) / 1_200);
    }

    JsonArray snapshot() {
        var array = new JsonArray();
        players.forEach((uuid, name) -> {
            var player = new JsonObject();
            player.addProperty("uuid", uuid.toString());
            player.addProperty("name", name);
            if (playTimeMinutes.containsKey(uuid)) player.addProperty("playTimeMinutes", playTimeMinutes.get(uuid));
            array.add(player);
        });
        return array;
    }

    private static Integer readPlayTime(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var reader = Files.newBufferedReader(file)) {
            if (JsonParser.parseReader(reader) instanceof JsonObject root
                    && root.get("stats") instanceof JsonObject stats
                    && stats.get("minecraft:custom") instanceof JsonObject custom
                    && custom.get("minecraft:play_time") instanceof JsonPrimitive ticks
                    && ticks.isNumber()) {
                int value = ticks.getAsBigDecimal().intValueExact();
                return value >= 0 ? value / 1_200 : null;
            }
        } catch (IOException | JsonParseException | ArithmeticException | NumberFormatException exception) {
            LOGGER.debug("Could not read play time from {}", file, exception);
        }
        return null;
    }

    private void scan(Path directory, Map<UUID, String> names, String... extensions) {
        try (var files = Files.list(directory)) {
            files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).forEach(path -> {
                String filename = path.getFileName().toString();
                for (var extension : extensions) {
                    if (filename.endsWith(extension)) {
                        var uuid = parseUuid(filename.substring(0, filename.length() - extension.length()));
                        if (uuid != null) players.putIfAbsent(uuid, names.get(uuid));
                        break;
                    }
                }
            });
        } catch (NoSuchFileException ignored) {
            // A world without player activity may not have created these directories.
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.warn("Could not read player record filenames from {}", directory, exception);
        }
    }

    private static Map<UUID, String> cachedNames(Path file) {
        var names = new HashMap<UUID, String>();
        try (var reader = Files.newBufferedReader(file)) {
            if (JsonParser.parseReader(reader) instanceof JsonArray entries) {
                for (var entry : entries) {
                    if (entry instanceof JsonObject profile) {
                        var uuid = parseUuid(string(profile.get("uuid")));
                        var name = string(profile.get("name"));
                        if (uuid != null && !name.isBlank()) names.putIfAbsent(uuid, name);
                    }
                }
            }
        } catch (NoSuchFileException ignored) {
            // A profile cache is optional. UUIDs from world records remain discoverable.
        } catch (IOException | JsonParseException exception) {
            LOGGER.warn("Could not read player names from the profile cache: {}", file, exception);
        }
        return names;
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : "";
    }

    private static UUID parseUuid(String value) {
        if (value.length() != 36) return null;
        try {
            var uuid = UUID.fromString(value);
            return uuid.toString().equalsIgnoreCase(value) ? uuid : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
