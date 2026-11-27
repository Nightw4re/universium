package com.universium.webcraft.exporter.ponder;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PonderRigidPartsTest {
    private static final float[] POSITION = {0, 0, -1, 0, 1, 0, 1, 0, 0, 10, 20, 30};
    private static final float[] NORMAL = {0, 0, -1, 0, 1, 0, 1, 0, 0};
    private static final int COLOR = 0x7f554433, OVERLAY = 0x000a0000, LIGHT = 0x00f00080;

    @Test
    void reusesFreshEqualTubeRingsButSeparatesShapesSectionsAndSpacing() {
        var variants = new PonderDrawCapture.RigidVariants();
        var rings = new ArrayList<>(List.of(new Vector3f(0, 1, 0), new Vector3f(1, 2, 3)));
        var equalRings = List.of(new Vector3f(0, 1, 0), new Vector3f(1, 2, 3));
        var exterior = variants.key(rings, "exterior", 1, false);
        assertSame(exterior, variants.key(equalRings, "exterior", 1, false));
        assertNotSame(exterior, variants.key(equalRings, "interior", 1, false));
        assertNotSame(exterior, variants.key(equalRings, "exterior", 2, false));
        assertNotSame(exterior, variants.key(equalRings, "exterior", 1, true));
        rings.set(1, new Vector3f(3, 2, 1));
        assertNotSame(exterior, variants.key(rings, "exterior", 1, false));
        assertSame(exterior, variants.key(equalRings, "exterior", 1, false));
    }

    @Test
    void reusesOneLocalPartAcrossOrderedPosesOnlyWhenTheyMatchTheOriginalDraw() {
        var model = PonderRigidParts.Part.localMesh(vertices(false, 1), 4);
        assertNotNull(model);
        assertEquals(96, model.bytes().length);
        var draw = vertices(true, 2);
        var secondPose = POSITION.clone();
        secondPose[9] = 20;
        var first = new PonderRigidParts.Placement(model, 0, POSITION, NORMAL);
        var second = new PonderRigidParts.Placement(model, 4, secondPose, NORMAL);
        var encoded = PonderRigidParts.encode(draw, 8, List.of(first, second));
        assertNotNull(encoded);
        assertEquals(1, encoded.version());
        assertEquals(262, encoded.bytes().length);
        assertEquals(0, encoded.attributes().size());
        assertEquals(0, encoded.normals().size());
        var packet = ByteBuffer.wrap(encoded.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, packet.getShort());
        for (var placement : List.of(first, second)) {
            byte[] digest = new byte[32];
            packet.get(digest);
            assertArrayEquals(model.digest(), digest);
            assertEquals(4, packet.getShort());
            for (float value : placement.position()) assertEquals(value, packet.getFloat());
            for (float value : NORMAL) assertEquals(value, packet.getFloat());
            assertEquals(COLOR, packet.getInt());
            assertEquals(OVERLAY, packet.getInt());
            assertEquals(LIGHT, packet.getInt());
        }

        var reasons = EnumSet.noneOf(PonderRigidParts.Fallback.class);
        assertNull(PonderRigidParts.encode(draw, 8, List.of(first), reasons::add));
        assertEquals(EnumSet.of(PonderRigidParts.Fallback.UNCOVERED_VERTICES), reasons);
        assertNull(PonderRigidParts.encode(draw, 8, List.of(second, first)));
        draw.putFloat(16, .125f);
        reasons.clear();
        assertNull(PonderRigidParts.encode(draw, 8, List.of(first, second), reasons::add));
        assertEquals(EnumSet.of(PonderRigidParts.Fallback.TEXTURE_COORDINATES), reasons);
        draw.putFloat(16, .25f);
        draw.put(32, (byte) 126);
        reasons.clear();
        assertNull(PonderRigidParts.encode(draw, 8, List.of(first, second), reasons::add));
        assertEquals(EnumSet.of(PonderRigidParts.Fallback.NORMAL_MAPPING_CONFLICT), reasons);
    }

    @Test
    void sharesRotatedItemPartsWithOrderedNormalPalettesAndInlineAxisParts() {
        var rotated = vertices(false, 2);
        var expectedNormals = new byte[] {-45, 64, 91, 55, -21, -119};
        for (int i = 0; i < 8; i++) {
            int first = i * 36 + 32;
            rotated.put(first, (byte) (i < 4 ? 90 : 110));
            rotated.put(first + 1, (byte) (i < 4 ? 0 : 64));
            rotated.put(first + 2, (byte) (i < 4 ? 90 : 0));
        }
        var tilted = PonderRigidParts.Part.localMesh(rotated, 8);
        assertNotNull(tilted);
        var axis = PonderRigidParts.Part.localMesh(vertices(false, 2), 8);
        assertNotNull(axis);
        var world = vertices(true, 4);
        for (int i = 0; i < 16; i++) {
            world.putFloat(i * 36, i < 8 ? 13 : 33);
            if (i < 8) {
                int paletteOffset = i / 4 * 3;
                for (int component = 0; component < 3; component++) {
                    world.put(i * 36 + 32 + component, expectedNormals[paletteOffset + component]);
                }
                world.putInt(i * 36 + 12, COLOR + i);
            }
        }
        var laterPose = POSITION.clone();
        laterPose[9] = 30;
        var first = new PonderRigidParts.Placement(tilted, 0, POSITION, NORMAL);
        var second = new PonderRigidParts.Placement(axis, 8, laterPose, NORMAL);
        var batch = PonderRigidParts.encode(world, 16, List.of(first, second));
        assertNotNull(batch);
        assertEquals(4, batch.version());
        assertEquals(2 + 2 * 194, batch.bytes().length);
        assertEquals(1, batch.attributes().size());
        assertEquals(1, batch.normals().size());
        var normals = batch.normals().getFirst();
        assertArrayEquals(expectedNormals, normals.bytes());
        assertArrayEquals(batch.attributes().getFirst().digest(), Arrays.copyOfRange(batch.bytes(), 132, 164));
        assertArrayEquals(normals.digest(), Arrays.copyOfRange(batch.bytes(), 164, 196));
        assertArrayEquals(new byte[64], Arrays.copyOfRange(batch.bytes(), 326, 390));
        var actualNormals = ByteBuffer.wrap(normals.bytes());
        for (int vertex = 0; vertex < 8; vertex++) {
            for (int component = 0; component < 3; component++) {
                assertEquals(world.get(vertex * 36 + 32 + component), actualNormals.get(vertex / 4 * 3 + component));
            }
        }

        var reasons = EnumSet.noneOf(PonderRigidParts.Fallback.class);
        var truncated = world.duplicate();
        truncated.limit(truncated.limit() - 1);
        assertNull(PonderRigidParts.encode(truncated, 16, List.of(first, second), reasons::add));
        assertEquals(EnumSet.of(PonderRigidParts.Fallback.BUFFER_LIMIT), reasons);
        reasons.clear();
        world.put(32, (byte) -128);
        assertNull(PonderRigidParts.encode(world, 16, List.of(first, second), reasons::add));
        assertEquals(EnumSet.of(PonderRigidParts.Fallback.INVALID_NORMAL), reasons);
        rotated.put(32, (byte) -128);
        assertNull(PonderRigidParts.Part.localMesh(rotated, 8));
    }

    @Test
    void keepsRawGeometryIfTheNormalPaletteWouldIncreaseTheDrawSize() {
        var local = vertices(false, 1);
        for (int i = 0; i < 4; i++) local.put(i * 36 + 32, (byte) 70);
        var tilted = PonderRigidParts.Part.localMesh(local, 4);
        assertNotNull(tilted);
        var reasons = EnumSet.noneOf(PonderRigidParts.Fallback.class);
        assertNull(PonderRigidParts.encode(vertices(true, 1), 4,
                List.of(new PonderRigidParts.Placement(tilted, 0, POSITION, NORMAL)), reasons::add));
        assertEquals(EnumSet.of(PonderRigidParts.Fallback.ENCODED_SIZE), reasons);
    }

    @Test
    void preservesASmallTrailingDetailAlongsideSharedRotatedItems() {
        var local = vertices(false, 48);
        for (int i = 0; i < 192; i++) {
            local.put(i * 36 + 32, (byte) 90);
            local.put(i * 36 + 34, (byte) 90);
        }
        var item = PonderRigidParts.Part.localMesh(local, 192);
        assertNotNull(item);
        var original = vertices(true, 54);
        for (int i = 0; i < 192; i++) original.putFloat(i * 36, 13);
        var known = new PonderRigidParts.Placement(item, 0, POSITION, NORMAL);
        var batch = PonderRigidParts.encode(original, 216, List.of(known));
        assertNotNull(batch);
        assertEquals(4, batch.version());
        assertEquals(2, batch.parts().size());
        assertArrayEquals(item.digest(), batch.parts().getFirst().digest());
        var detail = batch.parts().getLast();
        assertEquals(24, detail.count());
        assertArrayEquals(detail.digest(), Arrays.copyOfRange(batch.bytes(), 196, 228));
        var tailPose = ByteBuffer.wrap(batch.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        tailPose.position(230);
        var matrix = new float[12];
        for (int i = 0; i < matrix.length; i++) matrix[i] = tailPose.getFloat();
        var vertices = ByteBuffer.wrap(detail.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 24; i++) {
            int in = (192 + i) * 36, out = i * 24;
            for (int axis = 0; axis < 3; axis++) {
                float restored = PonderRigidParts.transform(matrix, axis,
                        vertices.getFloat(out), vertices.getFloat(out + 4), vertices.getFloat(out + 8), matrix[9 + axis]);
                assertEquals(original.getInt(in + axis * 4), Float.floatToRawIntBits(restored));
            }
            assertEquals(original.getLong(in + 16), vertices.getLong(out + 12));
            assertEquals(original.getInt(in + 32) & 0xffffff, vertices.getInt(out + 20));
        }
        for (int i = 192; i < 216; i++) original.putFloat(i * 36, original.getFloat(i * 36) + 16.125f);
        var shifted = PonderRigidParts.encode(original, 216, List.of(known));
        assertNotNull(shifted);
        assertArrayEquals(detail.digest(), shifted.parts().getLast().digest());

        original.putFloat(193 * 36, 0.0000001f);
        var unshiftable = PonderRigidParts.encode(original, 216, List.of(known));
        assertNotNull(unshiftable);
        var unshiftedVertices = ByteBuffer.wrap(unshiftable.parts().getLast().bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(original.getInt(192 * 36), unshiftedVertices.getInt(0));
        assertEquals(original.getInt(193 * 36), unshiftedVertices.getInt(24));

        original.put(192 * 36 + 32, (byte) -128);
        assertNull(PonderRigidParts.encode(original, 216, List.of(known)));
    }

    @Test
    void keepsSharedGeometryWhenTheUncoveredRangeExceedsTheOldSixtyFourVertexLimit() {
        var large = PonderRigidParts.Part.localMesh(vertices(false, 128), 512);
        assertNotNull(large);
        var draw = vertices(true, 176);
        for (int i = 0; i < 512; i++) draw.putFloat(i * 36, 13);
        var placement = new PonderRigidParts.Placement(large, 0, POSITION, NORMAL);
        var batch = PonderRigidParts.encode(draw, 704, List.of(placement));
        assertNotNull(batch);
        assertEquals(List.of(512, 192), batch.parts().stream().map(PonderRigidParts.Part::count).toList());
        assertArrayEquals(large.digest(), batch.parts().getFirst().digest());
    }

    @Test
    void keepsSharedPartsBeforeAndAfterACapturedDetailWithDifferentUvs() {
        var model = PonderRigidParts.Part.localMesh(vertices(false, 12), 48);
        var detail = PonderRigidParts.Part.localMesh(vertices(false, 2), 8);
        assertNotNull(model);
        assertNotNull(detail);
        var draw = vertices(true, 26);
        for (int vertex = 0; vertex < 104; vertex++) {
            draw.putFloat(vertex * 36, 13);
        }
        for (int vertex = 48; vertex < 56; vertex++) {
            draw.putFloat(vertex * 36 + 16, .125f);
        }
        var placements = List.of(new PonderRigidParts.Placement(model, 0, POSITION, NORMAL),
                new PonderRigidParts.Placement(detail, 48, POSITION, NORMAL),
                new PonderRigidParts.Placement(model, 56, POSITION, NORMAL));
        var reasons = EnumSet.noneOf(PonderRigidParts.Fallback.class);
        var batch = PonderRigidParts.encode(draw, 104, placements, reasons::add);
        assertNotNull(batch);
        assertEquals(0, reasons.size());
        assertEquals(List.of(48, 8, 48), batch.parts().stream().map(PonderRigidParts.Part::count).toList());
        assertSame(model, batch.parts().getFirst());
        assertSame(model, batch.parts().getLast());
        var local = ByteBuffer.wrap(batch.parts().get(1).bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int vertex = 0; vertex < 8; vertex++) {
            assertEquals(draw.getLong((48 + vertex) * 36 + 16), local.getLong(vertex * 24 + 12));
            draw.putFloat((48 + vertex) * 36, 15);
        }
        var moved = PonderRigidParts.encode(draw, 104, placements);
        assertNotNull(moved);
        assertArrayEquals(batch.parts().get(1).digest(), moved.parts().get(1).digest());
    }

    @Test
    void keepsOriginalDrawForMostlyUncoveredOrMalformedRegions() {
        var item = PonderRigidParts.Part.localMesh(vertices(false, 48), 192);
        var detail = PonderRigidParts.Part.localMesh(vertices(false, 6), 24);
        assertNotNull(item);
        assertNotNull(detail);
        assertNull(PonderRigidParts.encode(vertices(true, 12), 48,
                List.of(new PonderRigidParts.Placement(detail, 0, POSITION, NORMAL))));
        assertNull(PonderRigidParts.encode(vertices(true, 54), 216,
                List.of(new PonderRigidParts.Placement(item, 2, POSITION, NORMAL))));
        assertNull(PonderRigidParts.encode(vertices(true, 54), 216, List.of(
                new PonderRigidParts.Placement(item, 0, POSITION, NORMAL),
                new PonderRigidParts.Placement(detail, 188, POSITION, NORMAL))));
    }

    @Test
    void sharesTintedNativeGeometryWhenVertexColorsDiffer() {
        var model = PonderRigidParts.Part.localMesh(vertices(false, 2), 8);
        assertNotNull(model);
        var draw = vertices(true, 2);
        for (int vertex = 0; vertex < 8; vertex++) {
            int offset = vertex * 36;
            draw.putFloat(offset, 13);
            draw.putInt(offset + 12, COLOR + vertex);
        }
        var encoded = PonderRigidParts.encode(draw, 8,
                List.of(new PonderRigidParts.Placement(model, 0, POSITION, NORMAL)));
        assertNotNull(encoded);
        assertEquals(2, encoded.version());
        var attributes = ByteBuffer.wrap(encoded.attributes().getFirst().bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int vertex = 0; vertex < 8; vertex++) assertEquals(COLOR + vertex, attributes.getInt(vertex * 12));
    }

    @Test
    void preservesVaryingCatnipColorOverlayAndLightWithoutReplacingRemappedUv() {
        var model = PonderRigidParts.Part.localMesh(vertices(false, 2), 8);
        assertNotNull(model);
        var draw = vertices(true, 4);
        for (int vertex = 0; vertex < 16; vertex++) {
            int offset = vertex * 36;
            draw.putFloat(offset, vertex < 8 ? 13 : 33);
            if (vertex < 8) {
                draw.putInt(offset + 12, COLOR + vertex);
                draw.putInt(offset + 24, OVERLAY + vertex);
                draw.putInt(offset + 28, LIGHT + vertex);
            }
        }
        var outer = new PoseStack();
        outer.last().pose().set(0, 0, -1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 10, 20, 30, 1);
        outer.last().normal().set(0, 0, -1, 0, 1, 0, 1, 0, 0);
        var local = new PoseStack();
        var catnip = PonderRigidParts.catnipPlacement(model, 0, outer.last(), local.last(), true);
        var otherPose = POSITION.clone();
        otherPose[9] = 30;
        var uniform = new PonderRigidParts.Placement(model, 8, otherPose, NORMAL);
        var encoded = PonderRigidParts.encode(draw, 16, List.of(catnip, uniform));
        assertNotNull(encoded);
        assertEquals(2, encoded.version());
        var packet = ByteBuffer.wrap(encoded.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2 + 2 * 162, packet.remaining());
        assertEquals(2, packet.getShort());
        assertEquals(1, encoded.attributes().size());
        var asset = encoded.attributes().getFirst();
        byte[] digest = new byte[32];
        packet.position(2 + 118);
        assertEquals(0, packet.getInt());
        assertEquals(0, packet.getInt());
        assertEquals(0, packet.getInt());
        packet.get(digest);
        assertArrayEquals(asset.digest(), digest);
        packet.position(2 + 162 + 118);
        assertEquals(COLOR, packet.getInt());
        assertEquals(OVERLAY, packet.getInt());
        assertEquals(LIGHT, packet.getInt());
        packet.get(digest);
        assertArrayEquals(new byte[32], digest);
        var attributes = ByteBuffer.wrap(asset.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int vertex = 0; vertex < 8; vertex++) {
            assertEquals(COLOR + vertex, attributes.getInt());
            assertEquals(OVERLAY + vertex, attributes.getInt());
            assertEquals(LIGHT + vertex, attributes.getInt());
        }
        draw.putFloat(16, .125f);
        assertNull(PonderRigidParts.encode(draw, 16, List.of(catnip, uniform)));
    }

    @Test
    void capturesCatnipBlockTemplatesAtEndPositionWithEitherNormalSpace() {
        var original = vertices(false, 2);
        var template = ByteBuffer.allocate(8 * 32).order(ByteOrder.LITTLE_ENDIAN);
        for (int start = 0; start < original.limit(); start += 36) {
            for (int field = 0; field < 24; field += 4) template.putInt(original.getInt(start + field));
            template.putInt(original.getInt(start + 28));
            template.putInt(original.getInt(start + 32));
        }
        var model = PonderRigidParts.Part.catnipBlockTemplate(template, 32);
        assertNotNull(model);
        assertArrayEquals(PonderRigidParts.Part.localMesh(original, 8).bytes(), model.bytes());
        assertEquals(template.limit(), template.position());
        assertNull(PonderRigidParts.Part.catnipBlockTemplate(template, 36));

        var outer = new PoseStack();
        outer.last().pose().set(0, 0, -1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 10, 20, 30, 1);
        outer.last().normal().set(0, 0, -1, 0, 1, 0, 1, 0, 0);
        var local = new PoseStack();
        var draw = vertices(true, 2);
        for (int start = 0; start < draw.limit(); start += 36) draw.putFloat(start, 13);
        var full = PonderRigidParts.catnipPlacement(model, 0, outer.last(), local.last(), true);
        var localOnly = PonderRigidParts.catnipPlacement(model, 0, outer.last(), local.last(), false);
        assertArrayEquals(full.position(), localOnly.position());
        var lit = PonderRigidParts.encode(draw, 8, List.of(full));
        assertNotNull(lit);
        assertEquals(1, lit.version());
        for (int start = 0; start < draw.limit(); start += 36) draw.putInt(start + 32, 127 << 16);
        var localLit = PonderRigidParts.encode(draw, 8, List.of(localOnly));
        assertNotNull(localLit);
        assertEquals(1, localLit.version());
        assertArrayEquals(lit.parts().getFirst().digest(), localLit.parts().getFirst().digest());
    }

    @Test
    void encodesRealVanillaCuboidsWithMirroringAndNormalizedNormals() {
        var faces = EnumSet.allOf(Direction.class);
        var base = new ModelPart.Cube(2, 3, -3, -2, -1, 6, 4, 2, .125f, .25f, .375f,
                false, 64, 64, faces);
        var mirrored = new ModelPart.Cube(11, 6, .5f, .25f, .75f, 3, 2, 1, 0, 0, 0,
                true, 64, 64, faces);
        var pose = new PoseStack();
        try (var memory = new ByteBufferBuilder(4096)) {
            var neutral = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            base.compile(pose.last(), neutral, LIGHT, OVERLAY, COLOR);
            mirrored.compile(pose.last(), neutral, LIGHT, OVERLAY, COLOR);
            PonderRigidParts.Part model;
            try (var mesh = neutral.buildOrThrow()) {
                model = PonderRigidParts.Part.localMesh(mesh.vertexBuffer(), mesh.drawState().vertexCount());
            }
            assertNotNull(model);
            assertEquals(48, model.count());

            pose.translate(2.3, -.6, .712);
            pose.mulPose(new Quaternionf().rotationXYZ(.132f, -.433f, .713f));
            pose.scale(.75f, .6f, 1.1f);
            var posed = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            base.compile(pose.last(), posed, LIGHT, OVERLAY, COLOR);
            mirrored.compile(pose.last(), posed, LIGHT, OVERLAY, COLOR);
            try (var mesh = posed.buildOrThrow()) {
                var placement = PonderRigidParts.placement(model, 0, pose.last(), true);
                assertNotNull(PonderRigidParts.encode(mesh.vertexBuffer(), mesh.drawState().vertexCount(), List.of(placement)));
                var mapped = PonderRigidParts.encode(mesh.vertexBuffer(), mesh.drawState().vertexCount(),
                        List.of(PonderRigidParts.placement(model, 0, pose.last(), false)));
                assertNotNull(mapped);
                assertEquals(4, mapped.version());
            }
        }
    }

    @Test
    void matchesTheRendererRoundingOnAnAnimatedNonuniformPose() {
        var original = vertices(false, 1);
        var model = PonderRigidParts.Part.localMesh(original, 4);
        assertNotNull(model);
        var position = new Matrix4f().translation(43.125f, -2.7f, .875f)
                .rotateXYZ(.132f, -.433f, .713f).scale(.75f, .6f, 1.1f);
        var normal = new Matrix3f(position).invert().transpose();
        var packedPosition = new float[12];
        var full = position.get(new float[16]);
        for (int column = 0; column < 4; column++) System.arraycopy(full, column * 4, packedPosition, column * 3, 3);
        var actual = ByteBuffer.allocate(144).order(ByteOrder.LITTLE_ENDIAN);
        var direction = new Vector3f(0, 0, 1).mul(normal);
        for (int at = 0; at < original.limit(); at += 36) {
            var point = new Vector4f(original.getFloat(at), original.getFloat(at + 4), original.getFloat(at + 8), 1).mul(position);
            actual.putFloat(point.x).putFloat(point.y).putFloat(point.z);
            for (int field = 12; field < 32; field += 4) actual.putInt(original.getInt(at + field));
            for (int axis = 0; axis < 3; axis++) actual.put((byte) (Math.clamp(direction.get(axis), -1f, 1f) * 127f));
            actual.put((byte) 0);
        }
        var placement = new PonderRigidParts.Placement(model, 0, packedPosition, normal.get(new float[9]));
        assertNotNull(PonderRigidParts.encode(actual.flip(), 4, List.of(placement)));
    }

    private static ByteBuffer vertices(boolean transformed, int copies) {
        var buffer = ByteBuffer.allocate(copies * 144).order(ByteOrder.LITTLE_ENDIAN);
        for (int part = 0; part < copies; part++) {
            for (int vertex = 0; vertex < 4; vertex++) {
                int x = vertex == 0 || vertex == 3 ? 1 : 2;
                int y = vertex < 2 ? 2 : 3;
                buffer.putFloat(transformed ? 13 + part * 10 : x);
                buffer.putFloat(transformed ? y + 20 : y);
                buffer.putFloat(transformed ? 30 - x : 3);
                buffer.putInt(COLOR).putFloat(x / 4f).putFloat(y / 4f).putInt(OVERLAY).putInt(LIGHT);
                buffer.put((byte) (transformed ? 127 : 0)).put((byte) 0).put((byte) (transformed ? 0 : 127)).put((byte) 0);
            }
        }
        return buffer.flip();
    }
}
