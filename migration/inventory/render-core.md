# IP Subsystem Inventory — Render Core (`qouteall.imm_ptl.core.render.*`, root files)

**Source of truth:** `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/imm_ptl/core/render/` (branch `1.21.3`, commit `6831a3a "WIP update to 1.21.3"`).
All paths below that start with `render/`, `mixin/`, `ducks/`, `portal/`, or `context_management/` are relative to `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/imm_ptl/core/`.

> **WIP-branch caveat (important):** this IP checkout is a *work-in-progress* 1.21.3 migration. One file in this slice is stale and would not compile as-is: `render/ViewAreaRenderer.java:11` imports `net.minecraft.client.renderer.ShaderInstance` (removed in 1.21.2+) and `render/ViewAreaRenderer.java:84` reads `MyRenderHelper.portalAreaShader`, a field that is commented out (`render/MyRenderHelper.java:168`). The migrated replacement is the registered `ShaderProgram MyRenderHelper.PORTAL_AREA` (`render/MyRenderHelper.java:71-75`); the already-migrated usage pattern to copy is `drawPortalAreaWithFramebuffer` (`render/MyRenderHelper.java:184-202`: `RenderSystem.setShader(ShaderProgram)` then operate on the returned/current `CompiledShaderProgram`). Everything else in the slice is consistent with 1.21.3 (`CompiledShaderProgram`, `CoreShaders`, `DeltaTracker`, framegraph-lambda mixin targets).

---

## 1. Overview

This slice is the **client-side world-switching render engine** of Immersive Portals: everything needed to draw *another dimension* (or the same dimension from another camera) inside the current frame, and to draw the portal's visible window itself. It sits between the high-level portal renderers (`render/renderer/PortalRenderer`, `RendererUsingStencil`, `RendererUsingFrameBuffer` — a separate slice) and vanilla's `GameRenderer`/`LevelRenderer`: the renderer subpackage decides *which* portals to render and *how* (stencil vs FBO), then calls into this slice to (a) swap `Minecraft`'s world/renderer/camera/buffers state and re-enter `GameRenderer.renderLevel` for the destination world (`MyGameRenderer`), (b) rasterize the portal's view-area mesh to establish stencil/depth/color regions (`ViewAreaRenderer`, `MyRenderHelper`), (c) clip geometry against the portal plane on both sides (`FrontClipping` + shader patching via `ShaderCodeTransformation`), and (d) render entities that straddle a portal in both dimensions with correct clipping (`CrossPortalEntityRenderer`).

The slice also owns the per-secondary-dimension render bookkeeping that vanilla assumes is singleton: an unbounded, presets-cached section grid replacing vanilla's camera-centered `ViewArea` (`ImmPtlViewArea`), a single-threaded BFS visible-section discovery replacing vanilla's multithreaded occlusion graph during portal passes (`VisibleSectionDiscovery`), portal-frustum culling injected into vanilla/Sodium frustum tests (`FrustumCuller`), cross-dimension light-engine and mesh-upload pumping (`MyRenderHelper.lateUpdateLight` / `earlyRemoteUpload`), camera-rotation continuity across rotating portals (`TransformationManager`), GL occlusion queries for portal visibility prediction (`GlQueryObject`, `QueryManager`), and render-to-texture portal views for GUI use (`GuiPortalRendering`). Everything here is client-only (`@Environment(EnvType.CLIENT)` or client-only usage).

---

## 2. Class-by-class inventory

### 2.1 `MyGameRenderer` — the world-switch renderer (335 LOC, client)
`render/MyGameRenderer.java`

**Responsibility:** save the entire mutable render-context of `Minecraft`, install the destination world's context, re-invoke vanilla `GameRenderer.renderLevel`, then restore everything. This is the single entry point through which *all* portal-world rendering flows.

**Key public API:**
- `static void renderWorldNew(WorldRenderInfo worldRenderInfo, Consumer<Runnable> invokeWrapper)` (`:96-112`) — pushes the `WorldRenderInfo` onto the global stack, calls `switchAndRenderTheWorld`, pops. `invokeWrapper` lets the caller wrap the actual render invocation (e.g. stencil renderer sets GL state around it). Called from `render/renderer/PortalRenderer.java:252`, `compat/iris_compatibility/IrisPortalRenderer.java:310`, `compat/iris_compatibility/IrisCompatibilityPortalRenderer.java:129`.
- `static void resetFogState()` (`:291-307`), `static void updateFogColor()` (`:309-317`), `static void resetDiffuseLighting()` (`:323-332`) — `@IPVanillaCopy` re-implementations of fog/lighting setup fragments of `LevelRenderer.renderLevel`, re-run mid-frame after a dimension switch (called from `mixin/client/render/MixinLevelRenderer.java:150-153`).
- `static int vanillaTerrainSetupOverride` (`:66`) — frame counter; when >0 the *outer* (non-portal) world render also uses IP's single-threaded section discovery, because vanilla's multithreaded discovery produces garbage on the first frame after a teleport. Set to 1 by `teleportation/ClientTeleportationManager.java:423,452`; consumed in `mixin/client/render/MixinLevelRenderer.java:281-292`.
- `static boolean enablePortalCaveCulling` (`:68`); `static void init()` (`:70-74`) — registers secondary-render-buffer cleanup. **Note: `MyGameRenderer.init()` has zero call sites on this branch** (grep over `src/`), so that cleanup registration is dead code in the WIP checkout.

**State:** `Stack<RenderBuffers> secondaryRenderBuffers` pool + `usingRenderBuffersObjectNum` (`:60-61`) — portal passes get their own `RenderBuffers` so entity vertex building can't collide with the outer pass ("Already Building" hazard, comment `:196-201`).

**Dependencies (IP):** `ClientWorldLoader` (per-dim `LevelRenderer` + `DimensionRenderHelper`), `context_management` (`WorldRenderInfo`, `PortalRendering`, `RenderStates`, `FogRendererContext`, `DimensionRenderHelper`), `VisibleSectionDiscovery` (list pool), ducks `IEGameRenderer`/`IEMinecraftClient`/`IEParticleManager`/`IEWorldRenderer`/`IERenderSystem`/`IESectionRenderDispatcher`, `BlockManipulationClient`, Sodium/Iris interfaces.

**Vanilla touchpoints:** see mechanism §3.1 and the touchpoint table §4.

### 2.2 `FrontClipping` — portal-plane clipping state + uniform upload (226 LOC, client)
`render/FrontClipping.java`

**Responsibility:** central holder of the single active clip plane; computes the camera-relative plane equation, enables/disables `GL_CLIP_PLANE0` (= `GL_CLIP_DISTANCE0`, enum 0x3000), and pushes the equation into the current shader's injected `iportal_ClippingEquation` uniform.

**Key public API:**
- `setupInnerClipping(Plane clipping, Matrix4f modelView, double adjustment)` (`:67-88`) — clip destination-side geometry that is *behind* the destination portal plane.
- `setupOuterClipping(PoseStack matrixStack, Portal portal)` (`:122-140`) — clip source-side geometry that is *past* the portal plane (only used for cross-portal entities).
- `updateInnerClipping(PoseStack|Matrix4f)` (`:49-64`) — convenience: active plane from `PortalRendering.getActiveClippingPlane()`, or disable.
- `disableClipping()` (`:31-38`), `updateClippingEquationUniformForCurrentShader(boolean isRenderingEntities)` (`:175-208`), `unsetClippingUniform()` (`:210-225`), getters for both equation forms (`:167-173`).
- `public static boolean isClippingEnabled` (`:27`); `public static final double ADJUSTMENT = 0.01` (`:29`).

**State:** `double[] activeClipPlaneEquationBeforeModelView` (world coords, camera-relative) and `activeClipPlaneAfterModelView` (view coords) (`:24-25`). **The uniform upload uses the BEFORE-model-view equation** (`:191`), i.e. IP's injected shader code evaluates the plane in camera-relative *world* space, not view space.

**Vanilla touchpoints:** `RenderSystem.getShader()` → `CompiledShaderProgram` (`:182`), duck `IEShader.ip_getClippingEquationUniformLocation()` (populated by `mixin/client/render/shader/MixinShaderInstance.java:28-38` — `@Mixin(CompiledShaderProgram)`, `Uniform.glGetUniformLocation(programId, "iportal_ClippingEquation")` after `setupUniforms`), raw `GL11.glEnable/glDisable(GL_CLIP_PLANE0)`, `GL20.glUniform4f`.

