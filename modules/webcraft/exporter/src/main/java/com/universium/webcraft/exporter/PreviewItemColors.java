package com.universium.webcraft.exporter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Adapts legacy tint providers for the stack currently being captured. */
public final class PreviewItemColors {
    private static final ThreadLocal<ItemStack> CAPTURE = new ThreadLocal<>();

    private PreviewItemColors() {}

    static ItemStack begin(ItemStack stack) {
        ItemStack previous = CAPTURE.get();
        CAPTURE.set(stack);
        return previous;
    }

    static void restore(ItemStack previous) {
        if (previous == null) CAPTURE.remove();
        else CAPTURE.set(previous);
    }

    /** Adjusts the vanilla item tint only during a WebCraft capture of this exact stack. */
    public static int tint(ItemStack stack, int index, int color) {
        if (index < 0 || index > 1 || (color >>> 24) != 0 || CAPTURE.get() != stack) return color;
        return legacyRgbTint(BuiltInRegistries.ITEM.getKey(stack.getItem()), index, color);
    }

    static int legacyRgbTint(ResourceLocation id, int index, int color) {
        if ((color >>> 24) != 0) return color;
        String path = id.getPath();
        if (index == 1 && "productivebees".equals(id.getNamespace()) && "configurable_honeycomb".equals(path)) {
            return color | 0xff000000;
        }
        if (index != 0) return color;
        // These providers return RGB for their tinted model faces. ItemRenderer expects ARGB.
        boolean rgb = switch (id.getNamespace()) {
            case "productivebees" -> path.equals("configurable_honeycomb")
                    || (path.startsWith("advanced_") && path.endsWith("_beehive"))
                    || path.startsWith("expansion_box_");
            case "enderio" -> path.startsWith("clear_glass_dnm_");
            case "naturesaura" -> path.equals("ancient_leaves");
            case "mysticalagradditions" -> path.equals("insanium_farmland");
            default -> false;
        };
        return rgb ? color | 0xff000000 : color;
    }
}
