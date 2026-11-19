package com.universium.webcraft.exporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterial;
import net.minecraft.world.item.armortrim.TrimPattern;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

final class ArmorDesignExport {
    private ArmorDesignExport() {}

    static JsonObject collect(Minecraft minecraft, HolderLookup.Provider registries, DesignExport assets) throws IOException {
        var patterns = registries.lookup(Registries.TRIM_PATTERN);
        var materials = registries.lookup(Registries.TRIM_MATERIAL);
        if (patterns.isEmpty() || materials.isEmpty()) {
            return null;
        }
        var patternList = sorted(patterns.get());
        var materialList = sorted(materials.get());
        var profiles = new JsonObject();
        var pieces = new JsonArray();
        var items = BuiltInRegistries.ITEM.keySet().stream().filter(id -> id.getNamespace().equals("minecraft"))
                .sorted().toList();
        for (var id : items) {
            if (!(BuiltInRegistries.ITEM.get(id) instanceof ArmorItem armor)
                    || !armor.builtInRegistryHolder().is(ItemTags.TRIMMABLE_ARMOR)) {
                continue;
            }
            EquipmentSlot slot = armor.getEquipmentSlot();
            boolean leggings = slot == EquipmentSlot.LEGS;
            var armorMaterial = armor.getMaterial();
            var layers = baseLayers(armorMaterial, leggings, assets);
            if (layers == null || layers.isEmpty()) {
                continue;
            }
            String profile = armorMaterial.unwrapKey().orElseThrow().location() + (leggings ? "/inner" : "/outer");
            if (!profiles.has(profile)) {
                profiles.add(profile, trimTextures(minecraft, assets, armorMaterial, leggings, patternList, materialList));
            }
            var piece = new JsonObject();
            piece.addProperty("id", id.toString());
            piece.addProperty("name", armor.getDefaultInstance().getHoverName().getString());
            piece.addProperty("slot", slot.getName());
            piece.addProperty("profile", profile);
            piece.add("layers", layers);
            pieces.add(piece);
        }
        if (pieces.isEmpty()) {
            return null;
        }

        var result = new JsonObject();
        var materialNames = new JsonArray();
        for (var material : materialList) {
            materialNames.add(option(material.key().location(), material.value().description().getString()));
        }
        var patternNames = new JsonArray();
        for (var pattern : patternList) {
            patternNames.add(option(pattern.key().location(), pattern.value().description().getString()));
        }
        result.add("pieces", pieces);
        result.add("materials", materialNames);
        result.add("patterns", patternNames);
        result.add("profiles", profiles);
        result.add("projection", projection());
        return result;
    }

    private static JsonArray baseLayers(Holder<ArmorMaterial> material, boolean leggings, DesignExport assets) throws IOException {
        var layers = new JsonArray();
        for (var layer : material.value().layers()) {
            String hash = assets.resource(layer.texture(leggings));
            if (hash == null) {
                return null;
            }
            var row = new JsonObject();
            row.addProperty("texture", hash);
            if (layer.dyeable()) {
                row.addProperty("tint", 0xa06540);
            }
            layers.add(row);
        }
        return layers;
    }

    private static JsonArray trimTextures(Minecraft minecraft, DesignExport assets, Holder<ArmorMaterial> armor, boolean leggings,
                                          List<Holder.Reference<TrimPattern>> patterns, List<Holder.Reference<TrimMaterial>> materials)
            throws IOException {
        var sprites = new JsonArray();
        var atlas = minecraft.getTextureAtlas(Sheets.ARMOR_TRIMS_SHEET);
        for (var pattern : patterns) {
            for (var material : materials) {
                var trim = new ArmorTrim(material, pattern);
                var sprite = atlas.apply(leggings ? trim.innerTexture(armor) : trim.outerTexture(armor));
                String hash = assets.sprite(sprite);
                if (hash != null) {
                    var row = new JsonObject();
                    row.addProperty("pattern", pattern.key().location().toString());
                    row.addProperty("material", material.key().location().toString());
                    row.addProperty("texture", hash);
                    row.addProperty("decal", pattern.value().decal());
                    sprites.add(row);
                }
            }
        }
        return sprites;
    }

    private static JsonObject projection() {
        var projection = new JsonObject();
        projection.addProperty("width", 24);
        projection.addProperty("height", 36);
        var slots = new JsonObject();
        // Source rectangles are the front-face UVs of the vanilla 64 x 32 humanoid armor layer.
        var head = face(8, 8, 8, 8, 8, 1, false);
        var body = face(20, 20, 8, 12, 8, 10, false);
        var rightLeg = face(4, 20, 4, 12, 8, 22, false);
        var leftLeg = face(4, 20, 4, 12, 12, 22, true);
        slots.add("head", faces(head, face(40, 8, 8, 8, 8, 1, false)));
        slots.add("chest", faces(body, face(44, 20, 4, 12, 3, 9, false), face(44, 20, 4, 12, 17, 9, true)));
        slots.add("legs", faces(body, rightLeg, leftLeg));
        slots.add("feet", faces(rightLeg, leftLeg));
        projection.add("slots", slots);
        projection.add("mannequin", faces(head, body, face(44, 20, 4, 12, 4, 10, false),
                face(44, 20, 4, 12, 16, 10, true), rightLeg, leftLeg));
        return projection;
    }

    private static JsonArray faces(JsonArray... entries) {
        var rows = new JsonArray();
        for (var entry : entries) {
            rows.add(entry);
        }
        return rows;
    }

    private static JsonArray face(int u, int v, int width, int height, int x, int y, boolean mirrored) {
        var face = new JsonArray();
        for (int value : new int[]{u, v, width, height, x, y}) {
            face.add(value);
        }
        face.add(mirrored);
        return face;
    }

    private static JsonObject option(ResourceLocation id, String label) {
        var option = new JsonObject();
        option.addProperty("id", id.toString());
        option.addProperty("name", label);
        return option;
    }

    private static <T> List<Holder.Reference<T>> sorted(HolderLookup.RegistryLookup<T> lookup) {
        return lookup.listElements().sorted(Comparator.comparing(entry -> entry.key().location())).toList();
    }
}
