package com.universium.webcraft.exporter.ponder;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PonderRigidGlintTest {
    @Test
    void sharesTheSameItemPartAcrossOrderedFoilPoses() {
        var part = part();
        var first = pose(3.21);
        var second = pose(63.83);
        var placements = List.of(placement(part, 0, first), placement(part, 8, second));
        var captured = ByteBuffer.allocate(16 * 20).order(ByteOrder.LITTLE_ENDIAN);
        emit(captured, part, first);
        emit(captured, part, second);
        byte[] encoded = PonderRigidGlint.encode(captured.flip(), 16, placements);
        assertNotNull(encoded);
        var packet = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(166, packet.remaining());
        assertEquals(2, packet.getShort());
        for (var placement : placements) {
            byte[] digest = new byte[32];
            packet.get(digest);
            assertArrayEquals(part.digest(), digest);
            assertEquals(8, packet.getShort());
            for (float value : placement.position()) assertEquals(value, packet.getFloat());
        }
        assertEquals(0, packet.remaining());
    }

    @Test
    void keepsTheOriginalFoilMeshForUncoveredVerticesOrDifferentUvs() {
        var part = part();
        var pose = pose(.45);
        var placement = placement(part, 0, pose);
        var captured = ByteBuffer.allocate(8 * 20).order(ByteOrder.LITTLE_ENDIAN);
        emit(captured, part, pose);
        captured.flip();
        assertNotNull(PonderRigidGlint.encode(captured, 8, List.of(placement)));
        assertNull(PonderRigidGlint.encode(captured, 8, List.of()));
        assertNull(PonderRigidGlint.encode(captured, 8, List.of(placement(part, 4, pose))));
        assertNull(PonderRigidGlint.encode(ByteBuffer.allocate(12 * 20), 12, List.of(placement)));
        for (int offset : new int[] {0, 12, 16}) {
            byte original = captured.get(offset);
            captured.put(offset, (byte) (original ^ 1));
            assertNull(PonderRigidGlint.encode(captured, 8, List.of(placement)), "Changed byte at " + offset);
            captured.put(offset, original);
        }
    }

    private static PonderRigidGlint.Placement placement(PonderRigidParts.Part part, int start, PoseStack.Pose pose) {
        return new PonderRigidGlint.Placement(part, start, PonderRigidParts.placement(part, start, pose, false).position());
    }

    private static PonderRigidParts.Part part() {
        var local = ByteBuffer.allocate(8 * 24).order(ByteOrder.LITTLE_ENDIAN);
        for (int vertex = 0; vertex < 8; vertex++) {
            local.putFloat((vertex & 1) * .25f).putFloat((vertex >>> 1 & 1) * .79f).putFloat((vertex >>> 2 & 1) * .3125f);
            local.putFloat((vertex & 1) * .09f + .3f).putFloat((vertex >>> 1 & 1) * .169f + .47f);
            local.put((byte) 90).put((byte) 0).put((byte) -90).put((byte) 0);
        }
        return PonderRigidParts.Part.fromLocal(local.array(), 8);
    }

    private static PoseStack.Pose pose(double x) {
        var pose = new PoseStack();
        pose.translate(x, -4.55, 8.27);
        pose.mulPose(new Quaternionf().rotationXYZ(.142f, -.571f, .32f));
        pose.scale(.5f, .24f, .975f);
        return pose.last();
    }

    private static void emit(ByteBuffer target, PonderRigidParts.Part part, PoseStack.Pose pose) {
        var local = ByteBuffer.wrap(part.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < part.count() * 24; i += 24) {
            var position = new Vector4f(local.getFloat(i), local.getFloat(i + 4), local.getFloat(i + 8), 1).mul(pose.pose());
            target.putFloat(position.x).putFloat(position.y).putFloat(position.z).putLong(local.getLong(i + 12));
        }
    }
}
