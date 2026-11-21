package com.universium.webcraft.ponder;

import com.google.common.hash.Hashing;
import com.mojang.logging.LogUtils;
import com.universium.webcraft.api.WebCraftPonderArchive;
import com.universium.webcraft.api.WebCraftPonderArchive.Entry;
import com.universium.webcraft.api.WebCraftPreviewPack;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PonderRoutes implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String JSON = "application/json; charset=utf-8";
    private static final int MAX_QUERY_CHARS = 512;
    private static final int MAX_TEXTURE_SIDE = 8_192;
    private static final byte[] PNG_HEADER = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82};
    private static final Response NOT_READY = Response.error(503, "ponder_unavailable");
    private static final Response MISSING_EXPORT = Response.error(404, "ponder_export_missing");
    private static final Response MISSING_ASSET = Response.error(404, "ponder_asset_not_found");
    private static final Response INVALID_ASSET = Response.error(422, "ponder_asset_invalid");
    private final WebCraftPreviewPack pack;
    private boolean started;
    private Loaded loaded;

    PonderRoutes(WebCraftPreviewPack pack) {
        this.pack = pack;
    }

    synchronized void start() {
        started = true;
    }

    Response index(String rawQuery) {
        if (rawQuery != null) return Response.error(400, "unsupported_query");
        return current().index;
    }

    Response asset(String rawQuery) {
        String path = assetPath(rawQuery);
        Entry kind = path == null ? null : Entry.asset(path);
        if (kind == null) return Response.error(400, "invalid_ponder_path");
        var state = current();
        var catalog = state.catalog;
        if (catalog == null) return state.index;
        try {
            return catalog.asset(kind, path);
        } catch (IOException exception) {
            return INVALID_ASSET;
        } catch (IllegalStateException exception) {
            return NOT_READY; // The listener is closing with this world.
        }
    }

    private static String assetPath(String query) {
        if (query == null || query.length() > MAX_QUERY_CHARS || !query.startsWith("path=") || query.indexOf('&') >= 0) {
            return null;
        }
        try {
            return URLDecoder.decode(query.substring("path=".length()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private synchronized Loaded current() {
        if (!started) return new Loaded(null, NOT_READY);
        if (loaded == null) loaded = load();
        return loaded;
    }

    private Loaded load() {
        try {
            var archive = pack.openPonder();
            boolean opened = false;
            try {
                byte[] index = archive.read(Entry.INDEX, WebCraftPonderArchive.INDEX_PATH);
                if (index == null) return new Loaded(null, MISSING_EXPORT);
                var catalog = new Catalog(archive, PonderDocuments.manifests(index), new ConcurrentHashMap<>());
                opened = true;
                return new Loaded(catalog, Response.bytes(index, JSON));
            } finally {
                if (!opened) archive.close();
            }
        } catch (NoSuchFileException exception) {
            return new Loaded(null, MISSING_EXPORT);
        } catch (IOException exception) {
            LOGGER.warn("Could not open WebCraft Ponder export", exception);
            return new Loaded(null, Response.error(422, "ponder_export_invalid"));
        }
    }

    @Override
    public synchronized void close() {
        started = false;
        var previous = loaded;
        loaded = null;
        if (previous == null || previous.catalog == null) return;
        try {
            previous.catalog.archive.close();
        } catch (IOException exception) {
            LOGGER.warn("Could not close WebCraft Ponder export", exception);
        }
    }

    private record Loaded(Catalog catalog, Response index) {}

    private record Catalog(WebCraftPonderArchive archive, Set<String> manifests, Map<String, Integer> frameCounts) {
        Response asset(Entry kind, String path) throws IOException {
            switch (kind) {
                case MANIFEST -> {
                    if (!manifests.contains(path)) return MISSING_ASSET;
                }
                case FRAME -> {
                    int cut = path.lastIndexOf("/frames/");
                    String manifest = path.substring(0, cut) + "/scene.json";
                    int tick = Integer.parseInt(path.substring(cut + "/frames/".length(), path.length() - ".json".length()));
                    if (!manifests.contains(manifest) || tick >= frameCount(manifest)) return MISSING_ASSET;
                }
                default -> {}
            }

            byte[] bytes = archive.read(kind, path);
            if (bytes == null) return MISSING_ASSET;
            return switch (kind) {
                case MANIFEST -> {
                    frameCounts.put(path, PonderDocuments.frameCount(bytes));
                    yield Response.bytes(bytes, JSON);
                }
                case FRAME -> PonderDocuments.read(bytes).isJsonObject() ? Response.bytes(bytes, JSON) : INVALID_ASSET;
                case ANIMATION, PICKING -> hashMatches(path, bytes) && PonderDocuments.read(bytes).isJsonArray()
                        ? Response.bytes(bytes, JSON) : INVALID_ASSET;
                case TEXTURE -> hashMatches(path, bytes) && boundedPng(bytes) ? Response.png(bytes) : INVALID_ASSET;
                case MESH -> hashMatches(path, bytes) && bytes.length > 0
                        ? Response.bytes(bytes, "application/octet-stream") : INVALID_ASSET;
                case INDEX -> throw new IllegalArgumentException("Index is not an asset route");
            };
        }

        private int frameCount(String manifest) throws IOException {
            var cached = frameCounts.get(manifest);
            if (cached != null) return cached;
            byte[] bytes = archive.read(Entry.MANIFEST, manifest);
            int count = bytes == null ? 0 : PonderDocuments.frameCount(bytes);
            frameCounts.put(manifest, count);
            return count;
        }
    }

    private static boolean hashMatches(String path, byte[] bytes) {
        String hash = path.substring(path.lastIndexOf('/') + 1, path.lastIndexOf('.'));
        return Hashing.sha256().hashBytes(bytes).toString().equals(hash);
    }

    private static boolean boundedPng(byte[] bytes) {
        if (bytes.length < 24 || !Arrays.equals(PNG_HEADER, 0, PNG_HEADER.length, bytes, 0, PNG_HEADER.length)) {
            return false;
        }
        var header = ByteBuffer.wrap(bytes);
        int width = header.getInt(16);
        int height = header.getInt(20);
        return width > 0 && width <= MAX_TEXTURE_SIDE && height > 0 && height <= MAX_TEXTURE_SIDE;
    }
}
