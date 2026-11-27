package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NativeImage.class)
public interface PonderNativeImageAccessor {
    @Accessor("pixels")
    long webcraft$pixelPointer();
}
