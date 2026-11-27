package com.universium.webcraft.equipment;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.TooltipProvider;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

final class EquipmentRoute {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<EquipmentSlot> SLOTS = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND);
    private static final Set<String> ARMOR_SLOTS = Set.of("head", "chest", "legs", "feet");
    private static final List<DataComponentType<? extends TooltipProvider>> TOOLTIP_COMPONENTS = List.of(
            DataComponents.JUKEBOX_PLAYABLE, DataComponents.TRIM, DataComponents.STORED_ENCHANTMENTS,
            DataComponents.ENCHANTMENTS, DataComponents.DYED_COLOR, DataComponents.LORE, DataComponents.UNBREAKABLE);

    private final EquipmentPreviews previews;
    private final BooleanSupplier allowOffline;

    EquipmentRoute(EquipmentPreviews previews) {
        this(previews, () -> false);
    }

    EquipmentRoute(EquipmentPreviews previews, BooleanSupplier allowOffline) {
        this.previews = previews;
        this.allowOffline = allowOffline;
    }

    Response read(MinecraftServer server, UUID viewer) {
        return readPlayer(server, viewer, true);
    }

    Response readInventory(MinecraftServer server, UUID viewer) {
        return readPlayer(server, viewer, false);
    }

    private Response readPlayer(MinecraftServer server, UUID viewer, boolean loadout) {
        boolean usePreviewPack = loadout && previews.pack().isInstalled();
        Function<ItemStack, String> preview = stack -> usePreviewPack ? previewKey(stack, server) : null;
        var tooltipContext = Item.TooltipContext.of(server.registryAccess());
        return read(server, () -> {
            var player = server.getPlayerList().getPlayer(viewer);
            if (player == null) return null;
            var inventory = player.getInventory();
            String name = player.getGameProfile().getName();
            return loadout ? captureLoadout(name, inventory.selected, inventory::getItem, player::getItemBySlot, preview, tooltipContext)
                    : new Snapshot(name, captureSlots(36, inventory::getItem, stack -> null, tooltipContext), inventory.selected);
        }, loadout, () -> SavedInventory.read(server.getWorldPath(LevelResource.PLAYER_DATA_DIR), viewer,
                server.getFixerUpper()), tag -> {
            var inventory = SavedInventory.decode(tag, server.registryAccess());
            var cache = server.getProfileCache();
            String name = cache == null ? viewer.toString()
                    : cache.get(viewer).map(profile -> profile.getName()).orElseGet(viewer::toString);
            return loadout ? captureLoadout(name, inventory.selectedSlot(), inventory::item, inventory::equipment, preview, tooltipContext)
                    : new Snapshot(name, captureSlots(36, inventory::item, stack -> null, tooltipContext), inventory.selectedSlot());
        });
    }

    static Snapshot captureLoadout(String name, int selectedSlot, IntFunction<ItemStack> inventory,
                                   Function<EquipmentSlot, ItemStack> equipment, Function<ItemStack, String> preview) {
        return captureLoadout(name, selectedSlot, inventory, equipment, preview, Item.TooltipContext.EMPTY);
    }

    private static Snapshot captureLoadout(String name, int selectedSlot, IntFunction<ItemStack> inventory,
                                           Function<EquipmentSlot, ItemStack> equipment, Function<ItemStack, String> preview,
                                           Item.TooltipContext context) {
        var slots = new ArrayList<>(captureEquipment(equipment, preview, context));
        slots.addAll(captureSlots(9, inventory, preview, context));
        return new Snapshot(name, slots, selectedSlot);
    }

    static Snapshot captureInventory(String player, int selectedSlot, IntFunction<ItemStack> inventory) {
        return new Snapshot(player, captureSlots(36, inventory, item -> null, Item.TooltipContext.EMPTY), selectedSlot);
    }

    private static List<Slot> captureSlots(int count, IntFunction<ItemStack> inventory,
                                           Function<ItemStack, String> previewKey, Item.TooltipContext context) {
        return IntStream.range(0, count).mapToObj(index -> {
            ItemStack stack = inventory.apply(index);
            return new Slot(Integer.toString(index), stack.isEmpty() ? null : captureItem(stack, previewKey, context));
        }).toList();
    }

    private static String previewKey(ItemStack stack, MinecraftServer server) {
        try {
            return EquipmentPreviewPack.key(stack, server.registryAccess());
        } catch (RuntimeException exception) {
            // Some modded item components cannot be serialized for this registry set.
            return null;
        }
    }

    Response read(Executor gameThread, Supplier<Snapshot> capture) {
        return read(gameThread, capture, true);
    }

    Response read(Executor gameThread, Supplier<Snapshot> capture, boolean includePreviews) {
        return read(gameThread, capture, includePreviews, () -> null, tag -> null);
    }

    Response read(Executor gameThread, Supplier<Snapshot> capture, boolean includePreviews,
                  SavedData savedData, Function<CompoundTag, Snapshot> captureSaved) {
        Snapshot snapshot;
        try {
            snapshot = onGameThread(gameThread, capture);
            if (snapshot == null && allowOffline.getAsBoolean()) {
                CompoundTag saved = savedData.read();
                snapshot = onGameThread(gameThread, () -> {
                    var live = capture.get();
                    return live != null || saved == null ? live : captureSaved.apply(saved);
                });
            }
        } catch (TimeoutException exception) {
            return Response.error(503, "server_busy");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Response.error(503, "server_busy");
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Could not read equipment on the server thread", exception.getCause());
        } catch (IOException exception) {
            LOGGER.warn("Could not read saved player inventory", exception);
            return Response.error(503, "inventory_unavailable");
        }
        if (snapshot == null) {
            return Response.error(404, "player_offline");
        }

        JsonObject response = new JsonObject();
        response.addProperty("player", snapshot.player());
        if (snapshot.selectedSlot() != null) {
            response.addProperty("selectedSlot", snapshot.selectedSlot());
        }
        JsonArray slots = new JsonArray();
        for (Slot slot : snapshot.slots()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot.name());
            if (slot.item() == null) {
                entry.add("item", JsonNull.INSTANCE);
            } else {
                CapturedItem item = slot.item();
                JsonObject details = new JsonObject();
                details.addProperty("id", item.id().toString());
                details.addProperty("name", item.name());
                details.addProperty("count", item.count());
                if (item.modName() != null) details.addProperty("modName", item.modName());
                if (!item.tooltip().plain().isEmpty()) {
                    var tooltip = new JsonArray();
                    item.tooltip().plain().forEach(tooltip::add);
                    details.add("tooltip", tooltip);
                    details.add("tooltipLines", item.tooltip().styled());
                }
                if (item.bar() != null) {
                    var bar = new JsonObject();
                    bar.addProperty("width", item.bar().width());
                    bar.addProperty("color", item.bar().color());
                    details.add("bar", bar);
                }
                if (ARMOR_SLOTS.contains(slot.name())) {
                    if (item.leatherTint() != null) details.addProperty("dye", item.leatherTint());
                    if (item.trim() != null) {
                        JsonObject trim = new JsonObject();
                        trim.addProperty("material", item.trim().material());
                        trim.addProperty("pattern", item.trim().pattern());
                        details.add("trim", trim);
                    }
                }
                if (includePreviews) {
                    var exported = previews.pack().find(item.previewKey());
                    if (exported == null) {
                        exported = item.hasUnsupportedState() ? EquipmentPreviews.missing("unsupported_item_state")
                                : previews.resolve(item.id(), item.leatherTint());
                    }
                    details.add("preview", exported);
                }
                entry.add("item", details);
            }
            slots.add(entry);
        }
        response.add("slots", slots);
        return Response.ok(response);
    }

    private static Snapshot onGameThread(Executor gameThread, Supplier<Snapshot> capture)
            throws InterruptedException, ExecutionException, TimeoutException {
        var task = new FutureTask<>(capture::get);
        gameThread.execute(task);
        try {
            return task.get(2, TimeUnit.SECONDS);
        } finally {
            task.cancel(false);
        }
    }

    @FunctionalInterface
    interface SavedData {
        CompoundTag read() throws IOException;
    }

    static Snapshot capture(String player, Function<EquipmentSlot, ItemStack> equipment,
                            Function<ItemStack, String> previewKey) {
        return new Snapshot(player, captureEquipment(equipment, previewKey, Item.TooltipContext.EMPTY), null);
    }

    private static List<Slot> captureEquipment(Function<EquipmentSlot, ItemStack> equipment,
                                               Function<ItemStack, String> previewKey, Item.TooltipContext context) {
        return SLOTS.stream().map(slot -> {
            ItemStack stack = equipment.apply(slot);
            CapturedItem item = stack.isEmpty() ? null : captureItem(stack, previewKey, context);
            return new Slot(slot.name().toLowerCase(Locale.ROOT), item);
        }).toList();
    }

    private static CapturedItem captureItem(ItemStack stack, Function<ItemStack, String> previewKey,
                                             Item.TooltipContext context) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        Integer leatherTint = EquipmentPreviews.usesLeatherLayers(id)
                ? DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR) & 0xFFFFFF : null;
        var trim = stack.get(DataComponents.TRIM);
        boolean unsupported = stack.has(DataComponents.CUSTOM_MODEL_DATA) || trim != null
                || (stack.is(ItemTags.DYEABLE) && leatherTint == null);
        String modName = id.getNamespace().equals("minecraft") ? "Minecraft"
                : ModList.get().getModContainerById(id.getNamespace()).map(mod -> mod.getModInfo().getDisplayName()).orElse(null);
        return new CapturedItem(id, stack.getHoverName().getString(), stack.getCount(), unsupported, previewKey.apply(stack),
                leatherTint, registeredTrim(trim), modName, stackTooltip(stack, context), stackBar(stack));
    }

    private static StackTooltip stackTooltip(ItemStack stack, Item.TooltipContext context) {
        if (stack.has(DataComponents.HIDE_TOOLTIP)) return StackTooltip.EMPTY;
        var lines = new ArrayList<Component>();
        if (stack.is(Items.FILLED_MAP) && !stack.has(DataComponents.CUSTOM_NAME)) {
            var id = stack.get(DataComponents.MAP_ID);
            if (id != null) lines.add(MapItem.getTooltipForId(id));
        }
        if (!stack.has(DataComponents.HIDE_ADDITIONAL_TOOLTIP)) {
            // ItemStack.getTooltipLines posts a client tooltip event even on a dedicated server.
            appendItemTooltip(stack, context, lines);
        }
        Consumer<Component> add = lines::add;
        for (var component : TOOLTIP_COMPONENTS) appendComponentTooltip(stack, context, component, add);
        var canBreak = stack.get(DataComponents.CAN_BREAK);
        if (canBreak != null && canBreak.showInTooltip()) {
            lines.add(CommonComponents.EMPTY);
            lines.add(AdventureModePredicate.CAN_BREAK_HEADER);
            canBreak.addToTooltip(add);
        }
        var canPlace = stack.get(DataComponents.CAN_PLACE_ON);
        if (canPlace != null && canPlace.showInTooltip()) {
            lines.add(CommonComponents.EMPTY);
            lines.add(AdventureModePredicate.CAN_PLACE_HEADER);
            canPlace.addToTooltip(add);
        }
        try {
            var plain = new ArrayList<String>();
            var styled = new JsonArray();
            for (var line : lines.stream().limit(48).toList()) {
                var spans = styledSpans(line);
                var text = new StringBuilder();
                for (var span : spans) text.append(span.getAsJsonObject().get("text").getAsString());
                plain.add(text.toString());
                styled.add(spans);
            }
            return new StackTooltip(plain, styled);
        } catch (RuntimeException | LinkageError exception) {
            return StackTooltip.EMPTY;
        }
    }

    private static JsonArray styledSpans(Component line) {
        var spans = new JsonArray();
        var text = new StringBuilder();
        var style = new Style[] {Style.EMPTY};
        var length = new int[] {0};
        StringDecomposer.iterateFormatted(line, Style.EMPTY, (index, nextStyle, codePoint) -> {
            int width = Character.charCount(codePoint);
            if (length[0] + width > 512) return false;
            if (!style[0].equals(nextStyle)) {
                appendSpan(spans, text, style[0]);
                style[0] = nextStyle;
            }
            text.appendCodePoint(codePoint);
            length[0] += width;
            return true;
        });
        appendSpan(spans, text, style[0]);
        return spans;
    }

    private static void appendSpan(JsonArray spans, StringBuilder text, Style style) {
        if (text.isEmpty()) return;
        var span = new JsonObject();
        span.addProperty("text", text.toString());
        if (style.getColor() != null) span.addProperty("color", style.getColor().getValue() & 0xFFFFFF);
        if (style.isBold()) span.addProperty("bold", true);
        if (style.isItalic()) span.addProperty("italic", true);
        if (style.isUnderlined()) span.addProperty("underlined", true);
        if (style.isStrikethrough()) span.addProperty("strikethrough", true);
        if (style.isObfuscated()) span.addProperty("obfuscated", true);
        spans.add(span);
        text.setLength(0);
    }

    private static <T extends TooltipProvider> void appendComponentTooltip(ItemStack stack, Item.TooltipContext context,
                                                                           DataComponentType<T> type, Consumer<Component> add) {
        try {
            stack.addToTooltip(type, context, add, TooltipFlag.NORMAL);
        } catch (RuntimeException | LinkageError exception) {
            // A missing registry entry can make an individual tooltip component unavailable.
        }
    }

    private static void appendItemTooltip(ItemStack stack, Item.TooltipContext context, List<Component> lines) {
        try {
            stack.getItem().appendHoverText(stack.copy(), context, lines, TooltipFlag.NORMAL);
        } catch (RuntimeException | LinkageError exception) {
            // Keep the stack's standard details when a mod's extra lines need a game client.
        }
    }

    private static Bar stackBar(ItemStack stack) {
        try {
            return stack.isBarVisible() ? new Bar(Mth.clamp(stack.getBarWidth(), 0, 13), stack.getBarColor() & 0xFFFFFF) : null;
        } catch (RuntimeException | LinkageError exception) {
            return null;
        }
    }

    private static Trim registeredTrim(ArmorTrim trim) {
        if (trim == null) return null;
        var material = trim.material().unwrapKey();
        var pattern = trim.pattern().unwrapKey();
        return material.isPresent() && pattern.isPresent()
                ? new Trim(material.get().location().toString(), pattern.get().location().toString()) : null;
    }

    record Snapshot(String player, List<Slot> slots, Integer selectedSlot) {}
    private record Slot(String name, CapturedItem item) {}
    private record Trim(String material, String pattern) {}
    private record Bar(int width, int color) {}
    private record StackTooltip(List<String> plain, JsonArray styled) {
        private static final StackTooltip EMPTY = new StackTooltip(List.of(), new JsonArray());
    }
    private record CapturedItem(ResourceLocation id, String name, int count, boolean hasUnsupportedState, String previewKey,
                                Integer leatherTint, Trim trim, String modName, StackTooltip tooltip, Bar bar) {}
}
