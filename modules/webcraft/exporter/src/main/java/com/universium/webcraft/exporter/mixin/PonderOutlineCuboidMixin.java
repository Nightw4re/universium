package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.createmod.catnip.outliner.Outline", remap = false)
abstract class PonderOutlineCuboidMixin {
    @Inject(method = "bufferCuboid", at = @At("HEAD"), remap = false)
    private void webcraft$rememberCuboid(PoseStack.Pose pose, VertexConsumer consumer, Vector3f minimum, Vector3f maximum,
                                        Vector4f color, int light, boolean disableDiffuse, CallbackInfo callback) {
        PonderDrawCapture.captureOutlineCuboid(pose, consumer, minimum, maximum, color, light, disableDiffuse);
    }
}
