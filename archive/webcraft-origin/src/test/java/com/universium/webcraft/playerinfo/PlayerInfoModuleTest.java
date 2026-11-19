package com.universium.webcraft.playerinfo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.universium.webcraft.TestFixtures.item;

class PlayerInfoModuleTest {
    @Test
    void rendersExpectedJsonShape() {
        PlayerInfoItem helmet = item("minecraft:diamond_helmet", 1, 400, "Diamond Helmet", Map.of("enchanted", true));
        PlayerInfoItem chest = item("minecraft:diamond_chestplate", 1, 500, "Diamond Chestplate", Map.of());
        PlayerInfoItem mainHand = item("minecraft:diamond_sword", 1, 1561, "Diamond Sword", Map.of("nbtTag", "value"));
        PlayerInfoItem offHand = item("minecraft:shield", 1, 336, "Shield", Map.of());

        PlayerInfoData data = new PlayerInfoData(
                "nightware6",
                123456L,
                List.of(helmet, chest),
                mainHand,
                offHand,
                List.of(mainHand, offHand),
                List.of(helmet)
        );

        String json = new PlayerInfoModule().render(data).toJson();

        assertTrue(json.contains("\"playerName\":\"nightware6\""));
        assertTrue(json.contains("\"playedTimeTicks\":123456"));
        assertTrue(json.contains("\"id\":\"minecraft:diamond_sword\""));
        assertTrue(json.contains("\"nbt\":{\"nbtTag\":\"value\"}"));
        assertTrue(json.contains("\"inventory\":["));
        assertTrue(json.contains("\"enderChest\":["));
    }
}

