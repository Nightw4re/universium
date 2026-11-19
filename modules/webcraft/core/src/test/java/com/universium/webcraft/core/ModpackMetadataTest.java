package com.universium.webcraft.core;

import com.universium.webcraft.core.ModpackMetadata.Identity;
import com.universium.webcraft.core.ModpackMetadata.Selection;
import com.universium.webcraft.core.ModpackMetadata.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModpackMetadataTest {
    @TempDir Path instance;

    @Test
    void prefersRunningBccAndUsesOnlyTheRequestedSourceInManualMode() throws IOException {
        write("config/bcc-common.toml", """
                [general]
                modpackName = "All the Mods 10"
                modpackVersion = "8.1"
                """);
        var running = new Identity("All the Mods 10", "8.6");
        var metadata = new ModpackMetadata(instance, () -> Optional.of(running));
        assertEquals(Optional.of(running), metadata.resolve(selection(Source.AUTO)));
        assertEquals(Optional.of(new Identity("All the Mods 10", "8.1")), metadata.resolve(selection(Source.BCC_TOML)));
        assertTrue(metadata.resolve(selection(Source.BCC_JSON)).isEmpty());
    }

    @Test
    void readsTheFtbMetadataInsteadOfThePlaceholderVersionAndRereadsAfterUpgrade() throws IOException {
        write("config/bcc-common.toml", """
                [general]
                modpackName = "FTB Skies 2 - Minecraft 1.21.1"
                modpackVersion = "1.0.0"
                useMetadata = true
                """);
        var metadata = new ModpackMetadata(instance, Optional::empty);
        assertTrue(metadata.resolve(selection(Source.AUTO)).isEmpty());
        write("config/metadata.json", """
                {"name":"FTB Skies 2","version":{"name":"1.25.3"}}
                """);
        assertEquals(Optional.of(new Identity("FTB Skies 2", "1.25.3")), metadata.resolve(selection(Source.AUTO)));
        write("config/metadata.json", """
                {"name":"FTB Skies 2","version":{"name":"1.26.0"}}
                """);
        assertEquals(Optional.of(new Identity("FTB Skies 2", "1.26.0")), metadata.resolve(selection(Source.AUTO)));
    }

    @Test
    void fallsBackToBccJsonWhenTomlIsUnusable() throws IOException {
        write("config/bcc-common.toml", """
                [general]
                modpackName = "CHANGE_ME"
                modpackVersion = "CHANGE_ME"
                """);
        write("config/bcc-common.json", """
                {"modpackName":{"value":"All the Mods 11"},"modpackVersion":{"value":"0.15.0-beta"},
                 "useMetadata":{"value":false}}
                """);
        var metadata = new ModpackMetadata(instance, Optional::empty);
        assertEquals(Optional.of(new Identity("All the Mods 11", "0.15.0-beta")), metadata.resolve(selection(Source.AUTO)));
    }

    @Test
    void readsPackwizAndCustomKeyPathsWithoutUsingAnUnselectedManifest() throws IOException {
        write("manifest.json", """
                {"manifestType":"minecraftModpack","name":"Universum","version":"2.0.3"}
                """);
        var metadata = new ModpackMetadata(instance, Optional::empty);
        assertTrue(metadata.resolve(selection(Source.AUTO)).isEmpty());
        assertEquals(Optional.of(new Identity("Universum", "2.0.3")), metadata.resolve(selection(Source.CURSEFORGE_MANIFEST)));

        write("pack.toml", """
                name = "IUNA Mark II"
                version = "3.10.0"
                pack-format = "packwiz:1.1.0"
                """);
        var iuna = Optional.of(new Identity("IUNA Mark II", "3.10.0"));
        assertEquals(iuna, metadata.resolve(selection(Source.AUTO)));
        assertEquals(iuna, metadata.resolve(new Selection(Source.CUSTOM, "pack.toml", "name", "version")));

        write("config/pack-info.json", """
                {"pack":{"displayName":"Other Pack","release":"01.02"}}
                """);
        assertEquals(Optional.of(new Identity("Other Pack", "01.02")), metadata.resolve(
                new Selection(Source.CUSTOM, "config/pack-info.json", "pack.displayName", "pack.release")));
        assertTrue(metadata.resolve(new Selection(Source.CUSTOM, "config/pack-info.json", "pack.missing", "pack.release")).isEmpty());
        assertTrue(metadata.resolve(new Selection(Source.CUSTOM, instance.resolve("pack.toml").toString(), "name", "version")).isEmpty());
    }

    private void write(String file, String content) throws IOException {
        Path path = instance.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static Selection selection(Source source) {
        return new Selection(source, "", "name", "version");
    }
}
