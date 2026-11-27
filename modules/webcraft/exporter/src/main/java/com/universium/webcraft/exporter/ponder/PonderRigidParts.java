package com.universium.webcraft.exporter.ponder;

import com.google.common.hash.Hashing;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/** Encodes complete entity draws as reusable local geometry and ordered poses. */
final class PonderRigidParts {
    private static final int DRAW_VERTEX = 36;
    private static final int PART_VERTEX = 24;
    private static final int POSE_BYTES = 130;
    private static final int DIGEST_BYTES = 32;
    private static final int ATTRIBUTE_BYTES = 12;
    private static final int MAX_PARTS = 512;
    private static final float[] IDENTITY_POSITION = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
    private static final float[] IDENTITY_NORMAL = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    static final int MAX_PART_VERTICES = 4096;
    private PonderRigidParts() {}

    record Part(byte[] bytes, byte[] digest, int count, boolean axisNormals, int[] localNormals, int[] normalSlots) {
        static Part localMesh(ByteBuffer source, int count) {
            if (count < 4 || count > MAX_PART_VERTICES || count % 4 != 0 || source.remaining() != count * DRAW_VERTEX) return null;
            var vertices = source.slice().order(ByteOrder.LITTLE_ENDIAN);
            return localMesh(vertices, count, DRAW_VERTEX, 32);
        }

        static Part catnipBlockTemplate(ByteBuffer source, int stride) {
            if (stride != 32 || source.limit() % stride != 0) return null;
            int count = source.limit() / stride;
            if (count < 4 || count > MAX_PART_VERTICES || count % 4 != 0) return null;
            // Catnip keeps its copied template positioned at the end and reads it by absolute offsets.
            return localMesh(source, count, stride, 28);
        }

        private static Part localMesh(ByteBuffer vertices, int count, int stride, int normalOffset) {
            return fromLocal(localMeshBytes(vertices, count, stride, normalOffset), count);
        }

        private static byte[] localMeshBytes(ByteBuffer vertices, int count, int stride, int normalOffset) {
            var local = ByteBuffer.allocate(count * PART_VERTEX).order(ByteOrder.LITTLE_ENDIAN);
            for (int start = 0; start < count * stride; start += stride) {
                for (int field = 0; field < 12; field += 4) local.putFloat(vertices.getFloat(start + field));
                local.putFloat(vertices.getFloat(start + 16)).putFloat(vertices.getFloat(start + 20));
                for (int axis = 0; axis < 3; axis++) local.put(vertices.get(start + normalOffset + axis));
                local.put((byte) 0);
            }
            return local.array();
        }

        static Part fromLocal(byte[] bytes, int count) {
            var local = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (count < 4 || count > MAX_PART_VERTICES || count % 4 != 0 || bytes.length != count * PART_VERTEX) return null;
            var slots = new LinkedHashMap<Integer, Integer>();
            var normalSlots = new int[count];
            boolean onlyAxes = true;
            for (int start = 0; start < bytes.length; start += PART_VERTEX) {
                for (int field = 0; field < 20; field += 4) {
                    if (!Float.isFinite(local.getFloat(start + field))) return null;
                }
                byte nx = bytes[start + 20], ny = bytes[start + 21], nz = bytes[start + 22];
                if (nx == -128 || ny == -128 || nz == -128) return null;
                onlyAxes &= Math.abs(nx) + Math.abs(ny) + Math.abs(nz) == 127
                        && axisComponent(nx) && axisComponent(ny) && axisComponent(nz);
                int key = local.getInt(start + 20) & 0xffffff;
                normalSlots[start / PART_VERTEX] = slots.computeIfAbsent(key, ignored -> slots.size());
            }
            int[] normals = slots.keySet().stream().mapToInt(Integer::intValue).toArray();
            return new Part(bytes, Hashing.sha256().hashBytes(bytes).asBytes(), count, onlyAxes, normals, normalSlots);
        }

        private static boolean axisComponent(byte value) {
            return value == 0 || value == 127 || value == -127;
        }
    }

    record Placement(Part part, int start, float[] position, float[] normal) {}

