package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.createmod.catnip.render.DefaultSuperRenderTypeBuffer", remap = false)
abstract class PonderPartRenderTypeMixin {
    @Inject(method = {"getBuffer", "getEarlyBuffer", "getLateBuffer"}, at = @At("RETURN"), remap = false)
    private void webcraft$rememberPonderLayer(RenderType type, CallbackInfoReturnable<VertexConsumer> callback) {
        PonderDrawCapture.rememberLayer(type, callback.getReturnValue());
    }
}
