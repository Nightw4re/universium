package com.universium.webcraft.exporter;

import com.google.common.hash.Hashing;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

final class MachineRecipeMaterials {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_ANIMATION_PIXELS = 1024 * 1024;
    private static final int MAX_TEXTURE_BYTES = 512 * 1024;
    private final Mekanism mekanism = Mekanism.load();
    private final HashMap<Sprite, TextureCapture> captures = new HashMap<>();
    private final TreeMap<String, Texture> textures = new TreeMap<>();
    private final HashSet<String> fluidTextures = new HashSet<>();
    private final StaticPreviewWriter previews;
    private final ExportDiagnostics diagnostics;

    MachineRecipeMaterials(StaticPreviewWriter previews, ExportDiagnostics diagnostics) {
        this.previews = previews;
        this.diagnostics = diagnostics;
    }

    JsonObject fluid(FluidStack stack) {
        var ingredient = material("fluid", BuiltInRegistries.FLUID.getKey(stack.getFluid()), stack.getHoverName());
        ingredient.addProperty("amountMb", stack.getAmount());
        try {
            var extension = IClientFluidTypeExtensions.of(stack.getFluid());
            addTexture(ingredient, extension.getStillTexture(stack), extension.getTintColor(stack));
        } catch (RuntimeException | LinkageError error) {
            recordTexture(ingredient, null, "sprite_lookup_failed");
            LOGGER.debug("WebCraft could not resolve the fluid sprite for {}", ingredient.get("id"), error);
        }
        return ingredient;
    }

    JsonObject jeiChemical(Object value) {
        if (mekanism == null || !mekanism.stackType().isInstance(value)) return null;
        try {
            if ((boolean) mekanism.empty().invoke(value)) return null;
            return chemical(mekanism.chemical().invoke(value), (ResourceLocation) mekanism.id().invoke(value),
                    (long) mekanism.amount().invoke(value));
        } catch (ReflectiveOperationException error) {
            LOGGER.debug("WebCraft could not read a Mekanism chemical stack", error);
            return null;
        }
    }

    JsonObject chemical(Object key, ResourceLocation id, long amount) {
        if (mekanism == null || !mekanism.chemicalType().isInstance(key) || id == null || amount < 1) return null;
        var ingredient = material("chemical", id, null);
        ingredient.addProperty("amount", Long.toString(amount));
        try {
            addName(ingredient, (Component) mekanism.name().invoke(key));
            addTexture(ingredient, (ResourceLocation) mekanism.icon().invoke(key),
                    (int) mekanism.tint().invoke(key) | 0xff000000);
        } catch (ReflectiveOperationException error) {
            recordTexture(ingredient, null, "sprite_lookup_failed");
            LOGGER.debug("WebCraft could not read Mekanism chemical {}", id, error);
        }
        return ingredient;
    }

    private static JsonObject material(String kind, ResourceLocation id, Component name) {
        var ingredient = new JsonObject();
        ingredient.addProperty("kind", kind);
        ingredient.addProperty("id", id.toString());
        if (name != null) addName(ingredient, name);
        return ingredient;
    }

    private static void addName(JsonObject ingredient, Component name) {
        String label = name.getString();
        label = label.substring(0, Math.min(label.length(), 256));
        if (!label.isBlank()) ingredient.addProperty("name", label);
    }

    private void addTexture(JsonObject ingredient, ResourceLocation id, int tint) {
        if (id == null) {
            recordTexture(ingredient, null, "sprite_not_declared");
            return;
        }
        // Remember missing textures as well, since the same sprite occurs in many recipes.
        var capture = captures.computeIfAbsent(new Sprite(id, tint), this::capture);
        if (capture.hash() != null) {
            ingredient.addProperty("texture", capture.hash());
            if ("fluid".equals(ingredient.get("kind").getAsString())) fluidTextures.add(capture.hash());
        }
        recordTexture(ingredient, id.toString(), capture.reason());
    }

