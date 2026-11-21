package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftChanges;
import com.universium.webcraft.api.WebCraftLiveRoute;
import com.universium.webcraft.api.WebCraftReadRoute.Response;

final class ServerStatusRoute implements WebCraftLiveRoute.Public {
    private final WebCraftChanges changes = new WebCraftChanges();

    @Override
    public WebCraftChanges changes() {
        return changes;
    }
    static final String PATH = "/api/v1/server-status";

    enum State {
        STARTING("starting"),
        READY("ready"),
        STOPPING("stopping");

        private final String value;

        State(String value) {
            this.value = value;
        }
    }

    // Lifecycle hooks update the state on the game thread. HTTP workers read the latest state.
    private volatile State state = State.STARTING;

    void markReady() {
        if (state == State.STARTING) {
            state = State.READY;
            changes.changed();
        }
    }

    void markStopping() {
        state = State.STOPPING;
        changes.changed();
    }

    Response unavailable() {
        return state == State.READY ? null : Response.error(503, "server_unavailable");
    }

    @Override
    public Response readPublic(String rawQuery) {
        if (rawQuery != null) {
            return Response.error(400, "unsupported_query");
        }
        var body = new JsonObject();
        body.addProperty("status", state.value);
        return Response.ok(body);
    }
}
