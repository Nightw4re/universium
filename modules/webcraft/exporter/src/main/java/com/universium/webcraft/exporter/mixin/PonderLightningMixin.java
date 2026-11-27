package com.universium.webcraft.exporter.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LightningBoltRenderer.class)
abstract class PonderLightningMixin {
    @WrapOperation(method = "render(Lnet/minecraft/world/entity/LightningBolt;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer webcraft$rememberBolt(MultiBufferSource buffers, RenderType renderType, Operation<VertexConsumer> original,
                                                 @Local(argsOnly = true) LightningBolt bolt, @Local(argsOnly = true) PoseStack pose) {
        VertexConsumer consumer = original.call(buffers, renderType);
        PonderDrawCapture.captureLightning(consumer, bolt.seed, pose.last().pose());
        return consumer;
    }
}
