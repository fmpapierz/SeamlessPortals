# S13-H — THE DRIVER-CORE DESIGN CONTRACT (design doc; no code in this stage)

**Stage S13-H of the entity-portal migration — the design contract for the last inert link on the
command→pixels chain.** Every other link is live-proven (S13-G/attempt-6 evidence): portals spawn +
sync, the flag-ON dispatch fires (`AFTER_TRANSLUCENT_TERRAIN` → `PortalRenderer` lifecycle with the
correct `viewRotationMatrix`), `RendererUsingStencil` runs its full R5 choreography. What is missing
is the DEST-RENDER DRIVER CORE: `MyGameRenderer.switchAndRenderTheWorld`'s invoke is a bare
`client.gameRenderer.renderLevel(client.getDeltaTracker())` (`MOD-qouteall:MyGameRenderer.java:285`),
which re-renders the ALREADY-EXTRACTED MAIN-world state — `WorldRenderInfo.cameraPos` /
`cameraTransformation` are consumed by NOTHING, so the window shows the player's own view.

This document is the implementation contract for that core: the exact invoke sequence (§1), the
state-ownership map (§2), the P3 lifecycle-tail homes (§3), the invariant checklist (§4), and the
recursion/nesting model (§5). §6 lists the items that need a parent/spec decision.

**Ground truth consumed (all read end-to-end this stage):** the proven live core
`MOD:render/PortalContextSwitch.java` (`withSwitchedWorld` :569-664; `doFboRender` :1006-2098 — the
driver body :1533-2057; the sub-renderers :895-1000; `writeProjectionBuffer` :2378, `writePortalFogBuffer`
:2495); `MOD-qouteall:render/MyGameRenderer.java` (the shell); `CUTOVER_SPEC.md` §1-§6;
`S11B-render-drivers.md` §1/§4; `MOD-qouteall:ClientWorldLoader.java`;
`MOD-qouteall:render/renderer/{PortalRenderer,RendererUsingStencil}.java`;
`MOD-qouteall:render/VisibleSectionDiscovery.java`; `MOD-qouteall:render/context_management/
{WorldRenderInfo,RenderStates,PortalRendering}.java`; the wired dispatch
`fabric:SeamlessPortalsClientFabric.java:103-115`; the flag-ON P3 hosts
`MOD:mixin/client/{MinecraftFramePumpMixin,GameRendererMixin}.java`; IP originals
`IP:mixin/client/render/{MixinGameRenderer,MixinLevelRenderer}.java`; 26.2 surfaces
`26.2:GameRenderer.java:372-450,525-590`, `26.2:extract/LevelExtractor.java:95-199`,
`26.2:LevelRenderer.java:151,156-274,509,608`, `26.2:state/GameRenderState.java`,
`26.2:state/level/LevelRenderState.java`. Citation conventions as the slice docs
(`IP:` 1.21.3, `26.2:` mc262-ref, `MOD:` = `com/warwa/seamlessportals`, `MOD-qouteall:` = the ported tree).

---

## 0. ARCHITECTURE VERDICT — the invoke CANNOT stay a recursive `gameRenderer.renderLevel`; it becomes the stencil-direct decomposition

Three independent disqualifiers, each source-grounded, rule out "re-point state + recurse
`gameRenderer.renderLevel`" at the dispatch seam the port actually uses:

1. **Nested-framegraph hazard (block-era-proven).** The flag-ON dispatch fires from
   `AFTER_TRANSLUCENT_TERRAIN` — INSIDE `LevelRenderer.render`'s executing framegraph
   (`SeamlessPortalsClientFabric.java:103`; the qouteall chain is
   `onBeforeTranslucentRendering → doRenderPortal → renderPortalContent → renderWorldNew →
   switchAndRenderTheWorld → invoke`). A recursive `gameRenderer.renderLevel` re-enters
   `levelRenderer.render` (`26.2:GameRenderer.java:563-565`), which builds + executes ANOTHER full
   framegraph mid-execution — exactly the "heavy nested destRenderer.render disrupted the imported
   main target → overworld blanks" failure the block era diagnosed and designed around
   (`MOD:PortalContextSwitch.java:673-693`; the reason FBO-mode phase-1 moved to `renderLevel` HEAD
   and stencil-direct draws via `renderGroup`).
2. **Main-state corruption inside `renderLevel`.** 26.2 `GameRenderer.renderLevel` writes the single
   main `FogRenderer` WORLD ring-buffer slot (`this.fogRenderer.updateBuffer(cameraState.fogData)`,
   `26.2:GameRenderer.java:559` — the exact R9 corruption CUTOVER_SPEC §3.2 forbids), then clears the
   WHOLE depth texture and renders the hand (`:571-572`) — wiping the outer frame's depth that the
   R5 Row-12 restore and every later main pass depend on, once per portal per frame.
3. **The read path is hard-bound to the MAIN extracted state.** `renderLevel` reads
   `this.gameRenderState.levelRenderState.cameraRenderState` (`:532`), and
   `GameRenderState.levelRenderState` is `public final` (`26.2:GameRenderState.java:10`); the
   secondary `LevelRenderState`s live on the per-dim extractor/renderer pair instead
   (`MOD-qouteall:ClientWorldLoader.java:534-540`). Making the recursion read dest state would need a
   `@Mutable` swap of a final vanilla field PLUS fixes for (1) and (2) — three deviations to
   reproduce what the proven core already does without any of them.

**VERDICT: the invoke body is REPLACED by a re-expression of the runtime-proven stencil-direct
dest-draw sequence** (`MOD:PortalContextSwitch.java:1533-2057`, `stencilDirectMode` branch — the
C3-default, shipped-and-soaked mode; memory `stencil-direct-rework-status` /
`portal-stutter-is-mirror-fbo-cost`): extract → SOG delta feed → compileSections drain → armed
discovery → `prepareChunkRenders(destViewMatrix)` → fog/sky/clip → `renderGroup(OPAQUE)` → entities →
`renderGroup(TRANSLUCENT)` → clouds → restore. No framegraph is nested (`renderGroup` closes its
`RenderPass` in try-with-resources; raw stencil/clip state persists through `applyPipelineState` —
both facts runtime-proven, `MOD:PortalContextSwitch.java:1728-1734`, memory
`stencil-direct-rework-status`). This **is** IP's semantics on 26.2 mechanics: IP's recursive
`renderLevel` was, after IP's own mixins finished with it, exactly "sky + terrain layers with clip +
entities + translucent + clouds, masked by stencil, from the transformed camera, with vanilla
terrain-visibility replaced by `VisibleSectionDiscovery`" (`IP:MixinLevelRenderer.java:195-306`
cancels `setupRender` and brackets the layers) — the decomposition renders the same pass list in the
same order without the 26.2-only framegraph shell.

