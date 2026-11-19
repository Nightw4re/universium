package com.universium.webcraft.designs;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProfileSkinRouteTest {
    @Test
    void usesOfficialServicesAndCachesThePublicSkin() throws IOException {
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", png);
        String hash = "a".repeat(64);
        var calls = new ArrayList<String>();
        var route = new ProfileSkinRoute(address -> {
            calls.add(address);
            if (calls.size() == 1) return account();
            if (calls.size() == 2) return profile("http://textures.minecraft.net/texture/" + hash);
            return png.toByteArray();
        });

        assertEquals(400, route.read("name=Viewer&extra=1").status());
        var response = route.read("name=Viewer");
        var skin = response.body().getAsJsonObject();
        assertEquals(200, response.status());
        assertEquals("Viewer", skin.get("name").getAsString());
        assertTrue(skin.get("slim").getAsBoolean());
        assertArrayEquals(png.toByteArray(), Base64.getDecoder().decode(skin.get("png").getAsString()));
        assertEquals(response, route.read("name=viewer"));
        assertEquals(List.of(
                "https://api.minecraftservices.com/minecraft/profile/lookup/name/Viewer",
                "https://sessionserver.mojang.com/session/minecraft/profile/" + "b".repeat(32),
                "https://textures.minecraft.net/texture/" + hash), calls);
    }

    @Test
    void rejectsAnySkinOutsideTheTextureService() {
        var calls = new ArrayList<String>();
        var route = new ProfileSkinRoute(address -> {
            calls.add(address);
            return calls.size() == 1 ? account() : profile("https://example.com/texture/" + "a".repeat(64));
        });

        assertEquals(502, route.read("name=Viewer").status());
        assertEquals(2, calls.size());
    }

    private static byte[] account() {
        return ("{\"id\":\"" + "b".repeat(32) + "\",\"name\":\"Viewer\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] profile(String url) {
        String texture = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\",\"metadata\":{\"model\":\"slim\"}}}}";
        String value = Base64.getEncoder().encodeToString(texture.getBytes(StandardCharsets.UTF_8));
        return ("{\"properties\":[{\"name\":\"textures\",\"value\":\"" + value + "\"}]}").getBytes(StandardCharsets.UTF_8);
    }
}
