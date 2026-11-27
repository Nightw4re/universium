package com.universium.webcraft.quests;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

final class QuestDataRouteTest {
    private static final UUID PLAYER = UUID.fromString("abcdef01-0000-0000-0000-000000000001");
    private static final String CHAPTER_ID = "1111111111111111";
    private static final String FIRST_QUEST_ID = "2222222222222222";
    private static final String SECOND_QUEST_ID = "3333333333333333";

    @Test
    void returnsTheSameCatalogWithOptionalTeamCountsAndOnlyThePublicFields() {
        var catalog = new QuestDataRoute.Catalog(List.of(new QuestDataRoute.Chapter(CHAPTER_ID, "Chapter", List.of(
                new QuestDataRoute.Quest(FIRST_QUEST_ID, "First", "Example description", true),
                new QuestDataRoute.Quest(SECOND_QUEST_ID, "Second", "", false)))));
        var initial = QuestDataRoute.read(Runnable::run, player -> {
            assertNull(player);
            return catalog;
        }, null);
        assertEquals(200, initial.status());
        assertEquals(JsonParser.parseString("""
                {"total":2,"chapters":[{"id":"%s","title":"Chapter","quests":[
                  {"id":"%s","title":"First","description":"Example description"},
                  {"id":"%s","title":"Second","description":""}]}],"itemDetailVersion":5}
                """.formatted(CHAPTER_ID, FIRST_QUEST_ID, SECOND_QUEST_ID)), initial.body());

        var selected = QuestDataRoute.read(Runnable::run, player -> {
            assertEquals(PLAYER, player);
            return catalog;
        }, "player=" + PLAYER);
        var expected = initial.body().deepCopy().getAsJsonObject();
        expected.addProperty("completed", 1);
        expected.add("completedIds", JsonParser.parseString("[\"" + FIRST_QUEST_ID + "\"]"));
        assertEquals(200, selected.status());
        assertEquals(expected, selected.body());
        var compact = QuestDataRoute.read(Runnable::run, player -> catalog, "player=" + PLAYER + "&view=progress");
        expected.remove("chapters");
        expected.remove("itemDetailVersion");
        assertEquals(expected, compact.body());
    }

    @Test
    void returnsCatalogCoordinatesAndOnlyVisibleDependencies() {
        var layout = new QuestDataRoute.Layout(-2.5, 3.25, 1, "hexagon",
                List.of(FIRST_QUEST_ID, CHAPTER_ID, "4444444444444444"), 1, false, false);
        var catalog = new QuestDataRoute.Catalog(List.of(new QuestDataRoute.Chapter(CHAPTER_ID, "Chapter", List.of(
                new QuestDataRoute.Quest(FIRST_QUEST_ID, "First", "", true),
                new QuestDataRoute.Quest(SECOND_QUEST_ID, "Second", "", false, null, null, layout, List.of(), List.of())))));
        var result = QuestDataRoute.read(Runnable::run, player -> catalog, null);
        var quest = result.body().getAsJsonObject().getAsJsonArray("chapters").get(0).getAsJsonObject()
                .getAsJsonArray("quests").get(1);
        assertEquals(JsonParser.parseString("""
                {"id":"%s","title":"Second","description":"","x":-2.5,"y":3.25,"size":1,"shape":"hexagon",
                 "dependencyIds":["%s","%s"],"hiddenDependencyCount":1,"minimumRequired":1}
                """.formatted(SECOND_QUEST_ID, FIRST_QUEST_ID, CHAPTER_ID)), quest);
    }

