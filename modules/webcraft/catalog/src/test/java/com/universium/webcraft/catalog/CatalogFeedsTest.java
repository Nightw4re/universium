package com.universium.webcraft.catalog;

import com.universium.webcraft.api.WebCraftPreviewPack;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
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

final class CatalogFeedsTest {
    private static final ResourceLocation HELMET = ResourceLocation.parse("minecraft:leather_helmet");
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone");
    private static final String RED = "id=minecraft%3Aleather_helmet&variant=color%2Fred";
    private static final String STACK = """
            {"version":1,"count":1,"components":{"minecraft:dyed_color":{"rgb":1122867}},
             "nbt":{"minecraft:custom_data":"{bytes:[B;1B,2B]}"},"omittedComponents":[]}
            """;
    private static final String TOOLTIP = """
            {"version":1,"locale":"cs_cz","hasGraphicElements":true,
             "lines":[[{"text":"Kožená čapka","italic":true,"color":1122867}],[],[{"text":"<script>"}]]}
            """;

    @Test
    void servesPublicVariantDataEvenWhenItsIconIsUnavailable(@TempDir Path directory) throws IOException {
        var catalog = new ItemCatalog(Set.of(HELMET, STONE), pack(directory, Map.of(
                "catalog.json", """
                        [{"id":"minecraft:leather_helmet","variants":[
                          {"id":"color/red","options":[{"axis":"color","value":"red","label":"Red"}]}]},
                         {"id":"minecraft:stone"}]
                        """,
                "catalog-data/stacks/minecraft/leather_helmet@color%2Fred.json", STACK,
                "catalog-data/tooltips/minecraft/leather_helmet@color%2Fred.json", TOOLTIP,
                "catalog-data/stacks/minecraft/stone@.json", STACK,
                "catalog-data/stacks/minecraft/leather_helmet@private.json", STACK,
                "catalog-data/tooltips/minecraft/stone@.json", TOOLTIP.replace("\"italic\":true", "\"italic\":\"true\""))));
        var stack = catalog.stack(RED);
        assertEquals(200, stack.status());
        var data = stack.body().getAsJsonObject();
        assertEquals(HELMET.toString(), data.get("catalogId").getAsString());
        assertEquals(HELMET.toString(), data.get("itemId").getAsString());
        assertEquals("color/red", data.get("variantId").getAsString());
        assertEquals(1122867, data.getAsJsonObject("components").getAsJsonObject("minecraft:dyed_color").get("rgb").getAsInt());
        var tooltip = catalog.tooltip(RED).body().getAsJsonObject();
        assertEquals("cs_cz", tooltip.get("locale").getAsString());
        assertTrue(tooltip.get("hasGraphicElements").getAsBoolean());
        assertEquals(0, tooltip.getAsJsonArray("lines").get(1).getAsJsonArray().size());
        assertEquals("<script>", tooltip.getAsJsonArray("lines").get(2).getAsJsonArray().get(0).getAsJsonObject().get("text").getAsString());
        var details = catalog.detail("id=" + HELMET).body().getAsJsonObject();
        assertFalse(details.getAsJsonArray("variants").get(0).getAsJsonObject().get("hasPreview").getAsBoolean());
        assertFalse(catalog.items(null).body().getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject().has("components"));
        assertTrue(catalog.stack("id=" + STONE).body().getAsJsonObject().get("variantId").isJsonNull());
        assertError(404, "tooltip_unavailable", catalog.tooltip("id=" + STONE));
        assertError(404, "stack_data_unavailable", catalog.stack("id=" + HELMET));
        assertError(404, "variant_not_found", catalog.stack("id=" + HELMET + "&variant=private"));
        assertError(404, "item_not_found", catalog.tooltip("id=sample:client_only"));
        assertError(400, "invalid_item_id", catalog.tooltip("id=" + HELMET + "&variant=%ZZ"));
    }

