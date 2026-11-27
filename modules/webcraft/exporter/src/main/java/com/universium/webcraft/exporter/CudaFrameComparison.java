package com.universium.webcraft.exporter;

import com.mojang.logging.LogUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.cuda.CUDA_ARRAY_DESCRIPTOR;
import org.lwjgl.cuda.CUDA_MEMCPY2D;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import static com.universium.webcraft.exporter.CudaPixelComparison.check;
import static org.lwjgl.cuda.CU.*;
import static org.lwjgl.cuda.CUGL.cuGraphicsGLRegisterImage;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;

/** Compares the off-screen RGBA8 texture against the previous frame for each active animation. */
final class CudaFrameComparison implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final int size;
    private final int pixelCount;
    private final long frameBytes;
    private long context;
    private long resource;
    private long function;
    private long history;
    private long current;
    private long changed;
    private boolean formatChecked;

    private CudaFrameComparison(int size) {
        this.size = size;
        pixelCount = size * size;
        frameBytes = (long) pixelCount * Integer.BYTES;
    }

    static CudaFrameComparison open(int texture, int size, int slots) {
        var comparison = new CudaFrameComparison(size);
        try {
            comparison.initialize(texture, slots);
            return comparison;
        } catch (RuntimeException | LinkageError exception) {
            comparison.close();
            throw exception;
        }
    }

    private void initialize(int texture, int slots) {
        try (var stack = MemoryStack.stackPush()) {
            var output = stack.mallocPointer(1);
            context = CudaPixelComparison.createContext(stack, output);
            try {
                check("cuGraphicsGLRegisterImage",
                        cuGraphicsGLRegisterImage(output, texture, GL_TEXTURE_2D, CU_GRAPHICS_REGISTER_FLAGS_READ_ONLY));
                resource = output.get(0);

                function = CudaPixelComparison.loadKernel(stack, output);

                check("cuMemAlloc", cuMemAlloc(output, (slots + 1) * frameBytes + Integer.BYTES));
                history = output.get(0);
                current = history + slots * frameBytes;
                changed = current + frameBytes;
            } finally {
                check("cuCtxPopCurrent", cuCtxPopCurrent(output));
            }
        }
    }

    boolean unchanged(int slot, boolean first) {
        try (var stack = MemoryStack.stackPush()) {
            var output = stack.mallocPointer(1);
            long previous = history + slot * frameBytes;
            check("cuCtxPushCurrent", cuCtxPushCurrent(context));
            try {
                copyTexture(stack, output, first ? previous : current);
                if (first) return false;

                return CudaPixelComparison.unchanged(stack, function, current, previous, changed, pixelCount);
            } finally {
                check("cuCtxPopCurrent", cuCtxPopCurrent(output));
            }
        }
    }

    private void copyTexture(MemoryStack stack, PointerBuffer output, long destination) {
        var resources = stack.pointers(resource);
        check("cuGraphicsMapResources", cuGraphicsMapResources(resources, 0));
        try {
            check("cuGraphicsSubResourceGetMappedArray", cuGraphicsSubResourceGetMappedArray(output, resource, 0, 0));
            long array = output.get(0);
            if (!formatChecked) {
                var descriptor = CUDA_ARRAY_DESCRIPTOR.calloc(stack);
                check("cuArrayGetDescriptor", cuArrayGetDescriptor(descriptor, array));
                if (descriptor.Format() != CU_AD_FORMAT_UNSIGNED_INT8 || descriptor.NumChannels() != 4
                        || descriptor.Width() != size || descriptor.Height() != size) {
                    throw new IllegalStateException("Expected a packed RGBA8 inventory render texture");
                }
                formatChecked = true;
            }
            long rowBytes = (long) size * Integer.BYTES;
            var copy = CUDA_MEMCPY2D.calloc(stack)
                    .srcMemoryType(CU_MEMORYTYPE_ARRAY).srcArray(array)
                    .dstMemoryType(CU_MEMORYTYPE_DEVICE).dstDevice(destination).dstPitch(rowBytes)
                    .WidthInBytes(rowBytes).Height(size);
            check("cuMemcpy2DUnaligned", cuMemcpy2DUnaligned(copy));
        } finally {
            check("cuGraphicsUnmapResources", cuGraphicsUnmapResources(resources, 0));
        }
    }

    @Override
    public void close() {
        if (context == 0) return;
        try (var stack = MemoryStack.stackPush()) {
            if (resource != 0) {
                check("cuCtxPushCurrent", cuCtxPushCurrent(context));
                try {
                    check("cuGraphicsUnregisterResource", cuGraphicsUnregisterResource(resource));
                } finally {
                    check("cuCtxPopCurrent", cuCtxPopCurrent(stack.mallocPointer(1)));
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not unregister the WebCraft CUDA render texture", exception);
        } finally {
            // The private context owns the module and device allocation.
            try {
                check("cuCtxDestroy", cuCtxDestroy(context));
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.warn("Could not destroy the WebCraft CUDA context", exception);
            }
            context = 0;
        }
    }
}
