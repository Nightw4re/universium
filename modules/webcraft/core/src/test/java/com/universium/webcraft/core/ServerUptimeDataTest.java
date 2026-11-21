package com.universium.webcraft.core;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ServerUptimeDataTest {
    @BeforeAll
    static void detectMinecraftVersion() {
        SharedConstants.tryDetectVersion();
    }

    @Test
    void recoversOnlyTheLastCompletedCheckpointAfterAnAbruptExit(@TempDir Path world) throws IOException {
        var uptime = ServerUptimeData.open(world);
        assertEquals(9_999, uptime.checkpoint(9_999));
        assertFalse(Files.exists(world.resolve("data").resolve(ServerUptimeData.FILE_NAME)));
        assertEquals(10_000, uptime.checkpoint(10_000));
        assertEquals(18_375, uptime.checkpoint(18_375));
        assertEquals(18_375, uptime.checkpoint(17_000));

        var restarted = ServerUptimeData.open(world);
        assertEquals(10_000, restarted.checkpoint(0));
        assertEquals(21_000, restarted.checkpoint(11_000));
        restarted.stop(12_345);
        assertEquals(22_345, ServerUptimeData.open(world).checkpoint(0));
    }

    @Test
    void carriesLegacyTotalAcrossSessionsAndExcludesTimeBetweenThem(@TempDir Path world) throws IOException {
        long twoDays = TimeUnit.DAYS.toMillis(2);
        writeLegacy(world, twoDays);
        var uptime = ServerUptimeData.open(world);
        assertEquals(twoDays, uptime.checkpoint(0));
        uptime.stop(twoDays);
        assertEquals(TimeUnit.DAYS.toMillis(4), ServerUptimeData.open(world).checkpoint(0));
    }

    @Test
    void normalizesInvalidDurationAndPreventsOverflow(@TempDir Path world) throws IOException {
        writeLegacy(world, -1);
        assertEquals(0, ServerUptimeData.open(world).checkpoint(Long.MIN_VALUE));
        writeLegacy(world, Long.MAX_VALUE - 1);
        assertEquals(Long.MAX_VALUE, ServerUptimeData.open(world).checkpoint(20));
    }

    @Test
    void leavesUnreadableExistingDataInPlace(@TempDir Path world) throws IOException {
        var data = Files.createDirectories(world.resolve("data"));
        var file = data.resolve(ServerUptimeData.FILE_NAME);
        Files.writeString(file, "existing unreadable data");
        assertThrows(IOException.class, () -> ServerUptimeData.open(world));
        assertEquals("existing unreadable data", Files.readString(file));
    }

    @Test
    void rejectsATruncatedNbtPayload(@TempDir Path world) throws IOException {
        var data = Files.createDirectories(world.resolve("data"));
        var file = data.resolve(ServerUptimeData.FILE_NAME);
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeByte(10); // Compound root.
            out.writeUTF("");
            out.writeByte(4); // Long field with a missing value.
            out.writeUTF("truncated");
        }
        assertThrows(IOException.class, () -> ServerUptimeData.open(world));
    }

    private static void writeLegacy(Path world, long millis) throws IOException {
        var folder = Files.createDirectories(world.resolve("data"));
        var root = new CompoundTag();
        var data = new CompoundTag();
        data.putLong("onlineMillis", millis);
        root.put("data", data);
        root.putInt("DataVersion", 3955);
        NbtIo.writeCompressed(root, folder.resolve(ServerUptimeData.FILE_NAME));
    }
}
