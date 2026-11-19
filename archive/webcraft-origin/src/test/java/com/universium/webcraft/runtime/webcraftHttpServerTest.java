package com.universium.webcraft.runtime;

import com.universium.webcraft.config.webcraftConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.universium.webcraft.TestFixtures.config;
import static com.universium.webcraft.TestFixtures.samplePlayer;

class webcraftHttpServerTest {
    private webcraftHttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void servesServerInfoAndPlayerInfoOverHttp() throws Exception {
        int port = freePort();
        webcraftConfig config = config(port);

        server = new webcraftHttpServer(config, new webcraftRouteDispatcher(config, new webcraftPlayerDataProvider() {
            @Override
            public com.universium.webcraft.playerinfo.PlayerInfoData getPlayerInfo(String playerName) {
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
        }, System.currentTimeMillis()));
        server.start();

        String serverInfo = request("http://127.0.0.1:" + port + "/api/server-info");
        String playerNames = request("http://127.0.0.1:" + port + "/api/player-list");
        String onlinePlayerNames = request("http://127.0.0.1:" + port + "/api/player-list/online");
        String playerInfo = request("http://127.0.0.1:" + port + "/api/player-info/nightware6/");

        assertTrue(serverInfo.contains("\"serverName\":\"Universium\""));
        assertTrue(serverInfo.contains("\"port\":" + port));
        assertTrue(playerNames.contains("\"success\":true"));
        assertTrue(playerNames.contains("nightware6"));
        assertTrue(onlinePlayerNames.contains("\"success\":true"));
        assertTrue(playerInfo.contains("\"success\":true"));
        assertTrue(playerInfo.contains("\"playerName\":\"nightware6\""));
    }

    @Test
    void servesPlayerNamesOverHttp() throws Exception {
        int port = freePort();
        webcraftConfig config = config(port);

        server = new webcraftHttpServer(config, new webcraftRouteDispatcher(config, new webcraftPlayerDataProvider() {
            @Override
            public com.universium.webcraft.playerinfo.PlayerInfoData getPlayerInfo(String playerName) {
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
        }, System.currentTimeMillis()));
        server.start();

        String playerNames = request("http://127.0.0.1:" + port + "/api/player-list");
        String onlinePlayerNames = request("http://127.0.0.1:" + port + "/api/player-list/online");

        assertTrue(playerNames.contains("\"success\":true"));
        assertTrue(onlinePlayerNames.contains("\"success\":true"));
    }

    private static String request(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.connect();
        try (InputStream inputStream = connection.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
}

