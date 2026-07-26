package com.warwa.seamlessportals.passthrough;

/**
 * REDSTONE/RAIL/MINECART PASSTHROUGH — THE PROBE LEVER HOLDER
 * ({@code migration/REDSTONE_RECON.md} §5; engagement prompt
 * {@code migration/REDSTONE_PASSTHROUGH_PROMPT.md} §4 lever discipline).
 *
 * <p>Two DEFAULT-OFF probe levers for the (a) "placeable aperture" diagnose-first round. Both are
 * diagnostics only — with neither property set this class is byte-inert and every call site
 * short-circuits on one static-field read.
 *
 * <ul>
 *   <li>{@link #CENSUS_ENABLED} — {@code -Dseamlessportals.apertureCensusProbe=true}. One-shot
 *       per portal entity: dumps the aperture cell coordinates and their current block states, so
 *       a live round can read real {@code /setblock} targets out of {@code latest.log} instead of
 *       guessing them from the F3 screen.</li>
 *   <li>{@link #SUPPRESS_TEARDOWN} — {@code -Dseamlessportals.apertureSuppressTeardown=true}.
 *       Relaxes {@code NetherPortalEntity.isPortalIntactOnThisSide} so a non-placeholder block in
 *       an opening cell no longer fails the integrity predicate. WITHOUT this the decisive
 *       experiment is impossible: a {@code /setblock} into the aperture kills the portal (and its
 *       cross-dimension twin) within at most 233 ticks, long before the seam can be inspected.
 *       The obsidian FRAME requirement is deliberately left intact — this suppresses only the
 *       opening-contents half of the predicate, so a genuine frame break still tears down.</li>
 * </ul>
 *
 * <p>Deliberately a plain holder class, NOT hosted on a mixin and NOT hosted on the probe itself:
 * {@code Boolean.getBoolean} is a method call rather than a javac compile-time constant, so a
 * static-final read hosted on the probe class would trigger that class's {@code <clinit>} in every
 * environment (crossing gametest included), and Mixin silently drops non-constant static
 * initializers on mixin classes. Same rationale as
 * {@code qouteall.imm_ptl.core.compat.iris_compatibility.ShaderpackViewsProbeLever}.
 */
public final class AperturePassthroughLever {

    /** Aperture census dump. Byte-inert unless {@code -Dseamlessportals.apertureCensusProbe=true}. */
    public static final boolean CENSUS_ENABLED =
        Boolean.getBoolean("seamlessportals.apertureCensusProbe");

    /**
     * Suppresses the opening-contents half of the portal integrity predicate. DIAGNOSTIC ONLY —
     * the shipped (a) fix will replace the predicate itself, not gate it behind a probe.
     */
    public static final boolean SUPPRESS_TEARDOWN =
        Boolean.getBoolean("seamlessportals.apertureSuppressTeardown");

    private AperturePassthroughLever() {}
}
