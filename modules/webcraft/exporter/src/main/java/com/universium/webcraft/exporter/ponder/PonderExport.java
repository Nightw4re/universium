package com.universium.webcraft.exporter.ponder;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Exports registered Ponder scenes from the active Minecraft client. */
public final class PonderExport {
    public static final String INDEX = "ponder/index.json";

    private final Minecraft minecraft;
    private final String locale;
    private final Iterator<StoryBoardEntry> remaining;
    private final Consumer<Map<String, byte[]>> submit;
    private final PonderDrawCapture.Assets assets = new PonderDrawCapture.Assets();
    private final Map<String, SceneEntry> scenes = new LinkedHashMap<>();
    private PonderScene scene;
    private PonderSceneCapture capture;
    private SceneEntry current;
    private int tick;
    private boolean hasGeometry;

    public PonderExport(Minecraft minecraft, Set<String> filter, Consumer<Map<String, byte[]>> submit) {
        this.minecraft = minecraft;
        this.locale = minecraft.getLanguageManager().getSelected();
        this.submit = submit;
        var entries = PonderIndex.getSceneAccess().getRegisteredEntries().stream().map(Map.Entry::getValue)
                .filter(entry -> filter.isEmpty() || filter.contains(entry.getSchematicLocation().toString()))
                .sorted(Comparator.comparing((StoryBoardEntry entry) -> entry.getSchematicLocation().toString())
                        .thenComparing(entry -> entry.getComponent().toString())).toList();
        var unmatched = new TreeSet<>(filter);
        unmatched.removeAll(entries.stream().map(entry -> entry.getSchematicLocation().toString()).collect(Collectors.toSet()));
        if (!unmatched.isEmpty()) throw new IllegalArgumentException("Unknown Ponder schematics: " + String.join(", ", unmatched));
        if (entries.isEmpty()) throw new IllegalArgumentException("No Ponder scenes are registered in this client.");
        remaining = entries.iterator();
    }

    public boolean captureNext() throws IOException {
        if (scene == null) {
            if (!remaining.hasNext()) return false;
            prepare(remaining.next());
            return true;
        }
        var frame = capture.frame(tick, assets);
        hasGeometry |= !frame.getAsJsonArray("scene").isEmpty();
        var files = assets.drain();
        files.put(current.directory + "/frames/" + tick + ".json", json(frame));
        tick++;
        if (scene.isFinished()) {
            if (!hasGeometry) {
                throw new IOException("Ponder scene rendered no geometry: " + scene.getId());
            }
            completeScene(files);
        }
        submit.accept(files);
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
        capture = new PonderSceneCapture(minecraft, candidate);
        scenes.put(key, current);
        tick = 0;
        hasGeometry = false;
    }

    private void completeScene(Map<String, byte[]> files) {
        var manifest = new JsonObject();
        manifest.addProperty("ticksPerSecond", 20);
        manifest.addProperty("frameCount", tick);
        manifest.addProperty("width", PonderSceneCapture.WIDTH);
        manifest.addProperty("height", PonderSceneCapture.HEIGHT);
        manifest.add("diffuseLights", PonderSceneCapture.lighting());
        var keyframes = new JsonArray();
        for (int index = 0; index < scene.getKeyframeCount(); index++) keyframes.add(scene.getKeyframeTime(index));
        manifest.add("keyframes", keyframes);
        files.put(current.directory + "/scene.json", json(manifest));
        scene = null;
        capture = null;
        current = null;
    }

    public byte[] index() {
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
        return json(index);
    }

    public String progress() {
        return current == null ? scenes.size() + " scenes captured" : current.title + " · tick " + tick;
    }

    private static byte[] json(JsonObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private record SceneEntry(String id, String title, String schematic, String directory, Set<String> items) {}
}
