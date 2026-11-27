package com.universium.webcraft.exporter.ponder;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PonderOutlineCuboidsTest {
    private static final Vector3f MINIMUM = new Vector3f(-.125f, 1, -2.45f);
    private static final Vector3f MAXIMUM = new Vector3f(.125f, 7, 3.125f);
    private static final Vector4f COLOR = new Vector4f(.25f, .5f, .75f, 1);
    private static final int OVERLAY = 0x000a0000;
    private static final int LIGHT = 0x00f000a0;

    @Test
    void recordsTwoCatnipCuboidsInOriginalOrderWithTheirOwnShading() {
        var pose = pose();
        var secondColor = new Vector4f(1, .3f, .41f, .65f);
        var first = PonderOutlineCuboids.record(0, pose, MINIMUM, MAXIMUM, COLOR, OVERLAY, LIGHT, false);
        var second = PonderOutlineCuboids.record(24, pose, MINIMUM, MAXIMUM, secondColor, OVERLAY, LIGHT, true);
        var mesh = ByteBuffer.allocate(48 * 36).order(ByteOrder.LITTLE_ENDIAN);
        emit(mesh, pose, COLOR, false);
        emit(mesh, pose, secondColor, true);

        byte[] encoded = PonderOutlineCuboids.encode(mesh.flip(), 48, List.of(first, second));
        assertNotNull(encoded);
        var packet = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(244, packet.remaining());
        assertEquals(2, packet.getShort());
        for (var cuboid : List.of(first, second)) {
            for (float[] vector : List.of(cuboid.minimum(), cuboid.maximum(), cuboid.position(), cuboid.normal())) {
                for (float number : vector) assertEquals(number, packet.getFloat());
            }
            assertEquals(cuboid.color(), packet.getInt());
            assertEquals(OVERLAY, packet.getInt());
            assertEquals(LIGHT, packet.getInt());
            assertEquals(cuboid.disableDiffuse() ? 1 : 0, packet.get());
        }
        assertEquals(0, packet.remaining());
    }

    @Test
    void leavesTheOriginalMeshWhenAnythingDiffersFromTheRecordedCuboid() {
        var pose = pose();
        var shape = PonderOutlineCuboids.record(0, pose, MINIMUM, MAXIMUM, COLOR, OVERLAY, LIGHT, false);
        var mesh = ByteBuffer.allocate(24 * 36).order(ByteOrder.LITTLE_ENDIAN);
        emit(mesh, pose, COLOR, false);
        mesh.flip();
        assertNotNull(PonderOutlineCuboids.encode(mesh, 24, List.of(shape)));
        assertNull(PonderOutlineCuboids.encode(mesh, 23, List.of(shape)));
        assertNull(PonderOutlineCuboids.encode(mesh, 24, List.of(PonderOutlineCuboids.record(4, pose, MINIMUM, MAXIMUM,
                COLOR, OVERLAY, LIGHT, false))));
        assertNull(PonderOutlineCuboids.encode(mesh, 24, List.of(PonderOutlineCuboids.record(0, pose, MAXIMUM, MINIMUM,
                COLOR, OVERLAY, LIGHT, false))));
        for (int field : new int[] {0, 12, 16, 20, 24, 28, 32}) {
            byte expected = mesh.get(field);
            mesh.put(field, (byte) (expected ^ 1));
            assertNull(PonderOutlineCuboids.encode(mesh, 24, List.of(shape)), "Changed byte at " + field);
            mesh.put(field, expected);
        }
        assertNull(PonderOutlineCuboids.record(0, pose, MINIMUM, MAXIMUM, new Vector4f(Float.NaN, 0, 0, 1),
                OVERLAY, LIGHT, false));
    }

    private static PoseStack.Pose pose() {
        var pose = new PoseStack();
        pose.translate(3.265, -4.54, 5.106);
        pose.mulPose(new Quaternionf().rotationXYZ(-.54f, .25f, .32f));
        pose.scale(.7f, 1.12f, .4f);
        return pose.last();
    }

    private static void emit(ByteBuffer bytes, PoseStack.Pose pose, Vector4f color, boolean flat) {
        var corners = List.of(new Vector4f(MINIMUM.x, MINIMUM.y, MINIMUM.z, 1), new Vector4f(MINIMUM.x, MINIMUM.y, MAXIMUM.z, 1),
                new Vector4f(MINIMUM.x, MAXIMUM.y, MINIMUM.z, 1), new Vector4f(MINIMUM.x, MAXIMUM.y, MAXIMUM.z, 1),
                new Vector4f(MAXIMUM.x, MINIMUM.y, MINIMUM.z, 1), new Vector4f(MAXIMUM.x, MINIMUM.y, MAXIMUM.z, 1),
                new Vector4f(MAXIMUM.x, MAXIMUM.y, MINIMUM.z, 1), new Vector4f(MAXIMUM.x, MAXIMUM.y, MAXIMUM.z, 1));
        var normals = List.of(new Vector3f(0, -1, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, -1),
                new Vector3f(0, 0, 1), new Vector3f(-1, 0, 0), new Vector3f(1, 0, 0));
        var faces = List.of(List.of(1, 0, 4, 5), List.of(2, 3, 7, 6), List.of(6, 4, 0, 2),
                List.of(3, 1, 5, 7), List.of(2, 0, 1, 3), List.of(7, 5, 4, 6));
        int[] u = {0, 0, 1, 1};
        int[] v = {0, 1, 1, 0};
        for (int face = 0; face < faces.size(); face++) {
            var normal = new Vector3f(normals.get(flat ? 1 : face)).mul(pose.normal());
            for (int vertex = 0; vertex < 4; vertex++) {
                var position = new Vector4f(corners.get(faces.get(face).get(vertex))).mul(pose.pose());
                bytes.putFloat(position.x()).putFloat(position.y()).putFloat(position.z());
                for (int channel = 0; channel < 4; channel++) bytes.put((byte) (color.get(channel) * 255));
                bytes.putFloat(u[vertex]).putFloat(v[vertex]).putInt(OVERLAY).putInt(LIGHT);
                for (int axis = 0; axis < 3; axis++) bytes.put((byte) (Math.clamp(normal.get(axis), -1f, 1f) * 127f));
                bytes.put((byte) 0);
            }
        }
    }
}
