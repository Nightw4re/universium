package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftChanges;
import com.universium.webcraft.api.WebCraftLiveRoute;
import com.universium.webcraft.api.WebCraftReadRoute.Response;

final class PlayerListRoute implements WebCraftLiveRoute.Public {
    private final WebCraftChanges changes = new WebCraftChanges();

    @Override
    public WebCraftChanges changes() {
        return changes;
    }
    static final String ONLINE_PATH = "/api/v1/players/online";
    static final String ROSTER_PATH = "/api/v1/players/all";

    private volatile Response snapshot = response(new JsonArray());

    void publish(JsonArray players) {
        var published = response(players);
        if (!published.equals(snapshot)) {
            snapshot = published;
            changes.changed();
        }
    }

    @Override
    public Response readPublic(String rawQuery) {
        return rawQuery == null ? snapshot : Response.error(400, "unsupported_query");
    }

    private static Response response(JsonArray players) {
        var body = new JsonObject();
        body.add("players", players);
        return Response.ok(body);
    }
}
