package com.universium.webcraft.ponder;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.universium.webcraft.api.WebCraftPonderArchive.Entry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

final class PonderDocuments {
    static final int MAX_SCENES = 4_096;
    static final int MAX_FRAMES = 12_000;

    private PonderDocuments() {}

    static Set<String> manifests(byte[] bytes) throws IOException {
        var root = read(bytes);
        if (!root.isJsonObject()) throw new IOException("Expected a Ponder index object");
        var index = root.getAsJsonObject();
        var scenes = index.get("scenes");
        if (positiveInteger(index.get("version"), 1) != 1
                || !string(index.get("locale"))
                || scenes == null || !scenes.isJsonArray()
                || scenes.getAsJsonArray().size() > MAX_SCENES) {
            throw new IOException("Invalid Ponder index schema");
        }
        var manifests = new HashSet<String>();
        for (var entry : scenes.getAsJsonArray()) {
            if (!entry.isJsonObject()) throw new IOException("Invalid scene entry");
            var scene = entry.getAsJsonObject();
            var manifest = scene.get("manifest");
            var items = scene.get("items");
            if (!string(manifest) || !Entry.MANIFEST.matches(manifest.getAsString())
                    || !manifests.add(manifest.getAsString())
                    || !string(scene.get("id")) || !string(scene.get("title")) || !string(scene.get("schematic"))
                    || items == null || !items.isJsonArray()) {
                throw new IOException("Invalid scene metadata or manifest path");
            }
            for (var item : items.getAsJsonArray()) {
                if (!string(item)) throw new IOException("Invalid Ponder item association");
            }
        }
        return Set.copyOf(manifests);
    }

    static int frameCount(byte[] bytes) throws IOException {
        var document = read(bytes);
        if (!document.isJsonObject()) throw new IOException("Expected a Ponder scene manifest object");
        int count = positiveInteger(document.getAsJsonObject().get("frameCount"), MAX_FRAMES);
        if (count == 0) throw new IOException("Invalid Ponder frame count");
        return count;
    }

    static JsonElement read(byte[] bytes) throws IOException {
        try (var reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8.newDecoder()))) {
            var value = Streams.parse(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Expected one Ponder JSON document");
            return value;
        } catch (JsonParseException exception) {
            throw new IOException("Invalid Ponder JSON", exception);
        }
    }

    private static boolean string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static int positiveInteger(JsonElement value, int maximum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return 0;
        String number = value.getAsString();
        if (!number.matches("[1-9][0-9]{0,4}")) return 0;
        int parsed = Integer.parseInt(number);
        return parsed <= maximum ? parsed : 0;
    }
}
