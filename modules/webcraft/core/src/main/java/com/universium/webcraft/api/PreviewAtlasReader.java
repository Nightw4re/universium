package com.universium.webcraft.api;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.glavo.webp.WebPImage;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static com.universium.webcraft.archive.PreviewAtlasFormat.*;

final class PreviewAtlasReader {
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final int CACHED_SHEETS = 4;
    private static final int CACHED_ICONS = 128;
    private final LinkedHashMap<String, int[]> sheets = new LinkedHashMap<>(CACHED_SHEETS, 0.75f, true);
    private final LinkedHashMap<String, byte[]> icons = new LinkedHashMap<>(CACHED_ICONS, 0.75f, true);
    private ArchiveStamp stamp;
    private Index index;

    synchronized WebCraftPreviewPack.Icon read(Path archive, ZipFile zip, String key) throws IOException {
        var entry = zip.getEntry(INDEX);
        if (entry == null) return null;
        var attributes = Files.readAttributes(archive, BasicFileAttributes.class);
        var current = new ArchiveStamp(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size(), entry.getCrc());
        if (!current.equals(stamp)) {
            index = readIndex(WebCraftPreviewPack.readEntry(zip, entry, MAX_INDEX_BYTES));
            sheets.clear();
            icons.clear();
            stamp = current;
        }
        if (index == null) return null;
        var location = index.sprites().get(key);
        if (location == null) return null;
        byte[] png = icons.get(key);
        if (png == null) {
            String hash = index.hashes().get(location.sheet());
            int[] sheet = sheets.get(hash);
            if (sheet == null) {
                sheet = readSheet(zip, hash);
                // Keep an invalid-sheet result in the same bounded cache.
                if (sheet == null) sheet = new int[0];
                sheets.put(hash, sheet);
                if (sheets.size() > CACHED_SHEETS) sheets.pollFirstEntry();
            }
            if (sheet.length == 0) return null;
            var tile = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            int x = (location.tile() % GRID_SIZE) * TILE_SIZE;
            int y = (location.tile() / GRID_SIZE) * TILE_SIZE;
            tile.setRGB(0, 0, TILE_SIZE, TILE_SIZE, sheet, y * IMAGE_SIZE + x, IMAGE_SIZE);
            var output = new ByteArrayOutputStream();
            try (var stream = new MemoryCacheImageOutputStream(output)) {
                if (!ImageIO.write(tile, "png", stream)) return null;
            }
            png = output.toByteArray();
            icons.put(key, png);
            if (icons.size() > CACHED_ICONS) icons.pollFirstEntry();
        }
        return new WebCraftPreviewPack.Icon(png.clone(), "image/png");
    }

    private static int[] readSheet(ZipFile zip, String hash) throws IOException {
        String path = imagePath(hash);
        byte[] bytes = WebCraftPreviewPack.readEntry(zip, zip.getEntry(path), MAX_IMAGE_BYTES);
        if (!WebCraftPreviewPack.isLosslessWebp(bytes, IMAGE_SIZE)
                || !hash.equals(Hashing.sha256().hashBytes(bytes).toString())) return null;
        try {
            return WebPImage.read(new ByteArrayInputStream(bytes)).getFirstFrame().getArgbArray();
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static Index readIndex(byte[] bytes) {
        if (bytes == null) return null;
        try {
            if (!(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)) instanceof JsonObject root)
                    || integer(root.get("version")) != VERSION || integer(root.get("tileSize")) != TILE_SIZE
                    || integer(root.get("columns")) != GRID_SIZE || integer(root.get("rows")) != GRID_SIZE
                    || !(root.get("atlases") instanceof JsonArray atlases)
                    || !(root.get("sprites") instanceof JsonObject entries)) return null;
            var hashes = new ArrayList<String>(atlases.size());
            for (var value : atlases) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                        || !HASH.matcher(value.getAsString()).matches()) return null;
                hashes.add(value.getAsString());
            }
            var sprites = new HashMap<String, Location>(entries.size());
            for (var entry : entries.entrySet()) {
                if (!HASH.matcher(entry.getKey()).matches() || !(entry.getValue() instanceof JsonArray pair)
                        || pair.size() != 2) continue;
                int sheet = integer(pair.get(0));
                int tile = integer(pair.get(1));
                if (sheet >= 0 && sheet < hashes.size() && tile >= 0 && tile < TILE_COUNT) {
                    sprites.put(entry.getKey(), new Location(sheet, tile));
                }
            }
            return new Index(hashes, sprites);
        } catch (JsonParseException exception) {
            return null;
        }
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
        try {
            return Integer.parseInt(value.getAsString());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private record Index(List<String> hashes, Map<String, Location> sprites) {}

    private record Location(int sheet, int tile) {}

    private record ArchiveStamp(Object fileKey, FileTime modified, long size, long indexCrc) {}
}
