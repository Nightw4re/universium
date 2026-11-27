package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.universium.webcraft.api.WebCraftReadRoute;
import com.universium.webcraft.api.WebCraftReadRoute.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

final class PlayerPrivacyRoute implements WebCraftReadRoute.PublicRoute {
    static final String PATH = "/api/v1/players/privacy";
    private static final Pattern QUERY = Pattern.compile("player=([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})");
    private static final Set<String> FIELDS = Set.of("online", "playTime", "gameMode", "operator",
            "equipment", "inventory", "achievements", "quests");
    private final Path directory;

    PlayerPrivacyRoute(Path directory) {
        this.directory = directory;
    }

    @Override
    public Response readPublic(String rawQuery) {
        return request(rawQuery, null);
    }

    Response update(String rawQuery, String body) {
        return request(rawQuery, body);
    }

    private synchronized Response request(String query, String body) {
        if (query == null) return Response.error(400, "invalid_query");
        var match = QUERY.matcher(query);
        if (!match.matches()) return Response.error(400, "invalid_query");
        var file = directory.resolve(UUID.fromString(match.group(1)) + ".json");
        try {
            if (body == null) {
                return Response.ok(Files.exists(file) ? parse(Files.readString(file)) : document(Set.of()));
            }
            JsonObject settings;
            try {
                settings = parse(body);
            } catch (JsonParseException exception) {
                return Response.error(400, "invalid_privacy_settings");
            }
            Files.createDirectories(directory);
            var temporary = Files.createTempFile(directory, "privacy-", ".tmp");
            try {
                Files.writeString(temporary, settings.toString());
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return Response.ok(settings);
        } catch (IOException | JsonParseException exception) {
            return Response.error(503, "privacy_unavailable");
        }
    }

    private static JsonObject parse(String input) {
        if (!(JsonParser.parseString(input) instanceof JsonObject settings) || settings.size() != 1
                || !(settings.get("hidden") instanceof JsonArray hidden) || hidden.size() > FIELDS.size()) {
            throw new JsonParseException("Expected a hidden fields array");
        }
        var fields = new TreeSet<String>();
        for (var entry : hidden) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()
                    || !FIELDS.contains(entry.getAsString()) || !fields.add(entry.getAsString())) {
                throw new JsonParseException("Invalid hidden field");
            }
        }
        return document(fields);
    }

    private static JsonObject document(Set<String> fields) {
        var settings = new JsonObject();
        var hidden = new JsonArray();
        fields.forEach(hidden::add);
        settings.add("hidden", hidden);
        return settings;
    }
}
