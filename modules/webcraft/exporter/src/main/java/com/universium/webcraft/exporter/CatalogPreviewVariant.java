package com.universium.webcraft.exporter;

import net.minecraft.world.item.ItemStack;

import java.util.List;

record CatalogPreviewVariant(String id, ItemStack stack, List<Option> options) {
    record Option(String axis, String value, String label) {}
}
