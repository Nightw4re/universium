package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.universium.webcraft.api.WebCraftReadRoute;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Registered as an internal feed so the dispatcher checks access before reading any data. */
final class AdvancementsRoute implements WebCraftReadRoute.PublicRoute {
    static final String PATH = "/api/v1/advancements";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern PLAYER_QUERY = Pattern.compile("player=([A-Za-z0-9_]{3,16})(&view=progress)?");
    private final Executor gameThread;
    private final Function<String, AdvancementSnapshots.Snapshot> capture;
    private final SavedAdvancements saves;

    AdvancementsRoute(Executor gameThread, Function<String, AdvancementSnapshots.Snapshot> capture, SavedAdvancements saves) {
        this.gameThread = gameThread;
        this.capture = capture;
        this.saves = saves;
    }

    @Override
    public Response readPublic(String rawQuery) {
        String name = null;
        boolean progressOnly = false;
        if (rawQuery != null) {
            var match = PLAYER_QUERY.matcher(rawQuery);
            if (!match.matches()) return Response.error(400, "invalid_query");
            name = match.group(1);
            progressOnly = match.group(2) != null;
        }

        String requested = name;
        var task = new FutureTask<>(() -> capture.apply(requested));
        gameThread.execute(task);
        AdvancementSnapshots.Snapshot snapshot;
        try {
            snapshot = task.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            return Response.error(503, "server_busy");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Response.error(503, "server_busy");
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Could not read advancements on the server thread", exception.getCause());
        } finally {
            task.cancel(false);
        }
        if (snapshot == null) return Response.error(404, "player_not_found");

        var completed = snapshot.completed();
        var dates = snapshot.dates();
        if (completed == null) {
            try {
                dates = saves.completed(snapshot.player().uuid(), snapshot.catalog());
                completed = dates.keySet();
            } catch (IOException exception) {
                LOGGER.debug("Could not read saved advancement progress", exception);
                return Response.error(503, "advancement_progress_unavailable");
            }
        }

        var advancements = new JsonArray();
        var completedIds = new JsonArray();
        var completedAt = new JsonObject();
        for (var entry : snapshot.catalog()) {
            String id = entry.id().toString();
            if (completed.contains(entry.id())) {
                completedIds.add(id);
                var timestamp = dates.get(entry.id());
                if (timestamp != null) completedAt.addProperty(id, timestamp);
            }
            if (progressOnly) continue;
            var row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("title", entry.title());
            row.addProperty("description", entry.description());
            row.addProperty("frame", entry.frame());
            row.addProperty("group", entry.group().toString());
            row.addProperty("groupTitle", entry.groupTitle());
            if (entry.iconItemId() != null) row.addProperty("iconItemId", entry.iconItemId().toString());
            row.addProperty("x", entry.x());
            row.addProperty("y", entry.y());
            if (entry.parentId() != null) row.addProperty("parentId", entry.parentId().toString());
            advancements.add(row);
        }
        var body = new JsonObject();
        body.addProperty("total", snapshot.catalog().size());
        if (!progressOnly) body.add("advancements", advancements);
        if (snapshot.player() != null) {
            body.addProperty("player", snapshot.player().name());
            body.addProperty("completed", completedIds.size());
            body.add("completedIds", completedIds);
            if (progressOnly && completedAt.size() > 0) body.add("completedAt", completedAt);
        }
        return Response.ok(body);
    }
}
