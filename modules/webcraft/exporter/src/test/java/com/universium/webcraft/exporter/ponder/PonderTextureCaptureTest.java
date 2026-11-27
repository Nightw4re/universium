package com.universium.webcraft.exporter.ponder;

import com.universium.webcraft.exporter.CudaTextureComparison;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PonderTextureCaptureTest {
    @Test
    void keepsTheLastImageForTheSameTextureAndDimensions() {
        var history = new PonderTextureCapture.History(2);
        var snapshot = history.snapshot(4, 256, 128);
        snapshot.image = "previous.png";
        assertSame(snapshot, history.snapshot(4, 256, 128));
        assertEquals("previous.png", history.snapshot(4, 256, 128).image);
    }

    @Test
    void reseedsWhenDimensionsChangeOrThePreviousCaptureBypassesCuda() {
        var history = new PonderTextureCapture.History(2);
        var snapshot = history.snapshot(4, 256, 128);
        snapshot.image = "previous.png";
        var resized = history.snapshot(4, 128, 256);
        assertEquals(snapshot.slot, resized.slot);
        assertNull(resized.image);
        resized.image = "resized.png";

        history.invalidate(4);
        assertNull(history.snapshot(4, 128, 256).image);
    }

    @Test
    void evictsTheLeastRecentlyUsedSnapshotAndReusesItsSlot() {
        var history = new PonderTextureCapture.History(2);
        var first = history.snapshot(4, 256, 128);
        var second = history.snapshot(5, 256, 128);
        second.image = "old.png";
        assertSame(first, history.snapshot(4, 256, 128));

        var third = history.snapshot(6, 256, 128);
        assertEquals(second.slot, third.slot);
        assertNull(third.image);
        assertSame(first, history.snapshot(4, 256, 128));
        assertNull(history.snapshot(5, 256, 128).image);
    }

    @Test
    void acceptsRectangularImagesWithinThePackedByteLimit() {
        assertTrue(CudaTextureComparison.supports(2048, 512));
        assertFalse(CudaTextureComparison.supports(2048, 513));
        assertFalse(CudaTextureComparison.supports(0, 256));
        assertFalse(CudaTextureComparison.supports(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
