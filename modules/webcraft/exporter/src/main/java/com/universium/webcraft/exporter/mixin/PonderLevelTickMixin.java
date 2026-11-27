package com.universium.webcraft.exporter.mixin;

import com.universium.webcraft.exporter.ponder.PonderEntityTick;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Iterator;
import java.util.List;

@Pseudo
@Mixin(targets = "net.createmod.ponder.api.level.PonderLevel", remap = false)
abstract class PonderLevelTickMixin {
    @Redirect(method = "tick()V", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"), remap = false)
    private Iterator<Entity> webcraft$iterateExportEntities(List<Entity> entities) {
        return PonderEntityTick.iterator(entities);
    }
}
