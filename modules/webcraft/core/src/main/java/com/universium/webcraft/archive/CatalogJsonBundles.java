package com.universium.webcraft.archive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipException;

/** Packs small catalog documents while retaining their logical ZIP paths and exact UTF-8 payloads. */
public final class CatalogJsonBundles {
    private static final String ROOT = "catalog-data/bundles/v1/";
    private static final int MAX_BUNDLE_BYTES = 128 * 1024;
    private static final int MAX_DOCUMENT_BYTES = 32 * 1024;
    private static final int MAX_INDEX_BYTES = 1024 * 1024;
    private static final int SHARDS = 512;
    private static final Set<String> PREFIXES = Set.of("stacks", "tooltips", "recipes", "recipe-tags",
            "machine-recipes/items", "machine-recipes/fluids", "machine-recipes/chemicals", "machine-recipes/groups");
    private static final String CATALOG = "catalog-data/";

    private CatalogJsonBundles() {}

    /** Reads one bundled document by its legacy entry name. */
    public static byte[] read(String name, int limit, EntryReader entries) throws IOException {
        if (!isBundledPath(name)) return null;
        byte[] index = entries.read(indexPath(shard(name)), MAX_INDEX_BYTES);
        if (index == null) return null;
        try {
            if (!(JsonParser.parseString(new String(index, StandardCharsets.UTF_8)) instanceof JsonObject paths)
                    || !(paths.get(name) instanceof JsonArray location) || location.size() != 3) return null;
            int bundle = integer(location, 0);
            int offset = integer(location, 1);
            int length = integer(location, 2);
            if (bundle < 0 || offset < 1 || offset >= MAX_BUNDLE_BYTES || length < 1
                    || length > limit || length > MAX_DOCUMENT_BYTES) return null;
            byte[] bytes = entries.read(bundlePath(bundle), MAX_BUNDLE_BYTES);
            return bytes != null && length < bytes.length - offset
                    ? Arrays.copyOfRange(bytes, offset, offset + length) : null;
        } catch (JsonParseException | ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private static int integer(JsonArray location, int index) {
        var value = location.get(index);
        return value instanceof JsonPrimitive number && number.isNumber() ? number.getAsBigDecimal().intValueExact() : -1;
    }

    private static boolean isBundledPath(String name) {
        if (!name.startsWith(CATALOG) || !name.endsWith(".json")) return false;
        for (String prefix : PREFIXES) {
            if (name.startsWith(prefix + "/", CATALOG.length())) return true;
        }
        return false;
    }

    private static int shard(String name) {
        return name.hashCode() & (SHARDS - 1);
    }

    private static String indexPath(int shard) {
        return ROOT + "index/" + shard + ".json";
    }

    private static String bundlePath(int bundle) {
        return ROOT + bundle + ".json";
    }

    /** Writes archive entries, collecting eligible JSON documents into bounded arrays of raw payloads. */
    public static final class Writer {
        private final EntryWriter entries;
        private final ByteArrayOutputStream block = new ByteArrayOutputStream(MAX_BUNDLE_BYTES);
        private final ByteArrayOutputStream[] indexes = new ByteArrayOutputStream[SHARDS];
        private final Set<String> paths = new HashSet<>();
        private int blockId;

        /** Creates a bundler owned by a single archive writer. */
        public Writer(EntryWriter entries) {
            this.entries = entries;
            block.write('[');
        }

        /** Writes an entry now or adds it to the current bundle. */
        public void write(String name, byte[] bytes) throws IOException {
            if (!isBundledPath(name)) {
                entries.write(name, bytes);
                return;
            }
            if (!paths.add(name)) throw new ZipException("duplicate entry: " + name);
            if (bytes.length == 0 || bytes.length > MAX_DOCUMENT_BYTES) {
                entries.write(name, bytes);
                return;
            }

            int shard = shard(name);
            var index = indexes[shard];
            if (index == null) {
                index = new ByteArrayOutputStream();
                index.write('{');
                indexes[shard] = index;
            }
            // Account for a separator and the closing bracket.
            if (block.size() + bytes.length + 2 > MAX_BUNDLE_BYTES) flush();
            int offset = block.size() + (block.size() == 1 ? 0 : 1);
            byte[] record = (new JsonPrimitive(name) + ":[" + blockId + "," + offset + "," + bytes.length + "]")
                    .getBytes(StandardCharsets.UTF_8);
            if (index.size() + record.length + 2 > MAX_INDEX_BYTES) {
                entries.write(name, bytes);
                return;
            }
            if (block.size() > 1) block.write(',');
            block.writeBytes(bytes);
            if (index.size() > 1) index.write(',');
            index.writeBytes(record);
        }

        /** Writes the final bundle and its lookup indexes before the ZIP is published. */
        public void finish() throws IOException {
            flush();
            for (int shard = 0; shard < indexes.length; shard++) {
                var index = indexes[shard];
                if (index != null && index.size() > 1) {
                    index.write('}');
                    entries.write(indexPath(shard), index.toByteArray());
                }
            }
        }

        private void flush() throws IOException {
            if (block.size() == 1) return;
            block.write(']');
            entries.write(bundlePath(blockId++), block.toByteArray());
            block.reset();
            block.write('[');
        }
    }

    /** Reads a physical ZIP entry with an uncompressed byte limit. */
    @FunctionalInterface
    public interface EntryReader {
        byte[] read(String name, int limit) throws IOException;
    }

    /** Writes a physical ZIP entry. */
    @FunctionalInterface
    public interface EntryWriter {
        void write(String name, byte[] bytes) throws IOException;
    }
}
