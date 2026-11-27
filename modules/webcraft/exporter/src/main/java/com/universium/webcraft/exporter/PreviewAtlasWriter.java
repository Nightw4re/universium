package com.universium.webcraft.exporter;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.universium.webcraft.archive.PreviewAtlasFormat.*;

/** Assembles static sheets on the archive thread. Animation candidates are staged on disk until sampling ends. */
final class PreviewAtlasWriter implements AutoCloseable {
    private final Path stagingDirectory;
    private final StaticPreviewWriter codec;
    private final Map<String, Integer> sheetIds = new HashMap<>();
    private final JsonArray sheets = new JsonArray();
    private final JsonObject sprites = new JsonObject();
    private final String[] keys = new String[TILE_COUNT];
    private final Set<String> animated = new HashSet<>();
    private final int[] pixels = new int[IMAGE_SIZE * IMAGE_SIZE];
    private int used;
    private Path pending;
    private DataOutputStream pendingOutput;
    private int pendingCount;

    PreviewAtlasWriter(Path stagingDirectory, StaticPreviewWriter codec) {
        this.stagingDirectory = stagingDirectory;
        this.codec = codec;
    }

    void write(ExportArchive.EntryWriter writer, String key, PngPixels capture, boolean mayAnimate) throws IOException {
        if (capture.width() != TILE_SIZE || capture.height() != TILE_SIZE) {
            throw new IOException("Unexpected item preview size: " + capture.width() + " x " + capture.height());
        }
        if (mayAnimate) {
            if (pendingOutput == null) {
                pending = Files.createTempFile(stagingDirectory, "preview-animation-candidates-", ".tmp");
                pendingOutput = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(pending)));
            }
            pendingOutput.writeUTF(key);
            for (int pixel : capture.rgba()) pendingOutput.writeInt(pixel);
            pendingCount++;
        } else {
            tile(writer, key, capture.rgba());
        }
    }

    void markAnimated(String key) {
        animated.add(key);
    }

    void finish(ExportArchive.EntryWriter writer) throws IOException {
        if (pendingOutput != null) {
            pendingOutput.close();
            pendingOutput = null;
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(pending)))) {
                var capture = new PngPixels(TILE_SIZE, TILE_SIZE, new int[TILE_SIZE * TILE_SIZE]);
                for (int index = 0; index < pendingCount; index++) {
                    String key = input.readUTF();
                    for (int pixel = 0; pixel < capture.rgba().length; pixel++) capture.rgba()[pixel] = input.readInt();
                    if (animated.contains(key)) {
                        codec.prepare(key, capture).write(writer);
                    } else {
                        tile(writer, key, capture.rgba());
                    }
                }
            }
        }
        if (used > 0) flush(writer);
        var index = new JsonObject();
        index.addProperty("version", VERSION);
        index.addProperty("tileSize", TILE_SIZE);
        index.addProperty("columns", GRID_SIZE);
        index.addProperty("rows", GRID_SIZE);
        index.add("atlases", sheets);
        index.add("sprites", sprites);
        byte[] bytes = index.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_INDEX_BYTES) throw new IOException("Preview atlas index exceeds the supported size");
        writer.write(INDEX, bytes);
    }

    private void tile(ExportArchive.EntryWriter writer, String key, int[] capture) throws IOException {
        int x = (used % GRID_SIZE) * TILE_SIZE;
        int y = (used / GRID_SIZE) * TILE_SIZE;
        for (int row = 0; row < TILE_SIZE; row++) {
            System.arraycopy(capture, row * TILE_SIZE, pixels, (y + row) * IMAGE_SIZE + x, TILE_SIZE);
        }
        keys[used++] = key;
        if (used == TILE_COUNT) flush(writer);
    }

    private void flush(ExportArchive.EntryWriter writer) throws IOException {
        byte[] webp = codec.encodeWebp(new PngPixels(IMAGE_SIZE, IMAGE_SIZE, pixels));
        String hash = Hashing.sha256().hashBytes(webp).toString();
        Integer sheet = sheetIds.get(hash);
        if (sheet == null) {
            sheet = sheets.size();
            writer.write(imagePath(hash), webp);
            sheetIds.put(hash, sheet);
            sheets.add(hash);
        }
        for (int tile = 0; tile < used; tile++) {
            var location = new JsonArray();
            location.add(sheet);
            location.add(tile);
            sprites.add(keys[tile], location);
        }
        used = 0;
        java.util.Arrays.fill(pixels, 0);
    }

    @Override
    public void close() throws IOException {
        try (var stream = pendingOutput) {
            // Ensure the stream is closed before deletion, including on Windows.
        } finally {
            if (pending != null) Files.deleteIfExists(pending);
        }
    }
}
