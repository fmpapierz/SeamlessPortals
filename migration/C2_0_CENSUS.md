# C2-0 javap census — Sodium 0.9.1 internals (raw record, wf_6d502c4f-7a0)

**READ THE CORRECTIONS FIRST. Verify lens 1 (Fable, C2-0) re-derived this census independently and
REFUTED/CORRECTED the following claims in the raw text below — where they conflict, THE LENS WINS
(evidence in port-note C2-sodium-iris.md §4):**

1. **REFUTED: "swapping `frame` neutralizes the #3 ChunkRenderList.reset trigger."** The trigger is a
   value-INEQUALITY test at collection time (VisibleChunkCollector.visit: `getLastVisibleFrame() !=
   this.frame` → `reset(frame)`) — a portal-pass collection over shared regions resets them under ANY
   frame arrangement, and the renderOutOfGraph sync fallback fires the SAME trigger through its internal
   VisibleChunkCollector. **#3 (per-layer getRenderList @Overwrite) ships unconditionally for same-dim;
   P5 is re-scoped to MEASURING the artifact, not deciding ship/no-ship.** `frame` stays in the swap
   set for staleness-bookkeeping coherence only.
2. **CORRECTED: `isWithinNearbySectionFrustum` does NOT route through isBoxVisible(III)/testSection** —
   it calls `isBoxVisibleLooser(III)` → `testSectionExpanded(FFFF)`. The D2 consumer deliberately
   leaves that path unhooked; this correction just makes the uncovered-path map accurate.
3. **CORRECTED: P9 detection shorthand.** The clean backend check is `DrawBackend.BACKEND != OPENGL`
   (or replicate the GpuDeviceAccessor.sodium() chain) — NOT `instanceof` on the GpuDevice.
4. **NEW HAZARD the census missed (the load-bearing lens-1 catch): the pendingTask-swap ×
   QueuedSectionStorage safe-read-phase race on same-dim (shared-RSM) portals.** startSafeReadPhase is a
   plain boolean (no refcount); endSafeReadPhase flushes queued section mutations into the LIVE map. Two
   concurrently-outstanding CullTasks (outer swapped-out + portal's own) race a section-map flush against
   a live async traversal of the SHARED SectionStorage. **C2-1 must pick + name a mitigation:** (a)
   refcount the phase via a tiny QueuedSectionStorage mixin, (b) consume/cancel the outer pendingTask
   before swap-in on a shared RSM, or (c) suppress async cull scheduling for portal contexts on a shared
   RSM (renderOutOfGraph-only path). Cross-dim per-dim RSMs are unaffected (separate SectionStorage).
5. **GUARD: do NOT swap SWR.renderDistance** (setupTerrain HEAD triggers a FULL reload() whenever it
   differs from `options.getEffectiveRenderDistance()` — per-pass reload thrash). Swap only
   RSM.renderDistance, with the validate guard `== options.getEffectiveRenderDistance()`.
6. **WATCH (accepted degradations, ledgered):** renderOutOfGraph is frustum-only (overdraw, not
   wrongness); per-portal-pass prepareFrame pollutes shared frame-duration/upload-budget telemetry
   (clamped, perf skew only); with updateChunksImmediately armed, `frame` advances once per drain-loop
   ITERATION (up to renderDistance times per pass).
7. **Wording:** probe require=0 protects against INJECTOR drift only; a renamed target CLASS still fails
   loud at required:true — by design (the honesty policy).

---
C2-0 JAVAP CENSUS â€” Sodium 0.9.1 (sodium-mc26.2-0.9.1-fabric.jar, sha 14f3388â€¦) + backend. Toolchain: Adoptium jdk-21.0.11 javap -p/-c, /usr/bin/unzip. Jar mixin config located at extracted/sodium-common.mixins.json (package net.caffeinemc.mods.sodium.mixin; entries "core.render.world.ChunkSectionsToRenderMixin" :38, "core.render.world.LevelExtractorMixin" :43, "core.render.world.LevelRendererMixin" :44). Every claim below is javap-quoted. Two corrections to the ground-truth docs are flagged inline (there is NO RSM.update; the driver is SWR.setupTerrain, and the swap set is WIDER than Â§3.1.2 named).

=========================================================
(a) SODIUM'S CORE LevelRendererMixin â€” SWR location, init, guards
=========================================================
`javap -p â€¦/mixin/core/render/world/LevelRendererMixin.class`:
  public abstract class â€¦LevelRendererMixin implements â€¦client.world.LevelRendererExtension {
    private net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer renderer;   â† PER-INSTANCE, NOT static
    â€¦
    public â€¦SodiumWorldRenderer sodium$getWorldRenderer();
  }
SodiumWorldRenderer lives as a NON-STATIC per-LevelRenderer instance field `renderer`. The one static field is unrelated (`STATIC_MAP` = an EnumMap draw cache). INIT SITE â€” the mixin's `init(...CallbackInfo)` handler (woven into vanilla LevelRenderer's constructor/init):
  0: aload_0
  1: new  #13  // SodiumWorldRenderer
  5: invokestatic #15 // Minecraft.getInstance()
  8: invokespecial #21 // SodiumWorldRenderer."<init>":(LMinecraft;)V
 11: putfield #7 // renderer
