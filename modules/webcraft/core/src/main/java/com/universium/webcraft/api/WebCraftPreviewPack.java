package com.universium.webcraft.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;

/** Reads the administrator's client export in place, without extracting archive entries. */
public final class WebCraftPreviewPack {
    private static final byte[] FORMAT = "webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG_HEADER = {(byte) 137, 80, 78, 71, 13, 10, 26, 10,
            0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 64, 0, 0, 0, 64};
    private final Path archive;

    /** References an optional preview archive. */
    public WebCraftPreviewPack(Path archive) {
        this.archive = archive;
    }

    /** Reports whether an administrator has installed the optional archive. */
    public boolean isInstalled() {
        return Files.isRegularFile(archive);
    }

    /** Reads an exported icon, or returns null when the entry is unavailable or invalid. */
    public byte[] readIcon(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) {
            return null;
        }
        byte[] bytes = readEntry("previews/" + key + ".png", 65_536);
        return bytes != null && bytes.length >= PNG_HEADER.length
                && Arrays.equals(PNG_HEADER, 0, PNG_HEADER.length, bytes, 0, PNG_HEADER.length) ? bytes : null;
    }

    /** Reads the optional 64-pixel animated PNG associated with an exported icon. */
    public byte[] readAnimation(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) {
            return null;
        }
        byte[] bytes = readEntry("animations/" + key + ".png", 3 * 1024 * 1024);
        // The exporter writes acTL immediately after IHDR. Reject static or oversized attachments.
        if (bytes == null || bytes.length < 53
                || !Arrays.equals(PNG_HEADER, 0, PNG_HEADER.length, bytes, 0, PNG_HEADER.length)) {
            return null;
        }
        var header = ByteBuffer.wrap(bytes);
        int frameCount = header.getInt(41);
        return header.getInt(33) == 8 && header.getInt(37) == 0x6163544c && frameCount >= 2 && frameCount <= 160
                ? bytes : null;
    }

    /** Reads metadata for exported default stacks. Earlier or inventory-only exports have no catalog. */
    public JsonArray readCatalog() {
        var root = readJson("catalog.json", 4 * 1024 * 1024);
        return root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
    }

    /** Reads the shared visual effects exported by the game client. */
    public JsonObject readEffects() {
        var root = readJson("effects.json", 128 * 1024);
        return root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
    }

    /** Reads the versioned visual catalog contributed by the given module. */
    public JsonObject readDesign(ResourceLocation module) {
        if (!module.getPath().matches("[a-z0-9_-]+")) {
            return new JsonObject();
        }
        String file = "/" + module.getPath() + ".json";
        var root = readJson("designs/" + module.getNamespace() + file, 2 * 1024 * 1024);
        if (root.isJsonNull() && module.getNamespace().equals("webcraft_designs")) {
            root = readJson("designs/webcraft_next_designs" + file, 2 * 1024 * 1024);
        }
        return root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
    }

    /** Reads a content-addressed PNG texture of at most 1024 by 1024 pixels. */
    public byte[] readTexture(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) {
            return null;
        }
        byte[] bytes = readEntry("textures/" + hash + ".png", 512 * 1024);
        if (bytes == null || bytes.length < PNG_HEADER.length || !Arrays.equals(PNG_HEADER, 0, 16, bytes, 0, 16)) {
            return null;
        }
        var header = ByteBuffer.wrap(bytes);
        int width = header.getInt(16);
        int height = header.getInt(20);
        return width > 0 && width <= 1024 && height > 0 && height <= 1024 ? bytes : null;
    }

    private JsonElement readJson(String name, int limit) {
        byte[] bytes = readEntry(name, limit);
        try {
            return bytes == null ? JsonNull.INSTANCE : JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException exception) {
            return JsonNull.INSTANCE;
        }
    }

    private byte[] readEntry(String name, int limit) {
        try (var zip = new ZipFile(archive.toFile())) {
            var format = zip.getEntry("format.txt");
            var entry = zip.getEntry(name);
            if (format == null || entry == null || entry.getSize() > limit) {
                return null;
            }
            try (var input = zip.getInputStream(format)) {
                if (!Arrays.equals(input.readNBytes(FORMAT.length + 1), FORMAT)) {
                    return null;
                }
            }
            try (var input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(limit + 1);
                return bytes.length <= limit ? bytes : null;
            }
        } catch (IOException exception) {
            return null;
        }
    }
}
