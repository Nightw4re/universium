package com.universium.webcraft.exporter;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.io.IOException;
import java.util.List;

final class BannerSlotExport {
    private BannerSlotExport() {}

    static JsonObject collect(List<Holder.Reference<BannerPattern>> patterns, DesignExport assets, InventoryIconRenderer renderer)
            throws IOException {
        var slots = new JsonObject();
        slots.add("banner", collectSlot(false, patterns, assets, renderer));
        slots.add("shield", collectSlot(true, patterns, assets, renderer));
        return slots;
    }

    private static JsonObject collectSlot(boolean shield, List<Holder.Reference<BannerPattern>> patterns,
                                          DesignExport assets, InventoryIconRenderer renderer) throws IOException {
        var result = new JsonObject();
        var bases = new JsonObject();
        var layers = new JsonObject();
        for (var color : DyeColor.values()) {
            try (var image = render(renderer, stack(shield, color, BannerPatternLayers.EMPTY))) {
                if (image != null) {
                    bases.addProperty(color.getName(), assets.texture(image));
                }
            }
        }
        try (var dark = render(renderer, stack(shield, DyeColor.BLACK, BannerPatternLayers.EMPTY));
             var light = render(renderer, stack(shield, DyeColor.WHITE, BannerPatternLayers.EMPTY))) {
            if (dark != null && light != null) {
                for (var pattern : patterns) {
                    var sprite = (shield ? Sheets.getShieldMaterial(pattern) : Sheets.getBannerMaterial(pattern)).sprite();
                    if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                        continue;
                    }
                    String hash = exportPattern(shield, pattern, assets, renderer, dark, light);
                    if (hash != null) {
                        layers.addProperty(pattern.key().location().toString(), hash);
                    }
                }
            }
        }
        result.add("bases", bases);
        result.add("patterns", layers);
        return result;
    }

    private static String exportPattern(boolean shield, Holder<BannerPattern> pattern, DesignExport assets,
                                        InventoryIconRenderer renderer, NativeImage dark, NativeImage light) throws IOException {
        var whitePattern = new BannerPatternLayers.Builder().add(pattern, DyeColor.WHITE).build();
        try (var overDark = render(renderer, stack(shield, DyeColor.BLACK, whitePattern));
             var overLight = render(renderer, stack(shield, DyeColor.WHITE, whitePattern))) {
            if (overDark == null || overLight == null) {
                return null;
            }
            try (var layer = BannerSlotLayers.separate(dark, light, overDark, overLight)) {
                return assets.texture(layer);
            }
        }
    }

    private static NativeImage render(InventoryIconRenderer renderer, ItemStack stack) throws IOException {
        byte[] image = renderer.render(stack);
        return image == null ? null : NativeImage.read(image);
    }

    static ItemStack stack(boolean shield, DyeColor color, BannerPatternLayers layers) {
        if (shield) {
            var stack = Items.SHIELD.getDefaultInstance();
            stack.set(DataComponents.BASE_COLOR, color);
            stack.set(DataComponents.BANNER_PATTERNS, layers);
            return stack;
        }
        var banner = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(color.getName() + "_banner"));
        var stack = banner.getDefaultInstance();
        stack.set(DataComponents.BANNER_PATTERNS, layers);
        return stack;
    }
}
