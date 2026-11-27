package com.universium.webcraft.quests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class QuestArtworkTest {
    @TempDir
    Path assets;

    @Test
    void resolvesOnlyPngTextureReferencesUnderThePublicAssetRoot() throws Exception {
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jc1sAAAAASUVORK5CYII=";
        var source = assets.resolve("assets/example/textures/item");
        Files.createDirectories(source);
        Files.write(source.resolve("icon.png"), Base64.getDecoder().decode(png));
        Files.writeString(source.resolve("bad.png"), "not a PNG");

        var loader = new QuestArtwork(assets);
        var reference = QuestArtwork.texture("example:item/icon");
        assertNotNull(reference);
        assertEquals("data:image/png;base64," + png, loader.read(reference));
        assertNull(loader.read(QuestArtwork.texture("example:item/bad")));
        assertNull(QuestArtwork.texture("example:textures/../../private"));
        assertNull(QuestArtwork.texture("https://example.com/image.png"));
    }
}
