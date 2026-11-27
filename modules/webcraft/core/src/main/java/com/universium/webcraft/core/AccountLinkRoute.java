package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import com.universium.webcraft.core.WebCraftRoutes.Request;
import com.universium.webcraft.core.WebCraftRoutes.Result;
import com.universium.webcraft.core.WebCraftRoutes.Scope;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

record AccountLinkRoute(AccountLinks links, WebCraftHttpServer.BearerToken token, boolean allowRemote) {
    static final String PATH = "/api/v1/internal/account-links/redeem";
    static final String TOKEN_VARIABLE = "WEBCRAFT_ACCOUNT_LINK_TOKEN";
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{16}");

    Result handle(Request request, ServerStatusRoute lifecycle) {
        if (request.scope() != Scope.HTTP) return new Result(Response.error(404, "not_found"));
        if (!"POST".equals(request.method())) {
            return new Result(Response.error(405, "method_not_allowed"), Map.of("Allow", "POST"), List.of());
        }
        if (!allowRemote && !request.peer().isLoopbackAddress()) {
            return new Result(Response.error(403, "loopback_required"));
        }
        if (!token.permits(request.authorization())) {
            return new Result(Response.error(401, "unauthorized"), Map.of("WWW-Authenticate", "Bearer"), List.of());
        }
        if (lifecycle != null) {
            Response unavailable = lifecycle.unavailable();
            if (unavailable != null) return new Result(unavailable);
        }
        if (request.target().getRawQuery() != null) return new Result(Response.error(400, "unsupported_query"));
        Attempt attempt = parse(request.body());
        if (attempt == null) return new Result(Response.error(400, "invalid_redemption"));

        var redemption = links.redeem(attempt.code, attempt.id);
        return switch (redemption.outcome()) {
            case REDEEMED -> {
                var response = new JsonObject();
                response.addProperty("uuid", redemption.player().uuid().toString());
                response.addProperty("name", redemption.player().name());
                yield new Result(Response.ok(response));
            }
            case INVALID_CODE -> new Result(Response.error(404, "invalid_code"));
            case ATTEMPT_CONFLICT -> new Result(Response.error(409, "attempt_conflict"));
            case RATE_LIMITED -> new Result(Response.error(429, "rate_limited"),
                    Map.of("Retry-After", "60"), List.of());
        };
    }

    private record Attempt(String code, UUID id) {}

    private static Attempt parse(String body) {
        if (body == null) return null;
        try (var reader = new JsonReader(new StringReader(body))) {
            reader.beginObject();
            String code = null;
            String attemptId = null;
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (reader.peek() != JsonToken.STRING) return null;
                if (field.equals("code") && code == null) {
                    code = reader.nextString();
                } else if (field.equals("attemptId") && attemptId == null) {
                    attemptId = reader.nextString();
                } else {
                    return null;
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || code == null || !CODE.matcher(code).matches()
                    || attemptId == null || attemptId.length() != 36) return null;
            UUID id = UUID.fromString(attemptId);
            return id.toString().equals(attemptId) ? new Attempt(code, id) : null;
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            return null;
        }
    }
}
