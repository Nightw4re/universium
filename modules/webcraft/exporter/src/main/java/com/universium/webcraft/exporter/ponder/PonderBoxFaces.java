package com.universium.webcraft.exporter.ponder;

import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/** Encodes ordered rectangular faces using local bounds and the renderer's submitted UVs and attributes. */
final class PonderBoxFaces {
    static final int MAX_FACES = 4096;
    private static final int FACE_BYTES = 59;
    private static final int GROUP_BYTES = 50;
    private static final int ENTITY_STRIDE = 36;
    private static final int RUN_HEADER_BYTES = 6;
    private static final int BOX_RUN = 0x8000;
    private static final int RIGID_RUN = 0x4000;

    record Face(int start, float[] matrix, float[] bounds) {}
    record Mixed(byte[] bytes, int version, List<PonderRigidParts.Batch> rigidParts) {}
    private record Run(int quads, ByteBuffer payload, int type) {}

    private PonderBoxFaces() {}

    static Face record(int start, Matrix4fc pose, float x0, float x1, float y0, float y1, float z0, float z1) {
        var matrix = new float[] {pose.m00(), pose.m01(), pose.m02(), pose.m10(), pose.m11(), pose.m12(),
                pose.m20(), pose.m21(), pose.m22(), pose.m30(), pose.m31(), pose.m32()};
        var bounds = new float[] {x0, x1, y0, y1, z0, z1};
        for (float value : matrix) if (!(Math.abs(value) <= 1e6)) return null;
        for (float value : bounds) if (!(Math.abs(value) <= 4096)) return null;
        return new Face(start, matrix, bounds);
    }

    static byte[] encode(ByteBuffer source, int vertexCount, List<Face> faces) {
        return encode(source, vertexCount, faces, false);
    }

    static byte[] encode(ByteBuffer source, int vertexCount, List<Face> faces, boolean block) {
        int stride = block ? 32 : ENTITY_STRIDE;
        if (faces.isEmpty() || faces.size() > MAX_FACES || vertexCount != 4 * faces.size()
                || source.remaining() != vertexCount * stride) return null;
        return encodeRange(source.slice().order(ByteOrder.LITTLE_ENDIAN), faces, block, 0);
    }

    static byte[] encodeMixed(ByteBuffer source, int vertexCount, List<Face> faces) {
        var mixed = encodeMixed(source, vertexCount, faces, List.of());
        return mixed == null ? null : mixed.bytes();
    }

