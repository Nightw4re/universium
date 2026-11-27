package com.universium.webcraft.exporter.ponder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonderEntityTickTest {
    @Test
    void toleratesTheReentrantEntityChangesSeenInDragonForge() {
        var entities = new ArrayList<>(List.of("dragon", "target", "other"));
        try (var tick = new PonderEntityTick()) {
            var active = PonderEntityTick.iterator(entities);
            assertEquals("dragon", active.next());
            entities.add("new particle entity");
            entities.remove("target");
            active.remove();
            assertEquals("other", active.next());
            assertFalse(active.hasNext());
            assertEquals(List.of("other", "new particle entity"), entities);
            PonderEntityTick.recordBlockEntityChange(entities);
            assertTrue(tick.dirtyBlockEntities().contains(entities));
        }
        var normal = PonderEntityTick.iterator(entities);
        assertEquals("other", normal.next());
        normal.remove();
        assertEquals(List.of("new particle entity"), entities);
    }
}
