package com.universium.webcraft.exporter.ponder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PonderFontVersionsTest {
    @Test
    void uploadAndTextureIdReuseInvalidateTheCachedAtlas() {
        int texture = 421;
        try {
            assertNull(PonderFontVersions.version(texture));
            PonderFontVersions.uploaded(texture);
            var first = PonderFontVersions.version(texture);
            assertEquals(first, PonderFontVersions.version(texture));
            PonderFontVersions.uploaded(texture);
            assertNotEquals(first, PonderFontVersions.version(texture));
            var beforeClose = PonderFontVersions.version(texture);
            PonderFontVersions.forget(texture);
            assertNull(PonderFontVersions.version(texture));
            PonderFontVersions.uploaded(texture);
            assertNotEquals(beforeClose, PonderFontVersions.version(texture));
        } finally {
            PonderFontVersions.forget(texture);
        }
    }
}
