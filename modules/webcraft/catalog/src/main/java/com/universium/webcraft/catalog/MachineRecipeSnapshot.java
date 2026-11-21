package com.universium.webcraft.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftPreviewPack;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

final class MachineRecipeSnapshot {
    private static final String COMBINED_SOURCE = "recipe_viewers";
    private static final Set<String> VIEWERS = Set.of("jei", "emi");
    private static final int MAX_DIMENSION = 4096;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Set<String> ROLES = Set.of("input", "output", "catalyst");
    private final WebCraftPreviewPack previews;
    private final EnumMap<Output, Set<String>> outputs = new EnumMap<>(Output.class);
    private final String source;
    private final Integer skipped;

    MachineRecipeSnapshot(WebCraftPreviewPack previews) {
        this.previews = previews;
        var index = previews.readMachineRecipeIndex();
        var currentSource = CatalogData.string(index.get("source"));
        if (CatalogData.versionOne(index) && currentSource != null
                && (COMBINED_SOURCE.equals(currentSource) || VIEWERS.contains(currentSource))
                && CatalogData.itemIds(index.get("resultItems")) && CatalogData.itemIds(index.get("resultFluids"))
                && (!index.has("resultChemicals") || CatalogData.itemIds(index.get("resultChemicals")))) {
            source = currentSource;
            skipped = CatalogData.integer(index.get("skipped"), 0, Integer.MAX_VALUE);
            outputs.put(Output.ITEM, readIds(index.getAsJsonArray("resultItems")));
            outputs.put(Output.FLUID, readIds(index.getAsJsonArray("resultFluids")));
            if (index.has("resultChemicals")) {
                outputs.put(Output.CHEMICAL, readIds(index.getAsJsonArray("resultChemicals")));
            }
        } else {
            source = null;
            skipped = null;
        }
    }

    JsonObject forItem(ResourceLocation id, boolean compact) {
        return skipped == null ? null : read(id, Output.ITEM, compact);
    }

    Response forFluid(String query, boolean compact) {
        return forMaterial(query, Output.FLUID, compact);
    }

    Response forChemical(String query, boolean compact) {
        return forMaterial(query, Output.CHEMICAL, compact);
    }

    private Response forMaterial(String query, Output kind, boolean compact) {
        var selection = ItemCatalog.selection("id=" + query.substring(kind.kind.length() + 1), false);
        if (selection == null) return Response.error(400, "invalid_" + kind.kind + "_id");
        if (skipped == null) return Response.error(404, "machine_recipe_export_unavailable");
        var data = read(ResourceLocation.parse(selection.id()), kind, compact);
        return data == null ? Response.error(404, "machine_recipes_unavailable") : Response.ok(data);
    }

    private JsonObject read(ResourceLocation id, Output kind, boolean compact) {
        String idField = kind.kind + "Id";
        String value = id.toString();
        JsonObject data;
        if (outputs.getOrDefault(kind, Set.of()).contains(value)) {
            data = kind == Output.CHEMICAL ? previews.readChemicalRecipes(id) : previews.readMachineRecipes(id, kind == Output.FLUID);
            boolean grouped = CatalogData.integer(data.get("version"), 2, 2) != null;
            if ((!grouped && !CatalogData.versionOne(data)) || !value.equals(CatalogData.string(data.get(idField)))
                    || !grouped && !(data.get("recipes") instanceof JsonArray)) {
                return null;
            }
            var recipes = grouped ? new JsonArray() : data.getAsJsonArray("recipes");
            if (data.has("truncated") && (grouped || !CatalogData.bool(data.get("truncated")) || !recipes.isEmpty())) {
                return null;
            }
            var identities = new HashSet<List<String>>();
            for (var recipe : recipes) {
                if (!(recipe instanceof JsonObject row) || !validateRow(row, value, kind, identities)) {
                    return null;
                }
            }
            if (grouped && !readGroups(data, value, kind, identities, compact)) return null;
        } else {
            data = new JsonObject();
            data.addProperty("version", 1);
            data.addProperty(idField, value);
            data.add("recipes", new JsonArray());
        }
        data.addProperty("source", source);
        data.addProperty("skipped", skipped);
        return data;
    }

