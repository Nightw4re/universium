package com.universium.webcraft.exporter.ponder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Packs already validated rigid pose rows without changing their order or values. */
final class PonderSparsePoses {
    private static final int POSE_BYTES = 130;
    private static final int PREVIOUS_ROW = Integer.MIN_VALUE;
    private static final int[] IDENTITY_LANES = {34, 50, 66, 82, 98, 114};

    private PonderSparsePoses() {}

    static byte[] pack(byte[] batch, int version) {
        if (version != 1 && version != 2 && version != 4) return batch;
        int stride = POSE_BYTES + (version == 4 ? 64 : version == 2 ? 32 : 0);
        int count = ByteBuffer.wrap(batch).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
        int lanes = 25 + (stride - POSE_BYTES) / 32;
        var defaults = ByteBuffer.allocate(stride).order(ByteOrder.LITTLE_ENDIAN);
        for (int lane : IDENTITY_LANES) defaults.putFloat(lane, 1);
        var packed = ByteBuffer.allocate(3 + count * (stride + 4)).order(ByteOrder.LITTLE_ENDIAN);
        packed.put((byte) version).putShort((short) count);
        for (int row = 0; row < count; row++) {
            int start = 2 + row * stride;
            int mask = difference(batch, start, defaults.array(), 0, lanes);
            int previous = row == 0 ? mask : difference(batch, start, batch, start - stride, lanes);
            if (size(previous, lanes) < size(mask, lanes)) mask = previous | PREVIOUS_ROW;
            packed.putInt(mask);
            for (int lane = 0; lane < lanes; lane++) {
                if ((mask & (1 << lane)) != 0) {
                    int from = boundary(lane), to = boundary(lane + 1);
                    packed.put(batch, start + from, to - from);
                }
            }
        }
        return packed.position() < batch.length ? Arrays.copyOf(packed.array(), packed.position()) : batch;
    }

    private static int difference(byte[] next, int start, byte[] prior, int previous, int lanes) {
        int mask = 0;
        for (int lane = 0; lane < lanes; lane++) {
            int from = boundary(lane), to = boundary(lane + 1);
            if (Arrays.mismatch(next, start + from, start + to, prior, previous + from, previous + to) >= 0) {
                mask |= 1 << lane;
            }
        }
        return mask;
    }

    private static int size(int mask, int lanes) {
        int bytes = 0;
        for (int lane = 0; lane < lanes; lane++) if ((mask & (1 << lane)) != 0) bytes += boundary(lane + 1) - boundary(lane);
        return bytes;
    }

    private static int boundary(int lane) {
        if (lane == 0) return 0;
        return lane <= 25 ? 34 + (lane - 1) * 4 : POSE_BYTES + (lane - 25) * 32;
    }
}
