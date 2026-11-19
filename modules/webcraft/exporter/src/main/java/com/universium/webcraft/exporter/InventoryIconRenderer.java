package com.universium.webcraft.exporter;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;

import java.io.IOException;

final class InventoryIconRenderer implements AutoCloseable {
    private static final int SIZE = 64;
    private static final float GUI_SCALE = 3.0F;
    private static final int HALF_SLOT = 8;
    private final Minecraft minecraft;
    private final TextureTarget target;

    InventoryIconRenderer(Minecraft minecraft) {
        this.minecraft = minecraft;
        target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        target.setClearColor(0, 0, 0, 0);
        minecraft.getMainRenderTarget().bindWrite(true);
    }

    byte[] render(ItemStack stack) throws IOException {
        return render(stack, false);
    }

    byte[] render(ItemStack stack, boolean allowTransparent) throws IOException {
        RenderSystem.assertOnRenderThread();
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        var shaderColor = RenderSystem.getShaderColor().clone();
        modelView.pushMatrix();
        try (var image = new NativeImage(SIZE, SIZE, false)) {
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            float far = ClientHooks.getGuiFarPlane();
            // Increase pixel density through the projection so the item keeps the inventory's unscaled pose.
            float halfExtent = SIZE / (2 * GUI_SCALE);
            var guiProjection = new Matrix4f().setOrtho(-halfExtent, halfExtent, halfExtent, -halfExtent, 1000, far);
            RenderSystem.setProjectionMatrix(guiProjection, VertexSorting.ORTHOGRAPHIC_Z);
            modelView.translation(0, 0, 10000 - far);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            Lighting.setupFor3DItems();

            var graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
            try {
                graphics.renderItem(stack, -HALF_SLOT, -HALF_SLOT);
            } finally {
                graphics.flush();
            }

            target.bindRead();
            try {
                image.downloadTexture(0, false);
            } finally {
                target.unbindRead();
            }
            image.flipY();
            return allowTransparent || hasVisiblePixels(image) ? image.asByteArray() : null;
        } finally {
            RenderSystem.setProjectionMatrix(projection, sorting);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            Lighting.setupFor3DItems();
            minecraft.getMainRenderTarget().bindWrite(true);
        }
    }

    private static boolean hasVisiblePixels(NativeImage image) {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if ((image.getPixelRGBA(x, y) >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        target.destroyBuffers();
        minecraft.getMainRenderTarget().bindWrite(true);
    }
}
