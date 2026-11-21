package com.universium.webcraft.exporter;

import com.google.gson.JsonObject;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.util.Iterator;
import java.util.List;

final class BannerSlotExport {
    private final boolean shield;
    private final InventoryIconRenderer renderer;
    private final Iterator<DyeColor> colors = List.of(DyeColor.values()).iterator();
    private final Iterator<BannerDesignExport.Pattern> patterns;
    private final JsonObject bases = new JsonObject();
    private final JsonObject layers = new JsonObject();
    private PngPixels dark;
    private PngPixels light;
    private boolean capturedBases;

    BannerSlotExport(boolean shield, List<BannerDesignExport.Pattern> patterns, InventoryIconRenderer renderer) {
        this.shield = shield;
        this.renderer = renderer;
        this.patterns = patterns.iterator();
    }

    boolean captureNext(DesignExport assets) {
        if (colors.hasNext()) {
            var color = colors.next();
            var image = renderer.render(stack(shield, color, BannerPatternLayers.EMPTY));
            if (image != null) {
                assets.submit(writer -> bases.addProperty(color.getName(), assets.texture(writer, image)));
            }
        } else if (!capturedBases) {
            capturedBases = true;
            dark = renderer.render(stack(shield, DyeColor.BLACK, BannerPatternLayers.EMPTY));
            light = renderer.render(stack(shield, DyeColor.WHITE, BannerPatternLayers.EMPTY));
        } else if (dark != null && light != null && patterns.hasNext()) {
            var pattern = patterns.next();
            var sprite = (shield ? Sheets.getShieldMaterial(pattern.holder()) : Sheets.getBannerMaterial(pattern.holder())).sprite();
            if (!sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                var white = new BannerPatternLayers.Builder().add(pattern.holder(), DyeColor.WHITE).build();
                var overDark = renderer.render(stack(shield, DyeColor.BLACK, white));
                var overLight = renderer.render(stack(shield, DyeColor.WHITE, white));
                if (overDark != null && overLight != null) {
                    assets.submit(writer -> {
                        var image = BannerSlotLayers.separate(dark, light, overDark, overLight);
                        String hash = assets.texture(writer, image);
                        if (hash != null) layers.addProperty(pattern.id(), hash);
                    });
                }
            }
        } else {
            return false;
        }
        return true;
    }

    JsonObject result() {
        var result = new JsonObject();
        result.add("bases", bases);
        result.add("patterns", layers);
        return result;
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
