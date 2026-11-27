package com.universium.webcraft.exporter.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.createmod.catnip.render.SuperByteBufferCache;
import net.createmod.ponder.api.scene.Selection;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.TextWindowElement;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

final class PonderSceneCapture extends PonderUI {
    static final int WIDTH = 480;
    static final int HEIGHT = 320;
    private static final Field SELECTION = field(WorldSectionElementImpl.class, "section");
    private static final Field TEXT = field(TextWindowElement.class, "textGetter");
    private static final Field TEXT_ANCHOR = field(TextWindowElement.class, "vec");
    private static final Field DIFFUSE_LIGHT_0 = field(PonderUI.class, "DIFFUSE_LIGHT_0");
    private static final Field DIFFUSE_LIGHT_1 = field(PonderUI.class, "DIFFUSE_LIGHT_1");
    private static final Set<String> RIGID_SHADERS = Set.of("rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped");

    private final PonderScene scene;
    private final boolean reuseGeometry;
    private final PonderSceneEvents events;
    private final PonderCompactModels compact;
    private final Map<TextWindowElement, String> captionText = new IdentityHashMap<>();
    private final Map<WorldSectionElementImpl, CompactBlocks> compactBlocks = new IdentityHashMap<>();
    private final Set<BlockState> missingStaticModels = new LinkedHashSet<>();

    private GeometryState previousGeometry;
    private GeometryState capturedGeometry;
    private Object previousBarrier;
    private JsonArray previousDraws;
    private JsonObject previousCompactGeometry;
    private JsonArray previousCompactPicking;
    private String previousPicking;
    private GeometryState.Motion geometryMotion;
    private boolean rigidGeometry;
    private int reusedTicks;
    private int reusedStaticSections;

