package com.universium.webcraft.exporter;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.entity.ItemRenderer;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.Base64;

final class ItemGlintExport {
    private ItemGlintExport() {}

    static JsonObject read(Minecraft minecraft) {
        byte[] texture;
        try (var input = minecraft.getResourceManager().open(ItemRenderer.ENCHANTED_GLINT_ITEM)) {
            texture = input.readNBytes(65_537);
            if (texture.length == 0 || texture.length > 65_536) {
                return null;
            }
        } catch (IOException exception) {
            return null;
        }

        // Read the texture transform from the standard item glint state without changing the active frame.
        var previous = new Matrix4f(RenderSystem.getTextureMatrix());
        var shader = new JsonObject();
        try {
            RenderStateShard.setupGlintTexturing(8.0F);
            var transform = RenderSystem.getTextureMatrix();
            shader.addProperty("rotation", Math.atan2(transform.m01(), transform.m00()));
            shader.addProperty("scale", Math.hypot(transform.m00(), transform.m01()));
        } finally {
            RenderSystem.setTextureMatrix(previous);
        }
        shader.addProperty("blend", "src_color_add");
        shader.addProperty("strength", minecraft.options.glintStrength().get());
        shader.addProperty("speed", minecraft.options.glintSpeed().get() * 8.0);
        shader.addProperty("uPeriod", 110_000);
        shader.addProperty("vPeriod", 30_000);
        shader.addProperty("texture", Base64.getEncoder().encodeToString(texture));
        var effects = new JsonObject();
        effects.add("glint", shader);
        return effects;
    }
}
