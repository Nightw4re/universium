package com.universium.webcraft.catalog;

import com.google.gson.JsonParser;
import com.universium.webcraft.api.WebCraftPreviewPack;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class ItemCatalogBarsTest {
    @Test
    void projectsDefaultAndVariantBarsIndependently(@TempDir Path directory) throws IOException {
        var archive = directory.resolve("previews.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            addEntry(zip, "format.txt", "webcraft-previews-v1/1.21.1\n");
            addEntry(zip, "catalog.json", """
                    [{"id":"example:tank","bar":{"width":0,"color":15660768},"variants":[
                       {"id":"state/full","bar":{"width":13,"color":2169651},
                        "options":[{"axis":"state","value":"full","label":"Full"}]},
                       {"id":"state/hidden","options":[{"axis":"state","value":"hidden","label":"Hidden"}]},
                       {"id":"state/invalid","bar":{"width":14,"color":2169651},
                        "options":[{"axis":"state","value":"invalid","label":"Invalid"}]}]},
                     {"id":"example:empty","bar":{"width":1,"color":"#fff"}}]
                    """);
        }

        var catalog = new ItemCatalog(Set.of(ResourceLocation.parse("example:tank"), ResourceLocation.parse("example:empty")),
                new WebCraftPreviewPack(archive));
        var items = catalog.items(null).body().getAsJsonObject().getAsJsonArray("items");
        assertFalse(items.get(0).getAsJsonObject().has("bar"));
        var emptyBar = JsonParser.parseString("{\"width\":0,\"color\":15660768}");
        assertEquals(emptyBar, items.get(1).getAsJsonObject().get("bar"));

        var tank = catalog.detail("id=example%3Atank").body().getAsJsonObject();
        assertEquals(emptyBar, tank.get("bar"));
        var variants = tank.getAsJsonArray("variants");
        assertEquals(JsonParser.parseString("{\"width\":13,\"color\":2169651}"),
                variants.get(0).getAsJsonObject().get("bar"));
        assertFalse(variants.get(1).getAsJsonObject().has("bar"));
        assertFalse(variants.get(2).getAsJsonObject().has("bar"));
    }

    private static void addEntry(ZipOutputStream zip, String path, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
