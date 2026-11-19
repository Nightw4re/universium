package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.universium.webcraft.exporter.InventorySpriteCapture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ItemRenderer.class)
abstract class ItemRendererMixin {
    @Inject(method = "renderQuadList", at = @At("HEAD"))
    private void webcraft$recordSprites(PoseStack pose, VertexConsumer consumer, List<BakedQuad> quads,
                                       ItemStack stack, int light, int overlay, CallbackInfo callback) {
        InventorySpriteCapture.record(quads);
    }
}
