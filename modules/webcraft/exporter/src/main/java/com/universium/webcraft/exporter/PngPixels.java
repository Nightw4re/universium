package com.universium.webcraft.exporter;

import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;

// An owned heap copy of a capture. It can be queued or dropped without retaining a native image.
record PngPixels(int width, int height, int[] rgba) {
    byte[] encode() throws IOException {
        try (var image = new NativeImage(width, height, false)) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setPixelRGBA(x, y, rgba[y * width + x]);
                }
            }
            return image.asByteArray();
        }
    }

    boolean visible() {
        for (int pixel : rgba) {
            if ((pixel >>> 24) != 0) return true;
        }
        return false;
    }
}
