package com.universium.webcraft.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftPreviewPack;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class RecipeSnapshot {
    private static final String SOURCE = "world_recipe_sync";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final Set<ResourceLocation> registered;
    private final WebCraftPreviewPack previews;
    private final Set<String> indexed;
    private final JsonObject coverage;
    private final MachineRecipeSnapshot machines;

    RecipeSnapshot(Set<ResourceLocation> registered, WebCraftPreviewPack previews) {
        this.registered = Set.copyOf(registered);
        this.previews = previews;
        machines = new MachineRecipeSnapshot(previews);
        JsonObject index = previews.readRecipeIndex();
        var ids = new HashSet<String>();
        if (CatalogData.versionOne(index) && SOURCE.equals(CatalogData.string(index.get("source")))
                && CatalogData.itemIds(index.get("resultItems")) && index.get("coverage") instanceof JsonObject counts
                && validCoverage(counts)) {
            coverage = counts;
            index.getAsJsonArray("resultItems").forEach(id -> ids.add(id.getAsString()));
        } else {
            coverage = null;
        }
        indexed = Set.copyOf(ids);
    }

    Response find(String query) {
        String compactParameter = "&compact=1";
        boolean compact = query != null && query.endsWith(compactParameter);
        if (compact) query = query.substring(0, query.length() - compactParameter.length());
        if (query != null && query.startsWith("fluid=")) return machines.forFluid(query, compact);
        if (query != null && query.startsWith("chemical=")) return machines.forChemical(query, compact);
        var selection = ItemCatalog.selection(query, false);
        if (selection == null) return Response.error(400, "invalid_item_id");
        ResourceLocation id = ResourceLocation.parse(selection.id());
        if (!registered.contains(id)) return Response.error(404, "item_not_found");
        if (coverage == null) return Response.error(404, "recipe_export_unavailable");
        var data = new JsonObject();
        data.addProperty("version", 1);
        data.addProperty("itemId", selection.id());
        data.addProperty("source", SOURCE);
        data.add("coverage", coverage);
        var machineRecipes = machines.forItem(id, compact);
        if (machineRecipes != null) data.add("machineRecipes", machineRecipes);
        if (!indexed.contains(selection.id())) {
            data.add("recipes", new JsonArray());
            return Response.ok(data);
        }
        var exported = previews.readRecipes(id);
        boolean referencesTags = CatalogData.integer(exported.get("version"), 2, 2) != null;
        if ((!referencesTags && !CatalogData.versionOne(exported))
                || !selection.id().equals(CatalogData.string(exported.get("itemId")))
                || !(exported.get("recipes") instanceof JsonArray recipes)) {
            return Response.error(404, "recipes_unavailable");
        }
        if (exported.has("truncated")) {
            if (!CatalogData.bool(exported.get("truncated"))) return Response.error(404, "recipes_unavailable");
            data.add("truncated", exported.get("truncated"));
        }
        var ids = new HashSet<String>();
        Map<String, JsonArray> tags = new HashMap<>();
        long bytes = 2;
        for (var recipe : recipes) {
            if (!(recipe instanceof JsonObject row) || !validRecipe(row, selection.id(), referencesTags)
                    || referencesTags && !compact && !inlineExamples(row, tags)
                    || !ids.add(row.get("id").getAsString())) {
                return Response.error(404, "recipes_unavailable");
            }
            if (referencesTags && !compact) {
                bytes += row.toString().getBytes(StandardCharsets.UTF_8).length + 1L;
                if (bytes > MAX_RESPONSE_BYTES) {
                    data.addProperty("truncated", true);
                    data.add("recipes", new JsonArray());
                    return Response.ok(data);
                }
            }
        }
        if (referencesTags && compact) data.addProperty("version", 2);
        data.add("recipes", recipes);
        return Response.ok(data);
    }

    Response texture(String query) {
        String hash = query != null && query.startsWith("hash=") ? query.substring("hash=".length()) : null;
        if (!MachineRecipeSnapshot.validTextureHash(hash)) return Response.error(400, "invalid_texture_hash");
        var texture = previews.readMachineRecipeTexture(hash);
        return texture == null ? Response.error(404, "texture_not_found") : Response.icon(texture);
    }

    Response tag(String query) {
        var selection = ItemCatalog.selection(query, false);
        if (selection == null) return Response.error(400, "invalid_tag_id");
        var data = previews.readRecipeTag(ResourceLocation.parse(selection.id()));
        return validTag(data, selection.id()) ? Response.ok(data) : Response.error(404, "recipe_tag_unavailable");
    }

    private static boolean validTag(JsonObject data, String id) {
        return CatalogData.versionOne(data) && id.equals(CatalogData.string(data.get("id")))
                && CatalogData.itemIds(data.get("items"));
    }

    private boolean inlineExamples(JsonObject row, Map<String, JsonArray> tags) {
        if (!(row.get("inputs") instanceof JsonArray inputs)) return true;
        for (var element : inputs) {
            if (element.isJsonNull()) continue;
            var input = element.getAsJsonObject();
            if (input.has("exampleItems")) continue;
            var examples = new TreeSet<String>();
            if (input.get("items") instanceof JsonArray items) collectExamples(items, examples);
            for (var ref : input.getAsJsonArray("tags")) {
                var id = ref.getAsString();
                var members = tags.computeIfAbsent(id, unused -> {
                    var tag = previews.readRecipeTag(ResourceLocation.parse(id));
                    return validTag(tag, id) ? tag.getAsJsonArray("items") : null;
                });
                if (members == null) return false;
                collectExamples(members, examples);
            }
            var values = new JsonArray();
            examples.stream().limit(32).forEach(values::add);
            input.remove("items");
            input.add("exampleItems", values);
            if (examples.size() > 32) input.addProperty("examplesTruncated", true);
        }
        return true;
    }

    private static void collectExamples(JsonArray members, TreeSet<String> examples) {
        for (var member : members) {
            String id = member.getAsString();
            if (!id.equals("minecraft:air")) examples.add(id);
            if (examples.size() > 33) examples.pollLast();
        }
    }

    private static boolean validCoverage(JsonObject value) {
        return value.keySet().equals(Set.of("dynamic", "unindexed", "unreadable"))
                && value.entrySet().stream().allMatch(entry -> CatalogData.integer(entry.getValue(), 0, Integer.MAX_VALUE) != null);
    }

    private static boolean validRecipe(JsonObject row, String itemId, boolean referencesTags) {
        if (!CatalogData.boundedJson(row, 0)) return false;
        for (String field : List.of("id", "type", "serializer")) {
            if (!CatalogData.isId(CatalogData.string(row.get(field)))) return false;
        }
        String status = CatalogData.string(row.get("status"));
        if ("unsupported".equals(status)) {
            return row.keySet().equals(Set.of("id", "type", "serializer", "status", "reportedResult"))
                    && validResult(row.get("reportedResult"), itemId);
        }
        if (!"supported".equals(status) || !validResult(row.get("result"), itemId)
                || !CatalogData.isId(CatalogData.string(row.get("station")))
                || !(row.get("layout") instanceof JsonObject layout)
                || !(row.get("inputs") instanceof JsonArray inputs)) {
            return false;
        }
        for (var value : inputs) {
            if (!value.isJsonNull() && !validIngredient(value, referencesTags)) return false;
        }
        return validLayout(layout, inputs.size())
                && (!row.has("fitsPlayerGrid") || CatalogData.bool(row.get("fitsPlayerGrid")))
                && (!row.has("copiesBaseComponents") || CatalogData.bool(row.get("copiesBaseComponents")))
                && (!row.has("cookingTicks") || CatalogData.integer(row.get("cookingTicks"), 0, Integer.MAX_VALUE) != null)
                && (!row.has("experience") || row.get("experience").isJsonPrimitive()
                        && row.getAsJsonPrimitive("experience").isNumber() && Double.isFinite(row.get("experience").getAsDouble()));
    }

    private static boolean validLayout(JsonObject layout, int size) {
        String kind = CatalogData.string(layout.get("kind"));
        if (kind == null) return false;
        return switch (kind) {
            case "grid" -> {
                Integer width = CatalogData.integer(layout.get("width"), 1, size);
                Integer height = CatalogData.integer(layout.get("height"), 1, size);
                yield width != null && height != null && (long) width * height == size;
            }
            case "slots" -> layout.get("slots") instanceof JsonArray slots && slots.size() == size
                    && slots.asList().stream().allMatch(slot -> CatalogData.string(slot) != null);
            case "single" -> size == 1;
            case "shapeless" -> size > 0;
            default -> false;
        };
    }

    private static boolean validIngredient(JsonElement value, boolean referencesTags) {
        if (!(value instanceof JsonObject input) || input.has("tags") && !CatalogData.itemIds(input.get("tags"))
                || input.has("examplesTruncated") && !CatalogData.bool(input.get("examplesTruncated"))) {
            return false;
        }
        String match = CatalogData.string(input.get("match"));
        if (!input.has("exampleItems")) {
            return referencesTags && "items".equals(match) && input.get("tags") instanceof JsonArray tags && !tags.isEmpty()
                    && (!input.has("items") || CatalogData.itemIds(input.get("items")))
                    && input.keySet().stream().allMatch(Set.of("match", "tags", "items")::contains);
        }
        if (!CatalogData.itemIds(input.get("exampleItems"))) return false;
        return "items".equals(match) || "custom".equals(match);
    }

    private static boolean validResult(JsonElement value, String itemId) {
        return value instanceof JsonObject result && itemId.equals(CatalogData.string(result.get("itemId")))
                && CatalogData.integer(result.get("count"), 1, Integer.MAX_VALUE) != null;
    }
}
