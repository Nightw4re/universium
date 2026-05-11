package com.universium.webcraft.runtime;

import com.universium.webcraft.playerinfo.PlayerInfoData;
import com.universium.webcraft.playerinfo.PlayerInfoItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.universium.webcraft.TestFixtures.item;

class webcraftBootstrapTest {
    @Test
    void buildsServerFromTomlRouteBindings() throws Exception {
        Path tempFile = Files.createTempFile("webcraft", ".toml");
        Files.writeString(tempFile, """
                [server]
                enabled = true
                auto_port = true
                host = "127.0.0.1"
                port = 0
                public_url = "http://127.0.0.1"
                minecraft_version = "1.21.1"
                neoforge_build = 224
                server_name = "Universium"

                [frontend]
                mode = "built_in"
                theme = "default"

                [logging]
                access_enabled = false
                error_enabled = false
                access_file = "logs/webcraft-access.log"
                error_file = "logs/webcraft-error.log"

                [route."GET /server-info"]
                type = "java"
                ref = "webcraft.serverInfo"

                [route."GET /player-list"]
                type = "java"
                ref = "webcraft.playerNames"

                [route."GET /player-list/online"]
                type = "java"
                ref = "webcraft.playerOnlineNames"

                [route."GET /player-info/{playerName}"]
                type = "java"
                ref = "webcraft.playerInfo"
                """);

        webcraftHttpServer server = new webcraftBootstrap(tempFile, ignored -> samplePlayer(), 1_000L).buildServer();
        assertTrue(server != null);
    }

    private static PlayerInfoData samplePlayer() {
        PlayerInfoItem item = item("minecraft:stone", 1, 0, "Stone", Map.of());
        return new PlayerInfoData("nightware6", 1234L, List.of(item), item, item, List.of(item), List.of(item));
    }
}

