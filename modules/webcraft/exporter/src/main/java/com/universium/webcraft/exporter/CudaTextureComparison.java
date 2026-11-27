package com.universium.webcraft.exporter;

import com.mojang.logging.LogUtils;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import static com.universium.webcraft.exporter.CudaPixelComparison.check;
import static org.lwjgl.cuda.CU.*;
import static org.lwjgl.cuda.CUGL.cuGraphicsGLRegisterBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;

/** Compares packed texture pixels through an exporter-owned OpenGL buffer shared with CUDA. */
public final class CudaTextureComparison implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SLOT_BYTES = 4L * 1024 * 1024;
    private final int slots;
    private int buffer;
    private long context;
    private long resource;
    private long function;
    private long history;
    private long changed;

    private CudaTextureComparison(int slots) {
        this.slots = slots;
    }

    /** Reports whether a packed RGBA8 image fits one history slot. */
    public static boolean supports(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= SLOT_BYTES / Integer.BYTES;
    }

    /** Allocates fixed-size history slots. Call on the OpenGL render thread. */
    public static CudaTextureComparison open(int slots) {
        if (slots <= 0) throw new IllegalArgumentException("CUDA texture history must have at least one slot");
        var comparison = new CudaTextureComparison(slots);
        try {
            comparison.initialize();
            return comparison;
        } catch (RuntimeException | LinkageError exception) {
            comparison.close();
            throw exception;
        }
    }

    private void initialize() {
        int binding = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        buffer = glGenBuffers();
        if (buffer == 0) throw new IllegalStateException("Could not allocate a Ponder pixel pack buffer");
        try {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
            glBufferData(GL_PIXEL_PACK_BUFFER, SLOT_BYTES, GL_DYNAMIC_COPY);
        } finally {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, binding);
        }
        checkReadback("allocate pixel pack buffer");

        try (var stack = MemoryStack.stackPush()) {
            var output = stack.mallocPointer(1);
            context = CudaPixelComparison.createContext(stack, output);
            try {
                check("cuGraphicsGLRegisterBuffer",
                        cuGraphicsGLRegisterBuffer(output, buffer, CU_GRAPHICS_REGISTER_FLAGS_READ_ONLY));
                resource = output.get(0);
                function = CudaPixelComparison.loadKernel(stack, output);
                check("cuMemAlloc", cuMemAlloc(output, slots * SLOT_BYTES + Integer.BYTES));
                history = output.get(0);
                changed = history + slots * SLOT_BYTES;
            } finally {
                check("cuCtxPopCurrent", cuCtxPopCurrent(output));
            }
        }
    }

    /** Samples level zero of the bound 2D texture, seeding the slot if it has no previous image. */
    public boolean unchanged(int slot, int width, int height, boolean seed) {
        if (context == 0) throw new IllegalStateException("CUDA texture comparison is closed");
        if (slot < 0 || slot >= slots || !supports(width, height)) {
            throw new IllegalArgumentException("Invalid CUDA texture history slot or dimensions");
        }
        int binding = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment = glGetInteger(GL_PACK_ALIGNMENT);
        try {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
            glPixelStorei(GL_PACK_ALIGNMENT, Integer.BYTES);
            // Match NativeImage.downloadTexture's format and conversion, including red-only font atlases.
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            checkReadback("send texture pixels to CUDA");
        } finally {
            glPixelStorei(GL_PACK_ALIGNMENT, alignment);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, binding);
        }

        int pixelCount = width * height;
        long previous = history + slot * SLOT_BYTES;
        try (var stack = MemoryStack.stackPush()) {
            var resources = stack.pointers(resource);
            var output = stack.mallocPointer(1);
            check("cuCtxPushCurrent", cuCtxPushCurrent(context));
            try {
                check("cuGraphicsMapResources", cuGraphicsMapResources(resources, 0));
                try {
                    var size = stack.mallocPointer(1);
                    check("cuGraphicsResourceGetMappedPointer", cuGraphicsResourceGetMappedPointer(output, size, resource));
                    long bytes = (long) pixelCount * Integer.BYTES;
                    if (size.get(0) < bytes) throw new IllegalStateException("CUDA pixel pack buffer is too small");
                    long current = output.get(0);
                    if (seed) {
                        check("cuMemcpyDtoD", cuMemcpyDtoD(previous, current, bytes));
                        return false;
                    }
                    return CudaPixelComparison.unchanged(stack, function, current, previous, changed, pixelCount);
                } finally {
                    check("cuGraphicsUnmapResources", cuGraphicsUnmapResources(resources, 0));
                }
            } finally {
                check("cuCtxPopCurrent", cuCtxPopCurrent(output));
            }
        }
    }

    private static void checkReadback(String operation) {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException("OpenGL could not " + operation + ": " + error);
    }

    @Override
    public void close() {
        try {
            if (context != 0) {
                try (var stack = MemoryStack.stackPush()) {
                    if (resource != 0) {
                        check("cuCtxPushCurrent", cuCtxPushCurrent(context));
                        try {
                            check("cuGraphicsUnregisterResource", cuGraphicsUnregisterResource(resource));
                        } finally {
                            check("cuCtxPopCurrent", cuCtxPopCurrent(stack.mallocPointer(1)));
                        }
                    }
                } finally {
                    // The context owns the module and the history allocation.
                    long closing = context;
                    context = 0;
                    check("cuCtxDestroy", cuCtxDestroy(closing));
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not cleanly release the WebCraft Ponder CUDA resources", exception);
        } finally {
            if (buffer != 0) {
                int closing = buffer;
                buffer = 0;
                try {
                    glDeleteBuffers(closing);
                } catch (RuntimeException | LinkageError exception) {
                    LOGGER.warn("Could not delete the WebCraft Ponder pixel pack buffer", exception);
                }
            }
        }
    }
}
