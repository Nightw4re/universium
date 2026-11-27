package com.universium.webcraft.quests;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reads only bounded PNGs from asset directories and loaded mod resource paths. */
final class QuestArtwork {
    private static final int MAX_PNG_BYTES = 128 * 1024;
    private static final int MAX_RESPONSE_CHARS = 8 * 1024 * 1024;
    private static final int MAX_LOOKUPS = 1024;
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final Pattern TEXTURE = Pattern.compile("[a-z0-9_-][a-z0-9_.-]*(/[a-z0-9_-][a-z0-9_.-]*)+");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_-][a-z0-9_.-]*");
    private final Path assetRoot;
    private final HashMap<ResourceLocation, Optional<String>> cache = new HashMap<>();
    private int includedChars;

    QuestArtwork() {
        this(Path.of("webcraft-assets", "quest-art"));
    }

    QuestArtwork(Path assetRoot) {
        this.assetRoot = assetRoot;
    }

    static ResourceLocation texture(String value) {
        if (value.length() > 512 || !value.contains(":")) return null;
        String source = value.split(";", 2)[0].strip();
        var id = ResourceLocation.tryParse(source);
        if (id == null || !NAMESPACE.matcher(id.getNamespace()).matches() || !TEXTURE.matcher(id.getPath()).matches()) return null;
        String path = id.getPath();
        if (!path.startsWith("textures/")) path = "textures/" + path;
        if (!path.endsWith(".png")) path += ".png";
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
    }

    String read(ResourceLocation texture) {
        if (includedChars >= MAX_RESPONSE_CHARS || (cache.size() >= MAX_LOOKUPS && !cache.containsKey(texture))) return null;
        String result = cache.computeIfAbsent(texture, id -> Optional.ofNullable(load(id))).orElse(null);
        if (result == null || result.length() > MAX_RESPONSE_CHARS - includedChars) return null;
        includedChars += result.length();
        return result;
    }

    private String load(ResourceLocation texture) {
        String namespace = texture.getNamespace();
        String path = texture.getPath();
        String override = readBelow(assetRoot, "assets", namespace, path);
        if (override != null) return override;
        String kubejs = readBelow(Path.of("kubejs", "assets"), namespace, path);
        if (kubejs != null) return kubejs;
        var modList = ModList.get();
        if (modList == null) return null;
        var result = readMod(modList, namespace, namespace, path);
        if (result != null) return result;
        // JourneyMap provides vanilla mob portraits under Minecraft's asset namespace.
        return namespace.equals("minecraft") && path.startsWith("textures/entity_icon/")
                ? readMod(modList, "journeymap", namespace, path) : null;
    }

    private static String readMod(ModList mods, String modId, String namespace, String path) {
        var mod = mods.getModFileById(modId);
        return mod == null ? null : encode(mod.getFile().findResource("assets", namespace, path));
    }

    private static String readBelow(Path root, String first, String second, String... rest) {
        try {
            if (!Files.isDirectory(root)) return null;
            Path base = root.toRealPath();
            Path candidate = base.resolve(first).resolve(second);
            for (String part : rest) candidate = candidate.resolve(part);
            if (!Files.isRegularFile(candidate)) return null;
            Path actual = candidate.toRealPath();
            return actual.startsWith(base) ? encode(actual) : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static String encode(Path source) {
        if (!Files.isRegularFile(source)) return null;
        try (var input = Files.newInputStream(source)) {
            byte[] bytes = input.readNBytes(MAX_PNG_BYTES + 1);
            if (bytes.length < 33 || bytes.length > MAX_PNG_BYTES || !Arrays.equals(bytes, 0, PNG.length, PNG, 0, PNG.length)) {
                return null;
            }
            var header = ByteBuffer.wrap(bytes, 8, 16);
            if (header.getInt() != 13 || header.getInt() != 0x49484452) return null;
            int width = header.getInt();
            int height = header.getInt();
            if (width <= 0 || width > 2048 || height <= 0 || height > 2048) return null;
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException exception) {
            return null;
        }
    }
}
