package com.universium.webcraft.exporter;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;
import org.slf4j.Logger;

final class InventoryIconRenderer implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int SIZE = 64;
    static final int GUI_SCALE = 3;
    static final int HALF_SLOT = 8;
    private static final ResourceLocation SPATIAL_RIFT = ResourceLocation.fromNamespaceAndPath("neovitae", "spatial_rift");
    private final Minecraft minecraft;
    private final TextureTarget target;
    private boolean tryCuda;
    private CudaFrameComparison cuda;

    InventoryIconRenderer(Minecraft minecraft, boolean experimentalCuda) {
        this.minecraft = minecraft;
        tryCuda = experimentalCuda;
        target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        target.setClearColor(0, 0, 0, 0);
        minecraft.getMainRenderTarget().bindWrite(true);
    }

    PngPixels render(ItemStack stack) {
        RenderSystem.assertOnRenderThread();
        var capture = renderStack(DomumPreviewStacks.withDefaultMaterials(stack), -1, false, false);
        if (capture.visible()) return capture;
        if (!SPATIAL_RIFT.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return null;

        // The Rift uses the air model. Show Minecraft's marker for an invisible block in the catalog.
        var marker = renderStack(Items.STRUCTURE_VOID.getDefaultInstance(), -1, false, false);
        return marker.visible() ? marker : null;
    }

    PngPixels captureAnimationFrame(ItemStack stack, int slot, boolean first, boolean warmup) {
        RenderSystem.assertOnRenderThread();
        return renderStack(DomumPreviewStacks.withDefaultMaterials(stack), slot, first, warmup);
    }

    private PngPixels renderStack(ItemStack previewStack, int animationSlot, boolean first, boolean warmup) {
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        var shaderColor = RenderSystem.getShaderColor().clone();
        modelView.pushMatrix();
        var previousTintStack = PreviewItemColors.begin(previewStack);
        try {
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            float far = ClientHooks.getGuiFarPlane();
            // Increase pixel density through the projection so the item keeps the inventory's unscaled pose.
            float halfExtent = SIZE / (2.0F * GUI_SCALE);
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

            if (warmup || (animationSlot >= 0 && unchanged(animationSlot, first))) {
                target.unbindRead();
                return null;
            }

            try (var image = new NativeImage(SIZE, SIZE, false)) {
                target.bindRead();
                try {
                    image.downloadTexture(0, false);
                } finally {
                    target.unbindRead();
                }
                image.flipY();
                return new PngPixels(SIZE, SIZE, image.getPixelsRGBA());
            }
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

    private boolean unchanged(int slot, boolean first) {
        if (!tryCuda) return false;
        try {
            if (cuda == null) {
                cuda = CudaFrameComparison.open(target.getColorTextureId(), SIZE, InventoryAnimationExport.BATCH_SIZE);
                LOGGER.info("WebCraft is using experimental CUDA inventory animation comparison");
            }
            return cuda.unchanged(slot, first);
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.info("WebCraft is using the standard inventory animation readback path: {}", exception.toString());
            tryCuda = false;
            closeCuda();
            return false;
        }
    }

    private void closeCuda() {
        if (cuda != null) {
            cuda.close();
            cuda = null;
        }
    }

    @Override
    public void close() {
        closeCuda();
        target.destroyBuffers();
        minecraft.getMainRenderTarget().bindWrite(true);
    }
}
