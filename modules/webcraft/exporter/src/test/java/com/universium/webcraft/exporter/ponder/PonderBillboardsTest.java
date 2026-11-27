package com.universium.webcraft.exporter.ponder;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PonderBillboardsTest {
    private static final PonderBillboards.Billboard QUAD =
            new PonderBillboards.Billboard(4, 5, 6, .5f, -.25f, .125f, .25f, .5f, .375f);
    private static final int COLOR = 0xa1f480cc;
    private static final int LIGHT = 0xf00070;
    private static final float[] EXPECTED = {4.5f, 4.75f, 6.125f, 4.25f, 5.5f, 6.375f,
            3.5f, 5.25f, 5.875f, 3.75f, 4.5f, 5.625f};

    @Test
    void encodesOnlyExactlyReconstructableVerticesInSubmissionOrder() {
        var vertices = quadVertices();
        var batch = PonderBillboards.encode(vertices, List.of(QUAD), 4);
        assertEquals(1, batch.version());
        var encoded = ByteBuffer.wrap(batch.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(66, encoded.remaining());
        assertEquals(1, encoded.getShort());
        assertEquals(1, encoded.getShort());
        var uv = new float[4];
        for (int index = 0; index < uv.length; index++) uv[index] = encoded.getFloat();
        assertArrayEquals(new float[] {.75f, .875f, .125f, .25f}, uv);
        assertEquals(COLOR, encoded.getInt());
        assertEquals(LIGHT, encoded.getInt());
        var geometry = new float[9];
        for (int index = 0; index < geometry.length; index++) geometry[index] = encoded.getFloat();
        assertArrayEquals(new float[] {4, 5, 6, .5f, -.25f, .125f, .25f, .5f, .375f}, geometry);
        assertEquals(0, encoded.getShort());
        assertEquals(0, vertices.position());
    }

    @Test
    void sharesTextureCoordinatesColorAndLightAcrossParticles() {
        var vertices = ByteBuffer.allocate(224).put(quadVertices()).put(quadVertices()).flip();
        var encoded = ByteBuffer.wrap(PonderBillboards.encode(vertices, List.of(QUAD, QUAD), 8).bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(104, encoded.remaining());
        assertEquals(2, encoded.getShort());
        assertEquals(1, encoded.getShort());
    }

    @Test
    void keepsMixedOrCustomParticleBatchesOnTheOriginalMeshPath() {
        var vertices = quadVertices();
        assertNull(PonderBillboards.encode(vertices, List.of(QUAD, QUAD), 4));
        vertices.putFloat(3 * 28, EXPECTED[9] + .01f);
        assertNull(PonderBillboards.encode(vertices, List.of(QUAD), 4));
        vertices.putFloat(3 * 28, EXPECTED[9]);
        vertices.putInt(2 * 28 + 20, COLOR ^ 1);
        assertNull(PonderBillboards.encode(vertices, List.of(QUAD), 4));
    }

    @Test
    void recoversVerifiedBillboardsFromAtm10SubmissionsWithoutACaptureHook() {
        // A native particle quad from an ATM10 Ponder mesh, including its rounded vertex coordinates.
        var corners = new int[][] {
                {0x3fb4edb7, 0x401c0ced, 0x40208009, 0x3f678029, 0x3f3bc55a},
                {0x3fbb45fe, 0x4023f313, 0x402507e4, 0x3f678029, 0x3f3ba55a},
                {0x3fcb1249, 0x4023f313, 0x401f7ff7, 0x3f67a029, 0x3f3ba55a},
                {0x3fc4ba02, 0x401c0ced, 0x401af81c, 0x3f67a029, 0x3f3bc55a}
        };
        var vertices = ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
        for (var corner : corners) {
            for (int value : corner) vertices.putInt(value);
            vertices.putInt(0xffffffff).putInt(0xf000f0);
        }
        vertices.flip();
        var encoded = PonderBillboards.encode(vertices, List.of(), 4);
        assertNotNull(encoded);
        assertEquals(66, encoded.bytes().length);
        vertices.putFloat(3 * 28, 5);
        assertNull(PonderBillboards.encode(vertices, List.of(), 4));
    }

    @Test
    void preservesSmallRoundedCornerCorrections() {
        var vertices = quadVertices();
        vertices.putInt(0, Float.floatToRawIntBits(EXPECTED[0]) + 4);
        var batch = PonderBillboards.encode(vertices, List.of(QUAD), 4);
        assertNotNull(batch);
        assertEquals(2, batch.version());
        assertEquals(72, batch.bytes().length);
        assertEquals(4, batch.bytes()[66]);
    }

    @Test
    void sharesOppositeDiagonalsAndKeepsSparseCorrectionsInSubmissionOrder() {
        var part = new PonderBillboards.Billboard(4, 5, 6, .5f, -.25f, .125f, .25f, .25f, -.125f);
        float[] positions = {4.5f, 4.75f, 6.125f, 4.25f, 5.25f, 5.875f,
                3.5f, 5.25f, 5.875f, 3.75f, 4.75f, 6.125f};
        var vertices = ByteBuffer.allocate(336).order(ByteOrder.LITTLE_ENDIAN)
                .put(quadVertices(positions)).put(quadVertices(positions)).put(quadVertices(positions)).flip();
        vertices.putInt(112, vertices.getInt(112) + 4);

        var batch = PonderBillboards.encode(vertices, List.of(part, part, part), 12);
        assertEquals(3, batch.version());
        var data = ByteBuffer.wrap(batch.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(130, data.remaining());
        assertEquals(3, data.getShort());
        assertEquals(1, data.getShort());
        assertEquals(0b110, data.getShort());
        assertEquals(1, data.getShort());
        assertEquals(.25f, data.getFloat(32 + 24));
        assertEquals(0, data.getShort(32 + 28));
        assertEquals(1, data.getShort(122));
        assertEquals(4, data.get(124));
    }

    @Test
    void sharesOneOrientationAcrossMovingParticlesOfDifferentSizes() {
        var basis = new PonderBillboards.Basis(1, -.5f, .25f, .5f, 1, .75f);
        var first = basis.scale(4, 5, 6, .5f);
        var second = basis.scale(5, 5, 6, .25f);
        float[] secondPositions = {5.25f, 4.875f, 6.0625f, 5.125f, 5.25f, 6.1875f,
                4.75f, 5.125f, 5.9375f, 4.875f, 4.75f, 5.8125f};
        var vertices = ByteBuffer.allocate(336).order(ByteOrder.LITTLE_ENDIAN)
                .put(quadVertices()).put(quadVertices(secondPositions)).put(quadVertices()).flip();
        vertices.putInt(112, vertices.getInt(112) - 4);
        var batch = PonderBillboards.encode(vertices, List.of(first, second, first), 12);
        assertNotNull(batch);
        assertEquals(4, batch.version());
        var data = ByteBuffer.wrap(batch.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(124, data.remaining());
        assertEquals(3, data.getShort());
        assertEquals(1, data.getShort());
        assertEquals(1, data.getShort());
        assertEquals(1, data.getShort());
        assertEquals(.5f, data.getFloat(56 + 12));
        assertEquals(.25f, data.getFloat(76 + 12));
        assertEquals(0, data.getShort(76 + 16));
        assertEquals(1, data.getShort(116));
        assertEquals(12, data.get(118));
    }

    @Test
    void keepsSharedOrientationsWhenOneQuadNeedsAnInferredCenter() {
        var basis = new PonderBillboards.Basis(1, -.5f, .25f, .5f, 1, .75f);
        var regular = basis.scale(4, 5, 6, .5f);
        var mismatched = basis.scale(8, 9, 10, .5f);
        var vertices = ByteBuffer.allocate(672).order(ByteOrder.LITTLE_ENDIAN);
        for (int quad = 0; quad < 6; quad++) vertices.put(quadVertices());
        vertices.flip();
        var batch = PonderBillboards.encode(vertices,
                List.of(mismatched, regular, regular, regular, regular, regular), 24);
        assertNotNull(batch);
        assertEquals(4, batch.version());
        var data = ByteBuffer.wrap(batch.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, data.getShort(4));
        assertEquals(0, data.getShort(6));
        assertEquals(4, data.getFloat(80));
        assertEquals(1, data.getFloat(80 + 12));
        assertEquals(.5f, data.getFloat(100 + 12));
    }

    @Test
    void verifiesCameraFacingGeometryWithoutTheVanillaParticleHook() {
        var basis = new PonderBillboards.Basis(1, -.5f, .25f, .5f, 1, .75f);
        float[] secondPositions = {5.25f, 4.875f, 6.0625f, 5.125f, 5.25f, 6.1875f,
                4.75f, 5.125f, 5.9375f, 4.875f, 4.75f, 5.8125f};
        var vertices = ByteBuffer.allocate(336).order(ByteOrder.LITTLE_ENDIAN)
                .put(quadVertices()).put(quadVertices(secondPositions)).put(quadVertices()).flip();
        var batch = PonderBillboards.encode(vertices, List.of(), 12, basis);
        assertNotNull(batch);
        assertEquals(4, batch.version());
        var data = ByteBuffer.wrap(batch.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, data.getShort(4));
        assertEquals(.5f, data.getFloat(56 + 12));
        assertEquals(.25f, data.getFloat(76 + 12));

        var incompatible = new PonderBillboards.Basis(1, 0, 0, 0, 1, 0);
        var fallback = PonderBillboards.encode(vertices, List.of(), 12, incompatible);
        assertNotNull(fallback);
        assertEquals(1, fallback.version());
    }

    private static ByteBuffer quadVertices() {
        return quadVertices(EXPECTED);
    }

    private static ByteBuffer quadVertices(float[] positions) {
        var buffer = ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
        for (int corner = 0; corner < 4; corner++) {
            buffer.putFloat(positions[corner * 3]).putFloat(positions[corner * 3 + 1]).putFloat(positions[corner * 3 + 2]);
            buffer.putFloat(corner < 2 ? .75f : .125f);
            buffer.putFloat(corner == 0 || corner == 3 ? .875f : .25f);
            buffer.putInt(COLOR).putInt(LIGHT);
        }
        return buffer.flip();
    }
}
