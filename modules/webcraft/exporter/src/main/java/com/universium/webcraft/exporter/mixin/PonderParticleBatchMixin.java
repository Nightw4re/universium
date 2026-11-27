package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BufferBuilder.class)
abstract class PonderParticleBatchMixin {
    @Inject(method = "build", at = @At("RETURN"))
    private void webcraft$associateParticles(CallbackInfoReturnable<MeshData> callback) {
        PonderDrawCapture.associateDynamicGeometry((BufferBuilder) (Object) this, callback.getReturnValue());
    }
}
