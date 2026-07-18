# S14-C round 5 — the wedge corruptor named: the dest-pass `ParticleEngine.extract` (S14.40)

> **SUPERSEDED (round 6): the wedge attribution below was REFUTED by the user's live run** —
> the particle fix did not remove the wedges; the sub-lever kit attributed them to the GIZMO
> family (`debug_skip_extract_gizmos`), verdict in `S14C-round6-gizmo-verdict.md`. The particle
> guard itself STAYS (its shared-accumulator corruption is source-proven; its live symptom is
> the main-world particle WIPE, the block-era-documented defect — not the wedges). §1's
> enumeration and §2's mechanism remain correct as source facts.

**Status:** fix + A/B lever + sub-lever kit committed, awaiting the user's confirming run.
**Bisection input (user-verified, round 4):** "ONLY `debug_skip_extract_only_enable` removed wedges"
— i.e. the corruptor is precisely `destExtractor.extract(deltaTracker, newCamera, partialTick)`
(SecondaryWorldRenderCore Step 5), NOT the SOG feed, NOT the compile drain, NOT any portal draw,
NOT any state install. Every draw and every other state writer was lever-exonerated in rounds 2–4.

## 1. The complete shared-write enumeration of `extract()` (26.2 `LevelExtractor.java:95-219`)

`grep RenderSystem` over the file returns NOTHING — `extract()` touches zero GL/RenderSystem
state. The corruption is Java-side. Every write to state outside the extractor's own
(extractor, levelRenderState) pair:

| # | call (line) | shared surface | verdict |
|---|---|---|---|
| 1 | `blockEntityRenderDispatcher().prepare(cameraPos)` (:120) | dispatcher camera-pos | self-heals: main extract re-prepares next frame before any consumer |
| 2 | `entityRenderDispatcher().prepare(camera, crosshairPickEntity)` (:121) | dispatcher camera | ditto (+ the shell already restores it) |
| 3 | `Entity.setViewScale(...)` (inside `extractVisibleEntities` :227) | GLOBAL static | benign — same value both extracts |
| 4 | `weatherEffectRenderer().extractRenderState(...)` (:182) | writes per-LRS state object | value-copy into destLRS, no cross-ref |
| 5 | `skyRenderer().extractRenderState(...)` (:186, null-gated) | per-LRS | ditto |
| 6 | `worldBorderRenderer().extract(...)` (:190) | per-LRS state; renderer's `lastMinX/needsRebuild` caches mutate only in `render()` (never run for dest) + `alpha=0` gate at ±30M border | inert here |
| 7 | **`minecraft.particleEngine.extract(destLRS.particlesRenderState, frustum, camera, pt)` (:199)** | **THE ONE SHARED ParticleEngine — cross-referenced mutable accumulators** | **THE CORRUPTOR (§2)** |
| 8 | `debugRenderer.emitGizmos(...)` + `gameTestBlockHighlightRenderer.emitGizmos()` + `extractGizmos()` (:207-216) | drains `Minecraft.getPerTickGizmos()` into the SECONDARY renderer | steal/leak concern, not a painter (gizmos render camera-relative in their own pass); levered anyway |

## 2. The mechanism (all source-proven, no inference)

1. `QuadParticleGroup.extractRenderState` (26.2 `:24-39`) re-fills **and returns
   `this.particleTypeRenderState`** — a field on the engine's per-group object, NOT a fresh
   state. `ParticlesRenderState.add(...)` stores that reference into the calling LRS.
2. `ParticlesRenderState.reset()` calls **`ParticleGroupRenderState::clear` on the contained
   (= shared) group states**, then clears its list.
3. Vanilla invariant: ONE `ParticleEngine.extract` per frame → exactly one LRS ever references
   the accumulators, and its own next-frame reset clears them. Sound with a single extractor.
4. The dest-pass extract breaks it mid-frame: `destLRS.reset()` **clears the accumulators the
   main LRS still references**, then the dest-camera extract **re-bills the MAIN world's
   particle pool against the DEST camera** into those same objects. The main frame's
   translucent particle submit then draws dest-camera geometry under main camera state.
