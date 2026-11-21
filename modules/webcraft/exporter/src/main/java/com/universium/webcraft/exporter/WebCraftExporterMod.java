package com.universium.webcraft.exporter;

import com.universium.webcraft.core.WebCraftCoreMod;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = WebCraftCoreMod.MOD_ID, dist = Dist.CLIENT)
public final class WebCraftExporterMod {
    private final ExportSettings settings = new ExportSettings();
    private boolean openRequested;

    public WebCraftExporterMod(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, settings.spec, ExportSettings.FILE_NAME);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::openExportScreen);
        NeoForge.EVENT_BUS.addListener(this::exportFrame);
        if (Boolean.getBoolean("webcraft.automaticExport")) {
            var run = new AutomaticPreviewExport(settings);
            NeoForge.EVENT_BUS.addListener(run::tick);
        }
    }

    private void exportFrame(RenderFrameEvent.Pre event) {
        if (Minecraft.getInstance().screen instanceof PreviewExportScreen screen) {
            screen.exportFrame();
        }
    }

    private void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("webcraft").then(Commands.literal("export")
                .executes(context -> {
                    if (Minecraft.getInstance().level == null) {
                        context.getSource().sendFailure(Component.literal("Load a Minecraft world before exporting previews."));
                        return 0;
                    }
                    openRequested = true;
                    return 1;
                })));
    }

    private void openExportScreen(ClientTickEvent.Post event) {
        if (!openRequested) {
            return;
        }
        openRequested = false;
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) {
            minecraft.setScreen(new PreviewExportScreen(settings));
        }
    }
}
