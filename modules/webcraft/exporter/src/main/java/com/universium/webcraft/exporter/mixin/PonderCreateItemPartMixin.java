package com.universium.webcraft.exporter.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.minecraft.client.resources.model.BakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.item.render.PartialItemModelRenderer", remap = false)
abstract class PonderCreateItemPartMixin {
    // Create's custom parts submit baked quads without passing through ItemRenderer.renderModelLists.
    @WrapMethod(method = "renderBakedItemModel(Lnet/minecraft/client/resources/model/BakedModel;I"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V", remap = false, require = 0)
    private void webcraft$captureItemPart(BakedModel model, int light, PoseStack pose, VertexConsumer consumer,
                                           Operation<Void> original) {
        var worldPose = pose.last();
        PonderDrawCapture.captureRigidItemPart(model, worldPose, consumer, (partPose, target) ->
                original.call(model, light, partPose == worldPose ? pose : new PoseStack(), target));
    }
}
