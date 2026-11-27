package com.universium.webcraft.exporter.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.outliner.AABBOutline;
import net.createmod.catnip.outliner.LineOutline;
import net.createmod.catnip.outliner.Outline;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.scene.Selection;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.PonderWorldParticles;
import net.createmod.ponder.foundation.element.AnimatedSceneElementBase;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3dc;
import org.joml.Vector4f;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Records resolved scene changes regardless of the language that registered the instructions. */
final class PonderSceneEvents {
    private static final Field SELECTION = field(WorldSectionElementImpl.class, "section");
    private static final Field FADE = field(AnimatedSceneElementBase.class, "fade");
    private static final Field PARTICLES = field(PonderLevel.class, "particles");
    private static final Field ACTIVE_PARTICLES = field(PonderWorldParticles.class, "byType");
    private static final Field LINE_START = field(LineOutline.class, "start");
    private static final Field LINE_END = field(LineOutline.class, "end");
    private static final Field LINE_WIDTH = field(Outline.OutlineParams.class, "lineWidth");
    private static final int MAX_BLOCK_ENTITY_DATA = 65_536;

    private final Map<BlockPos, BlockState> previousBlocks = new HashMap<>();
    private final Elements sections = new Elements();
    private final Elements effects = new Elements();
    private final Map<WorldSectionElementImpl, SelectionState> selections = new IdentityHashMap<>();
    private final Map<BlockState, String> stateNames = new IdentityHashMap<>();

