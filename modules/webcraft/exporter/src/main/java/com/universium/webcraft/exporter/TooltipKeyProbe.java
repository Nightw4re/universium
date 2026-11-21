package com.universium.webcraft.exporter;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Overrides key reads only while the exporter takes a tooltip snapshot. */
public final class TooltipKeyProbe {
    private static final ThreadLocal<TooltipKeyProbe> ACTIVE = new ThreadLocal<>();
    private static final int MAX_PROBES = 24;
    private static final int MAX_ALTERNATIVES = 8;
    private static final int MAX_SHORTCUT_KEYS = 3;
    private static final Comparator<Integer> SHORTCUT_ORDER = Comparator
            .<Integer, Boolean>comparing(key -> modifierGroup(key) == GLFW.GLFW_KEY_UNKNOWN)
            .thenComparingInt(Integer::intValue);

    private final List<Integer> pressed;
    private final Set<Integer> observed = new LinkedHashSet<>();

    private TooltipKeyProbe(List<Integer> pressed) {
        this.pressed = pressed;
    }

    /** Returns the simulated state of a polled keyboard key, or null outside a tooltip snapshot. */
    public static Boolean keyDown(int key) {
        TooltipKeyProbe probe = ACTIVE.get();
        return probe == null ? null : probe.readKey(key);
    }

    /** Returns the simulated state of a key mapping without updating the mapping or sending an input event. */
    public static Boolean mappingDown(KeyMapping mapping) {
        TooltipKeyProbe probe = ACTIVE.get();
        if (probe == null) return null;
        var key = mapping.getKey();
        return key.getType() == InputConstants.Type.KEYSYM && probe.readKey(key.getValue())
                && mapping.isConflictContextAndModifierActive();
    }

    private boolean readKey(int key) {
        if (key < GLFW.GLFW_KEY_SPACE || key > GLFW.GLFW_KEY_LAST) return false;
        observed.add(key);
        return pressed.contains(key);
    }

    static Capture capture(Supplier<JsonObject> tooltip) {
        var baseline = new TooltipKeyProbe(List.of());
        JsonObject base = baseline.snapshot(tooltip);
        var alternatives = new ArrayList<Alternative>();
        var pending = new ArrayDeque<List<Integer>>();
        var scheduled = new HashSet<List<Integer>>();
        baseline.queueMoreKeys(pending, scheduled);
        while (!pending.isEmpty() && alternatives.size() < MAX_ALTERNATIVES) {
            var probe = new TooltipKeyProbe(pending.removeFirst());
            JsonObject state;
            try {
                state = probe.snapshot(tooltip);
            } catch (RuntimeException ignored) {
                // An optional tooltip branch can require client state unavailable during export.
                continue;
            }
            if (!base.get("lines").equals(state.get("lines"))
                    && alternatives.stream().noneMatch(known -> probe.repeats(known, state))) {
                alternatives.add(new Alternative(probe.pressed, state));
            }
            probe.queueMoreKeys(pending, scheduled);
        }
        return new Capture(base, List.copyOf(alternatives));
    }

    private JsonObject snapshot(Supplier<JsonObject> tooltip) {
        TooltipKeyProbe previous = ACTIVE.get();
        ACTIVE.set(this);
        try {
            return tooltip.get();
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }

    private void queueMoreKeys(ArrayDeque<List<Integer>> pending, Set<List<Integer>> scheduled) {
        if (pressed.size() == MAX_SHORTCUT_KEYS) return;
        for (int key : observed) {
            if (scheduled.size() == MAX_PROBES) return;
            if (pressed.contains(key) || pressed.stream().anyMatch(candidate -> sameModifier(key, candidate))) continue;
            var keys = new ArrayList<>(pressed);
            keys.add(key);
            keys.sort(SHORTCUT_ORDER);
            var combination = List.copyOf(keys);
            if (scheduled.add(combination)) pending.addLast(combination);
        }
    }

    private boolean repeats(Alternative known, JsonObject state) {
        return known.state().get("lines").equals(state.get("lines")) && known.keys().stream().allMatch(key ->
                pressed.contains(key) || pressed.stream().anyMatch(candidate -> sameModifier(key, candidate)));
    }

    private static boolean sameModifier(int first, int second) {
        int group = modifierGroup(first);
        return group != GLFW.GLFW_KEY_UNKNOWN && group == modifierGroup(second);
    }

    private static int modifierGroup(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> GLFW.GLFW_KEY_LEFT_ALT;
            case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> GLFW.GLFW_KEY_LEFT_SUPER;
            default -> GLFW.GLFW_KEY_UNKNOWN;
        };
    }

    record Capture(JsonObject baseline, List<Alternative> alternatives) {}

    record Alternative(List<Integer> keys, JsonObject state) {}
}
