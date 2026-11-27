package com.universium.webcraft.exporter.ponder;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3fc;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Records the exact local bounds and pose used by Catnip's solid outline renderer. */
final class PonderOutlineCuboids {
    static final int MAX_CUBOIDS = 1024;
    private static final int VERTICES = 24;
    private static final int STRIDE = 36;
    private static final int RECORD_BYTES = 121;
    private static final int[][] CORNERS = {
            {1, 0, 4, 5}, {2, 3, 7, 6}, {6, 4, 0, 2}, {3, 1, 5, 7}, {2, 0, 1, 3}, {7, 5, 4, 6}
    };
    private static final int[] NORMAL_AXIS = {1, 1, 2, 2, 0, 0};

    record Cuboid(int start, float[] minimum, float[] maximum, float[] position, float[] normal,
                  int color, int overlay, int light, boolean disableDiffuse) {}

    private PonderOutlineCuboids() {}

    static Cuboid record(int start, PoseStack.Pose pose, Vector3fc minimum, Vector3fc maximum,
                         Vector4fc color, int overlay, int light, boolean disableDiffuse) {
        if (!channel(color.x()) || !channel(color.y()) || !channel(color.z()) || !channel(color.w())) return null;
        float[] transform = pose.pose().get(new float[16]);
        var position = new float[12];
        for (int column = 0; column < 4; column++) System.arraycopy(transform, column * 4, position, column * 3, 3);
        return new Cuboid(start, new float[]{minimum.x(), minimum.y(), minimum.z()},
                new float[]{maximum.x(), maximum.y(), maximum.z()}, position, pose.normal().get(new float[9]),
                component(color.x()) | component(color.y()) << 8 | component(color.z()) << 16 | component(color.w()) << 24,
                overlay, light, disableDiffuse);
    }

    static byte[] encode(ByteBuffer source, int vertexCount, List<Cuboid> cuboids) {
        if (cuboids.isEmpty() || cuboids.size() > MAX_CUBOIDS || vertexCount != cuboids.size() * VERTICES
                || source.remaining() != vertexCount * STRIDE) return null;
        var vertices = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        var document = ByteBuffer.allocate(2 + RECORD_BYTES * cuboids.size()).order(ByteOrder.LITTLE_ENDIAN);
        document.putShort((short) cuboids.size());
        int next = 0;
        for (var cuboid : cuboids) {
            if (cuboid.start() != next || !bounds(cuboid) || !matches(vertices, cuboid)) return null;
            floats(document, cuboid.minimum());
            floats(document, cuboid.maximum());
            floats(document, cuboid.position());
            floats(document, cuboid.normal());
            document.putInt(cuboid.color()).putInt(cuboid.overlay()).putInt(cuboid.light());
            document.put((byte) (cuboid.disableDiffuse() ? 1 : 0));
            next += VERTICES;
        }
        return document.array();
    }

    private static boolean bounds(Cuboid cuboid) {
        if (!bounded(cuboid.minimum(), 3) || !bounded(cuboid.maximum(), 3)
                || !bounded(cuboid.position(), 12) || !bounded(cuboid.normal(), 9)) return false;
        for (int axis = 0; axis < 3; axis++) if (cuboid.minimum()[axis] > cuboid.maximum()[axis]) return false;
        return true;
    }

    private static boolean matches(ByteBuffer vertices, Cuboid cuboid) {
        for (int face = 0; face < CORNERS.length; face++) {
            int axis = cuboid.disableDiffuse() ? 1 : NORMAL_AXIS[face];
            int sign = cuboid.disableDiffuse() || face % 2 == 1 ? 1 : -1;
            for (int point = 0; point < 4; point++) {
                int at = (cuboid.start() + face * 4 + point) * STRIDE;
                int corner = CORNERS[face][point];
                float x = coordinate(cuboid, corner, 0), y = coordinate(cuboid, corner, 1), z = coordinate(cuboid, corner, 2);
                for (int row = 0; row < 3; row++) {
                    float[] matrix = cuboid.position();
                    float expected = matrix[row] * x + (matrix[row + 3] * y + (matrix[row + 6] * z + matrix[row + 9]));
                    if (vertices.getInt(at + row * 4) != Float.floatToRawIntBits(expected)
                            || vertices.get(at + 32 + row) != packNormal(cuboid.normal()[axis * 3 + row] * sign)) return false;
                }
                if (vertices.getInt(at + 12) != cuboid.color()
                        || vertices.getInt(at + 16) != Float.floatToRawIntBits(point < 2 ? 0 : 1)
                        || vertices.getInt(at + 20) != Float.floatToRawIntBits(point == 0 || point == 3 ? 0 : 1)
                        || vertices.getInt(at + 24) != cuboid.overlay() || vertices.getInt(at + 28) != cuboid.light()) return false;
            }
        }
        return true;
    }

    private static float coordinate(Cuboid cuboid, int corner, int axis) {
        return ((corner >>> (2 - axis)) & 1) == 0 ? cuboid.minimum()[axis] : cuboid.maximum()[axis];
    }

    private static void floats(ByteBuffer buffer, float[] values) {
        for (float value : values) buffer.putFloat(value);
    }

    private static boolean bounded(float[] values, int length) {
        if (values.length != length) return false;
        for (float value : values) if (!(Math.abs(value) <= 1e6f)) return false;
        return true;
    }

    private static boolean channel(float value) {
        return value >= 0 && value <= 1;
    }

    private static int component(float value) {
        return (int) (value * 255);
    }

    private static byte packNormal(float value) {
        return (byte) (Math.clamp(value, -1f, 1f) * 127f);
    }
}
