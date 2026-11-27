package com.universium.webcraft.exporter.mixin;

import com.universium.webcraft.exporter.ponder.PonderEntityTick;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
abstract class PonderBlockEntityChangeMixin {
    @Inject(method = "setChanged()V", at = @At("HEAD"))
    private void webcraft$recordChangesDuringPonderExport(CallbackInfo callback) {
        PonderEntityTick.recordBlockEntityChange(this);
    }
}
