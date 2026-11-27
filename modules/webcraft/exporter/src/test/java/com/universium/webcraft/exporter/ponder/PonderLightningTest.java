package com.universium.webcraft.exporter.ponder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LightningBolt;
import net.neoforged.fml.loading.LoadingModList;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PonderLightningTest {
    @Test
    void validatesTheVanillaRendererByteForByteAndRejectsIncompleteBatches() throws Exception {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var renderer = new LightningBoltRenderer(new EntityRendererProvider.Context(null, null, null, null, null, null, null));
        // Rendering reads only the seed. Skip the entity constructor, which requires the mod registries and a world.
        var allocator = Unsafe.class.getDeclaredField("theUnsafe");
        allocator.setAccessible(true);
        var entity = (LightningBolt) ((Unsafe) allocator.get(null)).allocateInstance(LightningBolt.class);
        entity.seed = 0xABCD_FEDC_BA98_7654L;
        var pose = new PoseStack();
        pose.last().pose().set(new Matrix4f(.75f, -.1875f, .3125f, 0,
                -.3125f, .875f, .171875f, 0, -.5f, -.6875f, .40625f, 0, 2.25f, 17.625f, -31.5f, 1));
        var vertices = ByteBuffer.allocate(PonderLightning.VERTICES_PER_BOLT * 16).order(ByteOrder.LITTLE_ENDIAN);
        renderer.render(entity, 0, 0, pose, unused -> new VertexSink(vertices), 0);
        vertices.flip();
        assertEquals(PonderLightning.VERTICES_PER_BOLT * 16, vertices.remaining());
        var bolt = PonderLightning.record(0, entity.seed, pose.last().pose());
        var accepted = PonderLightning.encode(vertices, PonderLightning.VERTICES_PER_BOLT, List.of(bolt));
        assertNotNull(accepted);
        var bytes = ByteBuffer.wrap(accepted).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(58, accepted.length);
        assertEquals(1, bytes.getShort());
        assertEquals(0xFEDC_BA98_7654L, bytes.getLong());
        assertEquals(.75f, bytes.getFloat());
        assertEquals("e167604a8f3ae4e4a8017865c1215aecfa263ab13f03b5f8f617732cef49aafc",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(vertices.array())));
        assertNull(PonderLightning.encode(vertices, 895, List.of(bolt)));
        assertNull(PonderLightning.encode(vertices, 896, List.of(PonderLightning.record(4, entity.seed, pose.last().pose()))));
        vertices.put(12, (byte) 0);
        assertNull(PonderLightning.encode(vertices, 896, List.of(bolt)));
    }

    private record VertexSink(ByteBuffer vertices) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            vertices.putFloat(x).putFloat(y).putFloat(z);
            return this;
        }

        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            vertices.put((byte) red).put((byte) green).put((byte) blue).put((byte) alpha);
            return this;
        }

        @Override public VertexConsumer setUv(float u, float v) { throw new AssertionError("Unexpected UV"); }
        @Override public VertexConsumer setUv1(int u, int v) { throw new AssertionError("Unexpected overlay"); }
        @Override public VertexConsumer setUv2(int u, int v) { throw new AssertionError("Unexpected light"); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { throw new AssertionError("Unexpected normal"); }
    }
}
