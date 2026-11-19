package com.universium.webcraft.equipment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

final class EquipmentPreviews {
    private static final int MAX_ASSET_BYTES = 65_536;
    private static final String CUBE = "minecraft:block/cube";
    private static final String BLOCK_GUI = "minecraft:block/block";
    private static final List<String> VISIBLE_FACES = List.of("up", "north", "east");
    private static final Set<String> LEATHER_ARMOR = Set.of("leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots");
    private static final JsonElement CUBE_FROM = JsonParser.parseString("[0,0,0]");
    private static final JsonElement CUBE_TO = JsonParser.parseString("[16,16,16]");
    private static final JsonElement FULL_UV = JsonParser.parseString("[0,0,16,16]");
    private static final JsonElement STANDARD_GUI = JsonParser.parseString("""
            {"rotation":[30,225,0],"translation":[0,0,0],"scale":[0.625,0.625,0.625]}
            """);

    private final Path clientJar;
    private final EquipmentPreviewPack pack;
    private final Map<ResourceLocation, JsonObject> previews = new ConcurrentHashMap<>();

    EquipmentPreviews(Path clientJar) {
        this.clientJar = clientJar;
        this.pack = new EquipmentPreviewPack(clientJar.resolveSibling("previews.zip"));
    }

    EquipmentPreviewPack pack() {
        return pack;
    }

    static boolean usesLeatherLayers(ResourceLocation item) {
        return "minecraft".equals(item.getNamespace()) && LEATHER_ARMOR.contains(item.getPath());
    }

    JsonObject resolve(ResourceLocation item, Integer leatherTint) {
        if (!"minecraft".equals(item.getNamespace())) {
            return missing("unsupported_item");
        }
        if (!Files.isRegularFile(clientJar)) {
            return missing("assets_unavailable");
        }
        JsonObject preview = previews.computeIfAbsent(item, this::load);
        if ("layered".equals(preview.get("type").getAsString())) {
            if (leatherTint == null) {
                return missing("unsupported_item_state");
            }
            preview = preview.deepCopy();
            preview.addProperty("tint", leatherTint);
        }
        return preview;
    }

