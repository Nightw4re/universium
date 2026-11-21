package com.universium.webcraft.exporter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MachineRecipeExportTest {
    @Test
    void sharesTheLayoutAndStoresAllChangedSlotsInTheSameVariant() throws IOException {
        var first = JsonParser.parseString("""
                {"id":"example:first","category":"example:canner","title":"Canner","source":"jei","stations":["example:canner"],
                 "layout":{"width":80,"height":34,"slots":[
                   {"role":"input","alternatives":[{"kind":"item","id":"example:can","count":1}]},
                   {"role":"output","alternatives":[{"kind":"item","id":"example:food","count":1}]},
                   {"role":"output","alternatives":[{"kind":"item","id":"example:jar","count":1}]}]}}
                """).getAsJsonObject();
        var second = JsonParser.parseString(first.toString().replace("example:first", "example:second")
                .replace("\"count\":1", "\"count\":2")).getAsJsonObject();
        second.getAsJsonObject("layout").getAsJsonArray("slots").get(2).getAsJsonObject()
                .add("alternatives", new com.google.gson.JsonArray());
        var other = first.deepCopy();
        other.addProperty("title", "Another machine");
        Map<String, JsonObject> archive = new HashMap<>();
        var refs = MachineRecipeGroups.write((path, bytes) -> archive.put(path,
                JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject()),
                Stream.of(first, first, second, other));
        assertEquals(2, archive.size());
        assertEquals(3, refs.size());
        assertEquals(refs.get(first).groupId(), refs.get(second).groupId());
        var group = archive.get("catalog-data/machine-recipes/groups/0.json");
        assertFalse(group.getAsJsonObject("template").has("id"));
        assertEquals(JsonParser.parseString("{\"id\":\"example:first\"}"), group.getAsJsonArray("variants").get(0));
        var changed = group.getAsJsonArray("variants").get(1).getAsJsonObject().getAsJsonObject("slots");
        assertEquals(2, changed.getAsJsonArray("0").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(2, changed.getAsJsonArray("1").get(0).getAsJsonObject().get("count").getAsInt());
        assertTrue(changed.getAsJsonArray("2").isEmpty());
    }

    @Test
    void indexesEachOutputAndKeepsAnIngredientFromBecomingAResult() throws IOException {
        var recipe = JsonParser.parseString("""
                {"id":"example:mixing","category":"example:mixer","title":"Mixer","stations":["example:mixer"],
                 "layout":{"width":90,"height":50,"slots":[
                    {"role":"input","bounds":{"x":1,"y":2,"width":16,"height":30},
                     "alternatives":[{"kind":"fluid","id":"minecraft:water","amountMb":250}]},
                    {"role":"output","bounds":{"x":70,"y":2,"width":16,"height":16},
                     "alternatives":[{"kind":"item","id":"example:product","count":2}]},
                    {"role":"output","bounds":{"x":70,"y":20,"width":16,"height":20},
                     "alternatives":[{"kind":"fluid","id":"example:byproduct","amountMb":125}]}]}}
                """).getAsJsonObject();
        List<Supplier<JsonObject>> recipes = List.of(() -> recipe, () -> {
            throw new IllegalStateException("A recipe that cannot be inspected");
        }, () -> recipe.deepCopy());
        var fallback = recipe.deepCopy();
        fallback.addProperty("id", "example:pressing");
        fallback.addProperty("category", "example:press");
        var export = new MachineRecipeExport(List.of(
                new MachineRecipeExport.Source("jei", categories -> recipes.iterator()),
                new MachineRecipeExport.Source("emi", categories -> {
                    assertEquals(java.util.Set.of("example:mixer"), categories);
                    List<Supplier<JsonObject>> sources = List.of(() -> fallback);
                    return sources.iterator();
                })), null);
        assertTrue(export.captureNext());
        assertTrue(export.captureNext());
        assertTrue(export.captureNext());
        assertTrue(export.captureNext());
        assertFalse(export.captureNext());
        Map<String, JsonObject> archive = new HashMap<>();
        export.write((path, bytes) -> archive.put(path,
                JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject()));

        var index = archive.get("catalog-data/machine-recipes.json");
        assertEquals("recipe_viewers", index.get("source").getAsString());
        assertEquals(1, index.get("skipped").getAsInt());
        assertEquals("example:product", index.getAsJsonArray("resultItems").get(0).getAsString());
        assertEquals("example:byproduct", index.getAsJsonArray("resultFluids").get(0).getAsString());
        var items = archive.get("catalog-data/machine-recipes/items/example/product.json");
        var fluids = archive.get("catalog-data/machine-recipes/fluids/example/byproduct.json");
        assertEquals(2, items.get("version").getAsInt());
        assertEquals(items.get("groups"), fluids.get("groups"));
        assertEquals(2, items.getAsJsonObject("groups").size());
        var group = archive.get("catalog-data/machine-recipes/groups/0.json");
        var restored = group.getAsJsonObject("template").deepCopy();
        restored.add("id", group.getAsJsonArray("variants").get(0).getAsJsonObject().get("id"));
        assertEquals(recipe, restored);
        assertEquals("emi", archive.get("catalog-data/machine-recipes/groups/1.json")
                .getAsJsonObject("template").get("source").getAsString());
        assertFalse(archive.containsKey("catalog-data/machine-recipes/fluids/minecraft/water.json"));
    }
}
