package com.universium.webcraft.exporter.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.InventorySpriteCapture;
import com.universium.webcraft.exporter.ponder.PonderDrawCapture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(ItemRenderer.class)
abstract class ItemRendererMixin {
    @WrapMethod(method = "renderModelLists")
    private void webcraft$capturePonderItem(BakedModel model, ItemStack stack, int light, int overlay, PoseStack pose,
                                          VertexConsumer consumer, Operation<Void> original) {
        var worldPose = pose.last();
        PonderDrawCapture.captureRigidItemPart(model, worldPose, consumer, (partPose, target) ->
                original.call(model, stack, light, overlay, partPose == worldPose ? pose : new PoseStack(), target));
    }

    // Optimized renderers can submit these quads without calling ItemRenderer.renderQuadList.
    @ModifyExpressionValue(method = "renderModelLists", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/resources/model/BakedModel;getQuads(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private List<BakedQuad> webcraft$recordSprites(List<BakedQuad> quads) {
        InventorySpriteCapture.record(quads);
        return quads;
    }
}
