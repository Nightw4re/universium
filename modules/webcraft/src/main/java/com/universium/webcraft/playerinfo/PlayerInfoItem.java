package com.universium.webcraft.playerinfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PlayerInfoItem(
        String id,
        int count,
        int durability,
        String displayName,
        Map<String, Object> nbt,
        Map<String, Object> modelDescriptor,
        String renderMode,
        String texturePath,
        String textureAnchor,
        String textureFit,
        String textureRepeat,
        String previewPath
) {
    public PlayerInfoItem {
        id = Objects.requireNonNull(id, "id");
        displayName = Objects.requireNonNull(displayName, "displayName");
        nbt = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(nbt, "nbt")));
        modelDescriptor = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(modelDescriptor, "modelDescriptor")));
        renderMode = Objects.requireNonNullElse(renderMode, "item");
        texturePath = Objects.requireNonNullElse(texturePath, "");
        textureAnchor = Objects.requireNonNullElse(textureAnchor, "center");
        textureFit = Objects.requireNonNullElse(textureFit, "contain");
        textureRepeat = Objects.requireNonNullElse(textureRepeat, "no-repeat");
        previewPath = Objects.requireNonNullElse(previewPath, "");
    }
}

