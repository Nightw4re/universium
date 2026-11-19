package com.universium.webcraft.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.UUID;

/** A private read route by default. Implementations must be safe to call from an HTTP worker. */
@FunctionalInterface
public interface WebCraftReadRoute {
    /** Reads data for the authorized viewer. Access to live game state must run on the server thread. */
    Response read(MinecraftServer server, UUID viewer);

    /** Explicitly public route for data that does not contain viewer-specific information. */
    @FunctionalInterface
    interface PublicRoute extends WebCraftReadRoute {
        /** Handles the exact registered path and the raw query, or null if no query was supplied. */
        Response readPublic(String rawQuery);

        @Override
        default Response read(MinecraftServer server, UUID viewer) {
            return readPublic(null);
        }
    }

    /** The HTTP status and body returned by the route. */
    record Response(int status, JsonElement body, byte[] image) {
        public Response {
            Objects.requireNonNull(body, "body");
        }

        /** Creates a JSON response. */
        public Response(int status, JsonElement body) {
            this(status, body, null);
        }

        /** Returns a successful JSON document. */
        public static Response ok(JsonElement body) {
            return new Response(200, body);
        }

        /** Returns a validated PNG as a binary response. */
        public static Response png(byte[] image) {
            return new Response(200, com.google.gson.JsonNull.INSTANCE, Objects.requireNonNull(image, "image"));
        }

        /** Returns a failure with a stable error code. */
        public static Response error(int status, String code) {
            JsonObject body = new JsonObject();
            body.addProperty("error", code);
            return new Response(status, body);
        }
    }
}
