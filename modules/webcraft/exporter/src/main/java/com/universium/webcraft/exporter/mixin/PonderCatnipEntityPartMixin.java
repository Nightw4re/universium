package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.TemplateMesh;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.createmod.catnip.render.ShadeSeparatingSuperByteBuffer", remap = false)
abstract class PonderCatnipEntityPartMixin {
    @Shadow(remap = false) @Final private TemplateMesh template;
    @Shadow(remap = false) private SuperByteBuffer.SpriteShiftFunc spriteShiftFunc;
    @Shadow(remap = false) private boolean disableDiffuse;
    @Shadow(remap = false) private boolean useLevelLight;
    @Shadow(remap = false) private float r;
    @Shadow(remap = false) private float g;
    @Shadow(remap = false) private float b;
    @Shadow(remap = false) private float a;
    @Shadow(remap = false) public abstract PoseStack getTransforms();
    @Shadow(remap = false) public abstract boolean isEmpty();
    @Shadow(remap = false) public abstract SuperByteBuffer reset();
    @Shadow(remap = false) public abstract void renderInto(PoseStack pose, VertexConsumer consumer);

    @Inject(method = "renderInto", at = @At("HEAD"), cancellable = true, remap = false)
    private void webcraft$rememberEntityPart(PoseStack pose, VertexConsumer consumer, CallbackInfo callback) {
        if (!isEmpty() && PonderDrawCapture.captureShadedPart(this, getTransforms(), pose, consumer,
                spriteShiftFunc == null && !useLevelLight, disableDiffuse, r, g, b, a, this::renderInto, this::reset)) {
            callback.cancel();
            return;
        }
        PonderDrawCapture.captureCatnipEntityPart(template, getTransforms(), pose, consumer);
    }
}