    record Attributes(byte[] bytes, byte[] digest) {}
    record Normals(byte[] bytes, byte[] digest) {}
    record Batch(byte[] bytes, int version, List<Part> parts, List<Attributes> attributes, List<Normals> normals) {}
    enum Fallback {
        NO_PARTS, BUFFER_LIMIT, UNCOVERED_VERTICES, INVALID_POSE, POSITION, TEXTURE_COORDINATES,
        INVALID_NORMAL, NORMAL_MAPPING_CONFLICT, ENCODED_SIZE
    }
    private record EncodedPlacement(Placement placement, int color, int overlay, int light, Attributes attributes, Normals normals) {}
    private record CompletePlacements(List<Placement> placements, int residualBytes) {}

    static Placement placement(Part part, int start, PoseStack.Pose pose, boolean usePoseNormals) {
        var normals = pose.normal().get(new float[9]);
        if (usePoseNormals) {
            // Native cuboids use PoseStack's normalization rules. Their local normals are signed unit axes.
            var normal = new Vector3f();
            for (int axis = 0; axis < 3; axis++) {
                pose.transformNormal(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0, normal);
                normals[axis * 3] = normal.x;
                normals[axis * 3 + 1] = normal.y;
                normals[axis * 3 + 2] = normal.z;
            }
        }
        return new Placement(part, start, position(pose.pose()), normals);
    }

    static Placement catnipPlacement(Part part, int start, PoseStack.Pose pose, PoseStack.Pose local, boolean fullNormals) {
        var poseMatrix = new Matrix4f(pose.pose()).mul(local.pose());
        var normalMatrix = fullNormals ? new Matrix3f(pose.normal()).mul(local.normal()) : local.normal();
        return new Placement(part, start, position(poseMatrix), normalMatrix.get(new float[9]));
    }

    private static float[] position(Matrix4fc matrix) {
        var full = matrix.get(new float[16]);
        var position = new float[12];
        for (int column = 0; column < 4; column++) System.arraycopy(full, column * 4, position, column * 3, 3);
        return position;
    }

    static Batch encode(ByteBuffer source, int vertexCount, List<Placement> placements) {
        return encode(source, vertexCount, placements, reason -> {});
    }

    static Batch encode(ByteBuffer source, int vertexCount, List<Placement> placements, Consumer<Fallback> fallback) {
        if (placements.isEmpty()) return reject(fallback, Fallback.NO_PARTS);
        if (placements.size() > MAX_PARTS || source.remaining() != (long) vertexCount * DRAW_VERTEX) {
            return reject(fallback, Fallback.BUFFER_LIMIT);
        }
        var vertices = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        if (!orderedPlacements(vertexCount, placements)) return reject(fallback, Fallback.UNCOVERED_VERTICES);
        var matching = new ArrayList<Placement>(placements.size());
        Fallback firstMismatch = null;
        for (var placement : placements) {
            var mismatch = !bounded(placement.position()) || !bounded(placement.normal())
                    ? Fallback.INVALID_POSE : geometryMismatch(vertices, placement);
            if (mismatch == null) matching.add(placement);
            else if (firstMismatch == null) firstMismatch = mismatch;
        }
        var complete = completePlacements(vertices, vertexCount, matching);
        if (complete == null) {
            return reject(fallback, firstMismatch == null ? Fallback.UNCOVERED_VERTICES : firstMismatch);
        }
        var grouped = complete.placements();
        var encoded = new ArrayList<EncodedPlacement>(grouped.size());
        var parts = new ArrayList<Part>(grouped.size());
        var attributeAssets = new ArrayList<Attributes>();
        var normalAssets = new ArrayList<Normals>();
        int externalBytes = complete.residualBytes();
        for (var placement : grouped) {
            int count = placement.part().count();
            int[] submitted = submittedNormals(vertices, placement, fallback);
            if (submitted == null) return null;
            Normals normals = null;
            if (!matchesPredictedNormals(placement, submitted)) {
                normals = normalPalette(submitted);
                normalAssets.add(normals);
                externalBytes += normals.bytes().length;
            }
            int start = placement.start() * DRAW_VERTEX;
            int color = vertices.getInt(start + 12), overlay = vertices.getInt(start + 24), light = vertices.getInt(start + 28);
            Attributes attributes = null;
            if (!uniformAttributes(vertices, start, count, color, overlay, light)) {
                attributes = attributes(vertices, start, count);
                attributeAssets.add(attributes);
                externalBytes += attributes.bytes().length;
                color = overlay = light = 0;
            }
            encoded.add(new EncodedPlacement(placement, color, overlay, light, attributes, normals));
            parts.add(placement.part());
        }
        boolean usesNormalPalettes = !normalAssets.isEmpty();
        boolean writesAttributes = usesNormalPalettes || !attributeAssets.isEmpty();
        int version = usesNormalPalettes ? 4 : writesAttributes ? 2 : 1;
        int rowBytes = POSE_BYTES + (writesAttributes ? DIGEST_BYTES : 0) + (usesNormalPalettes ? DIGEST_BYTES : 0);
        int size = 2 + grouped.size() * rowBytes;
        if (size + externalBytes >= vertices.remaining()) return reject(fallback, Fallback.ENCODED_SIZE);
        var document = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        document.putShort((short) grouped.size());
        var inline = new byte[DIGEST_BYTES];
        for (var row : encoded) {
            var placement = row.placement();
            document.put(placement.part().digest()).putShort((short) placement.part().count());
            for (float value : placement.position()) document.putFloat(value);
            for (float value : placement.normal()) document.putFloat(value);
            document.putInt(row.color()).putInt(row.overlay()).putInt(row.light());
            if (writesAttributes) document.put(row.attributes() == null ? inline : row.attributes().digest());
            if (usesNormalPalettes) document.put(row.normals() == null ? inline : row.normals().digest());
        }
        return new Batch(document.array(), version, parts, attributeAssets, normalAssets);
    }

