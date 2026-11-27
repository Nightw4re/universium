package com.universium.webcraft.exporter.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class PonderExportStats {
    private static final double MIB = 1024.0 * 1024;
    private final Set<String> blockStates = new HashSet<>();
    private final Set<String> sceneBlockStates = new HashSet<>();
    private final JsonArray sceneSummaries = new JsonArray();
    private Snapshot total = Snapshot.EMPTY;
    private Snapshot sceneStart = Snapshot.EMPTY;
    private long blockEdits;
    private long startingBlockEdits;
    private long ticks;
    private long reusedGeometryTicks;
    private long reusedStaticSections;
    private long sceneMeshSubmissions;
    private long particleBatches;
    private long parameterizedParticleBatches;
    private long proceduralFluidFaces;
    private long rigidBatches;
    private long rigidAttributeBatches;
    private long sodiumRigidBatches;
    private long outlineCuboidBatches;
    private long startingParticleBatches;
    private long startingParameterizedParticleBatches;
    private long startingProceduralFluidFaces;
    private long startingRigidBatches;
    private long startingRigidAttributeBatches;
    private long startingSodiumRigidBatches;
    private long startingOutlineCuboidBatches;

    void startScene(long meshSubmissions) {
        sceneStart = total;
        sceneMeshSubmissions = meshSubmissions;
        sceneBlockStates.clear();
        startingBlockEdits = blockEdits;
        startingParticleBatches = particleBatches;
        startingParameterizedParticleBatches = parameterizedParticleBatches;
        startingProceduralFluidFaces = proceduralFluidFaces;
        startingRigidBatches = rigidBatches;
        startingRigidAttributeBatches = rigidAttributeBatches;
        startingSodiumRigidBatches = sodiumRigidBatches;
        startingOutlineCuboidBatches = outlineCuboidBatches;
    }

    void frame(JsonObject frame) {
        var fluids = frame.getAsJsonArray("fluidFaces");
        if (fluids != null) proceduralFluidFaces += fluids.size();
        var draws = frame.getAsJsonArray("scene");
        if (draws != null) {
            for (var entry : draws) {
                var draw = entry.getAsJsonObject();
                String vertices = draw.get("vertices").getAsString();
                if (vertices.startsWith("ponder/rigid-batches/")) rigidBatches++;
                if (vertices.startsWith("ponder/rigid-batches/v2/")) rigidAttributeBatches++;
                if (vertices.startsWith("ponder/rigid-batches/v3/")) sodiumRigidBatches++;
                if (vertices.startsWith("ponder/outline-cuboids/")) outlineCuboidBatches++;
                if ("particle".equals(draw.get("shader").getAsString())) {
                    particleBatches++;
                    if (vertices.startsWith("ponder/particle-quads/")) parameterizedParticleBatches++;
                }
            }
        }
        var events = frame.getAsJsonObject("events");
        if (events == null) return;
        var changes = events.getAsJsonArray("blocks");
        if (changes == null) return;
        blockEdits += changes.size();
        for (var change : changes) {
            var state = change.getAsJsonArray().get(3);
            if (state.isJsonNull()) continue;
            blockStates.add(state.getAsString());
            sceneBlockStates.add(state.getAsString());
        }
    }

    void files(Map<String, byte[]> files) {
        long meshCount = 0, meshBytes = 0, textureBytes = 0, sceneBytes = 0, pickingBytes = 0;
        for (var entry : files.entrySet()) {
            String path = entry.getKey();
            int size = entry.getValue().length;
            if (path.startsWith("ponder/meshes/")) {
                meshCount++;
                meshBytes += size;
            } else if (path.startsWith("ponder/textures/")) textureBytes += size;
            else if (path.startsWith("ponder/picking/")) pickingBytes += size;
            else if (path.startsWith("ponder/scenes/")) sceneBytes += size;
        }
        total = new Snapshot(total.meshCount + meshCount, total.meshBytes + meshBytes, total.textureBytes + textureBytes,
                total.sceneBytes + sceneBytes, total.pickingBytes + pickingBytes);
    }

    String detail() {
        return String.format(Locale.ROOT, "%d block states · %,d mesh files %.1f MiB · textures %.1f MiB · timeline %.1f MiB raw",
                blockStates.size(), total.meshCount, total.meshBytes / MIB, total.textureBytes / MIB, total.sceneBytes / MIB);
    }

    JsonObject finishScene(String id, String title, String schematic, int frames, int reused, int staticSections, long meshSubmissions) {
        var report = total.minus(sceneStart).report();
        report.addProperty("id", id);
        report.addProperty("title", title);
        report.addProperty("schematic", schematic);
        report.addProperty("patchedBlocks", blockEdits - startingBlockEdits);
        report.addProperty("distinctBlockStates", sceneBlockStates.size());
        report.addProperty("meshSubmissions", meshSubmissions - sceneMeshSubmissions);
        report.addProperty("ticks", frames);
        report.addProperty("particleBatches", particleBatches - startingParticleBatches);
        report.addProperty("parameterizedParticleBatches", parameterizedParticleBatches - startingParameterizedParticleBatches);
        report.addProperty("proceduralFluidFaces", proceduralFluidFaces - startingProceduralFluidFaces);
        report.addProperty("rigidBatches", rigidBatches - startingRigidBatches);
        report.addProperty("rigidAttributeBatches", rigidAttributeBatches - startingRigidAttributeBatches);
        report.addProperty("sodiumRigidBatches", sodiumRigidBatches - startingSodiumRigidBatches);
        report.addProperty("outlineCuboidBatches", outlineCuboidBatches - startingOutlineCuboidBatches);
        report.addProperty("reusedGeometryTicks", reused);
        report.addProperty("reusedStaticSections", staticSections);
        ticks += frames;
        reusedGeometryTicks += reused;
        reusedStaticSections += staticSections;
        sceneSummaries.add(report);
        return report;
    }

    JsonObject report(long meshSubmissions) {
        var report = total.report();
        report.addProperty("distinctBlockStates", blockStates.size());
        report.addProperty("patchedBlocks", blockEdits);
        report.addProperty("meshSubmissions", meshSubmissions);
        report.addProperty("ticks", ticks);
        report.addProperty("particleBatches", particleBatches);
        report.addProperty("parameterizedParticleBatches", parameterizedParticleBatches);
        report.addProperty("proceduralFluidFaces", proceduralFluidFaces);
        report.addProperty("rigidBatches", rigidBatches);
        report.addProperty("rigidAttributeBatches", rigidAttributeBatches);
        report.addProperty("sodiumRigidBatches", sodiumRigidBatches);
        report.addProperty("outlineCuboidBatches", outlineCuboidBatches);
        report.addProperty("reusedGeometryTicks", reusedGeometryTicks);
        report.addProperty("reusedStaticSections", reusedStaticSections);
        report.add("scenes", sceneSummaries);
        return report;
    }

    private record Snapshot(long meshCount, long meshBytes, long textureBytes, long sceneBytes, long pickingBytes) {
        private static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0);

        Snapshot minus(Snapshot previous) {
            return new Snapshot(meshCount - previous.meshCount, meshBytes - previous.meshBytes, textureBytes - previous.textureBytes,
                    sceneBytes - previous.sceneBytes, pickingBytes - previous.pickingBytes);
        }

        JsonObject report() {
            var report = new JsonObject();
            report.addProperty("newMeshFiles", meshCount);
            report.addProperty("meshBytes", meshBytes);
            report.addProperty("textureBytes", textureBytes);
            report.addProperty("sceneBytes", sceneBytes);
            report.addProperty("pickingBytes", pickingBytes);
            return report;
        }
    }
}
