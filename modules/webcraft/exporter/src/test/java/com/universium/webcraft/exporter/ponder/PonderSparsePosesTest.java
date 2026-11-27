package com.universium.webcraft.exporter.ponder;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PonderSparsePosesTest {
    @Test
    void keepsTheSamePartAndStoresOnlyTheChangedTranslation() {
        var source = ByteBuffer.allocate(262).order(ByteOrder.LITTLE_ENDIAN);
        source.putShort((short) 2);
        for (int start : new int[]{2, 132}) {
            source.put(start, (byte) 32).putShort(start + 32, (short) 4);
            for (int lane : new int[]{34, 50, 66, 82, 98, 114}) source.putFloat(start + lane, 1);
        }
        source.putFloat(132 + 70, 2.75f);
        var packed = ByteBuffer.wrap(PonderSparsePoses.pack(source.array(), 1)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(49, packed.limit());
        assertEquals(1, packed.get());
        assertEquals(2, packed.getShort());
        assertEquals(1, packed.getInt());
        assertEquals(32, packed.get());
        packed.position(41);
        assertEquals(0x80000400, packed.getInt());
        assertEquals(2.75f, packed.getFloat());
        assertSame(source.array(), PonderSparsePoses.pack(source.array(), 3));
    }
}
