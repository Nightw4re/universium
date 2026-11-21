package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftChanges;
import com.universium.webcraft.api.WebCraftLiveRoute;
import com.universium.webcraft.api.WebCraftReadRoute.Response;

import java.util.List;

final class PlayerHistoryRoute implements WebCraftLiveRoute.Public {
    private final WebCraftChanges changes = new WebCraftChanges();

    @Override
    public WebCraftChanges changes() {
        return changes;
    }
    static final String PATH = "/api/v1/players";

    private volatile List<PlayerHistoryData.Player> players = List.of();

    void publish(List<PlayerHistoryData.Player> snapshot) {
        if (!players.equals(snapshot)) {
            players = List.copyOf(snapshot);
            changes.changed();
        }
    }

    @Override
    public Response readPublic(String rawQuery) {
        if (rawQuery != null) {
            return Response.error(400, "unsupported_query");
        }
        var roster = new JsonArray();
        for (var player : players) {
            var record = new JsonObject();
            record.addProperty("uuid", player.uuid().toString());
            record.addProperty("name", player.name());
            record.addProperty("lastSeenAt", player.lastSeenAt());
            roster.add(record);
        }
        var body = new JsonObject();
        body.add("players", roster);
        return Response.ok(body);
    }
}