So each LevelRenderer instance constructs its OWN `new SodiumWorldRenderer(Minecraft.getInstance())` at init. `sodium$getWorldRenderer()` is a bare `getfield renderer; areturn`. `sodium$replace(...)` (level-change) does NOT reallocate `renderer` â€” it calls `renderer.reload()` and rebuilds the ignoring dispatcher.
GUARDS: `grep -i "levelRenderer|if_acmp|getInstance"` over the FULL disassembly shows NO `mc.levelRenderer == this` guard anywhere in the class. The only `Minecraft.getInstance()` uses are the SWR ctor arg (init), the texture manager (prepareChunkRenders), and gameRenderer/projection reads (getRenderState) â€” never an identity guard.
â‡’ VERDICT: SWR is a per-LevelRenderer instance field, constructed at LevelRenderer init, with no self-identity guard. With our per-dim ClientWorldLoader LevelRenderers, each per-dim LevelRenderer that this mixin weaves gets its own SWR+RSM (the P1 topology basis).

=========================================================
(b) LevelExtractorMixin + ChunkSectionsToRenderMixin â€” which renderer reference each routes through (the P1 static half)
=========================================================
EXTRACT phase â€” `javap -c â€¦/LevelExtractorMixin.class`. The renderer is resolved by `checkRenderer()`:
  private void checkRenderer();
   1: invokestatic #22 // Minecraft.getInstance()
   4: getfield    #28 // Minecraft.levelRenderer:LLevelRenderer;      â† reads the GLOBAL mc.levelRenderer
   7: checkcast   #32 // LevelRendererExtension
  10: invokeinterface #34 // sodium$getWorldRenderer()
  15: putfield    #12 // renderer                                     â† caches into the extractor mixin's field
The two `sodium$setRenderer(...)` injectors both call `checkRenderer()` first (the (ClientLevel,CI) overload also `renderer.setLevel(level)`). The terrain-setup driver `cullTerrain(...)` reads the CACHED field (`29: getfield #12 // renderer`) and calls `SodiumWorldRenderer.setupTerrain(Camera,Viewport,FogParameters,Z,Z,Matrix4f)` (offset 64). It builds the Viewport via `ViewportProvider.sodium$createViewport()`, pulls fog via `mc.gameRenderer` cast to `FogStorage.sodium$getFogParameters()`, and the cull Matrix4f via `FrustumAccessor.sodium$getMatrix()`.
SUBMIT phase â€” `javap -c â€¦/ChunkSectionsToRenderMixin.class`. It reads a `renderer` field SET EXTERNALLY via `sodium$setRendering(SodiumWorldRenderer,ChunkRenderMatrices,DDD)` and, in `sodium$renderGroup`, if non-null cancels vanilla and calls `renderer.drawChunkLayer(group,matrices,x,y,z,sampler)`. WHO sets it: LevelRendererMixin.`getRenderState` (the @WrapOperation around prepareChunkRenders):
  49: aload 5            // the ChunkSectionsToRender result
  51: checkcast SodiumChunkSection
  54: aload_0; 55: getfield #7 // renderer  â† THIS LevelRenderer instance's OWN SWR
 101: invokeinterface sodium$setRendering(SWR,matrices,camX,camY,camZ)
â‡’ VERDICT (P1 static half): NEITHER phase reads a permanently-captured field nor a decoupled `this`. EXTRACT reads `Minecraft.getInstance().levelRenderer` directly (checkRenderer) and caches it; SUBMIT bakes `this.renderer` (where `this` = the LevelRenderer whose render loop calls prepareChunkRenders, i.e. mc.levelRenderer in the normal path) onto the ChunkSectionsToRender state at prepare time. Both are keyed to whichever LevelRenderer is `mc.levelRenderer`/being-driven at extract+prepare. This is exactly why our :298 `mc.levelRenderer` repoint is load-bearing: if the repoint brackets the dest extract+prepare, both phases route to the dest LevelRenderer's SWR. The RUNTIME half (does `sodium$setRenderer`/getRenderState actually fire inside the repointed window for the dest pass) is not statically decidable â€” remains a runtime probe.

