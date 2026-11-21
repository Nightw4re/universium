package com.universium.webcraft.exporter;

import com.google.gson.JsonObject;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class ItemBarExportTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void recordsMinecraftBarDimensionsAndColorForTheActualStack() {
        var stack = Items.IRON_PICKAXE.getDefaultInstance();
        stack.setDamageValue(100);
        var entry = new JsonObject();
        ItemBarExport.addTo(entry, stack);
        var bar = entry.getAsJsonObject("bar");
        assertEquals(stack.getBarWidth(), bar.get("width").getAsInt());
        assertEquals(stack.getBarColor() & 0xffffff, bar.get("color").getAsInt());
    }

    @Test
    void distinguishesAVisibleEmptyTrackFromAHiddenBar() {
        var stack = Items.IRON_PICKAXE.getDefaultInstance();
        var undamaged = new JsonObject();
        ItemBarExport.addTo(undamaged, stack);
        assertFalse(undamaged.has("bar"));

        stack.setDamageValue(stack.getMaxDamage());
        var depleted = new JsonObject();
        ItemBarExport.addTo(depleted, stack);
        assertEquals(0, depleted.getAsJsonObject("bar").get("width").getAsInt());
    }
}
