package com.universium.webcraft.exporter.ponder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Projects a rigid item's local vertices onto the glint pass's position and UV layout. */
final class PonderRigidGlint {
    static final int MAX_PLACEMENTS = 512;
    private static final int STRIDE = 20;
    private static final int LOCAL_STRIDE = 24;
    private static final int ROW_BYTES = 82;

    record Placement(PonderRigidParts.Part part, int start, float[] position) {}

    private PonderRigidGlint() {}

    static byte[] encode(ByteBuffer source, int vertexCount, List<Placement> placements) {
        if (placements.isEmpty() || placements.size() > MAX_PLACEMENTS
                || source.remaining() != (long) vertexCount * STRIDE) return null;
        var vertices = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        var batch = ByteBuffer.allocate(2 + ROW_BYTES * placements.size()).order(ByteOrder.LITTLE_ENDIAN);
        batch.putShort((short) placements.size());
        int next = 0;
        for (var placement : placements) {
            int count = placement.part().count();
            if (placement.start() != next || count > vertexCount - next || !matches(vertices, placement)) return null;
            batch.put(placement.part().digest()).putShort((short) count);
            for (float value : placement.position()) batch.putFloat(value);
            next += count;
        }
        return next == vertexCount ? batch.array() : null;
    }

    private static boolean matches(ByteBuffer vertices, Placement placement) {
        float[] pose = placement.position();
        if (pose.length != 12) return false;
        for (float value : pose) if (!(Math.abs(value) <= 1e6f)) return false;
        var local = ByteBuffer.wrap(placement.part().bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < placement.part().count(); i++) {
            int source = i * LOCAL_STRIDE, target = (placement.start() + i) * STRIDE;
            float x = local.getFloat(source), y = local.getFloat(source + 4), z = local.getFloat(source + 8);
            for (int axis = 0; axis < 3; axis++) {
                float position = PonderRigidParts.transform(pose, axis, x, y, z, pose[9 + axis]);
                if (!Float.isFinite(position) || vertices.getInt(target + 4 * axis) != Float.floatToRawIntBits(position)) return false;
            }
            if (vertices.getLong(target + 12) != local.getLong(source + 12)) return false;
        }
        return true;
    }
}