5. Visual signature (matches every user observation): garbage translucent triangles that
   depth-test onto **far-depth pixels only** (sky/fog — occluded by all solid terrain), present
   **only while a portal is in view** (the only time the dest extract runs), any time of day,
   both dims, following the camera (re-billed every frame). The nether portal's constant purple
   `minecraft:portal` particles supply ever-present quads.

## 3. Precedent — this exact bug was found and fixed once already (block era)

`com.warwa...ParticleEnginePortalSkipMixin` documents the identical mechanism ("particles stop
in the overworld once the nether view loads") and HEAD-cancels `ParticleEngine.extract` — but
its gate is `PortalContextSwitch.isRenderingPortal`, the **block-era driver's flag, false in the
IP driver path**, so it is inert flag-ON. The block era additionally kept per-dest particle
engines (`PortalWorldManager.getOrCreateParticleEngine`) so dest particles still rendered.

IP's faithful mechanism (one shared engine, per-particle world tag + per-particle render filter
`RenderStates.shouldRenderParticle`) is **deferred item ②** in `MixinParticleEngine` — its 1.21.3
anchors (`Particle.render`, `tickParticle`) are gone in 26.2. The unguarded dest extract is a
direct consequence of that deferral.

## 4. The fix (S14.40)

- `SecondaryWorldRenderCore.isDestExtracting` — TRUE exactly around `destExtractor.extract(...)`
  (try/finally). The only flag-ON dest-extract site, so the gate covers GUI-portal runs too.
- `MixinParticleEngine.onBeginRenderParticles` — cancels the dest-pass `ParticleEngine.extract`
  under that gate. Until deferred item ② lands, the dest pass extracts NO particles; the engine
  holds the WRONG (main) world's pool for the dest view anyway, so nothing that ever rendered
  correctly is lost. IP's `>4` far-portal skip is untouched (and subsumed while the guard holds).
- **A/B lever** `debug_allow_dest_particle_extract` — ON restores the corrupting vanilla call
  live (wedges should reappear on the spot = positive attribution both directions).
- **Insurance kit** `MixinLevelExtractor_DestSubLevers` (default OFF, dest-extract-gated only —
  the main extract is untouched): `debug_skip_extract_berd_prepare`, `_erd_prepare`,
  `_entities`, `_block_entities`, `_weather`, `_sky`, `_border`, `_gizmos`. If any residue
  survives, one live session attributes it without a new code round.

## 5. User protocol (one session)

1. Relaunch, portal in view → **wedges should be GONE with no commands at all.**
2. `/imm_ptl_client_debug debug_allow_dest_particle_extract_enable` → **wedges should RETURN.**
   `_disable` → gone again. That closes the attribution loop.
3. If (1) fails: run the eight `debug_skip_extract_*_enable` switches one at a time; report
   which (if any) removes the residue.

## 6. Ledger

- **S18 periphery (+ ledgered here):** dest-world particles through the portal window = land IP
  deferred item ② (per-group `extractRenderState` world filter) or per-dim engines (block-era
  pattern, exists suppressed). Goes with dest clouds/weather isolation.
- **S20 removal:** `debug_allow_dest_particle_extract`, the 8 sub-levers +
  `MixinLevelExtractor_DestSubLevers`, and re-evaluate the S14.40 guard once item ② lands.
  Block-era `ParticleEnginePortalSkipMixin` + per-dest engines die with the block era; the
  S14.40 guard is their flag-ON replacement.
- **Rule (new, joins the 26.2 invariants):** any mid-frame SECOND `LevelExtractor.extract`
  breaks every vanilla one-extract-per-frame aliasing invariant; before reusing a vanilla
  extract path off the main cadence, enumerate its writes for cross-referenced mutable objects
  (`ParticlesRenderState` is the only one in 26.2 — verified by this enumeration).
