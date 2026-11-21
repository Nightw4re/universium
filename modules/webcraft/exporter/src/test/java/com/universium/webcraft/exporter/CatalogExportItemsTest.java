package com.universium.webcraft.exporter;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CatalogExportItemsTest {
    @BeforeAll
    static void initialize() throws ReflectiveOperationException {
        MinecraftTestBootstrap.initialize();
        // Allow NeoForge to post tab build events in a test JVM with no discovered mod containers.
        var mods = ModList.of(List.of(), List.of());
        var setLoadedMods = ModList.class.getDeclaredMethod("setLoadedMods", List.class);
        setLoadedMods.setAccessible(true);
        setLoadedMods.invoke(mods, List.of());
    }

    @Test
    void filtersTheCreativeCatalogWhileKeepingOperatorItemsAvailableInTheFullExport() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var tabs = registries.lookupOrThrow(Registries.CREATIVE_MODE_TAB);
        var parameters = new CreativeModeTab.ItemDisplayParameters(FeatureFlags.VANILLA_SET, true, registries);
        for (var key : List.of(CreativeModeTabs.BUILDING_BLOCKS, CreativeModeTabs.COLORED_BLOCKS,
                CreativeModeTabs.NATURAL_BLOCKS, CreativeModeTabs.OP_BLOCKS)) {
            tabs.get(key).orElseThrow().value().buildContents(parameters);
        }
        assertTrue(tabs.get(CreativeModeTabs.OP_BLOCKS).orElseThrow().value().getDisplayItems().stream()
                .anyMatch(stack -> stack.is(Items.DEBUG_STICK)));

        var visible = CatalogExportItems.visibleDefaults(tabs, item -> true).map(ItemStack::getItem).toList();
        assertTrue(visible.contains(Items.STONE));
        assertFalse(visible.contains(Items.DEBUG_STICK));
        assertFalse(visible.contains(Items.COMMAND_BLOCK));
        assertFalse(visible.contains(Items.AIR));
        assertEquals(visible.size(), visible.stream().distinct().count());

        var full = CatalogExportItems.fullRegistry().map(ItemStack::getItem).toList();
        assertTrue(full.containsAll(visible));
        assertTrue(full.contains(Items.DEBUG_STICK));
        assertTrue(full.contains(Items.COMMAND_BLOCK));
        assertFalse(full.contains(Items.AIR));

        var filtered = CatalogExportItems.visibleDefaults(tabs, item -> item != Items.STONE).map(ItemStack::getItem).toList();
        assertFalse(filtered.contains(Items.STONE));
        assertEquals(visible.size() - 1, filtered.size());
        assertTrue(full.contains(Items.STONE));
    }
}
