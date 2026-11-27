package com.universium.webcraft.quests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftReadRoute;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class QuestDataRoute implements WebCraftReadRoute.InternalRoute {
    private static final Pattern PLAYER_QUERY =
            Pattern.compile("player=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(&view=progress)?");
    private final CatalogCapture capture;

    QuestDataRoute(CatalogCapture capture) {
        this.capture = capture;
    }

    @Override
    public Response readInternal(MinecraftServer server, String rawQuery) {
        return read(server, (player, progressOnly) -> capture.read(server, player, progressOnly), rawQuery, new QuestArtwork());
    }

    static Response read(Executor gameThread, Function<UUID, Capture> capture, String rawQuery) {
        return read(gameThread, (player, progressOnly) -> capture.apply(player), rawQuery, new QuestArtwork());
    }

    private static Response read(Executor gameThread, BiFunction<UUID, Boolean, Capture> capture, String rawQuery,
                                 QuestArtwork artwork) {
        UUID player = null;
        boolean progressOnly = false;
        if (rawQuery != null) {
            var match = PLAYER_QUERY.matcher(rawQuery);
            if (!match.matches()) return Response.error(400, "invalid_query");
            player = UUID.fromString(match.group(1));
            progressOnly = match.group(2) != null;
        }

        UUID requested = player;
        boolean compact = progressOnly;
        var task = new FutureTask<>(() -> capture.apply(requested, compact));
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
            case Catalog catalog -> Response.ok(write(catalog, player != null, progressOnly, artwork));
            case Failure failure -> Response.error(failure.status, failure.code);
        };
    }

    private static JsonObject write(Catalog catalog, boolean includeProgress, boolean progressOnly, QuestArtwork artwork) {
        var chapters = new JsonArray();
        var completed = new JsonArray();
        var completedAt = new JsonObject();
        var visibleIds = progressOnly ? Set.<String>of() : catalog.chapters().stream()
                .flatMap(chapter -> Stream.concat(Stream.of(chapter.id()), chapter.quests().stream().map(Quest::id)))
                .collect(Collectors.toUnmodifiableSet());
        int total = 0;
        for (var chapter : catalog.chapters()) {
            var quests = new JsonArray();
            for (var quest : chapter.quests()) {
                total++;
                if (quest.completed()) {
                    completed.add(quest.id());
                    if (quest.completedAt() != null) completedAt.addProperty(quest.id(), quest.completedAt());
                }
                if (progressOnly) continue;
                var row = new JsonObject();
                row.addProperty("id", quest.id());
                row.addProperty("title", quest.title());
                row.addProperty("description", quest.description());
                writeArt(row, quest.art(), artwork);
                if (quest.layout() != null) writeLayout(row, quest.layout(), visibleIds);
                if (!quest.tasks().isEmpty()) row.add("tasks", writeDetails(quest.tasks(), artwork));
                if (!quest.rewards().isEmpty()) row.add("rewards", writeDetails(quest.rewards(), artwork));
                quests.add(row);
            }
            if (progressOnly) continue;
            var row = new JsonObject();
            row.addProperty("id", chapter.id());
            row.addProperty("title", chapter.title());
            writeArt(row, chapter.art(), artwork);
            if (chapter.group() != null) row.add("group", writeDetail(chapter.group(), artwork));
            var images = new JsonArray();
            for (var image : chapter.images()) {
                var decoration = new JsonObject();
                if (!writeArt(decoration, image.art(), artwork)) continue;
                decoration.addProperty("id", image.id());
                decoration.addProperty("x", image.x());
                decoration.addProperty("y", image.y());
                decoration.addProperty("width", image.width());
                decoration.addProperty("height", image.height());
                if (image.rotation() != 0) decoration.addProperty("rotation", image.rotation());
                if (image.alpha() < 255) decoration.addProperty("opacity", image.alpha() / 255.0);
                if (image.foreground()) decoration.addProperty("foreground", true);
                images.add(decoration);
            }
            if (images.size() > 0) row.add("images", images);
            row.add("quests", quests);
            chapters.add(row);
        }

        var response = new JsonObject();
        response.addProperty("total", total);
        if (!progressOnly) {
            response.add("chapters", chapters);
            response.addProperty("itemDetailVersion", 5);
        }
        if (includeProgress) {
            response.addProperty("completed", completed.size());
            response.add("completedIds", completed);
            if (completedAt.size() > 0) response.add("completedAt", completedAt);
        }
        return response;
    }

    private static JsonArray writeDetails(List<Detail> entries, QuestArtwork artwork) {
        var result = new JsonArray();
        entries.forEach(entry -> result.add(writeDetail(entry, artwork)));
        return result;
    }

    private static JsonObject writeDetail(Detail detail, QuestArtwork artwork) {
        var row = new JsonObject();
        row.addProperty("id", detail.id());
        row.addProperty("title", detail.title());
        if (detail.count() > 1 || (detail.itemEntry() && detail.count() == 1)) row.addProperty("count", detail.count());
        if (detail.countMax() > detail.count() && detail.count() > 0) row.addProperty("countMax", detail.countMax());
        if (detail.itemEntry()) row.addProperty("itemEntry", true);
        if (detail.itemId() != null) row.addProperty("itemId", detail.itemId());
        writeArt(row, detail.art(), artwork);
        return row;
    }

    private static boolean writeArt(JsonObject row, Art art, QuestArtwork artwork) {
        if (art == null) return false;
        String encoded = art.texture() == null ? null : artwork.read(art.texture());
        if (encoded != null) row.addProperty("iconImage", encoded);
        if (art.itemId() != null) row.addProperty("iconItemId", art.itemId());
        return encoded != null || art.itemId() != null || writeArt(row, art.fallback(), artwork);
    }

    private static void writeLayout(JsonObject row, Layout layout, Set<String> visibleIds) {
        if (Double.isFinite(layout.x()) && Double.isFinite(layout.y())) {
            row.addProperty("x", layout.x());
            row.addProperty("y", layout.y());
        }
        if (Double.isFinite(layout.size()) && layout.size() > 0) row.addProperty("size", layout.size());
        if (layout.shape() != null && layout.shape().length() <= 32) row.addProperty("shape", layout.shape());
        var dependencies = new JsonArray();
        layout.dependencyIds().stream().filter(visibleIds::contains).forEach(dependencies::add);
        row.add("dependencyIds", dependencies);
        int hidden = layout.dependencyIds().size() - dependencies.size();
        if (hidden > 0) row.addProperty("hiddenDependencyCount", hidden);
        if (layout.minimumRequired() > 0) {
            row.addProperty("minimumRequired", layout.minimumRequired());
        }
        if (layout.hideDependencyLines()) row.addProperty("hideDependencyLines", true);
        if (layout.hideDependentLines()) row.addProperty("hideDependentLines", true);
    }

    sealed interface Capture permits Catalog, Failure {}

    @FunctionalInterface
    interface CatalogCapture {
        Capture read(MinecraftServer server, UUID player, boolean progressOnly);
    }

    record Catalog(List<Chapter> chapters) implements Capture {}

    record Art(String itemId, ResourceLocation texture, Art fallback) {
        Art(String itemId, ResourceLocation texture) {
            this(itemId, texture, null);
        }
    }

    record Detail(String id, String title, long count, Art art, boolean itemEntry, String itemId, long countMax) {
        Detail(String id, String title, long count, Art art) {
            this(id, title, count, art, false, null, 0);
        }
    }

    record Decoration(String id, Art art, double x, double y, double width, double height, double rotation, int alpha,
                      boolean foreground) {}

    record Chapter(String id, String title, List<Quest> quests, Art art, Detail group, List<Decoration> images) {
        Chapter(String id, String title, List<Quest> quests) {
            this(id, title, quests, null, null, List.of());
        }
    }

    record Layout(double x, double y, double size, String shape, List<String> dependencyIds, int minimumRequired,
                  boolean hideDependencyLines, boolean hideDependentLines) {}

    record Quest(String id, String title, String description, boolean completed, Art art, Long completedAt,
                 Layout layout, List<Detail> tasks, List<Detail> rewards) {
        Quest(String id, String title, String description, boolean completed) {
            this(id, title, description, completed, null, null, null, List.of(), List.of());
        }
    }

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
