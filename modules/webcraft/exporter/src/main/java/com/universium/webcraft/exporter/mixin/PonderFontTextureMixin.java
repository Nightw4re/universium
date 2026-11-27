package com.universium.webcraft.exporter.mixin;

import com.mojang.blaze3d.font.SheetGlyphInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import com.universium.webcraft.exporter.ponder.PonderFontVersions;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontTexture.class)
abstract class PonderFontTextureMixin extends AbstractTexture {
    @Inject(method = "add", at = @At("RETURN"))
    private void webcraft$recordGlyphUpload(SheetGlyphInfo glyph, CallbackInfoReturnable<BakedGlyph> result) {
        if (result.getReturnValue() == null || id == NOT_ASSIGNED) return;
        if (RenderSystem.isOnRenderThread()) PonderFontVersions.uploaded(id);
        else PonderFontVersions.forget(id);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void webcraft$forgetReleasedAtlas(CallbackInfo callback) {
        if (id != NOT_ASSIGNED) PonderFontVersions.forget(id);
    }
}
