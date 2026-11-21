package com.universium.webcraft.ponder;

import com.google.common.hash.Hashing;
import com.universium.webcraft.api.WebCraftPreviewPack;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class PonderRoutesTest {
    private static final String SCENE = "ponder/scenes/" + "a".repeat(64);
    private static final String FOREIGN_SCENE = "ponder/scenes/" + "b".repeat(64);
    private static final String MANIFEST = SCENE + "/scene.json";
    private static final String FRAME = SCENE + "/frames/0.json";
    private static final String INDEX = "ponder/index.json";
    private static final String JSON = "application/json; charset=utf-8";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aPscAAAAASUVORK5CYII=");

    @Test
    void preservesArchiveBytesAndContentTypes(@TempDir Path temp) throws Exception {
        var entries = export(MANIFEST);
        byte[] mesh = {0, 1, 2, 3};
        byte[] picking = bytes("[{\"blocks\":[]}]\n");
        byte[] animation = bytes("[]");
        String meshPath = hashed("meshes", ".bin", mesh);
        String pickingPath = hashed("picking", ".json", picking);
        String animationPath = hashed("textures", ".json", animation);
        String texturePath = hashed("textures", ".png", PNG);
        entries.put(meshPath, mesh);
        entries.put(pickingPath, picking);
        entries.put(animationPath, animation);
        entries.put(texturePath, PNG);
        try (var routes = routes(temp, entries)) {
            assertContent(routes.index(null), JSON, entries.get(INDEX));
            assertContent(routes.asset(query(MANIFEST)), JSON, entries.get(MANIFEST));
            assertContent(routes.asset(query(FRAME)), JSON, entries.get(FRAME));
            assertContent(routes.asset(query(meshPath)), "application/octet-stream", mesh);
            assertContent(routes.asset(query(pickingPath)), JSON, picking);
            assertContent(routes.asset(query(animationPath)), JSON, animation);
            assertContent(routes.asset(query(texturePath)), "image/png", PNG);
            assertError(routes.index("path=" + FRAME), 400, "unsupported_query");
        }
    }

    @Test
    void rejectsPrivatePathsUnlistedScenesAndOutOfRangeFramesEvenWhenPresent(@TempDir Path temp) throws Exception {
        var entries = export(MANIFEST);
        entries.put(FOREIGN_SCENE + "/scene.json", bytes("{\"frameCount\":100}"));
        entries.put(FOREIGN_SCENE + "/frames/0.json", bytes("{}"));
        entries.put(SCENE + "/frames/1.json", bytes("{}"));
        String privatePreview = "previews/" + "a".repeat(64) + ".png";
        entries.put(privatePreview, PNG);
        entries.put("textures/" + "a".repeat(64) + ".png", PNG);
        try (var routes = routes(temp, entries)) {
            for (String attempted : new String[]{privatePreview, "textures/" + "a".repeat(64) + ".png",
                    INDEX, "ponder/meshes/" + "a".repeat(64) + ".json", "ponder/textures/" + "a".repeat(64) + ".webp",
                    SCENE + "/frames/00.json", SCENE + "/frames/-1.json", "ponder/../" + privatePreview,
                    SCENE + "/frames/../scene.json", SCENE + "\\scene.json", "/" + FRAME}) {
                assertError(routes.asset(query(attempted)), 400, "invalid_ponder_path");
            }
            assertError(routes.asset("path=%252e%252e%2F" + privatePreview), 400, "invalid_ponder_path");
            assertError(routes.asset(query(FRAME) + "&path=" + FRAME), 400, "invalid_ponder_path");
            assertError(routes.asset("path=%GG"), 400, "invalid_ponder_path");
            assertError(routes.asset("path=" + "a".repeat(512)), 400, "invalid_ponder_path");
            assertError(routes.asset(query(FOREIGN_SCENE + "/scene.json")), 404, "ponder_asset_not_found");
            assertError(routes.asset(query(FOREIGN_SCENE + "/frames/0.json")), 404, "ponder_asset_not_found");
            assertError(routes.asset(query(SCENE + "/frames/1.json")), 404, "ponder_asset_not_found");
        }
    }

    @Test
    void reportsMissingOrInvalidExportsAndCanReadAgainAfterReopeningTheWorld(@TempDir Path temp) throws Exception {
        var file = temp.resolve("previews.zip");
        try (var routes = new PonderRoutes(new WebCraftPreviewPack(file))) {
            assertError(routes.index(null), 503, "ponder_unavailable");
            routes.start();
            assertError(routes.index(null), 404, "ponder_export_missing");
            routes.close();
            write(file, Map.of("format.txt", bytes("webcraft-previews-v1/1.21.1\n")));
            routes.start();
            assertError(routes.index(null), 404, "ponder_export_missing");
            routes.close();
            write(file, export("previews/private.json"));
            routes.start();
            assertError(routes.index(null), 422, "ponder_export_invalid");
            routes.close();
            write(file, export(MANIFEST));
            routes.start();
            assertEquals(200, routes.index(null).status());
            routes.close();
            Files.delete(file);
        }
    }

    @Test
    void rejectsInvalidManifestBoundsOversizedEntriesAndIncorrectHashes(@TempDir Path temp) throws Exception {
        var entries = export(MANIFEST);
        entries.put(MANIFEST, bytes("{\"frameCount\":12001}"));
        try (var routes = routes(temp, entries)) {
            assertError(routes.asset(query(FRAME)), 422, "ponder_asset_invalid");
            assertError(routes.asset(query(MANIFEST)), 422, "ponder_asset_invalid");
        }
        entries.put(MANIFEST, bytes("{\"frameCount\":1}"));
        entries.put(FRAME, new byte[2 * 1024 * 1024 + 1]);
        String wrongHash = "ponder/meshes/" + "f".repeat(64) + ".bin";
        entries.put(wrongHash, new byte[]{1, 2, 3});
        byte[] invalidPng = bytes("not a PNG");
        String invalidTexture = hashed("textures", ".png", invalidPng);
        entries.put(invalidTexture, invalidPng);
        byte[] invalidJson = bytes("[] []");
        String invalidPicking = hashed("picking", ".json", invalidJson);
        entries.put(invalidPicking, invalidJson);
        try (var routes = routes(temp, entries)) {
            assertError(routes.asset(query(FRAME)), 422, "ponder_asset_invalid");
            assertError(routes.asset(query(wrongHash)), 422, "ponder_asset_invalid");
            assertError(routes.asset(query(invalidTexture)), 422, "ponder_asset_invalid");
            assertError(routes.asset(query(invalidPicking)), 422, "ponder_asset_invalid");
        }
    }

    private static void assertContent(Response response, String contentType, byte[] original) {
        assertEquals(200, response.status());
        assertEquals(contentType, response.imageContentType());
        assertArrayEquals(original, response.image());
    }

    private static void assertError(Response response, int status, String code) {
        assertEquals(status, response.status());
        assertEquals(code, response.body().getAsJsonObject().get("error").getAsString());
    }

    private static String query(String path) {
        return "path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    private static String hashed(String directory, String extension, byte[] bytes) {
        return "ponder/" + directory + "/" + Hashing.sha256().hashBytes(bytes) + extension;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static HashMap<String, byte[]> export(String advertised) {
        return new HashMap<>(Map.of(
                "format.txt", bytes("webcraft-previews-v1/1.21.1\n"),
                INDEX, bytes("""
                        {
                          "version": 1, "locale": "en_us",
                          "scenes": [{"id":"test:pump","title":"Pump","schematic":"test:pump","manifest":"%s","items":["test:pump"]}]
                        }
                        """.formatted(advertised)),
                MANIFEST, bytes("{ \"frameCount\": 1 }\n"),
                FRAME, bytes("{ \"scene\": [], \"overlay\": [] }\n")));
    }

    private static PonderRoutes routes(Path temp, Map<String, byte[]> entries) throws IOException {
        var file = temp.resolve("previews.zip");
        write(file, entries);
        var routes = new PonderRoutes(new WebCraftPreviewPack(file));
        routes.start();
        return routes;
    }

    private static void write(Path file, Map<String, byte[]> entries) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }
}
