# MC 26.2 port — render next pass (focused handoff)

Strict protocol: **no guessing.** Ground every API/field/method in the decompiled 26.2 sources at
`C:\Users\warwa\ModDev\mc262-ref`, the real run logs/crash reports/thread dumps, and the mod's own
code. State a Certainty Level before code; if < 95%, research more or ask — never write code on
assumption. Verify via `:fabric:runClient` + the user's visual check + log evidence. **Never run the
gametest (`runClientGametest`) — it freezes indefinitely.** Sodium is currently moved OUT of
`fabric/runs/client/mods/` (to the repo root) to test the vanilla path; Sodium-compat is a separate
layer to do AFTER the vanilla path renders fully.

## STATUS: core 26.2 render WORKS. Build is GREEN.
On a live client (no Sodium): builds, runs, all mixins apply, **no crashes**, and the
overworld→nether portal **renders real nether terrain** through the frame — correct depth, no cloud
X-ray, no freeze, no teleport crash. Fixes already landed + verified compiling:
- **Reversed-Z depth** (26.2 is reversed-Z, `GREATER_THAN_OR_EQUAL`): `PortalRenderTypes` stencil-depth pipeline `LESS_THAN_OR_EQUAL`→`GREATER_THAN_OR_EQUAL`.
- **Cloud X-ray** through the portal: `StencilPortalRenderer` depth-clear `glDepthRange(0,0)`→`(1,1)` (write near=1.0 in reversed-Z so later passes fail the depth test).
- **Freeze** (render thread pegged in `SectionOcclusionGraph.addSectionsInFrustum` via `extract()`'s per-frame `applyFrustum`): set the virtual camera's `capturedFrustum` (`CameraInvokerMixin` new accessor) so `extract()` skips `applyFrustum` (`LevelExtractor:125` gate; also skips the async graph update).
- **Empty FBO** (meshes never uploaded): removed the `prepareChunkRenders` `maxIndices==0` bail in `PortalContextSwitch.doFboRender` so `render()` runs and does `compileSections` + `uploadTerrainBuffersToGpu` (`LevelRenderer:255/262`).
- **Teleport crash** (`compileSections` NPE on out-of-`viewArea` section): `LevelRendererCompileSectionsMixin` (`@Redirect` the `sectionUpdateRenderStates` field-get, filter out nodes not in `viewArea`).
- Also on promote: `clearVisibleSections()` + `sectionOcclusionGraph().invalidate()` (kept; harmless, but NOT sufficient — see below).

## THE ONE REMAINING BUG — root cause DEFINITIVELY grounded
Symptom (live, no Sodium): after teleport the **nether (main) terrain is blank**, and the return
(nether-side) portal shows the overworld as a **thin strip at eye level** (consequence: no main-frame
depth → the depth-gated stencil only passes a sliver; the return FBO render itself SUCCEEDS).

Root cause (proven, not inferred): **`mc.levelExtractor` keeps driving the ORIGINAL renderer after the
mod swaps `mc.levelRenderer` to the promoted cached renderer.**
- 26.2: `Minecraft.levelExtractor` is `public final` (`Minecraft.java:280`), built ONCE bound to the
  original renderer (`Minecraft.java:649`: `new LevelExtractor(this, gameRenderState().levelRenderState, this.levelRenderer)`).
- The mod's client-first teleport swaps `mc.levelRenderer` to the promoted renderer but never swaps /
  re-points `mc.levelExtractor`. So `GameRenderer.extract()` → `mc.levelExtractor.extract()` →
  `applyFrustum` populates the ORIGINAL renderer's `visibleSections`, while `render()` uses the
  PROMOTED renderer with 0 `visibleSections` → blank.
- PROOF (log): `[SEAMLESS DIAG #1] PRE-swap oldRenderer(overworld).visibleSections=1225, promoted(the_nether).visibleSections=0`.
- This is 26.2-only: `LevelExtractor` didn't exist in 26.1.2, so the old `mc.levelRenderer`-only swap was sufficient.

## FIX PLAN (protocol order)
1. **Research/ground first** in `mc262-ref`: read `LevelExtractor` fields (`levelRenderer`, `level`,
   `sectionUpdateTracker`, `levelRenderState`) and `setLevel`/`allChanged` (`:389`/`:393`/`:406`); confirm
   exactly what `extract()` reads from each, and whether re-pointing `levelRenderer` alone gets terrain
   rendering vs. needing `level` + `sectionUpdateTracker` too. Read how the mod's client-first teleport
   swaps state (`SeamlessClientTeleport.performCrossing`/`doVisualSwap`, `HandleRespawnMixin`,
   `PortalWorldManager.promoteToMain`).
2. **Plan:** on promote, make `mc.levelExtractor` adopt the promoted renderer's state — re-point
   `levelRenderer` → promoted renderer, `level` → dest level, `sectionUpdateTracker` → the per-dimension
   `destExtractor`'s tracker (the mod's `extractors` map; `sectionUpdateTracker` is already AT/AW-widened).
   `mc.levelExtractor` is `public` (no Minecraft accessor needed); widen `LevelExtractor.levelRenderer` +
   `level` (AT in `accesstransformer.cfg` + AW in `seamlessportals.accesswidener`). Update the level
   WITHOUT `allChanged()` (which would wipe the cached meshes — the whole point of the cached renderer).
   Consider simply copying the already-correct `destExtractor`'s fields into `mc.levelExtractor`.
3. **Certainty ≥ 95% before code.** 4. **Self-review.** 5. **Verify:** `:fabric:runClient`; nether terrain
   renders after teleport; check `[SEAMLESS DIAG]` `visibleSections > 0` on the promoted renderer; user
   visual confirms; capture a thread dump if anything stalls.

## THEN (separate layer): Sodium compat
Re-initialize Sodium 0.9.0's per-renderer `RenderSectionManager` for the hand-built secondary
`LevelRenderer` (crash `crash-2026-06-25_21.48.31`: null `RenderSectionManager` in
`SodiumWorldRenderer.scheduleRebuildForChunk` ← `LevelExtractor.setSectionDirty`). Only after the
vanilla path renders fully. Sodium jar is at the repo root.

## Key references
- Decompiled 26.2 vanilla: `C:\Users\warwa\ModDev\mc262-ref`. JDK25 javap: `C:\Users\warwa\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2\bin\javap.exe`.
- `MIGRATION_API_MAP.md`, `PHASE_B_PORT_SPEC.md`, task #7. Memory: `port-seamless-portals-with-strict-fidelity`, `seamless-portals-is-ip-port`, `mc262-port-references`.
