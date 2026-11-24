package com.deds.meshelium.quasar;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Quasar MC addition: block-light color for the terrain mesh shader (binding 12).
 *
 * <p>World-anchored and sparse, so any view (later: portal views) can look color up by world position:</p>
 * <pre>
 *   uint[4]                      header: x = mode (0 off, 1 test pattern, 2 color), y = MAGIC
 *   uint[2 * TABLE]              section table, a ring over section coords (64 x 32 x 64 sections):
 *                                [key, page + 1], key = packed section coords | VALID (0 = empty)
 *   uint[PAGES * 4096]           color pages: one RGBA8 per block of a 16^3 section,
 *                                rgb = light color, a = block light in sixteenths (0..240)
 * </pre>
 *
 * <p>The buffer lives in device-local memory (the shader reads it for every terrain corner, so it must not
 * sit behind the PCIe bus). The CPU keeps the table itself; changes go through a small host-visible staging
 * buffer and are copied over once per frame from Meshelium's upload pump ({@link #pump}), which already
 * splices its copies into vanilla's submission in order. Only what changed is copied: whole pages for
 * rebuilt sections, 8 bytes per changed table entry.</p>
 *
 * <p>Until the first pump has cleared the buffer, {@link #vkBuffer} returns 0 and the drawer binds a dummy;
 * the shader only tints when header.y == MAGIC, so the dummy can never tint.</p>
 */
public final class QuasarColorLight {
    public static final int TX = 64, TY = 32, TZ = 64;
    public static final int TABLE = TX * TY * TZ;
    public static final int PAGES = 4096;
    public static final int CELLS = 4096;
    public static final int MAGIC = 0x51A5C010;
    private static final int VALID = 0x40000000;
    private static final long HEADER_BYTES = 16;
    private static final long TABLE_BYTES = 8L * TABLE;
    private static final long PAGE_BYTES = 4L * CELLS;
    public static final long BYTES = HEADER_BYTES + TABLE_BYTES + PAGE_BYTES * PAGES;

    /** Per-frame upload budget: rebuilt sections (pages) and changed table entries. */
    private static final int PAGE_BUDGET = 96;
    private static final int ENTRY_BUDGET = 4096;
    private static final long SLOT_BYTES = HEADER_BYTES + PAGE_BYTES * PAGE_BUDGET + 8L * ENTRY_BUDGET;

    private record Pending(int sx, int sy, int sz, int[] cells) {}

    private static final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private static Pending deferred; // didn't fit last frame's budget
    private static volatile int mode = Integer.getInteger("quasarmc.colorLightMode", 0);
    private static boolean modeDirty = true;

    // Device buffer + staging (render thread).
    private static long vma, deviceBuffer, deviceAlloc, stagingBuffer, stagingAlloc, stagingMapped;
    private static int slots;
    private static boolean failed, ready;
    private static long lastFrame = Long.MIN_VALUE;

    // CPU copy of the table.
    private static final ArrayDeque<Integer> freePages = new ArrayDeque<>();
    private static final int[] tableKey = new int[TABLE];
    private static final int[] tablePage = new int[TABLE]; // page + 1, 0 = none
    private static final int[] pageSlot = new int[PAGES];
    private static final int[] pageSx = new int[PAGES], pageSy = new int[PAGES], pageSz = new int[PAGES];
    private static volatile int camSx, camSy, camSz, keepRadius = 64;
    private static int sweep;

    // This frame's uploads (deduplicated: one copy per page / table entry).
    private static int stamp = 1;
    private static final int[] slotStamp = new int[TABLE];
    private static final int[] dirtySlots = new int[ENTRY_BUDGET];
    private static int dirtyCount;
    private static final int[] pageStamp = new int[PAGES];
    private static final int[] pageStageIndex = new int[PAGES];
    private static final int[] stagedPages = new int[PAGE_BUDGET];
    private static int stagedCount;
    private static long slotBase;

    private QuasarColorLight() {}

    /** 0 off, 1 test pattern (all block light red), 2 color. Quasium sets 2. */
    public static void setMode(int m) {
        mode = m;
        modeDirty = true;
    }

    /**
     * Queue a section's colors (any thread). {@code cells} = 4096 RGBA8 values indexed (y*16+z)*16+x, or null when
     * the section has no colored light (frees its page).
     */
    public static void submitSection(int sx, int sy, int sz, int[] cells) {
        queue.add(new Pending(sx, sy, sz, cells));
    }

    /**
     * Camera section and render distance in sections (render thread, each frame). Pages of sections outside
     * {@code radius + 1} are freed a few at a time: nothing else tells this table that a section unloaded.
     */
    public static void setCamera(int sx, int sy, int sz, int radius) {
        camSx = sx; camSy = sy; camSz = sz; keepRadius = radius + 1;
    }

    /** The buffer for the descriptor push (render thread), or 0 until the first upload pump has cleared it. */
    public static long vkBuffer() {
        return ready ? deviceBuffer : 0L;
    }

    // ------------------------------------------------------------------
    // Upload pump (Meshelium's MesheliumTerrainGpu.endFrame, render thread, once per frame)
    // ------------------------------------------------------------------

    /**
     * @param frameLag frames a staging slot must age before it is written again (Meshelium's FREE_FRAME_LAG:
     *                 submits in flight + 1).
     */
    public static void pump(long vmaHandle, VulkanCommandEncoder encoder, long frame, int frameLag) {
        if (failed) return;
        if (frame == lastFrame) return; // one pump per frame: a second would overwrite a slot still in flight
        if (deviceBuffer == 0L && !create(vmaHandle, frameLag + 1)) return;
        lastFrame = frame;
        boolean first = !ready;
        slotBase = stagingMapped + SLOT_BYTES * Math.floorMod(frame, (long) slots);
        if (++stamp == 0) stamp = 1;
        dirtyCount = 0;
        stagedCount = 0;

        if (!first) {
            sweep();
            applyQueue();
        }
        boolean header = modeDirty || first;
        if (!first && !header && dirtyCount == 0 && stagedCount == 0) return; // idle frame: no commands

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            if (first) {
                VK10.vkCmdFillBuffer(cb, deviceBuffer, 0, HEADER_BYTES + TABLE_BYTES, 0);
            }
            // The previous frames' shader reads (and the clear) before these writes.
            barrier(cb, stack);
            int regions = (header ? 1 : 0) + stagedCount + dirtyCount;
            VkBufferCopy.Buffer copies = VkBufferCopy.malloc(regions);
            try {
                int r = 0;
                long stageOffset = slotBase - stagingMapped;
                if (header) {
                    MemoryUtil.memPutInt(slotBase, mode);
                    MemoryUtil.memPutInt(slotBase + 4, MAGIC);
                    MemoryUtil.memPutInt(slotBase + 8, 0);
                    MemoryUtil.memPutInt(slotBase + 12, 0);
                    copies.get(r++).set(stageOffset, 0, HEADER_BYTES);
                    modeDirty = false;
                }
                for (int i = 0; i < stagedCount; i++) {
                    int page = stagedPages[i];
                    copies.get(r++).set(stageOffset + HEADER_BYTES + PAGE_BYTES * i,
                            HEADER_BYTES + TABLE_BYTES + PAGE_BYTES * page, PAGE_BYTES);
                }
                long entries = slotBase + HEADER_BYTES + PAGE_BYTES * PAGE_BUDGET;
                for (int i = 0; i < dirtyCount; i++) {
                    int s = dirtySlots[i];
                    MemoryUtil.memPutInt(entries + 8L * i, tableKey[s]);
                    MemoryUtil.memPutInt(entries + 8L * i + 4, tablePage[s]);
                    copies.get(r++).set(entries - stagingMapped + 8L * i, HEADER_BYTES + 8L * s, 8);
                }
                VK10.vkCmdCopyBuffer(cb, stagingBuffer, deviceBuffer, copies);
            } finally {
                copies.free();
            }
            // These writes before this frame's terrain draws.
            barrier(cb, stack);
            VK10.vkEndCommandBuffer(cb);
            encoder.execute(cb);
        }
        ready = true;
    }

    private static void barrier(VkCommandBuffer cb, MemoryStack stack) {
        VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(cb, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, b, null, null);
    }

    // ------------------------------------------------------------------
    // CPU table
    // ------------------------------------------------------------------

    private static int dist(int sx, int sy, int sz) {
        return Math.max(Math.max(Math.abs(sx - camSx), Math.abs(sz - camSz)), Math.abs(sy - camSy) / 2);
    }

    private static int key(int sx, int sy, int sz) {
        return (sx & 0x3FF) | ((sy & 0x3FF) << 10) | ((sz & 0x3FF) << 20) | VALID;
    }

    private static int slot(int sx, int sy, int sz) {
        return (Math.floorMod(sy, TY) * TZ + Math.floorMod(sz, TZ)) * TX + Math.floorMod(sx, TX);
    }

    private static boolean entryRoom(int n) {
        return dirtyCount + n <= ENTRY_BUDGET;
    }

    private static void markSlot(int s) {
        if (slotStamp[s] == stamp) return;
        slotStamp[s] = stamp;
        dirtySlots[dirtyCount++] = s;
    }

    private static void freePage(int page) {
        int s = pageSlot[page];
        if (s >= 0 && tablePage[s] == page + 1) {
            tableKey[s] = 0;
            tablePage[s] = 0;
            markSlot(s);
        }
        pageSlot[page] = -1;
        freePages.push(page);
    }

    /** Frees out-of-range pages, 128 checked per frame (the whole pool every 32 frames). */
    private static void sweep() {
        for (int n = 0; n < 128 && entryRoom(1); n++) {
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

    private static void applyQueue() {
        boolean allNear = false; // one failed eviction scan per frame is enough
        while (true) {
            Pending p = deferred != null ? deferred : queue.poll();
            deferred = null;
            if (p == null) return;
            // Worst case per section: free its old page, evict another, write its entry.
            if (!entryRoom(3) || (p.cells != null && stagedCount >= PAGE_BUDGET)) {
                deferred = p;
                return;
            }
            int s = slot(p.sx, p.sy, p.sz);
            int k = key(p.sx, p.sy, p.sz);
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
            // Stage the page (a section rebuilt twice this frame reuses its staging spot).
            int idx;
            if (pageStamp[page] == stamp) {
                idx = pageStageIndex[page];
            } else {
                idx = stagedCount++;
                pageStamp[page] = stamp;
                pageStageIndex[page] = idx;
                stagedPages[idx] = page;
            }
            MemoryUtil.memIntBuffer(slotBase + HEADER_BYTES + PAGE_BYTES * idx, CELLS).put(0, p.cells);
            if (tableKey[s] != k || tablePage[s] != page + 1) {
                tableKey[s] = k;
                tablePage[s] = page + 1;
                markSlot(s);
            }
            pageSlot[page] = s;
            pageSx[page] = p.sx; pageSy[page] = p.sy; pageSz[page] = p.sz;
        }
    }

    // ------------------------------------------------------------------
    // Buffers
    // ------------------------------------------------------------------

    private static boolean create(long vmaHandle, int slotCount) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vma = vmaHandle;
            slots = slotCount;
            LongBuffer pBuffer = stack.callocLong(1);
            PointerBuffer pAlloc = stack.callocPointer(1);

            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(BYTES)
                    .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE)
                    .requiredFlags(VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (Vma.vmaCreateBuffer(vma, info, alloc, pBuffer, pAlloc, null) != VK10.VK_SUCCESS) {
                failed = true;
                return false;
            }
            deviceBuffer = pBuffer.get(0);
            deviceAlloc = pAlloc.get(0);

            VkBufferCreateInfo sInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(SLOT_BYTES * slots)
                    .usage(VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo sAlloc = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                    .requiredFlags(VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                    .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            VmaAllocationInfo sInfoOut = VmaAllocationInfo.calloc(stack);
            if (Vma.vmaCreateBuffer(vma, sInfo, sAlloc, pBuffer, pAlloc, sInfoOut) != VK10.VK_SUCCESS
                    || sInfoOut.pMappedData() == 0L) {
                Vma.vmaDestroyBuffer(vma, deviceBuffer, deviceAlloc);
                deviceBuffer = 0L;
                failed = true;
                return false;
            }
            stagingBuffer = pBuffer.get(0);
            stagingAlloc = pAlloc.get(0);
            stagingMapped = sInfoOut.pMappedData();

            freePages.clear();
            for (int i = PAGES - 1; i >= 0; i--) { pageSlot[i] = -1; freePages.push(i); }
            return true;
        } catch (Throwable t) {
            failed = true;
            return false;
        }
    }
}
