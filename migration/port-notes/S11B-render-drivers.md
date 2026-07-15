# S11-B — Render drivers + the CUTOVER_SPEC (second third of U9)

Stage S11-B of the entity-portal migration: the **render DRIVERS** — the hardest stage. Six held IP
render classes land at their verbatim `qouteall.imm_ptl.core.render.*` paths, re-expressed onto the
mod's PROVEN 26.2 render mechanics and reconciled with the S11-A render-context foundation, plus the
**`CUTOVER_SPEC.md`** the `API_RISKS.md` render-cutover verdict demands before the S13 atomic commit.
**All six classes are inert until S13** (held-source `ip_scc_closed` filter); **no live
`com.warwa.seamlessportals` render file was edited** (the always-on stencil-substrate KEEP mixins stay
bound to their live sources in both flag states); shipping `:common`+`:fabric`+`:neoforge` stay GREEN.

**Footprint (git working tree):** 6 NEW held classes + the `CUTOVER_SPEC.md` deliverable. No `com.warwa`
render file touched; no `.accesswidener` / NeoForge-AT / `build.gradle` edit (§6). `git diff HEAD` is
empty — every S11-B artifact is a `??` untracked NEW file; live block-era render is byte-for-byte
untouched. Held probe `-Pip_scc_closed=true`: **195 (S11-A) → 176 stable** (§7).

**Authored:**
- `render/MyGameRenderer.java` — the world-context switch driver (REPLACE-BY `PortalContextSwitch`).
- `render/MyRenderHelper.java` — IP-add survivors + the S11-B FixGaps `getPortalAreaRenderType` family.
- `render/FrontClipping.java` — the BRIDGE to the single live `com.warwa` view-space plane store.
- `render/ImmPtlViewArea.java` — R4: a genuine 26.2 `ViewArea` SUBCLASS, unbounded coord-pinned store.
- `render/VisibleSectionDiscovery.java` — IP traversal VERBATIM + the mod's required-26.2 compile fold.
- `render/ViewAreaRenderer.java` — shape-polymorphic view-area mesh through `PortalRenderTypes.drawMesh`.
- `migration/CUTOVER_SPEC.md` — the atomic render-driver-core spec (R4/R5/R9/R2-A4/invariants/cutover set).

**Ground truth:** IP `MyGameRenderer.java:52` / `MyRenderHelper.java:61` / `FrontClipping.java:22` /
`ImmPtlViewArea` / `VisibleSectionDiscovery` / `ViewAreaRenderer`; `MIGRATION_API_MAP.md`;
`migration/api-map/{render-core,render-sub,current-mod-render}.md`; `migration/spikes/SPIKE-R4-viewarea.md`
+ `SPIKE-R1-sealevel.md`; `PHASE5_STENCIL_DIRECT_SPEC.md`; `RENDER_PIPELINE.md`;
`IP_CONTEXT_SWITCH_EXACT.md`; the S11-A port-note (`S11A-render-context.md`, carriage map §3 + flags
B1–B7); and `mc262-ref` source reads (every 26.2 API fact + geometry constant re-checked, D4.4).

Assembled from the two slice fragments (`fragments/S11B-gamerenderer.md` Slice A, `S11B-viewarea.md`
Slice B/C), the RESUME-STATE build/verify evidence, and the Fable adversarial re-verify executed this
run (§8). Citation conventions: `IP:` = 1.21.3 source, `26.2:` = `mc262-ref`, `MOD:` =
`common/src/main/java/com/warwa/seamlessportals`, `MOD-qouteall:` = the held qouteall-path port.

---

## 1. Diff-gate / reconcile record — the two context drivers + the FrontClipping bridge

**Diff-gate verdict:** every non-verbatim hunk across the six files is a mechanical 26.2 API rename or an
api-map/spike-sanctioned re-expression of a GONE 1.21.3 mechanism. The only *representation* changes:
`ImmPtlViewArea` owns fields the 1.21.3 `ViewArea` supplied (G25), `ViewAreaRenderer`'s per-call mask GL
state moved into the pipeline (G6/G9), and `FrontClipping`'s fixed-function clip became the bridge feed
(G5/G9). **No IP LOGIC deviated.**

### 1.1 `MyGameRenderer` — `switchAndRenderTheWorld` PortalContextSwitch reconciliation

IP's `switchAndRenderTheWorld` is the world-**context** switch: SAVE `mc.level`/renderer/camera/lightmap/
fog/particles/buffers/frustum → SWAP them to the destination → hand the render to an `invokeWrapper`
supplied by the CALLER (`PortalRenderer.invokeWorldRendering`, U10/S12, which sets up stencil/depth/clip
around it) → RESTORE. Ported as the IP shell with the mod's proven 26.2 mechanisms
(`PortalContextSwitch.withSwitchedWorld`) transplanted where IP's 1.21.3 calls are dead.

**SCOPE LINE (load-bearing).** The DRIVER CORE — the per-dim `LevelExtractor.extract(destCamera)` +
`compileSections` drain + `LevelRenderState` re-point that make 26.2's `renderLevel` actually draw the
DESTINATION world (render-core **G1**; the ~500-line proven body at
`MOD:PortalContextSwitch.java:1533-2057`) — lives BEHIND `invokeWrapper` and is wired by the CUTOVER_SPEC
at S12/S13. This class ports the context shell and translates the swap set; it does NOT inline the mod's
driver-core lambda (that would drag `com.warwa` render types into a qouteall class and duplicate the live
driver). The **extract()/compileSections PAIRING** rule (§5.1) and the **R5 reversed-Z** depth
choreography (§2/§5) therefore belong to the S12/S13 wiring, handed forward, not implemented here.

**26.2 re-expressions applied** (each api-map-sanctioned; no IP logic deviated):

