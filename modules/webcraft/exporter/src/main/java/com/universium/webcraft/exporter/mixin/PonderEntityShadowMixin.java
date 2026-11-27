package com.universium.webcraft.exporter.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
abstract class PonderEntityShadowMixin {
    @Inject(method = "renderBlockShadow", at = @At(value = "INVOKE", ordinal = 0, target =
            "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shadowVertex(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;IFFFFF)V"))
    private static void webcraft$rememberShadow(CallbackInfo callback,
                                                @Local(argsOnly = true) PoseStack.Pose pose,
                                                @Local(argsOnly = true) VertexConsumer consumer,
                                                @Local(ordinal = 4) float x0, @Local(ordinal = 5) float x1,
                                                @Local(ordinal = 6) float y,
                                                @Local(ordinal = 7) float z0, @Local(ordinal = 8) float z1) {
        PonderDrawCapture.captureBoxFace(consumer, pose.pose(), x0, x1, y, y, z0, z1);
    }
}
