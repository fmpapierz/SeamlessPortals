# CUTOVER_SPEC — the atomic render-driver-core spec (S11-B deliverable)

**Authored at S11-B (second third of U9), the spec the `API_RISKS.md` render-cutover verdict demands
BEFORE the S13 atomic commit.** The verdict is: *staged at the substrate boundary, atomic at the
driver core* — "the swap of {Portal entity + `PortalSyncPacket` sync, `PortalRenderer` +
`RendererUsingStencil`, `ViewAreaRenderer`, `MyGameRenderer` + context_management, `FrustumCuller`,
`TransformationManager`'s crossing math} is **one cutover**" (API_RISKS VERDICT §2). Two items must
be design-complete before that commit — R1's seaLevel protocol (SETTLED by SPIKE-R1) and R2's
frame-phase anchor (SETTLED + soak-proven by S3) — and **R5's reversed-Z sign flips and R4's ViewArea
decision are inside the atomic commit's scope and must be in its spec** (API_RISKS VERDICT closing
line). This document is that spec.

**Scope / execution phasing.** This spec is authored at S11-B; the mechanisms it governs execute
later:
- **R4** (§1) — subclass install decided here; **built + installed at S13** (C3 user checkpoint,
  default = rebuild).
- **R5** (§2) — checklist authored here; **executed at S12** (transplant into `RendererUsingStencil`);
  **signed off at S17**.
- **R9** (§3) — design settled here; **wired into the driver core at S12/S13**.
- **R2/A4** (§4) — already LIVE since S3 under block portals; **flag-dispatch wired at S13**.
- **Invariants** (§5) — hold for every stage that touches the secondary render path.
- **Atomic-cutover contents** (§6) — **flip at S13** (SCC closure + first light rung 1).

**Ground truth consumed:** `API_RISKS.md` (R1–R13 + VERDICT), `api-map/current-mod-render.md`,
`api-map/render-core.md` / `render-sub.md`, `port-notes/S11A-render-context.md`,
`spikes/SPIKE-R4-viewarea.md`, `spikes/SPIKE-R1-sealevel.md`, `port-notes/S03-frame-anchor.md`,
`PHASE5_STENCIL_DIRECT_SPEC.md`, plus direct source reads of
`IP:imm_ptl/core/render/renderer/RendererUsingStencil.java`, `IP:.../CHelper.java`,
`IP:.../portal/PortalRenderInfo.java`, and the mod's live
`MOD:render/StencilPortalRenderer.java` / `PortalRenderTypes.java`. Citation conventions match the
slice docs: `IP:` = 1.21.3 source, `26.2:` = `mc262-ref`, `MOD:` =
`common/src/main/java/com/warwa/seamlessportals`.

**Verification tier.** S11 is a designated Fable adversarial-verify stage
(`migration-model-tier-policy`). This spec is Opus-authored; the queued Fable (`model:'fable'`)
adversarial re-verify of §2 (the R5 checklist — a silent depth-sign flip is the live-render bug class the
flag-OFF shipping build cannot catch) **EXECUTED at S11-B assembly** — verdict PASS, no MAJOR, no wrong
flip direction. It folded in two prose corrections, both now applied above: **§2.1 Row 4** (the write mask
follows IP's `writeDepth = doModifyDepth && !fuseView` — TRUE non-fuse, FALSE fuse-view — NOT a false
mask; the `LEQUAL`→`GEQUAL` compare flip is the only flip) and **§2.1 Row 12** (both driver modes DO write
restore-phase depth; stencil-direct NEAR shield at STEP 3.7, FBO-mode at STEP 3.5; do not conflate with the
#7 FAR clear). See `port-notes/S11B-render-drivers.md` §8. **Still runtime-verified, NOT re-litigated
here:** §3 (R9) buffer-ownership FORM — instance-per-dim `FogRenderer` vs the swap-the-slot fallback — is a
named S13/S14 live check (§3.2, Appendix), not a spec sign flip.

---

## 1. R4 decision — rebuild `ImmPtlViewArea` on 26.2 (the fidelity default; C3 user checkpoint)

**DECISION (default): rebuild `ImmPtlViewArea` as a `ViewArea` SUBCLASS installed by redirecting the
`new ViewArea` construction, backed by a MOD-OWNED unbounded store, with the install gated on a GLOBAL
FLAG.** This is the COMPLETE-IP-fidelity default; it retires the mod's documented latent bug (the
pinned-bounded array collides when two same-dim portal dests are >71 chunks apart — API_RISKS R4
"Solved by mod?"; memory `walking-limbo-seed-overclaim` fix #5). **C3 is a USER CHECKPOINT** — the
user may elect the pinned-bounded deviation (conditional register entry F19) instead; absent that
election the default below is executed at S13.

### 1.1 Why the subclass+redirect is viable — SPIKE-R4-validated (CONFIRMED)

`SPIKE-R4-viewarea.md` retired the "can we even install and live through a `ViewArea` subclass on
26.2" risk with a real client-gametest run (`BUILD SUCCESSFUL`, no crash, five visually-normal
screenshot phases incl. a seamless nether crossing). Load-bearing facts the rebuild consumes:

- **Public 7-arg super ctor** `ViewArea(SectionRenderDispatcher, minY, maxY, minSectionY,
  maxSectionY, renderDistance, SectionOcclusionGraph)` (`26.2:ViewArea.java:19-27`); the main-thread
  `IllegalStateException` assert (`:31-33`) passes from the subclass on the Render thread. The
  1.21.3 ctor's `Level`/`LevelRenderer` params are GONE — a subclass that needs them sources them
  itself.
- **Every hot accessor an ImmPtlViewArea leans on is overridable:** `repositionCamera(SectionPos)`
  public (`:74`), `getRenderSectionAt(BlockPos)` public (`:87`), `getRenderSection(long)` protected
  (`:91` — the NEW hot accessor, 258k calls on the nether instance by run end), `releaseAllBuffers()`
  public (`:40`). All PROVEN reached at real per-frame rates through the subclass.