    private void recordTexture(JsonObject ingredient, String sprite, String reason) {
        diagnostics.checkedMaterial(ingredient.get("kind").getAsString(), ingredient.get("id").getAsString(), sprite, reason);
    }

    private TextureCapture capture(Sprite texture) {
        try {
            var sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(texture.id());
            var contents = sprite.contents();
            int width = contents.width();
            int height = contents.height();
            if (contents.name().equals(MissingTextureAtlasSprite.getLocation())) {
                return new TextureCapture(null, "sprite_missing_from_atlas");
            }
            if (width < 1 || height < 1 || width > 1024 || height > 1024) {
                return new TextureCapture(null, "sprite_dimensions_unsupported");
            }

            var frame = pixels(sprite, contents.getUniqueFrames().findFirst().orElse(0), texture.tint());
            var animation = animation(sprite, texture.tint());
            var frames = animation == null ? List.<AnimationFrames.Frame>of() : animation.frames();
            byte[] png = AnimatedPng.encode(frames);
            if (png == null || png.length > MAX_TEXTURE_BYTES) {
                if (!frame.visible()) return new TextureCapture(null, "transparent_sprite");
                png = frame.encode();
                frames = List.of();
            } else if (frames.stream().noneMatch(value -> value.pixels().visible())) {
                return new TextureCapture(null, "transparent_sprite");
            }
            if (png.length > MAX_TEXTURE_BYTES) return new TextureCapture(null, "sprite_exceeds_size_limit");
            String hash = Hashing.sha256().hashBytes(png).toString();
            textures.putIfAbsent(hash, new Texture(png, previews.supportsWebp() ? frames : List.of()));
            return new TextureCapture(hash, null);
        } catch (IOException | RuntimeException | LinkageError error) {
            LOGGER.debug("WebCraft could not capture machine recipe sprite {}", texture.id(), error);
            return new TextureCapture(null, "sprite_capture_failed");
        }
    }

    private static AnimationFrames animation(TextureAtlasSprite sprite, int tint) {
        var contents = sprite.contents();
        if (contents.getUniqueFrames().limit(2).count() < 2) return null;
        var section = contents.metadata().getSection(AnimationMetadataSection.SERIALIZER)
                .orElse(AnimationMetadataSection.EMPTY);
        var sheet = contents.getOriginalImage();
        int frameCount = (sheet.getWidth() / contents.width()) * (sheet.getHeight() / contents.height());
        var sequence = new ArrayList<FrameTiming>();
        section.forEachFrame((index, duration) -> {
            if (index >= 0 && index < frameCount && duration > 0 && sequence.size() <= AnimationTimeline.MAX_TICKS) {
                sequence.add(new FrameTiming(index, duration));
            }
        });
        if (sequence.isEmpty()) {
            if (frameCount > AnimationTimeline.MAX_TICKS || section.getDefaultFrameTime() < 1) return null;
            for (int index = 0; index < frameCount; index++) {
                sequence.add(new FrameTiming(index, section.getDefaultFrameTime()));
            }
        }
        long ticks = sequence.stream().mapToLong(FrameTiming::duration).sum();
        if (ticks > AnimationTimeline.MAX_TICKS || ticks * contents.width() * contents.height() > MAX_ANIMATION_PIXELS) {
            return null;
        }

        var frames = new AnimationFrames();
        for (int index = 0; index < sequence.size(); index++) {
            var timing = sequence.get(index);
            var current = pixels(sprite, timing.index(), tint);
            var next = sequence.get((index + 1) % sequence.size());
            boolean interpolate = section.isInterpolatedFrames() && timing.duration() > 1 && timing.index() != next.index();
            var target = interpolate ? pixels(sprite, next.index(), tint) : current;
            frames.addTick(current);
            for (int tick = 1; tick < timing.duration(); tick++) {
                frames.addTick(interpolate ? interpolate(current, target, tick / (double) timing.duration()) : current);
            }
        }
        return frames;
    }

