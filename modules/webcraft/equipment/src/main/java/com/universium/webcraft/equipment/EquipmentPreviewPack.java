package com.universium.webcraft.equipment;

import com.google.common.hash.Hashing;
import com.google.gson.JsonObject;
import com.universium.webcraft.api.WebCraftPreviewPack;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/** Reads client-rendered icons without unpacking the administrator's archive. */
final class EquipmentPreviewPack {
    private final WebCraftPreviewPack pack;

    EquipmentPreviewPack(Path archive) {
        this.pack = new WebCraftPreviewPack(archive);
    }

    boolean isInstalled() {
        return pack.isInstalled();
    }

    static String key(ItemStack stack, HolderLookup.Provider registries) {
        // Minecraft's SNBT visitor sorts compound keys. The archive format includes the count and component patch.
        return Hashing.sha256().hashString(stack.save(registries).toString(), StandardCharsets.UTF_8).toString();
    }

    JsonObject find(String key) {
        var icon = pack.readIconWithFormat(key);
        if (icon == null) {
            return null;
        }
        JsonObject preview = new JsonObject();
        preview.addProperty("type", "sprite");
        String field = icon.contentType().equals("image/webp") ? "webp" : "png";
        preview.addProperty(field, Base64.getEncoder().encodeToString(icon.bytes()));
        var animation = pack.readAnimationWithFormat(key);
        if (animation != null) {
            String animationField = animation.contentType().equals("image/webp") ? "animationWebp" : "animation";
            preview.addProperty(animationField, Base64.getEncoder().encodeToString(animation.bytes()));
        }
        return preview;
    }
}