- **The NEW-redirect fires at the moved construction site:** `new ViewArea` moved to
  `LevelRenderer.invalidateCompiledGeometry` (`26.2:LevelRenderer.java:819-827`, method `:796-832`),
  invoked from `LevelExtractor.extract` when `shouldInvalidateCompiledGeometry` is consumed
  (`LevelExtractor.java:122-124`), set by `allChanged()` (`:406-416`) on first extract, `setLevel`,
  and F3+A. The spike `@Redirect(method="invalidateCompiledGeometry", at=@At(value="NEW", target=...))`
  installed the subclass for BOTH the main renderer and the mod's secondary `LevelRenderer`s
  (26.2 retarget of IP's 1.21.3 `allChanged` NEW-redirect, `IP:MixinLevelRenderer.java:322-345`).

### 1.2 What the rebuild must own (the real R4 work, NOT the install)

The install is retired; the work concentrates in **storage semantics** and **occlusion-graph
coupling** (SPIKE-R4 §3, §6):

1. **Slot-stable vs coord-stable identity inversion.** 26.2's `RotatingSectionStorage.repositionCenter`
   REUSES `RenderSection` objects and mutates their `sectionNode` in place
   (`26.2:RotatingSectionStorage.java:44-71`, reassign `:60-64`). IP's presets design kept
   `RenderSection`s PINNED to coordinates and swapped whole arrays per camera cell. The mod-owned
   unbounded store must present coord-stable identity to `VisibleSectionDiscovery.rawFetch`
   (`IP:...:155`), `MixinLevelRenderer` `rawGet` casts (`:253,286,297,503-512`), and
   `ClientDebugCommand.getManagedSectionNum` (`:828-830`) while satisfying 26.2's callers.
2. **Fixed-size occlusion coupling (hard constraint).** `SectionOcclusionGraph.GraphStorage` sizes
   `SectionToNodeMap` from `viewArea.size()` and the `Octree` from
   `getCameraSectionPos()/getViewDistance()/sectionCount()/minY()`
   (`26.2:SectionOcclusionGraph.java:426-430`), rebuilt only at `waitAndReset` (per invalidate). An
   "unbounded" grid MUST still advertise **bounded, stable** answers from those getters — exactly as
   IP's presets did (grid size fixed at `sectionGridSize*`; only array CONTENTS swapped). The store is
   unbounded in COVERAGE (no >71-chunk collision), not in advertised dimensions.
3. **Thread-safety on BOTH accessors.** `getRenderSectionAt` AND `getRenderSection(long)` are called
   off-thread (SPIKE-R4 E2: `Worker-Main-*`; SectionOcclusionGraph full-update on
   `Util.backgroundExecutor()`). IP's 1.21.3 "may be accessed from another thread" note
   (`ImmPtlViewArea.java:431`) now covers the new hot accessor too.
4. **`setDirty` has no ViewArea seam** — dirty tracking externalized to
   `SectionUpdateTracker.SectionDirtyState` owned per-`LevelExtractor`, recreated in `allChanged`
   (`26.2:LevelExtractor.java:411`), consumed via the extract read-schedule-clear flow. The ImmPtl
   dirty-marking equivalent hooks the tracker/extractor, NOT ViewArea (render-core C28). This pairs
   with the §5 extract()/compileSections invariant.

### 1.3 Install gating — GLOBAL FLAG, never `mc.levelRenderer` identity (SPIKE-R4 §4-S1)

The redirect MUST gate on a global flag, mirroring IP's `IPCGlobal.useHackedChunkRenderDispatcher`
(`IP:MixinLevelRenderer.java:~335`), **never on `Minecraft.levelRenderer == this` identity**. SPIKE-R4
proved the identity check is unreliable: the mod swaps `Minecraft.levelRenderer` per portal-render
frame (`@Mutable` accessor, `MOD:mixin/client/MinecraftAccessorMixin.java:33-35`), so the nether
secondary's ctor self-identified as "MAIN". Any S11+ diagnostics/asserts key on instance identity or
dimension, never on `mc.levelRenderer` equality.

- Install gate = `entityPortals` (the exclusivity-flag; §6). Flag-OFF → the redirect is inert and
  vanilla `new ViewArea` runs unchanged (block-era render untouched). Flag-ON → the subclass installs
  for main + secondaries.
- **`@Redirect` exclusivity caveat (S11 one-line decision):** IP upstream uses plain `@Redirect`,
  which claims the instruction exclusively and conflicts if a third-party mod redirects the same
  `new ViewArea`. Ship plain `@Redirect` for fidelity; if a real conflict surfaces post-cutover,
  a `@WrapOperation` variant is the fallback (NOT tested by the spike — noted, not adopted).

### 1.4 `earlyRemoteUpload` per-dispatcher pump — NEEDS-RUNTIME-VERIFICATION (render-core G26)

IP's `earlyRemoteUpload` (`IP:MyRenderHelper.java:458-469`) becomes a **per-secondary-dispatcher**
pump on 26.2: the upload path reworked to `lock()/uploadTerrainBuffersToGpu()/unlock()`
(`26.2:SectionRenderDispatcher.java:135-155`). **Whether the new staging path can still strand async
compiles without this pump needs runtime verification** (render-core G26; render-core cross-dim-pump
pair 8: `lateUpdateLight` survives 1:1, `earlyRemoteUpload` becomes the per-dispatcher upload lock).

- **NAMED CHECK:** at **S13 rung 1** (same-dim command portals) observe whether the secondary's
  freshly compiled sections upload without an explicit `earlyRemoteUpload` pump; at **S14** (cross-dim,
  first ACTUALLY-loading secondary — a ZERO-chunk cold dest) confirm under the load that first exposes
  stranded uploads. This is the check the plan (S11 §956) names; do not pre-commit the pump — land it
  ONLY if S13/S14 exhibits stranded uploads, and if landed, wire it beside `lateUpdateLight` at the
  render TAIL (§5).
- Interlock with §5: `earlyRemoteUpload` is the UPLOAD half; the COMPILE half is the mandatory
  `compileSections` drain after every `extract()` — the two are distinct and both may be needed.

---

## 2. R5 — reversed-Z depth/stencil sign-flip CHECKLIST (executable at S12)

**26.2 reversed-Z ground truth** (source-verified, the derivation basis for every row):
- **Depth CLEAR value = 0.0 = FAR** (`26.2:GameRenderer.java:404-408,439`). 1.21.3: clear 1.0 = FAR.
- **Default depth COMPARE = `GREATER_THAN_OR_EQUAL`** (`26.2:DepthStencilState.java:9`). 1.21.3
  ambient default: `LEQUAL`/`LESS`.
- **`glDepthRange(near_val, far_val)`** maps NDC z to the window-depth interval; direction of the
  interval is unchanged, but under the reversed-Z projection the FAR plane lands at window depth
  **0.0** and the NEAR plane at **1.0**. So "force a fragment to FAR" = `glDepthRange(0,0)`; "force to
  NEAR" = `glDepthRange(1,1)`; the default full mapping stays `glDepthRange(0,1)`.
- **`GL_ALWAYS` and all stencil ops/funcs are direction-INDEPENDENT** — they do not flip. `GL_LESS`
  inside `clampStencilValue` is a STENCIL comparison (`ref < stencil`), NOT a depth comparison — it
  does NOT flip (the `//NOTE GL_GREATER means ref > stencil` comment at
  `IP:RendererUsingStencil.java:254` is about the stencil ref, deliberately not depth).

The mod's LIVE `StencilPortalRenderer` (runtime-proven, memory `stencil-direct-rework-status`) already
carries the flipped values; each row cites both the IP 1.21.3 constant and the mod's proven 26.2
value. The port TRANSPLANTS the runtime-proven substrate into the qouteall `RendererUsingStencil`; the
checklist below is the exhaustive audit that no constant is missed (D4.4 — every flip carries a
written derivation).

### 2.1 The choreography, op by op (source: `IP:RendererUsingStencil.java`)

| # | IP op (site) | Category | Reversed-Z verdict | Derivation / mod-proven cite |
|---|---|---|---|---|
| 1 | `glClearStencil(0)` + `glClear(GL_STENCIL_BUFFER_BIT)` (`prepareRendering :98-99`) | stencil clear | **UNCHANGED** | Clears STENCIL only — the main-frame reversed-Z DEPTH buffer is deliberately preserved for the stencil-write depth test (#4). Stencil is depth-direction-independent. |
| 2 | `_enableDepthTest()` + `glEnable(GL_STENCIL_TEST)` (`:101-102`) | enable | **UNCHANGED** | Toggles, not comparisons. |
| 3 | `glStencilFunc(GL_EQUAL, outerLayer, 0xFF)` (`renderPortalViewAreaToStencil :177`) | stencil func | **UNCHANGED** | Stencil comparison. |
| 4 | view-area stencil-write draw depth test → `glStencilOp(KEEP,KEEP,INCR)` on depth-pass (`:180`) | **IMPLICIT depth compare** | **FLIP the ambient pipeline compare: `LEQUAL` → `GREATER_THAN_OR_EQUAL`. Write mask is NOT `false` — it follows IP's own `doModifyDepth`/`fuseView` resolution (TRUE non-fuse, FALSE fuse-view).** | IP wrote no explicit `glDepthFunc` here — it relied on the vanilla DEFAULT depth compare (1.21.3 `LEQUAL`) to increment stencil where the portal is VISIBLE (portal depth nearer than scene). 26.2's default flipped to `GEQUAL` and the depth buffer holds reversed values, so the port MUST draw this mesh through a pipeline set to `GREATER_THAN_OR_EQUAL` (the compare flip). **The depth WRITE mask is a separate, non-flipped decision that follows IP verbatim:** this op is IP's `renderPortalArea(portal, …, doModifyColor=true, doModifyDepth=true, doClip=true)` (`IP:RendererUsingStencil.java:190-196`), and with `doModifyDepth=true` a NON-fuse portal does `GlStateManager._depthMask(true)` = WRITES depth (`IP:ViewAreaRenderer.java:51-57`; only a `fuseView` portal masks it off). So `writeDepth = doModifyDepth && !fuseView` = **TRUE for a non-fuse portal (the common case), FALSE only for a fuse-view portal** — the exact resolution the authored `MOD-qouteall:ViewAreaRenderer.java:53-64` + `fragments/S11B-viewarea.md` §3 feed into `DepthStencilState(GREATER_THAN_OR_EQUAL, writeDepth)`. **Compare-flip proven (write-mask NOT prescribed by this cite):** `MOD:PortalRenderTypes.java:110` `DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)` — comment "26.2 reversed-Z: GEQUAL = 'in front' (was LEQUAL); no write". That line proves ONLY the `LEQUAL`→`GEQUAL` COMPARE flip; its `false` write-mask is the block-era stencil-write pipeline's OWN choice, **NOT** the IP prescription for this view-area mesh — the S12 pipeline takes its write mask from the `writeDepth` resolution above, never from this block-era constant. **This is the easiest flip to miss** (no explicit compare constant in IP), and conflating it with a false write-mask would steer the S12 executor/verifier into a non-IP write mask + a false checklist mismatch against `ViewAreaRenderer`. |
| 5 | `glStencilMask(0xFF)` (`:185`) | stencil mask | **UNCHANGED** | — |
| 6 | `glDepthFunc(GL_ALWAYS)` (`clearDepthOfThePortalViewArea :214`) | depth func | **UNCHANGED (`ALWAYS`)** | ALWAYS passes regardless of direction. Port to `CompareOp.ALWAYS_PASS`. |
| 7 | **`glDepthRange(1, 1)`** (`clearDepthOfThePortalViewArea :217`) | **depth range = FAR write** | **FLIP → `glDepthRange(0, 0)`** | IP writes window depth 1.0 = FAR (1.21.3). 26.2 reversed-Z FAR = 0.0. Purpose: push the opening's depth to FAR so the dest terrain, drawn next under `GEQUAL`, ALL passes (nothing z-rejects it). **Proven:** `MOD:StencilPortalRenderer.java:401` `glDepthRange(0,0)` in the stencil-direct branch, with the explicit comment `:391-392` "26.2 reversed-Z: FAR = 0.0, written via glDepthRange(0,0)". |
| 8 | restore `glDepthFunc(originalDepthFunc)` (`:223`) | restore | **UNCHANGED** | Save/restore of the queried prior func. |
| 9 | restore `glDepthRange(0, 1)` (`:224`) | depth range restore | **UNCHANGED** | Returns the default full NDC→window mapping; direction-independent. **Proven:** `MOD:StencilPortalRenderer.java:403` restores `glDepthRange(0,1)` (NOT (0,0)). |
| 10 | `setStencilLimitation` → `glStencilFunc(GL_EQUAL, layer, 0xFF)` + `glStencilOp(KEEP,KEEP,KEEP)` (`:288-291`) | stencil | **UNCHANGED** | — |
| 11 | `glDepthFunc(GL_ALWAYS)` (`restoreDepthOfPortalViewArea :235`) | depth func | **UNCHANGED (`ALWAYS`)** | — |
| 12 | re-render view-area mesh writing the portal-plane's REAL projected depth (`:237-244`) | projected depth | **IP: NO explicit constant (mesh drawn at real projected depth through the reversed-Z projection). MOD re-expression: a flat NEAR shield, `glDepthRange(1,1)`** | The projected depth is already reversed-Z (comes from `cameraRenderState.projectionMatrix`). **Both driver modes DO write a restore-phase depth** — the mod re-expresses IP's op #12 (re-render the mesh at its REAL projected depth) as a flat **NEAR shield** over the whole opening: `glDepthRange(1,1)` = NEAR = 1.0, so later main-frame passes (clouds/weather/translucent) FAIL `GEQUAL` inside the opening and cannot overdraw the composited/direct dest view. **Stencil-DIRECT** (C3 default) writes the shield at **STEP 3.7, AFTER** the direct dest terrain (`MOD:StencilPortalRenderer.java:472`, comment `:464-471` "Mirrors IP restoreDepthOfThePortalViewArea"); the **FBO-mode** renderer (A1) writes the equivalent NEAR shield at **STEP 3.5, BEFORE** its composite (`MOD:...:408`, comment `:405-407`). **This NEAR restore (range (1,1)) is the OPPOSITE direction from the stencil-direct FAR clear (#7 / STEP 3.5, range (0,0), `:401`); the mod documents both directions at `:391-394`. Do not conflate the FAR clear (#7) with the NEAR restore (this row).** IP nuance NOT reproduced by the flat shield: IP occludes only geometry BEHIND the portal plane (real projected depth), the flat shield occludes ALL later passes inside the opening — sign-equivalent for composite protection; the exact-projected-depth form is an S13 driver-core option, not an S12 constant. |
| 13 | restore `glDepthFunc(originalDepthFunc)` (`:246`) | restore | **UNCHANGED** | — |
| 14 | `clampStencilValue`: `glStencilFunc(GL_LESS, max, 0xFF)` + `glStencilOp(KEEP,REPLACE,REPLACE)` (`:258-261`) | **stencil** compare | **UNCHANGED** | `GL_LESS` here is `ref < stencil` — a STENCIL comparison, NOT depth. Does not flip. |
| 15 | `clampStencilValue`: `glDepthMask(false)` + `_disableDepthTest()` + `renderScreenTriangle()` + re-enable (`:264-277`) | depth mask/enable | **UNCHANGED** | Stencil-only pass; depth is masked off and test disabled — no comparison to flip. |
| 16 | `replaceFrameBufferClearing`: `depthMask(false)` → `renderScreenTriangle(fogColor)` → `depthMask(true)` (`:40-43`) | dest sky/fog fill | **UNCHANGED** | Color-only fill of the stencil region with the dest world's fog color; touches no depth comparison. (The fog COLOR source is R9 §3.) |

### 2.2 Depth clamp — raw-GL toggle, keep verbatim (adjacent to R5)

`IP:CHelper.java:138-148`: `disableDepthClamp()`/`enableDepthClamp()` do
`GL11.glDisable/glEnable(GL32.GL_DEPTH_CLAMP)`, gated on `IPGlobal.enableClippingMechanism`.

- **Verdict: UNCHANGED, keep the raw-GL toggle verbatim.** Depth clamp clamps NDC z to `[near,far]`
  and is direction-independent. **26.2 has NO vanilla depth-clamp toggle** (API_RISKS R5; render-core
  point 7 "depth clamp is raw-GL only"; grep zero). The raw `GL32.GL_DEPTH_CLAMP` call stays exactly
  as IP wrote it — it is stable LWJGL, below the vanilla abstraction (R6 "raw GL stays", consistent
  with `GlQueryObject`/`QueryManager` verbatim-ported in S11-A).
- **Vulkan-gap degrade:** under the Vulkan backend (`26.2:com/mojang/blaze3d/vulkan/VulkanBackend.java`)
  the raw-GL depth-clamp toggle silently no-ops → the front-clip plane loses its clamp. Documented as
  part of the F18 degrade note (§2.4); the clip mechanism itself (`FrontClipping` +
  `GlCommandEncoderClipMixin`, gl_ClipDistance) also degrades under Vulkan. No fix — degrade only.

### 2.3 The reversed-Z FRUSTUM hazard (SPIKE-R1 §1.5 — spec invariant, not a choreography constant)

Not a choreography op but a depth-sign hazard the driver core MUST honor: **any portal/secondary cull
`Frustum` that can reach `SectionOcclusionGraph.addSectionsInFrustum` (i.e. any virtual camera without
a captured frustum whose extract runs `applyFrustum`) MUST be built from a conventional-Z culling
projection — mirror `Camera.createProjectionMatrixForCulling()` (`26.2:Camera.java:179-189`) — NEVER
from `cameraRenderState.projectionMatrix`.** SPIKE-R1 proved that feeding the reversed-Z render
projection hard-hangs the render thread FOREVER in `Frustum.offsetToFullyIncludeCameraCube` (the
z-row of the reversed-Z matrix collapses to ~+1.2e-5, so the camera-cube step loop never terminates —
deterministic, not a numerics fluke). The mod's native-render path carries this latently
(chip spawned, out of migration scope) — the ported driver must not inherit it.

### 2.4 F18 — Vulkan-gap degrade path for `PortalRenderInfo`'s occlusion-query consumer

`IP:PortalRenderInfo.renderAndDecideVisibility(Portal, Runnable)` (`:212-257`) decides whether a
portal's view area is visible using GL occlusion queries — `GlQueryObject.performQueryAnySamplePassed`
/ `fetchQueryResult` via `QueryManager` (`GL_ANY_SAMPLES_PASSED`, raw GL; ported VERBATIM in S11-A,
R6 "raw GL stays"). GPU queries on 26.2 are timestamp-only at the abstraction layer
(`26.2:GpuDevice.java:184`), so `QueryManager` correctly stays raw GL — **but under the Vulkan backend
every raw-GL occlusion query silently returns garbage** (API_RISKS R5: "every raw-GL mechanism …
occlusion queries … silently does nothing under it").

**DEGRADE PATH (F18):** detect the active backend once (GL vs Vulkan — via the `GpuDevice`/backend
type, cached at client init). When the backend is NOT OpenGL:
- **Bypass the query entirely and treat every portal as VISIBLE** (`decision = true`). Concretely,
  short-circuit `renderAndDecideVisibility` to run `queryRendering.run()` (so the view-area stencil
  still writes) and return `true`, skipping `performQueryAnySamplePassed`/`fetchQueryResult` and the
  `lastFrameQuery`/`thisFrameQuery` prediction bookkeeping.
- **Never gate portal rendering on an untrusted query result.** The cost is losing the
  occlusion-culling optimization (portals behind walls still render their view) — a PERFORMANCE
  degrade, never a correctness/visibility regression. This preserves the invariant "flag-ON never
  renders LESS than flag-OFF would show".
- Under OpenGL (the shipped-and-tested path) the verbatim query path runs unchanged.

This is the only F18 consumer in the render slice; the same backend-probe also documents the
depth-clamp (§2.2) and clip-plane Vulkan gaps.

---

## 3. R9 — per-dimension fog / environment design (resolves the S11-A UNKNOWN-NEEDS-DESIGN)

S11-A RE-EXPRESSED `FogRendererContext` onto 26.2 and **FLAGGED** the live per-layer buffer question
to this spec (`S11A-render-context.md` §4). This section resolves it with a concrete design.

### 3.1 What S11-A already settled (do not re-litigate)

- **Fog COLOR probe is ring-buffer-safe.** `getFogColorOf(destWorld)` is re-expressed onto the
  instance `FogRenderer.setupFog(Camera,int,DeltaTracker,float,ClientLevel)` reading `FogData.color`
  (`26.2:FogRenderer.java:167-186`; `FogData.java:15`). `setupFog` only COMPUTES a fresh `FogData`; it
  does NOT write the single per-frame WORLD ring-buffer slot (that is `updateBuffer`, `:188`) — so the
  mid-frame color probe cannot corrupt later passes. Reached via the mod's existing
  `GameRendererAccessorMixin.seamlessportals$getFogRenderer()`.
- **`StaticFieldsSwappingManager` has nothing left to swap for fog** — "compiles as-is" (render-sub
  G3); its `FogRendererContext` instance fields (`red/green/blue`, `target/previousBiomeFog`,
  `biomeChangedTime`) are VESTIGIAL snapshots kept for structural fidelity only.
- **Lightmap is ALREADY solved** by the S11-A-authored `DimensionRenderHelper` (the proven 26.2
  `Lightmap` + `LightmapRenderState` extraction, driven by `updateAndRender(virtualCamera,
  partialTicks)` at the context switch; per-dim instances in `ClientWorldLoader.RENDER_HELPER_MAP`).
  R9's lightmap half is closed — this section is fog + environment only.

### 3.2 The design — per-dim FogRenderer/FogData buffer ownership

**The hazard:** 26.2 fog is an instance `FogRenderer` producing a `FogData` UBO written into a **single
WORLD ring-buffer slot per frame** (`26.2:fog/FogRenderer.java:55-83,167-202`). Rendering the DEST
world's fog into that same slot mid-frame (during the recursive `renderPortalContent`) corrupts the
later main-world passes that read the slot. The color probe (§3.1) is safe because it never writes the
slot — but the **driver core's dest-world render pass DOES need the dest fog written to a buffer the
dest render reads**.

**DECISION — the secondary render gets its own `FogRenderer` instance, keyed per rendered dimension,
never touching the main-world ring-buffer slot.** Mirror the ownership model already proven for
lightmap (`DimensionRenderHelper` per-dim instances in `ClientWorldLoader.RENDER_HELPER_MAP`):

1. **Own `FogRenderer` per secondary dimension.** Each secondary render context owns a `FogRenderer`
   instance (constructed alongside the per-dim `LevelRenderer`/`LevelExtractor`/`DimensionRenderHelper`
   in `ClientWorldLoader`, same lifecycle, same per-dim map). The dest render pass sets fog through the
   secondary's own `FogRenderer` → its own `FogData` → its own `MappableRingBuffer` slot. The
   main-world `FogRenderer`'s single WORLD slot is NEVER written during a portal-view pass. This is the
   general answer for "a second rendered world needs its own buffer/instance" (render-sub G2;
   API_RISKS R9 "UNKNOWN-NEEDS-DESIGN on ownership" → SETTLED here).
   - **If per-instance `FogRenderer` ownership proves impractical** (e.g. a private final field the
     render path hard-reads), the fallback is a single mod-owned auxiliary `MappableRingBuffer`
     swapped in for the dest pass and swapped out before control returns to the main frame — the
     `StaticFieldsSwappingManager` push/pop shape applied to the ONE buffer slot rather than the whole
     `FogRenderer`. The instance-per-dim form is preferred (matches lightmap ownership); the
     swap-the-slot form is the documented fallback. **Which is feasible is the ONE runtime check named
     for S13/S14** (the color probe already runs at S12; the buffer-ownership choice needs the live
     dest render pass to confirm no main-frame slot corruption — watch for main-world fog flicker
     after a portal frame).

2. **`EnvironmentAttributeProbe` isolation.** The old static fog-smoothing state is now the per-Camera
   `EnvironmentAttributeProbe` (`26.2:Camera.java:80,87`). The secondary render already uses its OWN
   virtual `Camera` (the mod's proven recipe: bare `new Camera()` + `setLevel(secondary)` +
   `setEntity` + `initialized=true`, SPIKE-R1 §1.3), which carries its OWN `attributeProbe()` — so the
   dest world's `SKY_LIGHT_*` / `AMBIENT_LIGHT_COLOR` smoothing state is naturally isolated from the
   main camera's probe. **Invariant: the secondary render MUST use its own virtual Camera (never the
   main `mc.gameRenderer.mainCamera()`), so its `EnvironmentAttributeProbe` never poisons the main
   camera's smoothing.** `DimensionRenderHelper` already reads `SKY_LIGHT_*`/`AMBIENT_LIGHT_COLOR` +
   `bossOverlayWorldDarkening` from this probe (S11-A §3 tick-side parity) — the fog design reuses the
   same isolated probe, no new mechanism.

3. **`FogEnvironment` shared-mutable isolation.** The `FogEnvironment` instances
   (`AtmosphericFogEnvironment.rainFogMultiplier`, `26.2:fog/environment/AtmosphericFogEnvironment.java:26`)
   are MUTABLE and SHARED — the same cross-dimension leak shape as the old statics, new home
   (render-sub G2; mixin-client MixinFogRenderer row). **Design:** the dest fog computation runs
   through the dest `ClientLevel`'s own dimension effects, so the dest's `FogEnvironment` selection is
   data-driven per dimension (`DimensionType.Skybox` + `EnvironmentAttributes`; render-core G30-G32).
   Where a `FogEnvironment` instance's mutable smoothing field (e.g. `rainFogMultiplier`) would be
   written by the dest pass, treat it as part of the swap set: snapshot-and-restore around the dest
   render (the `StaticFieldsSwappingManager` pattern applied to the specific mutable field), so a
   dest-world rain-fog value never bleeds into the main-world's next pass. This is the SAME
   push/restore discipline as the fog-buffer fallback (1) — one swap bracket around the dest pass
   covers buffer slot + FogEnvironment mutable fields together.

4. **Fog-OFF for the portal-content pass** where IP disables fog uses vanilla's own empty-fog buffer:
   `getBuffer(FogMode.NONE)` (`26.2:FogRenderer.java:55-63,82`) — no custom buffer needed there.

### 3.3 Phase assignment (fog reads happen during the dest render pass)

Fog setup for the dest is part of the RECURSIVE render pass (`renderPortalContent` →
`MyGameRenderer.switchAndRenderTheWorld` → the re-invoked `renderLevel`), NOT the pre-render pump.
It runs GPU-side inside the dest render, after the dest `extract` populated the dest camera state.
This lands it on the "inside render = GPU-only" side of the §4 extract-vs-render phase split — the dest
fog must be set from the dest world's extracted state, so it is bound to the dest render invocation,
never the main-frame extract. The vestigial color-snapshot fields (§3.1) carry nothing across the
phase boundary.

---

## 4. R2/A4 anchor — restated from S3's LIVE soak-proven decision

R2 (frame-phase pump relocation) and A4 (upkeep re-home) are NOT open questions for this spec — they
were DECIDED and shipped LIVE at S3 and have soaked under block portals since (every BASELINE-SANITY
run from S3 onward re-exercises them; `S03-frame-anchor.md`). Restated here as the atomic-commit's
frame-phase contract because the S13 flag-dispatch wires into this exact host.

### 4.1 The decided anchor (soak-proven)

- **Anchor: inside `Minecraft.renderFrame(Z)V`, BEFORE the `gameRenderer.update(deltaTracker)` call**
  (`26.2:Minecraft.java:1290`), via `@At(value="INVOKE",
  target="GameRenderer.update(DeltaTracker)")`, no shift — the portal-animation.md #14 anchor. Host =
  the MOD-OWNED `MOD:mixin/client/MinecraftFramePumpMixin` (which replaced the deleted
  `GameRendererFrameCrossingMixin` at `GameRenderer.update` HEAD).
- **Why this and not `GameRenderer.update` HEAD:** both precede camera positioning (26.2 moved
  `mainCamera.update` into `GameRenderer.update`), but only `renderFrame` reproduces IP 1.21.3's
  **panorama non-firing** — `grabPanoramixScreenshot` calls `gameRenderer.update` at
  `26.2:Minecraft.java:2779` DIRECTLY, bypassing `renderFrame`, so a crossing check never fires
  mid-panorama (matching IP, whose 1.21.3 panorama path did not route through its `GameRenderer.render`
  HEAD hook). Documented fallback if a crossing-seam regression appears in soak: revert to
  `GameRenderer.update` HEAD.
- **This anchor is the R1-verdict's "design-complete before the atomic commit" satisfied for R2.**

### 4.2 The extract-vs-render phase assignment (the single largest semantic change in the slice)

Mixin-client cross-cut 3 calls the extract-vs-render phase decision "the single largest semantic
change in the slice": **every IP handler that mutated world/camera state mid-render must pick a phase
— BEFORE extract (affects the frame's captured state) or INSIDE render (GPU-only).** The rule, from
the soak-proven S3 anchor:

- **BEFORE extract (at the `renderFrame` pre-`update` pump):** the pre-render chain that must affect
  what the frame captures — `RenderStates.updatePreRenderInfo` → `StableClientTimer.update` →
  `ClientPortalAnimationManagement.update()` → `ClientTeleportationManager.manageTeleportation(false)`
  (the crossing/teleport — a render-time teleport must render the crossing frame from the DESTINATION,
  the seamless-crossing property), plus the A4 upkeep (staged-upload flush, adoption prune, bridge
  repaint pump). These run at the earliest render-thread point in the frame — before `update`
  (`:1290`), `extract` (`:1295`), AND `render` (`:1302`) — strictly OUTSIDE any in-flight framegraph
  (the A4 GPU-upload-safety requirement, S3 §4).
- **INSIDE render (GPU-only, during the recursive dest pass):** everything that only affects pixels —
  the stencil choreography (§2), the dest fog setup (§3), the per-portal view-area draws, the
  clip-plane uploads. These are bound to the dest render invocation and read the dest's extracted
  state.
- **The A4 upkeep ordering caveat is SETTLED (S3 §4):** the flush issues GL uploads and must run
  outside the framegraph; the `renderFrame` pre-`update` site is pre-framegraph AND pre-extract —
  strictly earlier than the old renderLevel-HEAD site and equally GPU-safe (live GL context, no
  in-flight pass). The phase-1 FBO dest render (FBO renderer only, A1) STAYS at `renderLevel` HEAD
  because it reads the extracted camera render-state.

### 4.3 The S13 flag-dispatch shape (already scaffolded at S3)

`MinecraftFramePumpMixin` is the MOD-OWNED shared host; its injected body keeps the block-era pump as a
single ordered unit so S13 wraps it without restructuring (D3 shared-host rule — mod code dispatches,
IP-ported bodies live in their own branch, never flag-polluted):

```java
if (entityPortals) {
    // ported IP pre-render chain (held/ported; IP MixinGameRenderer.java:86-96 analog):
    //   RenderStates.updatePreRenderInfo -> StableClientTimer.update
    //   -> ClientPortalAnimationManagement.update
    //   -> ClientTeleportationManager.manageTeleportation(false)
    //   -> [earlyRemoteUpload IFF §1.4 runtime check requires it]
} else {
    SeamlessClientTeleport.checkCameraCrossingPerFrame();          // block-era, LIVE
    if (level != null) StencilPortalRenderer.frameUpkeep();
}
```

---

## 5. Spec-level INVARIANTS — the extract()/compileSections pairing rule + the SOG delta feed

These are non-negotiable invariants for every stage that drives a secondary render (S13 onward). They
encode hard-won mod lessons that a verbatim IP port would otherwise re-break.

### 5.1 The extract()/compileSections pairing RULE (memory `ow-holes-consumed-compile-queue`)

**INVARIANT: every `LevelExtractor.extract()` on a secondary MUST be paired with a `compileSections`
drain.** `extract()` QUEUES a section compile AND CONSUMES the section's one-shot dirty flag; if the
matching `render()` (the only queue consumer) never runs, the section is stranded
`dirty=false + UNCOMPILED` — a permanent hole. The mod's OW-holes bug was exactly this: stencil-direct
ran `extract()` but never `render()`, and the honest resend-suppression then FOSSILIZED the loss. The
fix — and the invariant — is to invoke the real `compileSections` after EVERY dest `extract()`.

- **RULE (verbatim from memory):** *an honest "client holds X" ledger still fossilizes client
  RENDER-state loss; `extract()` must always be paired with a `compileSections` drain.*
- Concretely, the ported driver's dest-render path must, after each secondary `extract()`, drain the
  compile queue (the mod's proven budgeted `VisibleSectionDiscovery` scheduler — PORT-FORWARD as-is
  per S11-A §5's A3 verdict: it is a REQUIRED 26.2 adaptation, NOT re-derivable from IP's
  `ForceMainThreadRebuild`, which is an inert additive latch). This pairing is DISTINCT from §1.4's
  upload pump: compile (this rule) vs upload (`earlyRemoteUpload`) are two separate drains.
- The R4 rebuild's dirty-marking (§1.2 item 4) hooks the `SectionUpdateTracker`/`LevelExtractor` flow
  — it must feed this same compile drain, never strand a dirty section with no consumer.

### 5.2 The SOG delta feed (memory `distant-chunk-vanish-sog-desync`)

**INVARIANT: the secondary's `SectionOcclusionGraph` must receive the `ClientChunkCache`
loadedChunks delta feed, and partial-update suppression must not discard it.** The 26.2 delta protocol
(`26.2:ClientChunkCache.java:180-207` → `LevelExtractor.java:138-142` → SOG) did not exist in 1.21.3
and has no IP analog (chunk-loading "the largest genuinely new work item"; R13f). The mod-proven
`SeamlessClientChunkMap` (installed via `ClientLevelChunkSourceAccessor`) IS this delta feed; the
post-return distant-chunk vanish + walking-limbo bands were stencil-direct DISCARDING the dest
extract's chunk deltas (phantom holes) + truncated rebuild at bridge handback + a 30s partial-update
suppression window.

- Port `SeamlessClientChunkMap` + the SOG delta feed + store-center pinning FORWARD into the ported
  `ClientWorldLoader` (R13f; current-mod-core §5). The R4 rebuild (§1) supersedes the store-center
  PINNING (its unbounded store retires the >71-chunk collision), but the DELTA FEED itself stays.
- **First-rebuild latch:** the first SOG rebuild at bridge handback must not be truncated
  (`distant-chunk-vanish` fix). Any "client holds X" resend-suppression ledger must be ACK/predicate
  -verified AND survive the client's retention lifetime (memory `walking-limbo-seed-overclaim` RULE) —
  a bare radius-square seed fossilizes holes.

### 5.3 Supporting invariants (carried from S11-A / prior lessons)

- **`endFrame()` on pooled/secondary buffers is REQUIRED 26.2 additive** — never dropped as "not in
  IP" (memory `gpu-buffer-leak-endframe`). IP's private pool folds inline into the qouteall
  `MyGameRenderer` (`acquire/returnRenderBuffersObject`) verbatim + carries `endFramePooled()` wired to
  the render TAIL (S11-A B5/B6; registered as additive forced deviation so the diff-gate does not flag
  it).
- **`lateUpdateLight` stays at frame-END** (`runLightUpdates` on each live secondary at the render TAIL,
  paired with tick-side `pollLightUpdates`), NOT mid-tick (memory `portalview-light-engine-half-port`;
  the qouteall port drops the mod-only `isDestScopeLive` perf gate, S11-A B7).
- **No per-frame `LOGGER` on the render thread in the ports** (memory
  `render-thread-logging-log4j-stall`) — the mod's block-era diagnostics
  (`FrontClipping.setupOuterClipping` count-gated logs, `PortalRenderBuffersPool.acquire`, shader-patch
  dumps) must NOT be transplanted into the hot-path qouteall ports.
- **`FrontClipping` `Vector4f.mul(Matrix4fc)` = column form M·v, CORRECT** — never "fix" to
  `mulTranspose` (D4.4; the `IP_DEVIATIONS_ANALYSIS.md:88-89` "row-vector" note is WRONG). The
  always-on KEEP mixins (`GlCommandEncoderClipMixin`, `ShaderManagerCompilationCacheMixin`) bind to a
  SINGLE clip-plane source of truth — the qouteall `FrontClipping`/`ShaderCodeTransformation` ports
  adopt the mod's `getPlaneX/W` + `UNIFORM_NAME` view-space representation (S11-A B1/B2).

---

## 6. The ATOMIC-CUTOVER contents — what flips at S13, and the flag-gated dual-path dev model

### 6.1 Why it is one cutover (API_RISKS VERDICT §2, restated)

IP's renderer discovers portals by iterating `world.entitiesForRendering()` for `Portal` instances
plus `GlobalPortalStorage` (`IP:PortalRenderer.java:84-110`); its view-area mesh is shape-polymorphic
off the Portal entity (`Portal.renderViewAreaMesh` → `PortalShape.renderViewAreaMesh`,
`IP:Portal.java:877-889`). The mod's block-era `StencilPortalRenderer`/`PortalShapeRenderer`/
`PortalContextSwitch` trio consumes block-portal geometry + FBO-identity discovery. **Neither renderer
can consume the other's portal representation**, the representation is global per world (a world is
block-portal OR entity-portal, not both per portal), and the shared mutable seams — one
`LevelRenderState`, one `visibleSections` list, one fog UBO slot, one stencil choreography — forbid two
active portal-render drivers in one frame. So the driver swap is indivisible.

### 6.2 What flips ON at S13 (the cutover set)

Registered/wired in the SAME commit that sets `ip_scc_closed=true` + registers the IP mixin set:

1. **Portal ENTITY discovery** — the ported `PortalRenderer.getPortalsToRender` iterating
   `world.entitiesForRendering()` for `Portal` + `GlobalPortalStorage`, replacing FBO-identity block
   discovery. Portal entity-type family + `PortalPlaceholderBlock` + argument types + payload
   registrations are UNCONDITIONAL from S13 (registries must not differ between flag states or world
   saves break on flips — D3).
2. **The renderer swap** — `PortalRenderer` + `RendererUsingStencil` (the §2 reversed-Z choreography),
   `ViewAreaRenderer` (shape-polymorphic mesh), `MyGameRenderer` + context_management (the swap set),
   `FrustumCuller`, `TransformationManager`'s crossing math. Entity-RENDERER registration
   (`PortalEntityRenderer` for the Portal entity-type family + `LoadingIndicatorRenderer`) rides the S0
   named renderer-registration seam (Appendix A.9), NOT the unported Fabric entrypoint — wired at S13
   step 5 so rung 1 can render any portal.
3. **The R4 ViewArea subclass install** (§1) — global-flag-gated redirect of `new ViewArea`.
4. **The pre-render pump's IP branch** (§4.3) — `entityPortals` flips `MinecraftFramePumpMixin` from
   the block-era pump to the ported IP pre-render chain.

### 6.3 The exclusivity ledger's flag-ON DRIVER SUPPRESSIONS (D3; `EXCLUSIVITY_LEDGER.md`)

Flag-ON suppresses the block-era DRIVERS, each gated `!entityPortals` in mod-owned code, landed in the
SAME S13 commit — so **at no point do two portal drivers run in one session** (the operational answer
to the verdict's one-driver-per-frame prohibition). The suppressed set:

- `LocalPlayerMixin` crossing detection; `EntityMixin` server detection + `PortalTeleporter`;
  `ProjectilePortalHandler`; `PortalManager`/`PortalDetector`/`PortalTracker` scans;
  `PortalChunkTracker` tick + `RedirectedPacketApplier`; `PortalEntityTracker`; `RemoteBlockUpdater`;
  `SeamlessClientTeleport`/`SeamlessServerTeleport` dispatch;
  **`StencilPortalRenderer`/`PortalContextSwitch`/`PortalWorldManager` entry** (the block-era render
  driver); `CameraTransitionHandler` (superseded by `TransformationManager`).

**Suppressions active in BOTH states:** the `handlePortal` cancel keeps vanilla nether-portal blocks
inert in flag-ON worlds until S16 replaces it with IP's structural suppression.

**Substrate KEEPs that apply ALWAYS (both flag states) — never suppressed:** the stencil FBO chain
(`GlBackendMixin`/`GlConstMixin`/`RenderTargetMixin`/`GlStateManagerMixin`), extractor plumbing
accessors, `GlCommandEncoderClipMixin`, `ShaderManagerCompilationCacheMixin`, `DimensionRenderHelper` +
`GameRendererLightmapMixin`, `PortalRenderBuffersPool.endFramePooled`, `lateUpdateLight`,
`FrontClipping`, diagnostics (F16). These are exactly the mechanisms the ported IP classes call into,
runtime-proven today under block portals.

### 6.4 The flag-gated dual-path dev model (the mitigation that makes "atomic" ≠ "big-bang blind")

The mod has already proven flag-gated dual render paths on 26.2 (`STENCIL_DIRECT`). The entity-portal
driver is built behind `entityPortals` (load-time, read once at mixin-plugin time by the KEEP'd
`SeamlessMixinConfigPlugin`; flip requires a game restart) and exercised against manually-spawned
Portal entities in a DEDICATED test world while the block path still serves normal play — **the
atomicity is in the user-facing switch, not the development process** (API_RISKS VERDICT §2
mitigation).

- Default `false` until S17; `true` from S17; the flag + ledger + holding machinery removed at S20.
- **Registries are unconditional from S13** → flag-ON testing happens in DEDICATED test worlds; never
  open a main world with the flag ON before S17.
- Rollback at any S13–S19 stage = `entityPortals=false` (or revert the stage's commits).
- **R13i re-anchor (easy to silently drop):** the fabulous/transparency suppression is an OVERRIDE to
  port, not a read — `Minecraft.useShaderTransparency()` HEAD-cancel re-anchors onto
  `GameRenderState.useShaderTransparency()` (`26.2:state/GameRenderState.java:17-19`; ducks G10). It
  rides the S12 client-mixin set; named here so the cutover checklist does not miss it.

### 6.5 The periphery that TRAILS the cutover (NOT in the S13 set)

Per VERDICT §3, these land AFTER the core cutover without invalidating it (additive; their absence at
cutover is status quo, not regression):

- **`CrossPortalEntityRenderer`** (R3 — the largest unknown; the mod ships nothing equivalent today).
  Its COMPILE-level design (per-entity clip bracketing on the R3 surface) is an S11 deliverable
  (`S11-R3-clip-bracketing.md`, D7); its RUNTIME wiring/verification trails to S18 (checkpoint C4).
- `GuiPortalRendering`, `OverlayRendering`, mirrors/`BreakableMirror`, the FBO fallback renderer +
  renderMode config (A1 — `RendererUsingFrameBuffer`/`RendererDummy`/`RendererDebug`, S12), IP's
  view-bob distance scaling (A2, S12), wand/debug overlays (Gizmos), portal-animation view refinements.

---

## Appendix — cross-references index

| Item | Decided here (§) | Executed at | Verified/signed at |
|---|---|---|---|
| R4 ViewArea rebuild (subclass+redirect, unbounded store) | §1 | S13 | C3 user checkpoint (default=rebuild) |
| R4 `earlyRemoteUpload` per-dispatcher pump necessity | §1.4 | S13 (land iff needed) | S13 rung 1 / S14 cold-dest (render-core G26) |
| R5 reversed-Z sign-flip checklist | §2 | S12 | S17 sign-off |
| F18 Vulkan-gap occlusion-query degrade | §2.4 | S12 | S18 (Vulkan-backend run, if available) |
| R9 per-dim fog/environment ownership | §3 | S12/S13 | S13 rung 1 / S14 (main-frame fog flicker watch) |
| R2/A4 frame anchor + phase split | §4 | LIVE since S3 | soaked S3→S17 |
| extract()/compileSections pairing + SOG delta feed | §5 | S13 onward | rung 1–4 bring-up (S13–S16) |
| Atomic-cutover set + exclusivity ledger flag-ON | §6 | S13 | 12-point regression at S17 |
