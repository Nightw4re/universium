package com.universium.webcraft.designs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.universium.webcraft.api.WebCraftReadRoute.Response;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

final class ProfileSkinRoute {
    private static final int BYTE_LIMIT = 65_536;
    private static final long CACHE_MILLIS = 120_000;
    private final Semaphore requests = new Semaphore(2);
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final Transport transport;

    ProfileSkinRoute() {
        this(ProfileSkinRoute::download);
    }

    ProfileSkinRoute(Transport transport) {
        this.transport = transport;
    }

    Response read(String query) {
        if (query == null || !query.matches("name=[a-zA-Z0-9_]{3,16}")) {
            return Response.error(400, "invalid_profile_name");
        }
        String name = query.substring(5);
        String key = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry != null && entry.expiresAt() > now) {
                return Response.ok(entry.skin());
            }
            cache.remove(key);
        }
        if (!requests.tryAcquire()) {
            return Response.error(503, "profile_lookup_busy");
        }
        try {
            JsonObject skin = lookup(name);
            if (skin == null) {
                return Response.error(404, "profile_skin_not_found");
            }
            synchronized (cache) {
                cache.put(key, new CacheEntry(skin, System.currentTimeMillis() + CACHE_MILLIS));
                if (cache.size() > 32) {
                    cache.remove(cache.keySet().iterator().next());
                }
            }
            return Response.ok(skin);
        } catch (IOException | RuntimeException exception) {
            return Response.error(502, "profile_lookup_failed");
        } finally {
            requests.release();
        }
    }

    private JsonObject lookup(String name) throws IOException {
        var account = json(transport.get("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name));
        if (account == null) {
            return null;
        }
        String id = account.get("id").getAsString();
        String displayName = account.get("name").getAsString();
        if (!id.matches("[0-9a-f]{32}") || !displayName.matches("[a-zA-Z0-9_]{3,16}")) {
            throw new IOException("Invalid profile response");
        }
        var profile = json(transport.get("https://sessionserver.mojang.com/session/minecraft/profile/" + id));
        if (profile == null) {
            return null;
        }
        for (var property : profile.getAsJsonArray("properties")) {
            var entry = property.getAsJsonObject();
            if ("textures".equals(entry.get("name").getAsString())) {
                return skin(displayName, json(Base64.getDecoder().decode(entry.get("value").getAsString())));
            }
        }
        return null;
    }

    private JsonObject skin(String name, JsonObject property) throws IOException {
        var skin = property.getAsJsonObject("textures").getAsJsonObject("SKIN");
        if (skin == null) {
            return null;
        }
        var url = URI.create(skin.get("url").getAsString());
        String path = url.getRawPath();
        if (!"textures.minecraft.net".equals(url.getHost()) || path == null || !path.matches("/texture/[0-9a-f]{32,64}")) {
            throw new IOException("Unexpected skin location");
        }
        byte[] png = transport.get("https://textures.minecraft.net" + path);
        if (!isSkinPng(png)) {
            return null;
        }
        var result = new JsonObject();
        var metadata = skin.getAsJsonObject("metadata");
        result.addProperty("name", name);
        result.addProperty("slim", metadata != null && metadata.has("model") && "slim".equals(metadata.get("model").getAsString()));
        result.addProperty("png", Base64.getEncoder().encodeToString(png));
        return result;
    }

    private static boolean isSkinPng(byte[] png) {
        if (png == null || png.length < 24) {
            return false;
        }
        var header = ByteBuffer.wrap(png);
        return header.getLong(0) == 0x89504e470d0a1a0aL && header.getInt(8) == 13 && header.getInt(12) == 0x49484452
                && header.getInt(16) == 64 && (header.getInt(20) == 64 || header.getInt(20) == 32);
    }

    private static JsonObject json(byte[] body) {
        return body == null ? null : JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] download(String address) throws IOException {
        var connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(1400);
        connection.setReadTimeout(1400);
        try {
            int status = connection.getResponseCode();
            if (status == 404 || status == 204) {
                return null;
            }
            if (status != 200 || connection.getContentLengthLong() > BYTE_LIMIT) {
                throw new IOException("Unexpected response from the Minecraft service");
            }
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(BYTE_LIMIT + 1);
                if (bytes.length > BYTE_LIMIT) {
                    throw new IOException("Minecraft service response is too large");
                }
                return bytes;
            }
        } finally {
            connection.disconnect();
        }
    }

    @FunctionalInterface
    interface Transport {
        byte[] get(String address) throws IOException;
    }

    private record CacheEntry(JsonObject skin, long expiresAt) {}
}
