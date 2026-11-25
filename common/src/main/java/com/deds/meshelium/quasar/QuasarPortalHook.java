package com.deds.meshelium.quasar;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.vk.TerrainDrawer;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * Quasar MC: the seam Quasar Immersive Portals uses to draw portal views with Meshelium's terrain (found by
 * reflection, so neither mod needs the other to build).
 *
 * <p>{@link #afterOpaque} runs once per frame right after Meshelium drew the main opaque terrain, at a point where no
 * render pass is open (renderGroup is called between passes; the event stages are not). Inside it the portal mod may
 * call {@link #drawTerrain} for its virtual cameras and composite the results itself.</p>
 */
public final class QuasarPortalHook {
    /** Set by the portal mod. Runs on the render thread after the main opaque terrain. */
    public static volatile Runnable afterOpaque;
    
    /**
     * Render distance for portal views, in sections (chunks) around the virtual camera, horizontal (like vanilla's
     * render distance). Set by the portal mod; beyond it the far view comes from the panorama (sky colour until then).
     */
    public static volatile int portalRenderDistance = Integer.MAX_VALUE;
    
    private static boolean failed;

    /**
     * Threads that compile sections of another client world (Quasar Immersive Portals' background-world
     * terrain). Meshelium's compile tap ignores them: its residency holds the player's world only.
     */
    private static final ThreadLocal<Boolean> FOREIGN_COMPILE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Marks the calling thread as compiling another world's sections (called once by each such worker). */
    public static void markForeignCompileThread() {
        FOREIGN_COMPILE.set(Boolean.TRUE);
    }

    public static boolean isForeignCompile() {
        return FOREIGN_COMPILE.get();
    }

    /**
     * Background client worlds held by the portal mod (other dimensions seen through portals). Their chunk,
     * light and block events must not reach Meshelium's world-bound bookkeeping (far field). A registered world
     * the player is in counts as the player's world (never foreign).
     */
    private static final java.util.Set<Object> FOREIGN_LEVELS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public static void registerForeignLevel(Object level) {
        if (level != null) FOREIGN_LEVELS.add(level);
    }

    public static void unregisterForeignLevel(Object level) {
        if (level != null) FOREIGN_LEVELS.remove(level);
    }

    /** True when {@code level} is a portal background world the player is not in. */
    public static boolean isForeignLevel(Object level) {
        if (level == null || FOREIGN_LEVELS.isEmpty() || !FOREIGN_LEVELS.contains(level)) return false;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc.player == null || mc.player.level() != level;
    }

    /** True when {@code chunkSource} belongs to a foreign level (see {@link #isForeignLevel}). */
    public static boolean isForeignChunkSource(Object chunkSource) {
        if (chunkSource == null || FOREIGN_LEVELS.isEmpty()) return false;
        for (Object l : FOREIGN_LEVELS) {
            if (l instanceof net.minecraft.client.multiplayer.ClientLevel cl && cl.getChunkSource() == chunkSource) {
                return isForeignLevel(cl);
            }
        }
        return false;
    }
    
    private QuasarPortalHook() {}
    
    /** Called by the terrain kill switch after Meshelium drew the opaque group. */
    public static void fireAfterOpaque() {
        Runnable r = afterOpaque;
        if (r == null || failed) {
            return;
        }
        try {
            r.run();
        } catch (Throwable t) {
            failed = true;
            MesheliumLog.LOGGER.error("Quasar portal views failed; portal views are off for this session", t);
        }
    }
    
    /** This frame's main camera (the one the opaque terrain was just drawn with), or null. */
    public static CameraRenderState mainCamera() {
        return TerrainDrawer.quasarMainCamera();
    }
    
    /**
     * Draws the resident opaque terrain as seen from {@code cam} into the given color/depth attachments (RGBA8 +
     * D32, reverse-Z: clear depth to 0 first). {@code cam} needs pos, blockPos, viewRotationMatrix,
     * projectionMatrix and cullFrustum (prepared). Returns false when Meshelium cannot draw right now.
     */
    /** Sections the last {@link #drawTerrain} call drew (for the portal F3 line). */
    public static int lastSections() {
        return TerrainDrawer.quasarPortalSections();
    }
    
    public static boolean drawTerrain(CameraRenderState cam, GpuTextureView color, GpuTextureView depth, int width,
            int height) {
        return TerrainDrawer.quasarDrawPortalView(cam, color, depth, width, height);
    }

    /** Water, glass and other translucent terrain for a portal view (after drawTerrain, same target). */
    public static boolean drawTranslucent(CameraRenderState cam, GpuTextureView color, GpuTextureView depth,
            int width, int height) {
        return TerrainDrawer.quasarDrawPortalTranslucent(cam, color, depth, width, height);
    }

    /** Translucent sections drawn by the last portal translucent pass. */
    public static int lastTranslucentSections() {
        return TerrainDrawer.quasarLastPortalTranslucentSections();
    }
}
