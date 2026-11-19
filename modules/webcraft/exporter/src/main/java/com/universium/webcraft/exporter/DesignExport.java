package com.universium.webcraft.exporter;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class DesignExport {
    private final Minecraft minecraft;
    private final EntryWriter writer;
    private final Set<String> written = new HashSet<>();
    private final Set<String> referenced = new LinkedHashSet<>();
    private final Map<ResourceLocation, String> sprites = new HashMap<>();
    private final Map<ResourceLocation, String> resources = new HashMap<>();

    private DesignExport(Minecraft minecraft, EntryWriter writer) {
        this.minecraft = minecraft;
        this.writer = writer;
    }

    static void write(Minecraft minecraft, HolderLookup.Provider registries, InventoryIconRenderer renderer, EntryWriter writer)
            throws IOException {
        var export = new DesignExport(minecraft, writer);
        export.writeManifest("banners", BannerDesignExport.collect(registries, export, renderer));
        var armor = ArmorDesignExport.collect(minecraft, registries, export);
        export.writeManifest("armor", armor);
        if (armor != null) {
            export.writeManifest("armor_geometry", ArmorGeometryExport.collect(minecraft));
        }
    }

    private void writeManifest(String module, JsonObject data) throws IOException {
        if (data != null) {
            data.addProperty("version", 1);
            var hashes = new JsonArray();
            referenced.forEach(hashes::add);
            data.add("textures", hashes);
            writer.write("designs/webcraft_designs/" + module + ".json", data.toString().getBytes(StandardCharsets.UTF_8));
        }
        referenced.clear();
    }

    String sprite(TextureAtlasSprite sprite) throws IOException {
        var contents = sprite.contents();
        if (contents.name().equals(MissingTextureAtlasSprite.getLocation()) || !supportedSize(contents.width(), contents.height())) {
            return null;
        }
        String hash = sprites.get(contents.name());
        if (hash != null) {
            referenced.add(hash);
            return hash;
        }
        try (var image = new NativeImage(contents.width(), contents.height(), false)) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setPixelRGBA(x, y, sprite.getPixelRGBA(0, x, y));
                }
            }
            hash = texture(image);
        }
        if (hash != null) {
            sprites.put(contents.name(), hash);
        }
        return hash;
    }

    String resource(ResourceLocation path) throws IOException {
        String hash = resources.get(path);
        if (hash != null) {
            referenced.add(hash);
            return hash;
        }
        var resource = minecraft.getResourceManager().getResource(path);
        if (resource.isEmpty()) {
            return null;
        }
        try (var input = resource.get().open(); var image = NativeImage.read(input)) {
            hash = texture(image);
        }
        if (hash != null) {
            resources.put(path, hash);
        }
        return hash;
    }

    String texture(NativeImage image) throws IOException {
        if (!supportedSize(image.getWidth(), image.getHeight())) {
            return null;
        }
        byte[] png = image.asByteArray();
        if (png.length > 512 * 1024) {
            return null;
        }
        String hash = Hashing.sha256().hashBytes(png).toString();
        if (written.add(hash)) {
            writer.write("textures/" + hash + ".png", png);
        }
        referenced.add(hash);
        return hash;
    }

    private static boolean supportedSize(int width, int height) {
        return width > 0 && width <= 1024 && height > 0 && height <= 1024;
    }

    @FunctionalInterface
    interface EntryWriter {
        void write(String name, byte[] bytes) throws IOException;
    }
}
