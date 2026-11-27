package com.universium.webcraft.exporter.ponder;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounds the sampled texels and maps atlas coordinates into an original-resolution region. */
record PonderAtlasRegion(int x, int y, int width, int height) {
    private static final int ALIGNMENT = 32;
    static final int MAX_PACKED_REGIONS = 16;
    record Slice(int firstVertex, int vertexCount, PonderAtlasRegion region) {}
    record Placement(PonderAtlasRegion region, int x, int y) {}
    record Packed(int width, int height, List<Placement> placements) {}
    record SpriteCopy(int x, int y, int width, int height, int sourceX, int sourceY) {}

    static SpriteCopy clipSprite(Placement placement, PonderAtlasRegion sprite) {
        var region = placement.region;
        int x = Math.max(region.x, sprite.x), y = Math.max(region.y, sprite.y);
        int width = Math.min(region.x + region.width, sprite.x + sprite.width) - x;
        int height = Math.min(region.y + region.height, sprite.y + sprite.height) - y;
        return width <= 0 || height <= 0 ? null : new SpriteCopy(placement.x + x - region.x,
                placement.y + y - region.y, width, height, x - sprite.x, y - sprite.y);
    }

    static Packed packSparseQuads(ByteBuffer source, int count, int stride, int uvOffset, Matrix4fc matrix,
                                  int atlasWidth, int atlasHeight, int pageSize, PonderAtlasRegion bounds) {
        int area = bounds == null ? 0 : bounds.width * bounds.height;
        // A single crop needs no per-fragment region lookup. Keep it for small or dense draws.
        if (bounds != null && area < 512 * 512) return null;
        var packed = packQuads(source, count, stride, uvOffset, matrix, atlasWidth, atlasHeight, pageSize);
        return packed != null && (bounds == null || 2 * packed.width * packed.height <= area) ? packed : null;
    }

    static Packed packQuads(ByteBuffer source, int count, int stride, int uvOffset, Matrix4fc matrix,
                            int atlasWidth, int atlasHeight, int pageSize) {
        if (count % 4 != 0 || !canSelect(source, count, stride, uvOffset, matrix, atlasWidth, atlasHeight, pageSize)) return null;
        var vertices = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        var regions = new ArrayList<PonderAtlasRegion>();
        for (int vertex = 0; vertex < count; vertex += 4) {
            var quad = selectRange(vertices, vertex, 4, stride, uvOffset, matrix,
                    atlasWidth, atlasHeight, pageSize);
            if (quad == null || !mergeRegion(regions, quad, pageSize)) return null;
        }
        if (regions.size() < 2 || regions.size() > MAX_PACKED_REGIONS) return null;
        regions.sort(Comparator.comparingInt(PonderAtlasRegion::height).reversed()
                .thenComparing(Comparator.comparingInt(PonderAtlasRegion::width).reversed())
                .thenComparingInt(PonderAtlasRegion::y).thenComparingInt(PonderAtlasRegion::x));
        Packed best = null;
        int minWidth = regions.stream().mapToInt(PonderAtlasRegion::width).max().orElse(ALIGNMENT);
        for (int width = powerOfTwo(minWidth); width <= pageSize; width <<= 1) {
            var packed = shelve(regions, width, pageSize);
            if (packed != null && (best == null || width * packed.height() < best.width() * best.height())) best = packed;
        }
        return best;
    }

    private static boolean mergeRegion(List<PonderAtlasRegion> regions, PonderAtlasRegion incoming, int pageSize) {
        for (int index = 0; index < regions.size();) {
            var old = regions.get(index);
            if (!incoming.intersects(old)) {
                index++;
                continue;
            }
            int x = Math.min(incoming.x, old.x), y = Math.min(incoming.y, old.y);
            int width = Math.max(incoming.x + incoming.width, old.x + old.width) - x;
            int height = Math.max(incoming.y + incoming.height, old.y + old.height) - y;
            if (width > pageSize || height > pageSize) return false;
            incoming = new PonderAtlasRegion(x, y, width, height);
            regions.remove(index);
            index = 0;
        }
        if (regions.size() >= 4 * MAX_PACKED_REGIONS) return false;
        regions.add(incoming);
        return true;
    }

    private static Packed shelve(List<PonderAtlasRegion> regions, int width, int pageSize) {
        var placements = new ArrayList<Placement>(regions.size());
        int x = 0, y = 0, shelfHeight = 0;
        for (var region : regions) {
            if (x + region.width() > width) {
                x = 0;
                y += shelfHeight;
                shelfHeight = 0;
            }
            if (y + region.height() > pageSize) return null;
            placements.add(new Placement(region, x, y));
            x += region.width();
            shelfHeight = Math.max(shelfHeight, region.height());
        }
        int height = powerOfTwo(y + shelfHeight);
        return height <= pageSize ? new Packed(width, height, List.copyOf(placements)) : null;
    }

