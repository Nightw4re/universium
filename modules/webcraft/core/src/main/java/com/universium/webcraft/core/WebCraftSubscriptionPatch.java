package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

record WebCraftSubscriptionPatch(byte[] bytes, boolean unchanged) {
    private static final WebCraftSubscriptionPatch FULL_UPDATE = new WebCraftSubscriptionPatch(null, false);
    private static final WebCraftSubscriptionPatch UNCHANGED = new WebCraftSubscriptionPatch(null, true);

    static WebCraftSubscriptionPatch between(byte[] baseline, int status, JsonObject current, int fullSize) {
        var previous = JsonParser.parseString(new String(baseline, StandardCharsets.UTF_8)).getAsJsonObject();
        if (status != previous.get("status").getAsInt()) return FULL_UPDATE;

        var body = previous.getAsJsonObject("body");
        var set = new JsonObject();
        for (var field : current.entrySet()) {
            if (!sameValue(body.get(field.getKey()), field.getValue())) {
                set.add(field.getKey(), field.getValue());
            }
        }
        var remove = new JsonArray();
        for (String key : body.keySet()) {
            if (!current.has(key)) remove.add(key);
        }
        if (set.size() == 0 && remove.isEmpty()) return UNCHANGED;

        var patch = new JsonObject();
        patch.add("set", set);
        patch.add("remove", remove);
        byte[] bytes = patch.toString().getBytes(StandardCharsets.UTF_8);
        return bytes.length < fullSize ? new WebCraftSubscriptionPatch(bytes, false) : FULL_UPDATE;
    }

    private static boolean sameValue(JsonElement previous, JsonElement current) {
        if (previous instanceof JsonObject before && current instanceof JsonObject after) {
            return before.size() == after.size() && after.entrySet().stream()
                    .allMatch(field -> sameValue(before.get(field.getKey()), field.getValue()));
        }
        if (previous instanceof JsonArray before && current instanceof JsonArray after) {
            if (before.size() != after.size()) return false;
            for (int index = 0; index < after.size(); index++) {
                if (!sameValue(before.get(index), after.get(index))) return false;
            }
            return true;
        }
        if (previous instanceof JsonPrimitive before && before.isNumber()
                && current instanceof JsonPrimitive after && after.isNumber()) {
            // Gson's numeric equality can coerce parsed numbers to double and lose precision.
            return before.getAsString().equals(after.getAsString());
        }
        return Objects.equals(previous, current);
    }
}
