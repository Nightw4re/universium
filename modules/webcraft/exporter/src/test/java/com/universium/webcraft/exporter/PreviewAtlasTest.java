package com.universium.webcraft.exporter;

import com.google.common.hash.Hashing;
import com.google.gson.JsonParser;
import com.universium.webcraft.api.WebCraftPreviewPack;
import com.universium.webcraft.archive.PreviewAtlasFormat;
import net.minecraft.world.item.ItemStack;
import org.glavo.webp.WebPImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Timeout(30)
final class PreviewAtlasTest {
    @TempDir
    Path directory;

    @Test
    void writesAddressedSheetsAndPreservesStaticFallbacksForAnimations() throws Exception {
        MinecraftTestBootstrap.initialize();
        var output = directory.resolve("atlas-fixture.zip");
        var codec = new StaticPreviewWriter(StaticPreviewWriter.Format.WEBP);
        try (var archive = new ExportArchive(output, codec)) {
            for (int index = 0; index < 102; index++) {
                while (!archive.hasRoom(1)) Thread.sleep(1);
                archive.submit(archive.preparePreview(codec, key(index), capture(index), index >= 100));
            }
            var animations = new InventoryAnimationExport(codec, false);
            animations.add(key(100), ItemStack.EMPTY, 2);
            animations.add(key(101), ItemStack.EMPTY, 2);
            while (!archive.hasRoom(2)) Thread.sleep(1);
            animations.tick(stack -> capture(101), archive);
            animations.tick((stack, slot, first, warmup) -> capture(101 - slot), archive);
            animations.tick(stack -> capture(101), archive);
            animations.tick(stack -> capture(101), archive);
            assertFalse(animations.hasPending());
            while (!archive.hasRoom(1)) Thread.sleep(1);
            archive.finish(writer -> {
                writer.write("format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(UTF_8));
                writer.write("export-diagnostics.json", "{\"selectedSections\":[\"items\"]}".getBytes(UTF_8));
                writer.write("catalog.json", ("[" + item("stone", 0) + "," + item("iron_ingot", 99)
                        + "," + item("clock", 101) + "]").getBytes(UTF_8));
                writer.write("catalog-data/advancement-previews.json", ("{\"minecraft:story/mine_stone\":\""
                        + key(99) + "\"}").getBytes(UTF_8));
                writer.write("catalog-data/recipe-item-previews.json", ("{\"minecraft:cobblestone\":\""
                        + key(100) + "\"}").getBytes(UTF_8));
            });
            while (!archive.finished()) Thread.sleep(1);
        }
        try (var zip = new ZipFile(output.toFile())) {
            var index = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry(PreviewAtlasFormat.INDEX))
                    .readAllBytes(), UTF_8)).getAsJsonObject();
            var hashes = index.getAsJsonArray("atlases");
            var sprites = index.getAsJsonObject("sprites");
            assertEquals(2, hashes.size());
            assertEquals(101, sprites.size());
            assertEquals("[0,0]", sprites.get(key(0)).toString());
            assertEquals("[0,10]", sprites.get(key(10)).toString());
            assertEquals("[0,99]", sprites.get(key(99)).toString());
            assertEquals("[1,0]", sprites.get(key(100)).toString());
            assertFalse(sprites.has(key(101)));
            for (int indexNumber = 0; indexNumber < hashes.size(); indexNumber++) {
                String hash = hashes.get(indexNumber).getAsString();
                byte[] bytes = zip.getInputStream(zip.getEntry(PreviewAtlasFormat.imagePath(hash))).readAllBytes();
                assertEquals(hash, Hashing.sha256().hashBytes(bytes).toString());
                var sheet = WebPImage.read(new ByteArrayInputStream(bytes));
                assertEquals(640, sheet.getWidth());
                assertEquals(640, sheet.getHeight());
                assertFalse(sheet.isLossy());
                assertFalse(sheet.isAnimated());
                if (indexNumber == 1) assertEquals(0, sheet.getFirstFrame().getArgbArray()[640 * 639 + 639]);
            }
            assertNull(zip.getEntry("previews/" + key(100) + ".webp"));
            assertNotNull(zip.getEntry("previews/" + key(101) + ".webp"));
            assertNotNull(zip.getEntry("animations/" + key(101) + ".webp"));
        }
        var pack = new WebCraftPreviewPack(output);
        for (int key : new int[]{0, 10, 99, 100, 100}) {
            assertArgb(pack, key, 0x80112200 | key);
        }
        assertEquals("image/webp", pack.readIconWithFormat(key(101)).contentType());
        assertEquals("image/webp", pack.readAnimationWithFormat(key(101)).contentType());
        assertNull(pack.readIconWithFormat(key(102)));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void refreshesCachedTilesAndChecksSheetHashesAfterReplacement() throws Exception {
        var output = directory.resolve("previews.zip");
        var replacement = directory.resolve("replacement.zip");
        var pack = new WebCraftPreviewPack(output);
        writeOneTile(output, 1, false);
        assertArgb(pack, 0, 0x80112201);
        byte[] callerBytes = pack.readIcon(key(0));
        callerBytes[0] = 0;
        assertArgb(pack, 0, 0x80112201);
        writeOneTile(replacement, 2, false);
        Files.move(replacement, output, StandardCopyOption.REPLACE_EXISTING);
        assertArgb(pack, 0, 0x80112202);
        writeOneTile(replacement, 2, true);
        Files.move(replacement, output, StandardCopyOption.REPLACE_EXISTING);
        assertNull(pack.readIconWithFormat(key(0)));
    }

    private static void writeOneTile(Path output, int color, boolean wrongHash) throws Exception {
        var codec = new StaticPreviewWriter(StaticPreviewWriter.Format.WEBP);
        try (var zip = new ZipOutputStream(Files.newOutputStream(output));
             var atlas = new PreviewAtlasWriter(output.getParent(), codec)) {
            ExportArchive.EntryWriter writer = (name, bytes) -> {
                zip.putNextEntry(new ZipEntry(name));
                if (wrongHash && name.endsWith(".webp")) bytes[bytes.length - 2] ^= 1;
                zip.write(bytes);
                zip.closeEntry();
            };
            writer.write("format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(UTF_8));
            atlas.write(writer, key(0), capture(color), false);
            atlas.finish(writer);
        }
    }

    private static void assertArgb(WebCraftPreviewPack pack, int number, int argb) throws Exception {
        var icon = pack.readIconWithFormat(key(number));
        assertNotNull(icon);
        assertEquals("image/png", icon.contentType());
        var image = ImageIO.read(new ByteArrayInputStream(icon.bytes()));
        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
        assertEquals(argb, image.getRGB(3, 2));
        assertEquals(0, image.getRGB(0, 0));
    }

    private static PngPixels capture(int color) {
        int[] rgba = new int[64 * 64];
        rgba[2 * 64 + 3] = 0x80002211 | (color << 16);
        return new PngPixels(64, 64, rgba);
    }

    private static String key(int index) {
        return "%064x".formatted(index + 1);
    }

    private static String item(String id, int icon) {
        return "{\"id\":\"minecraft:" + id + "\",\"name\":\"" + id + "\",\"previewKey\":\"" + key(icon) + "\"}";
    }
}
