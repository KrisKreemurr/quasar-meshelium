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
