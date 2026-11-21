package com.universium.webcraft.exporter;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;

final class InventoryIconRenderer implements AutoCloseable {
    private static final int SIZE = 64;
    private static final float GUI_SCALE = 3.0F;
    private static final int HALF_SLOT = 8;
    private static final ResourceLocation SPATIAL_RIFT = ResourceLocation.fromNamespaceAndPath("neovitae", "spatial_rift");
    private final Minecraft minecraft;
    private final TextureTarget target;

    InventoryIconRenderer(Minecraft minecraft) {
        this.minecraft = minecraft;
        target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        target.setClearColor(0, 0, 0, 0);
        minecraft.getMainRenderTarget().bindWrite(true);
    }

    PngPixels render(ItemStack stack) {
        return render(stack, false);
    }

    PngPixels render(ItemStack stack, boolean allowTransparent) {
        RenderSystem.assertOnRenderThread();
        var capture = renderStack(DomumPreviewStacks.withDefaultMaterials(stack));
        if (allowTransparent || capture.visible()) return capture;
        if (!SPATIAL_RIFT.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return null;

        // The Rift uses the air model. Show Minecraft's marker for an invisible block in the catalog.
        var marker = renderStack(Items.STRUCTURE_VOID.getDefaultInstance());
        return marker.visible() ? marker : null;
    }

    private PngPixels renderStack(ItemStack previewStack) {
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        var shaderColor = RenderSystem.getShaderColor().clone();
        modelView.pushMatrix();
        var previousTintStack = PreviewItemColors.begin(previewStack);
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
                graphics.renderItem(previewStack, -HALF_SLOT, -HALF_SLOT);
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
            return new PngPixels(SIZE, SIZE, image.getPixelsRGBA());
        } finally {
            PreviewItemColors.restore(previousTintStack);
            RenderSystem.setProjectionMatrix(projection, sorting);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            Lighting.setupFor3DItems();
            minecraft.getMainRenderTarget().bindWrite(true);
        }
    }

    @Override
    public void close() {
        target.destroyBuffers();
        minecraft.getMainRenderTarget().bindWrite(true);
    }
}