**Mod status: ALREADY PORTED (adapted).** `Portal 26.2/common/src/main/java/com/warwa/seamlessportals/render/FrontClipping.java` — note the mod's port stores the plane in **VIEW space** (its doc header, lines 14-27) while IP uploads the **before-model-view** (camera-relative world-space) equation (`render/FrontClipping.java:191`); an entity-portal port with "zero deviation" must reconcile this (IP's convention is the ground truth).

### 2.3 `ViewAreaRenderer` — portal view-area mesh drawing (224 LOC, client)
`render/ViewAreaRenderer.java`

**Responsibility:** rasterize the portal's window shape (a triangle mesh in camera-relative coordinates) with configurable color/depth/cull/clip write masks. Used by every portal renderer to (a) punch the stencil/depth hole and (b) draw the fog-colored backdrop.

**Key public API:**
- `renderPortalArea(Portal portal, Vec3 fogColor, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, boolean doFaceCulling, boolean doModifyColor, boolean doModifyDepth, boolean doClip)` (`:25-118`). Callers: `render/renderer/RendererUsingStencil.java:190,237`, `RendererUsingFrameBuffer.java:110`, `RendererDebug.java:82`, plus 4 Iris-compat callers.
- `buildPortalViewAreaTrianglesBuffer(Vec3 fogColor, Portal portal, Vec3 cameraPos, float partialTick)` (`:120-145`) — builds + immediately draws the triangles (`BufferUploader.draw`, `:144`).
- `outputTriangle(...)` (`:147-163`) and `outputFullQuad(...)` (`:211-223`) — mesh emission helpers used by the `portal/shape/PortalShape` implementations (e.g. `portal/shape/RectangularPortalShape.java:155-157`).

**Mesh source:** `portal.renderViewAreaMesh(originRelativeToCamera, vertexOutput)` delegates to `portal.getPortalShape().renderViewAreaMesh(...)` (`portal/Portal.java:877-889`) — the geometry itself lives in the portal-shape slice; this class owns only vertex emission + draw state.

**STALE (see caveat above):** `:84-101` uses removed `ShaderInstance` API and the commented-out `MyRenderHelper.portalAreaShader` field.

### 2.4 `CrossPortalEntityRenderer` — entities halfway through portals (428 LOC, client)
`render/CrossPortalEntityRenderer.java`

**Responsibility:** track entities colliding with portals (`WeakHashMap<Entity,Object> collidedEntities`, `:45`), clip their "normal" render against the portal plane, render their transformed **projection** in the destination dimension, and gate which entities are visible during a portal pass.

**Key public API (all static; call sites are mixins):**
- `init()` (`:51-57`) — registers `POST_CLIENT_TICK_EVENT` pruning (`:63-68`) and cleanup events.
- `onEntityTickClient(Entity)` (`:70-78`) — feeds the collided set; called from `IPMcHelper.java:107` (per client entity tick).
- `onBeginRenderingEntitiesAndBlockEntities(Matrix4f modelView)` (`:80-89`) / `onEndRenderingEntitiesAndBlockEntities(PoseStack)` (`:98-108`) — bracket vanilla's entity pass; called from `mixin/client/render/MixinLevelRenderer.java:135,171` (injected into the framegraph main-pass lambda `method_62214`).
- `beforeRenderingEntity(Entity, PoseStack)` / `afterRenderingEntity(Entity)` (`:110-143`) — per-entity outer clipping; called from `MixinLevelRenderer.java:357,361`.
- `shouldRenderEntityNow(Entity)` (`:369-402`) — visibility gate, consumed by `mixin/client/render/MixinEntityRenderDispatcher.java:28`.
- `shouldRenderPlayerDefault()` (`:339-367`) — used by `mixin/client/render/MixinCamera.java:82`.
- `shouldRenderPlayerNormally(Entity)` (`:404-418`), `getRenderingCameraPos(Entity)` (`:420-427`).
- Flags `isRenderingEntityNormally` / `isRenderingEntityProjection` (`:47-49`) — read by `mixin/client/render/MixinRenderSystem_Clipping.java:40-44` to re-upload the clip uniform on *every* `RenderSystem.setShader` call.

**Iris opt-out:** the whole projection mechanism is disabled when Iris is present (`isCrossPortalRenderingEnabled`, `:91-96`).

**Vanilla touchpoints:** `MultiBufferSource.BufferSource.endBatch()` for forced draw-call splits (`:123,139,208,308`), private `LevelRenderer.renderEntity` via duck `ip_myRenderEntity(Entity, camX, camY, camZ, partialTick, PoseStack, MultiBufferSource)` (`ducks/IEWorldRenderer.java:19-27`), `Entity.position()/getEyePosition(float)/isRemoved()/level()`, `LocalPlayer`, `client.options.getCameraType().isFirstPerson()`, `PoseStack` push/translate/scale/mulPose/pop.

### 2.5 `MyRenderHelper` — shader registration + FB blit + cross-dim pumps (621 LOC, client)
`render/MyRenderHelper.java`

**Responsibility:** grab-bag of render utilities that other slices call constantly.

**Key public API:**
- Custom core-shader registrations (`:65-81`): `BLIT_SCREEN_NOBLEND` (`DefaultVertexFormat.BLIT_SCREEN`), `PORTAL_AREA` (`POSITION_COLOR`), `PORTAL_DRAW_FB_IN_AREA` (`POSITION_COLOR`) — registered via `CoreShadersAccessor.register(name, format, ShaderDefines.EMPTY)` (accessor mixin into `CoreShaders`); the actual GLSL lives in the mod assets under namespace-corrected paths.
- `drawPortalAreaWithFramebuffer(Portal, RenderTarget, Matrix4f modelView, Matrix4f projection)` (`:172-214`) — samples a secondary FBO's color texture (`bindSampler("DiffuseSampler", target.getColorTextureId())`, `:188`; `w`/`h` uniforms `:189-192`) and draws it masked to the portal's view-area mesh — the composite step of the FBO rendering path.
- `renderScreenTriangle(...)` (`:216-301`) — full-screen quad in identity MV/ortho projection with `CoreShaders.POSITION_COLOR` (used to overwrite color inside stencil regions).
- `drawScreenFrameBuffer` / `drawFramebuffer*` family (`:306-435`) — `@IPVanillaCopy` of `RenderTarget.blitToScreen` with explicit control of blend (documented pre-multiplied-alpha blend func `ONE, ONE_MINUS_SRC_ALPHA, ZERO, ONE`, `:388-393`) and alpha-write mask.
- **`lateUpdateLight()` (`:441-451`)** — for every client world whose dimension was **not** rendered this frame, run `world.getChunkSource().getLightEngine().runLightUpdates()`. Header comment `:437-440`: it removes light sections marked for removal (avoiding a light-data leak and wrong removal on chunk reload) and "should not run before world rendering or the smooth lighting may become abnormal in section edge". Called at frame end from `mixin/client/render/MixinGameRenderer.java:136-141`, gated by `IPCGlobal.lateClientLightUpdate`. **Mod status: ALREADY PORTED** (commit 3a2c14e; `GameRendererMixin.render@TAIL` → `PortalWorldManager.lateUpdateSecondaryLight()`).
- `earlyRemoteUpload()` (`:458-469`) — for every dimension other than `client.level`'s, call `worldRenderer.getSectionRenderDispatcher().uploadAllPendingUploads()`; without it "the future created in `SectionRenderDispatcher#uploadSectionLayer` may never complete" (`:453-457`). Called at `GameRenderer.render` HEAD (`MixinGameRenderer.java:94-96`), gated `IPCGlobal.earlyRemoteUpload`.
- `applyMirrorFaceCulling()` / `recoverFaceCulling()` (`:471-477`) — raw `glCullFace(GL_FRONT/GL_BACK)`; mirrors flip winding.
- `clearAlphaTo1(RenderTarget)` (`:479-485`), `restoreViewPort()` (`:487-495`), `transformFogDistance(float)` (`:497-515`) — fog killed (×23333) when `WorldRenderInfo.isFogEnabled()` is false or the rendering portal is fuse-view.