    private static PngPixels pixels(TextureAtlasSprite sprite, int frame, int tint) {
        int width = sprite.contents().width();
        int height = sprite.contents().height();
        var pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = tint(sprite.getPixelRGBA(frame, x, y), tint);
            }
        }
        return new PngPixels(width, height, pixels);
    }

    private static PngPixels interpolate(PngPixels current, PngPixels next, double progress) {
        var pixels = new int[current.rgba().length];
        for (int index = 0; index < pixels.length; index++) {
            int from = current.rgba()[index];
            int to = next.rgba()[index];
            // Minecraft keeps the current alpha and blends the three color channels.
            pixels[index] = (from & 0xff000000)
                    | blend(from >>> 16, to >>> 16, progress) << 16
                    | blend(from >>> 8, to >>> 8, progress) << 8
                    | blend(from, to, progress);
        }
        return new PngPixels(current.width(), current.height(), pixels);
    }

    private static int blend(int from, int to, double progress) {
        return (int) ((from & 255) * (1 - progress) + (to & 255) * progress);
    }

    // NativeImage packs pixels as ABGR, while NeoForge supplies an ARGB tint.
    private static int tint(int pixel, int tint) {
        int red = (pixel & 255) * ((tint >>> 16) & 255) / 255;
        int green = ((pixel >>> 8) & 255) * ((tint >>> 8) & 255) / 255;
        int blue = ((pixel >>> 16) & 255) * (tint & 255) / 255;
        int alpha = (pixel >>> 24) * (tint >>> 24) / 255;
        return alpha << 24 | blue << 16 | green << 8 | red;
    }

    void write(ExportArchive.EntryWriter writer) throws IOException {
        for (var entry : textures.entrySet()) {
            String path = "catalog-data/machine-recipes/textures/" + entry.getKey();
            entry.getValue().write(writer, path, previews, fluidTextures.contains(entry.getKey()));
        }
    }

    record Texture(byte[] png, List<AnimationFrames.Frame> frames) {
        void write(ExportArchive.EntryWriter writer, String path, StaticPreviewWriter previews, boolean fluid) throws IOException {
            if (previews.supportsWebp() && !frames.isEmpty()) {
                byte[] webp = null;
                try {
                    webp = previews.encodeAnimatedWebp(frames);
                } catch (IOException error) {
                    LOGGER.debug("WebCraft could not encode a machine recipe texture as WebP", error);
                }
                if (webp != null && webp.length <= MAX_TEXTURE_BYTES) {
                    writer.write(path + ".webp", webp);
                    if (fluid) return;
                }
            }
            writer.write(path + ".png", png);
        }
    }

    private record Sprite(ResourceLocation id, int tint) {}
    private record TextureCapture(String hash, String reason) {}
    private record FrameTiming(int index, int duration) {}

    // Both viewers expose Mekanism API objects. Keep that API optional and out of the exporter classpath.
    private record Mekanism(Class<?> stackType, Class<?> chemicalType, Method empty, Method chemical, Method id,
                            Method amount, Method name, Method icon, Method tint) {
        static Mekanism load() {
            if (!ModList.get().isLoaded("mekanism")) return null;
            try {
                var stack = Class.forName("mekanism.api.chemical.ChemicalStack");
                var chemical = Class.forName("mekanism.api.chemical.Chemical");
                return new Mekanism(stack, chemical, stack.getMethod("isEmpty"), stack.getMethod("getChemical"),
                        stack.getMethod("getTypeRegistryName"), stack.getMethod("getAmount"),
                        chemical.getMethod("getTextComponent"), chemical.getMethod("getIcon"), chemical.getMethod("getTint"));
            } catch (ReflectiveOperationException | LinkageError error) {
                LOGGER.warn("WebCraft could not initialize Mekanism recipe ingredients", error);
                return null;
            }
        }
    }
}
