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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class DesignExport {
    private final Minecraft minecraft;
    private final HolderLookup.Provider registries;
    private final ExportArchive archive;
    private final BannerDesignExport banners;
    private ArmorDesignExport armor;
    private boolean bannersDone;
    // Only the archive worker changes these sets. The client may use the caches to skip copying a known texture.
    private final Set<String> written = new HashSet<>();
    private final Set<String> referenced = new LinkedHashSet<>();
    private final Map<ResourceLocation, String> sprites = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, String> resources = new ConcurrentHashMap<>();

    DesignExport(Minecraft minecraft, HolderLookup.Provider registries, InventoryIconRenderer renderer, ExportArchive archive) {
        this.minecraft = minecraft;
        this.registries = registries;
        this.archive = archive;
        banners = new BannerDesignExport(registries, renderer);
    }

    Capture captureNext() {
        if (!bannersDone) {
            var capture = banners.captureNext(this);
            if (capture != Capture.DONE) return capture;
            bannersDone = true;
        }
        if (armor == null) {
            armor = new ArmorDesignExport(minecraft, registries);
            return Capture.PROGRESSED;
        }
        return armor.captureNext(this);
    }

    enum Capture { PROGRESSED, WAITING, DONE }

    void submit(ExportArchive.Work work) {
        archive.submit(work);
    }

    void writeManifest(ExportArchive.EntryWriter writer, String module, JsonObject data) throws IOException {
        if (data != null) {
            data.addProperty("version", 1);
            var hashes = new JsonArray();
            referenced.forEach(hashes::add);
            data.add("textures", hashes);
            writer.write("designs/webcraft_designs/" + module + ".json", data.toString().getBytes(StandardCharsets.UTF_8));
        }
        referenced.clear();
    }

    Texture sprite(TextureAtlasSprite sprite) {
        var contents = sprite.contents();
        var id = contents.name();
        int width = contents.width();
        int height = contents.height();
        if (id.equals(MissingTextureAtlasSprite.getLocation()) || !supportedSize(width, height)) {
            return writer -> null;
        }
        String cached = sprites.get(id);
        if (cached != null) return writer -> reference(cached);
        var pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = sprite.getPixelRGBA(0, x, y);
            }
        }
        var capture = new PngPixels(width, height, pixels);
        return writer -> {
            String hash = sprites.get(id);
            if (hash != null) return reference(hash);
            hash = texture(writer, capture);
            if (hash != null) sprites.put(id, hash);
            return hash;
        };
    }

    Texture resource(ResourceLocation path) {
        String cached = resources.get(path);
        if (cached != null) return writer -> reference(cached);
        var resource = minecraft.getResourceManager().getResource(path);
        if (resource.isEmpty()) return writer -> null;
        return writer -> {
            String hash = resources.get(path);
            if (hash != null) return reference(hash);
            try (var input = resource.get().open(); var image = NativeImage.read(input)) {
                if (!supportedSize(image.getWidth(), image.getHeight())) return null;
                hash = writeTexture(writer, image.asByteArray());
            }
            if (hash != null) resources.put(path, hash);
            return hash;
        };
    }

    String texture(ExportArchive.EntryWriter writer, PngPixels image) throws IOException {
        return writeTexture(writer, image.encode());
    }

    private String writeTexture(ExportArchive.EntryWriter writer, byte[] png) throws IOException {
        if (png.length > 512 * 1024) return null;
        String hash = Hashing.sha256().hashBytes(png).toString();
        if (written.add(hash)) writer.write("textures/" + hash + ".png", png);
        return reference(hash);
    }

    private String reference(String hash) {
        referenced.add(hash);
        return hash;
    }

    private static boolean supportedSize(int width, int height) {
        return width > 0 && width <= 1024 && height > 0 && height <= 1024;
    }

    @FunctionalInterface
    interface Texture {
        String write(ExportArchive.EntryWriter writer) throws IOException;
    }
}
