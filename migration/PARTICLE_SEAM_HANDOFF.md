# PARTICLE SEAM HANDOFF — rounds 27-46, USER-CONFIRMED CLEAN 2026-08-10 ("seams good" at 30a4ee5)

Worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`, branch
`redstone/passthrough`, tip `51b3eab` (round 38). Suite: `gradlew :fabric:runCrossingGametest
-PapertureCensusProbe=true -PapertureTeardownTest=true` (the fractional gates NEST inside the
teardown leg — without those levers the suite passes vacuously). Client: `gradlew :fabric:runClient`.
Probe lever for particle/seam logging: `-PseamFractionalProbe=true`.

## THE USER'S BINDING RULES (verbatim, non-negotiable)
1. "You cannot offset the particles from the torch position" — positions are sacred, ever.
2. "if the window is between player and PARTICLE, it does not show" — the window rule,
   PARTICLE-anchored (round 34 correction of my round-33 source-block misreading).
3. "the particles should be consumed by the seam teleport the same way half of blocks/torches
   are consumed ... the same delete + mirror thing for particles like we do with blocks" — the
   particle seam teleport is the user's own design (round 35).

## CURRENT LIVE STATE (user's last report, tip 51b3eab — WORSE than round 37)
- SMOKE bleeding locally AND "painting in the portal render from side b".
- TORCH FLAME particle bleeding (regressed — flames were clean in rounds 36–37).
- Particles NOT rendering on the correct far side (regressed — far side worked in rounds 35–37).

## PRIME SUSPECT (hypothesis — VERIFY WITH INSTRUMENTS, do not patch blind)
Round 37 made the teleport fire in OPEN aperture cells via: "particle beyond a binding's plane →
teleport via that binding". In a BI-FACED open cell there are two bindings with opposite fronts,
so EVERY particle is always beyond exactly one of them → the rule teleports every particle in any
open aperture cell EVERY TICK. Round 37 masked this because the round-35 driver
(`Particle.tick` RETURN inject) demonstrably did not fire for smoke (flame teleported, smoke did
not — live-observed). Round 38 moved the driver engine-side (`ParticleGroup.tickParticle`
redirect, virtual dispatch, catches everything) — which would have UNMASKED the ping-pong:
particles oscillating between levels every tick explains simultaneously (a) both types bleeding,
(b) smoke painted inside the portal render, (c) far side "missing" (it oscillates), (d) flames
regressing (they now also hit the open-cell rule in the cell above the torch).
FIX SHAPE IF CONFIRMED: teleport only on an actual CROSSING this tick — compare the previous
position's half (xo/yo/zo, available via IEParticle-style accessors) with the current half;
transition required. That gives hysteresis, kills ping-pong, and also answers "which binding"
unambiguously (the one whose front half was where the particle CAME FROM).

## FULL ATTEMPT LEDGER (what was tried, mechanism, user verdict)
- r27 `animateTick` camera-side suppression — "bleeds when a slow particle outlives the view
  change"; also would hide owned-half particles from side views. WRONG INVARIANT (camera).
- r28 position rule at `ParticleEngine.createParticle` (deep-empty spawn refusal) — killed ALL
  torch particles (spawns at cell centre = ON the plane, strict side test misassigned them).
- r29 plane-epsilon pass at spawn — neck flame (billboard ON the plane) bled constantly.
- r30 spawn SHIFT 0.06 + strict tick-cull (`Particle.tick` inject) — user: offset visible +
  quads still poke (billboard extent ~0.1 > 0.06).
- r31 shift 0.2 + dying band 0.12 — user REJECTED offsets outright (rule 1 above).
- r32 window rule, particle-anchored, via NEW @Redirect on the per-particle extract inside
  `QuadParticleGroup.extractRenderState` — "not working, same as before" (see r34 finding).
- r33 window rule re-anchored to SOURCE BLOCK (animateTick bracket + weak tag map) — "still
  bleeding / still half plume"; user corrected the anchor back to PARTICLE with emphasis.
- r34 finding: the r32/r33 redirect targeted the SAME INVOKE already owned by IP's S18
  world-filter `@WrapOperation` (`MixinQuadParticleGroup`) — moved the window rule INSIDE that
  wrap (one instruction, one owner); added symmetric-emptiness filter to the dest-pass
  `ip_extractIsolated` (S18 DOES render remote particles through windows — the earlier
  "mod-wide limitation" claim was WRONG). User: window rule works (item 2 of r33 verdicts).
- r35 THE PARTICLE SEAM TELEPORT (user design): `SeamParticleTeleport.maybeTeleport` re-tags
  particle level via new IEParticle mutating accessors (@Mutable on protected-final `level`),
  maps position via binding `mapDir` frame, remaps velocity; driver = `Particle.tick` RETURN.
  User: "particles paint to correct far side" ✓ but locals flicker.
- r36 THE BAND RULE: near-plane (|local−off|<0.12) particles hidden ONLY from empty-side
  viewers (billboard poke; crossers never flash — teleport runs in the moving tick). User:
  flames clean; smoke still bleeding + smoke going to local wrong side (not crossing).
- r37 open-aperture generalization (occupancy no longer the gate; per-binding beyond test;
  occupied cells keep material protection). User: smoke STILL not crossing (r35 driver never
  fired for smoke — the flame/smoke asymmetry was the tell), far flame orphan found.
- r38 engine-side driver (`ParticleGroup.tickParticle` redirect) + fire lifecycle
  classification (`FireBlock.tick` removeBlock→PLAYER_BREAK, setBlock→PLAYER_PLACE). User:
  EVERYTHING bleeding, far side broken — see PRIME SUSPECT.

## LIVE MACHINERY (files, all currently active at 51b3eab)
- `render/SeamParticleTeleport.java` — the teleport (r35 mechanics + r37 open-cell gate).
- `mixin/client/ParticleSeamTeleportMixin.java` — engine-side driver (r38): redirect of
  `Particle.tick()` inside `ParticleGroup.tickParticle`.
- `qouteall/.../particle/IEParticle.java` — accessor duck + r35 mutating half (level/x/y/z/
  xo/yo/zo/xd/yd/zd setters; `@Mutable` on level).
- `qouteall/.../particle/MixinQuadParticleGroup.java` — S18 world filter + r34 WINDOW RULE +
  r36 BAND RULE (all in the one wrap).
- `qouteall/.../particle/MixinParticleEngine.java` — S14.40 dest-extract corruption cancel +
  S18 `ip_extractIsolated` (world-filtered dest extract) + r34 symmetric-emptiness filter.
- `render/SeamParticleOcclusion.java` — window-rule segment test (camera→particle vs portal
  quads; per-tick portal cache).
- `passthrough/SeamFractional.java` — `positionInEmptyHalf` (strict), `particleSpawnPos`
  (deep-empty refusal only; plane-band passes as-is), `particleHiddenFromEmptySide` (band rule).
- Fire family: `FireBlockSeamBreakMixin` (checkBurnOut r28 + tick lifecycle r38),
  `BaseFireBlockSeamMixin` (entityInside by owned half), `FlintAndSteelSeamClaimMixin` (full
  placement bracket r29), fire canSurvive-on-own-half (r30).

## OPEN EVIDENCE QUESTIONS (answer these BEFORE any code change)
1. PING-PONG: arm `-PseamFractionalProbe=true`; `SeamParticleTeleport` logs each crossing.
   Live repro with a torch: if the log shows the same crossing repeatedly per second, the
   bidirectional open-cell rule is confirmed. (Add a per-particle teleport counter if needed.)
2. Did the r38 driver actually change smoke behaviour, or was r37's smoke miss something else?
   javap said `BaseAshSmokeParticle.tick` reaches the base via `invokespecial
   SingleQuadParticle.tick` (no declaration there → resolves to Particle.tick, which the r35
   mixin transformed) — the live evidence (flame yes, smoke no) CONTRADICTS that analysis.
   Resolve the contradiction with an instrument, not another reading: counter in the r35-style
   base inject vs counter in the engine-side redirect, per particle class.
3. Where exactly does the "painting in the portal render from side b" come from — the dest
   pass (`ip_extractIsolated` + inner clip) or main pass? DrawCallTrace/probe the dest extract
   counts per frame.
4. Does the r36 band rule interact with teleported particles (hidden the frame after arrival)?
5. Is `ParticleGroup.tickParticle` the ONLY per-particle tick path (26.2 may tick some groups
   elsewhere — ItemPickup/ElderGuardian bespoke groups are skipped in ip_extractIsolated but
   do they tick through the same funnel)?

## MEASURED ANSWERS (2026-08-05 instrumentation round — probes landed, NO fixes)

Instruments: `SeamParticleProbe` (TP/DRV/MAIN/DEST/CENSUS 1 Hz sections, all behind
`-PseamFractionalProbe=true`), `ParticleBaseTickProbeMixin` (r35-anchor counter),
measurement leg `rsSeamParticleMeasureLeg` in CrossingSmoke (cross-dim teardown fixture +
same-dim /portal pair, torch + deterministic open-cell smoke, asserts nothing). Two full
suite runs, both ALL LEGS PASS with the probes armed.

1. PING-PONG (Q1): REFUTED as a world flip-flop, CONFIRMED as a POSITION flap with a
   different mechanism. Cross-dim: 533-541 teleports/run, EVERY one a distinct particle's
   single crossing (maxLifetimeCrossings=1, pingPongers=0). Same-dim (/portal pair, the
   user's live construction): 2,435 teleports in ~35 s, ~9 particles crossing 7-9×/sec,
   max 39 crossings for one flame, 63 particles ≥10 crossings — and every logged crossing
   goes the SAME direction from the SAME source cell; there is never a return crossing.
2. ROOT CAUSE (bytecode-proven, 26.2 `Particle.move` offsets 136-152): `move()` ends with
   `setBoundingBox(bb.move(dx,dy,dz)); setLocationFromBoundingbox();` — x/y/z are
   RE-DERIVED FROM THE BOUNDING BOX every moving tick. The r35 teleport writes the
   x/y/z/xo/yo/zo FIELDS via IEParticle accessors and never touches `bb`, so a teleported
   particle renders at the far site for the remainder of that frame-window, then vanilla
   snaps it back to the source next tick. Same-dim: level unchanged → it immediately
   re-qualifies → teleports again (the observed flap). Cross-dim: level=dest but position
   snaps back to SOURCE coords → a zombie (dest-tagged at source coords), invisible to
   both passes (world filter kills it in main, frustum in dest) → "crossers never reach
   the far side". The r35 note "locals flicker" and the r36 band rule were treating the
   visible half of this flap all along; rounds changed WHICH particles enter the broken
   teleport (r37 open cells, r38 PortalParticle), never the flaw.
3. DRIVERS (Q2): the r35 base inject DID fire for smoke — live counters show
   baseTickReturns == engineRedirectTicks EXACTLY for Smoke/Flame/Ash/LargeSmoke every
   second (javap agrees: `BaseAshSmokeParticle.tick` super-chains into the transformed
   base). The ONLY class the r38 engine driver newly covers is PortalParticle (tick
   override, no super) — 208 open-cell crossings in run 1. The r37 "driver never fired
   for smoke" claim is dead; r35→r38 driver swap was coverage-neutral for flame+smoke.
4. WINDOW CONTENT (Q3): the "painting in the portal render" is billed to the dest pass
   (`ip_extractIsolated`), and cross-dim it is ~500/s of AshParticle/LargeSmokeParticle —
   NETHER BIOME AMBIENT spawned dest-tagged by `tickRemoteWorldRandomTicksClient` (which
   also ticks the whole engine a SECOND time per game tick, and animateTicks the remote
   world around the portal-transformed camera). Seam-adjacent far particles can NEVER
   render through the window: `RenderStates.shouldRenderParticle` applies
   `isOnDestinationSide(pos, 0.5)` — a 0.5-block dead zone past the plane (72k drops/run;
   FlameParticle was dest-extracted ZERO times across both runs). A mirrored torch's
   flame lives at the plane, inside the dead zone, forever.
5. BAND RULE (Q4): zero interaction — bandDrop-of-recently-teleported = 0 all runs.
6. FUNNEL (Q5): `ParticleGroup.tickParticle` is the jar's ONLY `Particle.tick()` call
   site (private, non-overridable; all four groups inherit). Side door: TrackingEmitter,
   ticked directly by `ParticleEngine.tick`, overrides tick without super (never hits the
   funnel or base inject; irrelevant to torches). Flag-ON the funnel runs ~2×/game tick
   when a remote world has a nearby portal (the remote `CLIENT.particleEngine.tick()`).

## ROUND 40 — THE FIX (landed, measured, user live-verify pending)

Two changes in `SeamParticleTeleport`, both dictated by the r39 measurements:
1. **The bounding box moves too**: position write is now `particle.setPos(nx, ny, nz)`
   (26.2 bytecode verified: sets x/y/z AND rebuilds the AABB from bbWidth/bbHeight, no
   other side effects). The r35 field-only writes are gone.
2. **OPEN cells teleport only on a genuine crossing this tick** (the handoff's prescribed
   hysteresis): previous-position half (xo/yo/zo, new IEParticle getters) vs current half
   must differ against the binding's plane, and the came-from half picks the binding
   (front == prevHalf). Without this, (1) alone oscillates every open-cell arrival at tick
   rate — an arrival is always "beyond" the counterpart's other binding, any rotation.
   Arrivals set xo/yo/zo = landing point → no transition → rest; later genuine
   re-crossings legitimately travel back. OWNED-cell logic unchanged (empty-half residents
   still consumed via the material's continuation — cannot loop: claimCrossingHalf derives
   dest-owned through the same mapDir as the position transform, so arrivals land in
   material and hit stayOwned). Probe gained `openRestingNoCrossing` (gate-rested
   residents, 10-35/s live).

MEASURED RESULT (run 3, same fixtures as the r39 runs, suite ALL LEGS PASS): lifetime
teleports 2,968 → 429; maxLifetimeCrossingsOneParticle 39 → 1; pingPongers 63 → 0;
same-dim teleports/sec ~70 → 3-5, every crossing line lifetimeCrossings=1; cross-dim
arrivals now genuinely persist at the counterpart (they were zombies at source coords
before). Every teleport in the run is a one-shot delete+continue — rule 3 as designed.

## ROUND 41 — far-side window visibility + the spawn-scatter bleed (user's live report)

User live-verified r40 on an exact pair: crossings clean, but (1) crossers invisible
through the window, (2) fire smoke resting on the wrong side. Their own armed session
measured both: DEST extract 19.6k shouldRenderDrops/s with extracted=0 (the 0.5 valve),
and windowDropByClass empty for LargeSmoke with openRestingNoCrossing 30-260/s (fire's
animateTick spawn-scatters some smoke past the plane at birth; xo==x so the r40 gate
rested it there). Two fixes:
1. `RenderStates.shouldRenderParticle` — the spatial valve relaxes to -0.12 (one
   billboard reach past the plane) for lattice-mirrorable portals ONLY
   (`SeamMap.isMirrorable`, single-entry per-portal cache); the armed hardware inner clip
   trims wrong-side fragments per-pixel. Non-seam portals keep IP's 0.5.
2. `SeamParticleTeleport` OPEN branch — spawn-scatter correction: a particle at age<=1
   found past a binding's plane with no transition crossed AT BIRTH (rule 3) and is
   consumed via that binding; age<=1 is true exactly once per particle (each funnel pass
   runs tick() first), so arrivals can never re-trigger it.
MEASURED (run 4, ALL LEGS PASS): window now extracts FlameParticle 20-78/s +
SmokeParticle 6-56/s during the torch phases (was 0 in every prior run);
openRestingNoCrossing fell to 0-10/s same-dim (was 15-32); crossings still strictly
one-shot (cross-dim 310, same-dim +91, max=1, pingPongers=0 lifetime).

Still noted: client-side dest occupancy sometimes reads 0 where the server says HALF_*
(r39 branch attribution); the transition gate makes this harmless for particles. The
2026-07-26 exact-only alignment policy silently declines non-lattice pairs (cost the user
two live rounds on 2026-08-05 — chat-notice chip pending their decision).

## ROUND 42 — THE STITCHED-SPACE BATCH (user-approved architecture A-E, 2026-08-10)

The user's confirmed contract (memory `particle-seam-failure-ledger`): source side A ∪ dest
side B are ONE real place; the mirrored half-torch EMITS its own flame/smoke at the dest;
NOTHING weaker than plane-exact clipping of particle geometry; fire placed on the seam and
mirrored SPREADS from the dest seam cell into dest blocks; all topologies. Landed:

A. `render/SeamDestAmbience` + END_CLIENT_TICK driver (flag-ON): display-ticks nearby
   mirrorable portals' SAME-DIM dest regions (dest-origin anchor, IECamera distance-gate
   defeat, %2 cadence, NO extra engine tick — double-aging hazard; skip dests within 16
   blocks of the player). Cross-dim stays IP's remote pass. MEASURED: AMB 10-11 passes/s;
   window extracted the far torch's OWN organic Flame 114-144/s + Smoke 28-52/s same-dim —
   structurally zero in every prior run.
B. `SecondaryWorldRenderCore`: re-arm the inner clip before BOTH renderAllFeatures draw
   sites — the submitEntities TAIL (CrossPortalEntityRenderer:171) disables the store
   UNCONDITIONALLY mid-pass (IP 1.21.3 semantics; on 26.2 the draws come later), so ALL
   portal-pass feature draws (entities/BEs/particles) ran unclipped. r41's valve is now
   honest (core/particle IS clip-injected).
C. Plane-exact quad clip: `render/SeamParticleQuadClip` + `QuadParticleRenderStateClipMixin`
   (add/clear/buildLayer/renderRotatedQuad hooks) — per-quad camera-relative seam plane
   side-channel recorded at the two extract sites, Sutherland-Hodgman in billboard parameter
   space at build time (affine plane function — exact), UV-lerped, pass-agnostic AND
   renderer-agnostic (CPU — works under sodium/iris). The r36 BAND RULE is RETIRED (it
   over-hid the owned-side portion; the clip cuts geometry AT the plane instead). MEASURED:
   141-342 quads/s clipped at the seam, 0 fully culled.
D. Dest fire lives: (D1) `SeamMirror.applyToDestination` schedules the initial fire tick
   after a mirrored FireBlock write (SKIP_ON_PLACE skips FireBlock.onPlace — vanilla's ONLY
   scheduler; `FireBlockInvoker.getFireTickDelay`); (D2) `ServerLevelFireSpreadMixin` gained
   the ENTITY-ERA watcher branch: a non-spectator player within the fire gamerule radius of
   a portal ENTRANCE counts as within it of the EXIT region (McHelper.findEntitiesRough on
   the live Portal registry; only ever ADDS true; -1 gamerule short-circuits).
E. `rsSeamFireAndLightGate` in the teardown nest: dest block-light >= 12 after a mirrored
   torch (NOTE: passed VACUOUSLY this run — nether baseline was already 14 from ambient;
   improve with a darker read point later); D1 hasScheduledTick assert; D2 OUTCOME assert —
   planks above the DEST fire ignited within ~12s with the player at the SOURCE end only
   (source fire re-placed on age-out: its burnout break-both-clears the mirror, by design).
   doFireTick enabled only inside the gate, restored in finally.

Suite: ALL LEGS PASS with everything live; teleports still strictly one-shot (cross-dim 324,
same-dim +168, max=1, pingPongers=0 — the new far-end population resurrected no oscillation).
KNOWN-OPEN: light gate weak on bright nether baselines; iris portal-pass hardware clip still
un-injected (CPU clip C covers particles); spread-arrived fire mirrors without half-claims
(vanilla whole-cell semantics, user-accepted default).

## RESEARCH PLAN FOR THE NEXT SESSION
1. INSTRUMENT FIRST: per-tick counters (teleports total + per particle-class, current level
   distribution of engine particles, dest-extract submissions) behind the probe lever; one
   live minute with a seam torch; read the numbers.
2. Decide the crossing-detection fix from evidence (expected: transition-based hysteresis via
   previous-position half; only teleport on genuine crossings; direction picks the binding).
3. Re-verify the four-layer contract afterwards: teleport (crossers), window rule
   (between-ness), band rule (billboard extent), dest-pass emptiness — each layer one job.
4. Consider a headless pixel gate for particles (goldFraction-style over emissive particle
   colors is unreliable; a counter-based gate on the teleport/occlusion probes is the
   realistic assertion).
5. House rules: javap before touching any vanilla signature (26.2 drift list in memory);
   check for an existing wrap on a target instruction BEFORE adding a redirect; suite +
   levers before commit; user live-verifies every round.
