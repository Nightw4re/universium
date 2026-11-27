package com.universium.webcraft.exporter.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ModelPart.class)
abstract class PonderModelPartMixin {
    @WrapMethod(method = "compile")
    private void webcraft$captureNativePart(PoseStack.Pose pose, VertexConsumer consumer, int light,
                                            int overlay, int color, Operation<Void> original) {
        PonderDrawCapture.captureRigidPart(this, pose, consumer, true,
                (partPose, target) -> original.call(partPose, target, light, overlay, color));
    }
}
