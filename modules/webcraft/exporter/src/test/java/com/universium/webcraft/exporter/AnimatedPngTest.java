package com.universium.webcraft.exporter;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class AnimatedPngTest {
    @Test
    void writesLoopingFramesWithExactTickDurationsAndAReadableDefaultImage() throws IOException {
        var amber = pixels(0xff00ccff);
        var clear = pixels(0);
        var animation = new AnimationFrames();
        animation.addTick(amber);
        animation.addTick(amber);
        animation.addTick(clear);
        animation.addTick(amber);
        byte[] bytes = AnimatedPng.encode(animation.frames());
        assertEquals(0xffffcc00, ImageIO.read(new ByteArrayInputStream(bytes)).getRGB(32, 32));

        var png = ByteBuffer.wrap(bytes).position(8);
        var delays = new ArrayList<Integer>();
        int sequence = 0;
        while (png.hasRemaining()) {
            var data = new byte[png.getInt()];
            var type = new byte[4];
            png.get(type);
            png.get(data);
            var crc = new CRC32();
            crc.update(type);
            crc.update(data);
            assertEquals((int) crc.getValue(), png.getInt());
            var chunk = ByteBuffer.wrap(data);
            switch (new String(type, StandardCharsets.US_ASCII)) {
                case "acTL" -> {
                    assertEquals(3, chunk.getInt());
                    assertEquals(0, chunk.getInt());
                }
                case "fcTL" -> {
                    assertEquals(sequence++, chunk.getInt());
                    delays.add((int) chunk.getShort(20));
                    assertEquals(20, chunk.getShort(22));
                    assertEquals(0, chunk.get(25)); // Replace all prior pixels, including transparency.
                }
                case "fdAT" -> assertEquals(sequence++, chunk.getInt());
                default -> { }
            }
        }
        assertEquals(List.of(2, 1, 1), delays);
    }

    @Test
    void omitsAnAnimationWhenEveryRenderedFrameIsIdentical() throws IOException {
        var animation = new AnimationFrames();
        animation.addTick(pixels(0xff00ccff));
        animation.addTick(pixels(0xff00ccff));
        assertNull(AnimatedPng.encode(animation.frames()));
    }

    @Test
    void combinesPeriodsWithinTheCaptureLimit() {
        assertEquals(40, AnimationTimeline.combine(1, 40));
        assertEquals(36, AnimationTimeline.combine(36, 12));
        assertEquals(144, AnimationTimeline.combine(36, 16));
        assertEquals(0, AnimationTimeline.combine(36, 40));
        assertEquals(0, AnimationTimeline.combine(1, 161));
    }

    private static PngPixels pixels(int rgba) {
        var pixels = new int[64 * 64];
        pixels[32 * 64 + 32] = rgba;
        return new PngPixels(64, 64, pixels);
    }
}
