package com.universium.webcraft.exporter.ponder;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Exports registered Ponder scenes from the active Minecraft client. */
public final class PonderExport implements AutoCloseable {
    public static final String INDEX = "ponder/index.json";
    public static final String COMPACT_MODELS = PonderCompactModels.PATH;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Minecraft minecraft;
    private final String locale;
    private final Iterator<StoryBoardEntry> remaining;
    private final Consumer<Map<String, byte[]>> submit;
    private final PonderDrawCapture.Assets assets;
    private final PonderExportStats stats = new PonderExportStats();
    private final boolean timelines;
    private final PonderCompactModels compact;
    private final Map<String, SceneEntry> scenes = new LinkedHashMap<>();
    private final Set<Integer> referenceTicks;
    private PonderScene scene;
    private PonderSceneCapture capture;
    private SceneEntry current;
    private int tick;
    private boolean hasContent;
    private PonderTimeline timeline;

    public PonderExport(Minecraft minecraft, Set<String> filter, boolean experimentalCuda, boolean timelines, boolean compact,
                        int atlasPageSize, Consumer<Map<String, byte[]>> submit) {
        this.minecraft = minecraft;
        this.locale = minecraft.getLanguageManager().getSelected();
        this.submit = submit;
        this.timelines = timelines;
        referenceTicks = Arrays.stream(System.getProperty("webcraft.export.ponderReferenceTicks", "").split(","))
                .filter(value -> !value.isBlank()).map(Integer::valueOf).collect(Collectors.toUnmodifiableSet());
        var entries = PonderIndex.getSceneAccess().getRegisteredEntries().stream().map(Map.Entry::getValue)
                .filter(entry -> filter.isEmpty() || filter.contains(entry.getSchematicLocation().toString()))
                .sorted(Comparator.comparing((StoryBoardEntry entry) -> entry.getSchematicLocation().toString())
                        .thenComparing(entry -> entry.getComponent().toString())).toList();
        var unmatched = new TreeSet<>(filter);
        unmatched.removeAll(entries.stream().map(entry -> entry.getSchematicLocation().toString()).collect(Collectors.toSet()));
        if (!unmatched.isEmpty()) throw new IllegalArgumentException("Unknown Ponder schematics: " + String.join(", ", unmatched));
        if (entries.isEmpty()) throw new IllegalArgumentException("No Ponder scenes are registered in this client.");
        remaining = entries.iterator();
        assets = new PonderDrawCapture.Assets(experimentalCuda, compact ? atlasPageSize : 0);
        this.compact = compact ? new PonderCompactModels(minecraft, assets) : null;
    }

    public boolean captureNext() throws IOException {
        if (scene == null) {
            if (!remaining.hasNext()) return false;
            prepare(remaining.next());
            return true;
        }
        JsonObject frame;
        try {
            if (tick == 100_000) throw new IllegalStateException("Animation exceeds the supported duration");
            frame = capture.frame(tick, assets);
            if (referenceTicks.contains(tick)) {
                var directory = minecraft.gameDirectory.toPath().resolve("webcraft-exports/ponder-reference");
                Files.createDirectories(directory);
                var sceneHash = current.directory.substring(current.directory.lastIndexOf('/') + 1);
                Files.write(directory.resolve(sceneHash + "-" + tick + ".png"), capture.nativeReference(tick));
            }
        } catch (RuntimeException | LinkageError exception) {
            throw new IOException("Could not evaluate Ponder scene " + current.id + " at tick " + tick, exception);
        }
        hasContent |= !frame.getAsJsonArray("scene").isEmpty() || timelines
                && (!frame.getAsJsonArray("overlay").isEmpty() || !frame.getAsJsonArray("captions").isEmpty());
        stats.frame(frame);
        var files = assets.drain();
        if (timeline != null) {
            timeline.append(frame);
            timeline.flush(current.directory, ++tick, scene.isFinished(), files);
        } else {
            files.put(current.directory + "/frames/" + tick++ + ".json", json(frame));
        }
        boolean finished = scene.isFinished();
        if (finished) {
            if (!hasContent && compact == null) {
                throw new IOException("Ponder scene produced no playback content: " + scene.getId());
            }
            completeScene(files);
        }
        stats.files(files);
        if (finished) {
            var summary = stats.finishScene(current.id, current.title, current.schematic, tick, capture.reusedTicks(),
                    capture.reusedStaticSections(), assets.meshSubmissions());
            if (compact != null) {
                summary.addProperty("reusedMovingParts", compact.takeMovingPartReuses());
                summary.addProperty("poseOnlyMovingParts", compact.takePoseOnlyMovingParts());
            }
            var rigidFallbacks = assets.takeRigidFallbacks();
            if (!rigidFallbacks.isEmpty()) summary.add("rigidFallbacks", rigidFallbacks);
            var particleOrientations = assets.takeParticleOrientations();
            if (!particleOrientations.isEmpty()) summary.add("particleOrientations", particleOrientations);
            summary.add("missingStaticModels", capture.missingStaticModels());
            LOGGER.info("WebCraft Ponder scene data: {}", summary);
            scene = null;
            capture = null;
            current = null;
            timeline = null;
        }
        if (!files.isEmpty()) submit.accept(files);
        return true;
    }

