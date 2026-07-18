# S14-C round 8 — the teleport flash + crossing stutter (S14.45, instrument-first)

**Status:** capture kit implemented + **kit-verify PASS (`wf_a144117b-011`, 6 MINORs — 4 folded:
main/portal sky-draw counter split by WorldRenderInfo.isRendering [stencil-direct defeats the
target-hash disambiguation], rcLog zero-guard for the promote frame, post-dump stall marker row,
skyRenNull wording corrected — the sky-skip signature is skybox=NONE + main skyDraws=0, NOT
skyRenNull, which addSkyPass makes non-null before its skybox gate)**; the aliasing question
came back CLEAN (the dest pass writes an isolated CameraRenderState — TAIL reads are valid).
Committed as S14.45. **CAPTURE RUN DONE (user, 4-5 worlds) → CLASSIFIED: candidate (a),
capture-proven both directions (§5). Fix implemented (S14.46, §6) — Fable verify in flight.**

## 5. Classification (from the user's capture, fabric/runs/client/logs/latest.log 23:46-23:48)

User's felt report: "nether gets an OW flash in distance/fog/sky, OW gets a dark flash in
distance/fog/sky" — matched EXACTLY by the rows:

- **OW→nether (f=4759, warm):** frames 1-3 paint PURE OW atmosphere in the nether
  (`fogCol=0.753,0.847,1.000`, fogDist env 0..1024 = OW-wide) with `probeFog=ffc0d8ff` showing
  NO endpoint divergence — the probe's internal level pointer is still the OW world (phase 1:
  it updates only in Camera.tick). Frame 4 (`f=4762`): endpoints split
  `probeFog=ffc0d8ff->ff330808`, painted color lerps to nether dark-red over ~9 frames ≈ 1 tick
  (phase 2: partialTickLerp). Total ~12 frames ≈ 90ms.
- **Nether→OW (f=7625, warm):** symmetric — 2 frames of pure nether fog
  (`fogCol=0.200,0.027,0.027`) AND a BLACK sky disc (`skyCol=0` while `skyDraws=6` — the OW sky
  pass drawing with nether's SKY_COLOR=0), then the ~1-tick lerp to blue. The "dark flash".
- **(b) sky-skip REFUTED:** skybox correct every frame both directions (NONE in nether,
  OVERWORLD immediately on return; no zero-main-sky-draw OW frame). **(c) rainMult REFUTED:**
  0.000 stable throughout.
- **Stutter reading (same rows):** ONE ~20ms promote frame (baseline 5-6ms), no elevated tail,
  compQ ≤ 43, `rcLog` marks the promote frame (part of its 20ms is the RenderChainProbe 1Hz
  write — S20-removed anyway). The felt "stutter" is likely DOMINATED by the 90ms color flash
  reading as jank + one dropped frame; re-assess feel after the fix, against the IP zero-lag
  bar, before any perf work.

## 6. The fix (S14.46): the missing 26.2 HALF of IP's per-dim fog swap

`FogRendererContext.onPlayerTeleport` (the IP-analog site, already called at
ClientTeleportationManager:551 after the level swap + player placement) swapped only the
block-era static-field contexts — a 26.2 no-op (the fog statics are gone; smoothing moved into
`Camera.attributeProbe`, which the crossing never touched — the half-ported-IP-call pattern
again, memory portalview-light-engine-half-port). Fix: the method now also does
`probe.reset()` + `probe.tick(client.level, client.player.getEyePosition())` — the next
extract's lazily-created ValueProbes sample lastValue=newValue=dest → instant snap, killing
BOTH phases. Fidelity: IP's literal saved-context restore degenerates to the same snap for any
absence > 2 ticks (ValueProbe.tick evicts unread entries), so the snap IS the faithful 26.2
re-expression. Vanilla's own dimension travel is untouched (it never routes through
onPlayerTeleport and keeps its loading screen).

NO root cause was asserted before the capture (NO-GUESSING held).

## 7. Post-fix live round (user, 2026-07-18): FLASH GONE + three S18-family observations

