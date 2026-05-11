package com.universium.webcraft.staticdelivery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticDeliveryTest {
    @Test
    void defaultStaticFilesExist() throws IOException {
        Path root = resolveWebrootRoot();

        assertTrue(Files.isRegularFile(root.resolve("index.html")));
        assertTrue(Files.isRegularFile(root.resolve("robots.txt")));
        assertTrue(Files.isRegularFile(root.resolve("app.js")));
    }

    private static Path resolveWebrootRoot() {
        Path[] candidates = new Path[] {
                Path.of("webroot"),
                Path.of("modules", "webcraft", "webroot"),
                Path.of("..", "webroot")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("index.html"))) {
                return candidate;
            }
        }
        return candidates[0];
    }
}

