package com.universium.webcraft.runtime;

import com.universium.webcraft.config.webcraftConfig;
import com.universium.webcraft.playerinfo.PlayerInfoItem;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class webcraftPreviewCacheTest {
    @Test
    void generatesAndCachesItemPreviewPng() throws Exception {
        Path tempRoot = Files.createTempDirectory("webcraft-preview-cache");
        Path resourceRoot = tempRoot.resolve("packs");
        Path packDir = resourceRoot.resolve("pack-a");
        Path texturesDir = packDir.resolve("assets/minecraft/textures/item");
        Files.createDirectories(texturesDir);
        writePng(texturesDir.resolve("test_item.png"), 8, 8, new Color(255, 0, 0, 255));

        Path listFile = tempRoot.resolve("resourcepacks.txt");
        Files.writeString(listFile, "pack-a\n");

        webcraftConfig config = config(resourceRoot, listFile, tempRoot.resolve("webroot"));
        webcraftResourcePackRegistry registry = new webcraftResourcePackRegistry(config);
        webcraftPreviewCache cache = new webcraftPreviewCache(registry, tempRoot.resolve("cache"));

        cache.warm();
        byte[] preview = cache.resolvePreview("item/test_item.png");

        assertNotNull(preview);
        assertTrue(preview.length > 0);
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(preview));
        assertNotNull(image);
        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
        assertTrue(Files.isRegularFile(tempRoot.resolve("cache").resolve("item/test_item.png")));
    }

    @Test
    void previewDiffersFromSourceTextureForGrassBlock() throws Exception {
        Path tempRoot = Files.createTempDirectory("webcraft-preview-grass");
        Path resourceRoot = tempRoot.resolve("packs");
        Path packDir = resourceRoot.resolve("pack-a");
        Path itemTexturesDir = packDir.resolve("assets/minecraft/textures/item");
        Path blockTexturesDir = packDir.resolve("assets/minecraft/textures/block");
        Files.createDirectories(itemTexturesDir);
        Files.createDirectories(blockTexturesDir);
        writeGrassBlockPng(blockTexturesDir.resolve("grass_block.png"));

        Path listFile = tempRoot.resolve("resourcepacks.txt");
        Files.writeString(listFile, "pack-a\n");

        webcraftConfig config = config(resourceRoot, listFile, tempRoot.resolve("webroot"));
        webcraftResourcePackRegistry registry = new webcraftResourcePackRegistry(config);
        webcraftPreviewCache cache = new webcraftPreviewCache(registry, tempRoot.resolve("cache"));

        cache.warm();
        byte[] source = registry.resolveTexture("block/grass_block.png");
        byte[] preview = cache.resolvePreview("block/grass_block.png");

        assertNotNull(source);
        assertNotNull(preview);
        assertTrue(preview.length > 0);
        assertTrue(source.length > 0);
        assertEquals(64, ImageIO.read(new java.io.ByteArrayInputStream(preview)).getWidth());
        assertEquals(64, ImageIO.read(new java.io.ByteArrayInputStream(preview)).getHeight());
        assertTrue(!java.util.Arrays.equals(source, preview), "Preview must not be byte-identical to the source texture");
    }

    @Test
    void previewUrlUsesPreviewPathWhenPresent() {
        webcraftConfig config = config(Path.of("."), Path.of("resourcepacks.txt"), Path.of("../webroot"));
        webcraftResourcePackRegistry registry = new webcraftResourcePackRegistry(config);
        webcraftPreviewCache cache = new webcraftPreviewCache(registry, Path.of("build").resolve("preview-test-cache"));
        PlayerInfoItem item = new PlayerInfoItem(
                "minecraft:shield",
                1,
                0,
                "Shield",
                Map.of(),
                Map.of(),
                "item",
                "/resources/textures/item/shield.png",
                "left center",
                "200% 100%",
                "no-repeat",
                "/resources/previews/item/shield.png"
        );

        assertEquals("/resources/previews/item/shield.png", cache.previewUrlFor(item));
    }

    private static webcraftConfig config(Path resourceRoot, Path listFile, Path webrootDir) {
        return new webcraftConfig(
                new webcraftConfig.Server(
                        true,
                        true,
                        false,
                        false,
                        "127.0.0.1",
                        8087,
                        "https://example.invalid",
                        "1.21.1",
                        224,
                        "Universium",
                        "Universium Dev Server",
                        "127.0.0.1:25565",
                        "",
                        "",
                        "JKS",
                        webrootDir.toString(),
                        "index.html",
                        true,
                        true,
                        true,
                        32,
                        10,
                        604800,
                        resourceRoot.toString(),
                        listFile.toString(),
                        List.of()
                ),
                new webcraftConfig.Frontend("built_in", "universum"),
                new webcraftConfig.Logging(false, false, "logs/webcraft-access.log", "logs/webcraft-error.log")
        );
    }

    private static void writePng(Path path, int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "PNG", path.toFile());
    }

    private static void writeGrassBlockPng(Path path) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean grass = y < 4;
                boolean dirt = y >= 4;
                int rgb = grass ? new Color(95, 159, 53, 255).getRGB() : new Color(121, 85, 58, 255).getRGB();
                if (dirt && ((x + y) % 5 == 0)) {
                    rgb = new Color(102, 70, 47, 255).getRGB();
                }
                image.setRGB(x, y, rgb);
            }
        }
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "PNG", path.toFile());
    }
}
