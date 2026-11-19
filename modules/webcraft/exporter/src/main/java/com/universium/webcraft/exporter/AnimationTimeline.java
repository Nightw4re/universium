package com.universium.webcraft.exporter;

final class AnimationTimeline {
    // Keep GPU readback, export time and the encoded payload bounded for modded packs.
    static final int MAX_TICKS = 160;

    private AnimationTimeline() {}

    static int combine(int first, int second) {
        if (first < 1 || second < 1 || first > MAX_TICKS || second > MAX_TICKS) {
            return 0;
        }
        int a = first;
        int b = second;
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        int period = first / a * second;
        return period <= MAX_TICKS ? period : 0;
    }
}