    private static boolean orderedPlacements(int vertexCount, List<Placement> placements) {
        if (vertexCount % 4 != 0) return false;
        int next = 0;
        for (var placement : placements) {
            int start = placement.start(), count = placement.part().count();
            if (start < next || start % 4 != 0 || start > vertexCount || count > vertexCount - start) return false;
            next = start + count;
        }
        return true;
    }

    /** Completes ordered draws that already have substantial reusable geometry. */
    private static CompletePlacements completePlacements(ByteBuffer vertices, int vertexCount, List<Placement> placements) {
        int uncovered = vertexCount;
        for (var placement : placements) uncovered -= placement.part().count();
        if (uncovered == 0) return new CompletePlacements(placements, 0);
        if (uncovered > MAX_PART_VERTICES || uncovered * 2 >= vertexCount) return null;

        var complete = new ArrayList<Placement>(placements.size() + 1);
        int next = 0;
        for (var placement : placements) {
            if (next < placement.start() && !appendResidual(vertices, complete, next, placement.start())) return null;
            complete.add(placement);
            next = placement.start() + placement.part().count();
        }
        if (next < vertexCount && !appendResidual(vertices, complete, next, vertexCount)) return null;
        return complete.size() <= MAX_PARTS ? new CompletePlacements(complete, uncovered * PART_VERTEX) : null;
    }

    private static boolean appendResidual(ByteBuffer vertices, List<Placement> complete, int start, int end) {
        int count = end - start;
        var raw = vertices.slice(start * DRAW_VERTEX, count * DRAW_VERTEX);
        var origin = IDENTITY_POSITION.clone();
        var local = Part.localMeshBytes(raw.order(ByteOrder.LITTLE_ENDIAN), count, DRAW_VERTEX, 32);
        var shifted = shiftResidual(local, origin);
        var part = Part.fromLocal(shifted == null ? local : shifted, count);
        if (part == null) return false;
        var placement = new Placement(part, start, shifted == null ? IDENTITY_POSITION : origin, IDENTITY_NORMAL);
        if (shifted == null && geometryMismatch(vertices, placement) != null) return false;
        complete.add(placement);
        return true;
    }

