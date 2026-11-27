package com.universium.webcraft.exporter.ponder;

import com.google.gson.JsonArray;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PonderCompactModelsTest {
    private static final int ALL_FACES = (1 << Direction.values().length) - 1;

    @BeforeAll
    static void bootstrap() {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void leavesAnimatedAndInvisibleBlocksOutOfTheStaticModelPalette() {
        var models = new PonderCompactModels(null, null);
        assertEquals(PonderCompactModels.NO_STATIC_MODEL, models.model(Blocks.CHEST.defaultBlockState(), null, BlockPos.ZERO));
        assertEquals(PonderCompactModels.NO_STATIC_MODEL, models.model(Blocks.OAK_SIGN.defaultBlockState(), null, BlockPos.ZERO));
        assertEquals(PonderCompactModels.NO_STATIC_MODEL, models.model(Blocks.BARRIER.defaultBlockState(), null, BlockPos.ZERO));
        assertEquals(0, models.document().getAsJsonObject("blockStates").size());
    }

    @Test
    void readsTheWorldLayerOfAModelWithACustomItemRenderer() throws Exception {
        var data = ModelData.builder().with(new ModelProperty<Boolean>(), true).build();
        try (var contents = new SpriteContents(ResourceLocation.parse("example:block/part"), new FrameSize(1, 1),
                new NativeImage(1, 1, true), ResourceMetadata.EMPTY);
             var assets = new PonderDrawCapture.Assets(false)) {
            var sprite = new TextureAtlasSprite(TextureAtlas.LOCATION_BLOCKS, contents, 1, 1, 0, 0) {};
            var face = new BakedQuad(new int[32], -1, Direction.UP, sprite, true);
            var model = new BakedModelWrapper<BakedModel>(null) {
                @Override
                public boolean isCustomRenderer() {
                    return true;
                }

                @Override
                public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData modelData) {
                    assertSame(data, modelData);
                    return ChunkRenderTypeSet.of(RenderType.solid());
                }

                @Override
                public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random,
                                                ModelData modelData, RenderType renderType) {
                    assertSame(data, modelData);
                    return side == null ? List.of(face) : List.of();
                }
            };
            var models = new PonderCompactModels(null, assets);
            var state = Blocks.STONE.defaultBlockState();
            var baked = models.faces(state, model, data, state.getSeed(BlockPos.ZERO), 0, unused -> fail("Untinted face"));
            assertEquals(new PonderCompactModels.StaticModel(0, false), baked);
            var entry = models.document().getAsJsonArray("models").get(0).getAsJsonArray().get(0).getAsJsonObject();
            assertEquals(4, entry.get("vertexCount").getAsInt());
            assertEquals(4 * 24, assets.drain().get(entry.get("vertices").getAsString()).length);
        }
    }

    @Test
    void keepsTheAtlasTileSelectedByConnectedTextureUvs() {
        try (var original = new SpriteContents(ResourceLocation.parse("example:block/tank"), new FrameSize(16, 16),
                     new NativeImage(16, 16, true), ResourceMetadata.EMPTY);
             var connected = new SpriteContents(ResourceLocation.parse("example:block/tank_connected"), new FrameSize(128, 128),
                     new NativeImage(128, 128, true), ResourceMetadata.EMPTY)) {
            var declared = new TextureAtlasSprite(TextureAtlas.LOCATION_BLOCKS, original, 512, 512, 256, 192) {};
            var sheet = new TextureAtlasSprite(TextureAtlas.LOCATION_BLOCKS, connected, 512, 512, 0, 0) {};
            int[] data = new int[32];
            int[][] tile = {{32, 48}, {32, 64}, {48, 64}, {48, 48}};
            for (int i = 0; i < tile.length; i++) {
                data[i * 8 + 4] = Float.floatToRawIntBits(tile[i][0] / 512f);
                data[i * 8 + 5] = Float.floatToRawIntBits(tile[i][1] / 512f);
            }
            var quad = new BakedQuad(data, -1, Direction.UP, declared, true);
            var selected = PonderCompactModels.bakedSprite(quad, uv -> uv.fits(sheet) ? sheet : null);
            assertSame(sheet, selected);
            var exported = ByteBuffer.wrap(PonderCompactModels.vertices(quad, selected, -1)).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < tile.length; i++) {
                assertEquals(tile[i][0] / 128f, exported.getFloat(i * 24 + 16));
                assertEquals(tile[i][1] / 128f, exported.getFloat(i * 24 + 20));
            }
        }
    }

    @Test
    void keepsAtlasCoordinatesWhenAFaceCrossesSpriteBoundaries() {
        try (var contents = new SpriteContents(ResourceLocation.parse("example:block/tank"), new FrameSize(16, 16),
                new NativeImage(16, 16, true), ResourceMetadata.EMPTY)) {
            var declared = new TextureAtlasSprite(TextureAtlas.LOCATION_BLOCKS, contents, 512, 512, 128, 192) {};
            int[] data = new int[32];
            int[][] corners = {{136, 196}, {136, 212}, {152, 212}, {152, 196}};
            for (int i = 0; i < corners.length; i++) {
                data[i * 8 + 4] = Float.floatToRawIntBits(corners[i][0] / 512f);
                data[i * 8 + 5] = Float.floatToRawIntBits(corners[i][1] / 512f);
            }
            var quad = new BakedQuad(data, -1, Direction.UP, declared, true);
            var sprite = PonderCompactModels.bakedSprite(quad, uv -> null);
            assertNull(sprite);
            var exported = ByteBuffer.wrap(PonderCompactModels.vertices(quad, sprite, -1)).order(ByteOrder.LITTLE_ENDIAN);
            var region = new PonderAtlasRegion(128, 192, 32, 32);
            var page = new PonderDrawCapture.Assets.AtlasPage("crop.png", region, 512, 512, null, false);
            for (int i = 0; i < corners.length; i++) {
                assertEquals(corners[i][0] / 512f, exported.getFloat(i * 24 + 16));
                assertEquals(corners[i][1] / 512f, exported.getFloat(i * 24 + 20));
            }
            PonderCompactModels.remapAtlasVertices(exported, page);
            for (int i = 0; i < corners.length; i++) {
                assertEquals(corners[i][0], exported.getFloat(i * 24 + 16) * region.width() + region.x());
                assertEquals(corners[i][1], exported.getFloat(i * 24 + 20) * region.height() + region.y());
            }
        }
    }

    @Test
    void usesTheBlockSeedForEachRenderLayerAndFaceAndCachesVariantsSeparately() {
        var state = Blocks.STONE.defaultBlockState();
        long firstSeed = state.getSeed(new BlockPos(1, 0, 1));
        long secondSeed = state.getSeed(new BlockPos(2, 0, 2));
        int first = RandomSource.create(firstSeed).nextInt();
        int second = RandomSource.create(secondSeed).nextInt();
        assertNotEquals(first, second);
        var choices = new ArrayList<Integer>();
        var faces = new ArrayList<Direction>();
        var model = new BakedModelWrapper<BakedModel>(null) {
            @Override
            public ChunkRenderTypeSet getRenderTypes(BlockState block, RandomSource random, ModelData data) {
                choices.add(random.nextInt());
                random.nextLong();
                return ChunkRenderTypeSet.of(RenderType.solid(), RenderType.cutout());
            }

            @Override
            public List<BakedQuad> getQuads(BlockState block, Direction side, RandomSource random,
                                           ModelData data, RenderType renderType) {
                choices.add(random.nextInt());
                faces.add(side);
                return List.of();
            }
        };
        var models = new PonderCompactModels(null, null);
        IntUnaryOperator tint = unused -> -1;
        assertEquals(PonderCompactModels.NO_STATIC_MODEL, models.bake(state, model, ModelData.EMPTY, firstSeed, ALL_FACES, tint));
        assertEquals(PonderCompactModels.NO_STATIC_MODEL, models.bake(state, model, ModelData.EMPTY, secondSeed, ALL_FACES, tint));
        assertEquals(PonderCompactModels.NO_STATIC_MODEL, models.bake(state, model, ModelData.EMPTY, firstSeed, ALL_FACES, tint));
        assertEquals(Collections.nCopies(15, first), choices.subList(0, 15));
        assertEquals(Collections.nCopies(15, second), choices.subList(15, choices.size()));

        faces.clear();
        int east = 1 << Direction.EAST.ordinal();
        assertEquals(PonderCompactModels.NO_STATIC_MODEL, models.bake(state, model, ModelData.EMPTY, firstSeed, east, tint));
        assertEquals(4, faces.size());
        assertNull(faces.get(0));
        assertEquals(Direction.EAST, faces.get(1));
        assertNull(faces.get(2));
        assertEquals(Direction.EAST, faces.get(3));
    }

    @Test
    void cullsTheInternalGlassFaceWhileKeepingTheFacesAtTheVisibleSectionBoundary() {
        var state = Blocks.GLASS.defaultBlockState();
        var position = BlockPos.ZERO;
        var section = new FluidSection(Map.of(), Map.of(position, state, position.east(), state));
        assertEquals(ALL_FACES & ~(1 << Direction.EAST.ordinal()), PonderCompactModels.visibleFaces(state, section, position));
        var separated = new FluidSection(Map.of(), Map.of(position, state));
        assertEquals(ALL_FACES, PonderCompactModels.visibleFaces(state, separated, position));
    }

    @Test
    void recordsThePlantOffsetWithoutShiftingItsBlockPositionOrModelId() {
        var flower = Blocks.DANDELION.defaultBlockState();
        var position = new BlockPos(8, 2, 7);
        var world = new FluidSection(Map.of(), Map.of(position, flower));
        var offset = flower.getOffset(world, position);
        assertNotEquals(Vec3.ZERO, offset);
        var block = PonderCompactModels.blockPlacement(position, 12, offset);
        assertEquals(PonderDrawCapture.numbers(8, 2, 7, 12, (float) offset.x, (float) offset.y, (float) offset.z), block);
        assertEquals(PonderDrawCapture.numbers(8, 2, 7, 12), PonderCompactModels.blockPlacement(position, 12, Vec3.ZERO));

        var box = flower.getShape(world, position, CollisionContext.empty()).bounds();
        assertEquals(5 / 16.0 + offset.x, box.minX, .00001);
        assertEquals(5 / 16.0 + offset.z, box.minZ, .00001);
    }

    @Test
    void exposesLiquidFacesAtTheSectionBoundary() {
        var position = BlockPos.ZERO;
        var source = Fluids.LAVA.getSource(false);
        var fluids = new HashMap<BlockPos, FluidState>();
        var section = new FluidSection(fluids, Map.of());
        for (var side : Direction.values()) fluids.put(position.relative(side), source);
        for (var side : Direction.Plane.HORIZONTAL) fluids.put(position.relative(side).above(), source);
        assertEquals(1f, source.getHeight(section, position));
        assertEquals(0, PonderCompactModels.liquidShape(source, section, position).faces());

        fluids.remove(position.above());
        fluids.remove(position.east());
        fluids.remove(position.west().above());
        fluids.put(position.west(), Fluids.FLOWING_LAVA.getFlowing(2, false));
        var shape = PonderCompactModels.liquidShape(source, section, position);
        assertEquals((1 << Direction.UP.ordinal()) | (1 << Direction.EAST.ordinal()), shape.faces());
    }

    @Test
    void slopesBetweenDifferentLevelsOfTheSameLiquidWithoutAnInternalWall() {
        var position = BlockPos.ZERO;
        var source = Fluids.WATER.getSource(false);
        var flowing = Fluids.FLOWING_WATER.getFlowing(2, false);
        var section = new FluidSection(Map.of(position, source, position.east(), flowing), Map.of());
        var first = PonderCompactModels.liquidShape(source, section, position);
        var second = PonderCompactModels.liquidShape(flowing, section, position.east());

        assertEquals(190, first.northWest());
        assertEquals(179, first.northEast());
        assertEquals(first.northEast(), second.northWest());
        assertEquals(first.southEast(), second.southWest());
        assertEquals(0, first.faces() & (1 << Direction.EAST.ordinal()));
        assertEquals(0, second.faces() & (1 << Direction.WEST.ordinal()));
    }

    @Test
    void hidesLiquidFacesAgainstOpaqueBlocksAndKeepsFacesAgainstGlass() {
        var position = BlockPos.ZERO;
        var section = new FluidSection(Map.of(), Map.of(
                position.below(), Blocks.STONE.defaultBlockState(),
                position.west(), Blocks.STONE.defaultBlockState(),
                position.east(), Blocks.GLASS.defaultBlockState(),
                position.above(), Blocks.STONE.defaultBlockState()));
        var shape = PonderCompactModels.liquidShape(Fluids.WATER.getSource(false), section, position);
        assertEquals((1 << Direction.UP.ordinal()) | (1 << Direction.NORTH.ordinal())
                | (1 << Direction.SOUTH.ordinal()) | (1 << Direction.EAST.ordinal()), shape.faces());
    }

    @Test
    void hidesWaterAgainstTheSolidFaceOfItsOwnWaterloggedSlab() {
        var position = BlockPos.ZERO;
        var slab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        var fluid = slab.getFluidState();
        var section = new FluidSection(Map.of(position, fluid), Map.of(position, slab));
        var shape = PonderCompactModels.liquidShape(fluid, section, position);

        assertEquals(0, shape.faces() & (1 << Direction.DOWN.ordinal()));
        assertEquals(1 << Direction.UP.ordinal(), shape.faces() & (1 << Direction.UP.ordinal()));
        assertEquals(1 << Direction.EAST.ordinal(), shape.faces() & (1 << Direction.EAST.ordinal()));
    }

    @Test
    void usesTheFlowDirectionAndShrinksTheUpperSpriteCoordinates() {
        var shape = new PonderCompactModels.LiquidShape(256, 256, 256, 256, 1 << Direction.UP.ordinal());
        var flow = PonderCompactModels.LiquidFlow.from(new Vec3(0, 0, 1));
        assertNull(PonderCompactModels.LiquidFlow.from(new Vec3(0, -1, 0)));
        var still = ByteBuffer.wrap(PonderCompactModels.liquidFaceVertices(shape, Direction.UP, null, .2f, -1, false))
                .order(ByteOrder.LITTLE_ENDIAN);
        var flowing = ByteBuffer.wrap(PonderCompactModels.liquidFaceVertices(shape, Direction.UP, flow, .2f, -1, true))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(.999f, still.getFloat(4));
        assertEquals(.1f, still.getFloat(16));
        assertEquals(.1f, still.getFloat(20));
        assertEquals(.3f, flowing.getFloat(16), .00001f);
        assertEquals(.3f, flowing.getFloat(20), .00001f);
        assertArrayEquals(flowing.array(), ByteBuffer.allocate(8 * 24).put(flowing.array(), 0, 4 * 24)
                .put(flowing.array(), 0, 24).put(flowing.array(), 3 * 24, 24)
                .put(flowing.array(), 2 * 24, 24).put(flowing.array(), 24, 24).array());
    }

    @Test
    void pullsTheCroppedFlowingSideAwayFromAdjacentGlass() {
        var shape = new PonderCompactModels.LiquidShape(64, 128, 64, 192,
                1 << Direction.EAST.ordinal() | 1 << Direction.DOWN.ordinal());
        var vertices = ByteBuffer.wrap(PonderCompactModels.liquidFaceVertices(shape, Direction.EAST, null, 0, -1, false))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(.999f, vertices.getFloat(0));
        assertEquals(.5f, vertices.getFloat(4));
        assertEquals(0, vertices.getFloat(16));
        assertEquals(.25f, vertices.getFloat(20));
        assertEquals(.5f, vertices.getFloat(24 + 16));
        assertEquals(.125f, vertices.getFloat(24 + 20));
        assertEquals(.001f, vertices.getFloat(2 * 24 + 4));
        assertEquals(.5f, vertices.getFloat(2 * 24 + 20));
        assertEquals(0xff999999, vertices.getInt(12));
        var center = PonderCompactModels.liquidFaceCenter(vertices.array());
        assertEquals(.999f, center.get(0).getAsFloat());
        assertEquals(.313f, center.get(1).getAsFloat(), .00001f);
        assertEquals(.5f, center.get(2).getAsFloat());
    }

    @Test
    void movingPartReuseDependsOnTheBytesThatReachTheWebModel() {
        var vertices = ByteBuffer.allocateDirect(4 * 32);
        var original = PonderCompactModels.movingPartSignature(vertices);
        vertices.putInt(24, 0x00f000f0);
        vertices.putInt(28, 0x00007f00);
        assertEquals(original, PonderCompactModels.movingPartSignature(vertices));

        vertices.putFloat(16, 0.25f);
        assertNotEquals(original, PonderCompactModels.movingPartSignature(vertices));
        vertices.putFloat(16, 0);
        vertices.putInt(12, 0xffffffff);
        assertNotEquals(original, PonderCompactModels.movingPartSignature(vertices));
        assertEquals(0, vertices.position());
        assertEquals(4 * 32, vertices.limit());
    }

    @Test
    void reusesNeutralModelsOnlyForTheSameBufferAndShading() {
        var models = new PonderCompactModels(null, null);
        var buffer = new Object();
        var layer = RenderType.cutoutMipped();
        var firstLight = new Vector3f(1, 2, 3);
        var secondLight = new Vector3f(3, 2, 1);
        var part = new PonderCompactModels.MovingPart(7, new JsonArray());
        var key = new PonderCompactModels.ShadedPartKey(buffer, layer, 1, 1, 1, 1, firstLight, secondLight);
        assertNull(models.reuseShadedPart(key));
        models.rememberShadedPart(key, part);
        firstLight.set(1, 2, 4);
        assertSame(part, models.reuseShadedPart(new PonderCompactModels.ShadedPartKey(
                buffer, layer, 1, 1, 1, 1, new Vector3f(1, 2, 3), secondLight)));
        assertNull(models.reuseShadedPart(new PonderCompactModels.ShadedPartKey(
                buffer, layer, 1, 1, 1, 1, firstLight, secondLight)));
        assertNull(models.reuseShadedPart(new PonderCompactModels.ShadedPartKey(
                buffer, layer, 1, 1, 1, 1, null, null)));
        assertNull(models.reuseShadedPart(new PonderCompactModels.ShadedPartKey(
                buffer, layer, .5f, 1, 1, 1, new Vector3f(1, 2, 3), secondLight)));
        assertNull(models.reuseShadedPart(new PonderCompactModels.ShadedPartKey(
                new Object(), layer, 1, 1, 1, 1, new Vector3f(1, 2, 3), secondLight)));
        assertNull(models.reuseShadedPart(new PonderCompactModels.ShadedPartKey(
                buffer, RenderType.solid(), 1, 1, 1, 1, new Vector3f(1, 2, 3), secondLight)));
        assertEquals(1, models.takePoseOnlyMovingParts());
        assertEquals(0, models.takePoseOnlyMovingParts());
    }

    @Test
    void emitsEachCatnipPoseWithoutRenderingTheCachedVerticesAgain() {
        var models = new PonderCompactModels(null, null);
        var buffer = new Object();
        var layer = RenderType.cutoutMipped();
        var offsets = new JsonArray();
        offsets.add(PonderDrawCapture.numbers(.25f, .75f));
        var model = new PonderCompactModels.MovingPart(7, offsets);
        models.rememberShadedPart(new PonderCompactModels.ShadedPartKey(buffer, layer, 1, 1, 1, 1, null, null), model);
        var resets = new AtomicInteger();

        try (var memory = new ByteBufferBuilder(1024)) {
            for (int angle = 0; angle < 3; angle++) {
                var transform = new PoseStack();
                transform.last().pose().translation(3, 4, 5).rotateY(angle * (float) (Math.PI / 2));
                try (var capture = new PonderDrawCapture(null, models, new Matrix4f())) {
                    var consumer = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                    PonderDrawCapture.rememberLayer(layer, consumer);
                    assertTrue(PonderDrawCapture.captureShadedPart(buffer, transform, new PoseStack(), consumer,
                            true, true, 1, 1, 1, 1, (pose, target) -> fail("Cached geometry was rendered again"), () -> {
                                transform.last().pose().identity();
                                resets.incrementAndGet();
                            }));
                    var placement = capture.movingLayout().get(0).getAsJsonObject();
                    var matrix = placement.getAsJsonArray("transform");
                    assertEquals(1 - angle, matrix.get(0).getAsFloat());
                    assertEquals(3, matrix.get(12).getAsFloat());
                    assertEquals(7, placement.getAsJsonArray("blocks").get(0).getAsJsonArray().get(3).getAsInt());
                    assertEquals(offsets, placement.getAsJsonArray("textureOffsets"));
                }
            }
        }
        assertEquals(3, models.takePoseOnlyMovingParts());
        assertEquals(3, resets.get());
    }

    @Test
    void keepsAtlasUvOnTheSameSideOfTheTexelBoundary() {
        float first = 0.25f + 0.125f / 8192;
        float second = 0.25f + 0.99f / 8192;
        float atlasOffset = PonderCompactModels.uvRelative(second, first, true);
        float spriteOffset = PonderCompactModels.uvRelative(second, first, false);
        assertEquals(second, first + atlasOffset);
        assertEquals(2048, (int) ((first + atlasOffset) * 8192));
        assertEquals(2049, (int) ((first + spriteOffset) * 8192));
    }

    @Test
    void appliesRgbTintToBakedAbgrVerticesWithoutChangingAlpha() {
        assertEquals(0x80200840, PonderCompactModels.tintedVertexColor(0x80ff40ff, 0x804040, .5f));
        assertEquals(0x80302010, PonderCompactModels.tintedVertexColor(0x80302010, -1, 1));
        assertEquals(0x80000000, PonderCompactModels.tintedVertexColor(0x80ffffff, 0, 1));
    }

    private record FluidSection(Map<BlockPos, FluidState> fluids, Map<BlockPos, BlockState> solids) implements BlockGetter {
        @Override
        public FluidState getFluidState(BlockPos position) {
            return fluids.getOrDefault(position, Fluids.EMPTY.defaultFluidState());
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            return solids.getOrDefault(position, getFluidState(position).createLegacyBlock());
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }
}
