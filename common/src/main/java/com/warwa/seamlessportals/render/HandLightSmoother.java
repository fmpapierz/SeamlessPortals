package com.warwa.seamlessportals.render;

import net.minecraft.util.LightCoordsUtil;

/**
 * Smooths the first-person hand's light coordinates over time.
 *
 * <p>The hand's packed light is sampled fresh every frame at the player's position
 * ({@code GameRenderer.renderItemInHand} → {@code getPackedLightCoords(player)}). On a
 * seamless crossing the sample jumps in ONE frame from the source dimension's light to the
 * destination's (e.g. overworld sky 15 → nether sky 0 + portal block light) — vanilla hides
 * this behind the portal fade screen; a seamless teleport exposes it as an instant lighting
 * pop on the hand, the one object that persists across the cut.
 *
 * <p>Approach: converge the block/sky levels toward the live target at a fixed rate
 * ({@value #LEVELS_PER_SECOND} levels/s). Normal gameplay changes light 1-2 levels per block
 * walked, converging in well under 100 ms — imperceptible. A 15-level dimension jump takes
 * ~0.6 s — the gradual transition the eye expects. Always-on (no crossing detection needed),
 * exactly like the hand's own sway/bob fields which are also continuous lerps.
 *
 * <p>Output uses FRACTIONAL light coords: the classic packed format carries 4 sub-level bits
 * per channel (u = block×16 in 0..240, v = sky×16 — see {@code LightCoordsUtil}
 * MAX_SMOOTH_LIGHT_LEVEL) and the lightmap is linear-filtered, so intermediate values render
 * as genuinely intermediate brightness, not 15 discrete steps.
 *
 * <p>Render-thread only (called from the hand submit in GameRenderer.render).
 */
public final class HandLightSmoother {

    private HandLightSmoother() {}

    private static final float LEVELS_PER_SECOND = 25.0f;
    /** After this long without a sample (world exit/load screens), snap instead of lerping. */
    private static final long SNAP_AFTER_NANOS = 2_000_000_000L;

    private static float smoothedBlock;
    private static float smoothedSky;
    private static long lastSampleNanos;
    private static boolean initialized;

    /** @param targetPacked the live packed light (classic format) sampled at the player. */
    public static int smooth(int targetPacked) {
        int targetBlock = LightCoordsUtil.block(targetPacked);
        int targetSky = LightCoordsUtil.sky(targetPacked);
        long now = System.nanoTime();

        if (!initialized || now - lastSampleNanos > SNAP_AFTER_NANOS) {
            smoothedBlock = targetBlock;
            smoothedSky = targetSky;
            initialized = true;
            lastSampleNanos = now;
            return targetPacked;
        }

        float step = LEVELS_PER_SECOND * ((now - lastSampleNanos) / 1_000_000_000.0f);
        lastSampleNanos = now;
        smoothedBlock = approach(smoothedBlock, targetBlock, step);
        smoothedSky = approach(smoothedSky, targetSky, step);

        // Fractional pack: u = block*16 (0..240), v = sky*16 — equal to
        // LightCoordsUtil.pack for whole levels, sub-texel in between.
        int u = clamp240(Math.round(smoothedBlock * 16.0f));
        int v = clamp240(Math.round(smoothedSky * 16.0f));
        return (v << 16) | u;
    }

    private static float approach(float current, float target, float maxStep) {
        float diff = target - current;
        if (Math.abs(diff) <= maxStep) return target;
        return current + Math.copySign(maxStep, diff);
    }

    private static int clamp240(int v) {
        return Math.max(0, Math.min(240, v));
    }
}
