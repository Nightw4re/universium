package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BufferUploader.class)
abstract class PonderDrawCaptureMixin {
    @Inject(method = "drawWithShader", at = @At("HEAD"), cancellable = true)
    private static void webcraft$capturePonderMesh(MeshData mesh, CallbackInfo callback) {
        if (PonderDrawCapture.record(mesh)) callback.cancel();
    }
}
