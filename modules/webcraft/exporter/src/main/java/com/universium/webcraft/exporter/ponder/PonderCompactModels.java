package com.universium.webcraft.exporter.ponder;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntUnaryOperator;

/** Exports reusable block-local vertex buffers from the resource pack's baked models. */
final class PonderCompactModels {
    static final int NO_STATIC_MODEL = -2;
    static final String PATH = "ponder/block-models.json";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int STRIDE = 24;
    private static final int CAPTURED_BLOCK_STRIDE = 32;
    private static final int CAPTURED_UV_OFFSET = 16;
    private static final int MOVING_PART_CACHE_LIMIT = 2048;
    private static final int STATIC_VARIANT_CACHE_LIMIT = 16_384;
    private static final int SPRITE_GRID = 64;
    private static final int MAX_MODEL_DRAWS = 128;
    private static final int UV_PRECISION = 16_384;
    private static final Direction[] MODEL_SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final int[][] FLUID_FACES = {
            {4, 0, 1, 5}, {2, 6, 7, 3}, {2, 3, 1, 0}, {7, 6, 4, 5}, {6, 2, 0, 4}, {3, 7, 5, 1}
    };
    record LiquidShape(int northWest, int northEast, int southWest, int southEast, int faces) {
        int height(int corner) {
            return switch (corner & 5) {
                case 0 -> northWest;
                case 1 -> northEast;
                case 4 -> southWest;
                default -> southEast;
            };
        }
    }
    record LiquidFlow(float sine, float cosine) {
        static LiquidFlow from(Vec3 flow) {
            if (flow.x == 0 && flow.z == 0) return null;
            float angle = (float) Mth.atan2(flow.z, flow.x) - (float) (Math.PI / 2);
            return new LiquidFlow(Mth.sin(angle) * .25f, Mth.cos(angle) * .25f);
        }

        float[] uv(int vertex, float shrink) {
            float u = switch (vertex) {
                case 0 -> -cosine - sine;
                case 1 -> -cosine + sine;
                case 2 -> cosine + sine;
                default -> cosine - sine;
            };
            float v = switch (vertex) {
                case 0 -> -cosine + sine;
                case 1 -> cosine + sine;
                case 2 -> cosine - sine;
                default -> -cosine - sine;
            };
            return new float[]{Mth.lerp(shrink, .5f + u, .5f), Mth.lerp(shrink, .5f + v, .5f)};
        }
    }
    private record LiquidModel(TextureAtlasSprite still, TextureAtlasSprite flowing, TextureAtlasSprite overlay,
                               LiquidShape shape, LiquidFlow flow, int overlayFaces, boolean backwardTop, int tint, String shader) {}
    private record PartMesh(ByteArrayOutputStream vertices, float u, float v) {}
    private record BakedDraw(String shader, TextureAtlasSprite sprite, byte[] vertices) {}
    record QuadUv(float minU, float minV, float maxU, float maxV) {
        static QuadUv read(IntToDoubleFunction coordinate) {
            float minU = 1, minV = 1, maxU = 0, maxV = 0;
            for (int vertex = 0; vertex < 4; vertex++) {
                float u = (float) coordinate.applyAsDouble(vertex * 2);
                float v = (float) coordinate.applyAsDouble(vertex * 2 + 1);
                minU = Math.min(minU, u);
                minV = Math.min(minV, v);
                maxU = Math.max(maxU, u);
                maxV = Math.max(maxV, v);
            }
            return new QuadUv(minU, minV, maxU, maxV);
        }

        boolean fits(TextureAtlasSprite sprite) {
            return minU >= sprite.getU0() && maxU <= sprite.getU1() && minV >= sprite.getV0() && maxV <= sprite.getV1();
        }
    }
    private record PartMaterial(TextureAtlasSprite sprite, PonderDrawCapture.Assets.AtlasPage page) {
        float u(float value) {
            return page == null ? localU(sprite, value) : page.u(value);
        }

