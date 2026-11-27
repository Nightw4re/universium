package com.universium.webcraft.exporter;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExportSectionTest {
    @Test
    void recipesOnlyDoesNotNeedTheItemCatalog() {
        var sections = ExportSection.parse("recipes");
        assertEquals(EnumSet.of(ExportSection.RECIPES), sections);
        assertFalse(ExportSection.needsCatalog(sections));
    }

    @Test
    void tooltipsKeepTheCatalogIndexWithoutSelectingPreviews() {
        var sections = ExportSection.parse("tooltips, recipes");
        assertEquals(EnumSet.of(ExportSection.TOOLTIPS, ExportSection.RECIPES), sections);
        assertTrue(ExportSection.needsCatalog(sections));
        assertFalse(sections.contains(ExportSection.ITEMS));
    }

    @Test
    void ponderSelectsOneCompactExportAndLegacyFramesRequireTheirOwnId() {
        assertEquals(EnumSet.of(ExportSection.PONDER_COMPACT), ExportSection.parse("ponder,ponder-compact"));
        assertEquals("ponder", ExportSection.PONDER_COMPACT.id());
        assertEquals(EnumSet.of(ExportSection.PONDER), ExportSection.parse("ponder-legacy"));
    }

    @Test
    void rejectsEmptyAndUnknownSelections() {
        assertThrows(IllegalArgumentException.class, () -> ExportSection.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ExportSection.parse("recipes,unknown"));
    }
}
