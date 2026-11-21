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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

final class BannerDesignExport {
    private final InventoryIconRenderer renderer;
    private final boolean available;
    private final Iterator<Holder.Reference<BannerPattern>> patterns;
    private final List<BannerPatternItem> templates;
    private final String white;
    private final List<Pattern> loomPatterns = new ArrayList<>();
    private BannerSlotExport banner;
    private BannerSlotExport shield;
    private boolean started;
    private boolean finished;
    private boolean patternCompletionQueued;
    private volatile Boolean baseAvailable;
    private volatile boolean patternsReady;

    // These are assembled in archive order on the writer thread.
    private JsonObject result;
    private final JsonArray entries = new JsonArray();

    BannerDesignExport(HolderLookup.Provider registries, InventoryIconRenderer renderer) {
        this.renderer = renderer;
        var lookup = registries.lookup(Registries.BANNER_PATTERN);
        available = lookup.isPresent();
        patterns = lookup.stream()
                .flatMap(HolderLookup.RegistryLookup::listElements)
                .sorted(Comparator.comparing(entry -> entry.key().location().toString())).iterator();
        templates = BuiltInRegistries.ITEM.stream().filter(BannerPatternItem.class::isInstance)
                .map(BannerPatternItem.class::cast).toList();
        white = Component.translatable("color.minecraft.white").getString() + " ";
    }

    DesignExport.Capture captureNext(DesignExport assets) {
        if (!available || finished) return DesignExport.Capture.DONE;
        if (!started) {
            started = true;
            captureBase(assets);
            return DesignExport.Capture.PROGRESSED;
        }
        if (baseAvailable == null) return DesignExport.Capture.WAITING;
        if (!baseAvailable) return DesignExport.Capture.DONE;
        if (patterns.hasNext()) {
            capturePattern(assets, patterns.next());
            return DesignExport.Capture.PROGRESSED;
        }
        if (banner == null) {
            if (!patternCompletionQueued) {
                patternCompletionQueued = true;
                assets.submit(writer -> patternsReady = true);
                return DesignExport.Capture.PROGRESSED;
            }
            if (!patternsReady) return DesignExport.Capture.WAITING;
            banner = new BannerSlotExport(false, loomPatterns, renderer);
            shield = new BannerSlotExport(true, loomPatterns, renderer);
        }
        if (banner.captureNext(assets) || shield.captureNext(assets)) return DesignExport.Capture.PROGRESSED;
        finished = true;
        assets.submit(writer -> {
            var slots = new JsonObject();
            slots.add("banner", banner.result());
            slots.add("shield", shield.result());
            result.add("patterns", entries);
            result.add("slotPreviews", slots);
            assets.writeManifest(writer, "banners", result);
        });
        return DesignExport.Capture.PROGRESSED;
    }

    private void captureBase(DesignExport assets) {
        var base = assets.sprite(ModelBakery.BANNER_BASE.sprite());
        var cloth = assets.sprite(Sheets.BANNER_BASE.sprite());
        var colors = new JsonArray();
        for (var color : DyeColor.values()) {
            var row = new JsonObject();
            row.addProperty("id", color.getName());
            row.addProperty("name", Component.translatable("color.minecraft." + color.getName()).getString());
            row.addProperty("rgb", color.getTextureDiffuseColor() & 0xffffff);
            colors.add(row);
        }
        assets.submit(writer -> {
            String baseHash = base.write(writer);
            String clothHash = cloth.write(writer);
            if (baseHash == null || clothHash == null) {
                assets.writeManifest(writer, "banners", null);
                baseAvailable = false;
                return;
            }
            result = new JsonObject();
            result.addProperty("base", baseHash);
            result.addProperty("cloth", clothHash);
            result.addProperty("maxLayers", 6);
            var face = new JsonArray();
            for (int coordinate : new int[]{1, 1, 20, 40}) face.add(coordinate);
            result.add("face", face);
            result.add("colors", colors);
            baseAvailable = true;
        });
    }

    private void capturePattern(DesignExport assets, Holder.Reference<BannerPattern> pattern) {
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
            if (possibleTemplates.isEmpty()) return;
        }
        var texture = assets.sprite(Sheets.getBannerMaterial(pattern).sprite());
        String id = pattern.key().location().toString();
        var row = new JsonObject();
        row.addProperty("id", id);
        String name = Component.translatable(pattern.value().translationKey() + ".white").getString();
        row.addProperty("name", name.startsWith(white) ? name.substring(white.length()) : name);
        assets.submit(writer -> {
            String hash = texture.write(writer);
            if (hash == null) return;
            row.addProperty("texture", hash);
            row.add("templates", possibleTemplates);
            entries.add(row);
            loomPatterns.add(new Pattern(pattern, id));
        });
    }

    record Pattern(Holder.Reference<BannerPattern> holder, String id) {}
}
