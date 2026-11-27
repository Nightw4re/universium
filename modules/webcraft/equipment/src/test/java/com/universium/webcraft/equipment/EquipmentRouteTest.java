package com.universium.webcraft.equipment;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterial;
import net.minecraft.world.item.armortrim.TrimMaterials;
import net.minecraft.world.item.armortrim.TrimPattern;
import net.minecraft.world.item.armortrim.TrimPatterns;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.junit.jupiter.api.Test;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            assertFalse(data.has("selectedSlot"));
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
    void serializesTheWornArmorDyeAndRegisteredTrim(@TempDir Path root) {
        var material = Holder.Reference.createStandAlone(new HolderOwner<TrimMaterial>() {}, TrimMaterials.GOLD);
        var pattern = Holder.Reference.createStandAlone(new HolderOwner<TrimPattern>() {}, TrimPatterns.SENTRY);
        var chestplate = dyedChestplate(0xAA1122);
        chestplate.set(DataComponents.TRIM, new ArmorTrim(material, pattern));
        var route = new EquipmentRoute(new EquipmentPreviews(root.resolve("client.jar")));

        var response = route.read(Runnable::run, () -> EquipmentRoute.capture("TestViewer", slot -> switch (slot) {
            case CHEST, MAINHAND -> chestplate;
            default -> ItemStack.EMPTY;
        }, stack -> null));

        var data = response.body().getAsJsonObject();
        var worn = item(data, "chest");
        assertEquals(0xAA1122, worn.get("dye").getAsInt());
        assertEquals("minecraft:gold", worn.getAsJsonObject("trim").get("material").getAsString());
        assertEquals("minecraft:sentry", worn.getAsJsonObject("trim").get("pattern").getAsString());
        assertFalse(item(data, "mainhand").has("dye"));
        assertFalse(item(data, "mainhand").has("trim"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8})
    void serializesAllInventoryPositionsAndTheActiveHotbarSlot(int selectedSlot, @TempDir Path root) {
        var route = new EquipmentRoute(new EquipmentPreviews(root.resolve("client.jar")));
        var response = route.read(Runnable::run, () -> EquipmentRoute.captureInventory("TestViewer", selectedSlot,
                index -> index == 35 ? new ItemStack(Items.DIAMOND, 3) : ItemStack.EMPTY), false);
        assertEquals(200, response.status());
        var data = response.body().getAsJsonObject();
        assertEquals("TestViewer", data.get("player").getAsString());
        assertTrue(data.getAsJsonPrimitive("selectedSlot").isNumber());
        assertEquals(selectedSlot, data.get("selectedSlot").getAsInt());
        var slots = data.getAsJsonArray("slots");
        assertEquals(36, slots.size());
        for (int index = 0; index < slots.size(); index++) {
            var entry = slots.get(index).getAsJsonObject();
            assertTrue(entry.getAsJsonPrimitive("slot").isString());
            assertEquals(Integer.toString(index), entry.get("slot").getAsString());
            if (index < 35) {
                assertTrue(entry.get("item").isJsonNull());
            }
        }
        var item = item(data, "35");
        assertEquals("minecraft:diamond", item.get("id").getAsString());
        assertEquals(new ItemStack(Items.DIAMOND).getHoverName().getString(), item.get("name").getAsString());
        assertEquals(3, item.get("count").getAsInt());
        assertFalse(item.has("preview"));
    }

    @Test
    void capturesDetailsFromTheActualStackWithoutAnExport(@TempDir Path root) {
        var sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Named sword"));
        sword.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Actual stack lore").withStyle(ChatFormatting.AQUA))));
        sword.setDamageValue(sword.getMaxDamage() / 2);
        var route = new EquipmentRoute(new EquipmentPreviews(root.resolve("client.jar")));
        var tooltipEvent = new AtomicBoolean();
        Consumer<ItemTooltipEvent> listener = event -> {
            if (ItemStack.isSameItemSameComponents(event.getItemStack(), sword)) tooltipEvent.set(true);
        };
        NeoForge.EVENT_BUS.addListener(ItemTooltipEvent.class, listener);
        try {
            sword.getTooltipLines(Item.TooltipContext.EMPTY, null, TooltipFlag.NORMAL);
            assertTrue(tooltipEvent.getAndSet(false));
            var response = route.read(Runnable::run, () -> EquipmentRoute.captureInventory("TestViewer", 0,
                    index -> index == 0 ? sword : ItemStack.EMPTY), false);
            var item = item(response.body().getAsJsonObject(), "0");
            assertEquals("Named sword", item.get("name").getAsString());
            assertEquals("Actual stack lore", item.getAsJsonArray("tooltip").get(0).getAsString());
            var span = item.getAsJsonArray("tooltipLines").get(0).getAsJsonArray().get(0).getAsJsonObject();
            assertEquals("Actual stack lore", span.get("text").getAsString());
            assertEquals(0x55FFFF, span.get("color").getAsInt());
            assertEquals("Minecraft", item.get("modName").getAsString());
            assertEquals(sword.getBarWidth(), item.getAsJsonObject("bar").get("width").getAsInt());
            assertEquals(sword.getBarColor(), item.getAsJsonObject("bar").get("color").getAsInt());
            assertFalse(tooltipEvent.get());
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
    }

    @Test
    void readsSavedEquipmentAndInventoryThroughTheSameResponseFormats(@TempDir Path root) throws IOException {
        var uuid = UUID.randomUUID();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var saved = new CompoundTag();
        NbtUtils.addCurrentDataVersion(saved);
        saved.putInt("SelectedItemSlot", 8);
        var items = new ListTag();
        items.add(savedStack(registries, 8, new ItemStack(Items.DIAMOND_SWORD)));
        items.add(savedStack(registries, 35, new ItemStack(Items.DIAMOND, 3)));
        items.add(savedStack(registries, 100, new ItemStack(Items.IRON_BOOTS)));
        items.add(savedStack(registries, 101, new ItemStack(Items.IRON_LEGGINGS)));
        items.add(savedStack(registries, 102, dyedChestplate(0xAA1122)));
        items.add(savedStack(registries, 103, new ItemStack(Items.IRON_HELMET)));
        items.add(savedStack(registries, 150, new ItemStack(Items.SHIELD)));
        saved.put("Inventory", items);
        NbtIo.writeCompressed(saved, root.resolve(uuid + ".dat"));

        var route = new EquipmentRoute(new EquipmentPreviews(root.resolve("client.jar")), () -> true);
        EquipmentRoute.SavedData disk = () -> SavedInventory.read(root, uuid, DataFixers.getDataFixer());
        var loadout = route.read(Runnable::run, () -> null, true, disk, tag -> {
            var inventory = SavedInventory.decode(tag, registries);
            return EquipmentRoute.captureLoadout("TestViewer", inventory.selectedSlot(), inventory::item,
                    inventory::equipment, stack -> null);
        });
        assertEquals(200, loadout.status());
        var gear = loadout.body().getAsJsonObject();
        assertEquals(15, gear.getAsJsonArray("slots").size());
        assertEquals(8, gear.get("selectedSlot").getAsInt());
        assertEquals("minecraft:diamond_sword", item(gear, "mainhand").get("id").getAsString());
        assertEquals(item(gear, "mainhand"), item(gear, "8"));
        assertEquals("minecraft:iron_helmet", item(gear, "head").get("id").getAsString());
        assertEquals(0xAA1122, item(gear, "chest").get("dye").getAsInt());
        assertEquals("minecraft:iron_leggings", item(gear, "legs").get("id").getAsString());
        assertEquals("minecraft:iron_boots", item(gear, "feet").get("id").getAsString());
        assertEquals("minecraft:shield", item(gear, "offhand").get("id").getAsString());

        var response = route.read(Runnable::run, () -> null, false, disk, tag -> {
            var inventory = SavedInventory.decode(tag, registries);
            return EquipmentRoute.captureInventory("TestViewer", inventory.selectedSlot(), inventory::item);
        });
        assertEquals(200, response.status());
        var inventory = response.body().getAsJsonObject();
        assertEquals(36, inventory.getAsJsonArray("slots").size());
        assertEquals(8, inventory.get("selectedSlot").getAsInt());
        assertEquals(3, item(inventory, "35").get("count").getAsInt());
        assertFalse(item(inventory, "35").has("preview"));
        assertTrue(slot(inventory, "34").get("item").isJsonNull());
    }

    @Test
    void usesSavedDataOnlyWhenEnabledAndPrefersLiveData(@TempDir Path root) {
        var previews = new EquipmentPreviews(root.resolve("client.jar"));
        EquipmentRoute.SavedData unexpectedRead = () -> {
            throw new AssertionError("Unexpected disk read");
        };
        var disabled = new EquipmentRoute(previews);
        assertEquals(404, disabled.read(Runnable::run, () -> null, true, unexpectedRead, null).status());

        var enabled = new EquipmentRoute(previews, () -> true);
        assertEquals(200, enabled.read(Runnable::run, EquipmentRouteTest::sampleEquipment, true,
                unexpectedRead, null).status());
        var live = new AtomicReference<EquipmentRoute.Snapshot>();
        assertEquals(200, enabled.read(Runnable::run, live::get, true, () -> {
            live.set(sampleEquipment());
            return new CompoundTag();
        }, tag -> {
            throw new AssertionError("Expected the newly connected player to take precedence");
        }).status());
    }

    @Test
    void fallsBackToPreviousSaveAndDistinguishesMissingAndUnreadableData(@TempDir Path root) throws IOException {
        var uuid = UUID.randomUUID();
        var fixer = DataFixers.getDataFixer();
        var route = new EquipmentRoute(new EquipmentPreviews(root.resolve("client.jar")), () -> true);
        EquipmentRoute.SavedData disk = () -> SavedInventory.read(root, uuid, fixer);
        assertNull(disk.read());
        assertEquals(404, route.read(Runnable::run, () -> null, false, disk, null).status());

        Files.writeString(root.resolve(uuid + ".dat"), "unreadable");
        var response = route.read(Runnable::run, () -> null, false, disk, null);
        assertEquals(503, response.status());
        assertEquals("inventory_unavailable", response.body().getAsJsonObject().get("error").getAsString());

        var previous = new CompoundTag();
        NbtUtils.addCurrentDataVersion(previous);
        previous.put("Inventory", new ListTag());
        NbtIo.writeCompressed(previous, root.resolve(uuid + ".dat_old"));
        assertEquals(previous, disk.read());
    }

    private static CompoundTag savedStack(RegistryAccess registries, int index, ItemStack item) {
        var tag = new CompoundTag();
        tag.putByte("Slot", (byte) index);
        return (CompoundTag) item.save(registries, tag);
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
                        ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY, stack -> null)).body().getAsJsonObject();
        assertEquals("unsupported_model", preview(special, "offhand").get("reason").getAsString());

        ItemStack red = dyedChestplate(0xAA1122);
        ItemStack blue = dyedChestplate(0x2233BB);
        var armor = route.read(Runnable::run, () -> EquipmentRoute.capture("TestViewer", slot -> switch (slot) {
            case CHEST -> red;
            case OFFHAND -> blue;
            default -> ItemStack.EMPTY;
        }, stack -> null)).body().getAsJsonObject();
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

    @ParameterizedTest
    @ValueSource(strings = {"png", "webp"})
    void usesAnExportedPreviewForTheExactVariantEvenWithoutVanillaAssets(String format, @TempDir Path root)
            throws IOException {
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
        byte[] exported = format.equals("png") ? png.toByteArray() : Base64.getDecoder().decode(
                "UklGRigAAABXRUJQVlA4TBwAAAAvP8APEA8wVSMyqgMY8CCTtkn9i9/OiP6nCzIc");
        try (var zip = new ZipOutputStream(Files.newOutputStream(root.resolve("previews.zip")))) {
            zip.putNextEntry(new ZipEntry("format.txt"));
            zip.write("webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("previews/" + redKey + "." + format));
            zip.write(exported);
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
        assertArrayEquals(exported, Base64.getDecoder().decode(fromPack.get(format).getAsString()));
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
        return EquipmentRoute.capture("TestViewer", slot -> equipped.getOrDefault(slot, ItemStack.EMPTY), stack -> null);
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
