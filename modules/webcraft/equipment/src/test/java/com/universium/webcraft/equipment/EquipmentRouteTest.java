package com.universium.webcraft.equipment;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class EquipmentRouteTest {
    @Test
    void serializesTypedSlotsAndKeepsItemDetailsWhenAssetsAreMissing(@TempDir Path root) {
        var route = new EquipmentRoute(new EquipmentPreviews(root.resolve("client.jar")));
        Thread httpWorker = Thread.currentThread();
        try (var gameThread = Executors.newSingleThreadExecutor()) {
            var response = route.read(gameThread, () -> {
                assertNotEquals(httpWorker, Thread.currentThread());
                return sampleEquipment();
            });
            assertEquals(200, response.status());
            JsonObject data = response.body().getAsJsonObject();
            assertEquals("TestViewer", data.get("player").getAsString());
            assertEquals(6, data.getAsJsonArray("slots").size());
            assertTrue(slot(data, "chest").get("item").isJsonNull());
            assertEquals("minecraft:diamond_sword", item(data, "mainhand").get("id").getAsString());
            assertEquals(8, item(data, "offhand").get("count").getAsInt());
            assertEquals("assets_unavailable", preview(data, "mainhand").get("reason").getAsString());
            assertEquals("unsupported_item_state", preview(data, "feet").get("reason").getAsString());
            var offline = route.read(gameThread, () -> null);
            assertEquals(404, offline.status());
            assertEquals("player_offline", offline.body().getAsJsonObject().get("error").getAsString());
        }
    }

    @Test
    void resolvesRealVanillaSpritesAndCubeFacesWhenClientArchiveIsSupplied() throws IOException {
        String archive = System.getProperty("webcraft.testAssets", "");
        assumeTrue(!archive.isEmpty(), "Run with -PwebcraftTestAssets to check official client assets");
        Path client = Path.of(archive);
        var route = new EquipmentRoute(new EquipmentPreviews(client));
        JsonObject data = route.read(Runnable::run, EquipmentRouteTest::sampleEquipment).body().getAsJsonObject();
        var sword = preview(data, "mainhand");
        var helmet = preview(data, "head");
        var table = preview(data, "offhand");
        assertEquals("sprite", sword.get("type").getAsString());
        assertEquals("sprite", helmet.get("type").getAsString());
        assertEquals("cube", table.get("type").getAsString());

        try (var jar = new ZipFile(client.toFile())) {
            assertTexture(jar, "item/diamond_sword", sword.get("png"));
            assertTexture(jar, "item/iron_helmet", helmet.get("png"));
            var faces = table.getAsJsonObject("faces");
            assertTexture(jar, "block/crafting_table_top", faces.get("up"));
            assertTexture(jar, "block/crafting_table_front", faces.get("north"));
            assertTexture(jar, "block/crafting_table_side", faces.get("east"));
        }
        JsonObject special = route.read(Runnable::run,
                () -> EquipmentRoute.capture("TestViewer", slot -> slot == EquipmentSlot.OFFHAND
                        ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY)).body().getAsJsonObject();
        assertEquals("unsupported_model", preview(special, "offhand").get("reason").getAsString());

        ItemStack red = dyedChestplate(0xAA1122);
        ItemStack blue = dyedChestplate(0x2233BB);
        var armor = route.read(Runnable::run, () -> EquipmentRoute.capture("TestViewer", slot -> switch (slot) {
            case CHEST -> red;
            case OFFHAND -> blue;
            default -> ItemStack.EMPTY;
        })).body().getAsJsonObject();
        var redPreview = preview(armor, "chest");
        var bluePreview = preview(armor, "offhand");
        assertEquals("layered", redPreview.get("type").getAsString());
        assertEquals(0xAA1122, redPreview.get("tint").getAsInt());
        assertEquals(0x2233BB, bluePreview.get("tint").getAsInt());
        assertEquals(redPreview.get("base"), bluePreview.get("base"));
        assertEquals(redPreview.get("overlay"), bluePreview.get("overlay"));
        try (var jar = new ZipFile(client.toFile())) {
            assertTexture(jar, "item/leather_chestplate", redPreview.get("base"));
            assertTexture(jar, "item/leather_chestplate_overlay", redPreview.get("overlay"));
        }
    }

    @Test
    void usesAnExportedPreviewForTheExactVariantEvenWithoutVanillaAssets(@TempDir Path root) throws IOException {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var red = dyedChestplate(0xAA1122);
        var blue = dyedChestplate(0x2233BB);
        String redKey = EquipmentPreviewPack.key(red, registries);
        String blueKey = EquipmentPreviewPack.key(blue, registries);
        assertNotEquals(redKey, blueKey);
        assertEquals(redKey, EquipmentPreviewPack.key(red.copy(), registries));

        var icon = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        icon.setRGB(32, 32, 0xFFFF2200);
        var png = new ByteArrayOutputStream();
        ImageIO.write(icon, "png", png);
        try (var zip = new ZipOutputStream(Files.newOutputStream(root.resolve("previews.zip")))) {
            zip.putNextEntry(new ZipEntry("format.txt"));
            zip.write("webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("previews/" + redKey + ".png"));
            zip.write(png.toByteArray());
            zip.closeEntry();
        }
        var route = new EquipmentRoute(new EquipmentPreviews(root.resolve("client.jar")));
        var response = route.read(Runnable::run, () -> EquipmentRoute.capture("TestViewer", slot -> switch (slot) {
            case CHEST -> red;
            case OFFHAND -> blue;
            default -> ItemStack.EMPTY;
        }, stack -> EquipmentPreviewPack.key(stack, registries))).body().getAsJsonObject();
        var fromPack = preview(response, "chest");
        assertEquals("sprite", fromPack.get("type").getAsString());
        assertArrayEquals(png.toByteArray(), Base64.getDecoder().decode(fromPack.get("png").getAsString()));
        assertEquals("assets_unavailable", preview(response, "offhand").get("reason").getAsString());
    }

    private static ItemStack dyedChestplate(int rgb) {
        var stack = new ItemStack(Items.LEATHER_CHESTPLATE);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb, true));
        return stack;
    }

    private static EquipmentRoute.Snapshot sampleEquipment() {
        var boots = new ItemStack(Items.IRON_BOOTS);
        boots.set(DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(1));
        var equipped = Map.of(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET),
                EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD),
                EquipmentSlot.OFFHAND, new ItemStack(Items.CRAFTING_TABLE, 8), EquipmentSlot.FEET, boots);
        return EquipmentRoute.capture("TestViewer", slot -> equipped.getOrDefault(slot, ItemStack.EMPTY));
    }

    private static void assertTexture(ZipFile jar, String texture, JsonElement encoded) throws IOException {
        try (var stream = jar.getInputStream(jar.getEntry("assets/minecraft/textures/" + texture + ".png"))) {
            assertArrayEquals(stream.readAllBytes(), Base64.getDecoder().decode(encoded.getAsString()));
        }
    }

    private static JsonObject slot(JsonObject data, String name) {
        for (JsonElement entry : data.getAsJsonArray("slots")) {
            var slot = entry.getAsJsonObject();
            if (name.equals(slot.get("slot").getAsString())) {
                return slot;
            }
        }
        throw new AssertionError("Missing equipment slot " + name);
    }

    private static JsonObject item(JsonObject data, String name) {
        return slot(data, name).getAsJsonObject("item");
    }

    private static JsonObject preview(JsonObject data, String name) {
        return item(data, name).getAsJsonObject("preview");
    }
}
