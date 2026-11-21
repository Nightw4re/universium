package com.universium.webcraft.exporter;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.CreativeModeTabSearchRegistry;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

final class CatalogExportItems {
    private static final ResourceLocation HIDDEN = ResourceLocation.fromNamespaceAndPath("c", "hidden_from_recipe_viewers");
    private static final TagKey<CreativeModeTab> HIDDEN_TABS = TagKey.create(Registries.CREATIVE_MODE_TAB, HIDDEN);
    private static final TagKey<Item> HIDDEN_ITEMS = TagKey.create(Registries.ITEM, HIDDEN);
    private static final TagKey<Block> HIDDEN_BLOCKS = TagKey.create(Registries.BLOCK, HIDDEN);

    private CatalogExportItems() {}

    static Stream<ItemStack> fullRegistry() {
        return defaultStacks(BuiltInRegistries.ITEM.stream());
    }

    static Stream<ItemStack> visible(ClientPacketListener connection, RegistryAccess registries) {
        if (CreativeModeTabs.tryRebuildTabContents(connection.enabledFeatures(), false, registries)) {
            // Keep the client's search trees in sync with the shared creative tab cache.
            var searchTrees = connection.searchTrees();
            for (var tab : CreativeModeTabs.allTabs()) {
                if (!tab.hasSearchBar()) continue;
                var stacks = List.copyOf(tab.getDisplayItems());
                searchTrees.updateCreativeTooltips(registries, stacks, CreativeModeTabSearchRegistry.getNameSearchKey(tab));
                searchTrees.updateCreativeTags(stacks, CreativeModeTabSearchRegistry.getTagSearchKey(tab));
            }
        }
        var mods = ModList.get();
        var viewer = mods.isLoaded("jei") ? JeiMachineRecipePlugin.visibleItems() : Optional.<Set<Item>>empty();
        if (viewer.isEmpty() && mods.isLoaded("emi")) viewer = EmiMachineRecipes.visibleItems();
        return visibleDefaults(registries.lookupOrThrow(Registries.CREATIVE_MODE_TAB),
                viewer.<Predicate<Item>>map(items -> items::contains).orElse(item -> true));
    }

    static Stream<ItemStack> visibleDefaults(HolderLookup.RegistryLookup<CreativeModeTab> tabs, Predicate<Item> viewerVisible) {
        return defaultStacks(tabs.listElements()
                .filter(tab -> !tab.is(CreativeModeTabs.OP_BLOCKS) && !tab.is(HIDDEN_TABS))
                .map(tab -> tab.value())
                .filter(tab -> tab.getType() == CreativeModeTab.Type.CATEGORY)
                .flatMap(tab -> Stream.concat(tab.getDisplayItems().stream(), tab.getSearchTabDisplayItems().stream()))
                .filter(stack -> !stack.isEmpty() && !stack.is(HIDDEN_ITEMS))
                .map(ItemStack::getItem)
                .filter(item -> !(item instanceof BlockItem block) || !block.getBlock().defaultBlockState().is(HIDDEN_BLOCKS))
                .distinct()
                .filter(viewerVisible));
    }

    private static Stream<ItemStack> defaultStacks(Stream<Item> items) {
        return items.map(Item::getDefaultInstance).filter(stack -> !stack.isEmpty());
    }
}
