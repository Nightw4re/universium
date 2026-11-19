package com.universium.webcraft.exporter;

import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

final class InventoryAnimationExport {
    private static final int BATCH_SIZE = 4;
    private final Queue<Candidate> pending = new ArrayDeque<>();
    private final List<Sample> active = new ArrayList<>();
    private int exported;

    void add(String key, ItemStack stack, int ticks) {
        pending.add(new Candidate(key, stack, ticks));
    }

    boolean hasPending() {
        return !pending.isEmpty() || !active.isEmpty();
    }

    int remaining() {
        return pending.size() + active.size();
    }

    int exported() {
        return exported;
    }

    void clear() {
        pending.clear();
        active.clear();
    }

    void tick(InventoryIconRenderer renderer, ArchiveWriter writer) throws IOException {
        while (active.size() < BATCH_SIZE && !pending.isEmpty()) {
            active.add(new Sample(pending.remove()));
        }
        for (var iterator = active.iterator(); iterator.hasNext();) {
            var sample = iterator.next();
            try {
                sample.png.addTick(renderer.render(sample.candidate.stack(), true));
            } catch (RuntimeException exception) {
                iterator.remove();
                continue;
            }
            if (--sample.remaining == 0) {
                iterator.remove();
                byte[] animation = sample.png.encode();
                if (animation != null) {
                    writer.write("animations/" + sample.candidate.key() + ".png", animation);
                    exported++;
                }
            }
        }
    }

    @FunctionalInterface
    interface ArchiveWriter {
        void write(String path, byte[] png) throws IOException;
    }

    private record Candidate(String key, ItemStack stack, int ticks) {}

    private static final class Sample {
        private final Candidate candidate;
        private final AnimatedPng png = new AnimatedPng();
        private int remaining;

        private Sample(Candidate candidate) {
            this.candidate = candidate;
            remaining = candidate.ticks();
        }
    }
}
