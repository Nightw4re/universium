package com.universium.webcraft.equipment;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

final class EquipmentRoute {
    private static final List<EquipmentSlot> SLOTS = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND);

    private final EquipmentPreviews previews;

    EquipmentRoute(EquipmentPreviews previews) {
        this.previews = previews;
    }

    Response read(MinecraftServer server, UUID viewer) {
        boolean usePreviewPack = previews.pack().isInstalled();
        return read(server, () -> {
            var player = server.getPlayerList().getPlayer(viewer);
            return player == null ? null : capture(player.getGameProfile().getName(), player::getItemBySlot,
                    stack -> usePreviewPack ? previewKey(stack, server) : null);
        });
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
        var task = new FutureTask<>(capture::get);
        gameThread.execute(task);
        Snapshot snapshot;
        try {
            snapshot = task.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            return Response.error(503, "server_busy");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Response.error(503, "server_busy");
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Could not read equipment on the server thread", exception.getCause());
        } finally {
            task.cancel(false);
        }
        if (snapshot == null) {
            return Response.error(404, "player_offline");
        }

        JsonObject response = new JsonObject();
        response.addProperty("player", snapshot.player());
        JsonArray slots = new JsonArray();
        for (Slot slot : snapshot.slots()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot.name());
            if (slot.item() == null) {
                entry.add("item", JsonNull.INSTANCE);
            } else {
                Item item = slot.item();
                JsonObject details = new JsonObject();
                details.addProperty("id", item.id().toString());
                details.addProperty("name", item.name());
                details.addProperty("count", item.count());
                var exported = previews.pack().find(item.previewKey());
                if (exported == null) {
                    exported = item.hasUnsupportedState() ? EquipmentPreviews.missing("unsupported_item_state")
                            : previews.resolve(item.id(), item.leatherTint());
                }
                details.add("preview", exported);
                entry.add("item", details);
            }
            slots.add(entry);
        }
        response.add("slots", slots);
        return Response.ok(response);
    }

    static Snapshot capture(String player, Function<EquipmentSlot, ItemStack> equipment,
                            Function<ItemStack, String> previewKey) {
        List<Slot> slots = SLOTS.stream().map(slot -> {
            ItemStack stack = equipment.apply(slot);
            Item item = stack.isEmpty() ? null : captureItem(stack, previewKey);
            return new Slot(slot.name().toLowerCase(Locale.ROOT), item);
        }).toList();
        return new Snapshot(player, slots);
    }

    private static Item captureItem(ItemStack stack, Function<ItemStack, String> previewKey) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        Integer leatherTint = EquipmentPreviews.usesLeatherLayers(id)
                ? DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR) & 0xFFFFFF : null;
        boolean unsupported = stack.has(DataComponents.CUSTOM_MODEL_DATA) || stack.has(DataComponents.TRIM)
                || (stack.is(ItemTags.DYEABLE) && leatherTint == null);
        return new Item(id, stack.getHoverName().getString(), stack.getCount(), unsupported, previewKey.apply(stack), leatherTint);
    }

    record Snapshot(String player, List<Slot> slots) {}
    private record Slot(String name, Item item) {}
    private record Item(ResourceLocation id, String name, int count, boolean hasUnsupportedState, String previewKey, Integer leatherTint) {}
}
