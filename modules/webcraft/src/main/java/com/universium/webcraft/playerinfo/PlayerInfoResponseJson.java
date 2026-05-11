package com.universium.webcraft.playerinfo;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PlayerInfoResponseJson {
    private PlayerInfoResponseJson() {
    }

    static String toJson(PlayerInfoData data) {
        Objects.requireNonNull(data, "data");
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        appendField(builder, "playerName", quote(data.playerName()));
        builder.append(',');
        appendField(builder, "playedTimeTicks", Long.toString(data.playedTimeTicks()));
        builder.append(',');
        appendField(builder, "equipment", itemsToJson(data.equipment()));
        builder.append(',');
        appendField(builder, "mainHand", itemToJson(data.mainHand()));
        builder.append(',');
        appendField(builder, "offHand", itemToJson(data.offHand()));
        builder.append(',');
        appendField(builder, "inventory", itemsToJson(data.inventory()));
        builder.append(',');
        appendField(builder, "enderChest", itemsToJson(data.enderChest()));
        builder.append('}');
        return builder.toString();
    }

    private static String itemsToJson(List<PlayerInfoItem> items) {
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(itemToJson(items.get(i)));
        }
        builder.append(']');
        return builder.toString();
    }

    private static String itemToJson(PlayerInfoItem item) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        appendField(builder, "id", quote(item.id()));
        builder.append(',');
        appendField(builder, "count", Integer.toString(item.count()));
        builder.append(',');
        appendField(builder, "durability", Integer.toString(item.durability()));
        builder.append(',');
        appendField(builder, "displayName", quote(item.displayName()));
        builder.append(',');
        appendField(builder, "nbt", mapToJson(item.nbt()));
        builder.append(',');
        appendField(builder, "modelDescriptor", mapToJson(item.modelDescriptor()));
        builder.append(',');
        appendField(builder, "renderMode", quote(item.renderMode()));
        builder.append(',');
        appendField(builder, "texturePath", quote(item.texturePath()));
        builder.append(',');
        appendField(builder, "textureAnchor", quote(item.textureAnchor()));
        builder.append(',');
        appendField(builder, "textureFit", quote(item.textureFit()));
        builder.append(',');
        appendField(builder, "textureRepeat", quote(item.textureRepeat()));
        builder.append(',');
        appendField(builder, "previewPath", quote(item.previewPath()));
        builder.append('}');
        return builder.toString();
    }

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        Iterator<Map.Entry<String, Object>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Object> entry = iterator.next();
            appendField(builder, entry.getKey(), valueToJson(entry.getValue()));
            if (iterator.hasNext()) {
                builder.append(',');
            }
        }
        builder.append('}');
        return builder.toString();
    }

    private static String valueToJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder();
            builder.append('{');
            Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                appendField(builder, String.valueOf(entry.getKey()), valueToJson(entry.getValue()));
                if (iterator.hasNext()) {
                    builder.append(',');
                }
            }
            builder.append('}');
            return builder.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder builder = new StringBuilder();
            builder.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(valueToJson(list.get(i)));
            }
            builder.append(']');
            return builder.toString();
        }
        return quote(String.valueOf(value));
    }

    private static void appendField(StringBuilder builder, String name, String value) {
        builder.append(quote(name)).append(':').append(value);
    }

    private static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

