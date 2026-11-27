package com.universium.webcraft.exporter.ponder;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonderTimelineTest {
    @Test
    void retainsOnlyChangesAcrossChunkBoundaries() {
        var timeline = new PonderTimeline();
        var files = new HashMap<String, byte[]>();
        var initial = JsonParser.parseString("{\"scene\":[],\"captions\":[]}").getAsJsonObject();
        for (int tick = 1; tick <= PonderTimeline.CHUNK_TICKS; tick++) {
            timeline.append(initial);
            timeline.flush("scene", tick, false, files);
            if (tick < PonderTimeline.CHUNK_TICKS) assertTrue(files.isEmpty());
        }
        var first = JsonParser.parseString(new String(files.get("scene/timeline/0.json"),
                StandardCharsets.UTF_8)).getAsJsonArray();
        assertEquals(PonderTimeline.CHUNK_TICKS, first.size());
        assertEquals(initial, first.get(0));
        assertTrue(first.get(15).getAsJsonObject().isEmpty());

        var changed = JsonParser.parseString("{\"scene\":[],\"captions\":[{\"text\":\"Start\"}]}").getAsJsonObject();
        timeline.append(changed);
        timeline.append(initial);
        timeline.flush("scene", 18, true, files);
        var second = JsonParser.parseString(new String(files.get("scene/timeline/16.json"),
                StandardCharsets.UTF_8));
        assertEquals(JsonParser.parseString("[{\"captions\":[{\"text\":\"Start\"}]},{\"captions\":[]}]"), second);
    }
}