    static Mixed encodeMixed(ByteBuffer source, int vertexCount, List<Face> faces, List<PonderRigidParts.Placement> parts) {
        if (faces.isEmpty() || faces.size() > MAX_FACES || vertexCount <= 0 || vertexCount > MAX_FACES * 4
                || vertexCount % 4 != 0 || source.remaining() != vertexCount * ENTITY_STRIDE) return null;
        var vertices = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        var valid = new ArrayList<Face>(faces.size());
        var scratch = ByteBuffer.allocate(FACE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int next = 0;
        for (var face : faces) {
            if (face.start() < next || face.start() % 4 != 0 || face.start() > vertexCount - 4) return null;
            next = face.start() + 4;
            scratch.clear();
            if (writeFace(scratch, vertices, face, ENTITY_STRIDE, false)) valid.add(face);
        }
        if (valid.isEmpty()) return null;

        var compactRuns = new TreeMap<Integer, Run>();
        for (int first = 0; first < valid.size();) {
            int start = valid.get(first).start();
            int end = first + 1;
            while (end < valid.size() && valid.get(end).start() == start + 4 * (end - first)) end++;
            byte[] payload = encodeRange(vertices, valid.subList(first, end), false, start);
            if (payload == null) return null;
            compactRuns.put(start, new Run(end - first, ByteBuffer.wrap(payload), BOX_RUN));
            first = end;
        }
        var rigidParts = new ArrayList<PonderRigidParts.Batch>();
        for (var placement : parts) {
            int start = placement.start();
            int count = placement.part().count();
            int end = start + count;
            if (start < 0 || start % 4 != 0 || count <= 0 || count % 4 != 0 || count > vertexCount - start) continue;
            var before = compactRuns.floorEntry(start);
            var after = compactRuns.ceilingKey(start);
            if ((before != null && before.getKey() + before.getValue().quads() * 4 > start)
                    || (after != null && after < end)) continue;
            var range = vertices.slice(start * ENTITY_STRIDE, count * ENTITY_STRIDE).order(ByteOrder.LITTLE_ENDIAN);
            var local = new PonderRigidParts.Placement(placement.part(), 0, placement.position(), placement.normal());
            var batch = PonderRigidParts.encode(range, count, List.of(local));
            if (batch == null) continue;
            var bytes = PonderSparsePoses.pack(batch.bytes(), batch.version());
            if (bytes.length + RUN_HEADER_BYTES + 1 >= range.remaining()) continue;
            var payload = ByteBuffer.allocate(1 + bytes.length);
            payload.put((byte) (bytes == batch.bytes() ? batch.version() : 5)).put(bytes).flip();
            compactRuns.put(start, new Run(count / 4, payload, RIGID_RUN));
            rigidParts.add(batch);
        }
        var runs = new ArrayList<Run>();
        next = 0;
        for (var entry : compactRuns.entrySet()) {
            int start = entry.getKey();
            if (next < start && !appendRawRun(runs, vertices, next, start)) return null;
            var run = entry.getValue();
            runs.add(run);
            next = start + run.quads() * 4;
        }
        if (next < vertexCount && !appendRawRun(runs, vertices, next, vertexCount)) return null;

        int size = 2;
        for (var run : runs) size += RUN_HEADER_BYTES + run.payload().remaining();
        if (size >= source.remaining()) return null;
        var packed = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        packed.putShort((short) runs.size());
        for (var run : runs) {
            packed.putShort((short) (run.quads() | run.type()));
            packed.putInt(run.payload().remaining()).put(run.payload());
        }
        return new Mixed(packed.array(), rigidParts.isEmpty() ? 3 : 4, rigidParts);
    }

    private static boolean appendRawRun(List<Run> runs, ByteBuffer vertices, int start, int end) {
        var raw = vertices.slice(start * ENTITY_STRIDE, (end - start) * ENTITY_STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = 0; at < raw.limit(); at += ENTITY_STRIDE) {
            if (!Float.isFinite(raw.getFloat(at)) || !Float.isFinite(raw.getFloat(at + 4))
                    || !Float.isFinite(raw.getFloat(at + 8)) || !Float.isFinite(raw.getFloat(at + 16))
                    || !Float.isFinite(raw.getFloat(at + 20))) return false;
        }
        runs.add(new Run((end - start) / 4, raw, 0));
        return true;
    }

    private static byte[] encodeRange(ByteBuffer vertices, List<Face> faces, boolean block, int start) {
        int stride = block ? 32 : ENTITY_STRIDE;
        int groups = 0;
        float[] previous = null;
        for (var face : faces) {
            if (!Arrays.equals(previous, face.matrix())) groups++;
            previous = face.matrix();
        }
        var packed = ByteBuffer.allocate(2 + groups * GROUP_BYTES + faces.size() * FACE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        packed.putShort((short) groups);
        previous = null;
        int groupStart = 0, faceCount = 0;
        for (int i = 0; i < faces.size(); i++) {
            var face = faces.get(i);
            if (!Arrays.equals(previous, face.matrix())) {
                if (faceCount > 0) packed.putShort(groupStart, (short) faceCount);
                groupStart = packed.position();
                packed.putShort((short) 0);
                for (float lane : face.matrix()) packed.putFloat(lane);
                faceCount = 0;
                previous = face.matrix();
            }
            if (face.start() != start + i * 4 || !writeFace(packed, vertices, face, stride, block)) return null;
            faceCount++;
        }
        packed.putShort(groupStart, (short) faceCount);
        return packed.array();
    }

    private static boolean writeFace(ByteBuffer packed, ByteBuffer vertices, Face face, int stride, boolean block) {
        int base = face.start() * stride;
        int color = vertices.getInt(base + 12);
        int light = vertices.getInt(base + stride - 8);
        // Block faces use the entity format's overlay slot for the second endpoint light.
        int overlayOrLight = block ? light : vertices.getInt(base + 24);
        // Both vertex layouts submit three normal bytes. The fourth byte is alignment padding.
        int normal = vertices.getInt(base + stride - 4) & 0x00ffffff;
        int[] uv = {vertices.getInt(base + 16), vertices.getInt(base + 16), vertices.getInt(base + 20), vertices.getInt(base + 20)};
        int corners = 0, selectors = 0;
        for (int point = 0; point < 4; point++) {
            int at = base + point * stride;
            int corner = matchingCorner(vertices, at, face);
            if (corner < 0 || vertices.getInt(at + 12) != color
                    || (vertices.getInt(at + stride - 4) & 0x00ffffff) != normal) return false;
            corners |= corner << (3 * point);
            int vertexLight = vertices.getInt(at + stride - 8);
            if (block && vertexLight != light) {
                if (overlayOrLight != light && overlayOrLight != vertexLight) return false;
                overlayOrLight = vertexLight;
                corners |= 1 << (12 + point);
            } else if (!block && (vertexLight != light || vertices.getInt(at + 24) != overlayOrLight)) return false;
            for (int axis = 0; axis < 2; axis++) {
                int value = vertices.getInt(at + 16 + 4 * axis), first = axis * 2;
                if (!Float.isFinite(Float.intBitsToFloat(value))) return false;
                if (value == uv[first]) continue;
                if (uv[first + 1] != uv[first] && value != uv[first + 1]) return false;
                uv[first + 1] = value;
                selectors |= 1 << (2 * point + axis);
            }
        }
        for (float lane : face.bounds()) packed.putFloat(lane);
        packed.putShort((short) corners).put((byte) selectors);
        for (int lane : uv) packed.putInt(lane);
        packed.putInt(color).putInt(overlayOrLight).putInt(light).putInt(normal);
        return true;
    }

    private static int matchingCorner(ByteBuffer vertices, int at, Face face) {
        for (int corner = 0; corner < 8; corner++) {
            float x = face.bounds()[corner & 1], y = face.bounds()[2 + (corner >>> 1 & 1)];
            float z = face.bounds()[4 + (corner >>> 2 & 1)];
            int axis = 0;
            while (axis < 3 && vertices.getInt(at + axis * 4) == Float.floatToRawIntBits(
                    PonderRigidParts.transform(face.matrix(), axis, x, y, z, face.matrix()[9 + axis]))) axis++;
            if (axis == 3) return corner;
        }
        return -1;
    }
}
