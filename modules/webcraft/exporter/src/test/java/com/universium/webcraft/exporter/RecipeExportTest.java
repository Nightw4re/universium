package com.universium.webcraft.exporter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.registries.ModifyRegistriesEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistriesSetup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecipeExportTest {
    @BeforeAll
    static void bootstrap() throws ReflectiveOperationException {
        MinecraftTestBootstrap.initialize();
        var modBus = BusBuilder.builder().build();
        NeoForgeRegistriesSetup.setup(modBus);
        // Trigger the same registry-sync flags as NeoForge's mod-loading phase.
        var event = ModifyRegistriesEvent.class.getDeclaredConstructor();
        event.setAccessible(true);
        modBus.post(event.newInstance());
    }

    @Test
    void writesTagMembersOnceForBothIngredientSlots() throws IOException {
        var registry = BuiltInRegistries.ITEM;
        var previous = new HashMap<TagKey<Item>, List<Holder<Item>>>();
        registry.getTags().forEach(entry -> previous.put(entry.getFirst(), entry.getSecond().stream().toList()));
        var tag = TagKey.create(Registries.ITEM, ResourceLocation.parse("sample:alloys"));
        var bindings = new HashMap<>(previous);
        bindings.put(tag, List.of(Items.IRON_INGOT.builtInRegistryHolder(), Items.COPPER_INGOT.builtInRegistryHolder()));
        registry.bindTags(bindings);
        try {
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var shaped = recipe(registries, "tagged", """
                    {"type":"minecraft:crafting_shaped","pattern":["II"],
                     "key":{"I":{"tag":"sample:alloys"}},
                     "result":{"id":"minecraft:iron_pickaxe"}}
                    """);
            Map<String, JsonObject> archive = new HashMap<>();
            var exporter = new RecipeExport(registries, List.of(shaped));
            assertEquals("0 / 1 recipes checked", exporter.progress());
            assertTrue(exporter.captureNext());
            assertFalse(exporter.captureNext());
            assertEquals("1 / 1 recipes checked", exporter.progress());
            var ingredients = new ArrayList<ResourceLocation>();
            exporter.ingredientItems().forEachRemaining(ingredients::add);
            assertEquals(List.of(ResourceLocation.parse("minecraft:copper_ingot"),
                    ResourceLocation.parse("minecraft:iron_ingot")), ingredients);
            exporter.write((path, bytes) ->
                    archive.put(path, JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject()));
            var document = archive.get("catalog-data/recipes/minecraft/iron_pickaxe.json");
            var inputs = document.getAsJsonArray("recipes").get(0).getAsJsonObject().getAsJsonArray("inputs");
            assertEquals(2, document.get("version").getAsInt());
            assertEquals(JsonParser.parseString("""
                    {"match":"items","tags":["sample:alloys"]}
                    """), inputs.get(0));
            assertEquals(inputs.get(0), inputs.get(1));
            assertEquals(JsonParser.parseString("""
                    {"version":1,"id":"sample:alloys","items":["minecraft:copper_ingot","minecraft:iron_ingot"]}
                    """), archive.get("catalog-data/recipe-tags/sample/alloys.json"));
        } finally {
            var cleared = new HashMap<>(previous);
            cleared.put(tag, List.of());
            registry.bindTags(cleared);
        }
    }

    @Test
    void projectsTheClientPacketWithPatternAlternativesSlotsAndDynamicCoverage() throws IOException {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var shaped = recipe(registries, "pickaxe", """
                {"type":"minecraft:crafting_shaped","pattern":["III"," S "," S "],
                 "key":{"I":[{"item":"minecraft:iron_ingot"},{"item":"minecraft:copper_ingot"}],"S":{"item":"minecraft:stick"}},
                 "result":{"id":"minecraft:iron_pickaxe"}}
                """);
        var smithing = recipe(registries, "netherite", """
                {"type":"minecraft:smithing_transform","template":{"item":"minecraft:netherite_upgrade_smithing_template"},
                 "base":{"item":"minecraft:diamond_pickaxe"},"addition":{"item":"minecraft:netherite_ingot"},
                 "result":{"id":"minecraft:netherite_pickaxe"}}
                """);
        var dynamic = recipe(registries, "fireworks", """
                {"type":"minecraft:crafting_special_firework_rocket"}
                """);
        var packet = new ClientboundUpdateRecipesPacket(List.of(shaped, smithing, dynamic));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries, ConnectionType.NEOFORGE);
        ClientboundUpdateRecipesPacket.STREAM_CODEC.encode(buffer, packet);
        var synchronizedRecipes = ClientboundUpdateRecipesPacket.STREAM_CODEC.decode(buffer);
        buffer.release();

        Map<String, JsonObject> archive = new HashMap<>();
        capture(registries, synchronizedRecipes.getRecipes()).write((path, bytes) ->
                archive.put(path, JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject()));
        var pickaxe = archive.get("catalog-data/recipes/minecraft/iron_pickaxe.json").getAsJsonArray("recipes").get(0).getAsJsonObject();
        assertEquals("sample:pickaxe", pickaxe.get("id").getAsString());
        assertEquals("minecraft:crafting_table", pickaxe.get("station").getAsString());
        assertEquals(3, pickaxe.getAsJsonObject("layout").get("width").getAsInt());
        assertEquals(3, pickaxe.getAsJsonObject("layout").get("height").getAsInt());
        var inputs = pickaxe.getAsJsonArray("inputs");
        assertEquals(9, inputs.size());
        assertEquals(2, inputs.get(0).getAsJsonObject().getAsJsonArray("exampleItems").size());
        assertTrue(inputs.get(3).isJsonNull());
        assertEquals("minecraft:stick", inputs.get(7).getAsJsonObject().getAsJsonArray("exampleItems").get(0).getAsString());
        var netherite = archive.get("catalog-data/recipes/minecraft/netherite_pickaxe.json").getAsJsonArray("recipes").get(0).getAsJsonObject();
        assertEquals("minecraft:smithing_table", netherite.get("station").getAsString());
        assertEquals("base", netherite.getAsJsonObject("layout").getAsJsonArray("slots").get(1).getAsString());
        assertTrue(netherite.get("copiesBaseComponents").getAsBoolean());
        var index = archive.get("catalog-data/recipes.json");
        assertEquals(1, index.getAsJsonObject("coverage").get("dynamic").getAsInt());
        assertEquals(0, index.getAsJsonObject("coverage").get("unreadable").getAsInt());
    }

    private static RecipeExport capture(HolderLookup.Provider registries, Collection<RecipeHolder<?>> recipes) {
        var export = new RecipeExport(registries, recipes);
        for (int index = 0; index < recipes.size(); index++) assertTrue(export.captureNext());
        assertFalse(export.captureNext());
        return export;
    }

    private static RecipeHolder<?> recipe(RegistryAccess registries, String name, String json) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("sample", name),
                Recipe.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).getOrThrow());
    }

    @Test
    void distinguishesCookingStationsAndStonecutting() throws IOException {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        Map<String, String> stations = Map.of("smelting", "furnace", "blasting", "blast_furnace",
                "smoking", "smoker", "campfire_cooking", "campfire");
        var recipes = new ArrayList<RecipeHolder<?>>();
        for (String type : stations.keySet()) {
            recipes.add(recipe(registries, type, """
                    {"type":"minecraft:%s","ingredient":{"item":"minecraft:raw_iron"},
                     "result":{"id":"minecraft:iron_ingot"},"experience":0.7,"cookingtime":200}
                    """.formatted(type)));
        }
        recipes.add(recipe(registries, "stonecutting", """
                {"type":"minecraft:stonecutting","ingredient":{"item":"minecraft:iron_block"},
                 "result":{"id":"minecraft:iron_ingot","count":9}}
                """));
        Map<String, JsonObject> archive = new HashMap<>();
        capture(registries, recipes.reversed()).write((path, bytes) ->
                archive.put(path, JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject()));
        var entries = archive.get("catalog-data/recipes/minecraft/iron_ingot.json").getAsJsonArray("recipes");
        assertEquals(5, entries.size());
        assertEquals(List.of("sample:blasting", "sample:campfire_cooking", "sample:smelting", "sample:smoking", "sample:stonecutting"),
                entries.asList().stream().map(entry -> entry.getAsJsonObject().get("id").getAsString()).toList());
        for (var element : entries) {
            var entry = element.getAsJsonObject();
            String type = entry.get("id").getAsString().substring("sample:".length());
            assertEquals("single", entry.getAsJsonObject("layout").get("kind").getAsString());
            if (type.equals("stonecutting")) {
                assertEquals("minecraft:stonecutter", entry.get("station").getAsString());
                assertEquals(9, entry.getAsJsonObject("result").get("count").getAsInt());
            } else {
                assertEquals("minecraft:" + stations.get(type), entry.get("station").getAsString());
                assertEquals(200, entry.get("cookingTicks").getAsInt());
            }
        }
    }

    @Test
    void keepsReportedResultsFromRecipeBookHiddenRecipes() throws IOException {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var hiddenRecipe = new CustomRecipe(CraftingBookCategory.MISC) {
            @Override
            public ItemStack getResultItem(HolderLookup.Provider provider) {
                return new ItemStack(Items.IRON_INGOT, 2);
            }

            @Override
            public ItemStack assemble(CraftingInput input, HolderLookup.Provider provider) {
                return getResultItem(provider);
            }

            @Override
            public boolean matches(CraftingInput input, Level level) {
                return false;
            }

            @Override
            public boolean canCraftInDimensions(int width, int height) {
                return false;
            }

            @Override
            public RecipeSerializer<?> getSerializer() {
                return RecipeSerializer.SHAPED_RECIPE;
            }
        };
        var wrapper = new RecipeHolder<>(ResourceLocation.parse("sample:hidden"), hiddenRecipe);
        Map<String, JsonObject> archive = new HashMap<>();
        capture(registries, List.of(wrapper)).write((path, bytes) ->
                archive.put(path, JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject()));
        var row = archive.get("catalog-data/recipes/minecraft/iron_ingot.json")
                .getAsJsonArray("recipes").get(0).getAsJsonObject();
        assertEquals("unsupported", row.get("status").getAsString());
        assertEquals(2, row.getAsJsonObject("reportedResult").get("count").getAsInt());
        assertFalse(row.has("station"));
        assertEquals(0, archive.get("catalog-data/recipes.json").getAsJsonObject("coverage").get("dynamic").getAsInt());
    }
}
