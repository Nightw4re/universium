package com.universium.webcraft.runtime;

import com.universium.webcraft.config.webcraftConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class webcraftResourcePackRegistry {
    private final webcraftConfig config;
    private final List<webcraftResourcePack> packs;
    private final Map<String, byte[]> textureIndex;
    private final Map<String, ItemModelInfo> itemModelIndex;

    public webcraftResourcePackRegistry(webcraftConfig config) {
        this.config = config;
        this.packs = loadPacksInternal();
        this.textureIndex = buildTextureIndex();
        this.itemModelIndex = buildItemModelIndex();
    }

    public List<webcraftResourcePack> loadPacks() {
        return packs;
    }

    public byte[] resolveTexture(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        String normalized = normalizeRelativePath(relativePath);
        byte[] direct = textureIndex.get(normalized);
        if (direct != null) {
            return direct;
        }
        if (normalized.startsWith("block/") && normalized.endsWith(".png")) {
            String base = normalized.substring("block/".length(), normalized.length() - 4);
            String[] alternates = new String[] {
                    "block/" + base + "_side.png",
                    "block/" + base + "_top.png",
                    "block/" + base + "_bottom.png",
                    "block/" + base + "_front.png",
                    "block/" + base + "_back.png",
                    "block/" + base + "_left.png",
                    "block/" + base + "_right.png",
                    "block/" + base + "_particle.png"
            };
            for (String alternateKey : alternates) {
                byte[] alternate = textureIndex.get(alternateKey);
                if (alternate != null) {
                    return alternate;
                }
            }
        }
        if (normalized.startsWith("item/")) {
            String itemId = normalized.substring("item/".length());
            String resolved = resolveItemTexturePath(itemIdWithoutExtension(itemId));
            String alternateKey = resolved.replace("/api/resources/textures/", "").replace("/resources/textures/", "");
            byte[] alternate = textureIndex.get(normalizeRelativePath(alternateKey));
            if (alternate != null) {
                return alternate;
            }
            if ("crafting_table.png".equals(itemId) || "crafting_table".equals(itemIdWithoutExtension(itemId))) {
                byte[] front = textureIndex.get("block/crafting_table_front.png");
                if (front != null) {
                    return front;
                }
                byte[] side = textureIndex.get("block/crafting_table_side.png");
                if (side != null) {
                    return side;
                }
            }
        }
        return null;
    }

    public Map<String, byte[]> textureIndex() {
        return textureIndex;
    }

    public String resolveItemRenderMode(String itemId) {
        ItemModelInfo info = resolveItemModelInfo(itemId);
        if (info == null) {
            return "item";
        }
        return info.renderMode();
    }

    public String resolveItemTexturePath(String itemId) {
        ItemModelInfo info = resolveItemModelInfo(itemId);
        if (info == null) {
            return "";
        }
        if (info.texturePath() != null && !info.texturePath().isBlank()) {
            return "/resources/textures/" + info.texturePath();
        }
        String key = normalizeItemKey(itemId);
        return "/resources/textures/item/" + key + ".png";
    }

    private static String itemIdWithoutExtension(String value) {
        String normalized = value == null ? "" : value;
        if (normalized.endsWith(".png")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }

    public List<webcraftResourcePack> refreshPacks() {
        return loadPacksInternal();
    }

    private List<webcraftResourcePack> loadPacksInternal() {
        Path root = resolveResourcePackRoot();
        Path listFile = resolveListFile();
        if (!Files.exists(listFile)) {
            return List.of();
        }

        List<webcraftResourcePack> packs = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(listFile, StandardCharsets.UTF_8);
            int priority = 0;
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                Path candidate = root.resolve(line).normalize();
                if (Files.exists(candidate)) {
                    String id = candidate.getFileName().toString();
                    packs.add(new webcraftResourcePack(id, id, root.relativize(candidate).toString().replace('\\', '/'), priority++));
                }
            }
        } catch (IOException exception) {
            return List.of();
        }
        packs.sort(Comparator.comparingInt(webcraftResourcePack::priority));
        return List.copyOf(applyWebOrder(packs));
    }

    private List<webcraftResourcePack> applyWebOrder(List<webcraftResourcePack> source) {
        List<String> order = config.server().resourcePacksWebOrder();
        if (order.isEmpty()) {
            return source;
        }
        List<webcraftResourcePack> ordered = new ArrayList<>();
        for (String token : order) {
            webcraftResourcePack match = findPack(source, token);
            if (match != null && !ordered.contains(match)) {
                ordered.add(match);
            }
        }
        return ordered.isEmpty() ? source : ordered;
    }

    private webcraftResourcePack findPack(List<webcraftResourcePack> source, String token) {
        String normalizedToken = orderKey(token);
        String normalizedStem = orderKey(stripExtension(token));
        for (webcraftResourcePack pack : source) {
            if (matchesOrderToken(pack, normalizedToken, normalizedStem)) {
                return pack;
            }
        }
        return null;
    }

    private boolean matchesOrderToken(webcraftResourcePack pack, String normalizedToken, String normalizedStem) {
        return normalizedToken.equals(orderKey(pack.id()))
                || normalizedToken.equals(orderKey(pack.name()))
                || normalizedToken.equals(orderKey(pack.path()))
                || normalizedStem.equals(orderKey(stripExtension(pack.id())))
                || normalizedStem.equals(orderKey(stripExtension(pack.name())))
                || normalizedStem.equals(orderKey(stripExtension(pack.path())));
    }

    private static String stripExtension(String value) {
        String normalized = value == null ? "" : value.trim();
        int dot = normalized.lastIndexOf('.');
        if (dot > 0) {
            return normalized.substring(0, dot);
        }
        return normalized;
    }

    private static String orderKey(String value) {
        return normalizeRelativePath(stripExtension(value)).toLowerCase();
    }

    public int packCount() {
        return packs.size();
    }

    public String manifestJson() {
        return toManifestJson();
    }

    public String toManifestJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"success\":true,\"data\":{\"packs\":[");
        for (int index = 0; index < packs.size(); index++) {
            webcraftResourcePack pack = packs.get(index);
            if (index > 0) {
                builder.append(',');
            }
            builder.append("{\"id\":").append(quote(pack.id()))
                    .append(",\"name\":").append(quote(pack.name()))
                    .append(",\"path\":").append(quote(pack.path()))
                    .append(",\"priority\":").append(pack.priority())
                    .append('}');
        }
        builder.append("]}}");
        return builder.toString();
    }

    public String modelsJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"success\":true,\"data\":{\"models\":[");
        int index = 0;
        for (Map.Entry<String, ItemModelInfo> entry : itemModelIndex.entrySet()) {
            if (index > 0) {
                builder.append(',');
            }
            ItemModelInfo info = entry.getValue();
            builder.append("{\"id\":").append(quote(entry.getKey()))
                    .append(",\"kind\":").append(quote(info.kind()))
                    .append(",\"renderMode\":").append(quote(info.renderMode()))
                    .append(",\"texturePath\":").append(quote(info.texturePath()))
                    .append(",\"stateKey\":").append(quote("default"))
                    .append(",\"variant\":").append(quote("default"))
                    .append(",\"parent\":").append(quote(info.parent()))
                    .append(",\"textures\":").append(mapToJson(info.textures()))
                    .append(",\"previewPath\":").append(quote(previewPathFor(info.renderMode(), entry.getKey())))
                    .append('}');
            index++;
        }
        builder.append("]}}");
        return builder.toString();
    }

    private Map<String, byte[]> buildTextureIndex() {
        Map<String, byte[]> index = new LinkedHashMap<>();
        if (!packs.isEmpty()) {
            Path root = resolveResourcePackRoot();
            for (webcraftResourcePack pack : packs) {
                Path packRoot = root.resolve(pack.path()).normalize();
                indexPackTextures(index, packRoot);
            }
        }
        indexVanillaTextures(index);
        return Collections.unmodifiableMap(index);
    }

    private Map<String, ItemModelInfo> buildItemModelIndex() {
        Map<String, ItemModelInfo> index = new LinkedHashMap<>();
        if (!packs.isEmpty()) {
            Path root = resolveResourcePackRoot();
            for (webcraftResourcePack pack : packs) {
                Path packRoot = root.resolve(pack.path()).normalize();
                indexItemModels(index, packRoot);
            }
        }
        indexVanillaItemModels(index);
        return Collections.unmodifiableMap(index);
    }

    private void indexVanillaItemModels(Map<String, ItemModelInfo> index) {
        Path artifactsDir = resolveArtifactsDir();
        if (!Files.exists(artifactsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(artifactsDir)) {
            stream
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith("-resources-aka-client-extra.jar")
                                || name.endsWith("-resources.jar")
                                || name.endsWith("-minecraft-resources.jar");
                    })
                    .forEach(path -> indexItemModels(index, path));
        } catch (IOException ignored) {
        }
    }

    private void indexItemModels(Map<String, ItemModelInfo> index, Path packRoot) {
        try {
            if (Files.isRegularFile(packRoot) && isArchive(packRoot)) {
                indexArchiveModels(index, packRoot);
                return;
            }
            Path modelsRoot = packRoot.resolve("assets/minecraft/models/item").normalize();
            if (!Files.exists(modelsRoot)) {
                return;
            }
            try (Stream<Path> stream = Files.walk(modelsRoot)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    Path relative = modelsRoot.relativize(path);
                    String key = normalizeRelativePath(relative.toString());
                    if (key.endsWith(".json")) {
                        try {
                            index.putIfAbsent(key.substring(0, key.length() - 5), inferItemModelInfo(Files.readString(path, StandardCharsets.UTF_8)));
                        } catch (IOException ignored) {
                        }
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }

    private void indexArchiveModels(Map<String, ItemModelInfo> index, Path archive) {
        try (JarFile jarFile = new JarFile(archive.toFile())) {
            jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().startsWith("assets/minecraft/models/item/"))
                    .filter(entry -> entry.getName().endsWith(".json"))
                    .forEach(entry -> {
                        String key = normalizeRelativePath(entry.getName().substring("assets/minecraft/models/item/".length()));
                        try {
                            index.putIfAbsent(key.substring(0, key.length() - 5), inferItemModelInfo(new String(readJarEntryBytes(jarFile, entry), StandardCharsets.UTF_8)));
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private ItemModelInfo inferItemModelInfo(String modelJson) {
        String normalized = modelJson == null ? "" : modelJson.toLowerCase();
        if (normalized.contains("\"parent\": \"builtin/entity\"") || normalized.contains("\"parent\":\"builtin/entity\"")) {
            return new ItemModelInfo("special", "shield", "entity/shield_base.png", "builtin/entity", Map.of("layer0", "minecraft:item/shield"));
        }
        if (normalized.contains("\"parent\": \"minecraft:block/") || normalized.contains("\"parent\":\"minecraft:block/")) {
            String representative = extractRepresentativeBlockTexture(normalized);
            String texturePath = representative == null ? "" : representative;
            return new ItemModelInfo("block", "block", texturePath, "minecraft:block/cube_all", texturePath.isBlank() ? Map.of() : Map.of("all", "minecraft:" + texturePath.replace(".png", "")));
        }
        String layer0 = extractTexturePath(normalized, "layer0");
        if (layer0 != null && !layer0.isBlank()) {
            if (layer0.startsWith("minecraft:")) {
                layer0 = layer0.substring("minecraft:".length());
            }
            if (layer0.startsWith("block/")) {
                String texturePath = layer0 + ".png";
                return new ItemModelInfo("block", "block", texturePath, "minecraft:block/cube_all", Map.of("all", "minecraft:" + layer0));
            }
            if (layer0.startsWith("item/")) {
                String texturePath = layer0 + ".png";
                return new ItemModelInfo("item", "item", texturePath, "minecraft:item/generated", Map.of("layer0", "minecraft:" + layer0));
            }
            String texturePath = "item/" + layer0 + ".png";
            return new ItemModelInfo("item", "item", texturePath, "minecraft:item/generated", Map.of("layer0", "minecraft:item/" + layer0));
        }
        return new ItemModelInfo("item", "item", "", "minecraft:item/generated", Map.of());
    }

    private static String normalizeItemKey(String itemId) {
        String value = itemId == null ? "" : itemId.trim();
        if (value.startsWith("minecraft:")) {
            value = value.substring("minecraft:".length());
        }
        int namespace = value.indexOf(':');
        if (namespace >= 0 && namespace + 1 < value.length()) {
            value = value.substring(namespace + 1);
        }
        return value;
    }

    private ItemModelInfo resolveItemModelInfo(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return new ItemModelInfo("item", "item", "", "minecraft:item/generated", Map.of());
        }
        String key = normalizeItemKey(itemId);
        if ("crafting_table".equals(key)) {
            return new ItemModelInfo("block", "block", "block/crafting_table_front.png", "minecraft:block/crafting_table", Map.of("front", "minecraft:block/crafting_table_front", "side", "minecraft:block/crafting_table_side"));
        }
        ItemModelInfo info = itemModelIndex.get(key);
        if (info != null) {
            return info;
        }
        if ("shield".equals(key)) {
            return new ItemModelInfo("special", "shield", "entity/shield_base.png", "builtin/entity", Map.of("layer0", "minecraft:item/shield"));
        }
        return new ItemModelInfo("item", "item", "item/" + key + ".png", "minecraft:item/generated", Map.of("layer0", "minecraft:item/" + key));
    }

    private static String previewPathFor(String kind, String key) {
        String normalizedKind = kind == null ? "item" : kind;
        String normalizedKey = key == null ? "unknown" : key;
        if ("block".equals(normalizedKind)) {
            return "/resources/previews/block/" + normalizedKey + ".png";
        }
        return "/resources/previews/item/" + normalizedKey + ".png";
    }

    private static String extractJsonValue(String json, String key) {
        String needle = "\"" + key + "\"";
        int index = json.indexOf(needle);
        if (index < 0) {
            return null;
        }
        int colon = json.indexOf(':', index + needle.length());
        if (colon < 0) {
            return null;
        }
        int firstQuote = json.indexOf('"', colon + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return null;
        }
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static String extractTexturePath(String json, String key) {
        String needle = "\"" + key + "\"";
        int index = json.indexOf(needle);
        if (index < 0) {
            return null;
        }
        int colon = json.indexOf(':', index + needle.length());
        if (colon < 0) {
            return null;
        }
        int firstQuote = json.indexOf('"', colon + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return null;
        }
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static String extractRepresentativeBlockTexture(String json) {
        String[] preferredKeys = new String[] {"particle", "north", "up", "east", "west", "south", "down"};
        for (String key : preferredKeys) {
            String value = extractTexturePath(json, key);
            if (value != null && !value.isBlank()) {
                if (value.startsWith("minecraft:")) {
                    value = value.substring("minecraft:".length());
                }
                if (value.startsWith("block/")) {
                    return value + ".png";
                }
                if (value.contains("/")) {
                    return value.endsWith(".png") ? value : value + ".png";
                }
                return "block/" + value + ".png";
            }
        }
        return null;
    }

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        int index = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(quote(entry.getKey())).append(':').append(valueToJson(entry.getValue()));
            index++;
        }
        builder.append('}');
        return builder.toString();
    }

    private static String valueToJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder();
            builder.append('{');
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(quote(String.valueOf(entry.getKey()))).append(':').append(valueToJson(entry.getValue()));
                index++;
            }
            builder.append('}');
            return builder.toString();
        }
        if (value instanceof java.util.List<?> list) {
            StringBuilder builder = new StringBuilder();
            builder.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(valueToJson(list.get(i)));
            }
            builder.append(']');
            return builder.toString();
        }
        return quote(String.valueOf(value));
    }

    private record ItemModelInfo(String kind, String renderMode, String texturePath, String parent, Map<String, Object> textures) {
    }

    private void indexVanillaTextures(Map<String, byte[]> index) {
        Path artifactsDir = resolveArtifactsDir();
        if (!Files.exists(artifactsDir)) {
            return;
        }

        try (Stream<Path> stream = Files.list(artifactsDir)) {
            stream
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith("-resources-aka-client-extra.jar")
                                || name.endsWith("-resources.jar")
                                || name.endsWith("-minecraft-resources.jar");
                    })
                    .forEach(path -> indexPackTextures(index, path));
        } catch (IOException ignored) {
        }
    }

    private void indexPackTextures(Map<String, byte[]> index, Path packRoot) {
        try {
            if (Files.isRegularFile(packRoot) && isArchive(packRoot)) {
                indexArchiveTextures(index, packRoot);
                return;
            }
            Path texturesRoot = packRoot.resolve("assets/minecraft/textures/item").normalize();
            indexDirectoryTextures(index, texturesRoot, "item/");
            indexDirectoryTextures(index, packRoot.resolve("assets/minecraft/textures/block").normalize(), "block/");
            indexDirectoryTextures(index, packRoot.resolve("assets/minecraft/textures/entity").normalize(), "entity/");
        } catch (IOException ignored) {
        }
    }

    private void indexDirectoryTextures(Map<String, byte[]> index, Path texturesRoot, String prefix) throws IOException {
        if (!Files.exists(texturesRoot)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(texturesRoot)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                Path relative = texturesRoot.relativize(path);
                String key = prefix + normalizeRelativePath(relative.toString());
                if (key.endsWith(".png")) {
                    try {
                        index.putIfAbsent(key, Files.readAllBytes(path));
                    } catch (IOException ignored) {
                    }
                }
            });
        }
    }

    private void indexArchiveTextures(Map<String, byte[]> index, Path archive) {
        try (JarFile jarFile = new JarFile(archive.toFile())) {
            jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().startsWith("assets/minecraft/textures/item/")
                            || entry.getName().startsWith("assets/minecraft/textures/block/")
                            || entry.getName().startsWith("assets/minecraft/textures/entity/"))
                    .filter(entry -> entry.getName().endsWith(".png"))
                    .forEach(entry -> {
                        String key = normalizeRelativePath(entry.getName().substring("assets/minecraft/textures/".length()));
                        try {
                            index.putIfAbsent(key, readJarEntryBytes(jarFile, entry));
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private byte[] readJarEntryBytes(JarFile jarFile, JarEntry entry) throws IOException {
        try (var inputStream = jarFile.getInputStream(entry)) {
            return inputStream.readAllBytes();
        }
    }

    private static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static String normalizeRelativePath(String value) {
        return value.replace('\\', '/').replaceAll("^/+", "");
    }

    private Path resolveResourcePackRoot() {
        Path configured = Path.of(config.server().resourcePacksDir());
        Path[] candidates = new Path[] {
                configured,
                Path.of("..").resolve(configured),
                Path.of("..", "..").resolve(configured),
                Path.of("resourcepacks"),
                Path.of("modules", "webcraft", "resourcepacks")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return configured.toAbsolutePath().normalize();
    }

    private Path resolveArtifactsDir() {
        Path[] candidates = new Path[] {
                Path.of("build", "moddev", "artifacts"),
                Path.of("..").resolve("build").resolve("moddev").resolve("artifacts"),
                Path.of("..", "..").resolve("build").resolve("moddev").resolve("artifacts"),
                Path.of("modules", "webcraft", "build", "moddev", "artifacts"),
                Path.of("..").resolve("modules").resolve("webcraft").resolve("build").resolve("moddev").resolve("artifacts"),
                Path.of(System.getProperty("user.home", "."), ".gradle", "caches", "neoformruntime", "artifacts"),
                Path.of(System.getProperty("user.home", "."), ".gradle", "caches", "neoformruntime", "intermediate_results")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return candidates[0].toAbsolutePath().normalize();
    }

    private Path resolveListFile() {
        Path configured = Path.of(config.server().resourcePacksList());
        if (Files.exists(configured)) {
            return configured.toAbsolutePath().normalize();
        }
        Path[] candidates = new Path[] {
                Path.of("config").resolve(configured),
                Path.of("..").resolve(configured),
                Path.of("..", "..").resolve(configured),
                Path.of("modules", "webcraft", "config").resolve(configured)
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return configured.toAbsolutePath().normalize();
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + safe + "\"";
    }
}
