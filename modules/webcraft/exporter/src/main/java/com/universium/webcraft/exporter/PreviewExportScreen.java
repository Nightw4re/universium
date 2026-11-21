package com.universium.webcraft.exporter;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class PreviewExportScreen extends Screen {
    private static final long FRAME_BUDGET_NANOS = 12_000_000;
    private static final long AUTOMATIC_FRAME_BUDGET_NANOS = 24_000_000;
    private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BUTTON_WIDTH = 240;
    private static final int ROW_HEIGHT = 24;
    private static final int CHECKBOX_ROW_HEIGHT = 18;
    private static final Component SELECT_ITEMS = Component.literal("Choose which data to export from the loaded world.");
    private final ExportSettings settings;
    private final Consumer<Boolean> completion;
    private final EnumSet<ExportSection> selectedSections;
    private final ArrayList<Checkbox> sectionControls = new ArrayList<>();
    private StaticPreviewWriter.Format staticFormat = StaticPreviewWriter.Format.WEBP;
    private Button format;
    private Button advanced;
    private Button inventory;
    private Button catalog;
    private Button fullCatalog;
    private Button close;
    private PreviewExport job;
    private Component status = SELECT_ITEMS;
    private boolean advancedOpen;
    private long frameBudgetNanos = FRAME_BUDGET_NANOS;
    private boolean automatic;
    private long exportStartedAt;
    private PreviewExport.Phase timedPhase;
    private PreviewExport.Phase reportedPhase;
    private long lastProgressAt;
    private long phaseStartedAt;
    private long phaseCaptureNanos;
    private int phaseFrames;
    private int phaseFirstQueueCheck;

    PreviewExportScreen(ExportSettings settings) {
        this(settings, completed -> {});
    }

    PreviewExportScreen(ExportSettings settings, Consumer<Boolean> completion) {
        super(Component.literal("WebCraft Preview Exporter"));
        this.settings = settings;
        this.selectedSections = settings.sections();
        this.completion = completion;
    }

    void startAutomatic(StaticPreviewWriter.Format format, boolean includeHidden, Set<ExportSection> sections) {
        staticFormat = format;
        automatic = true;
        frameBudgetNanos = AUTOMATIC_FRAME_BUDGET_NANOS;
        start(includeHidden ? Mode.FULL_CATALOG : Mode.VISIBLE_CATALOG, sections);
    }

    @Override
    protected void init() {
        int left = (width - BUTTON_WIDTH) / 2;
        format = addRenderableWidget(Button.builder(formatLabel(), button -> {
                    staticFormat = staticFormat == StaticPreviewWriter.Format.WEBP
                            ? StaticPreviewWriter.Format.PNG : StaticPreviewWriter.Format.WEBP;
                    button.setMessage(formatLabel());
                }).bounds(left, 0, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.literal("Select the image format for previews and recipe sprites."))).build());
        sectionControls.clear();
        for (ExportSection section : ExportSection.values()) {
            var checkbox = Checkbox.builder(Component.literal(section.label), font)
                    .selected(selectedSections.contains(section))
                    .onValueChange((control, selected) -> {
                        if (selected) selectedSections.add(section);
                        else selectedSections.remove(section);
                        saveSections();
                        updateButtons();
                    }).build();
            sectionControls.add(addRenderableWidget(checkbox));
        }
        catalog = addRenderableWidget(Button.builder(Component.literal("Export selected data"), button -> start(Mode.VISIBLE_CATALOG))
                .bounds(left, 0, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.literal("Export the checked sections using the visible item catalog."))).build());
        advanced = addRenderableWidget(Button.builder(Component.empty(), button -> {
                    advancedOpen = !advancedOpen;
                    updateButtons();
                }).bounds(left, 0, BUTTON_WIDTH, 20).build());
        inventory = addRenderableWidget(Button.builder(Component.literal("Export inventory only"), button -> start(Mode.INVENTORY))
                .bounds(left, 0, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.literal("Export previews of the exact stacks in your inventory."))).build());
        fullCatalog = addRenderableWidget(Button.builder(Component.literal("Export including hidden items"),
                        button -> start(Mode.FULL_CATALOG))
                .bounds(left, 0, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.literal("Export the checked sections using the full item registry."))).build());
        close = addRenderableWidget(Button.builder(CommonLabels.CLOSE, button -> onClose())
                .bounds(left, 0, BUTTON_WIDTH, 20).build());
        updateButtons();
    }

    private void saveSections() {
        try {
            settings.saveSections(selectedSections);
            status = SELECT_ITEMS;
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not save WebCraft export sections", exception);
            status = Component.literal("Could not save the export selections. See the Minecraft log for details.");
        }
    }

    private void start(Mode mode) {
        start(mode, mode == Mode.INVENTORY ? Set.of(ExportSection.ITEMS) : Set.copyOf(selectedSections));
    }

    private void start(Mode mode, Set<ExportSection> sections) {
        if (minecraft.player == null || minecraft.level == null) {
            status = Component.literal("Load a Minecraft world before exporting previews.");
            completion.accept(false);
            return;
        }
        var stacks = new ArrayList<ItemStack>();
        if (sections.contains(ExportSection.ITEMS)) {
            var playerInventory = minecraft.player.getInventory();
            for (int index = 0; index < playerInventory.getContainerSize(); index++) {
                ItemStack stack = playerInventory.getItem(index);
                if (!stack.isEmpty()) {
                    stacks.add(stack.copy());
                }
            }
        }
        if (mode == Mode.INVENTORY && stacks.isEmpty()) {
            status = Component.literal("Put the items you want to export in your inventory first.");
            return;
        }

        try {
            var output = minecraft.gameDirectory.toPath().resolve("webcraft-exports").resolve("previews.zip");
            var connection = minecraft.player.connection;
            var registries = minecraft.level.registryAccess();
            Supplier<Iterator<ItemStack>> catalogItems = null;
            if (mode != Mode.INVENTORY && ExportSection.needsCatalog(sections)) {
                catalogItems = mode == Mode.FULL_CATALOG ? () -> CatalogExportItems.fullRegistry().iterator()
                        : () -> CatalogExportItems.visible(connection, registries).iterator();
            }
            job = new PreviewExport(minecraft, stacks, catalogItems, output, staticFormat, sections, settings.ponderScenes());
            exportStartedAt = System.nanoTime();
            timedPhase = null;
        } catch (IllegalArgumentException exception) {
            status = Component.literal(exception.getMessage());
            LOGGER.warn("Could not start WebCraft export", exception);
            completion.accept(false);
        } catch (LinkageError exception) {
            LOGGER.warn("Could not link the Ponder exporter", exception);
            status = Component.literal("This Ponder version is incompatible with the exporter. See the Minecraft log.");
            completion.accept(false);
        } catch (RuntimeException exception) {
            fail(exception);
        }
        updateButtons();
    }

    @Override
    public void tick() {
        if (job == null || !job.animating()) return;
        try {
            long started = System.nanoTime();
            updatePhaseTiming(started);
            job.captureAnimationTick();
            phaseCaptureNanos += System.nanoTime() - started;
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    void exportFrame() {
        if (job == null) return;
        try {
            if (job.finished()) {
                long finished = System.nanoTime();
                logPhaseTiming(finished);
                LOGGER.info("WebCraft export finished in {} ms, archive worker active for {} ms",
                        TimeUnit.NANOSECONDS.toMillis(finished - exportStartedAt),
                        TimeUnit.NANOSECONDS.toMillis(job.archiveActiveNanos()));
                status = Component.literal(job.result());
                endJob();
                completion.accept(true);
                return;
            }
            long started = System.nanoTime();
            updatePhaseTiming(started);
            reportProgress(started);
            phaseFrames++;
            do {
                if (!job.captureNext()) break;
            } while (job.phase() == timedPhase && System.nanoTime() - started < frameBudgetNanos);
            phaseCaptureNanos += System.nanoTime() - started;
        } catch (IOException | RuntimeException | LinkageError exception) {
            fail(exception);
        }
    }

    private void reportProgress(long now) {
        if (!automatic || (timedPhase == reportedPhase && now - lastProgressAt < PROGRESS_INTERVAL_NANOS)) return;
        var progress = job.progress();
        String detail = progress.detail();
        LOGGER.info("WebCraft export progress: Step {}/{} - {}{}", progress.step(), progress.phases().size(),
                progress.current().label, detail.isEmpty() ? "" : " (" + detail + ")");
        reportedPhase = timedPhase;
        lastProgressAt = now;
    }

    private void updatePhaseTiming(long now) {
        var phase = job.phase();
        if (phase == timedPhase) return;
        logPhaseTiming(now);
        timedPhase = phase;
        phaseStartedAt = now;
        phaseCaptureNanos = 0;
        phaseFrames = 0;
        phaseFirstQueueCheck = job.fullArchiveQueueChecks();
    }

    private void logPhaseTiming(long now) {
        if (timedPhase == null) return;
        LOGGER.info("WebCraft export phase {}: {} ms elapsed, {} ms capture, {} frames, {} full archive queue checks",
                timedPhase.label, TimeUnit.NANOSECONDS.toMillis(now - phaseStartedAt),
                TimeUnit.NANOSECONDS.toMillis(phaseCaptureNanos), phaseFrames,
                job.fullArchiveQueueChecks() - phaseFirstQueueCheck);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int top = contentTop();
        graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);
        if (job == null) {
            int y = top + 16;
            for (var line : font.split(status, Math.min(320, width - 16))) {
                graphics.drawCenteredString(font, line, width / 2, y, 0xDADADA);
                y += font.lineHeight;
            }
            return;
        }
        renderProgress(graphics, top);
    }

    private void renderProgress(GuiGraphics graphics, int top) {
        var progress = job.progress();
        int step = progress.step();
        int count = progress.phases().size();
        graphics.drawCenteredString(font, "Step " + step + " of " + count, width / 2, top + 16, 0xFFFFFF);
        if (!progress.detail().isEmpty()) {
            graphics.drawCenteredString(font, progress.detail(), width / 2, top + 29, 0xE0E0E0);
        }
        int left = (width - BUTTON_WIDTH) / 2;
        int y = top + 46;
        for (int index = 0; index < count; index++) {
            boolean current = index + 1 == step;
            String prefix = current ? "> " : "  ";
            int color = current ? 0xFFD75F : index + 1 < step ? 0xABCC9E : 0xC1C1C1;
            graphics.drawString(font, prefix + (index + 1) + ". " + progress.phases().get(index).label, left, y, color);
            y += 12;
        }
    }

    @Override
    public void removed() {
        boolean cancelled = job != null;
        endJob();
        if (cancelled) completion.accept(false);
    }

    private void fail(Throwable exception) {
        LOGGER.warn("Could not export WebCraft previews", exception);
        status = Component.literal(exception.getCause() instanceof StaticPreviewWriter.WebpEncodingException webp
                ? webp.getMessage() : "Export failed. See the Minecraft log for details.");
        endJob();
        completion.accept(false);
    }

    private void endJob() {
        if (job != null) {
            try {
                job.close();
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not clean up a WebCraft preview export", exception);
            } finally {
                job = null;
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean ready = job == null;
        format.active = format.visible = ready;
        catalog.visible = ready;
        catalog.active = ready && !selectedSections.isEmpty();
        advanced.active = advanced.visible = ready;
        inventory.active = inventory.visible = ready && advancedOpen;
        fullCatalog.visible = ready && advancedOpen;
        fullCatalog.active = fullCatalog.visible && ExportSection.needsCatalog(selectedSections);
        advanced.setMessage(Component.literal(advancedOpen ? "Hide advanced options" : "Advanced options"));
        close.setMessage(ready ? CommonLabels.CLOSE : CommonLabels.CANCEL);
        int firstRow = contentTop() + (ready ? 36 : 139);
        int left = (width - BUTTON_WIDTH) / 2;
        format.setY(firstRow);
        for (int index = 0; index < sectionControls.size(); index++) {
            var checkbox = sectionControls.get(index);
            checkbox.visible = ready;
            checkbox.active = ready && (index != ExportSection.PONDER.ordinal() || ModList.get().isLoaded("ponder")
                    || selectedSections.contains(ExportSection.PONDER));
            checkbox.setX(left + index % 2 * BUTTON_WIDTH / 2);
            checkbox.setY(firstRow + ROW_HEIGHT + index / 2 * CHECKBOX_ROW_HEIGHT);
        }
        int actionRow = firstRow + ROW_HEIGHT + ((sectionControls.size() + 1) / 2) * CHECKBOX_ROW_HEIGHT;
        catalog.setY(actionRow);
        advanced.setY(actionRow + ROW_HEIGHT);
        inventory.setY(actionRow + 2 * ROW_HEIGHT);
        fullCatalog.setY(actionRow + 3 * ROW_HEIGHT);
        close.setY(ready ? actionRow + (advancedOpen ? 4 : 2) * ROW_HEIGHT : firstRow);
    }

    private int contentTop() {
        int contentHeight = job != null ? 159 : advancedOpen ? 230 : 182;
        return Math.max(8, (height - contentHeight) / 2);
    }

    private Component formatLabel() {
        return Component.literal(staticFormat == StaticPreviewWriter.Format.WEBP
                ? "Preview format: WebP" : "Preview format: PNG");
    }

    private enum Mode { INVENTORY, VISIBLE_CATALOG, FULL_CATALOG }

    private static final class CommonLabels {
        private static final Component CLOSE = Component.translatable("gui.done");
        private static final Component CANCEL = Component.translatable("gui.cancel");
    }
}
