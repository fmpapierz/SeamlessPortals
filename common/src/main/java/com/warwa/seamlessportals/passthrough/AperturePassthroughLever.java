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
     * THE SEAM CLIP — ★ DEFAULT OFF BY USER DECISION, 2026-07-27 (live round after the landing).
     *
     * <p>The clip cuts a seam block at its portal plane in every view: qualifying cells are
     * excluded from compiled section meshes (reported as AIR during compile, which also un-culls
     * neighbour faces) and re-drawn dynamically each pass with a per-cell
     * {@code gl_ClipDistance} plane keeping the CAMERA-side half. It landed gated and
     * pixel-proven (12-run matrix green; `rsSeamClipGate`), but the user tested the walk-around
     * live and DECLINED the view-dependent doorway semantics: the kept half necessarily swaps
     * when the camera crosses the plane's lateral extension (arc-verified, commit {@code 7c57241}
     * — inherent to one clip plane per draw, not a defect). The chosen direction instead is the
     * FULL FRACTIONAL MODEL (recon §0.9: each dimension holds a genuine partial block — real
     * geometry, collision and state ending at the plane — which retires both the invisible-solid
     * far half AND the crossing pop). The clip machinery is kept intact as that model's RENDERER.
     *
     * <p>★ THE RENDER FLIP — 2026-08-03, the LAST step of the user's fractional sequencing
     * ("gate → storage → collision → render flip LAST"). The 2026-07-27 default-OFF decision was
     * about the CAMERA-derived clip above; under the OWNER-HALF model the clip machinery became
     * the fractional model's renderer, occupancy-driven and view-independent, and every live
     * verification round since ran it via {@code -PenableSeamClip}. Round 15 proved what default
     * OFF means once the model is on: state, collision, outline and breaking all correct while
     * EVERY seam cell paints vanilla's whole cube (the user's "full continuous block" on both
     * empty sides — the renderer was simply idle, {@code cellsDrawn=0}). So the default now
     * follows the model itself:
     * <ul>
     *   <li>{@code -PdisableSeamClip} → force OFF (the A/B row);</li>
     *   <li>{@code -PenableSeamClip} → force ON (the legacy camera-clip row, works with the
     *       fractional model off);</li>
     *   <li>otherwise → ON exactly when the fractional model is on (the
     *       {@code disableSeamFractional} sysprop is read directly here rather than via
     *       {@code SeamFractional.active()} — same truth, no cross-class static-init edge).</li>
     * </ul>
     * Every consumer reads THIS field; self-gates OFF under sodium regardless (no meshing hook).
     * Design + panel record: {@code migration/SEAM_CLIP_DESIGN.md}.
     */
    public static final boolean DISABLE_SEAM_CLIP =
        Boolean.getBoolean("seamlessportals.disableSeamClip")
            || (!Boolean.getBoolean("seamlessportals.enableSeamClip")
                && Boolean.getBoolean("seamlessportals.disableSeamFractional"));

    /**
     * Per-frame seam-clip probe ({@code -Dseamlessportals.seamClipProbe=true}, DEFAULT-OFF):
     * 1 Hz-latched {@code [SEAM CLIP]} counter line — cells excluded/drawn, draws issued,
     * own-plane dest draws, recompiles scheduled ({@code SeamClipRenderer.counters()}).
     */
    public static final boolean SEAM_CLIP_PROBE =
        Boolean.getBoolean("seamlessportals.seamClipProbe");

    // =============================================================================================
    // SUB-FEATURE (c) — REDSTONE SIGNAL ACROSS THE SEAM. Fix levers DEFAULT-ON, probe DEFAULT-OFF,
    // every one with a -P row in BOTH fabric/build.gradle blocks. Spec: migration/REDSTONE_C_SPEC.md.
    // =============================================================================================

    /**
     * MASTER OFF-SWITCH for sub-feature (c) — {@code -Dseamlessportals.disableSeamSignal=true}.
     * With this set no signal crosses the seam: the neighbor-signal union never consults the far
     * side, the powered-rail walk stops at the plane, and no cross-seam dispatch is queued —
     * exactly the (b)-era behaviour where the mirrored half shows powered but propagation dies at
     * the seam. The single A/B attribution lever for the whole of (c).
     */
    public static final boolean DISABLE_SEAM_SIGNAL =
        Boolean.getBoolean("seamlessportals.disableSeamSignal");

    /**
     * Disables cross-seam UPDATE DISPATCH while leaving cross-seam READS working —
     * {@code -Dseamlessportals.disableSeamSignalDispatch=true}. With it set the far side can SEE
     * power through the bridge but is never TOLD to look: a far circuit goes stale until something
     * else touches it. "Can the far side see power" and "is it told to re-evaluate" are the two
     * independent halves of (c), split for attribution exactly as (b) split read from write.
     */
    public static final boolean DISABLE_SEAM_SIGNAL_DISPATCH =
        Boolean.getBoolean("seamlessportals.disableSeamSignalDispatch");

    /**
     * Disables the WALK-SEVER fix (RS-XTALK, 2026-08-22) —
     * {@code -Dseamlessportals.disableSeamWalkSever=true}.
     *
     * <p>With the fix ON (default), a powered-rail walk step that LEAVES a bound seam cell
     * through the plane never consults the raw stepped cell: in raw per-dimension coordinates
     * that cell holds the OTHER stitching (the opposite through-path), and the walk either
     * redirects into the far level or, when the far side is unresolvable (cold chunk), answers
     * false with a warm-up retry queued — never the raw read. With it OFF, the pre-fix
     * LOCAL-FIRST order returns and the 2026-08-22 live defect reproduces: two opposite
     * through-paths sharing a seam cell exchange power, because the unpowered path's walk
     * continues raw through the shared slot into the powered path's approach and finds its
     * power source within the 8-rail cap ((c)'s "purely additive" premise assumed nothing
     * conductive raw-behind the plane, which the mirror's two-sided writes made false).
     */
    public static final boolean DISABLE_SEAM_WALK_SEVER =
        Boolean.getBoolean("seamlessportals.disableSeamWalkSever");

    /**
     * Disables the STALE-PROVENANCE fix — {@code -Dseamlessportals.disableSeamBreakUnmark=true}.
     *
     * <p>With the fix ON (default), breaking a seam cell clears that cell's OWN mirror-created
     * mark (the clear path only ever removed the counterpart's). With it OFF, the 2026-07-28
     * live defect returns: after a place-from-far → break → re-place-from-near cycle the near
     * cell keeps a stale mark, and the authority rule suppresses the PLAYER'S OWN rail there —
     * dark at placement, deaf to its neighbors, "only placing on the other half works".
     */
    public static final boolean DISABLE_SEAM_BREAK_UNMARK =
        Boolean.getBoolean("seamlessportals.disableSeamBreakUnmark");

    /**
     * Disables the POWER-WAKE carve-out in the mirror-authority rule —
     * {@code -Dseamlessportals.disableSeamPowerWake=true}.
     *
     * <p>With the fix ON (default), a notification arriving at a provenance-marked (mirror-created)
     * rail or wire is still cancelled — the marked half never evaluates or writes in place (the
     * first build did, and looped ~500k same-drain iterations against the authority revert) — but
     * the poke is FORWARDED to the counterpart (the player half) through the tick-end dispatch
     * queue, which re-derives with the bridged reads and shape-syncs back. With it OFF, the (a)-era
     * full suppression returns and the LIVE defect of 2026-07-28 reproduces: signal entering a
     * coincident pair from the MIRROR half's side dies at the seam ("stops at the first half of the
     * seam rail"), half/side-dependently on provenance, with break-and-replace as the only
     * workaround.
     */
    public static final boolean DISABLE_SEAM_POWER_WAKE =
        Boolean.getBoolean("seamlessportals.disableSeamPowerWake");

    /**
     * MASTER OFF-SWITCH for (c) step 2 — redstone WIRE (dust) continuity across the seam —
     * {@code -Dseamlessportals.disableSeamWire=true}.
     *
     * <p>With the fix ON (default), dust participates in the stitched space (user ruling
     * 2026-08-10: full continuity, "the lit dust should also propagate down the stream"): the wire
     * evaluator's neighbour reads ({@code getIncomingWireSignal} — spec F11: raw
     * {@code getBlockState}+POWER, untouched by step 1's SignalGetter bridge), the connection-shape
     * reads ({@code getConnectingSide}), and the block-power intake
     * ({@code getBlockSignal → getBestNeighborSignal}) all see across the seam
     * ({@link SeamWireBridge}, {@code SeamSignalContinuity.neighborSignalStrengthAcross}); the
     * wire-side mirror-authority mixin suppresses self-derivation at marked cells with the same
     * poke-forward as rails. With it OFF, dust reverts to 2026-08-10-morning behaviour: stops dead
     * at the plane, ignores far power — and, at a mirrored seam cell, fights the authority revert
     * in the 1M-chained-update loop that stalled the server 42 s that evening (the wire authority
     * mixin also honours this lever, so OFF reproduces the loop for diagnosis; that is deliberate).
     */
    public static final boolean DISABLE_SEAM_WIRE =
        Boolean.getBoolean("seamlessportals.disableSeamWire");

    /**
     * Disables F8 half-scoping of seam redstone —
     * {@code -Dseamlessportals.disableSeamHalfScope=true}.
     *
     * <p>With the fix ON (default), a seam cell with a claimed primary half participates in
     * redstone per-half (user live finding 2026-08-11: "seam redstone powers too broadly —
     * source side A ... sends power to dest side A AND source side B"): raw reads of the cell
     * from its empty side see the Secondary or air; the cell's own reads use exactly one
     * candidate per seam-axis direction (owned side local, empty side far); the far six-scans
     * skip the counterpart's behind-plane neighbour; repeater/comparator raw wire reads gate the
     * same way. With it OFF, the 2026-08-11-morning leak reproduces. Cells with no claimed
     * primary (every command-staged fixture) are never affected either way. The ONE
     * implementation gate is {@code SeamFractional.emptyHalfDir}.
     */
    public static final boolean DISABLE_SEAM_HALF_SCOPE =
        Boolean.getBoolean("seamlessportals.disableSeamHalfScope");

    // RETIRED SAME-DAY (2026-07-27): disableSeamCrossPreference. A "crossing-preference" fix for
    // bi-faced cluster binding selection was implemented on the theory that the two twins' bindings
    // answer along-axis queries differently (180°-apart rotations) — and REFUTED by its own
    // inversion gate: flipped twins SHARE the portal transform, so blockRotationOf gives both the
    // same rotation and their continuations agree; first-match was never wrong. The adversarial
    // bi-faced repro (flipped twins spawned first) stays in the tree as the record.

    /**
     * Per-event signal probe ({@code -Dseamlessportals.seamSignalProbe=true}, DEFAULT-OFF): union
     * reads/hits, walk crossings, dispatch queue traffic and the budget counters —
     * {@code SeamSignalContinuity.counters()} printed by the gametest legs and on demand.
     */
    public static final boolean SEAM_SIGNAL_PROBE =
        Boolean.getBoolean("seamlessportals.seamSignalProbe");

    // =============================================================================================
    // SUB-FEATURE (d) — MINECART TRAVERSAL ACROSS THE SEAM. Probe DEFAULT-OFF; fix levers (when the
    // instrument round has decided the design) DEFAULT-ON, every one with a -P row in BOTH
    // fabric/build.gradle blocks.
    // =============================================================================================

    /**
     * MASTER OFF-SWITCH for sub-feature (d) — {@code -Dseamlessportals.disableSeamCartRail=true}.
     *
     * <p>With the fix ON (default), a minecart's rail resolution ({@code OldMinecartBehavior}'s
     * on-rails test, shape read and lane-snap lookahead, plus
     * {@code AbstractMinecart.getCurrentBlockPosOrRailBelow}) sees the far side's continuation
     * rail through the seam for the ONE cell past the plane, so the stranded tick between
     * crossing and teleport stays on rails at riding height. With it OFF, the measured 2026-07-28
     * defect returns on DISJOINT seams: the stranded tick {@code comeOffTrack}s at the behind-cell,
     * the teleport transfers the corrupted Y (arrival epsilon below the far rail's cell), and the
     * cart halts one block past the far plane, permanently off-rail beside a good rail.
     * COINCIDENT (obsidian) crossings work either way — measured clean stock; the lever's
     * observable inversion is the DISJOINT arm.
     *
     * <p>Note the bridge consumes {@link SeamShadowBridge#shadowFor}, so it also dies under (b)'s
     * {@code -PdisableSeamShadow} — same dependency the (c) walk has.
     */
    public static final boolean DISABLE_SEAM_CART_RAIL =
        Boolean.getBoolean("seamlessportals.disableSeamCartRail");

    /**
     * Restores the UNRESTRICTED (two-directional) seam view for cart rail resolution —
     * {@code -Dseamlessportals.disableSeamCartCrossOnly=true}.
     *
     * <p>With the narrowing ON (default), (d)'s bridge asks {@code SeamShadowBridge} for
     * {@code crossingOnly} shadows: only the canonical crossing direction
     * ({@code step == binding.crossDir()}) can answer. With it OFF, the COINCIDENT backward
     * fallback in {@code SeamBinding.continuationToward} — the far world's cell CO-LOCATED with
     * this side's approach, which (b)'s SHAPE resolver legitimately consults — is read as PHYSICAL
     * RAIL PRESENCE, and the pre-fix defect returns: on a coincident pair whose far side has an
     * approach rail but whose near approach is unrailed, a cart rolling out of the portal keeps
     * resolving "on rails" and LEVITATES one cell past the end of the track, indefinitely.
     *
     * <p>Found by the adversarial panel before commit (2026-07-28, two independent lenses), never
     * shipped. The lever exists so {@code rsCartLegPhantomRail} can reproduce it on demand — a
     * gate whose verdict does not invert cannot tell "the fix works" from "the defect never
     * existed here" (the house rule that has caught this engagement out before).
     */
    public static final boolean DISABLE_SEAM_CART_CROSS_ONLY =
        Boolean.getBoolean("seamlessportals.disableSeamCartCrossOnly");

    /**
     * Removes the STRADDLE TEST from (d)'s rail bridge —
     * {@code -Dseamlessportals.disableSeamCartStraddle=true}.
     *
     * <p>With the test ON (default), the bridge answers only for a cart whose own collision box
     * overlaps the seam cell — i.e. one physically ON the seam, mid-crossing. With it OFF, any
     * cart resolving the through-image cell gets the far world's rail, and the measured defect
     * returns: on a BI-FACED portal (every obsidian frame is a four-entity cluster, and each
     * face's own {@code crossDir} points the opposite way, so the direction narrowing alone
     * cannot help) a cart resting one cell clear of the aperture over open air HOVERS on the far
     * world's track instead of falling — measured at 0.038 blocks of drop in 60 ticks against
     * 1.100 with the test on.
     *
     * <p>Found by {@code rsCartLegPhantomRail} after the adversarial panel's direction finding was
     * already fixed — the panel named the family, the gate found the member that survived.
     */
    public static final boolean DISABLE_SEAM_CART_STRADDLE =
        Boolean.getBoolean("seamlessportals.disableSeamCartStraddle");

    /**
     * Restores the OLD (one-term) ridden-vehicle carry offset —
     * {@code -Dseamlessportals.disableSeamVehicleAttach=true}.
     *
     * <p>With the fix ON (default), {@code McHelper.getVehicleOffsetFromPassenger} returns the
     * true inverse of vanilla's rider placement,
     * {@code passengerVehicleAttach - vehiclePassengerAttach}. With it OFF, the pre-2026-07-28
     * behaviour returns: only the first term, so a ridden vehicle carried through a portal is
     * placed too HIGH by the vehicle's own passenger-attachment offset — 0.1875 for a minecart,
     * measured five out of five in the user's live round, both directions, cross-dim and
     * same-dim alike.
     *
     * <p>⚠ This lever spans SHARED vehicle machinery: the same helper carries boats, horses and
     * every other ridden vehicle across a portal, on both the client and server paths. It is
     * user-authorised (2026-07-28, "change shared vehicle-crossing machinery as part of (d) to
     * make it totally seamless") and lever-gated so the widening stays reversible and
     * A/B-attributable, per the rule that paid for commit {@code 7766010}.
     */
    public static final boolean DISABLE_SEAM_VEHICLE_ATTACH =
        Boolean.getBoolean("seamlessportals.disableSeamVehicleAttach");

    /**
     * RS (d) minecart-crossing instrument ({@code -Dseamlessportals.seamCartProbe=true},
     * DEFAULT-OFF): per-tick SAMPLE lines per watched cart, COME-OFF-TRACK event lines with the
     * failing resolution cell, teleport-path EVT lines (queued / skip reasons / run) from
     * {@code ServerTeleportationManager}, and per-hit bridge lines from
     * {@link SeamCartContinuity}. The recon §5.5 ordering experiment that decided the (d) design
     * ran under this lever (2026-07-28). See {@link SeamCartProbe}.
     */
    public static final boolean SEAM_CART_PROBE =
        Boolean.getBoolean("seamlessportals.seamCartProbe");

    /**
     * ENGINE STAGE 1 (SEAM_ENTITY_ENGINE_DESIGN §6): {@code -DseamResolver=shadow} computes the
     * engine's new-form verdicts alongside the legacy gates inside {@link SeamCrossingRule},
     * logging every divergence (volume-capped). A zero-divergence live lap gates each verdict
     * flip. Off = stage-0 behavior exactly.
     */
    public static final boolean SEAM_RESOLVER_SHADOW =
        "shadow".equals(System.getProperty("seamlessportals.seamResolver"));

    /**
     * ENGINE STAGE 2b (SEAM_ENTITY_ENGINE_DESIGN §2.5): the post-pass band painter.
     *
     * <p><b>★ DEFAULT-OFF SINCE ROUND 29 — THE POLARITY IS DELIBERATE AND EVIDENCE-BACKED.</b>
     * This lever was born as {@code disableSeamBandPainter} (default-ON, per the house rule that
     * every fix is default-ON behind a disable flag). The 2026-08-19/20 tint laps proved the
     * painter is not a fix but a net REGRESSION, so the polarity is inverted: it now runs only
     * when explicitly asked for.
     *
     * <p><b>The evidence (all user-confirmed live):</b> the painter authored THREE of the four
     * standing artifacts — the thin sliver at the plane (piece 1), the toward-crossing sliver
     * (piece 2), and the first-person own-head obstruction (piece 1 submits the real body with
     * {@code offset=ZERO} translated by {@code pos − camPos}; in first person {@code camPos} IS
     * the eye, so the model lands mathematically inside the player's head — and it bypasses BOTH
     * own-player guards, because vanilla's is an EXTRACTION gate while
     * {@code SeamBandPainter.drawPiece} calls {@code extractEntity} directly, and IP's
     * {@code shouldRenderEntityNow} never runs on the painter's own dispatcher). Disabling it
     * removed all three; the surviving BLUE bleed was then fixed properly by
     * {@link SeamCrossingRule#inPassProjectionSideAgrees}.
     *
     * <p><b>And what shipped was never §2.5's painter.</b> §2.5 specifies an exact slab,
     * stencil-intersected, with a mark pass and depth writes OFF, and explicitly marks the
     * half-space whole-piece redraw <b>[PROHIBITED]</b>. The implementation is precisely that
     * prohibited variant — unstenciled, depth-writes ON, no mark pass (its own probe line:
     * {@code BAND-GL depthFunc=0x206 depthMask=true stencil=false}). So §2.5's
     * "identical repaint ⇒ invisible by construction" claim was never falsified; it was never
     * the thing that ran. The painter is retained in-tree for that redesign, not for use.
     *
     * <p>Enable with {@code -PenableSeamBandPainter=true} (write {@code =true} explicitly — the
     * gradle rows compare against the string, so a BARE {@code -P} flag sets "" and does not
     * fire; here that fails SAFE).
     */
    public static final boolean ENABLE_SEAM_BAND_PAINTER =
        Boolean.getBoolean("seamlessportals.enableSeamBandPainter");

    /**
     * DIAGNOSTIC (verdict wf_8a9d68af-951 Part 4.1): the band painter's LANDING BEACON — pieces
     * drawn +2Y with clipping DISABLED and the gate widened to the whole crossing, so their
     * landing is visually undeniable. Beacon visible = the own-dispatcher immediate path paints
     * pixels (the zero-change cause is gating/coverage); beacon absent while the outcome probes
     * fire = fragment-level failure. One variable per lap: never read clip or gating conclusions
     * from a beacon lap.
     */
    public static final boolean SEAM_BAND_BEACON =
        Boolean.getBoolean("seamlessportals.seamBandBeacon");

    /**
     * DIAGNOSTIC (SEAM_BAND_HANDOFF §4.1 — the missing pixel-attribution instrument): PER-PAINTER
     * TINT ({@code -Dseamlessportals.seamPainterTint=true}, DEFAULT-OFF). Every vanilla fragment
     * shader gains a debug uniform through the SAME load-time injection triple as the clip plane
     * (ShaderCodeTransformation / ShaderManagerCompilationCacheMixin / GlCommandEncoderClipMixin),
     * and every seam painter bakes a distinct colour into the clip {@code Snapshot} that already
     * travels with its draws: main body RED, main-pass projection ORANGE, in-pass projection BLUE,
     * in-pass ambient content GREEN, band P1 MAGENTA, band P2 CYAN, seam-cell block redraw YELLOW.
     * One lap attributes every artifact pixel to its painter by colour — or, if an artifact pixel
     * carries NO tint, proves it is painted by none of them (portal quad / sodium terrain /
     * particles — the §5 painter-inventory discriminator). With the lever off the shader sources
     * are byte-identical and every tint field is inert. See
     * {@link com.warwa.seamlessportals.render.SeamTint}.
     */
    public static final boolean SEAM_PAINTER_TINT =
        Boolean.getBoolean("seamlessportals.seamPainterTint");

    /**
     * ENGINE §1.3 verdict (b) — rollback lever for the in-pass projection SIDE AGREEMENT gate
     * ({@code -Dseamlessportals.disableSeamInPassSideAgreement=true}), round 29's fix for the
     * one artifact that survived the 2026-08-19 tint laps: the BLUE cross-twin bleed in both
     * crossing directions. With the fix ON (default), an in-pass seam projection whose clip
     * keeps the half-space OPPOSITE to the one the pass's armed clip shows is culled — it could
     * only ever paint into the region the pass deletes for its own terrain. With it OFF, the
     * measured pre-round-29 behavior returns exactly: 9214 of 9278 in-pass seam projections
     * threading one co-located twin's clip into the other twin's pass.
     *
     * <p>See {@link SeamCrossingRule#inPassProjectionSideAgrees} for the derivation, the
     * identity-free co-planarity scope, and the measured evidence.
     */
    public static final boolean DISABLE_SEAM_INPASS_SIDE_AGREEMENT =
        Boolean.getBoolean("seamlessportals.disableSeamInPassSideAgreement");

    /**
     * ★ ROUND 30 — rollback lever for THE VISUAL SWEEP, the tail-clip fix
     * ({@code -Dseamlessportals.disableSeamVisualSweep=true}).
     *
     * <p>With the fix ON (default), the DRAW-side existence predicates and the crossing's CLOSE
     * guard evaluate on the SWEEP of the last-tick and post-tick boxes — the exact region the
     * interpolated visual can occupy — instead of the post-tick box alone. With it OFF, the
     * measured defect returns: at a crossing speed of 0.489 blocks/tick the post-tick box leads
     * the rendered visual by half a block, so on the tick the box clears the plane every
     * projection stops while up to <b>0.285 blocks</b> of the RENDERED body is still behind it,
     * unpainted — the trailing ~25–29% of the cart+cow vanishes for that tick's frames.
     *
     * <p>This is the 29-round "tail-end sliver", finally attributed: it was never the
     * ±ADJUSTMENT band. It is ONE TICK OF MOVEMENT, which is why it scaled with crossing speed,
     * why it appeared in both directions, why it snapped in one frame, and why no epsilon
     * arithmetic ever touched it. Evidence: the per-painter tint showed the vanishing piece was
     * the main-pass projection (away) and the in-pass projection (toward) — a painter DROPPING
     * OUT, not a clip plane cutting; the probe log then put every one of 14 dropouts at
     * 0.400–0.510 blocks/tick with {@code ANCHOR-CLOSE} on the same tick.
     *
     * <p>See {@link SeamStraddleBracket#backPieceExists} for the arithmetic and the scope
     * discipline (booking, pruning and collision deliberately stay on the post-tick box).
     */
    public static final boolean DISABLE_SEAM_VISUAL_SWEEP =
        Boolean.getBoolean("seamlessportals.disableSeamVisualSweep");

    /**
     * ★ ROUND 31 — rollback lever for THE MODEL MARGIN, the residual rider-clip fix
     * ({@code -Dseamlessportals.disableSeamModelMargin=true}).
     *
     * <p>With the fix ON (default), the draw-side existence predicates widen the collision box by
     * a normal-projected margin before testing it against the seam plane, because the clip is a
     * hardware plane on the drawn MODEL while the predicates measure the BOX. With it OFF, the
     * measured defect returns: the cow's muzzle overhangs its box by 0.4875 blocks and its rump
     * by 0.175 (versus the minecart shell's 0.135), so at 0.4 blocks/tick the cow's leading
     * overhang spans 1.22 tick steps and reliably loses one — the user's "front of the cow's face
     * cut off" and "tail end cut off", which are the same defect on opposite edges. See
     * {@link SeamStraddleBracket} for the geometry, the yaw-independence argument, and the
     * consumer-by-consumer safety case.
     */
    public static final boolean DISABLE_SEAM_MODEL_MARGIN =
        Boolean.getBoolean("seamlessportals.disableSeamModelMargin");

    /**
     * ★ ROUND 31 (D2) — rollback lever for THE PROJECTION CAMERA DISTANCE, the vanishing-shadow
     * fix ({@code -Dseamlessportals.disableSeamProjectionCameraDistance=true}).
     *
     * <p>With the fix ON (default), a projected entity's {@code distanceToCameraSq} is measured to
     * the position it is actually DRAWN at. With it OFF, the measured defect returns: on a
     * ~691-block seam the stamped value is ~4.8e5 against vanilla's shadow threshold of 256, so
     * no projected entity ever gets a shadow — the user's "the ENTIRE shadow disappears when it
     * touches the seam and reappears when it exits".
     *
     * <p>⚠ SCOPE NOTE: {@code distanceToCameraSq} also drives vanilla's NAME-TAG distance gates
     * (64 blocks, and 10 for the sneaking case), so this fix additionally makes name tags behave
     * correctly on projected entities. That is a deliberate widening of the same correctness
     * rule — the distance a viewer perceives is the distance to what is drawn — and the lever
     * reverts both together.
     */
    public static final boolean DISABLE_SEAM_PROJECTION_CAMERA_DISTANCE =
        Boolean.getBoolean("seamlessportals.disableSeamProjectionCameraDistance");

    /**
     * ★ ROUND 35 — rollback lever for THE RENDER-SIDE BOOKING SUPPLEMENT, the face-cut fix
     * ({@code -Dseamlessportals.disableSeamRenderBooking=true}).
     *
     * <p>With the fix ON (default), the projection painter's candidate list is supplemented with
     * seam faces whose plane the entity's DRAWN model straddles but which the physics booking has
     * not yet acquired. With it OFF, the measured defect returns: booking fires at ~0.71 blocks
     * from the plane while a cow's muzzle crosses at 0.9375, leaving ~0.23 blocks of travel in
     * which the emerged muzzle has no painter at all — a few frames at crossing speed, ~31 at a
     * crawl. Render-only: {@code PortalCollisionHandler} is never written, so physics, teleport
     * timing and collision are unaffected either way.
     *
     * <p>See {@link SeamCrossingRule#mustBook} for the measurement and for why the round-31/34
     * model margin could never have fixed this (it reaches only retention and cull predicates —
     * none of which can create a booking).
     */
    /**
     * ⚠ ROUND 35 POSTSCRIPT — POLARITY INVERTED TO DEFAULT-OFF. The supplement was built on the
     * booking mechanism and that mechanism is now <b>REFUTED BY DIRECT TEST</b>: with the
     * supplement live it fired 3426 times over 9 crossings and produced real draws (main-pass
     * projection draws rose to 16922, coverage extended from ~0.7 out to 2.75 blocks), and the
     * face cut was <b>unchanged</b>. Admission is therefore genuinely innocent — the painter is
     * invoked and drawing throughout the window the mechanism blamed.
     *
     * <p>It is also EXPENSIVE: it iterates every rendered entity against every nearby portal, and
     * the probe measured spikes of <b>14,820 draws in a single tick</b> against a 2-6 baseline.
     * A change that fixes nothing and costs that does not stay on. Retained behind
     * {@code -PenableSeamRenderBooking=true} for A/B only.
     *
     * <p>What the refutation leaves: the projection DRAWS every tick while the user observes the
     * orange painter FLICKERING on and off between consecutive frames at essentially the same
     * entity position. A per-frame flicker with a per-tick-constant draw count means the
     * fragments are being discarded downstream — depth or clip — by something that varies with
     * the CAMERA, not with the entity. That is the "depth wall" the original 27-round band arc
     * died on, and the standing handoff's conclusion applies: only pixel-level attribution
     * (RenderDoc frame capture) can carry it further.
     */
    public static final boolean DISABLE_SEAM_RENDER_BOOKING =
        !Boolean.getBoolean("seamlessportals.enableSeamRenderBooking");

    /**
     * ★ ROUND 35 — rollback lever for THE RENDER ENVELOPE
     * ({@code -Dseamlessportals.disableSeamRenderEnvelope=true}).
     *
     * <p>With the fix ON (default), the seam's draw-side predicates measure a per-entity upper
     * bound on the DRAWN model — vanilla's own frustum-cull box, which is contractual for every
     * renderable entity including modded ones and carries Mojang's per-type widenings. With it
     * OFF, they fall back to the plain collision box and every predicate is once again blind to
     * the box-to-model gap, which differs per species (cow 0.9375 reach vs 0.45 box half;
     * minecart 0.625 vs 0.49).
     *
     * <p>This is what makes the seam fixes satisfy the user's requirement that they work for
     * "every single type of rider, entity, literally everything, not just cows" — the correction
     * is derived at runtime per entity rather than hardcoded from the test fixture.
     * See {@link SeamRenderExtent}.
     */
    public static final boolean DISABLE_SEAM_RENDER_ENVELOPE =
        Boolean.getBoolean("seamlessportals.disableSeamRenderEnvelope");

    /**
     * ★ CART CROSS-DIM SMOOTHNESS rollback lever
     * ({@code -PdisableSeamVisualCarryover} → {@code -Dseamlessportals.disableSeamVisualCarryover=true}).
     *
     * <p>With the fix ON (default), a seam-engaged entity removed from a client level stashes
     * its visual state, and the cross-dim crossing RPC applies it — transformed through the
     * crossing portal — onto the freshly recreated destination instance, so the on-screen path
     * is continuous through the dimension flip exactly as it already is same-dim (where the
     * entity object persists and the F6 rebase does this in place). With it OFF, the fresh
     * instance keeps its zero-history spawn visual: the measured freeze-blink-pop
     * (round-7 log 2026-08-24, {@code REBASE-SKIP … visual == server} on every cross-dim
     * crossing). See {@link SeamVisualCarryover}.
     */
    public static final boolean DISABLE_SEAM_VISUAL_CARRYOVER =
        Boolean.getBoolean("seamlessportals.disableSeamVisualCarryover");

    /**
     * ★ PROJECTION LIGHT rollback lever
     * ({@code -PdisableSeamProjectionLight} → {@code -Dseamlessportals.disableSeamProjectionLight=true}).
     *
     * <p>With the fix ON (default), a seam projection's light coords are sampled at the DRAWN
     * position in the projection's target level (scoped override around its extraction,
     * {@code CrossPortalEntityRenderer.projectionLightWorld}); with it OFF, vanilla samples the
     * entity's real position in its own level and the viewing dimension's lightmap
     * misinterprets them — cross-dim the back-half image renders near-black (nether sky=0 under
     * the overworld night lightmap; user report 2026-08-24 "source side becomes much darker").
     */
    public static final boolean DISABLE_SEAM_PROJECTION_LIGHT =
        Boolean.getBoolean("seamlessportals.disableSeamProjectionLight");

    /**
     * ★ ROUND 37 — rollback lever for THE ENTRY-LIFETIME MODEL EXTENT
     * ({@code -Dseamlessportals.disableSeamKeepModelExtent=true}).
     *
     * <p>With the fix ON (default), {@link SeamStraddleBracket#keeps} — the predicate deciding how
     * long a seam collision ENTRY survives the per-tick prune — measures the swept box plus the
     * per-entity render envelope, as every draw predicate already does. With it OFF, it reverts to
     * the raw post-tick collision box and the measured defect returns: the MINECART loses its
     * entry mid-crossing, drops out of {@code CrossPortalEntityRenderer.collidedEntities}
     * entirely, and is never visited by the projection loop — producing zero probe lines of any
     * kind and an unpainted cart at the destination, which reads as the rider's face being cut.
     */
    public static final boolean DISABLE_SEAM_KEEP_MODEL_EXTENT =
        Boolean.getBoolean("seamlessportals.disableSeamKeepModelExtent");

    /**
     * ★ ROUND 38 — PER-ENTITY TINT MODE ({@code -PseamTintPerEntity=true}, on top of
     * {@code -PseamPainterTint=true}; inert without it).
     *
     * <p>Switches {@link com.warwa.seamlessportals.render.SeamTint} from keying colour on the
     * PAINTER to keying it on the ENTITY, so one entity is a single flat hue across the seam. The
     * role palette cannot answer the open question because painter identity changes at the seam by
     * design — exactly where the defect appears — so a colour change there is produced by the
     * normal handoff and by a defect alike.
     */
    public static final boolean SEAM_TINT_PER_ENTITY =
        Boolean.getBoolean("seamlessportals.seamTintPerEntity");

    /**
     * ★ ROUND 41 — NEUTRAL TINT ({@code -PseamTintNeutral=true}, alongside -PseamPainterTint).
     *
     * <p>Keeps every PIPELINE side effect of the tint — fragment-shader rewriting, fresh Snapshot
     * instances per painter, the per-draw uniform upload — and removes only the visible colour.
     * Isolates "the tint's pipeline perturbation is what fixes the cut" from "the colours were
     * merely masking it", which the 2026-08-22 tint-off regression made the live question.
     */
    public static final boolean SEAM_TINT_NEUTRAL =
        Boolean.getBoolean("seamlessportals.seamTintNeutral");

    /**
     * ★ ROUND 39 — rollback lever for the ALL-FACE in-pass visibility verdict
     * ({@code -PdisableSeamAllFaceVisibility=true}).
     *
     * <p>With the fix ON (default), {@code CrossPortalEntityRenderer.shouldRenderEntityNow} judges
     * its seam verdict against EVERY straddled face in the collision handler. With it OFF, the
     * verdict is judged against {@code ip_getCollidingPortal()} — hard-wired to
     * {@code portalCollisions.get(0)} — and the measured defect returns: on a bi-faced seam
     * cluster the straddled face is often not first, the verdict comes back NOT_ENGAGED against
     * the wrong face, the real body draws vanilla inside the portal pass under the pass's ambient
     * clip, and that clip amputates the model at the window plane (user-visible as the rider's
     * nose being cut; the tint lap paints the animal GREEN at exactly that moment).
     */
    public static final boolean DISABLE_SEAM_ALL_FACE_VISIBILITY =
        Boolean.getBoolean("seamlessportals.disableSeamAllFaceVisibility");

    /**
     * DIAGNOSTIC (SEAM_BAND_HANDOFF §4.3 — the bias LADDER): the band painter's glDepthRange
     * forward bias, {@code -Dseamlessportals.seamBandBias=<double>}. DEFAULT 1e-5 = the round-26
     * tie-break value (round 27 proved it does NOT close the artifacts — kept as the incumbent so
     * an unset lever changes nothing). Ladder rungs 1e-3 / 1e-2 are DIAGNOSTIC ONLY, never
     * shippable (a large bias visibly pulls band fragments in front of genuinely nearer
     * occluders); if the tail sliver closes at some rung, the artifact pixels' stored-depth
     * deficit is bounded and the angle-scaled-geometry mechanism is confirmed in one lap.
     */
    public static final double SEAM_BAND_BIAS = parseSeamBandBias();

    private static double parseSeamBandBias() {
        String raw = System.getProperty("seamlessportals.seamBandBias");
        if (raw == null) {
            return 1.0e-5;
        }
        try {
            return Double.parseDouble(raw);
        }
        catch (NumberFormatException e) {
            return 1.0e-5;
        }
    }

    // ============================================================================================
    // STEP (e) — THE TWO 2026-07-28 CART DEFECTS: same-dim window rendering + cross-dim riding.
    // ============================================================================================

    /**
     * Restores the WRAP-AROUND section lookup on the dest-pass entity visibility gate —
     * {@code -Dseamlessportals.disableDestEntitySectionExact=true}.
     *
     * <p>With the fix ON (default), {@link
     * com.warwa.seamlessportals.mixin.client.LevelRendererEntityVisibilityMixin} resolves the
     * queried section by EXACT coordinates through {@code ImmPtlViewArea.rawGet} — the unbounded,
     * coord-pinned column map — which is the shape that mixin's own S15 comment already cites
     * ("IP's exact shape: rawGet + compiled != UNCOMPILED"). With it OFF, the pre-fix call returns:
     * {@code ViewArea.getRenderSectionAt(BlockPos)}, which on {@link
     * qouteall.imm_ptl.core.render.ImmPtlViewArea} applies {@code positiveModulo} into the CURRENT
     * PRESET array with no exact-node guard (the hazard ledgered in that file's own
     * {@code getRenderSection(long)} note: "getRenderSectionAt (BlockPos-keyed) shares the wrap
     * hazard — ledgered for the S20 audit, not changed here").
     *
     * <p>⚠ THE DEFECT IT CLOSES IS NOT MINECART-SPECIFIC AND NOT SEAM-SPECIFIC — it is
     * DISTANCE-dependent. The preset window is re-centred on the dest camera for CROSS-DIM only
     * ({@code SecondaryWorldRenderCore:663}, {@code if (!sharedState && viewArea != null)}, whose
     * comment explains that moving it same-dim would corrupt the main frame). So for a SAME-DIM
     * pair whose destination lies outside ±renderDistance chunks of the player, the gate is
     * answered by an unrelated section near the player — normally UNCOMPILED — and EVERY entity in
     * that window is culled. Window TERRAIN is unaffected because terrain discovery goes through
     * {@code ImmPtlViewArea.rawFetch}, which is unbounded. That is exactly the user's 2026-07-28
     * report: the cart vanishes as it crosses a 40 km same-dim seam while the terrain keeps drawing.
     *
     * <p>⚠ DO NOT "fix" this by adding the exact-node guard to {@code getRenderSectionAt} itself:
     * that returns null for an out-of-window query, the gate then answers false, and the cart stays
     * culled. The read has to go to the UNBOUNDED map, not merely be made honest about the bounded
     * one.
     */
    public static final boolean DISABLE_DEST_ENTITY_SECTION_EXACT =
        Boolean.getBoolean("seamlessportals.disableDestEntitySectionExact");

    /**
     * Restores the pre-fix behaviour at BOTH portal carry sites —
     * {@code -Dseamlessportals.disableCrossDimPositionCodecSync=true}.
     *
     * <p>Covers {@code ClientTeleportationManager.moveClientEntityAcrossDimension} (the CROSS-DIM
     * carry) and {@code McHelper.adjustVehicle} (the SAME-DIM carry). One rule, two sites: a
     * vehicle that has just been carried through a portal must not keep a relative-move base from
     * where it used to be. The same-dim site was missed by the first build precisely because a
     * same-dimension crossing never enters {@code moveClientEntityAcrossDimension} — the
     * {@code fromDimension != toDimension} gate skips it — so the identical defect survived there
     * and stranded the rider at an interpolated point 13,000 blocks along the line between the two
     * portal endpoints.
     *
     * <p>With the fix ON (default), an entity moved across dimensions on the client has its
     * relative-move base rebased ({@code Entity.syncPacketPositionCodec}) and its interpolation
     * cancelled. With it OFF, the measured 2026-08-01 defect returns: a player-ridden minecart
     * crossing into the nether is yanked to SOURCE-dimension coordinates by the first
     * {@code ClientboundMoveEntityPacket$Pos} after arrival (relative deltas decoded against a
     * stale {@code VecDeltaCodec} base), dragged back by the next absolute position sync, and
     * lerped across the 40,000-block gap in between — with the rider carried along and ultimately
     * stranded, escapable only by {@code /kill}.
     *
     * <p>⚠ SCOPE: this method carries every entity the client moves across a dimension, so the
     * rebase is not minecart-specific. It is nonetheless the narrow correct rule — an entity that
     * has just been teleported must not have a delta base from where it used to be — and vanilla
     * applies exactly this rule wherever it sets an absolute position from a packet.
     */
    public static final boolean DISABLE_CROSS_DIM_POSITION_CODEC_SYNC =
        Boolean.getBoolean("seamlessportals.disableCrossDimPositionCodecSync");

    /**
     * The (e) DEFECT-A instrument ({@code -Dseamlessportals.cartWindowProbe=true}, DEFAULT-OFF):
     * client-side, logs the dest-pass entity visibility gate. Emits a line only when the WRAP and
     * EXACT section lookups DISAGREE (the aliasing itself, with both resolved section nodes and
     * both mesh states), plus one summary line per second carrying the gate call/false/disagree
     * counts and the same-dim extract count. Rate-limited by construction — see
     * {@link com.warwa.seamlessportals.render.CartWindowProbe}; the 2026-07-28 live round caught a
     * probe emitting 96% of a 46k-line log, which is why every new instrument here states its
     * limiter.
     */
    public static final boolean CART_WINDOW_PROBE =
        Boolean.getBoolean("seamlessportals.cartWindowProbe");

    /**
     * The (e) DEFECT-B instrument ({@code -Dseamlessportals.seamRideProbe=true}, DEFAULT-OFF):
     * CLIENT-side ride trace across a cross-dim crossing. The existing {@link SeamCartProbe}
     * channel is entirely server-side and therefore structurally blind to the reported symptom
     * (forced dismount / player spazzing in place) — the fourth time this engagement has paid for
     * <em>assert the outcome on the side of the wire the user's eyes are on</em>.
     *
     * <p>Records: every client-side {@code Entity.removeVehicle} on the local player with its
     * cause, every {@code startRiding} write and its DISCARDED return value, the passenger-bearing
     * entity packets ({@code handleRemoveEntities} — which has no guard anywhere in this tree —
     * {@code handleAddEntity} and its IP guard, {@code handleSetEntityPassengersPacket}), and a
     * per-tick sample of the player/vehicle link for a bounded window around a dimension change.
     * Limiter: armed ONLY for {@link com.warwa.seamlessportals.passthrough.SeamRideProbe#WINDOW_TICKS}
     * ticks around a crossing, and only for the local player's own vehicle cluster.
     */
    public static final boolean SEAM_RIDE_PROBE =
        Boolean.getBoolean("seamlessportals.seamRideProbe");

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

    // ============================================================================================
    // THE FRACTIONAL SEAM MODEL — genuine partial blocks: geometry, collision and state ending at
    // the plane. Spec: migration/FRACTIONAL_DESIGN.md (user decisions 2026-08-02).
    // Sequencing (decision C): GATE -> STORAGE -> COLLISION -> render flip LAST. These levers are
    // declared with the GATE so it is lever-aware from its first run, before any fix exists.
    // ============================================================================================

    /**
     * MASTER OFF-SWITCH for the fractional seam model —
     * {@code -Dseamlessportals.disableSeamFractional=true}.
     *
     * <p>With the fix ON (default), a mirror-admitted seam cell's block is genuinely divided by the
     * portal plane: {@link SeamFractional#active()} answers true once the model is built and every
     * arm consults it. With it OFF, seam blocks are whole cubes in both dimensions — today's
     * behaviour, and the thing {@code rsSeamCollisionGate} currently asserts.
     *
     * <p>⚠ THIS LEVER IS DECLARED AHEAD OF ITS FIX, DELIBERATELY. The gate is built first
     * (decision C) and must be able to state which truth it is asserting from its very first run;
     * a gate whose expectation is hardcoded is "actively wrong in whichever configuration it was
     * not written for" (the house rule earned at {@code rsPlayerPlaceBracketGate}). Until front 3
     * lands, {@link SeamFractional#CUT_IMPLEMENTED} keeps {@code active()} false in BOTH lever
     * positions and the gate says so in its log line rather than passing vacuously and silently.
     */
    public static final boolean DISABLE_SEAM_FRACTIONAL =
        Boolean.getBoolean("seamlessportals.disableSeamFractional");

    /**
     * TIER (i) half of the model — movement, raytracing and block picking
     * ({@code -Dseamlessportals.disableSeamFractionalCollision=true}).
     *
     * <p>The 3-arg {@code BlockStateBase.getCollisionShape(level, pos, ctx)}
     * ({@code BlockBehaviour.java:669-671}) has NO cache branch even for an empty context, so the
     * entity-movement funnel ({@code BlockCollisions:93} → {@code EntityCollisionContext:64}),
     * {@code ClipContext.Block.COLLIDER} and block picking are all fully hookable and cannot be
     * defeated. Separate from {@link #DISABLE_SEAM_FRACTIONAL_SUPPORT} on purpose: it isolates
     * "does the player walk into the far half" from "does the world agree the block is partial",
     * which are different failures with different causes.
     */
    public static final boolean DISABLE_SEAM_FRACTIONAL_COLLISION =
        Boolean.getBoolean("seamlessportals.disableSeamFractionalCollision");

    /**
     * TIER (ii) half of the model — support, redstone conduction and suffocation
     * ({@code -Dseamlessportals.disableSeamFractionalSupport=true}).
     *
     * <p>These read the PER-BLOCKSTATE cache ({@code isFaceSturdy} :867-868,
     * {@code isCollisionShapeFullBlock} :871-872, 2-arg {@code getCollisionShape} :665-666), which
     * is built once against {@code EmptyBlockGetter}/{@code BlockPos.ZERO} and has no position slot
     * to vary over — so this half must intercept AHEAD of the {@code cache != null} ternary.
     *
     * <p>⚠ AND AT THE PREDICATE SEAM TOO. {@code isRedstoneConductor}/{@code isSuffocating} are
     * per-block {@code StatePredicate} fields carrying {@code (state, level, pos)}; ~34 vanilla
     * blocks override them, so intercepting only {@code isCollisionShapeFullBlock} silently misses
     * every one. {@code Blocks.SOUL_SAND} is the witness the gate uses: partial collision shape
     * (14/16), {@code getBlockSupportShape} overridden back to a full block, and both predicates
     * forced true — see {@code FRACTIONAL_DESIGN.md} §4a.
     */
    public static final boolean DISABLE_SEAM_FRACTIONAL_SUPPORT =
        Boolean.getBoolean("seamlessportals.disableSeamFractionalSupport");

    /**
     * ⚠ EXPERIMENT LEVER, DEFAULT-OFF AND DELIBERATELY NOT A FIX LEVER —
     * {@code -Dseamlessportals.seamSupportUnion=true}. Needs the user's word before it could ever
     * become the default; see {@code FRACTIONAL_DESIGN.md} §5.
     *
     * <p>User decision 2026-08-02 (B) was that a partial block reports PARTIAL everywhere gameplay
     * looks — so rails pop off a partial support block, and that is the shipped default. This lever
     * exposes the other defensible reading of "partial" AT A SEAM specifically: a COINCIDENT or
     * FRACTIONAL cell is, in {@link SeamMap}'s own words, "one physical slot seen from two sides",
     * so a rail laid across the seam rests on the UNION of the two halves — a whole cube — while
     * collision still genuinely ends at the plane.
     *
     * <p>That reading is not an invention: {@code Blocks.SOUL_SAND} ships exactly it (§4a). The
     * lever exists so the choice is made on one live run instead of on argument. For cells with no
     * counterpart (query-only bindings) partial reporting stands under BOTH readings and the rail
     * correctly pops.
     */
    public static final boolean SEAM_SUPPORT_UNION =
        Boolean.getBoolean("seamlessportals.seamSupportUnion");

    /**
     * The fractional-model instrument ({@code -Dseamlessportals.seamFractionalProbe=true},
     * DEFAULT-OFF): 1 Hz-latched per-frame summary, log prefix {@code [SEAM FRAC]} (distinct from
     * the clip's {@code [SEAM CLIP]} and the iris session's {@code IS5-SEAM}). Limiter: one latched
     * summary per second, never a per-query line — the (e) round lost a rare event to a shared
     * 400-line budget and collision queries are the highest-volume call site in the game.
     */
    public static final boolean SEAM_FRACTIONAL_PROBE =
        Boolean.getBoolean("seamlessportals.seamFractionalProbe");

    private AperturePassthroughLever() {}
}
