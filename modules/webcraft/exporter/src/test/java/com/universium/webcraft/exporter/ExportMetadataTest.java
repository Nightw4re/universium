package com.universium.webcraft.exporter;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ExportMetadataTest {
    @Test
    void readsCurseForgeIdentityAndOmitsOtherManifestData(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("manifest.json"), """
                {"manifestType":"minecraftModpack","name":"All the Mods 10","version":"8.1","files":[]}
                """);
        assertEquals(JsonParser.parseString("""
                {"version":1,"modpack":{"name":"All the Mods 10","version":"8.1"}}
                """), ExportMetadata.read(directory));
    }

    @Test
    void prefersExplicitIdentityAndFallsBackWhenItIsMalformed(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("manifest.json"), """
                {"manifestType":"minecraftModpack","name":"Installed Pack","version":"2.0"}
                """);
        var override = directory.resolve("webcraft-modpack.json");
        Files.writeString(override, """
                {"name":" Export Profile ","version":"custom-3"}
                """);
        assertEquals("Export Profile", ExportMetadata.read(directory).getAsJsonObject("modpack").get("name").getAsString());

        Files.writeString(override, "{");
        assertEquals("Installed Pack", ExportMetadata.read(directory).getAsJsonObject("modpack").get("name").getAsString());
    }

    @Test
    void omitsUnknownIdentity(@TempDir Path directory) throws IOException {
        assertNull(ExportMetadata.read(directory));
        Files.writeString(directory.resolve("manifest.json"), """
                {"name":"Unrelated Manifest","version":"1.0"}
                """);
        assertNull(ExportMetadata.read(directory));
        Files.writeString(directory.resolve("manifest.json"), """
                {"manifestType":"minecraftModpack","name":"Incomplete","version":" "}
                """);
        assertNull(ExportMetadata.read(directory));
    }
}