        float v(float value) {
            return page == null ? localV(sprite, value) : page.v(value);
        }
    }
    private record SpriteAnimation(JsonArray frames, boolean interpolate) {}
    private record MovingPartKey(RenderType layer, HashCode signature) {}
    record ShadedPartKey(Object buffer, RenderType layer, float red, float green, float blue, float alpha,
                         Vector3f firstLight, Vector3f secondLight) {
        ShadedPartKey {
            firstLight = firstLight == null ? null : new Vector3f(firstLight);
            secondLight = secondLight == null ? null : new Vector3f(secondLight);
        }
    }
    record StaticModel(int id, boolean tinted) {}
    private record StaticVariant(BlockState state, long seed, int visibleFaces) {}
    record MovingPart(int model, JsonArray textureOffsets) {}
    private final Minecraft minecraft;
    private final PonderDrawCapture.Assets assets;
    private final LinkedHashMap<StaticVariant, Integer> states = new LinkedHashMap<>(128, .75f, true);
    private final Map<LiquidModel, Integer> liquids = new LinkedHashMap<>();
    private final LinkedHashMap<MovingPartKey, MovingPart> movingParts = new LinkedHashMap<>(64, .75f, true);
    private final LinkedHashMap<ShadedPartKey, MovingPart> shadedParts = new LinkedHashMap<>(64, .75f, true);
    private final Map<TextureAtlasSprite, String> textures = new IdentityHashMap<>();
    private final Map<TextureAtlasSprite, SpriteAnimation> spriteAnimations = new IdentityHashMap<>();
    private final Map<SpriteAnimation, Integer> spriteAnimationIds = new IdentityHashMap<>();
    private final JsonArray spriteAnimationPalette = new JsonArray();
    private final Map<JsonArray, Integer> shared = new LinkedHashMap<>();
    private final JsonArray models = new JsonArray();
    private final JsonObject blockStates = new JsonObject();
    private final RandomSource random = RandomSource.create();
    private List<List<TextureAtlasSprite>> spritesByCell;
    private int movingPartReuses;
    private int poseOnlyMovingParts;

    PonderCompactModels(Minecraft minecraft, PonderDrawCapture.Assets assets) {
        this.minecraft = minecraft;
        this.assets = assets;
    }

    int model(BlockState state, BlockAndTintGetter world, BlockPos position) {
        if (state.getBlock() instanceof LiquidBlock || state.getRenderShape() != RenderShape.MODEL) return NO_STATIC_MODEL;
        try {
            var model = minecraft.getBlockRenderer().getBlockModel(state);
            var data = model.getModelData(world, position, state, world.getModelData(position));
            return bake(state, model, data, state.getSeed(position), visibleFaces(state, world, position),
                    index -> minecraft.getBlockColors().getColor(state, world, position, index));
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not resolve Ponder model data for {}: {}", state, exception.toString());
            return -1;
        }
    }

    static int visibleFaces(BlockState state, BlockGetter world, BlockPos position) {
        int faces = 0;
        for (var direction : Direction.values()) {
            if (Block.shouldRenderFace(state, world, position, direction, position.relative(direction))) {
                faces |= 1 << direction.ordinal();
            }
        }
        return faces;
    }

    static JsonArray blockPlacement(BlockPos position, int model, Vec3 offset) {
        var placement = PonderDrawCapture.numbers(position.getX(), position.getY(), position.getZ(), model);
        if (offset.x != 0 || offset.y != 0 || offset.z != 0) {
            placement.add((float) offset.x);
            placement.add((float) offset.y);
            placement.add((float) offset.z);
        }
        return placement;
    }

    int liquid(FluidState fluid, BlockAndTintGetter world, BlockPos position) {
        if (fluid.isEmpty()) return NO_STATIC_MODEL;
        try {
            return liquidModel(fluid, world, position);
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not resolve Ponder liquid model for {}: {}", fluid, exception.toString());
            return -1;
        }
    }

    private int liquidModel(FluidState fluid, BlockAndTintGetter world, BlockPos position) {
        var shape = liquidShape(fluid, world, position);
        if (shape.faces() == 0) return NO_STATIC_MODEL;
        var extension = IClientFluidTypeExtensions.of(fluid.getType());
        var textureId = extension.getStillTexture(fluid, world, position);
        String shader = shader(ItemBlockRenderTypes.getRenderLayer(fluid));
        if (textureId == null || shader == null) return -1;
        var atlas = minecraft.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
        var still = atlas.apply(textureId);
        var flowTexture = extension.getFlowingTexture(fluid, world, position);
        var overlayTexture = extension.getOverlayTexture(fluid, world, position);
        int overlayFaces = 0;
        if (overlayTexture != null) {
            for (var side : Direction.Plane.HORIZONTAL) {
                if (!liquidFace(shape, side)) continue;
                var neighbor = position.relative(side);
                if (world.getBlockState(neighbor).shouldDisplayFluidOverlay(world, neighbor, fluid)) {
                    overlayFaces |= 1 << side.ordinal();
                }
            }
        }
        var flow = liquidFace(shape, Direction.UP) ? LiquidFlow.from(fluid.getFlow(world, position)) : null;
        var model = new LiquidModel(still, flowTexture == null ? still : atlas.apply(flowTexture),
                overlayFaces == 0 ? null : atlas.apply(overlayTexture), shape, flow, overlayFaces,
                liquidFace(shape, Direction.UP) && fluid.shouldRenderBackwardUpFace(world, position.above()),
                extension.getTintColor(fluid, world, position), shader);
        return liquids.computeIfAbsent(model, this::bakeLiquid);
    }

