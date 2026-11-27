package com.universium.webcraft.exporter.ponder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Encodes verified particle quads as a center and two opposite-corner offsets. */
final class PonderBillboards {
    private static final int VERTEX_BYTES = 28;
    private static final int QUAD_BYTES = 38;
    private static final int APPEARANCE_BYTES = 24;
    private static final int MAX_QUADS = 16_384;
    private static final int CORRECTION_BYTES = 6;
    private static final int BASIS_BYTES = 24;
    private static final int SCALED_QUAD_BYTES = 20;

    record Encoded(byte[] bytes, int version) {}

    record Basis(float ax, float ay, float az, float bx, float by, float bz) {
        void write(ByteBuffer target) {
            target.putFloat(ax).putFloat(ay).putFloat(az).putFloat(bx).putFloat(by).putFloat(bz);
        }

        Billboard scale(float x, float y, float z, float size) {
            return new Billboard(x, y, z, ax * size, ay * size, az * size, bx * size, by * size, bz * size, this, size);
        }
    }

    record Billboard(float x, float y, float z, float ax, float ay, float az, float bx, float by, float bz, Basis basis, float size) {
        Billboard(float x, float y, float z, float ax, float ay, float az, float bx, float by, float bz) {
            this(x, y, z, ax, ay, az, bx, by, bz, null, 0);
        }

        boolean matches(ByteBuffer vertices, int offset, int vertex) {
            float sign = vertex < 2 ? 1 : -1;
            return matchesPosition(vertices, offset, x + sign * (vertex % 2 == 0 ? ax : bx))
                    && matchesPosition(vertices, offset + 4, y + sign * (vertex % 2 == 0 ? ay : by))
                    && matchesPosition(vertices, offset + 8, z + sign * (vertex % 2 == 0 ? az : bz));
        }

        private static boolean matchesPosition(ByteBuffer vertices, int offset, float expected) {
            return Float.isFinite(expected) && vertices.getInt(offset) == Float.floatToRawIntBits(expected);
        }

        void write(ByteBuffer target) {
            target.putFloat(x).putFloat(y).putFloat(z).putFloat(ax).putFloat(ay).putFloat(az)
                    .putFloat(bx).putFloat(by).putFloat(bz);
        }

        long corrections(ByteBuffer vertices, int start) {
            var values = new float[] {x, y, z, ax, ay, az, bx, by, bz};
            long result = 0;
            for (int lane = 0; lane < 12; lane++) {
                int vertex = lane / 3;
                int axis = lane % 3;
                float sign = vertex < 2 ? 1 : -1;
                float value = values[axis] + sign * values[3 + (vertex % 2) * 3 + axis];
                int actual = vertices.getInt(start + vertex * VERTEX_BYTES + axis * 4);
                long difference = (long) actual - Float.floatToRawIntBits(value);
                if (!finite(actual) || !Float.isFinite(value) || difference < -7 || difference > 7) return -1;
                result |= (difference & 15) << (lane * 4);
            }
            return result;
        }
    }

    private record Appearance(int uFar, int vFar, int uNear, int vNear, int color, int light) {
        void write(ByteBuffer target) {
            target.putInt(uFar).putInt(vFar).putInt(uNear).putInt(vNear).putInt(color).putInt(light);
        }

        boolean matches(ByteBuffer vertices, int offset, int vertex) {
            return vertices.getInt(offset + 12) == (vertex < 2 ? uFar : uNear)
                    && vertices.getInt(offset + 16) == (vertex == 0 || vertex == 3 ? vFar : vNear)
                    && vertices.getInt(offset + 20) == color && vertices.getInt(offset + 24) == light;
        }
    }

    private record Axis(float center, float a, float b) {}

    static Encoded encode(ByteBuffer source, List<Billboard> billboards, int vertexCount) {
        return encode(source, billboards, vertexCount, null);
    }

