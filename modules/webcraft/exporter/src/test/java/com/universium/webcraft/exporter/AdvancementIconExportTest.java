package com.universium.webcraft.exporter;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class AdvancementIconExportTest {
    @Test
    void capturesTheActualStacksByAdvancementIncludingItemsOutsideTheCreativeCatalog() {
        MinecraftTestBootstrap.initialize();
        var first = new ItemStack(Items.KNOWLEDGE_BOOK);
        var second = first.copy();
        first.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(1));
        second.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(2));
        var tree = new AdvancementTree();
        tree.addAll(List.of(advancement("first", first, false), advancement("second", second, false),
                advancement("hidden", first, true), advancement("recipes/wood", first, false),
                advancement("empty", ItemStack.EMPTY, false)));

        var icons = PreviewExport.advancementIcons(tree.nodes()).stream()
                .collect(Collectors.toMap(icon -> icon.id().getPath(), PreviewExport.AdvancementIcon::stack));
        assertEquals(Map.of("first", new CustomModelData(1), "second", new CustomModelData(2)),
                icons.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().get(DataComponents.CUSTOM_MODEL_DATA))));
        assertEquals(Items.KNOWLEDGE_BOOK, icons.get("first").getItem());
        assertNotSame(first, icons.get("first"));
    }

    private static AdvancementHolder advancement(String path, ItemStack icon, boolean hidden) {
        var text = Component.literal(path);
        var display = new DisplayInfo(icon, text, text, Optional.empty(), AdvancementType.TASK, false, false, hidden);
        return new AdvancementHolder(ResourceLocation.fromNamespaceAndPath("demo", path),
                new Advancement(Optional.empty(), Optional.of(display), AdvancementRewards.EMPTY, Map.of(),
                        new AdvancementRequirements(List.of()), false));
    }
}