    private static int powerOfTwo(int value) {
        return Integer.highestOneBit(2 * value - 1);
    }

    static List<Slice> partition(ByteBuffer vertices, int count, int stride, int uvOffset, Matrix4fc matrix,
                                 int atlasWidth, int atlasHeight, int pageSize, int maxSlices) {
        if (count <= 0 || count % 4 != 0 || uvOffset < 0 || uvOffset > stride - 8
                || vertices.remaining() != (long) count * stride
                || (atlasWidth <= pageSize && atlasHeight <= pageSize)) return List.of();
        var slices = new ArrayList<Slice>();
        return partition(vertices.slice(), 0, count, stride, uvOffset, matrix, atlasWidth, atlasHeight, pageSize, maxSlices, slices)
                ? slices : List.of();
    }

    private static boolean partition(ByteBuffer vertices, int start, int count, int stride, int uvOffset, Matrix4fc matrix,
                                     int atlasWidth, int atlasHeight, int pageSize, int maxSlices, List<Slice> slices) {
        if (slices.size() >= maxSlices) return false;
        var region = select(vertices.slice(start * stride, count * stride), count, stride, uvOffset, matrix,
                atlasWidth, atlasHeight, pageSize);
        if (region != null) {
            slices.add(new Slice(start, count, region));
            return true;
        }
        if (count == 4) return false;
        int leftCount = count / 8 * 4;
        return partition(vertices, start, leftCount, stride, uvOffset, matrix, atlasWidth, atlasHeight, pageSize, maxSlices, slices)
                && partition(vertices, start + leftCount, count - leftCount, stride, uvOffset, matrix, atlasWidth, atlasHeight,
                        pageSize, maxSlices, slices);
    }

    static PonderAtlasRegion select(ByteBuffer source, int count, int stride, int uvOffset, Matrix4fc matrix,
                                    int atlasWidth, int atlasHeight, int pageSize) {
        if (!canSelect(source, count, stride, uvOffset, matrix, atlasWidth, atlasHeight, pageSize)) return null;
        return selectRange(source.slice().order(ByteOrder.LITTLE_ENDIAN), 0, count, stride, uvOffset, matrix,
                atlasWidth, atlasHeight, pageSize);
    }

    private static boolean canSelect(ByteBuffer source, int count, int stride, int uvOffset, Matrix4fc matrix,
                                     int atlasWidth, int atlasHeight, int pageSize) {
        return (atlasWidth > pageSize || atlasHeight > pageSize) && count > 0 && uvOffset >= 0 && uvOffset <= stride - 8
                && source.remaining() == (long) count * stride && matrix.m03() == 0 && matrix.m13() == 0 && matrix.m33() == 1;
    }

    private static PonderAtlasRegion selectRange(ByteBuffer vertices, int start, int count, int stride, int uvOffset,
                                                 Matrix4fc matrix, int atlasWidth, int atlasHeight, int pageSize) {
        float left = 1, right = 0, top = 1, bottom = 0;
        for (int vertex = start; vertex < start + count; vertex++) {
            int index = vertex * stride + uvOffset;
            float u = vertices.getFloat(index), v = vertices.getFloat(index + 4);
            float x = matrix.m00() * u + matrix.m10() * v + matrix.m30();
            float y = matrix.m01() * u + matrix.m11() * v + matrix.m31();
            if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1)) return null;
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
        }
        int x = start(left, atlasWidth), y = start(top, atlasHeight);
        int width = end(right, atlasWidth) - x, height = end(bottom, atlasHeight) - y;
        return width > 0 && height > 0 && width <= pageSize && height <= pageSize
                ? new PonderAtlasRegion(x, y, width, height) : null;
    }

    private static int start(float uv, int extent) {
        return Math.max(0, ((int) Math.floor(uv * extent / ALIGNMENT) - 1) * ALIGNMENT);
    }

    private static int end(float uv, int extent) {
        return Math.min(extent, ((int) Math.ceil(uv * extent / ALIGNMENT) + 1) * ALIGNMENT);
    }

    boolean intersects(PonderAtlasRegion other) {
        return x < other.x + other.width && other.x < x + width
                && y < other.y + other.height && other.y < y + height;
    }

    Matrix4f textureMatrix(Matrix4fc original, int atlasWidth, int atlasHeight) {
        return new Matrix4f().translation(-x / (float) width, -y / (float) height, 0)
                .scale(atlasWidth / (float) width, atlasHeight / (float) height, 1).mul(original);
    }
}
