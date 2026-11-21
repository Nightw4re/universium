package com.universium.webcraft.ponder;

import com.universium.webcraft.api.RegisterWebCraftModulesEvent;
import com.universium.webcraft.api.WebCraftPreviewPack;
import com.universium.webcraft.api.WebCraftReadRoute;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.nio.file.Path;
import java.util.Map;

@Mod(WebCraftPonderMod.MOD_ID)
public final class WebCraftPonderMod {
    static final String MOD_ID = "webcraft_ponder";
    private final PonderRoutes routes = new PonderRoutes(new WebCraftPreviewPack(Path.of("webcraft-assets", "previews.zip")));

    public WebCraftPonderMod(IEventBus modBus) {
        modBus.addListener(this::register);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, this::start);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::stop);
    }

    private void register(RegisterWebCraftModulesEvent event) {
        event.register(ResourceLocation.fromNamespaceAndPath(MOD_ID, "ponder"), Map.of(
                "index", (WebCraftReadRoute.PublicRoute) routes::index,
                "asset", (WebCraftReadRoute.PublicRoute) routes::asset));
    }

    private void start(ServerStartedEvent event) {
        routes.start();
    }

    private void stop(ServerStoppedEvent event) {
        routes.close();
    }
}
