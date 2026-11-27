package com.universium.webcraft.exporter.ponder;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.TextWindowElement;
import net.createmod.ponder.foundation.instruction.PonderInstruction;
import net.createmod.ponder.foundation.instruction.TextInstruction;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Collects registered scene definitions without advancing or rendering a scene. */
public final class PonderDefinitionExport {
    public static final String INDEX = "ponder/definitions.json";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_SCHEMATIC_BYTES = 16 * 1024 * 1024;
    private static final Field SCHEDULE = field(PonderScene.class, "schedule");
    private static final Field TEXT_INDEX = field(PonderScene.class, "textIndex");
    private static final Field TOTAL_TICKS = field(TickingInstruction.class, "totalTicks");
    private static final Field TEXT_ELEMENT = field(TextInstruction.class, "element");
    private static final Field TEXT_GETTER = field(TextWindowElement.class, "textGetter");
    private static final Field TEXT_ANCHOR = field(TextWindowElement.class, "vec");
    private static final Field TEXT_NEAR_TARGET = field(TextWindowElement.class, "nearScene");
    private static final Field TEXT_Y = field(TextWindowElement.class, "y");

    private final ResourceManager resources;
    private final String locale;
    private final List<StoryBoardEntry> registrations;
    private final BiConsumer<String, byte[]> submit;
    private final JsonArray definitions = new JsonArray();
    private final Map<ResourceLocation, String> schematics = new HashMap<>();
    private final Set<String> schematicFiles = new HashSet<>();
    private int next;

    public PonderDefinitionExport(ResourceManager resources, String locale, Set<String> filter,
                                  BiConsumer<String, byte[]> submit) {
        this.resources = resources;
        this.locale = locale;
        this.submit = submit;
        registrations = PonderIndex.getSceneAccess().getRegisteredEntries().stream()
                .map(Map.Entry::getValue)
                .filter(entry -> filter.isEmpty() || filter.contains(entry.getSchematicLocation().toString()))
                .toList();
        var unmatched = new TreeSet<>(filter);
        registrations.forEach(entry -> unmatched.remove(entry.getSchematicLocation().toString()));
        if (!unmatched.isEmpty()) throw new IllegalArgumentException("Unknown Ponder schematics: " + String.join(", ", unmatched));
    }

    public boolean captureNext() {
        if (next == registrations.size()) return false;
        var entry = registrations.get(next++);
        var definition = new JsonObject();
        var schematic = entry.getSchematicLocation();
        definition.addProperty("namespace", entry.getNamespace());
        definition.addProperty("item", entry.getComponent().toString());
        definition.addProperty("schematic", schematic.toString());
        var tags = new JsonArray();
        entry.getTags().forEach(tag -> tags.add(tag.toString()));
        definition.add("tags", tags);
        var ordering = new JsonArray();
        for (var order : entry.getOrderingEntries()) {
            var constraint = new JsonObject();
            constraint.addProperty("type", order.type().name());
            constraint.addProperty("scene", order.sceneId().toString());
            ordering.add(constraint);
        }
        definition.add("ordering", ordering);
        try {
            String schematicFile = schematicFile(schematic);
            if (schematicFile != null) definition.addProperty("schematicFile", schematicFile);
        } catch (IOException exception) {
            LOGGER.warn("Could not export Ponder schematic {}", schematic, exception);
            definition.addProperty("schematicError", exception.getClass().getSimpleName());
        }
        try {
            var compiled = PonderIndex.getSceneAccess().compile(List.of(entry));
            if (compiled.size() != 1) throw new IllegalStateException("Expected one compiled Ponder scene");
            definition.add("scene", describe(compiled.getFirst()));
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not compile Ponder definition for {} ({})", entry.getComponent(), schematic, exception);
            definition.addProperty("compileError", exception.getClass().getSimpleName());
        }
        definitions.add(definition);
        return true;
    }

