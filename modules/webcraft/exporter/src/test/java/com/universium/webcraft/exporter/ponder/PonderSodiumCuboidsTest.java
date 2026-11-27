package com.universium.webcraft.exporter.ponder;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonderSodiumCuboidsTest {
    private static final int COLOR = 0x7fab7654, OVERLAY = 0x000a0000, LIGHT = 0x00b00070;
    private static final int[] CORNERS = {5, 4, 0, 1, 2, 3, 7, 6, 5, 1, 2, 6, 1, 0, 3, 2, 0, 4, 7, 3, 4, 5, 6, 7};
    private static final int[] SELECTORS = {0, 1, 3, 2, 4, 5, 7, 6};
    private static final Vector3f[] NORMALS = {new Vector3f(0, -1, 0), new Vector3f(0, 1, 0), new Vector3f(1, 0, 0),
            new Vector3f(0, 0, -1), new Vector3f(-1, 0, 0), new Vector3f(0, 0, 1)};

    @Test
    void sharesCuboidUvAndSelectorsAndKeepsExactPositionsAndPackedNormals() {
        var ordinary = new Cuboid(false, 63);
        var mirrored = new Cuboid(true, 31);
        var first = PonderSodiumCuboids.readModel(ordinary);
        var second = PonderSodiumCuboids.readModel(mirrored);
        assertNotNull(first);
        assertNotNull(second);
        var pose = pose();
        var frame = ByteBuffer.allocate(44 * 36).order(ByteOrder.LITTLE_ENDIAN);
        boolean nonAffineRounding = emit(frame, ordinary, pose);
        assertTrue(emit(frame, mirrored, pose) || nonAffineRounding);
        var p0 = PonderSodiumCuboids.placement(first, 0, pose.pose());
        var p1 = PonderSodiumCuboids.placement(second, 24, pose.pose());
        byte[] bytes = PonderSodiumCuboids.encode(frame.flip(), 44, List.of(p0, p1));
        assertNotNull(bytes);
        var packet = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(226, packet.remaining());
        assertEquals(2, packet.getShort());
        for (var part : List.of(p0, p1)) {
            byte[] digest = new byte[32];
            packet.get(digest);
            assertArrayEquals(part.model().part().digest(), digest);
            assertEquals(part.model().part().count(), packet.getShort());
            for (float position : part.position()) assertEquals(position, packet.getFloat());
            byte[] normals = new byte[18];
            packet.get(normals);
            for (int enabled = 0; enabled < part.model().faces().length; enabled++) {
                int slot = part.model().faces()[enabled];
                for (int lane = 0; lane < 3; lane++) {
                    assertEquals(frame.get((part.start() + enabled * 4) * 36 + 32 + lane), normals[slot * 3 + lane]);
                }
            }
            if (part == p1) assertEquals(0, normals[17]);
            assertEquals(COLOR, packet.getInt());
            assertEquals(OVERLAY, packet.getInt());
            assertEquals(LIGHT, packet.getInt());
        }
        assertEquals(0, packet.remaining());
        var local = ByteBuffer.wrap(second.part().bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1f, local.getFloat(0));
        assertEquals(0f, local.getFloat(4));
        assertEquals(0f, local.getFloat(8));
        assertEquals(0x8100, local.getInt(20));
    }

    @Test
    void sharesTheSameCuboidWithDistinctAtlasSprites() {
        var cube = new Cuboid(false, 63);
        var first = new PonderSodiumCuboids.SpriteUv(.125f, .25f, .25f, .3125f);
        var second = new PonderSodiumCuboids.SpriteUv(.75f, .875f, .5f, .625f);
        var firstModel = PonderSodiumCuboids.readModel(cube, first);
        var secondModel = PonderSodiumCuboids.readModel(cube, second);
        var pose = pose();
        var frame = ByteBuffer.allocate(24 * 36).order(ByteOrder.LITTLE_ENDIAN);
        emit(frame, cube, pose);
        frame.flip();
        for (int i = 0; i < 24; i++) {
            int offset = i * 36 + 16;
            frame.putFloat(offset, .125f + .125f * frame.getFloat(offset));
            frame.putFloat(offset + 4, .25f + .0625f * frame.getFloat(offset + 4));
        }
        assertNotNull(PonderSodiumCuboids.encode(frame, 24, List.of(PonderSodiumCuboids.placement(firstModel, 0, pose.pose()))));
        assertNull(PonderSodiumCuboids.encode(frame, 24, List.of(PonderSodiumCuboids.placement(secondModel, 0, pose.pose()))));
        assertNull(PonderSodiumCuboids.encode(frame, 24,
                List.of(PonderSodiumCuboids.placement(PonderSodiumCuboids.readModel(cube), 0, pose.pose()))));
    }

    @Test
    void rejectsChangedGeometryMalformedModelsAndMixedDraws() {
        var original = new Cuboid(false, 63);
        var model = PonderSodiumCuboids.readModel(original);
        assertNotNull(model);
        var pose = pose();
        var placement = PonderSodiumCuboids.placement(model, 0, pose.pose());
        var frame = ByteBuffer.allocate(24 * 36).order(ByteOrder.LITTLE_ENDIAN);
        emit(frame, original, pose);
        frame.flip();
        assertNotNull(PonderSodiumCuboids.encode(frame, 24, List.of(placement)));
        for (int offset : new int[] {0, 12, 16, 24, 28, 35, 36 + 32}) {
            byte previous = frame.get(offset);
            frame.put(offset, (byte) (previous ^ 1));
            assertNull(PonderSodiumCuboids.encode(frame, 24, List.of(placement)), "Changed byte at " + offset);
            frame.put(offset, previous);
        }
        assertNull(PonderSodiumCuboids.encode(frame, 24, List.of(PonderSodiumCuboids.placement(model, 4, pose.pose()))));
        assertNull(PonderSodiumCuboids.encode(ByteBuffer.allocate(28 * 36), 28, List.of(placement)));
        assertNull(PonderSodiumCuboids.readModel(new Object()));
        original.positions[0] = 8;
        assertNull(PonderSodiumCuboids.readModel(original));
    }

    private static PoseStack.Pose pose() {
        var stack = new PoseStack();
        stack.translate(251.352, -114.71, 3.051);
        stack.mulPose(new Quaternionf().rotationXYZ(.398f, -.237f, .613f));
        stack.scale(.19f, 2.13f, .75f);
        return stack.last();
    }

    private static boolean emit(ByteBuffer frame, Cuboid cube, PoseStack.Pose pose) {
        var matrix = pose.pose();
        var origin = new Vector4f(cube.originX, cube.originY, cube.originZ, 1).mul(matrix);
        var xStep = matrix.getColumn(0, new Vector4f()).mul(cube.sizeX);
        var yStep = matrix.getColumn(1, new Vector4f()).mul(cube.sizeY);
        var zStep = matrix.getColumn(2, new Vector4f()).mul(cube.sizeZ);
        var corners = List.of(origin, new Vector4f(origin).add(xStep), new Vector4f(origin).add(xStep).add(yStep),
                new Vector4f(origin).add(yStep), new Vector4f(origin).add(zStep), new Vector4f(origin).add(xStep).add(zStep),
                new Vector4f(origin).add(xStep).add(yStep).add(zStep), new Vector4f(origin).add(yStep).add(zStep));
        boolean different = false;
        for (int face = 0; face < 6; face++) {
            if (!cube.shouldDrawFace(face)) continue;
            int direction = cube.sizeX < 0 && (face == 2 || face == 4) ? 6 - face : face;
            var normal = new Vector3f(NORMALS[direction]).mul(pose.normal()).normalize();
            for (int vertex = 0; vertex < 4; vertex++) {
                int index = face * 4 + vertex;
                var point = corners.get(cube.positions[index]);
                int mask = SELECTORS[cube.positions[index]];
                var direct = new Vector4f(cube.originX + (mask & 1) * cube.sizeX, cube.originY + (mask >>> 1 & 1) * cube.sizeY,
                        cube.originZ + (mask >>> 2 & 1) * cube.sizeZ, 1).mul(matrix);
                different |= !point.equals(direct);
                frame.putFloat(point.x).putFloat(point.y).putFloat(point.z).putInt(COLOR);
                frame.putLong(cube.textures[index]).putInt(OVERLAY).putInt(LIGHT);
                for (int axis = 0; axis < 3; axis++) frame.put((byte) (Math.clamp(normal.get(axis), -1f, 1f) * 127f));
                frame.put((byte) 0);
            }
        }
        return different;
    }

    public static final class Cuboid {
        public final float originX = .17f, originY = -.14f, originZ = .81f;
        public final float sizeX, sizeY = 1.18f, sizeZ = .23f;
        public final int[] positions = CORNERS.clone();
        public final long[] textures = new long[positions.length];
        private final int mask;

        Cuboid(boolean mirror, int mask) {
            sizeX = mirror ? -.33f : .33f;
            this.mask = mask;
            for (int i = 0; i < positions.length; i++) {
                int uv = i % 4;
                if (mirror) positions[i] = CORNERS[(i / 4) * 4 + 3 - uv];
                long u = Integer.toUnsignedLong(Float.floatToRawIntBits((uv == 0 || uv == 3 ? .17f : .33f)));
                long v = (long) Float.floatToRawIntBits((uv < 2 ? .5f : .75f)) << 32;
                textures[i] = u | v;
            }
        }

        public boolean shouldDrawFace(int face) {
            return (mask & 1 << face) != 0;
        }
    }
}
