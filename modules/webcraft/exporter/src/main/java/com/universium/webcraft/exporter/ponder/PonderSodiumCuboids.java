package com.universium.webcraft.exporter.ponder;

import org.joml.Matrix4fc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;

/** Encodes the ordered float additions in Sodium's immediate cuboid renderer. */
final class PonderSodiumCuboids {
    static final int MAX_PARTS = 512;
    private static final int DRAW_VERTEX = 36;
    private static final int PART_VERTEX = 24;
    private static final int ROW_BYTES = 112;
    private static final int[] CORNER_MASK = {0, 1, 3, 2, 4, 5, 7, 6};
    private static final int[] FACE_NORMAL = {0x008100, 0x007f00, 0x00007f, 0x810000, 0x000081, 0x7f0000};
    private static final ClassValue<Optional<SodiumFields>> SODIUM_FIELDS = new ClassValue<>() {
        @Override
        protected Optional<SodiumFields> computeValue(Class<?> type) {
            try {
                return Optional.of(new SodiumFields(type.getField("originX"), type.getField("originY"), type.getField("originZ"),
                        type.getField("sizeX"), type.getField("sizeY"), type.getField("sizeZ"), type.getField("positions"),
                        type.getField("textures"), type.getMethod("shouldDrawFace", int.class)));
            } catch (ReflectiveOperationException | SecurityException exception) {
                return Optional.empty();
            }
        }
    };

    private record SodiumFields(Field originX, Field originY, Field originZ, Field sizeX, Field sizeY, Field sizeZ,
                                Field positions, Field textures, Method drawsFace) {}
    record Model(PonderRigidParts.Part part, float[] origin, float[] size, byte[] corners, byte[] faces) {}
    record Placement(Model model, int start, float[] position) {}
    record SpriteUv(float u0, float u1, float v0, float v1) {
        long expand(long uv) {
            float u = u0 + (u1 - u0) * Float.intBitsToFloat((int) uv);
            float v = v0 + (v1 - v0) * Float.intBitsToFloat((int) (uv >>> 32));
            return Integer.toUnsignedLong(Float.floatToRawIntBits(u)) | (long) Float.floatToRawIntBits(v) << 32;
        }
    }

    private PonderSodiumCuboids() {}

    static Model readModel(Object cuboid) {
        return readModel(cuboid, null);
    }

