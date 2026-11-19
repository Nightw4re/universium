package com.universium.webcraft;

import com.universium.webcraft.config.webcraftConfig;
import com.universium.webcraft.playerinfo.PlayerInfoData;
import com.universium.webcraft.playerinfo.PlayerInfoItem;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class TestFixtures {
    private TestFixtures() {
    }

    public static webcraftConfig config(int port) {
        return new webcraftConfig(
                new webcraftConfig.Server(
                        true,
                        false,
                        false,
                        false,
                        "127.0.0.1",
                        port,
                        "https://example.invalid",
                        "1.21.1",
                        224,
                        "Universium",
                        "Universium Dev Server",
                        "127.0.0.1:25565",
                        "",
                        "",
                        "JKS",
                        "../webroot",
                        "index.html",
                        true,
                        true,
                        true,
                        32,
                        10,
                        604800,
                        "../resourcepacks",
                        "resourcepacks.txt",
                        List.of()
                ),
                new webcraftConfig.Frontend("built_in", "universum"),
                new webcraftConfig.Logging(false, false, "logs/webcraft-access.log", "logs/webcraft-error.log")
        );
    }

    public static webcraftConfig config(Path webrootDir, Path resourceRoot, Path listFile, int port) {
        return new webcraftConfig(
                new webcraftConfig.Server(
                        true,
                        true,
                        false,
                        false,
                        "127.0.0.1",
                        port,
                        "https://example.invalid",
                        "1.21.1",
                        224,
                        "Universium",
                        "Universium Dev Server",
                        "127.0.0.1:25565",
                        "",
                        "",
                        "JKS",
                        webrootDir.toString(),
                        "index.html",
                        true,
                        true,
                        true,
                        32,
                        10,
                        604800,
                        resourceRoot.toString(),
                        listFile.toString(),
                        List.of()
                ),
                new webcraftConfig.Frontend("built_in", "universum"),
                new webcraftConfig.Logging(false, false, "logs/webcraft-access.log", "logs/webcraft-error.log")
        );
    }

    public static PlayerInfoItem item(String id) {
        return new PlayerInfoItem(id, 1, 0, prettify(id), Map.of(), Map.of(), "item", "", "center", "contain", "no-repeat", "");
    }

    public static PlayerInfoItem item(String id, int count, int durability, String name, Map<String, Object> nbt) {
        return new PlayerInfoItem(id, count, durability, name, nbt, Map.of(), "item", "", "center", "contain", "no-repeat", "");
    }

    public static PlayerInfoData samplePlayer(String name) {
        PlayerInfoItem item = item("minecraft:stone");
        return new PlayerInfoData(name, 1234L, List.of(item), item, item, List.of(item), List.of(item));
    }

    private static String prettify(String id) {
        String value = id == null ? "" : id;
        int colon = value.indexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            value = value.substring(colon + 1);
        }
        return value.replace('_', ' ');
    }
}
