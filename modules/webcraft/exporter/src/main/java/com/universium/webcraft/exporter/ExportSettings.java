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

    ExportSettings() {
        var builder = new ModConfigSpec.Builder();
        var defaults = SECTION_IDS.stream().filter(id -> !id.equals(ExportSection.PONDER.id())).toList();
        sections = builder.comment("Data sections to export from this client instance.",
                        "Available sections: " + String.join(", ", SECTION_IDS))
                .defineListAllowEmpty("sections", defaults, () -> ExportSection.ITEMS.id(), SECTION_IDS::contains);
        ponderScenes = builder.comment("Ponder schematic IDs to include. An empty list includes all registered scenes.")
                .defineListAllowEmpty("ponderScenes", List.of(), () -> "create:mechanical_pump/speed",
                        value -> value instanceof String id && id.contains(":") && ResourceLocation.tryParse(id) != null);
        spec = builder.build();
    }

    EnumSet<ExportSection> sections() {
        var selected = EnumSet.noneOf(ExportSection.class);
        var ids = sections.get();
        for (ExportSection section : ExportSection.values()) {
            if (ids.contains(section.id())) selected.add(section);
        }
        return selected;
    }

    void saveSections(Set<ExportSection> selected) {
        sections.set(selected.stream().sorted().map(ExportSection::id).toList());
        sections.save();
    }

    Set<String> ponderScenes() {
        return Set.copyOf(ponderScenes.get());
    }
}
