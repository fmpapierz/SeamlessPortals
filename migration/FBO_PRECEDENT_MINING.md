# FBO PRECEDENT MINING — the block-era mirror-FBO "compatibility render mode"

**What this is:** the pre-S20 extraction of the block-era (com.warwa) portal renderer's
mirror-FBO architecture — *render the dest world into an offscreen FBO once per portal per
frame, then paste the texture onto the portal opening* — mined and adversarially verified at
**HEAD a375efe** (branch `claude/nifty-kepler`). S20 DELETES this code; this document is the
architectural precedent for the IRIS SHADERS-ON engagement
(`migration/IRIS_SHADERS_ON_HANDOFF.md`): the same FBO shape re-expressed onto the flag-ON
substrate's `SecondaryWorldRenderCore.renderDestWorld`, with the ACTIVE iris pipeline doing
the world render.

**Post-S20 readers:** every cited file may be gone from the working tree. Recover any of them
with `git show a375efe:<path>`, e.g.
`git show a375efe:common/src/main/java/com/warwa/seamlessportals/render/PortalContextSwitch.java`.

**Anchors:** `FILE:LINE` at a375efe. Abbreviations (all under
`common/src/main/java/com/warwa/seamlessportals/` unless noted):

| Abbrev | File |
|---|---|
| PCS | `render/PortalContextSwitch.java` |
| SPR | `render/StencilPortalRenderer.java` |
| VSD | `render/VisibleSectionDiscovery.java` |
| PIC | `render/PortalInnerCull.java` |
| DRH | `render/DimensionRenderHelper.java` |
| FC  | `render/FrontClipping.java` (the block-era warwa one — NOT qouteall's) |
| PWM | `client/PortalWorldManager.java` |
| PRT | `render/PortalRenderTypes.java` |
| PrepMixin | `mixin/client/GameRendererPortalPrepareMixin.java` |
| FabClient | `fabric/src/main/java/com/warwa/seamlessportals/fabric/SeamlessPortalsClientFabric.java` |

---

## §0 FRAMING — which mode is the precedent (read this first)

The block-era system has TWO draw strategies, both funnelling into the SAME core routine
`doFboRender` (PCS:1006). The fork is `StencilPortalRenderer.STENCIL_DIRECT` (SPR:41,
**default `true`**) plus the inner flag `PortalContextSwitch.stencilDirectMode` (set at
PCS:868, cleared PCS:882).

- **Stencil-direct (the SHIPPING block-era default):** NO FBO. The dest world's terrain is
  drawn inline at `AFTER_TRANSLUCENT_TERRAIN` straight into the main target via
  `ChunkSectionsToRender.renderGroup`, masked by raw-GL stencil (PCS:1865-1968). This is NOT
  the precedent — it is a dead end for iris (see §7-B).
- **FBO-composite (the A/B fallback, `STENCIL_DIRECT=false`):** the "render dest into an
  offscreen FBO at renderLevel HEAD, paste through the stencil at
  AFTER_TRANSLUCENT_TERRAIN" architecture. **This is the precedent** — it is structurally
  what the iris shaders-ON compatibility renderer must be, because a shader-pipeline world
  render (like the nested vanilla framegraph it replaces here) cannot be interleaved into
  the main frame mid-flight.

Two readings of "end-state," both true: (i) within the block-era subsystem the FBO path is
DORMANT (retained, switchable, verified working); (ii) the whole subsystem is unregistered
at the shipping default `entityPortals=true` (FabClient:163+ else-branch registers
`StencilPortalRenderer.renderPortals()` at FabClient:177-179 only flag-OFF; PrepMixin:63
`if (isEntityPortals()) return;` gates phase 1). Mine the SHAPE; port none of the wiring.

`doFboRender` forks on `stencilDirectMode` at **exactly three points** (confirmed in the
javadoc of `renderDestinationDirect`, PCS:827-832):
1. the render target handed to `withSwitchedWorld` (PCS:1391-1400),
2. the stencil setup before the draw (PCS:1728-1739),
3. the draw itself (PCS:1865 `renderGroup` chain vs PCS:1974 `destRenderer.render(...)`).
Everything else — camera, frustum, extractor, fog, lightmap, state isolation — is shared.

---

## §1 THE RENDER SEQUENCE (FBO path, verified step order)

### 1.0 Frame anchors — the two-phase split

26.2 world render is deferred through a FrameGraphBuilder: `GameRenderer.renderLevel`
runs after `GameRenderer.extract(...)` and then executes the main framegraph; Fabric's
`AFTER_TRANSLUCENT_TERRAIN` fires from INSIDE that main pass (PrepMixin:14-52).

- **Phase 1 (heavy dest render into the FBO)** — `GameRenderer.renderLevel` **HEAD**,
  BEFORE the main framegraph builds/executes: PrepMixin:57-64 →
  `SPR.prepareDestinationRender()` (SPR:234). This placement is THE load-bearing timing
  constraint: the FBO draw is a full nested `destRenderer.render(...)` framegraph, and
  issuing a nested framegraph from `AFTER_TRANSLUCENT_TERRAIN` (mid-main-framegraph)
  disrupted the imported "main" target and **blanked the overworld** (PCS:673-688,
  PrepMixin doc). Phase 1 also MUST stay at renderLevel HEAD because it reads the extracted
  `cameraRenderState` that `GameRenderer.extract` just populated (SPR:239-241).
- **Phase 2 (lightweight composite)** — `AFTER_TRANSLUCENT_TERRAIN` →
  `SPR.renderPortals()` (SPR:288) → `renderOnePortal` (SPR:323) → `renderDestWorldDirect`
  (SPR:525) → flag-false branch → `PCS.compositeDestinationWorld` (PCS:751). Deferred here
  because the composite needs the stencil mask, which only exists in phase 2
  (PCS:2100-2110). Nothing in phase 2 nests a framegraph (PCS:736-739).
- Hand-off: per-portal ready flags `fboReadyByPortal` (PCS:375), set at PCS:709, read at
  PCS:757; reset once per main frame by `beginPortalFrame()` (PCS:406-409), called from
  phase 1 at SPR:251. NOTE: `beginPortalFrame` and the eviction sweep sit AFTER the
  `if (STENCIL_DIRECT) return;` at SPR:246 — they run only in FBO mode.

### 1.1 Phase-1 per-portal loop (SPR:258-283)

`resolveRenderTargets()` discovers per-portal render groups (each with its OWN
destination), near-to-far, capped at `MAX_PORTALS_RENDERED = 4` (SPR:54). No portals →
`evictUnusedPortalFbos(emptySet)` frees ALL pooled FBOs (SPR:263 — walking away frees
them). Else, for each group: `PCS.prepareDestinationWorld(portal, link, camera)`
(SPR:273), then `evictUnusedPortalFbos(renderedIds)` (SPR:282). Cost recorded off-thread
as the `fboRender(Np)` PerfTimers bucket (SPR:279).

### 1.2 prepareDestinationWorld (PCS:695-730) — the phase-1 gate + stencil neutralize

- `activePortalId = srcPortal.getPortalId()` (PCS:701) — selects this portal's pooled FBO.
- `fboReadyThisFrame = tryFboRender(...)`; `fboReadyByPortal.put(pid, result)`
  (PCS:708-709).
- **CRITICAL `finally` (PCS:710-729):** reset the GL stencil to benign —
  `glStencilMask(0xFF)`, `glStencilFunc(ALWAYS,0,0xFF)`, `glDisable(GL_STENCIL_TEST)`
  (PCS:726-728) — before returning to vanilla renderLevel. doFboRender's inner cleanup
  re-enables `EQUAL(1)/mask 0x00` (PCS:2028-2030, correct only in the OLD single-phase
  design); if that persisted into the main framegraph (fresh stencil buffer = all zeros),
  EVERY main-world fragment would be rejected — the overworld blanks via the stencil.

### 1.3 tryFboRender (PCS:775-816) — the entry gate

1. Bail if `mc.level`/`mc.player` null (PCS:778).
2. `destRenderer = PWM.getOrCreateRenderer(destDim)`; `destLevel = PWM.getLevel(destDim)`;
   bail if null (PCS:780-782).
3. `destLevel.getChunkSource().updateViewCenter(destOrigin.x>>4, destOrigin.z>>4)`
   (PCS:786-787).
4. **Minimum-chunks gate:** `RemoteChunkManager.getChunkCount(destDim) < 9` → return false
   (PCS:792-803) — the caller takes the background fallback.
5. `doFboRender(...)` inside try/catch (PCS:805-815); any exception → false.

(The stencil-direct twin `renderDestinationDirect`, PCS:838-884, has the same guards plus
off-thread `destBail:{noRenderer,lowChunks(n),noGeometry,exception}/<dim>` PerfTimers
histograms at PCS:852/864/872/875, and brackets `stencilDirectMode=true` in try/finally.)

### 1.4 doFboRender (PCS:1006-2123) — the core, in verified order

**Step 1 — dest camera from the portal link (PCS:1024-1084).**
- `destCameraPos = PortalTransform.transformPoint(srcPortal, destPortal, type, mainCamera.position())`
  (PCS:1043) — a 1:1 TRANSLATION of the player through the portal (mirror/"locked window"
  parallax). Explicitly NOT `transformTeleportPoint`, which negates the depth axis and
  inverts parallax — user-verified worse (PCS:1029-1035). This is a block-era
  approximation of IP's rotation transform; the flag-ON system has IP's own.
- `yawOffset` for axis mismatch: src Z→+90°, else −90°; same axis → 0 (PCS:1046-1048).
- Fresh `Camera virtualCamera = new Camera()`: `setLevel(destLevel)`, `setEntity(player)`,
  invoker-set rotation `(mainCamera.yRot()+yawOffset, mainCamera.xRot())` + position
  (PCS:1051-1056). Then **`virtualCamera.tick()` (PCS:1061) — CRITICAL:** ticks the
  camera's EnvironmentAttributeProbe against destLevel+destPos; without it the probe
  returns zeros → black fog, zero skylight (PCS:1057-1060).
- Frustum: `viewMatrix` from `virtualCamera.getViewRotationMatrix`; `projMatrix` = COPY of
  the main `cameraRenderState.projectionMatrix` (same FOV/aspect); `new Frustum(view,proj)`;
  `prepare(destCameraPos)`; set as the camera's cull frustum (PCS:1077-1084).

**Step 2 — native-vs-manual gate (PCS:1086-1150).**
`nativeRender = useContinuousExtract && isDestResident(NATIVE_RESIDENCY_RADIUS) &&
isDestStable(NATIVE_STABLE_NANOS) && loadedChunks <= NATIVE_MAX_LOADED_CHUNKS`
(PCS:1123-1130). **`useContinuousExtract` is hard-`false` (PCS:227)** — the native
sog.update warm path is disabled as unshippable (intermittent freezes; its async
full-build saturates the shared background pool). So in shipping code `nativeRender` is
ALWAYS false and the manual path is live: the frustum is CAPTURED on the virtual camera
(PCS:1148) so `extract()` skips `applyFrustum` (the manual scan supplies
`visibleSections`) and `sog.update` is skipped. `setInitialized(true)` (PCS:1150).

**Step 3 — extractor resolution + state re-bind + the manual visible-section flood
(PCS:1152-1345).**
- `destExtractor = PWM.getExtractor(destDim)` (PCS:1156-1157) — the dest's OWN per-dim
  LevelExtractor (created in PWM `createRenderer`/`demoteFromMain`).
- **LevelRenderState re-bind (PCS:1159-1181):** `extract()` writes the extractor's OWN
  final `LevelRenderState`; a promoted renderer may have been re-bound to the shared main
  state. If they diverge, re-point the renderer at the extractor's state — else
  entities/clouds/particles silently vanish while terrain still draws (terrain reads
  `visibleSections`, not the state).
- **Grid centering (PCS:1189-1218):** reposition the dest ViewArea grid on a STABLE point
  — `PWM.getDestScopeCenter(destDim)` / portal origin — NEVER the 1:1-moving mirror camera
  (`viewArea.repositionCamera`, PCS:1205). Camera-centering makes vanilla
  RotatingSectionStorage relocate+reset RenderSections on every section-boundary crossing
  → UNCOMPILED meshes → noGeometry bail → flat fill ("nether terrain disappears"). Also
  pin the chunk-store view center to the portal (PCS:1218) so the bounded vanilla chunk
  array doesn't slide and silently evict ACKed chunks (the OW-holes class).
- `dispatcher.setCameraPosition(destCameraPos)` (PCS:1220-1225) — the REAL mirror pos
  (vanilla does this in cullTerrain every frame).
- Clear `visibleSections` + `prebuiltVisibleSections`; fresh `RenderRegionCache`
  (PCS:1227-1231). `PortalFrameSuppressor.maybeForceDirtyForPortal` one-shot (PCS:1244).
- Compile budget: `PORTAL_VIEW_COMPILE_BUDGET_NS = isPromoteBridgeActive() ? 400_000 :
  3_000_000` (PCS:1285) — a per-frame TIME budget on async-compile scheduling
  (`createRegion` is ~1ms/section synchronous on the render thread; a fixed COUNT stalled
  ~128ms/frame).
- **The flood (manual path, PCS:1322-1336):** build the inner cull cone
  `PortalInnerCull.buildFromDestPortal(destPortal, destCameraPos)`, then
  `VisibleSectionDiscovery.discoverAndScheduleForPortalView(viewArea, destCameraPos,
  destFrustum, innerCull, destDepthRadiusSq(), destLevel, sectionUpdateTracker,
  cache, schedSet, budget, visibleSections, prebuiltVisibleSections)` — the bounded
  6-neighbour BFS that replaced the old O(all-sections) per-frame scan (78K-101K
  iterations at RD32 — the dominant teleport-stutter cost). `destDepthRadiusSq()` = config
  `portalRenderDistance`², live (PCS:507-510). VSD details: seed = camera's own section
  (frustum-exempt); admission = 2D horizontal cylinder (full Y column); flood THROUGH
  unloaded chunks but only `hasChunk`-loaded ones enter the visible list (the
  trees-before-ground fix); dirty/UNCOMPILED sections get budget-gated `compileAsync`
  scheduling with a one-shot per-dim `schedSet` guard (compileAsync CANCELS in-flight
  tasks — double-schedule means never finishing) (VSD:170-282).

**Step 4 — destViewMatrix + render-target fork (PCS:1379-1400).**
`destViewMatrix` = the virtual camera's view-rotation matrix (PCS:1379-1380). Fork:
stencil-direct captures `directChunkSampler` = the MAIN renderer's `chunkLayerSampler`
(captured HERE, while `mc.levelRenderer` is still the main renderer — the dest renderer's
own is null because its main pass never runs; PCS:1391-1393) + `directMainTarget`
(PCS:1394-1396). FBO mode → `prepareSecondaryFbo()` (PCS:1397-1398).
`switchTarget = stencilDirectMode ? directMainTarget : secondaryFbo` (PCS:1400).

**Step 5 — dest CameraRenderState (PCS:1402-1430).**
`destCameraState = destLRS.cameraRenderState`; `virtualCamera.extractRenderState(...)`;
projection overridden from the main camera (PCS:1419); bob/hurt/dead zeroed defensively
(PCS:1424-1430). Oblique near-plane clipping is REMOVED — `obliqueApplied = false`
hardcoded (PCS:1442-1447): oblique corrupts depth precision across the whole frustum;
gl_ClipDistance affects only clipped fragments (IP's technique).

**Step 6 — dest fog (PCS:1456-1505).**
- `destFogRadius = smoothedDestFogRadius(destDim, max(2, PWM.getDestScopeRadius(destDim)))`
  (PCS:1476-1477): fog at the dest's ACTUAL graduated loaded radius, NOT full RD (else sky
  shows where terrain stops), smoothed ~1.5s so the 5/15-block scope steps don't pop.
- `destFogData = fogRenderer.setupFog(virtualCamera, destFogRadius, deltaTracker, 0f,
  destLevel)` (PCS:1478-1484).
- SAVE `savedFogData`/`savedFogType`, then write `destCameraState.fogData = destFogData`,
  `fogType = NONE` (PCS:1494-1497) — `cameraRenderState` is SHARED across LevelRenderer
  instances; restored in the outer finally (PCS:2042-2043) or dest fog leaks into the main
  world (Sodium reads the field directly).
- `destFogBuffer = writePortalFogBuffer(destFogData)` (PCS:1503) — a STANDALONE 48-byte
  std140 GpuBuffer per call (PCS:2495-2518). NEVER `fogRenderer.updateBuffer()` — that
  writes the MappableRingBuffer shared with the main renderer's terrainFog slice → dark
  clipping artifacts across the entire world (PCS:2486-2494).
- Record the real dest fog colour for the flat opening fill (PCS:1504-1505).

**Step 7 — per-dim lightmap (PCS:1507-1512).**
`DimensionRenderHelper.getOrCreate(destDim).updateAndRender(virtualCamera, partialTick)` —
DRH reproduces the vanilla lightmap extract using `virtualCamera.attributeProbe()` for the
DEST dim (blockLightTint/skyFactor/skyLightColor/ambient/brightness/darkness/nightvision),
then renders its own per-dim Lightmap (DRH:63-117). `portalLightmapOverride` set to its
texture view (PCS:1512), cleared in the outer finally (PCS:2036).

**Step 8 — the switched-world render (PCS:1514-2033).**
Pre-capture `savedCameraPos` + `savedLevelGameTime` for the Globals-UBO restore
(PCS:1519-1520). `fboRendered = {false}` — the result latch (PCS:1523-1529).
`isRenderingPortal = true` (PCS:1531 — the recursion guard, IP `PortalRendering.isRendering`
analog; checked at SPR:256/291). Then `withSwitchedWorld(destLevel, destRenderer,
switchTarget, virtualCamera, dimHelper.getLightmap(), lambda)` (PCS:1533-1536; full swap
set in §4). Inside the lambda, in order:

- **8a. Extract + finally-fed drains (PCS:1537-1645).**
  `destExtractor.extract(deltaTracker, virtualCamera, partialTick)` (PCS:1581) runs while
  `mc.levelRenderer == destRenderer` — REQUIRED: Sodium's `LevelExtractorMixin.cullTerrain`
  @Inject (fired from inside extract) resolves the SodiumWorldRenderer via
  `Minecraft.getInstance().levelRenderer` AT CALL TIME and drives `setupTerrain` on the
  DEST renderer's graph (PCS:1541-1546; running extract outside the switch drove the MAIN
  SWR with dest data → empty graph + the CullTask-on-terminated-pool crash). In a
  **FINALLY** (extract can throw AFTER consuming state):
  * Feed the flipped chunk deltas to the dest SectionOcclusionGraph —
    `updateLoadedChunks`/`updateEmptySections` (PCS:1583-1594) — guarded by the
    `LAST_APPLIED_DELTA_WINDOW` set-IDENTITY check (PCS:1586-1587; the cache
    double-buffers its delta sets, so identity distinguishes windows; never re-apply a
    stale removed-set). Without this the stencil-direct path DISCARDS every delta
    (vanilla's only consumer is `render() → sog.update`, never called there) → SOG
    desyncs for the whole away-stay → phantom holes on return-promote.
  * `if (stencilDirectMode) invokeCompileSections(destCameraState)` (PCS:1640-1643) —
    drain the one-shot compile queue `extract()` consumed. **FBO mode is EXCLUDED**
    (PCS:1634-1637): `render()` drains it there, and draining twice compileAsyncs the same
    regions twice per frame — compileAsync cancels in-flight tasks, so double-drain means
    the compile never finishes.
- **8b. Re-assert manual visibleSections (PCS:1655-1663).** Manual path only:
  `populateVisibleSectionsByFrustum(...)` — which just `addAll`s the
  `prebuiltVisibleSections` computed by the SAME frame's VSD sweep (PCS:2151-2168), so the
  frustum scan runs once per frame, not twice.
- **8c. Probe geometry (PCS:1665-1692).** `destChunks =
  destRenderer.prepareChunkRenders(destViewMatrix)` (PCS:1672). **Do NOT bail when
  empty** — the compile→upload pipeline lives INSIDE `render()`; bailing left meshes
  scheduled but never uploaded ("constant thin sliver that never fills"). `destChunks` is
  a standalone probe kept only for the maxIndices==0 diagnostic + the Sodium re-point.
- **8d. Stencil setup fork (PCS:1728-1739).** Stencil-direct →
  `SPR.setStencilLimitation(layer)` (SPR:506-510 = `glStencilFunc(EQUAL, layer, 0xFF)`,
  `glStencilOp(KEEP,KEEP,KEEP)`, `glStencilMask(0x00)` — raw GL that persists through
  renderGroup because blaze3d's `GlCommandEncoder.applyPipelineState` never touches
  stencil, SPR:500-504). FBO mode → `glDisable(GL_STENCIL_TEST)` (PCS:1738) — no masking
  inside the offscreen FBO; the stencil clips at COMPOSITE time instead.
- **8e. Clip + matrix push (PCS:1740-1810).** Capture FrontClipping outer snapshot
  (PCS:1761); `FrontClipping.disable()` (PCS:1789 — the outer plane must not leak into
  dest space); push+identity the RenderSystem modelview stack (PCS:1790-1792);
  `backupProjectionMatrix` + `setProjectionMatrix(writeProjectionBuffer(destProj),
  PERSPECTIVE)` (PCS:1800-1803) — ALWAYS override, else the dest world inherits the
  player's footstep bob.
- **8f. Globals UBO → dest (PCS:1811-1821).** `GlobalSettingsUniform.update(...)` with
  `destLevel.getGameTime()` + `destCameraPos`.
- **8g. Sodium arm + fog override (PCS:1832-1863).**
  `SodiumBridge.updateChunkSectionsRenderer(destChunks, destRenderer, destProj,
  destViewMatrix, destCameraPos)` (PCS:1850-1855 — vestigial belt-and-suspenders, see §5);
  `SodiumFogOverride.activate(destFogData)` (PCS:1857-1858), cleared in a finally
  (PCS:1989).
- **8h. THE DRAW — the three-point fork's third point (PCS:1864-1987).**
  * Stencil-direct (PCS:1865-1968), gated on `directChunkSampler != null &&
    destChunks.maxIndicesRequired() > 0`: save shader fog +
    `RenderSystem.setShaderFog(destFogBuffer)` (PCS:1893-1895); `renderPortalSky`
    (PCS:1903 — sky BEFORE the inner clip is armed: the camera-centred dome spans both
    plane sides, clipping halves it); arm `FrontClipping.setupInnerClipping` (PCS:1916-1919);
    `renderGroup(OPAQUE, directChunkSampler)` (PCS:1921-1922; LOAD, no clear — preserves
    the overworld outside the opening); re-arm the clip pushed 0.5 blocks camera-ward via
    `setupInnerClippingForEntities` (PCS:1940-1943) so a plane-straddling mob isn't
    bisected; `renderPortalEntities` (PCS:1944); re-tighten (PCS:1945-1948);
    `renderGroup(TRANSLUCENT, ...)` (PCS:1956-1957 — the dest renderer's translucent
    target is null → falls back to the main target, stencil-masked);
    `renderPortalClouds` (PCS:1958-1960); finally `FrontClipping.disable()` + restore
    shader fog (PCS:1961-1966). `fboRendered[0]=true` (PCS:1967).
  * **FBO mode (PCS:1969-1987):** `destRenderer.render(GraphicsResourceAllocator.UNPOOLED,
    deltaTracker, false, destCameraState, destViewMatrix, destFogBuffer,
    destFogData.color, true)` (PCS:1974-1983) — the 26.2 **8-arg** signature; `render()`
    builds its own ChunkSectionsToRender internally via `prepareChunkRenders(modelView)`.
    The FULL nested framegraph draws into `switchTarget = secondaryFbo`, INCLUDING the
    clear (to dest fog/sky colour) — the mod never hand-clears the FBO.
    `fboRendered[0]=true` (PCS:1986).
- **8i. Nested finallys (PCS:1988-2031).** `SodiumFogOverride.clear()` (PCS:1989); restore
  the Globals UBO with the SOURCE camera + `savedLevelGameTime` **while mc.mainRT is
  still the secondary FBO** (sizes match — PCS:2000-2013); `restoreProjectionMatrix`
  (PCS:2014); `popMatrix` (PCS:2021); `FrontClipping.restore(outerSnap)` (PCS:2027);
  re-enable stencil `EQUAL(1)/mask 0x00` (PCS:2028-2030 — the old single-phase tail that
  §1.2's phase-1 finally must neutralize).

**Step 9 — outer finally (PCS:2034-2078).** `isRenderingPortal=false`;
`portalLightmapOverride=null`; restore `destCameraState.fogData/fogType` (PCS:2042-2043);
**re-run `fogRenderer.setupFog(mainCamera, effectiveRenderDistance, deltaTracker, 0f,
mc.level)`** (PCS:2062-2077) so Sodium's `FogRenderer.setupFog` @Inject re-captures SOURCE
fog THIS frame — otherwise Sodium serves dest fog into the source world until next frame.

**Step 10 — the latch decision (PCS:2080-2122).** `if (!fboRendered[0]) return false`
(PCS:2085-2087): the cull produced no drawable geometry (async meshes still compiling) and
the FBO holds only the cleared fog colour — compositing it would flash fog-only through
the portal. The caller falls back and retries next frame. Else `phase2SuccessCount++`,
return true; the composite is deferred to phase 2 (PCS:2100-2110).

### 1.5 The fallback ladder ("nothing rendered" is always covered)

Phase 2 `compositeDestinationWorld` (PCS:751-773): ready → `compositePortalFbo()`; not
ready → `renderColoredBlocks` if `RemoteChunkManager.hasDimensionData(destDim)`
(PCS:765-766) else `PortalShapeRenderer.drawPortalBackground` (PCS:768-771). Stencil-direct
twin: `renderDestWorldDirect` paints `drawScreenFillStencilGated(destFillArgb)`
(SPR:531-536). The opening never shows a hole into the source world.

---

## §2 THE FBO LIFECYCLE

### 2.1 Stencil-capable depth: two registered mixins + one retired

1. **`mixin/client/stencil/GlConstMixin.java` (registered,
   `seamlessportals-common.mixins.json:17`)** — global depth-format rewrite. HEAD-cancellable
   injects on `GlConst.toGlInternalId/toGlExternalId/toGlType`: every `GpuFormat.D32_FLOAT`
   depth texture in the whole game is allocated as `GL_DEPTH24_STENCIL8` (internal 35056,
   external `GL_DEPTH_STENCIL` 34041, type `GL_UNSIGNED_INT_24_8` 34042)
   (GlConstMixin:33-62). This puts 8 stencil bits into every depth texture, including the
   secondary FBO's.
2. **`mixin/client/stencil/RenderTargetMixin.java` (registered, mixins.json:18)** —
   attachment surgery at the ONE unified FBO-allocation path. @Inject at RETURN of
   `FrameBufferCache.createFbo(CacheKey, DirectStateAccess, List<FrameBufferAttachment>,
   FrameBufferAttachment depth)` (RenderTargetMixin:39-45). In 26.2 EVERY render FBO —
   game main FBO and the command-encoder render-pass FBOs — routes through
   `FrameBufferCache.getFbo() → createFbo()` (doc :20-31). The mixin: saves the read/write
   FBO bindings via **GlStateManager's caches, not raw GL** (the S14.26 cache-invariant
   fix, :52-61); binds the new FBO; detaches `GL_DEPTH_ATTACHMENT` and re-attaches the
   same depth texture id as `GL_DEPTH_STENCIL_ATTACHMENT` (:63-65); checks
   `glCheckFramebufferStatus` and REVERTS to plain depth on incomplete (:67-73); on
   success records `StencilState.gameFboId = fbo` (:75 — deprecated, see §8); restores the
   saved bindings (:78-79).
3. `mixin/client/stencil/GlTextureViewMixin.java` — the RETIRED 26.1.2 predecessor
   (targeted `GlTextureView.createFbo`, removed in 26.2). **NOT registered**; history only.

### 2.2 Allocation, keying, sizing — `prepareSecondaryFbo` (PCS:2174-2189)

- The per-portal surface is a vanilla `TextureTarget`:
  `new TextureTarget("seamless_portal", w, h, /*useDepth=*/true, GpuFormat.RGBA8_UNORM)`
  (PCS:2182). Color = RGBA8_UNORM; depth requested D32_FLOAT → rewritten to
  DEPTH24_STENCIL8 by the mixins above. Its GL FBO is created lazily by blaze3d when
  first used as a render-pass attachment — the mod never allocates or captures an FBO id.
- **Keying:** `portalFbos: Map<UUID, TextureTarget>` keyed by SOURCE-portal id (PCS:373).
  The two-portals fix: each portal renders its OWN destination into its OWN full-screen
  target (PCS:367-372); the per-portal ready flags `fboReadyByPortal` (PCS:375) replaced a
  single `fboReadyThisFrame`, which collapsed two portals into one stretched view.
  `activePortalId` (PCS:377) selects the live pool entry; `secondaryFbo` (PCS:365) is a
  transient pointer re-aimed before each render (PCS:2180-2188) and each composite
  (PCS:755-756) so the big methods need no per-call plumbing.
- **Buffering: single-buffered per portal, reused sequentially frame to frame** — the
  code's own analogy is IP's `SecondaryFrameBuffer` "reused sequentially" (PCS:363,
  2170-2173). (Any "double-buffering" notes near PCS:275 concern the ClientChunkCache
  DELTA SETS consumed by the §1 step-8a identity guard — nothing to do with the FBO.)
- **Sizing: always full-screen**, exactly the main RT's w×h (PCS:2175-2177), resized
  in place on mismatch (PCS:2185-2186). There is NO resolution scaling: the config
  `portalFramebufferScale` (config/SeamlessPortalsConfig.java) has ZERO callers in the
  render path — dead/vestigial. Full-screen + rendered with the main camera's projection
  (PCS:1419) is precisely what lets the composite be a 1:1 screen-space blit. (The
  memory-note "mirror-FBO cost scales config²" is the dest WORLD-RENDER workload bounded
  by `portalRenderDistance`, not FBO resolution.)
- **Clear:** delegated — in FBO mode the nested vanilla framegraph
  (`destRenderer.render(...)`) clears the bound target to the dest fog/sky colour
  (PCS:2080-2082 confirms "the FBO holds only the cleared fog color" when nothing drew).
  Note the alpha of that clear is 0 — the reason the composite disables blend (§3).
- **Stencil of the secondary FBO is UNUSED** in FBO mode (glDisable inside the FBO render,
  PCS:1736-1739); masking happens against the MAIN target's stencil at composite time.
  The secondary depth IS used normally by the dest draw.

### 2.3 Eviction / release

`evictUnusedPortalFbos(keepIds)` (PCS:416-425): `destroyBuffers()` + remove every entry
not in `keepIds`. Called at the end of phase 1 with the rendered set (SPR:282) and with an
EMPTY set when no portals resolve (SPR:260-264) — walking away frees everything. Resize is
in-place, no destroy.

**GAP (verified):** `PWM.cleanup()` (disconnect/quit) calls
`PortalContextSwitch.resetPerDimRenderState()` (PWM:2067), which clears ONLY the fog/delta
bookkeeping maps (PCS:286-290) — `portalFbos` is untouched anywhere in the cleanup path.
On world exit the pooled TextureTargets are never `destroyBuffers()`'d: a latent GPU-handle
leak on the flag-OFF FBO path (masked today because STENCIL_DIRECT=true means they're
never allocated). Any FBO pool the iris compositor builds needs an explicit world-exit
teardown.

---

## §3 THE COMPOSITE/PASTE STEP — `compositePortalFbo` (PCS:2410-2484)

Runs in phase 2, AFTER `renderOnePortal` wrote the portal opening into the stencil
(value 1, depth-tested so the obsidian frame occludes it) and left
`glStencilFunc(EQUAL,1)/glStencilMask(0x00)` pending. Concrete recipe, in order:

1. Bail if `secondaryFbo` or its color view is null (PCS:2411).
2. **Raw-GL backstops** (renderLevel may have changed state, and
   `GlCommandEncoder.applyPipelineState` short-circuits when `lastPipeline` is unchanged —
   pipeline-declared state may not be re-applied):
   `glEnable(GL_STENCIL_TEST)`, `glStencilFunc(EQUAL, 1, 0xFF)`, `glStencilMask(0x00)`
   (PCS:2417-2419); `glDisable(GL_BLEND)` (PCS:2420-2423 — the FBO clear alpha is 0;
   with blend on, main-world clouds/sky show through the paste);
   `glDisable(GL_DEPTH_TEST)` (PCS:2424-2430 — never let leftover portal-plane depth
   GEQUAL-gate the blit; the blue-curtain fix).
3. `createRenderPass` on the MAIN RT's color + depth texture VIEWS — the **6-arg overload
   with an EXPLICIT full `RenderArea(0, 0, mainRT.width, mainRT.height)`**
   (PCS:2455-2462). The 5-arg overload auto-derives renderArea from
   `colorView.getWidth/Height(0)`, which the GL backend enforces as the SCISSOR for every
   draw — at ultrawide the composite was clipped to the bottom half (PCS:2446-2454).
   Texture views only; blaze3d resolves the real FBO internally via
   `FrameBufferCache.getFbo` — no FBO id is ever captured. RenderTargetMixin (§2.1)
   guarantees that pass FBO carries `DEPTH_STENCIL_ATTACHMENT` so the stencil is readable
   (PCS:2406-2408).
4. `pass.setPipeline(PortalRenderTypes.portalCompositeBlit())` (PCS:2472). **The pipeline
   (PRT:189-202):** a hand-built clone of vanilla `RenderPipelines.TRACY_BLIT` — vertex
   `core/screenquad`, fragment `core/blit_screen`, bind groups GLOBALS + IN_SAMPLER,
   `PrimitiveTopology.TRIANGLES`, cull off — **with
   `withDepthStencilState(Optional.empty())`, which makes `applyPipelineState` call
   `_disableDepthTest()`: depth FULLY OFF.** (CORRECTION vs some in-repo comments/javadoc
   that say "ALWAYS_PASS depth": the build comment at PRT:196-198 records that an
   ALWAYS_PASS DepthStencilState was TRIED and kept the depth test ENABLED, still gating
   GEQUAL — `Optional.empty()` is the actual, working state. The raw
   `glDisable(GL_DEPTH_TEST)` in step 2 is the belt-and-suspenders for the
   lastPipeline short-circuit.) Why not vanilla TRACY_BLIT: its empty depth state, driven
   through a render pass that HAS a depth attachment (required for the stencil), made the
   GL backend apply the reversed-Z GEQUAL default and depth-gate the blit — the
   portal-plane depth (~0.85) failed GEQUAL vs the triangle's ~0.5 → the source sky showed
   through the upper opening = the "blue curtain" (PCS:2463-2470).
5. `RenderSystem.bindDefaultUniforms(pass)` (PCS:2473).
6. `pass.bindTexture("InSampler", secondaryFbo.getColorTextureView(),
   samplerCache.getClampToEdge(NEAREST))` (PCS:2476-2478) — **via the render pass, never
   raw `glBindTexture`** (raw GL does not affect render-pass sampler bindings,
   PCS:2474-2475).
7. **`pass.draw(3, 1, 0, 0)` — a FULL-SCREEN TRIANGLE** (PCS:2479). No vertex buffer, no
   portal-corner geometry, no matrices: `core/screenquad` generates positions from
   `gl_VertexID`. **There is NO portal quad in the paste.** Portal SHAPE is imposed
   entirely by the stencil EQUAL(1) mask — deliberately: re-rasterizing the quad geometry
   (with any epsilon) can differ from the STEP-2 stencil write's rasterization → a thin
   see-through sliver ring at the frame edge (SPR:544-558). Quad geometry is used only for
   the stencil WRITE and the depth quads in SPR, never for the composite.
8. **UV mapping: screen-space 1:1, not projective portal-quad UV.** `core/blit_screen`
   samples InSampler at the fragment's screen position; because the FBO is full-screen and
   was rendered with the SAME projection as the main camera, the dest content already sits
   at the correct pixels. Straight per-pixel copy, NEAREST, clamp-to-edge, no MSAA/resolve
   anywhere (TextureTarget is single-sample).
9. **Depth: neither tested nor written** by the composite. Occlusion of LATER main-frame
   passes (clouds/weather) over the pasted opening is handled separately: renderOnePortal's
   FBO branch writes NEAR depth (glDepthRange(1,1), reversed-Z) across the opening via the
   stencil-gated full-screen depth shield (SPR:404-411, 458-475; pipeline
   `portalScreenDepthClear`, PRT:212-223).
10. Restore `glEnable(GL_BLEND)` + `glEnable(GL_DEPTH_TEST)` (PCS:2482-2483).

**FBO-0 hazard context:** Fabric render callbacks fire with `GL_FRAMEBUFFER 0` bound
(`GlCommandEncoder.finishRenderPass` unconditionally binds 0 — `render/StencilState.java:9-14`).
Manual raw-GL stencil ops in SPR must first re-discover and bind the live FBO (dummy-draw
into `StencilState.lastBoundFbo`, SPR:328-338). The composite avoids the whole problem by
going through `createRenderPass` on the RT's texture views — blaze3d rebinds.

---

## §4 THE CONTEXT SWITCH + RESTORE — `withSwitchedWorld` (PCS:569-664)

The atomic "point mc at the dest world, run the render, restore in finally." try/finally
(PCS:628/646) guarantees restore on a mid-render throw. Exact swap set, verified:

**SAVE (PCS:592-611):** particleEngine, mainRenderTarget, mc.level, mc.levelRenderer,
mainCamera, lightmap, mc.hitResult, player.noPhysics, pooled RenderBuffers acquire
(`PortalRenderBuffersPool.acquire()` — null if exhausted → NO swap, fall back to the
renderer's own buffers, no crash), gameRenderer's renderBuffers, destRenderer's
renderBuffers.

**SWAP-IN (PCS:629-643, this order):** `setMainRenderTarget(destMainRT)` →
`mc.level = destLevel` (raw field write) → `setLevelRenderer(destRenderer)` →
`setMainCamera(destCamera)` → `setLightmap(destLightmap)` → `mc.hitResult = null` →
`player.noPhysics = true` → `setParticleEngine(destParticleEngine)` + `destParticlesActive
= true` → `setRenderBuffers(pooled)` on BOTH gameRenderer and destRenderer. Then
`renderCallback.run()` (PCS:645).

**RESTORE (finally, PCS:646-663): exact reverse order**, ending with
`PortalRenderBuffersPool.release(pooledBuffers)` (PCS:662).

Key mechanics:
- **The ParticleEngine is swapped WHOLE, not `setLevel`'d** (PCS:584-594): the public
  setter calls `clearParticles()` as a side effect, which would wipe the source dim's
  particles every frame; and a separate engine = separate particle groups, so the
  mid-frame dest extract can't corrupt the source world's shared group accumulators.
- **`mc.levelExtractor` is NOT swapped.** The dest extract is driven directly via
  `PWM.getExtractor(destDim)`; what IS load-bearing is `mc.levelRenderer == destRenderer`
  during the nested render, because Sodium (and any call-time resolver) reads
  `Minecraft.getInstance().levelRenderer` (§1 step 8a).
- Dropped-from-IP swap fields with no 26.2 equivalent: blockEntityRenderDispatcher.level,
  doRenderHand, FogRendererContext.swappingManager (PCS:553-562).
- **Why pooled RenderBuffers** (`render/PortalRenderBuffersPool.java`): the dormant
  vanilla renderer (now a per-dim map entry) SHARES its renderBuffers with
  mc.renderBuffers — using it as a secondary while the main render is in flight conflicts
  on those buffers. Pool of CAP=2 (IP's acquire/return cap-2, headroom for the still-gated
  depth-2 portal-in-portal), `BUFFER_BUDGET=4`.

**The endFrame walk** — the GPU-buffer-leak discipline (host:
`mixin/client/GameRendererMixin.java:38-70`, @Inject at `GameRenderer.render` TAIL, right
after vanilla's own `renderBuffers.endFrame()`; active in BOTH flag states):
1. `PWM.lateUpdateSecondaryLight()` (:47 — IP lateUpdateLight: run each live secondary's
   light engine at frame-render END).
2. `PWM.endSecondaryRenderFrames()` (:48; PWM:1993-2019): identity-dedup'd walk — seed the
   done-set with the vanilla-owned RenderBuffers (vanilla already ended it; double-endFrame
   is skipped), then `endFrame()` once per identity for `mc.levelRenderer`'s buffers (a
   PROMOTED renderer draws the main world from its ex-dest buffers, which vanilla's
   endFrame misses) and every renderer in the per-dim map; plus `renderer.endFrame()` for
   every secondary to rotate the cloud UBO ring.
3. `PortalRenderBuffersPool.endFramePooled()` (:49) — the pooled buffers, fully idle at
   TAIL (all sub-renders acquire/release within renderLevel).
Without every mod-created RenderBuffers getting endFrame() EVERY frame, StagedVertexBuffer
pools never fence-recycle → tens of MB/s VRAM leak → multi-second driver-paging stalls
inside arbitrary GL calls. (The flag-ON counterparts — `MyGameRenderer.endFramePooled` +
`SecondaryWorldRenderCore.closeFrameTransientUbos` — are wired at the same TAIL,
GameRendererMixin:57-64.)

**Per-dim substrate PWM holds** (block-era): four ConcurrentHashMaps keyed by
`ResourceKey<Level>` — renderers, levels, extractors, particleEngines (PWM:67-90).
`createRenderer` (PWM:561-696) builds a fully isolated secondary: fresh
FeatureRenderDispatcher + RenderBuffers, `new LevelRenderer` with its final fields
overridden to an isolated `LevelRenderState`, a per-dim `new LevelExtractor`, a
`new ClientLevel` with storage radius = config portalRenderDistance, optional
`SeamlessClientChunkMap` (unbounded store) swap BEFORE setLevel, then
`destExtractor.setLevel(destLevel)` (PWM:680 — see §8 warning 1). `promoteToMain` /
`demoteFromMain` re-point extractor fields DIRECTLY (deliberately NOT `setLevel()` —
`allChanged()` → `invalidateCompiledGeometry` wipes cached meshes) and must
`clearVisibleSections()` on promote (stale RenderSection nodes → teleport NPE in
compileSections). `tickRemoteWorlds` ticks each live secondary per client tick under a
MINIMAL swap (only mc.level + mc.particleEngine). `DimensionRenderHelper` keeps its own
per-dim {Lightmap, LightmapRenderState} map (DRH:36).

**Per-dim time/sky/fog:** dest gameTime flows through the Globals UBO update (§1 8f) and
PWM keeps secondary levels' gameTime synced to the active level; sky = `renderPortalSky`
driven by dest sky state (skipped for no-sky dims); fog = the §1 step-6 isolated buffer +
DRH lightmap. All pipeline-agnostic.

---

## §5 SODIUMBRIDGE ARM MECHANICS

**What the files are.** `compat/SodiumBridge.java` = a REFLECTIVE bridge to Sodium's
per-LevelRenderer API (no compile-time dep); `compat/SodiumCompat.java` = the load gate
(`isModLoaded("sodium")` + NeoForge/embeddium fallbacks, cached volatile). Every bridge
method early-returns when Sodium is absent.

**Live-at-HEAD surface (verified by call-site sweep):** exactly THREE methods are invoked,
all from doFboRender — `updateChunkSectionsRenderer` (PCS:1850-1855), and the diagnostics
`getVisibleChunkCount` (PCS:1859-1862, 1992-1993) + `getDebugInfo` (PCS:1994-1995), both
gated `phase2SuccessCount <= 3`. Everything else (ensureSodiumLevel,
setupTerrainForCamera, notifyChunkLoaded, notifyChunkAddedToRenderer,
scheduleRebuildForChunk) is defined-but-DEAD.

**What "arming" means.** `updateChunkSectionsRenderer` (SodiumBridge:473-530) reflects
`net.caffeinemc.mods.sodium.client.util.SodiumChunkSection.sodium$setRendering(
SodiumWorldRenderer, ChunkRenderMatrices, double, double, double)` and invokes it on a
vanilla `ChunkSectionsToRender` — re-pointing Sodium's three mixin-installed per-draw
fields (renderer, matrices, camera offset) at the DEST SWR with the portal-view
projection+view + dest camera pos. WHY (docblock SodiumBridge:410-435): extract runs
FIRST with STALE matrices (the last main-render's values are still on the LevelRenderer's
Sodium `matrices` field), so an unarmed dest ChunkSectionsToRender draws at the wrong
position — "visible chunks but off-screen / wrong angle = only fog color visible in FBO."
The arm goes AFTER extract, BEFORE the draw.

**HONEST STATUS: the live arm at HEAD is INERT.** The in-code comment at the call site
(PCS:1837-1849) is explicit: Sodium 0.9.x's own `LevelRendererMixin.getRenderState`
@WrapOperation already arms the ChunkSectionsToRender that `render(...)` builds INTERNALLY
(with the portal-view matrices from the RenderSystem projection set at PCS:1800-1803 and
the dest camera pos from destCameraState). The bridge arms the SEPARATE standalone
`destChunks` probe (built at PCS:1672 only for the maxIndices diagnostic) — the wrong
instance. Kept as a belt-and-suspenders no-op for older Sodium builds. **The working
sodium mechanism at HEAD is Sodium's own wrap under the world switch, not the bridge.**

**How Sodium terrain is ACTUALLY driven:** implicitly — `destExtractor.extract(...)` runs
inside `withSwitchedWorld` while `mc.levelRenderer == destRenderer`, so Sodium's
`LevelExtractorMixin.cullTerrain` @Inject resolves the DEST SWR at call time and drives
`setupTerrain` on the correct graph (PCS:1537-1546, 1823-1830). The bridge's own
reflective `setupTerrainForCamera` driver is the abandoned earlier approach.

**Version honesty:** the bridge PREDATES the pinned Sodium 0.9.1 (introduced 2026-05-15,
tracked a moving target reflectively). Its version-detection heuristic (setupTerrain
arity-6, 6th-param type) MISLABELS: the arity-6/Matrix4f@6 shape it calls "0.6.x" is
actually 0.9.x (real 0.6.0 setupTerrain was arity-4 — `migration/C2_091_MAP.md:17`).
Cross-checked vs the real 0.9.1 javap map: `sodium$getWorldRenderer`,
`scheduleTerrainUpdate`, the private `renderSectionManager` field,
`ChunkTrackerHolder.get`, `ChunkTracker.onChunkStatusAdded`,
`SimpleFrustum(FrustumIntersection)` all survive; `RenderSectionManager` was rearchitected
async/multi-tree so `onSectionAdded`/`onChunkAdded`/`scheduleRebuildForChunk` are
unmapped/likely-dead; the SWR debug methods and `Viewport(Frustum,Vector3d)` ctor are
unverified. Never trust the in-file version comments; javap the pinned jar.

**Managed-code discipline:** any reflective call into Sodium's GL command list / chunk
graph must bracket `RenderDevice.enterManagedCode()/exitManagedCode()` (helpers
SodiumBridge:224-240) or Sodium throws "Tried to access device from unmanaged context."
The one live method deliberately does NOT bracket — `sodium$setRendering` only sets
fields, no GL.

**Cold start:** NO manual chunk priming. Chunks arrive via redirected vanilla packets;
Sodium's own ClientChunkCacheMixin queues load events into the per-ClientLevel
ChunkTracker, drained inside the next portal-view setupTerrain. The bridge's priming
methods were REMOVED from the feed path because directly poking RenderSectionManager
CORRUPTED the graph — "garbled chunk meshes / striped texture artifacts post-teleport —
neighbor links broken by double-registration. The natural Sodium flow is sufficient"
(PWM:920-927). The `fboRendered` latch + fallback + next-frame retry is the entire
cold-start strategy.

**FlawlessFrames / GLOBAL_PASS_SERIAL:** ABSENT from the block-era system entirely. The
equal-frame lastVisibleFrame collision handling, FlawlessFrames arming, and the D1
context-registry/repoint machinery are C2 flag-ON discoveries on the qouteall substrate
(`migration/port-notes/C2-sodium-iris.md`) — do not mine this code for them.

**C2 already rebuilt the arm concept correctly:** `ip_armDestChunkRenders`
(C2-sodium-iris.md §3.4 area, port-note :239-242) replicates
`SodiumChunkSection.sodium$setRendering` with the dest draw projection + view matrix +
camera pos, with one deliberate omission (the LevelRendererMixin.matrices putfield —
jar-proven consumer-free). The concept is validated; the block-era reflective plumbing is
what dies.

---

## §6 THE STENCIL/FBO FORK + PERIPHERY

### 6.1 The fork, precisely

- Master switch `STENCIL_DIRECT` (SPR:41, static boolean, default true; javadoc SPR:29-40:
  flip false "to restore the working FBO render" for A/B). GLOBAL — there is NO
  per-portal-class routing: `PortalType` (NETHER/END/CUSTOM) is a teleport-transform
  descriptor, not a render-mode selector; same-dim ("mirror") portals are explicitly
  SKIPPED by resolveRenderTargets, never rendered.
- Fork point 1 — phase-1 short-circuit: `if (STENCIL_DIRECT) return;` (SPR:246). In
  stencil-direct there is NO phase-1 FBO render at all (and no
  beginPortalFrame/evict — no FBOs exist to manage).
- Fork point 2 — the per-portal draw seam `renderDestWorldDirect` (SPR:525-542):
  true → `PCS.renderDestinationDirect(portal, link, camera, layer)`; failure → the
  stencil-gated dimension-coloured full-screen fill. false →
  `PCS.compositeDestinationWorld(...)` (the paste).
- Fork point 3 — the depth/fill discipline inside renderOnePortal is `STENCIL_DIRECT`-
  branched: direct clears opening depth to FAR (glDepthRange(0,0)) before dest terrain
  (SPR:388-403); FBO branch writes NEAR (glDepthRange(1,1)) shields before/after the
  composite (SPR:404-411, 458-475).
- Inside doFboRender the same decision appears as `stencilDirectMode` at the three points
  listed in §0.
- **Evidence model** (`render/PerfTimers.java` — off-thread aggregator, one summary line
  per 5s, never logs on the render thread): the mutually-exclusive buckets `fboRender(Np)`
  (SPR:279, FBO mode only) vs `stencilDirectRender` (SPR:463, direct only) discriminate
  the live mode; the `destBail:*` histogram pins why an opening went blank.

### 6.2 The clip story (two mechanisms; one live, one abandoned)

- **LIVE: gl_ClipDistance[0] inner clip** = `render/ShaderCodeTransformation.java` (the
  MECHANISM: GLSL string-rewrite of vanilla vertex shaders at load — appends
  `out float gl_ClipDistance[1]` + a `seamlessportals_ClipPlane` uniform and injects
  `gl_ClipDistance[0] = dot(sp_viewPos.xyz, plane.xyz) + plane.w` after the canonical
  `gl_Position = ProjMat * ModelViewMat * vec4(pos,1.0)` write; ONLY shaders matching that
  exact pattern are patched; the explicit `[1]` sizing is required — unsized
  gl_ClipDistance is silently dropped by NVIDIA/AMD drivers) + `render/FrontClipping.java`
  (the STATE: view-space plane; `setupInnerClipping` puts the plane at the dest portal
  with the normal flipped to KEEP the far side; `setupInnerClippingForEntities` pushes it
  0.5 blocks camera-ward for the entity pass; raw `glEnable(GL_CLIP_DISTANCE0)` persists
  through renderGroup like the stencil). History that matters: in the pure-mirror-FBO era
  the inner clip was DISABLED (the "curtain fix" — with the 1:1 mirror camera, planeW
  grows as the player backs away and the clip swallowed the near half of the view; the
  stencil mask already confines composited pixels); it was RE-ENABLED for stencil-direct
  because the stencil confines PIXELS but cannot remove near-side OCCLUDERS along the
  sight-lines (PCS:1762-1788).
- **ABANDONED for the dest render: Lengyel oblique near-plane projection**
  (`applyObliqueNearPlane`, PCS:2198+, dead — `obliqueApplied=false` hardcoded,
  PCS:1442-1447). Its still-used sibling `render/PortalSlicing.java` obliquely clips the
  MAIN (source) world's projection at the SOURCE portal when the eye is within ~3 blocks —
  a different job (the crossing moment), driven by GameRendererObliqueClipMixin.

### 6.3 LevelExtractorFlashBridgeMixin (portal-view-relevant clause)

@Inject at `LevelExtractor.applyFrustum` HEAD, cancellable: while
`PortalContextSwitch.isRenderingPortal` is true, `ci.cancel()` — (1) SKIPS vanilla's SOG
frustum walk over the bulk-loaded secondary world (the lit-portal 100-766ms stutter, two
thread dumps caught in `Frustum.offsetToFullyIncludeCameraCube ←
SectionOcclusionGraph.addSectionsInFrustum ← applyFrustum ← doFboRender`), and (2)
prevents applyFrustum from CLEARING the visibleSections the VSD flood already filled this
frame. The mechanism is PROTECTIVE, not additive: extract still runs for its delta-flip +
particle side effects; its visibleSections repopulation is short-circuited. (The mixin's
other clauses are the post-teleport promote flash bridge — a different concern.)

### 6.4 PortalFrameSuppressor + SodiumFogOverride

- `render/PortalFrameSuppressor.java`: the frame-hiding half is DISABLED (`isFrameBlock`
  always false — the user wants the dest obsidian frame visible). Only live method:
  `maybeForceDirtyForPortal` — a one-shot re-dirty of sections within radius 2 of the dest
  portal so meshes baked under the old suppression regenerate (called at PCS:1244).
- `render/SodiumFogOverride.java` + `mixin/client/compat/SodiumFogOverrideMixin.java`
  (@Pseudo, targets Sodium's merged `sodium$getFogParameters`, require=0): while
  `activate(destFogData)` holds (PCS:1857-1858 → clear PCS:1989), Sodium's chunk-draw
  fog uniform is served OUR dest-dim FogParameters instead of its captured main-render
  fog. Under the C2-P8 gate-audit (`-Dseamlessportals.compatProbe=true` one-shot probe)
  for S20 reachability — at default flag-ON the block-era doFboRender never runs, so
  activate() is never called, but the mixin stays registered in both flag states.

---

## §7 WHAT TRANSFERS TO THE IRIS SHADERS-ON COMPATIBILITY SHAPE

The target (per `migration/IRIS_SHADERS_ON_HANDOFF.md`): render the dest world through the
ACTIVE iris pipeline into an offscreen FBO once per portal per frame, then paste onto the
portal opening — re-expressed onto the flag-ON substrate's
`SecondaryWorldRenderCore.renderDestWorld`.

### 7.1 TRANSFERS (the headline mappings)

1. **The FBO fork of doFboRender is the precedent, not the shipping default.** The iris
   compat render is, like `destRenderer.render(...)` at PCS:1974, a FULL pipeline-owned
   world render — the structural twin of the FBO fork. And the block-era proof is
   definitive on timing: **a nested/full world render CANNOT be issued from
   mid-main-framegraph** (AFTER_TRANSLUCENT_TERRAIN blanked the overworld — PrepMixin
   doc, PCS:673-688). The heavy once-per-portal FBO render belongs at a frame anchor
   OUTSIDE the main framegraph (block-era answer: renderLevel HEAD / phase 1), and ONLY
   the lightweight paste belongs at the stencil/composite site (phase 2). Caveat for the
   flag-ON substrate: the qouteall path hand-drives renderGroup/renderAllFeatures with no
   framegraph re-fire for dest passes — so re-check whether the iris pipeline's own passes
   re-introduce a framegraph-nesting hazard before choosing the anchor; the two-phase
   split is the proven safe shape if they do.
2. **The FBO lifecycle transfers whole:** one full-screen `TextureTarget` per portal,
   keyed by source-portal UUID, sized to the main RT (never config-scaled), resized in
   place, single-buffered and reused sequentially, per-portal ready flags, per-frame flag
   reset, evicted (destroyBuffers) when the portal leaves the rendered set — plus the
   missing world-exit teardown (§2.3 gap) done RIGHT this time. Full-screen + main-camera
   projection = the 1:1 screen-space paste; do not shrink the FBO to the opening.
3. **The paste transfers as-is** and is the cleanest reusable artifact (§3): stencil
   EQUAL(1) full-screen triangle through a screenquad/blit_screen pipeline with depth
   state `Optional.empty()` (fully disabled) + raw backstops, blend off, 6-arg
   createRenderPass with explicit full RenderArea, bindTexture via the pass, texture
   views only (FBO resolved via FrameBufferCache — never a captured id), NEAREST
   clamp-to-edge. All four embedded hard-won fixes (explicit RenderArea; disabled-depth
   clone vs TRACY_BLIT; pass-bound sampler; blend off vs alpha-0 clear) apply verbatim.
   The depth-stencil plumbing that makes any stencil-masked composite possible on 26.2
   (GlConstMixin + RenderTargetMixin at FrameBufferCache.createFbo) is pipeline-agnostic.
4. **The context-switch discipline transfers as the template** (§4): save → swap →
   run → restore-in-finally in exact reverse order, with the load-bearing invariant that
   third-party renderers (sodium's SWR, and iris's pipeline selection off
   mc.level/dimension) resolve their state via `Minecraft.getInstance()` fields AT CALL
   TIME — so the dest extract/setup/draw must ALL run inside the bracket. The C2 tier
   already re-expressed this as the single-renderLevel swap + SodiumRendererRepoint
   brackets (C2-sodium-iris.md §2.5 discipline) — where the C2 form exists, it supersedes;
   this block-era code is the precedent that proves the shape.
5. **The pipeline-agnostic dest-side setup transfers whole:** virtual camera via
   transform + `camera.tick()` to prime the EnvironmentAttributeProbe; frustum from
   copied main projection; per-dim lightmap via DimensionRenderHelper/attributeProbe;
   the isolated standalone fog GpuBuffer (never the shared ring buffer); the SHARED
   `cameraRenderState.fogData/fogType` save/restore; the source-fog re-capture in the
   outer finally; the graduated-radius smoothed fog. An iris FBO render still needs all
   of these.
6. **The endFrame walk + pooled-buffer discipline transfers** (§4): any per-secondary or
   pooled RenderBuffers the iris path drives must be endFrame()'d every frame at
   GameRenderer.render TAIL, identity-dedup'd, skipping the vanilla-owned one. This is
   the same family as the C2 secondary-SWR UBM/endFrame lesson (C2-sodium-iris.md §3.4
   discipline — the C2 form supersedes for the flag-ON substrate).
7. **The cold-start shape transfers:** the `fboRendered` latch → don't composite a
   fog-only FBO → coloured/stencil-gated fallback → retry next frame; NO manual chunk/
   graph priming (proven corrupting, §5).
8. **The inner-cone cull (PIC) is a pipeline-independent cost lever** worth carrying
   conceptually (bound the dest draw to the cone through the hole — IP's one real
   render-cost lever), IF the iris path exposes a place to apply it. The clip CONCEPT
   (discard dest geometry on the camera side of the dest plane; entity-pass margin)
   transfers; the block-era implementation does not (see below).

### 7.2 DIES WITH THE BLOCK ERA

A. **The stencil-direct default is a dead end for iris.** Hand-driving
   `renderGroup(OPAQUE/TRANSLUCENT)` into the main target under raw-GL stencil works only
   because renderGroup is not a framegraph and vanilla's chunk pipeline is a fixed shader
   you can hand a sampler to. Iris owns its pipeline (shadow/deferred/composite passes);
   it cannot be hand-driven that way. The whole direct fork — setStencilLimitation, the
   directChunkSampler capture, the inline AFTER_TRANSLUCENT timing, the compileSections
   drain — is precisely what the FBO approach exists to avoid.
B. **The manual visible-set machinery** (captured-frustum extract-skip, VSD flood,
   the applyFrustum HEAD-cancel, the SOG delta feed, the compile-queue drain) is a
   workaround family for the block-era per-dim secondary-renderer substrate whose native
   sog.update path was disabled (`useContinuousExtract=false`). An iris render that drives
   the engine's/pipeline's own render gets real occlusion and must NOT inherit these —
   they are corrections for an architecture that dies at S20.
C. **The per-dim secondary LevelRenderer/LevelExtractor/ParticleEngine + promote/demote
   model** (PWM) — the flag-ON ClientWorldLoader already re-expresses this better. The
   render-state/extractor-state divergence re-bind, grid-centering, delta feeds are
   hazard-CLASS knowledge, not lines to port.
D. **The RemoteChunkManager coloured-block / drawPortalBackground fallbacks and the
   <9-chunk gate** — block-era streaming artifacts.
E. **The block-era FrontClipping/ShaderCodeTransformation clip does not survive
   shaders-ON at all:** the GLSL rewrite only patches vanilla shaders matching the
   canonical gl_Position pattern — iris shader-pack vertex shaders will not match → the
   injection no-ops → no clip. The iris engagement must obtain the near-side clip through
   IP's own clip-plane route on the flag-ON substrate.
F. **The mirror camera (`transformPoint` 1:1 translation) and the ±90° yaw hack** — the
   flag-ON system uses IP's real rotation transform and camera model.
G. **The SodiumBridge reflective plumbing** — version-sniffing, the dead
   setupTerrain/priming/RSM-poking methods, the inert probe-object arm. The ARM CONCEPT
   survives as C2's `ip_armDestChunkRenders`; use that. Likewise the D1 registry /
   GLOBAL_PASS_SERIAL / FlawlessFrames machinery is C2 flag-ON work, already built,
   already better — not minable from here.
H. **StencilState.gameFboId / lastBoundFbo captured-id idioms** — deprecated, unsound
   (§8); the flag-ON path resolves the live FBO via `GlDevice.frameBufferCache().getFbo`.

---

## §8 WARNINGS (merged from all facets, deduped — what a re-implementation must NOT copy, and what it MUST keep)

1. **Never issue a nested/full world render from mid-main-framegraph.** It re-enters the
   render while the framegraph's "main" target import is in flight and blanks the
   overworld (PrepMixin:14-52, PCS:673-688). The single most load-bearing timing
   constraint; an iris FBO render inherits it.
2. **Stencil neutralize on phase exit.** doFboRender's tail re-enables EQUAL(1)/mask 0x00
   (PCS:2028-2030); phase 1 MUST reset to ALWAYS/mask 0xFF/disabled before the main
   framegraph (PCS:726-728) or the entire main world is stencil-rejected against a
   cleared (all-zero) stencil buffer.
3. **Fog isolation, three layers, all mandatory:** (a) write a STANDALONE per-call
   GpuBuffer (PCS:2495-2518), never `fogRenderer.updateBuffer()` (shared
   MappableRingBuffer → dark clipping across the whole world); (b) save/restore the
   SHARED `cameraRenderState.fogData/fogType` (PCS:1494-1497 / 2042-2043) or dest fog
   tints the source world for direct readers (Sodium); (c) re-run source `setupFog` in
   the outer finally (PCS:2062-2077) or Sodium's captured FogParameters serve dest fog
   into the source world for a frame.
4. **Persistent, never-closed GPU buffers:** the projection/restore GpuBuffers are static
   fields overwritten, never `.close()`d — the GPU may still reference the previous
   frame's buffer ("Buffer is not writable" / use-after-free otherwise) (PCS:2364-2395
   region). Same rationale for the fresh-each-call fog buffer. (The flag-ON substrate's
   answer is the frame-transient UBO ledger drained at render TAIL —
   `SecondaryWorldRenderCore.closeFrameTransientUbos`, GameRendererMixin:60-64 — prefer
   that discipline.)
5. **Globals UBO restore ordering:** re-update with SOURCE camera + gameTime WHILE
   mc.mainRenderTarget is still the secondary FBO (sizes match) before the switch unwinds
   (PCS:2000-2013).
6. **endFrame every mod-created RenderBuffers every frame** at GameRenderer.render TAIL,
   identity-dedup'd, skipping the vanilla-owned set but covering a promoted renderer's
   ex-dest buffers (§4). Miss it → tens of MB/s VRAM leak → multi-second driver-paging
   stalls. Related C2 lesson: a secondary SWR's UniformBufferManager needs its per-frame
   cleanup driven too (C2-sodium-iris.md).
7. **Composite traps (all four load-bearing, §3):** explicit full RenderArea via the
   6-arg createRenderPass (5-arg auto-scissor clips ultrawide); the depth-DISABLED
   (`Optional.empty()`) blit clone — NOT vanilla TRACY_BLIT (reversed-Z GEQUAL default =
   blue curtain) and NOT ALWAYS_PASS (tried; kept the test enabled and still gated);
   bindTexture via the render pass, never raw glBindTexture; glDisable(GL_BLEND) because
   the FBO clear alpha is 0. Plus raw-GL backstops for everything pipeline-declared,
   because `applyPipelineState` short-circuits on unchanged lastPipeline.
8. **Never capture an FBO id.** `StencilState.gameFboId` is a deprecated last-writer-wins
   capture (stored at RenderTargetMixin:75) that goes stale at resource-lifecycle events
   and once caused a 51k/session GL_INVALID_OPERATION flood; `lastBoundFbo` +
   the dummy-draw discovery (SPR:328-338) is the same anti-pattern class. Resolve live
   via `FrameBufferCache.getFbo` / pass texture views to createRenderPass. Also: Fabric
   render callbacks fire with FBO 0 bound (StencilState doc :9-14) — any manual raw-GL
   op must rebind first; render passes avoid the problem.
9. **Unbracketed setLevel/dirty sites under sodium (the C2-ledger defect — do NOT
   copy):** PWM's `destExtractor.setLevel(destLevel)` at createRenderer (PWM:680) and
   `setLevel(null)` at cleanup (PWM:2051), plus the SeamlessClientChunkMap /
   RemoteBlockUpdater dirty sites, run OUTSIDE any world-switch/repoint bracket — sodium
   resolves `mc.levelRenderer` at call time, so they route to the WRONG SWR. The flag-ON
   fix is `SodiumRendererRepoint.runWithRendererRepointed` around every such site
   (C2-sodium-iris.md). Every setLevel/dirty call in a re-implementation needs the
   bracket. (Promote/demote's direct-field-set trick avoids the mesh-wipe of
   `setLevel()` → `allChanged()`, but does NOT itself provide the repoint.)
10. **Never manually poke a third-party renderer's internal chunk graph.** RSM
    onSectionAdded/onChunkAdded/scheduleRebuildForChunk double-registration broke
    neighbor links → garbled meshes/striped textures; removed (PWM:920-927). Let the
    natural event flow register sections; cold start = fallback + retry.
11. **Silent-permanent reflective init:** SodiumBridge's one-shot init catches every
    Throwable, warns once, never retries — one moved class silently no-ops ALL bridge
    behaviour (SodiumBridge:200-205). Surface such failures louder in any re-expression;
    and pin + javap the dependency version instead of runtime API-sniffing (the bridge's
    version labels are provably wrong).
12. **Grid/store centering:** center a secondary ViewArea grid on a STABLE point (portal
    origin / scope center), never the moving mirror camera (PCS:1189-1218) —
    camera-centering resets a band of meshes to UNCOMPILED every section crossing; and
    pin the bounded chunk-store view center or it silently evicts ACKed chunks.
13. **One-shot consumption pairing:** `extract()` consumes chunk deltas + one-shot dirty
    flags; whoever skips `render()` must feed the SOG (identity-guarded, in a finally)
    and drain the compile queue EXACTLY once per frame (PCS:1580-1644) —
    double-draining compileAsyncs the same regions twice and compileAsync cancels
    in-flight tasks (never finishes). Any path that calls the real render() (as the FBO
    fork and an iris render do) must NOT also drain.
14. **Renderer-state vs extractor-state divergence** (PCS:1159-1181): extract writes the
    extractor's LevelRenderState, render reads the renderer's — divergence silently drops
    entities/clouds/particles while terrain still draws. A hazard class for any
    split-extract/render substrate.
15. **No mid-pass GPU uploads:** staged mesh uploads flush pre-framegraph at renderLevel
    HEAD only (SPR frameUpkeep; PCS:1629-1634) — flushing mid-pass resizes bound GPU
    buffers → screen flash. The compile drain inside the extract finally is CPU-only
    scheduling for exactly this reason.
16. **Render-thread logging = ~130ms log4j stalls.** Every block-era diagnostic is gated
    (`phase2SuccessCount <= 3/5` first-frames latches, one-shot volatile latches) or
    off-thread (PerfTimers 5s aggregation, RenderSpikeMonitor). Keep the discipline.
17. **GL-backend fragility to note, not copy:** the raw-GL stencil/clip persistence
    through renderGroup depends on `GlCommandEncoder.applyPipelineState` never touching
    stencil (verified in mc262-ref, SPR:500-504) — an implementation detail of the
    current GL backend, not portable and not valid under an iris pipeline.
18. **Camera probe priming:** any virtual camera used for fog/lightmap MUST be
    `tick()`'d against the dest level+position (PCS:1057-1061) or every attribute reads
    zero (black fog, dark lightmap).
19. **Per-portal keying is correctness, not hygiene:** FBO target AND ready flag keyed by
    source-portal UUID (PCS:373/375/377) — the historical single flag collapsed two
    portals into one stretched destination.
20. **World-exit FBO teardown is MISSING here** (§2.3): `resetPerDimRenderState` never
    frees `portalFbos`. The iris pool must add the explicit disconnect/quit
    destroyBuffers sweep the block era forgot.
