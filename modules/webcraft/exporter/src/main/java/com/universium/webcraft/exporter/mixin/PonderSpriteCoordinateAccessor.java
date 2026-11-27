package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteCoordinateExpander.class)
public interface PonderSpriteCoordinateAccessor {
    @Accessor("delegate")
    VertexConsumer webcraft$delegate();

    @Accessor("sprite")
    TextureAtlasSprite webcraft$sprite();
}