    JsonObject record(PonderScene scene, Set<Object> dirtyBlockEntities) {
        var world = scene.getWorld();
        var result = new JsonObject();
        var changes = blocks(world.getBlockMap());
        if (!changes.isEmpty()) result.add("blocks", changes);

        scene.forEachVisible(WorldSectionElementImpl.class, section -> {
            if (!section.isEmpty()) sections.put(section, () -> section(section));
        });
        selections.keySet().removeIf(section -> !scene.getElements().contains(section));
        sections.writeChanges(result, "sections");

        for (var entity : world.getBlockEntities()) {
            if (entity.isRemoved()) continue;
            effects.put(entity, dirtyBlockEntities.contains(entity), () -> {
                var value = effect("block_entity", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString());
                value.add("position", position(entity.getBlockPos()));
                try {
                    String data = entity.saveWithoutMetadata(world.registryAccess()).toString();
                    if (data.length() <= MAX_BLOCK_ENTITY_DATA) value.addProperty("data", data);
                    else value.addProperty("dataError", "size_limit");
                } catch (RuntimeException exception) {
                    value.addProperty("dataError", exception.getClass().getSimpleName());
                }
                return value;
            });
        }
        for (var entity : world.getEntityList()) {
            if (entity.isRemoved()) continue;
            effects.put(entity, () -> {
                var value = effect("entity", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                value.add("position", vector(entity.getX(), entity.getY(), entity.getZ()));
                value.add("rotation", PonderDrawCapture.numbers(entity.getYRot(), entity.getXRot()));
                return value;
            });
        }
        var worldParticles = read(PARTICLES, world);
        var particles = worldParticles == null ? Map.of() : (Map<?, ?>) read(ACTIVE_PARTICLES, worldParticles);
        for (var group : particles.values()) {
            if (!(group instanceof Collection<?> collection)) continue;
            for (var entry : collection) {
                if (!(entry instanceof Particle particle) || !particle.isAlive()) continue;
                effects.put(particle, () -> {
                    var value = effect("particle", particle.getClass().getName());
                    value.add("bounds", bounds(particle.getBoundingBox()));
                    return value;
                });
            }
        }
        for (var entry : scene.getOutliner().getOutlines().values()) {
            if (!entry.isAlive()) continue;
            var outline = entry.getOutline();
            effects.put(outline, () -> {
                var value = effect("outline", outline.getClass().getName());
                value.addProperty("remainingTicks", entry.getTicksTillRemoval());
                var params = outline.getParams();
                var color = new Vector4f();
                params.loadColor(color);
                float opacity = entry.isFading() ? Math.max(0, 1 + entry.getTicksTillRemoval() / 8f) : 1;
                value.add("color", PonderDrawCapture.numbers(color.x, color.y, color.z, opacity * opacity * opacity));
                value.addProperty("lineWidth", (float) read(LINE_WIDTH, params));
                if (outline instanceof AABBOutline box && box.getBounds() != null) value.add("bounds", bounds(box.getBounds()));
                if (outline instanceof LineOutline line) {
                    value.add("start", vector((Vector3dc) read(LINE_START, line)));
                    value.add("end", vector((Vector3dc) read(LINE_END, line)));
                }
                return value;
            });
        }
        effects.writeChanges(result, "effects");
        return result;
    }

    private JsonArray blocks(Map<BlockPos, BlockState> next) {
        var changes = new JsonArray();
        next.entrySet().stream().filter(entry -> !entry.getValue().isAir() && previousBlocks.get(entry.getKey()) != entry.getValue())
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder())).forEach(entry -> {
            var position = entry.getKey();
            var state = entry.getValue();
            previousBlocks.put(position.immutable(), state);
            var change = position(position);
            change.add(stateNames.computeIfAbsent(state, PonderSceneEvents::stateName));
            changes.add(change);
        });
        var removed = previousBlocks.keySet().stream().filter(position -> {
            var state = next.get(position);
            return state == null || state.isAir();
        }).sorted().toList();
        for (var position : removed) {
            previousBlocks.remove(position);
            var change = position(position);
            change.add(JsonNull.INSTANCE);
            changes.add(change);
        }
        return changes;
    }

    private JsonObject section(WorldSectionElementImpl section) {
        var definition = new JsonObject();
        var selection = (Selection) read(SELECTION, section);
        var cached = selections.get(section);
        if (cached == null || cached.selection != selection || PonderRenderState.needsRedraw(section)) {
            var positions = new JsonArray();
            if (selection != null) selection.forEach(block -> positions.add(position(block)));
            cached = new SelectionState(selection, positions);
            selections.put(section, cached);
        }
        definition.add("positions", cached.positions);
        var pose = new PoseStack();
        section.transformMS(pose, 1);
        definition.add("transform", PonderDrawCapture.matrix(pose.last().pose()));
        definition.addProperty("fade", ((LerpedFloat) read(FADE, section)).getValue(1));
        return definition;
    }

    private record SelectionState(Selection selection, JsonArray positions) {}

    static String stateName(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        var properties = state.getProperties().stream().sorted(Comparator.comparing(Property::getName))
                .map(property -> propertyValue(state, property)).toList();
        return properties.isEmpty() ? id : id + '[' + String.join(",", properties) + ']';
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName() + '=' + property.getName(state.getValue(property));
    }

    private static JsonObject effect(String kind, String type) {
        var value = new JsonObject();
        value.addProperty("kind", kind);
        value.addProperty("type", type);
        return value;
    }

    private static JsonArray position(BlockPos pos) {
        var value = new JsonArray(3);
        value.add(pos.getX());
        value.add(pos.getY());
        value.add(pos.getZ());
        return value;
    }

    private static JsonArray bounds(AABB box) {
        return vector(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static JsonArray vector(Vector3dc v) {
        return vector(v.x(), v.y(), v.z());
    }

    private static JsonArray vector(double... values) {
        var array = new JsonArray(values.length);
        for (double value : values) array.add(value);
        return array;
    }

    private static Field field(Class<?> owner, String name) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported Ponder event data: " + owner.getSimpleName() + "." + name, exception);
        }
    }

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not read Ponder event data", exception);
        }
    }

    private static final class Elements {
        private record State(int id, JsonObject value) {}

        private Map<Object, State> previous = new IdentityHashMap<>();
        private Map<Object, State> current = new IdentityHashMap<>();
        private int nextId;

        void put(Object element, Supplier<JsonObject> describe) {
            put(element, true, describe);
        }

        void put(Object element, boolean refresh, Supplier<JsonObject> describe) {
            var old = previous.get(element);
            if (old != null && !refresh) current.put(element, old);
            else current.put(element, new State(old == null ? nextId++ : old.id, describe.get()));
        }

        void writeChanges(JsonObject result, String field) {
            var changes = new JsonObject();
            for (var entry : current.entrySet()) {
                var value = entry.getValue();
                var old = previous.get(entry.getKey());
                var patch = new JsonObject();
                for (var property : value.value.entrySet()) {
                    if (old == null || !property.getValue().equals(old.value.get(property.getKey()))) {
                        patch.add(property.getKey(), property.getValue());
                    }
                }
                if (old != null) {
                    for (String key : old.value.keySet()) {
                        if (!value.value.has(key)) patch.add(key, JsonNull.INSTANCE);
                    }
                }
                if (!patch.isEmpty()) changes.add(Integer.toString(value.id), patch);
            }
            previous.forEach((element, state) -> {
                if (!current.containsKey(element)) changes.add(Integer.toString(state.id), JsonNull.INSTANCE);
            });
            if (!changes.isEmpty()) result.add(field, changes);
            var old = previous;
            previous = current;
            current = old;
            current.clear();
        }
    }
}
