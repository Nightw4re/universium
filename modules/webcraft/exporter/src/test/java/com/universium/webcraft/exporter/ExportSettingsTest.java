package com.universium.webcraft.exporter;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ExportSettingsTest {
    @Test
    void newConfigSelectsAllSections() {
        var config = CommentedConfig.inMemory();
        new ExportSettings().spec.correct(config);
        assertEquals(List.of("items", "recipes", "tooltips", "designs"), config.get("sections"));
    }

    @Test
    void acceptsAnExplicitRecipeOnlyOrEmptySelection() {
        var config = CommentedConfig.inMemory();
        var settings = new ExportSettings();
        config.set("sections", List.of("recipes"));
        settings.spec.correct(config);
        assertEquals(List.of("recipes"), config.get("sections"));
        config.set("sections", List.of());
        settings.spec.correct(config);
        assertEquals(List.of(), config.get("sections"));
    }

    @Test
    void acceptsPonderAloneWithARegisteredSchematicId() {
        var config = CommentedConfig.inMemory();
        config.set("sections", List.of("ponder"));
        config.set("ponderScenes", List.of("create:mechanical_pump/speed"));
        new ExportSettings().spec.correct(config);
        assertEquals(List.of("ponder"), config.get("sections"));
        assertEquals(List.of("create:mechanical_pump/speed"), config.get("ponderScenes"));
    }
}
