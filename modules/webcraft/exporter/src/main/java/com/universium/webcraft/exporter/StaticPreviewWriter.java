package com.universium.webcraft.exporter;

import com.dylibso.chicory.runtime.Instance;
import com.universium.webcraft.exporter.codec.WebpModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Encodes previews with one codec instance per worker thread. */
final class StaticPreviewWriter {
    enum Format { WEBP, PNG }

    private final Format format;
    private final ThreadLocal<Instance> encoders = ThreadLocal.withInitial(
            () -> Instance.builder(WebpModule.load()).withMachineFactory(WebpModule::create).build());

    StaticPreviewWriter(Format format) {
        this.format = format;
    }

    ExportArchive.Work prepare(String key, PngPixels pixels) throws IOException {
        String name = "previews/" + key + (supportsWebp() ? ".webp" : ".png");
        byte[] bytes = supportsWebp() ? encodeWebp(pixels) : pixels.encode();
        return writer -> writer.write(name, bytes);
    }

    boolean writeAnimation(ExportArchive.EntryWriter writer, String key, AnimationFrames animation) throws IOException {
        var frames = animation.frames();
        if (frames.size() < 2) return false;

        String name = "animations/" + key;
        if (supportsWebp()) {
            writer.write(name + ".webp", encodeAnimatedWebp(frames));
        } else {
            writer.write(name + ".png", AnimatedPng.encode(frames));
        }
        return true;
    }

    boolean supportsWebp() {
        return format == Format.WEBP;
    }

    byte[] encodeAnimatedWebp(List<AnimationFrames.Frame> frames) throws IOException {
        return AnimatedWebp.encode(frames, this::encodeWebp);
    }

    byte[] encodeWebp(PngPixels pixels) throws IOException {
        try {
            return encode(encoders.get(), pixels);
        } catch (IOException | LinkageError | RuntimeException exception) {
            encoders.remove();
            throw new WebpEncodingException(exception);
        }
    }

    private byte[] encode(Instance encoder, PngPixels pixels) throws IOException {
        // Minecraft packs RGBA as ABGR integers. In little-endian order these are the original RGBA bytes.
        var rgba = ByteBuffer.allocate(pixels.rgba().length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        rgba.asIntBuffer().put(pixels.rgba());
        var free = encoder.export("free");
        int input = (int) encoder.export("malloc").apply(rgba.capacity())[0];
        if (input == 0) throw new IOException("Could not allocate the WebP input buffer");
        try {
            encoder.memory().write(input, rgba.array());
            long result = encoder.export("encode").apply(input, pixels.width(), pixels.height())[0];
            if (result == 0) throw new IOException("Could not encode the WebP preview");
            int output = (int) result;
            try {
                return encoder.memory().readBytes(output, (int) (result >>> 32));
            } finally {
                free.apply(output);
            }
        } finally {
            free.apply(input);
        }
    }

    static final class WebpEncodingException extends IOException {
        WebpEncodingException(Throwable cause) {
            super("Could not encode WebP. Select PNG to export without WebP.", cause);
        }
    }
}
