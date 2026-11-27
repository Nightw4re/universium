package com.universium.webcraft.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixTypes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads detached progress on an API worker, using Minecraft's save format and migrations. */
final class SavedAdvancements {
    private static final int MAX_SAVE_BYTES = 8 * 1024 * 1024;
    // The fallback data version matches PlayerAdvancements for files without a DataVersion.
    private static final int LEGACY_DATA_VERSION = 1343;
    private final Path directory;
    private final Codec<Map<ResourceLocation, AdvancementProgress>> codec;

    SavedAdvancements(Path directory, DataFixer fixer) {
        this.directory = directory;
        codec = DataFixTypes.ADVANCEMENTS.wrapCodec(
                Codec.unboundedMap(ResourceLocation.CODEC, AdvancementProgress.CODEC), fixer, LEGACY_DATA_VERSION);
    }

    Map<ResourceLocation, Long> completed(UUID player, List<AdvancementSnapshots.Entry> catalog) throws IOException {
        Map<ResourceLocation, AdvancementProgress> progress;
        try {
            progress = read(directory.resolve(player.toString() + ".json"));
        } catch (NoSuchFileException exception) {
            return Map.of();
        } catch (JsonParseException exception) {
            throw new IOException("Invalid saved advancements", exception);
        }

        var completed = new HashMap<ResourceLocation, Long>();
        for (var entry : catalog) {
            var saved = progress.get(entry.id());
            if (saved != null) {
                saved.update(entry.requirements());
                if (saved.isDone()) completed.put(entry.id(), AdvancementSnapshots.completedAt(saved, entry.requirements()));
            }
        }
        return completed;
    }

    private Map<ResourceLocation, AdvancementProgress> read(Path file) throws IOException {
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > MAX_SAVE_BYTES) {
            throw new IOException("Invalid advancement save file type or size");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_SAVE_BYTES + 1);
        }
        if (bytes.length > MAX_SAVE_BYTES) throw new IOException("Advancement save exceeds the size limit");

        try (var reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8.newDecoder()))) {
            var json = Streams.parse(reader);
            if (!json.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Expected one JSON object in the advancement save");
            }
            validateDataVersion(json.getAsJsonObject().get("DataVersion"));
            return codec.parse(JsonOps.INSTANCE, json).getOrThrow(JsonParseException::new);
        }
    }

    private static void validateDataVersion(JsonElement value) throws IOException {
        if (value == null) return;
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && value.getAsBigDecimal().intValueExact() >= 0) return;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IOException("Invalid advancement save data version", exception);
        }
        throw new IOException("Invalid advancement save data version");
    }
}