=========================================================
(c) THE D1 SWAP-SET CENSUS â€” see the dedicated `swapSetClassification` field for the full per-field table. Summary of the driver anatomy proven here (CORRECTS the docs' "RSM.update"):
=========================================================
There is NO `RenderSectionManager.update` and NO `setupTerrain` on RSM. `javap -p RenderSectionManager` method table has NONE of those names. The driver is `SodiumWorldRenderer.setupTerrain(Camera,Viewport,FogParameters,boolean,boolean,Matrix4f)` which, per its `javap -c`, runs on the render thread:
  (HEAD) re-read useEntityCulling from options; reload() if renderDistance changed;
  compute cameraChanged/fogChanged/rotChanged/matrixChanged from SWR camera-cache fields (lastCameraPos/Pitch/Yaw/lastFogParameters/cullMatrix) â†’ if any, RSM.notifyChangedCamera();
  RSM.prepareFrame(camPos);
  loop bound = updateChunksImmediately ? renderDistance : 1  (offset 358â€“371), body:
     RSM.prepareRender();  â† frame++ here
     RSM.prepareRenderTrees(viewport,fog,arg4);  â† consume(false)+scheduleAsyncWork
     RSM.cleanupAndFlip(ubo); RSM.updateChunks(viewport,arg5); RSM.processChunkBuilds(...);
     break when !RSM.needsUpdate();
  RSM.finalizeRenderLists(camera,viewport,fog,arg5);  â† readRenderListFromTree OR renderOutOfGraph
  RSM.tickVisibleRenders();
Field mutability facts that shape the swap mechanism (all javap -p): `private final int renderDistance` (needs @Shadow @Final @Mutable â€” STILL final, matches map); `renderLists/renderTree/taskLists/pendingTask/frame/needsGraphUpdate/needsRenderListUpdate/cameraChanged/cameraStableSince/cameraPosition` are ALL non-final (reference-swappable); BUT `cullResults` is `private final Map<CullType,SectionTree>` and `cameraTimingControl` is `private final AsyncCameraTimingControl` â€” FINAL, so IP's field-reference swap CANNOT swap them (content-swap/reset required). `renderableSectionTree` is `private final RemovableMultiForest` and is world-derived (NEVER-SWAP).

=========================================================
(d) CullTask ctor + RSM.scheduleAsyncWork â€” does the setupTerrain Viewport flow IN or a copy? (P4)
=========================================================
`RSM.scheduleAsyncWork(Viewport,FogParameters,boolean)` bytecode:
  28: new #269 // CullTask
  33: aload_1                         // â† the Viewport arg, passed straight through
  38: getfield #257 // frame           // captures the CURRENT frame
  43: getfield occlusionCuller
  46: iload_3                          // useOcclusionCulling
  48: getfield level
  51: invokespecial CullTask."<init>":(Viewport,FF,I,OcclusionCuller,Z,Level)V
  54: putfield #90 // pendingTask
  74: CullTask.submitTo(asyncCullExecutor)   // = executor.submit(this)
The Viewport `aload_1` is the same instance threaded from setupTerrain arg2 â†’ prepareRenderTrees arg1 â†’ scheduleAsyncWork arg1. `CullTask` ctor delegates to `AsyncRenderTask.<init>(Viewport,int)` which stores it DIRECTLY:
  AsyncRenderTask.<init>:  5: aload_1; 6: putfield #7 // viewport   (field is `protected final Viewport viewport;`)
`CullTask.runTask()` reads `getfield viewport #42` (the same instance) and hands it to TaskCollectingTree/SectionTree/RayOcclusionSectionTree ctors + OcclusionCuller.findVisible. NO copy anywhere.
â‡’ VERDICT (P4, STATICALLY SETTLED): the setupTerrain Viewport instance flows BY REFERENCE into the CullTask, stored as `protected final Viewport viewport`, no defensive copy. `submitTo` = `executor.submit(this)` â€” the happens-before edge. The D2 design (duck fields on the Viewport instance, published before submit, read on the worker) is viable AS-IS; NO copy-site propagation inject is needed.

=========================================================
(e) RSM drive: what updateChunksImmediately + FlawlessFrames.isActive gate â€” is there a SYNCHRONOUS cull/build path? (P10)
=========================================================
`FlawlessFrames.isActive()Z` confirmed EXISTS-IDENTICAL. In LevelExtractorMixin.cullTerrain it is computed (`12: invokestatic FlawlessFrames.isActive; 15: istore 7`) and passed as setupTerrain's 5th boolean arg (updateChunksImmediately); `smartCull` (from CameraRenderState.smartCull) is passed as the 4th boolean.
TWO synchronous mechanisms proven:
1) BUILD-DRAIN LOOP CAP â€” setupTerrain offset 358: `iload 5` (updateChunksImmediately); if true push `renderDistance` else push `1` (offset 363/370) as the loop bound. The body drains prepareRender/prepareRenderTrees/cleanupAndFlip/updateChunks/processChunkBuilds and breaks on `!needsUpdate()` (`needsUpdate()` = `return needsGraphUpdate`). So updateChunksImmediately raises the per-frame build/mesh drain cap from 1 to renderDistance iterations.
2) SYNC RENDER-LIST PATH â€” `finalizeRenderLists(...,boolean arg4)`:
   0: cameraTimingControl.getShouldRenderSync(camera) â†’ iload5
  10: iload 4 (updateChunksImmediately) ifne â†’ 34 (renderOutOfGraph)
  15: iload 5 ifeq â†’ 43 (async tree path)
  20: needsGraphUpdate ifne â†’ 34; 27: needsRenderListUpdate ifeq â†’ 43 else â†’ 34
  So when updateChunksImmediately is TRUE, finalizeRenderLists ALWAYS takes `renderOutOfGraph`. `renderOutOfGraph` builds render lists SYNCHRONOUSLY from the world-derived `renderableSectionTree` (RemovableMultiForest.prepareForTraversal+traverse) via a `FallbackVisibleChunkCollector`, writing renderLists/taskLists/renderTree directly â€” NO async CullTask wait.