    @Test
    void servesNamedTagMembersAndKeepsTheLegacyRecipeProjection(@TempDir Path directory) throws IOException {
        var archive = pack(directory, Map.of(
                "catalog-data/recipes.json", """
                        {"version":1,"source":"world_recipe_sync","coverage":{"dynamic":0,"unindexed":0,"unreadable":0},
                         "resultItems":["minecraft:stone"]}
                        """,
                "catalog-data/recipe-tags/sample/ingredients/stone.json", """
                        {"version":1,"id":"sample:ingredients/stone","items":["minecraft:cobblestone","minecraft:stone"]}
                        """,
                "catalog-data/recipes/minecraft/stone.json", """
                        {"version":2,"itemId":"minecraft:stone","recipes":[
                          {"id":"sample:craft","type":"minecraft:crafting","serializer":"minecraft:crafting_shapeless",
                           "status":"supported","station":"minecraft:crafting_table","layout":{"kind":"shapeless"},
                           "result":{"itemId":"minecraft:stone","count":1},
                           "inputs":[{"match":"items","tags":["sample:ingredients/stone"],"items":["minecraft:andesite"]}]}
                        ]}
                        """));
        var snapshot = new RecipeSnapshot(Set.of(STONE), archive);
        var compact = snapshot.find("id=" + STONE + "&compact=1").body().getAsJsonObject();
        var legacy = snapshot.find("id=" + STONE).body().getAsJsonObject();
        assertEquals(2, compact.get("version").getAsInt());
        assertEquals(1, legacy.get("version").getAsInt());
        var reference = compact.getAsJsonArray("recipes").get(0).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject();
        assertFalse(reference.has("exampleItems"));
        var expanded = legacy.getAsJsonArray("recipes").get(0).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject();
        assertEquals("[\"minecraft:andesite\",\"minecraft:cobblestone\",\"minecraft:stone\"]",
                expanded.getAsJsonArray("exampleItems").toString());
        assertEquals(200, snapshot.tag("id=sample%3Aingredients%2Fstone").status());
        assertEquals(2, snapshot.tag("id=sample:ingredients/stone").body().getAsJsonObject().getAsJsonArray("items").size());
        assertError(404, "recipe_tag_unavailable", snapshot.tag("id=sample:missing"));
    }

    @Test
    void distinguishesOldPacksMissingIndexedFilesAndEmptyRecipeResults(@TempDir Path directory) throws IOException {
        var archive = pack(directory, Map.of(
                "catalog-data/recipes.json", """
                        {"version":1,"source":"world_recipe_sync","coverage":{"dynamic":2,"unindexed":1,"unreadable":0},
                         "resultItems":["minecraft:leather_helmet","minecraft:iron_pickaxe"]}
                        """,
                "catalog-data/recipes/minecraft/leather_helmet.json", """
                        {"version":1,"itemId":"minecraft:leather_helmet","recipes":[
                          {"id":"example:machine","type":"example:machine","serializer":"example:machine","status":"unsupported",
                           "reportedResult":{"itemId":"minecraft:leather_helmet","count":1}}
                        ]}
                        """));
        var pickaxe = ResourceLocation.parse("minecraft:iron_pickaxe");
        var ids = Set.of(HELMET, STONE, pickaxe);
        var recipes = new RecipeSnapshot(ids, archive);
        var response = recipes.find("id=" + HELMET);
        assertEquals(200, response.status());
        var document = response.body().getAsJsonObject();
        assertEquals("world_recipe_sync", document.get("source").getAsString());
        assertEquals(2, document.getAsJsonObject("coverage").get("dynamic").getAsInt());
        var recipe = document.getAsJsonArray("recipes").get(0).getAsJsonObject();
        assertEquals("unsupported", recipe.get("status").getAsString());
        assertFalse(recipe.has("station"));
        assertFalse(recipe.has("result"));
        assertTrue(recipes.find("id=" + STONE).body().getAsJsonObject().getAsJsonArray("recipes").isEmpty());
        assertError(404, "recipes_unavailable", recipes.find("id=" + pickaxe));
        assertError(404, "item_not_found", recipes.find("id=sample:client_only"));
        assertError(400, "invalid_item_id", recipes.find(RED));
        var old = Files.createDirectory(directory.resolve("old"));
        assertError(404, "recipe_export_unavailable", new RecipeSnapshot(ids, pack(old, Map.of())).find("id=" + STONE));
    }

    private static WebCraftPreviewPack pack(Path directory, Map<String, String> files) throws IOException {
        var archive = directory.resolve("previews.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            write(output, "format.txt", "webcraft-previews-v1/1.21.1\n");
            for (var entry : files.entrySet()) write(output, entry.getKey(), entry.getValue());
        }
        return new WebCraftPreviewPack(archive);
    }

    private static void write(ZipOutputStream output, String path, String data) throws IOException {
        output.putNextEntry(new ZipEntry(path));
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static void assertError(int status, String error, Response response) {
        assertEquals(status, response.status());
        assertEquals(error, response.body().getAsJsonObject().get("error").getAsString());
    }
}
