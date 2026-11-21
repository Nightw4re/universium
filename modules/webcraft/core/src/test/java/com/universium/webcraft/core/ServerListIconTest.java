package com.universium.webcraft.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ServerListIconTest {
    @Test
    void includesThePngPrefixInTheLimit() {
        assertEquals(95_998, ServerListIcon.png(new byte[71_982]).length());
        assertNull(ServerListIcon.png(new byte[71_983]));
    }

    @Test
    void loadsOnlyABoundedWebpFile(@TempDir Path directory) throws IOException {
        var file = directory.resolve("server-icon.webp");
        assertNull(ServerListIcon.loadWebp(file));
        Files.writeString(file, "Not a WebP");
        assertNull(ServerListIcon.loadWebp(file));
        Files.write(file, new byte[71_983]);
        assertNull(ServerListIcon.loadWebp(file));
        var encoded = "UklGRhwAAABXRUJQVlA4TBAAAAAvAAAAAAfQ0v5H/wMR0f8A";
        Files.write(file, Base64.getDecoder().decode(encoded));
        assertEquals("data:image/webp;base64," + encoded, ServerListIcon.loadWebp(file));
    }
}