3) BLOCKING FLUSH â€” additionally, `readRenderListFromTree` (the async path) itself blocks when no valid cached tree exists: `findBestTree` null â†’ (in-graph) `34: iconst_1; 35: consumeCullTaskResults(TRUE)` then findBestTree again. `consumeCullTaskResults(true)` skips the isDone() poll and calls `pendingTask.getResult()` = `future.get()` (BLOCKS). So even the non-FlawlessFrames path will block-wait that frame for a freshly-scheduled cull when it has no cached tree.
â‡’ VERDICT (P10, STATICALLY SETTLED, POSITIVE): updateChunksImmediately (= FlawlessFrames.isActive) gates a genuine synchronous path â€” it forces `renderOutOfGraph` (immediate render list off the world-derived renderableSectionTree) AND raises the build-drain cap. Independently, readRenderListFromTree does a blocking `future.get()` when it has no valid tree. IMPLICATION for D1/P3: a cold swapped-in context is NOT doomed to show nothing for N frames â€” either arm FlawlessFrames (renderOutOfGraph, same-frame terrain from renderableSectionTree, which is NEVER-SWAP world state) or rely on the blocking flush inside readRenderListFromTree to consume the same-pass-scheduled cull. This means the design's persistent cross-frame registry (to keep the CullTask alive to a later pass) can be SIMPLIFIED toward a per-pass path that forces updateChunksImmediately=true for cold portal passes â€” the design's Â§3.1.5 "@ModifyVariable only on P10 evidence" is now GREEN-LIT by evidence. (The design's `@ModifyVariable(updateChunksImmediately=true)` should target setupTerrain arg5; note this also forces the build cap to renderDistance â€” a perf cost bounded per pass.)

=========================================================
(f) Viewport.isBoxVisible(III) â€” testSection arg derivation, and the BFS gate chain (P6a)
=========================================================
`javap -c Viewport.isBoxVisible(int,int,int)`:
   iload_1 - transform.intX; i2f; - transform.fracX  â†’ fstore 4
   iload_2 - transform.intY; i2f; - transform.fracY  â†’ fstore 5
   iload_3 - transform.intZ; i2f; - transform.fracZ  â†’ fstore 6
   frustum.testSection(fload4, fload5, fload6)Z; ireturn
So the 3 int args are converted to CAMERA-RELATIVE FLOATS: `(argX âˆ’ CameraTransform.intX) âˆ’ fracX`, i.e. section-coordinate minus the integer+fractional camera position. The 3 ints are the section CENTER: `OcclusionCuller.isWithinFrustum(Viewport,RenderSection)` = `viewport.isBoxVisible(section.getCenterX(), getCenterY(), getCenterZ())`. So `Frustum.testSection(FFF)` receives the CAMERA-RELATIVE SECTION CENTER as 3 floats.
BFS gate chain confirmed: inside OcclusionCuller's traversal, `187: invokestatic isWithinFrustum(Viewport,RenderSection)` â†’ if false, `blockLocalIncoming()` (section culled). `isWithinFrustum` is `private static` (matches map) â†’ `isBoxVisible(III)` â†’ `testSection(FFF)`. A parallel `public static isWithinNearbySectionFrustum` also routes through isBoxVisible(III).
â‡’ VERDICT (P6a, STATICALLY SETTLED): testSection args = camera-relative section CENTER (float). The D2 consumer (`@WrapOperation` on `Frustum.testSection(FFF)Z` inside `Viewport.isBoxVisible(III)Z`) with `centerÂ±8` AABB reconstruction is correct â€” centerÂ±8 rebuilds the 16Â³ section AABB in the exact camera-relative float space that `canDetermineInvisibleWithCameraCoord` already consumes. The chain `isWithinFrustum â†’ isBoxVisible(III) â†’ testSection` is the terrain-BFS visibility gate as the 091 map claimed.

=========================================================
(g) DefaultChunkRenderer.render + RenderListProvider path â€” re-fetch region.getRenderList() per draw, or stored refs? (P12)
=========================================================
`DefaultChunkRenderer.render(ChunkRenderMatrices, ChunkRenderListIterable, TerrainRenderPass, CameraTransform, FogParameters, boolean, GpuSampler, GpuBufferSlice, GpuBuffer)` â€” the render list is passed IN as a `ChunkRenderListIterable`. `javap -c` shows it iterates `ChunkRenderListIterable.iterator(isTranslucent)` â†’ `Iterator<ChunkRenderList>`; per element it calls `ChunkRenderList.getRegion()` (offset 78/383) then `region.getStorage(pass)/getResources()/getCachedBatch(pass)`. It NEVER calls `region.getRenderList()`. Each `ChunkRenderList` carries `private final RenderRegion region` (confirmed on ChunkRenderList) exposed via `getRegion()`.
The list handed to draw is the swapped field: `SWR.renderLayer` reads `renderSectionManager.getRenderLists()` (`14: RenderSectionManager.getRenderLists()`) and passes it to `ChunkRenderer.render(matrices, <that SortedRenderLists>, pass, â€¦)` (offset 60). `RSM.getRenderLists()` returns the `renderLists` field.
â‡’ VERDICT (P12, STATICALLY SETTLED): the DRAW path reads STORED refs â€” it iterates the `SortedRenderLists` snapshot (the swapped `renderLists`), each `ChunkRenderList` already holding its `getRegion()`. No per-draw `region.getRenderList()` re-fetch. Consequence for #3: the per-layer-list hazard lives entirely in the COLLECTION phase (VisibleChunkCollector, which does call `region.getRenderList()` â†’ `getLastVisibleFrame()` â†’ `reset(int)`), not the draw phase; and because the drawn `renderLists` is a per-pass snapshot, swapping `renderLists` isolates the DRAW regardless of #3.

=========================================================
(h) DrawContext.create() â€” GL vs VK backend selection (P9)
=========================================================
`DrawContext.create()`: reads static `DrawBackend.BACKEND` and branches: `== OPENGL â†’ new GLDrawContext`; `== VK_MULTIDRAW â†’ new VKMultiDrawContext`; `== VK_INDIRECT â†’ new VKIndirectContext`; else `throw IllegalStateException("Unknown backend")`. `DrawBackend.<clinit>`: `45: invokestatic chooseBackend(); 48: putstatic BACKEND`. `chooseBackend()`:
   0: RenderSystem.getDevice() (Mojang GpuDevice)
   5: checkcast GpuDeviceAccessor; sodium$getBackend()
  13: instanceof com/mojang/blaze3d/vulkan/VulkanDevice
  16: ifeq 63 â†’ return OPENGL
   (VulkanDevice branch) multiDrawDirectInterleaved â†’ VK_MULTIDRAW; else multiDrawIndirect â†’ VK_INDIRECT; else throw
â‡’ VERDICT (P9, STATICALLY SETTLED): the backend is AUTO-DETECTED from Minecraft's own active `RenderSystem.getDevice()` â€” VK only if MC itself is running its `com.mojang.blaze3d.vulkan.VulkanDevice` backend; otherwise OPENGL. It is NOT a Sodium opt-in config. On a normal GL machine (MC's default OpenGL device), chooseBackend() returns OPENGL â†’ GLDrawContext. The VK contexts are reachable ONLY when MC is launched on its experimental Vulkan device backend. IMPLICATION for D4: the clip chain (GL_CLIP_DISTANCE0 + loose uniform via `com/mojang/blaze3d/opengl/GlCommandEncoder`) is GL-only by construction; under MC-Vulkan the OpenGL-targeted GlCommandEncoderClipMixin will not even load/fire, so clipping silently no-ops (does NOT misbehave). Detect via `RenderSystem.getDevice() instanceof VulkanDevice` (or DrawBackend.BACKEND != OPENGL). Not the common path; not a dead letter.

