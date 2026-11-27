package com.universium.webcraft.exporter;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.universium.webcraft.exporter.ponder.PonderDefinitionExport;
import com.universium.webcraft.exporter.ponder.PonderExport;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class PreviewExport implements AutoCloseable {
    private final Minecraft minecraft;
    private final ClientLevel level;
    private final HolderLookup.Provider registries;
    private final Set<ExportSection> sections;
    private final List<ItemStack> items;
    private final List<CatalogPreviewVariant> variants = new ArrayList<>();
    private final int catalogStart;
    private final JsonArray catalog = new JsonArray();
    private final JsonObject ingredientPreviews = new JsonObject();
    private final JsonObject advancementPreviews = new JsonObject();
    private final CompletableFuture<List<AdvancementIcon>> advancementIcons;
    private final RecipeExport recipes;
    private final MachineRecipeExport machineRecipes;
    private final List<Phase> phases;
    private final Phase itemPhase;
    private final Map<String, JsonObject> listings = new HashMap<>();
    private final Set<String> exportedKeys = new HashSet<>();
    private final InventoryAnimationExport animations;
    private final DirectInventoryIcons directIcons;
    private final boolean experimentalCuda;
    private InventoryIconRenderer renderer;
    private final Path output;
    private final Path gameDirectory;
    private final ExportArchive archive;
    private final StaticPreviewWriter staticPreviews;
    private final PonderExport ponder;
    private final PonderDefinitionExport ponderDefinitions;
    private final ExportDiagnostics diagnostics = new ExportDiagnostics();
    private Supplier<Iterator<ItemStack>> catalogSource;
    private Iterator<ItemStack> catalogItems;
    private Iterator<ResourceLocation> ingredientItems;
    private Iterator<AdvancementIcon> pendingAdvancementIcons;
    private Iterator<List<CatalogPreviewVariant>> villagerVariants;
    private JsonObject effects;
    private DesignExport designs;
    private int dyedIndex;
    private int processed;
    private int skipped;
    private int directIconsExported;
    private boolean prepared;
    private boolean recipesCaptured;
    private boolean ingredientPreviewsCaptured;
    private boolean advancementPreviewsCaptured;
    private boolean machineRecipesCaptured;
    private boolean ponderCaptured;
    private boolean ponderDefinitionsCaptured;
    private boolean finishRequested;
    private int fullArchiveQueueChecks;

    PreviewExport(Minecraft minecraft, List<ItemStack> inventory, Supplier<Iterator<ItemStack>> catalogSource, Path output,
                  StaticPreviewWriter.Format staticFormat, Set<ExportSection> sections, Set<String> ponderScenes,
                  int ponderAtlasPageSize, boolean experimentalCuda, boolean previewAtlas) {
        this.minecraft = minecraft;
        this.experimentalCuda = experimentalCuda;
        this.level = minecraft.level;
        this.registries = level.registryAccess();
        this.sections = Set.copyOf(sections);
        this.items = new ArrayList<>(inventory);
        this.catalogSource = catalogSource;
        this.catalogStart = catalogSource == null ? -1 : inventory.size();
        dyedIndex = catalogStart;
        prepared = catalogStart < 0;
        boolean exportRecipes = sections.contains(ExportSection.RECIPES);
        recipes = exportRecipes ? new RecipeExport(registries, level.getRecipeManager().getRecipes()) : null;
        this.output = output;
        staticPreviews = new StaticPreviewWriter(staticFormat);
        machineRecipes = exportRecipes ? MachineRecipeExport.start(staticPreviews, diagnostics) : null;
        boolean exportPreviews = sections.contains(ExportSection.ITEMS);
        directIcons = exportPreviews ? new DirectInventoryIcons(minecraft) : null;
        advancementPreviewsCaptured = !exportPreviews || catalogStart < 0;
        advancementIcons = advancementPreviewsCaptured ? null : loadAdvancementIcons(minecraft);
        itemPhase = exportPreviews ? Phase.PREVIEWS : Phase.TOOLTIPS;
        var selectedPhases = new ArrayList<Phase>();
        if (!prepared) selectedPhases.add(Phase.CATALOG);
        if (exportPreviews || sections.contains(ExportSection.TOOLTIPS)) selectedPhases.add(itemPhase);
        if (exportRecipes) selectedPhases.add(Phase.RECIPES);
        ingredientPreviewsCaptured = !exportRecipes || !exportPreviews;
        if (!ingredientPreviewsCaptured) selectedPhases.add(Phase.INGREDIENT_PREVIEWS);
        if (!advancementPreviewsCaptured) selectedPhases.add(Phase.ADVANCEMENT_PREVIEWS);
        if (exportPreviews) selectedPhases.add(Phase.ANIMATIONS);
        if (machineRecipes != null) selectedPhases.add(Phase.MACHINE_RECIPES);
        boolean compactPonder = sections.contains(ExportSection.PONDER_COMPACT);
        if (compactPonder && (sections.contains(ExportSection.PONDER) || sections.contains(ExportSection.PONDER_DEFINITIONS))) {
            throw new IllegalArgumentException("The ponder section cannot be combined with ponder-legacy or ponder-definitions.");
        }
        ponderDefinitions = compactPonder || sections.contains(ExportSection.PONDER_DEFINITIONS) ? startPonderDefinitions(ponderScenes) : null;
        ponder = ponderDefinitions != null || sections.contains(ExportSection.PONDER)
                ? startPonder(ponderScenes, ponderPhase() != Phase.PONDER, compactPonder, ponderAtlasPageSize) : null;
        if (ponderDefinitions != null) selectedPhases.add(Phase.PONDER_DEFINITIONS);
        if (ponder != null) selectedPhases.add(ponderPhase());
        if (sections.contains(ExportSection.DESIGNS)) selectedPhases.add(Phase.DESIGNS);
        selectedPhases.add(Phase.ARCHIVE);
        phases = List.copyOf(selectedPhases);
        animations = new InventoryAnimationExport(staticPreviews, ModList.get().isLoaded("sodium"));
        gameDirectory = minecraft.gameDirectory.toPath();
        archive = new ExportArchive(output, previewAtlas && exportPreviews && staticPreviews.supportsWebp() ? staticPreviews : null);
    }

    private PonderExport startPonder(Set<String> scenes, boolean timelines, boolean compact, int atlasPageSize) {
        if (!ModList.get().isLoaded("ponder")) throw new IllegalArgumentException("Ponder scenes require the Ponder client mod.");
        return new PonderExport(minecraft, scenes, experimentalCuda, timelines, compact, atlasPageSize, files -> archive.submit(writer -> {
            for (var entry : files.entrySet()) writer.write(entry.getKey(), entry.getValue());
        }));
    }

    private PonderDefinitionExport startPonderDefinitions(Set<String> scenes) {
        if (!ModList.get().isLoaded("ponder")) throw new IllegalArgumentException("Ponder definitions require the Ponder client mod.");
        return new PonderDefinitionExport(minecraft.getResourceManager(), minecraft.getLanguageManager().getSelected(), scenes,
                (path, bytes) -> archive.submit(writer -> writer.write(path, bytes)));
    }

    boolean finished() throws IOException {
        return archive.finished();
    }

    boolean captureNext() throws IOException {
        if (minecraft.level != level) throw new IOException("The client world changed during preview export");
        if (finishRequested) return false;
        if (!archive.hasRoom(1)) {
            fullArchiveQueueChecks++;
            return false;
        }
        if (renderer == null && (sections.contains(ExportSection.ITEMS) || sections.contains(ExportSection.DESIGNS))) {
            renderer = new InventoryIconRenderer(minecraft, experimentalCuda);
            return true;
        }
        if (!prepared) {
            prepareNext();
            return true;
        }
        if (processed < items.size() + variants.size()) {
            exportNextStack();
            return true;
        }
        if (recipes != null && !recipesCaptured) {
            recipesCaptured = !recipes.captureNext();
            return true;
        }
        if (!ingredientPreviewsCaptured) {
            captureIngredientPreview();
            return true;
        }
        if (!advancementPreviewsCaptured) return captureAdvancementPreview();
        if (animations.hasPending()) return false;
        if (machineRecipes != null && !machineRecipesCaptured) {
            machineRecipesCaptured = !machineRecipes.captureNext();
            return true;
        }
        if (ponderDefinitions != null && !ponderDefinitionsCaptured) {
            ponderDefinitionsCaptured = !ponderDefinitions.captureNext();
            return true;
        }
        if (ponder != null && !ponderCaptured) {
            ponderCaptured = !ponder.captureNext();
            if (ponderCaptured) ponder.close();
            return true;
        }
        if (sections.contains(ExportSection.DESIGNS)) {
            if (designs == null) {
                designs = new DesignExport(minecraft, registries, renderer, archive);
                return true;
            }
            var capture = designs.captureNext();
            if (capture != DesignExport.Capture.DONE) return capture == DesignExport.Capture.PROGRESSED;
        }
        archive.finish(this::writeDocuments);
        finishRequested = true;
        return false;
    }

    private void prepareNext() {
        if (catalogSource != null) {
            if (sections.contains(ExportSection.ITEMS)) effects = ItemGlintExport.read(minecraft);
            catalogItems = catalogSource.get();
            catalogSource = null;
        } else if (catalogItems != null) {
            if (catalogItems.hasNext()) {
                items.add(catalogItems.next());
            } else {
                catalogItems = null;
                villagerVariants = EasyVillagerVariants.batches(registries);
            }
        } else if (villagerVariants.hasNext()) {
            variants.addAll(villagerVariants.next());
        } else if (dyedIndex < items.size()) {
            variants.addAll(DyedItemVariants.collect(items.get(dyedIndex++)));
        } else {
            prepared = true;
        }
    }

    private void writeDocuments(ExportArchive.EntryWriter writer) throws IOException {
        writer.write("format.txt", "webcraft-previews-v1/1.21.1\n".getBytes(StandardCharsets.US_ASCII));
        var metadata = ExportMetadata.read(gameDirectory);
        if (metadata != null) {
            writer.write("metadata.json", metadata.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (catalogStart >= 0) {
            writer.write("catalog.json", catalog.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (recipes != null) recipes.write(writer);
        if (!ingredientPreviews.isEmpty()) {
            writer.write("catalog-data/recipe-item-previews.json", ingredientPreviews.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!advancementPreviews.isEmpty()) {
            writer.write("catalog-data/advancement-previews.json", advancementPreviews.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (machineRecipes != null) machineRecipes.write(writer);
        if (ponderDefinitions != null) {
            writer.write(PonderDefinitionExport.INDEX,
                    ponderDefinitions.index(ponderPhase() != Phase.PONDER ? ponder.index() : null));
            if (sections.contains(ExportSection.PONDER_COMPACT)) writer.write(PonderExport.COMPACT_MODELS, ponder.compactModels());
        }
        if (sections.contains(ExportSection.PONDER)) {
            writer.write(PonderExport.INDEX, ponder.index().toString().getBytes(StandardCharsets.UTF_8));
        }
        if (effects != null) {
            writer.write("effects.json", effects.toString().getBytes(StandardCharsets.UTF_8));
        }
        diagnostics.write(writer, sections, catalogStart >= 0, catalog.size(), machineRecipes != null && machineRecipes.available(),
                machineRecipes == null ? 0 : machineRecipes.skipped(), ponder == null ? null : ponder.diagnostics());
    }

    private void captureIngredientPreview() throws IOException {
        if (ingredientItems == null) ingredientItems = recipes.ingredientItems();
        if (!ingredientItems.hasNext()) {
            ingredientPreviewsCaptured = true;
            return;
        }
        var id = ingredientItems.next();
        String name = id.toString();
        if (listings.containsKey(name)) return;
        var stack = BuiltInRegistries.ITEM.get(id).getDefaultInstance();
        if (stack.isEmpty()) return;
        var entries = new StackEntries();
        String key = exportPreview(stack, entries, name, null);
        if (key == null) return;
        ingredientPreviews.addProperty(name, key);
        if (!entries.work.isEmpty()) archive.submit(entries::writeAll);
    }

    private static CompletableFuture<List<AdvancementIcon>> loadAdvancementIcons(Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        if (server != null) {
            return server.submit(() -> advancementIcons(server.getAdvancements().tree().nodes()));
        }
        var connection = minecraft.getConnection();
        return CompletableFuture.completedFuture(connection == null ? List.of()
                : advancementIcons(connection.getAdvancements().getTree().nodes()));
    }

    static List<AdvancementIcon> advancementIcons(Collection<AdvancementNode> nodes) {
        var icons = new ArrayList<AdvancementIcon>();
        for (var node : nodes) {
            var holder = node.holder();
            var display = holder.value().display().orElse(null);
            if (display != null && !display.isHidden() && !display.getIcon().isEmpty()
                    && !holder.id().getPath().startsWith("recipes/")) {
                icons.add(new AdvancementIcon(holder.id(), display.getIcon().copy()));
            }
        }
        return List.copyOf(icons);
    }

    private boolean captureAdvancementPreview() throws IOException {
        if (pendingAdvancementIcons == null) {
            if (!advancementIcons.isDone()) return false;
            pendingAdvancementIcons = advancementIcons.join().iterator();
        }
        if (!pendingAdvancementIcons.hasNext()) {
            advancementPreviewsCaptured = true;
            return true;
        }
        var icon = pendingAdvancementIcons.next();
        var entries = new StackEntries();
        String id = icon.id().toString();
        String key = exportPreview(icon.stack(), entries, id, null);
        if (key != null) advancementPreviews.addProperty(id, key);
        if (!entries.work.isEmpty()) archive.submit(entries::writeAll);
        return true;
    }

    record AdvancementIcon(ResourceLocation id, ItemStack stack) {}

    private void exportNextStack() throws IOException {
        var entries = new StackEntries();
        exportNextStack(entries);
        if (!entries.work.isEmpty()) archive.submit(entries::writeAll);
    }

    private void exportNextStack(StackEntries entries) throws IOException {
        int index = processed++;
        if (index >= items.size()) {
            exportVariant(variants.get(index - items.size()), entries);
            return;
        }
        ItemStack stack = items.get(index);
        String id = catalogStart >= 0 && index >= catalogStart ? BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() : null;
        boolean exportPreviews = sections.contains(ExportSection.ITEMS);
        String key = exportPreviews ? exportPreview(stack, entries, id, null) : null;
        if (id != null) {
            JsonObject listing = new JsonObject();
            listing.addProperty("id", id);
            try {
                listing.addProperty("name", stack.getHoverName().getString());
                if (exportPreviews) {
                    listing.addProperty("canGlint", effects != null && stack.isEnchantable() && !stack.hasFoil());
                }
            } catch (RuntimeException exception) {
                listing.addProperty("name", id);
            }
            if (exportPreviews) ItemBarExport.addTo(listing, stack);
            if (key != null) {
                listing.addProperty("previewKey", key);
            }
            if (CatalogItemDataExport.write(minecraft, registries, stack, null, sections, entries)) {
                listing.addProperty("hasPublicData", true);
            }
            catalog.add(listing);
            listings.put(id, listing);
        }
    }

    private void exportVariant(CatalogPreviewVariant variant, StackEntries entries) throws IOException {
        String id = BuiltInRegistries.ITEM.getKey(variant.stack().getItem()).toString();
        var listing = listings.get(id);
        if (listing == null) {
            return;
        }
        boolean exportPreviews = sections.contains(ExportSection.ITEMS);
        String key = exportPreviews ? exportPreview(variant.stack(), entries, id, variant.id()) : null;
        boolean hasPublicData = CatalogItemDataExport.write(minecraft, registries, variant.stack(), variant.id(), sections, entries);
        if (key == null && !hasPublicData) {
            return;
        }
        if (!listing.has("variants")) {
            listing.add("variants", new JsonArray());
        }
        var row = new JsonObject();
        row.addProperty("id", variant.id());
        if (exportPreviews) ItemBarExport.addTo(row, variant.stack());
        if (key != null) row.addProperty("previewKey", key);
        var options = new JsonArray();
        for (var option : variant.options()) {
            var entry = new JsonObject();
            entry.addProperty("axis", option.axis());
            entry.addProperty("value", option.value());
            entry.addProperty("label", option.label());
            options.add(entry);
        }
        row.add("options", options);
        listing.getAsJsonArray("variants").add(row);
    }

    private String exportPreview(ItemStack stack, StackEntries entries, String item, String variant) {
        String key;
        PngPixels image;
        PngPixels direct;
        int loopTicks = 1;
        boolean missingTexture = false;
        try {
            // This key also covers stack components used by a mod's client renderer.
            key = Hashing.sha256().hashString(stack.save(registries).toString(), StandardCharsets.UTF_8).toString();
            if (exportedKeys.contains(key)) {
                return key;
            }
            direct = directIcons.capture(stack);
            if (direct != null) {
                image = direct;
            } else {
                try (var sprites = new InventorySpriteCapture()) {
                    image = renderer.render(stack);
                    missingTexture = sprites.onlyMissingTexture();
                    loopTicks = sprites.loopTicks();
                }
            }
        } catch (RuntimeException exception) {
            skipped++;
            diagnostics.skippedItem(item, variant, "render_failed");
            return null;
        }
        if (image == null || missingTexture) {
            skipped++;
            diagnostics.skippedItem(item, variant, missingTexture ? "missing_texture" : "empty_render");
            return null;
        }
        entries.work.add(archive.preparePreview(staticPreviews, key, image, loopTicks > 1));
        exportedKeys.add(key);
        if (direct != null) directIconsExported++;
        if (loopTicks > 1) {
            animations.add(key, stack, loopTicks);
        }
        return key;
    }

    boolean animating() {
        return prepared && processed == items.size() + variants.size() && ingredientPreviewsCaptured && advancementPreviewsCaptured
                && (recipes == null || recipesCaptured) && animations.hasPending();
    }

    void captureAnimationTick() {
        // Minecraft advances texture atlases once, before ticking the screen.
        if (minecraft.level == level && level.tickRateManager().runsNormally()) {
            animations.tick(renderer::captureAnimationFrame, archive);
        } else {
            animations.clear();
        }
    }

    Progress progress() {
        var phase = phase();
        String detail = switch (phase) {
            case PREVIEWS, TOOLTIPS -> processed + " / " + (items.size() + variants.size()) + " stacks processed";
            case RECIPES -> recipes.progress();
            case ANIMATIONS -> animations.remaining() + " animations remaining";
            case MACHINE_RECIPES -> machineRecipes.progress();
            case PONDER_DEFINITIONS -> ponderDefinitions.progress();
            case PONDER, PONDER_TIMELINES, PONDER_COMPACT -> ponder.progress();
            default -> "";
        };
        return new Progress(phases, phase, detail);
    }

    Phase phase() {
        if (!prepared) return Phase.CATALOG;
        if (processed < items.size() + variants.size()) return itemPhase;
        if (recipes != null && !recipesCaptured) return Phase.RECIPES;
        if (!ingredientPreviewsCaptured) return Phase.INGREDIENT_PREVIEWS;
        if (!advancementPreviewsCaptured) return Phase.ADVANCEMENT_PREVIEWS;
        if (animations.hasPending()) return Phase.ANIMATIONS;
        if (machineRecipes != null && !machineRecipesCaptured) return Phase.MACHINE_RECIPES;
        if (ponderDefinitions != null && !ponderDefinitionsCaptured) return Phase.PONDER_DEFINITIONS;
        if (ponder != null && !ponderCaptured) return ponderPhase();
        if (sections.contains(ExportSection.DESIGNS) && !finishRequested) return Phase.DESIGNS;
        return Phase.ARCHIVE;
    }

    int fullArchiveQueueChecks() {
        return fullArchiveQueueChecks;
    }

    long archiveActiveNanos() {
        return archive.activeNanos();
    }

    int directIconsExported() {
        return directIconsExported;
    }

    private Phase ponderPhase() {
        if (sections.contains(ExportSection.PONDER_COMPACT)) return Phase.PONDER_COMPACT;
        return sections.contains(ExportSection.PONDER) ? Phase.PONDER : Phase.PONDER_TIMELINES;
    }

    enum Phase {
        CATALOG("Prepare item catalog"),
        PREVIEWS("Render item previews"),
        TOOLTIPS("Capture item tooltips"),
        ANIMATIONS("Capture animations"),
        RECIPES("Read world recipes"),
        INGREDIENT_PREVIEWS("Render recipe ingredient previews"),
        ADVANCEMENT_PREVIEWS("Render advancement icons"),
        MACHINE_RECIPES("Read machine recipes"),
        PONDER_DEFINITIONS("Read Ponder definitions"),
        PONDER("Capture Ponder scenes"),
        PONDER_TIMELINES("Evaluate Ponder timelines"),
        PONDER_COMPACT("Evaluate Ponder block layouts"),
        DESIGNS("Capture banner and armor designs"),
        ARCHIVE("Write and save archive");

        final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    record Progress(List<Phase> phases, Phase current, String detail) {
        int step() {
            return phases.indexOf(current) + 1;
        }
    }

    String result() {
        int missing = diagnostics.missingImages();
        if (missing > 0) {
            return "Saved " + output.getFileName() + " with " + missing + " missing images. See " + ExportDiagnostics.FILE + ".";
        }
        if (recipes != null && (machineRecipes == null || !machineRecipes.available())) {
            return "Saved " + output.getFileName() + ". Recipe viewer data unavailable. See " + ExportDiagnostics.FILE + ".";
        }
        if (machineRecipes != null && machineRecipes.skipped() > 0) {
            return "Saved " + output.getFileName() + " with " + machineRecipes.skipped()
                    + " machine recipes skipped. See " + ExportDiagnostics.FILE + ".";
        }
        if (!sections.contains(ExportSection.ITEMS)) return "Saved webcraft-exports/" + output.getFileName();
        return "Saved " + exportedKeys.size() + " previews (" + animations.exported()
                + " animated, " + skipped + " skipped) to webcraft-exports/" + output.getFileName();
    }

    @Override
    public void close() {
        archive.close();
        try {
            if (ponder != null) ponder.close();
        } finally {
            if (renderer != null) renderer.close();
        }
    }

    private static final class StackEntries implements ExportArchive.EntryWriter {
        private final List<ExportArchive.Work> work = new ArrayList<>(3);

        @Override
        public void write(String name, byte[] bytes) {
            work.add(writer -> writer.write(name, bytes));
        }

        private void writeAll(ExportArchive.EntryWriter writer) throws IOException {
            for (var entry : work) entry.write(writer);
        }
    }
}
