package com.universium.webcraft.exporter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BannerSlotLayersTest {
    @Test
    void leavesUnchangedPixelsTransparent() {
        assertEquals(0, BannerSlotLayers.separate(0xff202020, 0xffeeeeee, 0xff202020, 0xffeeeeee));
    }

    @Test
    void recoversTheNeutralShadedColorAndPartialOpacity() {
        int layer = BannerSlotLayers.separate(0xff1e1e1e, 0xffe6e6e6, 0xff4f4f4e, 0xffb3b3b2);
        assertEquals(128, layer >>> 24);
        for (int shift = 0; shift <= 16; shift += 8) {
            assertTrue(Math.abs(((layer >>> shift) & 255) - 128) <= 2);
        }
    }

    @Test
    void treatsEqualOutputsOnBothBasesAsOpaque() {
        int layer = BannerSlotLayers.separate(0xff202020, 0xffeeeeee, 0xfffefff9, 0xfffefff9);
        assertEquals(0xffffffff, layer);
    }
}
