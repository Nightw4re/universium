package com.universium.webcraft.exporter.ponder;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.universium.webcraft.exporter.mixin.LightTextureAccessor;
import com.universium.webcraft.exporter.mixin.PonderNativeImageAccessor;
import com.universium.webcraft.exporter.mixin.PonderSpriteCoordinateAccessor;
import com.universium.webcraft.exporter.mixin.PonderTintedVertexConsumerAccessor;
import com.universium.webcraft.exporter.mixin.PonderVertexBufferAccessor;
import com.universium.webcraft.exporter.mixin.PonderVertexMultiConsumerAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.createmod.catnip.render.PonderRenderTypes;
import net.createmod.catnip.render.TemplateMesh;
import net.createmod.ponder.mixin.client.accessor.RenderSystemAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Records the meshes submitted by one Ponder render pass. Minecraft owns meshes outside that pass. */
public final class PonderDrawCapture implements AutoCloseable {
    private static final ThreadLocal<PonderDrawCapture> ACTIVE = new ThreadLocal<>();
    private final Assets assets;
    private final PonderCompactModels compact;
    private final Matrix4f inverseCamera;
    private final Map<VertexConsumer, RenderType> layers = new IdentityHashMap<>();
    private final Map<VertexConsumer, List<PonderBillboards.Billboard>> particleBuffers = new IdentityHashMap<>();
    private final Map<MeshData, List<PonderBillboards.Billboard>> particleMeshes = new IdentityHashMap<>();
    private PonderBillboards.Basis particleCameraBasis;
    private final Map<BufferBuilder, List<PonderRigidParts.Placement>> rigidBuffers = new IdentityHashMap<>();
    private final Map<MeshData, List<PonderRigidParts.Placement>> rigidMeshes = new IdentityHashMap<>();
    private final Map<BufferBuilder, List<PonderRigidGlint.Placement>> glintBuffers = new IdentityHashMap<>();
    private final Map<MeshData, List<PonderRigidGlint.Placement>> glintMeshes = new IdentityHashMap<>();
    private final Map<BufferBuilder, List<PonderSodiumCuboids.Placement>> sodiumBuffers = new IdentityHashMap<>();
    private final Map<BufferBuilder, List<PonderLightning.Bolt>> lightningBuffers = new IdentityHashMap<>();
    private final Map<MeshData, List<PonderLightning.Bolt>> lightningMeshes = new IdentityHashMap<>();
    private final Map<MeshData, List<PonderSodiumCuboids.Placement>> sodiumMeshes = new IdentityHashMap<>();
    private final Map<BufferBuilder, List<PonderOutlineCuboids.Cuboid>> outlineBuffers = new IdentityHashMap<>();
    private final Map<MeshData, List<PonderOutlineCuboids.Cuboid>> outlineMeshes = new IdentityHashMap<>();
    private final Map<BufferBuilder, List<PonderBoxFaces.Face>> boxFaceBuffers = new IdentityHashMap<>();
    private final Map<MeshData, List<PonderBoxFaces.Face>> boxFaceMeshes = new IdentityHashMap<>();
    private final JsonArray movingLayout = new JsonArray();
    private final JsonArray fluidFaces = new JsonArray();
    private int fluidTiles;
    private boolean buildingPart;
    private final JsonArray draws = new JsonArray();

    public PonderDrawCapture(Assets assets) {
        this(assets, null, null);
    }

    PonderDrawCapture(Assets assets, PonderCompactModels compact, Matrix4f camera) {
        if (ACTIVE.get() != null) throw new IllegalStateException("A Ponder capture is already active");
        this.assets = assets;
        this.compact = compact;
        inverseCamera = camera == null ? null : new Matrix4f(camera).invert();
        ACTIVE.set(this);
    }

    /** Whether the current export pass replaces Ponder's block layers with shared models. */
    public static boolean skipsSectionBlocks() {
        var capture = ACTIVE.get();
        return capture != null && capture.compact != null;
    }

    /** Associates Catnip's consumer with its material during compact playback capture. */
    public static void rememberLayer(RenderType type, VertexConsumer consumer) {
        var capture = ACTIVE.get();
        if (capture != null && capture.compact != null) capture.layers.put(consumer, type);
    }

