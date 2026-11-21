package com.universium.webcraft.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CatalogData {
    private static final List<String> STYLES = List.of("italic", "bold", "underlined", "strikethrough", "obfuscated");
    private static final Set<String> TOOLTIP_FIELDS = Set.of("version", "locale", "hasGraphicElements", "lines");
    private static final Set<String> TOOLTIP_WITH_ALTERNATIVES = Set.of("version", "locale", "hasGraphicElements",
            "lines", "alternatives");
    private static final Set<String> ALTERNATIVE_FIELDS = Set.of("keys", "label", "hasGraphicElements", "lines");

    private CatalogData() {}

    static boolean validStack(JsonObject data) {
        if (!versionOne(data) || !data.keySet().equals(Set.of("version", "count", "components", "nbt", "omittedComponents"))
                || integer(data.get("count"), 1, Integer.MAX_VALUE) == null
                || !(data.get("components") instanceof JsonObject components)
                || !(data.get("nbt") instanceof JsonObject nbt)
                || !itemIds(data.get("omittedComponents"))) {
            return false;
        }
        return components.entrySet().stream().allMatch(entry -> isId(entry.getKey()) && boundedJson(entry.getValue(), 0))
                && nbt.entrySet().stream().allMatch(entry -> isId(entry.getKey()) && string(entry.getValue()) != null);
    }

    static boolean validTooltip(JsonObject data) {
        String locale = string(data.get("locale"));
        if (!versionOne(data) || locale == null || !locale.matches("[A-Za-z0-9_-]{1,32}") || !validTooltipText(data)) {
            return false;
        }
        if (data.keySet().equals(TOOLTIP_FIELDS)) return true;
        if (!data.keySet().equals(TOOLTIP_WITH_ALTERNATIVES)
                || !(data.get("alternatives") instanceof JsonArray alternatives) || alternatives.size() > 8) {
            return false;
        }
        var shortcuts = new HashSet<Set<String>>();
        for (var value : alternatives) {
            if (!(value instanceof JsonObject alternative) || !alternative.keySet().equals(ALTERNATIVE_FIELDS)
                    || !validTooltipText(alternative)) {
                return false;
            }
            String label = string(alternative.get("label"));
            if (label == null || label.isBlank() || label.length() > 128
                    || !(alternative.get("keys") instanceof JsonArray keys) || keys.isEmpty() || keys.size() > 3) {
                return false;
            }
            var shortcut = new HashSet<String>();
            for (var key : keys) {
                String id = string(key);
                if (id == null || !id.matches("key\\.keyboard\\.[a-z0-9_.-]{1,64}") || !shortcut.add(id)) return false;
            }
            if (!shortcuts.add(Set.copyOf(shortcut))) return false;
        }
        return true;
    }

    private static boolean validTooltipText(JsonObject data) {
        if (!bool(data.get("hasGraphicElements")) || !(data.get("lines") instanceof JsonArray lines)) return false;
        for (var element : lines) {
            if (!(element instanceof JsonArray spans)) return false;
            for (var value : spans) {
                if (!(value instanceof JsonObject span) || string(span.get("text")) == null
                        || span.has("color") && integer(span.get("color"), 0, 0xffffff) == null
                        || STYLES.stream().anyMatch(style -> span.has(style) && !bool(span.get(style)))) {
                    return false;
                }
                if (span.keySet().stream().anyMatch(key -> !key.equals("text") && !key.equals("color") && !STYLES.contains(key))) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean versionOne(JsonObject data) {
        return integer(data.get("version"), 1, 1) != null;
    }

    static boolean itemIds(JsonElement value) {
        if (!(value instanceof JsonArray array)) return false;
        for (var element : array) {
            if (!isId(string(element))) return false;
        }
        return true;
    }

    static boolean isId(String id) {
        if (id == null || id.indexOf(':') < 1 || id.endsWith(":")) return false;
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        return parsed != null && id.equals(parsed.toString());
    }

    static boolean bool(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean();
    }

    static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    static Integer integer(JsonElement element, int min, int max) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return null;
        try {
            int value = element.getAsBigDecimal().intValueExact();
            return value >= min && value <= max ? value : null;
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    static boolean boundedJson(JsonElement element, int depth) {
        if (depth > 32) return false;
        if (element instanceof JsonObject object) {
            return object.entrySet().stream().allMatch(entry -> boundedJson(entry.getValue(), depth + 1));
        }
        if (element instanceof JsonArray array) {
            for (var value : array) {
                if (!boundedJson(value, depth + 1)) return false;
            }
        }
        return true;
    }
}
