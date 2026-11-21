package com.universium.webcraft.exporter;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InventorySpriteCaptureTest {
    @Test
    void flagsOnlyCapturesThatUseThePlaceholderOnEveryQuad() {
        try (var missing = contents(MissingTextureAtlasSprite.getLocation());
             var real = contents(ResourceLocation.parse("example:block/real"));
             var capture = new InventorySpriteCapture()) {
            assertFalse(capture.onlyMissingTexture());
            InventorySpriteCapture.record(List.of(quad(missing), quad(missing)));
            assertTrue(capture.onlyMissingTexture());
            InventorySpriteCapture.record(List.of(quad(real)));
            assertFalse(capture.onlyMissingTexture());
        }
    }

    private static SpriteContents contents(ResourceLocation id) {
        return new SpriteContents(id, new FrameSize(1, 1), new NativeImage(1, 1, false), ResourceMetadata.EMPTY);
    }

    private static BakedQuad quad(SpriteContents contents) {
        var sprite = new TextureAtlasSprite(TextureAtlas.LOCATION_BLOCKS, contents, 1, 1, 0, 0) {};
        return new BakedQuad(new int[32], -1, Direction.UP, sprite, true);
    }
}