    /** Remembers the compact parameters without changing the order or contents of the original batch. */
    public static void captureParticle(VertexConsumer consumer, Quaternionf rotation, float x, float y, float z, float size) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null || !(consumer instanceof BufferBuilder)) return;
        var a = new Vector3f(1, -1, 0).rotate(rotation);
        var b = new Vector3f(1, 1, 0).rotate(rotation);
        var basis = new PonderBillboards.Basis(a.x, a.y, a.z, b.x, b.y, b.z);
        capture.particleBuffers.computeIfAbsent(consumer, unused -> new ArrayList<>())
                .add(basis.scale(x, y, z, size));
    }

    public static void setParticleCamera(Camera camera) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null) return;
        if (camera == null) {
            capture.particleCameraBasis = null;
            return;
        }
        var left = camera.getLeftVector();
        var up = camera.getUpVector();
        capture.particleCameraBasis = new PonderBillboards.Basis(
                -left.x() - up.x(), -left.y() - up.y(), -left.z() - up.z(),
                -left.x() + up.x(), -left.y() + up.y(), -left.z() + up.z());
    }

    /** Remembers an outline's local bounds before Catnip transforms its corners. */
    public static void captureOutlineCuboid(PoseStack.Pose pose, VertexConsumer consumer, Vector3fc minimum,
                                            Vector3fc maximum, Vector4fc color, int light, boolean disableDiffuse) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null || !(consumer instanceof BufferBuilder builder)
                || ((PonderVertexBufferAccessor) builder).webcraft$format() != DefaultVertexFormat.NEW_ENTITY) return;
        var cuboids = capture.outlineBuffers.computeIfAbsent(builder, unused -> new ArrayList<>());
        if (cuboids.size() >= PonderOutlineCuboids.MAX_CUBOIDS) return;
        var buffer = (PonderVertexBufferAccessor) builder;
        var cuboid = PonderOutlineCuboids.record(buffer.webcraft$vertexCount(), pose, minimum, maximum, color,
                OverlayTexture.NO_OVERLAY, light, disableDiffuse);
        if (cuboid != null) cuboids.add(cuboid);
    }

    /** Records Sodium's immutable local cuboid and its current pose before the immediate renderer submits it. */
    public static void captureSodiumCuboid(PoseStack.Pose pose, Object writer, Object cuboid) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null || capture.buildingPart) return;
        TextureAtlasSprite sprite = null;
        if (writer instanceof PonderSpriteCoordinateAccessor expander) {
            sprite = expander.webcraft$sprite();
            writer = expander.webcraft$delegate();
        }
        if (!(writer instanceof BufferBuilder builder)
                || ((PonderVertexBufferAccessor) builder).webcraft$format() != DefaultVertexFormat.NEW_ENTITY) return;
        var placements = capture.sodiumBuffers.computeIfAbsent(builder, unused -> new ArrayList<>());
        if (placements.size() >= PonderSodiumCuboids.MAX_PARTS) return;
        var model = capture.assets.localSodiumCuboid(cuboid, sprite);
        if (model == null) return;
        var buffer = (PonderVertexBufferAccessor) builder;
        placements.add(PonderSodiumCuboids.placement(model, buffer.webcraft$vertexCount(), pose.pose()));
    }

    /** Matches the recorded parameters to the exact buffer Ponder sends to the renderer. */
    public static void associateDynamicGeometry(BufferBuilder builder, MeshData mesh) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null) return;
        var billboards = capture.particleBuffers.remove(builder);
        if (mesh != null && billboards != null) capture.particleMeshes.put(mesh, billboards);
        var parts = capture.rigidBuffers.remove(builder);
        if (mesh != null && parts != null) capture.rigidMeshes.put(mesh, parts);
        var glint = capture.glintBuffers.remove(builder);
        if (mesh != null && glint != null) capture.glintMeshes.put(mesh, glint);
        var sodium = capture.sodiumBuffers.remove(builder);
        if (mesh != null && sodium != null) capture.sodiumMeshes.put(mesh, sodium);
        var cuboids = capture.outlineBuffers.remove(builder);
        if (mesh != null && cuboids != null) capture.outlineMeshes.put(mesh, cuboids);
        var faces = capture.boxFaceBuffers.remove(builder);
        if (mesh != null && faces != null) capture.boxFaceMeshes.put(mesh, faces);
        var bolts = capture.lightningBuffers.remove(builder);
        if (mesh != null && bolts != null) capture.lightningMeshes.put(mesh, bolts);
    }

    /** Records the seed and transform of a vanilla lightning bolt without changing its renderer. */
    public static void captureLightning(VertexConsumer consumer, long seed, Matrix4f pose) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null || !(consumer instanceof BufferBuilder builder)) return;
        var buffer = (PonderVertexBufferAccessor) builder;
        if (buffer.webcraft$format() != DefaultVertexFormat.POSITION_COLOR) return;
        var bolt = PonderLightning.record(buffer.webcraft$vertexCount(), seed, pose);
        if (bolt == null) return;
        var bolts = capture.lightningBuffers.computeIfAbsent(builder, unused -> new ArrayList<>());
        if (bolts.size() < PonderLightning.MAX_BOLTS) bolts.add(bolt);
    }

    /** Records a rectangular face's local bounds without changing its original render order. */
    public static void captureBoxFace(VertexConsumer consumer, Matrix4f pose,
                                      float x0, float x1, float y0, float y1, float z0, float z1) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null || capture.buildingPart || !(consumer instanceof BufferBuilder builder)) return;
        var format = ((PonderVertexBufferAccessor) builder).webcraft$format();
        if (format != DefaultVertexFormat.NEW_ENTITY && format != DefaultVertexFormat.BLOCK) return;
        var faces = capture.boxFaceBuffers.computeIfAbsent(builder, unused -> new ArrayList<>());
        if (faces.size() >= PonderBoxFaces.MAX_FACES) return;
        var face = PonderBoxFaces.record(((PonderVertexBufferAccessor) builder).webcraft$vertexCount(), pose, x0, x1, y0, y1, z0, z1);
        if (face != null) faces.add(face);
    }

    /** Associates a model's local geometry with its original range in an entity draw. */
    public static void captureRigidPart(Object model, PoseStack.Pose pose, VertexConsumer consumer,
                                        boolean usePoseNormals,
                                        BiConsumer<PoseStack.Pose, VertexConsumer> render) {
        captureRigidPart(model, pose, consumer, usePoseNormals, false, render);
    }

    /** Records a model variant that transforms positions with a matrix and emits its normals in local coordinates. */
    public static void captureRigidMatrixPart(List<?> model, Object variant, int detail, boolean ponderScreen,
                                              Matrix4f matrix, VertexConsumer consumer, BiConsumer<Matrix4f, VertexConsumer> render) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null || capture.buildingPart || !(consumer instanceof BufferBuilder builder)
                || ((PonderVertexBufferAccessor) builder).webcraft$format() != DefaultVertexFormat.NEW_ENTITY) {
            render.accept(matrix, consumer);
            return;
        }
        var key = capture.assets.rigidVariants.key(model, variant, detail, ponderScreen);
        if (key == null) {
            render.accept(matrix, consumer);
            return;
        }
        var pose = new PoseStack().last();
        pose.pose().set(matrix);
        captureRigidPart(key, pose, consumer, false, (partPose, target) ->
                render.accept(partPose == pose ? matrix : partPose.pose(), target));
    }

    /** Records both render ranges of a foil item while retaining Minecraft's original consumer. */
    public static void captureRigidItemPart(Object model, PoseStack.Pose pose, VertexConsumer consumer,
                                            BiConsumer<PoseStack.Pose, VertexConsumer> render) {
        captureRigidPart(model, pose, consumer, true, true, render);
    }

    private static void captureRigidPart(Object model, PoseStack.Pose pose, VertexConsumer consumer, boolean usePoseNormals,
                                         boolean allowFoil, BiConsumer<PoseStack.Pose, VertexConsumer> render) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null) {
            render.accept(pose, consumer);
            return;
        }
        var submitted = allowFoil && consumer instanceof PonderTintedVertexConsumerAccessor tinted
                ? tinted.webcraft$inner() : consumer;
        var pair = allowFoil && !capture.buildingPart ? foilBuffers(submitted) : null;
        BufferBuilder builder = pair == null ? (submitted instanceof BufferBuilder direct ? direct : null) : pair.base();
        if (builder == null || ((PonderVertexBufferAccessor) builder).webcraft$format() != DefaultVertexFormat.NEW_ENTITY) {
            render.accept(pose, consumer);
            return;
        }
        var buffer = (PonderVertexBufferAccessor) builder;
        var foil = pair == null ? null : (PonderVertexBufferAccessor) pair.glint();
        PonderRigidParts.Part part;
        boolean wasBuildingPart = capture.buildingPart;
        capture.buildingPart = true;
        try {
            part = capture.assets.localRigidPart(model, render);
        } finally {
            capture.buildingPart = wasBuildingPart;
        }
        int start = buffer.webcraft$vertexCount();
        int glintStart = foil == null ? 0 : foil.webcraft$vertexCount();
        render.accept(pose, consumer);
        if (part == null) return;
        boolean baseMatches = buffer.webcraft$vertexCount() - start == part.count();
        boolean glintMatches = foil != null && foil.webcraft$vertexCount() - glintStart == part.count();
        if (!baseMatches && !glintMatches) return;
        var placement = PonderRigidParts.placement(part, start, pose, usePoseNormals);
        if (baseMatches) capture.rigidBuffers.computeIfAbsent(builder, unused -> new ArrayList<>()).add(placement);
        if (glintMatches) {
            var glint = capture.glintBuffers.computeIfAbsent(pair.glint(), unused -> new ArrayList<>());
            if (glint.size() < PonderRigidGlint.MAX_PLACEMENTS) {
                glint.add(new PonderRigidGlint.Placement(part, glintStart, placement.position()));
            }
        }
    }

    private record FoilBuffers(BufferBuilder base, BufferBuilder glint) {}

    private static FoilBuffers foilBuffers(VertexConsumer consumer) {
        if (!(consumer instanceof PonderVertexMultiConsumerAccessor pair)
                || !(pair.webcraft$first() instanceof BufferBuilder first)
                || !(pair.webcraft$second() instanceof BufferBuilder second)) return null;
        var firstFormat = ((PonderVertexBufferAccessor) first).webcraft$format();
        var secondFormat = ((PonderVertexBufferAccessor) second).webcraft$format();
        if (firstFormat == DefaultVertexFormat.NEW_ENTITY && secondFormat == DefaultVertexFormat.POSITION_TEX) {
            return new FoilBuffers(first, second);
        }
        if (firstFormat == DefaultVertexFormat.POSITION_TEX && secondFormat == DefaultVertexFormat.NEW_ENTITY) {
            return new FoilBuffers(second, first);
        }
        return null;
    }

    /** Records a Catnip entity part without altering its transform or consuming the original render. */
    public static void captureCatnipEntityPart(TemplateMesh template, PoseStack transforms, PoseStack pose,
                                               VertexConsumer consumer) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null || capture.buildingPart || !(consumer instanceof BufferBuilder builder)
                || ((PonderVertexBufferAccessor) builder).webcraft$format() != DefaultVertexFormat.NEW_ENTITY) return;
        var part = capture.assets.localCatnipPart(template);
        if (part == null) return;
        var buffer = (PonderVertexBufferAccessor) builder;
        var placement = PonderRigidParts.catnipPlacement(part, buffer.webcraft$vertexCount(), pose.last(), transforms.last(), true);
        capture.rigidBuffers.computeIfAbsent(builder, unused -> new ArrayList<>()).add(placement);
    }

    public static void captureCatnipDefaultPart(ByteBuffer template, int stride, boolean fullNormals,
                                                PoseStack transforms, PoseStack pose, VertexConsumer consumer) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null || capture.buildingPart || !(consumer instanceof BufferBuilder builder)
                || ((PonderVertexBufferAccessor) builder).webcraft$format() != DefaultVertexFormat.NEW_ENTITY) return;
        var part = capture.assets.localCatnipDefaultPart(template, stride);
        if (part == null) return;
        var buffer = (PonderVertexBufferAccessor) builder;
        var placement = PonderRigidParts.catnipPlacement(part, buffer.webcraft$vertexCount(), pose.last(), transforms.last(), fullNormals);
        capture.rigidBuffers.computeIfAbsent(builder, unused -> new ArrayList<>()).add(placement);
    }

    /** Records Catnip's tiled fluid face before it emits its temporary vertices. */
    public static boolean captureFluidFace(Direction side, float left, float bottom, float right, float top, float plane,
                                           VertexConsumer consumer, PoseStack pose, int light, int tint,
                                           TextureAtlasSprite sprite, float scale) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null || capture.layers.get(consumer) != PonderRenderTypes.fluid()) return false;
        if (left >= right || bottom >= top || !(scale > 0 && scale <= 1) || light < 0 || light > 0xffffff) return false;
        var bounds = new float[] {left, bottom, right, top, plane};
        for (float coordinate : bounds) if (!(Math.abs(coordinate) <= 4096)) return false;
        float shrink = sprite.uvShrinkRatio() * .25f * scale;
        if (!(shrink >= 0 && shrink <= 1)) return false;
        int tiles = (int) ((Math.ceil(right) - Math.floor(left)) * (Math.ceil(top) - Math.floor(bottom)));
        if (capture.fluidFaces.size() >= 256 || capture.fluidTiles + tiles > 1024) return false;
        var modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
        var matrix = modelView.get(new float[16]);
        for (float value : matrix) if (!(Math.abs(value) <= 1e6)) return false;
        try {
            var face = new JsonObject();
            face.add("geometry", numbers(side.ordinal(), left, bottom, right, top, plane, scale, shrink, light));
            face.add("transform", numbers(matrix));
            face.addProperty("tint", tint);
            face.addProperty("image", capture.compact.texture(sprite));
            face.addProperty("lightmap", capture.assets.lightmap());
            capture.compact.addSpriteAnimation(face, sprite);
            capture.fluidFaces.add(face);
            capture.fluidTiles += tiles;
            return true;
        } catch (IOException exception) {
            // Preserve the original draw when a required texture cannot be exported.
            return false;
        }
    }

    /** Bakes a moving Catnip part without its pose so repeated frames share its mesh. */
    public static boolean capturePart(PoseStack transforms, PoseStack pose, VertexConsumer consumer,
                                      BiConsumer<PoseStack, VertexConsumer> render) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null || capture.buildingPart) return false;
        var layer = capture.layers.get(consumer);
        if (!PonderCompactModels.sharedPartLayer(layer) || capture.movingLayout.size() >= 512) return false;
        capture.writePart(transforms.last(), pose, layer, render, null, null);
        return true;
    }

    public static boolean captureShadedPart(Object buffer, PoseStack transforms, PoseStack pose, VertexConsumer consumer,
                                            boolean stableMaterial, boolean disableDiffuse, float r, float g, float b, float a,
                                            BiConsumer<PoseStack, VertexConsumer> render, Runnable reset) {
        var capture = ACTIVE.get();
        if (capture == null || capture.compact == null || capture.buildingPart) return false;
        var layer = capture.layers.get(consumer);
        if (!PonderCompactModels.sharedPartLayer(layer) || capture.movingLayout.size() >= 512) return false;
        var key = stableMaterial ? shadedPartKey(buffer, layer, disableDiffuse, r, g, b, a) : null;
        capture.writePart(transforms.last(), pose, layer, render, key, reset);
        return true;
    }

    private static PonderCompactModels.ShadedPartKey shadedPartKey(Object buffer, RenderType layer, boolean disableDiffuse,
                                                                   float r, float g, float b, float a) {
        if (disableDiffuse) return new PonderCompactModels.ShadedPartKey(buffer, layer, r, g, b, a, null, null);
        var shaderPack = ShaderPackProbe.IS_IN_USE;
        if (shaderPack == null) return null;
        try {
            if ((boolean) shaderPack.invoke(null)) {
                return new PonderCompactModels.ShadedPartKey(buffer, layer, r, g, b, a, null, null);
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        }
        var lights = RenderSystemAccessor.catnip$getShaderLightDirections();
        return new PonderCompactModels.ShadedPartKey(buffer, layer, r, g, b, a, lights[0], lights[1]);
    }

    private static final class ShaderPackProbe {
        private static final Method IS_IN_USE = find();

        private static Method find() {
            try {
                return Class.forName("dev.engine_room.flywheel.lib.util.ShadersModHelper").getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError exception) {
                return null;
            }
        }
    }

    private void writePart(PoseStack.Pose local, PoseStack pose, RenderType layer, BiConsumer<PoseStack, VertexConsumer> render,
                           PonderCompactModels.ShadedPartKey key, Runnable reset) {
        var transform = new Matrix4f(inverseCamera).mul(pose.last().pose()).mul(local.pose());
        var cached = key == null ? null : compact.reuseShadedPart(key);
        if (cached != null) {
            reset.run();
            addMovingPart(transform, cached);
            return;
        }
        local.pose().identity();
        local.normal().identity();
        buildingPart = true;
        try {
            var builder = new BufferBuilder(assets.partBuffer(), layer.mode(), layer.format());
            render.accept(new PoseStack(), builder);
            try (var mesh = builder.build()) {
                if (mesh == null) return;
                var part = compact.movingPart(layer, mesh);
                if (part.model() < 0) return;
                if (key != null) compact.rememberShadedPart(key, part);
                addMovingPart(transform, part);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            buildingPart = false;
        }
    }

    private void addMovingPart(Matrix4f transform, PonderCompactModels.MovingPart part) {
        var placement = new JsonObject();
        var values = transform.get(new float[16]);
        for (int index = 0; index < values.length; index++) {
            values[index] = Math.round(values[index] * 1_000_000f) / 1_000_000f;
        }
        placement.add("transform", numbers(values));
        var blocks = new JsonArray();
        blocks.add(numbers(0, 0, 0, part.model()));
        placement.add("blocks", blocks);
        placement.add("textureOffsets", part.textureOffsets());
        movingLayout.add(placement);
    }

    JsonArray movingLayout() {
        return movingLayout;
    }

    JsonArray fluidFaces() {
        return fluidFaces;
    }

    public static boolean record(MeshData mesh) {
        var capture = ACTIVE.get();
        if (capture == null) return false;
        try (mesh) {
            capture.write(mesh);
        }
        return true;
    }

    public JsonArray draws() {
        return draws;
    }

    @Override
    public void close() {
        ACTIVE.remove();
    }

    private void write(MeshData mesh) {
        var state = mesh.drawState();
        var format = state.format();
        var draw = new JsonObject();
        draw.addProperty("mode", state.mode().name());
        draw.addProperty("vertexCount", state.vertexCount());
        draw.addProperty("indexCount", state.indexCount());
        draw.addProperty("stride", format.getVertexSize());
        var indices = mesh.indexBuffer();
        var shader = RenderSystem.getShader();
        String shaderName = shader == null ? "" : shader.getName();
        var bolts = lightningMeshes.remove(mesh);
        var lightning = bolts != null && format == DefaultVertexFormat.POSITION_COLOR
                && state.mode() == VertexFormat.Mode.QUADS && shaderName.equals("rendertype_lightning")
                ? PonderLightning.encode(mesh.vertexBuffer(), state.vertexCount(), bolts) : null;
        var billboards = particleMeshes.remove(mesh);
        var quads = compact != null && indices == null && format == DefaultVertexFormat.PARTICLE
                && state.mode() == VertexFormat.Mode.QUADS && shaderName.equals("particle")
                ? PonderBillboards.encode(mesh.vertexBuffer(), billboards == null ? List.of() : billboards, state.vertexCount(),
                        particleCameraBasis) : null;
        if (billboards != null && billboards.size() * 4 == state.vertexCount()) assets.trackedParticleBatches++;
        if (quads != null && quads.version() == 4) assets.sharedParticleOrientationBatches++;
        var cuboids = outlineMeshes.remove(mesh);
        var outlines = cuboids != null && indices == null && format == DefaultVertexFormat.NEW_ENTITY
                && state.mode() == VertexFormat.Mode.QUADS && shaderName.equals("rendertype_entity_solid")
                ? PonderOutlineCuboids.encode(mesh.vertexBuffer(), state.vertexCount(), cuboids) : null;
        var sodium = sodiumMeshes.remove(mesh);
        var sodiumBatch = sodium != null && indices == null && format == DefaultVertexFormat.NEW_ENTITY
                && state.mode() == VertexFormat.Mode.QUADS
                ? PonderSodiumCuboids.encode(mesh.vertexBuffer(), state.vertexCount(), sodium) : null;
        var glintPlacements = glintMeshes.remove(mesh);
        var glintBatch = glintPlacements != null && indices == null && format == DefaultVertexFormat.POSITION_TEX
                && state.mode() == VertexFormat.Mode.QUADS && shaderName.equals("rendertype_glint")
                ? PonderRigidGlint.encode(mesh.vertexBuffer(), state.vertexCount(), glintPlacements) : null;
        var parts = rigidMeshes.remove(mesh);
        var faces = boxFaceMeshes.remove(mesh);
        boolean chainFaces = format == DefaultVertexFormat.BLOCK && shaderName.equals("rendertype_cutout_mipped");
        boolean entityFaces = format == DefaultVertexFormat.NEW_ENTITY
                && (shaderName.equals("rendertype_entity_translucent_cull") || shaderName.equals("rendertype_entity_no_outline"));
        var boxFaces = faces != null && state.mode() == VertexFormat.Mode.QUADS && (chainFaces
                || entityFaces)
                ? PonderBoxFaces.encode(mesh.vertexBuffer(), state.vertexCount(), faces, chainFaces) : null;
        var mixedBoxFaces = outlines == null && sodiumBatch == null && boxFaces == null && faces != null
                && entityFaces && state.mode() == VertexFormat.Mode.QUADS
                ? PonderBoxFaces.encodeMixed(mesh.vertexBuffer(), state.vertexCount(), faces, parts == null ? List.of() : parts) : null;
        var rigids = outlines == null && sodiumBatch == null && boxFaces == null && compact != null && format == DefaultVertexFormat.NEW_ENTITY
                && state.mode() == VertexFormat.Mode.QUADS && (mixedBoxFaces == null || mixedBoxFaces.rigidParts().isEmpty())
                ? PonderRigidParts.encode(mesh.vertexBuffer(), state.vertexCount(), parts == null ? List.of() : parts,
                        reason -> assets.recordRigidFallback(shaderName, reason, mesh.vertexBuffer().remaining())) : null;
        if (lightning != null) {
            draw.addProperty("vertices", assets.store("lightning/v1", ".bin", lightning));
        } else if (outlines != null) {
            draw.addProperty("vertices", assets.store("outline-cuboids/v1", ".bin", outlines));
        } else if (sodiumBatch != null) {
            for (var placement : sodium) assets.storeRigidPart(placement.model().part());
            draw.addProperty("vertices", assets.store("rigid-batches/v3", ".bin", sodiumBatch));
        } else if (boxFaces != null) {
            draw.addProperty("vertices", assets.store(chainFaces ? "box-faces/v2" : "box-faces/v1", ".bin", boxFaces));
        } else if (rigids != null) {
            assets.storeRigidBatchParts(rigids);
            var bytes = PonderSparsePoses.pack(rigids.bytes(), rigids.version());
            int version = bytes == rigids.bytes() ? rigids.version() : 5;
            draw.addProperty("vertices", assets.store("rigid-batches/v" + version, ".bin", bytes));
        } else if (mixedBoxFaces != null) {
            for (var batch : mixedBoxFaces.rigidParts()) assets.storeRigidBatchParts(batch);
            draw.addProperty("vertices", assets.store("box-faces/v" + mixedBoxFaces.version(), ".bin", mixedBoxFaces.bytes()));
        } else if (glintBatch != null) {
            for (var placement : glintPlacements) assets.storeRigidPart(placement.part());
            draw.addProperty("vertices", assets.store("rigid-glint/v1", ".bin", glintBatch));
        } else if (quads != null) {
            draw.addProperty("vertices", assets.store("particle-quads/v" + quads.version(), ".bin", quads.bytes()));
        } else draw.addProperty("vertices", assets.store("meshes", ".bin", bytes(mesh.vertexBuffer())));
        if (indices != null) {
            draw.addProperty("indices", assets.store("meshes", ".bin", bytes(indices)));
            draw.addProperty("indexType", state.indexType().name());
        }
        var attributes = new JsonArray();
        for (var element : format.getElements()) {
            var attribute = new JsonObject();
            attribute.addProperty("name", format.getElementName(element));
            attribute.addProperty("offset", format.getOffset(element));
            attribute.addProperty("type", element.type().name());
            attribute.addProperty("count", element.count());
            attributes.add(attribute);
        }
        draw.add("attributes", attributes);
        draw.addProperty("shader", shaderName);
        var textures = new JsonObject();
        Matrix4f textureMatrix = RenderSystem.getTextureMatrix();
        for (int slot = 0; slot < 3; slot++) {
            if ((slot != 0 && (shaderName.equals("rendertype_eyes") || shaderName.equals("rendertype_crumbling")))
                    || (slot == 1 && (shaderName.equals("rendertype_entity_no_outline")
                    || shaderName.equals("rendertype_armor_cutout_no_cull")))) continue;
            int id = RenderSystem.getShaderTexture(slot);
            if (!format.hasUV(slot) || id <= 0) continue;
            var region = slot == 0 ? assets.cropBlockAtlas(id, mesh.vertexBuffer(), state.vertexCount(),
                    format.getVertexSize(), format.getOffset(VertexFormatElement.UV0), textureMatrix,
                    state.mode() == VertexFormat.Mode.QUADS) : null;
            var texture = region == null ? assets.texture(id, slot == 0 && shaderName.contains("text")) : region.texture();
            if (region != null) textureMatrix = region.matrix();
            if (slot != 0 && texture != null && texture.has("atlasAnimation")) {
                texture = texture.deepCopy();
                texture.remove("atlasAnimation");
                texture.addProperty("animated", true);
            }
            if (texture != null) textures.add(Integer.toString(slot), texture);
        }
        draw.add("textures", textures);
        draw.add("projection", matrix(RenderSystem.getProjectionMatrix()));
        draw.add("modelView", matrix(RenderSystem.getModelViewMatrix()));
        draw.add("textureMatrix", matrix(textureMatrix));
        float[] color = RenderSystem.getShaderColor();
        if (shaderName.equals("rendertype_glint")) {
            float alpha = RenderSystem.getShaderGlintAlpha();
            draw.add("color", numbers(color[0] * alpha, color[1] * alpha, color[2] * alpha, color[3]));
        } else draw.add("color", numbers(color));
        draw.addProperty("depthTest", GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        draw.addProperty("depthFunction", GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        draw.addProperty("depthWrite", GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        draw.addProperty("cull", GL11.glIsEnabled(GL11.GL_CULL_FACE));
        draw.addProperty("blend", GL11.glIsEnabled(GL11.GL_BLEND));
        if (GL11.glIsEnabled(GL11.GL_BLEND)) {
            draw.add("blendFactors", numbers(GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                    GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)));
        }
        if (state.mode().name().contains("LINE")) draw.addProperty("lineWidth", RenderSystem.getShaderLineWidth());
        draws.add(draw);
    }

    static JsonArray matrix(Matrix4f matrix) {
        return numbers(matrix.get(new float[16]));
    }

    static JsonArray numbers(float... values) {
        var array = new JsonArray(values.length);
        for (float value : values) array.add(value);
        return array;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        var bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    static final class RigidVariants {
        private record Variant(List<?> model, Object section, int detail, boolean ponderScreen) {}

        private final Map<Variant, Variant> keys = new HashMap<>();

        Object key(List<?> model, Object section, int detail, boolean ponderScreen) {
            // Hypertube rebuilds its ring list for each call and changes the normals in Ponder.
            var requested = new Variant(model, section, detail, ponderScreen);
            var cached = keys.get(requested);
            if (cached != null) return cached;
            if (keys.size() >= 256) return null;
            var stored = new Variant(List.copyOf(model), section, detail, ponderScreen);
            keys.put(stored, stored);
            return stored;
        }
    }

    public static final class Assets implements AutoCloseable {
        private record FontImage(Long version, String image) {}
        private record AtlasSample(JsonObject texture, Matrix4f matrix) {}
        private record AnimatedAtlasSprite(TextureAtlasSprite sprite, PonderAtlasRegion region) {}
        private record AtlasAnimation(String path, boolean containsStillSprites) {
            void writeTo(JsonObject texture) {
                if (path != null) texture.addProperty("atlasAnimation", path);
                if (containsStillSprites) texture.addProperty("animated", true);
            }
        }
        record AtlasPage(String image, PonderAtlasRegion region, int atlasWidth, int atlasHeight,
                         String animation, boolean animated) {
            float u(float atlasU) {
                return (atlasU * atlasWidth - region.x()) / region.width();
            }

            float v(float atlasV) {
                return (atlasV * atlasHeight - region.y()) / region.height();
            }

            void writeTexture(JsonObject draw) {
                draw.addProperty("image", image);
                if (animation != null) draw.addProperty("atlasAnimation", animation);
                if (animated) draw.addProperty("animated", true);
            }
        }
        record AtlasPageSlice(int firstVertex, int vertexCount, AtlasPage page) {}
        private static final Matrix4fc IDENTITY_UV = new Matrix4f();
        private static final List<net.minecraft.resources.ResourceLocation> ATLAS_LOCATIONS = List.of(
                TextureAtlas.LOCATION_BLOCKS, TextureAtlas.LOCATION_PARTICLES, Sheets.BED_SHEET, Sheets.SIGN_SHEET,
                Sheets.CHEST_SHEET, Sheets.BANNER_SHEET, Sheets.SHIELD_SHEET, Sheets.SHULKER_SHEET,
                Sheets.ARMOR_TRIMS_SHEET, Sheets.DECORATED_POT_SHEET);

        private final Set<String> stored = new HashSet<>();
        private final Map<Integer, JsonObject> textures = new HashMap<>();
        private final Map<Integer, AtlasAnimation> atlasAnimations = new HashMap<>();
        private final Map<Integer, String> atlasNames = new HashMap<>();
        private final Map<String, String> textureSources = new LinkedHashMap<>();
        private final Map<Integer, FontImage> fontImages = new HashMap<>();
        private final PonderTextureCapture textTextures;
        private final int atlasPageSize;
        private PonderAtlasReadback atlasReadback;
        private final Map<PonderAtlasRegion, JsonObject> blockAtlasRegions = new HashMap<>();
        private final Map<PonderAtlasRegion.Packed, JsonObject> packedBlockAtlasRegions = new HashMap<>();
        private TextureAtlas blockAtlas;
        private int blockAtlasWidth;
        private int blockAtlasHeight;
        private long blockAtlasBoundsFallbacks;
        private long blockAtlasReadbackFallbacks;
        private List<AnimatedAtlasSprite> animatedSprites;
        private final Map<TextureAtlasSprite, JsonObject> regionalSpriteAnimations = new IdentityHashMap<>();
        private ByteBufferBuilder partBuffer;
        private ByteBufferBuilder rigidBuffer;
        private final Map<Object, PonderRigidParts.Part> rigidParts = new IdentityHashMap<>();
        private final RigidVariants rigidVariants = new RigidVariants();
        private final Map<SodiumCuboidKey, PonderSodiumCuboids.Model> sodiumCuboids = new HashMap<>();
        private final Map<TemplateMesh, PonderRigidParts.Part> catnipParts = new IdentityHashMap<>();
        private final Map<ByteBuffer, PonderRigidParts.Part> catnipDefaultParts = new IdentityHashMap<>();
        private final Map<String, Map<PonderRigidParts.Fallback, RigidFallbackStats>> rigidFallbacks = new LinkedHashMap<>();
        private String lightmapImage;
        private Map<String, byte[]> pending = new LinkedHashMap<>();
        private long meshSubmissions;
        private long trackedParticleBatches;
        private long sharedParticleOrientationBatches;

        Assets(boolean experimentalCuda) {
            this(experimentalCuda, 0);
        }

        Assets(boolean experimentalCuda, int atlasPageSize) {
            textTextures = new PonderTextureCapture(experimentalCuda);
            this.atlasPageSize = atlasPageSize;
        }

        @Override
        public void close() {
            textTextures.close();
            if (atlasReadback != null) atlasReadback.close();
            if (partBuffer != null) partBuffer.close();
            if (rigidBuffer != null) rigidBuffer.close();
        }

        ByteBufferBuilder partBuffer() {
            if (partBuffer == null) partBuffer = new ByteBufferBuilder(65_536);
            return partBuffer;
        }

        void recordRigidFallback(String shader, PonderRigidParts.Fallback reason, int bytes) {
            var count = rigidFallbacks.computeIfAbsent(shader, unused -> new LinkedHashMap<>())
                    .computeIfAbsent(reason, unused -> new RigidFallbackStats());
            count.submissions++;
            count.submittedBytes += bytes;
        }

        JsonObject takeRigidFallbacks() {
            var output = new JsonObject();
            rigidFallbacks.forEach((shader, reasons) -> {
                var totals = new JsonObject();
                reasons.forEach((reason, count) -> {
                    var entry = new JsonObject();
                    entry.addProperty("submissions", count.submissions);
                    entry.addProperty("submittedBytes", count.submittedBytes);
                    totals.add(reason.name(), entry);
                });
                output.add(shader, totals);
            });
            rigidFallbacks.clear();
            return output;
        }

        JsonObject takeParticleOrientations() {
            var output = new JsonObject();
            if (trackedParticleBatches > 0) output.addProperty("trackedBatches", trackedParticleBatches);
            if (sharedParticleOrientationBatches > 0) output.addProperty("sharedBatches", sharedParticleOrientationBatches);
            trackedParticleBatches = sharedParticleOrientationBatches = 0;
            return output;
        }

        private static final class RigidFallbackStats {
            private long submissions;
            private long submittedBytes;
        }

        PonderRigidParts.Part localRigidPart(Object model, BiConsumer<PoseStack.Pose, VertexConsumer> render) {
            if (rigidParts.containsKey(model)) return rigidParts.get(model);
            if (rigidParts.size() >= 4096) return null;
            if (rigidBuffer == null) rigidBuffer = new ByteBufferBuilder(32_768);
            var neutral = new BufferBuilder(rigidBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            render.accept(new PoseStack().last(), neutral);
            try (var mesh = neutral.build()) {
                var part = mesh == null ? null : PonderRigidParts.Part.localMesh(mesh.vertexBuffer(), mesh.drawState().vertexCount());
                rigidParts.put(model, part);
                return part;
            }
        }

        void storeRigidPart(PonderRigidParts.Part part) {
            storeRigidAsset("rigid-parts", part.bytes(), part.digest());
        }

        void storeRigidBatchParts(PonderRigidParts.Batch batch) {
            for (var part : batch.parts()) storeRigidPart(part);
            for (var attributes : batch.attributes()) storeRigidAttributes(attributes);
            for (var normals : batch.normals()) storeRigidNormals(normals);
        }

        private record SodiumCuboidKey(Object cuboid, TextureAtlasSprite sprite) {}

        PonderSodiumCuboids.Model localSodiumCuboid(Object cuboid, TextureAtlasSprite sprite) {
            var key = new SodiumCuboidKey(cuboid, sprite);
            if (sodiumCuboids.containsKey(key)) return sodiumCuboids.get(key);
            if (sodiumCuboids.size() >= 4096) return null;
            var bounds = sprite == null ? null : new PonderSodiumCuboids.SpriteUv(sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1());
            var model = PonderSodiumCuboids.readModel(cuboid, bounds);
            sodiumCuboids.put(key, model);
            return model;
        }

        PonderRigidParts.Part localCatnipPart(TemplateMesh template) {
            if (catnipParts.containsKey(template)) return catnipParts.get(template);
            int count = template.vertexCount();
            if (catnipParts.size() >= 4096 || count < 4 || count > PonderRigidParts.MAX_PART_VERTICES || count % 4 != 0) return null;
            var bytes = ByteBuffer.allocate(count * 24).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                bytes.putFloat(template.x(i)).putFloat(template.y(i)).putFloat(template.z(i));
                bytes.putFloat(template.u(i)).putFloat(template.v(i)).putInt(template.normal(i) & 0xffffff);
            }
            var part = PonderRigidParts.Part.fromLocal(bytes.array(), count);
            catnipParts.put(template, part);
            return part;
        }

        PonderRigidParts.Part localCatnipDefaultPart(ByteBuffer template, int stride) {
            if (catnipDefaultParts.containsKey(template)) return catnipDefaultParts.get(template);
            if (catnipDefaultParts.size() >= 4096) return null;
            var part = PonderRigidParts.Part.catnipBlockTemplate(template, stride);
            catnipDefaultParts.put(template, part);
            return part;
        }

        void storeRigidAttributes(PonderRigidParts.Attributes attributes) {
            storeRigidAsset("rigid-attributes", attributes.bytes(), attributes.digest());
        }

        void storeRigidNormals(PonderRigidParts.Normals normals) {
            storeRigidAsset("rigid-normals", normals.bytes(), normals.digest());
        }

        private void storeRigidAsset(String kind, byte[] bytes, byte[] digest) {
            String path = "ponder/" + kind + "/v1/" + HexFormat.of().formatHex(digest) + ".bin";
            if (stored.add(path)) pending.put(path, bytes);
        }

        public Map<String, byte[]> drain() {
            var files = pending;
            pending = new LinkedHashMap<>();
            return files;
        }

        String store(String directory, String extension, byte[] bytes) {
            if (directory.equals("meshes")) meshSubmissions++;
            String path = "ponder/" + directory + "/" + Hashing.sha256().hashBytes(bytes) + extension;
            if (stored.add(path)) pending.put(path, bytes);
            return path;
        }

        long meshSubmissions() {
            return meshSubmissions;
        }

        private AtlasSample cropBlockAtlas(int id, ByteBuffer vertices, int count, int stride, int uvOffset,
                                           Matrix4f matrix, boolean quads) {
            if (!prepareBlockAtlas() || id != blockAtlas.getId()) return null;
            var region = PonderAtlasRegion.select(vertices, count, stride, uvOffset, matrix,
                    blockAtlasWidth, blockAtlasHeight, atlasPageSize);
            if (quads) {
                var packed = PonderAtlasRegion.packSparseQuads(vertices, count, stride, uvOffset, matrix,
                        blockAtlasWidth, blockAtlasHeight, atlasPageSize, region);
                if (packed != null) {
                    var page = packedBlockAtlasRegions.computeIfAbsent(packed, this::readPackedBlockAtlas);
                    if (page != null) return new AtlasSample(page, matrix);
                    blockAtlasReadbackFallbacks++;
                }
            }
            var texture = blockAtlasRegionTexture(region);
            if (texture != null) return new AtlasSample(texture, region.textureMatrix(matrix, blockAtlasWidth, blockAtlasHeight));
            if (region == null) blockAtlasBoundsFallbacks++;
            return null;
        }

        AtlasPage blockAtlasPage(ByteBuffer vertices, int count, int stride, int uvOffset) {
            if (!prepareBlockAtlas()) return null;
            var region = selectBlockAtlasRegion(vertices, count, stride, uvOffset, IDENTITY_UV);
            var texture = blockAtlasRegionTexture(region);
            return texture == null ? null : atlasPage(texture, region);
        }

        List<AtlasPageSlice> blockAtlasPages(ByteBuffer vertices, int count, int stride, int uvOffset, int maxSlices) {
            if (!prepareBlockAtlas()) return List.of();
            var plan = PonderAtlasRegion.partition(vertices, count, stride, uvOffset, IDENTITY_UV,
                    blockAtlasWidth, blockAtlasHeight, atlasPageSize, maxSlices);
            if (plan.isEmpty()) blockAtlasBoundsFallbacks++;
            var result = new ArrayList<AtlasPageSlice>(plan.size());
            for (var slice : plan) {
                var texture = blockAtlasRegionTexture(slice.region());
                if (texture == null) return List.of();
                result.add(new AtlasPageSlice(slice.firstVertex(), slice.vertexCount(), atlasPage(texture, slice.region())));
            }
            return result;
        }

        private AtlasPage atlasPage(JsonObject texture, PonderAtlasRegion region) {
            return new AtlasPage(texture.get("image").getAsString(), region, blockAtlasWidth, blockAtlasHeight,
                    texture.has("atlasAnimation") ? texture.get("atlasAnimation").getAsString() : null, texture.has("animated"));
        }

        private boolean prepareBlockAtlas() {
            if (atlasPageSize == 0 || !GL.getCapabilities().OpenGL30) return false;
            if (blockAtlas == null) {
                var manager = Minecraft.getInstance().getTextureManager();
                if (!(manager.getTexture(TextureAtlas.LOCATION_BLOCKS, null) instanceof TextureAtlas atlas)) return false;
                blockAtlas = atlas;
                atlasReadback = new PonderAtlasReadback(GL.getCapabilities().OpenGL45);
                int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                RenderSystem.bindTexture(atlas.getId());
                try {
                    blockAtlasWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                    blockAtlasHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                } finally {
                    RenderSystem.bindTexture(previous);
                }
            }
            return blockAtlasWidth > 0 && blockAtlasHeight > 0;
        }

        private PonderAtlasRegion selectBlockAtlasRegion(ByteBuffer vertices, int count, int stride, int uvOffset, Matrix4fc matrix) {
            var region = PonderAtlasRegion.select(vertices, count, stride, uvOffset, matrix,
                    blockAtlasWidth, blockAtlasHeight, atlasPageSize);
            if (region == null) blockAtlasBoundsFallbacks++;
            return region;
        }

        private JsonObject blockAtlasRegionTexture(PonderAtlasRegion region) {
            if (region == null) return null;
            var texture = blockAtlasRegions.get(region);
            if (texture == null) {
                texture = readBlockAtlasRegion(blockAtlas.getId(), region);
                if (texture == null) {
                    blockAtlasReadbackFallbacks++;
                    return null;
                }
                blockAtlasRegions.put(region, texture);
            }
            return texture;
        }

        private JsonObject readBlockAtlasRegion(int id, PonderAtlasRegion region) {
            try (var image = new NativeImage(region.width(), region.height(), false)) {
                long pixels = ((PonderNativeImageAccessor) (Object) image).webcraft$pixelPointer();
                if (!atlasReadback.read(id, region, pixels)) return null;
                String path = store("textures", ".png", image.asByteArray());
                nameTexture(path, TextureAtlas.LOCATION_BLOCKS + " [" + region.x() + "," + region.y()
                        + " " + region.width() + "x" + region.height() + "]");
                return atlasTexture(path, List.of(new PonderAtlasRegion.Placement(region, 0, 0)));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private JsonObject readPackedBlockAtlas(PonderAtlasRegion.Packed packed) {
            try (var image = new NativeImage(packed.width(), packed.height(), true)) {
                long pixels = ((PonderNativeImageAccessor) (Object) image).webcraft$pixelPointer();
                var mapping = new JsonArray(packed.placements().size());
                for (var placement : packed.placements()) {
                    var region = placement.region();
                    long destination = pixels + 4L * (placement.y() * packed.width() + placement.x());
                    if (!atlasReadback.read(blockAtlas.getId(), region, destination, packed.width())) return null;
                    mapping.add(numbers(region.x() / (float) blockAtlasWidth, region.y() / (float) blockAtlasHeight,
                            (region.x() + region.width()) / (float) blockAtlasWidth,
                            (region.y() + region.height()) / (float) blockAtlasHeight,
                            blockAtlasWidth / (float) packed.width(), blockAtlasHeight / (float) packed.height(),
                            (placement.x() - region.x()) / (float) packed.width(),
                            (placement.y() - region.y()) / (float) packed.height()));
                }
                String path = store("textures", ".png", image.asByteArray());
                nameTexture(path, TextureAtlas.LOCATION_BLOCKS + " [" + packed.placements().size() + " regions, "
                        + packed.width() + "x" + packed.height() + "]");
                var texture = atlasTexture(path, packed.placements());
                texture.add("atlasRegions", mapping);
                return texture;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private JsonObject atlasTexture(String path, List<PonderAtlasRegion.Placement> regions) throws IOException {
            var texture = new JsonObject();
            texture.addProperty("image", path);
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            RenderSystem.bindTexture(blockAtlas.getId());
            try {
                texture.addProperty("minFilter", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER));
                texture.addProperty("magFilter", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER));
            } finally {
                RenderSystem.bindTexture(previous);
            }
            texture.addProperty("wrapS", GL12.GL_CLAMP_TO_EDGE);
            texture.addProperty("wrapT", GL12.GL_CLAMP_TO_EDGE);
            if (animatedSprites == null) {
                animatedSprites = atlasSprites(blockAtlas, blockAtlasWidth, blockAtlasHeight);
            }
            writeAtlasAnimation(regions, animatedSprites).writeTo(texture);
            return texture;
        }

        private static List<AnimatedAtlasSprite> atlasSprites(TextureAtlas atlas, int width, int height) {
            return atlas.getTextures().values().stream()
                    .filter(sprite -> sprite.contents().getUniqueFrames().limit(2).count() > 1)
                    .sorted(java.util.Comparator.comparing(sprite -> sprite.contents().name()))
                    .map(sprite -> new AnimatedAtlasSprite(sprite, new PonderAtlasRegion(Math.round(sprite.getU0() * width),
                            Math.round(sprite.getV0() * height), sprite.contents().width(), sprite.contents().height())))
                    .toList();
        }

        private AtlasAnimation writeAtlasAnimation(List<PonderAtlasRegion.Placement> regions,
                                                   List<AnimatedAtlasSprite> sprites) throws IOException {
            var copies = new JsonArray();
            boolean still = false;
            for (var region : regions) {
                for (var animated : sprites) {
                    var copy = PonderAtlasRegion.clipSprite(region, animated.region());
                    if (copy == null) continue;
                    var sprite = animated.sprite();
                    if (!regionalSpriteAnimations.containsKey(sprite)) {
                        regionalSpriteAnimations.put(sprite, regionalSpriteAnimation(sprite));
                    }
                    var definition = regionalSpriteAnimations.get(sprite);
                    if (definition == null) {
                        still = true;
                        continue;
                    }
                    var patch = new JsonObject();
                    definition.entrySet().forEach(field -> patch.add(field.getKey(), field.getValue()));
                    patch.add("rect", numbers(copy.x(), copy.y(), copy.width(), copy.height()));
                    patch.add("source", numbers(copy.sourceX(), copy.sourceY(), animated.region().width(), animated.region().height()));
                    copies.add(patch);
                }
            }
            if (copies.size() > 4096) return new AtlasAnimation(null, true);
            String animation = copies.isEmpty() ? null : store("textures", ".json",
                    copies.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new AtlasAnimation(animation, still);
        }

        private JsonObject regionalSpriteAnimation(TextureAtlasSprite sprite) throws IOException {
            var contents = sprite.contents();
            var source = contents.getOriginalImage();
            var metadata = contents.metadata().getSection(AnimationMetadataSection.SERIALIZER).orElse(null);
            if (metadata == null || source.getWidth() > 2048 || source.getHeight() > 2048) return null;
            int count = (source.getWidth() / contents.width()) * (source.getHeight() / contents.height());
            var frames = new JsonArray();
            metadata.forEachFrame((index, duration) -> {
                if (index >= 0 && index < count && duration > 0) frames.add(numbers(index, duration));
            });
            if (frames.isEmpty()) {
                contents.getUniqueFrames().forEach(frame -> frames.add(numbers(frame, metadata.getDefaultFrameTime())));
            }
            if (frames.size() < 2 || frames.size() > 4096) return null;
            for (var frame : frames) {
                int duration = frame.getAsJsonArray().get(1).getAsInt();
                if (duration < 1 || duration > 32767) return null;
            }
            String image = store("textures", ".png", source.asByteArray());
            nameTexture(image, contents.name().toString());
            var definition = new JsonObject();
            definition.addProperty("image", image);
            definition.add("frames", frames);
            if (metadata.isInterpolatedFrames()) definition.addProperty("interpolate", true);
            return definition;
        }

        private JsonObject texture(int id, boolean refresh) {
            if (!refresh && textures.containsKey(id)) return textures.get(id);
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            RenderSystem.bindTexture(id);
            try {
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (width == 0 || height == 0) return null;
                String image = refresh ? textImage(id, width, height)
                        : downloadTexture(width, height);
                var texture = new JsonObject();
                texture.addProperty("image", image);
                texture.addProperty("minFilter", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER));
                texture.addProperty("magFilter", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER));
                texture.addProperty("wrapS", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S));
                texture.addProperty("wrapT", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T));
                var animation = atlasAnimation(id, width, height);
                if (animation != null) animation.writeTo(texture);
                String atlasName = atlasNames.get(id);
                if (atlasName != null) textureSources.putIfAbsent(image, atlasName);
                textures.put(id, texture);
                return texture;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            } finally {
                RenderSystem.bindTexture(previous);
            }
        }

        String blockAtlasImage() {
            int id = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId();
            return texture(id, false).get("image").getAsString();
        }

        String lightmap() throws IOException {
            if (lightmapImage == null) {
                var renderer = Minecraft.getInstance().gameRenderer.lightTexture();
                var image = ((LightTextureAccessor) renderer).webcraft$getLightPixels();
                lightmapImage = store("textures", ".png", image.asByteArray());
                nameTexture(lightmapImage, "minecraft:lightmap");
            }
            return lightmapImage;
        }

        JsonObject textureDiagnostics() {
            var sources = new JsonObject();
            textureSources.forEach(sources::addProperty);
            return sources;
        }

        JsonObject atlasDiagnostics() {
            var report = new JsonObject();
            report.addProperty("pageSize", atlasPageSize);
            if (blockAtlas != null) {
                report.addProperty("width", blockAtlasWidth);
                report.addProperty("height", blockAtlasHeight);
                report.addProperty("backend", atlasReadback.backend());
            }
            report.addProperty("regions", blockAtlasRegions.size());
            report.addProperty("packedPages", packedBlockAtlasRegions.size());
            report.addProperty("boundsFallbacks", blockAtlasBoundsFallbacks);
            report.addProperty("readbackFallbacks", blockAtlasReadbackFallbacks);
            return report;
        }

        void nameTexture(String path, String name) {
            textureSources.putIfAbsent(path, name);
        }

        private String textImage(int id, int width, int height) {
            var generation = PonderFontVersions.version(id);
            var cached = fontImages.get(id);
            if (generation != null && cached != null && generation.equals(cached.version)) return cached.image;
            String image = textTextures.image(id, width, height, () -> downloadTexture(width, height));
            if (generation != null) fontImages.put(id, new FontImage(generation, image));
            else fontImages.remove(id);
            return image;
        }

        private String downloadTexture(int width, int height) {
            try (var image = new NativeImage(width, height, false)) {
                image.downloadTexture(0, false);
                return store("textures", ".png", image.asByteArray());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private AtlasAnimation atlasAnimation(int id, int width, int height) throws IOException {
            if (atlasAnimations.containsKey(id)) return atlasAnimations.get(id);
            var manager = Minecraft.getInstance().getTextureManager();
            AtlasAnimation animation = null;
            for (var location : ATLAS_LOCATIONS) {
                if (manager.getTexture(location, null) instanceof TextureAtlas atlas && atlas.getId() == id) {
                    atlasNames.put(id, location.toString());
                    var sprites = atlas == blockAtlas && animatedSprites != null ? animatedSprites : atlasSprites(atlas, width, height);
                    animation = width > 2048 || height > 2048 ? new AtlasAnimation(null, !sprites.isEmpty())
                            : writeAtlasAnimation(List.of(new PonderAtlasRegion.Placement(new PonderAtlasRegion(0, 0, width, height), 0, 0)), sprites);
                    break;
                }
            }
            atlasAnimations.put(id, animation);
            return animation;
        }

    }
}
