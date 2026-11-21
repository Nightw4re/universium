package com.universium.webcraft.exporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

final class ExportDiagnostics {
    static final String FILE = "export-diagnostics.json";

    private final TreeSet<MissingImage> missing = new TreeSet<>(Comparator
            .comparing(MissingImage::kind).thenComparing(MissingImage::id)
            .thenComparing(MissingImage::variant).thenComparing(MissingImage::sprite)
            .thenComparing(MissingImage::reason));
    private final HashSet<Material> checkedMaterials = new HashSet<>();
    private int skippedInventory;

    void skippedItem(String id, String variant, String reason) {
        if (id == null) {
            skippedInventory++;
        } else {
            missing.add(new MissingImage("item", id, variant == null ? "" : variant, "", reason));
        }
    }

    void checkedMaterial(String kind, String id, String sprite, String reason) {
        checkedMaterials.add(new Material(kind, id));
        if (reason != null) {
            missing.add(new MissingImage(kind, id, "", sprite == null ? "" : sprite, reason));
        }
    }

    int missingImages() {
        return missing.size();
    }

    void write(ExportArchive.EntryWriter writer, Set<ExportSection> sections, boolean catalog, int catalogItems, boolean recipeViewerAvailable,
               int skippedMachineRecipes) throws IOException {
        var report = new JsonObject();
        report.addProperty("version", 1);
        var selected = new JsonArray();
        sections.stream().sorted().map(ExportSection::id).forEach(selected::add);
        report.add("selectedSections", selected);
        report.addProperty("catalogExported", catalog);
        report.addProperty("catalogItems", catalogItems);
        report.addProperty("recipeViewerAvailable", recipeViewerAvailable);
        report.addProperty("recipeMaterialsChecked", checkedMaterials.size());
        report.addProperty("skippedMachineRecipes", skippedMachineRecipes);
        report.addProperty("skippedInventoryPreviews", skippedInventory);
        var entries = new JsonArray();
        for (var issue : missing) {
            var entry = new JsonObject();
            entry.addProperty("kind", issue.kind());
            entry.addProperty("id", issue.id());
            if (!issue.variant().isEmpty()) entry.addProperty("variant", issue.variant());
            if (!issue.sprite().isEmpty()) entry.addProperty("sprite", issue.sprite());
            entry.addProperty("reason", issue.reason());
            entries.add(entry);
        }
        report.add("missingImages", entries);
        writer.write(FILE, report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private record Material(String kind, String id) {}
    private record MissingImage(String kind, String id, String variant, String sprite, String reason) {}
}
