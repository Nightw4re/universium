package com.universium.webcraft.exporter.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.universium.webcraft.exporter.InventorySpriteCapture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(ItemRenderer.class)
abstract class ItemRendererMixin {
    // Optimized renderers can submit these quads without calling ItemRenderer.renderQuadList.
    @ModifyExpressionValue(method = "renderModelLists", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/resources/model/BakedModel;getQuads(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private List<BakedQuad> webcraft$recordSprites(List<BakedQuad> quads) {
        InventorySpriteCapture.record(quads);
        return quads;
    }
}
