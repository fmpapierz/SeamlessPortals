package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * THE TARGETING PROBE — the instrument for the single highest-risk unknown in sub-feature (a).
 *
 * <p><b>The disagreement it exists to settle.</b> The (a) design panel's two adversarial verifiers
 * reasoned from IDENTICAL geometry to OPPOSITE conclusions about what happens when a player aims at
 * a block sitting in a portal aperture. Verifier A held that roughly half of every top-face click
 * lands the block in the DESTINATION dimension; Verifier B held that only a narrow band near the
 * plane is lost. Neither observed it. The failure is silent and wrong-dimensional rather than a
 * crash, and it lands on the single most common gesture the whole feature exists to support —
 * putting a rail on the portal floor.
 *
 * <p><b>The mechanism under test</b> is one comparison in
 * {@code BlockManipulationClient.updatePointedBlock} ({@code :81}):
 * <pre>if (distanceToPortalPointing &lt; getCurrentTargetDistance() + 0.2) { … reroute through the portal … }</pre>
 * Historically the aperture held only {@code PortalPlaceholderBlock}, for which
 * {@code getCurrentTargetDistance()} returns a sentinel 23333 ({@code :104-109}) — so the portal
 * always won and every click went through to the far world. Once (a) puts REAL blocks in those
 * cells, the local hit has a real distance, and which side wins becomes a genuine race. Aiming at
 * the near half of a straddling block should keep the click local; aiming past the plane — the far
 * half of a floor block's top face — should hand it to the portal.
 *
 * <p>Logs the two distances, the decision, and whether the locally-hit cell is a registered seam
 * cell, so the reading needs no interpretation: a line where {@code localIsSeam=true} and
 * {@code decision=THROUGH-PORTAL} is a block that will land in the wrong dimension.
 *
 * <p>Runs on the render thread, so it is 1 Hz-latched — per-frame log4j on the 26.2 render thread
 * costs ~130 ms stalls. Byte-inert without {@code -Dseamlessportals.seamAimProbe=true}, and
 * self-disarms on any throw so a diagnostic can never take down rendering.
 */
public final class SeamAimProbe {

    private SeamAimProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long RATE_LIMIT_NS = 1_000_000_000L;

    private static boolean disarmed = false;
    private static long lastLogNanos = 0L;

    /**
     * Called at the reroute decision in {@code BlockManipulationClient.updatePointedBlock}.
     *
     * @param level         the player's level, for the seam-cell lookup
     * @param localHit      the block the ordinary (non-portal) ray hit, or null when it missed
     * @param portalDist    distance from the camera to the portal plane hit
     * @param localDist     {@code getCurrentTargetDistance()} — 23333 means "nothing local hit"
     * @param reroute       the decision actually taken: true = the click goes through the portal
     */
    public static void aimDecision(
        @Nullable Level level, @Nullable BlockPos localHit,
        double portalDist, double localDist, boolean reroute, boolean seamOverrideFired
    ) {
        if (!AperturePassthroughLever.SEAM_AIM_PROBE || disarmed) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (now - lastLogNanos < RATE_LIMIT_NS) {
                return;
            }
            lastLogNanos = now;

            boolean localIsSeam = level != null && localHit != null
                && SeamRegistry.isSeamCell(level, localHit);

            // A reroute is only a DEFECT when a real block was there to lose. With an empty aperture
            // cell (the 23333 sentinel) rerouting is correct behaviour — it is what lets a player
            // reach through an open portal — so it must not be labelled a loss.
            boolean realBlockLost = localIsSeam && reroute && localDist < 20000;

            LOGGER.info(
                "[RS-SEAM-AIM] localHit={} localIsSeam={} localDist={} portalDist={} margin={}"
                    + " decision={} seamOverride={}{}",
                localHit == null ? "(none)" : localHit,
                localIsSeam,
                localDist > 20000 ? "NONE(23333)" : String.format("%.3f", localDist),
                String.format("%.3f", portalDist),
                localDist > 20000 ? "n/a" : String.format("%.3f", localDist + 0.2 - portalDist),
                reroute ? "THROUGH-PORTAL" : "LOCAL",
                seamOverrideFired ? "FIRED (kept local)" : "no",
                realBlockLost
                    ? "   <-- DEFECT: a REAL block in a seam cell was lost to the far dimension"
                    : (localIsSeam && reroute
                        ? "   (empty aperture cell — rerouting is CORRECT, reach-through preserved)"
                        : ""));
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        try {
            LOGGER.warn("[RS-SEAM-AIM] disarmed after a throw (diagnostic only, render unaffected)", t);
        }
        catch (Throwable ignored) {
            // never let the probe's own failure escape
        }
    }
}