=========================================================
CONFIRMATIONS (feed P5)
=========================================================
`ChunkRenderList.reset(int)` â€” EXISTS (`public void reset(int);`). `ChunkRenderList.getLastVisibleFrame()` â€” EXISTS (`public int getLastVisibleFrame();`). `ChunkRenderList` also holds `private final RenderRegion region` + `getRegion()`. The reset trigger is `VisibleChunkCollector`/`readRenderListFromTree` at collection: `region.getRenderList() â†’ getLastVisibleFrame() != frame â†’ reset(frame)`. Because `frame` is a per-PASS counter incremented in `prepareRender()` (`frame = frame + 1`), a portal sub-pass that runs setupTerrain on a SHARED RSM (same-dim) advances the shared `frame`, which is precisely the #3 reset trigger on shared regions â€” so `frame` should join the swap set (see (c)/swapSetClassification), OR the #3 per-layer-list fix must ship. `SortedRenderLists.empty()` â€” EXISTS, returns `getstatic EMPTY` where `private static final SortedRenderLists EMPTY;` â€” an immutable EMPTY singleton (confirmed the seed survives). `FlawlessFrames.isActive()Z` â€” EXISTS-IDENTICAL.

## SWAP-SET CLASSIFICATION
FULL FIELD CENSUS of RenderSectionManager (RSM) + SodiumWorldRenderer (SWR), each classified SWAP (camera/view-derived â€” must isolate per portal view) / NEVER-SWAP (world/build/GPU/timing infra) / UNCERTAIN(+why). Mutability from `javap -p`; classification proven by the `javap -c` bytecode of setupTerrain / prepareRender / notifyChangedCamera / prepareFrame / prepareRenderTrees / scheduleAsyncWork / consumeCullTaskResults / finalizeRenderLists / findBestTree / readRenderListFromTree / renderOutOfGraph (all quoted in the report). This EXPANDS the design's Â§3.1.2 minimum set (which named only renderLists/renderDistance/renderTree/cullResults/pending/frame/SWR-cache) with six additional per-view fields and flags two FINAL collections that cannot be reference-swapped.

