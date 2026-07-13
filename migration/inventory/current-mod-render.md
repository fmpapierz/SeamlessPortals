# Current mod: render inventory + disposition

Slice: `common/src/main/java/com/warwa/seamlessportals/render/` (19 classes, ~5,700 LOC) + all render-related
mixins in `common/src/main/java/com/warwa/seamlessportals/mixin/` + the repo render spec docs
(`RENDER_PIPELINE.md`, `IP_CONTEXT_SWITCH_EXACT.md`, `PHASE5_STENCIL_DIRECT_SPEC.md`,
`IP_DEVIATIONS_ANALYSIS.md`, `RENDER_NEXT_PASS.md`, `PHASE_B_PORT_SPEC.md`).

All paths below are relative to `C:/Users/warwa/ModDev/Portals/Portal 26.2/` unless absolute.
IP ground truth: `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/imm_ptl/core/render/`
(hierarchy verified on disk: `renderer/{PortalRenderer, RendererUsingStencil, RendererUsingFrameBuffer,
RendererDummy, RendererDebug}`, `ViewAreaRenderer`, `MyGameRenderer`, `MyRenderHelper`, `FrontClipping`,
`FrustumCuller`, `VisibleSectionDiscovery`, `ShaderCodeTransformation`, `TransformationManager`,
`SecondaryFrameBuffer`, `CrossPortalEntityRenderer`, `PortalEntityRenderer`, `ImmPtlViewArea`,
`context_management/{DimensionRenderHelper, FogRendererContext, PortalRendering, RenderStates, WorldRenderInfo,
CloudContext, StaticFieldsSwappingManager}`).

---

## 1. Overview

This subsystem is the mod's hand-built equivalent of IP's client render stack: it renders the *destination*
dimension through the portal opening in the *source* dimension's frame. Architecturally it maps onto IP as:
`StencilPortalRenderer` ≈ IP `PortalRenderer` + `RendererUsingStencil` (per-portal loop, stencil write/limit/
reset, depth clear/restore), `PortalContextSwitch` ≈ IP `MyGameRenderer.switchAndRenderTheWorld` + the
`context_management` package (the client-state swap set, per-dim lightmap/fog, the dest draw itself),
`PortalShapeRenderer` ≈ IP `ViewAreaRenderer` (portal-opening geometry draws), `VisibleSectionDiscovery`/
`PortalInnerCull`/`FrontClipping`/`ShaderCodeTransformation`/`DimensionRenderHelper` are direct ports of the
same-named/`FrustumCuller` IP classes adapted to MC 26.2. The one deliberate architectural difference from
this codebase's own history: as of Phase 5 the mod draws the dest terrain **stencil-direct into the main
render target** (IP's `RendererUsingStencil` model — flag `StencilPortalRenderer.STENCIL_DIRECT = true`,
`StencilPortalRenderer.java:41`), with the old mirror-FBO + composite path retained behind the same flag for
A/B (`RendererUsingFrameBuffer` model).

The load-bearing difference vs IP that the entity-portal migration must reconcile: the mod's **view camera is a
1:1 mirror translation of the player through the portal** (`PortalTransform.transformPoint`,
`PortalContextSwitch.java:1043`) rather than IP's rotation-based transform; portals are axis-aligned
`PortalInfo` (origin/width/height/axis) block rectangles rather than IP's `Portal` entity with arbitrary
orientation vectors. Every geometry routine in this slice (quad building, inner cull cone, clip planes,
yaw offset) branches on `Direction.Axis` and must be re-sourced from the Portal entity's
pos/axisW/axisH/width/height + `transformPoint` once the migration lands. The 26.2-specific mechanics
(pipelines, stencil persistence, extract/renderGroup pairing, extractor plumbing) carry over unchanged.

### Proven 26.2 render-pipeline knowledge the IP port MUST reuse (hard-won, verified live)

1. **Raw-GL stencil persists through the vanilla chunk draw.** blaze3d models no stencil
   (`DepthStencilState` is depth-only) and `GlCommandEncoder.applyPipelineState` never touches stencil
   (verified `mc262-ref GlCommandEncoder.java:771-827`, per `PHASE5_STENCIL_DIRECT_SPEC.md:26-27`). So
   `glStencilFunc/Op/Mask` set before `ChunkSectionsToRender.renderGroup(...)` masks the vanilla terrain
   draw — the single fact that makes IP's `RendererUsingStencil` portable to 26.2
   (`StencilPortalRenderer.setStencilLimitation`, `StencilPortalRenderer.java:480-484`). The same
   persistence property holds for `GL_CLIP_DISTANCE0` (`PortalContextSwitch.java:1904-1915`).
2. **A direct, non-framegraph terrain draw exists and lands in the main target.**
   `LevelRenderer.prepareChunkRenders(Matrix4fc)` is public (`mc262-ref LevelRenderer.java:509`);
   `ChunkSectionsToRender.renderGroup(group, sampler)` issues the GL draws with LOAD (no clear), and
   `OPAQUE.outputTarget()` is `gameRenderer.mainRenderTarget()` (spec table rows 1-4,
   `PHASE5_STENCIL_DIRECT_SPEC.md:21-24`). Implemented at `PortalContextSwitch.java:1920-1957`.
3. **Never nest a second framegraph mid-frame.** The historical "overworld blanks" bug was the nested
   `destRenderer.render(...)` re-importing the "main" target inside the in-flight main framegraph
   (`PHASE5_STENCIL_DIRECT_SPEC.md:25`); the FBO fallback still runs its heavy render at
   `GameRenderer.renderLevel` HEAD, before the main framegraph (`GameRendererPortalPrepareMixin`).
4. **Stencil hardware plumbing is 3 mixins**: request GLFW stencil bits (`GlBackendMixin`), force every
   `D32_FLOAT` depth texture to `DEPTH24_STENCIL8` (`GlConstMixin`), reattach depth as
   `GL_DEPTH_STENCIL_ATTACHMENT` in the single 26.2 FBO factory `FrameBufferCache.createFbo`
   (`stencil/RenderTargetMixin.java:39-71`). FBO identity is discovered at runtime via
   `GlStateManagerMixin` capturing `_glBindFramebuffer` (`StencilState.lastBoundFbo`).
5. **26.2 is reversed-Z.** Depth test for "in front" is `GREATER_THAN_OR_EQUAL`
   (`PortalRenderTypes.java:110`); depth-clear-to-FAR is `glDepthRange(0,0)` and NEAR-shield is
   `glDepthRange(1,1)` (`StencilPortalRenderer.java:362-385, 438-449`). Do NOT copy IP's 1.21.3 depth
   literals — translate them.
6. **Stencil-gated FULL-SCREEN triangles, not re-rasterized portal quads,** for depth clear and background
   fill — IP's `renderScreenTriangle` analogs via the `core/screenquad` vertex shader (positions from
   `gl_VertexID`); re-drawn quad geometry rasterizes differently from the stencil write and leaves 1px
   sliver rings (`StencilPortalRenderer.java:518-533, 576-649`).
7. **`extract()` must always be paired with a `compileSections` drain.** `LevelExtractor.extract` consumes
   each dirty section's one-shot flag and queues into `levelRenderState.sectionUpdateRenderStates`; the ONLY
   vanilla consumer is private `LevelRenderer.compileSections` (`mc262-ref LevelRenderer.java:608`). A path
   that extracts but never renders strands sections dirty=false + UNCOMPILED forever (the OW chunk-holes bug;
   drain invoked at `PortalContextSwitch.java:1640-1643` via
   `LevelRendererAccessorMixin.seamlessportals$invokeCompileSections`).
8. **The dest SOG's chunk deltas must be fed manually on the stencil-direct path.** `extract()` flips the
   ClientChunkCache's added/removed chunk delta sets into `ChunkLoadingRenderState`; the only vanilla consumer
   is `render() → sectionOcclusionGraph.update`, which the direct path never calls — so the deltas are applied
   by hand with a set-identity double-buffer guard (`PortalContextSwitch.java:1580-1594`).
9. **GPU-buffer endFrame rule:** every mod-created `RenderBuffers` must get vanilla's per-frame
   `endFrame()` or its `StagedVertexBuffer` pools never fence-recycle → multi-second driver paging stalls.
   Hooked at `GameRenderer.render` TAIL (`GameRendererMixin.java:33-46`,
   `PortalRenderBuffersPool.endFramePooled`).
10. **IP's `lateUpdateLight` split:** the secondary levels' light engines are POLLED during the client tick
    but must be RUN (`runLightUpdates`) at frame-render END — `GameRendererMixin.java:42` calls
    `PortalWorldManager.lateUpdateSecondaryLight()` at `render` TAIL, exactly IP `MyRenderHelper.lateUpdateLight`.
11. **SkyRenderer captures its RenderTarget at construction** and keeps writing into it; any renderer reused
    across targets needs `SkyRendererTargetMixin` (redirect every `renderTarget` field read to the CURRENT
    `mainRenderTarget()`) or the sky disc corrupts the other target ("the curtain",
    `SkyRendererTargetMixin.java:14-46`).
12. **ViewArea grid centering:** never reposition a secondary renderer's `ViewArea` to the moving mirror
    camera — `RotatingSectionStorage` relocates a band of sections per boundary crossing → meshes reset to
    UNCOMPILED → view collapses. Center the grid (and the chunk-store view center) on a STABLE anchor
    (dest portal origin) (`PortalContextSwitch.java:1190-1218`).
13. **`sog.update`/`runPartialUpdate` is a render-thread freeze on bulk-loaded secondaries** — the vanilla
    occlusion graph cannot drive a hand-fed secondary renderer; IP's answer (and the mod's) is the bounded
    BFS `VisibleSectionDiscovery` + skipping `applyFrustum` via a captured frustum
    (`CameraInvokerMixin.java:37-47`, `LevelExtractorFlashBridgeMixin.java:44-61`, the multi-attempt history
    at `PortalContextSwitch.java:141-226`).
14. **The lightmap consumed by the chunk draw is `GameRenderer.lightmap()`** — `renderGroup` binds it as
    Sampler2, so a per-dim lightmap needs either the field swap (`withSwitchedWorld`) or the HEAD-inject
    override (`GameRendererLightmapMixin`, `PortalContextSwitch.portalLightmapOverride`).
15. **Fog is a GPU buffer slice bound via `RenderSystem.setShaderFog`;** never write the global
    `FogRenderer` ring buffer for the portal view (shared memory with the main render) — write a standalone
    48-byte std140 buffer (`PortalContextSwitch.writePortalFogBuffer`, `PortalContextSwitch.java:2495-2518`)
    and save/restore the bound slice around the dest draws (`PortalContextSwitch.java:1893-1965`).
