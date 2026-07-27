package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.q_misc_util.my_util.Plane;

/**
 * IS5-SEAM-ARM — the inner-clip arm-site census (2026-07-27; always-on, log-only, 1 Hz,
 * emits only within {@link #GATE_DIST} blocks of the active clip plane).
 *
 * <h2>Why it exists</h2>
 * The {@code front_clipping} live A/B (both directions, one session) proved the inner clip plane
 * CARRIES the black seam band: disable ⇒ band gone across repeated slow crossings; enable ⇒ band
 * back. But the static plane geometry cannot void aperture rays while the render eye is on the
 * CLIPPED side of the plane — every rasterized aperture ray then crosses into the kept half-space
 * at the aperture itself and dest terrain beyond it draws. An all-void aperture requires frames
 * whose RENDER camera (partialTick-interpolated + view bob) sits ON or PAST the plane before the
 * tick-keyed crossing detector fires. That is a hypothesis, not a measurement — this census
 * measures it per armed frame instead of guessing.
 *
 * <h2>Sign conventions (D4.4 discipline — read before touching)</h2>
 * <ul>
 *   <li>{@code plane.normal()} points to the KEPT side (into the dest world, away from the
 *       arriving camera). {@code camToPlane = n·(cam − plane.pos)} is therefore NEGATIVE for a
 *       camera on the clipped (normal, pre-crossing) side; its magnitude is the camera's distance
 *       to the raw portal plane.</li>
 *   <li>The com.warwa store evaluates {@code gl_ClipDistance = dot(viewPos, planeXYZ) + planeW};
 *       the EYE is the view-space ORIGIN, so {@code planeW} IS the eye's own clip distance to the
 *       ARMED (adjustment-shifted) plane. {@code planeW >= 0} ⇒ the eye is on the kept side of
 *       the armed plane ⇒ every aperture ray points away from the kept boundary it already passed
 *       — the FULLY-VOID frame class.</li>
 *   <li>Feed-coherence invariant: the arm passes {@code correction = −ADJUSTMENT (−0.01)}, and
 *       {@code getClipEquationInner} yields {@code c = camToPlane − correction}, so
 *       {@code planeW − camToPlane − 0.01} is EXPECTED ZERO on the unscaled, translation-free
 *       path. A nonzero residual measures a feed divergence (bob W-term, scale, matrix space) —
 *       printed as {@code feedErr} so a shader-space mismatch cannot hide behind this census.</li>
 * </ul>
 *
 * <h2>How to read the line</h2>
 * {@code fullyVoid > 0} on the seconds the band shows ⇒ the render camera really does cross the
 * armed plane pre-teleport — the fix must handle the crossing window (design panel next).
 * {@code fullyVoid == 0} with the band showing ⇒ the void is NOT the eye-side class; suspect the
 * per-draw upload space (feedErr) or a different clip consumer.
 * {@code nearStraddle} counts frames where the near plane can poke through (partial-band class).
 * Accumulators are min/max over EVERY armed frame of the emission window, not 1 Hz samples — the
 * suspect frames are sparse (bob oscillation) and a sampled census would miss them
 * (never generalize from one sampled block).
 */
public final class SeamClipArmCensus {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Emit only when the camera is within this of the active plane — silent in ordinary play. */
    private static final double GATE_DIST = 3.0;

    /** Farthest near-plane corner reach: 0.05 near × secant. ~0.087 at FOV 70/16:9, ~0.154 at
     *  Quake Pro. 0.10 is the counter threshold (a "near disk may straddle" flag, not a claim). */
    private static final double NEAR_REACH = 0.10;

    private static long windowStartNanos = 0L;
    private static int framesArmed = 0;
    private static int framesDisarmed = 0;
    private static int framesNullPlane = 0;
    private static int fullyVoidFrames = 0;
    private static int nearStraddleFrames = 0;
    private static double minCamToPlane = Double.POSITIVE_INFINITY;
    private static double maxCamToPlane = Double.NEGATIVE_INFINITY;
    private static double minPlaneW = Double.POSITIVE_INFINITY;
    private static double maxPlaneW = Double.NEGATIVE_INFINITY;
    private static double maxAbsFeedErr = 0.0;

    private SeamClipArmCensus() {}

