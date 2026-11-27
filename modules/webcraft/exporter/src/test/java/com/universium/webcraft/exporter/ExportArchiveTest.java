package com.universium.webcraft.exporter;

import net.minecraft.world.item.ItemStack;
import org.glavo.webp.WebPImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
final class ExportArchiveTest {
    @TempDir
    Path directory;

    @Test
    void archivesLosslessWebpFromRgbaPixels() throws Exception {
        var output = directory.resolve("previews.zip");
        int[] rgba = new int[64 * 64];
        rgba[2] = 0xff332211;
        rgba[3] = 0x80443322;
        rgba[64] = 0x00554433;
        var preview = new StaticPreviewWriter(StaticPreviewWriter.Format.WEBP);
        try (var archive = new ExportArchive(output)) {
            archive.finish(archive.prepare(() -> preview.prepare("key", new PngPixels(64, 64, rgba))));
            while (!archive.finished()) Thread.sleep(5);
        }
        try (var zip = new ZipFile(output.toFile())) {
            assertEquals(1, zip.size());
            var entry = zip.getEntry("previews/key.webp");
            assertEquals(ZipEntry.STORED, entry.getMethod());
            try (var input = zip.getInputStream(entry)) {
                var image = WebPImage.read(input);
                assertEquals(64, image.getWidth());
                assertEquals(64, image.getHeight());
                assertFalse(image.isLossy());
                assertFalse(image.isAnimated());
                var expectedArgb = new int[rgba.length];
                expectedArgb[2] = 0xff112233;
                expectedArgb[3] = 0x80223344;
                expectedArgb[64] = 0x00334455;
                assertArrayEquals(expectedArgb, image.getFirstFrame().getArgbArray());
            }
        }
    }

    @Test
    void writesPreparedWebpPreviewsInSubmissionOrder() throws Exception {
        var output = directory.resolve("previews.zip");
        var preview = new StaticPreviewWriter(StaticPreviewWriter.Format.WEBP);
        try (var archive = new ExportArchive(output)) {
            for (int index = 0; index < 8; index++) {
                int value = index;
                archive.submit(archive.prepare(() -> preview.prepare("item-" + value, animationPixels(value))));
            }
            archive.finish(writer -> {});
            while (!archive.finished()) Thread.sleep(5);
        }
        try (var zip = new ZipFile(output.toFile())) {
            assertEquals(8, zip.size());
            int index = 0;
            for (var entry : java.util.Collections.list(zip.entries())) {
                assertEquals("previews/item-" + index + ".webp", entry.getName());
                try (var input = zip.getInputStream(entry)) {
                    var pixels = WebPImage.read(input).getFirstFrame().getArgbArray();
                    int[] expected = new int[64 * 64];
                    expected[index++] = 0x80443322;
                    assertArrayEquals(expected, pixels);
                }
            }
        }
    }