    static Encoded encode(ByteBuffer source, List<Billboard> billboards, int vertexCount, Basis cameraBasis) {
        int count = vertexCount / 4;
        if (count == 0 || count > MAX_QUADS || vertexCount != count * 4 || source.remaining() != vertexCount * VERTEX_BYTES
                || (!billboards.isEmpty() && billboards.size() != count)) return null;
        var vertices = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        var quads = ByteBuffer.allocate(count * QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var appearances = new LinkedHashMap<Appearance, Integer>();
        long[] corrections = null;
        for (int quad = 0; quad < count; quad++) {
            int start = quad * 4 * VERTEX_BYTES;
            int uFar = vertices.getInt(start + 12);
            int vFar = vertices.getInt(start + 16);
            int uNear = vertices.getInt(start + 2 * VERTEX_BYTES + 12);
            int vNear = vertices.getInt(start + VERTEX_BYTES + 16);
            if (!finite(uFar) || !finite(uNear) || !finite(vFar) || !finite(vNear)) return null;
            var appearance = new Appearance(uFar, vFar, uNear, vNear, vertices.getInt(start + 20), vertices.getInt(start + 24));
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = start + vertex * VERTEX_BYTES;
                if (!appearance.matches(vertices, offset, vertex)) return null;
            }
            var billboard = billboards.isEmpty() ? null : billboards.get(quad);
            if (!matches(billboard, vertices, start)) {
                var inferred = infer(vertices, start, true);
                if (matches(inferred, vertices, start)) billboard = inferred;
                else {
                    long correction = billboard == null ? -1 : billboard.corrections(vertices, start);
                    if (correction < 0) {
                        billboard = infer(vertices, start, false);
                        correction = billboard.corrections(vertices, start);
                        if (correction < 0) return null;
                    }
                    if (correction != 0) {
                        if (corrections == null) corrections = new long[count];
                        corrections[quad] = correction;
                    }
                }
            }
            billboard.write(quads);
            quads.putShort(appearances.computeIfAbsent(appearance, unused -> appearances.size()).shortValue());
        }
        int originalSize = 4 + appearances.size() * APPEARANCE_BYTES + quads.position()
                + (corrections == null ? 0 : count * CORRECTION_BYTES);
        quads.flip();
        var compact = packOppositeDiagonals(quads, appearances, corrections, count, originalSize);
        var scaled = packSharedBases(vertices, quads, billboards, cameraBasis, appearances, corrections, count,
                compact == null ? originalSize : compact.bytes().length);
        if (scaled != null) return scaled;
        if (compact != null) return compact;
        var packed = ByteBuffer.allocate(originalSize).order(ByteOrder.LITTLE_ENDIAN);
        packed.putShort((short) count).putShort((short) appearances.size());
        appearances.keySet().forEach(appearance -> appearance.write(packed));
        packed.put(quads);
        if (corrections != null) {
            for (long correction : corrections) writeCorrection(packed, correction);
        }
        return new Encoded(packed.array(), corrections == null ? 1 : 2);
    }

