package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftChanges;
import com.universium.webcraft.api.WebCraftLiveRoute;
import com.universium.webcraft.api.WebCraftReadRoute.Response;

import java.util.Optional;

final class ServerInfoRoute implements WebCraftLiveRoute.Public {
    private final WebCraftChanges changes = new WebCraftChanges();

    @Override
    public WebCraftChanges changes() {
        return changes;
    }
    static final String PATH = "/api/v1/server-info";

    private final JsonObject metadata = new JsonObject();
    private volatile Snapshot snapshot;

    ServerInfoRoute(String serverName, String joinAddress) {
        if (!serverName.isBlank()) {
            metadata.addProperty("serverName", serverName.strip());
        }
        if (!joinAddress.isBlank()) {
            metadata.addProperty("joinAddress", joinAddress.strip());
        }
        publish(new JsonObject(), null, null);
    }

    void initializeModpack(Optional<ModpackMetadata.Identity> identity, boolean publishName, boolean publishVersion) {
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
        publish(new JsonObject(), null, null);
    }

    void publish(JsonObject liveFields, String pngIcon, String webpIcon) {
        var next = metadata.deepCopy();
        liveFields.entrySet().forEach(field -> next.add(field.getKey(), field.getValue()));
        if (pngIcon != null) {
            next.addProperty("serverIcon", pngIcon);
        }
        var preferred = next;
        if (webpIcon != null) {
            preferred = next.deepCopy();
            preferred.addProperty("serverIcon", webpIcon);
        }
        var published = new Snapshot(next, preferred);
        if (!published.equals(snapshot)) {
            snapshot = published;
            changes.changed();
        }
    }

    @Override
    public Response readPublic(String rawQuery) {
        var current = snapshot;
        return switch (rawQuery) {
            case null -> Response.ok(current.png());
            case "iconFormat=webp" -> Response.ok(current.webpPreferred());
            default -> Response.error(400, "unsupported_query");
        };
    }

    private record Snapshot(JsonObject png, JsonObject webpPreferred) {}
}
