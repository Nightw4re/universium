package com.universium.webcraft.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftPreviewPack;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class ItemCatalog {
    private static final String BREEDER_ID = "easy_villagers:breeder";
    private final WebCraftPreviewPack previews;
    private final Map<String, Entry> entries;
    private final Map<String, String> recipePreviewKeys;
    private final JsonObject listing;
    private final JsonObject effects;

    ItemCatalog(Set<ResourceLocation> registered, WebCraftPreviewPack previews) {
        this.previews = previews;
        effects = previews.readEffects();
        Map<ResourceLocation, ExportedItem> exported = loadExportedItems(registered, previews.readCatalog());
        Map<String, Entry> index = new HashMap<>();
        JsonArray items = new JsonArray();
        // Sort by identifier so ordering stays stable across language settings and export runs.
        exported.keySet().stream().sorted((first, second) -> first.toString().compareTo(second.toString())).forEach(id -> {
            var image = exported.get(id);
            String key = image.key();
            JsonObject item = new JsonObject();
            item.addProperty("id", id.toString());
            item.addProperty("name", image.name() == null ? fallbackName(id) : image.name());
            item.addProperty("hasPreview", key != null);
            if (image.bar() != null) item.add("bar", image.bar());
            index.put(id.toString(), new Entry(item, key, image.variants(), image.canGlint(), image.hasPublicData()));
            items.add(item);
        });
        addColorFamilies(exported.keySet(), index);
        entries = Map.copyOf(index);
        var recipePreviews = new HashMap<String, String>();
        var recipeIndex = previews.readRecipeItemPreviews();
        for (var id : registered) {
            String name = id.toString();
            String key = stringValue(recipeIndex, name);
            if (validKey(key)) recipePreviews.put(name, key);
        }
        recipePreviewKeys = Map.copyOf(recipePreviews);
        listing = new JsonObject();
        listing.add("items", items);
    }

    Response items(String query) {
        return query == null ? Response.ok(listing) : Response.error(400, "unsupported_query");
    }

    Response effects(String query) {
        return query == null ? Response.ok(effects) : Response.error(400, "unsupported_query");
    }

    Response icon(String query) {
        final String animationParameter = "&animated=1";
        boolean animated = query != null && query.length() <= 1200 && query.endsWith(animationParameter);
        if (animated) {
            query = query.substring(0, query.length() - animationParameter.length());
        }
        Selection selection = selection(query, true);
        if (selection == null) {
            return Response.error(400, "invalid_item_id");
        }
        var item = entries.get(selection.id());
        if (item == null && selection.variant() != null) {
            return Response.error(404, "preview_not_found");
        }
        String key = item == null ? recipePreviewKeys.get(selection.id())
                : selection.variant() == null ? item.key() : variantKey(item, selection.variant());
        if (selection.secondVariant() == null && animated) {
            var animation = previews.readAnimationWithFormat(key);
            if (animation != null) {
                return Response.icon(animation);
            }
        }
        var icon = previews.readIconWithFormat(key);
        if (selection.secondVariant() == null) {
            return icon == null ? Response.error(404, "preview_not_found") : Response.icon(icon);
        }
        byte[] combined = BreederIcons.combine(previews.readIconWithFormat(item.key()), icon,
                previews.readIconWithFormat(variantKey(item, selection.secondVariant())));
        return combined == null ? Response.error(404, "preview_not_found") : Response.png(combined);
    }

    private static String variantKey(Entry item, String id) {
        Variant variant = findVariant(item, id);
        return variant == null ? null : variant.key();
    }

    private static Variant findVariant(Entry item, String id) {
        return item.variants().stream().filter(variant -> id.equals(variant.id())).findFirst().orElse(null);
    }

    Response detail(String query) {
        Selection selection = selection(query, false);
        if (selection == null) {
            return Response.error(400, "invalid_item_id");
        }
        Entry item = entries.get(selection.id());
        if (item == null) {
            return Response.error(404, "item_not_found");
        }
        JsonObject detail = item.summary().deepCopy();
        detail.addProperty("canGlint", item.canGlint() && effects.has("glint"));
        var variants = new JsonArray();
        for (Variant variant : item.variants()) {
            var value = entries.get(variant.itemId()).summary().deepCopy();
            value.addProperty("hasPreview", variant.key() != null);
            if (!variant.registryDefault()) {
                value.remove("bar");
                if (variant.bar() != null) value.add("bar", variant.bar());
            }
            value.addProperty("variantId", variant.id());
            value.add("options", variant.options());
            variants.add(value);
        }
        detail.add("variants", variants);
        return Response.ok(detail);
    }

    Response stack(String query) {
        return attachment(query, false);
    }

    Response tooltip(String query) {
        return attachment(query, true);
    }

    private Response attachment(String query, boolean tooltip) {
        Selection selection = selection(query, true);
        if (selection == null) return Response.error(400, "invalid_item_id");
        Entry item = entries.get(selection.id());
        if (item == null) return Response.error(404, "item_not_found");
        String itemId = selection.id();
        String variantId = selection.variant();
        String exportVariantId = variantId;
        String unavailable = tooltip ? "tooltip_unavailable" : "stack_data_unavailable";
        if (variantId != null) {
            Variant variant = findVariant(item, variantId);
            if (variant == null) return Response.error(404, "variant_not_found");
            if (selection.secondVariant() != null) {
                return Response.error(404, findVariant(item, selection.secondVariant()) == null
                        ? "variant_not_found" : unavailable);
            }
            itemId = variant.itemId();
            if (variant.registryDefault()) exportVariantId = null;
        }
        ResourceLocation id = ResourceLocation.parse(itemId);
        JsonObject data = tooltip ? previews.readCatalogTooltip(id, exportVariantId)
                : previews.readCatalogStack(id, exportVariantId);
        if (!(tooltip ? CatalogData.validTooltip(data) : CatalogData.validStack(data))) {
            return Response.error(404, unavailable);
        }
        data.addProperty("catalogId", selection.id());
        data.addProperty("itemId", itemId);
        data.add("variantId", JsonNull.INSTANCE);
        if (variantId != null) data.addProperty("variantId", variantId);
        return Response.ok(data);
    }

    static Selection selection(String query, boolean allowVariant) {
        if (query == null || query.length() > 1200) {
            return null;
        }
        var params = query.split("&", -1);
        if (!params[0].startsWith("id=") || params.length > 2
                || (params.length == 2 && (!allowVariant || !params[1].startsWith("variant=")))) {
            return null;
        }
        try {
            String id = URLDecoder.decode(params[0].substring(3), StandardCharsets.UTF_8);
            String variant = params.length == 2 ? URLDecoder.decode(params[1].substring(8), StandardCharsets.UTF_8) : null;
            if (!CatalogData.isId(id)) {
                return null;
            }
            if (variant == null || validValue(variant)) {
                return new Selection(id, variant, null);
            }
            String[] pair = variant.split("\\+", -1);
            if (BREEDER_ID.equals(id) && pair.length == 2 && validValue(pair[0]) && validValue(pair[1])
                    && pair[0].startsWith("villager_1/") && pair[1].startsWith("villager_2/")) {
                return new Selection(id, pair[0], pair[1]);
            }
            return null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Map<ResourceLocation, ExportedItem> loadExportedItems(Set<ResourceLocation> registered, JsonArray entries) {
        Map<ResourceLocation, ExportedItem> items = new HashMap<>();
        for (JsonElement value : entries) {
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject entry = value.getAsJsonObject();
            String identity = stringValue(entry, "id");
            ResourceLocation id = identity == null ? null : ResourceLocation.tryParse(identity);
            if (id == null || !id.toString().equals(identity) || !registered.contains(id)) {
                continue;
            }
            String name = stringValue(entry, "name");
            String key = stringValue(entry, "previewKey");
            items.putIfAbsent(id, new ExportedItem(name != null && !name.isBlank() && name.length() <= 256 ? name : null,
                    validKey(key) ? key : null, readVariants(id.toString(), entry.get("variants")),
                    booleanValue(entry.get("canGlint")), booleanValue(entry.get("hasPublicData")),
                    readBar(entry.get("bar"))));
        }
        return items;
    }

    private static List<Variant> readVariants(String itemId, JsonElement source) {
        if (source == null || !source.isJsonArray()) {
            return List.of();
        }
        var variants = new ArrayList<Variant>();
        var ids = new HashSet<String>();
        for (var value : source.getAsJsonArray()) {
            if (!value.isJsonObject()) {
                continue;
            }
            var entry = value.getAsJsonObject();
            String id = stringValue(entry, "id");
            String key = stringValue(entry, "previewKey");
            var options = readOptions(entry.get("options"));
            if (validValue(id) && (!entry.has("previewKey") || validKey(key)) && !options.isEmpty() && ids.add(id)) {
                variants.add(new Variant(id, itemId, key, options, false, readBar(entry.get("bar"))));
            }
        }
        return List.copyOf(variants);
    }

    private static JsonObject readBar(JsonElement value) {
        if (!(value instanceof JsonObject bar) || !bar.keySet().equals(Set.of("width", "color"))) return null;
        Integer width = CatalogData.integer(bar.get("width"), 0, 13);
        Integer color = CatalogData.integer(bar.get("color"), 0, 0xffffff);
        if (width == null || color == null) return null;
        var result = new JsonObject();
        result.addProperty("width", width);
        result.addProperty("color", color);
        return result;
    }

    private static JsonArray readOptions(JsonElement value) {
        var result = new JsonArray();
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > 8) {
            return result;
        }
        var axes = new HashSet<String>();
        for (var option : value.getAsJsonArray()) {
            if (!option.isJsonObject()) return new JsonArray();
            var row = option.getAsJsonObject();
            String axis = stringValue(row, "axis");
            String id = stringValue(row, "value");
            String label = stringValue(row, "label");
            if (axis == null || !axis.matches("[a-z][a-z0-9_-]{0,31}") || !axes.add(axis)
                    || !validValue(id) || label == null || label.isBlank() || label.length() > 96) {
                return new JsonArray();
            }
            result.add(option(axis, id, label));
        }
        return result;
    }

    private static void addColorFamilies(Set<ResourceLocation> registered, Map<String, Entry> index) {
        var colors = DyeColor.values();
        Map<ResourceLocation, EnumMap<DyeColor, ResourceLocation>> families = new HashMap<>();
        for (ResourceLocation id : registered) {
            for (DyeColor color : colors) {
                String prefix = color.getName() + "_";
                if (id.getPath().startsWith(prefix) && id.getPath().length() > prefix.length()) {
                    var base = id.withPath(id.getPath().substring(prefix.length()));
                    families.computeIfAbsent(base, unused -> new EnumMap<>(DyeColor.class)).put(color, id);
                    break;
                }
            }
        }
        for (var family : families.entrySet()) {
            if (family.getValue().size() != colors.length) continue;
            var members = new ArrayList<>(family.getValue().values());
            var variants = new ArrayList<Variant>();
            var base = family.getKey();
            var neutral = defaultColorMember(base, registered);
            if (neutral != null) {
                members.add(neutral);
                boolean fluix = !neutral.equals(base);
                addColorVariant(index, variants, neutral, fluix ? "fluix" : "undyed", fluix ? "Fluix (default)" : "Undyed");
            }
            for (var color : family.getValue().entrySet()) {
                String name = color.getKey().getName();
                addColorVariant(index, variants, color.getValue(), name, fallbackName(color.getValue().withPath(name)));
            }
            if (variants.size() < 2) continue;
            if (neutral != null) {
                index.get(neutral.toString()).summary().addProperty("defaultColor", true);
            }
            var shared = List.copyOf(variants);
            for (var member : members) {
                index.computeIfPresent(member.toString(), (id, entry) -> entry.variants().isEmpty()
                        ? new Entry(entry.summary(), entry.key(), shared, entry.canGlint(), entry.hasPublicData()) : entry);
            }
        }
    }

    private static ResourceLocation defaultColorMember(ResourceLocation base, Set<ResourceLocation> registered) {
        if (registered.contains(base)) {
            return base;
        }
        // AE2 registers the dye-independent cable color under its Fluix prefix.
        if (base.getNamespace().equals("ae2")) {
            var fluix = base.withPath("fluix_" + base.getPath());
            if (registered.contains(fluix)) {
                return fluix;
            }
        }
        return null;
    }

    private static void addColorVariant(Map<String, Entry> index, List<Variant> variants, ResourceLocation id, String value, String label) {
        var entry = index.get(id.toString());
        if (entry.key() != null || entry.hasPublicData()) {
            var options = new JsonArray();
            options.add(option("color", value, label));
            variants.add(new Variant("color/" + value, id.toString(), entry.key(), options, true, null));
        }
    }

    private static JsonObject option(String axis, String id, String label) {
        var option = new JsonObject();
        option.addProperty("axis", axis);
        option.addProperty("value", id);
        option.addProperty("label", label);
        return option;
    }

    private static boolean validKey(String key) {
        return key != null && key.matches("[a-f0-9]{64}");
    }

    private static boolean validValue(String value) {
        return value != null && value.matches("[a-z0-9_.:/-]{1,256}");
    }

    private static boolean booleanValue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static String stringValue(JsonObject object, String property) {
        var value = object.get(property);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static String fallbackName(ResourceLocation id) {
        return Arrays.stream(id.getPath().split("[/_-]+"))
                .filter(word -> !word.isEmpty()).map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    record Selection(String id, String variant, String secondVariant) {}
    private record Entry(JsonObject summary, String key, List<Variant> variants, boolean canGlint, boolean hasPublicData) {}
    private record ExportedItem(String name, String key, List<Variant> variants, boolean canGlint,
                                boolean hasPublicData, JsonObject bar) {}
    private record Variant(String id, String itemId, String key, JsonArray options, boolean registryDefault,
                           JsonObject bar) {}
}
