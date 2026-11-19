package com.universium.webcraft.exporter;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
        byte[] amber = png(0xffffcc00);
        byte[] clear = png(0);
        var animation = new AnimatedPng();
        animation.addTick(amber);
        animation.addTick(amber);
        animation.addTick(clear);
        animation.addTick(amber);
        byte[] bytes = animation.encode();
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
        var animation = new AnimatedPng();
        animation.addTick(png(0xffffcc00));
        animation.addTick(png(0xffffcc00));
        assertNull(animation.encode());
    }

    @Test
    void combinesPeriodsWithinTheCaptureLimit() {
        assertEquals(40, AnimationTimeline.combine(1, 40));
        assertEquals(36, AnimationTimeline.combine(36, 12));
        assertEquals(144, AnimationTimeline.combine(36, 16));
        assertEquals(0, AnimationTimeline.combine(36, 40));
        assertEquals(0, AnimationTimeline.combine(1, 161));
    }

    private static byte[] png(int color) throws IOException {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(32, 32, color);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
