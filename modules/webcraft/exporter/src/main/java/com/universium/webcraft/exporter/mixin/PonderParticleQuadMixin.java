package com.universium.webcraft.exporter.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.minecraft.client.particle.SingleQuadParticle;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SingleQuadParticle.class)
abstract class PonderParticleQuadMixin {
    @ModifyExpressionValue(method = "renderRotatedQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Quaternionf;FFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/SingleQuadParticle;getQuadSize(F)F"))
    private float webcraft$recordParticle(float size, VertexConsumer consumer, Quaternionf rotation, float x, float y,
                                          float z, float partialTick) {
        PonderDrawCapture.captureParticle(consumer, rotation, x, y, z, size);
        return size;
    }
}