**"ran it, flash is gone"** — the fix is live-confirmed in both directions (formal close awaits
the Fable verify verdict). Three further observations, all classified as the DEST-PASS
TRAILING-PERIPHERY family (none are flash-fix regressions — the fix touches only the main
camera probe at the crossing instant), all **user-verified present in original IP** → upgraded
from deviations to confirmed IP-parity items for S18:

1. **Dest clouds absent in the portal view** ("in nether, ow clouds dont render, they pop in
   when you teleport to ow") — the documented S13-J deliberate deviation
   (SecondaryWorldRenderCore ~:899-914): dest clouds mid-submit would rotate/fence the SINGLE
   shared CloudRenderer ring buffers the main pass draws later the same frame;
   renderPortalClouds is preserved-but-uncalled; the S18 work = IP's CloudContext per-dim
   isolation reconciled with 26.2's single-renderer ring buffer.
2. **Break particles absent in the portal view** (block breaks through the portal show no
   particles in the dest world; source-dim particles fine) — deferred item ② since S14.40: the
   dest-pass particle extract is cancelled (MixinParticleEngine round-5 guard — shared
   accumulator corruption otherwise); S18 = IP's per-particle world filter / per-dim engines.
3. **Targeted-block outline absent through the portal** (NEW ledger entry): the decomposed dest
   pass has no hit-outline step (grep: zero outline sites in SecondaryWorldRenderCore);
   LevelRendererBlockOutlineMixin only re-buckets the MAIN world's outline near portals.
   Cross-portal block breaking works (server interaction fine) — only the dest-view outline
   visual is missing. S18: submit the transformed hit outline inside the dest pass.

## 8. S14.47 — the phantom boost rocket (log-classified, THIRD crossing path + player-reuse orphan)

**User:** "when i fly through portal with a rocket boost elytra, a phantom rocket shoots out in
front of me." The 2026-07-08 fix had TWO halves (ProjectilePortalHandler + EntityMixin — both
block-era paths) and its orphan analysis assumed the OLD player-recreate. Log evidence names two
NEW mechanisms:

1. **The IP path teleports the attached rocket:** `ServerTeleportationManager` — "Entity is too
   far to teleport FireworkRocketEntity" (the rejected attempts; in-range attempts recreate it
   DETACHED in the dest → vertical free-flight phantom exactly where the player emerges).
   Neither old skip covers this path. Fix: the THIRD-half skip in `shouldEntityTeleport`
   (covers both the local scan and the global-portal scan).
2. **Player REUSE keeps the orphan's attachment LIVE:** vanilla `FireworkRocketEntity.tick`
   glues the rocket to `attachedToEntity`'s coordinates AND injects elytra boost acceleration
   into it, with NO level/removed check — under reuse the source-world orphan tracks the
   player's DEST-world coordinates (60+ block per-tick moves = the "[ImmPtl] Skipping collision
   calculation because entity moves too fast" stack spam) and keeps BOOSTING the cross-dim
   player (a real gameplay bug, not just visual). Vanilla escapes only because its recreate
   kills the reference. Fix: `changePlayerDimension` discards the player's attached rocket(s)
   (bbox.inflate(8) sweep, predicate attachedToEntity == player) before the move — vanilla's
   "the boost rocket is lost through a portal" outcome re-expressed under reuse; attached
   rockets are invisible (shouldRender && !isAttachedToEntity) so the discard has zero visual.
   New accessor: FireworkRocketEntityAccessor.seamlessportals$getAttachedToEntity.

## 9. The zero-lag hunt (open — instrumented, awaiting the next capture)