    private void prepare(StoryBoardEntry entry) throws IOException {
        var compiled = PonderIndex.getSceneAccess().compile(List.of(entry));
        if (compiled.size() != 1) throw new IOException("Could not load Ponder schematic " + entry.getSchematicLocation());
        var candidate = compiled.getFirst();
        String schematic = entry.getSchematicLocation().toString();
        String key = candidate.getId() + "\n" + schematic;
        var known = scenes.get(key);
        if (known != null) {
            known.items.add(entry.getComponent().toString());
            return;
        }
        String directory = "ponder/scenes/" + Hashing.sha256().hashString(key, StandardCharsets.UTF_8);
        current = new SceneEntry(candidate.getId().toString(), candidate.getTitle(), schematic, directory,
                new TreeSet<>(List.of(entry.getComponent().toString())));
        scene = candidate;
        capture = new PonderSceneCapture(minecraft, candidate, timelines, compact);
        timeline = timelines ? new PonderTimeline() : null;
        scenes.put(key, current);
        stats.startScene(assets.meshSubmissions());
        tick = 0;
        hasContent = false;
    }

    private void completeScene(Map<String, byte[]> files) {
        if (timelines && compact == null) {
            LOGGER.info("WebCraft Ponder timeline {}: {} ticks, {} geometry captures, {} reused",
                    current.id, tick, tick - capture.reusedTicks(), capture.reusedTicks());
        }
        var manifest = new JsonObject();
        manifest.addProperty("ticksPerSecond", 20);
        manifest.addProperty("frameCount", tick);
        manifest.addProperty("width", PonderSceneCapture.WIDTH);
        manifest.addProperty("height", PonderSceneCapture.HEIGHT);
        if (compact != null) {
            manifest.addProperty("blockModels", COMPACT_MODELS);
            if (!scene.shouldHidePlatformShadow() && scene.getBasePlateSize() > 0) {
                manifest.add("platformShadow", PonderDrawCapture.numbers(
                        scene.getBasePlateOffsetX(), scene.getBasePlateOffsetZ(), scene.getBasePlateSize()));
            }
        }
        manifest.add("diffuseLights", PonderSceneCapture.lighting());
        var keyframes = new JsonArray();
        for (int index = 0; index < scene.getKeyframeCount(); index++) keyframes.add(scene.getKeyframeTime(index));
        manifest.add("keyframes", keyframes);
        files.put(current.directory + "/scene.json", json(manifest));
    }

    public JsonObject index() {
        var index = new JsonObject();
        index.addProperty("version", 1);
        index.addProperty("locale", locale);
        var entries = new JsonArray();
        for (var scene : scenes.values()) {
            var entry = new JsonObject();
            entry.addProperty("id", scene.id);
            entry.addProperty("title", scene.title);
            entry.addProperty("schematic", scene.schematic);
            entry.addProperty("manifest", scene.directory + "/scene.json");
            var items = new JsonArray();
            scene.items.forEach(items::add);
            entry.add("items", items);
            entries.add(entry);
        }
        index.add("scenes", entries);
        return index;
    }

    public String progress() {
        if (current == null) return scenes.size() + " scenes captured";
        String progress = current.title + " · tick " + tick;
        if (compact != null) return progress + " · " + (scenes.size() - 1) + " scenes · " + compact.modelCount()
                + " shared block models · " + stats.detail();
        return timelines ? progress + " · " + (scenes.size() - 1) + " scenes, " + capture.reusedTicks()
                + " geometry reuses · " + stats.detail() : progress;
    }

    public byte[] compactModels() {
        return json(compact.document());
    }

    public JsonObject diagnostics() {
        var report = stats.report(assets.meshSubmissions());
        report.add("textureSources", assets.textureDiagnostics());
        report.add("blockAtlas", assets.atlasDiagnostics());
        return report;
    }

    @Override
    public void close() {
        assets.close();
    }

    private static byte[] json(JsonObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private record SceneEntry(String id, String title, String schematic, String directory, Set<String> items) {}
}
