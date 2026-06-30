# Phase 5 — Stencil-Direct Portal Render (drop the mirror-FBO)

Goal (user's words): *"Port IP's stencil renderer. No full-screen FBO render + composite.
Draw the dest world directly into the main framebuffer, masked by the stencil, so the
fragment cost is limited to the portal opening. Do the specific thing that makes IP cheap."*

This replaces the current two-phase mirror-FBO path (per-frame full-screen render of a second
world into an FBO, then composite) — whose cost scales with `portalRenderDistance²`
(see memory `portal-stutter-is-mirror-fbo-cost`) — with IP's `RendererUsingStencil` model.

---

## 0. Source verification (DONE — 2026-06-30, all from `C:\Users\warwa\ModDev\mc262-ref`)

The whole rework rested on two load-bearing 26.2 claims. Both are now **confirmed from source**,
not assumed. The earlier blocker ("drawing into the main buffer mid-frame blanks the overworld")
is also explained and dispositioned.

| # | Verified fact | Source |
|---|---|---|
| 1 | **A direct, non-framegraph terrain draw EXISTS.** `LevelRenderer.prepareChunkRenders(Matrix4fc modelView)` is **public** and builds a `ChunkSectionsToRender` from `this.visibleSections`. | `LevelRenderer.java:509` |
| 2 | `ChunkSectionsToRender.renderGroup(ChunkSectionLayerGroup, GpuSampler)` issues the actual GL draws. It is a plain method call — **no `FrameGraphBuilder`**. The framegraph's `addMainPass` merely *schedules a lambda that calls it* (`renderGroup(OPAQUE,…)` at `LevelRenderer.java:409`, `renderGroup(TRANSLUCENT,…)` at `:438`). | `ChunkSectionsToRender.java:31` |
| 3 | **OPAQUE draws straight into the MAIN target** (the one carrying the stencil): `ChunkSectionLayerGroup.outputTarget()` → `OPAQUE → minecraft.gameRenderer.mainRenderTarget()`, `TRANSLUCENT → levelRenderer.translucentTarget()`. | `ChunkSectionLayerGroup.java:30-38` |
| 4 | `renderGroup` opens its `RenderPass` with `Optional.empty()` color-clear and `OptionalDouble.empty()` depth-clear = **LOAD, not clear** → drawing dest terrain **preserves** the overworld already in the main target. We draw *on top of* it, masked by stencil. | `ChunkSectionsToRender.java:42-48` |
| 5 | **The blanking was the NESTED FRAMEGRAPH, not "drawing into the main buffer."** The 8-arg `destRenderer.render(...)` (PortalContextSwitch ~line 1392) builds its *own* `FrameGraphBuilder`, re-enters `LevelRenderer.render`, and re-imports/recreates the "main" target handle (`addMainPass` does `this.targets.main = pass.readsAndWrites(this.targets.main)` at `:365`), disrupting the outer frame's import. Eliminating that nested render removes the blank. | `LevelRenderer.java:178,212,239,365` |
| 6 | **blaze3d models NO stencil.** `DepthStencilState` is `record(CompareOp depthTest, boolean writeDepth, float depthBiasScaleFactor, float depthBiasConstant)` — depth only. So **all stencil must be raw GL** (`GL11.glStencilFunc/Op`, `glEnable(GL_STENCIL_TEST)`), exactly as the mod already does. | `DepthStencilState.java:8` |
| 7 | **The GL backend never touches stencil.** `GlCommandEncoder.applyPipelineState(...)` sets depth/cull/blend/polygon-mode/color-mask and **nothing stencil** (no `glEnable/Disable(GL_STENCIL_TEST)`, no `glStencil*` anywhere in the encoder). ⇒ **Raw-GL stencil state set BEFORE `renderGroup` PERSISTS through the dest-terrain draws.** This is the single fact that makes IP's trick portable to 26.2. | `GlCommandEncoder.java:771-827` |
| 8 | **Hook point.** The IP-exact `Sheets.translucentItemSheet()` INVOKE is **GONE** in 26.2 (submit-model rewrite removed `MultiBufferSource`). The faithful hook is inside the `addMainPass` lambda (`LevelRenderer.java:391-442`); Fabric `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN` fires there (after `renderGroup(TRANSLUCENT)` at `:438`). Use the Fabric event for the first cut; a mixin between `:409` (OPAQUE) and `:438` (TRANSLUCENT) is the option for strict pre-translucent ordering later. | `LevelRenderer.java:391-442` |

**Residual empirical risk (step 1, runtime-only):** whether `renderGroup`'s `createRenderPass`
binds an FBO that exposes a live **stencil** attachment for `mainRenderTarget()`. The mod's
EXISTING composite already stencil-masks against the main target successfully (so the main FBO
has a working `GL_DEPTH_STENCIL_ATTACHMENT` per `RenderTargetMixin`), but `renderGroup`'s exact
RenderPass path differs and must be confirmed with a `:fabric:runClient` smoke test. Bisect
fallback if the mask doesn't bite: draw one flat colored quad through the stencil at the hook
before attempting terrain.

---

## The mechanism (what replaces the FBO)

Per visible portal, near-to-far, inside the `addMainPass` lambda (via AFTER_TRANSLUCENT_TERRAIN):

1. Raw-GL: `glEnable(GL_STENCIL_TEST)`, clear stencil on the main FBO.
2. Write the opening to stencil — reuse `PortalShapeRenderer.drawMergedPortalShapeWithDepthTest`
   under `glStencilOp(KEEP,KEEP,INCR)` (recursion) / func = current layer, keep `GL_DEPTH_CLAMP`.
3. `clearDepthOfThePortalViewArea` — reuse the existing `drawMergedPortalShapeWithDepthClear` +
   `glDepthRange(1,1)` (already reversed-Z-correct — do NOT copy IP's literals).
4. `setStencilLimitation(layer)` = `glStencilFunc(GL_EQUAL, layer, 0xFF)`, `glStencilOp(KEEP,KEEP,KEEP)`, `glStencilMask(0)`.
5. **Dest terrain DIRECT draw** (replaces the FBO composite): `withSwitchedWorld(dest)` →
   build dest camera + inner frustum → `VisibleSectionDiscovery.discoverAndScheduleForPortalView`
   (bounded BFS — the cost lever) → dest `LevelRenderer.prepareChunkRenders(destModelView)` →
   `chunkSectionsToRender.renderGroup(OPAQUE, sampler)` then `renderGroup(TRANSLUCENT, sampler)`
   straight into the main target, masked by the live stencil. **No `destRenderer.render`, no FBO.**
6. `restoreDepthOfPortalViewArea` — write the portal-plane depth back so the frame and beyond sort.
7. `clampStencilValue(outerLayer)` (recursion only) + STEP-5 teardown (reuse existing).

---

## DELETE vs REUSE

**DELETE (FBO machinery — mostly `PortalContextSwitch` + `StencilPortalRenderer`):**
- `StencilPortalRenderer.prepareDestinationRender()` (PHASE 1) and `GameRendererPortalPrepareMixin`
  (the renderLevel-HEAD hook) — they exist **only** to move the nested framegraph out of mid-frame;
  moot once there is no nested framegraph.
- `PortalContextSwitch`: the 8-arg `destRenderer.render(...)` call (~1392), `compositePortalFbo()`
  (~1827-1901), `compositeDestinationWorld` FBO branch, the FBO pool (`portalFbos`,
  `fboReadyByPortal`, `secondaryFbo`, `prepareSecondaryFbo`, `evictUnusedPortalFbos`,
  `prepareDestinationWorld` phase-1 body), the `fboReadyThisFrame` hand-off.
- `PortalRenderTypes`: `PORTAL_COMPOSITE_BLIT` / composite pipelines (FBO→screen blit only).
- `renderOnePortal` STEP 1 dummy-draw FBO discovery + STEP 4 composite call.

**REUSE (keep / lightly generalize):**
- `RenderTargetMixin` (stencil-on-main-target) — **the enabler, keep as-is**.
- `PortalShapeRenderer.*` (merged-shape / depth-test / depth-clear draws = IP `ViewAreaRenderer`).
- `PortalRenderTypes` stencil/depth pipelines (`PORTAL_STENCIL_WITH_DEPTH` GEQUAL, `PORTAL_DEPTH_CLEAR`).
- `withSwitchedWorld(...)` (IP `switchAndRenderTheWorld` state swap) — repoint its callback at the
  direct `renderGroup` draws instead of `destRenderer.render`.
- `VisibleSectionDiscovery.discoverAndScheduleForPortalView` + inner-frustum cull (the cost lever).
- Dest camera / `virtualCamera` / `destFrustum` build, `destExtractor`, per-dim lightmap/fog.
- `PerfTimers` / `RenderSpikeMonitor` (rename `fboRender` bucket → `stencilDirectRender`).

---

## Incremental sequence (each ends in `:fabric:runClient` + user visual check + `[SEAMLESS TIMERS]` read)

Vanilla only (no Sodium). NEVER `runClientGametest` (freezes). Already on feature branch `claude/nifty-kepler`.

- **Step 0 — Scaffold, no behavior change.** Add `setStencilLimitation(layer)` + `clampStencilValue(max)`
  helpers + a `renderDestWorldDirect(portal,link,camera,layer)` that for now delegates to the existing
  FBO path. Build green. *Verify:* identical to today.
- **Step 1 — Single portal, stencil-direct, overworld NOT blanking (THE milestone).** For 1 portal /
  1 layer: stencil write → `setStencilLimitation(1)` → dest OPAQUE+CUTOUT via `renderGroup` direct into
  main target (no FBO, no nested framegraph). Hook = AFTER_TRANSLUCENT_TERRAIN. *Verify:* overworld
  does NOT blank; nether shows in the opening; `fboRender` gone from TIMERS → `stencilDirectRender`;
  no `[SEAMLESS STUCK]`. *Bisect fallback:* flat colored quad through the stencil first.
- **Step 2 — Depth correctness.** `clearDepthOfThePortalViewArea` (before) + `restoreDepthOfPortalViewArea`
  (after), reversed-Z-corrected from the mod's proven FBO values. Add dest TRANSLUCENT + sky/clouds via
  IP `WorldRenderInfo` flags. *Verify:* no cloud/weather X-ray; obsidian frame occludes; entities show.
- **Step 3 — Multi-portal (1 layer).** Restore `MAX_PORTALS=4`, near-to-far loop (each `doRenderPortal`
  self-contained, per IP `renderPortals`). *Verify:* two portals → two distinct dests; no mask bleed.
- **Step 4 — Recursion via stencil layers.** `push/popPortalLayer` + INCR + `clampStencilValue(outerLayer)`;
  cap small (start `maxPortalLayer=2`). *Verify:* portal-in-portal nests; no stencil leak past parent;
  layer cap halts; bounded frame time. (Genuine multi-day sub-project.)

## Reference (algorithm source of truth)
`C:\Users\warwa\ModDev\ImmersivePortalsMod\src\main\java\qouteall\imm_ptl\core\render\renderer\RendererUsingStencil.java`
(+ `PortalRenderer.java`, `ViewAreaRenderer.java`, `mixin\client\render\MixinLevelRenderer.java`).
