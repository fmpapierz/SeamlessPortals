package com.warwa.seamlessportals.passthrough;

/**
 * THE ONE PLACE THAT DECIDES WHETHER A WRITE MIRRORS.
 *
 * <p>Two independent questions, each answered by its own enum, and this class is the only thing that
 * turns them into a yes or no:
 * <ul>
 *   <li><b>WHO wrote it</b> — {@link SeamWriteSource}. Today: players only.</li>
 *   <li><b>HOW the seam lines up</b> — {@link SeamAlignment}. Today: exact lattice only.</li>
 * </ul>
 *
 * <p><b>Why a separate class rather than two {@code if}s in the driver.</b> The user's requirement is
 * that blocks and rails generalise later "to everything else easily, including offset mirroring".
 * That is only true if widening the feature never means re-reading the driver, the veto, the clear
 * path and the reconciliation pass to find every place a rule got inlined. Every one of those call
 * sites asks this class instead, so adding piston support or offset support is editing the two
 * methods below — and the gates then A/B it in one lever.
 *
 * <p><b>What this class must never become.</b> It answers "is this write eligible", not "where does
 * it go" and not "is the destination free". Target resolution stays in {@code SeamMap}/
 * {@code SeamRegistry}; conflict refusal stays in {@code SeamMirror.mayPlace}. Folding those in here
 * is how a policy object turns into a second implementation of the feature.
 */
public final class SeamMirrorPolicy {

    private SeamMirrorPolicy() {}

    /**
     * Whether a write from this source may mirror.
     *
     * <p>User decision 2026-07-26: player actions only. This NARROWS
     * {@code REDSTONE_RECON.md} §0.8, which had pinned non-item writes (piston, dispenser, explosion,
     * gravity, {@code /setblock}, {@code /fill}) as "ACCEPT BEST-EFFORT". Those are now classified and
     * declined rather than mirrored on a best-effort basis.
     *
     * <p>Breaks stay mirrored: "break one half breaks the other" is §0.7, a separate rule the
     * narrowing did not withdraw, and dropping it would strand a far-side half with no way to remove
     * it.
     *
     * <p><b>To widen later:</b> add the source to this test. That is the whole change — every call
     * site already routes here, and {@code -PdisableSeamPlayerOnly} A/Bs it in both directions.
     */
    public static boolean mirrors(SeamWriteSource source) {
        if (AperturePassthroughLever.DISABLE_SEAM_PLAYER_ONLY) {
            return true;   // pre-2026-07-26 behaviour: mirror every write regardless of origin
        }
        return source.isPlayer();
    }

    /**
     * Whether a seam with this alignment may mirror.
     *
     * <p>User decision 2026-07-26: exact lattice alignment only, offset support deferred.
     *
     * <p><b>To widen later:</b> implement the destination resolution for {@link SeamAlignment#OFFSET}
     * — greatest-overlap, or the fractional seam blocks the user has approved as the long-term goal —
     * and admit it here. {@link SeamAlignment#UNMAPPABLE} must stay excluded: it is not deferred work,
     * it is geometry with no cell correspondence at any phase.
     */
    public static boolean mirrors(SeamAlignment alignment) {
        if (AperturePassthroughLever.DISABLE_SEAM_EXACT_ONLY) {
            // Pre-2026-07-26 behaviour: mirror offset seams too, via the greatest-overlap cell that
            // resolveDestCell already produces. Kept as a lever because the offset case is a real
            // geometry the feature is expected to support eventually, so being able to switch it back
            // on is how the future work gets tested against the current one.
            return alignment != SeamAlignment.UNMAPPABLE;
        }
        return alignment.hasExactCounterpart();
    }
}
