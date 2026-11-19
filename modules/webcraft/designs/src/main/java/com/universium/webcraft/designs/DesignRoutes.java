package com.universium.webcraft.designs;

import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftPreviewPack;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

final class DesignRoutes {
    private final WebCraftPreviewPack pack;
    private volatile Catalog catalog;

    DesignRoutes(WebCraftPreviewPack pack) {
        this.pack = pack;
    }

    void load(ResourceLocation module) {
        var data = pack.readDesign(module);
        if (!data.has("version") || !data.get("version").isJsonPrimitive()
                || !data.getAsJsonPrimitive("version").isNumber() || !"1".equals(data.get("version").getAsString())
                || !data.has("textures") || !data.get("textures").isJsonArray()) {
            catalog = null;
            return;
        }
        var hashes = new HashSet<String>();
        for (var texture : data.getAsJsonArray("textures")) {
            if (texture.isJsonPrimitive() && texture.getAsJsonPrimitive().isString()
                    && texture.getAsString().matches("[a-f0-9]{64}")) {
                hashes.add(texture.getAsString());
            }
        }
        data.remove("textures");
        catalog = new Catalog(data, Set.copyOf(hashes));
    }

    Response data(String query) {
        if (query != null) {
            return Response.error(400, "unsupported_query");
        }
        Catalog current = catalog;
        return current == null ? Response.error(404, "design_export_missing") : Response.ok(current.data());
    }

    Response texture(String query) {
        if (query == null || !query.matches("hash=[a-f0-9]{64}")) {
            return Response.error(400, "invalid_texture_hash");
        }
        String hash = query.substring(5);
        Catalog current = catalog;
        byte[] texture = current != null && current.hashes().contains(hash) ? pack.readTexture(hash) : null;
        return texture == null ? Response.error(404, "texture_not_found") : Response.png(texture);
    }

    private record Catalog(JsonObject data, Set<String> hashes) {}
}
