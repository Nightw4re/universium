package com.universium.webcraft.exporter;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.cuda.CU.*;
import static org.lwjgl.cuda.CUGL.CU_GL_DEVICE_LIST_ALL;
import static org.lwjgl.cuda.CUGL.cuGLGetDevices;
import static org.lwjgl.system.MemoryUtil.memAddress;

final class CudaPixelComparison {
    private static final int THREADS_PER_BLOCK = 128;
    // One lane compares one complete RGBA pixel. Each block contributes at most one write to the flag.
    private static final String COMPARE_PTX = """
            .version 6.0
            .target sm_50
            .address_size 64

            .visible .entry compare_rgba(
                .param .u64 current,
                .param .u64 previous,
                .param .u64 changed,
                .param .u32 pixel_count
            ) {
                .reg .pred %in_range, %diff, %block_diff, %leader, %write;
                .reg .u32 %block, %size, %lane, %index, %count, %new, %old, %unused;
                .reg .u64 %current, %previous, %changed, %offset, %src, %dst;
                ld.param.u64 %current, [current];
                ld.param.u64 %previous, [previous];
                ld.param.u64 %changed, [changed];
                ld.param.u32 %count, [pixel_count];
                mov.u32 %block, %ctaid.x;
                mov.u32 %size, %ntid.x;
                mov.u32 %lane, %tid.x;
                mad.lo.u32 %index, %block, %size, %lane;
                setp.lt.u32 %in_range, %index, %count;
                setp.ne.u32 %diff, 0, 0;
                @!%in_range bra reduce;
                mul.wide.u32 %offset, %index, 4;
                add.u64 %src, %current, %offset;
                add.u64 %dst, %previous, %offset;
                ld.global.u32 %new, [%src];
                ld.global.u32 %old, [%dst];
                setp.ne.u32 %diff, %new, %old;
                @%diff st.global.u32 [%dst], %new;
            reduce:
                bar.red.or.pred %block_diff, 0, %diff;
                setp.eq.u32 %leader, %lane, 0;
                and.pred %write, %block_diff, %leader;
                @%write atom.global.or.b32 %unused, [%changed], 1;
                ret;
            }
            """;

    private CudaPixelComparison() {}

    static long createContext(MemoryStack stack, PointerBuffer output) {
        check("cuInit", cuInit(0));
        var count = stack.mallocInt(1);
        var devices = stack.mallocInt(2);
        check("cuGLGetDevices", cuGLGetDevices(count, devices, CU_GL_DEVICE_LIST_ALL));
        if (count.get(0) != 1) throw new IllegalStateException("Expected one CUDA device for the OpenGL context");
        check("cuCtxCreate", cuCtxCreate(output, 0, devices.get(0)));
        return output.get(0);
    }

    static long loadKernel(MemoryStack stack, PointerBuffer output) {
        check("cuModuleLoadData", cuModuleLoadData(output, stack.ASCII(COMPARE_PTX)));
        check("cuModuleGetFunction", cuModuleGetFunction(output, output.get(0), "compare_rgba"));
        return output.get(0);
    }

    // Compares every packed RGBA8 pixel and advances the device copy to this sample.
    static boolean unchanged(MemoryStack stack, long function, long current, long previous, long changed, int pixelCount) {
        check("cuMemsetD32", cuMemsetD32(changed, 0, 1));
        var arguments = stack.pointers(
                memAddress(stack.longs(current)),
                memAddress(stack.longs(previous)),
                memAddress(stack.longs(changed)),
                memAddress(stack.ints(pixelCount)));
        int blocks = (pixelCount + THREADS_PER_BLOCK - 1) / THREADS_PER_BLOCK;
        check("cuLaunchKernel", ncuLaunchKernel(function, blocks, 1, 1, THREADS_PER_BLOCK, 1, 1,
                0, 0, memAddress(arguments), 0));

        var flag = stack.mallocInt(1);
        check("cuMemcpyDtoH", cuMemcpyDtoH(flag, changed));
        return flag.get(0) == 0;
    }

    static void check(String operation, int result) {
        if (result != CUDA_SUCCESS) throw new IllegalStateException(operation + " returned CUDA status " + result);
    }
}
