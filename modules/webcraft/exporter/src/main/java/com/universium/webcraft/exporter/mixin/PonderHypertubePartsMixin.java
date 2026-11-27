package com.universium.webcraft.exporter.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.List;

@Pseudo
@Mixin(targets = "com.pedrorok.hypertube.client.BezierTextureRenderer", remap = false)
abstract class PonderHypertubePartsMixin {
    @WrapMethod(method = "renderComponent(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Matrix4f;[IILjava/util/List;"
            + "Lcom/pedrorok/hypertube/client/BezierTextureRenderer$SectionType;I)V", remap = false, require = 0)
    private void webcraft$captureTubeSection(VertexConsumer consumer, Matrix4f matrix, int[] lights, int overlay,
                                             List<?> rings, @Coerce Object section, int spacing, Operation<Void> original) {
        boolean ponderScreen = Minecraft.getInstance().screen instanceof PonderUI;
        PonderDrawCapture.captureRigidMatrixPart(rings, section, spacing, ponderScreen, matrix, consumer,
                (transform, target) -> original.call(target, transform, lights, overlay, rings, section, spacing));
    }
}
