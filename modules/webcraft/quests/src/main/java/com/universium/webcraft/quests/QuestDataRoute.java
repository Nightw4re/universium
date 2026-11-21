package com.universium.webcraft.quests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftReadRoute;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

final class QuestDataRoute implements WebCraftReadRoute.InternalRoute {
    private static final Pattern PLAYER_QUERY =
            Pattern.compile("player=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private final BiFunction<MinecraftServer, UUID, Capture> capture;

    QuestDataRoute(BiFunction<MinecraftServer, UUID, Capture> capture) {
        this.capture = capture;
    }

    @Override
    public Response readInternal(MinecraftServer server, String rawQuery) {
        return read(server, player -> capture.apply(server, player), rawQuery);
    }

    static Response read(Executor gameThread, Function<UUID, Capture> capture, String rawQuery) {
        UUID player = null;
        if (rawQuery != null) {
            var match = PLAYER_QUERY.matcher(rawQuery);
            if (!match.matches()) return Response.error(400, "invalid_query");
            player = UUID.fromString(match.group(1));
        }

        UUID requested = player;
        var task = new FutureTask<>(() -> capture.apply(requested));
        gameThread.execute(task);
        Capture result;
        try {
            result = task.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            return Response.error(503, "server_busy");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Response.error(503, "server_busy");
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Could not read FTB Quests on the server thread", exception.getCause());
        } finally {
            task.cancel(false);
        }

        return switch (result) {
            case Catalog catalog -> Response.ok(write(catalog, player != null));
            case Failure failure -> Response.error(failure.status, failure.code);
        };
    }

    private static JsonObject write(Catalog catalog, boolean includeProgress) {
        var chapters = new JsonArray();
        var completed = new JsonArray();
        int total = 0;
        for (var chapter : catalog.chapters()) {
            var quests = new JsonArray();
            for (var quest : chapter.quests()) {
                var row = new JsonObject();
                row.addProperty("id", quest.id());
                row.addProperty("title", quest.title());
                row.addProperty("description", quest.description());
                quests.add(row);
                if (quest.completed()) completed.add(quest.id());
            }
            var row = new JsonObject();
            row.addProperty("id", chapter.id());
            row.addProperty("title", chapter.title());
            row.add("quests", quests);
            chapters.add(row);
            total += quests.size();
        }

        var response = new JsonObject();
        response.addProperty("total", total);
        response.add("chapters", chapters);
        if (includeProgress) {
            response.addProperty("completed", completed.size());
            response.add("completedIds", completed);
        }
        return response;
    }

    sealed interface Capture permits Catalog, Failure {}

    record Catalog(List<Chapter> chapters) implements Capture {}

    record Chapter(String id, String title, List<Quest> quests) {}

    record Quest(String id, String title, String description, boolean completed) {}

    enum Failure implements Capture {
        NOT_READY(503, "quests_unavailable"),
        UNKNOWN_PLAYER(404, "player_not_found"),
        NO_TEAM_PROGRESS(404, "team_progress_unavailable"),
        TOO_LARGE(413, "response_too_large");

        private final int status;
        private final String code;

        Failure(int status, String code) {
            this.status = status;
            this.code = code;
        }
    }
}
