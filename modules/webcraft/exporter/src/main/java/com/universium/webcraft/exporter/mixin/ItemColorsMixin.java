package com.universium.webcraft.exporter.mixin;

import com.universium.webcraft.exporter.PreviewItemColors;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemColors.class)
abstract class ItemColorsMixin {
    @Inject(method = "getColor", at = @At("RETURN"), cancellable = true)
    private void webcraft$tintPreview(ItemStack stack, int index, CallbackInfoReturnable<Integer> callback) {
        int color = callback.getReturnValueI();
        int adjusted = PreviewItemColors.tint(stack, index, color);
        if (adjusted != color) callback.setReturnValue(adjusted);
    }
}
