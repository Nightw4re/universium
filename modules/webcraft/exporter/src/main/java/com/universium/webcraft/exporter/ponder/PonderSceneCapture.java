package com.universium.webcraft.exporter.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.createmod.ponder.api.scene.Selection;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.TextWindowElement;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class PonderSceneCapture extends PonderUI {
    static final int WIDTH = 480;
    static final int HEIGHT = 320;
    private static final Field SELECTION = field(WorldSectionElementImpl.class, "section");
    private static final Field TEXT = field(TextWindowElement.class, "bakedText");
    private static final Field TEXT_ANCHOR = field(TextWindowElement.class, "vec");
    private static final Field DIFFUSE_LIGHT_0 = field(PonderUI.class, "DIFFUSE_LIGHT_0");
    private static final Field DIFFUSE_LIGHT_1 = field(PonderUI.class, "DIFFUSE_LIGHT_1");

    private final PonderScene scene;

    PonderSceneCapture(Minecraft minecraft, PonderScene scene) {
        super(List.of(scene));
        this.scene = scene;
        init(minecraft, WIDTH, HEIGHT);
        scene.begin();
    }

    JsonObject frame(int tick, PonderDrawCapture.Assets assets) {
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var camera = minecraft.getCameraEntity();
        int previousPonderTicks = ponderTicks;
        var graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            ponderTicks = tick;
            if (tick > 0) scene.tick();
            float farPlane = ClientHooks.getGuiFarPlane();
            modelView.translation(0, 0, 10000 - farPlane);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, WIDTH, HEIGHT, 0, 1000, farPlane),
                    VertexSorting.ORTHOGRAPHIC_Z);
            var frame = new JsonObject();
            try (var capture = new PonderDrawCapture(assets)) {
                renderScene(graphics, -1, -1, 0, 1);
                graphics.flush();
                frame.add("scene", capture.draws());
            }
            var cameraPose = new PoseStack();
            scene.getTransform().apply(cameraPose, 1);
            frame.add("sceneToGui", PonderDrawCapture.matrix(cameraPose.last().pose()));
            frame.addProperty("picking", assets.store("picking", ".json",
                    selection().toString().getBytes(StandardCharsets.UTF_8)));
            try (var capture = new PonderDrawCapture(assets)) {
                RenderSystem.disableDepthTest();
                graphics.pose().translate(0, 0, 100);
                scene.renderOverlay(this, graphics, 1);
                graphics.flush();
                frame.add("overlay", capture.draws());
            }
            frame.add("captions", captions());
            return frame;
        } finally {
            ponderTicks = previousPonderTicks;
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            if (minecraft.getCameraEntity() != camera) minecraft.setCameraEntity(camera);
            Lighting.setupFor3DItems();
        }
    }

    private JsonArray selection() {
        var sections = new JsonArray();
        var world = scene.getWorld();
        var collision = CollisionContext.of(minecraft.player);
        scene.forEachVisible(WorldSectionElementImpl.class, element -> {
            var selection = (Selection) read(SELECTION, element);
            if (selection == null || element.isEmpty()) return;
            var pose = new PoseStack();
            element.transformMS(pose, 1);
            var section = new JsonObject();
            section.add("transform", PonderDrawCapture.matrix(pose.last().pose()));
            var blocks = new JsonArray();
            for (var position : selection) {
                var state = world.getBlockState(position);
                if (state.isAir()) continue;
                var shape = state.getShape(world, position, collision);
                if (shape.isEmpty()) continue;
                var block = new JsonObject();
                block.add("position", PonderDrawCapture.numbers(position.getX(), position.getY(), position.getZ()));
                block.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                var item = state.getBlock().asItem();
                if (item != net.minecraft.world.item.Items.AIR) {
                    block.addProperty("item", BuiltInRegistries.ITEM.getKey(item).toString());
                }
                var boxes = new JsonArray();
                shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> boxes.add(PonderDrawCapture.numbers(
                        (float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1)));
                block.add("boxes", boxes);
                blocks.add(block);
            }
            section.add("blocks", blocks);
            sections.add(section);
        });
        return sections;
    }

    private JsonArray captions() {
        var captions = new JsonArray();
        scene.forEachVisible(TextWindowElement.class, element -> {
            var text = (String) read(TEXT, element);
            if (text == null) return;
            var caption = new JsonObject();
            caption.addProperty("text", text);
            caption.addProperty("color", element.getPalette().getColor());
            caption.addProperty("opacity", element.getFade(1));
            var anchor = (Vec3) read(TEXT_ANCHOR, element);
            if (anchor != null) caption.add("anchor", PonderDrawCapture.numbers((float) anchor.x, (float) anchor.y, (float) anchor.z));
            captions.add(caption);
        });
        return captions;
    }

    static JsonArray lighting() {
        var directions = new JsonArray();
        for (var field : List.of(DIFFUSE_LIGHT_0, DIFFUSE_LIGHT_1)) {
            var vector = (Vector3f) read(field, null);
            directions.add(PonderDrawCapture.numbers(vector.x, vector.y, vector.z));
        }
        return directions;
    }

    private static Field field(Class<?> owner, String name) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported Ponder scene layout: " + owner.getSimpleName() + "." + name, exception);
        }
    }

    private static Object read(Field field, Object element) {
        try {
            return field.get(element);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not read Ponder scene data", exception);
        }
    }
}
