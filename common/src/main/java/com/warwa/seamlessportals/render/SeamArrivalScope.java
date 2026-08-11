package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import qouteall.imm_ptl.core.portal.Portal;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * IS5-ARRIVE (2026-08-10, `migration/IS5_ARRIVE_DESIGN.md`) — the sideways-arrival mark.
 *
 * <p>On a TELEPORT frame, a just-exited reverse portal classified SIDEWAYS (near-plane,
 * |look·N| ≤ 0.2) is marked here by the visibility consume; the V2 crossing-window clip
 * suspension then WITHHOLDS for exactly that portal on exactly that frame, so its dest pass
 * renders under the ARMED V1 clip (planeW pinned at +CROSSING_EYE_CLEARANCE = +0.20 —
 * armedVoidRisk stays 0 by arithmetic) instead of unclipped — the §4b sideways wrong-content
 * paint's fix. Backward arrivals keep suspension (user-clean; V1 there is the measured-band
 * geometry); forward arrivals keep the XFLICK skip (their mesh is null anyway).
 *
 * <p>JUDGE FOLDS: a per-frame identity SET, not a single slot (two portals can classify
 * sideways on one tp frame — cluster flip portals; teleportLimitPerFrame=3 multi-cross
 * frames); typed {@link Portal}; cleared at manageTeleportation HEAD **above** the
 * disableTeleportation early-return (a runtime toggle after a sideways arrival must not
 * latch the mark + flag into a sustained V1-in-doorway band). WeakReferences + the
 * isTeleportingFrame gate + the per-manage clear triple-bound the lifetime
 * (cache-outlives-subject rule).
 */
public final class SeamArrivalScope {

    private SeamArrivalScope() {}

    private static final ArrayList<WeakReference<Portal>> marked = new ArrayList<>(4);

    /** Census-read: withheld suspensions this census window (reset by SeamClipArmCensus). */
    public static int withheldCount = 0;

    private static boolean liveNoted = false;

    public static void markSidewaysArrival(Portal portal) {
        if (portal == null) return;
        for (WeakReference<Portal> r : marked) {
            if (r.get() == portal) return;
        }
        marked.add(new WeakReference<>(portal));
    }

    public static boolean isMarked(Object portal) {
        if (portal == null || marked.isEmpty()) return false;
        for (WeakReference<Portal> r : marked) {
            if (r.get() == portal) return true;
        }
        return false;
    }

    public static void clear() {
        marked.clear();
    }

    public static void noteWithheld() {
        withheldCount++;
        if (!liveNoted) {
            liveNoted = true;
            SeamlessPortalsConstants.LOGGER.info(
                "[Seamless Portals] [IS5-ARRIVE] sideways arrival-frame suspension withheld LIVE"
                    + " (armed V1 clip, eye pinned +0.20; first consume this session;"
                    + " disable row: -PdisableSeamArrivalScope)");
        }
    }
}
