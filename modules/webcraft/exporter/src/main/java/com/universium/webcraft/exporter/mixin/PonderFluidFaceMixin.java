package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.createmod.catnip.render.FluidRenderHelper", remap = false)
abstract class PonderFluidFaceMixin {
    @Inject(method = "renderTiledFace", at = @At("HEAD"), cancellable = true, remap = false)
    private static void webcraft$recordFluidFace(Direction side, float left, float bottom, float right, float top,
                                                float plane, VertexConsumer consumer, PoseStack pose, int light,
                                                int tint, TextureAtlasSprite sprite, float scale, CallbackInfo callback) {
        if (PonderDrawCapture.captureFluidFace(side, left, bottom, right, top, plane, consumer, pose,
                light, tint, sprite, scale)) callback.cancel();
    }
}
