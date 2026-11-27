package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.Stats;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class OnlinePlayersFeed {
    private final PlayerListRoute route;
    private long refreshAt = System.nanoTime();

    OnlinePlayersFeed(PlayerListRoute route) {
        this.route = route;
    }

    void tick(MinecraftServer server) {
        long now = System.nanoTime();
        if (now - refreshAt < 0) return;
        refreshAt = now + TimeUnit.SECONDS.toNanos(1);

        var players = new JsonArray();
        if (!server.hidesOnlinePlayers()) {
            for (var player : server.getPlayerList().getPlayers()) {
                if (player.allowsListing()) {
                    var profile = player.getGameProfile();
                    var entry = row(profile.getName(), player.connection.latency(), player.getUUID(),
                            player.getMaxHealth(), player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)));
                    entry.addProperty("gameMode", player.gameMode.getGameModeForPlayer().getName());
                    entry.addProperty("operator", server.getPlayerList().isOp(profile));
                    players.add(entry);
                }
            }
        }
        route.publish(players);
    }

    static JsonObject row(String name, int pingMillis, UUID uuid, float maxHealth, int playTimeTicks) {
        var player = new JsonObject();
        player.addProperty("name", name);
        player.addProperty("pingMillis", Math.max(0, pingMillis));
        player.addProperty("uuid", uuid.toString());
        player.addProperty("maxHealth", maxHealth);
        player.addProperty("playTimeMinutes", Math.max(0, playTimeTicks) / 1_200);
        return player;
    }
}