### 2.6 `TransformationManager` — camera rotation continuity + isometric (322 LOC, client)
`render/TransformationManager.java`

**Responsibility:** keeps the camera rotation visually continuous when the player crosses a **rotating** portal, and hosts mirror/isometric matrix helpers. Rotation composition order is documented at `:23-31`: `finalRot = rawCameraRotation * gravity * animationDelta * portalRot` (right side applies first).

**Key public API:**
- `managePlayerRotationAndChangeGravity(Portal portal)` (`:135-224`) — on crossing a portal with rotation: computes the post-teleport raw pitch/yaw so the *immediate final rotation* is unchanged, writes them **directly into `LocalPlayer` fields** including bob history (`player.setYRot/setXRot`, `yRotO/xRotO`, `yBob/xBob/yBobO/xBobO`, `:195-203` — these public-field writes are a version-sensitive touchpoint), handles GravityChanger interop, then starts a 1-second interpolation (`:215-220`) of an `animationDelta` quaternion back to identity (`sin` easing, `mapProgress`, `:128-132`). Finally re-runs `camera.setup(...)` (`:230-239`).
- `processTransformation(Camera, Matrix4f)` (`:97-102`) — applies the current animation delta plus `WorldRenderInfo.applyAdditionalTransformations` (the per-layer portal camera transform); hooked into vanilla by `MixinGameRenderer.wrapCameraTransformation` (`mixin/client/render/MixinGameRenderer.java:304-317`) wrapping `Matrix4f.rotation(Quaternionfc)` inside `renderLevel`.
- `getMirrorTransformation(Vec3 normal)` (`:241-255`) — `new Matrix4f().reflection(x, y, z, 0)`.
- `getIsometricProjection()` (`:258-283`), `RemoteCallables.enable/disableIsometricView` (`:288-301`, toggles `client.smartCull`), `getIsometricAdjustedCameraPos(Camera)` (`:309-321`), flags `isIsometricView`, `isCalculatingViewBobbingOffset` (`:43,285`).

### 2.7 `GuiPortalRendering` — render a world into an off-screen RenderTarget (125 LOC, client)
`render/GuiPortalRendering.java`

**Responsibility:** API for mods/GUI to request "render world X from camera Y into this `RenderTarget`" once per frame.
- `submitNextFrameRendering(WorldRenderInfo, RenderTarget)` (`:91-109`) — queues; auto-resizes the target to the main FB size (`:102-106`).
- `_onGameRenderEnd()` (`:112-119`) — executes all queued tasks after the frame (`MixinGameRenderer.java:134`). Each task (`renderWorldIntoFrameBuffer`, `:38-87`): resets `RenderStates.basicProjectionMatrix`, re-poses the original camera via duck `IECamera.ip_resetState(cameraPos, world)` (`:46-48`), **swaps `Minecraft.mainRenderTarget`** via duck `IEMinecraftClient.ip_setFrameBuffer` (`:59`), optionally pre-clears with 0 alpha when `!doRenderSky` (`:61-66`), then drives the active portal renderer: `IPCGlobal.renderer.prepareRendering(); invokeWorldRendering(worldRenderInfo); finishRendering()` (`:70-74`), restores and rebinds.
- `getRenderingFrameBuffer()` / `isRendering()` (`:30-36`) — read by other slices to know a GUI-portal pass is active.

### 2.8 `ImmPtlViewArea` — presets-cached section storage (490 LOC, client)
`render/ImmPtlViewArea.java`

**Responsibility:** replaces vanilla `ViewArea` (extends it, `:37`) for every `LevelRenderer` when `IPCGlobal.useHackedChunkRenderDispatcher` (installed via `@Redirect` of the `new ViewArea` in `LevelRenderer.allChanged`, `mixin/client/render/MixinLevelRenderer.java:322-341`). Vanilla's `ViewArea` is a fixed camera-centered torus array; portals need render sections at arbitrary remote locations, so this maintains:
- `Long2ObjectOpenHashMap<Column> columnMap` — unbounded per-chunk-column `RenderSection[]` (`:60`), sections created lazily via the **inner-class constructor** `factory.new RenderSection(0, x, y, z)` (`:260-263`).
- `Long2ObjectOpenHashMap<Preset> presets` — per-camera-chunk snapshot arrays with vanilla's wrap-around layout, memoized by camera chunk pos (`:61`, built `:183-210` mirroring vanilla `repositionCamera` math); `repositionCamera` override just installs the preset (`:140-164`).
- Purge lifecycle: ticked from `POST_CLIENT_TICK_EVENT` (`:83-94`); presets dropped after 20 s inactive (3 s under memory pressure via `GcMonitor`), columns dropped 5 s after losing all preset marks, buffers released ≤100/frame on the `PRE_GAME_RENDER_TASK_LIST` (`:271-352`).
- `rawFetch(cx, cy, cz, timeMark)` (`:458-471`) — create-on-demand accessor used by BFS discovery; `rawGet` non-creating (`:474-489`); `provideBuiltChunkByChunkPos` for `setDirty` (`:166-178`); `getRenderSectionAt(BlockPos)` override with wrap-around indexing, `@Nullable`, "may be accessed from another thread" (`:431-455`); `onChunkUnload` → duck `IERenderSection.portal_fullyReset()` (`:405-413`); `releaseAllBuffers` override (`:122-132`).

**Vanilla inherited-field contract:** relies on `ViewArea`'s `sectionGridSizeX/Y/Z`, `sections`, `level` fields and overridable `createSections`/`repositionCamera`/`setDirty`/`getRenderSectionAt` — the tightest coupling to vanilla renderer internals in this slice (all overrides at `:115-170, 431-455`).

### 2.9 `VisibleSectionDiscovery` — single-threaded BFS section discovery (199 LOC, client)
`render/VisibleSectionDiscovery.java`

**Responsibility:** replaces vanilla's multithreaded `SectionOcclusionGraph` traversal during portal passes (and for 1 frame after teleport). Header doc `:27-33`: no cave culling, no threading, no garbage. See §3.5 for the algorithm. Also owns the shared `ObjectArrayList<RenderSection>` pool (`takeList`/`returnList`, `:168-182`) used by `MyGameRenderer` for scratch `visibleSections` lists.

**Called from:** `MixinLevelRenderer.onSetupTerrainBegin` (`setupRender` HEAD, cancels vanilla when portal-rendering, `mixin/client/render/MixinLevelRenderer.java:234-263`) and `onSetupTerrainEnd` (outer world, teleport-override frames, `:270-306`); both gated off when Sodium is present or Iris is rendering shadows (`ip_allowOverrideTerrainSetup`, `:265-268`).

### 2.10 `FrustumCuller` — portal-shaped frustum culling (402 LOC, client)
`render/FrustumCuller.java`

**Responsibility:** builds a camera-relative `BoxPredicateF canDetermineInvisibleFunc` each frame (`update(camX, camY, camZ)`, `:31-36`):
- While portal-rendering: **inner culling** — cull sections fully outside the 4-plane pyramid through the portal window (`getCanDetermineInvisibleFunc` → `renderingPortal.getPortalShape().getInnerFrustumCullingFunc(...)`, `:89-97`, which for flat portals calls back into `getFlatPortalInnerFrustumCullingFunc`, `:176-202`; the vertex winding is flipped for `Mirror`, `:190-195`).
- Outer world: **outer culling** — cull sections hidden *behind* the nearest cullable portal (fully behind the portal plane AND fully inside the portal frustum, `:229-240`); only enabled with Sodium because vanilla's lazy visibility rebuild would leave artifacts (`:103-110`).

Plane equation derivation is documented in-source (`:243-246`): `planeW = -origin·normal`, kept side `> 0`. `isFullyInFrontOfPlane`/`isFullyBehindPlane` test the single box corner extremal against the plane sign (`:247-267`).

**Wired via:** `mixin/client/render/optimization/MixinFrustum.java:40-112` (one `FrustumCuller` per vanilla `Frustum`, updated in `prepare`, consulted in visibility tests through duck `IEFrustum`) and `compat/mixin/sodium/MixinSodiumWorldRenderer.java:23` + `MixinSodiumViewport.java:29-33` for Sodium.

