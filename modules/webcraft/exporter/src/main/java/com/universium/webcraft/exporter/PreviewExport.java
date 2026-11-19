package com.universium.webcraft.exporter;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PreviewExport implements AutoCloseable {
    private final Minecraft minecraft;
    private final HolderLookup.Provider registries;
    private final List<ItemStack> items;
    private final List<CatalogPreviewVariant> variants;
    private final int catalogStart;
    private final JsonArray catalog = new JsonArray();
    private final JsonObject effects;
    private final Map<String, JsonObject> listings = new HashMap<>();
    private final Set<String> exportedKeys = new HashSet<>();
    private final InventoryAnimationExport animations = new InventoryAnimationExport();
    private final InventoryIconRenderer renderer;
    private final Path temporary;
    private final Path output;
    private final ZipOutputStream archive;
    private int processed;
    private int skipped;

    PreviewExport(Minecraft minecraft, HolderLookup.Provider registries, List<ItemStack> items, int catalogStart,
                  List<CatalogPreviewVariant> variants, Path output) throws IOException {
        this.minecraft = minecraft;
        this.registries = registries;
        this.items = List.copyOf(items);
        this.catalogStart = catalogStart;
        this.variants = List.copyOf(variants);
        effects = catalogStart >= 0 ? ItemGlintExport.read(minecraft) : null;
        this.output = output;
        Files.createDirectories(output.getParent());
        temporary = Files.createTempFile(output.getParent(), "previews-", ".tmp");
        renderer = new InventoryIconRenderer(minecraft);
        try {
            archive = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)));
        } catch (IOException exception) {
            renderer.close();
            Files.deleteIfExists(temporary);
            throw exception;
        }
    }

    boolean tick() throws IOException {
        if (processed == items.size() + variants.size()) {
            writeEntry("format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.US_ASCII));
            if (catalogStart >= 0) {
                writeEntry("catalog.json", catalog.toString().getBytes(StandardCharsets.UTF_8));
                DesignExport.write(minecraft, registries, renderer, this::writeEntry);
            }
            if (effects != null) {
                writeEntry("effects.json", effects.toString().getBytes(StandardCharsets.UTF_8));
            }
            archive.close();
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }

        int index = processed++;
        if (index >= items.size()) {
            exportVariant(variants.get(index - items.size()));
            return false;
        }
        ItemStack stack = items.get(index);
        String key = exportPreview(stack);
        if (catalogStart >= 0 && index >= catalogStart) {
            JsonObject listing = new JsonObject();
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            listing.addProperty("id", id);
            try {
                listing.addProperty("name", stack.getHoverName().getString());
                listing.addProperty("canGlint", effects != null && stack.isEnchantable() && !stack.hasFoil());
            } catch (RuntimeException exception) {
                listing.addProperty("name", id);
            }
            if (key != null) {
                listing.addProperty("previewKey", key);
            }
            catalog.add(listing);
            listings.put(id, listing);
        }
        return false;
    }

    private void exportVariant(CatalogPreviewVariant variant) throws IOException {
        var listing = listings.get(BuiltInRegistries.ITEM.getKey(variant.stack().getItem()).toString());
        if (listing == null) {
            return;
        }
        String key = exportPreview(variant.stack());
        if (key == null) {
            return;
        }
        if (!listing.has("variants")) {
            listing.add("variants", new JsonArray());
        }
        var row = new JsonObject();
        row.addProperty("id", variant.id());
        row.addProperty("previewKey", key);
        var options = new JsonArray();
        for (var option : variant.options()) {
            var entry = new JsonObject();
            entry.addProperty("axis", option.axis());
            entry.addProperty("value", option.value());
            entry.addProperty("label", option.label());
            options.add(entry);
        }
        row.add("options", options);
        listing.getAsJsonArray("variants").add(row);
    }

    private String exportPreview(ItemStack stack) throws IOException {
        String key;
        byte[] image;
        int loopTicks;
        try (var sprites = new InventorySpriteCapture()) {
            // This key also covers stack components used by a mod's client renderer.
            key = Hashing.sha256().hashString(stack.save(registries).toString(), StandardCharsets.UTF_8).toString();
            if (exportedKeys.contains(key)) {
                return key;
            }
            image = renderer.render(stack);
            loopTicks = sprites.loopTicks();
        } catch (RuntimeException exception) {
            skipped++;
            return null;
        }
        if (image == null) {
            skipped++;
            return null;
        }
        writeEntry("previews/" + key + ".png", image);
        exportedKeys.add(key);
        if (loopTicks > 1) {
            animations.add(key, stack, loopTicks);
        }
        return key;
    }

    boolean animating() {
        return processed == items.size() + variants.size() && animations.hasPending();
    }

    void captureAnimationTick() throws IOException {
        // Minecraft advances texture atlases once, before ticking the screen.
        if (minecraft.level != null && minecraft.level.tickRateManager().runsNormally()) {
            animations.tick(renderer, this::writeEntry);
        } else {
            animations.clear();
        }
    }

    private void writeEntry(String name, byte[] bytes) throws IOException {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(bytes);
        archive.closeEntry();
    }

    String progress() {
        if (animating()) {
            return "Capturing animations (" + animations.remaining() + " remaining)";
        }
        return processed + " / " + (items.size() + variants.size()) + " stacks processed";
    }

    String result() {
        return "Saved " + exportedKeys.size() + " previews (" + animations.exported()
                + " animated, " + skipped + " skipped) to webcraft-exports/" + output.getFileName();
    }

    @Override
    public void close() throws IOException {
        try {
            archive.close();
        } finally {
            try {
                renderer.close();
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
