package com.universium.webcraft.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Base64;

final class ServerListIcon {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_LENGTH = 96_000;
    private static final String PNG_PREFIX = "data:image/png;base64,";
    private static final String WEBP_PREFIX = "data:image/webp;base64,";

    private ServerListIcon() {}

    static String png(byte[] bytes) {
        return dataUrl(PNG_PREFIX, bytes);
    }

    static String loadWebp(Path file) {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(maxBytes(WEBP_PREFIX) + 1);
            if (bytes.length <= maxBytes(WEBP_PREFIX) && isWebp(bytes)) {
                return dataUrl(WEBP_PREFIX, bytes);
            }
            LOGGER.warn("Ignoring an invalid or oversized WebCraft WebP server icon: {}", file);
        } catch (NoSuchFileException ignored) {
            // A separate WebP icon is optional.
        } catch (IOException exception) {
            LOGGER.warn("Could not read the WebCraft WebP server icon: {}", file, exception);
        }
        return null;
    }

    private static boolean isWebp(byte[] bytes) {
        if (bytes.length < 20 || !new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                || !new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            return false;
        }
        var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int chunkSize = header.getInt(16);
        return header.getInt(4) == bytes.length - 8
                && chunkSize > 0 && (long) chunkSize + (chunkSize & 1) <= bytes.length - 20
                && switch (new String(bytes, 12, 4, StandardCharsets.US_ASCII)) {
                    case "VP8 ", "VP8L", "VP8X" -> true;
                    default -> false;
                };
    }

    private static String dataUrl(String prefix, byte[] bytes) {
        return bytes.length <= maxBytes(prefix) ? prefix + Base64.getEncoder().encodeToString(bytes) : null;
    }

    private static int maxBytes(String prefix) {
        return (MAX_LENGTH - prefix.length()) / 4 * 3;
    }
}
