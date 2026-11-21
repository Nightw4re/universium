package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

final class ServerInfoFeed {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TICKS_PER_DAY = 24_000;
    private final ServerInfoRoute route;
    private final Fields fields;
    private final ServerUptimeData uptime;
    private final Instant worldStart;
    private final String webpIcon;
    private final long startedAt = System.nanoTime();
    private long refreshAt = startedAt;
    private ServerStatus.Favicon icon;
    private String iconData;

    ServerInfoFeed(ServerInfoRoute route, ServerInfoSettings settings, MinecraftServer server, Instant worldStart) {
        this.route = route;
        this.worldStart = settings.publishWorldAge.get() ? worldStart : null;
        fields = new Fields(settings.publishPlayersOnline.get(), settings.publishMotd.get(), settings.publishServerIcon.get(),
                settings.publishUptime.get(), settings.publishWorldTime.get());
        uptime = settings.publishTotalUptime.get()
                ? openUptime(server)
                : null;
        webpIcon = fields.icon() ? ServerListIcon.loadWebp(server.getServerDirectory().resolve("server-icon.webp")) : null;
        tick(server);
    }

    void tick(MinecraftServer server) {
        long now = System.nanoTime();
        if (now - refreshAt < 0) {
            return;
        }
        var status = fields.icon() ? server.getStatus() : null;
        // The first tick can pick up the favicon initialized just after ServerStartedEvent.
        if (!fields.icon() || status != null) {
            refreshAt = now + TimeUnit.SECONDS.toNanos(1);
        }
        long sessionMillis = TimeUnit.NANOSECONDS.toMillis(now - startedAt);
        var fieldsJson = new JsonObject();
        if (fields.playersOnline()) {
            fieldsJson.addProperty("playersOnline", server.getPlayerCount());
            fieldsJson.addProperty("maxPlayers", server.getMaxPlayers());
        }
        if (fields.motd()) {
            String motd = server.getMotd();
            if (motd != null && !motd.isBlank()) {
                fieldsJson.addProperty("motd", motd);
            }
        }
        if (fields.icon()) {
            var next = status == null ? null : status.favicon().orElse(null);
            if (next != icon) {
                icon = next;
                iconData = icon == null ? null : ServerListIcon.png(icon.iconBytes());
            }
        }
        if (fields.uptime()) {
            fieldsJson.addProperty("uptimeSeconds", TimeUnit.MILLISECONDS.toSeconds(sessionMillis));
        }
        if (uptime != null) {
            fieldsJson.addProperty("totalUptimeSeconds", TimeUnit.MILLISECONDS.toSeconds(uptime.checkpoint(sessionMillis)));
        }
        if (worldStart != null) {
            long minutes = WorldAgeData.completedMinutes(worldStart, Instant.now());
            fieldsJson.addProperty("worldAgeDays", TimeUnit.MINUTES.toDays(minutes));
            fieldsJson.addProperty("worldAgeMinutes", minutes);
        }
        if (fields.worldTime()) {
            fieldsJson.addProperty("worldTimeTicks", Math.floorMod(server.overworld().getDayTime(), TICKS_PER_DAY));
        }
        route.publish(fieldsJson, iconData, webpIcon);
    }

    void stop() {
        if (uptime != null) {
            uptime.stop(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        }
    }

    private static ServerUptimeData openUptime(MinecraftServer server) {
        var world = server.getWorldPath(LevelResource.ROOT);
        try {
            return ServerUptimeData.open(world);
        } catch (IOException exception) {
            LOGGER.warn("Could not load WebCraft total uptime for {}", world, exception);
            return null;
        }
    }

    private record Fields(boolean playersOnline, boolean motd, boolean icon, boolean uptime, boolean worldTime) {}
}