**Where the code lives.** One NEW additive class,
`qouteall.imm_ptl.core.render.SecondaryWorldRenderCore` (name final at implementation), owning the
sequence + the driver-core state of §2.2. `MyGameRenderer.switchAndRenderTheWorld` keeps IP's exact
invoke SHAPE — the runnable handed to `invokeWrapper` — but its body becomes
`SecondaryWorldRenderCore.renderDestWorld(newWorld, worldRenderer, newCamera, renderDistance)`
instead of the bare `renderLevel` call (`MyGameRenderer.java:280-287`). Like
`VisibleSectionDiscovery`'s armed fold and `endFramePooled` (S11-A B5/B6), this is a REGISTERED
additive forced deviation: it has no 1.21.3 analog because 26.2 split extract from render (G1), and
it must be registered so the S20 diff-gate does not flag it. DISCIPLINE BOUNDARY (S11-B §1) honored:
no `com.warwa` TYPE flows into a qouteall signature; the core reaches private vanilla members through
the PRE-EXISTING public `com.warwa` accessor-mixin interfaces exactly as the shell already does for
the lightmap (`MyGameRenderer.java:215`, sanctioned S11-B §6 "no new accessor") — accessor interfaces
are substrate, not driver state.

**The virtual-camera CONFIG (the `:81/:267` S13 hand-forward) lands in the shell, not the core:**
`switchAndRenderTheWorld` constructs `Camera newCamera` at `:207` and consumes it at `:268`
(`helper.updateAndRender(newCamera, …)` — the per-dim lightmap prime) BEFORE the invoke, so the
camera must be configured immediately after construction (§1 Step 1), or the first-visit lightmap
extract reads a zeroed camera (black fog/ambient — the exact failure
`MOD:PortalContextSwitch.java:1057-1061` documents).

---

## 1. THE EXACT INVOKE SEQUENCE (step-by-step, with the proven lines each step re-expresses)

Context already established by the SHELL before the invoke runs (verbatim IP, committed S11-B —
cited so the implementation does not duplicate it): `WorldRenderInfo` pushed
(`MyGameRenderer.renderWorldNew:172`); `mc.level = destLevel` + `mc.levelRenderer = destRenderer`
(`:233-234`); per-dim lightmap installed (`:235`); `noPhysics` on (`:237`); fog swapping-manager
pushed (`:239`); particles world swapped (`:240`); hit-result masked (`:241-246`); camera swapped to
`newCamera` (`:247`); pooled `RenderBuffers` swapped (`:249-259`); outer renderer's
`visibleSections` list saved + replaced with a pooled fresh list (`:218-228` — the same-dim /
same-renderer protection, IP-verbatim); Sodium/Iris context swapped (`:261-264`); first-visit
lightmap primed (`:266-270`); projection backed up + IDENTITY model-view pushed (`:274-277`). The
STENCIL is already limited to this layer: `RendererUsingStencil.doRenderPortal` ran
`setStencilStateForWorldRendering()` (= `glStencilFunc(EQUAL, layer)`) before `renderPortalContent`
(`RendererUsingStencil.java:238`), and the opening's depth is FAR-cleared (Row 7, `:296-336`).

Mode selector, evaluated once at core entry:
`sharedState = (destLRS == client.gameRenderer.gameRenderState().levelRenderState)` where
`destLRS = ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getLevelRenderState()`. TRUE
exactly when the dest dim is the currently-extracted main dim (same-dim portals — the S13 rung-1
test case; `26.2:LevelRenderer.java:151` binds the main renderer to `gameRenderState().levelRenderState`,
`ClientWorldLoader.java:534-540` gives every secondary its own). The two modes differ ONLY where
marked **[cross-dim]** / **[same-dim]** below.

### Step 1 — virtual-camera CONFIG (in the SHELL, right after `new Camera()` at `MyGameRenderer.java:207`)

Re-expresses `MOD:PortalContextSwitch.java:1051-1061,1144-1150`:

1. `((IECamera) newCamera).ip_resetState(WorldRenderInfo.getCameraPos(), newWorld)` — position =
   `WorldRenderInfo.cameraPos` (built by `renderPortalContent` from
   `PortalRendering.getRenderingCameraPos()` = the ORIGINAL camera position transformed through every
   pushed portal layer, `PortalRendering.java:113-120`; `PortalRenderer.java:306`), level = dest.
   This is **cameraPos consumption point #1**.
2. `portal_setFocusedEntity(client.getCameraEntity())`.
3. Rotation: copy the ORIGINAL camera's `yRot`/`xRot` (`RenderStates.originalCamera`) via the
   existing `MOD:CameraInvokerMixin.seamlessportals$invokeSetRotation` (proven `:1054-1055`). IP
   semantics: the portal's ROTATION rides `cameraTransformation` into the view matrix (Step 3), never
   the camera angles — do NOT bake any portal yaw into the camera (the block-era `yawOffset` is a
   block-era axis-transform artifact, NOT ported).
4. `newCamera.tick()` — primes the camera's OWN `EnvironmentAttributeProbe` with dest
   level+position (R9 §3.2 item 2 isolation invariant; without it fog color/sky factor/ambient are
   zero — `:1057-1061`).
5. `seamlessportals$setInitialized(true)` (R13k initialized-flag gate, `MixinCamera` javadoc :33-37).
6. Captured frustum: `seamlessportals$setCapturedFrustum(destFrustum)` with the Step-3 frustum. The
   capture is the 26.2 RE-EXPRESSION of IP's `setupRender` HEAD-cancel
   (`IP:MixinLevelRenderer.java:239-262`): it makes `LevelExtractor.extract` skip `applyFrustum`
   (`26.2:LevelExtractor.java:125-134` — the `camera.getCapturedFrustum() == null` gate), so (a) the
   engine's SOG BFS never overwrites the discovery-built `visibleSections`, and (b) the SPIKE-R1
   reversed-Z `offsetToFullyIncludeCameraCube` hang path (CUTOVER_SPEC §2.3) is never entered from
   extract. Proven `:1144-1150`.

### Step 2 — resolve the per-dim substrate (core entry)

