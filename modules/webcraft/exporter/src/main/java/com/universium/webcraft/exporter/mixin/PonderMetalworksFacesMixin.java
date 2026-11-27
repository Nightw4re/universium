package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "cy.jdkdigital.productivemetalworks.util.RenderHelper", remap = false)
abstract class PonderMetalworksFacesMixin {
    @Inject(method = "renderTexturedVertex", at = @At("HEAD"), remap = false, require = 0)
    private static void webcraft$rememberFace(VertexConsumer consumer, Matrix4f pose, Direction side,
                                               float x0, float x1, float y0, float y1, float z0, float z1,
                                               float[] color, TextureAtlasSprite sprite, int light, int overlay, CallbackInfo callback) {
        PonderDrawCapture.captureBoxFace(consumer, pose, x0, x1, y0, y1, z0, z1);
    }
}