    @Test
    void publishesChapterMetadataAndIndividualQuestTasksAndRewardsWithoutAddingThemToProgress() {
        var stone = new QuestDataRoute.Art("minecraft:stone", null);
        var dirt = new QuestDataRoute.Art("minecraft:dirt", null);
        var chapter = new QuestDataRoute.Chapter(CHAPTER_ID, "Chapter", List.of(
                new QuestDataRoute.Quest(FIRST_QUEST_ID, "First", "", true, stone, 100L, null,
                        List.of(new QuestDataRoute.Detail("4", "Stone", 1, stone, true, "minecraft:cobblestone", 0)),
                        List.of(new QuestDataRoute.Detail("5", "Experience", 0, null),
                                new QuestDataRoute.Detail("8", "Dirt", 5, dirt, true, "minecraft:dirt", 10)))),
                stone, new QuestDataRoute.Detail("6", "Getting started", 0, null),
                List.of(new QuestDataRoute.Decoration("7", stone, -2, 3, 4, 2, 12, 128, true)));
        var catalog = new QuestDataRoute.Catalog(List.of(chapter));
        var full = QuestDataRoute.read(Runnable::run, player -> catalog, null);
        assertEquals(JsonParser.parseString("""
                {"total":1,"chapters":[{"id":"%s","title":"Chapter","iconItemId":"minecraft:stone",
                  "group":{"id":"6","title":"Getting started"},
                  "images":[{"id":"7","iconItemId":"minecraft:stone","x":-2,"y":3,"width":4,"height":2,
                    "rotation":12,"opacity":%s,"foreground":true}],
                  "quests":[{"id":"%s","title":"First","description":"","iconItemId":"minecraft:stone",
                    "tasks":[{"id":"4","title":"Stone","count":1,"itemEntry":true,"itemId":"minecraft:cobblestone","iconItemId":"minecraft:stone"}],
                    "rewards":[{"id":"5","title":"Experience"},{"id":"8","title":"Dirt","count":5,"countMax":10,
                      "itemEntry":true,"itemId":"minecraft:dirt","iconItemId":"minecraft:dirt"}]}]}],"itemDetailVersion":5}
                """.formatted(CHAPTER_ID, 128 / 255.0, FIRST_QUEST_ID)), full.body());
        var compact = QuestDataRoute.read(Runnable::run, player -> catalog, "player=" + PLAYER + "&view=progress");
        assertEquals(JsonParser.parseString("""
                {"total":1,"completed":1,"completedIds":["%s"],"completedAt":{"%s":100}}
                """.formatted(FIRST_QUEST_ID, FIRST_QUEST_ID)), compact.body());
    }

    @Test
    void keepsQuestAndTaskArtworkSeparateAndDoesNotTurnMobOrExperienceIconsIntoItems() {
        var egg = new QuestDataRoute.Art("minecraft:creeper_spawn_egg", null);
        var battleIcon = new QuestDataRoute.Art("minecraft:iron_sword", null, egg);
        var unavailable = new QuestDataRoute.Art(null, ResourceLocation.fromNamespaceAndPath("missing", "textures/no-such-quest-icon.png"), egg);
        var xp = new QuestDataRoute.Art("minecraft:experience_bottle", null);
        var quest = new QuestDataRoute.Quest(FIRST_QUEST_ID, "Kill: 10x Creeper", "", false, battleIcon, null, null,
                List.of(new QuestDataRoute.Detail("4", "Kill: Creeper", 10, unavailable, true, null, 0)),
                List.of(new QuestDataRoute.Detail("5", "XP", 25, xp, true, null, 0)));
        var catalog = new QuestDataRoute.Catalog(List.of(new QuestDataRoute.Chapter(CHAPTER_ID, "Bounty Board", List.of(quest))));
        var response = QuestDataRoute.read(Runnable::run, player -> catalog, null);
        var row = response.body().getAsJsonObject().getAsJsonArray("chapters").get(0).getAsJsonObject()
                .getAsJsonArray("quests").get(0).getAsJsonObject();
        assertEquals(JsonParser.parseString("""
                {"id":"%s","title":"Kill: 10x Creeper","description":"","iconItemId":"minecraft:iron_sword",
                  "tasks":[{"id":"4","title":"Kill: Creeper","count":10,"itemEntry":true,"iconItemId":"minecraft:creeper_spawn_egg"}],
                  "rewards":[{"id":"5","title":"XP","count":25,"itemEntry":true,"iconItemId":"minecraft:experience_bottle"}]}
                """.formatted(FIRST_QUEST_ID)), row);
    }

    @Test
    void rejectsNonCanonicalOrUnexpectedQueriesBeforeSchedulingGameWork() {
        for (String query : List.of("", "player=", "player=1-0-0-0-1", "player=ABCDEF01-0000-0000-0000-000000000001",
                "player=" + PLAYER + "&player=" + PLAYER, "uuid=" + PLAYER, "player=PlayerName")) {
            var result = QuestDataRoute.read(task -> fail("Invalid query scheduled game work"),
                    player -> fail("Invalid query read player data"), query);
            assertEquals(400, result.status(), query);
            assertEquals("invalid_query", result.body().getAsJsonObject().get("error").getAsString());
        }
    }

    @Test
    void reportsUnresolvedTeamProgressSeparatelyFromUnknownPlayers() {
        var unknown = QuestDataRoute.read(Runnable::run, player -> QuestDataRoute.Failure.UNKNOWN_PLAYER, "player=" + PLAYER);
        var missing = QuestDataRoute.read(Runnable::run, player -> QuestDataRoute.Failure.NO_TEAM_PROGRESS, "player=" + PLAYER);
        assertEquals(404, unknown.status());
        assertEquals(404, missing.status());
        assertEquals("player_not_found", unknown.body().getAsJsonObject().get("error").getAsString());
        assertEquals("team_progress_unavailable", missing.body().getAsJsonObject().get("error").getAsString());
    }
}
