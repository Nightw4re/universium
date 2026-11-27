package com.universium.webcraft.exporter.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;

record GeometryState(Matrix4f camera, List<Section> sections) {
    private static final float RENDER_DEPTH = -800;
    private static final float MATRIX_TOLERANCE = 0.0001f;

    record Section(Object element, Matrix4f transform, float fade, Object fadeDirection) {}
    record Motion(Matrix4f blocks, Matrix4f shadow) {}

    boolean canReuse(GeometryState previous, Object barrier, Object previousBarrier) {
        return barrier != null && barrier == previousBarrier && equals(previous);
    }

    boolean canRepose(GeometryState previous, Object barrier, Object previousBarrier) {
        if (barrier == null || barrier != previousBarrier || previous == null
                || sections.size() != previous.sections.size()) return false;
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.get(i);
            var prior = previous.sections.get(i);
            if (section.element != prior.element || section.fade != 1 || prior.fade != 1) return false;
        }
        return true;
    }

    JsonArray repose(JsonArray groups) {
        if (groups.size() != sections.size()) return null;
        var result = new JsonArray();
        for (int i = 0; i < sections.size(); i++) {
            var group = new JsonObject();
            group.add("transform", PonderDrawCapture.matrix(sections.get(i).transform));
            group.add("blocks", groups.get(i).getAsJsonObject().get("blocks"));
            result.add(group);
        }
        return result;
    }

    Motion motionFrom(GeometryState reference) {
        if (reference == null || sections.isEmpty() || sections.size() != reference.sections.size()) return null;
        var oldCamera = renderedCamera(reference.camera);
        var newCamera = renderedCamera(camera);
        var shadow = camera.equals(reference.camera) ? new Matrix4f() : relative(oldCamera, newCamera);
        if (shadow == null) return null;

        Matrix4f blocks = null;
        for (int i = 0; i < sections.size(); i++) {
            var before = reference.sections.get(i);
            var after = sections.get(i);
            if (before.element != after.element || before.fade != 1 || after.fade != 1
                    || !Objects.equals(before.fadeDirection, after.fadeDirection)) return null;
            var delta = relative(new Matrix4f(oldCamera).mul(before.transform), new Matrix4f(newCamera).mul(after.transform));
            if (delta == null || blocks != null && !blocks.equals(delta, MATRIX_TOLERANCE)) return null;
            blocks = delta;
        }
        return new Motion(blocks, shadow);
    }

    private static Matrix4f renderedCamera(Matrix4f camera) {
        // PonderUI offsets the submitted vertices in depth before applying the scene camera.
        return new Matrix4f().translation(0, 0, RENDER_DEPTH).mul(camera);
    }

    private static Matrix4f relative(Matrix4f before, Matrix4f after) {
        if (!before.isFinite() || !after.isFinite() || Math.abs(before.determinant3x3()) < 1e-6f) return null;
        var delta = new Matrix4f(after).mul(new Matrix4f(before).invertAffine());
        return delta.isFinite() ? delta : null;
    }
}
