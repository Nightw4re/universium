package com.universium.webcraft.exporter;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;

final class PreviewExportScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Component SELECT_ITEMS = Component.literal("Export previews from the loaded world and resource packs.");
    private Button inventory;
    private Button catalog;
    private Button close;
    private PreviewExport job;
    private Component status = SELECT_ITEMS;

    PreviewExportScreen() {
        super(Component.literal("WebCraft Preview Exporter"));
    }

    @Override
    protected void init() {
        int left = (width - 224) / 2;
        int top = height / 2 - 22;
        inventory = addRenderableWidget(Button.builder(Component.literal("Export inventory"), button -> start(false))
                .bounds(left, top, 224, 20).build());
        catalog = addRenderableWidget(Button.builder(Component.literal("Export item catalog and inventory"), button -> start(true))
                .bounds(left, top + 24, 224, 20).build());
        close = addRenderableWidget(Button.builder(CommonLabels.CLOSE, button -> onClose())
                .bounds(left, top + 48, 224, 20).build());
        updateButtons();
    }

    private void start(boolean includeCatalog) {
        if (minecraft.player == null || minecraft.level == null) {
            status = Component.literal("Load a Minecraft world before exporting previews.");
            return;
        }
        var stacks = new ArrayList<ItemStack>();
        var playerInventory = minecraft.player.getInventory();
        for (int index = 0; index < playerInventory.getContainerSize(); index++) {
            ItemStack stack = playerInventory.getItem(index);
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        int catalogStart = includeCatalog ? stacks.size() : -1;
        if (includeCatalog) {
            BuiltInRegistries.ITEM.stream().map(item -> item.getDefaultInstance())
                    .filter(stack -> !stack.isEmpty()).forEach(stacks::add);
        }
        if (stacks.isEmpty()) {
            status = Component.literal("Put the items you want to export in your inventory first.");
            return;
        }

        try {
            var output = minecraft.gameDirectory.toPath().resolve("webcraft-exports").resolve("previews.zip");
            var registries = minecraft.level.registryAccess();
            var variants = new ArrayList<CatalogPreviewVariant>();
            if (includeCatalog) {
                variants.addAll(EasyVillagerVariants.collect(registries));
                variants.addAll(DyedItemVariants.collect(stacks.subList(catalogStart, stacks.size())));
            }
            job = new PreviewExport(minecraft, registries, stacks, catalogStart, variants, output);
            status = Component.literal(job.progress());
        } catch (IOException exception) {
            fail(exception);
        }
        updateButtons();
    }

    @Override
    public void tick() {
        if (job == null) {
            return;
        }
        try {
            if (job.animating()) {
                job.captureAnimationTick();
                status = Component.literal(job.progress());
                return;
            }
            long budget = System.nanoTime() + 12_000_000;
            for (int index = 0; index < 8; index++) {
                if (job.tick()) {
                    status = Component.literal(job.result());
                    endJob();
                    return;
                }
                if (job.animating() || System.nanoTime() >= budget) {
                    break;
                }
            }
            status = Component.literal(job.progress());
        } catch (IOException exception) {
            fail(exception);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 63, 0xFFFFFF);
        graphics.drawCenteredString(font, status, width / 2, height / 2 - 43, 0xDADADA);
    }

    @Override
    public void removed() {
        endJob();
    }

    private void fail(IOException exception) {
        LOGGER.warn("Could not export WebCraft inventory previews", exception);
        status = Component.literal("Export failed. See the Minecraft log for details.");
        endJob();
    }

    private void endJob() {
        if (job != null) {
            try {
                job.close();
            } catch (IOException exception) {
                LOGGER.warn("Could not clean up a WebCraft preview export", exception);
            } finally {
                job = null;
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        inventory.active = job == null;
        catalog.active = job == null;
        close.setMessage(job == null ? CommonLabels.CLOSE : CommonLabels.CANCEL);
    }

    private static final class CommonLabels {
        private static final Component CLOSE = Component.translatable("gui.done");
        private static final Component CANCEL = Component.translatable("gui.cancel");
    }
}