    /**
     * Called once per full-pipeline dest arm, right after the plane is fed and frozen.
     * Never throws; log-only.
     *
     * @param plane          the plane the arm consumed ({@code getActiveClippingPlane()});
     *                       null when the arm collapsed to disableClipping (counted, not judged)
     * @param armed          the com.warwa store snapshot taken just after the arm
     * @param destCameraPos  the nested pass's virtual (dest-side) camera position
     */
    public static void note(
        @Nullable Plane plane,
        FrontClipping.Snapshot armed,
        Vec3 destCameraPos
    ) {
        try {
            if (plane == null) {
                framesNullPlane++;
                maybeEmit();
                return;
            }
            Vec3 n = plane.normal();
            Vec3 p = plane.pos();
            double camToPlane = n.x * (destCameraPos.x - p.x)
                + n.y * (destCameraPos.y - p.y)
                + n.z * (destCameraPos.z - p.z);
            if (Math.abs(camToPlane) > GATE_DIST) {
                return; // far from the plane: neither accumulate nor emit
            }
            if (!armed.enabled) {
                framesDisarmed++;
                maybeEmit();
                return;
            }
            framesArmed++;
            minCamToPlane = Math.min(minCamToPlane, camToPlane);
            maxCamToPlane = Math.max(maxCamToPlane, camToPlane);
            minPlaneW = Math.min(minPlaneW, armed.w);
            maxPlaneW = Math.max(maxPlaneW, armed.w);
            // Expected: planeW = camToPlane − correction = camToPlane + ADJUSTMENT(0.01).
            double feedErr = armed.w - camToPlane
                - qouteall.imm_ptl.core.render.FrontClipping.ADJUSTMENT;
            maxAbsFeedErr = Math.max(maxAbsFeedErr, Math.abs(feedErr));
            if (armed.w >= 0) {
                fullyVoidFrames++;
            }
            else if (armed.w > -NEAR_REACH) {
                nearStraddleFrames++;
            }
            maybeEmit();
        }
        catch (Throwable t) {
            // log-only instrument: never let it touch the render pass
            try {
                LOGGER.warn("[Seamless Portals] IS5-SEAM-ARM census failed (instrument only)", t);
            }
            catch (Throwable ignored) {
            }
        }
    }

    private static void maybeEmit() {
        long now = System.nanoTime();
        if (windowStartNanos == 0L) {
            windowStartNanos = now;
            return;
        }
        if (now - windowStartNanos < 1_000_000_000L) {
            return;
        }
        windowStartNanos = now;
        if (framesArmed == 0 && framesDisarmed == 0 && framesNullPlane == 0) {
            return;
        }
        LOGGER.info(
            "[Seamless Portals] IS5-SEAM-ARM census (1Hz, |camToPlane| < {}): framesArmed={}"
                + " disarmed={} nullPlane={} | camToPlane min={} max={} (kept-normal signed;"
                + " NEGATIVE = camera on the clipped side) | planeW min={} max={} (= the eye's own"
                + " clip distance; >=0 = FULLY-VOID frame) | fullyVoid={} nearStraddle={} (planeW >"
                + " -{}) | maxAbsFeedErr={} (expected ~0; planeW - camToPlane - 0.01)"
                + " — fullyVoid>0 on band seconds => the render eye crosses the armed plane"
                + " pre-teleport; fullyVoid=0 with the band showing => eye-side class REFUTED, read"
                + " feedErr before theorising.",
            GATE_DIST, framesArmed, framesDisarmed, framesNullPlane,
            fmt(minCamToPlane), fmt(maxCamToPlane),
            fmt(minPlaneW), fmt(maxPlaneW),
            fullyVoidFrames, nearStraddleFrames, NEAR_REACH,
            fmt(maxAbsFeedErr)
        );
        framesArmed = 0;
        framesDisarmed = 0;
        framesNullPlane = 0;
        fullyVoidFrames = 0;
        nearStraddleFrames = 0;
        minCamToPlane = Double.POSITIVE_INFINITY;
        maxCamToPlane = Double.NEGATIVE_INFINITY;
        minPlaneW = Double.POSITIVE_INFINITY;
        maxPlaneW = Double.NEGATIVE_INFINITY;
        maxAbsFeedErr = 0.0;
    }

    private static String fmt(double v) {
        if (Double.isInfinite(v)) {
            return "n/a";
        }
        return String.format("%.4f", v);
    }
}
