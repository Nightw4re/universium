package com.universium.webcraft.exporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class MachineRecipeGroups {
    private static final int TARGET_DOCUMENT_BYTES = 1024 * 1024;

    private MachineRecipeGroups() {}

    static Map<JsonObject, Reference> write(ExportArchive.EntryWriter writer, Stream<JsonObject> recipes) throws IOException {
        var matching = new LinkedHashMap<JsonObject, List<JsonObject>>();
        Map<JsonObject, Reference> references = new IdentityHashMap<>();
        recipes.forEach(recipe -> {
            if (!references.containsKey(recipe)) {
                references.put(recipe, null);
                matching.computeIfAbsent(shape(recipe), ignored -> new ArrayList<>()).add(recipe);
            }
        });
        int groupId = 0;
        for (var rows : matching.values()) {
            var template = without(rows.getFirst(), Set.of("id"));
            int templateBytes = bytes(template).length;
            var variants = new JsonArray();
            // Reserve room for the document fields and separators.
            int length = templateBytes + 64;
            for (var row : rows) {
                var variant = variant(template, row);
                int variantBytes = bytes(variant).length + 1;
                if (!variants.isEmpty() && length + variantBytes > TARGET_DOCUMENT_BYTES) {
                    writeGroup(writer, groupId++, template, variants);
                    variants = new JsonArray();
                    length = templateBytes + 64;
                }
                references.put(row, new Reference(groupId, variants.size()));
                variants.add(variant);
                length += variantBytes;
            }
            writeGroup(writer, groupId++, template, variants);
        }
        return references;
    }

    private static JsonObject shape(JsonObject recipe) {
        var shape = without(recipe, Set.of("id", "layout"));
        var layout = without(recipe.getAsJsonObject("layout"), Set.of("slots"));
        var slots = new JsonArray();
        for (var slot : slots(recipe)) {
            slots.add(without(slot.getAsJsonObject(), Set.of("alternatives")));
        }
        layout.add("slots", slots);
        shape.add("layout", layout);
        return shape;
    }

    private static void writeGroup(ExportArchive.EntryWriter writer, int id, JsonObject template, JsonArray variants)
            throws IOException {
        var group = new JsonObject();
        group.addProperty("version", 1);
        group.add("template", template);
        group.add("variants", variants);
        writer.write("catalog-data/machine-recipes/groups/" + id + ".json", bytes(group));
    }

    private static JsonObject variant(JsonObject template, JsonObject row) {
        var baseSlots = slots(template);
        var variant = new JsonObject();
        variant.add("id", row.get("id"));
        var replacements = new JsonObject();
        var rowSlots = slots(row);
        for (int index = 0; index < baseSlots.size(); index++) {
            var alternatives = rowSlots.get(index).getAsJsonObject().getAsJsonArray("alternatives");
            if (!alternatives.equals(baseSlots.get(index).getAsJsonObject().get("alternatives"))) {
                replacements.add(Integer.toString(index), alternatives);
            }
        }
        if (!replacements.isEmpty()) variant.add("slots", replacements);
        return variant;
    }

    private static JsonObject without(JsonObject source, Set<String> excluded) {
        var copy = new JsonObject();
        source.entrySet().forEach(entry -> {
            if (!excluded.contains(entry.getKey())) copy.add(entry.getKey(), entry.getValue());
        });
        return copy;
    }

    private static JsonArray slots(JsonObject recipe) {
        return recipe.getAsJsonObject("layout").getAsJsonArray("slots");
    }

    private static byte[] bytes(JsonObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    record Reference(int groupId, int variant) {}
}
