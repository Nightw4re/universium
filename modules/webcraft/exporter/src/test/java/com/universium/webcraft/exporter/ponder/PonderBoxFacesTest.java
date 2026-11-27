package com.universium.webcraft.exporter.ponder;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PonderBoxFacesTest {
    @Test
    void identicalNormalComponentsIgnoreOnlyTheUnusedAlignmentByte() {
        var pose = new Matrix4f().translation(.125f, .25f, .5f);
        float[][] corners = {{1, .31f, 4}, {2, .31f, 4}, {2, .31f, 3}, {1, .31f, 3}};
        var face = PonderBoxFaces.record(0, pose, 1, 2, .31f, .31f, 3, 4);
        var entity = ByteBuffer.allocate(144).order(ByteOrder.LITTLE_ENDIAN);
        emit(entity, pose, corners);
        entity.flip();
        var block = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        for (int vertex = 0; vertex < 4; vertex++) {
            int start = vertex * 36;
            block.put(entity.slice(start, 24)).put(entity.slice(start + 28, 8));
        }
        block.flip();
        for (int stride : List.of(32, 36)) {
            var source = stride == 32 ? block : entity;
            var packed = PonderBoxFaces.encode(source, 4, List.of(face), stride == 32);
            assertNotNull(packed);
            for (int vertex = 0; vertex < 4; vertex++) source.put((vertex + 1) * stride - 1, (byte) (0xab + vertex));
            assertArrayEquals(packed, PonderBoxFaces.encode(source, 4, List.of(face), stride == 32));
            assertEquals((byte) 0xab, source.get(stride - 1));
            source.put(stride - 2, (byte) 1);
            assertNull(PonderBoxFaces.encode(source, 4, List.of(face), stride == 32));
        }
    }

    @Test
    void preservesCornerOrderUvWindingAndGroupsByPose() {
        var firstPose = new Matrix4f().translation(7.25f, -10.1f, 2.5f).rotateY(.51f);
        var secondPose = new Matrix4f(firstPose).translate(.125f, 0, .9375f);
        var first = PonderBoxFaces.record(0, firstPose, 1, 2, .31f, .31f, 3, 4);
        var second = PonderBoxFaces.record(4, firstPose, 1, 2, .31f, .94f, 3, 3);
        var third = PonderBoxFaces.record(8, secondPose, 1, 2, .31f, .94f, 3, 3);
        var vertices = ByteBuffer.allocate(12 * 36).order(ByteOrder.LITTLE_ENDIAN);
        emit(vertices, firstPose, new float[][] {{1, .31f, 4}, {2, .31f, 4}, {2, .31f, 3}, {1, .31f, 3}});
        float[][] side = {{2, .94f, 3}, {1, .94f, 3}, {1, .31f, 3}, {2, .31f, 3}};
        emit(vertices, firstPose, side);
        emit(vertices, secondPose, side);
        var faces = List.of(first, second, third);
        byte[] packed = PonderBoxFaces.encode(vertices.flip(), 12, faces);
        assertNotNull(packed);
        var data = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(279, packed.length);
        assertEquals(2, data.getShort(0));
        assertEquals(2, data.getShort(2));
        assertEquals(1, data.getShort(170));
        assertEquals(180, Byte.toUnsignedInt(data.get(52 + 26)));
        vertices.putFloat(2 * 36 + 16, .71f);
        assertNull(PonderBoxFaces.encode(vertices, 12, faces));
    }

    @Test
    void storesRectangularFacesBetweenUnmodifiedEntityQuadsInSubmissionOrder() {
        var pose = new Matrix4f().translation(1.25f, 0, 2).rotateY(.51f);
        float[][] corners = {{1, .31f, 4}, {2, .31f, 4}, {2, .31f, 3}, {1, .31f, 3}};
        var faces = List.of(PonderBoxFaces.record(4, pose, 1, 2, .31f, .31f, 3, 4),
                PonderBoxFaces.record(8, pose, 1, 2, .31f, .31f, 3, 4),
                PonderBoxFaces.record(12, pose, 1, 2, .31f, .31f, 3, 4));
        var vertices = ByteBuffer.allocate(16 * 36).order(ByteOrder.LITTLE_ENDIAN);
        for (int quad = 0; quad < 4; quad++) emit(vertices, pose, corners);
        vertices.flip();
        vertices.putInt(3 * 144 + 32, 0x72807f00);
        assertNull(PonderBoxFaces.encode(vertices, 16, faces));
        byte[] encoded = PonderBoxFaces.encodeMixed(vertices, 16, faces);
        assertNotNull(encoded);
        var data = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, data.getShort());
        assertEquals(1, data.getShort());
        assertEquals(144, data.getInt());
        assertEquals(-1, data.slice(8, 144).mismatch(vertices.slice(0, 144)));
        data.position(152);
        assertEquals(0x8002, Short.toUnsignedInt(data.getShort()));
        assertEquals(170, data.getInt());
        assertEquals(1, data.getShort(158));
        assertEquals(2, data.getShort(160));
        data.position(328);
        assertEquals(1, data.getShort());
        assertEquals(144, data.getInt());
        assertEquals(-1, data.slice(334, 144).mismatch(vertices.slice(432, 144)));
        assertEquals(478, data.limit());

        assertNull(PonderBoxFaces.encodeMixed(vertices, 16, List.of(faces.get(1), faces.get(0))));
        vertices.putFloat(0, Float.NaN);
        assertNull(PonderBoxFaces.encodeMixed(vertices, 16, faces));
    }

    @Test
    void sharesTheItemModelAlongsideBoxFacesWithoutComparingUnusedNormalPadding() {
        var identity = new Matrix4f();
        float[][] corners = {{1, .31f, 4}, {2, .31f, 4}, {2, .31f, 3}, {1, .31f, 3}};
        var vertices = ByteBuffer.allocate(20 * 36).order(ByteOrder.LITTLE_ENDIAN);
        for (int quad = 0; quad < 5; quad++) emit(vertices, identity, corners);
        vertices.flip();
        var model = PonderRigidParts.Part.localMesh(vertices.slice(0, 288), 8);
        assertNotNull(model);
        var position = new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
        var normal = new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
        var item = new PonderRigidParts.Placement(model, 0, position, normal);
        var faces = List.of(PonderBoxFaces.record(8, identity, 1, 2, .31f, .31f, 3, 4),
                PonderBoxFaces.record(12, identity, 1, 2, .31f, .31f, 3, 4));
        for (int vertex = 0; vertex < 8; vertex++) vertices.putInt(vertex * 36 + 12, 0x7182a3b0 + vertex);
        vertices.putInt(4 * 144 + 32, 0x78007f00);
        var result = PonderBoxFaces.encodeMixed(vertices, 20, faces, List.of(item));
        assertNotNull(result);
        assertEquals(4, result.version());
        assertEquals(1, result.rigidParts().size());
        assertSame(model, result.rigidParts().getFirst().parts().getFirst());
        var tints = ByteBuffer.wrap(result.rigidParts().getFirst().attributes().getFirst().bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int vertex = 0; vertex < 8; vertex++) assertEquals(0x7182a3b0 + vertex, tints.getInt(vertex * 12));
        var payload = ByteBuffer.wrap(result.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, payload.getShort());
        assertEquals(0x4002, Short.toUnsignedInt(payload.getShort()));
        int rigidBytes = payload.getInt();
        assertEquals(5, payload.get());
        payload.position(8 + rigidBytes);
        assertEquals(0x8002, Short.toUnsignedInt(payload.getShort()));
        payload.position(payload.position() + 4 + payload.getInt(payload.position()));
        assertEquals(1, payload.getShort());
        assertEquals(144, payload.getInt());
        assertEquals(-1, payload.slice().mismatch(vertices.slice(576, 144)));

        vertices.put(35, (byte) 0x78);
        vertices.put(8 * 36 + 35, (byte) 0xdf);
        var padded = PonderBoxFaces.encodeMixed(vertices, 20, faces, List.of(item));
        assertNotNull(padded);
        assertEquals(4, padded.version());
        assertArrayEquals(result.bytes(), padded.bytes());

        vertices.putInt(32, 0x78807f00);
        var fallback = PonderBoxFaces.encodeMixed(vertices, 20, faces, List.of(item));
        assertNotNull(fallback);
        assertEquals(3, fallback.version());
    }

    private static void emit(ByteBuffer out, Matrix4f pose, float[][] points) {
        var position = new Vector3f();
        for (int i = 0; i < points.length; i++) {
            var xyz = points[i];
            pose.transformPosition(xyz[0], xyz[1], xyz[2], position);
            out.putFloat(position.x).putFloat(position.y).putFloat(position.z).putInt(0x8182a3b4);
            out.putFloat(i == 1 || i == 2 ? .35f : .25f).putFloat(i < 2 ? .5f : .71f);
            out.putInt(0x000a0000).putInt(0x00c00090).putInt(0x007f00);
        }
    }
}