===== RenderSectionManager â€” SWAP (camera/view-derived) =====
â€¢ renderLists : SortedRenderLists (non-final) â€” SWAP. Per-view output list; regenerated by readRenderListFromTree/renderOutOfGraph, consumed by the draw path (SWR.renderLayer â†’ getRenderLists()). Seed = SortedRenderLists.empty(). [the map/design already list this]
â€¢ renderTree : SectionTree (non-final) â€” SWAP. Per-view current cull tree; set from findBestTree/renderOutOfGraph. Seed = null (cold = legitimate).
â€¢ pendingTask : CullTask (non-final) â€” SWAP. THE pending async cull task for this view; set in scheduleAsyncWork (`putfield pendingTask`), cleared in consumeCullTaskResults. Must swap so a portal pass's in-flight cull is not consumed into the outer context (and vice-versa). Seed = null. This is the exact "pending-async-task field" the design asked to name.
â€¢ taskLists : DeferredTaskList (non-final) â€” SWAP (was NOT in Â§3.1.2). Set in consumeCullTaskResults (`= result.getPendingTaskLists()`) and renderOutOfGraph (`= fallbackCollector.getPendingTaskLists()`); consumed by updateChunks/submitDeferredSectionTasks. View-derived deferred-build list â€” swap so a portal view's deferred builds don't bleed into the outer world's build submission.
â€¢ frame : int (non-final) â€” SWAP. Incremented once per prepareRender (`frame=frame+1`); captured by CullTask (schedule-time), used by cameraStableSince (notifyChangedCamera sets `cameraStableSince=frame`), by cull-staleness (consumeCullTaskResults: `cameraStableSince <= task.getFrame()`), and by ChunkRenderList.reset(int)/getLastVisibleFrame (the #3 trigger). It is per-PASS, NOT per-camera (correction to Â§3.1.2's "IF per-camera"): a shared-RSM portal sub-pass advances the outer world's frame â†’ swap it to neutralize the #3 reset trigger and keep frame-based staleness per-context.
â€¢ needsRenderListUpdate : boolean (non-final) â€” SWAP. Set by invalidateRenderLists (fires when a new cull lands or camera changed), cleared in finalizeRenderLists. Per-view render-list dirty flag.
â€¢ cameraChanged : boolean (non-final) â€” SWAP. Set true by notifyChangedCamera; consumed/reset in prepareRender (â†’invalidateRenderLists) and finalizeRenderLists. Camera-derived.
â€¢ cameraStableSince : int (non-final) â€” SWAP. Set to `frame` in notifyChangedCamera; gates LOCAL cull-tree acceptance in consumeCullTaskResults. Camera-derived.
â€¢ cameraPosition : Vector3dc (non-final) â€” SWAP. Set in prepareFrame from the camera position arg. Camera-derived.
â€¢ needsGraphUpdate : boolean (non-final) â€” SWAP (leaning; world-origin dirty flag consumed per-view). Set true by markGraphDirty (world/section change), cleared in scheduleAsyncWork; `needsUpdate()` returns it (the setupTerrain loop-termination). Swap so a portal pass scheduling a cull (which clears it) does not suppress the outer world's needed re-cull, and so loop termination is per-context. UNCERTAINTY: its origin is world-change (a block edit dirties every view), so one could argue NEVER-SWAP; but it is consumed per-view to gate cull scheduling and loop exit, so isolation is the safe default â€” flag for the live same-dim discriminator.
â€¢ renderDistance : final int â€” SWAP, via @Shadow @Final @Mutable (STILL final on 0.9.1 â€” matches the map). View config; must track the swapped-in context and SWR.renderDistance.

===== RenderSectionManager â€” FINAL camera/view-derived (CANNOT reference-swap â†’ UNCERTAIN: content-swap or reset) =====
â€¢ cullResults : final Map<CullType,SectionTree> â€” UNCERTAIN(FINAL). Per-view async-cull cache; populated by consumeCullTaskResults (`.put(LOCAL/REGULAR/WIDE, tree)`), LOCAL removed on cameraChanged in prepareRenderTrees; read by findBestTree. It IS view/camera state, but being `final` the reference can't be swapped like IP's mechanism â€” the widened context must CLEAR+repopulate (content-swap) or accept sharing. Partial self-protection: findBestTree validates each tree via `SectionTree.isValidFor(viewport, dist)`, so a portal's cull tree is rejected for the outer viewport â€” but the LOCAL-removal and staleness bookkeeping still argue for content isolation. VERDICT: treat as SWAP-CONTENT (clear on swap-in, restore on swap-out); confirm the exact isolation need with the same-dim mirror live discriminator (P3).
â€¢ cameraTimingControl : final AsyncCameraTimingControl â€” UNCERTAIN(FINAL). Holds `previousPosition : Vec3` + `isSyncRendering : boolean` (camera-derived); `getShouldRenderSync(Camera)` in finalizeRenderLists decides the sync vs async render-list path. A portal camera's position would update previousPosition and thrash the outer camera's sync detection. Final â†’ content-reset needed, not reference-swap. VERDICT: SWAP-CONTENT or reset-on-swap; low-severity (worst case = an extra sync/async flip), acceptable-degradation candidate if content-swap is awkward.

===== RenderSectionManager â€” NEVER-SWAP (world / build / GPU / timing infra) =====
â€¢ Constants (static final): NEARBY_REBUILD_DISTANCE, IMMEDIATE_PRESENT_DISTANCE, NEARBY_SORT_DISTANCE, FRAME_DURATION_UPLOAD_FRACTION, MIN_UPLOAD_DURATION_BUDGET, FRAME_DURATION_UPDATE_RATIO, CULL_DURATION_UPDATE_RATIO.
â€¢ builder : final ChunkBuilder â€” build thread infra.
â€¢ regions : final RenderRegionManager â€” GPU region/buffer storage (world geometry).
â€¢ sectionCache : final ClonedChunkSectionCache â€” world chunk-data cache.
â€¢ renderSections : final SectionStorage â€” world section storage (the map explicitly: NEVER SWAP).
â€¢ buildResults : final ConcurrentLinkedDeque<â€¦> â€” build outputs queue.
â€¢ jobDurationEstimator / meshTaskSizeEstimator / jobUploadDurationEstimator : final â€” perf estimators.
â€¢ lastBlockingCollector : ChunkJobCollector (non-final) â€” build-job collector (build pipeline, not camera).
â€¢ thisFrameBlockingTasks / nextFrameBlockingTasks / deferredTasks : int (non-final) â€” build-task counters (build pipeline, shared).
â€¢ chunkRenderer : final ChunkRenderer â€” the GPU draw backend (shared).
â€¢ level : final ClientLevel â€” world.
â€¢ sectionsWithGlobalEntities : final ReferenceSet<RenderSection> â€” world global-entity sections.
â€¢ occlusionCuller : final OcclusionCuller â€” holds the world SectionStorage; the async cull engine (shared; the per-pass camera enters via the Viewport passed to the CullTask, NOT via this field).
â€¢ sortBehavior : final SortBehavior â€” config.
â€¢ sortTriggering : final SortTriggering â€” translucency GFNI sort infra (world).
â€¢ importantTasks : final EnumMap<DeferMode,â€¦> â€” build task queues.
â€¢ asyncCullExecutor : final ExecutorService â€” the shared async cull thread pool.
â€¢ renderableSectionTree : final RemovableMultiForest â€” WORLD-derived tree of sections that have renderable geometry; updated on onSectionAdded/Removed; read by renderOutOfGraph. NEVER-SWAP (map: RemovableMultiForest = world-derived). This is what makes the renderOutOfGraph sync path safe under a swapped view (it reads shared world geometry, writes the swapped renderLists).
â€¢ lastFrameDuration / averageFrameDuration / lastFrameAtTime / averageCullDurationNanos : long â€” timing/perf telemetry.

===== SodiumWorldRenderer â€” SWAP (camera-cache; else portal passes thrash setupTerrain's camera-changed detection) =====
These are the "SWR-level camera-cache fields" the design asked the census to reveal. setupTerrain computes cameraChanged/fogChanged/rotChanged/matrixChanged by comparing the live camera to these, then updates them:
â€¢ renderDistance : int (non-final) â€” SWAP. View config (must match RSM.renderDistance); reload() fires on mismatch at setupTerrain HEAD.
â€¢ lastCameraPos : Vector3d (non-final) â€” SWAP. Camera-changed + GFNI-movement basis.
â€¢ lastCameraPitch : double (non-final) â€” SWAP. Rotation-changed basis.
â€¢ lastCameraYaw : double (non-final) â€” SWAP. Rotation-changed basis.
â€¢ lastFogParameters : FogParameters (non-final) â€” SWAP. Fog-cull-distance-changed basis.
â€¢ cullMatrix : Matrix4f (non-final) â€” SWAP. Matrix-changed basis (cull frustum matrix).
NOTE: if these are NOT swapped, every portal pass sees a "camera moved" delta vs the outer camera and fires notifyChangedCamera()/invalidateRenderLists() spuriously â€” thrashing camera-stable detection for BOTH views. This is exactly the design's stated failure mode.

===== SodiumWorldRenderer â€” NEVER-SWAP =====
â€¢ client : final Minecraft â€” infra.
â€¢ level : ClientLevel (non-final) â€” world (set via setLevel on level change, not per pass).
â€¢ useEntityCulling : boolean (non-final) â€” re-read from SodiumOptions at setupTerrain HEAD every call â†’ self-refreshing config, NEVER-SWAP.
â€¢ useTranslucencySorting : boolean (non-final) â€” config.
â€¢ uniformBufferManager : UniformBufferManager â€” GPU UBO infra.
â€¢ MAX_ENTITY_CHECK_VOLUME : static final double â€” constant.
â€¢ renderSectionManager : RenderSectionManager (non-final) â€” NEVER-SWAP THE REFERENCE. This is the HOST the ip_swapContext operates INSIDE (its per-view fields above are the swap set). Same-dim portal isolation swaps inside this one RSM; cross-dim isolation is provided by a different per-dim LevelRendererâ†’SWRâ†’RSM (P1). Reference-swapping the whole RSM is an alternative architecture but is NOT what IP's ip_swapContext does.

===== NET GUIDANCE FOR D1 =====
Reference-swappable set (IP-style symmetric 3-way tmp swap): {renderLists, renderTree, pendingTask, taskLists, frame, needsGraphUpdate, needsRenderListUpdate, cameraChanged, cameraStableSince, cameraPosition} on RSM + {renderDistance(@Mutable), lastCameraPos, lastCameraPitch, lastCameraYaw, lastFogParameters, cullMatrix} on SWR + renderDistance(@Mutable) on RSM. Content-handle (final, can't reference-swap): cullResults (Map â€” clear/restore) and cameraTimingControl (reset previousPosition/isSyncRendering). Everything else NEVER-SWAP. Validate guards on swap-in: renderDistance != 0; a cold context has null renderTree/pendingTask and empty renderLists (legitimate). The renderOutOfGraph sync path (armed via updateChunksImmediately) reads only NEVER-SWAP world state (renderableSectionTree) and writes only SWAP fields (renderLists/taskLists/renderTree) â€” so a cold swapped-in context can be made to converge SAME-PASS, which materially simplifies the persistence requirement (see P10).

## STATIC PROBE ANSWERS
- P4 â€” STATICALLY SETTLED (fully). The setupTerrain Viewport flows BY REFERENCE into the CullTask: RSM.scheduleAsyncWork does `new CullTask(aload_1 /*viewport*/, â€¦, this.frame, â€¦)` then submitTo(executor); CullTaskâ†’AsyncRenderTask.<init>(Viewport,int) stores `protected final Viewport viewport` with a direct `putfield` (no copy); runTask reads that same instance. `submitTo`=`executor.submit(this)` is the happens-before edge. â‡’ D2 duck-fields-on-Viewport are viable as-is; NO copy-site propagation inject needed. The P4 contingency in Â§3.2 can be dropped.
- P6a â€” STATICALLY SETTLED (fully). Viewport.isBoxVisible(III) converts the 3 int args to camera-relative floats `(arg âˆ’ CameraTransform.intXYZ) âˆ’ fracXYZ` and calls `Frustum.testSection(FFF)`. The 3 ints are the section CENTER (OcclusionCuller.isWithinFrustum passes section.getCenterX/Y/Z). So testSection receives the camera-relative section center. The BFS gate chain isWithinFrustum(private static) â†’ isBoxVisible(III) â†’ testSection(FFF) is confirmed (isWithinFrustum called in the traversal; falseâ†’blockLocalIncoming). â‡’ The D2 consumer `@WrapOperation Frustum.testSection(FFF)Z` inside isBoxVisible(III) with centerÂ±8 AABB reconstruction is exactly right.
- P9 â€” STATICALLY SETTLED (fully). DrawContext.create() switches on static DrawBackend.BACKEND = chooseBackend(): `RenderSystem.getDevice()`; if `instanceof com.mojang.blaze3d.vulkan.VulkanDevice` â†’ VK_MULTIDRAW/VK_INDIRECT (by device features) else â†’ OPENGL. Backend AUTO-DETECTS from MC's own active GpuDevice, NOT a Sodium opt-in. On a normal GL machine MC's device is not VulkanDevice â†’ OPENGL â†’ GLDrawContext. VK reachable ONLY if MC itself runs its experimental Vulkan device backend. â‡’ D4: clipping (GL_CLIP_DISTANCE0 + the com.mojang.blaze3d.opengl.GlCommandEncoder uploader) is GL-only by construction; under MC-Vulkan the OpenGL-targeted mixin never loads so clipping silently no-ops (does not misbehave). Detect via `getDevice() instanceof VulkanDevice`. Not the common path, not a dead letter.
- P10 â€” STATICALLY SETTLED (fully, POSITIVE). updateChunksImmediately (fed by FlawlessFrames.isActive(), which EXISTS) gates a real synchronous path: in setupTerrain it raises the build-drain loop cap from 1 to renderDistance (offset 358â€“371, break on !needsUpdate()); in finalizeRenderLists it FORCES the `renderOutOfGraph` branch, which builds render lists synchronously from the world-derived renderableSectionTree via FallbackVisibleChunkCollector â€” no async CullTask wait. Independently, readRenderListFromTree does a BLOCKING `consumeCullTaskResults(true)` = future.get() when it has no valid cached tree. â‡’ A cold swapped-in portal context can converge SAME-PASS (arm FlawlessFrames â†’ renderOutOfGraph, or rely on the blocking flush). The design's optional `@ModifyVariable(updateChunksImmediately=true)` for cold portal passes is EVIDENCE-BACKED (target setupTerrain arg5); this can simplify D1 away from a cross-frame persistent registry (at a bounded per-pass build-cap/blocking-cull perf cost).
- P12 â€” STATICALLY SETTLED (fully). The DRAW path reads STORED refs, not a per-draw region.getRenderList(). DefaultChunkRenderer.render takes a ChunkRenderListIterable, iterates ChunkRenderList elements, and reads `ChunkRenderList.getRegion()` (each list holds `private final RenderRegion region`) â†’ getStorage/getResources/getCachedBatch. SWR.renderLayer feeds it `RSM.getRenderLists()` (the swapped `renderLists`). â‡’ #3's per-layer-list hazard is confined to the COLLECTION phase (VisibleChunkCollector calls region.getRenderList()â†’getLastVisibleFrame()â†’reset(int)); swapping `renderLists` isolates the DRAW regardless. Confirmed reset(int)+getLastVisibleFrame() exist and SortedRenderLists.empty() returns the immutable static EMPTY (P5 inputs).
- P1 â€” PARTIALLY SETTLED (static half only). STATIC HALF SETTLED: Sodium's core LevelRendererMixin holds SodiumWorldRenderer as a NON-static per-LevelRenderer instance field `renderer`, constructed `new SodiumWorldRenderer(mc.getInstance())` at LevelRenderer init, with NO `mc.levelRenderer==this` guard anywhere in the class. EXTRACT routing reads `Minecraft.getInstance().levelRenderer` directly via checkRenderer() (getfield Minecraft.levelRenderer â†’ sodium$getWorldRenderer â†’ cache). SUBMIT routing bakes `this.renderer` (this = the LevelRenderer whose loop calls prepareChunkRenders, i.e. mc.levelRenderer) onto the ChunkSectionsToRender at prepare via sodium$setRendering. So both phases key off whichever LevelRenderer is mc.levelRenderer/being-driven at extract+prepare â€” our :298 mc.levelRenderer repoint is the correct and load-bearing lever. RUNTIME HALF REMAINS: whether per-dim ClientWorldLoader LevelRenderers each actually acquire an initialized SWR, and whether checkRenderer()/sodium$setRenderer/prepareChunkRenders truly fire inside the :298â†’:424 repointed window for the dest pass, are runtime-only (C2-0 baseline round: SWR identityHashCode per dim + the cross-dim dest-aperture discriminator).
- P5 â€” CONFIRMATIONS SETTLED, TRIGGER REMAINS RUNTIME. Statically confirmed: ChunkRenderList.reset(int) and getLastVisibleFrame() EXIST; the reset fires in the collection phase when getLastVisibleFrame()!=frame; `frame` is a per-PASS counter incremented in prepareRender (so a shared-RSM same-dim portal sub-pass DOES advance the outer world's frame â†’ the #3 trigger structurally persists). SortedRenderLists.empty() returns the immutable static EMPTY. Whether the mid-frame reset actually corrupts the outer translucent pass UNDER THE D1 SWAP (a swapped `frame` would neutralize it) is runtime â€” decided by the C2-0/C2-1 reset instrumentation with same-dim portals on screen. â‡’ #3 defaults to SHIP (frame is per-pass and the trigger exists) unless P5 evidence with a swapped frame shows it neutralized.
- P3 â€” RUNTIME-ONLY (design fork). Whether a cold swapped-in context yields populated dest renderLists same-pass vs N frames, and whether the naive swap corrupts the dim's persistent state on same-dim portals, is runtime. BUT the census narrows it decisively: renderOutOfGraph (P10) gives a same-pass sync fallback off world-derived renderableSectionTree, and readRenderListFromTree blocks on the same-pass cull â€” so the 'CullTask dies with a per-pass fresh context' worry is largely answered (the swap must bracket both prepareRenderTrees and finalizeRenderLists, which it does, both being inside setupTerrain inside our extract bracket). The remaining runtime question is convergence latency/quality, measured in the C2-1 live round.
- P8/P11/P7 â€” NOT ADDRESSED by this census (out of the (a)-(h) scope): fog double-apply (P8), iris-installed shaders-off sodium GLSL routing (P11), and clip-transport source/upload reach (P7a/b/c) are runtime/source-seam probes for later stages, not javap-settleable here.