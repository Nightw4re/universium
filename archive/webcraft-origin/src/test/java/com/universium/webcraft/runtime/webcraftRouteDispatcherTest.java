package com.universium.webcraft.runtime;

import com.universium.webcraft.config.webcraftConfig;
import com.universium.webcraft.playerinfo.PlayerInfoData;
import com.universium.webcraft.runtime.webcraftPlayerDataProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.universium.webcraft.TestFixtures.config;
import static com.universium.webcraft.TestFixtures.samplePlayer;

class webcraftRouteDispatcherTest {
    @Test
    void returnsServerInfoFromConfig() {
        webcraftConfig config = config(8087);

        webcraftRouteDispatcher dispatcher = new webcraftRouteDispatcher(config, playerName -> samplePlayer(playerName), 1_000L);
        webcraftResponse response = dispatcher.dispatch(new webcraftRequest("GET", "/api/server-info", Map.of(), Map.of()));

        assertTrue(response.body().contains("\"serverName\":\"Universium\""));
        assertTrue(response.body().contains("\"port\":8087"));
        assertTrue(response.body().contains("\"publicUrl\":\"https://example.invalid\""));
    }

    @Test
    void returnsPlayerNamesList() {
        webcraftConfig config = config(8087);

        webcraftRouteDispatcher dispatcher = new webcraftRouteDispatcher(config, new webcraftPlayerDataProvider() {
            @Override
            public PlayerInfoData getPlayerInfo(String playerName) {
                return samplePlayer("nightware6");
            }

            @Override
            public List<String> getPlayerNames() {
                return List.of("nightware6", "builder", "admin");
            }

            @Override
            public List<String> getOnlinePlayerNames() {
                return List.of("nightware6", "admin");
            }
        }, 1_000L);
        webcraftResponse response = dispatcher.dispatch(new webcraftRequest("GET", "/api/player-list", Map.of(), Map.of()));

        assertTrue(response.body().contains("\"success\":true"));
        assertTrue(response.body().contains("nightware6"));
    }

    @Test
    void returnsOnlinePlayerNamesList() {
        webcraftConfig config = config(8087);

        webcraftRouteDispatcher dispatcher = new webcraftRouteDispatcher(config, new webcraftPlayerDataProvider() {
            @Override
            public PlayerInfoData getPlayerInfo(String playerName) {
                return samplePlayer("nightware6");
            }

            @Override
            public List<String> getPlayerNames() {
                return List.of("nightware6", "builder", "admin");
            }

            @Override
            public List<String> getOnlinePlayerNames() {
                return List.of("nightware6", "admin");
            }
        }, 1_000L);
        webcraftResponse response = dispatcher.dispatch(new webcraftRequest("GET", "/api/player-list/online", Map.of(), Map.of()));

        assertTrue(response.body().contains("\"success\":true"));
        assertTrue(response.body().contains("admin"));
        assertTrue(response.body().contains("nightware6"));
    }

    @Test
    void returnsPlayerInfoByPlayerPath() {
        webcraftConfig config = config(8087);

        webcraftRouteDispatcher dispatcher = new webcraftRouteDispatcher(config, ignored -> samplePlayer("nightware6"), 1_000L);
        webcraftResponse response = dispatcher.dispatch(new webcraftRequest("GET", "/api/player-info/nightware6/", Map.of(), Map.of()));

        assertTrue(response.body().contains("\"success\":true"));
        assertTrue(response.body().contains("\"playerName\":\"nightware6\""));
        assertTrue(response.body().contains("\"playedTimeTicks\":1234"));
    }

}

