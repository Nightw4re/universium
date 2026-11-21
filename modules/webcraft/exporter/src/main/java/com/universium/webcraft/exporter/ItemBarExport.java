package com.universium.webcraft.exporter;

import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

final class ItemBarExport {
    private ItemBarExport() {}

    static void addTo(JsonObject entry, ItemStack stack) {
        try {
            if (!stack.isBarVisible()) return;
            var bar = new JsonObject();
            bar.addProperty("width", Mth.clamp(stack.getBarWidth(), 0, 13));
            bar.addProperty("color", stack.getBarColor() & 0xffffff);
            entry.add("bar", bar);
        } catch (RuntimeException ignored) {
            // A modded bar hook may depend on uninitialized client state.
        }
    }
}
