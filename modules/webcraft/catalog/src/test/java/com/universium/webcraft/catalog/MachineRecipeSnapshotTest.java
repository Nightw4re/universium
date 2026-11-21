package com.universium.webcraft.catalog;

import com.universium.webcraft.api.WebCraftPreviewPack;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MachineRecipeSnapshotTest {
    private static final String STONE = "minecraft:stone";
    private static final String WATER = "minecraft:water";
    private static final String LEGACY = """
            {"version":1,"source":"world_recipe_sync","coverage":{"dynamic":0,"unindexed":0,"unreadable":0},
             "resultItems":[]}
            """;
    private static final String MACHINE_INDEX = """
            {"version":1,"source":"jei","skipped":2,"resultItems":["minecraft:stone"],"resultFluids":["minecraft:water"]}
            """;
    private static final String RECIPE = """
            {"id":"example:process","category":"example:machine","title":"Machine","stations":["example:machine"],
             "layout":{"width":100,"height":55,"slots":[
              {"role":"input","bounds":{"x":2,"y":3,"width":16,"height":16},
               "alternatives":[{"kind":"item","id":"minecraft:cobblestone","count":2}]},
              {"role":"output","bounds":{"x":40,"y":3,"width":16,"height":16},
               "alternatives":[{"kind":"item","id":"minecraft:stone","count":1}]},
              {"role":"output","bounds":{"x":60,"y":3,"width":16,"height":32},
               "alternatives":[{"kind":"fluid","id":"minecraft:water","amountMb":250}]}]}}
            """;

    @Test
    void supplementsTheItemFeedAndCreatesAFluidResultFeed(@TempDir Path directory) throws IOException {
        var snapshot = snapshot(directory, Map.of(
                "catalog-data/machine-recipes.json", MACHINE_INDEX,
                "catalog-data/machine-recipes/items/minecraft/stone.json", result("itemId", STONE, RECIPE),
                "catalog-data/machine-recipes/fluids/minecraft/water.json", result("fluidId", WATER, RECIPE)));

        var item = snapshot.find("id=minecraft%3Astone");
        assertEquals(200, item.status());
        var document = item.body().getAsJsonObject();
        assertEquals("world_recipe_sync", document.get("source").getAsString());
        assertTrue(document.getAsJsonArray("recipes").isEmpty());
        var imported = document.getAsJsonObject("machineRecipes");
        assertEquals("jei", imported.get("source").getAsString());
        assertEquals(2, imported.get("skipped").getAsInt());
        var fluid = snapshot.find("fluid=minecraft%3Awater");
        assertEquals(200, fluid.status());
        var response = fluid.body().getAsJsonObject();
        assertEquals(WATER, response.get("fluidId").getAsString());
        assertEquals(imported.get("recipes"), response.get("recipes"));
        var recipe = response.getAsJsonArray("recipes").get(0).getAsJsonObject();
        var fluidSlot = recipe.getAsJsonObject("layout").getAsJsonArray("slots").get(2).getAsJsonObject();
        assertEquals(32, fluidSlot.getAsJsonObject("bounds").get("height").getAsInt());
        assertEquals(250, fluidSlot.getAsJsonArray("alternatives").get(0).getAsJsonObject().get("amountMb").getAsInt());
        assertEquals(400, snapshot.find("fluid=minecraft:water&variant=red").status());
        assertTrue(snapshot.find("fluid=minecraft:lava").body().getAsJsonObject().getAsJsonArray("recipes").isEmpty());
    }

    @Test
    void expandsAWholeVariantOrReturnsItsSharedLayout(@TempDir Path directory) throws IOException {
        var template = RECIPE.replace("\"id\":\"example:process\",", "");
        var group = """
                {"version":1,"template":%s,"variants":[
                 {"id":"example:base"},{"id":"example:changed","slots":{
                  "0":[{"kind":"item","id":"minecraft:cobblestone","count":4}],
                  "1":[{"kind":"item","id":"minecraft:stone","count":2}],"2":[]}}]}
                """.formatted(template);
        var result = """
                {"version":2,"itemId":"minecraft:stone","groups":{"5":[0,1]}}
                """;
        var snapshot = snapshot(directory, Map.of("catalog-data/machine-recipes.json", MACHINE_INDEX,
                "catalog-data/machine-recipes/items/minecraft/stone.json", result,
                "catalog-data/machine-recipes/groups/5.json", group));
        var compact = snapshot.find("id=" + STONE + "&compact=1").body().getAsJsonObject().getAsJsonObject("machineRecipes");
        assertEquals(2, compact.get("version").getAsInt());
        assertEquals(1, compact.getAsJsonArray("groups").size());
        var legacy = snapshot.find("id=" + STONE).body().getAsJsonObject().getAsJsonObject("machineRecipes");
        assertEquals(1, legacy.get("version").getAsInt());
        assertFalse(legacy.has("groups"));
        var changed = legacy.getAsJsonArray("recipes").get(1).getAsJsonObject();
        assertEquals("example:changed", changed.get("id").getAsString());
        var slots = changed.getAsJsonObject("layout").getAsJsonArray("slots");
        assertEquals(4, slots.get(0).getAsJsonObject().getAsJsonArray("alternatives").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(2, slots.get(1).getAsJsonObject().getAsJsonArray("alternatives").get(0).getAsJsonObject().get("count").getAsInt());
        assertTrue(slots.get(2).getAsJsonObject().getAsJsonArray("alternatives").isEmpty());

        var invalid = snapshot(directory, Map.of("catalog-data/machine-recipes.json", MACHINE_INDEX,
                "catalog-data/machine-recipes/items/minecraft/stone.json", result,
                "catalog-data/machine-recipes/groups/5.json", group.replace("\"2\":[]", "\"7\":[]")));
        assertFalse(invalid.find("id=" + STONE + "&compact=1").body().getAsJsonObject().has("machineRecipes"));
    }

    @Test
    void leavesLegacyRecipesReadableWhenTheMachineExportIsAbsentOrInvalid(@TempDir Path directory) throws IOException {
        var legacy = snapshot(directory, Map.of());
        assertFalse(legacy.find("id=" + STONE).body().getAsJsonObject().has("machineRecipes"));
        assertEquals(404, legacy.find("fluid=" + WATER).status());
        var invalidAmount = RECIPE.replace("250", "-1");
        var invalid = snapshot(directory, Map.of(
                "catalog-data/machine-recipes.json", MACHINE_INDEX,
                "catalog-data/machine-recipes/items/minecraft/stone.json", result("itemId", STONE, invalidAmount),
                "catalog-data/machine-recipes/fluids/minecraft/water.json", result("fluidId", WATER, invalidAmount)));
        var item = invalid.find("id=" + STONE);
        assertEquals(200, item.status());
        assertFalse(item.body().getAsJsonObject().has("machineRecipes"));
        assertEquals(404, invalid.find("fluid=" + WATER).status());
    }

    @Test
    void preservesViewerProvenanceAndRejectsInconsistentRows(@TempDir Path directory) throws IOException {
        String index = MACHINE_INDEX.replace("\"jei\"", "\"recipe_viewers\"");
        String emiRecipe = RECIPE.replace("{\"id\":\"example:process\"", "{\"source\":\"emi\",\"id\":\"example:process\"");
        var snapshot = snapshot(directory, Map.of(
                "catalog-data/machine-recipes.json", index,
                "catalog-data/machine-recipes/items/minecraft/stone.json", result("itemId", STONE, emiRecipe),
                "catalog-data/machine-recipes/fluids/minecraft/water.json", result("fluidId", WATER, RECIPE)));
        var imported = snapshot.find("id=" + STONE).body().getAsJsonObject().getAsJsonObject("machineRecipes");
        assertEquals("recipe_viewers", imported.get("source").getAsString());
        assertEquals("emi", imported.getAsJsonArray("recipes").get(0).getAsJsonObject().get("source").getAsString());
        assertEquals(404, snapshot.find("fluid=" + WATER).status());
    }

    private static String result(String field, String id, String row) {
        return "{\"version\":1,\"" + field + "\":\"" + id + "\",\"recipes\":[" + row + "]}";
    }

    private static RecipeSnapshot snapshot(Path directory, Map<String, String> entries) throws IOException {
        var archive = Files.createTempFile(directory, "previews-", ".zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            entry(zip, "format.txt", "webcraft-previews-v1/1.21.1\n");
            entry(zip, "catalog-data/recipes.json", LEGACY);
            for (var value : entries.entrySet()) entry(zip, value.getKey(), value.getValue());
        }
        return new RecipeSnapshot(Set.of(ResourceLocation.parse(STONE)), new WebCraftPreviewPack(archive));
    }

    private static void entry(ZipOutputStream zip, String path, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
