package com.universium.webcraft.exporter;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class ExportSettings {
    static final String FILE_NAME = "webcraft-export.toml";
    private static final List<String> SECTION_IDS = Arrays.stream(ExportSection.values()).map(ExportSection::id).toList();

    final ModConfigSpec spec;
    private final ModConfigSpec.ConfigValue<List<? extends String>> sections;
    private final ModConfigSpec.ConfigValue<List<? extends String>> ponderScenes;
    private final ModConfigSpec.IntValue ponderAtlasPageSize;
    private final ModConfigSpec.BooleanValue previewAtlas;
    private final ModConfigSpec.BooleanValue experimentalCuda;
    private final ModConfigSpec.BooleanValue experimentalBackgroundBoost;

    ExportSettings() {
        var builder = new ModConfigSpec.Builder();
        var defaults = List.of(ExportSection.ITEMS.id(), ExportSection.RECIPES.id(),
                ExportSection.TOOLTIPS.id(), ExportSection.DESIGNS.id());
        sections = builder.comment("Data sections to export from this client instance.",
                        "Available sections: " + String.join(", ", SECTION_IDS))
                .defineListAllowEmpty("sections", defaults, () -> ExportSection.ITEMS.id(),
                        value -> value instanceof String id && ExportSection.byId(id) != null);
        ponderScenes = builder.comment("Ponder schematic IDs to include. An empty list includes all registered scenes.")
                .defineListAllowEmpty("ponderScenes", List.of(), () -> "create:mechanical_pump/speed",
                        value -> value instanceof String id && id.contains(":") && ResourceLocation.tryParse(id) != null);
        ponderAtlasPageSize = builder.comment("Maximum width and height in pixels for Ponder texture atlas regions.")
                .defineInRange("ponderAtlasPageSize", 2048, 256, 2048);
        previewAtlas = builder.comment("Pack static item previews into lossless WebP atlases in WebP exports.")
                .define("previewAtlas", false);
        experimentalCuda = builder.comment("Try CUDA for comparing inventory animation frames and Ponder text textures.")
                .define("experimentalCuda", true);
        experimentalBackgroundBoost = builder.comment("Allow longer capture batches in automatic background exports.")
                .define("experimentalBackgroundBoost", false);
        spec = builder.build();
    }

    EnumSet<ExportSection> sections() {
        var selected = EnumSet.noneOf(ExportSection.class);
        for (String id : sections.get()) {
            selected.add(ExportSection.byId(id));
        }
        return selected;
    }

    void saveSections(Set<ExportSection> selected) {
        sections.set(selected.stream().sorted().map(ExportSection::id).toList());
        sections.save();
    }

    Set<String> ponderScenes() {
        String override = System.getProperty("webcraft.export.ponderScenes");
        return override == null ? Set.copyOf(ponderScenes.get()) : Set.copyOf(Arrays.asList(override.split(",")));
    }

    boolean experimentalCuda() {
        return experimentalCuda.get();
    }

    int ponderAtlasPageSize() {
        return ponderAtlasPageSize.get();
    }

    boolean previewAtlas() {
        return previewAtlas.get();
    }

    boolean experimentalBackgroundBoost() {
        return experimentalBackgroundBoost.get();
    }
}
