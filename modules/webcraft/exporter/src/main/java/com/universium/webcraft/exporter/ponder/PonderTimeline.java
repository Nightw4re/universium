package com.universium.webcraft.exporter.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;

final class PonderTimeline {
    static final int CHUNK_TICKS = 16;

    private JsonArray chunk = new JsonArray(CHUNK_TICKS);
    private JsonObject previous;

    void append(JsonObject frame) {
        var changes = new JsonObject();
        for (var field : frame.entrySet()) {
            if (field.getKey().equals("events")) {
                if (!field.getValue().getAsJsonObject().isEmpty()) changes.add("events", field.getValue());
                continue;
            }
            if (previous == null || !field.getValue().equals(previous.get(field.getKey()))) {
                changes.add(field.getKey(), field.getValue());
            }
        }
        chunk.add(changes);
        previous = frame;
    }

    void flush(String directory, int ticks, boolean finished, Map<String, byte[]> files) {
        if (ticks % CHUNK_TICKS != 0 && !finished) return;
        int firstTick = ticks - chunk.size();
        String path = directory + "/timeline/" + firstTick + ".json";
        files.put(path, chunk.toString().getBytes(StandardCharsets.UTF_8));
        chunk = new JsonArray(CHUNK_TICKS);
    }
}
