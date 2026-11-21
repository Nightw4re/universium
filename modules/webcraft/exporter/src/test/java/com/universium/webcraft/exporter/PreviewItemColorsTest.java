package com.universium.webcraft.exporter;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PreviewItemColorsTest {
    @Test
    void makesKnownLegacyRgbTintsOpaque() {
        for (String id : new String[]{"productivebees:advanced_regions_unexplored_eucalyptus_beehive",
                "productivebees:expansion_box_bamboo", "enderio:clear_glass_dnm_black", "naturesaura:ancient_leaves",
                "mysticalagradditions:insanium_farmland", "productivebees:configurable_honeycomb"}) {
            var item = ResourceLocation.parse(id);
            assertEquals(0xffc38362, PreviewItemColors.legacyRgbTint(item, 0, 0x00c38362));
            assertEquals(0x80c38362, PreviewItemColors.legacyRgbTint(item, 0, 0x80c38362));
        }
        for (String id : new String[]{"minecraft:beehive", "productivebees:bee_nest", "enderio:clear_glass",
                "naturesaura:golden_leaves"}) {
            assertEquals(0x00c38362, PreviewItemColors.legacyRgbTint(ResourceLocation.parse(id), 0, 0x00c38362));
        }
    }

    @Test
    void keepsBothHoneycombLayersVisible() {
        var comb = ResourceLocation.parse("productivebees:configurable_honeycomb");
        assertEquals(0xffb59500, PreviewItemColors.legacyRgbTint(comb, 1, 0x00b59500));
        assertEquals(0x80b59500, PreviewItemColors.legacyRgbTint(comb, 1, 0x80b59500));
        assertEquals(0x00b59500, PreviewItemColors.legacyRgbTint(comb, 2, 0x00b59500));
        var farmland = ResourceLocation.parse("mysticalagradditions:insanium_farmland");
        assertEquals(0x00b59500, PreviewItemColors.legacyRgbTint(farmland, 1, 0x00b59500));
    }
}
