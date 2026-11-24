package com.deds.meshelium.quasar;

import com.deds.meshelium.mixin.GpuDeviceAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.PointerBuffer;

import java.nio.LongBuffer;

/**
 * Quasar MC addition: the block-light color buffer the terrain mesh shader reads (binding 12).
 *
 * <p>Layout (std430): {@code uvec4 header} then one RGBA8 {@code uint} per cell of a world-anchored ring of
 * {@link #SIZE_X}x{@link #SIZE_Y}x{@link #SIZE_Z} blocks (cell = world block coordinate modulo the ring size).
 * header.x = mode (0 off, 1 test pattern, 2 color), header.yzw reserved for the ring origin.</p>
 *
 * <p>Host-mapped (written directly by the CPU, read by the GPU). Filled by Quasar MC's Quasium mod.</p>
 */
public final class QuasarColorLight {
    public static final int SIZE_X = 128, SIZE_Y = 64, SIZE_Z = 128;
    public static final long HEADER_BYTES = 16;
    public static final long BYTES = HEADER_BYTES + 4L * SIZE_X * SIZE_Y * SIZE_Z;

    private static long vkBuffer;
    private static long mapped;
    private static boolean failed;

    private QuasarColorLight() {}

    /** The buffer handle for the descriptor push, creating it on first use; 0 if it could not be created. */
    public static long vkBuffer() {
        if (vkBuffer == 0L && !failed) create();
        return vkBuffer;
    }

    /** Persistently mapped address of the buffer (0 until created). */
    public static long mapped() {
        return mapped;
    }

    private static void create() {
        try {
            GpuDevice facade = RenderSystem.tryGetDevice();
            if (facade == null) return;
            VulkanDevice device = (VulkanDevice) ((GpuDeviceAccessor) (Object) facade).meshelium$backend();
            long vma = device.vma();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                        .size(BYTES).usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                        .requiredFlags(VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                        .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
                LongBuffer pBuffer = stack.callocLong(1);
                PointerBuffer pAlloc = stack.callocPointer(1);
                VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
                int r = Vma.vmaCreateBuffer(vma, info, alloc, pBuffer, pAlloc, allocInfo);
                if (r != VK10.VK_SUCCESS || allocInfo.pMappedData() == 0L) {
                    failed = true;
                    return;
                }
                mapped = allocInfo.pMappedData();
                MemoryUtil.memSet(mapped, 0, BYTES);
                // Step 1 of the color work: test pattern on, so a red tint on block light proves the path works.
                MemoryUtil.memPutInt(mapped, Integer.getInteger("quasarmc.colorLightMode", 1));
                vkBuffer = pBuffer.get(0);
            }
        } catch (Throwable t) {
            failed = true;
        }
    }
}
