package com.universium.webcraft.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.universium.webcraft.archive.CatalogJsonBundles;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads the administrator's client export in place, without extracting archive entries. */
public final class WebCraftPreviewPack {
    private static final byte[] FORMAT = "webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_CATALOG_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ICON_BYTES = 65_536;
    private static final int MAX_ANIMATION_BYTES = 3 * 1024 * 1024;
    private static final byte[] PNG_HEADER = {(byte) 137, 80, 78, 71, 13, 10, 26, 10,
            0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 64, 0, 0, 0, 64};
    private final Path archive;
    private final PreviewAtlasReader atlas = new PreviewAtlasReader();

    /** References an optional preview archive. */
    public WebCraftPreviewPack(Path archive) {
        this.archive = archive;
    }

    /** Reports whether an administrator has installed the optional archive. */
    public boolean isInstalled() {
        return Files.isRegularFile(archive);
    }

    /** Reads an exported icon, or returns null when the entry is unavailable or invalid. */
    public byte[] readIcon(String key) {
        Icon icon = readIconWithFormat(key);
        return icon == null ? null : icon.bytes();
    }

    /** Reads a standalone WebP or PNG, or recovers the indexed atlas tile as a PNG. */
    public Icon readIconWithFormat(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) {
            return null;
        }
        String name = "previews/" + key;
        try (var zip = new ZipFile(archive.toFile())) {
            if (!hasSupportedFormat(zip)) return null;
            byte[] webp = readEntry(zip, name + ".webp", MAX_ICON_BYTES);
            if (isLosslessWebp(webp, 64)) return new Icon(webp, "image/webp");
            byte[] png = readEntry(zip, name + ".png", MAX_ICON_BYTES);
            if (png != null && png.length >= PNG_HEADER.length
                    && Arrays.equals(PNG_HEADER, 0, PNG_HEADER.length, png, 0, PNG_HEADER.length)) {
                return new Icon(png, "image/png");
            }
            return atlas.read(archive, zip, key);
        } catch (IOException exception) {
            return null;
        }
    }

    static boolean isLosslessWebp(byte[] bytes, int size) {
        if (bytes == null || bytes.length < 26) return false;
        var header = ByteBuffer.wrap(bytes);
        if (header.getInt(0) != 0x52494646 || header.getInt(8) != 0x57454250
                || header.getInt(12) != 0x5650384c || bytes[20] != 0x2f) {
            return false;
        }
        header.order(ByteOrder.LITTLE_ENDIAN);
        int chunkSize = header.getInt(16);
        int dimensions = header.getInt(21);
        return header.getInt(4) == bytes.length - 8 && chunkSize > 5 && chunkSize <= bytes.length - 20
                && chunkSize + (chunkSize & 1) == bytes.length - 20
                // Two 14-bit dimensions minus one, followed by an alpha flag and three zero version bits.
                && (dimensions & 0xefffffff) == ((size - 1) | ((size - 1) << 14));
    }

    /** The encoded bytes and media type of a preview. */
    public record Icon(byte[] bytes, String contentType) {}

    /** Reads a 64-pixel animated preview, preferring WebP over legacy APNG. */
    public Icon readAnimationWithFormat(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) return null;
        String name = "animations/" + key;
        try (var zip = new ZipFile(archive.toFile())) {
            if (!hasSupportedFormat(zip)) return null;
            byte[] webp = readEntry(zip, name + ".webp", MAX_ANIMATION_BYTES);
            if (isAnimatedWebpTexture(webp)) {
                var header = ByteBuffer.wrap(webp).order(ByteOrder.LITTLE_ENDIAN);
                if ((header.getInt(24) & 0xffffff) == 63 && (header.getInt(27) & 0xffffff) == 63) {
                    return new Icon(webp, "image/webp");
                }
            }
            byte[] png = readEntry(zip, name + ".png", MAX_ANIMATION_BYTES);
            return isAnimatedPngIcon(png) ? new Icon(png, "image/png") : null;
        } catch (IOException exception) {
            return null;
        }
    }

    /** Reads the optional 64-pixel animated PNG associated with an exported icon. */
    public byte[] readAnimation(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) {
            return null;
        }
        byte[] bytes = readEntry("animations/" + key + ".png", MAX_ANIMATION_BYTES);
        return isAnimatedPngIcon(bytes) ? bytes : null;
    }

    private static boolean isAnimatedPngIcon(byte[] bytes) {
        // The exporter writes acTL immediately after IHDR. Reject static or oversized attachments.
        if (bytes == null || bytes.length < 53
                || !Arrays.equals(PNG_HEADER, 0, PNG_HEADER.length, bytes, 0, PNG_HEADER.length)) {
            return false;
        }
        var header = ByteBuffer.wrap(bytes);
        int frameCount = header.getInt(41);
        return header.getInt(33) == 8 && header.getInt(37) == 0x6163544c && frameCount >= 2 && frameCount <= 160;
    }

    /** Reads metadata for exported default stacks. Earlier or inventory-only exports have no catalog. */
    public JsonArray readCatalog() {
        var root = readJson("catalog.json", MAX_CATALOG_BYTES);
        return root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
    }

    /** Reads default icon keys for recipe ingredients outside the exported item catalog. */
    public JsonObject readRecipeItemPreviews() {
        return readObject("catalog-data/recipe-item-previews.json", MAX_CATALOG_BYTES);
    }

    /** Reads the serialized components of a public catalog stack. */
    public JsonObject readCatalogStack(ResourceLocation item, String variant) {
        return readCatalogAttachment("stacks", item, variant, 256 * 1024);
    }

    /** Reads the client-resolved text for a public catalog stack. */
    public JsonObject readCatalogTooltip(ResourceLocation item, String variant) {
        return readCatalogAttachment("tooltips", item, variant, 128 * 1024);
    }

    /** Reads the coverage and result item index of the exported recipe snapshot. */
    public JsonObject readRecipeIndex() {
        return readObject("catalog-data/recipes.json", 2 * 1024 * 1024);
    }

    /** Reads the recipe snapshot for one result item. */
    public JsonObject readRecipes(ResourceLocation item) {
        return readObject("catalog-data/recipes/" + item.getNamespace() + "/" + item.getPath() + ".json", 4 * 1024 * 1024);
    }

    /** Reads the resolved members of a named item tag used by exported recipes. */
    public JsonObject readRecipeTag(ResourceLocation tag) {
        return readObject("catalog-data/recipe-tags/" + tag.getNamespace() + "/" + tag.getPath() + ".json",
                4 * 1024 * 1024);
    }

    /** Reads the optional recipe viewer output index. */
    public JsonObject readMachineRecipeIndex() {
        return readObject("catalog-data/machine-recipes.json", 4 * 1024 * 1024);
    }

    /** Reads the machine recipes indexed for an item or fluid output. */
    public JsonObject readMachineRecipes(ResourceLocation output, boolean fluid) {
        return readMachineRecipes(output, fluid ? "fluids" : "items");
    }

    /** Reads the machine recipes indexed for a chemical output. */
    public JsonObject readChemicalRecipes(ResourceLocation output) {
        return readMachineRecipes(output, "chemicals");
    }

    private JsonObject readMachineRecipes(ResourceLocation output, String directory) {
        return readObject("catalog-data/machine-recipes/" + directory + "/" + output.getNamespace()
                + "/" + output.getPath() + ".json", 4 * 1024 * 1024);
    }

    /** Visits the selected shared machine recipe groups using one archive handle. */
    public boolean visitMachineRecipeGroups(Set<Integer> groups, BiPredicate<Integer, JsonObject> visitor) {
        try (var zip = new ZipFile(archive.toFile())) {
            if (!hasSupportedFormat(zip)) return false;
            for (int group : groups) {
                if (group < 0) return false;
                var bytes = readCatalogDocument(zip, "catalog-data/machine-recipes/groups/" + group + ".json", 4 * 1024 * 1024);
                if (!(parseJson(bytes) instanceof JsonObject data) || !visitor.test(group, data)) return false;
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private JsonObject readCatalogAttachment(String directory, ResourceLocation item, String variant, int limit) {
        if (variant != null && !variant.matches("[a-z0-9_.:/-]{1,256}")) {
            return new JsonObject();
        }
        String suffix = variant == null ? "" : variant.replace(":", "%3A").replace("/", "%2F");
        return readObject("catalog-data/" + directory + "/" + item.getNamespace() + "/" + item.getPath()
                + "@" + suffix + ".json", limit);
    }

    private JsonObject readObject(String name, int limit) {
        try (var zip = new ZipFile(archive.toFile())) {
            if (hasSupportedFormat(zip) && parseJson(readCatalogDocument(zip, name, limit)) instanceof JsonObject root) {
                return root;
            }
        } catch (IOException exception) {
            return new JsonObject();
        }
        return new JsonObject();
    }

    private static byte[] readCatalogDocument(ZipFile zip, String name, int limit) throws IOException {
        if (zip.getEntry(name) != null) return readEntry(zip, name, limit);
        return CatalogJsonBundles.read(name, limit, (path, maxBytes) -> readEntry(zip, path, maxBytes));
    }

    /** Reads the shared visual effects exported by the game client. */
    public JsonObject readEffects() {
        var root = readJson("effects.json", 128 * 1024);
        return root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
    }

    /** Reads the versioned visual catalog contributed by the given module. */
    public JsonObject readDesign(ResourceLocation module) {
        if (!module.getPath().matches("[a-z0-9_-]+")) {
            return new JsonObject();
        }
        String file = "/" + module.getPath() + ".json";
        var root = readJson("designs/" + module.getNamespace() + file, 2 * 1024 * 1024);
        if (root.isJsonNull() && module.getNamespace().equals("webcraft_designs")) {
            root = readJson("designs/webcraft_next_designs" + file, 2 * 1024 * 1024);
        }
        return root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
    }

    /** Reads a content-addressed PNG texture of at most 1024 by 1024 pixels. */
    public byte[] readTexture(String hash) {
        return readTexture("textures/", hash);
    }

    /** Reads a machine recipe texture, preferring its animated WebP when present. */
    public Icon readMachineRecipeTexture(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) return null;
        String directory = "catalog-data/machine-recipes/textures/";
        try (var zip = new ZipFile(archive.toFile())) {
            if (!hasSupportedFormat(zip)) return null;
            byte[] webp = readEntry(zip, directory + hash + ".webp", 512 * 1024);
            if (isAnimatedWebpTexture(webp)) return new Icon(webp, "image/webp");
            var png = boundedTexturePng(readEntry(zip, directory + hash + ".png", 512 * 1024));
            return png == null ? null : new Icon(png, "image/png");
        } catch (IOException exception) {
            return null;
        }
    }

    private static boolean isAnimatedWebpTexture(byte[] bytes) {
        if (bytes == null || bytes.length < 68) return false;
        var header = ByteBuffer.wrap(bytes);
        if (header.getInt(0) != 0x52494646 || header.getInt(8) != 0x57454250
                || header.getInt(12) != 0x56503858 || header.getInt(30) != 0x414e494d
                || header.getInt(44) != 0x414e4d46 || (bytes[20] & 0x02) == 0) return false;
        header.order(ByteOrder.LITTLE_ENDIAN);
        return header.getInt(4) == bytes.length - 8 && header.getInt(16) == 10 && header.getInt(34) == 6
                && (header.getInt(24) & 0xffffff) < 1024 && (header.getInt(27) & 0xffffff) < 1024;
    }

    private byte[] readTexture(String directory, String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) {
            return null;
        }
        return boundedTexturePng(readEntry(directory + hash + ".png", 512 * 1024));
    }

    private static byte[] boundedTexturePng(byte[] bytes) {
        if (bytes == null || bytes.length < PNG_HEADER.length || !Arrays.equals(PNG_HEADER, 0, 16, bytes, 0, 16)) {
            return null;
        }
        var header = ByteBuffer.wrap(bytes);
        int width = header.getInt(16);
        int height = header.getInt(20);
        return width > 0 && width <= 1024 && height > 0 && height <= 1024 ? bytes : null;
    }

    private JsonElement readJson(String name, int limit) {
        return parseJson(readEntry(name, limit));
    }

    private static JsonElement parseJson(byte[] bytes) {
        try {
            return bytes == null ? JsonNull.INSTANCE : JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException exception) {
            return JsonNull.INSTANCE;
        }
    }

    private byte[] readEntry(String name, int limit) {
        try (var zip = new ZipFile(archive.toFile())) {
            return hasSupportedFormat(zip) ? readEntry(zip, name, limit) : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static boolean hasSupportedFormat(ZipFile zip) throws IOException {
        var format = zip.getEntry("format.txt");
        if (format == null) return false;
        try (var input = zip.getInputStream(format)) {
            return Arrays.equals(input.readNBytes(FORMAT.length + 1), FORMAT);
        }
    }

    private static byte[] readEntry(ZipFile zip, String name, int limit) throws IOException {
        return readEntry(zip, zip.getEntry(name), limit);
    }

    static byte[] readEntry(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        if (entry == null || entry.getSize() > limit) return null;
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            return bytes.length <= limit ? bytes : null;
        }
    }
}
