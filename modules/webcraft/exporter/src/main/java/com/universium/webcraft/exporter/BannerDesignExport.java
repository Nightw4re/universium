package com.universium.webcraft.exporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BannerPatternTags;
import net.minecraft.world.item.BannerPatternItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPattern;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;

final class BannerDesignExport {
    private BannerDesignExport() {}

    static JsonObject collect(HolderLookup.Provider registries, DesignExport assets, InventoryIconRenderer renderer) throws IOException {
        var patterns = registries.lookup(Registries.BANNER_PATTERN);
        if (patterns.isEmpty()) {
            return null;
        }
        String base = assets.sprite(ModelBakery.BANNER_BASE.sprite());
        String cloth = assets.sprite(Sheets.BANNER_BASE.sprite());
        if (base == null || cloth == null) {
            return null;
        }

        var result = new JsonObject();
        result.addProperty("base", base);
        result.addProperty("cloth", cloth);
        result.addProperty("maxLayers", 6);
        var face = new JsonArray();
        for (int coordinate : new int[]{1, 1, 20, 40}) {
            face.add(coordinate);
        }
        result.add("face", face);
        var colors = new JsonArray();
        for (var color : DyeColor.values()) {
            var row = new JsonObject();
            row.addProperty("id", color.getName());
            row.addProperty("name", Component.translatable("color.minecraft." + color.getName()).getString());
            row.addProperty("rgb", color.getTextureDiffuseColor() & 0xffffff);
            colors.add(row);
        }
        result.add("colors", colors);

        var templates = BuiltInRegistries.ITEM.stream().filter(BannerPatternItem.class::isInstance)
                .map(BannerPatternItem.class::cast).toList();
        var entries = new JsonArray();
        var loomPatterns = new ArrayList<Holder.Reference<BannerPattern>>();
        String white = Component.translatable("color.minecraft.white").getString() + " ";
        var registered = patterns.get().listElements().sorted(Comparator.comparing(entry -> entry.key().location().toString())).toList();
        for (var pattern : registered) {
            var possibleTemplates = new JsonArray();
            if (!pattern.is(BannerPatternTags.NO_ITEM_REQUIRED)) {
                for (var template : templates) {
                    if (pattern.is(template.getBannerPattern())) {
                        var row = new JsonObject();
                        row.addProperty("id", BuiltInRegistries.ITEM.getKey(template).toString());
                        row.addProperty("name", template.getDefaultInstance().getHoverName().getString());
                        possibleTemplates.add(row);
                    }
                }
                if (possibleTemplates.isEmpty()) {
                    continue;
                }
            }
            String hash = assets.sprite(Sheets.getBannerMaterial(pattern).sprite());
            if (hash == null) {
                continue;
            }
            String id = pattern.key().location().toString();
            var row = new JsonObject();
            row.addProperty("id", id);
            String name = Component.translatable(pattern.value().translationKey() + ".white").getString();
            row.addProperty("name", name.startsWith(white) ? name.substring(white.length()) : name);
            row.addProperty("texture", hash);
            row.add("templates", possibleTemplates);
            entries.add(row);
            loomPatterns.add(pattern);
        }
        result.add("patterns", entries);
        result.add("slotPreviews", BannerSlotExport.collect(loomPatterns, assets, renderer));
        return result;
    }
}
