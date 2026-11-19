package com.universium.webcraft.exporter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

final class AnimatedPng {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final List<Frame> frames = new ArrayList<>();

    void addTick(byte[] png) {
        var last = frames.isEmpty() ? null : frames.getLast();
        if (last != null && Arrays.equals(last.png, png)) {
            last.ticks++;
        } else {
            frames.add(new Frame(png));
        }
    }

    byte[] encode() throws IOException {
        if (frames.size() < 2) {
            return null;
        }
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.write(SIGNATURE);
            byte[] header = Arrays.copyOfRange(frames.getFirst().png, 16, 29);
            chunk(out, "IHDR", header);
            chunk(out, "acTL", ByteBuffer.allocate(8).putInt(frames.size()).putInt(0).array());
            int sequence = 0;
            for (var frame : frames) {
                var control = ByteBuffer.allocate(26).putInt(sequence++).put(header, 0, 8)
                        .putInt(0).putInt(0).putShort((short) frame.ticks).putShort((short) 20)
                        .put((byte) 0).put((byte) 0);
                chunk(out, "fcTL", control.array());
                // NativeImage encodes each capture as an RGBA PNG. Reuse its compressed image data.
                var png = ByteBuffer.wrap(frame.png).position(SIGNATURE.length);
                while (png.remaining() >= 12) {
                    int length = png.getInt();
                    int type = png.getInt();
                    if (type == 0x49444154) { // IDAT
                        if (frame == frames.getFirst()) {
                            var data = new byte[length];
                            png.get(data);
                            chunk(out, "IDAT", data);
                        } else {
                            var data = ByteBuffer.allocate(Integer.BYTES + length).putInt(sequence++);
                            data.put(png.slice(png.position(), length));
                            png.position(png.position() + length);
                            chunk(out, "fdAT", data.array());
                        }
                    } else {
                        png.position(png.position() + length);
                    }
                    png.getInt(); // CRC from the source PNG
                }
            }
            chunk(out, "IEND", new byte[0]);
        }
        return bytes.toByteArray();
    }

    private static void chunk(DataOutputStream out, String name, byte[] data) throws IOException {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(type);
        crc.update(data);
        out.writeInt(data.length);
        out.write(type);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }

    private static final class Frame {
        private final byte[] png;
        private int ticks = 1;

        private Frame(byte[] png) {
            this.png = png;
        }
    }
}
