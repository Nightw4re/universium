package com.universium.webcraft.exporter;

import com.universium.webcraft.api.WebCraftPreviewPack;
import org.glavo.webp.WebPImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
final class MachineRecipeTextureTest {
    private static final String DIRECTORY = "catalog-data/machine-recipes/textures/";

    @TempDir
    Path directory;

    @Test
    void storesOnlyWebpForAnimatedFluidsAndReadsBothArchiveLayouts() throws Exception {
        var first = new PngPixels(2, 2, new int[]{0xff0000ff, 0, 0, 0});
        var second = new PngPixels(2, 2, new int[]{0, 0xff00ff00, 0, 0});
        var frames = List.of(new AnimationFrames.Frame(first, 2), new AnimationFrames.Frame(second, 3));
        var animation = new MachineRecipeMaterials.Texture(AnimatedPng.encode(frames), frames);
        var still = new MachineRecipeMaterials.Texture(first.encode(), List.of());
        var invalid = new PngPixels(0, 1, new int[1]);
        var fallback = new MachineRecipeMaterials.Texture(animation.png(), List.of(
                new AnimationFrames.Frame(invalid, 2), new AnimationFrames.Frame(invalid, 3)));
        var webp = new StaticPreviewWriter(StaticPreviewWriter.Format.WEBP);
        var png = new StaticPreviewWriter(StaticPreviewWriter.Format.PNG);
        String fluid = "a".repeat(64);
        String chemical = "b".repeat(64);
        String pngFluid = "c".repeat(64);
        String staticFluid = "d".repeat(64);
        String fallbackFluid = "e".repeat(64);
        var output = directory.resolve("previews.zip");
        try (var archive = new ExportArchive(output)) {
            archive.finish(writer -> {
                writer.write("format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(US_ASCII));
                animation.write(writer, DIRECTORY + fluid, webp, true);
                animation.write(writer, DIRECTORY + chemical, webp, false);
                animation.write(writer, DIRECTORY + pngFluid, png, true);
                still.write(writer, DIRECTORY + staticFluid, webp, true);
                fallback.write(writer, DIRECTORY + fallbackFluid, webp, true);
            });
            while (!archive.finished()) Thread.sleep(5);
        }

        try (var zip = new ZipFile(output.toFile())) {
            assertNull(zip.getEntry(DIRECTORY + fluid + ".png"));
            assertNotNull(zip.getEntry(DIRECTORY + chemical + ".png"));
            for (String key : List.of(pngFluid, staticFluid, fallbackFluid)) {
                assertNull(zip.getEntry(DIRECTORY + key + ".webp"));
            }
        }
        var pack = new WebCraftPreviewPack(output);
        var icon = pack.readMachineRecipeTexture(fluid);
        assertEquals("image/webp", icon.contentType());
        try (var bytes = new ByteArrayInputStream(icon.bytes())) {
            var image = WebPImage.read(bytes);
            assertTrue(image.isAnimated());
            assertEquals(2, image.getFrames().size());
            assertEquals(250, image.getLoopDurationMillis());
        }
        assertEquals("image/webp", pack.readMachineRecipeTexture(chemical).contentType());
        for (String key : List.of(pngFluid, fallbackFluid)) {
            var pngIcon = pack.readMachineRecipeTexture(key);
            assertEquals("image/png", pngIcon.contentType());
            assertArrayEquals(animation.png(), pngIcon.bytes());
        }
        assertArrayEquals(still.png(), pack.readMachineRecipeTexture(staticFluid).bytes());
    }
}
