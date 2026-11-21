package com.universium.webcraft.exporter.ponder;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Records the meshes submitted by one Ponder render pass. Minecraft owns meshes outside that pass. */
public final class PonderDrawCapture implements AutoCloseable {
    private static final ThreadLocal<PonderDrawCapture> ACTIVE = new ThreadLocal<>();
    private final Assets assets;
    private final JsonArray draws = new JsonArray();

    public PonderDrawCapture(Assets assets) {
        if (ACTIVE.get() != null) throw new IllegalStateException("A Ponder capture is already active");
        this.assets = assets;
        ACTIVE.set(this);
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
        draw.addProperty("vertices", assets.store("meshes", ".bin", bytes(mesh.vertexBuffer())));
        var indices = mesh.indexBuffer();
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
        var shader = RenderSystem.getShader();
        String shaderName = shader == null ? "" : shader.getName();
        draw.addProperty("shader", shaderName);
        var textures = new JsonObject();
        for (int slot = 0; slot < 3; slot++) {
            int id = RenderSystem.getShaderTexture(slot);
            if (!format.hasUV(slot) || id <= 0) continue;
            var texture = assets.texture(id, slot == 0 && shaderName.contains("text"));
            if (texture != null) textures.add(Integer.toString(slot), texture);
        }
        draw.add("textures", textures);
        draw.add("projection", matrix(RenderSystem.getProjectionMatrix()));
        draw.add("modelView", matrix(RenderSystem.getModelViewMatrix()));
        draw.add("textureMatrix", matrix(RenderSystem.getTextureMatrix()));
        draw.add("color", numbers(RenderSystem.getShaderColor()));
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

    public static final class Assets {
        private static final List<net.minecraft.resources.ResourceLocation> ATLAS_LOCATIONS = List.of(
                TextureAtlas.LOCATION_BLOCKS, TextureAtlas.LOCATION_PARTICLES, Sheets.BED_SHEET, Sheets.SIGN_SHEET,
                Sheets.CHEST_SHEET, Sheets.BANNER_SHEET, Sheets.SHIELD_SHEET, Sheets.SHULKER_SHEET,
                Sheets.ARMOR_TRIMS_SHEET, Sheets.DECORATED_POT_SHEET);

        private final Set<String> stored = new HashSet<>();
        private final Map<Integer, JsonObject> textures = new HashMap<>();
        private final Map<Integer, String> atlasAnimations = new HashMap<>();
        private Map<String, byte[]> pending = new LinkedHashMap<>();

        public Map<String, byte[]> drain() {
            var files = pending;
            pending = new LinkedHashMap<>();
            return files;
        }

        String store(String directory, String extension, byte[] bytes) {
            String path = "ponder/" + directory + "/" + Hashing.sha256().hashBytes(bytes) + extension;
            if (stored.add(path)) pending.put(path, bytes);
            return path;
        }

        private JsonObject texture(int id, boolean refresh) {
            if (!refresh && textures.containsKey(id)) return textures.get(id);
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            RenderSystem.bindTexture(id);
            try {
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (width == 0 || height == 0) return null;
                try (var image = new NativeImage(width, height, false)) {
                    image.downloadTexture(0, false);
                    var texture = new JsonObject();
                    texture.addProperty("image", store("textures", ".png", image.asByteArray()));
                    String animation = atlasAnimation(id, width, height);
                    if (animation != null) texture.addProperty("animation", animation);
                    texture.addProperty("minFilter", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER));
                    texture.addProperty("magFilter", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER));
                    texture.addProperty("wrapS", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S));
                    texture.addProperty("wrapT", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T));
                    textures.put(id, texture);
                    return texture;
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            } finally {
                RenderSystem.bindTexture(previous);
            }
        }

        private String atlasAnimation(int id, int width, int height) throws IOException {
            if (atlasAnimations.containsKey(id)) return atlasAnimations.get(id);
            var manager = Minecraft.getInstance().getTextureManager();
            String animation = null;
            for (var location : ATLAS_LOCATIONS) {
                if (manager.getTexture(location, null) instanceof TextureAtlas atlas && atlas.getId() == id) {
                    animation = writeAtlasAnimation(atlas, width, height);
                    break;
                }
            }
            atlasAnimations.put(id, animation);
            return animation;
        }

        private String writeAtlasAnimation(TextureAtlas atlas, int width, int height) throws IOException {
            var sprites = new JsonArray();
            for (var entry : atlas.getTextures().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                var sprite = entry.getValue();
                var contents = sprite.contents();
                var metadata = contents.metadata().getSection(AnimationMetadataSection.SERIALIZER).orElse(null);
                if (metadata == null) continue;
                var source = contents.getOriginalImage();
                var frames = new JsonArray();
                metadata.forEachFrame((index, duration) -> {
                    int count = (source.getWidth() / contents.width()) * (source.getHeight() / contents.height());
                    if (index >= 0 && index < count && duration > 0) frames.add(numbers(index, duration));
                });
                if (frames.isEmpty()) {
                    contents.getUniqueFrames().forEach(frame -> frames.add(numbers(frame, metadata.getDefaultFrameTime())));
                }
                if (frames.size() < 2) continue;
                var animation = new JsonObject();
                animation.addProperty("id", entry.getKey().toString());
                animation.addProperty("image", store("textures", ".png", source.asByteArray()));
                animation.add("rect", numbers(Math.round(sprite.getU0() * width), Math.round(sprite.getV0() * height),
                        contents.width(), contents.height()));
                animation.add("frames", frames);
                animation.addProperty("interpolate", metadata.isInterpolatedFrames());
                sprites.add(animation);
            }
            if (sprites.isEmpty()) return null;
            return store("textures", ".json", sprites.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