- `destExtractor = ClientWorldLoader.getWorldExtractor(destDim)` — the EXTRACTOR-IDENTITY router
  (`ClientWorldLoader.java:374-401`): main dim → the vanilla global `CLIENT.levelExtractor` ITSELF,
  secondary → the construction-bound per-dim instance (memory
  `nether-block-freeze-orphaned-extractor`). Never construct a second extractor for a level (its
  `extract()` would steal the real extractor's chunk-delta flip + one-shot dirty flags — see §4-I9).
- `destLRS` (above), `viewArea = ((IEWorldRenderer) destRenderer).ip_getBuiltChunkStorage()` — an
  `ImmPtlViewArea` flag-ON (the R4 redirect installs it for main + secondaries,
  `MOD-qouteall:MixinLevelRenderer.java:111-139`), `sut = destExtractor.sectionUpdateTracker`
  (public field, proven `MOD:PortalContextSwitch.java:1304-1305`).
- **Renderer-state coherence assert** (re-expresses `:1159-1181`): the extractor's
  `LevelRenderState` (via `MOD:LevelExtractorAccessor.seamlessportals$getLevelRenderState`) must be
  the SAME OBJECT as the renderer's; in the qouteall port this holds by construction for both main
  (`26.2:LevelRenderer.java:151` + `Minecraft.java:649`) and secondaries
  (`ClientWorldLoader.java:534-540`). Keep the block-era defensive re-point
  (`seamlessportals$setLevelRenderState(extractorState)` on mismatch) — it is the exact
  "entities/clouds/particles silently vanish" failure signature if any future promote path diverges
  them.

### Step 3 — dest view matrix + frustum (the cameraTransformation consumption point)

Re-expresses `:1076-1084,1379-1380` + IP's renderLevel rotation wrap
(`IP:MixinGameRenderer.java:304-317`):

1. `destViewMatrix = new Matrix4f(newCamera.getViewRotationMatrix())` — the un-transformed rotation
   from the player angles set in Step 1.
2. `destViewMatrix = TransformationManager.processTransformation(newCamera, destViewMatrix)` —
   which runs `WorldRenderInfo.applyAdditionalTransformations` over the WHOLE render-info stack
   (`TransformationManager.java:88-100`, `WorldRenderInfo.java:128-147`): per layer, identity-reset
   when `overwriteCameraTransformation`, then `pose.mul(cameraTransformation)` (JOML column form
   M·v — the D4.4 anti-"fix" guard; never transpose). **This is where
   `WorldRenderInfo.cameraTransformation` is consumed** — the 26.2 site replacing IP's wrap of
   `Matrix4f.rotation(quat)` inside the recursive renderLevel. The main pass's R13k post-process
   (`MOD-qouteall:MixinGameRenderer.onExtractEnded:153-159`) covers ONLY the main extract; the dest
   pass consumes the stack here because no recursive extract exists.
3. Dest projection: `destProjection = new Matrix4f(mainCameraState.projectionMatrix)` — the
   UNBOBBED extract-time main projection (bob is multiplied into a local copy inside vanilla
   `renderLevel`, `26.2:GameRenderer.java:535-542`, so the extracted one is basic; identical to
   `RenderStates.basicProjectionMatrix` semantics). Proven `:1081,1419`.
4. Cull/extract frustum: `destFrustum = new Frustum(destViewMatrix, CONVENTIONAL-Z culling
   projection)`; `destFrustum.prepare(cameraPos)` (**cameraPos consumption #2**). CUTOVER_SPEC §2.3
   is BINDING here: the discovery step calls `new Frustum(f).offsetToFullyIncludeCameraCube(8)`
   (IP-verbatim, `IP:MixinLevelRenderer.java:255`), and feeding the reversed-Z render projection to
   that call deterministically hangs the render thread (SPIKE-R1 §1.5). Build this frustum from a
   conventional-Z culling projection mirroring `Camera.createProjectionMatrixForCulling()`
   (`26.2:Camera.java:179-189`). `MixinFrustum_FixDeadLoop` (registered) is the loud backstop, not
   the design. (The block era got away with the reversed-Z projection at `:1081-1083` only because
   it never calls the offset function.)
5. Set it as the camera's captured frustum (Step 1.6) and `seamlessportals$setCullFrustum`.

### Step 4 — dispatcher camera + camera render state

- `destRenderer.sectionRenderDispatcher().setCameraPosition(destCameraPos)` — vanilla does this
  per-frame in the cull path; without it terrain renders at wrong screen positions (proven
  `:1220-1225`; IP analog `IP:MixinLevelRenderer.java:243-247`
  `sectionRenderDispatcher.setCamera(camera.getPosition())` under `WorldRenderInfo.isRendering()`).
  **cameraPos consumption #3.** **[same-dim]** save the main camera position first and restore it in
  the Step-10 finally (the main frame's remaining passes must not inherit the dest position).
- Camera state target:
  - **[cross-dim]** `destCameraState = destLRS.cameraRenderState` (the secondary's own object).
  - **[same-dim]** `destCameraState` = a CORE-OWNED scratch `CameraRenderState`; REASSIGN
    `destLRS.cameraRenderState = scratch` for the duration (the field is public non-final,
    `26.2:LevelRenderState.java:13`) and restore the original reference in the finally. This is the
    load-bearing **"LevelRenderState re-point"** in the same-dim case: entity submission subtracts
    `levelRenderState.cameraRenderState.pos` (`26.2:LevelRenderer.java:654`), and the main frame's
    later passes (weather `:477-480`, hand `26.2:GameRenderer.java:567`) keep reading the ORIGINAL
    object untouched.
- `newCamera.extractRenderState(destCameraState, partialTick)` (proven `:1403-1404`), then:
  - `destCameraState.viewRotationMatrix.set(destViewMatrix)` — the dest-pass R13k analog: the
    transform is applied AFTER extract onto the state, never by wrapping the cached
    `Camera.getViewRotationMatrix` (consumed downstream by `PerEntityClipBracket`/R3 readers and any
    state-reading compat).
  - `destCameraState.projectionMatrix.set(destProjection)` (`:1419`).
  - zero bob/hurt on `destCameraState.entityRenderState` (`:1424-1430`, defensive verbatim).
  - **[cross-dim]** save `fogData`/`fogType` before the Step-6 overwrite and restore them in the
    finally (`:1485-1497,2042-2043` — the proven leak guard; Sodium reads `cameraRenderState.fogData`
    directly). **[same-dim]** unnecessary (scratch object), but harmless to keep symmetrical.

### Step 5 — dest EXTRACT + SOG delta feed + compileSections drain **[cross-dim ONLY]**

The heart of the pairing invariant. Re-expresses `:1553-1645` VERBATIM in mechanics:

```
try {
    destExtractor.extract(deltaTracker, newCamera, partialTick);      // (a)
} finally {
    // (b) SOG delta feed — ChunkLoadingRenderState -> destRenderer.sectionOcclusionGraph()
    //     updateLoadedChunks(added, removed) + updateEmptySections(added, removed),
    //     guarded by the per-dim set-object IDENTITY window (LAST_APPLIED_DELTA_WINDOW):
    //     apply once per flip window, idempotent within a window, NEVER lost on throw.
    // (c) compileSections drain — the §5.1 invariant:
    //     ((LevelRendererAccessorMixin) destRenderer)
    //         .seamlessportals$invokeCompileSections(destCameraState);
}
```

- (a) runs while `mc.levelRenderer == destRenderer` (the shell swapped it) — REQUIRED so Sodium's
  `LevelExtractorMixin.cullTerrain` resolves the DEST renderer (`:1536-1552,1823-1830`), and so the
  extract's dispatcher `prepare()` calls target the swapped context. The captured frustum makes it
  skip `applyFrustum` (Step 1.6). `extract` populates `destLRS` with entities / block entities /
  particles / sky / weather / border states and the dest `gameTime`
  (`26.2:LevelExtractor.java:110-199`), consumes the dest `ClientChunkCache` delta sets
  (`:136-148` — the flip), and queues section compiles while CONSUMING one-shot dirty flags
  (`:149-169`).
- (b) is invariant §5.2 (memory `distant-chunk-vanish-sog-desync`): the decomposed core never runs
  `destRenderer.render()`, vanilla's ONLY delta consumer (`26.2:LevelRenderer.java:271`), so the core
  feeds the dest SOG itself or the dim's SOG silently desyncs for the whole away-stay and the return
  crossing promotes phantom holes. In a FINALLY with the identity-window guard, exactly as the proven
  comment block `:1554-1594` derives.
- (c) is invariant §5.1 (memory `ow-holes-consumed-compile-queue`): `extract()` queued
  `SectionUpdateRenderState`s into `destLRS.sectionUpdateRenderStates` and set the sections
  not-dirty; the only vanilla consumer is the private `compileSections` inside `render()`
  (`26.2:LevelRenderer.java:254-255,608-614`), which never runs here — invoke it directly via the
  existing accessor or the sections strand `dirty=false + UNCOMPILED` forever. Timing is safe
  mid-main-framegraph: no GPU `RenderPass` is open at this point and the drain is compileAsync
  scheduling (`:1619-1633` derivation). In the same finally as (b) — extract can throw AFTER
  consuming flags.
- The UPLOAD half is NOT here: staged results reach the GPU via `MyRenderHelper.earlyRemoteUpload`
  (`lock()/uploadTerrainBuffersToGpu()/unlock()` per secondary dispatcher, G26) at the pre-frame
  pump — ALREADY WIRED flag-ON (`MOD:MinecraftFramePumpMixin.java:79-81`,
  `IPCGlobal.earlyRemoteUpload = true` default). **This resolves CUTOVER_SPEC §1.4's
  "land iff needed": the decomposition NEVER runs `render()`'s upload tail
  (`26.2:LevelRenderer.java:262`), so the pump is needed BY CONSTRUCTION** — the block era proved the
  same with `flushDestStagedUploads` at `renderLevel` HEAD (`:1628-1633`). Same pre-frame,
  outside-framegraph timing class.
- **[same-dim]** NO extract, NO delta feed, NO drain: the main frame's own `extract()` already ran
  this frame and the main `render()` already consumed the deltas and will drain its own compile
  queue. Re-running extract on `CLIENT.levelExtractor` mid-frame would `levelRenderState.reset()`
  the main frame's state and steal its delta window — the §4-I9 prohibition.

### Step 6 — dest FOG (R9) — compute-only probe + core-owned standalone buffer

Re-expresses `:1456-1505` + CUTOVER_SPEC §3:

1. `FogRenderer fr = ((GameRendererAccessorMixin) client.gameRenderer).seamlessportals$getFogRenderer()`.
2. `destFogData = fr.setupFog(newCamera, WorldRenderInfo.getRenderDistance(), deltaTracker, 0f,
   destLevel)` — compute-only, ring-buffer-safe (§3.1; `setupFog` never writes the WORLD slot,
   `26.2:FogRenderer.java:167-186`). Fog radius = the render-info render distance (IP's graduated
   dest radius arrives through `getPortalRenderDistance`, `PortalRenderer.java:326-339`); the
   block-era `smoothedDestFogRadius` is a PortalWorldManager-scope artifact, not ported.
3. `destCameraState.fogData = destFogData; destCameraState.fogType = FogType.NONE` (save/restore per
   Step 4).
4. Write the fog UBO to a **core-owned standalone `GpuBuffer`** — re-express `writePortalFogBuffer`
   (`:2495-2518`): 48-byte std140 (`putVec4(color)` + six floats + padding),
   `RenderSystem.getDevice().createBuffer(USAGE_UNIFORM)`, fresh buffer per pass, never
   `fr.updateBuffer(...)` (main WORLD slot corruption — dark clipping artifacts across the whole
   world, `:1499-1503`). **R9 FORM RESOLUTION:** this adopts CUTOVER_SPEC §3.2's documented FALLBACK
   (mod-owned auxiliary buffer) over the preferred instance-per-dim `FogRenderer`, on runtime-proof
   grounds — the standalone-buffer form is the one the live driver has soaked; the instance-per-dim
   form remains available if the §3.2 fog-flicker watch ever indicts sharing the main `FogRenderer`
   for the compute. Recorded as a spec-preference override (§6.4).
5. `FogRendererContext` already serves the DEST fog color for Row 16's `replaceFrameBufferClearing`
   fill: the shell pushed the swapping manager (`MyGameRenderer.java:239`), so
   `getCurrentFogColor.get()` probes the dest world.

### Step 7 — dest PROJECTION set (inside the shell's backup/restore bracket)

`RenderSystem.setProjectionMatrix(coreProjectionSlice(destProjection), ProjectionType.PERSPECTIVE)` —
re-express `writeProjectionBuffer` (`:2378-2396`: 64-byte fresh `USAGE_UNIFORM` buffer per call; do
NOT close the previous, the GPU may still read it; do NOT reuse the GameRenderer's
`levelProjectionMatrixBuffer` — the main pass's slice must survive). ALWAYS override, even when no
oblique/scale transform applies: the main path pushed the BOBBED projection before the dispatch fired
(`:1794-1803`). The shell's `RenderSystem.backupProjectionMatrix()` (`MyGameRenderer.java:274`) /
`restoreProjectionMatrix()` (`:293`) bracket this — only the SET is core work.

### Step 8 — Globals UBO for the dest pass

`seamlessportals$getGlobalSettingsUniform().update(w, h, glint, destLevel.getGameTime(),
deltaTracker, blur, destCameraPos, texFiltering)` (**cameraPos consumption #4**; proven
`:1812-1821`), restored with the pre-captured SOURCE gameTime + camera pos in the finally
(`:2004-2013` — capture `savedCameraPos`/`savedLevelGameTime` BEFORE the shell swap, i.e. at
dispatch/`renderPortalContent` level or from `RenderStates.originalCamera` +
`ClientWorldLoader.getWorld(RenderStates.originalPlayerDimension).getGameTime()`).

### Step 9 — visibleSections: ARMED discovery (the S13-G item (b) seam)

Re-expresses IP's setupRender override (`IP:MixinLevelRenderer.java:249-262`) + the armed fold
(`S11B-render-drivers.md §4`; the arm contract documented at `PortalRenderer.java:287-302`):

1. `VisibleSectionDiscovery.armCompileScheduling(destLevel, sut, cache, schedSet, budgetNs)` where:
   - `sut` = Step 2's per-dim tracker (**[same-dim]** `CLIENT.levelExtractor.sectionUpdateTracker`);
   - `cache` = a fresh per-pass `RenderRegionCache` (proven `:1227-1228`);
   - `schedSet` = the core's per-dim one-shot UNCOMPILED guard set (§2.2; proven `:1306-1307`);
   - `budgetNs` = `3_000_000L` (the proven steady-state pump budget, `:1285`; the block-era 400µs
     promote-bridge derate has no qouteall analog yet — revisit at S14 cold-dest).
2. `VisibleSectionDiscovery.discoverVisibleSections(destLevel, (ImmPtlViewArea) viewArea, newCamera,
   new Frustum(destFrustum).offsetToFullyIncludeCameraCube(8),
   ((IEWorldRenderer) destRenderer).portal_getChunkInfoList())` — IP-verbatim call shape
   (`IP:MixinLevelRenderer.java:252-257`), result list = the CURRENT renderer's live
   `visibleSections` (**[same-dim]** that is the shell's pooled fresh list; the outer frame's list is
   already saved — `MyGameRenderer.java:218-228`). Discovery auto-disarms in its `finally` (P2 fix).
   **cameraPos consumption #5** (BFS seed + Chebyshev bound).
3. The compile half of §5.1 for NOT-YET-QUEUED sections rides the armed `acceptVisible`
   (`VisibleSectionDiscovery.java:257-292`): own-chunk `hasChunk` gate, budgeted `compileAsync`,
   dirty-state clear, one-shot schedSet guard.

Ordering note: discovery runs AFTER extract so a section compiled/dirtied by the extract window is
observed coherently; the block era orders the manual re-population after extract for the same reason
(`:1547-1552,1655-1663`).

### Step 10 — the DRAW sequence (masked by the live stencil; raw-GL state persists)

Re-expresses `:1672-2031`, wrapped in `try/finally`:

1. `destChunks = destRenderer.prepareChunkRenders(destViewMatrix)` (`26.2:LevelRenderer.java:509`;
   public). NEVER bail on `maxIndicesRequired()==0` — draws are simply empty while async meshes
   compile and the view fills over frames (`:1673-1692`).
2. `savedShaderFog = RenderSystem.getShaderFog(); RenderSystem.setShaderFog(destFogBuffer)` — the
   chunk/feature shaders apply the BOUND Fog uniform (`:1879-1895`); restore in the finally or nether
   fog leaks onto source clouds (`:1887-1892`).
3. **Row-16 background fill:** `IPCGlobal.renderer.replaceFrameBufferClearing()` — the DRIVER-INVOKED
   re-expression of IP's `redirectClearing` anchor (`IP:MixinLevelRenderer.java:308-320`): inside the
   recursive render IP replaced the framebuffer clear with the dest-fog COLOR_FILL over the stencil
   region; the decomposition has no clear to replace, so the core invokes the same renderer method at
   the same sequence point (before sky/terrain). `RendererUsingStencil.replaceFrameBufferClearing`
   (`:81-102`) already implements Row 16 + the `doRenderSky` gate.
4. **Dest sky** (gated on `WorldRenderInfo.getTopRenderInfo().doRenderSky` — fuse-view portals set it
   false, `PortalRenderer.java:313`): re-express `renderPortalSky` (`:895-940`) — skip skybox NONE;
   core-owned lazy `SkyRenderer(textureManager, destAtlasManager, mainRT)` (size-tracked); push
   `destViewMatrix` on the global model-view stack; `renderSkyDisc/renderSunriseAndSunset/
   renderSunMoonAndStars/renderDarkDisc` (or `renderEndSky`) from `destLRS.skyRenderState`. Sky draws
   BEFORE the clip is armed (the dome spans both sides of the plane — clipping halves it,
   `:1896-1902`; IP likewise excludes sky).
5. **Inner clip + portal draw state** — IP's per-layer bracket
   (`IP:MixinLevelRenderer.java:192-232`) re-expressed around the terrain/entity/cloud draws:
   `FrontClipping.setupInnerClipping(PortalRendering.getActiveClippingPlane(), destViewMatrix,
   -FrontClipping.ADJUSTMENT)` (qouteall `FrontClipping.java:103` — the BRIDGE feed into the single
   live plane store); `if (PortalRendering.isRenderingOddNumberOfMirrors())
   MyRenderHelper.applyMirrorFaceCulling()`; `if (IPGlobal.enableDepthClampForPortalRendering)
   CHelper.enableDepthClamp()`.
6. `destChunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, mainChunkSampler)` — solid+cutout into the
   OPAQUE output target = the real main render target, masked by the live stencil; LOAD, no clear
   (`:1865-1922`). `mainChunkSampler` is the block-atlas `GpuSampler` captured ONCE PER FRAME from
   the TRUE MAIN renderer while it is live (§2.2; the dest renderer's own sampler is null because its
   main pass never runs — `:1382-1396`). Guard the whole draw block on sampler-non-null and
   `maxIndicesRequired() > 0` (`:1877-1878`).
7. **Dest lighting for features:** `MyGameRenderer.resetDiffuseLighting()` with `mc.level` = dest
   (already swapped) — the 26.2 re-expression (`gameRenderer.lighting().updateLevel(cardinalLightType)`,
   `MyGameRenderer.java:365-372`) of IP's per-pass diffuse reset (`IP:MixinLevelRenderer.java:153`).
   Re-run it for the SOURCE dim in the finally (the outer frame's hand/translucent needs source
   lighting; IP restored it via the outer pass's own hook firing — the decomposition restores
   explicitly).
8. **Dest entities/block-entities/particles** — re-express `renderPortalEntities` (`:979-1000`):
   `seamlessportals$invokeSubmitFeatures(destLRS, destStorage, false)` then
   `seamlessportals$getFeatureRenderDispatcher().renderAllFeatures(destStorage)` with
   `destViewMatrix` pushed on the model-view stack; the dest renderer owns its OWN
   `SubmitNodeStorage`/`FeatureRenderDispatcher`/`PreparedFrame` (R3 bracket isolation,
   `MixinPreparedFrame` javadoc :32-34); the feature frame captures the swapped-in dest lightmap
   (`:969-977`). Entity clip: rung 1 draws entities under the Step-5 terrain clip as-is; the
   per-entity R3 bracket (`PerEntityClipBracket` / `CrossPortalEntityRenderer`) is the S18/C4 surface
   with the BOTH-mechanisms A/B switch — do not fold the block-era 0.5-block margin re-arm
   (`:1926-1948`) into the core now; note it as the fallback if straddling mobs bisect at S13.
   **[same-dim]** `destLRS.entityRenderStates` was already cleared by the main pass's submit
   (`26.2:LevelRenderer.java:282`) — same-dim portal views render NO entities at rung 1 (documented
   gap, §6.1).
9. `destChunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, mainChunkSampler)` — the dest
   renderer's translucent target is null so the group falls back to the main target, blended over the
   opaque dest terrain, before the Row-12 depth restore (`:1949-1957`).
10. **Nested portal layers** — `IPCGlobal.renderer.onBeforeTranslucentRendering(destViewMatrix)` at
    THIS point: the driver-invoked re-expression of IP's per-pass translucent hook
    (`IP:MixinLevelRenderer.java:138-156`), which is what recursed nested portals in IP. See §5.
11. **Dest clouds** — re-express `renderPortalClouds` (`:947-967`):
    `destRenderer.cloudRenderer().render(destLRS.cloudColor, cloudStatus, destLRS.cloudHeight,
    cloudRange, destCameraState.pos, destLRS.gameTime, partialTick)` with `destViewMatrix` pushed.
12. **Weather + world border: NOT drawn** (parity with the proven core, which omits both; IP renders
    weather in-portal via its weather-pass clip hook). Deferred, documented §6.3 — not
    first-light-blocking.
13. **finally:** `FrontClipping.disableClipping()`; `MyRenderHelper.recoverFaceCulling()`;
    `CHelper.disableDepthClamp()` (if enabled); restore `savedShaderFog`; restore Globals UBO
    (Step 8); source-dim `resetDiffuseLighting`; **[same-dim]** restore
    `destLRS.cameraRenderState` reference + dispatcher camera position; **[cross-dim]** restore
    `destCameraState.fogData/fogType`. Defensive stencil re-assert
    (`glEnable(GL_STENCIL_TEST)` + `setStencilLimitation(layer)`) mirroring `:2028-2030` — cheap
    insurance against a feature-renderer or compat mod touching raw stencil.

### Step 11 — after the invoke returns (already ported, cited for the audit trail)

The SHELL restores everything it saved (`MyGameRenderer.java:289-328`, incl.
`EntityRenderDispatcher.prepare(oldCamera, crosshair)` — undoing the extract's dispatcher prepare);
`renderPortalContent` re-enables depth + restores the viewport (`PortalRenderer.java:319-321`); the
STENCIL choreography then runs Row 10-15: `restoreDepthOfPortalViewArea` (Row 12 — see the §4-W1
MUST-RESOLVE) + `clampStencilValue` (`RendererUsingStencil.java:240-249`).

---

## 2. STATE-OWNERSHIP MAP

### 2.1 Per-dim objects that ALREADY EXIST in the qouteall port (consume, never duplicate)

| Object | Owner / accessor | Identity rule |
|---|---|---|
| `ClientLevel` per dim | `ClientWorldLoader.CLIENT_WORLD_MAP` / `getWorld` | created on demand; R1 seaLevel ctor |
| `LevelRenderer` per dim | `ClientWorldLoader.WORLD_RENDERER_MAP` / `getWorldRenderer` | main entry = `CLIENT.levelRenderer` |
| `LevelExtractor` per dim | `ClientWorldLoader.WORLD_EXTRACTOR_MAP` / **`getWorldExtractor`** | **EXTRACTOR-IDENTITY:** main dim → the vanilla global `CLIENT.levelExtractor` ITSELF; secondaries → the construction-bound instance, NEVER rebuilt (memory `nether-block-freeze-orphaned-extractor`; `ClientWorldLoader.java:356-401,537-540`) |
| `LevelRenderState` per dim | rides the extractor+renderer pair (same object by construction) | never reconstruct; re-point only defensively (Step 2) |
| `SectionUpdateTracker` per dim | `destExtractor.sectionUpdateTracker` (public) | recreated by `allChanged` inside the extractor — always re-read from the extractor, never cache across frames |
| `ImmPtlViewArea` per renderer | vanilla `viewArea` field via `IEWorldRenderer.ip_getBuiltChunkStorage` | installed by the R4 flag-gated NEW-redirect for main + secondaries |
| `visibleSections` per renderer | `IEWorldRenderer.portal_get/setChunkInfoList` (@Mutable) | the shell's save/swap protects the outer pass |
| SOG per renderer | `destRenderer.sectionOcclusionGraph()` (public) | fed by Step 5(b) |
| `DimensionRenderHelper` (lightmap) per dim | `ClientWorldLoader.RENDER_HELPER_MAP` / `getDimensionRenderHelper` | conflict-guarded in `ClientWorldLoader.tick` |
| Dispatchers (entity/BE), atlas, shaders | shared singletons passed into every secondary ctor | extract mutates them; the shell's restore un-does it |
| Private-member reach | the PRE-EXISTING public `com.warwa` accessor mixins: `GameRendererAccessorMixin` (fogRenderer, lightmap, mainCamera, globalSettingsUniform, mainRenderTarget), `LevelRendererAccessorMixin` (levelRenderState get/set, viewArea, visibleSections, chunkLayerSampler, submitNodeStorage, featureRenderDispatcher, atlasManager, invokeSubmitFeatures, invokeCompileSections), `LevelExtractorAccessor` (levelRenderState), `CameraInvokerMixin` (setRotation, setCullFrustum, setCapturedFrustum, setInitialized) | all verified present this stage; interface-only consumption = the sanctioned bridge pattern (S11-B §6, FrontClipping B1 precedent). NO new AW/AT expected. |

### 2.2 CREATED by the driver core (`SecondaryWorldRenderCore`-owned; no com.warwa duplication — the live `PortalWorldManager`/`PortalContextSwitch` state stays untouched and suppressed flag-ON)

- `Map<ResourceKey<Level>, Set<Long>> portalCompileScheduled` — the armed-discovery one-shot guard
  (re-expresses `MOD:PortalContextSwitch.portalCompileScheduled`).
- `Map<ResourceKey<Level>, LongSet-identity> lastAppliedDeltaWindow` — the SOG delta-window identity
  guard (re-expresses `LAST_APPLIED_DELTA_WINDOW`).
- The standalone projection + fog `GpuBuffer` writers (fresh `USAGE_UNIFORM` buffer per call, old
  never closed — proven mechanics §1 Steps 6-7).
- The lazy portal `SkyRenderer` (+ tracked size) per §1 Step 10.4.
- The per-frame captured `mainChunkSampler`: captured ONCE per frame at dispatch entry (the
  `AFTER_TRANSLUCENT_TERRAIN` callback / `prepareRendering`, while `mc.levelRenderer` is still the
  true main renderer) — NOT inside the invoke, where nested layers would resolve a secondary whose
  sampler is null (`:1382-1396`).
- **[same-dim]** the scratch `CameraRenderState`.
- The budget constant (3 ms) + `compileSections`-drain plumbing.
- Cleanup: register on `IPCGlobal.CLIENT_CLEANUP_EVENT` +
  `ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` (the `VisibleSectionDiscovery.init`
  precedent, `VisibleSectionDiscovery.java:310-316`).

---

## 3. P3 LIFECYCLE-TAIL HOMES (each vs IP's MixinGameRenderer anchor)

| P3 | IP anchor | 26.2 flag-ON home | STATUS |
|---|---|---|---|
| (c) `IPGlobal.PRE_TOTAL_RENDER_TASK_LIST.processTasks()` | `IP:MixinGameRenderer.java:76` — render HEAD, BEFORE the `level==null` guard (`:78`) | `MOD:MinecraftFramePumpMixin.java:62-66` — the `renderFrame` pre-`update` pump, ABOVE the level guard (IP order preserved); drains `PortalRenderInfo`'s GC-disposal one-shots (`PortalRenderInfo.java:135`) | **ALREADY LANDED** — verify only |
| (a) `RenderStates.frameIndex++` | `IP:MixinGameRenderer.java:99` — closes the pre-render handler, level-guarded | `MOD:MinecraftFramePumpMixin.java:82-86` — after the ported chain (`updatePreRenderInfo → StableClientTimer → ClientPortalAnimationManagement → manageTeleportation → PRE_GAME_RENDER_EVENT → earlyRemoteUpload`), level-guarded; rotates `PortalRenderInfo.updateQuerySet`'s occlusion-query buffers | **ALREADY LANDED** — verify only |
| (b) `MyGameRenderer.endFramePooled()` | none (ADDITIVE B6) | `MOD:GameRendererMixin.java:57-59` — `GameRenderer.render` TAIL, right after vanilla's own `renderBuffers.endFrame()` (`26.2:GameRenderer.java:447`), flag-gated | **ALREADY LANDED** — verify only |
| (d) `IPCGlobal.renderer.finishRendering()` → `RenderStates.onTotalRenderEnd()` → `GuiPortalRendering._onGameRenderEnd()` → `MyRenderHelper.lateUpdateLight()` (gated `IPCGlobal.lateClientLightUpdate`) | `IP:MixinGameRenderer.java:127-142` — `@Inject(method="render", at=@At(INVOKE renderLevel, shift=AFTER))` | **TO LAND WITH THE CORE:** a new handler in the held/registered `MOD-qouteall:mixin/client/render/MixinGameRenderer` at the SAME 26.2 anchor — `@Inject(method="render", at=@At(value="INVOKE", target="GameRenderer.renderLevel(DeltaTracker)V", shift=AFTER))` (`26.2:GameRenderer.java:425` — the call exists; the point is reached only when the level rendered, matching IP). Order the three calls verbatim IP. `finishRendering()` STAYS in the dispatch callback (`SeamlessPortalsClientFabric.java:114`) — a no-op on `RendererUsingStencil`; when the A1 FBO renderer is exercised it must MOVE to this anchor (its `finishRendering` does real work at IP's timing) — note carried. | **DESIGNED HERE** |

Why the after-`renderLevel` anchor and NOT `render` TAIL for (d): `_onGameRenderEnd` prepares the
GUI-portal framebuffers the GUI pass consumes — it must precede `guiRenderer.render`
(`26.2:GameRenderer.java:443`); `onTotalRenderEnd` restores the CURRENT dim's lightmap identity
(`RenderStates.java:231-247`) before GUI/next-extract; `lateUpdateLight` is frame-render-END by the
memory lesson (`portalview-light-engine-half-port`) and sits exactly where IP put it. No
double-drive: the block-era TAIL substrate (`lateUpdateSecondaryLight`, both flag states,
`MOD:GameRendererMixin.java:47`) iterates `PortalWorldManager`'s own per-dim map, which is EMPTY
flag-ON (block-era driver suppressed) — `MyRenderHelper.lateUpdateLight` iterates
`ClientWorldLoader`'s worlds; the two never overlap. S13-C weave gate applies to the new anchor
(javap the `renderLevel(DeltaTracker)V` INVOKE in `render` before registering; `require:1`).

---

## 4. THE INVARIANT CHECKLIST (the implementation must satisfy every row)

**CUTOVER_SPEC §5 (verbatim obligations):**
- **I1 (§5.1):** *every `LevelExtractor.extract()` on a secondary MUST be paired with a
  `compileSections` drain* — Step 5(c), in the SAME finally as the delta feed; never also run
  `destRenderer.render()` (double-drain cancels in-flight compiles). Same-dim mode satisfies it
  vacuously (no extract).
- **I2 (§5.2):** the secondary SOG receives the `ClientChunkCache` delta feed with the
  identity-window guard, applied in a finally, never discarded on throw — Step 5(b).
- **I3 (§5.3):** `endFramePooled()` at `GameRenderer.render` TAIL — landed (§3 b).
- **I4 (§5.3):** `lateUpdateLight` at frame-render END, never mid-tick — §3 (d).
- **I5 (§5.3):** NO per-frame `LOGGER` on the render thread anywhere in the core (the block-era
  `rlog` diagnostics are NOT transplanted; memory `render-thread-logging-log4j-stall`).
- **I6 (§5.3):** FrontClipping plane math stays column-form M·v; ONE plane store (the bridge); never
  `mulTranspose`.
- **I7 (§2.3):** any frustum that reaches `offsetToFullyIncludeCameraCube` (the discovery frustum)
  is built from a CONVENTIONAL-Z culling projection mirroring
  `Camera.createProjectionMatrixForCulling` — never `cameraRenderState.projectionMatrix`. Step 3.4.
- **I8 (R9):** the main `FogRenderer` WORLD ring-buffer slot is NEVER written during a portal pass
  (compute-only `setupFog` + core-owned buffer); the virtual camera owns its own
  `EnvironmentAttributeProbe` (never reuse `mainCamera`).
- **I9 (extractor identity):** one extractor per level, the level's construction-bound one; the main
  dim routes through `CLIENT.levelExtractor` itself; NEVER a second extractor over the same level and
  NEVER a mid-frame extract on the main extractor (it resets the main frame's state and steals the
  chunk-delta flip + one-shot dirty flags).
- **I10 (exclusivity, §6.3):** one driver per session — the core touches none of
  `PortalWorldManager`/`PortalContextSwitch`/`StencilPortalRenderer` state; flag-OFF surface stays
  byte-identical (the only `com.warwa` edits this stage are the already-flag-gated hosts of §3).
- **I11:** flag-ON never renders LESS than flag-OFF would (F18: under a non-GL backend, bypass
  occlusion queries → treat portals visible).

**Reversed-Z watch items (named by the mission):**
- **W1 — Row 12 (MUST-RESOLVE at implementation):** the ported `restoreDepthOfPortalViewArea` keeps
  IP's exact-projected-depth op #12 (`RendererUsingStencil.java:350-366`), but its
  `glDepthFunc(GL_ALWAYS)` bracket (Row 11) is clobbered by the GEQUAL portal-area pipeline that
  `ViewAreaRenderer` selects (`applyPipelineState` sets the pipeline's depth compare) — the S11-B §8
  watch item. Since S13-H ELECTS the exact-projected-depth form, the implementation MUST add an
  ALWAYS_PASS depth-compare variant to the `getPortalAreaRenderType` family (a 4th selector bit or a
  dedicated restore-phase pipeline) used ONLY by the Row-11/12 restore draw. Without it the restore
  depth-write is GEQUAL-gated against the content depth — near-equivalent in the common case but NOT
  IP's contract, and wrong wherever dest terrain sits in front of the portal plane. (The alternative
  — the block-era flat NEAR shield `glDepthRange(1,1)` — is the documented non-IP fallback.)
- **W2 — PreparedFrame (S11-R3 §6 rung-1 check):** the R3 clip bracket's `@Unique` prev-snapshot on
  `PreparedFrame` restores on RETURN only; the nested dest pass uses a DIFFERENT
  dispatcher/PreparedFrame instance (isolation holds), but the throw-path restore + per-`PassState`
  recursion behaviour must be observed at rung 1 (`MixinPreparedFrame` javadoc :30-36). The core's
  Step-10.8 feature draw is the first code that exercises it.
- **W3 — Row 4/Row 7 (already executed at S12, re-verify by observation):** the stencil-write depth
  compare rides the GEQUAL pipeline; the FAR clear is `glDepthRange(0,0)`
  (`RendererUsingStencil.java:264-336`). The core adds no new depth constants — Steps 1-11 contain
  ZERO raw depth-compare or depth-range writes (D4.4: the core's sign surface is empty; all R5 lives
  in the choreography).
- **W4 — composite/fill depth gating:** Row-16's COLOR_FILL and any screen-triangle draw must stay
  depth-independent (the pipelines `MyRenderHelper.renderScreenTriangle` selects are depth-off —
  the block era's blue-curtain lesson, `:2424-2430,2464-2471`).

---

## 5. RECURSION / NESTING NOTES

- **The dispatch guard becomes defensive, not load-bearing.** The Fabric callback early-returns when
  `PortalRendering.isRendering()` (`SeamlessPortalsClientFabric.java:104-106`) — wired against the
  recursive `renderLevel` re-firing `AFTER_TRANSLUCENT_TERRAIN`. The decomposition never re-enters
  `LevelRenderer.render`, so the event cannot re-fire mid-pass; keep the guard (cheap, protects
  against any future FBO-mode path).
- **Nested layers are driven BY THE CORE, reproducing IP's per-pass hook:** IP recursed via
  `MixinLevelRenderer.onMyBeforeTranslucentRendering` firing inside every recursive world render
  (`IP:MixinLevelRenderer.java:138-156`). The decomposition calls
  `IPCGlobal.renderer.onBeforeTranslucentRendering(destViewMatrix)` at Step 10.10 (after the dest
  translucent terrain — IP's anchor is the translucent-item-sheet point). That re-enters
  `doPortalRendering` → `renderPortals` for the INNER layer: `getPortalsToRender` iterates the
  now-current dest world's `entitiesForRendering()` with the dest-view frustum;
  `PortalRendering.isRendering()` is true so the post-pass branch runs
  `setStencilStateForWorldRendering()` (not `myFinishRendering`) — IP-verbatim
  (`RendererUsingStencil.java:105-130`).
- **What stacks per nesting level:** `WorldRenderInfo` stack (shell push/pop);
  `PortalRendering.portalLayers` (choreography push/pop, `RendererUsingStencil.java:228-242`); the
  shell's whole save/restore set as LOCALS (re-entrant by construction); `FogRendererContext`
  swapping stack; the core's per-pass locals (fresh `RenderRegionCache`, fresh fog/projection
  buffers, scratch camera state); the pooled `visibleSections` lists
  (`VisibleSectionDiscovery.takeList/returnList`); stencil layer = `PortalRendering.getPortalLayer()`
  (replaces the block-era `stencilDirectLayer`). Depth gate: `renderPortalContent` returns above
  `getMaxPortalLayer()` (`PortalRenderer.java:277-279`).
- **Extract state under nesting:** each nested pass extracts ITS dest dim's extractor (Step 5).
  Re-extracting a dim already extracted this frame (A→B→A patterns) is safe: the delta-window
  identity guard no-ops the second feed, `levelRenderState.reset()` only clears state the INNER pass
  is about to rebuild for itself, and the sectionUpdates loop finds the flags already consumed. The
  one asymmetry: an inner pass over the ORIGINAL dim (`destLRS == gameRenderState.levelRenderState`)
  takes the **[same-dim]** no-extract path regardless of nesting depth — the shared-state test in §1
  is object-identity, not layer-count, so it composes.
- **Camera transform under nesting:** `WorldRenderInfo.applyAdditionalTransformations` walks the
  WHOLE stack each time (Step 3.2), and `PortalRendering.getRenderingCameraPos()` re-transforms the
  ORIGINAL camera position through every layer — both are cumulative by construction; the core never
  caches a transformed camera across layers.
- **`vanillaTerrainSetupOverride` (`MyGameRenderer.java:123`):** IP's first-frame-after-teleport
  MAIN-pass discovery override (`IP:MixinLevelRenderer.java:275-306`, the `setupRender` RETURN hook)
  is a CROSSING-time concern for the MAIN renderer, not the portal pass; its 26.2 home (an extract- 
  or SOG-side override for the first post-teleport frame) rides the S13 crossing rung with
  `ClientTeleportationManager` (which writes the field), not this core. Recorded so the field's
  consumer-less state is not mistaken for dead code.

---

## 6. ITEMS NEEDING PARENT / SPEC DECISION (the "problems" register)

1. **Same-dim (rung-1) entity gap.** The shared-`LevelRenderState` analysis (§1 Step 5/8) means a
   SAME-DIM portal view renders terrain+sky+clouds but NO entities at first light (the main pass
   already consumed+cleared `entityRenderStates`; re-running `extract` on the main extractor is
   prohibited by I9). Cross-dim portals render entities fully. Options: (a) accept for rung 1
   (recommended — rung-1's purpose is the terrain window; entities-through-portals are the S18/C4
   surface anyway), or (b) add a `LevelExtractor.extractVisibleEntities` invoker and re-extract
   entity states into a scratch list for the same-dim pass (more accessor surface, unproven
   side-effect profile). **Recommendation: (a), revisit after first light.**
2. **Row-12 ALWAYS_PASS pipeline variant (W1)** — a small but MANDATORY `MyRenderHelper.
   getPortalAreaRenderType` family extension not authored by S11-B/S12. Needs a one-line approval
   that extending the additive pipeline family is in-scope for the S13-H implementation commit
   (it is the S11-B §8 watch item maturing into a requirement because the port kept IP's op #12).
3. **Weather + world border in portal views** are omitted (parity with the proven block-era core;
   IP draws weather with a clip bracket). Trailing item, proposed to ride the S12 A1/periphery wave —
   flag if the 12-point S17 regression list requires in-portal weather earlier.
4. **R9 FORM override:** this design adopts CUTOVER_SPEC §3.2's FALLBACK (core-owned standalone fog
   buffer, the runtime-proven form) over the spec-preferred instance-per-dim `FogRenderer`. The §3.2
   named S13/S14 fog-flicker watch stays live; if it trips, the per-dim-instance form is the escape
   hatch. Recorded as a deliberate spec-preference deviation with proof-of-life rationale.
5. **CUTOVER_SPEC §1.4 resolution:** `earlyRemoteUpload` is required BY CONSTRUCTION under the
   decomposition (no `render()` upload tail ever runs for a dest renderer) — the "land it ONLY if
   S13/S14 exhibits stranded uploads" clause is superseded. It is already wired flag-ON
   (`MinecraftFramePumpMixin.java:79-81`; `IPCGlobal.earlyRemoteUpload=true`). Needs the spec's
   Appendix row flipped from "land iff needed" to "landed, required by driver-core form" at the next
   spec touch.
6. **`finishRendering()` placement:** stays in the dispatch callback for the stencil renderer
   (no-op); MUST move to the §3(d) after-`renderLevel` anchor when the A1 `RendererUsingFrameBuffer`
   is exercised. Carried note, no action now.

---

## VERIFICATION NOTE (this stage)

Design-only: no source file was created or edited; shipping ×3 + `:common:test` state is untouched
by construction (the only working-tree change is this document). Every API/line cited above was
re-read this stage from the current on-disk sources (`mc262-ref`, the ported tree, the live
`com.warwa` driver, IP 1.21.3). The S13-C weave gate and S13-D init-trace gate are inherited by the
implementation stage for its two new anchors (the §3(d) `MixinGameRenderer` handler; no other new
mixin is required — the core is plain Java called from the existing shell).