    private static byte[] shiftResidual(byte[] bytes, float[] origin) {
        var original = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int axis = 0; axis < 3; axis++) origin[9 + axis] = original.getFloat(axis * 4);
        if (!bounded(origin) || (origin[9] == 0 && origin[10] == 0 && origin[11] == 0)) {
            return null;
        }
        var local = ByteBuffer.wrap(bytes.clone()).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = 0; at < local.limit(); at += PART_VERTEX) {
            float x = original.getFloat(at) - origin[9];
            float y = original.getFloat(at + 4) - origin[10];
            float z = original.getFloat(at + 8) - origin[11];
            for (int axis = 0; axis < 3; axis++) {
                float restored = transform(origin, axis, x, y, z, origin[9 + axis]);
                if (Float.floatToRawIntBits(restored) != original.getInt(at + axis * 4)) {
                    return null;
                }
            }
            local.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z);
        }
        return local.array();
    }

    private static Batch reject(Consumer<Fallback> report, Fallback reason) {
        report.accept(reason);
        return null;
    }

    private static Fallback geometryMismatch(ByteBuffer vertices, Placement placement) {
        var local = ByteBuffer.wrap(placement.part().bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < placement.part().count(); i++) {
            int original = (placement.start() + i) * DRAW_VERTEX, neutral = i * PART_VERTEX;
            float x = local.getFloat(neutral), y = local.getFloat(neutral + 4), z = local.getFloat(neutral + 8);
            for (int axis = 0; axis < 3; axis++) {
                float position = transform(placement.position(), axis, x, y, z, placement.position()[9 + axis]);
                if (!Float.isFinite(position) || vertices.getInt(original + axis * 4) != Float.floatToRawIntBits(position)) {
                    return Fallback.POSITION;
                }
            }
            if (vertices.getInt(original + 16) != local.getInt(neutral + 12)
                    || vertices.getInt(original + 20) != local.getInt(neutral + 16)) return Fallback.TEXTURE_COORDINATES;
        }
        return null;
    }

    private static int[] submittedNormals(ByteBuffer vertices, Placement placement, Consumer<Fallback> fallback) {
        var part = placement.part();
        int[] submitted = new int[part.localNormals().length];
        Arrays.fill(submitted, -1);
        for (int vertex = 0; vertex < part.count(); vertex++) {
            int start = (placement.start() + vertex) * DRAW_VERTEX + 32;
            int value = vertices.getInt(start) & 0xffffff;
            if ((byte) value == -128 || (byte) (value >>> 8) == -128 || (byte) (value >>> 16) == -128) {
                fallback.accept(Fallback.INVALID_NORMAL);
                return null;
            }
            int slot = part.normalSlots()[vertex];
            if (submitted[slot] != -1 && submitted[slot] != value) {
                fallback.accept(Fallback.NORMAL_MAPPING_CONFLICT);
                return null;
            }
            submitted[slot] = value;
        }
        return submitted;
    }

    private static boolean matchesPredictedNormals(Placement placement, int[] submitted) {
        if (!placement.part().axisNormals()) return false;
        for (int slot = 0; slot < submitted.length; slot++) {
            int local = placement.part().localNormals()[slot];
            float nx = (byte) local / 127f, ny = (byte) (local >>> 8) / 127f, nz = (byte) (local >>> 16) / 127f;
            for (int axis = 0; axis < 3; axis++) {
                float normal = transform(placement.normal(), axis, nx, ny, nz, 0);
                if ((byte) (submitted[slot] >>> (axis * 8)) != packNormal(normal)) return false;
            }
        }
        return true;
    }

    private static Normals normalPalette(int[] submitted) {
        byte[] bytes = new byte[submitted.length * 3];
        for (int slot = 0; slot < submitted.length; slot++) {
            int value = submitted[slot];
            for (int axis = 0; axis < 3; axis++) bytes[slot * 3 + axis] = (byte) (value >>> (axis * 8));
        }
        return new Normals(bytes, Hashing.sha256().hashBytes(bytes).asBytes());
    }

    private static boolean uniformAttributes(ByteBuffer vertices, int start, int count, int color, int overlay, int light) {
        for (int offset = start + DRAW_VERTEX; offset < start + count * DRAW_VERTEX; offset += DRAW_VERTEX) {
            if (vertices.getInt(offset + 12) != color || vertices.getInt(offset + 24) != overlay
                    || vertices.getInt(offset + 28) != light) return false;
        }
        return true;
    }

    private static Attributes attributes(ByteBuffer vertices, int start, int count) {
        var records = ByteBuffer.allocate(count * ATTRIBUTE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = start; offset < start + count * DRAW_VERTEX; offset += DRAW_VERTEX) {
            records.putInt(vertices.getInt(offset + 12)).putInt(vertices.getInt(offset + 24)).putInt(vertices.getInt(offset + 28));
        }
        byte[] bytes = records.array();
        return new Attributes(bytes, Hashing.sha256().hashBytes(bytes).asBytes());
    }

    static float transform(float[] matrix, int axis, float x, float y, float z, float translation) {
        return matrix[axis] * x + (matrix[3 + axis] * y + (matrix[6 + axis] * z + translation));
    }

    private static byte packNormal(float value) {
        return (byte) (Math.clamp(value, -1f, 1f) * 127f);
    }

    private static boolean bounded(float[] matrix) {
        for (float value : matrix) if (!(Math.abs(value) <= 1e6)) return false;
        return true;
    }
}