### 2.11 `CrossPortalViewRendering` — third-person/view-bobbing camera through a portal (157 LOC, client)
`render/CrossPortalViewRendering.java`

**Responsibility:** when the *camera* (not the player) is on the other side of a portal — third person, or view bobbing pushing the eye across — replaces the whole vanilla `renderLevel` call with a portal-world render. `renderCrossPortalView()` (`:29-99`): raytrace player-eye→camera for portals (`PortalCommand.raytracePortals`, `:54-56`), require `portal.canTeleportEntity(cameraEntity)` (`:65`), compute transformed camera pos (third-person: re-clip against dest-world blocks in `getThirdPersonCameraPos`, `:113-134`, mirroring `Camera.getMaxZoom`), reposition the original camera via duck `portal_setPos` (`:83`), build a `WorldRenderInfo` (hand off, bobbing off, `:85-94`) and call `IPCGlobal.renderer.invokeWorldRendering`. Hooked by `MixinGameRenderer.redirectRenderingWorld` (`@Redirect` of `GameRenderer.renderLevel` inside `render`, `mixin/client/render/MixinGameRenderer.java:145-160`) — returns true ⇒ vanilla world render skipped this frame.

### 2.12 `PortalEntityRenderer` — the `EntityRenderer` for portal entities (62 LOC, client)
`render/PortalEntityRenderer.java`

**Responsibility:** vanilla entity-renderer shim: `render(...)` (`:26-49`) calls `IPCGlobal.renderer.renderPortalInEntityRenderer(portal)` (lets `RendererDebug`/experimental paths draw during the entity pass; the stencil/FBO renderers collect portals elsewhere), then breakable-portal overlay (`OverlayRendering.shouldRenderOverlay`/`onRenderPortalEntity`, `:37-39`) and optional debug wire mesh (`:41-46`). `getTextureLocation` returns `null` (`:52-59`). **This is 1.21.3-shaped API** (`render(T, float yaw, float partialTick, PoseStack, MultiBufferSource, int light)`); in 26.2 the entire `EntityRenderer` contract changed to the extract→submit model — see MIGRATION_API_MAP.md headline.

### 2.13 `OverlayRendering` — breakable-portal block overlay (169 LOC, client)
`render/OverlayRendering.java`

**Responsibility:** draws a translucent block model (e.g. nether portal blocks) over `BreakablePortalEntity` areas, modeled on `FallingBlockRenderer` (javadoc `:90-92`). Per block pos of `portal.blockPortalShape.area`: `BlockRenderDispatcher.getBlockModel(state)`, quads via `BakedModel.getQuads(state, facing|null, random)` (`:72-88`), emitted with `VertexConsumer.putBulkData(pose, quad, float[]{1,1,1,1}, r,g,b,opacity, int[] packedLight{14680304×4}, OverlayTexture.NO_OVERLAY, true)` (`:150-161`) on `Sheets.translucentCullBlockSheet()` (`:133`). Disabled under Iris shaders with a one-time chat warning (`:53-60`).

### 2.14 `GlQueryObject` / `QueryManager` — GL occlusion queries (102 + 66 LOC, client)
`render/GlQueryObject.java`, `render/QueryManager.java`

- `GlQueryObject`: pooled query objects (batch `glGenQueries` ×500, `:77-83`; pool cap 1500, `:93-101`); `performQueryAnySamplePassed(Runnable)` uses `GL33.GL_ANY_SAMPLES_PASSED`, falling back to `GL15.GL_SAMPLES_PASSED` on macOS (`:19-27`); `fetchQueryResult()` is a **blocking** `glGetQueryObjecti(..., GL_QUERY_RESULT)` (`:51-58`). Consumed by `render/optimization/PortalRenderInfo` (visibility prediction — separate slice).
- `QueryManager`: single-object synchronous variant, `renderAndGetDoesAnySamplePass(Runnable)` (`:14-41`); `queryStallCounter` (`:9`) tracks blocking reads.

### 2.15 `SecondaryFrameBuffer` — lazily-sized `TextureTarget` wrapper (41 LOC, client)
`render/SecondaryFrameBuffer.java` — `prepare()` sizes to main FB `viewWidth/viewHeight` (`:12-17`); creates `new TextureTarget(w, h, /*depth*/ true, Minecraft.ON_OSX)` + `checkStatus()`, resizes on mismatch (`:19-38`). Used by the FBO renderer path and `PortalGunRenderingRelated`-style consumers.

### 2.16 `ShaderCodeTransformation` — GLSL source patching for the clip uniform (114 LOC, client)
`render/ShaderCodeTransformation.java`

**Responsibility:** loads regex transformation rules from `immersive_portals:shaders/shader_transformation.yaml` (snakeyaml shadowed via cloth-config, `:54-70`; gated by `IPGlobal.enableClippingMechanism`) and rewrites vanilla shader GLSL at compile time: `transform(CompiledShader.Type type, String shaderId, String inputCode)` (`:72-96`), `shouldAddUniform(String shaderName)` (`:106-113`). This is how `iportal_ClippingEquation` + `gl_ClipDistance[0]` logic get into vanilla core shaders — invoked from a shader-compilation mixin (outside this slice). Uses `com.mojang.blaze3d.shaders.CompiledShader.Type.VERTEX/FRAGMENT` (`:22-30`). **Mod status: ALREADY PORTED (adapted)** — `seamlessportals/render/ShaderCodeTransformation.java` exists.

### 2.17 `ForceMainThreadRebuild` — frame-scoped synchronous chunk compile (46 LOC, client)
`render/ForceMainThreadRebuild.java` — `forceMainThreadRebuildFor(int frameCount)` (`:39-41`) + per-frame latch `onPreRender()` called from `RenderStates.updatePreRenderInfo` (`context_management/RenderStates.java:121`). Consumed by `mixin/client/render/MixinLevelRenderer_ForceMainThreadRebuild.java:11-26` — `@ModifyVariable` forcing the `prioritizeChunkUpdates` boolean in `LevelRenderer.compileSections` to true, and by the Sodium flawless-frames hook (`compat/mixin/sodium/MixinSodiumFlawlessFrames.java:14`).

### 2.18 `LoadingIndicatorRenderer` — no-op entity renderer (43 LOC, client)
`render/LoadingIndicatorRenderer.java` — `EntityRenderer<LoadingIndicatorEntity>` whose `render` body is fully commented out (`:21-42`); `getTextureLocation` returns null. Exists so the entity type has a registered renderer.

---

## 3. Mechanisms

### 3.1 The world-switch render flow — `MyGameRenderer.switchAndRenderTheWorld` (`render/MyGameRenderer.java:114-285`)

This is a *pure context-swap around a recursive `GameRenderer.renderLevel` call*. Order matters; the port must preserve it.

**Phase 0 — cave-culling gates (`:122-128`):** `client.smartCull = false` when portal cave culling is off or `PortalRendering.shouldEnableSodiumCaveCulling()` says no.

**Phase 1 — save old state (`:130-168`):**
`client.level`, `client.levelRenderer`, `gameRenderer.lightTexture()`, `client.player.noPhysics`, hand-render flag (duck), the **old renderer's `visibleSections` list** (duck `portal_getChunkInfoList`), `client.hitResult`, `gameRenderer.getMainCamera()`, the **destination renderer's** transparency `PostChain` and `RenderBuffers`, `client.renderBuffers()`, the destination dispatcher's `SectionBufferBuilderPack` fixedBuffers (duck), the destination renderer's `Frustum` (duck), `RenderSystem.getProjectionMatrix()` (comment `:159-160`: the projection matrix contains view bobbing, which is scale-related), the RenderSystem model-view stack (duck `IERenderSystem.ip_getModelViewStack`), and the Iris pipeline object.

