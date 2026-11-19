package com.universium.webcraft.exporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;

final class ArmorGeometryExport {
    private ArmorGeometryExport() {}

    static JsonObject collect(Minecraft minecraft) {
        var models = minecraft.getEntityModels();
        var player = models.bakeLayer(ModelLayers.PLAYER);
        var slim = models.bakeLayer(ModelLayers.PLAYER_SLIM);
        var outer = models.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR);
        var inner = models.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR);
        var meshes = new JsonObject();
        meshes.add("mannequin", mesh(player, "head", "body", "right_arm", "left_arm", "right_leg", "left_leg"));
        meshes.add("mannequin_slim", mesh(slim, "head", "body", "right_arm", "left_arm", "right_leg", "left_leg"));
        meshes.add("skin_overlay", mesh(player, "hat", "jacket", "right_sleeve", "left_sleeve", "right_pants", "left_pants"));
        meshes.add("skin_overlay_slim", mesh(slim, "hat", "jacket", "right_sleeve", "left_sleeve", "right_pants", "left_pants"));
        meshes.add("head", mesh(outer, "head", "hat"));
        meshes.add("chest", mesh(outer, "body", "right_arm", "left_arm"));
        meshes.add("legs", mesh(inner, "body", "right_leg", "left_leg"));
        meshes.add("feet", mesh(outer, "right_leg", "left_leg"));
        var result = new JsonObject();
        result.add("meshes", meshes);
        return result;
    }

    private static JsonArray mesh(ModelPart model, String... parts) {
        var output = new MeshVertices();
        for (String part : parts) {
            model.getChild(part).visit(new PoseStack(), (pose, path, index, cube) -> cube.compile(pose, output, 0, 0, -1));
        }
        return output.data;
    }

    /** Collects each baked quad in position, UV and normal order. */
    private static final class MeshVertices implements VertexConsumer {
        private final JsonArray data = new JsonArray();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            data.add(x);
            data.add(y);
            data.add(z);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            data.add(u);
            data.add(v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            data.add(x);
            data.add(y);
            data.add(z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }
    }
}