Post-flash-fix capture reading (user: "seems really good, but i want zero lag, i feel like a
frame or 2 is getting dropped"): EVERY crossing costs one 15-29ms frame (the row after the
PROMOTE marker; baseline 5-8ms) — 1-2 dropped frames at 60fps, matching the feel. Also
occasional non-crossing spikes (one 54.91ms; 22-25ms clusters mid-flight — possibly the
boost-rocket collision-spam server work, re-check after S14.47). S14.47 adds promote-frame
phase timing to the kit rows: `pMs=` (the whole promoteAndDemoteOnPlayerDimensionChange body)
and `dMs=` (the synchronous override discovery) — the next capture run names the dominant term,
THEN we optimize that term (no guessing). Notes (S14.47 verify PASS, wf_a1b18604-e2c): part of
the promote frame is the RenderChainProbe 1Hz write (`rcLog` rows) which is S20-removed
diagnostic weight, and `pMs` ITSELF brackets the cutover LOGGER.info + probe arming — if pMs
alone explains the spike, discount one log write before attributing to promote logic. Also: the
discarded rocket's CLIENT replica may run 1-2 more invisible glue ticks until the remove packet
lands — bounded, not a fix failure.

## 10. S14.48 — the term NAMED (capture round 3) + the warm-gate fix

