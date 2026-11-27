package com.universium.webcraft.exporter.ponder;

import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryStateTest {
    @Test
    void reusesOnlyWithinTheSameDelayWithUnchangedGeometryState() {
        var delay = new Object();
        var element = new Object();
        var camera = new Matrix4f();
        var section = new GeometryState.Section(element, new Matrix4f(), 1, null);
        var first = new GeometryState(camera, List.of(section));
        var unchanged = new GeometryState(new Matrix4f(camera), List.of(section));

        assertTrue(unchanged.canReuse(first, delay, delay));
        assertFalse(unchanged.canReuse(first, delay, new Object()));
        assertFalse(unchanged.canReuse(first, null, null));
        assertFalse(unchanged.canReuse(null, delay, delay));
        assertFalse(new GeometryState(new Matrix4f().rotateY(1), List.of(section)).canReuse(first, delay, delay));
        assertFalse(new GeometryState(camera, List.of()).canReuse(first, delay, delay));

        var moving = new GeometryState.Section(element, new Matrix4f().translate(1, 0, 0), 1, null);
        var fading = new GeometryState.Section(element, new Matrix4f(), 0.5f, null);
        assertFalse(new GeometryState(camera, List.of(moving)).canReuse(first, delay, delay));
        assertFalse(new GeometryState(camera, List.of(fading)).canReuse(first, delay, delay));
    }

    @Test
    void reposesIndependentCompactSectionsWithinOneDelay() {
        var delay = new Object();
        var first = new GeometryState.Section(new Object(), new Matrix4f(), 1, null);
        var second = new GeometryState.Section(new Object(), new Matrix4f(), 1, null);
        var before = new GeometryState(new Matrix4f(), List.of(first, second));
        var moved = new GeometryState.Section(first.element(), new Matrix4f().rotateY(1), 1, null);
        var after = new GeometryState(new Matrix4f().translate(1, 2, 3), List.of(moved, second));
        assertTrue(after.canRepose(before, delay, delay));
        assertFalse(after.canRepose(before, delay, new Object()));
        assertFalse(new GeometryState(before.camera(), List.of(second, moved)).canRepose(before, delay, delay));
        var fading = new GeometryState.Section(first.element(), moved.transform(), 0.5f, null);
        assertFalse(new GeometryState(before.camera(), List.of(fading, second)).canRepose(before, delay, delay));

        var groups = JsonParser.parseString("[{\"blocks\":[[1,2,3,4]]},{\"blocks\":[[2,3,4,5]]}]").getAsJsonArray();
        var transformed = after.repose(groups);
        for (int i = 0; i < groups.size(); i++) {
            assertSame(groups.get(i).getAsJsonObject().get("blocks"), transformed.get(i).getAsJsonObject().get("blocks"));
            assertEquals(PonderDrawCapture.matrix(after.sections().get(i).transform()),
                    transformed.get(i).getAsJsonObject().get("transform"));
        }
    }

    @Test
    void projectsRigidSectionTranslationRelativeToTheCapturedPose() {
        var section = new Object();
        var camera = new Matrix4f().translation(100, 0, -800).scale(2, -3, 4);
        var anchor = new GeometryState(camera, List.of(new GeometryState.Section(section, new Matrix4f().translation(5, 1, 2), 1, null)));
        var moved = new GeometryState(camera, List.of(new GeometryState.Section(section, new Matrix4f().translation(6, 3, 4), 1, null)));
        var translation = moved.motionFrom(anchor);
        assertTrue(translation.blocks().getTranslation(new Vector3f()).distance(new Vector3f(2, -6, 8)) < 0.001f);
        assertEquals(new Matrix4f(), translation.shadow());

        var rotated = new GeometryState(camera, List.of(new GeometryState.Section(section, new Matrix4f().rotateY(1), 1, null)));
        var motion = rotated.motionFrom(anchor);
        var projection = new Matrix4f().translation(0, 0, -800).mul(camera);
        var local = new Vector3f(1, 2, 3);
        var expected = new Matrix4f(projection).rotateY(1).transformPosition(new Vector3f(local));
        var original = new Matrix4f(projection).translate(5, 1, 2).transformPosition(new Vector3f(local));
        assertTrue(motion.blocks().transformPosition(original).distance(expected) < 0.001f);
        assertEquals(new Matrix4f(), motion.shadow());
        assertNull(new GeometryState(camera, List.of()).motionFrom(anchor));
    }

    @Test
    void cameraRotationsMoveTheShadowAndAllSectionsWithTheSameDelta() {
        var sections = List.of(
                new GeometryState.Section(new Object(), new Matrix4f().translation(5, 0, 0), 1, null),
                new GeometryState.Section(new Object(), new Matrix4f().translation(0, 5, 0), 1, null));
        var reference = new GeometryState(new Matrix4f(), sections);
        var motion = new GeometryState(new Matrix4f().rotateY(0.5f), sections).motionFrom(reference);
        assertTrue(motion.blocks().equals(motion.shadow(), 0.0001f));
        var first = sections.getFirst();
        var independent = List.of(new GeometryState.Section(first.element(), new Matrix4f().rotateX(1), 1, null), sections.getLast());
        assertNull(new GeometryState(reference.camera(), independent).motionFrom(reference));
    }
}
