package com.universium.webcraft.exporter.ponder;

import com.mojang.logging.LogUtils;
import com.universium.webcraft.exporter.CudaTextureComparison;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;

final class PonderTextureCapture implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int HISTORY_SLOTS = 8;

    private final History history = new History(HISTORY_SLOTS);
    private boolean tryCuda;
    private boolean closed;
    private CudaTextureComparison cuda;
    private long seeds;
    private long comparisons;
    private long reused;
    private long avoidedBytes;
    private long readbacks;
    private long cudaNanos;
    private long readbackNanos;

    PonderTextureCapture(boolean experimentalCuda) {
        tryCuda = experimentalCuda;
    }

    String image(int id, int width, int height, Supplier<String> capture) {
        Snapshot snapshot = null;
        if (tryCuda && CudaTextureComparison.supports(width, height) && tightlyPacked()) {
            long start = System.nanoTime();
            try {
                if (cuda == null) {
                    cuda = CudaTextureComparison.open(HISTORY_SLOTS);
                    LOGGER.info("WebCraft is using experimental CUDA Ponder texture comparison");
                }
                snapshot = history.snapshot(id, width, height);
                boolean seed = snapshot.image == null;
                boolean unchanged = cuda.unchanged(snapshot.slot, width, height, seed);
                if (seed) {
                    seeds++;
                } else {
                    comparisons++;
                    if (unchanged) {
                        reused++;
                        avoidedBytes += (long) width * height * Integer.BYTES;
                        return snapshot.image;
                    }
                }
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.info("WebCraft is using the standard Ponder texture readback path: {}", exception.toString());
                tryCuda = false;
                releaseCuda();
                snapshot = null;
            } finally {
                cudaNanos += System.nanoTime() - start;
            }
        } else if (tryCuda) {
            history.invalidate(id);
        }

        long start = System.nanoTime();
        readbacks++;
        try {
            String image = capture.get();
            if (snapshot != null) snapshot.image = image;
            return image;
        } finally {
            readbackNanos += System.nanoTime() - start;
        }
    }

    private static boolean tightlyPacked() {
        return glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING) == 0
                && glGetInteger(GL_PACK_ROW_LENGTH) == 0
                && glGetInteger(GL_PACK_SKIP_ROWS) == 0
                && glGetInteger(GL_PACK_SKIP_PIXELS) == 0;
    }

    private void releaseCuda() {
        if (cuda != null) {
            cuda.close();
            cuda = null;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        releaseCuda();
        if (reused == 0 && readbacks == 0) return;
        LOGGER.info("WebCraft Ponder text textures: {} CUDA seeds, {} comparisons ({} changed, {} reused), "
                        + "{} bytes of full CPU readback avoided, {} standard readbacks, "
                        + "{} ms in CUDA path, {} ms in standard readback and PNG storage",
                seeds, comparisons, comparisons - reused, reused, avoidedBytes, readbacks,
                NANOSECONDS.toMillis(cudaNanos), NANOSECONDS.toMillis(readbackNanos));
    }

    static final class History {
        private final int slots;
        private final LinkedHashMap<Integer, Snapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);

        History(int slots) {
            this.slots = slots;
        }

        Snapshot snapshot(int id, int width, int height) {
            var previous = snapshots.get(id);
            if (previous != null && previous.width == width && previous.height == height) return previous;
            int slot = snapshots.size();
            if (previous != null) {
                slot = previous.slot;
            } else if (slot == slots) {
                slot = snapshots.pollFirstEntry().getValue().slot;
            }
            var snapshot = new Snapshot(slot, width, height);
            snapshots.put(id, snapshot);
            return snapshot;
        }

        void invalidate(int id) {
            var snapshot = snapshots.get(id);
            if (snapshot != null) snapshot.image = null;
        }
    }

    static final class Snapshot {
        final int slot;
        private final int width;
        private final int height;
        String image;

        private Snapshot(int slot, int width, int height) {
            this.slot = slot;
            this.width = width;
            this.height = height;
        }
    }
}
