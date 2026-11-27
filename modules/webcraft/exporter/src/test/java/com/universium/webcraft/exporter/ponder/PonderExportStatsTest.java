package com.universium.webcraft.exporter.ponder;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PonderExportStatsTest {
    @Test
    void reportsProceduralCoverageEvenWhenTheSceneDoesNotChangeBlocks() {
        var stats = new PonderExportStats();
        stats.startScene(0);
        stats.frame(JsonParser.parseString("""
                {"fluidFaces":[{},{}],"scene":[
                  {"shader":"particle","vertices":"ponder/particle-quads/v1/a.bin"},
                  {"shader":"particle","vertices":"ponder/meshes/b.bin"},
                  {"shader":"rendertype_solid","vertices":"ponder/meshes/c.bin"},
                  {"shader":"rendertype_entity_solid","vertices":"ponder/outline-cuboids/v1/d.bin"},
                  {"shader":"rendertype_entity_cutout_no_cull","vertices":"ponder/rigid-batches/v3/e.bin"}
                ]}
                """).getAsJsonObject());
        var first = stats.finishScene("create:one", "First", "create:first", 1, 0, 0, 2);
        assertEquals(2, first.get("proceduralFluidFaces").getAsInt());
        assertEquals(2, first.get("particleBatches").getAsInt());
        assertEquals(1, first.get("parameterizedParticleBatches").getAsInt());
        assertEquals(1, first.get("outlineCuboidBatches").getAsInt());
        assertEquals(1, first.get("sodiumRigidBatches").getAsInt());

        stats.startScene(2);
        var second = stats.finishScene("create:two", "Second", "create:second", 0, 0, 0, 2);
        assertEquals(0, second.get("particleBatches").getAsInt());
        assertEquals(0, second.get("outlineCuboidBatches").getAsInt());
        assertEquals(0, second.get("sodiumRigidBatches").getAsInt());
        assertEquals(1, stats.report(2).get("parameterizedParticleBatches").getAsInt());
    }

    @Test
    void attributesOnlyNewFilesToEachSceneAndSharesBlockStateCounts() {
        var stats = new PonderExportStats();
        var changed = JsonParser.parseString("""
                {"events":{"blocks":[[0,0,0,"minecraft:stone"],[1,0,0,"minecraft:stone"],[0,0,0,null]]}}
                """).getAsJsonObject();
        stats.startScene(0);
        stats.frame(changed);
        stats.files(Map.of("ponder/meshes/first.bin", new byte[100], "ponder/textures/first.png", new byte[40],
                "ponder/scenes/first/timeline/0.json", new byte[30]));
        var first = stats.finishScene("create:one", "First", "create:first", 5, 2, 7, 3);
        assertEquals(1, first.get("distinctBlockStates").getAsInt());
        assertEquals(3, first.get("patchedBlocks").getAsInt());
        assertEquals(3, first.get("meshSubmissions").getAsInt());

        stats.startScene(3);
        stats.frame(changed);
        stats.files(Map.of("ponder/scenes/second/scene.json", new byte[20], "ponder/picking/second.json", new byte[10]));
        var second = stats.finishScene("create:two", "Second", "create:second", 4, 1, 8, 6);
        assertEquals(0, second.get("newMeshFiles").getAsInt());
        assertEquals(10, second.get("pickingBytes").getAsInt());

        var totals = stats.report(6);
        assertEquals(100, totals.get("meshBytes").getAsInt());
        assertEquals(50, totals.get("sceneBytes").getAsInt());
        assertEquals(1, totals.get("distinctBlockStates").getAsInt());
        assertEquals(9, totals.get("ticks").getAsInt());
        assertEquals(3, totals.get("reusedGeometryTicks").getAsInt());
        assertEquals(7, first.get("reusedStaticSections").getAsInt());
        assertEquals(15, totals.get("reusedStaticSections").getAsInt());
    }
}
