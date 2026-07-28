package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.q_misc_util.my_util.Plane;

/**
 * IS5-SEAM-ARM — the inner-clip arm-site census (2026-07-27; always-on, log-only, 1 Hz,
 * emits only within {@link #GATE_DIST} blocks of the active clip plane). Born as the pre-fix
 * hypothesis test (its first live run measured fullyVoid=6 / nearStraddle=38 across three
 * crossings, feed coherent at 0.0000 — the numbers behind the crossing-window relax); it now
 * doubles as the SHIPPED FIX'S verification instrument, so its classes were REKEYED
 * (panel-corrected): the relax deliberately drives the armed plane's eye clearance to
 * +CROSSING_EYE_CLEARANCE, so classifying on the armed plane would count every relaxed frame as
 * "void" and convict a working fix.
 *
 * <h2>Sign conventions (D4.4 discipline — read before touching)</h2>
 * <ul>
 *   <li>{@code plane.normal()} points to the KEPT side (into the dest world, away from the
 *       arriving camera). {@code camToPlane = n·(cam − plane.pos)} is therefore NEGATIVE for a
 *       camera on the clipped (normal, pre-crossing) side; its magnitude is the camera's distance
 *       to the raw portal plane.</li>
 *   <li>The com.warwa store evaluates {@code gl_ClipDistance = dot(viewPos, planeXYZ) + planeW};
 *       the EYE is the view-space ORIGIN, so {@code planeW} IS the eye's own clip distance to the
 *       ARMED plane (whatever correction produced it).</li>
 *   <li>Feed-coherence invariant: {@code getClipEquationInner} yields
 *       {@code c = camToPlane − correction} for WHATEVER correction the arm passed, so
 *       {@code planeW − camToPlane + corrUsed} is EXPECTED ZERO on the unscaled,
 *       translation-free path — printed as {@code feedErr} so a feed/space divergence (bob
 *       W-term, scale) cannot hide behind this census.</li>
 * </ul>
 *
 * <h2>The three counter classes (and which leg each speaks for)</h2>
 * <ul>
 *   <li><b>baselineVoid / baselineStraddle</b> — classified on the UNRELAXED BASELINE plane
 *       ({@code camToPlane + ADJUSTMENT}): what the IP-constant arm would have armed. These label
 *       the crossing-window seconds in EVERY leg (nonzero there is EXPECTED, fix on or off) —
 *       they are the window marker, not a health check.</li>
 *   <li><b>armedVoidRisk</b> — the fix's health check: frames inside the sliver-capable zone
 *       ({@code d < FrontClipping.SLIVER_ZONE}) whose ARMED eye clearance is below
 *       {@link #RISK_MARGIN} (the view-bob erosion budget — bob translation subtracts
 *       {@code dot(nView, bobT)}, up to ~0.1 at a sprint bob peak, from the effective clearance).
 *       Relax ON ⇒ MUST be 0 (the hold pins clearance at +0.20); nonzero means the clearance is
 *       being outrun (scaled portal, extreme FOV) — the live detector for the documented limits.
 *       Relax OFF ({@code -PdisableSeamClipRelax}) ⇒ fires on every close approach, reproducing
 *       the pre-fix signature — the attribution leg.</li>
 * </ul>
 * Accumulators are min/max over EVERY armed frame of the emission window, not 1 Hz samples — the
 * suspect frames are sparse (bob oscillation) and a sampled census would miss them
 * (never generalize from one sampled block).
 */
public final class SeamClipArmCensus {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Emit only when the camera is within this of the active plane — silent in ordinary play. */
    private static final double GATE_DIST = 3.0;

    /** Baseline-straddle threshold: near-plane corner reach ~0.087-0.154 (FOV 70/16:9 .. Quake
     *  Pro). Used ONLY to classify the baseline (window-marker) counters. */
    private static final double NEAR_REACH = 0.10;

    /** The armed-clearance floor the fix must hold in the sliver zone: the view-bob erosion
     *  budget (see the class javadoc). Below it, void frames become possible again. */
    private static final double RISK_MARGIN = 0.10;

