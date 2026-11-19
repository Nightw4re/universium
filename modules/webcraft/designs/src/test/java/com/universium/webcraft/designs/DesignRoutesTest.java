package com.universium.webcraft.designs;

import com.universium.webcraft.api.WebCraftPreviewPack;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class DesignRoutesTest {
    @ParameterizedTest
    @ValueSource(strings = {"webcraft_designs", "webcraft_next_designs"})
    void servesOnlyTheTextureHashesDeclaredByTheSelectedModule(String packNamespace, @TempDir Path directory) throws IOException {
        var archive = directory.resolve("previews.zip");
        String banner = "a".repeat(64);
        String armor = "b".repeat(64);
        String oversized = "c".repeat(64);
        byte[] bannerPng = image(64, 64);
        byte[] armorPng = image(64, 32);
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "format.txt", "webcraft-previews-v1/1.21.1\n");
            put(zip, "designs/" + packNamespace + "/banners.json", """
                    {"version":1,"textures":["%s","%s"],"base":"%s"}
                    """.formatted(banner, oversized, banner));
            put(zip, "designs/" + packNamespace + "/armor.json", """
                    {"version":1,"textures":["%s"],"projection":{"width":24}}
                    """.formatted(armor));
            put(zip, "designs/" + packNamespace + "/armor_geometry.json", """
                    {"version":1,"textures":[],"meshes":{"head":[0,1,2]}}
                    """);
            put(zip, "textures/" + banner + ".png", bannerPng);
            put(zip, "textures/" + armor + ".png", armorPng);
            put(zip, "textures/" + oversized + ".png", image(1025, 32));
        }

        var routes = new DesignRoutes(new WebCraftPreviewPack(archive));
        assertEquals(404, routes.data(null).status());
        routes.load(ResourceLocation.parse("webcraft_designs:banners"));
        assertEquals(banner, routes.data(null).body().getAsJsonObject().get("base").getAsString());
        assertFalse(routes.data(null).body().getAsJsonObject().has("textures"));
        assertArrayEquals(bannerPng, routes.texture("hash=" + banner).image());
        assertEquals(404, routes.texture("hash=" + armor).status());
        assertEquals(404, routes.texture("hash=" + oversized).status());
        assertEquals(400, routes.texture("hash=../catalog.json").status());
        assertEquals(400, routes.data("version=1").status());

        routes.load(ResourceLocation.parse("webcraft_designs:armor"));
        assertArrayEquals(armorPng, routes.texture("hash=" + armor).image());
        assertEquals(404, routes.texture("hash=" + banner).status());
        assertFalse(routes.data(null).body().getAsJsonObject().has("meshes"));
        routes.load(ResourceLocation.parse("webcraft_designs:armor_geometry"));
        assertEquals(3, routes.data(null).body().getAsJsonObject().getAsJsonObject("meshes").getAsJsonArray("head").size());
        assertEquals(404, routes.texture("hash=" + armor).status());
        routes.load(ResourceLocation.parse("webcraft_designs:unknown"));
        assertEquals(404, routes.data(null).status());
        assertEquals(404, routes.texture("hash=" + armor).status());
    }

    private static byte[] image(int width, int height) throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }

    private static void put(ZipOutputStream zip, String path, String contents) throws IOException {
        put(zip, path, contents.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(ZipOutputStream zip, String path, byte[] contents) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(contents);
        zip.closeEntry();
    }
}
