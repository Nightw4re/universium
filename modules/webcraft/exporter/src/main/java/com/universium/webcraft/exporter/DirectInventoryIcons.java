package com.universium.webcraft.exporter;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Transformation;
import com.universium.webcraft.exporter.mixin.ItemColorsAccessor;
import com.universium.webcraft.exporter.mixin.LightTextureAccessor;
import com.universium.webcraft.exporter.mixin.ModelBakeryAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.model.RegistryAwareItemModelShaper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class DirectInventoryIcons {
    private static final int SOURCE_SIZE = InventoryIconRenderer.HALF_SLOT * 2;
    private static final float UV_EPSILON = 0.000001F;
    private static final List<String> GUI_SHADERS = List.of(
            "core/rendertype_entity_translucent_cull.json",
            "core/rendertype_entity_translucent_cull.vsh",
            "core/rendertype_entity_translucent_cull.fsh",
            "include/light.glsl", "include/fog.glsl");
    private final Minecraft minecraft;
    private final BooleanSupplier standardShaders;
    private final Map<ModelResourceLocation, CachedSprite> sprites = new HashMap<>();
    private ModelBakery bakery;
    private boolean vanillaGuiShaders;

    DirectInventoryIcons(Minecraft minecraft) {
        this.minecraft = minecraft;
        standardShaders = shaderPackCheck();
    }

    PngPixels capture(ItemStack stack) {
        RenderSystem.assertOnRenderThread();
        try {
            return captureIfSupported(stack);
        } catch (RuntimeException | LinkageError exception) {
            // The authoritative renderer still captures the stack and its usual diagnostics.
            return null;
        }
    }

    private PngPixels captureIfSupported(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() instanceof BlockItem || stack.is(Items.TRIDENT) || stack.is(Items.SPYGLASS)
                || !stack.getComponentsPatch().isEmpty() || stack.hasFoil()
                || IClientItemExtensions.of(stack) != IClientItemExtensions.DEFAULT
                || ((ItemColorsAccessor) minecraft.getItemColors()).webcraft$getItemColors().containsKey(stack.getItem())
                || !standardShaders.getAsBoolean()
                || !(RenderSystem.getShaderFogStart() >= 160 || RenderSystem.getShaderFogColor()[3] == 0)) {
            return null;
        }
        if (!(minecraft.getItemRenderer().getItemModelShaper() instanceof RegistryAwareItemModelShaper shaper)) {
            return null;
        }
        var manager = shaper.getModelManager();
        var currentBakery = manager.getModelBakery();
        if (bakery != currentBakery) {
            sprites.clear();
            bakery = currentBakery;
            vanillaGuiShaders = GUI_SHADERS.stream().allMatch(shader -> minecraft.getResourceManager()
                    .getResource(ResourceLocation.withDefaultNamespace("shaders/" + shader))
                    .filter(resource -> resource.sourcePackId().equals("vanilla")).isPresent());
        }
        if (!vanillaGuiShaders) return null;

        var location = shaper.getLocation(stack);
        var baked = shaper.getItemModel(stack);
        if (baked == manager.getMissingModel() || baked != manager.getModel(location)
                || baked.getClass() != SimpleBakedModel.class
                || !baked.getRenderTypes(stack, true).equals(List.of(Sheets.translucentCullBlockSheet()))) {
            return null;
        }
        var cached = sprites.get(location);
        if (cached == null || cached.model() != baked) {
            var unbaked = ((ModelBakeryAccessor) bakery).webcraft$getTopLevelModels().get(location);
            var contents = unbaked instanceof BlockModel blockModel ? staticSprite(blockModel, baked) : null;
            cached = new CachedSprite(baked, contents);
            sprites.put(location, cached);
        }
        var contents = cached.contents();
        if (contents == null) return null;

        // The vanilla entity shader fetches this texel for GuiGraphics' packed full-bright light.
        var lightmap = ((LightTextureAccessor) minecraft.gameRenderer.lightTexture()).webcraft$getLightPixels();
        return pixels(contents.getOriginalImage().getPixelsRGBA(), lightmap.getPixelRGBA(15, 15));
    }

    private static BooleanSupplier shaderPackCheck() {
        var mods = ModList.get();
        if (mods.isLoaded("optifine") || mods.isLoaded("citresewn") || mods.isLoaded("cit_resewn")) return () -> false;
        if (!mods.isLoaded("iris") && !mods.isLoaded("oculus")) return () -> true;
        try {
            var api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            var instance = api.getMethod("getInstance").invoke(null);
            var active = api.getMethod("isShaderPackInUse");
            return () -> {
                try {
                    return Boolean.FALSE.equals(active.invoke(instance));
                } catch (ReflectiveOperationException exception) {
                    return false;
                }
            };
        } catch (ReflectiveOperationException | LinkageError exception) {
            return () -> false;
        }
    }

    static SpriteContents staticSprite(BlockModel source, BakedModel baked) {
        if (!standardGeneratedModel(source) || baked.getClass() != SimpleBakedModel.class || baked.isGui3d()
                || baked.usesBlockLight() || baked.getOverrides() != ItemOverrides.EMPTY
                || !identityGuiTransform(baked.getTransforms().getTransform(ItemDisplayContext.GUI))) {
            return null;
        }
        var random = RandomSource.create(42);
        for (var direction : Direction.values()) {
            if (!baked.getQuads(null, direction, random).isEmpty()) return null;
        }
        var quads = baked.getQuads(null, null, random);
        if (quads.isEmpty()) return null;
        var sprite = quads.getFirst().getSprite();
        var contents = sprite.contents();
        var material = source.getMaterial("layer0");
        float shrink = sprite.uvShrinkRatio();
        if (sprite.getClass() != TextureAtlasSprite.class || contents.getClass() != SpriteContents.class
                || !TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())
                || !material.atlasLocation().equals(sprite.atlasLocation()) || !material.texture().equals(contents.name())
                || MissingTextureAtlasSprite.getLocation().equals(contents.name())
                || contents.metadata().getSection(AnimationMetadataSection.SERIALIZER).isPresent()
                || contents.width() != SOURCE_SIZE || contents.height() != SOURCE_SIZE
                || contents.getOriginalImage().getWidth() != SOURCE_SIZE
                || contents.getOriginalImage().getHeight() != SOURCE_SIZE
                || !(shrink >= 0 && shrink < 1.0F / (SOURCE_SIZE * InventoryIconRenderer.GUI_SCALE))) {
            return null;
        }
        return standardQuads(quads, sprite, shrink) ? contents : null;
    }

    private static boolean standardGeneratedModel(BlockModel source) {
        if (source.getClass() != BlockModel.class || source.getRootModel() != ModelBakery.GENERATION_MARKER
                || !source.getElements().isEmpty()
                || source.getGuiLight() != BlockModel.GuiLight.FRONT || !source.hasTexture("layer0")
                || source.customData.hasCustomGeometry() || source.customData.getRenderTypeHint() != null
                || !Transformation.identity().equals(source.customData.getRootTransform())) {
            return false;
        }
        return ItemModelGenerator.LAYERS.stream().skip(1).noneMatch(source::hasTexture);
    }

    private static boolean identityGuiTransform(ItemTransform transform) {
        // ItemTransform.equals in this version omits NeoForge's right-side rotation.
        return ItemTransform.NO_TRANSFORM.equals(transform) && transform.rightRotation.lengthSquared() == 0;
    }

    private static boolean standardQuads(List<BakedQuad> quads, TextureAtlasSprite sprite, float shrink) {
        int frontAndBack = 0;
        for (var quad : quads) {
            if (quad.getClass() != BakedQuad.class || quad.getSprite() != sprite || quad.getTintIndex() != 0) return false;
            var vertices = quad.getVertices();
            if (vertices.length != 32) return false;
            var direction = quad.getDirection();
            if (direction.getAxis() == Direction.Axis.Z) {
                int face = direction == Direction.SOUTH ? 1 : 2;
                if ((frontAndBack & face) != 0 || !standardFace(quad, sprite, shrink)) return false;
                frontAndBack |= face;
            } else {
                // The extruded edge quads have zero screen area in the identity GUI pose.
                int axis = direction.getAxis() == Direction.Axis.X ? 0 : 1;
                for (int offset = 8; offset < vertices.length; offset += 8) {
                    if (vertices[axis] != vertices[offset + axis]) return false;
                }
            }
        }
        return frontAndBack == 3;
    }

    private static boolean standardFace(BakedQuad quad, TextureAtlasSprite sprite, float shrink) {
        var direction = quad.getDirection();
        var face = FaceInfo.fromFacing(direction);
        boolean front = direction == Direction.SOUTH;
        var vertices = quad.getVertices();
        for (int vertex = 0; vertex < 4; vertex++) {
            var corner = face.getVertexInfo(vertex);
            int offset = vertex * 8;
            float x = corner.xFace == FaceInfo.Constants.MAX_X ? 1 : 0;
            float y = corner.yFace == FaceInfo.Constants.MAX_Y ? 1 : 0;
            float u = sprite.getU(x == 0 ? shrink / 2 : 1 - shrink / 2);
            float v = sprite.getV(y == 1 ? shrink / 2 : 1 - shrink / 2);
            if (Float.intBitsToFloat(vertices[offset]) != x || Float.intBitsToFloat(vertices[offset + 1]) != y
                    || Float.intBitsToFloat(vertices[offset + 2]) != (front ? 8.5F : 7.5F) / 16
                    || vertices[offset + 3] != -1 || vertices[offset + 6] != 0
                    || vertices[offset + 7] != (front ? 0x7f0000 : 0x810000)
                    || !(Math.abs(Float.intBitsToFloat(vertices[offset + 4]) - u) <= UV_EPSILON)
                    || !(Math.abs(Float.intBitsToFloat(vertices[offset + 5]) - v) <= UV_EPSILON)) {
                return false;
            }
        }
        return true;
    }

    static PngPixels pixels(int[] source, int light) {
        int size = InventoryIconRenderer.SIZE;
        int scale = InventoryIconRenderer.GUI_SCALE;
        int margin = (size - SOURCE_SIZE * scale) / 2;
        int[] output = new int[size * size];
        boolean visible = false;
        for (int y = 0; y < SOURCE_SIZE; y++) {
            for (int x = 0; x < SOURCE_SIZE; x++) {
                int pixel = source[y * SOURCE_SIZE + x];
                int alpha = pixel >>> 24;
                if (alpha == 0) continue;
                // Blended edges can depend on shader and framebuffer state.
                if (alpha != 255) return null;
                visible = true;
                int color = 0xff000000;
                // Flat lighting saturates for the forward-facing quad. Apply the lightmap in ABGR order.
                for (int channel = 0; channel < 24; channel += 8) {
                    int value = ((pixel >>> channel & 255) * (light >>> channel & 255) + 127) / 255;
                    color |= value << channel;
                }
                for (int row = 0; row < scale; row++) {
                    int first = (margin + y * scale + row) * size + margin + x * scale;
                    Arrays.fill(output, first, first + scale, color);
                }
            }
        }
        return visible ? new PngPixels(size, size, output) : null;
    }

    private record CachedSprite(BakedModel model, SpriteContents contents) {}
}
