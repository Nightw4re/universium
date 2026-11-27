package com.universium.webcraft.exporter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class AnimationFrames {
    private final List<Frame> frames = new ArrayList<>();

    void addTick(PngPixels pixels) {
        var last = frames.isEmpty() ? null : frames.getLast();
        if (last != null && Arrays.equals(last.pixels().rgba(), pixels.rgba())) {
            repeatTick();
        } else {
            frames.add(new Frame(pixels, 1));
        }
    }

    void repeatTick() {
        var last = frames.getLast();
        frames.set(frames.size() - 1, new Frame(last.pixels(), last.ticks() + 1));
    }

    List<Frame> frames() {
        return List.copyOf(frames);
    }

    boolean isAnimated() {
        return frames.size() > 1;
    }

    record Frame(PngPixels pixels, int ticks) {}
}
