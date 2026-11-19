package com.universium.webcraft.runtime;

import com.universium.webcraft.config.webcraftConfig;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class webcraftResourcePackRegistryTest {
    @Test
    void indexesTexturesAndInfersItemModelFallbacks() throws Exception {
        Path tempRoot = Files.createTempDirectory("webcraft-resource-pack");
        Path resourceRoot = tempRoot.resolve("packs");
        Path packDir = resourceRoot.resolve("pack-a");
        Path texturesDir = packDir.resolve("assets/minecraft/textures/item");
        Path modelsDir = packDir.resolve("assets/minecraft/models/item");
        Files.createDirectories(texturesDir);
        Files.createDirectories(modelsDir);

        writePng(texturesDir.resolve("shield.png"), 8, 8, new Color(255, 255, 255, 255));
        Files.writeString(modelsDir.resolve("shield.json"), """
                {"parent":"builtin/entity"}
                """);
        Files.writeString(modelsDir.resolve("poppy.json"), """
                {"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:block/poppy"}}
                """);

        Path listFile = tempRoot.resolve("resourcepacks.txt");
        Files.writeString(listFile, "pack-a\n");

        webcraftConfig config = new webcraftConfig(
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
                        "../webroot",
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

        webcraftResourcePackRegistry registry = new webcraftResourcePackRegistry(config);
        assertEquals(1, registry.packCount());
        assertNotNull(registry.resolveTexture("item/shield.png"));
        assertEquals("shield", registry.resolveItemRenderMode("minecraft:shield"));
        assertTrue(registry.resolveItemTexturePath("minecraft:shield").contains("shield_base.png") || registry.resolveItemTexturePath("minecraft:shield").contains("shield.png"));
        assertTrue(registry.resolveItemTexturePath("minecraft:poppy").contains("poppy"));
    }

    @Test
    void resolvesBlockTextureAlternates() throws Exception {
        Path tempRoot = Files.createTempDirectory("webcraft-resource-pack-block");
        Path resourceRoot = tempRoot.resolve("packs");
        Path packDir = resourceRoot.resolve("pack-a");
        Path texturesDir = packDir.resolve("assets/minecraft/textures/block");
        Files.createDirectories(texturesDir);

        writePng(texturesDir.resolve("reinforced_deepslate_side.png"), 8, 8, new Color(0, 0, 0, 255));

        Path listFile = tempRoot.resolve("resourcepacks.txt");
        Files.writeString(listFile, "pack-a\n");

        webcraftConfig config = new webcraftConfig(
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
                        "../webroot",
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

        webcraftResourcePackRegistry registry = new webcraftResourcePackRegistry(config);
        assertNotNull(registry.resolveTexture("block/reinforced_deepslate.png"));
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
}
