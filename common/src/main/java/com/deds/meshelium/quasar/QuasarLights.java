package com.deds.meshelium.quasar;

/**
 * Quasar MC addition (not upstream Meshelium): moving lights handed in by Quasar MC's Quasium mod every frame.
 *
 * <p>Quasium bakes moving lights into the chunk meshes at whole-block positions (that keeps them out of
 * walls) and calls {@link #set} each frame with, per light, the exact world position, the block-centre
 * position of the current bake and the light level. The terrain mesh shader uses the pair to slide the baked
 * light smoothly to the exact position (see {@code quasarDynamicLight} in terrain.mesh).</p>
 *
 * <p>Called reflectively by Quasium, so neither mod needs the other to build.</p>
 */
public final class QuasarLights {
    public static final int MAX = 16;
    /** Doubles per light: exact x, y, z, baked-centre x, y, z, level. */
    public static final int STRIDE = 7;

    private static volatile double[] data = new double[0];
    private static volatile int count;

    private QuasarLights() {}

    /** @param lights {@link #STRIDE} doubles per light, world coordinates; at most {@link #MAX} are used. */
    public static void set(double[] lights, int lightCount) {
        data = lights;
        count = Math.min(MAX, Math.min(lightCount, lights.length / STRIDE));
    }

    public static double[] data() {
        return data;
    }

    public static int count() {
        return count;
    }
}