    private static long windowStartNanos = 0L;
    private static int framesArmed = 0;
    private static int framesDisarmed = 0;
    private static int framesSuspended = 0;
    private static int framesNullPlane = 0;
    private static int baselineVoidFrames = 0;
    private static int baselineStraddleFrames = 0;
    private static int armedVoidRiskFrames = 0;
    private static double minCamToPlane = Double.POSITIVE_INFINITY;
    private static double maxCamToPlane = Double.NEGATIVE_INFINITY;
    private static double minPlaneW = Double.POSITIVE_INFINITY;
    private static double maxPlaneW = Double.NEGATIVE_INFINITY;
    private static double minCorr = Double.POSITIVE_INFINITY;
    private static double maxCorr = Double.NEGATIVE_INFINITY;
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
     * @param corrUsed       the correction the arm actually passed (constant {@code -ADJUSTMENT},
     *                       or the IS5-SEAM crossing-window relax value) — the feed invariant is
     *                       {@code planeW == camToPlane − corrUsed}
     * @param suspendedByFix true when the V2 crossing-window gate SUSPENDED the inner clip for
     *                       this pass (the arm called disableClipping instead) — counted apart
     *                       from other disarmed frames so the fix's activity is visible
     */
    public static void note(
        @Nullable Plane plane,
        FrontClipping.Snapshot armed,
        Vec3 destCameraPos,
        double corrUsed,
        boolean suspendedByFix
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
            if (suspendedByFix) {
                framesSuspended++;
                // Window markers still accumulate for suspended frames (the baseline says what
                // the IP-constant arm WOULD have done — the crossing-second label).
                double baselineWSusp = camToPlane
                    + qouteall.imm_ptl.core.render.FrontClipping.ADJUSTMENT;
                if (baselineWSusp >= 0) {
                    baselineVoidFrames++;
                }
                else if (baselineWSusp > -NEAR_REACH) {
                    baselineStraddleFrames++;
                }
                maybeEmit();
                return;
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
            minCorr = Math.min(minCorr, corrUsed);
            maxCorr = Math.max(maxCorr, corrUsed);
            // The feed invariant: planeW = camToPlane − corrUsed (getClipEquationInner).
            double feedErr = armed.w - camToPlane + corrUsed;
            maxAbsFeedErr = Math.max(maxAbsFeedErr, Math.abs(feedErr));
            // WINDOW MARKERS — classified on the UNRELAXED BASELINE (what the IP-constant arm
            // would have armed), so they label crossing seconds identically in every leg.
            double baselineW = camToPlane
                + qouteall.imm_ptl.core.render.FrontClipping.ADJUSTMENT;
            if (baselineW >= 0) {
                baselineVoidFrames++;
            }
            else if (baselineW > -NEAR_REACH) {
                baselineStraddleFrames++;
            }
            // THE FIX'S HEALTH CHECK — inside the sliver-capable zone the ARMED eye clearance
            // must stay above the bob budget. Relax ON => must be 0; relax OFF => pre-fix
            // signature returns (the attribution leg).
            double d = -camToPlane;
            if (d < qouteall.imm_ptl.core.render.FrontClipping.SLIVER_ZONE
                && armed.w < RISK_MARGIN) {
                armedVoidRiskFrames++;
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
        if (framesArmed == 0 && framesDisarmed == 0 && framesSuspended == 0
            && framesNullPlane == 0) {
            return;
        }
        LOGGER.info(
            "[Seamless Portals] IS5-SEAM-ARM census (1Hz, |camToPlane| < {}): framesArmed={}"
                + " SUSPENDED={} (V2 crossing-window clip suspension — nonzero on crossing seconds"
                + " = the fix is live) disarmed={} nullPlane={} | camToPlane min={} max={}"
                + " (kept-normal signed;"
                + " NEGATIVE = camera on the clipped side) | planeW min={} max={} (the eye's clip"
                + " distance vs the ARMED plane) | corr min={} max={} (-0.0100 = IP constant;"
                + " below it = the crossing relax active) | WINDOW MARKERS baselineVoid={}"
                + " baselineStraddle={} (vs the UNRELAXED baseline — nonzero on crossing seconds"
                + " is EXPECTED in every leg) | FIX HEALTH armedVoidRisk={} (d < {} with armed"
                + " clearance < {}; relax ON => MUST be 0, nonzero = clearance outrun — scaled"
                + " portal / extreme FOV; relax OFF leg => fires on approach, the pre-fix"
                + " signature) | maxAbsFeedErr={} (expected ~0; planeW - camToPlane + corr).",
            GATE_DIST, framesArmed, framesSuspended, framesDisarmed, framesNullPlane,
            fmt(minCamToPlane), fmt(maxCamToPlane),
            fmt(minPlaneW), fmt(maxPlaneW),
            fmt(minCorr), fmt(maxCorr),
            baselineVoidFrames, baselineStraddleFrames,
            armedVoidRiskFrames,
            qouteall.imm_ptl.core.render.FrontClipping.SLIVER_ZONE, RISK_MARGIN,
            fmt(maxAbsFeedErr)
        );
        framesArmed = 0;
        framesDisarmed = 0;
        framesSuspended = 0;
        framesNullPlane = 0;
        baselineVoidFrames = 0;
        baselineStraddleFrames = 0;
        armedVoidRiskFrames = 0;
        minCamToPlane = Double.POSITIVE_INFINITY;
        maxCamToPlane = Double.NEGATIVE_INFINITY;
        minPlaneW = Double.POSITIVE_INFINITY;
        maxPlaneW = Double.NEGATIVE_INFINITY;
        minCorr = Double.POSITIVE_INFINITY;
        maxCorr = Double.NEGATIVE_INFINITY;
        maxAbsFeedErr = 0.0;
    }

    private static String fmt(double v) {
        if (Double.isInfinite(v)) {
            return "n/a";
        }
        return String.format("%.4f", v);
    }
}