    private static Encoded packSharedBases(ByteBuffer vertices, ByteBuffer quads, List<Billboard> billboards, Basis cameraBasis,
                                           LinkedHashMap<Appearance, Integer> appearances, long[] corrections,
                                           int count, int originalSize) {
        int size = 8 + appearances.size() * APPEARANCE_BYTES + count * SCALED_QUAD_BYTES;
        if ((billboards.isEmpty() && cameraBasis == null) || size + BASIS_BYTES >= originalSize) return null;
        var bases = new LinkedHashMap<Basis, Integer>();
        var adjustments = new LinkedHashMap<Integer, Long>();
        var placements = new ArrayList<Billboard>(count);
        boolean retainedOrientation = false;
        for (int quad = 0; quad < count; quad++) {
            int vertexStart = quad * 4 * VERTEX_BYTES;
            int start = quad * QUAD_BYTES;
            var shape = billboards.isEmpty() ? null : billboards.get(quad);
            long correction = shape == null || shape.basis() == null || !Float.isFinite(shape.size()) ? -1
                    : shape.corrections(vertices, vertexStart);
            if (correction < 0 && cameraBasis != null) {
                shape = fitCameraBasis(vertices, vertexStart, quads, start, cameraBasis);
                if (shape != null) correction = shape.corrections(vertices, vertexStart);
            }
            if (correction < 0) {
                // A submitted quad can need a different center to reproduce rounding near zero.
                var offsets = new Basis(quads.getFloat(start + 12), quads.getFloat(start + 16), quads.getFloat(start + 20),
                        quads.getFloat(start + 24), quads.getFloat(start + 28), quads.getFloat(start + 32));
                shape = offsets.scale(quads.getFloat(start), quads.getFloat(start + 4), quads.getFloat(start + 8), 1);
                correction = corrections == null ? 0 : corrections[quad];
            } else retainedOrientation = true;
            placements.add(shape);
            if (bases.putIfAbsent(shape.basis(), bases.size()) == null) size += BASIS_BYTES;
            if (correction != 0) {
                adjustments.put(quad, correction);
                size += 2 + CORRECTION_BYTES;
            }
            if (size >= originalSize) return null;
        }
        if (!retainedOrientation) return null;
        var packed = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        packed.putShort((short) count).putShort((short) appearances.size());
        packed.putShort((short) bases.size()).putShort((short) adjustments.size());
        appearances.keySet().forEach(appearance -> appearance.write(packed));
        bases.keySet().forEach(basis -> basis.write(packed));
        for (int quad = 0; quad < count; quad++) {
            var shape = placements.get(quad);
            packed.putFloat(shape.x()).putFloat(shape.y()).putFloat(shape.z()).putFloat(shape.size());
            packed.putShort(bases.get(shape.basis()).shortValue()).putShort(quads.getShort(quad * QUAD_BYTES + 36));
        }
        adjustments.forEach((quad, correction) -> {
            packed.putShort(quad.shortValue());
            writeCorrection(packed, correction);
        });
        return new Encoded(packed.array(), 4);
    }

    /** Sodium can submit quads without running the vanilla hook. Verify a camera-facing candidate against those vertices. */
    private static Billboard fitCameraBasis(ByteBuffer vertices, int vertexStart, ByteBuffer quads, int start, Basis basis) {
        var lanes = new float[] {basis.ax(), basis.ay(), basis.az(), basis.bx(), basis.by(), basis.bz()};
        int dominant = 0;
        for (int lane = 1; lane < lanes.length; lane++) {
            if (Math.abs(lanes[lane]) > Math.abs(lanes[dominant])) dominant = lane;
        }
        if (!(Math.abs(lanes[dominant]) > 0)) return null;
        float size = quads.getFloat(start + 12 + dominant * 4) / lanes[dominant];
        if (!Float.isFinite(size) || size <= 0) return null;
        float x = quads.getFloat(start), y = quads.getFloat(start + 4), z = quads.getFloat(start + 8);
        for (float candidate : new float[] {size, Math.nextDown(size), Math.nextUp(size)}) {
            var shape = basis.scale(x, y, z, candidate);
            if (shape.corrections(vertices, vertexStart) >= 0) return shape;
        }
        return null;
    }