    private static boolean liquidFace(LiquidShape shape, Direction side) {
        return (shape.faces() & (1 << side.ordinal())) != 0;
    }

    static LiquidShape liquidShape(FluidState fluid, BlockGetter world, BlockPos position) {
        var type = fluid.getType();
        float center = fluid.getHeight(world, position);
        float north = sampleLiquidHeight(type, world, position.north());
        float south = sampleLiquidHeight(type, world, position.south());
        float west = sampleLiquidHeight(type, world, position.west());
        float east = sampleLiquidHeight(type, world, position.east());
        int nw = liquidCorner(type, world, center, north, west, position.north().west());
        int ne = liquidCorner(type, world, center, north, east, position.north().east());
        int sw = liquidCorner(type, world, center, south, west, position.south().west());
        int se = liquidCorner(type, world, center, south, east, position.south().east());
        var host = world.getBlockState(position);
        var hostOcclusion = host.canOcclude() ? host.getOcclusionShape(world, position) : Shapes.empty();
        int visible = 0;
        for (var side : Direction.values()) {
            if (side != Direction.UP && Shapes.blockOccudes(Shapes.block(), hostOcclusion, side.getOpposite())) continue;
            var neighborPos = position.relative(side);
            var neighbor = world.getBlockState(neighborPos);
            if (neighbor.shouldHideAdjacentFluidFace(side.getOpposite(), fluid)) continue;
            int height = switch (side) {
                case UP -> Math.min(Math.min(nw, ne), Math.min(sw, se));
                case DOWN -> 228;
                case NORTH -> Math.max(nw, ne);
                case SOUTH -> Math.max(sw, se);
                case WEST -> Math.max(nw, sw);
                case EAST -> Math.max(ne, se);
            };
            if (neighbor.canOcclude() && Shapes.blockOccudes(
                    Shapes.box(0, 0, 0, 1, height / 256.0, 1),
                    neighbor.getOcclusionShape(world, neighborPos), side)) continue;
            visible |= 1 << side.ordinal();
        }
        return new LiquidShape(nw, ne, sw, se, visible);
    }

    private static float sampleLiquidHeight(Fluid fluid, BlockGetter world, BlockPos position) {
        var state = world.getBlockState(position);
        var neighbor = state.getFluidState();
        if (fluid.isSame(neighbor.getType())) return neighbor.getHeight(world, position);
        return state.isSolid() ? -1 : 0;
    }

    private static int liquidCorner(Fluid fluid, BlockGetter world, float center, float first, float second, BlockPos diagonal) {
        if (center >= 1 || first >= 1 || second >= 1) return 256;
        float corner = first > 0 || second > 0 ? sampleLiquidHeight(fluid, world, diagonal) : -1;
        if (corner >= 1) return 256;
        float sum = 0;
        int weight = 0;
        for (float height : new float[]{corner, center, second, first}) {
            if (height < 0) continue;
            int contribution = height >= .8f ? 10 : 1;
            sum += height * contribution;
            weight += contribution;
        }
        return Math.clamp(Math.round(sum / weight * 256), 1, 256);
    }

    private int bakeLiquid(LiquidModel model) {
        try {
            var draws = new JsonArray();
            var groups = new LinkedHashMap<TextureAtlasSprite, ByteArrayOutputStream>();
            for (var side : Direction.values()) {
                if (!liquidFace(model.shape(), side)) continue;
                boolean horizontal = side.getAxis().isHorizontal();
                boolean overlay = (model.overlayFaces() & (1 << side.ordinal())) != 0;
                var sprite = overlay ? model.overlay() : horizontal || side == Direction.UP && model.flow() != null
                        ? model.flowing() : model.still();
                var geometry = liquidFaceVertices(model.shape(), side, model.flow(), model.still().uvShrinkRatio(),
                        model.tint(), horizontal ? !overlay : side == Direction.UP && model.backwardTop());
                if (model.shader().equals("rendertype_translucent")) {
                    var draw = liquidDraw(model.shader(), sprite, geometry);
                    draw.add("liquidCenter", liquidFaceCenter(geometry));
                    draws.add(draw);
                } else {
                    groups.computeIfAbsent(sprite, unused -> new ByteArrayOutputStream()).writeBytes(geometry);
                }
            }
            for (var group : groups.entrySet()) {
                draws.add(liquidDraw(model.shader(), group.getKey(), group.getValue().toByteArray()));
            }
            return register(draws);
        } catch (IOException | RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not export Ponder liquid model for {}: {}", model.still().contents().name(), exception.toString());
            return -1;
        }
    }

