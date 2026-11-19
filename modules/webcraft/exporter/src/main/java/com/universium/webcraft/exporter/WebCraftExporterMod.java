package com.universium.webcraft.exporter;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = "webcraft_exporter", dist = Dist.CLIENT)
public final class WebCraftExporterMod {
    private boolean openRequested;

    public WebCraftExporterMod() {
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::openExportScreen);
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
            minecraft.setScreen(new PreviewExportScreen());
        }
    }
}
