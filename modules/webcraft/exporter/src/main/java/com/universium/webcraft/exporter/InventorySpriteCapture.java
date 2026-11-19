package com.universium.webcraft.exporter;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Collects the atlas sprites submitted by Minecraft's actual GUI item render. */
public final class InventorySpriteCapture implements AutoCloseable {
    private static final ThreadLocal<InventorySpriteCapture> CURRENT = new ThreadLocal<>();
    private final Set<SpriteContents> sprites = new HashSet<>();

    InventorySpriteCapture() {
        CURRENT.set(this);
    }

    /** Called by the client ItemRenderer hook while a preview is being captured. */
    public static void record(List<BakedQuad> quads) {
        var capture = CURRENT.get();
        if (capture != null) {
            for (var quad : quads) {
                capture.sprites.add(quad.getSprite().contents());
            }
        }
    }

    int loopTicks() {
        int ticks = 1;
        for (var sprite : sprites) {
            if (sprite.getUniqueFrames().limit(2).count() < 2) {
                continue;
            }
            ticks = AnimationTimeline.combine(ticks, spriteTicks(sprite));
            if (ticks == 0) {
                break;
            }
        }
        return ticks;
    }

    private static int spriteTicks(SpriteContents sprite) {
        var metadata = sprite.metadata().getSection(AnimationMetadataSection.SERIALIZER)
                .orElse(AnimationMetadataSection.EMPTY);
        var image = sprite.getOriginalImage();
        int frameCount = (image.getWidth() / sprite.width()) * (image.getHeight() / sprite.height());
        long[] ticks = {0};
        // Apply the same index and duration checks as SpriteContents.
        metadata.forEachFrame((index, duration) -> {
            if (index >= 0 && index < frameCount && duration > 0) {
                ticks[0] += duration;
            }
        });
        long total = ticks[0] == 0 ? (long) frameCount * metadata.getDefaultFrameTime() : ticks[0];
        return total > AnimationTimeline.MAX_TICKS ? 0 : (int) total;
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
