# S14-C round 8 — the teleport flash + crossing stutter (S14.45, instrument-first)

**Status:** capture kit implemented + **kit-verify PASS (`wf_a144117b-011`, 6 MINORs — 4 folded:
main/portal sky-draw counter split by WorldRenderInfo.isRendering [stencil-direct defeats the
target-hash disambiguation], rcLog zero-guard for the promote frame, post-dump stall marker row,
skyRenNull wording corrected — the sky-skip signature is skybox=NONE + main skyDraws=0, NOT
skyRenNull, which addSkyPass makes non-null before its skybox gate)**; the aliasing question
came back CLEAN (the dest pass writes an isolated CameraRenderState — TAIL reads are valid).
Committed as S14.45 — awaiting the user's capture run, then classification vs original IP.
NO root cause asserted yet (NO-GUESSING).

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