    private static Encoded packOppositeDiagonals(ByteBuffer quads, LinkedHashMap<Appearance, Integer> appearances,
                                                 long[] corrections, int count, int originalSize) {
        int oppositeAxes = 0b111;
        for (int start = 0; start < quads.limit() && oppositeAxes != 0; start += QUAD_BYTES) {
            for (int axis = 0; axis < 3; axis++) {
                if ((quads.getInt(start + 12 + axis * 4) ^ Integer.MIN_VALUE) != quads.getInt(start + 24 + axis * 4)) {
                    oppositeAxes &= ~(1 << axis);
                }
            }
        }
        int correctionCount = 0;
        if (corrections != null) {
            for (long value : corrections) if (value != 0) correctionCount++;
        }
        int quadBytes = QUAD_BYTES - Integer.bitCount(oppositeAxes) * 4;
        int size = 8 + appearances.size() * APPEARANCE_BYTES + count * quadBytes + correctionCount * (2 + CORRECTION_BYTES);
        if (size >= originalSize) return null;

        var packed = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        packed.putShort((short) count).putShort((short) appearances.size());
        packed.putShort((short) oppositeAxes).putShort((short) correctionCount);
        appearances.keySet().forEach(appearance -> appearance.write(packed));
        for (int start = 0; start < quads.limit(); start += QUAD_BYTES) {
            packed.putLong(quads.getLong(start)).putLong(quads.getLong(start + 8)).putLong(quads.getLong(start + 16));
            for (int axis = 0; axis < 3; axis++) {
                if ((oppositeAxes & (1 << axis)) == 0) packed.putInt(quads.getInt(start + 24 + axis * 4));
            }
            packed.putShort(quads.getShort(start + 36));
        }
        if (corrections != null) {
            for (int quad = 0; quad < count; quad++) {
                if (corrections[quad] == 0) continue;
                packed.putShort((short) quad);
                writeCorrection(packed, corrections[quad]);
            }
        }
        return new Encoded(packed.array(), 3);
    }

    private static void writeCorrection(ByteBuffer packed, long correction) {
        for (int index = 0; index < CORRECTION_BYTES; index++) packed.put((byte) (correction >>> (index * 8)));
    }

    private static boolean matches(Billboard billboard, ByteBuffer vertices, int start) {
        if (billboard == null) return false;
        for (int vertex = 0; vertex < 4; vertex++) {
            if (!billboard.matches(vertices, start + vertex * VERTEX_BYTES, vertex)) return false;
        }
        return true;
    }

    private static Billboard infer(ByteBuffer vertices, int start, boolean requireExact) {
        var x = fitAxis(vertices, start, requireExact);
        if (x == null) return null;
        var y = fitAxis(vertices, start + 4, requireExact);
        if (y == null) return null;
        var z = fitAxis(vertices, start + 8, requireExact);
        if (z == null) return null;
        return new Billboard(x.center(), y.center(), z.center(), x.a(), y.a(), z.a(), x.b(), y.b(), z.b());
    }

    private static Axis fitAxis(ByteBuffer vertices, int offset, boolean requireExact) {
        float a = vertices.getFloat(offset), b = vertices.getFloat(offset + VERTEX_BYTES);
        float c = vertices.getFloat(offset + 2 * VERTEX_BYTES), d = vertices.getFloat(offset + 3 * VERTEX_BYTES);
        // Opposite vertices can round in different directions. Try nearby centers and require the original bits.
        float first = (float) (((double) a + c) / 2);
        float second = (float) (((double) b + d) / 2);
        for (float center : new float[] {first, second, Math.nextDown(first), Math.nextUp(first), Math.nextDown(second), Math.nextUp(second)}) {
            float firstOffset = fitOffset(center, a, c);
            if (!Float.isFinite(firstOffset)) continue;
            float secondOffset = fitOffset(center, b, d);
            if (Float.isFinite(secondOffset)) return new Axis(center, firstOffset, secondOffset);
        }
        if (requireExact) return null;
        return new Axis((float) (((double) a + b + c + d) / 4),
                (float) (((double) a - c) / 2), (float) (((double) b - d) / 2));
    }

    private static float fitOffset(float center, float first, float second) {
        float midpoint = (float) (((double) first - second) / 2);
        for (float offset : new float[] {midpoint, first - center, center - second}) {
            if (Float.floatToRawIntBits(center + offset) == Float.floatToRawIntBits(first)
                    && Float.floatToRawIntBits(center - offset) == Float.floatToRawIntBits(second)) return offset;
        }
        return Float.NaN;
    }

    private static boolean finite(int bits) {
        return Float.isFinite(Float.intBitsToFloat(bits));
    }
}
