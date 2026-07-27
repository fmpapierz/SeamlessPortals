package com.warwa.seamlessportals.passthrough;

/**
 * HOW THE TWO SIDES OF A SEAM LINE UP — the geometry half of the mirror policy.
 *
 * <p>Separated from {@link SeamMirrorPolicy}'s decision for the same reason as
 * {@link SeamWriteSource}: adding offset support later must be implementing a mapping and flipping a
 * policy, not unpicking a driver.
 *
 * <p><b>Today only {@link #EXACT} mirrors</b> (user decision, 2026-07-26: "only activate mirroring
 * when blocks line up x/y/z perfectly across the portal, save offset support for future work").
 *
 * <p>⚠ <b>This REVERSES a previously pinned decision, and the reversal must not be silently undone.</b>
 * {@code REDSTONE_RECON.md} §0.7 pinned "Phase-offset target = GREATEST OVERLAP … this deliberately
 * reproduces the half-block jog the user predicted rather than concealing it", and commit
 * {@code 7766010} exists precisely because a design-panel finding once overrode that rule and
 * silently disabled mirroring on offset geometry. The situation now is the opposite — the USER has
 * chosen to defer offset support — so {@link #OFFSET} is kept as a named, classified state rather
 * than being folded into "unmappable". A future session reading only §0.7 would otherwise restore
 * greatest-overlap a second time.
 */
public enum SeamAlignment {

    /**
     * The portal maps the block lattice onto itself: a source cell has exactly ONE destination cell,
     * corner to corner. Every obsidian frame pair is EXACT, as is any custom pair whose planes' phases
     * sum to an integer. Tested by {@code SeamMap.latticeAligned}.
     */
    EXACT,

    /**
     * The lattice is preserved in shape but not in phase: a source cell's image straddles TWO
     * destination cells, so there is no exact counterpart. Mirroring is DECLINED here for now.
     *
     * <p>The future work this names has two candidate answers, both already discussed with the user:
     * greatest-overlap (pick the cell holding most of the image — reproduces the half-block jog), or
     * true fractional seam blocks (the far half arrives as a genuine partial block, which needs new
     * block states, models and collision). The second is the user-approved long-term goal.
     */
    OFFSET,

    /**
     * The linear part of the transform is not a signed axis permutation — scaling, or a rotation that
     * is not a yaw quarter-turn. No cell-to-cell correspondence exists at all, at any phase, so this
     * is not deferred work; it is geometry the mirror cannot have an answer for.
     */
    UNMAPPABLE;

    /** Whether a single, exact destination cell exists for a source cell. */
    public boolean hasExactCounterpart() {
        return this == EXACT;
    }
}