    private JsonObject load(ResourceLocation item) {
        try (ZipFile assets = new ZipFile(clientJar.toFile())) {
            Map<String, String> textures = new HashMap<>();
            String model = "minecraft:item/" + item.getPath();
            JsonObject faces = null;
            for (int depth = 0; depth < 16; depth++) {
                if ("minecraft:builtin/generated".equals(model) && faces == null) {
                    return usesLeatherLayers(item) ? leather(assets, textures) : sprite(assets, textures);
                }
                var id = ResourceLocation.tryParse(model);
                if (id == null || !id.getPath().matches("(item|block)/[a-z0-9_/.-]+")) {
                    return missing("unsupported_model");
                }
                byte[] json = asset(assets, id, "models", ".json");
                if (json == null) {
                    return missing("asset_missing");
                }
                JsonObject definition = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
                if (definition.has("loader") || !hasSupportedOverrides(definition)) {
                    return missing("unsupported_model");
                }
                if (definition.has("textures")) {
                    definition.getAsJsonObject("textures").entrySet().forEach(
                            entry -> textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString()));
                }
                if (BLOCK_GUI.equals(model) && faces != null) {
                    JsonObject display = definition.getAsJsonObject("display");
                    if (display != null && STANDARD_GUI.equals(display.get("gui"))) {
                        return cube(assets, faces, textures);
                    }
                    return missing("unsupported_model");
                }
                if (definition.has("display") && definition.getAsJsonObject("display").has("gui")) {
                    return missing("unsupported_model");
                }
                if (definition.has("elements")) {
                    if (!CUBE.equals(model) || (faces = cubeFaces(definition)) == null) {
                        return missing("unsupported_model");
                    }
                }
                if (!definition.has("parent")) {
                    break;
                }
                model = definition.get("parent").getAsString();
                if (!model.contains(":")) {
                    model = "minecraft:" + model;
                }
            }
            return missing("unsupported_model");
        } catch (IOException exception) {
            return missing("assets_unavailable");
        } catch (RuntimeException exception) {
            return missing("invalid_asset");
        }
    }

    private static boolean hasSupportedOverrides(JsonObject definition) {
        // EquipmentRoute excludes trimmed stacks, so an untrimmed item uses its base model.
        if (!definition.has("overrides")) {
            return true;
        }
        for (JsonElement entry : definition.getAsJsonArray("overrides")) {
            if (!entry.getAsJsonObject().getAsJsonObject("predicate").keySet().equals(Set.of("trim_type"))) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject cubeFaces(JsonObject definition) {
        JsonArray elements = definition.getAsJsonArray("elements");
        if (elements.size() != 1) {
            return null;
        }
        JsonObject element = elements.get(0).getAsJsonObject();
        if (!CUBE_FROM.equals(element.get("from")) || !CUBE_TO.equals(element.get("to")) || element.has("rotation")) {
            return null;
        }
        return element.getAsJsonObject("faces");
    }

    private static JsonObject sprite(ZipFile assets, Map<String, String> textures) throws IOException {
        // Limit sprites to a single layer. Multiple layers can require item-specific tint rules.
        if (!textures.containsKey("layer0") || textures.containsKey("layer1")) {
            return missing("unsupported_model");
        }
        byte[] png = texture(assets, textures, textures.get("layer0"));
        if (png == null) {
            return missing("asset_missing");
        }
        JsonObject preview = new JsonObject();
        preview.addProperty("type", "sprite");
        preview.addProperty("png", Base64.getEncoder().encodeToString(png));
        return preview;
    }

    private static JsonObject leather(ZipFile assets, Map<String, String> textures) throws IOException {
        if (!textures.containsKey("layer0") || !textures.containsKey("layer1") || textures.containsKey("layer2")) {
            return missing("unsupported_model");
        }
        byte[] base = texture(assets, textures, textures.get("layer0"));
        byte[] overlay = texture(assets, textures, textures.get("layer1"));
        if (base == null || overlay == null) {
            return missing("asset_missing");
        }
        JsonObject preview = new JsonObject();
        preview.addProperty("type", "layered");
        preview.addProperty("base", Base64.getEncoder().encodeToString(base));
        preview.addProperty("overlay", Base64.getEncoder().encodeToString(overlay));
        return preview;
    }

    private static JsonObject cube(ZipFile assets, JsonObject faces, Map<String, String> textures) throws IOException {
        JsonObject images = new JsonObject();
        for (String name : VISIBLE_FACES) {
            JsonObject face = faces.getAsJsonObject(name);
            if (face == null || face.has("tintindex") || face.has("rotation")
                    || (face.has("uv") && !FULL_UV.equals(face.get("uv")))) {
                return missing("unsupported_model");
            }
            byte[] png = texture(assets, textures, face.get("texture").getAsString());
            if (png == null) {
                return missing("asset_missing");
            }
            if (!isOpaque(png)) {
                return missing("unsupported_model");
            }
            images.addProperty(name, Base64.getEncoder().encodeToString(png));
        }
        JsonObject preview = new JsonObject();
        preview.addProperty("type", "cube");
        preview.add("faces", images);
        return preview;
    }

    private static boolean isOpaque(byte[] png) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            return false;
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 255) {
                    return false;
                }
            }
        }
        return true;
    }

    private static byte[] texture(ZipFile assets, Map<String, String> textures, String reference) throws IOException {
        for (int depth = 0; depth < 16 && reference != null; depth++) {
            if (reference.startsWith("#")) {
                reference = textures.get(reference.substring(1));
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(reference);
            if (id == null) {
                return null;
            }
            byte[] png = asset(assets, id, "textures", ".png");
            if (png == null || png.length < 24) {
                return null;
            }
            ByteBuffer header = ByteBuffer.wrap(png);
            int width = header.getInt(16);
            int height = header.getInt(20);
            if (header.getLong(0) != 0x89504E470D0A1A0AL || header.getInt(12) != 0x49484452
                    || width != height || width < 1 || width > 128) {
                return null;
            }
            return png;
        }
        return null;
    }

    private static byte[] asset(ZipFile assets, ResourceLocation id, String folder, String extension) throws IOException {
        if (!"minecraft".equals(id.getNamespace()) || id.getPath().contains("..")) {
            return null;
        }
        var entry = assets.getEntry("assets/minecraft/" + folder + "/" + id.getPath() + extension);
        if (entry == null || entry.getSize() > MAX_ASSET_BYTES) {
            return null;
        }
        try (var input = assets.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_ASSET_BYTES + 1);
            return bytes.length <= MAX_ASSET_BYTES ? bytes : null;
        }
    }

    static JsonObject missing(String reason) {
        JsonObject preview = new JsonObject();
        preview.addProperty("type", "missing");
        preview.addProperty("reason", reason);
        return preview;
    }
}