    PonderSceneCapture(Minecraft minecraft, PonderScene scene, boolean reuseGeometry, PonderCompactModels compact) {
        super(List.of(scene));
        this.scene = scene;
        this.reuseGeometry = reuseGeometry;
        this.compact = compact;
        events = reuseGeometry ? new PonderSceneEvents() : null;
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
            Object barrier = reuseGeometry && tick > 0 ? PonderRenderState.idleBarrier(scene) : null;
            Set<Object> dirtyBlockEntities = Set.of();
            if (tick > 0) {
                try (var entityTick = new PonderEntityTick()) {
                    scene.tick();
                    dirtyBlockEntities = entityTick.dirtyBlockEntities();
                }
            }
            if (barrier != null && barrier != PonderRenderState.idleBarrier(scene)) barrier = null;
            if (compact != null) return compactFrame(assets, dirtyBlockEntities, graphics, barrier);
            setupGuiProjection();
            var frame = new JsonObject();
            var changes = events == null ? null : events.record(scene, dirtyBlockEntities);
            if (changes != null) frame.add("events", changes);
            var cameraMatrix = cameraMatrix();
            var state = barrier == null || geometryChanged(changes) ? null : PonderRenderState.snapshot(scene, cameraMatrix, true);
            boolean stable = state != null && state.canReuse(previousGeometry, barrier, previousBarrier);
            var motion = !stable && state != null && barrier == previousBarrier && rigidGeometry
                    ? state.motionFrom(capturedGeometry) : null;
            if (stable || motion != null) {
                reusedTicks++;
                if (motion != null) {
                    geometryMotion = motion;
                    previousPicking = assets.store("picking", ".json", selection().toString().getBytes(StandardCharsets.UTF_8));
                }
                RenderSystem.enableBlend();
                RenderSystem.disableCull();
                RenderSystem.setupLevelDiffuseLighting((Vector3f) read(DIFFUSE_LIGHT_0, null),
                        (Vector3f) read(DIFFUSE_LIGHT_1, null));
            } else {
                try (var capture = new PonderDrawCapture(assets)) {
                    renderScene(graphics, -1, -1, 0, 1);
                    graphics.flush();
                    previousDraws = capture.draws();
                }
                cameraMatrix = cameraMatrix();
                state = barrier == null ? null : PonderRenderState.snapshot(scene, cameraMatrix, true);
                capturedGeometry = state;
                geometryMotion = null;
                rigidGeometry = state != null && supportsRigidMotion(previousDraws);
                previousPicking = assets.store("picking", ".json", selection().toString().getBytes(StandardCharsets.UTF_8));
            }
            previousGeometry = state;
            previousBarrier = barrier;
            frame.add("scene", previousDraws);
            if (reuseGeometry) {
                if (geometryMotion == null) frame.add("sceneTransform", JsonNull.INSTANCE);
                else {
                    var transform = new JsonObject();
                    transform.add("blocks", PonderDrawCapture.matrix(geometryMotion.blocks()));
                    transform.add("shadow", PonderDrawCapture.matrix(geometryMotion.shadow()));
                    frame.add("sceneTransform", transform);
                }
            }
            frame.add("sceneToGui", PonderDrawCapture.matrix(cameraMatrix));
            frame.addProperty("picking", previousPicking);
            captureOverlay(assets, graphics, frame);
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

    byte[] nativeReference(int tick) throws IOException {
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var camera = minecraft.getCameraEntity();
        int previousTick = ponderTicks;
        var modelView = RenderSystem.getModelViewStack();
        var target = new TextureTarget(WIDTH, HEIGHT, true, Minecraft.ON_OSX);
        modelView.pushMatrix();
        try {
            ponderTicks = tick;
            modelView.identity();
            setupGuiProjection();
            target.setClearColor(0, 0, 0, 0);
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            var graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
            // Compact capture skips the native section layers between reference ticks.
            SuperByteBufferCache.getInstance().invalidate(WorldSectionElementImpl.PONDER_WORLD_SECTION);
            renderScene(graphics, -1, -1, 0, 1);
            graphics.flush();
            try (var pixels = new NativeImage(WIDTH, HEIGHT, false)) {
                RenderSystem.bindTexture(target.getColorTextureId());
                pixels.downloadTexture(0, false);
                pixels.flipY();
                return pixels.asByteArray();
            }
        } finally {
            target.destroyBuffers();
            minecraft.getMainRenderTarget().bindWrite(true);
            ponderTicks = previousTick;
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            if (minecraft.getCameraEntity() != camera) minecraft.setCameraEntity(camera);
            Lighting.setupFor3DItems();
        }
    }

    private JsonObject compactFrame(PonderDrawCapture.Assets assets, Set<Object> dirtyBlockEntities, GuiGraphics graphics,
                                    Object barrier) {
        var frame = new JsonObject();
        scene.getTransform().updateScreenParams(WIDTH, HEIGHT, 0);
        var camera = cameraMatrix();
        frame.add("sceneToGui", PonderDrawCapture.matrix(camera));
        var changes = events.record(scene, dirtyBlockEntities);
        frame.add("events", changes);
        // The compact layout does not use Ponder's section mesh cache. Check positions and block changes directly.
        boolean changed = !dirtyBlockEntities.isEmpty() || geometryChanged(changes);
        if (changed) compactBlocks.clear();
        var state = barrier == null || changed ? null : PonderRenderState.snapshot(scene, camera, false);
        if (state != null && state.canReuse(previousGeometry, barrier, previousBarrier)) {
            reusedTicks++;
            RenderSystem.getModelViewStack().identity();
        } else if (state != null && state.canRepose(previousGeometry, barrier, previousBarrier)
                && reposeCompactGeometry(assets, state)) {
            reusedTicks++;
            RenderSystem.getModelViewStack().identity();
            scene.getTransform().updateSceneRVE(1);
        } else {
            previousCompactGeometry = captureCompactGeometry(assets, graphics);
        }
        previousCompactGeometry.entrySet().forEach(field -> frame.add(field.getKey(), field.getValue()));
        previousGeometry = state;
        previousBarrier = barrier;
        setupGuiProjection();
        captureOverlay(assets, graphics, frame);
        return frame;
    }

    private boolean reposeCompactGeometry(PonderDrawCapture.Assets assets, GeometryState state) {
        // Rendered effects and custom models may depend on more than the section poses.
        if (!previousCompactGeometry.getAsJsonArray("scene").isEmpty()
                || !previousCompactGeometry.getAsJsonArray("movingLayout").isEmpty()
                || !previousCompactGeometry.getAsJsonArray("fluidFaces").isEmpty()) return false;
        if (state.sections().equals(previousGeometry.sections())) return true;

        var nextLayout = state.repose(previousCompactGeometry.getAsJsonArray("layout"));
        var nextPicking = state.repose(previousCompactPicking);
        if (nextLayout == null || nextPicking == null) return false;
        var geometry = new JsonObject();
        previousCompactGeometry.entrySet().forEach(field -> geometry.add(field.getKey(), field.getValue()));
        geometry.add("layout", nextLayout);
        geometry.addProperty("picking", storeCompactPicking(assets, nextPicking));
        previousCompactGeometry = geometry;
        return true;
    }

    private String storeCompactPicking(PonderDrawCapture.Assets assets, JsonArray picking) {
        if (!picking.equals(previousCompactPicking)) {
            previousPicking = assets.store("picking", ".json", picking.toString().getBytes(StandardCharsets.UTF_8));
            previousCompactPicking = picking;
        }
        return previousPicking;
    }

    private JsonObject captureCompactGeometry(PonderDrawCapture.Assets assets, GuiGraphics graphics) {
        var frame = new JsonObject();
        captureMovingParts(assets, graphics, frame);
        var picking = new JsonArray();
        var layout = new JsonArray();
        var collision = CollisionContext.of(minecraft.player);
        scene.forEachVisible(WorldSectionElementImpl.class, section -> {
            var selection = (Selection) read(SELECTION, section);
            if (selection == null || section.isEmpty()) return;
            var transform = PonderDrawCapture.matrix(PonderRenderState.visibleSectionTransform(section));
            var prior = compactBlocks.get(section);
            var selected = sectionPicking(selection, collision, prior);
            picking.add(sectionGroup(transform, selected.blocks()));

            var blocks = prior;
            if (!selected.matchesLayout()) {
                blocks = compactBlockPlacements(selection);
                compactBlocks.put(section, blocks);
            } else {
                reusedStaticSections++;
            }
            layout.add(sectionGroup(transform, blocks.placements()));
        });
        frame.addProperty("picking", storeCompactPicking(assets, picking));
        frame.add("layout", layout);
        return frame;
    }

    private CompactBlocks compactBlockPlacements(Selection selection) {
        var blocks = new JsonArray();
        var snapshot = new ArrayList<SectionBlock>();
        var world = scene.getWorld();
        // Hidden blocks must not affect liquid surfaces in this section.
        world.setMask(selection);
        try {
            for (var position : selection) {
                var state = world.getBlockState(position);
                snapshot.add(new SectionBlock(position.immutable(), state));
                if (state.isAir()) continue;
                int model = compact.model(state, world, position);
                addCompactPlacement(blocks, position, state, model, model >= 0 ? state.getOffset(world, position) : Vec3.ZERO);
                addCompactPlacement(blocks, position, state, compact.liquid(state.getFluidState(), world, position), Vec3.ZERO);
            }
        } finally {
            world.clearMask();
        }
        return new CompactBlocks(snapshot, blocks);
    }

    private void addCompactPlacement(JsonArray blocks, BlockPos position, BlockState state, int model, Vec3 offset) {
        if (model == PonderCompactModels.NO_STATIC_MODEL) return;
        if (model < 0) missingStaticModels.add(state);
        blocks.add(PonderCompactModels.blockPlacement(position, model, offset));
    }

    private record SectionBlock(BlockPos position, BlockState state) {}

    private record CompactBlocks(List<SectionBlock> state, JsonArray placements) {}
    private record SectionPicking(JsonArray blocks, boolean matchesLayout) {}

    private static void setupGuiProjection() {
        float farPlane = ClientHooks.getGuiFarPlane();
        RenderSystem.getModelViewStack().translation(0, 0, 10000 - farPlane);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, WIDTH, HEIGHT, 0, 1000, farPlane),
                VertexSorting.ORTHOGRAPHIC_Z);
    }

    private void captureOverlay(PonderDrawCapture.Assets assets, GuiGraphics graphics, JsonObject frame) {
        var textOverlays = new ArrayList<TextWindowElement>();
        frame.add("captions", captions(textOverlays));
        textOverlays.forEach(element -> element.setVisible(false));
        var pose = graphics.pose();
        pose.pushPose();
        try (var capture = new PonderDrawCapture(assets)) {
            RenderSystem.disableDepthTest();
            pose.translate(0, 0, 100);
            scene.renderOverlay(this, graphics, 1);
            graphics.flush();
            frame.add("overlay", capture.draws());
        } finally {
            pose.popPose();
            textOverlays.forEach(element -> element.setVisible(true));
        }
    }

    private void captureMovingParts(PonderDrawCapture.Assets assets, GuiGraphics graphics, JsonObject frame) {
        // The web player uses this same projection for the shared block models.
        var projection = new Matrix4f().setOrtho(0, WIDTH, HEIGHT, 0, -4096, 4096);
        RenderSystem.getModelViewStack().identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.setupLevelDiffuseLighting((Vector3f) read(DIFFUSE_LIGHT_0, null),
                (Vector3f) read(DIFFUSE_LIGHT_1, null));
        var pose = graphics.pose();
        pose.pushPose();
        try (var capture = new PonderDrawCapture(assets, compact, cameraMatrix())) {
            scene.getTransform().apply(pose, 1);
            scene.getTransform().updateSceneRVE(1);
            var buffers = DefaultSuperRenderTypeBuffer.getInstance();
            scene.renderScene(buffers, graphics, 1);
            buffers.draw();
            graphics.flush();
            frame.add("scene", capture.draws());
            frame.add("movingLayout", capture.movingLayout());
            frame.add("fluidFaces", capture.fluidFaces());
        } finally {
            pose.popPose();
        }
    }

    int reusedTicks() {
        return reusedTicks;
    }

    int reusedStaticSections() {
        return reusedStaticSections;
    }

    JsonArray missingStaticModels() {
        var result = new JsonArray();
        missingStaticModels.stream().map(PonderSceneEvents::stateName).sorted().forEach(result::add);
        return result;
    }

    private Matrix4f cameraMatrix() {
        var pose = new PoseStack();
        scene.getTransform().apply(pose, 1);
        return pose.last().pose();
    }

    private static boolean supportsRigidMotion(JsonArray draws) {
        boolean hasBlocks = false;
        for (var entry : draws) {
            var draw = entry.getAsJsonObject();
            String shader = draw.get("shader").getAsString();
            if (shader.equals("rendertype_gui")) continue;
            if (!RIGID_SHADERS.contains(shader) || draw.get("blend").getAsBoolean()) return false;
            hasBlocks = true;
        }
        return hasBlocks;
    }

    private static boolean geometryChanged(JsonObject events) {
        if (events == null || events.has("blocks")) return true;
        var sections = events.getAsJsonObject("sections");
        if (sections == null) return false;
        return sections.entrySet().stream().anyMatch(change ->
                change.getValue().isJsonNull() || change.getValue().getAsJsonObject().has("positions"));
    }

    private JsonArray selection() {
        var sections = new JsonArray();
        var collision = CollisionContext.of(minecraft.player);
        scene.forEachVisible(WorldSectionElementImpl.class, element -> {
            var selection = (Selection) read(SELECTION, element);
            if (selection == null || element.isEmpty()) return;
            sections.add(sectionGroup(PonderDrawCapture.matrix(PonderRenderState.visibleSectionTransform(element)),
                    sectionPicking(selection, collision, null).blocks()));
        });
        return sections;
    }

    private static JsonObject sectionGroup(JsonArray transform, JsonArray blocks) {
        var section = new JsonObject();
        section.add("transform", transform);
        section.add("blocks", blocks);
        return section;
    }

    private SectionPicking sectionPicking(Selection selection, CollisionContext collision, CompactBlocks prior) {
        var world = scene.getWorld();
        var previous = prior == null ? null : prior.state().iterator();
        var blocks = new JsonArray();
        world.setMask(selection);
        try {
            for (var position : selection) {
                var state = world.getBlockState(position);
                if (previous != null) {
                    var expected = previous.hasNext() ? previous.next() : null;
                    if (expected == null || state != expected.state() || !position.equals(expected.position())) previous = null;
                }
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
        } finally {
            world.clearMask();
        }
        return new SectionPicking(blocks, previous != null && !previous.hasNext());
    }

    private JsonArray captions(List<TextWindowElement> textOverlays) {
        var captions = new JsonArray();
        scene.forEachVisible(TextWindowElement.class, element -> {
            String text = captionText.computeIfAbsent(element, key -> {
                var supplier = (Supplier<?>) read(TEXT, key);
                return supplier.get() instanceof String value ? value : null;
            });
            if (text == null || text.isBlank() || text.length() > 16_384) return;
            var caption = new JsonObject();
            caption.addProperty("text", text);
            caption.addProperty("color", element.getPalette().getColor());
            caption.addProperty("opacity", element.getFade(1));
            var anchor = (Vec3) read(TEXT_ANCHOR, element);
            if (anchor != null) caption.add("anchor", PonderDrawCapture.numbers((float) anchor.x, (float) anchor.y, (float) anchor.z));
            captions.add(caption);
            textOverlays.add(element);
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
