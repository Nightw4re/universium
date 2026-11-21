package com.universium.webcraft.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

final class WorldAgeData {
    static final String FILE_NAME = "webcraft_world_age.dat";

    private WorldAgeData() {}

    static Instant remember(Path world, boolean initialized, Instant observed, Instant verifiedStart) throws IOException {
        var file = world.resolve("data").resolve(FILE_NAME);
        var saved = WorldMetricFiles.read(file);
        if (saved.isPresent()) {
            var data = saved.get();
            var recorded = parse(data.getString("startedAt"))
                    .orElseThrow(() -> new IOException("Missing or invalid world start in " + file));
            var ignored = parse(data.getString("ignoredInitialOverride")).orElse(null);
            if (verifiedStart != null && !verifiedStart.equals(recorded) && !verifiedStart.equals(ignored)) {
                data.putString("startedAt", verifiedStart.toString());
                WorldMetricFiles.write(file, data);
                return verifiedStart;
            }
            return recorded;
        }

        boolean existingWorld = initialized || hasPriorWorldData(world);
        var start = existingWorld ? verifiedStart : observed;
        if (start != null) {
            var data = new CompoundTag();
            data.putString("startedAt", start.toString());
            if (!existingWorld && verifiedStart != null) {
                // Keep ignoring an inherited timestamp on later launches of the replacement.
                data.putString("ignoredInitialOverride", verifiedStart.toString());
            }
            WorldMetricFiles.write(file, data);
        }
        return start;
    }

    private static boolean hasPriorWorldData(Path world) throws IOException {
        // The pre-start save rotates any previous level.dat into level.dat_old.
        return Files.isRegularFile(world.resolve(LevelResource.OLD_LEVEL_DATA_FILE.getId()))
                || containsFile(world.resolve("region"), "r.*.*.mca")
                || containsFile(world.resolve(LevelResource.PLAYER_DATA_DIR.getId()), "*.dat");
    }

    private static boolean containsFile(Path directory, String glob) throws IOException {
        try (var files = Files.newDirectoryStream(directory, glob)) {
            for (var file : files) {
                if (Files.isRegularFile(file)) return true;
            }
            return false;
        } catch (NoSuchFileException exception) {
            return false;
        } catch (DirectoryIteratorException exception) {
            throw exception.getCause();
        }
    }

    static Optional<Instant> parse(String value) {
        if (value.isBlank()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(value.strip()));
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }

    static long completedMinutes(Instant startedAt, Instant now) {
        return Math.max(0, Duration.between(startedAt, now).toMinutes());
    }
}
