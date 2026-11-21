package com.universium.webcraft.exporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.FireworkRocketRecipe;
import net.minecraft.world.item.crafting.FireworkStarRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.MapExtendingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class RecipeExport {
    private static final int MAX_EXAMPLES = 32;
    private static final Set<Class<?>> DYNAMIC_DISPLAY_RESULTS = Set.of(
            MapExtendingRecipe.class, SmithingTrimRecipe.class, FireworkRocketRecipe.class, FireworkStarRecipe.class);
    private static final Map<Class<?>, String> STATIONS = Map.of(
            ShapedRecipe.class, "minecraft:crafting_table",
            ShapelessRecipe.class, "minecraft:crafting_table",
            SmeltingRecipe.class, "minecraft:furnace",
            BlastingRecipe.class, "minecraft:blast_furnace",
            SmokingRecipe.class, "minecraft:smoker",
            CampfireCookingRecipe.class, "minecraft:campfire",
            StonecutterRecipe.class, "minecraft:stonecutter",
            SmithingTransformRecipe.class, "minecraft:smithing_table");
    private final Map<String, List<JsonObject>> results = new TreeMap<>();
    private final Map<Ingredient, JsonElement> ingredients = new HashMap<>();
    private final Map<ResourceLocation, byte[]> recipeTags = new TreeMap<>();
    private final Set<ResourceLocation> ingredientItems = new TreeSet<>();
    private final RegistryOps<JsonElement> ops;
    private final HolderLookup.Provider registries;
    private final Iterator<RecipeHolder<?>> pending;
    private final int total;
    private int processed;
    private int dynamic;
    private int unindexed;
    private int unreadable;

    RecipeExport(HolderLookup.Provider registries, Collection<RecipeHolder<?>> synchronizedRecipes) {
        this.registries = registries;
        ops = registries.createSerializationContext(JsonOps.INSTANCE);
        pending = synchronizedRecipes.iterator();
        total = synchronizedRecipes.size();
    }

    boolean captureNext() {
        if (!pending.hasNext()) {
            ingredients.clear();
            return false;
        }
        capture(pending.next());
        processed++;
        return true;
    }

    String progress() {
        return processed + " / " + total + " recipes checked";
    }

    Iterator<ResourceLocation> ingredientItems() {
        return ingredientItems.iterator();
    }

    private void capture(RecipeHolder<?> holder) {
        try {
            Recipe<?> recipe = holder.value();
            // These display stacks do not identify a fixed output.
            if (DYNAMIC_DISPLAY_RESULTS.contains(recipe.getClass())) {
                dynamic++;
                return;
            }
            // isSpecial() controls the vanilla recipe book. Mod machines can still report an item result.
            ItemStack stack = recipe.getResultItem(registries);
            if (stack.isEmpty()) {
                if (recipe instanceof CustomRecipe) {
                    dynamic++;
                } else {
                    unindexed++;
                }
                return;
            }
            String itemId = itemId(stack);
            var result = new JsonObject();
            result.addProperty("itemId", itemId);
            result.addProperty("count", stack.getCount());
            var row = new JsonObject();
            row.addProperty("id", holder.id().toString());
            row.addProperty("type", BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());
            row.addProperty("serializer", BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()).toString());
            String station = STATIONS.get(recipe.getClass());
            if (station == null) {
                row.addProperty("status", "unsupported");
                row.add("reportedResult", result);
            } else {
                row.addProperty("status", "supported");
                row.addProperty("station", station);
                row.add("result", result);
                writeInputs(recipe, row);
            }
            results.computeIfAbsent(itemId, unused -> new ArrayList<>()).add(row);
        } catch (RuntimeException ignored) {
            // Some mod recipes cannot be inspected even though they can be synchronized.
            unreadable++;
        }
    }

    private void writeInputs(Recipe<?> recipe, JsonObject row) {
        var layout = new JsonObject();
        var inputs = new JsonArray();
        if (recipe instanceof SmithingTransformRecipe smithing) {
            layout.addProperty("kind", "slots");
            var slots = new JsonArray();
            var encoded = RecipeSerializer.SMITHING_TRANSFORM.codec().codec()
                    .encodeStart(ops, smithing).getOrThrow().getAsJsonObject();
            for (String slot : List.of("template", "base", "addition")) {
                slots.add(slot);
                inputs.add(ingredient(Ingredient.CODEC.parse(ops, encoded.get(slot)).getOrThrow()));
            }
            layout.add("slots", slots);
            row.addProperty("copiesBaseComponents", true);
        } else {
            if (recipe instanceof ShapedRecipe shaped) {
                layout.addProperty("kind", "grid");
                layout.addProperty("width", shaped.getWidth());
                layout.addProperty("height", shaped.getHeight());
                row.addProperty("fitsPlayerGrid", shaped.canCraftInDimensions(2, 2));
            } else if (recipe instanceof ShapelessRecipe shapeless) {
                layout.addProperty("kind", "shapeless");
                row.addProperty("fitsPlayerGrid", shapeless.canCraftInDimensions(2, 2));
            } else {
                layout.addProperty("kind", "single");
            }
            recipe.getIngredients().forEach(input -> inputs.add(ingredient(input)));
        }
        if (recipe instanceof AbstractCookingRecipe cooking) {
            row.addProperty("cookingTicks", cooking.getCookingTime());
            row.addProperty("experience", cooking.getExperience());
        }
        row.add("layout", layout);
        row.add("inputs", inputs);
    }

    private JsonElement ingredient(Ingredient input) {
        if (input.isEmpty()) return JsonNull.INSTANCE;
        return ingredients.computeIfAbsent(input, value -> {
            var tagged = taggedIngredient(value);
            if (tagged != null) return tagged;
            var ingredient = new JsonObject();
            var examples = Arrays.stream(value.getItems()).filter(stack -> !stack.isEmpty())
                    .map(RecipeExport::itemId).distinct().sorted().limit(MAX_EXAMPLES + 1L).toList();
            var items = new JsonArray();
            examples.stream().limit(MAX_EXAMPLES).forEach(id -> {
                items.add(id);
                ingredientItems.add(ResourceLocation.parse(id));
            });
            ingredient.add("exampleItems", items);
            if (examples.size() > MAX_EXAMPLES) ingredient.addProperty("examplesTruncated", true);
            if (value.isCustom()) {
                ingredient.addProperty("match", "custom");
                Ingredient.CODEC.encodeStart(ops, value).result()
                        .ifPresent(definition -> ingredient.add("definition", definition));
            } else {
                ingredient.addProperty("match", "items");
                var tags = new JsonArray();
                for (var entry : value.getValues()) {
                    if (entry instanceof Ingredient.TagValue tag) tags.add(tag.tag().location().toString());
                }
                if (!tags.isEmpty()) ingredient.add("tags", tags);
            }
            return ingredient;
        });
    }

    private JsonObject taggedIngredient(Ingredient ingredient) {
        if (ingredient.isCustom()) return null;
        var tags = new JsonArray();
        var items = new JsonArray();
        for (var value : ingredient.getValues()) {
            if (value instanceof Ingredient.TagValue tag) {
                var name = tag.tag().location();
                if (recipeTags.computeIfAbsent(name, unused -> tagDocument(tag.tag())) == null) return null;
                tags.add(name.toString());
            } else if (value instanceof Ingredient.ItemValue item) {
                if (!item.item().isEmpty()) items.add(itemId(item.item()));
            } else {
                return null;
            }
        }
        if (tags.isEmpty()) return null;
        var row = new JsonObject();
        row.addProperty("match", "items");
        row.add("tags", tags);
        if (!items.isEmpty()) {
            row.add("items", items);
            items.forEach(id -> ingredientItems.add(ResourceLocation.parse(id.getAsString())));
        }
        return row;
    }

    private byte[] tagDocument(TagKey<Item> key) {
        var tag = registries.lookup(Registries.ITEM).flatMap(items -> items.get(key));
        if (tag.isEmpty()) return null;
        var items = new JsonArray();
        tag.get().stream().map(holder -> BuiltInRegistries.ITEM.getKey(holder.value()).toString())
                .distinct().sorted().forEach(items::add);
        var data = new JsonObject();
        data.addProperty("version", 1);
        data.addProperty("id", key.location().toString());
        data.add("items", items);
        byte[] bytes = jsonBytes(data);
        if (bytes.length > 4 * 1024 * 1024) return null;
        items.forEach(id -> ingredientItems.add(ResourceLocation.parse(id.getAsString())));
        return bytes;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    void write(ExportArchive.EntryWriter writer) throws IOException {
        for (var entry : recipeTags.entrySet()) {
            var id = entry.getKey();
            writer.write("catalog-data/recipe-tags/" + id.getNamespace() + "/" + id.getPath() + ".json", entry.getValue());
        }
        var itemIds = new JsonArray();
        for (var entry : results.entrySet()) {
            var recipes = new JsonObject();
            recipes.addProperty("version", 2);
            recipes.addProperty("itemId", entry.getKey());
            var rows = new JsonArray();
            entry.getValue().stream().sorted(Comparator.comparing(row -> row.get("id").getAsString())).forEach(rows::add);
            recipes.add("recipes", rows);
            byte[] bytes = jsonBytes(recipes);
            if (bytes.length > 4 * 1024 * 1024) {
                recipes.add("recipes", new JsonArray());
                recipes.addProperty("truncated", true);
                bytes = jsonBytes(recipes);
            }
            writer.write("catalog-data/recipes/" + entry.getKey().replace(':', '/') + ".json", bytes);
            itemIds.add(entry.getKey());
        }
        var coverage = new JsonObject();
        coverage.addProperty("dynamic", dynamic);
        coverage.addProperty("unindexed", unindexed);
        coverage.addProperty("unreadable", unreadable);
        var index = new JsonObject();
        index.addProperty("version", 1);
        index.addProperty("source", "world_recipe_sync");
        index.add("coverage", coverage);
        index.add("resultItems", itemIds);
        writer.write("catalog-data/recipes.json", jsonBytes(index));
    }

    private static byte[] jsonBytes(JsonElement value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }
}