**S14.47 retest capture (user: firework GONE — 0 log mentions, 0 spam; "teleport is pretty
good, a little laggy going to OW"):** `pMs=0.5-1.7` (promote body EXONERATED), `dMs=3-12`
(discovery meaningful, not dominant). **The named term: `visSec=23,000-33,000` on EVERY promote
frame** (vs 140-1,900 baseline) — IP's `VisibleSectionDiscovery` is an occlusion-BLIND frustum
flood; its 23-33k list makes the promote frame's extract+draw the 15-35ms cost. OW-bound is
heavier (avg ~30ms vs ~15ms; the "laggy going to OW" feel) because OW's flood sections are
retained-COMPILED (real draws; nether's are mostly post-collapse uncompiled skips), plus
spike-stacking (one 99.79+62.15ms double-spike; compQ up to 836 on one promote).

**The design insight (vanilla-ref-verified):** `SOG.invalidate()` only schedules the ASYNC
rebuild — `currentGraph` stays the dim's WARM occlusion tree and vanilla's own applyFrustum
fill (which runs immediately before our override refill) walks it, producing a small
occlusion-culled set BFS'd from ~the same camera position on rapid crossings. The override then
overwrote the good set with the flood. The override's purpose was always the COLD case (S14.7:
a never-BFS'd/just-reset graph yields ~nothing → IP's blank-first-frame bug).

**Fix (S14.48, as SHIPPED after the round-1 folds — see §11):** two-condition gate — keep
vanilla's fill at applyFrustum RETURN only when `visibleSections.size() > 32` AND the SOG's
BFS origin (`prevCamX/Y/Z` accessors) is within ≤2 8-block cells of the current camera (the
round-1 BLOCKER: a far-origin warm tree passes the yield check while missing the arrival's
near field); otherwise the discovery runs (cold case unchanged; `alwaysOverrideTerrainSetup`
keeps the unconditional behavior); one-shot consumed either way; `vy=` records yield+branch.
CAUTION: Double.MIN_VALUE (the never-updated prevCam sentinel) is ~+0.0, not far — the cold
case is caught by the YIELD check alone (empty octree ⇒ yield ~0); do not weaken it.
+ RenderChainProbe's first armed 1Hz log moved off the promote frame, and the flash row's
`rcLog` marker re-keyed to `lastWriteMs` (stamped ONLY by an actual LOGGER write — the round-1
MAJOR: pacing-state keying false-flagged the promote row). + TRACKER CONTINUITY (§11): the
demote reuses fromDim's live tracker captured before the promote re-point — kills the
all-dirty-fresh-tracker phantom remesh wave.

**Open observations for the next capture:** 35× `ImmPtlChunkTickets Chunk loading failure`
(OW side — watch for correlation with residual OW-bound spikes); the 99/62ms double-spike class
(compile/upload burst? re-measure post-gate); expected post-fix promote frame ≈ dMs-free
warm-set cost, target ≲ 10ms.

## 11. S14.48 verify round 1 FAIL → folds, + the REMESH WAVE classified & fixed

**Gate live-confirmed first** (user capture, provisional build): first 2 crossings `vy=0/24` →
discovery correctly ran (cold graphs); ALL subsequent warm crossings `vy=160-2978`, no `dMs` —
promote frames dropped to **7.5-19ms** (nether-bound ~8-12 ≈ baseline; user: "seems a lot
better"). BUT verify round 1 (`wf_33dda3b2-9f5`) FAILed the naive yield-only gate:

- **BLOCKER (folded):** the TWO-PORTAL geometry — a warm tree BFS'd from a FAR last-main-stint
  origin still yields >32 stale in-frustum sections while MISSING the arrival's near field
  (occluded from the old origin) → 1-3 frames of holes on return-via-a-different-portal. Fold:
  the gate now also requires ORIGIN PROXIMITY — SOG `prevCamX/Y/Z` (the last BFS origin,
  8-block cells, new accessors; MIN_VALUE=cold→far) within ≤2 cells of the current camera;
  borderline → discovery (safe direction). Same-portal flow (the measured win) passes.
- **MAJOR (folded):** `rcLog` false-flagged every promote row (the marker keyed to pacing state
  the new arm mutates without a write — user-log-confirmed). Fold: `lastWriteMs` stamped only
  in the actual LOGGER branch; marker re-keyed.
- **User's mid-distance REMESH observation = REAL + classified (not normal, now fixed):**
  capture shows compile bursts at returns (`compQ=323`, one `compQ=3042` draining over seconds
  = the visible wave). Root cause (vanilla-source-proven): `new SectionUpdateTracker(...)`
  marks EVERY section dirty (vanilla only constructs at level init); our demote created a FRESH
  tracker per crossing for the departed dim → phantom full-dim remesh scheduled → warm-adopt on
  return = the wave. Exonerated: `onResourceManagerReload` (only sets shouldResetSkyRenderer).
  Fix: TRACKER CONTINUITY — capture fromDim's live tracker before the promote overwrites the
  field; the demote reuses it (pending dirty-marks survive exactly; null-guard falls back to
  fresh). Re-verify (`wf_0abb365a-666`) PASS (2 MINORs folded: the MIN_VALUE comment correction
  + this note's §10 rewritten to the shipped design). S14.48 @ `0786d21`. Post-S14.48 live
  round: block breaking clean (tracker holds), no arrival holes (origin gate holds).

## 12. S14.49 — the OPEN threads' instrumentation (shadow discriminator + many-portal cost)

Two observations from the many-portal live round, both instrumented (NO GUESSING):

1. **The superflat boundary shadow** (user: a 50-100-block horizontal shadow band + two
   receding legs on distant grass around a new distant portal; a block update there clears it):
   suspected = sections MESHED before (neighbor) light arrived, the light→remesh signal lost;
   previously masked by the phantom all-dirty remesh wave S14.48 removed. NOT yet confirmed —
   `debug_dump_light_section` (LightSectionDump) dumps the crosshair section's engine light
   values / chunkFull / lightOnInColumn / tracker SectionDirtyState (new accessor) / mesh
   class-vs-UNCOMPILED / SOG membership in one line. Protocol: dump on a SHADOWED block, then a
   HEALTHY one for contrast. **User confirmations (refined observation):** grid-aligned-ish
   edges with GAPS; legs point TOWARD the player and are short; sometimes CONCENTRIC square
   borders smaller→larger ("if i cross into the area while the distant chunk build edge is
   receding") — i.e., frozen chunk-STREAMING-RING boundary seams; area = freshly streamed
   (nether portal to OW +5000 blocks); stable until a block update. Sharpened suspect: the
   dest-pass compile path schedules boundary sections BEFORE vanilla's hasAllNeighbors
   deferral would (all 8 neighbor chunks + lightOnInColumn), baking dark seams and CONSUMING
   the one-shot dirty flag (the ow-holes rule: "extract() consumes one-shot dirty flags") —
   the later neighbor-arrival never re-marks. Predicted dump on a shadow: sky=15 + compiled
   mesh + dirty=false (the lost-signal case). Await the dump before any fix. Reading: sky=15 + mesh compiled + dirty=false = light data correct,
   mesh stale, no pending remesh (the lost-signal case → fix the light→setSectionDirty plumbing
   for portal-loaded chunks); sky=0 = the DATA is wrong (→ loader/packet path); dirty=true = a
   compile-scheduling stall instead. Await the user's screenshot-adjacent confirmations too:
   section-grid-aligned edges? freshly-streamed area? stable until block update?
2. **Many-portal steady-state lag** (142 non-promote frames ≥25ms with many portals; small
   visSec, empty compQ — the per-frame dest-pass scaling): `dp=` row field counts dest passes
   (incl. nesting layers) per frame — regressing ms against dp across a capture names the
   per-portal cost; then compare the same scene against the IP instance (its zero-lag bar was
   measured on teleporting, not necessarily many-portal scenes — get the IP-side feel for the
   SAME portal count before judging parity).

## 0. The observations (user, 2026-07-17, same round that closed the far-walk wipe)

1. **Teleport flash:** appears ONLY on distant-fog/sky pixels — looking down at nearby terrain
   during a crossing shows NO flash. (Far-depth pixel class: pixels no geometry overwrites —
   painted only by the clear fill (fogData.color), the sky pass, and the clouds pass.)
2. **Crossing stutter:** our port has a small hitch at crossings; **original IP (the user's
   side-by-side instance: `C:\Users\warwa\curseforge\minecraft\Instances\1.21.1 fabric ip`,
   MC 1.21.1 + IP 6.0.6, NO Sodium/Iris = vanilla renderer) feels ZERO-lag on the same
   machine.** Consequence: the "post-crossing mesh cost is partly inherent" position may NOT be
   asserted without numbers — the IP side-by-side is the acceptance bar.
3. Bonus (recorded elsewhere): entities now visible through the window, wipe CLOSED.

## 1. Enumeration (Opus workflow `wf_92129b0d-5f7`, 4 tracers, 74 cited sites — facts only)

The candidate set splits into two disjoint classes:

- **CLASS 1 — terrain one-shots** (vanillaTerrainSetupOverride + consumer, clearVisibleSections/
  invalidate/needsFrustumUpdate, cold invalidateCompiledGeometry, preResolvePromotedWindow):
  all act on visibleSections/loadedChunks/meshes. Failure mode = MISSING terrain (holes), which
  would hit near terrain too — the WRONG SHAPE for a far-pixels-only flash. Excluded by
  structure; confirmed behaving via RenderChainProbe fields.
- **CLASS 2 — atmospheric retained state that paints far-depth pixels EXCLUSIVELY:**
  1. **`Camera.attributeProbe` retained lerp:** the crossing calls `gameRenderer.setLevel` →
     `Camera.setLevel` which swaps ONLY the level pointer (ref Camera:491); the probe resets only
     in `Camera.reset()` (:487), never on the crossing path. So FOG_COLOR / SKY_COLOR /
     CLOUD_COLOR / sunrise / sun-moon-star angles lerp source→dest over ~1 tick
     (EnvironmentAttributeProbe.ValueProbe lastValue/newValue, promote-on-tick, ref :38-78)
     while geometry snaps. These attributes paint ONLY sky/fog/cloud pixels.
  2. **One-frame sky-pass skip:** vanilla sky extraction is `if (levelRenderer.skyRenderer() !=
     null)` (ref LevelExtractor:182); a promoted renderer that never ran vanilla addSkyPass has
     skyRenderer==null → extraction skipped → skybox stays NONE after LevelRenderState.reset →
     addSkyPass gate fails → NO sky pass for exactly one frame (flat clear color on far pixels);
     next frame addSkyPass lazily constructs it (ref LevelRenderer:317-322). The mod compensates
     for exactly this in the PORTAL pass (SecondaryWorldRenderCore ~:705-712) but not on the main
     promote frame.
  3. **Retained `AtmosphericFogEnvironment.rainFogMultiplier`** (singleton instance field):
     converges 0.2/tick cross-dim → fog DISTANCES stale several frames (the same field the dest
     pass brackets per-dim — S14-A FIX-6).
  - Also enumerated: skybox-type toggle (OW↔nether legitimately adds/removes the sky pass),
    fogType (fluid-in-camera), void-darkness new-dim term vs retained color, SkyRendererTargetMixin
    per-draw target resolution.

## 2. The capture kit (S14.45 — `TeleportFlashProbe`)

Composition of the two existing probe archetypes (RenderChainProbe's promote-arm lifecycle +
DrawCallTrace's buffer-then-single-dump discipline; NEVER per-frame log4j on the render thread):

- **Always-on 96-slot in-memory ring**, one preformatted row per frame, written at
  GameRenderer.render TAIL (GameRendererMixin) — so every capture includes pre-crossing baseline
  rows. Zero log writes on the hot path.
- **Row schema:** `f ms dim camY fogType fogCol fogDist[env/rd/sky/cloud] skybox skyCol
  sunriseCol starB darkDisc cloudCol skyRenNull skyReset skyDraws skyTgt probeFog probeSky
  rainMult ovr applyF visSec compQ rcLog` — where `probeFog`/`probeSky` are the attributeProbe
  endpoints (`getValue(attr, 0)`=lastValue vs `getValue(attr, 1)`=newValue; a mid-lerp frame
  prints `a->b`), `skyDraws/skyTgt` come from per-draw counters in SkyRendererTargetMixin,
  `rcLog` marks frames where RenderChainProbe's 1Hz log4j write fired (a write can itself stall —
  those frames must not be misread as organic stutter), and `ms` is TAIL→TAIL wall time (spans
  the full frame loop → the stutter profile).
- **Arming:** every promote auto-arms (marker row `[PROMOTE from -> to (cold/warm)]`, 40-frame
  window, one batched dump at close; mid-capture promotes extend + add a marker, never nest);
  manual lever `debug_capture_flash_enable` captures a no-crossing baseline.
- **Expected signatures** (kit-verify-corrected): (1) probe lerp → `probeFog/probeSky a->b`
  endpoint divergence is UNCONDITIONAL vanilla behavior at every crossing — classification rests
  on whether the PAINTED fogCol/skyCol track the lerp midpoints AND the felt flash duration
  matches ~1 tick; (2) sky-skip → exactly one row with `skybox=NONE` + MAIN `skyDraws=0`
  (`skyRenNull` reads false even on the skip frame — addSkyPass constructs the renderer before
  its skybox gate; portal-pass draws print separately as `(+Np)`); (3) rainMult → `rainMult`
  sliding at 0.2/tick with fogDist env distances shifted while colors/skybox stay healthy.
  All three verified pairwise-disjoint and distinct from a healthy crossing. KNOWN GAP (verify
  finding 6): rows are CPU-state only — an all-healthy capture on a flashing crossing refutes
  (a)/(b)/(c) and escalates to per-pass GPU attribution (DrawCallTrace `debug_capture_frame` on
  the crossing frame — the wedge-class instrument).

## 3. Capture protocol (user)

1. Portal crossings as usual (both directions, incl. right after a far walk); each crossing
   auto-dumps `==== TELEPORT-FLASH CAPTURE ====` blocks into latest.log (~1-2s after the cross).
2. Hand over latest.log (or the blocks). Optionally note per crossing whether the flash was seen
   looking at sky/horizon vs down at terrain.
3. IP side-by-side (same instance as above): cross while looking at the horizon — is there ANY
   one-frame flash on fog/sky? And note the felt lag. This classifies defect vs IP-authentic
   before any fix design.

## 4. Ledger

- S20 removal candidates: TeleportFlashProbe + its GameRendererMixin/ClientWorldLoader hook
  lines + the SkyRendererTargetMixin counters + the debug_capture_flash lever (same batch as
  RenderChainProbe/DrawCallTrace).
- The always-on ring costs ~10 String.format/frame — acceptable vs MC's per-frame allocation
  noise; if it ever matters, the primitive-ring alternative is documented in the kit-verify
  round.
- Stutter routing decision deferred until the capture: known lazy-compile tail → S18/polish
  (with the IP side-by-side as the acceptance bar); anomaly → fix in-rung.
