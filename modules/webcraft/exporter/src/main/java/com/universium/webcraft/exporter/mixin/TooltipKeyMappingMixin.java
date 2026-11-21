package com.universium.webcraft.exporter.mixin;

import com.universium.webcraft.exporter.TooltipKeyProbe;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.class)
abstract class TooltipKeyMappingMixin {
    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void webcraft$readTooltipKeyMapping(CallbackInfoReturnable<Boolean> callback) {
        Boolean down = TooltipKeyProbe.mappingDown((KeyMapping) (Object) this);
        if (down != null) callback.setReturnValue(down);
    }
}
