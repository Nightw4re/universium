package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BufferBuilder.class)
public interface PonderVertexBufferAccessor {
    @Accessor("vertices")
    int webcraft$vertexCount();

    @Accessor("format")
    VertexFormat webcraft$format();
}
