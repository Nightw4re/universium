package com.universium.webcraft.exporter;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

enum ExportSection {
    ITEMS("items", "Items and previews"),
    RECIPES("recipes", "Recipes"),
    TOOLTIPS("tooltips", "Tooltips"),
    DESIGNS("designs", "Banners and armor"),
    PONDER_COMPACT("ponder", "Ponder"),
    PONDER("ponder-legacy", "Ponder legacy frames"),
    PONDER_DEFINITIONS("ponder-definitions", "Ponder definitions");

    private final String id;
    final String label;

    ExportSection(String id, String label) {
        this.id = id;
        this.label = label;
    }

    String id() {
        return id;
    }

    static ExportSection byId(String id) {
        if ("ponder-compact".equals(id)) return PONDER_COMPACT;
        for (ExportSection section : values()) {
            if (section.id.equals(id)) return section;
        }
        return null;
    }

    static EnumSet<ExportSection> parse(String value) {
        var sections = EnumSet.noneOf(ExportSection.class);
        for (String id : value.split(",", -1)) {
            var section = byId(id.strip().toLowerCase(Locale.ROOT).replace('_', '-'));
            if (section == null) throw new IllegalArgumentException("Unknown export section: " + id.strip());
            sections.add(section);
        }
        return sections;
    }

    static boolean needsCatalog(Set<ExportSection> sections) {
        return sections.contains(ITEMS) || sections.contains(TOOLTIPS);
    }
}
