# SEAM FUNCTIONALITY HANDOFF — state as of 2026-08-10, USER-CONFIRMED CLEAN ("seams good")

Branch `claude/particle-seam-regression-c2f79c`, code tip `30a4ee5` (docs tip `b7e0881`),
worktree `E:\Immersive Portals - Copy\.claude\worktrees\particle-seam-regression-c2f79c`.
This document is the CURRENT-STATE map. The saga history (rounds 27–38 ledger, prime-suspect
autopsy) lives in `migration/PARTICLE_SEAM_HANDOFF.md`; rounds 39–46 are appended there too.

## ENVIRONMENT (read before building anything)
- Worktrees have NO build scaffolding (untracked): copy `gradlew`, `gradlew.bat`,
  `gradle/wrapper/*`, root `settings.gradle`, root `build.gradle` **from the REDSTONE worktree**
  `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`. The E:\ parent repo's
  copies are 26.1.2-era and WRONG (cost one failed build).
- Suite: `.\gradlew.bat :fabric:runCrossingGametest -PapertureCensusProbe=true
  -PapertureTeardownTest=true` (+ `-PseamFractionalProbe=true` to arm particle probes and the
  measurement legs). Green = the `ALL LEGS PASS` line in the run's own log AND exit 0 — an
  exit-0 with zero leg lines is a VACUOUS pass (happened: run 9, window closed 16 s in).
- The gradle daemon SERIALIZES builds: a suite launched while a live client runs queues 40–50
  min. Never `gradlew --stop` (machine-wide rule). The gametest opens a real window on the
  desktop — closing it voids the run silently.
- The gametest log rotates mid-run: reconstruct with the `.log.gz` files + `latest.log`
  before judging (`fabric/runs/gametest-crossing/logs/`).
- Client: `.\gradlew.bat :fabric:runClient -PseamFractionalProbe=true -PseamMapProbe=true`
  (second lever narrates alignment refusals — without it a non-exact pair fails SILENTLY and
  looks like total breakage; cost two live rounds on 2026-08-05).
- javap the deobf jar before using any vanilla signature:
  `C:\Users\warwa\ModDev\Portals\Portal 26.2\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-95f25a8320\26.2\minecraft-merged-95f25a8320-26.2.jar`.

## THE CONFIRMED CONTRACT (user, 2026-08-10, explicit yes ×5)
Stitched space: source side A ∪ dest side B are ONE real place — existence is the mechanism,
rendering a consequence, nothing camera-dependent. (1) Blocks mirror as real state (long
verified). (2) Light + fire spread work across the seam (now gated). (3) The mirrored
half-torch/fire EMITS its own particles at the dest, independently, all topologies. (4) Bleed
standard: NOTHING weaker than plane-exact clipping of particle geometry. (5) Same-dim near,
same-dim far, cross-dim — everything.

## LIVE PARTICLE/SEAM MACHINERY (all at 30a4ee5)
- `render/SeamParticleTeleport` — the seam teleport. r40: position writes via
  `particle.setPos` (fields + BOUNDING BOX — vanilla re-derives x/y/z from the bb every moving
  tick; field-only writes never actually moved anyone). Open cells teleport only on a genuine
  plane TRANSITION (prev half via IEParticle xo/yo/zo vs current), except age<=1 birth-crossers
  (r41: spawn scatter materializes past the plane). Owned cells: positional via the
  material-continuation binding (cannot loop — claimCrossingHalf uses the same mapDir as the
  position transform). r46: aperture-miss falls back to the PARTICLE MARGIN INDEX.
- `mixin/client/ParticleSeamTeleportMixin` — two drivers: the `Particle.tick()` redirect inside
  `ParticleGroup.tickParticle` (r38) and `ParticleGroup.add` HEAD (r43 — 26.2 drains new
  particles into groups AFTER the tick phase, so birth-crossers rendered 1–2 frames before
  their first tick; teleporting at the door closes the flash window).
- `render/SeamParticleQuadClip` + `mixin/client/QuadParticleRenderStateClipMixin` — plane-exact
  billboard clipping (r42): per-quad camera-relative plane side-channel recorded at the two
  extract sites, Sutherland–Hodgman in billboard parameter space at build time (the plane
  function is affine there — exact), UV-lerped, pass- and renderer-agnostic (CPU; sodium/iris
  covered). r44: CLIP_INSET 0.01 — a cut edge exactly ON the plane rasterizes the plane's own
  pixel column (the "tiny sliver"). r46: margin-index fallback for near-portal cells.
- PARTICLE MARGIN INDEX (r46) — `SeamIndexHolder.seamlessportals$particleMargin`
  (Long2Long, cell→governing aperture cell), registered in `SeamRegistry.bind` for all
  in-plane cells within `PARTICLE_MARGIN_RADIUS = 8`, swept in `unbind` by BASE-CELL LIVENESS
  (UUID-free: bi-faced pairs double-register; a UUID sweep would orphan the surviving face
  behind the fingerprint gate). Readers use `getOrDefault(key, Long.MIN_VALUE)`. The mixin
  field is constructed INLINE — a cross-class static call in a @Unique initializer runs inside
  Level's ctor and class-init-deadlocked world creation (silent freeze, twice).
- `render/SeamDestAmbience` (+ END_CLIENT_TICK driver in `SeamlessPortalsClientFabric`, flag-ON
  block) — same-dim dest regions were display-tick dead (vanilla samples ±31 around the PLAYER,
  spawns gated 32 blocks from the CAMERA; IP's remote pass walks other-dim worlds only). The
  pass display-ticks nearby mirrorable portals' dest anchors (dest ORIGIN, IECamera gate
  defeat, %2 cadence, NO extra engine tick — double-aging, skip dests within 16 of the player).
  Cross-dim stays IP's `tickRemoteWorldRandomTicksClient`.
