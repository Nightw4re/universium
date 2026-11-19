package com.universium.webcraft.core;

import com.mojang.logging.LogUtils;
import com.universium.webcraft.api.RegisterWebCraftModulesEvent;
import com.universium.webcraft.api.WebCraftReadRoute;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Mod(WebCraftCoreMod.MOD_ID)
public final class WebCraftCoreMod {
    public static final String MOD_ID = "webcraft_core";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ServerSettings settings = new ServerSettings();
    private Map<ResourceLocation, Map<String, WebCraftReadRoute>> registeredModules = Map.of();
    private WebCraftHttpServer listener;
    private ServerInfoRoute serverInfo;

    public WebCraftCoreMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, settings.spec, "webcraft-server.toml");
        modBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(this::startListener);
        NeoForge.EVENT_BUS.addListener(this::stopListener);
        NeoForge.EVENT_BUS.addListener(this::updatePlayerCount);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(this::collectModules);
    }

    private void collectModules() {
        Map<ResourceLocation, Map<String, WebCraftReadRoute>> registrations = new HashMap<>();
        ModLoader.postEvent(new RegisterWebCraftModulesEvent((id, routes) -> {
            if (!id.getNamespace().matches("[a-z0-9][a-z0-9_.-]*") || !id.getPath().matches("[a-z0-9_-]+")
                    || routes.keySet().stream().anyMatch(key -> !key.matches("[a-z0-9_-]+"))) {
                throw new IllegalArgumentException("WebCraft module IDs and route names must use URL-safe segments: " + id);
            }
            if (registrations.putIfAbsent(id, routes) != null) {
                throw new IllegalArgumentException("WebCraft module already registered: " + id);
            }
        }));
        registeredModules = Map.copyOf(registrations);
        LOGGER.info("WebCraft registered modules: {}", registeredModules.keySet());
    }

    private void startListener(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer() || !settings.listenerEnabled.get()) {
            return;
        }
        Map<ResourceLocation, Map<String, WebCraftReadRoute>> active = new HashMap<>();
        for (String configuredId : settings.enabledModules.get()) {
            ResourceLocation id = ResourceLocation.tryParse(configuredId);
            if (id == null || !registeredModules.containsKey(id)) {
                LOGGER.warn("WebCraft module is enabled but not installed: {}", configuredId);
            } else {
                active.put(id, registeredModules.get(id));
            }
        }

        WebCraftHttpServer.ViewerAccess access = null;
        if (!settings.viewerUuid.get().isBlank()) {
            try {
                String configuredUuid = settings.viewerUuid.get();
                UUID uuid = UUID.fromString(configuredUuid);
                if (!uuid.toString().equalsIgnoreCase(configuredUuid)) {
                    throw new IllegalArgumentException();
                }
                access = new WebCraftHttpServer.ViewerAccess(uuid, System.getenv("WEBCRAFT_VIEWER_TOKEN"));
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("WebCraft private routes are locked. Set a canonical viewer UUID and a valid viewer token.");
            }
        }

        try {
            ServerInfoRoute publicInfo = createServerInfo();
            if (publicInfo != null && publicInfo.publishesPlayerCount()) {
                publicInfo.updatePlayerCount(event.getServer().getPlayerCount());
            }
            listener = new WebCraftHttpServer(new InetSocketAddress(settings.bindAddress.get(), settings.port.get()),
                    event.getServer(), active, access, publicInfo, settings.apiDocsEnabled.get());
            serverInfo = publicInfo;
            LOGGER.info("WebCraft HTTP is listening on {}", listener.address());
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.error("WebCraft could not bind its HTTP listener", exception);
        }
    }

    private ServerInfoRoute createServerInfo() {
        if (!settings.serverInfoEnabled.get()) {
            return null;
        }
        boolean publishName = settings.publishModpackName.get();
        boolean publishVersion = settings.publishModpackVersion.get();
        Optional<ModpackMetadata.Identity> pack = Optional.empty();
        if (publishName || publishVersion) {
            var selection = new ModpackMetadata.Selection(settings.modpackSource.get(), settings.modpackFile.get(),
                    settings.modpackNameKey.get(), settings.modpackVersionKey.get());
            pack = new ModpackMetadata(FMLPaths.GAMEDIR.get(), ModpackMetadata::readRunningBcc).resolve(selection);
        }
        return new ServerInfoRoute(settings.serverName.get(), settings.joinAddress.get(),
                settings.publishPlayersOnline.get(), pack, publishName, publishVersion);
    }

    private void updatePlayerCount(ServerTickEvent.Post event) {
        if (serverInfo != null && serverInfo.publishesPlayerCount()) {
            serverInfo.updatePlayerCount(event.getServer().getPlayerCount());
        }
    }

    private void stopListener(ServerStoppingEvent event) {
        serverInfo = null;
        if (listener != null) {
            listener.close();
            listener = null;
        }
    }
}
