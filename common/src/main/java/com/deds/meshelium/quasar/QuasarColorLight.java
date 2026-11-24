package com.deds.meshelium.quasar;

import com.deds.meshelium.mixin.GpuDeviceAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Quasar MC addition: block-light color for the terrain mesh shader (binding 12).
 *
 * <p>World-anchored and sparse, so any view (later: portal views) can look color up by world position:</p>
 * <pre>
 *   uint[4]                      header: x = mode (0 off, 1 test pattern, 2 color)
 *   uint[2 * TABLE]              section table, a ring over section coords (64 x 32 x 64 sections):
 *                                [key, page + 1], key = packed section coords | VALID (0 = empty)
 *   uint[PAGES * 4096]           color pages: one RGBA8 per block of a 16^3 section,
 *                                rgb = light color, a = block light in sixteenths (0..240)
 * </pre>
 * Only sections with colored light get a page. Quasar MC's Quasium submits sections as they are meshed
 * ({@link #submitSection}, any thread); the render thread applies them in {@link #flush}.
 */
public final class QuasarColorLight {
    public static final int TX = 64, TY = 32, TZ = 64;
    public static final int TABLE = TX * TY * TZ;
    public static final int PAGES = 4096;
    public static final int CELLS = 4096;
    private static final int VALID = 0x40000000;
    private static final long HEADER_BYTES = 16;
    private static final long TABLE_BYTES = 8L * TABLE;
    public static final long BYTES = HEADER_BYTES + TABLE_BYTES + 4L * PAGES * CELLS;

    private record Pending(int sx, int sy, int sz, int[] cells) {}

    private static final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private static long vkBuffer;
    private static long mapped;
    private static boolean failed;
    private static volatile int mode = Integer.getInteger("quasarmc.colorLightMode", 0);
    private static final ArrayDeque<Integer> freePages = new ArrayDeque<>();
    private static final int[] tableKey = new int[TABLE];
    private static final int[] tablePage = new int[TABLE]; // page + 1, 0 = none
    // Owner of each page (slot -1 = free), so pages of sections far from the camera can be taken back.
    private static final int[] pageSlot = new int[PAGES];
    private static final int[] pageSx = new int[PAGES], pageSy = new int[PAGES], pageSz = new int[PAGES];
    private static volatile int camSx, camSy, camSz, keepRadius = 64;
    private static int sweep;

    private QuasarColorLight() {}

    /** 0 off, 1 test pattern (all block light red), 2 color. Quasium sets 2. */
    public static void setMode(int m) {
        mode = m;
        if (mapped != 0L) MemoryUtil.memPutInt(mapped, m);
    }

    /**
     * Queue a section's colors (any thread). {@code cells} = 4096 RGBA8 values indexed (y*16+z)*16+x, or null when
     * the section has no colored light (frees its page).
     */
    public static void submitSection(int sx, int sy, int sz, int[] cells) {
        queue.add(new Pending(sx, sy, sz, cells));
    }

    /** The buffer for the descriptor push (render thread); creates it on first use, applies queued sections. */
    public static long vkBuffer() {
        if (vkBuffer == 0L && !failed) create();
        if (vkBuffer != 0L) flush();
        return vkBuffer;
    }

    /**
     * Camera section and render distance in sections (render thread, each frame). Pages of sections outside
     * {@code radius + 1} are freed a few at a time: nothing else tells this table that a section unloaded.
     */
    public static void setCamera(int sx, int sy, int sz, int radius) {
        camSx = sx; camSy = sy; camSz = sz; keepRadius = radius + 1;
    }

    private static int dist(int sx, int sy, int sz) {
        return Math.max(Math.max(Math.abs(sx - camSx), Math.abs(sz - camSz)), Math.abs(sy - camSy) / 2);
    }

    private static void freePage(int page) {
        int s = pageSlot[page];
        if (s >= 0 && tablePage[s] == page + 1) {
            tableKey[s] = 0;
            tablePage[s] = 0;
            long entry = mapped + HEADER_BYTES + 8L * s;
            MemoryUtil.memPutInt(entry, 0);
            MemoryUtil.memPutInt(entry + 4, 0);
        }
        pageSlot[page] = -1;
        freePages.push(page);
    }

    /** Frees out-of-range pages, 128 checked per frame (the whole pool every 32 frames). */
    private static void sweep() {
        for (int n = 0; n < 128; n++) {
            int page = sweep;
            sweep = (sweep + 1) % PAGES;
            if (pageSlot[page] >= 0 && dist(pageSx[page], pageSy[page], pageSz[page]) > keepRadius) freePage(page);
        }
    }

    /** Pool full: free the page farthest from the camera if it is farther than the section asking. */
    private static boolean evictFarther(int sx, int sy, int sz) {
        int best = -1, bestD = dist(sx, sy, sz);
        for (int page = 0; page < PAGES; page++) {
            if (pageSlot[page] < 0) continue;
            int d = dist(pageSx[page], pageSy[page], pageSz[page]);
            if (d > bestD) { bestD = d; best = page; }
        }
        if (best < 0) return false;
        freePage(best);
        return true;
    }

    private static int key(int sx, int sy, int sz) {
        return (sx & 0x3FF) | ((sy & 0x3FF) << 10) | ((sz & 0x3FF) << 20) | VALID;
    }

    private static int slot(int sx, int sy, int sz) {
        return (Math.floorMod(sy, TY) * TZ + Math.floorMod(sz, TZ)) * TX + Math.floorMod(sx, TX);
    }

    private static void flush() {
        Pending p;
        sweep();
        int budget = 512; // sections per frame
        boolean allNear = false; // one failed eviction scan per frame is enough
        while (budget-- > 0 && (p = queue.poll()) != null) {
            int s = slot(p.sx, p.sy, p.sz);
            int k = key(p.sx, p.sy, p.sz);
            long entry = mapped + HEADER_BYTES + 8L * s;
            if (p.cells == null) {
                if (tableKey[s] == k && tablePage[s] != 0) freePage(tablePage[s] - 1);
                continue;
            }
            int page;
            if (tableKey[s] == k && tablePage[s] != 0) {
                page = tablePage[s] - 1;
            } else {
                if (tablePage[s] != 0) freePage(tablePage[s] - 1); // ring slot reused by another section
                if (freePages.isEmpty() && (allNear || !evictFarther(p.sx, p.sy, p.sz))) { // all nearer: stays white
                    allNear = true;
                    continue;
                }
                page = freePages.pop();
            }
            long dst = mapped + HEADER_BYTES + TABLE_BYTES + 4L * page * CELLS;
            MemoryUtil.memIntBuffer(dst, CELLS).put(0, p.cells);
            tableKey[s] = k;
            tablePage[s] = page + 1;
            pageSlot[page] = s;
            pageSx[page] = p.sx; pageSy[page] = p.sy; pageSz[page] = p.sz;
            MemoryUtil.memPutInt(entry + 4, page + 1);
            MemoryUtil.memPutInt(entry, k);
        }
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
                // Device-local + host-visible where the card allows it (ReBAR), plain host memory otherwise:
                // the CPU writes it directly, the GPU reads it.
                VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE)
                        .requiredFlags(VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                        .preferredFlags(VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
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
                MemoryUtil.memSet(mapped, 0, HEADER_BYTES + TABLE_BYTES);
                MemoryUtil.memPutInt(mapped, mode);
                for (int i = PAGES - 1; i >= 0; i--) { pageSlot[i] = -1; freePages.push(i); }
                vkBuffer = pBuffer.get(0);
            }
        } catch (Throwable t) {
            failed = true;
        }
    }
}
