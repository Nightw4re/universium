package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRenderer", remap = false)
abstract class PonderChainFacesMixin {
    @Inject(method = "renderQuad(Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;FFFFFFFFFFII)V", at = @At("HEAD"), remap = false, require = 0)
    private static void webcraft$rememberChainFace(Matrix4f matrix, PoseStack.Pose pose, VertexConsumer consumer,
                                                   float y0, float y1, float x0, float z0, float x1, float z1,
                                                   float u0, float u1, float v0, float v1, int light0, int light1,
                                                   CallbackInfo callback) {
        PonderDrawCapture.captureBoxFace(consumer, matrix, x0, x1, y0, y1, z0, z1);
    }
}