16. **Frame-order fact:** the crossing swap must run at `GameRenderer.update` HEAD (before the frame's camera
    is positioned), not `renderLevel`/`extract` HEAD — otherwise one frame renders the dest with a stale
    source camera (`GameRendererFrameCrossingMixin.java:11-27`; IP's `manageTeleportation` placement).
17. **Immediate mesh draw replacement for the removed `RenderType.draw(MeshData)`:**
    upload vertices to a transient `GpuBuffer`, fetch `RenderSystem.getSequentialBuffer(topology)`, draw via
    `renderType.prepare().drawFromBuffer(...)` (`PortalRenderTypes.drawMesh`, `PortalRenderTypes.java:288-306`).
18. **Custom pipelines are registered reflectively** via private `RenderPipelines.register` +
    `RenderType.create(String, RenderSetup)` (`PortalRenderTypes.java:70-75`) — there is no public API.
19. **Render-thread logging = log4j stall.** All per-frame diagnostics are ring-buffer/counter writes with
    off-thread daemon reporters (`PerfTimers`, `RenderSpikeMonitor`, `CrossingTracer`).

---

## 2. Class-by-class inventory — render package

Legend for dispositions (relative to the entity-portal migration):
- **REPLACE(X)** — superseded by porting IP class X; the mod class's 26.2 adaptations transplant into the port.
- **KEEP** — already IP-faithful (or 26.2-required enabler / mod-only diagnostic); survives the migration,
  possibly re-sourced from the Portal entity instead of `PortalInfo`.
- **DELETE** — mirror-architecture or dead/parked code that the migration removes.

### 2.1 StencilPortalRenderer — 654 LOC, client — REPLACE(IP `renderer/PortalRenderer` + `renderer/RendererUsingStencil`)

The per-frame portal render driver. Two-phase design: `prepareDestinationRender()` (phase 1, from
`GameRenderer.renderLevel` HEAD via `GameRendererPortalPrepareMixin`) and `renderPortals()` (phase 2, from
Fabric `AFTER_TRANSLUCENT_TERRAIN`). Under `STENCIL_DIRECT = true` (`:41`) phase 1 degenerates to per-frame
upkeep (flush staged uploads `:194`, prune entity adoptions `:198`, promote-bridge frustum-repaint pump
`:207-215`) and returns at `:220`; all real work happens in phase 2's `renderOnePortal` (`:297-466`), the
direct translation of IP `RendererUsingStencil.doRenderPortal`.

Key public API:
- `public static boolean STENCIL_DIRECT` (`:41`) — Phase 5 master switch.
- `public static void prepareDestinationRender()` (`:183`) — called by `GameRendererPortalPrepareMixin`.
- `public static void renderPortals()` (`:262`) — called by the Fabric event in
  `fabric/.../SeamlessPortalsClientFabric.java:26-28`.
- `public static boolean anyPortalNearCamera()` (`:107`) — consumed by `LevelRendererBlockOutlineMixin`.
- `static void setStencilLimitation(int layer)` (`:480`) — IP `setStencilLimitation`, raw GL EQUAL(layer)/mask 0.
- `public static void cleanup()` (`:651`) — reset counters (called on dim change / client close).

State: `framesRendered` log gate; lazy 1×1 fill texture (`screenFillTexture/-View/-Argb`, `:534-536`).
Portal discovery: `resolveRenderTargets()` (`:116-166`) — per-portal `RenderGroup(portal, link)` with
IP-style frustum visibility cull (inflate 8.0, `:142-143`), a SAME-DIM destination guard (`:152`), near-to-far
sort and `MAX_PORTALS_RENDERED = 4` cap (`:54`, `:160-164`).

Dependencies: `PortalManager/PortalTracker/PortalLink/PortalInfo` (portal state), `PortalContextSwitch`
(dest draw + FBO legacy), `PortalShapeRenderer`, `PortalRenderTypes`, `PerfTimers`/`RenderSpikeMonitor`/
`CrossingTracer`, `SeamlessPortalsConfig`.

Vanilla MC touched: `Camera` (+`getViewRotationMatrix`), `Frustum` (built by hand from the main
`CameraRenderState.projectionMatrix`, `:62-79`), `Minecraft.gameRenderer.gameRenderState()`,
`RenderTarget`/`RenderPass`/`GpuDevice`/`GpuTexture(View)`/`GpuSampler` (the stencil-gated screen fills,
`:576-649`), raw LWJGL `GL11/GL30/GL32` (stencil ops, `GL_DEPTH_CLAMP` `:330-336`, `glDepthRange`).

Migration notes: the per-portal loop, near-to-far sort, visibility cull, and layer plumbing all become IP's
`PortalRenderer.renderPortals` + `PortalRendering` layer stack. The 26.2-proven pieces to transplant verbatim:
the reversed-Z depth choreography (STEPs 3.5/3.7), depth-clamped stencil write (`:330`), stencil-gated
screenquad fills, and `setStencilLimitation`. IP's recursion (`INCR`, `clampStencilValue`) is spec'd in
`PHASE5_STENCIL_DIRECT_SPEC.md` step 4 but not yet implemented (single layer=1 today).

### 2.2 PortalContextSwitch — 2628 LOC, client — REPLACE(IP `MyGameRenderer` + `context_management/*`), transplant the 26.2 mechanics

The context-switch + dest-draw engine; the largest and most 26.2-knowledge-dense class in the mod.

Key public API:
- `withSwitchedWorld(ClientLevel, LevelRenderer, RenderTarget, Camera, Lightmap, Runnable)` (`:569-664`) —
  the IP `switchAndRenderTheWorld` swap set translated to 26.2: swaps `gameRenderer.mainRenderTarget` (field
  moved off `Minecraft` in 26.2), `mc.level`, `mc.levelRenderer`, `gameRenderer.mainCamera`,
  `gameRenderer.lightmap`, `mc.hitResult = null`, `player.noPhysics = true`, the WHOLE `mc.particleEngine`
  (per-dest engine — not just its level; `:592-594`, `:636-639`), and `mc.renderBuffers` +
  `destRenderer.renderBuffers` → a pooled `RenderBuffers` (`:607-643`); all restored in `finally` in reverse
  order. Dropped-from-IP set documented at `:553-562` (no BERD.level field, no doRenderHand, no
  FogRendererContext.swappingManager in 26.2 — fog handled by a separate GPU buffer). D6 note (`:613-626`):
  the 26.1.2 FeatureRenderDispatcher buffer-source swap is GONE under the submit model.
- `renderDestinationDirect(portal, link, camera, stencilLayer)` (`:838-884`) — Phase 5 entry; sets
  `stencilDirectMode` + layer, delegates to `doFboRender`, PerfTimers bail-reason histogram (`:852-864`).
- `prepareDestinationWorld / compositeDestinationWorld` (`:695-773`) — legacy FBO phase-1/phase-2 entries.
- `isRenderingPortal` (`:79`) — THE recursion/portal-context flag (IP `PortalRendering.isRendering()`),
  consulted by 6+ mixins.
- `armPromoteBridge()/isPromoteBridgeActive()/isPromoteBridgeMinActive()/getPromoteBridgeGeneration()`
  (`:308-337`) — the post-promote flash-bridge window (~1s floor, 30s cap, generation counter).
- `evictUnusedPortalFbos(Set<UUID>)` (`:416`), `beginPortalFrame()` (`:406`), `clearCompileSchedule(dim)`
  (`:436`), `resetChunkFedState(dim)` (`:666`), `resetPerDimRenderState()` (`:287`),
  `getDestSkyFogArgb(dim)` (`:112`), `portalLightmapOverride` (`:357`), `destParticlesActive` (`:138`),
  `useContinuousExtract` (`:227`, currently `false`; `:141-226` is the complete six-attempt history of why
  vanilla `sog.update` cannot drive the secondary renderer).

`doFboRender` (`:1006-2123`), the shared dest-render body (both modes fork on `stencilDirectMode`):
1. Dest camera: `PortalTransform.transformPoint` mirror position + axis-based `yawOffset` ±90° (`:1041-1056`),
   `new Camera()` + invoker `setRotation/setPosition`, `virtualCamera.tick()` for the
   `EnvironmentAttributeProbe` (`:1051-1061` — without the tick, fog/sky/ambient all zero).
2. Dest `Frustum` from the virtual camera's view rotation + the MAIN projection (`:1076-1084`);
   `capturedFrustum` set on the manual path so `extract()` skips `applyFrustum` (`:1144-1149`).
3. ViewArea grid + chunk-store view center pinned to the dest scope center / portal origin — NOT the mirror
   camera (`:1189-1218`, knowledge item 12); `dispatcher.setCameraPosition(destCameraPos)` (`:1220-1225`).
4. Bounded BFS `VisibleSectionDiscovery.discoverAndScheduleForPortalView` with `PortalInnerCull` cone,
   `destDepthRadiusSq()` (config-driven, `:507-510`), time-budgeted compile scheduling (400µs during the
   promote bridge, 3ms otherwise, `:1285`), per-dim one-shot `portalCompileScheduled` guard (`:448`, `:1306`).
5. Inside `withSwitchedWorld`: `destExtractor.extract(...)` + finally-fed SOG deltas (knowledge item 8,
   `:1580-1594`) + stencil-direct `compileSections` drain (item 7, `:1640-1643`);
   `populateVisibleSectionsByFrustum` re-asserts the BFS list post-extract (`:1660-1663`, `:2151-2168`);
   `destChunks = destRenderer.prepareChunkRenders(destViewMatrix)` (`:1672`).
6. Stencil-direct draw block (`:1728-1968`): `setStencilLimitation(layer)`; fresh modelview
   (`mvStack.pushMatrix(); identity()` `:1790-1792`); `RenderSystem.backupProjectionMatrix()` + clean
   (bob-free) dest projection via a hand-written 64-byte uniform buffer (`:1800-1803`, `:2378-2396`);
   `GlobalSettingsUniform.update(...)` re-driven with dest time/camera, restored with source values in the
   nested finally (`:1812-1821`, `:2004-2013`); dest fog bound via `RenderSystem.setShaderFog(destFogBuffer)`
   + restored (`:1893-1965`); order: SKY (before inner clip — IP excludes sky from clipping, `:1897-1903`) →
   inner clip armed → `renderGroup(OPAQUE)` → entity pass with clip pushed back 0.5 (`:1940-1944`) →
   `renderGroup(TRANSLUCENT)` → dest clouds (`:1956-1960`). FBO mode instead calls the 8-arg
   `destRenderer.render(...)` (`:1974-1983`).
7. Per-dim lightmap via `DimensionRenderHelper.updateAndRender` + `portalLightmapOverride` (`:1510-1512`);
   dest fog via `fogRenderer.setupFog(virtualCamera, smoothedDestFogRadius, ...)` with save/restore of
   `cameraRenderState.fogData/fogType` (`:1476-1497`, `:2042-2043`) and a post-render source-fog re-capture
   for Sodium (`:2062-2071`).

Private helpers with independent value: `renderPortalSky` (hand-driven `SkyRenderer` — the dest renderer's
own skyRenderer is null; cached per main-target size, `:895-940`), `renderPortalClouds` (`:947-967`),
`renderPortalEntities` (26.2 submit model driven by hand: `submitFeatures` invoker →
`featureRenderDispatcher.renderAllFeatures(storage)`, `:979-1000`), `compositePortalFbo` (FBO composite via
`createRenderPass` + `bindTexture("InSampler", ...)` + full-screen triangle with EXPLICIT RenderArea —
the ultrawide scissor fix, `:2446-2461`), `writePortalFogBuffer` (`:2495-2518`), `writeProjectionBuffer`
(`:2371-2396` — GC-pinned static GpuBuffer refs, never close), `renderColoredBlocks` (pre-FBO colored-block
fallback, `:2522-2618`), `applyObliqueNearPlane`/`applyMainCameraBobToProjection` (dead-path Lengyel oblique
+ bob math, `:2198-2362`).

Vanilla MC touched (the big set — see §4): `Minecraft` (level/levelRenderer/particleEngine/hitResult/options/
getDeltaTracker), `GameRenderer` (mainCamera/lightmap/mainRenderTarget/gameRenderState/fogRenderer/
globalSettingsUniform), `LevelRenderer` (render 8-arg, prepareChunkRenders, sectionRenderDispatcher,
sectionOcclusionGraph, cloudRenderer, visibleSections, viewArea, levelRenderState, submitNodeStorage,
featureRenderDispatcher, compileSections), `LevelExtractor` (extract, sectionUpdateTracker,
levelRenderState), `ViewArea.repositionCamera`, `SectionRenderDispatcher(.RenderSection)`,
`RenderRegionCache.createRegion`, `SectionOcclusionGraph.updateLoadedChunks/updateEmptySections`,
`ChunkSectionsToRender.renderGroup/maxIndicesRequired/drawGroupsPerLayer/textureView`,
`ChunkSectionLayerGroup`, `CameraRenderState` (all fields incl. `isFrustumCaptured`, `entityRenderState.*`),
`LevelRenderState` (+`skyRenderState`, `cloudColor/cloudHeight/gameTime`, `chunkLoadingRenderState`),
`FogRenderer.setupFog`, `FogData` (all 7 fields), `Lightmap`, `SkyRenderer` (ctor + 5 render methods),
`SkyRenderState`/`DimensionType.Skybox`, `AtlasManager`, `GlobalSettingsUniform.update`,
`ClientChunkCache.updateViewCenter/hasChunk/getLoadedChunksCount`, `RenderSystem` (getDevice,
setShaderFog/getShaderFog, backup/restore/setProjectionMatrix, getModelViewStack, getSamplerCache,
bindDefaultUniforms, getSequentialBuffer), `TextureTarget`/`RenderTarget`, `RenderPass`, `GpuBuffer(Slice)`,
`Std140Builder`, `DeltaTracker`, plus raw `GL11/GL30`.

Migration disposition detail: `withSwitchedWorld` IS the ported `switchAndRenderTheWorld` — keep, re-key by
IP `WorldRenderInfo`. The dest-camera math (`:1041-1056`) is replaced by IP `TransformationManager` /
Portal-entity `transformPoint` + rotation (no more axis yaw hack). The FBO branch, `portalFbos` pool
(`:365-377`), `compositePortalFbo`, `renderColoredBlocks`, and the oblique/bob dead code are DELETE per
`PHASE5_STENCIL_DIRECT_SPEC.md` "DELETE vs REUSE" once stencil-direct is default-only.

### 2.3 PortalShapeRenderer — 547 LOC, client — REPLACE(IP `ViewAreaRenderer`)

Builds and immediately draws the portal-opening geometry (camera-relative `POSITION_COLOR` meshes via
`BufferBuilder`/`ByteBufferBuilder` → `PortalRenderTypes.drawMesh`). All geometry is axis-aligned:
`axis == X` → XY quad at `z = origin.z + 0.5`, `axis == Z` → ZY quad (`:515-541`); merged-bounds loops for
multi-plane portals. Key methods: `drawPortalShape` (`:45`, + FBO-discovery dummy draw diagnostics),
`drawMergedPortalShape` (`:236`, stencil reset), `drawMergedPortalShapeWithDepthTest` (`:408`, THE stencil
write — flat plane quad with `EDGE_OUTSET = 0.01` into the obsidian, rationale `:438-460`; an alternate
constrained/box overload at `:310-406` kept for history), `drawMergedPortalShapeWithDepthClear` (`:77`, the
depth writes under caller-set `glDepthRange`), `drawPortalBackground` (`:157`, legacy FBO flat fill —
superseded by the stencil-gated screen fill for stencil-direct). Unused constant `EDGE_INSET` (`:39`).
Migration: IP `ViewAreaRenderer` does NOT build the mesh from portal vertices itself —
`buildPortalViewAreaTrianglesBuffer` (`ViewAreaRenderer.java:120`) calls `portal.renderViewAreaMesh`
(`:142` → `Portal.java:877`), which delegates to `getPortalShape().renderViewAreaMesh` (`Portal.java:889`),
shape-polymorphic: `RectangularPortalShape.java:143-158` builds the quad from
`UnilateralPortalState.getAxisW()/getAxisH()` half-extents via `ViewAreaRenderer.outputFullQuad`;
`BoxPortalShape.java:189` and `SpecialFlatPortalShape.java:156` provide different meshes. Port it THROUGH the
`PortalShape` abstraction; keep the `EDGE_OUTSET` overlap lesson and the "flat plane, not box" lesson
(`:438-450`).

### 2.4 PortalRenderTypes — 307 LOC, client — KEEP (26.2 pipeline layer; no IP equivalent exists on 26.2)

Static-init reflective registration (`:70-75`) of 5 `RenderType`s + 2 bare `RenderPipeline`s:
`PORTAL_STENCIL_ONLY` (no color, no depth), `PORTAL_STENCIL_WITH_DEPTH` (**GEQUAL** reversed-Z, no write,
`:110`), `PORTAL_NO_DEPTH_COLOR`, `PORTAL_DEPTH_CLEAR` (ALWAYS + depth write), `PORTAL_FBO_COMPOSITE`
(legacy), `PORTAL_COMPOSITE_BLIT` (TRACY_BLIT clone, `depthStencilState = Optional.empty()` → fully-off depth;
the "blue curtain" fix rationale `:44-60, 189-201`), `PORTAL_SCREEN_DEPTH_CLEAR` (screenquad, color-masked,
ALWAYS + write, `:204-223`). Fallback to `RenderTypes.debugQuads()` on failure (`:226-235`). Plus
`drawMesh(RenderType, MeshData)` (`:288-306`) — the immediate-draw replacement (knowledge item 17).
Vanilla touched: `RenderPipeline.builder()` (+ every builder method), `BindGroupLayouts.GLOBALS/
MATRICES_PROJECTION/SAMPLER0/IN_SAMPLER`, `RenderPipelines.register` (private, reflective),
`RenderType.create` (private, reflective), `RenderSetup.builder`, `ColorTargetState`, `DepthStencilState`,
`CompareOp`, `GpuFormat`, `DefaultVertexFormat.POSITION_COLOR/POSITION_TEX`, `PrimitiveTopology`,
`PreparedRenderType.drawFromBuffer`, `RenderSystem.getSequentialBuffer/AutoStorageIndexBuffer`, `MeshData`.
Migration: keep as the 26.2 pipeline home for the ported `RendererUsingStencil`; delete
`PORTAL_FBO_COMPOSITE` with the mirror path.

### 2.5 VisibleSectionDiscovery — 283 LOC, client — KEEP (faithful IP port, 26.2-adapted)

Port of IP `render/VisibleSectionDiscovery`: bounded, frustum-culled, 6-neighbour BFS from the camera section
that fills `visibleSections` WITHOUT touching `SectionOcclusionGraph` (`:19-35`). Two entry points:
- `discoverVisibleSections(viewArea, cameraPos, frustum, viewDistanceSections, level, out)` (`:65`) —
  Chebyshev-cube bound, seed skips frustum (IP `skipFrustumTest`), own-chunk `hasChunk` gate on OUTPUT while
  still flooding THROUGH unloaded sections (`:120-138`). Used by the promote flash-bridge.
- `discoverAndScheduleForPortalView(viewArea, cameraPos, frustum, innerCull, radiusSq, destLevel, sut, cache,
  schedSet, compileBudgetNs, visibleOut, prebuiltOut)` (`:170`) — the portal-view variant: 2D horizontal
  cylinder bound (full Y column — deliberately tighter than IP's cube because the mod keeps the just-left dim
  fully resident, `:157-166`), `PortalInnerCull.Cone` camera-relative rejection (`:243-248`), and folded-in
  budgeted compile scheduling: dirty (via `SectionUpdateTracker.SectionDirtyState`) OR UNCOMPILED-once
  (`schedSet` one-shot — `compileAsync` cancels in-flight tasks, `:266-279`).
Static scratch collections, render-thread-only (IP's design). Vanilla touched: `ViewArea.getRenderSection(long)`
(via invoker), `SectionPos.asLong/x/y/z/blockToSectionCoord`, `RenderSection.getSectionNode/getBoundingBox/
sectionMesh/compileAsync`, `CompiledSectionMesh.UNCOMPILED`, `SectionUpdateTracker`,
`RenderRegionCache.createRegion(ClientLevel, long)`, `Frustum.isVisible`, `ClientChunkCache.hasChunk`.
Migration: KEEP; the compile-scheduling fold-in is a mod extension IP doesn't have (IP's is display-only) —
retain it, it replaces the deleted O(all-sections) scan.

### 2.6 FrontClipping — 258 LOC, client — KEEP (IP port; re-source plane from the Portal entity)

Port of IP `render/FrontClipping`: view-space plane `(nx,ny,nz,w)`, kept half-space `dot(pos,n)+w >= 0`,
disabled state `(0,0,0,1)` (`:14-28`). `INNER_CLIP_ENABLED = true` flag (`:40`).
API: `setupOuterClipping(portal, camPos, viewRotation)` (`:116` — currently only logged, outer clip unused),
`setupInnerClipping(destPortal, virtualCamPos, viewRotation)` (`:163` — flip so the far side is kept),
`setupInnerClippingForEntities(..., margin)` (`:211` — plane pushed `margin` toward the camera so a mob
straddling the plane isn't bisected; the 2026-07-08 fix), `capture()/restore()/suspend()/disable()`
(`:81-103, 222-228`), `setupKillSwitchClipping()` diagnostic (`:237`). GL: `glEnable/Disable(GL30.GL_CLIP_DISTANCE0)`
(`:245-257`). NOTE on the JOML idiom used here: `Vector4f.mul(Matrix4fc)` (`:147`, `:181`) is the COLUMN
form **M·v**, byte-identical to `Matrix4f.transform` — verified empirically against `joml-1.10.5.jar`
(for M = rotationZ(90°), v = (1,0,0,0): `mul` and `transform` both yield (0,1,0,0); the row-vector form is
`mulTranspose`, which yields (0,−1,0,0) — the INVERSE rotation for a pure rotation. Evidence:
`migration/verify/current-mod-render.md` V1). The mod's usage is intrinsically correct; w=0 merely drops the
translation column, as intended for a plane normal. The contrary flag in `IP_DEVIATIONS_ANALYSIS.md:88-89`
("`Vector4f.mul(Matrix4f)` = v * M (row-vector) — WRONG for OpenGL") is itself wrong. DANGER: do NOT "fix"
this idiom to `mulTranspose`/a transpose during the port — that would introduce an inverse-rotation
clip-plane bug (the exact geometry-sign bug class this project was burned by twice).
Migration: keep the class; IP's Portal entity supplies `getNormal()`/plane distance directly (IP
`PortalRenderer.updateClipping`); delete the axis-derived normal sourcing.

### 2.7 ShaderCodeTransformation — 145 LOC, client — KEEP (IP port)

Port of IP `render/ShaderCodeTransformation` for 26.2's shader loading: rewrites vanilla vertex shaders at
`ShaderManager$CompilationCache.getShaderSource` time to add `out float gl_ClipDistance[1];` +
`uniform vec4 seamlessportals_ClipPlane;` and a `gl_ClipDistance[0] = dot(viewPos.xyz, plane.xyz) + plane.w`
write after `gl_Position` (`:36-121`). Explicit array redeclaration is load-bearing (drivers silently drop
unsized writes, `:43-53`). Conservative gate: only the canonical `ProjMat * ModelViewMat * vec4(pos, 1.0)`
pattern (`:78-98`). Idempotent via marker comment. Vanilla touched: none directly (pure string transform);
paired with `ShaderManagerCompilationCacheMixin` (source intercept) + `GlCommandEncoderClipMixin` (per-draw
uniform upload). Migration: KEEP unchanged.

### 2.8 DimensionRenderHelper — 135 LOC, client — KEEP (IP port, 26.2 Lightmap-based)

Port of IP `context_management/DimensionRenderHelper`: per-dimension `Lightmap` + `LightmapRenderState`,
map keyed by `ResourceKey<Level>` (`:36`). `updateAndRender(virtualCamera, partialTicks)` (`:63-117`)
replicates `LightmapRenderStateExtractor.extract` exactly but reads the VIRTUAL camera's
`attributeProbe().getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT/SKY_LIGHT_FACTOR/SKY_LIGHT_COLOR/
AMBIENT_LIGHT_COLOR/NIGHT_VISION_COLOR, pt)` (`:76-112`) plus gamma/darkness/night-vision from options+player,
then `lightmap.render(renderState)`. `cleanup()` closes all (`:129-134`). Vanilla touched:
`Lightmap`, `LightmapRenderState` (all fields), `Camera.attributeProbe`, `EnvironmentAttributes.*`,
`GameRenderer.nightVisionScale`, `MobEffects.DARKNESS/NIGHT_VISION/CONDUIT_POWER`, `ARGB.vector3fFromRGB24`.
Migration: KEEP; IP's version also ticks weather/skyDarken — verify parity during the port.

### 2.9 PortalInnerCull — 157 LOC, client — KEEP (faithful IP `FrustumCuller` port; re-source corners)

Port of IP `FrustumCuller.getFlatPortalInnerFrustumCullingFunc` + `Frustum4Planes.isFullyOutside` with the IP
file/line cited in-source (`:9-13`). `Cone` = 4 unit-normal side planes through the camera (W=0, all test
coords camera-relative); `isFullyOutside` = most-toward-normal corner test (`:52-68`).
`buildFromDestPortal(destPortal, camPos)` (`:83-134`) derives the 4 opening corners from
origin/width/height/axis in IP's counter-clockwise order, with **self-correcting winding** (portal center
must read inside; reversed rebuild; null on degenerate → caller falls back to frustum-only, `:122-133`).
Migration: KEEP the plane math; IP sources the corners via the **FrustumCuller** static
`FrustumCuller.getRectPortalFourVerticesCounterClockwise(UnilateralPortalState)` (`FrustumCuller.java:143`,
called at `:179` with `portal.getThisSideState()` — `Portal.java` has no such member), then transforms each
corner into dest space via `portal.transformPoint(v[i])` (`FrustumCuller.java:183-187`) with a Mirror-flip
special case (`:190-195`). That chain replaces the axis branch.

