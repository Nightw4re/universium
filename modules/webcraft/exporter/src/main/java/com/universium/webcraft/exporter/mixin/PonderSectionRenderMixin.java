package com.universium.webcraft.exporter.mixin;

import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.createmod.ponder.foundation.element.WorldSectionElementImpl", remap = false)
abstract class PonderSectionRenderMixin {
    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true, remap = false)
    private void webcraft$skipSharedBlocks(CallbackInfo callback) {
        if (PonderDrawCapture.skipsSectionBlocks()) callback.cancel();
    }
}
