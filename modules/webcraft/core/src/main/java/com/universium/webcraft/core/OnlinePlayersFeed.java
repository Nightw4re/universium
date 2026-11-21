package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

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
                    players.add(row(player.getGameProfile().getName(), player.connection.latency()));
                }
            }
        }
        route.publish(players);
    }

    static JsonObject row(String name, int pingMillis) {
        var player = new JsonObject();
        player.addProperty("name", name);
        player.addProperty("pingMillis", Math.max(0, pingMillis));
        return player;
    }
}
