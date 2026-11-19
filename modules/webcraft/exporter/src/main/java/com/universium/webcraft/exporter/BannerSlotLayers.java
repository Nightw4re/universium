package com.universium.webcraft.exporter;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.world.item.DyeColor;

final class BannerSlotLayers {
    private static final int WHITE = DyeColor.WHITE.getTextureDiffuseColor();

    private BannerSlotLayers() {}

    static NativeImage separate(NativeImage dark, NativeImage light, NativeImage overDark, NativeImage overLight) {
        var result = new NativeImage(dark.getWidth(), dark.getHeight(), false);
        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                int pixel = separate(dark.getPixelRGBA(x, y), light.getPixelRGBA(x, y),
                        overDark.getPixelRGBA(x, y), overLight.getPixelRGBA(x, y));
                result.setPixelRGBA(x, y, pixel);
            }
        }
        return result;
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
