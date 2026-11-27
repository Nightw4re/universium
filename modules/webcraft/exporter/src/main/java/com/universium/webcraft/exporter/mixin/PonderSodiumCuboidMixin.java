package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.immediate.model.EntityRenderer", remap = false)
abstract class PonderSodiumCuboidMixin {
    @Inject(method = "renderCuboid(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
            + "Lnet/caffeinemc/mods/sodium/api/vertex/buffer/VertexBufferWriter;"
            + "Lnet/caffeinemc/mods/sodium/client/render/immediate/model/ModelCuboid;III)V",
            at = @At("HEAD"), remap = false, require = 0)
    private static void webcraft$rememberCuboid(PoseStack.Pose pose, @Coerce Object writer, @Coerce Object cuboid,
                                                int light, int overlay, int color, CallbackInfo callback) {
        PonderDrawCapture.captureSodiumCuboid(pose, writer, cuboid);
    }
}
