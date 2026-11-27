package com.universium.webcraft.exporter.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.iafenvoy.uranus.client.model.AdvancedModelBox", remap = false)
abstract class PonderUranusPartMixin {
    @WrapMethod(method = "doRender(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", remap = false, require = 0)
    private void webcraft$captureLocalGeometry(PoseStack.Pose pose, VertexConsumer consumer, int light,
                                               int overlay, int color, Operation<Void> original) {
        PonderDrawCapture.captureRigidPart(this, pose, consumer, false,
                (partPose, target) -> original.call(partPose, target, light, overlay, color));
    }
}
