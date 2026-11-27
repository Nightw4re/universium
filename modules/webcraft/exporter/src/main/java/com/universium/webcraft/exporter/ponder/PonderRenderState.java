package com.universium.webcraft.exporter.ponder;

import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.PonderWorldParticles;
import net.createmod.ponder.foundation.element.AnimatedSceneElementBase;
import net.createmod.ponder.foundation.element.InputWindowElement;
import net.createmod.ponder.foundation.element.TextWindowElement;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.createmod.ponder.foundation.instruction.AnimateWorldSectionInstruction;
import net.createmod.ponder.foundation.instruction.DelayInstruction;
import net.createmod.ponder.foundation.instruction.PonderInstruction;
import net.createmod.ponder.foundation.instruction.RotateSceneInstruction;
import net.createmod.ponder.foundation.instruction.ShowInputInstruction;
import net.createmod.ponder.foundation.instruction.TextInstruction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Identifies consecutive stock delay ticks whose 3D draw data can be kept unchanged. */
final class PonderRenderState {
    private static final Field ACTIVE = field(PonderScene.class, "activeSchedule");
    private static final Field OUTLINE = field(TextInstruction.class, "outline");
    private static final Field REDRAW = field(WorldSectionElementImpl.class, "redraw");
    private static final Field FADE = field(AnimatedSceneElementBase.class, "fade");
    private static final Field FADE_VECTOR = field(AnimatedSceneElementBase.class, "fadeVec");
    private static final Field PARTICLES = field(PonderLevel.class, "particles");
    private static final Field PENDING_PARTICLES = field(PonderWorldParticles.class, "queue");
    private static final Field ACTIVE_PARTICLES = field(PonderWorldParticles.class, "byType");

    private PonderRenderState() {}

    static Object idleBarrier(PonderScene scene) {
        for (Object pending : (List<?>) read(ACTIVE, scene)) {
            if (!(pending instanceof PonderInstruction instruction)) return null;
            if (instruction.getClass() == DelayInstruction.class) return instruction;
            if (instruction.getClass() == AnimateWorldSectionInstruction.class && !instruction.isBlocking()) continue;
            if (instruction.getClass() == RotateSceneInstruction.class && !instruction.isBlocking()) continue;
            if (instruction.getClass() == ShowInputInstruction.class && !instruction.isBlocking()) continue;
            if (instruction.getClass() != TextInstruction.class || instruction.isBlocking()
                    || read(OUTLINE, instruction) != null) return null;
        }
        return null;
    }

    static GeometryState snapshot(PonderScene scene, Matrix4f camera, boolean requireBuiltSectionMeshes) {
        var world = scene.getWorld();
        if (scene.isFinished() || PonderIndex.editingModeActive() || !scene.getOutliner().getOutlines().isEmpty()
                || !world.getEntityList().isEmpty() || world.getBlockEntities().iterator().hasNext()
                || !world.getBlockBreakingProgressions().isEmpty() || hasParticles(world)) return null;

        for (var element : scene.getElements()) {
            // The overlay is captured separately even when the 3D geometry is reused.
            if (element.getClass() == TextWindowElement.class || element.getClass() == InputWindowElement.class) continue;
            if (element.getClass() != WorldSectionElementImpl.class) return null;
        }
        var visible = new ArrayList<WorldSectionElementImpl>();
        scene.forEachVisible(WorldSectionElementImpl.class, visible::add);
        var sections = new ArrayList<GeometryState.Section>(visible.size());
        for (var section : visible) {
            if (requireBuiltSectionMeshes && needsRedraw(section)) return null;
            var pose = new PoseStack();
            section.transformMS(pose, 1);
            float fade = ((LerpedFloat) read(FADE, section)).getValue(1);
            sections.add(new GeometryState.Section(section, new Matrix4f(pose.last().pose()), fade, read(FADE_VECTOR, section)));
        }
        return new GeometryState(new Matrix4f(camera), sections);
    }

    static boolean needsRedraw(WorldSectionElementImpl section) {
        return (boolean) read(REDRAW, section);
    }

    static Matrix4f visibleSectionTransform(WorldSectionElementImpl section) {
        var pose = new PoseStack();
        float fade = ((LerpedFloat) read(FADE, section)).getValue(1);
        if (read(FADE_VECTOR, section) instanceof Vec3 direction) {
            pose.translate(direction.x * (fade - 1), direction.y * (fade - 1), direction.z * (fade - 1));
        }
        section.transformMS(pose, 1);
        return pose.last().pose();
    }

    private static boolean hasParticles(PonderLevel world) {
        var particles = read(PARTICLES, world);
        if (particles == null) return false;
        if (!((Collection<?>) read(PENDING_PARTICLES, particles)).isEmpty()) return true;
        return ((Map<?, ?>) read(ACTIVE_PARTICLES, particles)).values().stream()
                .anyMatch(group -> !(group instanceof Collection<?> collection) || !collection.isEmpty());
    }

    private static Field field(Class<?> owner, String name) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported Ponder render state: " + owner.getSimpleName() + "." + name, exception);
        }
    }

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not read Ponder render state", exception);
        }
    }
}
