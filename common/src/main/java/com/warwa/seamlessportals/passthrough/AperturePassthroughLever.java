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
     * Disables MIRROR AUTHORITY — {@code -Dseamlessportals.disableMirrorAuthority=true}.
     *
     * <p>With the fix ON, a cell whose occupant this level received from a mirror is not re-derived
     * or deleted by the destination: its shape and validity are the source cell's. With it OFF,
     * vanilla runs — which re-resolves the mirrored copy against destination neighbours (the two
     * halves then hold different shapes) and can delete it for lack of support at the destination,
     * dropping an item while the source block survives. That last path is an ITEM DUPLICATION route,
     * so this lever exists to DEMONSTRATE the defect, not as a supported configuration.
     */
    public static final boolean DISABLE_MIRROR_AUTHORITY =
        Boolean.getBoolean("seamlessportals.disableMirrorAuthority");

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

    /**
     * THE DELIVERY PROBE ({@code -Dseamlessportals.seamDeliveryProbe=true}). Traces every mirrored
     * write down the whole client-delivery chain and prints, per write, which stage it stopped at.
     *
     * <p>It exists because the same-dimension man-made-portal bug survived THREE fixes written from
     * three different theories, none of which measured anything. See {@link SeamDeliveryProbe} for
     * the four independent drop points it distinguishes. Retiring traces on a timer is what makes a
     * stage that NEVER RAN report {@code NOT-REACHED} instead of reading as a stage that ran and
     * answered — the exact class of false reading that misled this engagement five times.
     */
    public static final boolean SEAM_DELIVERY_PROBE =
        Boolean.getBoolean("seamlessportals.seamDeliveryProbe");

    /**
     * Arms the RS-DELIVERY-TEST leg ({@code -Dseamlessportals.seamDeliveryTest=true}).
     *
     * <p>The headless reproduction of the same-dimension bug. Every previous round on it went
     * through the user playing live, which is slow and — worse — gives one observation per build,
     * which is how three fixes shipped on three unfalsified theories. The leg writes into the
     * aperture of the harness's SAME-dimension test portal and its CROSS-dimension one, and reports
     * for each whether the mirror reached the CLIENT. Two portals, one differing in exactly the
     * property the user's three observations turn on, measured in the same run.
     *
     * <p>It reports rather than asserts, and states its own coverage: if the client does not hold
     * the destination chunk the leg says {@code INCONCLUSIVE} instead of reading an absent chunk's
     * {@code void_air} as a stale block. That specific false reading has already cost this
     * engagement one wrong conclusion.
     */
    public static final boolean SEAM_DELIVERY_TEST =
        Boolean.getBoolean("seamlessportals.seamDeliveryTest");

    /**
     * Disables SAME-DIMENSION PORTAL TERRAIN FRESHNESS —
     * {@code -Dseamlessportals.disableSameDimRemesh=true}.
     *
     * <p>With the fix ON (default), a section that changes behind a same-dimension portal is
     * scheduled for a rebuild, so the portal window updates live. With it OFF, stock behaviour
     * returns: the block reaches the client correctly and the picture never changes until the
     * player goes and looks at the region directly.
     *
     * <p>It exists to make the defect <b>demonstrable</b> rather than merely asserted — the
     * RS-DELIVERY-TEST inversion discipline. See {@code com.warwa.seamlessportals.render.SameDimRemesh}
     * for which two losses it closes and why the obvious repair was rejected.
     */
    public static final boolean DISABLE_SAME_DIM_REMESH =
        Boolean.getBoolean("seamlessportals.disableSameDimRemesh");

    /**
     * Restores mirroring for NON-PLAYER writes — {@code -Dseamlessportals.disableSeamPlayerOnly=true}.
     *
     * <p>With the fix ON (default), only a player placing or breaking mirrors; pistons, dispensers,
     * gravity, fluid spread, {@code /setblock} and {@code /fill} are classified and declined
     * ({@link SeamWriteSource}). With it OFF, every write mirrors regardless of origin — the
     * pre-2026-07-26 behaviour that {@code REDSTONE_RECON.md} §0.8 pinned as "accept best-effort".
     *
     * <p>User decision 2026-07-26. The lever exists because this NARROWS a previously pinned rule,
     * and a narrowing that cannot be reversed in one flag is a decision nobody can re-examine.
     */
    public static final boolean DISABLE_SEAM_PLAYER_ONLY =
        Boolean.getBoolean("seamlessportals.disableSeamPlayerOnly");

    /**
     * Restores mirroring for OFFSET (non-lattice-aligned) seams —
     * {@code -Dseamlessportals.disableSeamExactOnly=true}.
     *
     * <p>With the fix ON (default), a block mirrors only where the two sides line up exactly on the
     * block lattice ({@link SeamAlignment#EXACT}). With it OFF, offset seams mirror again through the
     * greatest-overlap cell {@code resolveDestCell} already produces.
     *
     * <p>User decision 2026-07-26 ("save offset support for future work"). ⚠ This REVERSES
     * {@code REDSTONE_RECON.md} §0.7, and commit {@code 7766010} exists because a design panel once
     * made the same change without the user's word. The lever is how the eventual offset
     * implementation gets A/B'd against today's behaviour.
     */
    public static final boolean DISABLE_SEAM_EXACT_ONLY =
        Boolean.getBoolean("seamlessportals.disableSeamExactOnly");

    /**
     * Disables SAME-FRAME MIRRORING — {@code -Dseamlessportals.disableSeamPrediction=true}.
     *
     * <p>With the fix ON (default), the mirrored half of a player's placement is predicted on the
     * client so both halves appear in the same frame, like vanilla placement. With it OFF, only the
     * player's own block is predicted and the mirrored half waits for the server's block-update
     * packet — the visible "first side places, other side follows a split second later" the user
     * reported.
     *
     * <p>Separate from {@link #DISABLE_SEAM_MIRROR} on purpose: this turns off the PREDICTION only.
     * The server still mirrors, so the lever isolates "does the mirror happen" from "does it happen
     * in time", which are different failures with different causes.
     */
    public static final boolean DISABLE_SEAM_PREDICTION =
        Boolean.getBoolean("seamlessportals.disableSeamPrediction");

    // =============================================================================================
    // SUB-FEATURE (b) — RAILS CONNECTING ACROSS THE SEAM. Fix levers DEFAULT-ON, probes DEFAULT-OFF,
    // every one with a -P row in BOTH fabric/build.gradle blocks.
    // =============================================================================================

    /**
     * MASTER OFF-SWITCH for sub-feature (b) — {@code -Dseamlessportals.disableSeamShadow=true}.
     * With this set no {@code SeamShadow} is ever created: rails resolve exactly as stock (a) —
     * blocks that mirror but never join, connect or carry across the plane. The single A/B
     * attribution lever for the whole of (b).
     */
    public static final boolean DISABLE_SEAM_SHADOW =
        Boolean.getBoolean("seamlessportals.disableSeamShadow");

    /**
     * Disables cross-seam rail WRITES while leaving cross-seam READS working —
     * {@code -Dseamlessportals.disableSeamRailWrite=true}. "Is the far rail seen" and "is it
     * rewritten to meet ours" are independent halves of (b) that fail for different reasons; this
     * lever separates them for attribution.
     */
    public static final boolean DISABLE_SEAM_RAIL_WRITE =
        Boolean.getBoolean("seamlessportals.disableSeamRailWrite");

    /**
     * Disables the cross-seam SLOPE SUPPORT bridge — {@code -Dseamlessportals.disableSeamRailSlope=true}.
     * With it set, a rail ascending into the seam reverts to vanilla support rules and pops, because
     * its supporting cube lives on the far side.
     */
    public static final boolean DISABLE_SEAM_RAIL_SLOPE =
        Boolean.getBoolean("seamlessportals.disableSeamRailSlope");

    /**
     * Disables the RESEED on portal bind — {@code -Dseamlessportals.disableSeamRailReseed=true}.
     * With it set, lighting a portal over an existing track no longer re-resolves the track's shape,
     * so rails laid before the portal existed keep their pre-portal shapes until touched.
     */
    public static final boolean DISABLE_SEAM_RAIL_RESEED =
        Boolean.getBoolean("seamlessportals.disableSeamRailReseed");

    /**
     * Disables the DISJOINT-phase mirror gate — {@code -Dseamlessportals.disableSeamPhaseGate=true}.
     *
     * <p>With the gate ON (default), a positively-classified boundary-phase (DISJOINT) seam is NOT
     * mirrored: its two aperture cells are distinct face-to-face blocks in two worlds, so mirroring
     * duplicates whole blocks, refuse-on-conflict blocks the player from laying track toward the far
     * side's own rail, and (b)'s far shape write mirrors back onto the source in a loop. COINCIDENT
     * (every obsidian frame — the geometry (a) was user-verified on) is untouched.
     *
     * <p>✅ USER-CONFIRMED 2026-07-27 (live round after the (b) landing): the gate stays. This
     * lever restores unconditional mirroring exactly, kept for A/B attribution.
     */
    public static final boolean DISABLE_SEAM_PHASE_GATE =
        Boolean.getBoolean("seamlessportals.disableSeamPhaseGate");

    /**
     * Reverts {@code SeamRegistry.findDestinationPortal} to first-positional-match —
     * {@code -Dseamlessportals.disableSeamReverseDisambig=true}. Demonstrates the bi-faced fail-open:
     * two coincident opposite-normal faces both qualify by position, and on a boundary-phase pair the
     * wrong face resolves the mirror target one cell off.
     */
    public static final boolean DISABLE_SEAM_REVERSE_DISAMBIG =
        Boolean.getBoolean("seamlessportals.disableSeamReverseDisambig");

    /**
     * Disables SHAPE SYNC — {@code -Dseamlessportals.disableSeamShapeSync=true}.
     *
     * <p>With the fix ON (default), a same-block STATE refinement of a seam cell re-mirrors even
     * when its write carries no player bracket, provided the counterpart already holds the same
     * block. Vanilla rail resolution rewrites neighbours directly ({@code RailState.connectTo:205}
     * runs inside the OTHER cell's placement), so without this the second rail laid next to a seam
     * rail re-shapes it un-bracketed, the player-only policy declines the re-mirror, and the two
     * halves of the pair diverge — a straight half and a curved half on one visual block. Creation
     * and removal still obey the player-only policy in full; only refinements of an existing pair
     * pass. ✅ USER-CONFIRMED 2026-07-27 (live round after the (b) landing) as a deliberate
     * widening of the 2026-07-26 player-only decision — this lever restores the strict reading,
     * kept for A/B attribution.
     */
    public static final boolean DISABLE_SEAM_SHAPE_SYNC =
        Boolean.getBoolean("seamlessportals.disableSeamShapeSync");

    /**
     * Per-resolution rail probe ({@code -Dseamlessportals.seamRailProbe=true}, DEFAULT-OFF): shadow
     * creation, per-slot local/cross reads, cross writes, reseeds, declines and the budget counters —
     * {@code SeamRailContinuity.counters()} printed by the gametest legs and on demand.
     */
    public static final boolean SEAM_RAIL_PROBE =
        Boolean.getBoolean("seamlessportals.seamRailProbe");

    /**
     * Disables the SEAM CLIP — {@code -Dseamlessportals.disableSeamClip=true}.
     *
     * <p>With the fix ON (default), a seam block is cut at its portal plane in EVERY view: its
     * qualifying cells are excluded from compiled section meshes (reported as AIR during compile,
     * which also un-culls neighbour faces) and re-drawn dynamically each pass with a per-cell
     * {@code gl_ClipDistance} plane keeping the CAMERA-side half, so the far half never draws in
     * the dimension that does not own it — from the side as well as through the window. The
     * mirrored copy supplies the far half through the stencil window exactly as before. OFF
     * restores the pre-clip behaviour (whole cube drawn from the section mesh; the far half
     * visible from the side). Design + adversarial-panel record:
     * {@code migration/SEAM_CLIP_DESIGN.md}. Self-gates OFF under sodium regardless of this lever
     * (no meshing hook — the exclusion arm cannot apply there).
     */
    public static final boolean DISABLE_SEAM_CLIP =
        Boolean.getBoolean("seamlessportals.disableSeamClip");

    /**
     * Per-frame seam-clip probe ({@code -Dseamlessportals.seamClipProbe=true}, DEFAULT-OFF):
     * 1 Hz-latched {@code [SEAM CLIP]} counter line — cells excluded/drawn, draws issued,
     * own-plane dest draws, recompiles scheduled ({@code SeamClipRenderer.counters()}).
     */
    public static final boolean SEAM_CLIP_PROBE =
        Boolean.getBoolean("seamlessportals.seamClipProbe");

    /**
     * RS-ONLY SUITE MODE ({@code -Dseamlessportals.rsOnly=true}, DEFAULT-OFF) — the recorded
     * proposal from 2026-07-26: the user has flagged the suite as slow, and the RS gates are a small
     * fraction of each run. With this set the crossing/teleport legs (thrown items, hurt cow, ender
     * pearl, far-dest leg 7, and leg 5's world close-and-reopen) are SKIPPED; portal staging, legs
     * 6a/6b (which produce the bi-way pairs the seam-map gate's involution coverage requires), every
     * RS gate and the rail legs still run, and the run still ends with the same ALL LEGS PASS line.
     * A full run remains the default and is required before a commit.
     */
    public static final boolean RS_ONLY =
        Boolean.getBoolean("seamlessportals.rsOnly");

    private AperturePassthroughLever() {}
}
