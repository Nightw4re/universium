package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.createmod.catnip.render.DefaultSuperByteBuffer", remap = false)
abstract class PonderPartBufferMixin {
    @Shadow(remap = false) public abstract PoseStack getTransforms();
    @Shadow(remap = false) public abstract boolean isEmpty();
    @Shadow(remap = false) public abstract void renderInto(PoseStack pose, VertexConsumer consumer);

    @Inject(method = "renderInto", at = @At("HEAD"), cancellable = true, remap = false)
    private void webcraft$reuseMovingPart(PoseStack pose, VertexConsumer consumer, CallbackInfo callback) {
        if (!isEmpty() && PonderDrawCapture.capturePart(getTransforms(), pose, consumer, this::renderInto)) callback.cancel();
    }
}