**Phase 2 — install destination context (`:164-226`):**
1. Swap a pooled scratch `visibleSections` list onto the *old* renderer (`:164-166`) — critical when the destination `LevelRenderer` **is the same instance** (same-dimension portal): the outer pass's in-flight list must not be clobbered by the portal pass's discovery.
2. `ip_setWorldRenderer(worldRenderer)`; `client.level = newWorld`; lightmap → per-dimension `DimensionRenderHelper.lightmapTexture` (`:171-173`).
3. `client.getBlockEntityRenderDispatcher().level = newWorld`; `player.noPhysics = true`; `gameRenderer.setRenderHand(doRenderHand)` (`:175-177`).
4. `FogRendererContext.swappingManager.pushSwapping(newDimension)`; particle engine world (duck); hit result: remote-dim pointed block substituted, or nulled if `!PortalRendering.shouldRenderHitResult()` (`:179-186`); fresh `Camera` installed (`:139, 187`).
5. If `IPGlobal.useSecondaryEntityVertexConsumer`: acquire pooled `RenderBuffers` (`new RenderBuffers(0)` on miss, `:77-89`) and install on renderer + client + dispatcher fixedBuffers (`:189-204`); on pool denial, `endBatch()` the outer buffer source first (`:205-210`).
6. Sodium context switch (`:213-214`); transparency shader nulled — Fabulous transparency is incompatible with portal passes (`:216`); fresh `Matrix4fStack(16)` + `RenderSystem.applyModelViewMatrix()` (`:218-219`); Iris pipeline nulled (`:221`).
7. First-visit lightmap prime: `if (!RenderStates.isDimensionRendered(newDimension)) helper.lightmapTexture.updateLightTexture(0)` (`:224-226`).

**Phase 3 — render (`:229-235`):** `invokeWrapper.accept(() -> { profiler.push("render_portal_content"); client.gameRenderer.renderLevel(client.getTimer()); profiler.pop(); })` — a *recursive* invocation of the very method the outer frame is inside; all IP `GameRenderer`/`LevelRenderer` mixins run again with `PortalRendering.isRendering() == true`.

**Phase 4 — restore (`:237-284`):** exact mirror of phases 1-2, plus `gameRenderer.resetProjectionMatrix(oldProjectionMatrix)` (`:269`), and `client.getEntityRenderDispatcher().prepare(client.level, oldCamera, client.crosshairPickEntity)` (`:275-280`) because the recursive pass re-`prepare`d the dispatcher with the portal camera. Ends with `client.smartCull = true` (`:284`).

The public wrapper `renderWorldNew` (`:96-112`) brackets this with `WorldRenderInfo.pushRenderInfo/popRenderInfo` — the render-info stack (`context_management/WorldRenderInfo.java:73-118`) is what makes `Camera` repositioning (`adjustCameraPos`) and the camera-matrix override (`applyAdditionalTransformations`, `:120-139`) apply during the recursive pass.

### 3.2 Portal view-area mesh drawing — `ViewAreaRenderer.renderPortalArea` (`render/ViewAreaRenderer.java:25-118`)

GL-state sequence (exact order): face cull per `doFaceCulling` (`:32-37`); **color mask**: fuse-view portals with `maxPortalLayer != 0` write no color, otherwise mask = `doModifyColor` (`:39-49`); **depth mask**: `doModifyDepth ? !isFuseView : false` (`:51-61`); **mirror winding**: if `PortalRendering.isRenderingOddNumberOfMirrors()` → `glCullFace(GL_FRONT)` (`:63-66`, `MyRenderHelper.java:471-473`); **clipping**: if `doClip` and currently portal-rendering, `setupInnerClipping(activePlane, modelView, 0)` — comment "don't do adjustment" (`:68-78`); depth test on, **depth clamp on** (`:80-82`, so the mesh survives near-plane intersection when the camera is inside the portal slab); bind the portal-area shader, set `MODEL_VIEW_MATRIX`/`PROJECTION_MATRIX` directly on the shader (`:84-88`), upload the clip uniform (`:90`), `apply()`; then **build-and-draw**: `buildPortalViewAreaTrianglesBuffer` (`:94-99`) tessellates `VertexFormat.Mode.TRIANGLES` / `POSITION_COLOR` with every vertex colored the fog color (`:124-140`), geometry supplied by `portal.renderViewAreaMesh(portalOrigin - cameraPos, consumer)` (`:142`, → `portal/Portal.java:877-889` → shape slice; rectangular shape emits 2 triangles over axes `axisW·w/2`, `axisH·h/2`, `portal/shape/RectangularPortalShape.java:143-158`, quad corners (±1, ±1) in `outputFullQuad`, `render/ViewAreaRenderer.java:211-223`), uploaded immediately via `BufferUploader.draw(bufferBuilder.build())` (`:144`); cleanup restores cull/clamp/masks/winding and disables clipping (`:101-117`).

Callers choose the mask combination: stencil renderer draws it twice (once incrementing stencil with color off, once writing depth), FBO renderer draws it as the textured window via `MyRenderHelper.drawPortalAreaWithFramebuffer` which reuses `buildPortalViewAreaTrianglesBuffer` with the FB-sampling shader (`render/MyRenderHelper.java:172-214`).

### 3.3 FrontClipping plane math — signs, quoted (`render/FrontClipping.java`)

Plane convention (comment `:74`): **"the normal of plane points to the non-clipped side."** Kept half-space (comment `:113`): `planeNormal * p + c > 0`.

Inner equation — `getClipEquationInner(clippingPoint, clippingDirection, correction)` (`:102-120`), quoted:

```java
Vec3 planeNormal = clippingDirection;

Vec3 portalPos = clippingPoint
    .add(planeNormal.scale(correction))
    .subtract(cameraPos);

//equation: planeNormal * p + c > 0
//-planeNormal * portalCenter = c
double c = planeNormal.scale(-1).dot(portalPos);

return new double[]{ planeNormal.x, planeNormal.y, planeNormal.z, c };
```

So the equation is expressed in **camera-relative world coordinates** (`p` = worldPos − cameraPos). The plane source for inner clipping is `PortalRendering.getActiveClippingPlane()` (`context_management/PortalRendering.java:178-214`) → `renderingPortal.getInnerClipping()` (`portal/Portal.java:1388`) → shape; for rectangular shapes the plane sits at the **other-side** state's position with the other-side normal (`portal/shape/RectangularPortalShape.java:121-129`), i.e. destination geometry behind the destination portal plane is clipped. When the rendering portal has no inner clipping (scale-box groups), the plane is inherited from outer layers and transformed through each layer with `transformPoint` / `transformLocalVecNonScale` (`PortalRendering.java:184-211`).

Outer equation — `getClipEquationOuter(Portal)` (`:143-165`): same formula, plane from `portal.getPortalShape().getOuterClipping(portal.getThisSideState())` — this-side position + this-side normal, "the plane's normal points to the 'remaining' side" (`portal/shape/RectangularPortalShape.java:111-118`).

View-space form — `transformClipEquation(equation, modelView)` (`:90-100`): `eq' = transpose(inverse(M)) · eq` (standard plane transform). **Computed and stored but the uniform upload path uses the before-model-view equation** (`:191, 197-201`); the after-model-view form is exposed via getter (`:171-173`) for the Sodium compat layer.

Adjustment signs at the three call sites: terrain layers use `-FrontClipping.ADJUSTMENT` (= −0.01, plane moved *back* "to make world wrapping portal not z-fight", `mixin/client/render/MixinLevelRenderer.java:195-201`); the view-area mesh and the entity pass use `0` (`render/ViewAreaRenderer.java:70-73`, `render/CrossPortalEntityRenderer.java:83-88`).

Disabled-state uniform is `(0, 0, 0, 1)` — `0·p + 1 > 0` keeps everything (`:204, 223`). The uniform is (re)uploaded on **every shader bind** via `MixinRenderSystem_Clipping` (`mixin/client/render/MixinRenderSystem_Clipping.java:20-56`): during entity/projection rendering → upload; during portal weather → upload; otherwise → unset; always unset under Iris. `GL11.glEnable(GL_CLIP_PLANE0)` (`:34, 43`) — enum-identical to `GL_CLIP_DISTANCE0`, which activates the `gl_ClipDistance[0]` writes injected by `ShaderCodeTransformation`.

**Mod status:** ported (adapted) — but see §2.2: the mod's plane lives in view space; IP's in camera-relative world space.

### 3.4 CrossPortalEntityRenderer dual clipping (`render/CrossPortalEntityRenderer.java`)

An entity standing halfway in a portal is drawn **twice with complementary clip planes**:

