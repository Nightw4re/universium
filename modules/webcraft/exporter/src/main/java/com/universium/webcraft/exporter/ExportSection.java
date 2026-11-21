package com.universium.webcraft.exporter;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

enum ExportSection {
    ITEMS("Items and previews"),
    RECIPES("Recipes"),
    TOOLTIPS("Tooltips"),
    DESIGNS("Banners and armor"),
    PONDER("Ponder scenes");

    final String label;

    ExportSection(String label) {
        this.label = label;
    }

    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    static EnumSet<ExportSection> parse(String value) {
        var sections = EnumSet.noneOf(ExportSection.class);
        for (String id : value.split(",", -1)) {
            sections.add(valueOf(id.strip().toUpperCase(Locale.ROOT)));
        }
        return sections;
    }

    static boolean needsCatalog(Set<ExportSection> sections) {
        return sections.contains(ITEMS) || sections.contains(TOOLTIPS);
    }
}
