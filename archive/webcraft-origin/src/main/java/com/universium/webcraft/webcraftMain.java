package com.universium.webcraft;

import com.universium.webcraft.playerinfo.PlayerInfoData;
import com.universium.webcraft.playerinfo.PlayerInfoItem;
import com.universium.webcraft.runtime.webcraftService;
import com.universium.webcraft.runtime.webcraftPlayerDataProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
public final class webcraftMain {
    private webcraftMain() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = args.length > 0 ? Path.of(args[0]) : Path.of("static", "webcraft-server.toml");
        webcraftPlayerDataProvider playerDataProvider = webcraftMain::samplePlayer;
        webcraftService service = webcraftService.fromConfig(configPath, playerDataProvider);
        service.start();
        System.out.println("webcraft started using config: " + configPath.toAbsolutePath());
        Thread.currentThread().join();
    }

    private static PlayerInfoData samplePlayer(String playerName) {
        PlayerInfoItem item = new PlayerInfoItem("minecraft:air", 0, 0, "", Map.of(), Map.of(), "item", "", "center", "contain", "no-repeat", "");
        String resolvedName = playerName == null || playerName.isBlank() ? "Unknown" : playerName;
        return new PlayerInfoData(resolvedName, 1234L, List.of(item), item, item, List.of(item), List.of(item));
    }
}

