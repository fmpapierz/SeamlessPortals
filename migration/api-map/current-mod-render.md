# Disposition audit: current mod render slice (entity-portal migration)

Definitive replace/delete/keep audit for the slice inventoried in
`migration/inventory/current-mod-render.md` (19 render classes + 44 render mixins; adversarial verification
later found two `mixin/client/` files outside this census — see the §6 completeness note). Every disposition
below was confirmed against IP ground truth by opening the IP file (not trusting names).

- **IP citations** are relative to `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/` (IP 1.21.3, Mojang mappings).
- **Mod citations** are relative to `C:/Users/warwa/ModDev/Portals/Portal 26.2/` and carry over from the
  verified inventory doc (`migration/inventory/current-mod-render.md`, cited as `[INV §…]`).
- Vanilla-26.2 API fates for the IP render classes named here are in the companion
  `migration/api-map/render-sub.md` and `MIGRATION_API_MAP.md` — this document does not repeat them.

Legend:
- **REPLACE-BY ip.X** — superseded by porting IP class X; the mod class's 26.2 mechanics transplant into the port.
- **PORT-FORWARD** — already-IP-faithful port (or IP pattern) that survives the migration as-is, re-sourced
  from the `Portal` entity where noted.
- **KEEP** — 26.2-required enabler, compat shim, or mod diagnostic with no IP analog; survives.
- **DELETE** — dead/parked/mirror-architecture code the migration removes.
- **AMBIGUOUS** — flagged in §4 with the question that decides it (counted under its provisional disposition).

---

## 1. Render package (19 classes)

