package com.universium.webcraft.exporter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Wraps the bundled encoder's lossless VP8L chunks in a looping WebP container. */
final class AnimatedWebp {
    private static final int RIFF_HEADER_SIZE = 12;

    static byte[] encode(List<AnimationFrames.Frame> frames, FrameEncoder encoder) throws IOException {
        var body = new ByteArrayOutputStream();
        var canvas = frames.getFirst().pixels();
        chunkHeader(body, "VP8X", 10);
        body.write(0x02); // Animation. Set the alpha flag below if any frame uses transparency.
        littleEndian(body, 0, 3);
        littleEndian(body, canvas.width() - 1, 3);
        littleEndian(body, canvas.height() - 1, 3);
        chunkHeader(body, "ANIM", 6);
        littleEndian(body, 0, 4); // Transparent background.
        littleEndian(body, 0, 2); // Infinite loop.

        boolean alpha = false;
        for (var frame : frames) {
            byte[] webp = encoder.encode(frame.pixels());
            // The encoder always returns RIFF/WEBP followed by one padded VP8L chunk.
            alpha |= (webp[24] & 0x10) != 0;
            int frameSize = webp.length - RIFF_HEADER_SIZE;
            chunkHeader(body, "ANMF", 16 + frameSize);
            littleEndian(body, 0, 3);
            littleEndian(body, 0, 3);
            littleEndian(body, canvas.width() - 1, 3);
            littleEndian(body, canvas.height() - 1, 3);
            littleEndian(body, frame.ticks() * 50, 3);
            body.write(0x02); // Replace the full canvas, including transparent pixels.
            body.write(webp, RIFF_HEADER_SIZE, frameSize);
        }

        byte[] chunks = body.toByteArray();
        if (alpha) chunks[8] |= 0x10;
        var image = new ByteArrayOutputStream(RIFF_HEADER_SIZE + chunks.length);
        chunkHeader(image, "RIFF", 4 + chunks.length);
        image.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        image.writeBytes(chunks);
        return image.toByteArray();
    }

    private static void chunkHeader(ByteArrayOutputStream out, String name, int length) {
        out.writeBytes(name.getBytes(StandardCharsets.US_ASCII));
        littleEndian(out, length, 4);
    }

    private static void littleEndian(ByteArrayOutputStream out, int value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write(value >>> (i * Byte.SIZE));
        }
    }

    @FunctionalInterface
    interface FrameEncoder {
        byte[] encode(PngPixels pixels) throws IOException;
    }
}
