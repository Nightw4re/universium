package com.universium.webcraft.exporter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class ExportMetadata {
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private ExportMetadata() {}

    static JsonObject read(Path gameDirectory) {
        var modpack = readModpack(gameDirectory.resolve("webcraft-modpack.json"), false);
        if (modpack == null) {
            modpack = readModpack(gameDirectory.resolve("manifest.json"), true);
        }
        if (modpack == null) {
            return null;
        }
        var metadata = new JsonObject();
        metadata.addProperty("version", 1);
        metadata.add("modpack", modpack);
        return metadata;
    }

    private static JsonObject readModpack(Path path, boolean curseForge) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES
                    || !(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", ""))
                    instanceof JsonObject manifest)
                    || curseForge && !"minecraftModpack".equals(readText(manifest, "manifestType"))) {
                return null;
            }
            String name = readText(manifest, "name");
            String version = readText(manifest, "version");
            if (name == null || version == null) {
                return null;
            }
            var modpack = new JsonObject();
            modpack.addProperty("name", name);
            modpack.addProperty("version", version);
            return modpack;
        } catch (IOException | JsonParseException ignored) {
            return null;
        }
    }

    private static String readText(JsonObject manifest, String key) {
        var value = manifest.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = value.getAsString().strip();
        return text.isEmpty() || text.length() > 256 ? null : text;
    }
}