    private String schematicFile(ResourceLocation schematic) throws IOException {
        if (schematics.containsKey(schematic)) return schematics.get(schematic);
        var location = ResourceLocation.fromNamespaceAndPath(schematic.getNamespace(), "ponder/" + schematic.getPath() + ".nbt");
        var resource = resources.getResource(location);
        String path = null;
        if (resource.isPresent()) {
            try (var stream = resource.get().open()) {
                byte[] bytes = stream.readNBytes(MAX_SCHEMATIC_BYTES + 1);
                if (bytes.length > MAX_SCHEMATIC_BYTES) throw new IOException("Ponder schematic exceeds the archive limit");
                path = "ponder/schematics/" + Hashing.sha256().hashBytes(bytes) + ".nbt";
                if (schematicFiles.add(path)) submit.accept(path, bytes);
            }
        }
        schematics.put(schematic, path);
        return path;
    }

    private static JsonObject describe(PonderScene scene) {
        var data = new JsonObject();
        data.addProperty("id", scene.getId().toString());
        data.addProperty("title", scene.getTitle());
        data.addProperty("scale", scene.getScaleFactor());
        data.addProperty("yOffset", scene.getYOffset());
        data.addProperty("basePlateSize", scene.getBasePlateSize());
        data.addProperty("basePlateOffsetX", scene.getBasePlateOffsetX());
        data.addProperty("basePlateOffsetZ", scene.getBasePlateOffsetZ());
        data.addProperty("hidePlatformShadow", scene.shouldHidePlatformShadow());

        var text = new JsonObject();
        int end = (int) read(TEXT_INDEX, scene);
        for (int index = 1; index < end; index++) {
            String key = "text_" + index;
            text.addProperty(key, scene.getString(key));
        }
        data.add("text", text);

        var instructions = new JsonArray();
        for (Object value : (List<?>) read(SCHEDULE, scene)) {
            if (!(value instanceof PonderInstruction instruction)) {
                throw new IllegalStateException("Unsupported compiled Ponder instruction");
            }
            var description = new JsonObject();
            description.addProperty("type", instruction.getClass().getName());
            description.addProperty("blocking", instruction.isBlocking());
            if (instruction instanceof TickingInstruction) {
                description.addProperty("durationTicks", (int) read(TOTAL_TICKS, instruction));
            }
            if (instruction instanceof TextInstruction textInstruction) {
                description.add("caption", describeCaption(scene, textInstruction));
            }
            instructions.add(description);
        }
        data.add("instructions", instructions);
        return data;
    }

    private static JsonObject describeCaption(PonderScene scene, TextInstruction instruction) {
        var element = (TextWindowElement) read(TEXT_ELEMENT, instruction);
        var caption = new JsonObject();
        try {
            var text = (Supplier<?>) read(TEXT_GETTER, element);
            if (text.get() instanceof String resolved) caption.addProperty("text", resolved);
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not resolve Ponder caption for {}", scene.getId(), exception);
            caption.addProperty("textError", exception.getClass().getSimpleName());
        }
        caption.addProperty("color", element.getPalette().getColor());
        caption.addProperty("nearTarget", (boolean) read(TEXT_NEAR_TARGET, element));
        caption.addProperty("y", (int) read(TEXT_Y, element));
        if (read(TEXT_ANCHOR, element) instanceof Vec3 anchor) {
            var position = new JsonArray(3);
            position.add(anchor.x);
            position.add(anchor.y);
            position.add(anchor.z);
            caption.add("anchor", position);
        }
        return caption;
    }

    public byte[] index(JsonObject playback) {
        var index = new JsonObject();
        index.addProperty("version", 1);
        index.addProperty("locale", locale);
        index.addProperty("ticksPerSecond", 20);
        index.add("registrations", definitions);
        if (playback != null) index.add("playback", playback);
        return index.toString().getBytes(StandardCharsets.UTF_8);
    }

    public String progress() {
        return next + " / " + registrations.size() + " registrations read";
    }

    private static Field field(Class<?> owner, String name) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported Ponder definition layout: " + owner.getSimpleName() + "." + name, exception);
        }
    }

    private static Object read(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not read Ponder definition data", exception);
        }
    }
}
