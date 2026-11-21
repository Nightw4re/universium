package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.mojang.logging.LogUtils;
import com.universium.webcraft.api.RegisterWebCraftModulesEvent;
import com.universium.webcraft.api.WebCraftReadRoute;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Mod(WebCraftCoreMod.MOD_ID)
public final class WebCraftCoreMod {
    public static final String MOD_ID = "webcraft_core";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ServerSettings settings = new ServerSettings();
    private final ServerInfoSettings infoSettings = new ServerInfoSettings();
    private final ServerStatusSettings statusSettings = new ServerStatusSettings();
    private final PlayerHistoryRoute playerHistoryRoute = new PlayerHistoryRoute();
    private final PlayerListRoute onlinePlayersRoute = new PlayerListRoute();
    private final PlayerListRoute playerRosterRoute = new PlayerListRoute();
    private Map<ResourceLocation, Map<String, WebCraftReadRoute>> registeredModules = Map.of();
    private WebCraftHttpServer listener;
    private ServerStatusRoute serverStatus;
    private ServerInfoRoute serverInfoRoute;
    private ServerInfoFeed serverInfo;
    private PlayerHistoryData playerHistory;
    private OnlinePlayersFeed onlinePlayers;
    private PlayerRoster playerRoster;
    private ModConfig serverInfoConfig;
    private Instant worldStart;

