package com.universium.webcraft.exporter;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

final class InventoryAnimationExport {
    private static final int BATCH_SIZE = 24;
    private static final int RETRY_BATCH_SIZE = 4;
    private final Queue<Candidate> pending = new ArrayDeque<>();
    private final Queue<Candidate> retries = new ArrayDeque<>();
    private final List<Sample> active = new ArrayList<>();
    private final AtomicInteger exported = new AtomicInteger();
    private final StaticPreviewWriter previews;
    private final boolean warmUpSprites;
    private boolean retrying;

    InventoryAnimationExport(StaticPreviewWriter previews, boolean warmUpSprites) {
        this.previews = previews;
        this.warmUpSprites = warmUpSprites;
    }

    void add(String key, ItemStack stack, int ticks) {
        pending.add(new Candidate(key, stack, ticks));
    }

    boolean hasPending() {
        return !pending.isEmpty() || !active.isEmpty() || !retries.isEmpty();
    }

    int remaining() {
        return pending.size() + active.size() + retries.size();
    }

    int exported() {
        return exported.get();
    }

    void clear() {
        pending.clear();
        active.clear();
        retries.clear();
    }

    void tick(Function<ItemStack, PngPixels> render, ExportArchive archive) {
        if (pending.isEmpty() && active.isEmpty()) retrying = true;
        var ready = retrying ? retries : pending;
        int batchSize = retrying ? RETRY_BATCH_SIZE : BATCH_SIZE;
        while (active.size() < batchSize && !ready.isEmpty() && archive.hasRoom(active.size() + 1)) {
            active.add(new Sample(ready.remove(), warmUpSprites));
        }
        for (var iterator = active.iterator(); iterator.hasNext();) {
            var sample = iterator.next();
            try {
                var pixels = render.apply(sample.candidate.stack());
                // Sodium can advance a hidden sprite without uploading its pixels. Keep it visible
                // for a loop so every sprite can upload a fresh frame before we sample the atlas.
                if (sample.warmupTicks > 0) {
                    sample.warmupTicks--;
                    continue;
                }
                sample.frames.addTick(pixels);
            } catch (RuntimeException exception) {
                iterator.remove();
                retry(sample);
                continue;
            }
            if (--sample.remaining == 0) {
                iterator.remove();
                if (!sample.frames.isAnimated()) {
                    retry(sample);
                    continue;
                }
                // Each active sample reserved its slot before its first tick, so backpressure cannot interrupt a loop.
                archive.submit(writer -> {
                    if (previews.writeAnimation(writer, sample.candidate.key(), sample.frames)) {
                        exported.incrementAndGet();
                    }
                });
            }
        }
    }

    private void retry(Sample sample) {
        if (!retrying) retries.add(sample.candidate);
    }

    private record Candidate(String key, ItemStack stack, int ticks) {}

    private static final class Sample {
        private final Candidate candidate;
        private final AnimationFrames frames = new AnimationFrames();
        private int remaining;
        private int warmupTicks;

        private Sample(Candidate candidate, boolean warmUpSprites) {
            this.candidate = candidate;
            remaining = candidate.ticks();
            warmupTicks = warmUpSprites ? remaining : 0;
        }
    }
}
