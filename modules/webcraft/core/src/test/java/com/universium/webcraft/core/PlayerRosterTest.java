package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PlayerRosterTest {
    @Test
    void mergesWorldRecordsAndHistoryWithoutAddingCacheOnlyProfiles(@TempDir Path folder) throws IOException {
        var world = Files.createDirectory(folder.resolve("world"));
        var playerdata = Files.createDirectory(world.resolve("playerdata"));
        var stats = Files.createDirectory(world.resolve("stats"));
        var advancements = Files.createDirectory(world.resolve("advancements"));
        Files.createFile(playerdata.resolve("00000000-0000-0000-0000-000000000001.dat"));
        Files.createFile(playerdata.resolve("00000000-0000-0000-0000-000000000001-9472.dat"));
        Files.createFile(playerdata.resolve("00000000-0000-0000-0000-000000000002.dat_old"));
        Files.createDirectory(playerdata.resolve("00000000-0000-0000-0000-000000000007.dat"));
        Files.writeString(stats.resolve("00000000-0000-0000-0000-000000000001.json"), """
                {"stats":{"minecraft:custom":{"minecraft:play_time":3599}}}
                """);
        Files.writeString(stats.resolve("00000000-0000-0000-0000-000000000003.json"), "{broken");
        Files.createFile(advancements.resolve("00000000-0000-0000-0000-000000000004.json"));
        Files.createFile(advancements.resolve("0-0-0-0-9.json"));
        var cache = folder.resolve("usercache.json");
        Files.writeString(cache, """
                [
                  {"uuid":"00000000-0000-0000-0000-000000000001","name":"CachedOldName"},
                  {"uuid":"00000000-0000-0000-0000-000000000003","name":"CachedName"},
                  {"uuid":"00000000-0000-0000-0000-000000000004","name":45},
                  {"uuid":"00000000-0000-0000-0000-000000000008","name":"CacheOnly"}
                ]
                """);
        var roster = new PlayerRoster(world, cache, List.of(
                new PlayerHistoryData.Player(new UUID(0, 1), "RecordedName", 100),
                new PlayerHistoryData.Player(new UUID(0, 5), "RecordedOnly", 125)));
        assertEquals(JsonParser.parseString("""
                [
                  {"uuid":"00000000-0000-0000-0000-000000000001","name":"RecordedName","playTimeMinutes":2},
                  {"uuid":"00000000-0000-0000-0000-000000000002","name":null},
                  {"uuid":"00000000-0000-0000-0000-000000000003","name":"CachedName"},
                  {"uuid":"00000000-0000-0000-0000-000000000004","name":null},
                  {"uuid":"00000000-0000-0000-0000-000000000005","name":"RecordedOnly"}
                ]
                """), roster.snapshot());
        roster.recordJoin(new UUID(0, 2), "NewName");
        roster.recordJoin(new UUID(0, 6), "NewPlayer");
        roster.recordPlayTime(new UUID(0, 6), 1_200);
        assertEquals(1, roster.snapshot().get(5).getAsJsonObject().get("playTimeMinutes").getAsInt());
        assertEquals("NewName", roster.snapshot().get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("NewPlayer", roster.snapshot().get(5).getAsJsonObject().get("name").getAsString());
        assertEquals(new PlayerRoster.NamedPlayer(new UUID(0, 1), "RecordedName"), roster.findByName("recordedname"));
        assertEquals(new PlayerRoster.NamedPlayer(new UUID(0, 3), "CachedName"), roster.findByName("cACHEDnAME"));
        assertNull(roster.findByName("CacheOnly"));
        roster.recordJoin(new UUID(0, 2), "RECORDEDNAME");
        assertNull(roster.findByName("RecordedName"));
    }

    @Test
    void reportsListedPlayerStatsWithNonNegativePingAndWholeMinutes() {
        JsonObject row = OnlinePlayersFeed.row("Player", -1, new UUID(0, 1), 27.5f, 3_599);
        assertEquals(JsonParser.parseString("""
                {"name":"Player","pingMillis":0,"uuid":"00000000-0000-0000-0000-000000000001",
                 "maxHealth":27.5,"playTimeMinutes":2}
                """), row);
    }
}