    public WebCraftCoreMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, settings.spec, "webcraft-server.toml");
        container.registerConfig(ModConfig.Type.SERVER, infoSettings.spec, ServerInfoSettings.FILE_NAME);
        container.registerConfig(ModConfig.Type.SERVER, statusSettings.spec, ServerStatusSettings.FILE_NAME);
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onConfigLoaded);
        NeoForge.EVENT_BUS.addListener(this::onAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onStarted);
        NeoForge.EVENT_BUS.addListener(this::onStopping);
        NeoForge.EVENT_BUS.addListener(this::onStopped);
        NeoForge.EVENT_BUS.addListener(this::updateFeeds);
        NeoForge.EVENT_BUS.addListener(this::recordPlayerJoin);
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

    private void onAboutToStart(ServerAboutToStartEvent event) {
        var server = event.getServer();
        boolean dedicated = server.isDedicatedServer();
        boolean enabled = settings.listenerEnabled(dedicated);
        if (!dedicated && !enabled) {
            return;
        }
        var world = server.getWorldPath(LevelResource.ROOT);
        try {
            worldStart = WorldAgeData.remember(world, server.getWorldData().overworldData().isInitialized(),
                    Instant.now(), configuredWorldStart(server));
        } catch (IOException exception) {
            LOGGER.warn("Could not remember the WebCraft world start for {}", world, exception);
        }
        if (enabled && statusSettings.enabled.get()) {
            startListener(server, true);
        }
    }

    private void onConfigLoaded(ModConfigEvent.Loading event) {
        var config = event.getConfig();
        if (config.getSpec() == infoSettings.spec) {
            serverInfoConfig = config;
        }
    }

    private void onStarted(ServerStartedEvent event) {
        var server = event.getServer();
        boolean dedicated = server.isDedicatedServer();
        boolean enabled = settings.listenerEnabled(dedicated);
        if (!dedicated && !enabled) {
            return;
        }
        var storage = server.overworld().getDataStorage();
        boolean publishRoster = settings.playerRosterEnabled.get();
        boolean needRoster = publishRoster || enabled && settings.advancementsEnabled.get();
        if (settings.playerHistoryEnabled.get() || needRoster) {
            playerHistory = storage.computeIfAbsent(PlayerHistoryData.FACTORY, PlayerHistoryData.FILE_NAME);
            var players = playerHistory.players();
            if (settings.playerHistoryEnabled.get()) playerHistoryRoute.publish(players);
            if (needRoster) {
                playerRoster = new PlayerRoster(server.getWorldPath(LevelResource.ROOT),
                        server.getServerDirectory().resolve("usercache.json"), players);
                if (publishRoster) playerRosterRoute.publish(playerRoster.snapshot());
            }
        }
        if (!enabled) {
            return;
        }
        if (settings.onlinePlayersEnabled.get()) {
            onlinePlayers = new OnlinePlayersFeed(onlinePlayersRoute);
            onlinePlayers.tick(server);
        }
        if (listener == null) {
            startListener(server, false);
        } else if (serverInfoRoute != null) {
            serverInfo = initializeServerInfo(server, serverInfoRoute);
        }
    }

    private Instant configuredWorldStart(MinecraftServer server) {
        String value = infoSettings.worldStartTimestamp.get();
        if (value.isBlank()) return null;
        var worldConfigPath = server.getWorldPath(LevelResource.ROOT)
                .resolve("serverconfig").resolve(ServerInfoSettings.FILE_NAME).toAbsolutePath().normalize();
        if (serverInfoConfig == null || !worldConfigPath.equals(serverInfoConfig.getFullPath().toAbsolutePath().normalize())) {
            LOGGER.warn("Ignoring worldStartTimestamp outside the current world's WebCraft config: {}", worldConfigPath);
            return null;
        }
        var start = WorldAgeData.parse(value).filter(timestamp -> !timestamp.isAfter(Instant.now()));
        if (start.isEmpty()) {
            LOGGER.warn("Ignoring worldStartTimestamp in {}. Use a verified ISO-8601 timestamp in the past.", worldConfigPath);
        }
        return start.orElse(null);
    }

    private void startListener(MinecraftServer server, boolean starting) {
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

        var feeds = new ArrayList<WebCraftHttpServer.Feed>();
        var info = infoSettings.enabled.get() ? new ServerInfoRoute(infoSettings.serverName.get(), infoSettings.joinAddress.get()) : null;
        var status = statusSettings.enabled.get() ? new ServerStatusRoute() : null;
        if (info != null) {
            feeds.add(new WebCraftHttpServer.Feed(ServerInfoRoute.PATH, infoSettings.exposure.get(), info,
                    infoSettings.loopbackOnly.get(), infoSettings.transport.get()));
        }
        if (status != null) {
            feeds.add(new WebCraftHttpServer.Feed(ServerStatusRoute.PATH, statusSettings.exposure.get(), status,
                    statusSettings.loopbackOnly.get(), statusSettings.transport.get()));
        }
        if (settings.playerHistoryEnabled.get()) {
            feeds.add(new WebCraftHttpServer.Feed(PlayerHistoryRoute.PATH, FeedExposure.INTERNAL, playerHistoryRoute,
                    settings.playerHistoryLoopbackOnly.get(), settings.playerHistoryTransport.get()));
        }
        if (settings.onlinePlayersEnabled.get()) {
            feeds.add(new WebCraftHttpServer.Feed(PlayerListRoute.ONLINE_PATH, FeedExposure.INTERNAL, onlinePlayersRoute,
                    settings.onlinePlayersLoopbackOnly.get(), settings.onlinePlayersTransport.get()));
        }
        if (settings.playerRosterEnabled.get()) {
            feeds.add(new WebCraftHttpServer.Feed(PlayerListRoute.ROSTER_PATH, FeedExposure.INTERNAL, playerRosterRoute,
                    settings.playerRosterLoopbackOnly.get(), settings.playerRosterTransport.get()));
        }
        if (settings.advancementsEnabled.get()) {
            var capture = new AdvancementSnapshots(server, () -> playerRoster);
            var saves = new SavedAdvancements(server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR), server.getFixerUpper());
            feeds.add(new WebCraftHttpServer.Feed(AdvancementsRoute.PATH, FeedExposure.INTERNAL,
                    new AdvancementsRoute(server, capture::capture, saves),
                    settings.advancementsLoopbackOnly.get(), settings.advancementsTransport.get()));
        }
        WebCraftHttpServer.BearerToken feedToken = null;
        boolean hasInternalModuleRoute = active.values().stream().flatMap(routes -> routes.values().stream())
                .anyMatch(WebCraftReadRoute.InternalRoute.class::isInstance);
        if (hasInternalModuleRoute || feeds.stream().anyMatch(feed -> feed.exposure() == FeedExposure.INTERNAL)) {
            try {
                feedToken = new WebCraftHttpServer.BearerToken(System.getenv("WEBCRAFT_FEED_TOKEN"), "WEBCRAFT_FEED_TOKEN");
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("WebCraft internal feeds are locked. {}", exception.getMessage());
            }
        }
        try {
            var infoFeed = info != null && !starting ? initializeServerInfo(server, info) : null;
            var address = new InetSocketAddress(settings.listenerHost(server.isDedicatedServer()), settings.port.get());
            var policy = ApiTransport.Policy.from(settings.apiTransport.get(), settings.routeTransports.get());
            listener = new WebCraftHttpServer(address, server, active, access, feeds, feedToken, settings.apiDocsEnabled.get(),
                    policy, settings.websocketOrigins.get());
            serverStatus = status;
            serverInfoRoute = info;
            serverInfo = infoFeed;
            LOGGER.info("WebCraft API is listening on {}", listener.address());
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.error("WebCraft could not start its API listener", exception);
        }
    }

    private ServerInfoFeed initializeServerInfo(MinecraftServer server, ServerInfoRoute route) {
        boolean publishName = infoSettings.publishModpackName.get();
        boolean publishVersion = infoSettings.publishModpackVersion.get();
        Optional<ModpackMetadata.Identity> pack = Optional.empty();
        if (publishName || publishVersion) {
            var selection = new ModpackMetadata.Selection(infoSettings.modpackSource.get(), infoSettings.modpackFile.get(),
                    infoSettings.modpackNameKey.get(), infoSettings.modpackVersionKey.get());
            pack = new ModpackMetadata(FMLPaths.GAMEDIR.get(), ModpackMetadata::readRunningBcc).resolve(selection);
        }
        route.initializeModpack(pack, publishName, publishVersion);
        return new ServerInfoFeed(route, infoSettings, server, worldStart);
    }

    private void updateFeeds(ServerTickEvent.Post event) {
        if (serverInfo != null) {
            serverInfo.tick(event.getServer());
        }
        if (onlinePlayers != null) {
            onlinePlayers.tick(event.getServer());
        }
        if (serverStatus != null && event.getServer().isReady()) {
            serverStatus.markReady();
        }
    }

    private void recordPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (playerHistory != null && event.getEntity() instanceof ServerPlayer player) {
            var uuid = player.getUUID();
            var name = player.getGameProfile().getName();
            playerHistory.recordJoin(uuid, name, System.currentTimeMillis());
            if (settings.playerHistoryEnabled.get()) playerHistoryRoute.publish(playerHistory.players());
            if (playerRoster != null) {
                playerRoster.recordJoin(uuid, name);
                if (settings.playerRosterEnabled.get()) playerRosterRoute.publish(playerRoster.snapshot());
            }
        }
    }

    private void onStopping(ServerStoppingEvent event) {
        if (serverStatus != null) {
            serverStatus.markStopping();
        }
        if (serverInfo != null) {
            serverInfo.stop();
            serverInfo = null;
        }
        if (serverStatus == null) {
            closeListener();
        }
    }

    private void onStopped(ServerStoppedEvent event) {
        closeListener();
        serverInfoRoute = null;
        serverStatus = null;
        serverInfo = null;
        playerHistory = null;
        onlinePlayers = null;
        playerRoster = null;
        serverInfoConfig = null;
        worldStart = null;
        playerHistoryRoute.publish(List.of());
        onlinePlayersRoute.publish(new JsonArray());
        playerRosterRoute.publish(new JsonArray());
    }

    private void closeListener() {
        if (listener != null) {
            listener.close();
            listener = null;
        }
    }
}