    private JsonObject liquidDraw(String shader, TextureAtlasSprite sprite, byte[] vertices) throws IOException {
        var draw = new JsonObject();
        draw.addProperty("shader", shader);
        draw.addProperty("liquid", true);
        draw.addProperty("image", texture(sprite));
        addSpriteAnimation(draw, sprite);
        draw.addProperty("vertices", assets.store("meshes", ".bin", vertices));
        draw.addProperty("vertexCount", vertices.length / STRIDE);
        return draw;
    }

    static JsonArray liquidFaceCenter(byte[] vertices) {
        var geometry = ByteBuffer.wrap(vertices).order(ByteOrder.LITTLE_ENDIAN);
        var center = new JsonArray();
        for (int axis = 0; axis < 3; axis++) {
            int offset = axis * Float.BYTES;
            center.add((geometry.getFloat(offset) + geometry.getFloat(STRIDE + offset)
                    + geometry.getFloat(2 * STRIDE + offset) + geometry.getFloat(3 * STRIDE + offset)) / 4);
        }
        return center;
    }

    static byte[] liquidFaceVertices(LiquidShape shape, Direction side, LiquidFlow flow, float shrink, int tint, boolean doubleSided) {
        var vertices = ByteBuffer.allocate((doubleSided ? 8 : 4) * STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        boolean horizontal = side.getAxis().isHorizontal();
        float shade = switch (side) {
            case DOWN -> .5f;
            case NORTH, SOUTH -> .8f;
            case EAST, WEST -> .6f;
            default -> 1f;
        };
        int rgba = tintedVertexColor(tint & 0xff000000 | 0xffffff, tint, shade);
        for (int vertex = 0; vertex < 4; vertex++) {
            int corner = FLUID_FACES[side.ordinal()][vertex];
            float x = corner & 1, z = (corner >>> 2) & 1;
            float surfaceHeight = shape.height(corner) / 256f - (liquidFace(shape, Direction.UP) ? .001f : 0);
            float y = (corner & 2) != 0 ? surfaceHeight : liquidFace(shape, Direction.DOWN) ? .001f : 0;
            if (side.getAxis() == Direction.Axis.X) x += side == Direction.WEST ? .001f : -.001f;
            if (side.getAxis() == Direction.Axis.Z) z += side == Direction.NORTH ? .001f : -.001f;
            vertices.putFloat(x).putFloat(y).putFloat(z).putInt(rgba);
            if (horizontal) {
                vertices.putFloat(vertex == 1 || vertex == 2 ? .5f : 0)
                        .putFloat((corner & 2) == 0 ? .5f : (1 - surfaceHeight) * .5f);
            } else if (side == Direction.UP && flow != null) {
                var uv = flow.uv(vertex, shrink);
                vertices.putFloat(uv[0]).putFloat(uv[1]);
            } else if (side == Direction.UP) {
                vertices.putFloat(Mth.lerp(shrink, x, .5f)).putFloat(Mth.lerp(shrink, z, .5f));
            } else {
                vertices.putFloat(x).putFloat(z);
            }
        }
        if (doubleSided) {
            byte[] contents = vertices.array();
            for (int corner : new int[]{0, 3, 2, 1}) vertices.put(contents, corner * STRIDE, STRIDE);
        }
        return vertices.array();
    }

    JsonObject document() {
        var data = new JsonObject();
        data.addProperty("version", 1);
        data.add("models", models);
        if (!spriteAnimationPalette.isEmpty()) data.add("spriteAnimations", spriteAnimationPalette);
        data.add("blockStates", blockStates);
        return data;
    }

    int modelCount() {
        return models.size();
    }

    int bake(BlockState state, BakedModel model, ModelData data, long seed, int visibleFaces, IntUnaryOperator tint) {
        var variant = new StaticVariant(state, seed, visibleFaces);
        if (data == ModelData.EMPTY) {
            var cached = states.get(variant);
            if (cached != null) return cached;
        }
        StaticModel baked;
        try {
            baked = faces(state, model, data, seed, visibleFaces, tint);
        } catch (IOException | RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not export shared Ponder model {}: {}", state, exception.toString());
            return -1;
        }
        if (data == ModelData.EMPTY && !baked.tinted()) {
            if (states.size() >= STATIC_VARIANT_CACHE_LIMIT) states.pollFirstEntry();
            states.put(variant, baked.id());
            if (baked.id() != NO_STATIC_MODEL) {
                var name = PonderSceneEvents.stateName(state);
                if (!blockStates.has(name)) blockStates.addProperty(name, baked.id());
            }
        }
        return baked.id();
    }

    private int register(JsonArray draws) {
        return shared.computeIfAbsent(draws, value -> {
            int index = models.size();
            models.add(value);
            return index;
        });
    }

    static boolean sharedPartLayer(RenderType type) {
        return type != null && type.format() == DefaultVertexFormat.BLOCK
                && (type == RenderType.solid() || type == RenderType.cutout() || type == RenderType.cutoutMipped());
    }

    MovingPart reuseShadedPart(ShadedPartKey key) {
        var part = shadedParts.get(key);
        if (part != null) poseOnlyMovingParts++;
        return part;
    }

    void rememberShadedPart(ShadedPartKey key, MovingPart part) {
        if (shadedParts.size() >= MOVING_PART_CACHE_LIMIT) shadedParts.pollFirstEntry();
        shadedParts.put(key, part);
    }

    int takePoseOnlyMovingParts() {
        int count = poseOnlyMovingParts;
        poseOnlyMovingParts = 0;
        return count;
    }

    MovingPart movingPart(RenderType layer, MeshData mesh) throws IOException {
        var source = mesh.vertexBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        var key = new MovingPartKey(layer, movingPartSignature(source));
        var cached = movingParts.get(key);
        if (cached != null) {
            movingPartReuses++;
            return cached;
        }
        int vertexCount = mesh.drawState().vertexCount();
        var sprites = new TextureAtlasSprite[vertexCount / 4];
        ByteBuffer unmatched = null;
        for (int quad = 0; quad < sprites.length; quad++) {
            int start = quad * 4 * CAPTURED_BLOCK_STRIDE;
            var sprite = spriteForQuad(source, start);
            sprites[quad] = sprite;
            if (sprite == null) {
                if (unmatched == null) unmatched = ByteBuffer.allocate(vertexCount * 8).order(ByteOrder.LITTLE_ENDIAN);
                for (int vertex = start; vertex < start + 4 * CAPTURED_BLOCK_STRIDE; vertex += CAPTURED_BLOCK_STRIDE) {
                    unmatched.putLong(source.getLong(vertex + CAPTURED_UV_OFFSET));
                }
            }
        }
        if (unmatched != null) unmatched.flip();
        var page = unmatched == null ? null : assets.blockAtlasPage(unmatched, unmatched.remaining() / 8, 8, 0);
        var meshes = new LinkedHashMap<TextureAtlasSprite, PartMesh>();
        var vertices = ByteBuffer.allocate(4 * STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        for (int quad = 0; quad < sprites.length; quad++) {
            int start = quad * 4 * CAPTURED_BLOCK_STRIDE;
            var sprite = sprites[quad];
            var material = new PartMaterial(sprite, sprite == null ? page : null);
            var part = meshes.computeIfAbsent(sprite, unused -> new PartMesh(new ByteArrayOutputStream(),
                    material.u(source.getFloat(start + CAPTURED_UV_OFFSET)),
                    material.v(source.getFloat(start + CAPTURED_UV_OFFSET + 4))));
            vertices.clear();
            for (int vertex = start; vertex < start + 4 * CAPTURED_BLOCK_STRIDE; vertex += CAPTURED_BLOCK_STRIDE) {
                vertices.putInt(source.getInt(vertex)).putInt(source.getInt(vertex + 4))
                        .putInt(source.getInt(vertex + 8)).putInt(source.getInt(vertex + 12));
                // Scrolling textures belong to the placement. Keep only the UV shape in the shared mesh.
                vertices.putFloat(uvRelative(material.u(source.getFloat(vertex + CAPTURED_UV_OFFSET)), part.u(), sprite == null));
                vertices.putFloat(uvRelative(material.v(source.getFloat(vertex + CAPTURED_UV_OFFSET + 4)), part.v(), sprite == null));
            }
            part.vertices().writeBytes(vertices.array());
        }
        var draws = new JsonArray();
        var offsets = new JsonArray();
        for (var entry : meshes.entrySet()) {
            var draw = new JsonObject();
            var sprite = entry.getKey();
            var part = entry.getValue();
            byte[] bytes = part.vertices().toByteArray();
            draw.addProperty("shader", shader(layer));
            if (sprite != null) {
                draw.addProperty("image", texture(sprite));
                addSpriteAnimation(draw, sprite);
            } else if (page != null) {
                page.writeTexture(draw);
            } else {
                draw.addProperty("image", assets.blockAtlasImage());
            }
            draw.addProperty("vertices", assets.store("meshes", ".bin", bytes));
            draw.addProperty("vertexCount", bytes.length / STRIDE);
            draws.add(draw);
            offsets.add(PonderDrawCapture.numbers(part.u(), part.v()));
        }
        if (draws.isEmpty()) return new MovingPart(-1, offsets);
        var part = new MovingPart(register(draws), offsets);
        if (movingParts.size() >= MOVING_PART_CACHE_LIMIT) movingParts.pollFirstEntry();
        movingParts.put(key, part);
        return part;
    }

    static HashCode movingPartSignature(ByteBuffer vertices) {
        // The compact part uses position, color and UV. Light and normal bytes do not survive packing.
        var source = vertices.slice();
        var hash = Hashing.sha256().newHasher();
        int end = source.limit();
        for (int start = 0; start < end; start += CAPTURED_BLOCK_STRIDE) {
            source.limit(start + STRIDE).position(start);
            hash.putBytes(source);
        }
        return hash.hash();
    }

    int takeMovingPartReuses() {
        int count = movingPartReuses;
        movingPartReuses = 0;
        return count;
    }

    private static float localU(TextureAtlasSprite sprite, float u) {
        return sprite == null ? u : (u - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
    }

    private static float localV(TextureAtlasSprite sprite, float v) {
        return sprite == null ? v : (v - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
    }

    static float uvRelative(float value, float origin, boolean atlas) {
        float offset = value - origin;
        // The same normalized rounding interval covers many more texels in an atlas than in a sprite.
        return atlas ? offset : Math.round(offset * UV_PRECISION) / (float) UV_PRECISION;
    }

    private TextureAtlasSprite spriteForQuad(ByteBuffer source, int start) {
        return atlasSprite(QuadUv.read(lane -> source.getFloat(start + lane / 2 * CAPTURED_BLOCK_STRIDE
                + CAPTURED_UV_OFFSET + lane % 2 * 4)));
    }

    static TextureAtlasSprite bakedSprite(BakedQuad quad, Function<QuadUv, TextureAtlasSprite> atlasLookup) {
        int[] vertices = quad.getVertices();
        if (vertices.length != 32) throw new IllegalArgumentException("Unsupported baked block vertex format");
        var declared = quad.getSprite();
        var uv = QuadUv.read(lane -> Float.intBitsToFloat(vertices[lane / 2 * 8 + 4 + lane % 2]));
        float uTolerance = Math.ulp(declared.getU1()) * 2;
        float vTolerance = Math.ulp(declared.getV1()) * 2;
        if (uv.minU() >= declared.getU0() - uTolerance && uv.maxU() <= declared.getU1() + uTolerance
                && uv.minV() >= declared.getV0() - vTolerance && uv.maxV() <= declared.getV1() + vTolerance) return declared;
        // Connected textures can rewrite UVs to another atlas sprite while keeping the declared one.
        return atlasLookup.apply(uv);
    }

    private TextureAtlasSprite atlasSprite(QuadUv uv) {
        if (spritesByCell == null) {
            spritesByCell = new ArrayList<>(SPRITE_GRID * SPRITE_GRID);
            for (int cell = 0; cell < SPRITE_GRID * SPRITE_GRID; cell++) spritesByCell.add(new ArrayList<>());
            var atlas = (TextureAtlas) minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            for (var sprite : atlas.getTextures().values()) {
                for (int x = spriteCell(sprite.getU0()); x <= spriteCell(sprite.getU1()); x++) {
                    for (int y = spriteCell(sprite.getV0()); y <= spriteCell(sprite.getV1()); y++) {
                        spritesByCell.get(y * SPRITE_GRID + x).add(sprite);
                    }
                }
            }
        }
        int col = spriteCell((uv.minU() + uv.maxU()) * 0.5f), row = spriteCell((uv.minV() + uv.maxV()) * 0.5f);
        for (var sprite : spritesByCell.get(row * SPRITE_GRID + col)) {
            if (uv.fits(sprite)) return sprite;
        }
        return null;
    }

    private static int spriteCell(float uv) {
        return Math.clamp((int) (uv * SPRITE_GRID), 0, SPRITE_GRID - 1);
    }

    StaticModel faces(BlockState state, BakedModel model, ModelData data, long seed, int visibleFaces, IntUnaryOperator tint) throws IOException {
        var baked = new ArrayList<BakedDraw>();
        boolean unsupportedLayer = false;
        boolean tinted = false;
        random.setSeed(seed);
        for (var layer : model.getRenderTypes(state, random, data)) {
            String shader = shader(layer);
            if (shader == null) {
                unsupportedLayer = true;
                continue;
            }
            var bySprite = new LinkedHashMap<TextureAtlasSprite, ByteArrayOutputStream>();
            for (var side : MODEL_SIDES) {
                if (side != null && (visibleFaces & (1 << side.ordinal())) == 0) continue;
                random.setSeed(seed);
                for (var quad : model.getQuads(state, side, random, data, layer)) {
                    int color = -1;
                    if (quad.isTinted()) {
                        tinted = true;
                        color = tint.applyAsInt(quad.getTintIndex());
                    }
                    var sprite = bakedSprite(quad, this::atlasSprite);
                    bySprite.computeIfAbsent(sprite, unused -> new ByteArrayOutputStream())
                            .writeBytes(vertices(quad, sprite, color));
                }
            }
            for (var entry : bySprite.entrySet()) {
                if (entry.getValue().size() != 0) baked.add(new BakedDraw(shader, entry.getKey(), entry.getValue().toByteArray()));
            }
        }
        var draws = new JsonArray();
        int extraDraws = Math.max(0, MAX_MODEL_DRAWS - baked.size());
        for (var batch : baked) {
            var sprite = batch.sprite();
            var vertices = batch.vertices();
            if (sprite != null) {
                var draw = blockDraw(batch.shader(), texture(sprite), vertices);
                addSpriteAnimation(draw, sprite);
                draws.add(draw);
                continue;
            }
            var pages = assets.blockAtlasPages(ByteBuffer.wrap(vertices), vertices.length / STRIDE, STRIDE,
                    CAPTURED_UV_OFFSET, extraDraws + 1);
            if (pages.isEmpty()) {
                draws.add(blockDraw(batch.shader(), assets.blockAtlasImage(), vertices));
                continue;
            }
            extraDraws -= pages.size() - 1;
            for (var slice : pages) {
                int start = slice.firstVertex() * STRIDE;
                byte[] local = pages.size() == 1 ? vertices : Arrays.copyOfRange(vertices, start, start + slice.vertexCount() * STRIDE);
                remapAtlasVertices(ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN), slice.page());
                var draw = blockGeometry(batch.shader(), local);
                slice.page().writeTexture(draw);
                draws.add(draw);
            }
        }
        int id = draws.isEmpty() ? (unsupportedLayer ? -1 : NO_STATIC_MODEL) : register(draws);
        return new StaticModel(id, tinted);
    }

    private JsonObject blockDraw(String shader, String image, byte[] vertices) {
        var draw = blockGeometry(shader, vertices);
        draw.addProperty("image", image);
        return draw;
    }

    private JsonObject blockGeometry(String shader, byte[] vertices) {
        var draw = new JsonObject();
        draw.addProperty("shader", shader);
        draw.addProperty("vertices", assets.store("meshes", ".bin", vertices));
        draw.addProperty("vertexCount", vertices.length / STRIDE);
        return draw;
    }

    private static String shader(RenderType layer) {
        if (layer == RenderType.solid()) return "rendertype_solid";
        if (layer == RenderType.cutout()) return "rendertype_cutout";
        if (layer == RenderType.cutoutMipped()) return "rendertype_cutout_mipped";
        if (layer == RenderType.translucent()) return "rendertype_translucent";
        return null;
    }

    static byte[] vertices(BakedQuad quad, TextureAtlasSprite sprite, int tint) {
        int[] source = quad.getVertices();
        float shade = !quad.isShade() ? 1 : switch (quad.getDirection()) {
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            case EAST, WEST -> 0.6f;
            default -> 1;
        };
        var target = ByteBuffer.allocate(4 * STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 32; i += 8) {
            target.putInt(source[i]).putInt(source[i + 1]).putInt(source[i + 2]);
            target.putInt(tintedVertexColor(source[i + 3], tint, shade));
            target.putFloat(localU(sprite, Float.intBitsToFloat(source[i + 4])));
            target.putFloat(localV(sprite, Float.intBitsToFloat(source[i + 5])));
        }
        return target.array();
    }

    static void remapAtlasVertices(ByteBuffer vertices, PonderDrawCapture.Assets.AtlasPage page) {
        for (int vertex = 0; vertex < vertices.limit(); vertex += STRIDE) {
            int u = vertex + CAPTURED_UV_OFFSET, v = u + 4;
            vertices.putFloat(u, page.u(vertices.getFloat(u)));
            vertices.putFloat(v, page.v(vertices.getFloat(v)));
        }
    }

    static int tintedVertexColor(int abgr, int rgb, float shade) {
        int r = Math.round((abgr & 255) * shade * ((rgb >>> 16 & 255) / 255f));
        int g = Math.round((abgr >>> 8 & 255) * shade * ((rgb >>> 8 & 255) / 255f));
        int b = Math.round((abgr >>> 16 & 255) * shade * ((rgb & 255) / 255f));
        return (abgr & 0xff000000) | b << 16 | g << 8 | r;
    }

    String texture(TextureAtlasSprite sprite) throws IOException {
        String cached = textures.get(sprite);
        if (cached != null) return cached;
        var contents = sprite.contents();
        var original = contents.getOriginalImage();
        int index = 0;
        if (original.getWidth() != contents.width() || original.getHeight() != contents.height()) {
            var metadata = contents.metadata().getSection(AnimationMetadataSection.SERIALIZER).orElse(null);
            int columns = original.getWidth() / contents.width();
            int frameCount = columns * (original.getHeight() / contents.height());
            int[] first = {-1};
            if (metadata != null) metadata.forEachFrame((frame, duration) -> {
                if (first[0] == -1 && frame >= 0 && frame < frameCount) first[0] = frame;
            });
            index = Math.max(0, first[0]);
        }
        String image = storeSpriteFrame(contents, index);
        textures.put(sprite, image);
        return image;
    }

    void addSpriteAnimation(JsonObject face, TextureAtlasSprite sprite) {
        var animation = spriteAnimations.computeIfAbsent(sprite, key -> spriteAnimation(key.contents()));
        if (animation.frames().isEmpty()) return;
        var id = spriteAnimationIds.get(animation);
        if (id == null) {
            if (spriteAnimationPalette.size() >= 4096) {
                writeAnimation(face, animation);
                return;
            }
            id = spriteAnimationPalette.size();
            var definition = new JsonObject();
            writeAnimation(definition, animation);
            spriteAnimationPalette.add(definition);
            spriteAnimationIds.put(animation, id);
        }
        face.addProperty("spriteAnimation", id);
    }

    private static void writeAnimation(JsonObject face, SpriteAnimation animation) {
        face.add("frames", animation.frames());
        if (animation.interpolate()) face.addProperty("interpolate", true);
    }

    private SpriteAnimation spriteAnimation(SpriteContents contents) {
        var frames = new JsonArray();
        var still = new SpriteAnimation(frames, false);
        var metadata = contents.metadata().getSection(AnimationMetadataSection.SERIALIZER).orElse(null);
        if (metadata == null) return still;
        var source = contents.getOriginalImage();
        int count = (source.getWidth() / contents.width()) * (source.getHeight() / contents.height());
        if (count < 2 || count > 128) return still;
        var order = new ArrayList<int[]>();
        metadata.forEachFrame((index, duration) -> {
            if (index >= 0 && index < count && duration > 0) order.add(new int[] {index, duration});
        });
        if (order.isEmpty()) {
            contents.getUniqueFrames().forEach(index -> order.add(new int[] {index, metadata.getDefaultFrameTime()}));
        }
        if (order.size() < 2 || order.size() > 128 || order.stream().anyMatch(frame -> frame[1] < 1 || frame[1] > 32767)) return still;
        var images = new String[count];
        try {
            for (var frame : order) {
                int index = frame[0];
                if (images[index] == null) images[index] = storeSpriteFrame(contents, index);
                var entry = new JsonArray();
                entry.add(images[index]);
                entry.add(frame[1]);
                frames.add(entry);
            }
            return new SpriteAnimation(frames, metadata.isInterpolatedFrames());
        } catch (IOException exception) {
            LOGGER.warn("Could not export Ponder sprite animation for {}: {}", contents.name(), exception.toString());
            return new SpriteAnimation(new JsonArray(), false);
        }
    }

    private String storeSpriteFrame(SpriteContents contents, int index) throws IOException {
        var source = contents.getOriginalImage();
        int width = contents.width(), height = contents.height();
        byte[] bytes;
        if (source.getWidth() == width && source.getHeight() == height) {
            bytes = source.asByteArray();
        } else {
            int columns = source.getWidth() / width;
            int left = (index % columns) * width, top = (index / columns) * height;
            try (var frame = new NativeImage(width, height, false)) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) frame.setPixelRGBA(x, y, source.getPixelRGBA(left + x, top + y));
                }
                bytes = frame.asByteArray();
            }
        }
        String image = assets.store("textures", ".png", bytes);
        assets.nameTexture(image, contents.name().toString());
        return image;
    }
}
