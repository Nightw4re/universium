package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftReadRoute.PublicRoute;
import com.universium.webcraft.api.WebCraftReadRoute.Response;

import java.util.Optional;

final class ServerInfoRoute implements PublicRoute {
    static final String PATH = "/api/v1/server-info";

    private final JsonObject metadata = new JsonObject();
    private final boolean publishPlayerCount;
    private volatile int playersOnline;

    ServerInfoRoute(String serverName, String joinAddress, boolean publishPlayerCount,
                    Optional<ModpackMetadata.Identity> identity, boolean publishName, boolean publishVersion) {
        this.publishPlayerCount = publishPlayerCount;
        if (!serverName.isBlank()) {
            metadata.addProperty("serverName", serverName.strip());
        }
        if (!joinAddress.isBlank()) {
            metadata.addProperty("joinAddress", joinAddress.strip());
        }
        if (identity.isPresent() && (publishName || publishVersion)) {
            JsonObject pack = new JsonObject();
            if (publishName) {
                pack.addProperty("name", identity.get().name());
            }
            if (publishVersion) {
                pack.addProperty("version", identity.get().version());
            }
            metadata.add("modpack", pack);
        }
    }

    boolean publishesPlayerCount() {
        return publishPlayerCount;
    }

    void updatePlayerCount(int count) {
        playersOnline = count;
    }

    @Override
    public Response readPublic(String rawQuery) {
        if (rawQuery != null) {
            return Response.error(400, "unsupported_query");
        }
        JsonObject result = metadata.deepCopy();
        if (publishPlayerCount) {
            result.addProperty("playersOnline", playersOnline);
        }
        return Response.ok(result);
    }
}