    @Test
    void retriesAnAnimationWhenTheFirstLoopHasOnlyOneDistinctFrame() throws Exception {
        MinecraftTestBootstrap.initialize();
        var output = directory.resolve("retry.zip");
        var animation = new InventoryAnimationExport(new StaticPreviewWriter(StaticPreviewWriter.Format.PNG), false);
        animation.add("retry", ItemStack.EMPTY, 2);
        try (var archive = new ExportArchive(output)) {
            animation.tick(stack -> animationPixels(0), archive);
            animation.tick(stack -> animationPixels(0), archive);
            assertTrue(animation.hasPending());
            animation.tick(stack -> animationPixels(0), archive);
            animation.tick(stack -> animationPixels(1), archive);
            assertFalse(animation.hasPending());
            archive.finish(writer -> {});
            while (!archive.finished()) Thread.sleep(5);
            assertEquals(1, animation.exported());
        }
        try (var zip = new ZipFile(output.toFile())) {
            assertTrue(zip.getEntry("animations/retry.png").getSize() > 0);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void archivesAllSixteenWebpFramesWithExactPixelsAndTiming(boolean warmUpSprites, boolean skipRepeatReadback) throws Exception {
        MinecraftTestBootstrap.initialize();
        var output = directory.resolve("previews.zip");
        var preview = new StaticPreviewWriter(StaticPreviewWriter.Format.WEBP);
        var animation = new InventoryAnimationExport(preview, warmUpSprites);
        animation.add("key", ItemStack.EMPTY, 32);
        animation.add("still", ItemStack.EMPTY, 2);
        try (var archive = new ExportArchive(output)) {
            if (warmUpSprites) {
                for (int tick = 0; tick < 32; tick++) {
                    animation.tick(stack -> animationPixels(63), archive);
                }
                assertEquals(2, animation.remaining());
            }
            for (int tick = 0; tick < 32; tick++) {
                int frame = tick / 2;
                boolean repeats = tick % 2 == 1;
                animation.tick((stack, slot, first, warmup) ->
                        skipRepeatReadback && (warmup || (repeats && !first)) ? null : animationPixels(frame), archive);
            }
            assertTrue(animation.hasPending());
            for (int tick = 0; tick < (warmUpSprites ? 4 : 2); tick++) {
                animation.tick(stack -> animationPixels(0), archive);
            }
            assertFalse(animation.hasPending());
            archive.finish(archive.prepare(() -> preview.prepare("key", animationPixels(0))));
            while (!archive.finished()) Thread.sleep(5);
            assertEquals(1, animation.exported());
        }
        try (var zip = new ZipFile(output.toFile())) {
            assertEquals(2, zip.size());
            assertNull(zip.getEntry("animations/still.webp"));
            assertNull(zip.getEntry("animations/key.png"));
            try (var input = zip.getInputStream(zip.getEntry("animations/key.webp"))) {
                var image = WebPImage.read(input);
                assertTrue(image.isAnimated());
                assertFalse(image.isLossy());
                assertEquals(0, image.getLoopCount());
                assertEquals(1600, image.getLoopDurationMillis());
                assertEquals(16, image.getFrames().size());
                for (int i = 0; i < 16; i++) {
                    var frame = image.getFrames().get(i);
                    int[] expected = new int[64 * 64];
                    expected[i] = 0x80443322;
                    assertEquals(100, frame.getDurationMillis());
                    assertArrayEquals(expected, frame.getArgbArray());
                }
            }
        }
    }

    @Test
    void leavesThePreviousArchiveWhenWebpEncodingFails() throws Exception {
        var output = previousExport();
        var preview = new StaticPreviewWriter(StaticPreviewWriter.Format.WEBP);
        try (var archive = new ExportArchive(output)) {
            archive.finish(archive.prepare(() -> preview.prepare("invalid", new PngPixels(0, 1, new int[1]))));
            var reported = assertThrows(IOException.class, () -> {
                while (!archive.finished()) Thread.sleep(5);
            });
            assertEquals(StaticPreviewWriter.WebpEncodingException.class, reported.getCause().getClass());
            assertEquals("previous", Files.readString(output));
            assertFalse(hasTemporaryArchive());
        }
    }

    @Test
    void publishesTheCompleteZipAfterFinalization() throws Exception {
        var output = previousExport();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var archive = new ExportArchive(output)) {
            try {
                archive.submit(writer -> writer.write("previews/key.png", new byte[]{1, 2, 3}));
                archive.finish(writer -> {
                    entered.countDown();
                    await(release);
                    writer.write("format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(UTF_8));
                });
                await(entered);
                assertFalse(archive.finished());
                assertEquals("previous", Files.readString(output));
            } finally {
                release.countDown();
            }
            while (!archive.finished()) Thread.sleep(5);
        }
        try (var zip = new ZipFile(output.toFile())) {
            var preview = zip.getEntry("previews/key.png");
            assertEquals(ZipEntry.STORED, preview.getMethod());
            assertEquals(3, preview.getSize());
            assertEquals("webcraft-previews-v1/1.21.1\n",
                    new String(zip.getInputStream(zip.getEntry("format.txt")).readAllBytes(), UTF_8));
        }
        assertFalse(hasTemporaryArchive());
    }

    @Test
    void cancellationDiscardsQueuedWorkAndKeepsThePreviousArchive() throws Exception {
        var output = previousExport();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = new AtomicInteger();
        try (var archive = new ExportArchive(output)) {
            try {
                archive.submit(writer -> {
                    entered.countDown();
                    await(release);
                    writer.write("partial.txt", new byte[]{1});
                });
                await(entered);
                archive.finish(writer -> writes.incrementAndGet());
                archive.close();
                assertEquals("previous", Files.readString(output));
            } finally {
                release.countDown();
            }
            while (hasTemporaryArchive()) Thread.sleep(5);
            assertEquals("previous", Files.readString(output));
            assertEquals(0, writes.get());
        }
    }

    @Test
    void propagatesAWorkerFailureAfterCleaningUp() throws Exception {
        var output = previousExport();
        var failure = new IOException("test write failure");
        try (var archive = new ExportArchive(output)) {
            archive.finish(writer -> { throw failure; });
            var reported = assertThrows(IOException.class, () -> {
                while (!archive.finished()) Thread.sleep(5);
            });
            assertSame(failure, reported.getCause());
            assertEquals("previous", Files.readString(output));
            assertFalse(hasTemporaryArchive());
        }
    }

    @Test
    void capturesTwentyFourAnimationsInTwoSharedTicks(@TempDir Path run) throws Exception {
        MinecraftTestBootstrap.initialize();
        var output = run.resolve("previews.zip");
        var animation = new InventoryAnimationExport(new StaticPreviewWriter(StaticPreviewWriter.Format.PNG), false);
        for (int index = 0; index < 24; index++) animation.add("item-" + index, ItemStack.EMPTY, 2);
        try (var archive = new ExportArchive(output)) {
            animation.tick(stack -> animationPixels(0), archive);
            animation.tick(stack -> animationPixels(1), archive);
            assertFalse(animation.hasPending());
            archive.finish(writer -> {});
            while (!archive.finished()) Thread.sleep(5);
            assertEquals(24, animation.exported());
        }
        try (var zip = new ZipFile(output.toFile())) {
            assertEquals(24, zip.size());
        }
    }

    @Test
    void reservesQueueSpaceBeforeStartingAnAnimationSoSubsequentTicksAreCaptured() throws Exception {
        MinecraftTestBootstrap.initialize();
        var output = directory.resolve("previews.zip");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var animation = new InventoryAnimationExport(new StaticPreviewWriter(StaticPreviewWriter.Format.PNG), false);
        var captures = new AtomicInteger();
        try (var archive = new ExportArchive(output)) {
            try {
                archive.submit(writer -> {
                    entered.countDown();
                    await(release);
                });
                await(entered);
                while (archive.hasRoom(2)) archive.submit(writer -> {});
                animation.add("first", ItemStack.EMPTY, 2);
                animation.add("second", ItemStack.EMPTY, 2);
                animation.tick(stack -> pixels(captures.incrementAndGet()), archive);
                assertEquals(1, captures.get());
                animation.tick(stack -> pixels(captures.incrementAndGet()), archive);
                assertEquals(2, captures.get());
                assertFalse(archive.hasRoom(1));
                animation.tick(stack -> pixels(captures.incrementAndGet()), archive);
                assertEquals(2, captures.get());
            } finally {
                release.countDown();
            }
            while (!archive.hasRoom(2)) Thread.sleep(5);
            animation.tick(stack -> pixels(captures.incrementAndGet()), archive);
            animation.tick(stack -> pixels(captures.incrementAndGet()), archive);
            assertEquals(4, captures.get());
            assertFalse(animation.hasPending());
            archive.finish(writer -> {});
            while (!archive.finished()) Thread.sleep(5);
            assertEquals(2, animation.exported());
        }
        try (var zip = new ZipFile(output.toFile())) {
            assertEquals(2, zip.size());
            assertTrue(zip.getEntry("animations/first.png").getSize() > 0);
            assertTrue(zip.getEntry("animations/second.png").getSize() > 0);
        }
    }

    private Path previousExport() throws IOException {
        return Files.writeString(directory.resolve("previews.zip"), "previous");
    }

    private boolean hasTemporaryArchive() throws IOException {
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }

    private static PngPixels pixels(int value) {
        return new PngPixels(1, 1, new int[]{0xff000000 | value});
    }

    private static PngPixels animationPixels(int frame) {
        var rgba = new int[64 * 64];
        rgba[frame] = 0x80223344;
        return new PngPixels(64, 64, rgba);
    }
}
