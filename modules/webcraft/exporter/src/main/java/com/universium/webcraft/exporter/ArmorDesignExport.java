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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

final class ArmorDesignExport {
    private final Minecraft minecraft;
    private final boolean available;
    private final List<Holder.Reference<TrimPattern>> patterns;
    private final List<Holder.Reference<TrimMaterial>> materials;
    private final Iterator<ResourceLocation> items;
    private final Set<String> capturedProfiles = new HashSet<>();
    private Holder<ArmorMaterial> trimArmor;
    private String trimProfile;
    private boolean trimLeggings;
    private int patternIndex;
    private int materialIndex;
    private boolean finished;
    private boolean manifestQueued;
    private volatile Boolean geometryNeeded;

    // The writer assembles these from captured rows and resolved texture hashes.
    private final JsonObject profiles = new JsonObject();
    private final JsonArray pieces = new JsonArray();

    ArmorDesignExport(Minecraft minecraft, HolderLookup.Provider registries) {
        this.minecraft = minecraft;
        var patternLookup = registries.lookup(Registries.TRIM_PATTERN);
        var materialLookup = registries.lookup(Registries.TRIM_MATERIAL);
        available = patternLookup.isPresent() && materialLookup.isPresent();
        patterns = patternLookup.map(ArmorDesignExport::sorted).orElseGet(List::of);
        materials = materialLookup.map(ArmorDesignExport::sorted).orElseGet(List::of);
        items = BuiltInRegistries.ITEM.keySet().stream().filter(id -> id.getNamespace().equals("minecraft"))
                .filter(id -> BuiltInRegistries.ITEM.get(id) instanceof ArmorItem armor
                        && armor.builtInRegistryHolder().is(ItemTags.TRIMMABLE_ARMOR))
                .sorted().iterator();
    }

    DesignExport.Capture captureNext(DesignExport assets) {
        if (!available || finished) return DesignExport.Capture.DONE;
        if (manifestQueued) {
            if (geometryNeeded == null) return DesignExport.Capture.WAITING;
            finished = true;
            if (!geometryNeeded) return DesignExport.Capture.DONE;
            var geometry = ArmorGeometryExport.collect(minecraft);
            assets.submit(writer -> assets.writeManifest(writer, "armor_geometry", geometry));
        } else if (trimArmor != null && patternIndex < patterns.size() && !materials.isEmpty()) {
            captureTrim(assets);
        } else if (items.hasNext()) {
            capturePiece(assets, items.next());
        } else {
            manifestQueued = true;
            captureManifest(assets);
        }
        return DesignExport.Capture.PROGRESSED;
    }

    private void capturePiece(DesignExport assets, ResourceLocation id) {
        var armor = (ArmorItem) BuiltInRegistries.ITEM.get(id);
        EquipmentSlot slot = armor.getEquipmentSlot();
        boolean leggings = slot == EquipmentSlot.LEGS;
        var material = armor.getMaterial();
        var layers = material.value().layers();
        if (layers.isEmpty()) return;
        var textures = layers.stream().map(layer -> assets.resource(layer.texture(leggings))).toList();
        var tints = layers.stream().map(ArmorMaterial.Layer::dyeable).toList();
        String profile = material.unwrapKey().orElseThrow().location() + (leggings ? "/inner" : "/outer");
        if (capturedProfiles.add(profile)) {
            trimArmor = material;
            trimProfile = profile;
            trimLeggings = leggings;
            patternIndex = 0;
            materialIndex = 0;
        }
        var piece = new JsonObject();
        piece.addProperty("id", id.toString());
        piece.addProperty("name", armor.getDefaultInstance().getHoverName().getString());
        piece.addProperty("slot", slot.getName());
        piece.addProperty("profile", profile);
        assets.submit(writer -> {
            var rows = new JsonArray();
            for (int index = 0; index < textures.size(); index++) {
                String hash = textures.get(index).write(writer);
                if (hash == null) return;
                var row = new JsonObject();
                row.addProperty("texture", hash);
                if (tints.get(index)) row.addProperty("tint", 0xa06540);
                rows.add(row);
            }
            if (!profiles.has(profile)) profiles.add(profile, new JsonArray());
            piece.add("layers", rows);
            pieces.add(piece);
        });
    }

    private void captureTrim(DesignExport assets) {
        var pattern = patterns.get(patternIndex);
        var material = materials.get(materialIndex++);
        if (materialIndex == materials.size()) {
            materialIndex = 0;
            patternIndex++;
        }
        var trim = new ArmorTrim(material, pattern);
        var sprite = minecraft.getTextureAtlas(Sheets.ARMOR_TRIMS_SHEET)
                .apply(trimLeggings ? trim.innerTexture(trimArmor) : trim.outerTexture(trimArmor));
        var texture = assets.sprite(sprite);
        String profile = trimProfile;
        var row = new JsonObject();
        row.addProperty("pattern", pattern.key().location().toString());
        row.addProperty("material", material.key().location().toString());
        boolean decal = pattern.value().decal();
        assets.submit(writer -> {
            var rows = profiles.getAsJsonArray(profile);
            if (rows == null) return;
            String hash = texture.write(writer);
            if (hash == null) return;
            row.addProperty("texture", hash);
            row.addProperty("decal", decal);
            rows.add(row);
        });
    }

    private void captureManifest(DesignExport assets) {
        var materialNames = new JsonArray();
        for (var material : materials) {
            materialNames.add(option(material.key().location(), material.value().description().getString()));
        }
        var patternNames = new JsonArray();
        for (var pattern : patterns) {
            patternNames.add(option(pattern.key().location(), pattern.value().description().getString()));
        }
        assets.submit(writer -> {
            if (pieces.isEmpty()) {
                assets.writeManifest(writer, "armor", null);
                geometryNeeded = false;
                return;
            }
            var result = new JsonObject();
            result.add("pieces", pieces);
            result.add("materials", materialNames);
            result.add("patterns", patternNames);
            result.add("profiles", profiles);
            result.add("projection", projection());
            assets.writeManifest(writer, "armor", result);
            geometryNeeded = true;
        });
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
