package com.universium.webcraft.core;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayerHistoryDataTest {
    @Test
    void keepsTheLatestNamePerPlayerAcrossWorldSaves() {
        var id = new UUID(0, 1);
        var otherId = new UUID(0, 2);
        var history = new PlayerHistoryData();
        history.recordJoin(id, "OldName", 100);
        history.recordJoin(otherId, "OtherPlayer", 200);
        history.recordJoin(id, "NewName", 300);
        history.recordJoin(id, "OutdatedName", 250);
        assertTrue(history.isDirty());

        var saved = history.save(new CompoundTag(), null);
        var loaded = PlayerHistoryData.FACTORY.deserializer().apply(saved, null);
        assertEquals(List.of(new PlayerHistoryData.Player(id, "NewName", 300),
                new PlayerHistoryData.Player(otherId, "OtherPlayer", 200)), loaded.players());
    }
}
