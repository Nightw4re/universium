package com.universium.webcraft.quests;

import com.google.gson.JsonParser;
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
                  {"id":"%s","title":"Second","description":""}]}]}
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
