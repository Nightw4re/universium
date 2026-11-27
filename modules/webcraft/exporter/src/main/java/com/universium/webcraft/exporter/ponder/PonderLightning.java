package com.universium.webcraft.exporter.ponder;

import net.minecraft.util.RandomSource;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Validates seed-based vanilla lightning against the original complete draw. */
final class PonderLightning {
    static final int MAX_BOLTS = 16;
    static final int VERTICES_PER_BOLT = 896;
    private static final int STRIDE = 16;
    private static final int PACKED_COLOR = 0x4c7f7272;
    private static final long SEED_MASK = (1L << 48) - 1;
    private static final boolean[][] CORNERS = {{false, false}, {true, false}, {true, true}, {false, true}};

    record Bolt(int start, long seed, float[] pose) {}

    private PonderLightning() {}

    static Bolt record(int start, long seed, Matrix4fc pose) {
        var matrix = new float[] {pose.m00(), pose.m01(), pose.m02(), pose.m10(), pose.m11(), pose.m12(),
                pose.m20(), pose.m21(), pose.m22(), pose.m30(), pose.m31(), pose.m32()};
        for (float value : matrix) if (!(Math.abs(value) <= 1e6)) return null;
        return new Bolt(start, seed & SEED_MASK, matrix);
    }

    static byte[] encode(ByteBuffer source, int count, List<Bolt> bolts) {
        if (bolts.isEmpty() || bolts.size() > MAX_BOLTS || count != bolts.size() * VERTICES_PER_BOLT
                || source.remaining() != count * STRIDE) return null;
        var vertices = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        var packed = ByteBuffer.allocate(2 + bolts.size() * 56).order(ByteOrder.LITTLE_ENDIAN);
        packed.putShort((short) bolts.size());
        for (int i = 0; i < bolts.size(); i++) {
            var bolt = bolts.get(i);
            if (bolt.start() != i * VERTICES_PER_BOLT || !matches(vertices, bolt)) return null;
            packed.putLong(bolt.seed());
            for (float value : bolt.pose()) packed.putFloat(value);
        }
        return packed.array();
    }

    private static boolean matches(ByteBuffer vertices, Bolt bolt) {
        var random = RandomSource.create(bolt.seed());
        var centerX = new float[8];
        var centerZ = new float[8];
        float endX = 0, endZ = 0;
        for (int segment = 7; segment >= 0; segment--) {
            centerX[segment] = endX;
            centerZ[segment] = endZ;
            endX += random.nextInt(11) - 5;
            endZ += random.nextInt(11) - 5;
        }

        for (int layer = 0; layer < 4; layer++) {
            random.setSeed(bolt.seed());
            for (int branch = 0; branch < 3; branch++) {
                int top = 7 - branch;
                int bottom = branch == 0 ? 0 : top - 2;
                float x = centerX[top] - endX, z = centerZ[top] - endZ;
                int bound = branch == 0 ? 11 : 31;
                for (int segment = top; segment >= bottom; segment--) {
                    float nextX = x + (random.nextInt(bound) - bound / 2);
                    float nextZ = z + (random.nextInt(bound) - bound / 2);
                    float upper = 0.1F + layer * 0.2F, lower = upper;
                    if (branch == 0) {
                        upper *= segment * 0.1F + 1.0F;
                        lower *= (segment - 1.0F) * 0.1F + 1.0F;
                    }
                    // The vanilla quads walk four adjoining sides in this order.
                    for (int side = 0; side < 4; side++) {
                        var a = CORNERS[side];
                        var b = CORNERS[(side + 1) % 4];
                        if (!point(vertices, bolt.pose(), nextX, segment * 16, nextZ, lower, a)
                                || !point(vertices, bolt.pose(), x, (segment + 1) * 16, z, upper, a)
                                || !point(vertices, bolt.pose(), x, (segment + 1) * 16, z, upper, b)
                                || !point(vertices, bolt.pose(), nextX, segment * 16, nextZ, lower, b)) return false;
                    }
                    x = nextX;
                    z = nextZ;
                }
            }
        }
        return true;
    }

    private static boolean point(ByteBuffer vertices, float[] pose, float x, float y, float z, float width, boolean[] corner) {
        x += corner[0] ? width : -width;
        z += corner[1] ? width : -width;
        for (int axis = 0; axis < 3; axis++) {
            if (vertices.getInt() != Float.floatToRawIntBits(PonderRigidParts.transform(pose, axis, x, y, z, pose[9 + axis]))) {
                return false;
            }
        }
        return vertices.getInt() == PACKED_COLOR;
    }
}
