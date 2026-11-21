package com.universium.webcraft.catalog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    void listsOnlyExportedServerItemsAndServesOnlyIndexedCatalogImages(@TempDir Path directory) throws IOException {
        var archive = directory.resolve("previews.zip");
        String key = "a".repeat(64);
        String unlistedKey = "b".repeat(64);
        String variantKey = "c".repeat(64);
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", png);
        byte[] webp = Base64.getDecoder().decode(
                "UklGRigAAABXRUJQVlA4TBwAAAAvP8APEA8wVSMyqgMY8CCTtkn9i9/OiP6nCzIc");
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
            write(zip, "previews/" + key + ".webp", webp);
            write(zip, "previews/" + unlistedKey + ".png", png.toByteArray());
            write(zip, "previews/" + variantKey + ".png", png.toByteArray());
            write(zip, "previews/" + variantKey + ".webp", new byte[]{1, 2, 3});
            write(zip, "animations/" + key + ".png", animation);
            write(zip, "animations/" + variantKey + ".png", png.toByteArray());
            write(zip, "animations/" + unlistedKey + ".png", animation);
            write(zip, "effects.json", "{\"glint\":{\"blend\":\"src_color_add\"}}".getBytes(StandardCharsets.UTF_8));
        }
        var catalog = new ItemCatalog(Set.of(ResourceLocation.parse("sample:machine"), ResourceLocation.parse("minecraft:oak_stairs"),
                        ResourceLocation.parse("minecraft:debug_stick")),
                new WebCraftPreviewPack(archive));
        var items = catalog.items(null).body().getAsJsonObject().getAsJsonArray("items");
        assertEquals(2, items.size());
        assertEquals("Oak Stairs", items.get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(items.get(0).getAsJsonObject().get("hasPreview").getAsBoolean());
        assertEquals("Localized machine", items.get(1).getAsJsonObject().get("name").getAsString());
        assertTrue(items.get(1).getAsJsonObject().get("hasPreview").getAsBoolean());
        var still = catalog.icon("id=sample%3Amachine");
        assertEquals("image/webp", still.imageContentType());
        assertArrayEquals(webp, still.image());
        var animated = catalog.icon("id=sample%3Amachine&animated=1");
        assertEquals("image/png", animated.imageContentType());
        assertArrayEquals(animation, animated.image());
        assertEquals(404, catalog.icon("id=sample:client_only&animated=1").status());
        assertEquals(404, catalog.icon("id=sample:client_only").status());
        assertEquals(404, catalog.detail("id=minecraft:debug_stick").status());
        assertEquals(404, catalog.icon("id=minecraft:debug_stick").status());
        assertEquals(404, catalog.icon("id=minecraft:oak_stairs").status());
        assertEquals(400, catalog.icon("id=sample:machine&key=" + unlistedKey).status());
        var legacy = catalog.icon("id=sample:machine&variant=color%2Fred");
        assertEquals("image/png", legacy.imageContentType());
        assertArrayEquals(png.toByteArray(), legacy.image());
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

    @Test
    void servesRecipeIngredientIconsWithoutAddingTheirItemsToTheCatalog(@TempDir Path directory) throws IOException {
        var archive = directory.resolve("previews.zip");
        String key = "b".repeat(64);
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", png);
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            write(zip, "format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.UTF_8));
            write(zip, "catalog.json", "[{\"id\":\"sample:listed\"}]".getBytes(StandardCharsets.UTF_8));
            write(zip, "catalog-data/recipe-item-previews.json", ("""
                    {"sample:ingredient":"%s","sample:client_only":"%s","sample:invalid":"/bad"}
                    """).formatted(key, key).getBytes(StandardCharsets.UTF_8));
            write(zip, "previews/" + key + ".png", png.toByteArray());
        }
        var catalog = new ItemCatalog(Set.of(ResourceLocation.parse("sample:listed"),
                ResourceLocation.parse("sample:ingredient"), ResourceLocation.parse("sample:invalid")),
                new WebCraftPreviewPack(archive));
        var items = catalog.items(null).body().getAsJsonObject().getAsJsonArray("items");
        assertEquals(1, items.size());
        assertEquals("sample:listed", items.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(404, catalog.detail("id=sample:ingredient").status());
        assertArrayEquals(png.toByteArray(), catalog.icon("id=sample:ingredient").image());
        assertArrayEquals(png.toByteArray(), catalog.icon("id=sample:ingredient&animated=1").image());
        assertEquals(404, catalog.icon("id=sample:ingredient&variant=other").status());
        assertEquals(404, catalog.icon("id=sample:client_only").status());
        assertEquals(404, catalog.icon("id=sample:invalid").status());
    }

    @Test
    void servesAnimatedWebpFromAnArchiveWithNoPngAnimation(@TempDir Path directory) throws IOException {
        var archive = directory.resolve("previews.zip");
        var key = "a".repeat(64);
        byte[] webp = Base64.getDecoder().decode("UklGRoQAAABXRUJQVlA4WAoAAAACAAAAPwAAPwAAQU5JTQYAAA"
                + "AAAAAAAABBTk1GKAAAAAAAAAAAAD8AAD8AAGQAAAJWUDhMDwAAAC8/wA8ABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAD8"
                + "AAD8AAGQAAABWUDhMDwAAAC8/wA8ABxDR//4HIqL/AQA=");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            write(zip, "format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.UTF_8));
            write(zip, "catalog.json", ("[{\"id\":\"minecraft:stone\",\"previewKey\":\"" + key + "\"}]")
                    .getBytes(StandardCharsets.UTF_8));
            write(zip, "animations/" + key + ".webp", webp);
        }
        var catalog = new ItemCatalog(Set.of(ResourceLocation.parse("minecraft:stone")), new WebCraftPreviewPack(archive));
        var animated = catalog.icon("id=minecraft:stone&animated=1");
        assertEquals("image/webp", animated.imageContentType());
        assertArrayEquals(webp, animated.image());
    }

    @Test
    void doesNotExpandToTheRegistryWhenTheArchiveIsMissing(@TempDir Path directory) {
        var catalog = new ItemCatalog(Set.of(ResourceLocation.parse("minecraft:stone")),
                new WebCraftPreviewPack(directory.resolve("previews.zip")));
        assertTrue(catalog.items(null).body().getAsJsonObject().getAsJsonArray("items").isEmpty());
        assertEquals(404, catalog.detail("id=minecraft:stone").status());
    }

    @Test
    void readsLargeCatalogsFromModpacks(@TempDir Path directory) throws IOException {
        var archive = directory.resolve("previews.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            write(zip, "format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.UTF_8));
            String json = "[" + " ".repeat(5 * 1024 * 1024) + "{\"id\":\"minecraft:stone\"}]";
            write(zip, "catalog.json", json.getBytes(StandardCharsets.UTF_8));
        }

        var catalog = new ItemCatalog(Set.of(ResourceLocation.parse("minecraft:stone")), new WebCraftPreviewPack(archive));
        assertEquals(200, catalog.detail("id=minecraft:stone").status());
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
        // The WebP frame has two blue pixels and the same glass pixel as the PNG frames.
        byte[] first = Base64.getDecoder().decode(
                "UklGRjQAAABXRUJQVlA4TCgAAAAvP8APEBcwiIMoICi6bzUBQdF1ywXwF8ikbcI0zL/dCdgT0f80G18H");
        var second = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int glass = 0x80888888;
        int blue = 0xff0000ff;
        int orange = 0xffff6600;
        for (var frame : new BufferedImage[]{base, second}) {
            frame.setRGB(4, 1, glass);
        }
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
            write(zip, "previews/" + firstKey + ".webp", first);
            write(zip, "previews/" + secondKey + ".png", png(second));
        }

        var catalog = new ItemCatalog(Set.of(id), new WebCraftPreviewPack(archive));
        String query = "id=" + id + "&variant=";
        assertArrayEquals(first, catalog.icon(query + firstId).image());
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
        String blue = "ae2:blue_glass_cable";
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
            var catalogItems = JsonParser.parseString(("""
                    [{"id":"%s","previewKey":"%s"},{"id":"%s","previewKey":"%s"},{"id":"%s","hasPublicData":true}]
                    """).formatted(fluix, defaultKey, red, redKey, blue)).getAsJsonArray();
            var previewExamples = Set.of(fluix, red, blue);
            for (var id : registered) {
                String member = id.toString();
                if (previewExamples.contains(member)) continue;
                var row = new JsonObject();
                row.addProperty("id", member);
                catalogItems.add(row);
            }
            write(zip, "catalog.json", catalogItems.toString().getBytes(StandardCharsets.UTF_8));
            write(zip, "previews/" + defaultKey + ".png", png.toByteArray());
            write(zip, "previews/" + redKey + ".png", png.toByteArray());
            write(zip, "catalog-data/tooltips/ae2/fluix_glass_cable@.json", """
                    {"version":1,"locale":"en_us","hasGraphicElements":false,"lines":[[{"text":"ME Glass Cable"}]]}
                    """.getBytes(StandardCharsets.UTF_8));
        }

        var catalog = new ItemCatalog(registered, new WebCraftPreviewPack(archive));
        var defaultDetail = catalog.detail("id=" + fluix).body().getAsJsonObject();
        assertTrue(defaultDetail.get("defaultColor").getAsBoolean());
        var colors = catalog.detail("id=" + red).body().getAsJsonObject().getAsJsonArray("variants");
        assertEquals(3, colors.size());
        assertEquals("color/fluix", colors.get(0).getAsJsonObject().get("variantId").getAsString());
        assertEquals(fluix, colors.get(0).getAsJsonObject().get("id").getAsString());
        assertArrayEquals(png.toByteArray(), catalog.icon("id=" + red + "&variant=color/fluix").image());
        var tooltip = catalog.tooltip("id=" + red + "&variant=color/fluix").body().getAsJsonObject();
        assertEquals(red, tooltip.get("catalogId").getAsString());
        assertEquals(fluix, tooltip.get("itemId").getAsString());
        assertEquals("color/fluix", tooltip.get("variantId").getAsString());
        var blueOption = colors.asList().stream().map(value -> value.getAsJsonObject())
                .filter(value -> value.get("id").getAsString().equals(blue)).findFirst().orElseThrow();
        assertFalse(blueOption.get("hasPreview").getAsBoolean());
    }
}