    static Model readModel(Object cuboid, SpriteUv sprite) {
        var fields = SODIUM_FIELDS.get(cuboid.getClass()).orElse(null);
        if (fields == null) return null;
        try {
            if (!(fields.positions().get(cuboid) instanceof int[] positions)
                    || !(fields.textures().get(cuboid) instanceof long[] textures)) return null;
            var origin = new float[] {fields.originX().getFloat(cuboid), fields.originY().getFloat(cuboid), fields.originZ().getFloat(cuboid)};
            var size = new float[] {fields.sizeX().getFloat(cuboid), fields.sizeY().getFloat(cuboid), fields.sizeZ().getFloat(cuboid)};
            int mask = 0;
            for (int face = 0; face < 6; face++) if (Boolean.TRUE.equals(fields.drawsFace().invoke(cuboid, face))) mask |= 1 << face;
            return model(origin, size, positions, textures, mask, sprite);
        } catch (ReflectiveOperationException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static Model model(float[] origin, float[] size, int[] positions, long[] textures, int faceMask, SpriteUv sprite) {
        if (origin.length != 3 || size.length != 3 || !bounded(origin) || !bounded(size)
                || positions.length != 24 || textures.length != 24 || faceMask < 1 || faceMask > 63) return null;
        var faces = new byte[Integer.bitCount(faceMask)];
        var corners = new byte[faces.length * 4];
        var local = ByteBuffer.allocate(corners.length * PART_VERTEX).order(ByteOrder.LITTLE_ENDIAN);
        int slot = 0;
        for (int face = 0; face < 6; face++) {
            if ((faceMask & 1 << face) == 0) continue;
            faces[slot / 4] = (byte) face;
            for (int vertex = 0; vertex < 4; vertex++) {
                int index = face * 4 + vertex;
                int position = positions[index];
                if (position < 0 || position >= CORNER_MASK.length) return null;
                int corner = CORNER_MASK[position];
                corners[slot++] = (byte) corner;
                local.putFloat(corner & 1).putFloat(corner >>> 1 & 1).putFloat(corner >>> 2 & 1);
                long uv = textures[index];
                local.putLong(sprite == null ? uv : sprite.expand(uv)).putInt(FACE_NORMAL[face]);
            }
        }
        var part = PonderRigidParts.Part.fromLocal(local.array(), corners.length);
        return part == null ? null : new Model(part, origin.clone(), size.clone(), corners, faces);
    }

    static Placement placement(Model model, int start, Matrix4fc pose) {
        var matrix = pose.get(new float[16]);
        var position = new float[12];
        float[] origin = model.origin(), size = model.size();
        for (int row = 0; row < 3; row++) {
            position[row] = matrix[row] * origin[0] + (matrix[row + 4] * origin[1]
                    + (matrix[row + 8] * origin[2] + matrix[row + 12]));
            for (int axis = 0; axis < 3; axis++) position[row + (axis + 1) * 3] = matrix[row + axis * 4] * size[axis];
        }
        return new Placement(model, start, position);
    }

    static byte[] encode(ByteBuffer source, int vertexCount, List<Placement> placements) {
        if (placements.isEmpty() || placements.size() > MAX_PARTS || vertexCount < 4
                || vertexCount > MAX_PARTS * 24 || source.remaining() != vertexCount * DRAW_VERTEX) return null;
        var original = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        var document = ByteBuffer.allocate(2 + ROW_BYTES * placements.size()).order(ByteOrder.LITTLE_ENDIAN);
        document.putShort((short) placements.size());
        int next = 0;
        for (var placement : placements) {
            var model = placement.model();
            int count = model.part().count();
            if (placement.start() != next || count > vertexCount - next || !bounded(placement.position())) return null;
            document.put(model.part().digest()).putShort((short) count);
            for (float value : placement.position()) document.putFloat(value);
            int base = next * DRAW_VERTEX;
            int color = original.getInt(base + 12), overlay = original.getInt(base + 24), light = original.getInt(base + 28);
            if (!writeNormals(original, base, placement, color, overlay, light, document)) return null;
            document.putInt(color).putInt(overlay).putInt(light);
            next += count;
        }
        return next == vertexCount ? document.array() : null;
    }

    private static boolean writeNormals(ByteBuffer original, int base, Placement placement, int color, int overlay, int light,
                                        ByteBuffer document) {
        var model = placement.model();
        var local = ByteBuffer.wrap(model.part().bytes()).order(ByteOrder.LITTLE_ENDIAN);
        int faceIndex = 0;
        for (int face = 0; face < 6; face++) {
            int normal = 0;
            if (faceIndex < model.faces().length && model.faces()[faceIndex] == face) {
                int first = faceIndex++ * 4;
                normal = original.getInt(base + first * DRAW_VERTEX + 32);
                if ((normal >>> 24) != 0) return false;
                for (int i = first; i < first + 4; i++) {
                    int at = base + i * DRAW_VERTEX;
                    if (!matchesPosition(original, at, placement.position(), model.corners()[i])
                            || original.getLong(at + 16) != local.getLong(i * PART_VERTEX + 12)
                            || original.getInt(at + 12) != color || original.getInt(at + 24) != overlay
                            || original.getInt(at + 28) != light || original.getInt(at + 32) != normal) return false;
                }
            }
            document.put((byte) normal).put((byte) (normal >>> 8)).put((byte) (normal >>> 16));
        }
        return true;
    }

    private static boolean matchesPosition(ByteBuffer original, int at, float[] position, int corner) {
        for (int row = 0; row < 3; row++) {
            float expected = position[row];
            for (int axis = 0; axis < 3; axis++) {
                if ((corner & 1 << axis) != 0) expected += position[row + (axis + 1) * 3];
            }
            if (original.getInt(at + row * 4) != Float.floatToRawIntBits(expected)) return false;
        }
        return true;
    }

    private static boolean bounded(float[] values) {
        for (float value : values) if (!(Math.abs(value) <= 1e6f)) return false;
        return true;
    }
}
