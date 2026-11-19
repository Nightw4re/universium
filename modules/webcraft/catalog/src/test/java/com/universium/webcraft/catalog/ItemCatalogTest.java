package com.universium.webcraft.catalog;

import com.universium.webcraft.api.WebCraftPreviewPack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ItemCatalogTest {
    @Test
    void listsServerItemsAndServesOnlyIndexedCatalogImages(@TempDir Path directory) throws IOException {
        var archive = directory.resolve("previews.zip");
        String key = "a".repeat(64);
        String unlistedKey = "b".repeat(64);
        String variantKey = "c".repeat(64);
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", png);
        byte[] animation = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHe"
                + "AAAACGFjVEwAAAACAAAAAPONk3AAAAAaZmNUTAAAAAAAAABAAAAAQAAAAAAAAAAAADID6AAA7ceVQwAAADJJREFUeJ"
                + "ztxjERADAIBLA3gOea7lyWIoK7ZEoC8N1Tbw4AAAAAAAAAAAAAAAAAAGSTBvciAoIhL5cdAAAAGmZjVEwAAAABAAAA"
                + "AgAAAAIAAAADAAAAAwCWA+gAAADYGmgAAAAYZmRBVAAAAAJ4nGNgYGBgkGv4+h9EgwEAHBUCk0LX3NEAAAAASUVORK5CYII=");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            write(zip, "format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.UTF_8));
            write(zip, "catalog.json", ("""
                    [{"id":"sample:machine","name":"Localized machine","previewKey":"%s","canGlint":true,
                      "variants":[{"id":"color/red","previewKey":"%s",
                        "options":[{"axis":"color","value":"red","label":"Red"}]}]},
                     {"id":"sample:client_only","name":"Unregistered item","previewKey":"%s"},
                     {"id":"minecraft:oak_stairs","previewKey":"../../not-an-icon"}]
                    """).formatted(key, variantKey, unlistedKey).getBytes(StandardCharsets.UTF_8));
            write(zip, "previews/" + key + ".png", png.toByteArray());
            write(zip, "previews/" + unlistedKey + ".png", png.toByteArray());
            write(zip, "previews/" + variantKey + ".png", png.toByteArray());
            write(zip, "animations/" + key + ".png", animation);
            write(zip, "animations/" + variantKey + ".png", png.toByteArray());
            write(zip, "animations/" + unlistedKey + ".png", animation);
            write(zip, "effects.json", "{\"glint\":{\"blend\":\"src_color_add\"}}".getBytes(StandardCharsets.UTF_8));
        }
        var catalog = new ItemCatalog(Set.of(ResourceLocation.parse("sample:machine"), ResourceLocation.parse("minecraft:oak_stairs")),
                new WebCraftPreviewPack(archive));
        var items = catalog.items(null).body().getAsJsonObject().getAsJsonArray("items");
        assertEquals(2, items.size());
        assertEquals("Oak Stairs", items.get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(items.get(0).getAsJsonObject().get("hasPreview").getAsBoolean());
        assertEquals("Localized machine", items.get(1).getAsJsonObject().get("name").getAsString());
        assertTrue(items.get(1).getAsJsonObject().get("hasPreview").getAsBoolean());
        assertArrayEquals(png.toByteArray(), catalog.icon("id=sample%3Amachine").image());
        assertArrayEquals(animation, catalog.icon("id=sample%3Amachine&animated=1").image());
        assertEquals(404, catalog.icon("id=sample:client_only&animated=1").status());
        assertEquals(404, catalog.icon("id=sample:client_only").status());
        assertEquals(404, catalog.icon("id=minecraft:oak_stairs").status());
        assertEquals(400, catalog.icon("id=sample:machine&key=" + unlistedKey).status());
        assertArrayEquals(png.toByteArray(), catalog.icon("id=sample:machine&variant=color%2Fred").image());
        assertArrayEquals(png.toByteArray(), catalog.icon("id=sample:machine&variant=color%2Fred&animated=1").image());
        assertEquals(400, catalog.icon("id=sample:machine&animated=0").status());
        assertEquals(404, catalog.icon("id=sample:machine&variant=color/blue").status());
        assertEquals(404, catalog.icon("id=minecraft:oak_stairs&variant=color/red").status());
        var detail = catalog.detail("id=sample:machine").body().getAsJsonObject();
        assertTrue(detail.get("canGlint").getAsBoolean());
        var variant = detail.getAsJsonArray("variants").get(0).getAsJsonObject();
        assertEquals("color/red", variant.get("variantId").getAsString());
        assertEquals("sample:machine", variant.get("id").getAsString());
        assertFalse(variant.has("previewKey"));
        assertTrue(catalog.effects(null).body().getAsJsonObject().has("glint"));
        assertEquals(400, catalog.items("all=true").status());
    }

    private static void write(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    @Test
    void servesACombinedPngForTwoIndependentlySelectedBreederSlots(@TempDir Path directory) throws IOException {
        var id = ResourceLocation.parse("easy_villagers:breeder");
        var archive = directory.resolve("previews.zip");
        var base = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        var first = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        var second = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int glass = 0x80888888;
        int blue = 0xff0000ff;
        int orange = 0xffff6600;
        for (var frame : new BufferedImage[]{base, first, second}) {
            frame.setRGB(4, 1, glass);
        }
        first.setRGB(1, 1, blue);
        first.setRGB(2, 1, blue);
        second.setRGB(2, 1, orange);
        second.setRGB(3, 1, orange);
        String emptyKey = "a".repeat(64);
        String firstKey = "b".repeat(64);
        String secondKey = "c".repeat(64);
        String firstId = "villager_1/minecraft:plains/minecraft:farmer";
        String secondId = "villager_2/minecraft:desert/minecraft:librarian";
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            write(zip, "format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.UTF_8));
            write(zip, "catalog.json", """
                    [{"id":"easy_villagers:breeder","previewKey":"%s","variants":[
                      {"id":"%s","previewKey":"%s","options":[{"axis":"biome","value":"minecraft:plains","label":"Plains"}]},
                      {"id":"%s","previewKey":"%s","options":[{"axis":"biome","value":"minecraft:desert","label":"Desert"}]}]}]
                    """.formatted(emptyKey, firstId, firstKey, secondId, secondKey).getBytes(StandardCharsets.UTF_8));
            write(zip, "previews/" + emptyKey + ".png", png(base));
            write(zip, "previews/" + firstKey + ".png", png(first));
            write(zip, "previews/" + secondKey + ".png", png(second));
        }

        var catalog = new ItemCatalog(Set.of(id), new WebCraftPreviewPack(archive));
        String query = "id=" + id + "&variant=";
        assertArrayEquals(png(first), catalog.icon(query + firstId).image());
        assertArrayEquals(png(second), catalog.icon(query + secondId).image());
        var response = catalog.icon(query + firstId + "%2B" + secondId);
        assertEquals(200, response.status());
        assertArrayEquals(response.image(), catalog.icon(query + firstId + "%2B" + secondId + "&animated=1").image());
        var combined = ImageIO.read(new ByteArrayInputStream(response.image()));
        assertEquals(blue, combined.getRGB(1, 1));
        assertEquals(blue, combined.getRGB(2, 1));
        assertEquals(orange, combined.getRGB(3, 1));
        assertEquals(glass, combined.getRGB(4, 1));
        assertEquals(0, combined.getRGB(5, 1));
        assertEquals(400, catalog.icon(query + secondId + "%2B" + firstId).status());
        assertEquals(400, catalog.icon(query + firstId + "%2B" + firstId).status());
        assertEquals(404, catalog.icon(query + firstId + "%2Bvillager_2/missing").status());
    }

    private static byte[] png(BufferedImage frame) throws IOException {
        var png = new ByteArrayOutputStream();
        ImageIO.write(frame, "png", png);
        return png.toByteArray();
    }

    @Test
    void exposesTheFluixCableAsTheDefaultMemberOfTheAe2ColorFamily(@TempDir Path directory) throws IOException {
        var registered = new HashSet<ResourceLocation>();
        String fluix = "ae2:fluix_glass_cable";
        String red = "ae2:red_glass_cable";
        registered.add(ResourceLocation.parse(fluix));
        for (var color : DyeColor.values()) {
            registered.add(ResourceLocation.parse("ae2:" + color.getName() + "_glass_cable"));
        }
        var archive = directory.resolve("previews.zip");
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", png);
        String defaultKey = "a".repeat(64);
        String redKey = "b".repeat(64);
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            write(zip, "format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.UTF_8));
            write(zip, "catalog.json", ("""
                    [{"id":"%s","previewKey":"%s"},{"id":"%s","previewKey":"%s"}]
                    """).formatted(fluix, defaultKey, red, redKey).getBytes(StandardCharsets.UTF_8));
            write(zip, "previews/" + defaultKey + ".png", png.toByteArray());
            write(zip, "previews/" + redKey + ".png", png.toByteArray());
        }

        var catalog = new ItemCatalog(registered, new WebCraftPreviewPack(archive));
        var defaultDetail = catalog.detail("id=" + fluix).body().getAsJsonObject();
        assertTrue(defaultDetail.get("defaultColor").getAsBoolean());
        var colors = catalog.detail("id=" + red).body().getAsJsonObject().getAsJsonArray("variants");
        assertEquals(2, colors.size());
        assertEquals("color/fluix", colors.get(0).getAsJsonObject().get("variantId").getAsString());
        assertEquals(fluix, colors.get(0).getAsJsonObject().get("id").getAsString());
        assertArrayEquals(png.toByteArray(), catalog.icon("id=" + red + "&variant=color/fluix").image());
    }
}
