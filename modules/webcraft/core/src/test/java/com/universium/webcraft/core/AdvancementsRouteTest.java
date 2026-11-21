package com.universium.webcraft.core;

import com.google.gson.JsonParser;
import com.universium.webcraft.api.WebCraftReadRoute;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class AdvancementsRouteTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID OTHER_PLAYER = new UUID(0, 2);
    private static final String TOKEN = "A".repeat(43);
    private static final ResourceLocation TASK = ResourceLocation.parse("demo:a_task");
    private static final ResourceLocation GOAL = ResourceLocation.parse("demo:z_goal");
    private static final ResourceLocation SECRET = ResourceLocation.parse("demo:secret");
    private static final String DATE = "2026-01-01 00:00:00 +0000";

    @BeforeAll
    static void initializeMinecraft() {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void exposesOnlyAStableVisibleCatalogThroughTheInternalGetRoute(@TempDir Path saves) throws Exception {
        var calls = new AtomicInteger();
        var catalog = catalog();
        var route = new AdvancementsRoute(Runnable::run, name -> {
            calls.incrementAndGet();
            return name == null ? new AdvancementSnapshots.Snapshot(catalog, null, Set.of()) : null;
        }, saved(saves));
        var feed = new WebCraftHttpServer.Feed(AdvancementsRoute.PATH, FeedExposure.INTERNAL,
                route, true, ApiTransport.INHERIT);
        try (var server = new WebCraftHttpServer(new InetSocketAddress("127.0.0.1", 0), null, Map.of(), null,
                List.of(feed), new WebCraftHttpServer.BearerToken(TOKEN, "TEST"), true,
                new ApiTransport.Policy(ApiTransport.HTTP, Map.of()), List.of());
             var client = HttpClient.newHttpClient()) {
            assertEquals(404, http(client, server, "GET", AdvancementsRoute.PATH, TOKEN).statusCode());
            assertEquals(401, http(client, server, "GET", feed.path() + "?player=Someone", null).statusCode());
            assertEquals(401, http(client, server, "GET", feed.path(), "B".repeat(43)).statusCode());
            var post = http(client, server, "POST", feed.path(), TOKEN);
            assertEquals(405, post.statusCode());
            assertEquals("GET", post.headers().firstValue("Allow").orElseThrow());
            assertEquals(400, route.readPublic("").status());
            for (String query : List.of("?player=", "?player=ab", "?player=x/y", "?player=Some%20Name",
                    "?player=SomeName&player=OtherName", "?uuid=" + PLAYER)) {
                assertEquals(400, http(client, server, "GET", feed.path() + query, TOKEN).statusCode(), query);
            }
            assertEquals(0, calls.get());
            var response = http(client, server, "GET", feed.path(), TOKEN);
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(JsonParser.parseString("""
                    {"total":2,"advancements":[
                      {"id":"demo:a_task","title":"Task","description":"Task description","frame":"task"},
                      {"id":"demo:z_goal","title":"Goal","description":"Goal description","frame":"goal"}]}
                    """), JsonParser.parseString(response.body()));
            assertEquals(404, http(client, server, "GET", feed.path() + "?player=Someone", TOKEN).statusCode());
            String spec = http(client, server, "GET", ApiDocumentation.SPEC_PATH, null).body();
            assertFalse(JsonParser.parseString(spec).getAsJsonObject().getAsJsonObject("paths").has(feed.path()));
        }
    }

    @Test
    void countsOnlySelectedPlayersCurrentVisibleRequirementsFromTheSave(@TempDir Path saves) throws Exception {
        String progress = """
                {"demo:a_task":{"criteria":{"either":"%1$s","also":"%1$s"},"done":false},
                 "demo:z_goal":{"criteria":{"removed_criterion":"%1$s"},"done":true},
                 "demo:secret":{"criteria":{"always":"%1$s"},"done":true},
                 "demo:removed":{"criteria":{"always":"%1$s"},"done":true},
                 "DataVersion":%2$d}
                """.formatted(DATE, dataVersion());
        var selectedFile = saves.resolve(PLAYER + ".json");
        Files.writeString(selectedFile, progress);
        Files.writeString(saves.resolve(OTHER_PLAYER + ".json"), """
                {"demo:z_goal":{"criteria":{"always":"%s"},"done":true},"DataVersion":%d}
                """.formatted(DATE, dataVersion()));
        var route = selectedPlayer(saves, null);
        var response = route.readPublic("player=TeStPlAyEr");
        assertEquals(200, response.status());
        var body = response.body().getAsJsonObject();
        assertEquals(Set.of("total", "advancements", "player", "completed", "completedIds"), body.keySet());
        assertEquals("TestPlayer", body.get("player").getAsString());
        assertEquals(2, body.get("total").getAsInt());
        assertEquals(1, body.get("completed").getAsInt());
        assertEquals(JsonParser.parseString("[\"demo:a_task\"]"), body.get("completedIds"));
        assertEquals(progress, Files.readString(selectedFile));
    }

    @Test
    void differentiatesMissingOfflineProgressFromInvalidFilesAndPrefersLiveProgress(@TempDir Path saves) throws Exception {
        var offline = selectedPlayer(saves, null);
        var empty = offline.readPublic("player=TestPlayer");
        assertEquals(200, empty.status());
        assertEquals(0, empty.body().getAsJsonObject().get("completed").getAsInt());
        var file = saves.resolve(PLAYER + ".json");
        Files.createDirectory(file);
        assertUnavailable(offline.readPublic("player=TestPlayer"));
        Files.delete(file);
        for (String bad : List.of("{", "{} {}", "{\"DataVersion\":\"invalid\"}", """
                {"demo:a_task":{"criteria":{"also":"not a date"}},"DataVersion":%d}
                """.formatted(dataVersion()))) {
            Files.writeString(file, bad);
            assertUnavailable(offline.readPublic("player=TestPlayer"));
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            channel.position(8 * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertUnavailable(offline.readPublic("player=TestPlayer"));
        var live = selectedPlayer(saves, Set.of(GOAL, SECRET)).readPublic("player=TestPlayer");
        assertEquals(200, live.status());
        assertEquals(JsonParser.parseString("[\"demo:z_goal\"]"), live.body().getAsJsonObject().get("completedIds"));
    }

    private static void assertUnavailable(WebCraftReadRoute.Response response) {
        assertEquals(503, response.status());
        assertEquals(JsonParser.parseString("{\"error\":\"advancement_progress_unavailable\"}"), response.body());
    }

    private static AdvancementsRoute selectedPlayer(Path saves, Set<ResourceLocation> live) {
        var catalog = catalog();
        var roster = new PlayerRoster(saves, saves.resolve("usercache.json"), List.of(
                new PlayerHistoryData.Player(PLAYER, "TestPlayer", 1)));
        return new AdvancementsRoute(Runnable::run, name -> {
            var player = roster.findByName(name);
            return player == null ? null : new AdvancementSnapshots.Snapshot(catalog, player, live);
        }, saved(saves));
    }

    private static List<AdvancementSnapshots.Entry> catalog() {
        var tree = new AdvancementTree();
        var taskRequirements = new AdvancementRequirements(List.of(List.of("either", "alternative"), List.of("also")));
        var otherRequirements = AdvancementRequirements.allOf(List.of("always"));
        var taskDisplay = display("Task", AdvancementType.TASK, false);
        var goalDisplay = display("Goal", AdvancementType.GOAL, false);
        tree.addAll(List.of(
                advancement(GOAL, goalDisplay, otherRequirements),
                advancement(SECRET, display("Secret", AdvancementType.CHALLENGE, true), otherRequirements),
                advancement(ResourceLocation.parse("demo:recipes/wood"), taskDisplay, otherRequirements),
                advancement(ResourceLocation.parse("demo:no_display"), null, otherRequirements),
                advancement(TASK, taskDisplay, taskRequirements)));
        return AdvancementSnapshots.visibleCatalog(tree.nodes());
    }

    private static DisplayInfo display(String title, AdvancementType frame, boolean hidden) {
        return new DisplayInfo(ItemStack.EMPTY, Component.literal(title), Component.literal(title + " description"),
                Optional.empty(), frame, true, true, hidden);
    }

    private static AdvancementHolder advancement(ResourceLocation id, DisplayInfo display, AdvancementRequirements requirements) {
        return new AdvancementHolder(id, new Advancement(Optional.empty(), Optional.ofNullable(display),
                AdvancementRewards.EMPTY, Map.of(), requirements, false));
    }

    private static int dataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    private static SavedAdvancements saved(Path saves) {
        return new SavedAdvancements(saves, DataFixers.getDataFixer());
    }

    private static HttpResponse<String> http(HttpClient client, WebCraftHttpServer server, String method,
                                             String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.address().getPort() + path))
                .timeout(Duration.ofSeconds(3)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