- `RenderStates.shouldRenderParticle` — seam window valve (r41): −0.12 for
  `SeamMap.isMirrorable` portals (0.5 vanilla-IP otherwise) with a single-entry portal cache;
  correctness partner is the re-armed hardware clip + the CPU quad clip.
- `SecondaryWorldRenderCore` — r42: inner clip RE-ARMED before both `renderAllFeatures` sites
  (the submitEntities TAIL disarms the store unconditionally mid-pass; 26.2 draws come later).
  r43: OUTLINE phases (`shapeOutlines` + `outline`) exempted via
  `PerEntityClipBracket.registerPhaseOverride` with a disabled snapshot (the seam counterpart
  outline lives ON the plane and fragment-fights the clip boundary).
- Window content pipeline: `MixinParticleEngine.ip_extractIsolated` (world filter →
  shouldRenderParticle → frustum → symmetric-emptiness) → same-dim explicit submit /
  cross-dim submitFeatures → stencil-masked feature draws. `MixinQuadParticleGroup` main-pass
  wrap: world filter → WINDOW RULE (`SeamParticleOcclusion`) → clip side-channel. The r36 BAND
  RULE is RETIRED (over-hid; the clip cuts geometry instead).
- FIRE (r42): `SeamMirror.applyToDestination` schedules the mirrored fire's FIRST tick
  (SKIP_ON_PLACE skips `FireBlock.onPlace`, vanilla's only scheduler; `FireBlockInvoker`).
  `ServerLevelFireSpreadMixin` entity-era watcher gate: a non-spectator player within the fire
  gamerule radius of a portal ENTRANCE counts as near its EXIT region (live Portal registry via
  `McHelper.findEntitiesRough`; only ever ADDS true). Spread-written dest fire schedules itself
  (plain setBlock → onPlace).
- LIGHT: automatic (mirror writes real luminous state; `setBlockState`'s light check fires
  despite SKIP_ON_PLACE; `LightEngineSeamTransparencyMixin` exempts luminous states) — now
  pinned by the gate below.
- PROBES (`render/SeamParticleProbe`, all behind `-PseamFractionalProbe=true`): TP (teleports
  by branch/class/direction, per-particle lifetime crossings, pingPongers, margin counter,
  budgeted crossing lines), DRV (engine vs base-tick per class), MAIN (window/world drops),
  DEST (per-dim per-reason drops + extracted classes), AMB, CLIP, CENSUS (population by
  dim/class, seam-cell occupancy — note: inEmptyHalf covers SINGLE-OWNED cells only; open-cell
  far-side residents don't show there).
- GATES (CrossingSmoke, teardown nest): `rsSeamFireAndLightGate` — dest block-light ≥ 12 after
  a mirrored torch (VACUOUS on bright nether baselines — known-open), D1 `hasScheduledTick`,
  D2 OUTCOME (planks above the DEST fire ignite with the player at the SOURCE end only;
  source fire re-placed on age-out; doFireTick scoped inside). Measurement legs
  `rsSeamParticleMeasureLeg` (cross-dim teardown fixture) + `rsSeamParticleSameDimPhase`
  (the user's /portal recipe) — probe-lever-gated, assert nothing, print LIFETIME lines.

## VERIFIED (live-confirmed 2026-08-10 + suite-gated)
Torch and fire at the seam: crossers consume at the plane and continue at the far end (strictly
one-shot; ping-pong structurally dead); far end emits its own flame/smoke (window shows it);
plane-exact billboard seal incl. the boundary sliver and the 8-cell margin band; selection
outline steady both halves; mirrored fire lives and SPREADS at the dest with only a
through-portal watcher; block mirror / cross-seam building unaffected. Suite: 11 runs this
session, all genuinely green ones show `ALL LEGS PASS` with the full leg sequence.

## KNOWN-OPENS (recorded, none urgent)
1. Dest-light gate vacuous on bright baselines (nether ambient 14) — needs a darker read point.
2. Iris: portal-pass HARDWARE clip un-injected (particle programs not transformable) — the CPU
   quad clip covers particles; same posture as the terrain clip under sodium.
3. Smoke wandering >8 in-plane cells before crossing still escapes governance (rare, diffuse).
   Principled fix if ever needed: birth-side tagging (side-A-born found on side B with no
   teleport history = infiltrator regardless of where it crossed).
4. Alignment-refusal is silent in-game (exact-only policy declines non-lattice pairs
   query-only; `-PseamMapProbe` narrates in the log). Chat-notice chip pending user decision.
5. `SeamFractional.particleSpawnPos` and `particleHiddenFromEmptySide` are dead code (drivers
   removed r34/r42) — candidates for deletion in a cleanup round.
6. Skylight column sources still treat a cut cell as whole (FRACTIONAL_DESIGN §9 deferred).

## META-LESSONS THIS ARC BURNED IN
- When live-verify keeps failing while your instruments pass, STOP and restate the full
  contract for confirmation — three rounds optimized counters (extraction, teleports) while
  the user gated on existence + pixels; the explain-back unlocked everything ("OF COURSE!!!").
- Point tests can never seal a plane against billboards; clip geometry, then inset the edge.
- Existence beats rendering: the window was honest all along — the far side was empty.
- A green without the legs' own log lines is not a green (vacuous exit-0, run 9).
- Mixin @Unique initializers run inside the target's ctor — no cross-class static calls there.
- Caches/indexes live on the object they guard; sweeps key on LIVENESS, not ownership UUIDs.