1. **Real body, source side (outer clipping).** In the outer world pass only (`!PortalRendering.isRendering()`), for each entity in `collidedEntities`: flush the batch (`endBatch()`, `:123`), then `setupOuterClipping(matrixStack, collidingPortal)` (`:125`) — keeps geometry on the camera side of the this-side plane; after the entity, flush again and disable (`:132-143`). This slices off the part of the model that already poked through the portal.
2. **Projection, destination side (inner clipping).** After the entity pass (`onEndRenderingEntitiesAndBlockEntities`, `:98-108`), `renderEntityProjections` (`:147-169`) walks the collided entities; for each colliding non-`Mirror` portal whose `getDestDim()` equals the *currently rendered* dimension, `renderProjectedEntity`:
   - Outer world (`:205-215`): disable clip + `endBatch()` ("don't draw the existing triangles with culling enabled", `:207`), then `setupInnerClipping(collidingPortal.getInnerClipping(), pose, 0)` — keeps only geometry past the destination plane — render, disable.
   - Inside a portal pass (`:184-204`): correct rendering "needs two culling planes" so a rough workaround is used — skip if the colliding portal is a flipped/reverse portal of the rendering portal (`Portal.isFlippedPortal/isReversePortal`, `:190-191`), and skip if the camera is on the hidden side of the colliding portal's inner plane (`innerClipping.isPointOnPositiveSide(cameraPos)` false and it's not the rendering portal, `:195-199`). Additionally `renderEntity` requires the transformed bounding box to intersect the rendering portal's visible region (`PortalManipulation.isOtherSideBoxInside(transformedBoundingBox, renderingPortal)`, `:239-245`).
   - **Position trick** (`:279-298`, formula quoted in comments `:283-288`): the entity is *not* moved; instead the camera position passed to the render call is displaced: `newCameraPos = entityInstantPos − transform(entityInstantPos) + cameraPos`, so the entity renders at its transformed location. Scale/rotation portals additionally wrap the pose stack: translate to the entity anchor, `scale(portal.getScaling())`, `mulPose(portal.getRotation())`, translate back (`setupEntityProjectionRenderingTransformation`, `:316-337`). Draw goes through the private vanilla entity path duck `ip_myRenderEntity` and is flushed immediately (`:300-308`).
   - **LocalPlayer guards** (`:248-274`): config `renderYourselfInPortal`, per-portal `getDoRenderPlayer()`, first-person distance valve scaled by portal scaling, and camera-inside-transformed-box rejection.
3. **Visibility gate during portal passes** — `shouldRenderEntityNow` (`:369-402`), injected at `EntityRenderDispatcher.shouldRender(Entity, Frustum, double, double, double)Z` HEAD (cancellable, forces `false`) — `mixin/client/render/MixinEntityRenderDispatcher.java:15-19`, consuming `shouldRenderEntityNow` at `:28-31`: players hidden unless `renderingPortal.getDoRenderPlayer()`; entities whose colliding portal faces away from the camera are hidden (dot test `:388-390`); finally requires `renderingPortal.isOnDestinationSide(eyePos, -0.01)` (`:397-399`). (26.2: `shouldRender` survives signature-identical, `EntityRenderDispatcher.java:127-129`, now called from `LevelExtractor.isEntityVisible`, `LevelExtractor.java:254` — api-map S35.)

The collided-entity set is maintained by entity tick (`onEntityTickClient`, `:70-78`, from `IPMcHelper.java:107`) and pruned post-tick when the entity is removed or no longer colliding (`:63-68`).

### 3.5 lateUpdateLight + the cross-dimension pump pair (`render/MyRenderHelper.java:441-469`)

- `lateUpdateLight` (`:441-451`): after the whole frame renders (`MixinGameRenderer.onAfterRenderingCenter`, `mixin/client/render/MixinGameRenderer.java:119-142`, after `GuiPortalRendering._onGameRenderEnd()`), iterate `ClientWorldLoader.getClientWorlds()` and for each world whose dimension is **not** in `RenderStates.renderedDimensions` this frame (`context_management/RenderStates.java:227-231`), call `world.getChunkSource().getLightEngine().runLightUpdates()`. Rendered dimensions get their light run by the vanilla path inside their render pass; this covers loaded-but-not-rendered secondaries so queued removals/recomputes actually publish. Placement is deliberate: the IP header comment warns it must **not** run before world rendering — "this should not run before world rendering or the smooth lighting may become abnormal in section edge" (`:440`; full comment `:437-440`) — hence the frame-end hook. Gated by `IPCGlobal.lateClientLightUpdate`. **Already ported to the mod** (commit 3a2c14e, `GameRendererMixin.java:33-42` → `PortalWorldManager.lateUpdateSecondaryLight`).
- `earlyRemoteUpload` (`:458-469`): at `GameRenderer.render` HEAD (`MixinGameRenderer.java:70-97`), for every *other* dimension's `LevelRenderer`, `getSectionRenderDispatcher().uploadAllPendingUploads()` — otherwise remote-dimension section-compile futures can never complete (their upload normally happens inside their own render pass, which may not run every frame). These two are a matched pair; a faithful port needs both.

### 3.6 VisibleSectionDiscovery BFS (`render/VisibleSectionDiscovery.java:45-166`)

