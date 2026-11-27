package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.createmod.ponder.foundation.PonderWorldParticles", remap = false)
abstract class PonderParticleCameraMixin {
    @Inject(method = "renderParticles", at = @At("HEAD"), remap = false, require = 0)
    private void webcraft$rememberCamera(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick,
                                         CallbackInfo callback) {
        PonderDrawCapture.setParticleCamera(camera);
    }

    @Inject(method = "renderParticles", at = @At("RETURN"), remap = false, require = 0)
    private void webcraft$clearCamera(CallbackInfo callback) {
        PonderDrawCapture.setParticleCamera(null);
    }
}
