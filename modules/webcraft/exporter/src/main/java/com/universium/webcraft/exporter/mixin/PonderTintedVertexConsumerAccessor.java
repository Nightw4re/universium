package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "cy.jdkdigital.productivemetalworks.util.TintedVertexBuilder", remap = false)
public interface PonderTintedVertexConsumerAccessor {
    @Accessor(value = "inner", remap = false)
    VertexConsumer webcraft$inner();
}
