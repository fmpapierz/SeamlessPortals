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