    private boolean validateRow(JsonObject row, String output, Output kind, Set<List<String>> identities) {
        return matchesSource(row) && validRecipe(row, output, kind)
                && identities.add(List.of(row.get("category").getAsString(), row.get("id").getAsString()));
    }

    private boolean readGroups(JsonObject data, String output, Output kind, Set<List<String>> identities, boolean compact) {
        var groups = selectVariants(data);
        if (groups == null) return false;
        var expanded = new ArrayList<JsonObject>();
        int bytes = 2;
        for (var entry : groups) {
            var group = entry.getAsJsonObject();
            var template = group.getAsJsonObject("template");
            for (var element : group.getAsJsonArray("variants")) {
                if (!(element instanceof JsonObject variant) || !CatalogData.isId(CatalogData.string(variant.get("id")))) {
                    return false;
                }
                var row = withVariant(template, variant);
                if (row == null || !validateRow(row, output, kind, identities)) return false;
                if (!compact && bytes <= MAX_RESPONSE_BYTES) {
                    bytes += row.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                    if (bytes > MAX_RESPONSE_BYTES) expanded.clear();
                    else expanded.add(row);
                }
            }
        }
        var recipes = new JsonArray();
        if (compact) {
            data.add("groups", groups);
        } else {
            if (bytes > MAX_RESPONSE_BYTES) data.addProperty("truncated", true);
            expanded.stream().sorted(Comparator.comparing((JsonObject row) -> row.get("category").getAsString())
                    .thenComparing(row -> row.get("id").getAsString())).forEach(recipes::add);
            data.addProperty("version", 1);
            data.remove("groups");
        }
        data.add("recipes", recipes);
        return true;
    }

    private JsonArray selectVariants(JsonObject data) {
        if (!(data.get("groups") instanceof JsonObject groups) || groups.isEmpty()) return null;
        var selections = new LinkedHashMap<Integer, JsonArray>();
        for (var entry : groups.entrySet()) {
            String key = entry.getKey();
            if (!key.matches("0|[1-9][0-9]{0,8}") || !(entry.getValue() instanceof JsonArray ordinals)
                    || ordinals.isEmpty()) return null;
            selections.put(Integer.parseInt(key), ordinals);
        }
        var selected = new JsonArray();
        boolean valid = previews.visitMachineRecipeGroups(selections.keySet(), (id, group) -> {
            if (!CatalogData.versionOne(group)
                    || !(group.get("template") instanceof JsonObject template)
                    || template.has("id") || !CatalogData.boundedJson(template, 0)
                    || !(template.get("layout") instanceof JsonObject layout)
                    || !(layout.get("slots") instanceof JsonArray slots) || slots.size() > 128
                    || slots.asList().stream().anyMatch(slot -> !slot.isJsonObject())
                    || !(group.get("variants") instanceof JsonArray variants)) {
                return false;
            }
            var rows = new JsonArray();
            for (var ordinal : selections.get(id)) {
                var index = CatalogData.integer(ordinal, 0, variants.size() - 1);
                if (index == null) return false;
                rows.add(variants.get(index));
            }
            var projected = new JsonObject();
            projected.add("template", template);
            projected.add("variants", rows);
            selected.add(projected);
            return true;
        });
        return valid ? selected : null;
    }

    private static JsonObject withVariant(JsonObject template, JsonObject variant) {
        var row = template.deepCopy();
        row.add("id", variant.get("id"));
        if (!variant.has("slots")) return row;
        if (!(variant.get("slots") instanceof JsonObject replacements)) return null;
        var slots = row.getAsJsonObject("layout").getAsJsonArray("slots");
        for (var entry : replacements.entrySet()) {
            String indexText = entry.getKey();
            if (!indexText.matches("0|[1-9][0-9]{0,2}") || !(entry.getValue() instanceof JsonArray alternatives)) return null;
            int index = Integer.parseInt(indexText);
            if (index >= slots.size()) return null;
            slots.get(index).getAsJsonObject().add("alternatives", alternatives);
        }
        return row;
    }

    private boolean matchesSource(JsonObject recipe) {
        if (!recipe.has("source")) return "jei".equals(source);
        String recipeSource = CatalogData.string(recipe.get("source"));
        return recipeSource != null && (COMBINED_SOURCE.equals(source) ? VIEWERS.contains(recipeSource) : source.equals(recipeSource));
    }

