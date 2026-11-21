package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.universium.webcraft.exporter.TooltipKeyProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputConstants.class)
abstract class TooltipInputConstantsMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void webcraft$readTooltipKey(long window, int key, CallbackInfoReturnable<Boolean> callback) {
        Boolean down = TooltipKeyProbe.keyDown(key);
        if (down != null) callback.setReturnValue(down);
    }
}