| # | Current class | Disposition | IP counterpart / reason (verified citation) |
|---|---|---|---|
| 1 | `StencilPortalRenderer` | **REPLACE-BY** `imm_ptl.core.render.renderer.PortalRenderer` + `renderer.RendererUsingStencil` | `PortalRenderer` is the abstract driver: `public abstract class PortalRenderer` (`imm_ptl/core/render/renderer/PortalRenderer.java:42`), portal discovery `getPortalsToRender(Matrix4f)` (`:84`), shared content render `renderPortalContent(...)` (`:197`), `invokeWorldRendering(...)` (`:249`), transform helpers `getPortalTransformation/getPortalRotationMatrix/getPortalScaleMatrix` (`:259/:271/:290`). `RendererUsingStencil extends PortalRenderer` (`imm_ptl/core/render/renderer/RendererUsingStencil.java:32`); per-portal loop `renderPortals(Matrix4f)` (`:73-79`), `doRenderPortal(...)` (`:119`), depth choreography `clearDepthOfThePortalViewArea` (`:199`) / `restoreDepthOfPortalViewArea` (`:227`), recursion clamp `clampStencilValue` (`:249`), `setStencilLimitation(int)` (`:286`). Transplant verbatim the 26.2-proven pieces: reversed-Z depth choreography, depth-clamped stencil write, stencil-gated screenquad fills, FBO-identity discovery [INV §2.1]. |
| 2 | `PortalContextSwitch` | **REPLACE-BY** `imm_ptl.core.render.MyGameRenderer` + `render.context_management/*` | `MyGameRenderer` (`imm_ptl/core/render/MyGameRenderer.java:52`): public entry `renderWorldNew(WorldRenderInfo, Consumer<Runnable>)` (`:96`), the swap-set body `switchAndRenderTheWorld(ClientLevel, Vec3, Vec3, Consumer<Runnable>, int, boolean)` (`:114`). Context set: `PortalRendering` (`context_management/PortalRendering.java:28`; `pushPortalLayer` `:33`, `getPortalLayer` `:58`, `isRendering` `:62` — replaces the mod's `isRenderingPortal` flag), `WorldRenderInfo` (`context_management/WorldRenderInfo.java:23` — re-keys `withSwitchedWorld`), `RenderStates` (`context_management/RenderStates.java:41`), `FogRendererContext` (`:21`), `CloudContext` (`:17`), `StaticFieldsSwappingManager` (`:18`). The mod's `withSwitchedWorld` (`PortalContextSwitch.java:569-664`) IS the ported swap set translated to 26.2 — keep its mechanics (extract/compileSections pairing, SOG delta feed, fog-buffer/UBO isolation, grid pinning) inside the ported shell [INV §2.2]. Dest-camera math (`:1041-1056`, 1:1 mirror `transformPoint` + axis yaw hack) is replaced by `PortalRenderer.getPortalTransformation` (`PortalRenderer.java:259`) + `TransformationManager` (`imm_ptl/core/render/TransformationManager.java:33`). FBO branch / `compositePortalFbo` / `renderColoredBlocks` / oblique+bob dead code: DELETE — but see AMBIGUOUS A1 (IP retains an FBO renderer as `RenderMode.compatibility`). |
| 3 | `PortalShapeRenderer` | **REPLACE-BY** `imm_ptl.core.render.ViewAreaRenderer` | `public class ViewAreaRenderer` (`imm_ptl/core/render/ViewAreaRenderer.java:23`); mesh built by `buildPortalViewAreaTrianglesBuffer(...)` (`:120`, invoked at `:94`), which calls `portal.renderViewAreaMesh(originRelativeToCamera, vertexOutput)` (`:142`); `Portal.renderViewAreaMesh` (`imm_ptl/core/portal/Portal.java:877`) delegates to `getPortalShape().renderViewAreaMesh(...)` (`:889`) — **shape-polymorphic**: `RectangularPortalShape.renderViewAreaMesh` builds the quad from `UnilateralPortalState.getAxisW()/getAxisH()` scaled by width/2, height/2 via `ViewAreaRenderer.outputFullQuad` (`imm_ptl/core/portal/shape/RectangularPortalShape.java:143-158`); `BoxPortalShape.java:189` and `SpecialFlatPortalShape.java:156` provide different meshes. `Portal.getFourVerticesLocal` is NOT the mesh source — it never appears in `ViewAreaRenderer.java` (grep: zero matches); it feeds `Portal.getOuterFrustumCullingVertices` (`Portal.java:1395-1399`), a frustum-culling API. This replaces the mod's `Direction.Axis` branch geometry (`PortalShapeRenderer.java:515-541` [INV §2.3]); the port MUST go through the `PortalShape` abstraction (box/special-flat portals draw different view-area meshes). Keep the `EDGE_OUTSET` overlap lesson and the flat-plane-not-box lesson (`PortalShapeRenderer.java:438-460`). |
| 4 | `PortalRenderTypes` | **KEEP** | 26.2 pipeline substrate with no IP analog: IP 1.21.3 uses vanilla RenderType + raw GL directly; the submit→prepare→execute rewrite (`MIGRATION_API_MAP.md` headline) forces the reflective pipeline registration (`PortalRenderTypes.java:70-75`) and `drawMesh` immediate-draw replacement (`:288-306`) [INV §2.4]. `PORTAL_FBO_COMPOSITE` deletion is tied to AMBIGUOUS A1. |
| 5 | `VisibleSectionDiscovery` | **PORT-FORWARD** | Faithful port of `imm_ptl.core.render.VisibleSectionDiscovery` (`imm_ptl/core/render/VisibleSectionDiscovery.java:35`): `discoverVisibleSections(...)` (`:45`), `skipFrustumTest` seed semantics (`:143`, `:160`). The mod's folded-in budgeted compile scheduling (`render/VisibleSectionDiscovery.java:170-279` [INV §2.5]) has NO IP counterpart — grep of the IP file for `compile|rebuild|dirty` returns zero matches — see AMBIGUOUS A3. |
| 6 | `FrontClipping` | **PORT-FORWARD** | Port of `imm_ptl.core.render.FrontClipping` (`imm_ptl/core/render/FrontClipping.java:22`): `disableClipping` (`:31`), `updateInnerClipping` (`:49`, `:54`), `setupInnerClipping` (`:67`), `setupOuterClipping(PoseStack, Portal)` (`:122`), plane-equation getters (`:167/:171`). Re-source the plane from the Portal entity (IP passes the `Portal` object at `:122`). The mod's JOML `Vector4f.mul(Matrix4fc)` at `render/FrontClipping.java:147,181` is the COLUMN form M·v, identical to `Matrix4f.transform` — verified empirically against joml-1.10.5 (`migration/verify/current-mod-render.md` V1) — and is correct as-is; the row-vector form is `mulTranspose`, which for a pure rotation yields the INVERSE rotation. Do NOT "fix" the idiom to `mulTranspose` during the port — that would introduce an inverse-rotation clip-plane bug (the geometry-sign bug class). The "row-vector" flag in `IP_DEVIATIONS_ANALYSIS.md:88-89` is itself wrong [INV §2.6]. |
| 7 | `ShaderCodeTransformation` | **PORT-FORWARD** | Port of `imm_ptl.core.render.ShaderCodeTransformation` (`imm_ptl/core/render/ShaderCodeTransformation.java:15`, `transform(CompiledShader.Type, String, String)` `:72`). The mod's version is the 26.2 adaptation (injects at `ShaderManager$CompilationCache.getShaderSource`); IP's is YAML-config-driven (`:59`) — mechanics equivalent, keep the mod's 26.2 wiring unchanged [INV §2.7]. |
| 8 | `DimensionRenderHelper` | **PORT-FORWARD** | Port of `imm_ptl.core.render.context_management.DimensionRenderHelper` (`context_management/DimensionRenderHelper.java:9`; per-dim `lightmapTexture` `:13-24`, tick `:30-31`, cleanup-close `:36-37`). The mod's class is already the 26.2 `Lightmap`/`LightmapRenderState` shape (`render/DimensionRenderHelper.java:63-117` [INV §2.8]) that `render-sub.md` G4 prescribes for the IP class. Verify IP's tick-side weather/skyDarken parity during the port. |
| 9 | `PortalInnerCull` | **PORT-FORWARD** | Port of `imm_ptl.core.render.FrustumCuller` (`imm_ptl/core/render/FrustumCuller.java:22`): `getFlatPortalInnerFrustumCullingFunc` (`:176`), `getFrustumPlanesFromFourVerticesCounterClockwise` (`:156`), `record Frustum4Planes` (`:269`), `testBoxTwoVertices` (`:321`). Re-source the four corners the way IP does: `getFlatPortalInnerFrustumCullingFunc` (`FrustumCuller.java:176`) calls the **FrustumCuller static** `getRectPortalFourVerticesCounterClockwise(portal.getThisSideState())` (`FrustumCuller.java:179`; defined at `:143`, taking `UnilateralPortalState`, order comment `// 2 1 / 3 0` at `:141-142`), then transforms each corner into dest space via `portal.transformPoint(v[i])` (`:183-187`) with a Mirror-flip special case (`:190-195`) — replacing the mod's axis branch (`render/PortalInnerCull.java:83-134` [INV §2.9]). `Portal.getFourVerticesLocal` is not involved anywhere in `FrustumCuller.java`, and `Portal.java` has no `getRectPortalFourVerticesCounterClockwise` member. |
| 10 | `PortalRenderBuffersPool` | **PORT-FORWARD** | Direct port of IP's pool pattern: `MyGameRenderer.acquireRenderBuffersObject()` (`imm_ptl/core/render/MyGameRenderer.java:77`) / `returnRenderBuffersObject(RenderBuffers)` (`:91`), used inside `switchAndRenderTheWorld` (`:191`, returned `:264`). The mod adds the 26.2-required `endFramePooled()` (`render/PortalRenderBuffersPool.java:107-111` [INV §2.10]; GPU-buffer endFrame rule) — keep it. |
| 11 | `SodiumFogOverride` | **KEEP** | Compat shim, no IP analog in `imm_ptl/core/render/` (IP's Sodium interplay is `PortalRendering.shouldEnableSodiumCaveCulling`, consumed at `MyGameRenderer.java:125-127` — a different concern). Orthogonal to the entity migration [INV §2.11]. |
| 12 | `StencilState` | **KEEP** | 26.2 FBO-identity enabler. IP's 1.21.3 analog is the `IEFrameBuffer` duck (`imm_ptl/core/ducks/IEFrameBuffer.java:3-6`: `ip_getIsStencilBufferEnabled` / `ip_setIsStencilBufferEnabledAndReload`, consumed via `IPPortingLibCompat.setIsStencilEnabled` at `RendererUsingStencil.java:87-89`) — that porting-lib hook does not exist on 26.2; the mod's `FrameBufferCache.createFbo` reattach + `lastBoundFbo` capture fills the same role [INV §2.12]. |
| 13 | `PerfTimers` | **KEEP** | Mod diagnostic, no vanilla deps; render-thread-logging discipline [INV §2.13]. |
| 14 | `RenderSpikeMonitor` | **KEEP** | Mod diagnostic (frame-gap telemetry + stall watchdog) [INV §2.14]. |
| 15 | `CrossingTracer` | **KEEP** | Mod diagnostic; explicitly the regression tooling for this migration [INV §2.15]. |
| 16 | `HandLightSmoother` | **KEEP** | Mod extension; no IP analog (no hand-light handling anywhere in `imm_ptl/core/render/` — the only "hand" render concerns in IP are `doRenderHand`/`onBeforeHandRendering`, `PortalRenderer.java:66-69`, and hand-render gating in `MixinGameRenderer.java:202-210`). See AMBIGUOUS A5 (zero-deviation policy on additive extensions). |
| 17 | `CameraTransitionHandler` | **DELETE** | Config-driven position+rotation smoothstep lerp (`render/CameraTransitionHandler.java:18-73`). IP has NO position lerp at crossings; continuity comes from exact rotation math in `TransformationManager.managePlayerRotationAndChangeGravity(Portal)` (`imm_ptl/core/render/TransformationManager.java:135-225`), which is a no-op unless `portal.getRotation() != null` (`:138` — for plain portals IP touches nothing at a crossing), recomputes the player's pitch/yaw so the immediate final camera rotation is unchanged (rotation recompute `:143-203`; the "keep immediate final rotation unchanged, to keep teleportation seamless" comment at `:205-209`) and only starts a `DQuaternion` interpolation (`animationDelta`, progress `:108-127`, applied in `processTransformation` `:88-102`) when the residual delta exceeds 0.1° (`:215-219`). Porting `TransformationManager` (part of the migration) supersedes this class wholesale. |
| 18 | `PortalSlicing` | **DELETE** | Parked experiment (consumer mixin unregistered + pass-through, `GameRendererObliqueClipMixin.java:47-56`, absent from `seamlessportals-common.mixins.json` [INV §2.18]). IP never applies oblique near-plane clipping to any projection: repo-wide case-insensitive grep for `oblique` across `imm_ptl` returns zero matches; IP's only near-side clipping mechanism is `FrontClipping`'s `gl_ClipDistance` plane (`imm_ptl/core/render/FrontClipping.java:22-171`). |
| 19 | `PortalFrameSuppressor` | **DELETE** | Disabled no-op (`isFrameBlock` always false, `render/PortalFrameSuppressor.java:41-51` [INV §2.19]); IP renders the destination as-is — no frame-block suppression concept exists anywhere under `imm_ptl/core/render/` (directory audit, §5). The one live member (`maybeForceDirtyForPortal` self-healing re-dirty) dies with it once no suppression-baked meshes remain. |

---

## 2. Findings that CHANGE or sharpen the inventory's guesses

### 2.1 `MainProjectionBobMixin`: KEEP → **REPLACE** (IP does handle view bob — differently)

The inventory originally marked this KEEP with "re-evaluate vs IP, which does not skip bob" [INV §3.2 — since
corrected to REPLACE-BY, matching this section]. That was wrong in both directions: IP does NOT leave bob
alone, and it does NOT unconditionally kill it either. IP **distance-scales** the world view-bob offset near
portals (verified from source, `migration/verify/current-mod-render.md` V5):

- `MixinGameRenderer` `@ModifyArg`s all three `PoseStack.translate` args inside `bobView`, multiplying by
  `RenderStates.getViewBobbingOffsetMultiplier()` unless the hand is being rendered
  (`imm_ptl/core/mixin/client/render/MixinGameRenderer.java:213-253`).
- The multiplier is `viewBobFactor * getExtraModelViewScaling()`, gated by config
  `IPGlobal.viewBobbingReduce` (default `true`, `imm_ptl/core/IPGlobal.java:121`)
  (`context_management/RenderStates.java:184-196`).
- `viewBobFactor` is driven per pre-render frame from the camera's distance to the nearest portal:
  0 when inside/very near, `minPortalDistance - 1` in the 1..2 band, 1 otherwise, lerped down-fast/up-slow
  (`RenderStates.java:158-181`, `:198-204`, called from `updatePreRenderInfo` `:114`).

The mod's mixin instead no-ops `bobHurt` + `bobView` on the world projection unconditionally
(`mixin/client/MainProjectionBobMixin.java:35-59`). Faithful disposition: **REPLACE-BY** IP's
`bobView` scaling trio + `RenderStates.viewBobFactor` machinery, re-targeted to 26.2's
`renderLevel`-hosted `bobView(CameraRenderState, PoseStack)` (the mod's existing redirect targets prove the
26.2 signatures, `MainProjectionBobMixin.java:39,52`). IP does not touch `bobHurt`. See AMBIGUOUS A2 for the
open re-targeting/behavior question.

### 2.2 `GameRendererFrameCrossingMixin`: KEEP confirmed with the exact IP anchor

IP runs `ClientTeleportationManager.manageTeleportation(false)` in the pre-render block injected at
`GameRenderer.render` HEAD (`imm_ptl/core/mixin/client/render/MixinGameRenderer.java:70-92`), plus the
per-tick `manageTeleportation(true)` from `MixinMinecraft.java:137`
(`ClientTeleportationManager.manageTeleportation(boolean)` itself:
`imm_ptl/core/teleportation/ClientTeleportationManager.java:121`). The mod's `GameRenderer.update` HEAD hook
is the correct 26.2 translation of "before the frame's camera is positioned" (26.2 moved camera setup into
`update`, `render-sub.md` fact 1). This mixin becomes the injection point for the ported
`manageTeleportation` — KEEP.

### 2.3 `GameRendererPortalPrepareMixin`: DELETE, and the re-home target is now identified

IP hosts its per-frame pre-render upkeep in the same render-HEAD block: `RenderStates.updatePreRenderInfo`
(`RenderStates.java:87`), `ClientPortalAnimationManagement.update()`, `manageTeleportation`, and
`MyRenderHelper.earlyRemoteUpload()` (`MixinGameRenderer.java:86-96`; `earlyRemoteUpload` at
`imm_ptl/core/render/MyRenderHelper.java:458`). The mod's stranded upkeep (staged-upload flush, adoption
prune, bridge repaint pump — `render/StencilPortalRenderer.java:194-215` [INV §2.1]) re-homes to the
render-HEAD/update-HEAD pre-render hook the port will have anyway; then this mixin deletes with the FBO
phase-1 path. See AMBIGUOUS A4 for the ordering caveat.

---

## 3. Render mixins (44 files)

IP duck-interface counterparts verified: `IEGameRenderer` (`imm_ptl/core/ducks/IEGameRenderer.java:7-14`),
`IEWorldRenderer` (`ducks/IEWorldRenderer.java:14-41`), `IEMinecraftClient` (`ducks/IEMinecraftClient.java:8-17`),
`IECamera` (`ducks/IECamera.java:7-18`), `IEParticleManager` (`ducks/IEParticleManager.java:5-6`),
`IEFrameBuffer` (`ducks/IEFrameBuffer.java:3-6`).

### 3.1 Stencil substrate (`mixin/client/stencil/`)

| Mixin | Disposition | Confirmation |
|---|---|---|
| `GlBackendMixin` | **KEEP** | 26.2 stencil-bits request; IP's 1.21.3 equivalent is the porting-lib stencil enable (`RendererUsingStencil.java:87-89` via `IEFrameBuffer`), which has no 26.2 form — the GLFW hint + format force IS the replacement [INV §3.1]. |
| `GlConstMixin` | **KEEP** | Same substrate (D32_FLOAT → DEPTH24_STENCIL8) [INV §3.1]. |
| `stencil/RenderTargetMixin` | **KEEP** | "The enabler" — depth reattach as `GL_DEPTH_STENCIL_ATTACHMENT` in the single 26.2 FBO factory [INV §3.1]. |
| `GlStateManagerMixin` | **KEEP** | `lastBoundFbo` capture; feeds `StencilState` [INV §3.1]. |
| `GlTextureViewMixin` | **DELETE** | Unregistered history file; target method removed in 26.2 [INV §3.1]. |

### 3.2 GameRenderer hooks

| Mixin | Disposition | Confirmation |
|---|---|---|
| `GameRendererMixin` | **KEEP** (excise HEAD) | TAIL = IP `MyRenderHelper.lateUpdateLight()` (`imm_ptl/core/render/MyRenderHelper.java:441`) + endFrame rules [INV §3.2]. HEAD `CameraTransitionHandler.tick()` deletes with §1.17. |
| `GameRendererPortalPrepareMixin` | **DELETE** (after re-home, §2.3) | IP analog of the surviving upkeep = pre-render block `MixinGameRenderer.java:86-96`. |
| `GameRendererFrameCrossingMixin` | **KEEP** | Becomes the `manageTeleportation` injection point (§2.2). |
| `GameRendererLightmapMixin` | **KEEP** | 26.2 lightmap-binding override; serves the ported `DimensionRenderHelper` output (IP installs per-dim lightmaps via `IEGameRenderer.ip_setLightmapTextureManager`, `ducks/IEGameRenderer.java:8` — the mod's HEAD-cancellable `lightmap()` override is the 26.2 delivery for the same role) [INV §3.2]. |
| `GameRendererHandLightMixin` | **KEEP** | Feeds `HandLightSmoother` (§1.16 / A5). |
| `MainProjectionBobMixin` | **REPLACE-BY** IP bobView scaling (§2.1) | `MixinGameRenderer.java:213-253` + `RenderStates.java:158-204`. |
| `GameRendererObliqueClipMixin` | **DELETE** | Unregistered pass-through; dies with `PortalSlicing` (§1.18). |
| `compat/SodiumFogOverrideMixin` | **KEEP** | Pair of §1.11. |
| `GameRendererAccessorMixin` | **KEEP** | The mod's `IEGameRenderer` (`ducks/IEGameRenderer.java:7-14`: setLightmap/setCamera parallels; the mod adds 26.2-only members — `mainRenderTarget` moved off `Minecraft`, `globalSettingsUniform`, `fogRenderer`) [INV §3.2]. |

### 3.3 LevelRenderer / ViewArea / SOG hooks

| Mixin | Disposition | Confirmation |
|---|---|---|
| `LevelRendererAccessorMixin` | **KEEP** | The mod's `IEWorldRenderer` (`ducks/IEWorldRenderer.java:14-41`: `ip_getBuiltChunkStorage`, `ip_get/setRenderBuffers`, `portal_getFrustum`, `portal_getChunkInfoList` — same set, 26.2 members added) [INV §3.3]. |
| `LevelRendererCompileSectionsMixin` | **KEEP** | 26.2 teleport-crash guard, no IP analog needed (26.2-only compile queue) [INV §3.3]. |
| `LevelRendererEntityVisibilityMixin` | **KEEP** | 26.2 mesh-upload fade gate bypass during portal render [INV §3.3]. |
| `LevelRendererBlockOutlineMixin` | **KEEP** | 26.2 depth-write-vs-stencil ordering fix [INV §3.3]. |
| `LevelRendererCullTerrainMixin` | **DELETE** | Dead on 26.2 — target `cullTerrain` removed (grep zero matches in `mc262-ref`; cull moved into `LevelExtractor.extract`/`applyFrustum`, `mc262-ref LevelExtractor.java:373`) [INV §3.3]. |
| `LevelRendererDiagMixin` | **DELETE** | Unregistered; target `update(Camera)` removed in 26.2 (`MIGRATION_API_MAP.md` LevelRenderer table) [INV §3.3]. |
| `ViewAreaInvokerMixin` | **KEEP** | 26.2: `ViewArea.getRenderSection(long)` is protected (`MIGRATION_API_MAP.md` ViewArea section); needed by the BFS. |
| `SectionOcclusionGraphAccessorMixin` | **KEEP** | Bridge/rebuild checks [INV §3.3]. |
| `SectionOcclusionGraphPartialUpdateSkipMixin` | **KEEP** | The 26.2 freeze/limbo fix; IP-side rationale = vanilla occlusion cannot drive hand-fed secondaries (six-attempt history, `PortalContextSwitch.java:141-226`) [INV §3.3]. |
| `SkyRendererTargetMixin` | **KEEP** | 26.2 SkyRenderer target-capture fix ("the curtain") [INV §3.3]. |
| `SectionCompilerMixin` | **DELETE** | Swirl branch dies with block portals (entity portals leave no portal blocks to hide); obsidian branch already dead with `PortalFrameSuppressor` (§1.19) [INV §3.3]. |

### 3.4 LevelExtractor hooks (26.2-only layer)

All four **KEEP** — `LevelExtractorFlashBridgeMixin`, `LevelExtractorCreateRegionBudgetMixin`,
`LevelExtractorAccessor`, `ClientLevelExtractorAccessor`. The `LevelExtractor` class does not exist in
1.21.3 (26.2 render split, `MIGRATION_API_MAP.md` LevelExtractor section); these are pure 26.2 substrate the
ported IP renderer depends on (extract/compile pairing, promote/demote re-pointing) [INV §3.4].

### 3.5 Particle / debug / misc client hooks

| Mixin | Disposition | Confirmation |
|---|---|---|
| `ParticleEnginePortalSkipMixin` | **KEEP** | 26.2 shared-render-state guard [INV §3.5]. |
| `ParticleEngineAccessorMixin` | **KEEP** | IP `IEParticleManager.ip_setWorld(ClientLevel)` (`ducks/IEParticleManager.java:5-6`) — same raw-rebind pattern [INV §3.5]. |
| `QuadParticleGroupMixin` | **KEEP** | 26.2-mechanics cull (stencil doesn't survive particle pipeline re-binds); re-source the quad test from Portal entity geometry [INV §3.5]. |
| `DebugRendererPortalSkipMixin` | **KEEP** | Consequence of the captured-frustum trick [INV §3.5]. |
| `LivingEntityRendererDiagMixin` | **DELETE** | Diagnostic for the mirror-entity system the migration replaces wholesale (IP renders cross-portal entities via `CrossPortalEntityRenderer`, `imm_ptl/core/render/CrossPortalEntityRenderer.java:41`) [INV §3.5]. |
| `ClearSkipMixin` | **DELETE** | Empty documentation placeholder [INV §3.5]. |
| `MinecraftRenderTargetMixin` | **DELETE** | Unregistered; field moved to GameRenderer in 26.2 [INV §3.5]. |
| `MinecraftAccessorMixin` | **KEEP** | IP `IEMinecraftClient` (`ducks/IEMinecraftClient.java:8-17`: `ip_setFrameBuffer`, `ip_setWorldRenderer`, `ip_setRenderBuffers`) [INV §3.5]. |
| `CameraInvokerMixin` | **KEEP** | IP `IECamera` (`ducks/IECamera.java:7-18`: `ip_resetState`, `portal_setPos`, …); the mod adds the 26.2 `capturedFrustum`/`initialized` accessors (see `render-sub.md` G1 for why `initialized` matters) [INV §3.5]. |
| `ShaderManagerCompilationCacheMixin` | **KEEP** | 26.2 delivery of `ShaderCodeTransformation` (§1.7). |
| `GlCommandEncoderClipMixin` | **KEEP** | 26.2 per-draw clip-plane upload; pairs with `FrontClipping` (§1.6). |
| `MinecraftMixin` | **KEEP** | Session teardown (close/kick paths) [INV §3.5]. |
| `ClientLevelMixin` | **KEEP** (excise scan) | Particle bypass + lifecycle stay; the `onChunkLoaded` portal-block SCAN deletes with block portals — IP portals are synced entities, not scanned blocks [INV §3.5]. |
| `ApplyLightDataGuardMixin` | **KEEP** | Chunk-feed light infrastructure [INV §3.5]. |
| `ChunkLightLambdaGuardMixin` | **KEEP** | Same [INV §3.5]. |

---

## 4. AMBIGUOUS — the questions that decide them

**A1. The mirror-FBO fallback path (PortalContextSwitch FBO branch + `PORTAL_FBO_COMPOSITE` + `compositePortalFbo` + `portalFbos` pool) — provisionally DELETE.**
IP does NOT treat the FBO renderer as dead code: `IPGlobal.renderMode` is a four-way config enum
`{normal, compatibility, debug, none}` (`imm_ptl/core/IPGlobal.java:147-152`), `IPCGlobal` holds all four
renderer instances plus the active-renderer slot (`imm_ptl/core/IPCGlobal.java:16-20`: `renderer`,
`rendererUsingStencil`, `rendererUsingFrameBuffer`, `rendererDummy`, `rendererDebug`), both `RendererUsingStencil` and
`RendererUsingFrameBuffer` are constructed at client init (`imm_ptl/core/IPModMainClient.java:81-84`), and
`PortalRenderer.switchToCorrectRenderer()` switches on the mode per frame
(`renderer/PortalRenderer.java:310-350`; `RendererUsingFrameBuffer` at
`renderer/RendererUsingFrameBuffer.java:23`, backed by `SecondaryFrameBuffer.java:9`).
**Deciding question:** does COMPLETE IP fidelity require porting `RendererUsingFrameBuffer` +
`RendererDummy` + `RendererDebug` + the renderMode config (in which case the mod's FBO machinery is
*refactored into* `RendererUsingFrameBuffer` instead of deleted), or is the stencil renderer alone in scope?
This single decision moves ~600 LOC between REPLACE and DELETE.

**A2. World view-bob handling — provisionally REPLACE (§2.1).**
IP's mechanism is a portal-distance-scaled multiplier on `bobView`'s translate only
(`MixinGameRenderer.java:213-253`, `RenderStates.java:158-204`); the mod unconditionally no-ops `bobHurt` +
`bobView` (`MainProjectionBobMixin.java:35-59`). 26.2 re-targeting is nontrivial: IP `@ModifyArg`s
`PoseStack.translate(FFF)` *inside* `bobView`, and 26.2's `bobView(CameraRenderState, PoseStack)` body must
be read to find the equivalent injection; `bobHurt` reduction has no IP precedent at all.
**Deciding question:** port IP's `viewBobbingReduce` verbatim (bob returns when away from portals — a
user-visible behavior change from today's build), or keep the no-op as an accepted deviation?

**A3. `VisibleSectionDiscovery`'s folded-in compile scheduling — provisionally PORT-FORWARD as-is.**
IP's class only *discovers* (zero compile/rebuild/dirty references in
`imm_ptl/core/render/VisibleSectionDiscovery.java`); the mod's second entry point also *schedules
compiles* with a time budget (`render/VisibleSectionDiscovery.java:170-279`). The extension exists because
26.2's one-shot dirty-flag + private `compileSections` model strands sections otherwise (the OW-holes root
cause [INV knowledge item 7]).
**Deciding question:** is this classified as required 26.2 adaptation (no vanilla path compiles sections for
a hand-fed secondary renderer) or as a deviation to re-derive from IP's `ForceMainThreadRebuild`
(`imm_ptl/core/render/ForceMainThreadRebuild.java:12`, `onPreRender()` invoked at `RenderStates.java:121`)?
Needs a one-time comparative read of `ForceMainThreadRebuild` during the port.

**A4. `GameRendererPortalPrepareMixin` deletion order — provisionally DELETE.**
The upkeep it hosts under `STENCIL_DIRECT` (staged-upload flush, adoption prune, bridge repaint pump,
`render/StencilPortalRenderer.java:194-215`) must be re-homed to the ported pre-render block
(IP anchor: `MixinGameRenderer.java:86-96`) BEFORE the mixin is removed, and the flush specifically needs a
GPU-upload-safe point outside the framegraph — verify the 26.2 render-HEAD position satisfies that
(the current comment says renderLevel HEAD was chosen for exactly this,
`StencilPortalRenderer.java:190-194`).
**Deciding question:** which 26.2 frame position (update HEAD vs render HEAD vs renderLevel HEAD) is both
outside the framegraph and before extract? Must be settled in the port spec, not ad hoc.

**A5. `HandLightSmoother` (+ its `GameRendererHandLightMixin` feed) — provisionally KEEP.**
Additive mod extension with no IP analog (§1.16).
**Deciding question:** does the zero-deviation directive ban additive comfort features that IP lacks, or
only alterations to IP-specified behavior? User call; if banned, both files move to DELETE.

---

## 5. IP render classes the port ADDS (no current-mod counterpart in this slice)

For planning completeness — these appear in the IP render tree (directory read of
`imm_ptl/core/render/`) and are not replacements for any current class:
`PortalEntityRenderer` (the `EntityRenderer<Portal>`, `PortalEntityRenderer.java:19`),
`CrossPortalEntityRenderer` (`CrossPortalEntityRenderer.java:41`), `TransformationManager`
(`TransformationManager.java:33`), `MyRenderHelper` (`MyRenderHelper.java:61`; `renderScreenTriangle`
overloads `:216-271`, `lateUpdateLight` `:441` — already half-ported), `renderer/RendererDummy`,
`renderer/RendererDebug`, `renderer/RendererUsingFrameBuffer` (A1), `SecondaryFrameBuffer`
(`SecondaryFrameBuffer.java:9`), `ImmPtlViewArea`, `CrossPortalViewRendering`, `GuiPortalRendering`,
`OverlayRendering`, `LoadingIndicatorRenderer`, `ForceMainThreadRebuild` (A3), `GlQueryObject`/`QueryManager`,
`context_management/{RenderStates, FogRendererContext, CloudContext, StaticFieldsSwappingManager,
WorldRenderInfo, PortalRendering}` (§1.2). Their vanilla-26.2 API fates are mapped in
`migration/api-map/render-sub.md`.

---

## 6. Tally

| Disposition | Render classes | Mixins | Total |
|---|---|---|---|
| KEEP | 7 (§1.4, §1.11-1.16) | 34 | 41 |
| PORT-FORWARD | 6 (§1.5-1.10) | 0 | 6 |
| **KEEP + PORT-FORWARD (sameCount)** | **13** | **34** | **47** |
| **REPLACE (changedCount)** | 3 (§1.1-1.3) | 1 (`MainProjectionBobMixin`, §2.1) | **4** |
| **DELETE (goneCount)** | 3 (§1.17-1.19) | 9 (`GlTextureViewMixin`, `GameRendererPortalPrepareMixin`, `GameRendererObliqueClipMixin`, `LevelRendererCullTerrainMixin`, `LevelRendererDiagMixin`, `SectionCompilerMixin`, `LivingEntityRendererDiagMixin`, `ClearSkipMixin`, `MinecraftRenderTargetMixin`) | **12** |
| Total audited | 19 | 44 | 63 |

**Completeness note** (`migration/verify/current-mod-render.md` V7): the 44-mixin census misses two
`mixin/client/` files that appeared in neither the audited rows nor the inventory's named-exclusions list —
the directory holds 56 files (incl. `compat/` + `stencil/`): 44 audited + 10 named exclusions + these 2:

- `ClientLevelChunkSourceAccessor` — `@Mutable` accessor for the private final `ClientLevel.chunkSource`
  (`ClientLevelChunkSourceAccessor.java:15-21`), used by `PortalWorldManager.createRenderer` to install the
  unbounded `SeamlessClientChunkMap` on portal SECONDARY levels. Render-view world plumbing, arguably
  in-slice; KEEP while its consumer exists (same swap-point class as `MinecraftAccessorMixin` /
  `LevelExtractorAccessor`). Now accounted for in [INV §3.5].
- `LivingEntitySprintCancelDiagMixin` — crossing-slice sprint-cancel diagnostic
  (`LivingEntitySprintCancelDiagMixin.java:24-49`); dispositioned with the crossing slice, not here.

Changes vs the inventory's guesses: `MainProjectionBobMixin` KEEP → REPLACE (IP's `viewBobbingReduce`
mechanism found, §2.1); `CameraTransitionHandler`'s DELETE now has a concrete IP supersession citation
(`TransformationManager.managePlayerRotationAndChangeGravity`, §1.17); the FBO-path DELETE is downgraded to
AMBIGUOUS A1 (IP ships `RendererUsingFrameBuffer` as a live config mode). Everything else confirmed.