    private static Set<String> readIds(JsonArray ids) {
        var result = new HashSet<String>();
        for (var id : ids) result.add(id.getAsString());
        return Set.copyOf(result);
    }

    private static boolean validRecipe(JsonObject row, String output, Output kind) {
        if (!CatalogData.boundedJson(row, 0)
                || !CatalogData.isId(CatalogData.string(row.get("id")))
                || !CatalogData.isId(CatalogData.string(row.get("category")))
                || !text(row.get("title"), 256)
                || !CatalogData.itemIds(row.get("stations")) || row.getAsJsonArray("stations").size() > 64
                || !(row.get("layout") instanceof JsonObject layout) || !dimensions(layout)
                || !(layout.get("slots") instanceof JsonArray slots) || slots.size() > 128) {
            return false;
        }

        boolean matches = false;
        for (var entry : slots) {
            if (!(entry instanceof JsonObject slot) || !validSlot(slot)) return false;
            boolean outputSlot = "output".equals(slot.get("role").getAsString());
            for (var option : slot.getAsJsonArray("alternatives")) {
                if (!(option instanceof JsonObject ingredient) || !validIngredient(ingredient)) return false;
                matches |= outputSlot && output.equals(ingredient.get("id").getAsString())
                        && kind.kind.equals(ingredient.get("kind").getAsString());
            }
        }
        return matches;
    }

    private static boolean validSlot(JsonObject slot) {
        String role = CatalogData.string(slot.get("role"));
        return role != null && ROLES.contains(role)
                && (!slot.has("name") || text(slot.get("name"), 128))
                && (!slot.has("otherIngredients") || CatalogData.bool(slot.get("otherIngredients")))
                && (!slot.has("alternativesTruncated") || CatalogData.bool(slot.get("alternativesTruncated")))
                && (!slot.has("bounds") || slot.get("bounds") instanceof JsonObject bounds && dimensions(bounds)
                && CatalogData.integer(bounds.get("x"), -MAX_DIMENSION, MAX_DIMENSION) != null
                && CatalogData.integer(bounds.get("y"), -MAX_DIMENSION, MAX_DIMENSION) != null)
                && slot.get("alternatives") instanceof JsonArray options && options.size() <= 64;
    }

    private static boolean validIngredient(JsonObject ingredient) {
        if (!CatalogData.isId(CatalogData.string(ingredient.get("id")))) return false;
        String kind = CatalogData.string(ingredient.get("kind"));
        if ("item".equals(kind)) return positiveAmount(ingredient.get("count"));
        if (ingredient.has("name") && !text(ingredient.get("name"), 256)) return false;
        if (ingredient.has("texture") && !validTextureHash(CatalogData.string(ingredient.get("texture")))) return false;
        if ("fluid".equals(kind)) return positiveAmount(ingredient.get("amountMb"));
        if (!"chemical".equals(kind)) return false;
        String amount = CatalogData.string(ingredient.get("amount"));
        if (amount == null || !amount.matches("[1-9][0-9]{0,18}")) return false;
        return amount.length() < 19 || amount.compareTo(Long.toString(Long.MAX_VALUE)) <= 0;
    }

    static boolean validTextureHash(String hash) {
        return hash != null && hash.matches("[a-f0-9]{64}");
    }

    private static boolean dimensions(JsonObject size) {
        return positiveAmount(size.get("width"), MAX_DIMENSION) && positiveAmount(size.get("height"), MAX_DIMENSION);
    }

    private static boolean positiveAmount(JsonElement value) {
        return positiveAmount(value, Integer.MAX_VALUE);
    }

    private static boolean positiveAmount(JsonElement value, int maximum) {
        return CatalogData.integer(value, 1, maximum) != null;
    }

    private static boolean text(JsonElement value, int maxLength) {
        String text = CatalogData.string(value);
        return text != null && !text.isBlank() && text.length() <= maxLength;
    }

    private enum Output {
        ITEM("item"), FLUID("fluid"), CHEMICAL("chemical");

        private final String kind;

        Output(String kind) {
            this.kind = kind;
        }
    }
}