### 2.10 PortalRenderBuffersPool — 112 LOC, client — KEEP (IP pattern)

Mirrors IP's `acquireRenderBuffersObject/returnRenderBuffersObject` (cap 2 for depth-2 recursion, `:44`).
`acquire()` (null on exhaustion → caller skips the swap), `release(buf)`, and `endFramePooled()` (`:107-111`)
— the endFrame rule (knowledge item 9) for idle pooled buffers, called from `GameRenderer.render` TAIL.
Vanilla touched: `RenderBuffers(int)` ctor, `RenderBuffers.endFrame()`. Migration: KEEP.

### 2.11 SodiumFogOverride — 109 LOC, client — KEEP (compat shim, no IP analog)

Render-thread holder for a reflectively-constructed Sodium `FogParameters` built from vanilla `FogData`
(`:44-88`); consumed by `compat/SodiumFogOverrideMixin` intercepting the Sodium-merged
`GameRenderer.sodium$getFogParameters`. Zero compile-time Sodium dependency. Migration: KEEP (Sodium compat
layer is orthogonal to the entity migration).

### 2.12 StencilState — 24 LOC, client — KEEP (26.2 FBO-identity enabler)

Two static ints: `gameFboId` (set by `stencil/RenderTargetMixin` when a depth-attachment FBO passes the
stencil reattach + completeness check, `RenderTargetMixin.java:67`) and `lastBoundFbo` (set by
`GlStateManagerMixin` on every real `_glBindFramebuffer`, used by `renderOnePortal` STEP 1 to find the FBO to
clear stencil on, `StencilPortalRenderer.java:307-312`). No 1.21.3 IP analog (IP's `IEFrameBuffer` stencil
flag doesn't exist in 26.2). Migration: KEEP.

### 2.13 PerfTimers — 90 LOC, common-thread-safe — KEEP (mod diagnostic)

Off-thread bucket timing aggregator: `add(name, ns)`, `time(name, Runnable)`; daemon emits one
`[SEAMLESS TIMERS]` line per 5s (`:49-89`). Discipline from the render-thread-logging lesson. No vanilla deps.

### 2.14 RenderSpikeMonitor — 171 LOC, client — KEEP (mod diagnostic)

Frame-gap telemetry: render thread stamps `onFrame()` (called from `renderPortals`), daemons report spikes/GC
per 5s (`[SEAMLESS PERF]`), a 50ms-poll watchdog dumps the render-thread stack at >150ms stalls
(`[SEAMLESS STUCK]`) and ALL threads at >3s (`[SEAMLESS FREEZE]`) (`:109-170`). `recordFbo(ms)` bucket
(rename to `stencilDirectRender` per spec). Vanilla deps: none (JMX beans).

### 2.15 CrossingTracer — 242 LOC, client — KEEP (mod diagnostic)

4096-frame ring buffer (parallel arrays, zero allocation on the render thread): dim, camera/player pos,
signed plane distance to nearest linked portal, portal-views count, bridge flag, frame-detector state, and
the four hand-motion channels (bobAmp/walkD/velH/swayY, `:49-52`); `armDump()` + daemon dumps an annotated
window (`<== LEVEL SWAPPED`, flash-frame, BOB DIP markers) ~1.5s after a crossing (`:168-241`). Vanilla
touched: `Camera.position`, `LocalPlayer.avatarState().getInterpolatedBob/WalkDistance`,
`Entity.getDeltaMovement`, `player.yBob`. Migration: KEEP (regression tooling for the migration itself).

### 2.16 HandLightSmoother — 99 LOC, client — KEEP (mod extension, no IP analog)

Converges the first-person hand's block/sky light levels at 25 levels/s toward the live sample, with a
dimension-swap brightness remap onto the block axis (`:70-77`) and fractional packed coords (u=block×16,
v=sky×16, `:83-88`). Fed by `GameRendererHandLightMixin` (@ModifyArg on `submitHandsWithItems` arg 4).
Vanilla touched: `LightCoordsUtil.block/sky`. Migration: KEEP — seamless crossings expose this pop regardless
of portal representation.

### 2.17 CameraTransitionHandler — 89 LOC, client — DELETE with the migration

Config-driven (`getCameraSmoothingTicks`) position/rotation smoothstep lerp for crossings; ticked from
`GameRenderer.render` HEAD (`GameRendererMixin.java:18-21`). IP has no crossing camera lerp — continuity
comes from the teleportation transform itself; the entity migration's IP-faithful crossing supersedes this.

### 2.18 PortalSlicing — 197 LOC, client — DELETE (parked experiment)

Lengyel oblique near-clip of the MAIN world projection at the portal plane (`applyObliqueClip`, `:138-196`;
activation state `updateForFrame`, `:55-101`). Explicitly parked: its consumer mixin
`GameRendererObliqueClipMixin` is (a) pass-through (`:47-56` returns `self.getBuffer(matrix)` unconditionally)
and (b) NOT registered in `seamlessportals-common.mixins.json`. IP never slices the main projection
(`PortalContextSwitch.java:2620-2627` documents the finding). DELETE both.

### 2.19 PortalFrameSuppressor — 96 LOC, client — DELETE

Suppression DISABLED (`isFrameBlock` always false, `isActive` false, `:41-51`); only live member is the
one-shot `maybeForceDirtyForPortal` re-dirty of sections near a dest portal (radius 2, via
`SectionUpdateTracker.SectionDirtyState.setDirty(false)`, `:61-95`) to purge previously suppression-baked
meshes — a self-healing migration artifact of its own removal. Consumers: `SectionCompilerMixin`
(obsidian branch dead) + `PortalContextSwitch.java:1244`. Entity portals render the dest as-is (IP behavior);
delete class + the obsidian redirect.

---

## 3. Render-related mixins inventory

Registration ground truth: `common/src/main/resources/seamlessportals-common.mixins.json` (client list
`:7-60`). Plugin `SeamlessMixinConfigPlugin` drops `LevelRendererCullTerrainMixin` when Sodium is present
(`SeamlessMixinConfigPlugin.java:41-43`).

### 3.1 Stencil enablers (`mixin/client/stencil/`) — ALL KEEP (the 26.2 stencil substrate)

| Mixin | Target | Injection | Purpose | Disposition |
|---|---|---|---|---|
| `GlBackendMixin` | `com.mojang.blaze3d.opengl.GlBackend.setWindowHints` | TAIL | `glfwWindowHint(GLFW_STENCIL_BITS, 8)` (`:22-28`) | KEEP |
| `GlConstMixin` | `GlConst.toGlInternalId/toGlExternalId/toGlType` | HEAD cancellable ×3 | `D32_FLOAT` → `DEPTH24_STENCIL8` / `GL_DEPTH_STENCIL` / `GL_UNSIGNED_INT_24_8` (`:33-62`) | KEEP |
| `RenderTargetMixin` | `FrameBufferCache.createFbo(CacheKey, DirectStateAccess, List, FrameBufferAttachment)` — THE single 26.2 FBO factory (`:20-34`) | RETURN | Reattach depth as `GL_DEPTH_STENCIL_ATTACHMENT` (33306), completeness-check + revert, record `StencilState.gameFboId` (`:39-71`) | KEEP — "the enabler" per spec `:76` |
| `GlStateManagerMixin` | `GlStateManager._glBindFramebuffer` | HEAD | Capture `StencilState.lastBoundFbo` for real (>0, GL_FRAMEBUFFER) binds (`:25-32`) | KEEP |
| `GlTextureViewMixin` | `GlTextureView.createFbo` — **method removed in 26.2** | — | UNREGISTERED, retained for history (its own javadoc `:25-40` documents the FrameBufferCache unification) | DELETE file |

### 3.2 GameRenderer hooks

| Mixin | Target/point | Purpose | Disposition |
|---|---|---|---|
| `GameRendererMixin` | `render` HEAD + TAIL | HEAD: `CameraTransitionHandler.tick()`. TAIL: `lateUpdateSecondaryLight()` (IP `MyRenderHelper.lateUpdateLight`), `endSecondaryRenderFrames()`, `PortalRenderBuffersPool.endFramePooled()` (`:33-46`) | KEEP TAIL (items 9/10); HEAD tick goes with CameraTransitionHandler |
| `GameRendererPortalPrepareMixin` | `renderLevel(DeltaTracker)V` HEAD | Phase-1 entry (`StencilPortalRenderer.prepareDestinationRender`); the full framegraph-nesting rationale in javadoc (`:12-53`) | DELETE with the FBO path per spec `:64-66` — but the per-frame upkeep it still hosts under STENCIL_DIRECT (staged-upload flush, adoption prune, bridge repaint pump, `StencilPortalRenderer.java:194-215`) must be re-homed first |
| `GameRendererFrameCrossingMixin` | `update(DeltaTracker)V` HEAD | Per-frame camera-crossing check BEFORE the frame camera is positioned — IP's `manageTeleportation` placement translated to 26.2's update→extract→render frame pipeline (`:11-27`); intentionally `require` default (fail loudly) | KEEP — becomes the injection point for IP `ClientTeleportationManager.manageTeleportation` |
| `GameRendererLightmapMixin` | `lightmap()` HEAD cancellable | Serve `PortalContextSwitch.portalLightmapOverride` during dest draws — `renderGroup` binds `gameRenderer.lightmap()` as Sampler2 (`:11-24`) | KEEP |
| `GameRendererHandLightMixin` | `renderItemInHand(...)` @ModifyArg on `ItemInHandRenderer.submitHandsWithItems` index 4 | Route hand light through `HandLightSmoother` (`:24-35`) | KEEP |
| `MainProjectionBobMixin` | `renderLevel` @Redirect `bobHurt` + `bobView` | No-op the WORLD projection bob (hand still bobs via its own pose stack); kills the "dest slides inside a bobbing frame" mismatch (`:10-31`) | REPLACE-BY IP's `bobView` distance-scaling (api-map §2.1). IP DOES modify view bob: three `@ModifyArg`s on all three `PoseStack.translate` args inside `bobView`, multiplying by `RenderStates.getViewBobbingOffsetMultiplier()` unless rendering the hand (IP `MixinGameRenderer.java:212-253`), driven by the portal-distance `viewBobFactor` (0 within 1 block, dist−1 in the 1..2 band, 1 otherwise; `RenderStates.java:157-204`), gated by `IPGlobal.viewBobbingReduce` default `true` (`IPGlobal.java:121`). IP never touches `bobHurt` |
| `GameRendererObliqueClipMixin` | `renderLevel` @Redirect `ProjectionMatrixBuffer.getBuffer` (require=0) | Pass-through body, slice parked (`:47-56`); NOT in mixins.json | DELETE file |
| `compat/SodiumFogOverrideMixin` | `@Pseudo` inject into Sodium-merged `GameRenderer.sodium$getFogParameters` (remap=false, require=0) | Serve dest fog to Sodium chunk draws during portal render (`:34-59`) | KEEP |
| `GameRendererAccessorMixin` | accessors | `fogRenderer`, `levelProjectionMatrixBuffer`, `mainCamera` (+@Mutable), `lightmap` (+@Mutable), `globalSettingsUniform`, `renderBuffers` (+@Mutable), `mainRenderTarget` @Mutable setter (26.2: field moved off Minecraft — `:64-76`) | KEEP — the mod's `IEGameRenderer` |

### 3.3 LevelRenderer / ViewArea / SOG hooks

| Mixin | Target/point | Purpose | Disposition |
|---|---|---|---|
| `LevelRendererAccessorMixin` | accessors/invokers | `viewArea`, `visibleSections`, `levelRenderState` (+@Mutable — restores IP's per-renderer state isolation, `:39-49`), `renderBuffers` (+@Mutable), `featureRenderDispatcher` (+@Mutable, 26.2 final), `chunkLayerSampler` (`:104-117` — dest renderer's own is null, main's is live by AFTER_TRANSLUCENT_TERRAIN), `atlasManager`, `submitNodeStorage`, invokers `submitFeatures(LevelRenderState, SubmitNodeCollector, boolean)` + `compileSections(CameraRenderState)` (`:144-169`) | KEEP — the mod's `IEWorldRenderer` |
| `LevelRendererCompileSectionsMixin` | `compileSections` @Redirect on `LevelRenderState.sectionUpdateRenderStates` field-get | Filter out section-update states whose node isn't in the current `ViewArea` (vanilla NPEs at `wasPreviouslyEmpty`, `:15-42`) — the teleport-crash guard | KEEP |
| `LevelRendererEntityVisibilityMixin` | `isSectionCompiledAndVisible` HEAD cancellable | Return true while `isRenderingPortal` — vanilla's mesh-upload fade gate (`getVisibility >= 0.3`) culled EVERY dest entity (`:12-38`) | KEEP while dest sections upload on demand |
| `LevelRendererBlockOutlineMixin` | `submitBlockOutline` @ModifyArg on `submitHitOutline` arg 6 | Re-bucket the targeted-block outline to the after-terrain phase when `anyPortalNearCamera()` — outline depth-writes broke the depth-tested stencil write (`:9-42`) | KEEP |
| `LevelRendererCullTerrainMixin` | `cullTerrain(Camera,Frustum,Z)V` @Inject after `SectionOcclusionGraph.update` (require=0) | 26.1.2 sync-prime of the SOG on first post-promote frame (16ms budget) | **DEAD on 26.2** — `cullTerrain` does not exist in `mc262-ref` (grep: zero matches; the cull moved into `LevelExtractor.extract`/`applyFrustum`, `mc262-ref LevelExtractor.java:373`); require=0 silently no-ops. DELETE file (function superseded by the flash-bridge) |
| `LevelRendererDiagMixin` | `update(Camera)` — **removed in 26.2** | Diagnostic; UNREGISTERED, retained for history (javadoc `:26-42`) | DELETE file |
| `ViewAreaInvokerMixin` | invoker `ViewArea.getRenderSection(long)` | O(1) section lookup by packed node for the BFS + guards (`:9-27`) | KEEP |
| `SectionOcclusionGraphAccessorMixin` | accessors `fullUpdateTask`, `needsFrustumUpdate` | Bridge/rebuild-done checks + the bridge repaint pump (`:22-39`) | KEEP |
| `SectionOcclusionGraphPartialUpdateSkipMixin` | `runPartialUpdate` HEAD cancellable (require=0) | Skip the synchronous unbounded occlusion flood (a) during any portal-view render, (b) on the promoted main SOG until its FIRST post-promote rebuild lands (generation-latched, `:47-103`) — the freeze + limbo-bands fixes | KEEP |
| `SkyRendererTargetMixin` | @Redirect every `SkyRenderer.renderTarget` field read in 8 render methods | Serve the CURRENT `mainRenderTarget()` — the curtain fix (`:14-67`) | KEEP |
| `SectionCompilerMixin` | `SectionCompiler.compile` @Redirect `BlockState.getRenderShape` + `RenderSectionRegion.getBlockState` (require=0 ×2, Sodium replaces compile) | (1) Nether-portal swirl → `RenderShape.INVISIBLE` when render-through enabled; (2) dest-frame obsidian → AIR (dead — suppressor disabled) (`:28-67`) | Swirl branch: superseded by the Portal entity replacing portal BLOCKS entirely — DELETE with block-portal removal. Obsidian branch: DELETE now |

### 3.4 LevelExtractor hooks (26.2-only layer; no 1.21.3 analog)

| Mixin | Target/point | Purpose | Disposition |
|---|---|---|---|
| `LevelExtractorFlashBridgeMixin` | `applyFrustum` HEAD cancellable + RETURN (require=0) | HEAD: cancel during portal render (BFS owns `visibleSections`; the SOG walk froze 100-766ms) and during the promote bridge substitute `VisibleSectionDiscovery.discoverVisibleSections`; RETURN: empty-rescue re-flood when the engine cull yields nothing mid-bridge (`:44-124`) | KEEP |
| `LevelExtractorCreateRegionBudgetMixin` | `extract` HEAD + @Redirect `SectionDirtyState.isDirty` + @Redirect `RenderRegionCache.createRegion` | 24-slot per-extract budget on the ~1ms createRegion snapshots; charge on createRegion, not the dirty gate (trees-before-ground amplifier fix, `:14-83`) | KEEP |
| `LevelExtractorAccessor` | accessors | `levelRenderer` (@Mutable — de-final), `level`, `sectionUpdateTracker`, `lastViewDistance` (mismatch triggers `allChanged()` mesh wipe, `:54-60`), `levelRenderState` — the promote re-point set (`:11-33`) | KEEP |
| `ClientLevelExtractorAccessor` | `ClientLevel.levelExtractor` @Mutable | Re-point the level's final construction-time extractor on demote (the nether block-freeze root cause, `:9-26`) | KEEP |

### 3.5 Particle / debug / misc client hooks

| Mixin | Target/point | Purpose | Disposition |
|---|---|---|---|
| `ParticleEnginePortalSkipMixin` | `ParticleEngine.extract` HEAD cancellable | Fallback guard: skip the dest extract only when no per-dest engine is active (shared `particleTypeRenderState` corruption, `:13-54`) | KEEP |
| `ParticleEngineAccessorMixin` | accessors `level` (@Mutable — raw rebind, setLevel() clears particles), `resourceManager` | IP `IEParticleManager.setWorld_` pattern (`:10-46`) | KEEP |
| `QuadParticleGroupMixin` | `extractRenderState` HEAD/RETURN + @Redirect `Frustum.pointInFrustum` | Geometric cull of source particles behind an active portal (raw stencil doesn't survive pipeline re-binds for particles); pass-through during dest render (`:15-108`) | KEEP; re-source the quad test from Portal entity geometry |
| `DebugRendererPortalSkipMixin` | `DebugRenderer.emitGizmos` HEAD cancellable (require=0) | Skip gizmo emission during portal extract — `ChunkCullingDebugRenderer` draws a rainbow frustum whenever `getCapturedFrustum() != null`, ungated (`:11-40`) | KEEP (consequence of the captured-frustum trick) |
| `LivingEntityRendererDiagMixin` | `HumanoidMobRenderer.extractRenderState` TAIL (require=0) | Diagnostic: mirrored-entity equipment extraction logging (30 shots) | DELETE with the mirror-entity system (migration replaces mirroring wholesale) |
| `ClearSkipMixin` | `GlCommandEncoder` — empty body | Documentation placeholder: NEVER skip the clear pass; the FBO swap makes the clear target the secondary (`:6-28`) | DELETE file (fold note into docs) |
| `MinecraftRenderTargetMixin` | `Minecraft.mainRenderTarget` — field moved to GameRenderer in 26.2 | UNREGISTERED, history only (`:18-30`) | DELETE file |
| `MinecraftAccessorMixin` | accessors `levelRenderer` (+@Mutable), `particleEngine` (+@Mutable) | The context-switch swap points (`:30-54`) — IP `IEMinecraftClient` | KEEP |
| `CameraInvokerMixin` | invokers `setPosition`, `setRotation`; accessors `cullFrustum`, `capturedFrustum` (skips applyFrustum + SOG update — `:37-47`), `initialized` | Virtual-camera construction — IP `IECamera` | KEEP |
| `ShaderManagerCompilationCacheMixin` | `ShaderManager$CompilationCache.getShaderSource` RETURN cancellable (require=1) | Apply `ShaderCodeTransformation` to vanilla-namespace vertex shaders (`:14-62`) | KEEP |
| `GlCommandEncoderClipMixin` | `GlCommandEncoder.trySetup(GlRenderPass, Collection)Z` RETURN (require=1) | Upload the clip-plane vec4 to the current program per-draw (location cached per program id) (`:15-67`) | KEEP |
| `MinecraftMixin` | `Minecraft.close` HEAD; `updateLevelInEngines(ClientLevel,Z)V` HEAD (null level) | `StencilPortalRenderer.cleanup()`; session teardown on kick/transfer paths that never call `ClientLevel.disconnect` (`:18-43`) | KEEP |
| `ClientLevelMixin` | `doAddParticle` @Redirect `Vec3.distanceToSqr` (dest-particle distance-gate bypass, `:42-55`); `onChunkLoaded` TAIL (primary-level portal-block scan + dim-change → `StencilPortalRenderer.cleanup()`, `:72-138`); `disconnect` HEAD (full session cleanup, `:140-156`) | mixed render/lifecycle | KEEP particle bypass + lifecycle; the portal-block SCAN is DELETE with block portals (entity portals are synced, not scanned) |
| `ApplyLightDataGuardMixin` / `ChunkLightLambdaGuardMixin` | `ClientPacketListener.applyLightData` HEAD; `handleLevelChunkWithLight` @ModifyArg wrapping the `queueLightUpdate` Runnable | Null-level guards + captured dest-context re-establishment for redirected chunks' deferred light lambdas (`ChunkLightLambdaGuardMixin.java:57-100` swaps `this.level` + `mc.levelRenderer` around `original.run()`) | KEEP (chunk-feed infrastructure the render view depends on) |

Render-adjacent but owned by other slices (listed for the dependency map only): `HandleRespawnMixin` (player
reuse + `ClientLevel` ctor's 26.2 `LevelExtractor` arg — D7 in `PHASE_B_PORT_SPEC.md:111-116`),
`LocalPlayerMixin` (plane-crossing detection), `ChunkPacketGuardMixin`, the `ClientPacketListener*` family,
`NetherPortalUninteractableMixin` + `NetherPortalBlockMixin` (block-portal interaction; DELETE with block
portals), server-side mixins.

Two `mixin/client/` files were missing from BOTH the audited rows above and the exclusion list (caught by
adversarial verification, `migration/verify/current-mod-render.md` V7), accounted for here:

- `ClientLevelChunkSourceAccessor` — `@Mutable` accessor for the private final `ClientLevel.chunkSource`
  (`ClientLevelChunkSourceAccessor.java:15-21`; javadoc `:9-14`), used by `PortalWorldManager.createRenderer`
  to install the unbounded `SeamlessClientChunkMap` on portal SECONDARY levels (config-gated). This is
  render-view world plumbing on this slice's dependency path — same swap-point class as
  `MinecraftAccessorMixin`/`LevelExtractorAccessor`. KEEP while its consumer exists.
- `LivingEntitySprintCancelDiagMixin` — crossing-slice diagnostic: logs the calling code path of any
  sprint-cancel on the local player inside the post-swap window into `CrossingTracer`
  (`LivingEntitySprintCancelDiagMixin.java:24-49`). Out of this slice; dispositioned with the crossing-slice
  mixins.

---

## 4. Mechanisms

### 4.1 The stencil-direct frame (Phase 5, current default)

Per frame with `STENCIL_DIRECT = true`:

1. **`GameRenderer.update` HEAD** — crossing check/visual swap before the frame camera exists
   (`GameRendererFrameCrossingMixin`).
2. **`GameRenderer.renderLevel` HEAD** (phase 1, `StencilPortalRenderer.prepareDestinationRender`) — upkeep
   only: flush dest staged mesh uploads (GPU-upload-safe point, OUTSIDE the framegraph —
   `StencilPortalRenderer.java:190-194`), prune entity adoptions, force the SOG frustum-update flag during
   the promote bridge so arriving terrain repaints (`:207-215`).
3. **Main framegraph executes**; inside its main pass, after `renderGroup(TRANSLUCENT)`
   (`mc262-ref LevelRenderer.java:438`), Fabric fires `AFTER_TRANSLUCENT_TERRAIN` → `renderPortals()`.
4. **Per portal, near-to-far (≤4):** `renderOnePortal` (`StencilPortalRenderer.java:297-466`):
   - STEP 1: enable stencil; dummy draw discovers the live FBO (`StencilState.lastBoundFbo`); bind it,
     `glClearStencil(0)` + clear, unbind (`:302-312`).
   - STEP 2: stencil write — `glStencilFunc(ALWAYS,1)`, `glStencilOp(KEEP,KEEP,REPLACE)`, depth-tested
     (GEQUAL pipeline) flat portal quad under `GL_DEPTH_CLAMP` so the frame occludes the mask and silhouette
     pixels are stable (`:314-336`).
   - STEP 3: `glStencilFunc(EQUAL,1)`, mask 0.
   - STEP 3.5: depth clear to reversed-Z FAR across the opening — `glDepthRange(0,0)` + stencil-gated
     full-screen depth-write triangle (IP `clearDepthOfThePortalViewArea`) (`:362-377`).
   - STEP 3.6: stencil-gated full-screen fill with the dest fog colour (IP `replaceFrameBufferClearing`)
     so opaque-terrain gaps read as dest sky (`:387-405`).
   - STEP 4: `renderDestWorldDirect` → `PortalContextSwitch.renderDestinationDirect` (§4.2). On bail
     (no renderer/level, <9 chunks, no geometry) re-fill with the dest colour (`:499-516`).
   - STEP 3.7 (after content): depth shield — `glDepthRange(1,1)` + portal-quad depth write so later main
     passes (clouds/weather/translucent-after-terrain) fail GEQUAL inside the opening (`:438-449`).
   - STEP 5: stencil reset (write 0 through the shape), disable (`:451-458`).
5. **`GameRenderer.render` TAIL** — `lateUpdateSecondaryLight`, `endSecondaryRenderFrames`,
   `endFramePooled` (knowledge items 9/10).

### 4.2 The dest draw inside the switch (`doFboRender`, stencil-direct fork)

Camera build → dest frustum (captured) → grid/store pinning → bounded BFS + budgeted compiles →
`withSwitchedWorld(destLevel, destRenderer, MAIN target, virtualCamera, destLightmap, λ)`; inside λ:
`destExtractor.extract` (+ finally: SOG delta feed + `compileSections` drain) → re-assert `visibleSections`
from the BFS → `destChunks = prepareChunkRenders(destViewMatrix)` → `setStencilLimitation(layer)` →
fresh identity modelview + backed-up clean dest projection + dest `GlobalSettingsUniform` → bind dest fog →
SKY → arm inner clip → `renderGroup(OPAQUE)` → entity pass (clip margin 0.5) → `renderGroup(TRANSLUCENT)` →
clouds → restore fog/clip/projection/UBO/modelview → re-arm stencil EQUAL(1) — then `withSwitchedWorld`'s
finally restores the full client-state swap set. Section §2.2 has the line cites.

### 4.3 The legacy mirror-FBO path (A/B fallback, `STENCIL_DIRECT = false`)

Phase 1 at renderLevel HEAD renders each portal's dest into its own pooled full-screen `TextureTarget`
(`portalFbos`, evicted per frame) via the nested 8-arg `destRenderer.render(...)` (safe there — no framegraph
in flight); phase 2 composites the FBO through stencil EQUAL(1) with the TRACY_BLIT-clone pipeline, explicit
full RenderArea, blend+depth force-disabled (`compositePortalFbo`, `PortalContextSwitch.java:2410-2484`).
Cost scales with `portalRenderDistance²` — the reason Phase 5 exists (spec `:1-9`).

### 4.4 Section-visibility model for secondary renderers

Vanilla's `SectionOcclusionGraph` is structurally incompatible with hand-fed bulk-loaded secondaries
(six documented failed attempts, `PortalContextSwitch.java:141-226`). The working model: captured frustum
(skip `applyFrustum` + SOG update) + per-frame bounded BFS with inner-cone cull + own-chunk gate + folded-in
budgeted compile scheduling; the promote flash-bridge reuses the plain BFS on the MAIN renderer for ≤30s
post-promote until the SOG's first rebuild completes (HEAD substitute + RETURN empty-rescue,
`LevelExtractorFlashBridgeMixin`), with the runPartialUpdate flood skipped until that first rebuild
(generation latch, `SectionOcclusionGraphPartialUpdateSkipMixin`).

### 4.5 Clip-plane system (FrontClipping + shader injection)

Load-time GLSL rewrite adds a sized `gl_ClipDistance[1]` + uniform plane to canonical vanilla vertex shaders
(`ShaderCodeTransformation` via `ShaderManagerCompilationCacheMixin`); per-draw upload at
`GlCommandEncoder.trySetup` RETURN with a per-program location cache (`GlCommandEncoderClipMixin`);
`FrontClipping` owns the plane state + `GL_CLIP_DISTANCE0` toggling. Inner clip (dest side): keep the
far half-space of the dest portal plane; armed after sky, pushed back 0.5 for the entity pass. Raw clip
state persists through `renderGroup` for the same reason stencil does.

---

## 5. MC API touchpoint list (deduplicated; version-fragile)

**GameRenderer:** `render(DeltaTracker,boolean)` [HEAD/TAIL inject], `renderLevel(DeltaTracker)` [HEAD inject,
bobHurt/bobView redirects, ProjectionMatrixBuffer.getBuffer redirect], `update(DeltaTracker)` [HEAD inject],
`lightmap()` [HEAD cancellable], `renderItemInHand(CameraRenderState,float,Matrix4fc)` [ModifyArg on
`ItemInHandRenderer.submitHandsWithItems(F,PoseStack,SubmitNodeCollector,LocalPlayer,I)`], fields
`mainCamera`, `lightmap`, `fogRenderer`, `globalSettingsUniform`, `renderBuffers`, `mainRenderTarget`
(26.2: moved here from Minecraft), `gameRenderState()`, `mainCamera()`, `mainRenderTarget()`,
`bobHurt/bobView`, `GameRenderer.nightVisionScale`.

**LevelRenderer:** `render(GraphicsResourceAllocator, DeltaTracker, boolean, CameraRenderState, Matrix4fc,
GpuBufferSlice, Vector4f, boolean)` (8-arg, D5), `prepareChunkRenders(Matrix4fc)` (public,
`mc262-ref:509`), private `compileSections(CameraRenderState)` (`mc262-ref:608`, invoker + redirect on the
`sectionUpdateRenderStates` field-get), private `submitFeatures(LevelRenderState, SubmitNodeCollector,
boolean)` (invoker), `submitBlockOutline`/`submitHitOutline(..., boolean afterTerrain)` (ModifyArg),
`isSectionCompiledAndVisible(BlockPos)` (HEAD cancellable), `sectionRenderDispatcher()`,
`sectionOcclusionGraph()`, `cloudRenderer().render(int, CloudStatus, float, int, Vec3, long, float)`,
`clearVisibleSections()`, `visibleSections()`, fields `viewArea`, `visibleSections`, `levelRenderState`,
`renderBuffers`, `featureRenderDispatcher` (final), `chunkLayerSampler`, `atlasManager`,
`submitNodeStorage`. GONE in 26.2 (verified): `cullTerrain`, `update(Camera)`.

**LevelExtractor (26.2-new):** `extract(DeltaTracker, Camera, float)`, private `applyFrustum(Frustum)`
(`mc262-ref LevelExtractor.java:373`; HEAD cancellable + RETURN), fields `levelRenderer` (final→@Mutable),
`level`, `sectionUpdateTracker` (AT/AW-widened), `lastViewDistance`, `levelRenderState` (final),
`minecraft`; the extract loop's `SectionDirtyState.isDirty()` and `RenderRegionCache.createRegion(ClientLevel,
long)` call sites (redirects). Also `Minecraft.levelExtractor` (public final) and the `ClientLevel` ctor's
`LevelExtractor` parameter, plus `ClientLevel.levelExtractor` (private final → @Mutable).

**Section/chunk render:** `SectionRenderDispatcher.setCameraPosition(Vec3)`;
`SectionRenderDispatcher.RenderSection.getSectionNode()/getBoundingBox()/sectionMesh/compileAsync(
RenderSectionRegion)/wasPreviouslyEmpty()`; `CompiledSectionMesh.UNCOMPILED`;
`SectionUpdateTracker.getDirtyState(long)` / `SectionDirtyState.isDirty()/setDirty(boolean)/setNotDirty()`;
`ViewArea.repositionCamera(SectionPos)`, `ViewArea.sections`, protected `ViewArea.getRenderSection(long)`
(`mc262-ref net/minecraft/client/renderer/ViewArea.java:91`; invoker still required — protected is
inaccessible from mod code); `RenderRegionCache.createRegion(ClientLevel, long)`; `SectionCompiler.compile` (redirect targets
`BlockState.getRenderShape()`, `RenderSectionRegion.getBlockState(BlockPos)`);
`ChunkSectionsToRender.renderGroup(ChunkSectionLayerGroup, GpuSampler)/maxIndicesRequired()/
drawGroupsPerLayer()/textureView()`; `ChunkSectionLayerGroup.OPAQUE/TRANSLUCENT` (+`outputTarget()`
semantics); `SectionOcclusionGraph.update(...)/runPartialUpdate(CameraRenderState, LongSet)/
updateLoadedChunks(sets)/updateEmptySections(sets)/invalidate()`, fields `fullUpdateTask`,
`needsFrustumUpdate` (AtomicBoolean), `needsFullUpdate`.

**Render state records:** `CameraRenderState` (`pos`, `projectionMatrix`, `fogData`, `fogType`,
`isFrustumCaptured`, `entityRenderState.{bob, backwardsInterpolatedWalkDistance, hurtTime, hurtDuration,
hurtDir, deathTime, isDeadOrDying, isLiving, isPlayer}`), `LevelRenderState` (`cameraRenderState`,
`skyRenderState`, `cloudColor`, `cloudHeight`, `gameTime`, `sectionUpdateRenderStates`,
`chunkLoadingRenderState.{addedLoadedChunks, removedLoadedChunks, addedEmptySections,
removedEmptySections}`), `SectionUpdateRenderState.sectionNode()`, `SkyRenderState.{skybox, skyColor,
sunAngle, moonAngle, starAngle, moonPhase, rainBrightness, starBrightness, sunriseAndSunsetColor,
shouldRenderDarkDisc}`, `DimensionType.Skybox.NONE/END`, `LightmapRenderState` (all 11 fields),
`GameRenderState.optionsRenderState.{cloudStatus, cloudRange, glintStrength, menuBackgroundBlurriness,
bobView, damageTiltStrength}`.

**Camera:** protected `setPosition(Vec3)`/`setRotation(float,float)` (invokers), private `cullFrustum`,
`capturedFrustum`, `initialized` (accessors), `setLevel`, `setEntity`, `tick()`, `position()`, `yRot()/xRot()`,
`getViewRotationMatrix(Matrix4f)`, `extractRenderState(CameraRenderState, float)`, `attributeProbe()` +
`EnvironmentAttributes.BLOCK_LIGHT_TINT/SKY_LIGHT_FACTOR/SKY_LIGHT_COLOR/AMBIENT_LIGHT_COLOR/
NIGHT_VISION_COLOR`. `Frustum(Matrix4f, Matrix4f)`, `prepare(x,y,z)`, `isVisible(AABB)`,
`pointInFrustum(d,d,d)` (redirect).

**Fog/light/sky:** `FogRenderer.setupFog(Camera, int, DeltaTracker, float, ClientLevel)`, `FogData`
(color + 6 distance fields), `FogType.NONE`, `Lightmap` (ctor, `render(LightmapRenderState)`,
`getTextureView()`, `close()`), `SkyRenderer(TextureManager, AtlasManager, RenderTarget)` +
`renderSkyDisc/renderDarkDisc/renderSun/renderMoon/renderStars/renderSunriseAndSunset/renderEndSky/
renderEndFlash/renderSunMoonAndStars` (+ private field `renderTarget` — redirected),
`GlobalSettingsUniform.update(int, int, float, long, DeltaTracker, int, Vec3, boolean)`,
`LightCoordsUtil.block/sky`.

**blaze3d / GPU:** `RenderSystem.{getDevice, setShaderFog, getShaderFog, backupProjectionMatrix,
restoreProjectionMatrix, setProjectionMatrix(GpuBufferSlice, ProjectionType), getModelViewStack,
getSamplerCache, bindDefaultUniforms(RenderPass), getSequentialBuffer(PrimitiveTopology)}`;
`GpuDevice.{createBuffer, createTexture, createTextureView, createCommandEncoder}`;
`CommandEncoder.{createRenderPass(6-arg with explicit RenderArea), writeToTexture}`; `RenderPass.{setPipeline,
bindTexture, draw(int,int,int,int)}`; `GpuBuffer.USAGE_VERTEX/USAGE_UNIFORM`, `GpuBufferSlice`,
`Std140Builder`; `TextureTarget(String,int,int,boolean,GpuFormat)`, `RenderTarget.{width, height,
getColorTextureView, getDepthTextureView, resize, destroyBuffers}`; `RenderBuffers(int)`,
`RenderBuffers.endFrame()`; `MeshData(.DrawState)`, `BufferBuilder`, `ByteBufferBuilder`,
`DefaultVertexFormat`, `PrimitiveTopology`; `RenderPipeline.builder()` chain, `RenderPipelines.register`
(private), `RenderPipelines.TRACY_BLIT`, `RenderType.create` (private), `RenderSetup.builder`,
`PreparedRenderType.drawFromBuffer(GpuBuffer, GpuBuffer, IndexType, int, int, int)`, `BindGroupLayouts.*`,
`FilterMode.NEAREST`, `GpuFormat.RGBA8_UNORM/D32_FLOAT`, `GpuTexture.USAGE_TEXTURE_BINDING/USAGE_COPY_DST`.
GL-backend internals (mixin targets): `GlBackend.setWindowHints`, `GlConst.toGlInternalId/ExternalId/Type`,
`FrameBufferCache.createFbo(CacheKey, DirectStateAccess, List<FrameBufferAttachment>, FrameBufferAttachment)`,
`FrameBufferAttachment.glId()`, `GlStateManager._glBindFramebuffer/_glGetUniformLocation`,
`GlCommandEncoder.trySetup(GlRenderPass, Collection)Z`. Vanilla shader ids: `core/position_color`,
`core/position_tex`, `core/screenquad`, `core/blit_screen`; `ShaderManager$CompilationCache.getShaderSource(
Identifier, ShaderType)`.

**Client/level/particles:** `Minecraft.{level, levelRenderer (public, @Mutable), particleEngine (@Mutable),
hitResult, player, options, gameRenderer, getDeltaTracker, getTextureManager, isSameThread, close,
updateLevelInEngines(ClientLevel, boolean)}`; `ClientLevel.{dimension, getChunkSource, getGameTime,
doAddParticle (redirect), onChunkLoaded, disconnect, queueLightUpdate}`;
`ClientChunkCache.{updateViewCenter, hasChunk, getLoadedChunksCount}`; `ParticleEngine.{extract (HEAD
cancellable), countParticles, fields level/resourceManager}`; `QuadParticleGroup.extractRenderState`;
`DebugRenderer.emitGizmos`; `MultiPlayerGameMode.startDestroyBlock/continueDestroyBlock`;
`ClientPacketListener.{applyLightData, handleLevelChunkWithLight, field level}`;
`LocalPlayer.{noPhysics, avatarState(), yBob, getYRot, getDeltaMovement, getEyePosition, hasEffect,
getWaterVision, getEffectBlendFactor}`; `HumanoidMobRenderer.extractRenderState`;
`options.{getEffectiveRenderDistance, gamma, darknessEffectScale, cloudStatus...}` (via optionsRenderState);
`DeltaTracker.getGameTimeDeltaPartialTick(boolean)`; `CloudStatus.OFF`; `ARGB.alpha/vector3fFromRGB24`;
`SectionPos`, `BlockPos`, `Vec3`, `AABB`, `Direction.Axis`, `Mth`, `RandomSource`,
`FeatureRenderDispatcher.renderAllFeatures(SubmitNodeStorage)`, `SubmitNodeStorage`, `SubmitNodeCollector`.

**Fabric API:** `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN` (fabric-rendering-v1; fires inside the
main-pass lambda after `renderGroup(TRANSLUCENT)`, `mc262-ref LevelRenderer.java:438` per spec row 8),
`ClientTickEvents.END_CLIENT_TICK`. GLFW: `glfwWindowHint(GLFW_STENCIL_BITS, 8)`.

**Raw LWJGL GL (persist-through-pipeline set):** `GL11.glEnable/glDisable(GL_STENCIL_TEST | GL_BLEND |
GL_DEPTH_TEST)`, `glStencilFunc/glStencilOp/glStencilMask/glClearStencil/glClear(GL_STENCIL_BUFFER_BIT)`,
`glDepthRange`, `glGetInteger(GL_FRAMEBUFFER_BINDING | GL_STENCIL_REF | GL_STENCIL_FUNC | GL_CURRENT_PROGRAM)`,
`glIsEnabled`, `GL30.glBindFramebuffer/glFramebufferTexture2D/glCheckFramebufferStatus/GL_CLIP_DISTANCE0`,
`GL32.GL_DEPTH_CLAMP`, `GL20.glUniform4f`, `ARBDirectStateAccess.glNamedFramebufferTexture` (dead mixin only).

---

## 6. Registration & wiring

- **Mixins:** all via `common/src/main/resources/seamlessportals-common.mixins.json` (package
  `com.warwa.seamlessportals.mixin`, `defaultRequire: 1`, JAVA_25). Plugin
  `SeamlessMixinConfigPlugin.shouldApplyMixin` drops Sodium-conflicting mixins early (currently only
  `LevelRendererCullTerrainMixin`, `SeamlessMixinConfigPlugin.java:41-43`) — needed because Mixin raises
  `InvalidInjectionException` before `require = 0` applies. Files present but NOT registered:
  `GlTextureViewMixin`, `MinecraftRenderTargetMixin`, `LevelRendererDiagMixin`, `GameRendererObliqueClipMixin`.
- **Event hooks (Fabric):** `fabric/src/main/java/com/warwa/seamlessportals/fabric/SeamlessPortalsClientFabric.java`
  — `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> StencilPortalRenderer.renderPortals())`
  (`:26-28`) and `ClientTickEvents.END_CLIENT_TICK` driving the budgeted per-tick pumps: `drainChunks`,
  `advanceCompilePipelines`, `syncTime`, `tickRemoteWorlds`, `tickCachedParticles`, `evictUnboundedStores`
  (`:34-55`), all timed through `PerfTimers`. NeoForge: no equivalent client render hook is wired
  (`neoforge/.../SeamlessPortalsModNeoForge.java:45` notes it as TODO) — the render path is Fabric-only today.
- **Mixin-based frame hooks:** phase 1 + upkeep at `GameRenderer.renderLevel` HEAD
  (`GameRendererPortalPrepareMixin`), crossing swap at `GameRenderer.update` HEAD
  (`GameRendererFrameCrossingMixin`), frame-end lifecycle at `GameRenderer.render` TAIL (`GameRendererMixin`).
- **Static initialization:** `PortalRenderTypes` builds/registers all pipelines in its `<clinit>` (first
  class touch on the render thread); `DimensionRenderHelper`/`PortalRenderBuffersPool`/`portalFbos` are
  lazy per-dim/per-portal; `PerfTimers`/`RenderSpikeMonitor`/`CrossingTracer` self-start daemon threads on
  first use.
- **No entity/block registration in this slice.** Portal state arrives via `PortalManager`/`PortalTracker`
  (client portal registry, another slice); secondary levels/renderers/extractors via
  `PortalWorldManager.getOrCreateRenderer/getLevel/getExtractor` (client world-management slice).
- **Lifecycle/cleanup:** dim change → `StencilPortalRenderer.cleanup()` (`ClientLevelMixin.java:100`);
  disconnect/kick → `PortalWorldManager.cleanup()` + `SeamlessClientTeleport.onDisconnect()`
  (`ClientLevelMixin.java:140-156`, `MinecraftMixin.java:31-43`); `PortalContextSwitch.resetPerDimRenderState`
  clears per-dim smoothing maps; `DimensionRenderHelper.cleanup()` closes lightmaps.

---

## Disposition summary

| Class | Disposition |
|---|---|
| StencilPortalRenderer | REPLACE(IP `PortalRenderer`+`RendererUsingStencil`); transplant 26.2 stencil/depth/screenquad mechanics |
| PortalContextSwitch | REPLACE(IP `MyGameRenderer`+`context_management/*`); keep `withSwitchedWorld` mechanics, extract/renderGroup/compileSections pairing, delta feed, fog/UBO isolation; DELETE FBO branch + colored blocks + oblique/bob dead code |
| PortalShapeRenderer | REPLACE(IP `ViewAreaRenderer`); keep EDGE_OUTSET + flat-plane lessons |
| PortalRenderTypes | KEEP (26.2 pipeline layer); delete `PORTAL_FBO_COMPOSITE` with the mirror path |
| VisibleSectionDiscovery | KEEP (IP port + mod compile-scheduling extension) |
| FrontClipping | KEEP (IP port); re-source plane from Portal entity; `Vector4f.mul` is the correct column form M·v — do NOT change it to `mulTranspose` (§2.6) |
| ShaderCodeTransformation | KEEP (IP port) |
| DimensionRenderHelper | KEEP (IP port) |
| PortalInnerCull | KEEP (IP `FrustumCuller` port); re-source corners via `FrustumCuller.getRectPortalFourVerticesCounterClockwise(getThisSideState())` + `transformPoint` (§2.9) |
| PortalRenderBuffersPool | KEEP |
| SodiumFogOverride | KEEP |
| StencilState | KEEP |
| PerfTimers / RenderSpikeMonitor / CrossingTracer | KEEP (diagnostics) |
| HandLightSmoother | KEEP (mod extension) |
| CameraTransitionHandler | DELETE (IP teleport transform supersedes) |
| PortalSlicing | DELETE (parked; consumer unregistered + pass-through) |
| PortalFrameSuppressor | DELETE (disabled no-op) |
| Mixins | KEEP: stencil substrate (4), accessors/invokers (9, + `ClientLevelChunkSourceAccessor` §3.5), extractor hooks (4), SOG hooks (2), SkyRendererTarget, compileSections guard, entity-visibility, block-outline, lightmap, hand-light, frame-crossing, frame-end, clip pair, particle trio, DebugRendererSkip, MinecraftMixin, guards. REPLACE: MainProjectionBobMixin (IP `bobView` distance-scaling, api-map §2.1). DELETE: GlTextureViewMixin, MinecraftRenderTargetMixin, LevelRendererDiagMixin, GameRendererObliqueClipMixin, ClearSkipMixin, LevelRendererCullTerrainMixin (dead on 26.2 — target removed), LivingEntityRendererDiagMixin (with mirror system), SectionCompilerMixin (with block portals), GameRendererPortalPrepareMixin (after re-homing its upkeep) |
