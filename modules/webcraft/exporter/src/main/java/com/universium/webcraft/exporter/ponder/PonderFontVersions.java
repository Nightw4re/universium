package com.universium.webcraft.exporter.ponder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks uploads to known Minecraft font atlases, independently of the GPU comparison path. */
public final class PonderFontVersions {
    private static final AtomicLong NEXT = new AtomicLong();
    private static final Map<Integer, Long> VERSIONS = new ConcurrentHashMap<>();

    private PonderFontVersions() {}

    public static void uploaded(int texture) {
        VERSIONS.put(texture, NEXT.incrementAndGet());
    }

    public static void forget(int texture) {
        VERSIONS.remove(texture);
    }

    static Long version(int texture) {
        return VERSIONS.get(texture);
    }
}
