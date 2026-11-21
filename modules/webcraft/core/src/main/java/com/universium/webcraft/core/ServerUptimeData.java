package com.universium.webcraft.core;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class ServerUptimeData {
    static final String FILE_NAME = "webcraft_uptime.dat";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CHECKPOINT_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private final Path file;
    private final long previousMillis;
    private long totalMillis;
    private long savedMillis;
    private long lastAttemptMillis;

    static ServerUptimeData open(Path world) throws IOException {
        var file = world.resolve("data").resolve(FILE_NAME);
        var stored = WorldMetricFiles.read(file);
        if (stored.isPresent() && !stored.get().contains("onlineMillis", Tag.TAG_LONG)) {
            throw new IOException("Missing or invalid onlineMillis in " + file);
        }
        return new ServerUptimeData(file, stored.map(data -> data.getLong("onlineMillis")).orElse(0L));
    }

    private ServerUptimeData(Path file, long previousMillis) {
        this.file = file;
        this.previousMillis = Math.max(0, previousMillis);
        totalMillis = this.previousMillis;
        savedMillis = this.previousMillis;
    }

    long checkpoint(long sessionMillis) {
        updateTotal(sessionMillis);
        if (sessionMillis - lastAttemptMillis >= CHECKPOINT_MILLIS) {
            lastAttemptMillis = sessionMillis;
            persist();
        }
        return totalMillis;
    }

    void stop(long sessionMillis) {
        updateTotal(sessionMillis);
        persist();
    }

    private void updateTotal(long sessionMillis) {
        long total = previousMillis + Math.min(Math.max(0, sessionMillis), Long.MAX_VALUE - previousMillis);
        totalMillis = Math.max(total, totalMillis);
    }

    private void persist() {
        if (totalMillis == savedMillis) return;

        var data = new CompoundTag();
        data.putLong("onlineMillis", totalMillis);
        try {
            WorldMetricFiles.write(file, data);
            savedMillis = totalMillis;
        } catch (IOException exception) {
            LOGGER.warn("Could not save WebCraft total uptime to {}", file, exception);
        }
    }
}
