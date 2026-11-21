package com.universium.webcraft.core;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldAgeDataTest {
    @BeforeAll
    static void detectMinecraftVersion() {
        SharedConstants.tryDetectVersion();
    }

    @Test
    void countsOnlyWholeRealMinutesSinceWorldStart() {
        var start = Instant.parse("2025-03-29T12:30:00Z");
        assertEquals(0, WorldAgeData.completedMinutes(start, start.minusSeconds(61)));
        assertEquals(0, WorldAgeData.completedMinutes(start, start.plusSeconds(60).minusNanos(1)));
        assertEquals(1, WorldAgeData.completedMinutes(start, start.plusSeconds(60)));
        assertEquals(5, WorldAgeData.completedMinutes(start, start.plusSeconds(359)));
        assertEquals(1_439, WorldAgeData.completedMinutes(start, start.plusSeconds(86_399)));
        assertEquals(1_440, WorldAgeData.completedMinutes(start, start.plusSeconds(86_400)));
    }

    @Test
    void leavesMissingOrInvalidStartDatesUnknown() {
        assertTrue(WorldAgeData.parse("").isEmpty());
        assertTrue(WorldAgeData.parse("2025-04-07").isEmpty());
        assertEquals(Instant.parse("2025-04-07T09:00:00Z"),
                WorldAgeData.parse("2025-04-07T09:00:00Z").orElseThrow());
    }

    @Test
    void backfillsExistingWorldAndReadsItBackWithoutAVanillaSave(@TempDir Path world) throws Exception {
        var verified = Instant.parse("2025-04-07T09:00:00Z");
        var later = verified.plus(400, ChronoUnit.DAYS).plusSeconds(359);
        assertNull(WorldAgeData.remember(world, true, later, null));
        assertEquals(verified, WorldAgeData.remember(world, true, later, verified));
        var remembered = WorldAgeData.remember(world, true, later, null);
        assertEquals(verified, remembered);
        assertEquals(576_005, WorldAgeData.completedMinutes(remembered, later));
    }

    @Test
    void replacementUsesItsOwnLaunchTimeEvenWithAnOldConfig(@TempDir Path replacement) throws Exception {
        Files.createFile(replacement.resolve("level.dat"));
        Files.createDirectories(replacement.resolve("serverconfig"));
        var verified = Instant.parse("2025-04-07T09:00:00Z");
        var observed = verified.plus(400, ChronoUnit.DAYS);
        assertEquals(observed, WorldAgeData.remember(replacement, false, observed, verified));
        Files.createFile(replacement.resolve("level.dat_old"));
        assertEquals(observed, WorldAgeData.remember(replacement, false, observed.plusSeconds(60), verified));
        assertEquals(observed, WorldAgeData.remember(replacement, false, observed.plusSeconds(60), null));
        assertEquals(observed.minusSeconds(5),
                WorldAgeData.remember(replacement, true, observed.plusSeconds(60), observed.minusSeconds(5)));
    }

    @Test
    void recoversAnUninitializedWorldWithAPriorLevelFile(@TempDir Path world) throws Exception {
        Files.createFile(world.resolve("level.dat_old"));
        var verified = Instant.parse("2026-11-21T14:04:33Z");
        var observed = verified.plusSeconds(34 * 60);
        assertNull(WorldAgeData.remember(world, false, observed, null));
        assertEquals(verified, WorldAgeData.remember(world, false, observed, verified));
        assertEquals(34, WorldAgeData.completedMinutes(WorldAgeData.remember(world, false, observed, null), observed));
    }

    @Test
    void treatsPersistedChunksAsEvidenceEvenWithoutABackupLevelFile(@TempDir Path world) throws Exception {
        var region = Files.createDirectories(world.resolve("region"));
        Files.createFile(region.resolve("r.-1.0.mca"));
        var verified = Instant.parse("2026-11-21T14:04:33Z");
        assertEquals(verified, WorldAgeData.remember(world, false, verified.plusSeconds(3600), verified));
    }

    @Test
    void replacesAnIncorrectLegacyStartWithTheVerifiedWorldOverride(@TempDir Path world) throws Exception {
        var verified = Instant.parse("2026-11-21T14:04:33Z");
        var incorrect = Instant.parse("2026-11-21T14:36:24.407037100Z");
        var observed = incorrect.plusSeconds(120);
        var folder = Files.createDirectories(world.resolve("data"));
        var root = new CompoundTag();
        var data = new CompoundTag();
        data.putString("startedAt", incorrect.toString());
        root.put("data", data);
        root.putInt("DataVersion", 3955);
        NbtIo.writeCompressed(root, folder.resolve(WorldAgeData.FILE_NAME));

        assertEquals(incorrect, WorldAgeData.remember(world, true, observed, null));
        assertEquals(verified, WorldAgeData.remember(world, true, observed, verified));
        assertEquals(verified, WorldAgeData.remember(world, true, observed, null));
        assertEquals(33, WorldAgeData.completedMinutes(verified, observed));
    }
}
