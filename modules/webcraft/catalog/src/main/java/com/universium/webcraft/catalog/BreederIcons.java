package com.universium.webcraft.catalog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class BreederIcons {
    private static final int SIZE = 64;

    private BreederIcons() {}

    static byte[] combine(byte[] empty, byte[] first, byte[] second) {
        if (empty == null || first == null || second == null) {
            return null;
        }
        try {
            int[] base = pixels(empty);
            int[] front = pixels(first);
            int[] rear = pixels(second);
            if (base == null || front == null || rear == null) {
                return null;
            }
            // Slot one is nearer in the inventory pose. Compare to the empty frame to keep the glass and model intact.
            for (int index = 0; index < base.length; index++) {
                if (front[index] != base[index]) {
                    rear[index] = front[index];
                }
            }
            var combined = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            combined.setRGB(0, 0, SIZE, SIZE, rear, 0, SIZE);
            var output = new ByteArrayOutputStream();
            return ImageIO.write(combined, "png", output) ? output.toByteArray() : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static int[] pixels(byte[] png) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        return image == null || image.getWidth() != SIZE || image.getHeight() != SIZE
                ? null : image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
    }
}
