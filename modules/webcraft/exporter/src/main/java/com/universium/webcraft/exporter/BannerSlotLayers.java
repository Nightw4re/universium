package com.universium.webcraft.exporter;

import net.minecraft.world.item.DyeColor;

final class BannerSlotLayers {
    private static final int WHITE = DyeColor.WHITE.getTextureDiffuseColor();

    private BannerSlotLayers() {}

    static PngPixels separate(PngPixels dark, PngPixels light, PngPixels overDark, PngPixels overLight) {
        var pixels = new int[dark.rgba().length];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = separate(dark.rgba()[index], light.rgba()[index], overDark.rgba()[index], overLight.rgba()[index]);
        }
        return new PngPixels(dark.width(), dark.height(), pixels);
    }

    static int separate(int dark, int light, int overDark, int overLight) {
        if (dark == overDark && light == overLight) {
            return 0;
        }
        int baseContrast = 0;
        int remainingContrast = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int base = channel(light, shift) - channel(dark, shift);
            int rendered = channel(overLight, shift) - channel(overDark, shift);
            baseContrast += base * base;
            remainingContrast += base * rendered;
        }
        // Both captures contain the same mark. Its opacity is the amount of cloth contrast it covers.
        float alpha = baseContrast == 0 ? 1 : 1 - Math.clamp((float) remainingContrast / baseContrast, 0, 1);
        if (alpha < 1f / 255) {
            return 0;
        }
        int result = Math.round(alpha * 255) << 24;
        for (int shift = 0; shift <= 16; shift += 8) {
            float shaded = (channel(overDark, shift) - (1 - alpha) * channel(dark, shift)) / alpha;
            int white = channel(WHITE, 16 - shift);
            int neutral = Math.round(Math.clamp(shaded / white, 0, 1) * 255);
            result |= neutral << shift;
        }
        return result;
    }

    private static int channel(int rgb, int shift) {
        return (rgb >>> shift) & 255;
    }
}
