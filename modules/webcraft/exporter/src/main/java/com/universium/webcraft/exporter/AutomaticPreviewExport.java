package com.universium.webcraft.exporter;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

final class AutomaticPreviewExport {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WORLD = "WebCraft Export";
    private static final int WARMUP_TICKS = 300;
    private static final long STARTUP_TIMEOUT = TimeUnit.MINUTES.toNanos(15);
    private final ExportSettings settings;
    private final long launched = System.nanoTime();
    private final boolean includeHidden = Boolean.getBoolean("webcraft.export.includeHidden");
    private final StaticPreviewWriter.Format format = Boolean.getBoolean("webcraft.export.png")
            ? StaticPreviewWriter.Format.PNG : StaticPreviewWriter.Format.WEBP;
    private boolean worldRequested;
    private boolean exportStarted;
    private boolean stopping;
    private int ticksInWorld;

    AutomaticPreviewExport(ExportSettings settings) {
        this.settings = settings;
    }

    void tick(ClientTickEvent.Post event) {
        if (stopping) return;
        var client = Minecraft.getInstance();
        if (Files.isRegularFile(client.gameDirectory.toPath().resolve("cancel-export"))) {
            stop(client, "WebCraft automatic export cancelled");
            return;
        }
        if (!exportStarted && System.nanoTime() - launched >= STARTUP_TIMEOUT) {
            stop(client, "WebCraft could not start the automatic export. Check the client log and world loading screen.");
            return;
        }
        try {
            if (!worldRequested && client.getOverlay() == null && client.screen instanceof TitleScreen title) {
                worldRequested = true;
                openWorld(client, title);
            }
            if (!exportStarted && client.player != null && client.level != null && ++ticksInWorld >= WARMUP_TICKS) {
                exportStarted = true;
                String override = System.getProperty("webcraft.export.sections");
                var sections = override == null ? settings.sections() : ExportSection.parse(override);
                if (sections.isEmpty()) {
                    stop(client, "WebCraft automatic export needs at least one section in " + ExportSettings.FILE_NAME);
                    return;
                }
                var screen = new PreviewExportScreen(settings, success -> stop(client,
                        success ? "WebCraft automatic export completed" : "WebCraft automatic export did not complete"));
                LOGGER.info("Starting WebCraft automatic catalog export");
                client.setScreen(screen);
                screen.startAutomatic(format, includeHidden, sections);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Could not start the WebCraft automatic export", exception);
            stop(client, "WebCraft automatic export did not complete");
        }
    }

    private static void openWorld(Minecraft client, TitleScreen title) {
        var flows = client.createWorldOpenFlows();
        if (Files.isRegularFile(client.getLevelSource().getBaseDir().resolve(WORLD).resolve(LevelResource.LEVEL_DATA_FILE.getId()))) {
            flows.openWorld(WORLD, () -> client.setScreen(title));
            return;
        }
        var settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        flows.createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), title);
    }

    private void stop(Minecraft client, String message) {
        if (stopping) return;
        stopping = true;
        LOGGER.info(message);
        client.stop();
    }
}
