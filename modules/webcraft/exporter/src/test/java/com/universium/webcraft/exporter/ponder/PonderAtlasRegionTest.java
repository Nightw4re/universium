package com.universium.webcraft.exporter.ponder;

import com.google.gson.JsonObject;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonderAtlasRegionTest {
    @Test
    void preservesTexelCoordinatesAfterApplyingTheDrawTextureMatrix() {
        var original = new Matrix4f().translation(0.25f, 0.125f, 0);
        var region = PonderAtlasRegion.select(uvs(0.25f, 0.25f, 0.375f, 0.375f), 2, 8, 0,
                original, 8192, 4096, 2048);

        assertEquals(new PonderAtlasRegion(4064, 1504, 1088, 576), region);
        var remapped = region.textureMatrix(original, 8192, 4096).transform(new Vector4f(0.25f, 0.25f, 0, 1));
        assertEquals(32, remapped.x * region.width(), 0.001f);
        assertEquals(32, remapped.y * region.height(), 0.001f);
    }

    @Test
    void respectsTheConfiguredSizeAndFallsBackForWrappingCoordinates() {
        var identity = new Matrix4f();
        assertNull(PonderAtlasRegion.select(uvs(0, 0, 0.125f, 0.125f), 2, 8, 0, identity, 8192, 8192, 1024));
        assertNull(PonderAtlasRegion.select(uvs(0, 0, 1.01f, 0), 2, 8, 0, identity, 8192, 8192, 2048));
    }

    @Test
    void reconstructsMovingPartUvsFromLocalShapeAndPlacementOffset() {
        var region = PonderAtlasRegion.select(uvs(0.625f, 0.25f, 0.65625f, 0.28125f), 2, 8, 0,
                new Matrix4f(), 8192, 4096, 2048);
        var page = new PonderDrawCapture.Assets.AtlasPage("page.png", region, 8192, 4096, "animation.json", true);
        var draw = new JsonObject();
        page.writeTexture(draw);
        assertEquals("page.png", draw.get("image").getAsString());
        assertEquals("animation.json", draw.get("atlasAnimation").getAsString());
        assertTrue(draw.get("animated").getAsBoolean());
        float originU = page.u(0.625f), originV = page.v(0.25f);
        float storedU = PonderCompactModels.uvRelative(page.u(0.65625f), originU, true);
        float storedV = PonderCompactModels.uvRelative(page.v(0.28125f), originV, true);

        assertEquals(5376, (storedU + originU) * region.width() + region.x(), 0.001f);
        assertEquals(1152, (storedV + originV) * region.height() + region.y(), 0.001f);
    }

    @Test
    void splitsDistantAtlasQuadsWithoutChangingTheirOrder() {
        var vertices = ByteBuffer.allocate(12 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int x : new int[] {96, 6016, 6048}) {
            for (int vertex = 0; vertex < 4; vertex++) {
                vertices.putFloat((x + (vertex % 2) * 16) / 8192f).putFloat((128 + (vertex / 2) * 16) / 8192f);
            }
        }
        vertices.flip();
        var identity = new Matrix4f();
        var slices = PonderAtlasRegion.partition(vertices, 12, 8, 0, identity, 8192, 8192, 2048, 2);
        assertEquals(List.of(0, 4), slices.stream().map(PonderAtlasRegion.Slice::firstVertex).toList());
        assertEquals(List.of(4, 8), slices.stream().map(PonderAtlasRegion.Slice::vertexCount).toList());
        assertEquals(List.of(), PonderAtlasRegion.partition(vertices, 12, 8, 0, identity, 8192, 8192, 2048, 1));
        assertEquals(0, vertices.position());

        vertices.putFloat(8, 0.9f);
        assertEquals(List.of(), PonderAtlasRegion.partition(vertices, 12, 8, 0, identity, 8192, 8192, 2048, 3));
    }

    @Test
    void packsSeparatedRegionsAtNativeResolutionAndKeepsTheUvBuffer() {
        var vertices = ByteBuffer.allocate(12 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int x : new int[] {96, 6016, 6048}) {
            for (int vertex = 0; vertex < 4; vertex++) {
                vertices.putFloat((x + (vertex % 2) * 16) / 8192f).putFloat((128 + (vertex / 2) * 16) / 8192f);
            }
        }
        vertices.flip();
        var identity = new Matrix4f();
        assertNull(PonderAtlasRegion.select(vertices, 12, 8, 0, identity, 8192, 8192, 2048));
        var packed = PonderAtlasRegion.packQuads(vertices, 12, 8, 0, identity, 8192, 8192, 2048);
        assertNotNull(packed);
        assertEquals(128, packed.width());
        assertEquals(256, packed.height());
        assertEquals(2, packed.placements().size());
        var first = packed.placements().getFirst();
        var next = packed.placements().getLast();
        assertEquals(new PonderAtlasRegion(5984, 96, 128, 96), first.region());
        assertEquals(new PonderAtlasRegion(64, 96, 96, 96), next.region());
        assertEquals(0, first.y());
        assertEquals(96, next.y());
        assertFalse(first.region().intersects(next.region()));
        assertEquals(0, vertices.position());
        assertNull(PonderAtlasRegion.packQuads(vertices, 12, 8, 0, identity, 8192, 8192, 96));
    }

    @Test
    void clipsAnAnimatedSpriteAtACropEdgeAndUsesItsPackedDestination() {
        var sprite = new PonderAtlasRegion(48, 80, 32, 32);
        var region = new PonderAtlasRegion(64, 64, 64, 32);
        var placement = new PonderAtlasRegion.Placement(region, 128, 96);
        assertEquals(new PonderAtlasRegion.SpriteCopy(128, 112, 16, 16, 16, 0),
                PonderAtlasRegion.clipSprite(placement, sprite));
        assertNull(PonderAtlasRegion.clipSprite(placement, new PonderAtlasRegion(0, 80, 64, 16)));
    }

    @Test
    void packsSparseQuadsEvenWhenTheBoundingCropFitsInOnePage() {
        var vertices = ByteBuffer.allocate(8 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int origin : new int[] {96, 1408}) {
            for (int vertex = 0; vertex < 4; vertex++) {
                vertices.putFloat((origin + vertex % 2 * 16) / 8192f);
                vertices.putFloat((origin + vertex / 2 * 16) / 8192f);
            }
        }
        vertices.flip();
        var identity = new Matrix4f();
        var bounds = PonderAtlasRegion.select(vertices, 8, 8, 0, identity, 8192, 8192, 2048);
        assertNotNull(bounds);
        var packed = PonderAtlasRegion.packSparseQuads(vertices, 8, 8, 0, identity, 8192, 8192, 2048, bounds);
        assertNotNull(packed);
        assertEquals(128 * 256, packed.width() * packed.height());
        assertEquals(List.of(new PonderAtlasRegion(64, 64, 96, 96), new PonderAtlasRegion(1376, 1376, 96, 96)),
                packed.placements().stream().map(PonderAtlasRegion.Placement::region).toList());
        var small = PonderAtlasRegion.select(vertices.slice(0, 32), 4, 8, 0, identity, 8192, 8192, 2048);
        assertNotNull(small);
        assertNull(PonderAtlasRegion.packSparseQuads(vertices.slice(0, 32), 4, 8, 0, identity, 8192, 8192, 2048, small));
    }

    private static ByteBuffer uvs(float u1, float v1, float u2, float v2) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(u1).putFloat(v1).putFloat(u2).putFloat(v2).flip();
    }
}
