package com.universium.webcraft.exporter;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TooltipKeyProbeTest {
    @Test
    void capturesModifierAndConfiguredKeyboardShortcutDiscoveredInItsBranch() {
        var tooltip = TooltipKeyProbe.capture(() -> {
            if (TooltipKeyProbe.keyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || TooltipKeyProbe.keyDown(GLFW.GLFW_KEY_RIGHT_SHIFT)) {
                return text(TooltipKeyProbe.keyDown(GLFW.GLFW_KEY_M) ? "Description" : "Details");
            }
            return text("Hint");
        });

        assertEquals("Hint", tooltip.baseline().get("lines").getAsString());
        assertEquals(List.of(List.of(GLFW.GLFW_KEY_LEFT_SHIFT), List.of(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_M)),
                tooltip.alternatives().stream().map(TooltipKeyProbe.Alternative::keys).toList());
        assertEquals(List.of("Details", "Description"),
                tooltip.alternatives().stream().map(alternative -> alternative.state().get("lines").getAsString()).toList());
        assertNull(TooltipKeyProbe.keyDown(GLFW.GLFW_KEY_LEFT_SHIFT));
    }

    @Test
    void retainsTheBaseWhenOptionalBranchesFailOrDoNotChangeText() {
        var tooltip = TooltipKeyProbe.capture(() -> {
            if (TooltipKeyProbe.keyDown(GLFW.GLFW_KEY_M)) throw new IllegalStateException("Requires an inventory screen");
            TooltipKeyProbe.keyDown(GLFW.GLFW_KEY_LEFT_ALT);
            return text("Base");
        });
        assertEquals("Base", tooltip.baseline().get("lines").getAsString());
        assertTrue(tooltip.alternatives().isEmpty());
        assertNull(TooltipKeyProbe.keyDown(GLFW.GLFW_KEY_M));

        assertThrows(IllegalStateException.class, () -> TooltipKeyProbe.capture(() -> {
            TooltipKeyProbe.keyDown(GLFW.GLFW_KEY_M);
            throw new IllegalStateException("Base tooltip failed");
        }));
        assertNull(TooltipKeyProbe.keyDown(GLFW.GLFW_KEY_M));
    }

    @Test
    void doesNotRecomputeTooltipsThatNeverReadKeyboardInput() {
        var draws = new AtomicInteger();
        TooltipKeyProbe.capture(() -> text("Invocation " + draws.incrementAndGet()));
        assertEquals(1, draws.get());
    }

    private static JsonObject text(String value) {
        var data = new JsonObject();
        data.addProperty("lines", value);
        return data;
    }
}