Seeding: if the rendering portal's shape supplies a `getModifiedVisibleSectionIterationOrigin` (e.g. box shapes), seed there frustum-unchecked (`:67-81`); else if the camera is below/above the world, seed the whole bottom/top layer via `BlockTraverse.searchOnPlane` (`:82-87, 131-141`); else seed the camera's section (`:88-95`). Loop: pop, expand the 6 axis neighbors (`:97-110`). `checkSection` (`:143-166`): reject beyond `viewDistance` in Chebyshev distance per axis (view distance = `WorldRenderInfo.getRenderDistance()` degraded by `PerformanceLevel.getPortalRenderingDistance`, `:118-123`); fetch-or-create via `ImmPtlViewArea.rawFetch` (marks the column's `timeMark`, keeping it alive against the purger); dedupe with a per-section `portal_getMark/portal_setMark` nano-time stamp (duck `IERenderSection`); frustum-test `vanillaFrustum.isVisible(builtChunk.getBoundingBox())` (`:126-129`) — the caller passes `new Frustum(frustum).offsetToFullyIncludeCameraCube(8)` because "the vanilla frustum culling code may wrongly cull the first section" (`:125`, `MixinLevelRenderer.java:255`). Results append to the renderer's `visibleSections` list. No occlusion culling — cave culling is only re-enabled through Sodium (`PortalRendering.shouldEnableSodiumCaveCulling`).

### 3.7 Camera-rotation interpolation across rotating portals (`render/TransformationManager.java:135-224`)

On crossing: compute `immediateFinalRot = oldCameraRotation * conj(portalRotation)` (`:154-157`), derive new raw pitch/yaw by stripping the (possibly changed) gravity rotation (`:172-183`), clamp pitch reflection at ±90° (`:188-193`), write rotation + history + bob fields (`:195-203`), then store `animationDelta = conj(newCameraRotationWithGravity) * immediateFinalRot` (`:211-213`) and interpolate it to identity over 1 s (started only if the delta exceeds 0.1°, `:215-220`). Every frame, `processTransformation` multiplies the current interpolated delta into the camera matrix before `WorldRenderInfo` layer transforms (`:88-95`), hooked by wrapping `Matrix4f.rotation(Quaternionfc)` inside `renderLevel` (`MixinGameRenderer.java:304-317`).

---

## 4. MC API touchpoint list (deduplicated; ⚠ = known-changed in 26.2 per MIGRATION_API_MAP.md headline — immediate-mode `MultiBufferSource`/`BufferSource`, `PoseStack`-passing renderers, and the RenderSystem global-state model are gone, replaced by submit→prepare→execute `SubmitNodeCollector`/`RenderPass`)

**Minecraft (client singleton) mutable state:**
- `Minecraft.level` (get + **set**) — `MyGameRenderer.java:142,172,242`
- `Minecraft.levelRenderer` (**set** via duck `IEMinecraftClient.ip_setWorldRenderer`) — `:171,241`
- `Minecraft.mainRenderTarget` (**set** via duck `ip_setFrameBuffer`) — `GuiPortalRendering.java:59,76`
- `Minecraft.renderBuffers()` (get + **set** via duck) ⚠ — `MyGameRenderer.java:153,194,260`; `CrossPortalEntityRenderer.java:123,300`
- `Minecraft.smartCull` (public field, set) — `MyGameRenderer.java:123,127,284`; `TransformationManager.java:293,299`
- `Minecraft.hitResult` (public field, set) — `MyGameRenderer.java:149,182-186,249`
- `Minecraft.crosshairPickEntity`, `Minecraft.cameraEntity`, `Minecraft.player`, `Minecraft.particleEngine`, `Minecraft.gui.getBossOverlay().shouldCreateWorldFog()`, `Minecraft.options.getEffectiveRenderDistance()/getCameraType()/prioritizeChunkUpdates()`, `Minecraft.getWindow().getWidth()/getHeight()`, `Minecraft.getProfiler()`, `Minecraft.getTimer()` (→ `DeltaTracker`), `Minecraft.ON_OSX`, `Minecraft.getMainRenderTarget()`, `Minecraft.getBlockEntityRenderDispatcher().level` (set), `Minecraft.getEntityRenderDispatcher()`

**GameRenderer:** ⚠
- `renderLevel(DeltaTracker)` (recursive re-invocation!) — `MyGameRenderer.java:231-233`; mixin injection points around it — `MixinGameRenderer.java:103-184`
- `getMainCamera()` (+ **set** `mainCamera` via mixin), `lightTexture()` (+ **set** via mixin), `setRenderHand(boolean)` + `renderHand` field, `resetProjectionMatrix(Matrix4f)`, `getProjectionMatrix(float)`, `getRenderDistance()`, `getDarkenWorldAmount(float)`, `bobView(PoseStack,float)` internals (`PoseStack.translate` args modified), `renderItemInHand`, `resize(int,int)`, `panoramicMode` field

**LevelRenderer:** ⚠ (26.2: renderer rewrite — the biggest risk area)
- `renderLevel(GraphicsResourceAllocator, DeltaTracker, boolean, Camera, GameRenderer, LightTexture, Matrix4f, Matrix4f)` (wrapped) — `MixinGameRenderer.java:169-184`
- `setupRender(Camera, Frustum, boolean, boolean)` (cancelled/overridden) — `MixinLevelRenderer.java:234-306`
- `compileSections` (`prioritizeChunkUpdates` boolean forced) — `MixinLevelRenderer_ForceMainThreadRebuild.java:11-26`
- `allChanged` (its `new ViewArea` redirected to `ImmPtlViewArea`) — `MixinLevelRenderer.java:322-341`
- framegraph main-pass lambda `method_62214` (injection targets: `DimensionSpecialEffects.constantAmbientLight`, `Sheets.translucentItemSheet`, `MultiBufferSource$BufferSource.endLastBatch` ordinal 1, `LevelRenderer.renderSectionLayer(RenderType,DDD,Matrix4f,Matrix4f)`, `RenderSystem.clear`) — `MixinLevelRenderer.java:123-320`
- private fields via shadow/duck: `visibleSections` (`ObjectArrayList<SectionRenderDispatcher.RenderSection>`, get/set), `renderBuffers` (get/set), `cullingFrustum`/frustum (get/set), `sectionRenderDispatcher`, `transparencyChain` (`PostChain`, get/set), `entityOutlineTarget`, `lastViewDistance`, `viewArea`
- private method `renderEntity(...)` via duck `ip_myRenderEntity(Entity,double,double,double,float,PoseStack,MultiBufferSource)` ⚠ — `ducks/IEWorldRenderer.java:19-27`
- `getSectionRenderDispatcher()`, `resize(int,int)`

**ViewArea / SectionRenderDispatcher (chunk-mesh infrastructure):** ⚠
- `ViewArea` subclassing: protected/inherited `sectionGridSizeX/Y/Z`, `sections`, `level`; overrides `createSections`, `repositionCamera(double,double)`, `setDirty(int,int,int,boolean)`, `getRenderSectionAt(BlockPos)`, `releaseAllBuffers()` — `ImmPtlViewArea.java:37-455`
- `SectionRenderDispatcher.RenderSection` **inner-class instantiation** `dispatcher.new RenderSection(int, x, y, z)` — `ImmPtlViewArea.java:260-263`; `releaseBuffers()`, `setDirty(boolean)`, `getOrigin()`, `getBoundingBox()`
- `SectionRenderDispatcher.setCamera(Vec3)` — `MixinLevelRenderer.java:245`; `uploadAllPendingUploads()` — `MyRenderHelper.java:466`; fixed `SectionBufferBuilderPack` swap (duck) — `MyGameRenderer.java:154-156,202-203,261-262`
- `SectionOcclusionGraph.initializeQueueForFullUpdate` (threading assumption documented) — `ImmPtlViewArea.java:137`

**Camera:** `new Camera()` — `MyGameRenderer.java:139`, `CrossPortalViewRendering.java:36`; `setup(BlockGetter, Entity, boolean, boolean, float)` — `TransformationManager.java:232-238`, `CrossPortalViewRendering.java:41-46`; `getPosition()`, `rotation()`; ducks: `portal_setPos`, `ip_resetState`, `ip_getCameraY/ip_setCameraY`

**RenderSystem / GlStateManager / blaze3d:** ⚠ (26.2 removes the global-state model)
- `RenderSystem`: `getProjectionMatrix()`, `setProjectionMatrix(Matrix4f, ProjectionType.ORTHOGRAPHIC)`, `getModelViewStack()` (`Matrix4fStack` + private stack **set** via accessor `IERenderSystem.ip_setModelViewStack` — `MyGameRenderer.java:162,218,270`), `applyModelViewMatrix()`, `setShader(ShaderProgram)`/`setShader(CompiledShaderProgram)`/`getShader()`/`clearShader()` (also mixin'd — `MixinRenderSystem_Clipping.java:18-34`), `renderThreadTesselator()`, `enableBlend/disableBlend/blendFuncSeparate/defaultBlendFunc`, `colorMask`, `depthMask`, `disableDepthTest`, `viewport`, `clear(int)`, `clearColor`
- `GlStateManager`: `_enableCull/_disableCull/_colorMask/_depthMask/_enableDepthTest/_viewport`; `SourceFactor/DestFactor` enums
- `Tesselator`/`BufferBuilder`: `begin(VertexFormat.Mode.TRIANGLES|QUADS, DefaultVertexFormat.POSITION_COLOR|BLIT_SCREEN)`, `addVertex(float,float,float)`, `setColor`, `build()/buildOrThrow()`; `BufferUploader.draw`/`drawWithShader` ⚠
- `RenderTarget`/`TextureTarget`: ctor `(w,h,depth,onOsx)`, `bindWrite(boolean)`, `clear(boolean)`, `setClearColor`, `resize(w,h,boolean)`, `checkStatus()`, `getColorTextureId()`, `width/height/viewWidth/viewHeight` fields, `blitToScreen` (vanilla-copied)
- `Lighting.setupLevel()/setupNetherLevel()` — `MyGameRenderer.java:327-330`
- Raw GL (survives any MC version, needs only a GL context at the right moment): `GL11.glEnable/glDisable(GL_CLIP_PLANE0)`, `GL11.glCullFace(GL_FRONT/GL_BACK)`, `GL11.glReadPixels`, `GL20.glUniform4f`, `GL15/GL33` query API (`glGenQueries/glBeginQuery/glEndQuery/glGetQueryObjecti/glDeleteQueries`, `GL_ANY_SAMPLES_PASSED`, `GL_SAMPLES_PASSED`)

**Shader system:** ⚠ (already changed 1.21.1→1.21.3, changes again in 26.2)
- `ShaderProgram` registration via `CoreShaders` private list (accessor mixin `CoreShadersAccessor.register(String, VertexFormat, ShaderDefines)`) — `MyRenderHelper.java:65-81`
- `CompiledShaderProgram`: `MODEL_VIEW_MATRIX`/`PROJECTION_MATRIX` uniform fields, `apply()/clear()`, `getUniform(String)`, `bindSampler(String,int)`, `setupUniforms` (mixin point), `programId` (shadow) — `MyRenderHelper.java:184-213`, `MixinShaderInstance.java:18-39`
- `CoreShaders.POSITION_COLOR`, `CoreShaders.BLIT_SCREEN`
- `CompiledShader.Type.VERTEX/FRAGMENT` (GLSL source hook) — `ShaderCodeTransformation.java:22-30`
- `Uniform.glGetUniformLocation(int,CharSequence)` — `MixinShaderInstance.java:33`
- STALE: `ShaderInstance` refs in `ViewAreaRenderer.java:11,84`

**Light engine:** `ClientChunkCache.getLightEngine()` → `LevelLightEngine.runLightUpdates()` — `MyRenderHelper.java:448` (already mapped for 26.2 in the mod)

**Fog:** ⚠ (26.2 uses `FogParameters` in the framegraph lambda — visible in `MixinLevelRenderer.java:131`) — `FogRenderer.setupFog(Camera, FogMode.FOG_TERRAIN, float, boolean, float)`, `FogRenderer.levelFogColor()`, `FogRenderer.setupColor(Camera, float, ClientLevel, int, float)` — `MyGameRenderer.java:291-317`

**Entity rendering:** ⚠ (26.2: extract→submit model)
- `EntityRenderer<T>` subclassing + `render(T, float, float, PoseStack, MultiBufferSource, int)` + `getTextureLocation(T)` returning null — `PortalEntityRenderer.java:19-59`, `LoadingIndicatorRenderer.java:10-43`
- `EntityRendererProvider.Context`; Fabric `EntityRendererRegistry.register` — `platform_specific/IPModEntryClient.java:52-61`
- `EntityRenderDispatcher.prepare(Level, Camera, Entity)` — `MyGameRenderer.java:275-280`; `EntityRenderDispatcher.shouldRender(Entity, Frustum, double, double, double)Z` HEAD gate (cancellable) — `MixinEntityRenderDispatcher.java:15-19` (consumes `shouldRenderEntityNow` at `:28`)
- `MultiBufferSource.BufferSource.endBatch()/endLastBatch()` ⚠, `MultiBufferSource.getBuffer(RenderType)`, `RenderType.lines()`, `Sheets.translucentCullBlockSheet()`, `VertexConsumer.putBulkData(PoseStack.Pose, BakedQuad, float[], float,float,float,float, int[], int, boolean)`, `OverlayTexture.NO_OVERLAY`
- `BlockRenderDispatcher.getBlockModel(BlockState)`, `BakedModel.getQuads(BlockState, Direction, RandomSource)`
- `BlockEntityRenderDispatcher.level` (public field set)

**Entity/world queries:** `Entity.position()/getEyePosition(float)/isRemoved()/level()/getBbWidth()`, `LocalPlayer` rotation + bob fields (`setYRot/setXRot`, `yRotO/xRotO`, `yBob/xBob/yBobO/xBobO` — public fields, `TransformationManager.java:195-203`), `ClientLevel.dimension()/effects()/getGameTime()/getMinBuildHeight()/getMaxBuildHeight()`, `DimensionSpecialEffects.isFoggyAt(int,int)/constantAmbientLight()`, `Level.clip(ClipContext)` + `ClipContext.Block.VISUAL/Fluid.NONE` + `BlockHitResult`/`HitResult.Type`, `ResourceKey<Level>`, `ChunkPos.asLong/getX/getZ/toLong`, `SectionPos.of/blockToSectionCoord`, `BlockPos.containing`, `Mth.floor/floorDiv/positiveModulo/clamp/abs`, `AABB.contains`, `Frustum` (copy-ctor `new Frustum(Frustum)`, `prepare(x,y,z)`, `isVisible(AABB)`, `offsetToFullyIncludeCameraCube(int)`), `PostChain`, `DeltaTracker.getGameTimeDeltaPartialTick(boolean)`, `Profiler.get()`/`ProfilerFiller.push/pop`, `RandomSource.create/setSeed`, `Direction.getNearest`, `Tuple`, `LightTexture.updateLightTexture(float)`, `ParticleEngine` level field (duck)

---

## 5. Registration & wiring

**Static init chain** (`IPModMainClient.init()`, `IPModMainClient.java:71-135`, called from the Fabric client entrypoint `IPModEntryClient.onInitializeClient()`, `platform_specific/IPModEntryClient.java:66-67`):
- On the render thread (`Minecraft.getInstance().execute`): `ShaderCodeTransformation.init()` → `MyRenderHelper.init()` (currently a no-op body; shader registration happens in its static initializers `:65-81`) → construct `RendererUsingStencil`/`RendererUsingFrameBuffer` and set `IPCGlobal.renderer` (`:76-85`).
- Then: `CrossPortalEntityRenderer.init()` (`:89`), `VisibleSectionDiscovery.init()` (`:117`), `ImmPtlViewArea.init()` (`:119`), `GuiPortalRendering._init()` (`:123`), `ForceMainThreadRebuild.init()` (`:128`). **`MyGameRenderer.init()` is never called on this branch** (dead cleanup registration — see §2.1).

**Entity-renderer registration** (`IPModEntryClient.initPortalRenderers()`, `:39-63`): Fabric `EntityRendererRegistry.register(entityType, PortalEntityRenderer::new)` for the 9 portal entity types (`Portal`, `NetherPortalEntity`, `EndPortalEntity`, `Mirror`, `BreakableMirror`, `GlobalTrackedPortal`, `WorldWrappingPortal`, `VerticalConnectingPortal`, `GeneralBreakablePortal`), plus `LoadingIndicatorRenderer` for `LoadingIndicatorEntity`.

**Event hooks (IP's own event objects, not Fabric API):** `IPGlobal.POST_CLIENT_TICK_EVENT` (`CrossPortalEntityRenderer.java:52`, `ImmPtlViewArea.java:83`), `IPCGlobal.CLIENT_CLEANUP_EVENT` (disconnect cleanup: `CrossPortalEntityRenderer.java:54`, `VisibleSectionDiscovery.java:185`, `GuiPortalRendering.java:123`, `ForceMainThreadRebuild.java:19`), `ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` (`CrossPortalEntityRenderer.java:56`, `VisibleSectionDiscovery.java:187`), `ImmPtlClientChunkMap.clientChunkUnloadSignal` (`ImmPtlViewArea.java:70`), `IPGlobal.PRE_GAME_RENDER_TASK_LIST` (`ImmPtlViewArea.java:335`).

**Mixin wiring (the slice is driven almost entirely by mixins):**
- `MixinGameRenderer` (`GameRenderer`): pre-frame updates + `earlyRemoteUpload` at `render` HEAD; renderer prepare/finish around `renderLevel`; `GuiPortalRendering._onGameRenderEnd` + `lateUpdateLight` after; `CrossPortalViewRendering` redirect of `renderLevel`; camera-matrix wrap → `TransformationManager`; basic-projection-matrix capture/override; view-bobbing multiplier; `IEGameRenderer` duck impl (`mixin/client/render/MixinGameRenderer.java` throughout).
- `MixinLevelRenderer` (`LevelRenderer`): `CrossPortalEntityRenderer` begin/end/per-entity hooks, fog/lighting resets, `FrontClipping` terrain-layer setup/teardown, `VisibleSectionDiscovery` override of `setupRender`, `ImmPtlViewArea` construction redirect, FB-clear replacement, `IEWorldRenderer` duck impl.
- `MixinEntityRenderDispatcher`, `MixinCamera`, `MixinRenderSystem_Clipping`, `MixinShaderInstance` (`CompiledShaderProgram`), `MixinFrustum` (+ Sodium compat mixins), `MixinLevelRenderer_ForceMainThreadRebuild` — per-class sections above.

**Already ported in the mod** (`Portal 26.2/common/.../seamlessportals/`): `FrontClipping` (adapted — view-space plane, see §2.2), `ShaderCodeTransformation`, `VisibleSectionDiscovery`, `lateUpdateLight` (in `PortalWorldManager` + `GameRendererMixin`, commit 3a2c14e), plus functional analogs of `MyGameRenderer`'s context switch (`PortalContextSwitch`), `DimensionRenderHelper`, the secondary `RenderBuffers` pool (`PortalRenderBuffersPool`), and a stencil renderer (`StencilPortalRenderer`). These analogs are *adapted*, not 1:1 — the migration must audit each against the IP originals documented here.
