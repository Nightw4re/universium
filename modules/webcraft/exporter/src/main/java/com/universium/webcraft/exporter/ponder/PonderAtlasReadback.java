package com.universium.webcraft.exporter.ponder;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45C;

/** Reads atlas texels without keeping a full-size image in CPU memory. */
final class PonderAtlasReadback implements AutoCloseable {
    private static final int[] PACK_PARAMETERS = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH,
            GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_SKIP_ROWS, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES};
    private static final int[] PACK_VALUES = {4, 0, 0, 0, 0, 0};

    private final boolean direct;
    private int framebuffer;
    private boolean framebufferUnavailable;

    PonderAtlasReadback(boolean direct) {
        this.direct = direct;
    }

    String backend() {
        return direct ? "texture-sub-image" : "read-framebuffer";
    }

    boolean read(int texture, PonderAtlasRegion region, long pixels) {
        return read(texture, region, pixels, region.width());
    }

    boolean read(int texture, PonderAtlasRegion region, long pixels, int rowLength) {
        int packingBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        var previous = new int[PACK_PARAMETERS.length];
        var requested = PACK_VALUES.clone();
        if (rowLength != region.width()) requested[1] = rowLength;
        for (int i = 0; i < previous.length; i++) previous[i] = GL11.glGetInteger(PACK_PARAMETERS[i]);
        try {
            if (packingBuffer != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int i = 0; i < previous.length; i++) {
                if (previous[i] != requested[i]) GL11.glPixelStorei(PACK_PARAMETERS[i], requested[i]);
            }
            if (direct) {
                GL45C.nglGetTextureSubImage(texture, 0, region.x(), region.y(), 0, region.width(), region.height(), 1,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, ((region.height() - 1) * rowLength + region.width()) * 4, pixels);
                return true;
            }
            return readFramebuffer(texture, region, pixels);
        } finally {
            for (int i = 0; i < previous.length; i++) {
                if (previous[i] != requested[i]) GL11.glPixelStorei(PACK_PARAMETERS[i], previous[i]);
            }
            if (packingBuffer != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packingBuffer);
        }
    }

    private boolean readFramebuffer(int texture, PonderAtlasRegion region, long pixels) {
        if (framebufferUnavailable) return false;
        int previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            if (framebuffer == 0) {
                framebuffer = GL30.glGenFramebuffers();
                if (framebuffer == 0) {
                    framebufferUnavailable = true;
                    return false;
                }
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, texture, 0);
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                framebufferUnavailable = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE;
                if (framebufferUnavailable) return false;
            } else {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            }
            GL11C.nglReadPixels(region.x(), region.y(), region.width(), region.height(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            return true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous);
        }
    }

    @Override
    public void close() {
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
    }
}
