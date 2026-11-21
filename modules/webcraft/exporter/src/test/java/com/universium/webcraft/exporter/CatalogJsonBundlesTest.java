package com.universium.webcraft.exporter;

import com.google.gson.JsonParser;
import com.universium.webcraft.api.WebCraftPreviewPack;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
final class CatalogJsonBundlesTest {
    private static final String FORMAT = "webcraft-previews-v1/1.21.1\n";
    private static final String STACK = "catalog-data/stacks/example/nested/item@color%3Ablue%2Fdark.json";
    private static final String TOOLTIP = "catalog-data/tooltips/example/nested/item@color%3Ablue%2Fdark.json";
    private static final String RECIPE = "catalog-data/recipes/example/nested/item.json";
    private static final String TAG = "catalog-data/recipe-tags/example/nested/item.json";
    private static final String MACHINE_ITEM = "catalog-data/machine-recipes/items/example/nested/item.json";
    private static final String MACHINE_FLUID = "catalog-data/machine-recipes/fluids/example/nested/item.json";
    private static final String MACHINE_CHEMICAL = "catalog-data/machine-recipes/chemicals/example/nested/item.json";
    private static final String LARGE_GROUP = "catalog-data/machine-recipes/groups/6.json";
    private static final String GROUP = "catalog-data/machine-recipes/groups/7.json";
    private static final String UNICODE = "{\"text\":\"Příliš žluťoučký kůň\"}";
    private static final String LARGE = "{\"text\":\"" + "x".repeat(32 * 1024) + "\"}";

    @TempDir
    Path directory;

    @Test
    void readsTheSameCatalogDocumentsFromLegacyAndBundledArchives() throws Exception {
        var old = directory.resolve("legacy.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(old))) {
            documents((name, bytes) -> {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(bytes);
                zip.closeEntry();
            });
        }
        var output = directory.resolve("bundled.zip");
        try (var archive = new ExportArchive(output)) {
            archive.finish(CatalogJsonBundlesTest::documents);
            while (!archive.finished()) Thread.sleep(5);
        }
        try (var zip = new ZipFile(output.toFile())) {
            assertNull(zip.getEntry(STACK));
            assertNull(zip.getEntry(GROUP));
            assertNotNull(zip.getEntry(LARGE_GROUP));
            assertNotNull(zip.getEntry("catalog-data/recipes.json"));
            assertNotNull(zip.getEntry("catalog-data/bundles/v1/1.json"));
        }
        verifyDocuments(new WebCraftPreviewPack(old));
        verifyDocuments(new WebCraftPreviewPack(output));
    }

    private static void documents(ExportArchive.EntryWriter writer) throws IOException {
        writer.write("format.txt", FORMAT.getBytes(UTF_8));
        for (String name : new String[]{STACK, TOOLTIP, RECIPE, TAG, MACHINE_ITEM, MACHINE_FLUID, MACHINE_CHEMICAL,
                GROUP, "catalog-data/recipes.json"}) {
            writer.write(name, UNICODE.getBytes(UTF_8));
        }
        writer.write(LARGE_GROUP, LARGE.getBytes(UTF_8));
        byte[] filler = ("{\"text\":\"" + "z".repeat(31 * 1024) + "\"}").getBytes(UTF_8);
        for (int i = 0; i < 5; i++) writer.write("catalog-data/recipes/example/filler" + i + ".json", filler);
    }

    private static void verifyDocuments(WebCraftPreviewPack pack) {
        var id = ResourceLocation.parse("example:nested/item");
        var expected = JsonParser.parseString(UNICODE);
        assertEquals(expected, pack.readCatalogStack(id, "color:blue/dark"));
        assertEquals(expected, pack.readCatalogTooltip(id, "color:blue/dark"));
        assertEquals(expected, pack.readRecipes(id));
        assertEquals(expected, pack.readRecipeTag(id));
        assertEquals(expected, pack.readMachineRecipes(id, false));
        assertEquals(expected, pack.readMachineRecipes(id, true));
        assertEquals(expected, pack.readChemicalRecipes(id));
        assertEquals(expected, pack.readRecipeIndex());
        assertTrue(pack.visitMachineRecipeGroups(Set.of(6, 7), (number, object) -> {
            assertEquals(number == 6 ? JsonParser.parseString(LARGE) : expected, object);
            return true;
        }));
        assertTrue(pack.readRecipes(ResourceLocation.parse("example:missing")).isEmpty());
        assertEquals(31 * 1024, pack.readRecipes(ResourceLocation.parse("example:filler4")).get("text").getAsString().length());
    }
}
