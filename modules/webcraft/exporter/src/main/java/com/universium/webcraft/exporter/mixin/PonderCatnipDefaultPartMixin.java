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

import java.nio.ByteBuffer;

@Pseudo
@Mixin(targets = "net.createmod.catnip.render.DefaultSuperByteBuffer", remap = false)
abstract class PonderCatnipDefaultPartMixin {
    @Shadow(remap = false) protected ByteBuffer template;
    @Shadow(remap = false) protected int formatSize;
    @Shadow(remap = false) protected boolean fullNormalTransform;
    @Shadow(remap = false) public abstract PoseStack getTransforms();

    @Inject(method = "renderInto", at = @At("HEAD"), remap = false)
    private void webcraft$rememberEntityPart(PoseStack pose, VertexConsumer consumer, CallbackInfo callback) {
        PonderDrawCapture.captureCatnipDefaultPart(template, formatSize, fullNormalTransform, getTransforms(), pose, consumer);
    }
}
