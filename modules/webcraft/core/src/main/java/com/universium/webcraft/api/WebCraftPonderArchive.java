package com.universium.webcraft.api;

import java.io.IOException;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** A single preview archive restricted to Ponder version 1 paths. */
public final class WebCraftPonderArchive implements AutoCloseable {
    /** The fixed path of the scene index. */
    public static final String INDEX_PATH = "ponder/index.json";
    private static final String HASH = "[a-f0-9]{64}";
    private final ZipFile zip;

    WebCraftPonderArchive(ZipFile zip) {
        this.zip = zip;
    }

    /**
     * Reads an entry of the specified kind, or returns null for a missing entry.
     * A caller serving scene files must also authorize the directory and frame against the index.
     */
    public byte[] read(Entry kind, String path) throws IOException {
        if (!kind.matches(path)) throw new IllegalArgumentException("Unexpected path for Ponder entry");
        var entry = zip.getEntry(path);
        if (entry == null) return null;
        if (entry.isDirectory()) throw new IOException("Ponder entry is not a file");
        byte[] bytes = WebCraftPreviewPack.readEntry(zip, entry, kind.limit);
        if (bytes == null) throw new IOException("Ponder entry exceeds its limit");
        return bytes;
    }

    /** Releases the open archive. */
    @Override
    public void close() throws IOException {
        zip.close();
    }

    /** Each entry kind defines an exact pathname pattern and a maximum uncompressed byte size. */
    public enum Entry {
        INDEX(Pattern.quote(INDEX_PATH), 4 * 1024 * 1024),
        MANIFEST("ponder/scenes/" + HASH + "/scene\\.json", 64 * 1024),
        FRAME("ponder/scenes/" + HASH + "/frames/(?:0|[1-9][0-9]{0,4})\\.json", 2 * 1024 * 1024),
        MESH("ponder/meshes/" + HASH + "\\.bin", 4 * 1024 * 1024),
        TEXTURE("ponder/textures/" + HASH + "\\.png", 8 * 1024 * 1024),
        ANIMATION("ponder/textures/" + HASH + "\\.json", 1024 * 1024),
        PICKING("ponder/picking/" + HASH + "\\.json", 2 * 1024 * 1024);

        private final Pattern pattern;
        private final int limit;

        Entry(String pattern, int limit) {
            this.pattern = Pattern.compile(pattern);
            this.limit = limit;
        }

        /** Checks that the entire archive path matches this kind. */
        public boolean matches(String path) {
            return pattern.matcher(path).matches();
        }

        /** Resolves an asset route path without admitting the separate index entry. */
        public static Entry asset(String path) {
            for (var kind : values()) {
                if (kind != INDEX && kind.matches(path)) return kind;
            }
            return null;
        }
    }
}
