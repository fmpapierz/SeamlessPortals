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
 *   <li>{@link #TEARDOWN_TEST} — {@code -Dseamlessportals.apertureTeardownTest=true}. Arms the
 *       end-to-end regression leg. Its meaning INVERTS across step 3: before IP-core edit 3 it
 *       reported "TEARDOWN CONFIRMED", and after it must report "NO TEARDOWN".</li>
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

    // RETIRED AT STEP 3: apertureSuppressTeardown. It existed only to make the seam observable
    // before the real fix existed — it relaxed the opening-contents half of the integrity predicate
    // so a /setblock into the aperture would not kill the portal within 233 ticks. IP-core edit 3
    // now makes that predicate frame-only outright, so the diagnostic is subsumed by the shipped
    // behaviour and keeping it would be a second, redundant path to the same state.

    /**
     * Arms the RS-TEARDOWN-TEST gametest leg ({@code -Dseamlessportals.apertureTeardownTest=true}).
     *
     * <p>Settles a claim that was asserted from source reading but NEVER OBSERVED: that putting a
     * block into a lit portal's opening actually breaks the portal. Every probe run so far reported
     * {@code intact=true} only, because the suite never places anything in a real aperture — the
     * failure path has never once fired. The user reports not seeing a portal break, which is
     * evidence against the reading. The leg builds its OWN ignited nether portal, drops a rail into
     * a mid-height opening cell with the teardown suppressor OFF, waits past the 233-tick sweep, and
     * reports whether the portal entity survived.
     */
    public static final boolean TEARDOWN_TEST =
        Boolean.getBoolean("seamlessportals.apertureTeardownTest");

    // =============================================================================================
    // STEP 0 — THE (a) FIX LEVERS. All DEFAULT-ON; each names the thing it DISABLES, per the house
    // rule "every fix DEFAULT-ON behind -Dseamlessportals.disableX"
    // (migration/REDSTONE_PASSTHROUGH_PROMPT.md §4). Every one has a -P row in BOTH
    // fabric/build.gradle blocks.
    //
    // SPEC DEVIATION, DELIBERATE: REDSTONE_A_SPEC.md §7 step 0 says to retire SUPPRESS_TEARDOWN and
    // TEARDOWN_TEST at step 0. SUPPRESS_TEARDOWN could not go then — it was still READ by
    // NetherPortalEntity until IP-core edit #3 — so it was deferred, and is now retired here at
    // step 3 where the frame-only predicate subsumes it.
    // TEARDOWN_TEST is retained PERMANENTLY, and its purpose inverts across this step: before edit 3
    // its leg reported "TEARDOWN CONFIRMED", and from here it must report "NO TEARDOWN". That makes
    // it the end-to-end regression proof that (a) works, so deleting it would throw away the only
    // whole-feature assertion we have.
    // =============================================================================================

    /**
     * MASTER OFF-SWITCH for the whole of sub-feature (a) —
     * {@code -Dseamlessportals.disableAperturePassthrough=true}. With this set every (a) behaviour
     * reverts to stock IP: the placeholder is non-replaceable again, the integrity predicate reads
     * the opening again, ignition demands bare air, and no mirroring happens. It is the single
     * A/B-attribution lever for the entire feature.
     */
    public static final boolean DISABLED =
        Boolean.getBoolean("seamlessportals.disableAperturePassthrough");

    /**
     * Disables ONLY the cross-seam mirror while leaving placement and the frame-only integrity
     * predicate intact — {@code -Dseamlessportals.disableSeamMirror=true}. Isolates "can a block
     * exist in the aperture" from "does it appear on the other side", which are the two independent
     * halves of (a) and fail for entirely different reasons.
     */
    public static final boolean DISABLE_SEAM_MIRROR =
        Boolean.getBoolean("seamlessportals.disableSeamMirror");

    /**
     * Disables the seam TARGETING fix — {@code -Dseamlessportals.disableSeamTargeting=true}.
     *
     * <p>With the fix on, a crosshair resting on a seam cell that holds a REAL block targets that
     * block locally instead of being rerouted through the portal. With it off, stock IP applies and
     * blocks aimed at the far half of an aperture cell's top face land in the destination dimension.
     * Separate from the master lever so the targeting change can be A/B-attributed on its own —
     * it is the only part of (a) that alters how the crosshair behaves, and it sits next to
     * cross-portal block interaction, which must keep working.
     */
    public static final boolean DISABLE_SEAM_TARGETING =
        Boolean.getBoolean("seamlessportals.disableSeamTargeting");

    /**
     * Disables FRAME mirroring only — {@code -Dseamlessportals.disableFrameMirror=true}. Frame
     * mirroring (breaking obsidian on one side breaks the other; repairing one repairs the other) is
     * separable from aperture mirroring and reaches outside the opening, so it gets its own lever:
     * a player who wants blocks to cross the seam but wants frames to stay independent can have that.
     */
    public static final boolean DISABLE_FRAME_MIRROR =
        Boolean.getBoolean("seamlessportals.disableFrameMirror");

    /**
     * Reverts the ignition aperture rule to stock "every cell must be air" —
     * {@code -Dseamlessportals.disableIgnitionWhitelist=true}. Attribution lever for the confirmed
     * §4.1 rule (rails/redstone always admitted; a support cube admitted only when the cell above it
     * holds a whitelisted block; frame must stay at least half air).
     */
    public static final boolean DISABLE_IGNITION_WHITELIST =
        Boolean.getBoolean("seamlessportals.disableIgnitionWhitelist");

    /**
     * Restores stock behaviour in the portal-generation clear loops, which wipe the aperture before
     * filling it with placeholders — {@code -Dseamlessportals.disableSurvivorSkip=true}. With the
     * fix ON those loops skip surviving passthrough/support blocks, which is what lets a frame be
     * re-lit over an existing rail line.
     */
    public static final boolean DISABLE_SURVIVOR_SKIP =
        Boolean.getBoolean("seamlessportals.disableSurvivorSkip");

    // =============================================================================================
    // STEP 0 — THE (a) PROBE LEVERS. All DEFAULT-OFF.
    // =============================================================================================

    /**
     * Dumps the seam mapping for every bound aperture column — the source cell, the mirror cell, the
     * destination dimension and the derived block rotation ({@code -Dseamlessportals.seamMapProbe=true}).
     * The step-1 gate: its output is hand-checked against a live portal before anything consumes it.
     */
    public static final boolean SEAM_MAP_PROBE =
        Boolean.getBoolean("seamlessportals.seamMapProbe");

    /** Registry seeding/teardown accounting — bindings added and removed, and by which signal. */
    public static final boolean SEAM_RECONCILE_PROBE =
        Boolean.getBoolean("seamlessportals.seamReconcileProbe");

    /**
     * THE STEP-3 GATE, and the highest-value probe in the feature. Logs, per aim frame, whether the
     * crosshair resolved to a local aperture cell or was rerouted through the portal into the
     * destination world. The (a) design panel's two adversarial verifiers reasoned from IDENTICAL
     * geometry to OPPOSITE conclusions about this, and NEITHER observed it; the failure mode is
     * silent and wrong-dimensional rather than a crash, and it lands on the most common gesture the
     * feature exists to support. 1 Hz-latched — it reads on the render thread, where per-frame log4j
     * costs ~130 ms stalls.
     */
    public static final boolean SEAM_AIM_PROBE =
        Boolean.getBoolean("seamlessportals.seamAimProbe");

    /** Mirror write accounting — enqueues, flushes, refusals, and the recursion guard's trips. */
    public static final boolean SEAM_MIRROR_PROBE =
        Boolean.getBoolean("seamlessportals.seamMirrorProbe");

    /** Pending-clear journal accounting, for breaks that outlive an unloaded destination. */
    public static final boolean SEAM_LEDGER_PROBE =
        Boolean.getBoolean("seamlessportals.seamLedgerProbe");

    private AperturePassthroughLever() {}
}
