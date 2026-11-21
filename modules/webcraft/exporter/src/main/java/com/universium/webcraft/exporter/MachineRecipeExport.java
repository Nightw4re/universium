package com.universium.webcraft.exporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

final class MachineRecipeExport {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;
    private final Iterator<Source> sources;
    private final MachineRecipeMaterials materials;
    private final Map<String, Map<String, JsonObject>> byItem = new TreeMap<>();
    private final Map<String, Map<String, JsonObject>> byFluid = new TreeMap<>();
    private final Map<String, Map<String, JsonObject>> byChemical = new TreeMap<>();
    private final Set<String> categories = new HashSet<>();
    private final Set<String> contributingSources = new HashSet<>();
    private Iterator<Supplier<JsonObject>> recipes = Collections.emptyIterator();
    private String source;
    private String firstAvailableSource;
    private int processed;
    private int skipped;

    MachineRecipeExport(List<Source> sources, MachineRecipeMaterials materials) {
        this.sources = sources.iterator();
        this.materials = materials;
    }

    static MachineRecipeExport start(StaticPreviewWriter previews, ExportDiagnostics diagnostics) {
        var modList = ModList.get();
        if (!modList.isLoaded("jei") && !modList.isLoaded("emi")) return null;
        var materials = new MachineRecipeMaterials(previews, diagnostics);
        var sources = new ArrayList<Source>(2);
        if (modList.isLoaded("jei")) sources.add(new Source("jei", unused -> JeiMachineRecipePlugin.captures(materials)));
        if (modList.isLoaded("emi")) {
            sources.add(new Source("emi", exported -> {
                var excluded = new HashSet<>(exported);
                if (modList.isLoaded("jei")) excluded.addAll(JeiMachineRecipePlugin.categoryIds());
                return EmiMachineRecipes.captures(excluded, materials);
            }));
        }
        return new MachineRecipeExport(sources, materials);
    }

    boolean captureNext() {
        while (true) {
            try {
                if (recipes.hasNext()) {
                    capture(recipes.next());
                    return true;
                }
            } catch (RuntimeException | LinkageError error) {
                LOGGER.warn("WebCraft could not continue the optional {} recipe export", source, error);
                skipped++;
            }
            if (!sources.hasNext()) return false;
            var next = sources.next();
            source = next.name();
            recipes = Collections.emptyIterator();
            try {
                var available = next.open().apply(Set.copyOf(categories));
                if (available != null) {
                    recipes = available;
                    if (firstAvailableSource == null) firstAvailableSource = source;
                }
            } catch (RuntimeException | LinkageError error) {
                LOGGER.warn("WebCraft could not start the optional {} recipe export", source, error);
                skipped++;
            }
        }
    }

    private void capture(Supplier<JsonObject> capture) {
        processed++;
        try {
            var recipe = capture.get();
            if (recipe == null) return;
            recipe.addProperty("source", source);
            indexOutputs(recipe);
        } catch (RuntimeException | LinkageError error) {
            LOGGER.debug("WebCraft could not capture a {} recipe", source, error);
            skipped++;
        }
    }

    private void indexOutputs(JsonObject recipe) {
        String category = recipe.get("category").getAsString();
        String key = category + ' ' + recipe.get("id").getAsString();
        for (var entry : recipe.getAsJsonObject("layout").getAsJsonArray("slots")) {
            var slot = entry.getAsJsonObject();
            if (!"output".equals(slot.get("role").getAsString())) continue;
            for (var alternative : slot.getAsJsonArray("alternatives")) {
                var ingredient = alternative.getAsJsonObject();
                var index = switch (ingredient.get("kind").getAsString()) {
                    case "item" -> byItem;
                    case "fluid" -> byFluid;
                    case "chemical" -> byChemical;
                    default -> throw new IllegalArgumentException("Unsupported machine recipe output kind");
                };
                index.computeIfAbsent(ingredient.get("id").getAsString(), unused -> new TreeMap<>())
                        .putIfAbsent(key, recipe);
                contributingSources.add(source);
                categories.add(category);
            }
        }
    }

    String progress() {
        return processed + " recipes checked";
    }

    boolean available() {
        return firstAvailableSource != null;
    }

    int skipped() {
        return skipped;
    }

    void write(ExportArchive.EntryWriter writer) throws IOException {
        if (firstAvailableSource == null) return;
        var index = new JsonObject();
        index.addProperty("version", 1);
        index.addProperty("source", contributingSources.size() > 1 ? "recipe_viewers"
                : contributingSources.stream().findFirst().orElse(firstAvailableSource));
        index.addProperty("skipped", skipped);
        var definitions = Stream.of(byItem, byFluid, byChemical).flatMap(results -> results.values().stream())
                .flatMap(results -> results.values().stream());
        var references = MachineRecipeGroups.write(writer, definitions);
        index.add("resultItems", writeResults(writer, byItem, references, "items", "itemId"));
        index.add("resultFluids", writeResults(writer, byFluid, references, "fluids", "fluidId"));
        if (!byChemical.isEmpty()) {
            index.add("resultChemicals", writeResults(writer, byChemical, references, "chemicals", "chemicalId"));
        }
        if (materials != null) materials.write(writer);
        writer.write("catalog-data/machine-recipes.json", bytes(index));
    }

    private static JsonArray writeResults(ExportArchive.EntryWriter writer, Map<String, Map<String, JsonObject>> index,
                                          Map<JsonObject, MachineRecipeGroups.Reference> references,
                                          String directory, String field) throws IOException {
        var resultIds = new JsonArray();
        for (var entry : index.entrySet()) {
            var document = new JsonObject();
            document.addProperty("version", 2);
            document.addProperty(field, entry.getKey());
            var groups = new JsonObject();
            for (var recipe : entry.getValue().values()) {
                var reference = references.get(recipe);
                String group = Integer.toString(reference.groupId());
                if (!groups.has(group)) groups.add(group, new JsonArray());
                groups.getAsJsonArray(group).add(reference.variant());
            }
            document.add("groups", groups);
            byte[] content = bytes(document);
            if (content.length > MAX_DOCUMENT_BYTES) {
                document.addProperty("version", 1);
                document.remove("groups");
                document.add("recipes", new JsonArray());
                document.addProperty("truncated", true);
                content = bytes(document);
            }
            writer.write("catalog-data/machine-recipes/" + directory + "/" + entry.getKey().replace(':', '/') + ".json", content);
            resultIds.add(entry.getKey());
        }
        return resultIds;
    }

    private static byte[] bytes(JsonObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    record Source(String name, Function<Set<String>, Iterator<Supplier<JsonObject>>> open) {}
}
