package com.universium.webcraft.exporter;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

import java.util.ArrayList;
import java.util.List;

final class DyedItemVariants {
    private DyedItemVariants() {}

    static List<CatalogPreviewVariant> collect(List<ItemStack> items) {
        var variants = new ArrayList<CatalogPreviewVariant>();
        for (var item : items) {
            if (!item.is(ItemTags.DYEABLE)) {
                continue;
            }
            variants.add(variant("undyed", "Undyed", item));
            for (DyeColor dye : DyeColor.values()) {
                var colored = DyedItemColor.applyDyes(item, List.of(DyeItem.byColor(dye)));
                variants.add(variant(dye.getName(), DyeItem.byColor(dye).getDescription().getString().replaceFirst(" Dye$", ""), colored));
            }
        }
        return variants;
    }

    private static CatalogPreviewVariant variant(String color, String label, ItemStack stack) {
        return new CatalogPreviewVariant("color/" + color, stack,
                List.of(new CatalogPreviewVariant.Option("color", color, label)));
    }
}