| IP 1.21.3 call | 26.2 re-expression | Map |
|---|---|---|
| `renderLevel(getTimer())` | `renderLevel(getDeltaTracker())` — survives public (`26.2:GameRenderer.java:525`); renders from ALREADY-EXTRACTED state, dest extract is driver-core | C2 / **G1** |
| `client.getProfiler()` | `Profiler.get()` → `ProfilerFiller` | G16 |
| `RenderSystem.getProjectionMatrix()` save + `resetProjectionMatrix()` | `RenderSystem.backupProjectionMatrix()` / `restoreProjectionMatrix()` bracketing the invoke (`MOD:PortalContextSwitch.java:1800`); saved-Matrix4f field dropped | G27/G19 |
| `IERenderSystem.ip_get/setModelViewStack(new Matrix4fStack)` swap | push identity on `RenderSystem.getModelViewStack()`, pop on restore (`MOD:…:1790-1792`); stack-object swap meaningless on 26.2 | G28 |
| `client.renderBuffers()` | `client.gameRenderer.renderBuffers()` (moved off Minecraft) | G15 |
| `gameRenderer.lightTexture()` save + `ip_setLightmapTextureManager(LightTexture)` | save the main `Lightmap` via `seamlessportals$getLightmap()` (the exact identity `RenderStates.onTotalRenderEnd` restores to); install `helper.lightmapTexture`; restore | **G20** |
| `helper.lightmapTexture.updateLightTexture(0)` first-visit prime | `helper.updateAndRender(virtualCamera, partialTick)` (the mod's proven `Lightmap.render(extract)` driver; virtual-camera CONFIG is S13) | G20 |
| `EntityRenderDispatcher.prepare(Level,Camera,Entity)` | `prepare(Camera,Entity)` (Level arg removed, `26.2:EntityRenderDispatcher.java:122`) | C-rename |

**DROPPED (flagged, not silently deleted; each handed to S12):**
- `getSectionRenderDispatcher().ip_setFixedBuffers(...)` secondary-buffer isolation — no held
  `IESectionRenderDispatcher`; 26.2 buffer model is `StagingBuffer` (G15/G26). CUTOVER_SPEC (S12) decides
  whether a `SectionRenderDispatcher` fixed-buffer accessor is needed or the `RenderBuffers` pool swap
  alone isolates (the live path relies on the pool swap alone).
- `setRenderHand(...)` + `ip_getDoRenderHand()` save/restore — no hand flag on 26.2 `GameRenderer`
  (**G18**); hand suppression maps to `WorldRenderInfo.doRenderHand` → `renderItemInHand` gate at U10/S12.
- `blockEntityRenderDispatcher.level = newWorld` — BERD has no `level` field (**G35**); dim context is
  `prepare(destCameraPos)` in the extract (driver-core).
- `portal_get/setTransparencyShader(...)` per-dim fabulous `PostChain` swap — no such member on the held
  `IEWorldRenderer`; 26.2 translucency is framegraph-driven (26.2-superseded).
- `renderBuffers().bufferSource().endBatch()` pool-exhausted fallback — `bufferSource`/`endBatch` GONE, no
  mid-batch flush model on 26.2 (**G2**).

**Carriage flags resolved.** The inline `RenderBuffers` pool (`acquire`/`returnRenderBuffersObject` +
`secondaryRenderBuffers` stack, `new RenderBuffers(0)`) is folded in **VERBATIM per B5** — NOT delegated
to the mod's standalone `PortalRenderBuffersPool`. `endFramePooled()` is the **ADDITIVE 26.2-required**
per-frame `endFrame()` on the pooled buffers (**B6**; memory `gpu-buffer-leak-endframe`), wired from
`GameRenderer.render` TAIL at S12/S13 — registered here so the S13 diff-gate does not flag it as an
unexplained IP-diff addition. The `@IPVanillaCopy` fog/lighting resets: `resetDiffuseLighting`
RE-EXPRESSED onto `gameRenderer.lighting().updateLevel(dimensionType().cardinalLightType())` (**G30**, the
exact DEFAULT/NETHER selector vanilla's own `GameRenderer.setLevel` uses); `resetFogState`/`updateFogColor`
are 26.2-SUPERSEDED inert shims (IP's `FogRenderer` statics GONE, **G31/G32**) — the real per-dim fog is
CUTOVER_SPEC §3 (R9).

### 1.2 `MyRenderHelper` — survivors + the deferred blit-draw family (probe-honesty slice split)

Carried the load-bearing survivors: `lateUpdateLight` (VERBATIM — B7, drops the mod-only
`isDestScopeLive` perf gate; gates only on `RenderStates.isDimensionRendered`, `runLightUpdates()` at
frame-END), `earlyRemoteUpload` (RE-EXPRESSED onto
`sectionRenderDispatcher().lock()/uploadTerrainBuffersToGpu()/unlock()`, **G26**; `getSectionRenderDispatcher()`
→ public `sectionRenderDispatcher()`, `26.2:LevelRenderer.java:908`),
`applyMirrorFaceCulling`/`recoverFaceCulling` (raw GL `glCullFace(GL_FRONT/GL_BACK)`), `restoreViewPort`
(`GlStateManager._viewport`; the 26.2 package move `com.mojang.blaze3d.platform` →
`com.mojang.blaze3d.opengl.GlStateManager` — a real slip the probe caught + fixed),
`transformFogDistance` (pure logic), `debugFramebuffer*` (raw `glReadPixels` +
`gameRenderer.mainRenderTarget()`, **G14**, behind `debugEnabled=false`), `init()` (the pinned
`IPModMainClient.java:79` entry, now a documented near-no-op — IP's body was entirely commented-out
1.21.3 shader loading; 26.2 pipelines live in `PortalRenderTypes`).

**DEFERRED (documented, zero forward-ref added):** the entire 1.21.3 immediate-mode SHADER/BLIT draw
family — the three `CoreShaders`, `drawPortalAreaWithFramebuffer`, `renderScreenTriangle*`,
`drawScreenFrameBuffer`/`drawFramebuffer*`, `clearAlphaTo1`. Every one rests on the full
submit→prepare→execute rewrite (**G6/G7/G8/G9/G12/G29/G40**); their 26.2 form is the mod's proven
`PortalRenderTypes.drawMesh` + `RenderTarget.blitAndBlendToTexture` substrate, and their ONLY consumers
are the still-unauthored `GuiPortalRendering`/`OverlayRendering`/`RendererUsingFrameBuffer` (A1). Authored
WITH those consuming slices — NOT half-ported here against GONE symbols (which would ADD probe errors).
Nothing currently authored calls them, so deferring is forward-ref-clean. `getPortalAreaRenderType` is
**NOT** part of that deferred family (§6).

### 1.3 `FrontClipping` — RECONCILE decision: **BRIDGE** to the single live plane store

**Decision: BRIDGE (zero live edits).** The qouteall `FrontClipping` exposes IP's full API surface but
does NOT open a second plane store. It computes IP's plane (IP API + IP plane SOURCE
`portal.getPortalShape().getOuterClipping` / `PortalRendering.getActiveClippingPlane`, IP's kept-half-space
semantics) in the mod's VIEW-SPACE representation and **feeds the single `com.warwa` store** via that live
class's public `restore(Snapshot)` / `disable()` — the exact store the always-on KEEP mixin
`GlCommandEncoderClipMixin` already uploads to `gl_ClipDistance[0]`.

**Why bridge, not duplicate (B1/B2).** IP's 1.21.3 `FrontClipping` drives fixed-function `GL_CLIP_PLANE0`
(`GL11.glEnable/glDisable`) + a per-"current shader" `GL20.glUniform4f` upload (via `IEShader`) — BOTH
GONE on 26.2 core-profile (render-core **G5/G9**). The mod's proven mechanism is `gl_ClipDistance[0]` fed
from a single view-space plane store, injected by `ShaderCodeTransformation` and uploaded per-draw by two
**always-on substrate KEEP** mixins (`GlCommandEncoderClipMixin`, `ShaderManagerCompilationCacheMixin`)
that bind to the `com.warwa` store in BOTH flag states. Carriage flag **B1** requires ONE plane store (no
two divergent stores feeding one uniform). The bridge satisfies B1 with the two KEEP mixins **UNCHANGED**,
does not edit the live `com.warwa` copy (bridge is additive-call-only via existing public
`restore`/`disable`/`Snapshot`), and the exclusivity ledger guarantees only one renderer drives per
session, so the two `FrontClipping`s never feed the uniform simultaneously (`!entityPortals`: com.warwa
drives, qouteall inert; `entityPortals`: qouteall computes → feeds com.warwa store → KEEP mixins upload).
`isClippingEnabled` (**B2**, the public static boolean pinned by `ClientTeleportationManager.java:462`; the
mod's `glClipEnabled` is private) is mirrored locally in lockstep with every bridge enable/disable
(`feedViewSpacePlane` sets it `true`, `disableClipping` sets it `false`).

Rejected alternative — **port-forward/duplicate** (qouteall owns its own store + retarget the two KEEP
mixins to a flag-aware source at S13): viable but touches the always-on KEEP mixins and risks two stores;
B1 explicitly prefers "mixins unchanged / one store", so bridge wins. The IP `double[]`
before/after-model-view equation getters (`getActiveClipPlaneEquation{Before,After}ModelView`) are still
computed for the held IP-contract but are **vestigial on 26.2** (they backed the dead `GL_CLIP_PLANE0`
path). `updateClippingEquationUniformForCurrentShader` / `unsetClippingUniform` are **26.2-superseded
no-ops** — the per-draw upload is `GlCommandEncoderClipMixin`, not a static "current shader" call (G5).

### 1.4 `ViewAreaRenderer` — the view-area mesh through `PortalRenderTypes.drawMesh`

IP draws the shape-polymorphic portal opening (`Portal.renderViewAreaMesh` → RECTANGULAR/BOX/SPECIAL_FLAT)
with a custom `portalAreaShader` (ShaderInstance) + `Tesselator`/`BufferUploader` + per-call
`GlStateManager` masks. All GONE on 26.2. Re-expression: `buildPortalViewAreaTrianglesBuffer` builds the
POSITION_COLOR **TRIANGLES** mesh into a growable `ByteBufferBuilder` + `BufferBuilder`, feeds
`portal.renderViewAreaMesh(originRelativeToCamera, TriangleConsumer)`, `build()` → `MeshData`, draws
through the mod's proven `PortalRenderTypes.drawMesh(RenderType, MeshData)` (topology-agnostic — reads
`drawState().primitiveTopology()`, so a TRIANGLES mesh flows unchanged; camera-relative vertices exactly as
IP built them). IP's per-call `_colorMask/_depthMask/_enableCull/_enableDepthTest` are baked into the
pipeline: IP's flag DECISIONS are preserved **verbatim** as `writeColor = !(fuseView && maxPortalLayer!=0)
&& doModifyColor` (IP `:39-49`) and `writeDepth = doModifyDepth && !fuseView` (IP `:51-61`), which select
the render-type via `MyRenderHelper.getPortalAreaRenderType` (§6). Raw-GL stays (R6): `FrontClipping`
clip-plane, `CHelper.enableDepthClamp/disableDepthClamp` (GL32.GL_DEPTH_CLAMP),
`applyMirrorFaceCulling/recoverFaceCulling`. IP's global-state RESTORES are dropped — each `drawMesh` pass
sets its own pipeline state. The `outputTriangle`/`outputFullQuad`/`@Deprecated` generators are verbatim
IP (pure Vec3 math; winding derivation §3.3).

---

## 2. R4 — `ImmPtlViewArea` as a 26.2 `ViewArea` SUBCLASS + the S12 install handoff

**Decision (on SPIKE-R4 evidence; the C3 default = rebuild):** rebuild IP's unbounded presets-cached grid
as a `ViewArea` subclass backed by a **mod-owned unbounded store** (`columnMap` + `presets`
`Long2ObjectOpenHashMap`), NOT the mod's pinned-bounded deviation. This retires the documented latent
**>71-chunk multi-portal same-dim collision bug** (memory `walking-limbo-seed-overclaim` #5): the mod
pinned vanilla's single bounded array, which two same-dim portal dests >71 chunks apart overflow; IP's
per-column store has no such ceiling. CUTOVER_SPEC §1 is the full decision record (C3 is a USER
checkpoint; the pinned-bounded deviation is conditional-register entry F19).

### 2.1 The subclass contract (render-core G25/C22–C29 + SPIKE-R4 §3)

26.2's `ViewArea` (`26.2:ViewArea.java:13-94`) is a thin wrapper over a `private final
RotatingSectionStorage<RenderSection>` — no `sections`/`level`/grid-size fields, no `createSections`, no
`setDirty`. The subclass **keeps the super store ALIVE but never resolves sections from it**:

| Member | 26.2 fate | This port (`ImmPtlViewArea.java`) |
|---|---|---|
| ctor | 7-arg `(dispatcher, minY, maxY, minSectionY, maxSectionY, renderDistance, occlusionGraph)`; `isSameThread` assert in super (`:19-37`) | **8-arg** = the 7 + trailing `Level world` (`:139-172`); `super()` builds the bounded store, subclass derives grid sizes + allocates the current-preset array |
| `createSections` override | **GONE** (G25) | REMOVED; body (allocate empty `sections`) folded into the ctor `:168-171` |
| protected `sections`/`sectionGridSizeX/Y/Z`/`level` | **GONE** | subclass OWNS them (`:92-96`): `sections` = current preset array, grid sizes derived, `level` from ctor |
| `repositionCamera(double,double)` | `repositionCamera(SectionPos)` returns boolean (`:74`) | override `:198-222`: preset swap keyed by section pos, **then `super.repositionCamera` for bookkeeping coherence** (§2.2) |
| `setDirty(int,int,int,boolean)` override | **GONE** (C24 — dirty externalised to `SectionUpdateTracker`) | REMOVED; `provideBuiltChunkByChunkPos` kept verbatim (public helper) |
| `getRenderSectionAt(BlockPos)` | public non-final (`:87`) | kept **verbatim** wrap-around read `:494-517` |
| `getRenderSection(long)` — **NEW hot accessor** (`:91`; 258k+ calls/run, off-thread) | protected | **NEW override `:526-551`** applying `getRenderSectionAt`'s exact math to decoded section coords (no BlockPos alloc) |
| `releaseAllBuffers` | `section.reset()` (C27) | override `:174-184`; `releaseBuffers` → `reset` |
| `dispatcher.new RenderSection(0, x<<4, y, z<<4)` | `new RenderSection(int index, long sectionNode)` (C26) | packed-node conversion `:322-325` (§3.2) |

Everything else — the `Column`/`Preset` inner classes, `columnMap`/`presets` stores,
`createPresetByChunkPos` wrap-around, `foreachPresetCoveredChunkPoses`, `getChunkIndex`, `provideColumn`,
`tick`/`purge` GC, `getAllActiveBuiltChunks`, `getManagedSectionNum`, `getDebugString`, `getRadius`,
`isRegionActive`, `onChunkUnload`, `getSectionFromRawArray`, `rawFetch`/`rawGet`, `init` — is **verbatim
IP** (with the mechanical renames of §2.3).

### 2.2 The super-coherence reconcile (the one structural deviation, api-map-sanctioned)

SPIKE-R4 caution 1: `SectionOcclusionGraph.GraphStorage` sizes `SectionToNodeMap` from `viewArea.size()`
and the `Octree` from `getCameraSectionPos()/getViewDistance()/sectionCount()/minY()` — none overridden
here, so they read the super's private `RotatingSectionStorage`. During MAIN-dim render (IP's own javadoc:
`repositionCamera` "will only be called during vanilla outer world rendering") the occlusion graph runs
against this instance, so those getters must return bounded/stable answers coherent with the current
camera. The override therefore calls `super.repositionCamera(cameraSectionPos)` **after** the preset swap:
it advances the super store's `centerSectionPos` + invalidates the occlusion graph (its only two effects;
`repositionCenter` mutates node ids in place, no allocation) while the override's own preset array serves
every actual `getRenderSectionAt`/`getRenderSection` lookup. In 1.21.3 IP got this bookkeeping "for free"
from ViewArea's public fields; on 26.2 it moved onto the private store, so `super.repositionCamera` is the
only way to keep it coherent. **No geometry/sign surface** — pure bookkeeping; the boolean return
("camera moved → invalidate") is forwarded. Consequence: the super store's own RenderSections (built by
its ValueCreator lambda) are pure bookkeeping — never fetched, never compiled, never rendered (all lookups
overridden), never GPU-allocated (26.2 `RenderSection` ctor stores only index + sectionNode). One bounded
grid's worth of extra heap-inert objects; the coord-pinned columns carry the real render state.

Fable re-verify §2 (this run) confirmed `super.repositionCamera` is both **necessary** (centerSectionPos
feeds the Octree ctor + on-move graph invalidate) and **sufficient** (ALL section-resolving paths
overridden; non-overridden getters are scalar), and proved the index spaces equal so the super store's
`SectionToNodeMap.nodes[renderSection.index]` cannot overflow (§8).

### 2.3 26.2 renames applied (mechanical; verified in mc262-ref)

`Minecraft.getInstance().getProfiler()` → `Profiler.get()` (G16, 2 sites); `ChunkPos.asLong(int,int)` →
`ChunkPos.pack(int,int)` + surviving `getX(long)`/`getZ(long)` (ChunkPos is a **record** in 26.2,
`:19`; the matched pack/unpack family, 7 sites); `chunkPos.toLong()` instance form → `ChunkPos.pack`;
`chunkPos.x`/`.z` field → `.x()`/`.z()` record accessor; `RenderSection.releaseBuffers()` → `reset()`
(C27, 2 sites); `createSections`/`setDirty` overrides removed (G25/C24).

### 2.4 The S12 INSTALL HANDOFF (a mixin `@Redirect` landing at S12, not this slice)

A mixin `@Redirect` retargets the `new ViewArea(...)` construction inside
`LevelRenderer.invalidateCompiledGeometry` (`26.2:LevelRenderer.java:819-827`; the 26.2 move of IP
1.21.3's `allChanged` NEW-redirect, `IP:MixinLevelRenderer.java:322-345` — C13, SPIKE-R4 CONFIRMED for BOTH
main + secondary renderers):

```java
@Redirect(method = "invalidateCompiledGeometry",
    at = @At(value = "NEW",
        target = "(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;IIIII"
            + "Lnet/minecraft/client/renderer/SectionOcclusionGraph;)"
            + "Lnet/minecraft/client/renderer/ViewArea;"))
private ViewArea seamlessportals$installImmPtlViewArea(
    SectionRenderDispatcher dispatcher, int minY, int maxY, int minSectionY, int maxSectionY,
    int renderDistance, SectionOcclusionGraph occlusionGraph,
    ClientLevel level /* captured method param of invalidateCompiledGeometry */
) {
    if (!IPCGlobal.useHackedChunkRenderDispatcher /* ≙ entityPortals GLOBAL flag */) {
        return new ViewArea(dispatcher, minY, maxY, minSectionY, maxSectionY, renderDistance, occlusionGraph);
    }
    return new ImmPtlViewArea(dispatcher, minY, maxY, minSectionY, maxSectionY, renderDistance, occlusionGraph, level);
}
```

Hard constraints for the S12 mixin (from SPIKE-R4 §4; CUTOVER_SPEC §1.3):
- **Gate on the GLOBAL flag, NEVER on `mc.levelRenderer == this` identity** (SPIKE-R4 §4-S1: the mod swaps
  `Minecraft.levelRenderer` per portal-render frame, so the secondary ctor mis-self-identified as "MAIN").
  IP gates on `IPCGlobal.useHackedChunkRenderDispatcher`; this becomes the `entityPortals`
  exclusivity-ledger driver-swap toggle (registered flag-ON at S13).
- **The `ClientLevel` is the 8th ctor arg**, captured from
  `invalidateCompiledGeometry(ClientLevel, Options, Camera, BlockColors)`'s own first param (`@Redirect`
  handlers may append trailing method params) — this is how the subclass sources the `Level` the 26.2
  super ctor dropped. The ctor threads it to `this.level`, `sectionGridSizeY = McHelper.getYSectionNumber`,
  `minSectionY`/`endSectionY`, and every per-column `McHelper.getMinY(level)`.
- **`@Redirect` exclusivity** claims the instruction; a third-party mod redirecting the same `new ViewArea`
  conflicts. IP upstream uses plain `@Redirect`; ship that for fidelity. If Sodium/compat forces it,
  `@WrapOperation` is a one-line S12 fallback (not a design change).
- **`ImmPtlViewArea.init()` must be wired at client init** — it registers
  `ImmPtlClientChunkMap.clientChunkUnloadSignal → onChunkUnload` and `POST_CLIENT_TICK_EVENT → tick`/`purge`
  (`ImmPtlViewArea.java:103-129`). Same seam as the other render `init()` calls (`IPModMainClient`, S10/S12).

---

## 3. SIGN-DERIVATION notes (D4.4)

No R5 reversed-Z constant is authored in S11-B — the depth/stencil flips live in `RendererUsingStencil`
(U10/S12) and are enumerated in CUTOVER_SPEC §2. The sign surfaces in this slice are the FrontClipping
view-space feed, the C26 packed-node conversion, and the view-area mesh winding. All confirmed IP-faithful.

### 3.1 FrontClipping bridge feed — the kept half-space is preserved exactly

IP's kept half-space is `n·p_rel + c > 0` (`p_rel` = world pos − camera pos; normal points to the KEPT
side; `c = −n·(clipPoint + n·correction − camera)`, `getClipEquationInner`). The mod's store evaluates
`gl_ClipDistance[0] = dot(viewPos, planeXYZ) + planeW` with `viewPos = R·p_rel` (R = view rotation). Since
rotation preserves the dot product, `dot(R·p_rel, R·n) + c = n·p_rel + c` — IDENTICAL half-space. Hence
`planeXYZ = R·n` (the world normal rotated into view space) and `planeW = c`. The bridge writes exactly
that: `feedViewSpacePlane` rotates `new Vector4f(nx,ny,nz,0)` by the model-view and passes
`(nView.x, nView.y, nView.z, (float)beforeModelView[3], true)` to `com.warwa…FrontClipping.restore`
(`FrontClipping.java:136-150`).

**COLUMN-FORM anti-fix guard (rides in from S11-A §2).** The rotation is
`new Vector4f(nx,ny,nz,0).mul(modelView)` = **M·v (column form)** = `Matrix4f.transform` semantics. Do
**NOT** "fix" to `mulTranspose` — that yields the inverse rotation and a sign-flipped clip plane (the
geometry-sign bug class). The `IP_DEVIATIONS_ANALYSIS.md:88-89` "row-vector" note is WRONG. The `w=0`
normal makes the model-view translation column drop out, so passing the full model-view (as IP does)
rotates the normal correctly. **Scaling-portal edge** (model-view carries scale → `nView` non-unit → the
signed distance is mis-scaled): the mod's live path uses a rotation-only `viewRotation`; flagged for S13
driver-core, NOT "fixed" here. Fable re-verify §1 (this run) re-confirmed the column form and the
`planeW=c` match against the live store's own `setupOuterClipping` constant, and that `disable()` →
`(0,0,0,1)` matches IP's `glDisable`.

IP's `transformClipEquation` (inverse-transpose: `m.invert(); m.transpose(); m.transform(eq)`) is retained
**verbatim** for the vestigial `double[]` getters — the CORRECT covector/plane transform (a plane's
equation transforms by the inverse-transpose of the point transform), a DIFFERENT and also-correct idiom
from the view-space normal rotation above; neither is a bug.

### 3.2 The C26 packed-node conversion (`ImmPtlViewArea.createColumn`)

IP `createColumn` built each coord-PINNED section with block coords
`factory.new RenderSection(0, sectionX<<4, (offsetCY<<4)+minY, sectionZ<<4)`. 26.2's `RenderSection`
identity is a packed `SectionPos` long. Derivation: block-Y of the section bottom = `(offsetCY<<4)+minY`;
section-Y coord = `((offsetCY<<4)+minY) >> 4 = offsetCY + (minY>>4) = offsetCY + blockToSectionCoord(minY)`
(`minY = McHelper.getMinY(level)` is section-aligned). Port (`ImmPtlViewArea.java:322-325`):
`int sectionYCoord = offsetCY + SectionPos.blockToSectionCoord(minY); factory.new RenderSection(0,
SectionPos.asLong(sectionX, sectionYCoord, sectionZ))`. These sections are coord-pinned and never
repositioned — preserving IP's coord-stable identity, the INVERSE of 26.2's slot-stable/coord-mutable
`RotatingSectionStorage.repositionCenter` (SPIKE-R4 caution 2). `getRenderSection(long)` sign check
(`:533`): for node `(sx,sy,sz)`, `j = floorDiv((sy<<4) − getMinY(level), 16)` = `sy − minSectionY`,
identical to `getRenderSectionAt`'s `j = floorDiv(pos.getY() − getMinY, 16)` with `pos.getY()=sy<<4`, so
the two accessors resolve to the same column slot; `i = sx`, `k = sz`. Fable re-verify §1 re-derived the
encode/decode round-trip and confirmed **no off-by-one** for all `minY` (§8).

### 3.3 View-area mesh winding (`ViewAreaRenderer.outputFullQuad`)

`outputFullQuad` emits the two triangles `(+1,+1)(−1,+1)(+1,−1)` and `(−1,+1)(−1,−1)(+1,−1)` in local
(axisW, axisH) coords, scaled about the portal center — winding preserved byte-for-byte from IP; the
TRIANGLES sequential index buffer preserves it into the draw. No reversed-Z here (depth compare lives in
the pipeline — §6). `MyGameRenderer`/`MyRenderHelper` carry no depth/clip/winding surface: MyGameRenderer
only saves/swaps/restores context and pushes an IDENTITY model-view; MyRenderHelper's only GL op is the
winding-neutral `glCullFace(GL_FRONT/GL_BACK)` mirror face-select (raw GL, ported byte-for-byte).

---

## 4. `VisibleSectionDiscovery` — A3 comparative read + the `armCompileScheduling` contract

**A3 (S11-A note §5; current-mod-render AMBIGUOUS A3 → PORT-FORWARD):** IP's discovery is PURE traversal
— it only fills `resultHolder`. On 26.2 that strands sections: there is NO vanilla driver that compiles a
hand-fed secondary renderer's sections (`compileSections` is private; its one-shot dirty flag orphans them
— the OW-holes / trees-before-ground root cause, memories `ow-holes-consumed-compile-queue` +
`walking-limbo-seed-overclaim`). The A3 comparative read (S11-A §5) established that the mod's budgeted
scheduling is a REQUIRED 26.2 adaptation with **no IP counterpart** and CANNOT be re-derived from
`ForceMainThreadRebuild` (which presupposes vanilla's own rebuild path runs the sections, and is an inert
additive latch). The reconcile:

- **IP's traversal ported VERBATIM:** the Chebyshev cube bound (`|c−cam| > viewDistance`), the
  `PerformanceLevel.getPortalRenderingDistance(...)` view distance, the
  portal-shape-`getModifiedVisibleSectionIterationOrigin` seed, the bottom/top-layer
  `BlockTraverse.searchOnPlane` seeding, the `vanillaFrustum.isVisible` cull, the `IERenderSection`
  timeMark, `builtChunks.rawFetch`, the 6-neighbour flood-through, the list pool (`takeList`/`returnList`),
  `init`/`cleanUp`. 26.2 renames: `camera.getPosition()` → `position()` (C34);
  `getMinBuildHeight/getMaxBuildHeight` → `getMinY/getMaxY` (C40); `getOrigin()` → `getRenderOrigin()`
  (C29); `getBoundingBox()` survives (S27). No geometry-sign surface (BFS offsets + Chebyshev bound are
  integer topology).
- **The mod's budgeted compile scheduling + own-chunk gate folded into `acceptVisible`** — the ONE
  mechanism with no IP counterpart. Extracted from the mod's proven `discoverAndScheduleForPortalView`
  accept path: own-chunk `hasChunk` draw gate, budgeted async `compileAsync(cache.createRegion(destLevel,
  node))`, the one-shot `schedSet` guard, `SectionUpdateTracker.SectionDirtyState` dirty/clear
  (`VisibleSectionDiscovery.java:257-292`). **ARMED per run** by the driver via `armCompileScheduling`;
  UNARMED (`scratchDestLevel==null`) `acceptVisible` is IP's byte-for-byte `resultHolder.add(section)` — so
  pure-IP callers see no change. The flood still propagates through a still-loading gap because the
  `tempQueue.add` already happened in `checkSection`, so loaded terrain beyond the gap is reached and the
  per-frame re-run picks a section up once its chunk arrives.
- **NOT ported (mod deviations superseded by IP forms):** the mod's 2D-cylinder bound (IP's Chebyshev cube
  is faithful) and the mod's inner-cull cone in discovery (IP culls with ONLY the passed portal-clipped
  `vanillaFrustum`; the portal inner-frustum cull is IP-achieved at the render layer via
  `FrustumCuller.getFlatPortalInnerFrustumCullingFunc`, landed S11-A). Draw-cost tuning is a runtime
  concern for S13+, not a fidelity item.

**The disarm contract — `finally` on EVERY exit path (P2 fix, this run).** The four mod-additive A3
scratch fields (`scratchDestLevel`/`scratchSut`/`scratchCache`/`scratchSchedSet`) are nulled in a
**`finally`** block wrapping the discovery body (`:118-195`), so the arm-context disarms on ANY exit —
normal return AND an exception mid-run (e.g. `scratchCache.createRegion` or a portal-shape callback). This
honours the `armCompileScheduling` javadoc ("auto-disarmed at the end of that discovery run so a later
pure IP caller cannot inherit a stale context"): without the `finally`, an exception would leave the
context armed and the next PURE-IP caller would inherit a stale `destLevel` → wrong-level `hasChunk`
gating silently drops sections + schedules `compileAsync` against the wrong level's regions. **Not an IP
deviation:** the scratch fields have no 1.21.3 analog, so gating THEIR cleanup on `finally` is additive-A3
housekeeping; IP's own `resultHolder`/`builtChunks`/`vanillaFrustum` cleanup stays INSIDE the `try`,
verbatim (byte-level IP fidelity of the IP fields preserved). Fable re-verify §1 flagged the original
normal-completion-only disarm (P2) and this run applied the `finally` wrap (§8).

**S12 handoff:** the qouteall renderer (MyGameRenderer / context-switch port) calls
`VisibleSectionDiscovery.armCompileScheduling(destLevel, sut, cache, schedSet, budgetNs)` before
`discoverVisibleSections` at the portal-view pass — exactly where IP relied on vanilla's now-stranding
compile path. `sut` is the per-dim `SectionUpdateTracker` off the secondary `LevelExtractor`; `cache` is a
per-frame `RenderRegionCache`; `schedSet` + `budgetNs` are driver state (the mod's proven pump values).
This is the COMPILE half of the CUTOVER_SPEC §5.1 extract()/compileSections pairing invariant — distinct
from the `earlyRemoteUpload` UPLOAD half (§1.4 of the spec).

---

## 5. R5 / R9 / the getPortalAreaRenderType family → CUTOVER_SPEC.md

The reversed-Z depth/stencil choreography (R5) and the per-dim fog ownership (R9) are NOT authored in the
S11-B classes — they are executed at S12/S13 and their full design lives in **`migration/CUTOVER_SPEC.md`**
(finalized this run). Pointers:

- **R5 reversed-Z (CUTOVER_SPEC §2):** the exhaustive 16-row op-by-op audit of `IP:RendererUsingStencil`,
  each row citing the IP 1.21.3 constant + the mod's runtime-proven 26.2 value and a written flip
  derivation. Ground truth: depth CLEAR 0.0 = FAR (`26.2:GameRenderer.java:404-408`), default depth
  COMPARE `GREATER_THAN_OR_EQUAL` (`26.2:DepthStencilState.java:9`), `glDepthRange` FAR = `(0,0)` /
  NEAR = `(1,1)`, stencil ops direction-INDEPENDENT. **Executed at S12** (transplant into the qouteall
  `RendererUsingStencil`), **signed off at S17**.
- **The `getPortalAreaRenderType` R5-GEQUAL family** feeds directly off this: `ViewAreaRenderer` selects
  its render-type via `MyRenderHelper.getPortalAreaRenderType(writeColor, writeDepth, doFaceCulling)`, an
  8-variant TRIANGLES POSITION_COLOR pipeline family whose depth COMPARE is the 26.2 default
  `GREATER_THAN_OR_EQUAL` (the exact convention the live `PortalRenderTypes.java:110` ships) and whose
  depth WRITE follows IP's `writeDepth = doModifyDepth && !fuseView` resolution — NOT a false write mask
  (CUTOVER_SPEC §2.1 Row 4, corrected this run; §6 below). This is the S11-B FixGaps resolution.
- **R9 per-dim fog (CUTOVER_SPEC §3):** each secondary render gets its OWN `FogRenderer` instance keyed
  per rendered dimension (mirroring the proven `DimensionRenderHelper` lightmap ownership in
  `ClientWorldLoader.RENDER_HELPER_MAP`), never writing the main-world ring-buffer slot; fallback = a
  mod-owned auxiliary `MappableRingBuffer` swapped for the dest pass. Plus `EnvironmentAttributeProbe` +
  `FogEnvironment` isolation via the secondary's own virtual Camera. The color probe (`setupFog().color`,
  compute-only) is already ring-buffer-safe (S11-A). **Wired at S12/S13**; the buffer-ownership FORM is a
  named S13/S14 runtime check (main-frame fog flicker watch).

CUTOVER_SPEC §4 (R2/A4 anchor, LIVE since S3), §5 (extract()/compileSections pairing + SOG delta feed
invariants), and §6 (the atomic-cutover contents + exclusivity ledger) complete the spec.

---

## 6. FixGaps resolution + fidelity restore + AW/AT count

**`getPortalAreaRenderType` (the one S11-B-internal forward-ref).** The `ViewAreaRenderer:91` call to
`MyRenderHelper.getPortalAreaRenderType` was documented (fragment S11B-viewarea §3.1) to resolve "at the
MyRenderHelper commit" but was initially dropped there (MyRenderHelper carried the survivors + deferred the
blit-draw family — but `getPortalAreaRenderType` is NOT part of that deferred family; it is the pipeline
SELECTOR the already-authored `ViewAreaRenderer` calls, so its absence left a live same-stage forward-ref).
Authored in `MyRenderHelper` (`:88-181`): an 8-variant `RenderType[]` family keyed by
`(writeColor, writeDepth, doFaceCulling)` via `portalAreaKey`, built with the SAME reflective
`RenderPipelines.register` + `RenderType.create` pattern the mod's proven `PortalRenderTypes` uses
(POSITION_COLOR, **TRIANGLES** topology to match the view-area mesh's sequential index buffer; R5
reversed-Z `GREATER_THAN_OR_EQUAL` depth — the 26.2 default). `writeDepth` →
`DepthStencilState(GREATER_THAN_OR_EQUAL, writeDepth)`; `writeColor` false →
`ColorTargetState(..., WRITE_NONE)` (full color = builder default, omitted); `doFaceCulling` →
`withCull(...)`. Robustness parity with `PortalRenderTypes`' catch: any registration failure fills each
null slot with `RenderTypes.debugQuads()`. Every vanilla API re-verified in `mc262-ref`
(`RenderPipeline.Builder.with*`, `ColorTargetState.WRITE_NONE`, `RenderSetup.builder`,
`RenderTypes.debugQuads`). The mirror-reverse cull stays `ViewAreaRenderer`'s raw
`applyMirrorFaceCulling`/`glCullFace` for now; the §3.1 "raw glCullFace is clobbered by
`applyPipelineState` → make it a front/back pipeline selection" refinement is an **S12 runtime item**, not
required to close this forward-ref. Held/inert until S13 (the 8 pipelines register on first class-load,
device-ready; never under flag-OFF). Probe **176 → 175** at authoring; shipping stays GREEN;
category-(c) signature sweep = 0.

**`requireNonNull` fidelity restore.** `ViewAreaRenderer.buildPortalViewAreaTrianglesBuffer` draws
`PortalRenderTypes.drawMesh(renderType, Objects.requireNonNull(bufferBuilder.build()))` (`:157`). IP's line
is `BufferUploader.draw(Objects.requireNonNull(bufferBuilder.build()))`: only `BufferUploader.draw` →
`PortalRenderTypes.drawMesh` is a mandated 26.2 translation (G8); the `Objects.requireNonNull` assertion is
kept **VERBATIM**. A portal view-area mesh always emits geometry, so `build()` is never null in practice —
an empty mesh is an IP-contract violation and stays a hard fail, NOT softened to a silent skip
(`drawMesh`'s `try(mesh)`+`mesh.drawState()` would NPE on null regardless, so the `requireNonNull` only
sharpens the failure site). This restore removed an undocumented earlier hard-assert→silent-skip softening
(documented in fragment S11B-viewarea §3).

**AW/AT pairs: ZERO.** No access-widening required. `getRenderSection(long)` is `protected` non-final
(overridable); every other overridden/consumed vanilla member is public non-final or reached via public
getter. `ChunkPos.pack/getX/getZ`, `SectionPos.asLong`, `Profiler.get`,
`RenderSection.reset/getRenderOrigin/getBoundingBox/compileAsync/sectionMesh`,
`SectionUpdateTracker.getDirtyState`, `RenderRegionCache.createRegion`,
`LevelRenderer.sectionRenderDispatcher`, `gameRenderer.mainRenderTarget`, and the mod's
`PortalRenderTypes.drawMesh` are all public. The private `GameRenderer`/`Lightmap`/`FogRenderer` internals
MyGameRenderer/MyRenderHelper reach go through the PRE-EXISTING `com.warwa` `GameRendererAccessorMixin`
(`seamlessportals$getLightmap`, unchanged) — no new accessor. The reflective `RenderPipelines.register` /
`RenderType.create` in `getPortalAreaRenderType` deliberately uses reflection (the exact idiom the live
`PortalRenderTypes` proves) rather than an AW, so no widener hunk is added. Documented so the S13
SCC-closure diff-gate expects **no AW/AT hunk from S11-B**.

---

## 7. Probe-vs-U9-union triage

**Probe = AUTHORITATIVE ledger** (`:common:compileJava -Pip_scc_closed=true` over all held source).

| Build | Task | Result |
|---|---|---|
| Shipping | `:common`+`:fabric`(+`:neoforge`) `compileJava` (incl. `--rerun-tasks`) | **BUILD SUCCESSFUL** — live block-era render intact; all 6 held files excluded from both compile paths |
| Test | `:common:test` | **BUILD SUCCESSFUL** (`DQuaternionTest` green gate held) |
| Probe | held-source compile | **BUILD FAILED, 176** (the forward-ref ledger) |

**Reduction toward the U9 union: 195 (S11-A) → 176 stable.** The six S11-B files resolved the S11-A
forward-refs to `render.{ImmPtlViewArea,VisibleSectionDiscovery,ViewAreaRenderer,MyRenderHelper}` in
`RenderStates`/`PortalRendering`/context classes, plus MyGameRenderer's `takeList/returnList` ref. **Figure
note:** javac's own summary reports **175**; the working-tree grep figure is **176**. The +1 is a Gradle
problems-report REPEAT of one `Portal.java:69` diagnostic line, NOT a distinct error — recorded so future
stages do not chase a phantom ±1. (The earlier "177" in fragment S11B-viewarea and "175" mid-FixGaps are
stale point-in-time figures; the current stable working-tree figure is 176.)

**Per-file forward-ref ledger — every S11-B-authored error is a documented S13 forward-ref (no
translation slips):**

| File:line | Missing symbol | Resolves at | Source |
|---|---|---|---|
| `MyGameRenderer.java:25,241,242` | `block_manipulation.BlockManipulationClient` | U11 / S13 | frag S11B-gamerenderer forward-ref ✓ |
| `ImmPtlViewArea.java:29,340,356` | `miscellaneous.GcMonitor` | S13 | frag S11B-viewarea §4 ledger ✓ |
| `VisibleSectionDiscovery.java:24,212` | `nether_portal.BlockTraverse` | S13 (U12 closure slice) | frag S11B-viewarea §4 ledger ✓ |
| ~~`ViewAreaRenderer.java:91`~~ | ~~`MyRenderHelper.getPortalAreaRenderType`~~ | **RESOLVED (S11-B FixGaps)** | §6 — authored in `MyRenderHelper` ✓ |

MyRenderHelper + FrontClipping compile with ZERO errors. The whole-probe signature-category sweep
(private-access / incompatible-types / cannot-be-applied / does-not-override / ambiguous) = **ZERO** — every
one of the 176 is a cannot-find-symbol / package-does-not-exist forward-ref (S11-B's `BlockManipulationClient`
/ `GcMonitor` / `BlockTraverse` plus the pre-existing prior-stage entry-point/config debt). The remaining
~167 non-S11-B errors are documented prior-stage debt resolving at U10/U11/U12/S13.

**Hygiene note (Fable re-verifier 2 observation, no action).** `common/src/main/resources/
seamlessportals-ip-qmisc.mixins.json` is untracked but is an S10-era resource present BEFORE S11-B (a
likely S10.3 commit omission) — it is NOT an S11-B artifact and was NOT swept into any S11-B assembly; it
should be committed under its proper stage attribution, not silently with S11-B.

---

## 8. VERIFICATION TIER

**Round 1 (Opus FALLBACK — Fable was rate-limited, which is why the Fable re-verify below was queued):**
V1 (R4/R5/signs) PASS — R4 subclass faithful vs `mc262-ref` ViewArea; all 16 R5 rows' IP citations + flip
directions verified; FrontClipping column-form confirmed; R9 sound vs 26.2 `FogRenderer`. V2
(diff-gate/build) PASS — completeness confirmed (6 render files + spec, no early S11-C/S12 port), the probe
reduction re-measured as REAL, `:common:test` green. LOW/minor notes only.

**Fable adversarial re-verify — EXECUTED this run (`model:'fable'`; the designated S11 stage per
`migration-model-tier-policy`).** Verdict: **PASS on all axes**, no MAJOR findings, no wrong flip
direction, no sign/winding flip, no `mulTranspose`, no live regression (`git diff HEAD` empty), no packed
off-by-one, no broken extract/compileSections pairing. Two non-MAJOR pre-commit defects found and FIXED
this run (both source-verified before editing):

- **P1 (CUTOVER_SPEC §2.1 Row 4 prose):** the "depthWrite=false" half was wrong. IP's view-area
  stencil-write draw is `renderPortalArea(portal, …, doModifyDepth=true, …)`
  (`IP:RendererUsingStencil.java:190-196`) → a non-fuse portal does `GlStateManager._depthMask(true)` =
  WRITES depth (`IP:ViewAreaRenderer.java:51-57`). Row 4 corrected: the compare flip
  `LEQUAL`→`GREATER_THAN_OR_EQUAL` stays (correct); the write mask follows IP's
  `writeDepth = doModifyDepth && !fuseView` (TRUE non-fuse, FALSE fuse-view), matching the authored
  `MOD-qouteall:ViewAreaRenderer.java:53-64`. `PortalRenderTypes.java:110` `(GEQUAL, false)` proves ONLY
  the compare flip — its false write-mask is the block-era pipeline's own choice, not the IP prescription.
  This removes a false S12 checklist mismatch against `ViewAreaRenderer`.
- **P2 (`VisibleSectionDiscovery` disarm robustness):** the armed-fold disarm ran only on normal
  completion — an exception mid-run (e.g. `createRegion`, a portal-shape callback) would leave the context
  armed and a later PURE-IP caller would inherit a stale `destLevel`. Fixed: the discovery body is wrapped
  in `try{…}finally{ scratch*=null; }` (§4). Only the four mod-additive A3 scratch fields moved to
  `finally`; IP's own `resultHolder`/`builtChunks`/`vanillaFrustum` cleanup stays inside the `try`
  verbatim. Not an IP deviation.

The **Row 12** correction (both driver modes DO write restore-phase depth — stencil-direct NEAR shield
`glDepthRange(1,1)` at STEP 3.7 AFTER direct dest terrain, `StencilPortalRenderer:472`; FBO-mode at STEP
3.5 BEFORE composite, `:408`; do NOT conflate with the #7 FAR clear `range(0,0)` `:401`) was applied in the
prior fix phase and re-confirmed by both Fable re-verifiers vs `StencilPortalRenderer`. Fable re-verifier 2
re-ran the shipping (incremental + `--rerun-tasks`) and probe builds and re-confirmed GREEN shipping,
`git diff HEAD` empty, probe forward-ref-only, `:common:test` green.

**S13 WATCH ITEM (evidence only, from Fable re-verify §1) — no code change now.** If the S13 driver-core
elects the "exact-projected-depth" option for IP op #12 (CUTOVER_SPEC §2.1 Row 12) over the committed flat
NEAR-shield re-expression, IP's raw `glDepthFunc(GL_ALWAYS)` bracket (#11) is clobbered by the GEQUAL
pipeline — that option would need an `ALWAYS_PASS` variant of the portal-area pipeline family (not
authored). The committed NEAR-shield path uses `PORTAL_DEPTH_CLEAR` which is already `ALWAYS_PASS`, so the
committed path is unaffected. Recorded so S13 does not silently regress the depth bracket if it swaps
options.

**Remaining runtime-verification items (S13/S14 live checkpoints; none blocks the held S11-B assembly):**

1. **R4 super-coherence** (§2.2) — confirm at runtime that `super.repositionCamera` keeps the
   `SectionOcclusionGraph` octree sizing coherent during main-dim render, and the dual RenderSection sets
   (super bookkeeping vs coord-pinned columns) never cross-contaminate a lookup. (Fable re-verify proved it
   analytically; S13 confirms under real per-frame rates.)
2. **`earlyRemoteUpload` per-dispatcher pump necessity** (render-core G26; CUTOVER_SPEC §1.4) — observe at
   S13 rung 1 / S14 cold-dest whether freshly compiled secondary sections upload without an explicit pump;
   land the pump ONLY if stranded uploads appear.
3. **Mirror-cull pipeline selection** (§6; CUTOVER_SPEC §2.1 note) — decide at S12 whether the raw
   `applyMirrorFaceCulling` glCullFace survives `applyPipelineState` or must become a front/back pipeline
   selection.
4. **Scaled-portal FrontClipping edge** (§3.1) — the non-unit `nView` under a scaling model-view; the mod's
   rotation-only `viewRotation` refinement is an S13 driver-core item.
5. **R9 fog-buffer ownership FORM** (CUTOVER_SPEC §3.2) — instance-per-dim `FogRenderer` vs the swap-the-slot
   fallback; needs the live dest render pass to confirm no main-frame slot corruption (main-world fog
   flicker watch at S13/S14).
6. **F18 Vulkan degrade** (CUTOVER_SPEC §2.4) — under the Vulkan backend the raw-GL occlusion query /
   depth-clamp / clip-plane silently degrade; the backend-probe bypass (treat every portal VISIBLE) is
   verified only on a Vulkan-backend run if available (S18).
