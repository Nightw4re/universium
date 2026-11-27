package com.universium.webcraft.exporter;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.neoforged.neoforge.client.RenderTypeGroup;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class DirectInventoryIconsTest {
    private static final ResourceLocation SPRITE = ResourceLocation.parse("pack:item/replacement");

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void acceptsTheResolvedGeneratedLayerWithStandardBakedGeometry() throws Exception {
        try (var contents = contents(SPRITE, ResourceMetadata.EMPTY)) {
            var sprite = atlasSprite(contents);
            var source = source("""
                    {"parent":"item/generated","textures":{"layer0":"#preview","preview":"pack:item/replacement"}}
                    """);
            assertSame(contents, DirectInventoryIcons.staticSprite(source, bake(source, sprite)));
        }
    }

    @Test
    void rejectsAdditionalLayersTransformsAtlasSubstitutionsAndAnimation() throws Exception {
        var animation = new ResourceMetadata.Builder()
                .put(AnimationMetadataSection.SERIALIZER, AnimationMetadataSection.EMPTY).build();
        try (var contents = contents(SPRITE, ResourceMetadata.EMPTY);
             var animated = contents(SPRITE, animation);
             var substituted = contents(ResourceLocation.parse("pack:item/other"), ResourceMetadata.EMPTY)) {
            var sprite = atlasSprite(contents);
            var plain = source("""
                    {"parent":"item/generated","textures":{"layer0":"pack:item/replacement"}}
                    """);
            var layered = source("""
                    {"parent":"item/generated","textures":{"layer0":"pack:item/replacement","layer1":"pack:item/replacement"}}
                    """);
            var transformed = source("""
                    {"parent":"item/generated","textures":{"layer0":"pack:item/replacement"},
                     "display":{"gui":{"translation":[1,0,0]}}}
                    """);
            assertNull(DirectInventoryIcons.staticSprite(layered, bake(layered, sprite)));
            assertNull(DirectInventoryIcons.staticSprite(transformed, bake(transformed, sprite)));
            assertNull(DirectInventoryIcons.staticSprite(plain, bake(plain, atlasSprite(substituted))));
            assertNull(DirectInventoryIcons.staticSprite(plain, bake(plain, atlasSprite(animated))));
        }
    }

    @Test
    void mapsSourcePixelsToTheCenteredGuiSlotWithoutFlipping() {
        int[] source = new int[16 * 16];
        source[0] = 0xff8040ff;
        source[15] = 0xff00ff00;
        source[15 * 16] = 0xffff0000;
        source[255] = 0xff0000ff;
        source[17] = 0x00ffffff;

        int[] expected = new int[64 * 64];
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                expected[(8 + y) * 64 + 8 + x] = 0xff8040fc;
                expected[(8 + y) * 64 + 53 + x] = 0xff00ff00;
                expected[(53 + y) * 64 + 8 + x] = 0xffff0000;
                expected[(53 + y) * 64 + 53 + x] = 0xff0000fc;
            }
        }
        var result = DirectInventoryIcons.pixels(source, 0xfffffffc);
        assertEquals(64, result.width());
        assertEquals(64, result.height());
        assertArrayEquals(expected, result.rgba());
    }

    @Test
    void leavesEmptyAndPartiallyTransparentTexturesToTheRenderer() {
        int[] source = new int[16 * 16];
        assertNull(DirectInventoryIcons.pixels(source, -1));
        source[16] = 0x7fabcdef;
        assertNull(DirectInventoryIcons.pixels(source, -1));
    }

    private static BlockModel source(String json) {
        var model = BlockModel.fromString(json);
        var parent = BlockModel.fromString("{\"parent\":\"builtin/generated\"}");
        model.resolveParents(id -> id.getPath().equals("item/generated") ? parent : ModelBakery.GENERATION_MARKER);
        return model;
    }

    private static SpriteContents contents(ResourceLocation id, ResourceMetadata metadata) {
        var image = new NativeImage(16, 16, true);
        image.setPixelRGBA(0, 0, -1);
        return new SpriteContents(id, new FrameSize(16, 16), image, metadata);
    }

    private static TextureAtlasSprite atlasSprite(SpriteContents contents) throws ReflectiveOperationException {
        var constructor = TextureAtlasSprite.class.getDeclaredConstructor(ResourceLocation.class, SpriteContents.class,
                int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(TextureAtlas.LOCATION_BLOCKS, contents, 1024, 1024, 16, 32);
    }

    private static BakedModel bake(BlockModel source, TextureAtlasSprite sprite) {
        var generated = new ItemModelGenerator().generateBlockModel(material -> sprite, source);
        var builder = new SimpleBakedModel.Builder(generated, ItemOverrides.EMPTY, false).particle(sprite);
        UnbakedGeometryHelper.bakeElements(generated.getElements(), material -> sprite, BlockModelRotation.X0_Y0)
                .forEach(builder::addUnculledFace);
        return builder.build(RenderTypeGroup.EMPTY);
    }
}
