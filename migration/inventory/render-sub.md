# IP Slice Inventory: Render Context / Optimization / Renderer Family

**Slice scope (14 files, ~2,220 LOC total):**
- `qouteall/imm_ptl/core/render/context_management/` — RenderStates, PortalRendering, WorldRenderInfo, FogRendererContext, StaticFieldsSwappingManager, DimensionRenderHelper, CloudContext
- `qouteall/imm_ptl/core/render/optimization/` — GLResourceCache, SharedBlockMeshBuffers
- `qouteall/imm_ptl/core/render/renderer/` — PortalRenderer (abstract base), RendererUsingStencil, RendererUsingFrameBuffer, RendererDebug, RendererDummy

All files are **client-only** (PortalRendering is annotated `@Environment(EnvType.CLIENT)` at `PortalRendering.java:27`; the rest live exclusively on client codepaths — none is referenced from server logic).

Source root: `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/` (IP 1.21.3, Mojang mappings). All `file:line` citations below are relative to that root unless the path starts with a package other than `imm_ptl`.

---

## 1. Overview

This slice is the **brain of IP's recursive portal rendering**. It has two halves:

1. **Context management** — a set of static, stack-shaped state holders that answer, at any instant during a frame, "what are we rendering right now?" `PortalRendering` holds the stack of portals currently being recursed into (the *logical* portal layer stack); `WorldRenderInfo` holds the stack of world-render *tasks* (dimension + camera pos + camera matrix + render distance + per-task flags), which is a superset use-case (also used by cross-portal third-person view and GUI portal rendering, where `PortalRendering.isRendering()` is false — see the comment at `imm_ptl/core/render/context_management/WorldRenderInfo.java:141-145`). `RenderStates` is the per-frame scratchpad (original player dim/pos/camera, partial tick, per-frame counters, laggy detection, view-bobbing damping). `FogRendererContext` + `StaticFieldsSwappingManager` solve the "vanilla stores dimension-specific state in static fields" problem by check-pointed swap-in/swap-out of those statics per dimension. `DimensionRenderHelper` gives each dimension its own `LightTexture`. `CloudContext` is a (currently disabled) cloud-mesh cache.

2. **The renderer family** — the strategy objects that actually draw portals. `PortalRenderer` (abstract) owns portal discovery/culling (`getPortalsToRender`), the per-portal render-range/render-distance policy, the construction of the `WorldRenderInfo` for each recursion step, and the runtime renderer-switch logic (stencil vs framebuffer vs debug vs dummy vs the Iris-compat renderers). `RendererUsingStencil` is the flagship: it implements true same-framebuffer recursion using the stencil buffer, where the stencil value of a pixel equals the portal-layer depth of that pixel. `RendererUsingFrameBuffer` is the one-layer compatibility fallback that renders the destination into a secondary FBO and textures it onto the portal quad. These hook into vanilla via `MixinGameRenderer` / `MixinLevelRenderer` at fixed points of `GameRenderer.render` / `LevelRenderer.renderLevel` and delegate world re-rendering to `MyGameRenderer.renderWorldNew` (render package, separate slice).

The recursion is **re-entrant vanilla world rendering**: to render what's inside a portal, IP calls the whole `LevelRenderer.renderLevel` pipeline again (with swapped world/renderer/camera/lightmap), and that inner run hits the same mixin hooks — including `onBeforeTranslucentRendering`, which renders *nested* portals, recursing until `getMaxPortalLayer()`.

---

## 2. Class-by-class inventory

### 2.1 `context_management/RenderStates.java` (~294 LOC, client)

**Responsibility:** static per-frame render state — snapshot of the "real" player/camera at frame start, per-frame portal render bookkeeping, lag detection, view-bobbing damping near portals, partial-tick source of truth.

**Key public API:**
- `static void updatePreRenderInfo(float newPartialTick)` (`RenderStates.java:87-132`) — the frame-start reset. Captures `originalPlayerDimension/Pos/LastTickPos` from `Minecraft.cameraEntity` (:98-100), game mode from `PlayerInfo.getGameMode()` (:101-102), rotates `portalRenderInfos` → `lastPortalRenderInfos` (:106-107), calls `FogRendererContext.update()` (:110), captures `originalCamera = client.gameRenderer.getMainCamera()` (:117), nulls `basicProjectionMatrix` (:116), computes `originalPlayerBoundingBox` expanded backwards by velocity (:128-131).
- `static void onTotalRenderEnd()` (`RenderStates.java:207-221`) — restores the `GameRenderer` lightmap to the current `client.level` dimension's `DimensionRenderHelper.lightmapTexture` via the `IEGameRenderer` duck (:209-211); computes `cameraPosDelta` (zeroed if length² > 1, :213-217).
- `static float getPartialTick()` / `setPartialTick(float)` (:246-256) — **not** always equal to vanilla frame time; it is 0 right after ticking (doc comment :250-253).
- `static int getRenderedPortalNum()` (:223-225) — `portalRenderInfos.size()`, used as the per-frame portal cap counter.
- `static boolean isDimensionRendered(ResourceKey<Level>)` (:227-232).
- `static boolean shouldRenderParticle(Particle)` (:234-244) — particle's duck-typed world must equal `Minecraft.level`, and while portal rendering, particle center must satisfy `renderingPortal.isOnDestinationSide(pos, 0.5)` (:241).
- `static double getViewBobbingOffsetMultiplier()` (:184-196) — `viewBobFactor * PortalRendering.getExtraModelViewScaling()`, gated by `IPGlobal.viewBobbingReduce` and `WorldRenderInfo.isViewBobbingEnabled()`.
- `static List<String> collectDebugText()` (:258-293) — F3 text.

**State that matters:** `frameIndex` (incremented once per `GameRenderer.render` at `mixin/client/render/MixinGameRenderer.java:99`; the occlusion-query prediction in `portal/PortalRenderInfo.java:155-171` is keyed to it); `portalRenderInfos: List<List<WeakReference<Portal>>>` (one entry per rendered portal-world, each entry the full layer stack — appended by `PortalRendering.onBeginPortalWorldRendering`); `portalsRenderedThisFrame` ("mixins to sodium use that", :62); `renderedDimensions` set; `basicProjectionMatrix` (captured/replayed by `MixinGameRenderer.redirectGetBasicProjectionMatrix`, `MixinGameRenderer.java:277-302` — the *view-bobbing-free* projection reused for inner layers); `originalCamera`; `isLaggy` (mirror-room lag-attack protection: set when >10 portals last frame and avg FPS < 8 or min FPS < 6, cleared at min FPS > 15, gated by `IPGlobal.lagAttackProof`, :135-156); `viewBobFactor` (ramps to 0 within 2 blocks of a portal — instant decrease, `Mth.lerp(0.1,...)` recovery, :158-205); `shouldForceDisableCull` (consumed by `MixinGlStateManager.onEnableCull`, `mixin/client/render/MixinGlStateManager.java:27-32`, set by `MixinMultiBufferSourceBufferSource`); `isRenderingPortalWeather` (set around the weather pass at `MixinLevelRenderer.java:376,389`, read by `MixinRenderSystem_Clipping`); `renderedScalingPortal`; `originalPlayerBoundingBox` (used by `CrossPortalEntityRenderer` :269,409).

**Dependencies (IP):** ClientWorldLoader, CHelper, McHelper, IPGlobal, FogRendererContext, PortalRendering, WorldRenderInfo, MyRenderHelper, QueryManager, ForceMainThreadRebuild, ClientPerformanceMonitor, StableClientTimer, BlockManipulationClient, ducks `IEGameRenderer`/`IEEntity`/`IEParticle`.

**Vanilla touched:** `Minecraft` (cameraEntity, level, gameRenderer, gui, player), `Camera.getPosition`, `PlayerInfo.getGameMode`, `GameType`, `Particle.getBoundingBox`, `Entity.position/getBoundingBox/getEyePosition`, `Component.translatable`, `Mth.lerp`, `ResourceKey<Level>`, JOML `Matrix4f`.

### 2.2 `context_management/PortalRendering.java` (~228 LOC, client)

**Responsibility:** the portal-layer stack — which portal chain is currently being recursed into — plus every policy question derived from it (camera position after N transforms, active clipping plane, mirror parity, recursion validity, Sodium cave-culling policy).

**Key public API:**
- `static void pushPortalLayer(Portal)` / `popPortalLayer()` (`PortalRendering.java:33-41`) — push/pop + cache refresh. `updateCache()` (:43-53) recomputes `isRenderingCache = getPortalLayer() != 0` and counts `Mirror` instances for `isRenderingOddNumberOfMirrorsCache` (odd → mirror face culling must flip; consumed at `MixinLevelRenderer.java:203-205` and in the sky pass wrap at :434-436).
- `static int getPortalLayer()` (:58-60) — stack size; 0 = outer world, 1 = inside portal, 2 = portal-in-portal (doc comment :55-57).
- `static boolean isRendering()` (:62-64); `static Portal getRenderingPortal()` (:81-83) — innermost portal, `peek()`.
- `static int getMaxPortalLayer()` (:70-75) — 1 when `RenderStates.isLaggy`, else `IPGlobal.maxPortalLayer`.
- `static void onBeginPortalWorldRendering()` (:85-97) — snapshots the current layer stack into `RenderStates.portalRenderInfos` as weak refs, increments `portalsRenderedThisFrame`, sets `renderedScalingPortal` if any layer `hasScaling()`, `CHelper.checkGlError()`.
- `static void onEndPortalWorldRendering()` (:99-103) — records `peek().getDestDim()` into `RenderStates.renderedDimensions`.
- `static Vec3 getRenderingCameraPos()` (:105-111) — **the camera transformation composition**: starts from `RenderStates.originalCamera.getPosition()` and folds `portal.transformPoint(pos)` over the stack bottom→top.
- `static double getExtraModelViewScaling()` (:113-121) — product of `portal.getScale()` over layers where `!PortalRenderer.shouldApplyScaleToModelView(portal)` (i.e. scale that is NOT baked into the model-view).
- `static boolean shouldEnableSodiumCaveCulling()` (:134-148) — only while rendering, never for `BoxPortalShape`, and only when the inverse-transformed camera is < 5 blocks from the portal (rationale comment :123-133).
- `static boolean shouldRenderHitResult()` (:153-176) — false in mirrors; for block hits requires the block center on the positive side of `getInnerClipping().move(-0.1)`.
- `static @Nullable Plane getActiveClippingPlane()` (:178-214) — innermost portal's `getInnerClipping()`; if null (scale-box rendering-group case), walks **outward** through the stack for the nearest non-null plane and transforms it inward layer by layer with `portal1.transformPoint(pos)` / `portal1.transformLocalVecNonScale(normal)` (:197-203). Consumed by `FrontClipping.setupInnerClipping` around `renderSectionLayer` (`MixinLevelRenderer.java:195-201`) and the weather pass (:371-375).
- `static boolean isInvalidRecursionRendering(Portal toRender)` (:216-227) — with ≥2 layers, forbids rendering `toRender` when it equals the second-innermost layer AND `Portal.isReversePortal(toRender, last)` — the A→B→A oscillation guard.

**Dependencies (IP):** Portal, Mirror, BoxPortalShape, PortalRenderer, RenderStates, CHelper, IPGlobal, VisibleSectionDiscovery (javadoc ref only), `q_misc_util.my_util.Plane`.

**Vanilla touched:** `Minecraft.hitResult`, `BlockHitResult/HitResult.Type.BLOCK`, `BlockPos`, `Vec3.atCenterOf`, Fabric `@Environment(EnvType.CLIENT)`.

### 2.3 `context_management/WorldRenderInfo.java` (~279 LOC, client)

**Responsibility:** an immutable "world rendering task" descriptor plus the static stack of active tasks. This is the interchange format between everything that wants a world rendered (portal recursion, GUI portal, cross-portal third-person) and `MyGameRenderer.renderWorldNew`.

**Instance fields (all `public final`, `WorldRenderInfo.java:32-71`):** `ClientLevel world`; `Vec3 cameraPos`; `boolean overwriteCameraTransformation`; `@Nullable Matrix4f cameraTransformation` (if overwrite: replaces the camera rotation entirely; else: multiplied onto it — doc comment :41-46); `@Nullable UUID description` (visibility-prediction key, :50-54); `int renderDistance` ("It cannot render the chunks that are not synced to client", :56-59); `boolean doRenderHand`; `enableViewBobbing`; `doRenderSky`; `hasFog`; `@Nullable IsometricParameters isometricParameters` (**empty record, "NOT yet implemented"** — :25-27, :70-71).

**Construction:** private ctor (:78-101); use `WorldRenderInfo.Builder` (:197-278) — defaults: `overwriteCameraTransformation=true`, `renderDistance = Minecraft.options.getEffectiveRenderDistance()`, `doRenderHand=false`, `enableViewBobbing=true`, `doRenderSky=true`, `hasFog=true` (:200-208). `build()` validates world+cameraPos non-null (:269-277).

**Static stack API:**
- `pushRenderInfo` / `popRenderInfo` (:103-111) — push/pop + invalidate `renderingDescCache`. Called only from `MyGameRenderer.renderWorldNew` (`render/MyGameRenderer.java:100,111`) which brackets `switchAndRenderTheWorld`.
- `static void adjustCameraPos(Camera camera)` (:113-118) — if a task is active, force the vanilla `Camera` position to `getTopRenderInfo().cameraPos` via `IECamera.portal_setPos`. Wired at `Camera.setup` RETURN (`mixin/client/render/MixinCamera.java:41-51`) — **this is how the inner camera gets its transformed position.**
- `static void applyAdditionalTransformations(PoseStack)` (:120-139) — iterates the stack **bottom→top** (`Stack` iteration order = insertion order); for each task: if `overwriteCameraTransformation`, sets pose AND normal to identity (:122-125); then multiplies `cameraTransformation` into the pose, and into the normal matrix a copy scaled by `pow(1/|det|, 1/3)` so it doesn't scale normals (:127-137). Reached via `TransformationManager.processTransformation` (`render/TransformationManager.java:88-102`), which is wrapped around vanilla's `Matrix4f.rotation(Quaternionfc)` camera-rotation construction in `renderLevel` (`MixinGameRenderer.java:304-317`) — **this is how the inner camera gets its rotation/mirror/scale.**
- `isRendering()` (:146-148); `getRenderingLayer()` (:150-152); `getRenderingDescription()` (:155-162) — cached `List<UUID>` of stack descriptions ("rendering portal B inside portal A always has the same description", :154) — the occlusion-query prediction key; `getRenderDistance()` (:164-170) — top task's RD or vanilla effective RD; `getTopRenderInfo()` (:172-174); `getCameraPos()` (:176-179); `isViewBobbingEnabled()` — `allMatch` over the stack (:181-183); `isFogEnabled()` (:185-195, honors `IPGlobal.debugDisableFog`).

**Notable consumers of the stack state (other slices, verified):** terrain-setup override + camera-repoint (`MixinLevelRenderer.java:243-263`), `allChanged` reload cancellation during portal rendering (:394-400), sky-pass skip when `!doRenderSky` (:425-431), spectator-flag forcing for chunk culling (:469-474), `LevelRenderer` cam-pos overrides (`MixinLevelRenderer_Optional.java:93-120`), fog toggle (`MyRenderHelper.java:498`), RD source for `VisibleSectionDiscovery` (`render/VisibleSectionDiscovery.java:119`), teleport sanity assert (`teleportation/ClientTeleportationManager.java:461`).

**Vanilla touched:** `ClientLevel`, `Camera`, `Minecraft.options.getEffectiveRenderDistance()`, `PoseStack` (`last().pose()/normal()`), JOML `Matrix4f/Matrix3f` (determinant-normalization), `UUID`.

### 2.4 `context_management/FogRendererContext.java` (~115 LOC, client)

**Responsibility:** per-dimension save/restore of vanilla `FogRenderer`'s **static** fog state, plus computing "what would the fog color be at position X in world W" without corrupting the current dimension's fog interpolation.

**Key API / mechanics:**
- Instance fields mirror the vanilla statics: `red, green, blue, targetBiomeFog, previousBiomeFog, biomeChangedTime` (`FogRendererContext.java:22-27`).
- Static plumbing `copyContextFromObject` / `copyContextToObject` / `getCurrentFogColor` (:29-31) are **assigned inside `MixinFogRenderer`'s static initializer** (`mixin/client/multiworld_awareness/MixinFogRenderer.java:24-47`), which `@Shadow`s the six private statics of `net.minecraft.client.renderer.FogRenderer` (:11-22) and then calls `FogRendererContext.init()` (:46). `init()` itself force-loads the `FogRenderer` class (`FogRenderer.class.hashCode()`, `FogRendererContext.java:37`) and builds the `swappingManager` (non-strict, with constructor supplier) (:39-43).
- `static void update()` (:47-63) — called once per frame from `RenderStates.updatePreRenderInfo` (`RenderStates.java:110`): sets outer dimension, `resetChecks()`, ensures a `ContextRecord` exists per client world.
- `static Vec3 getFogColorOf(ClientLevel destWorld, Vec3 pos)` (:65-109) — pushes a fog-context swap, **temporarily reassigns `Minecraft.level = destWorld`** (:84), builds a throwaway `new Camera()` positioned via `IECamera.portal_setPos` and focused via `portal_setFocusedEntity(client.cameraEntity)` (:86-88), invokes vanilla `FogRenderer.setupColor(newCamera, partialTick, destWorld, effectiveRenderDistance, darkenWorldAmount)` (:91-97), reads the color through `getCurrentFogColor`, then pops the swap and restores `client.level` in a `finally` (:103-108). Used by `RendererUsingStencil.replaceFrameBufferClearing`'s sky-color triangle and `ViewAreaRenderer` (fog-colored portal quad).
- `static void onPlayerTeleport(from, to)` (:111-113) — delegates to `swappingManager.updateOuterDimensionAndChangeContext(to)` so the statics stay owned by the new outer dimension.

**Vanilla touched:** `FogRenderer.setupColor` + its six private static fields (shadowed), `Minecraft.level` (write!), `Camera` (new instance), `GameRenderer.getDarkenWorldAmount`, `Minecraft.getProfiler().push/pop`.

### 2.5 `context_management/StaticFieldsSwappingManager.java` (~162 LOC, client, generic)

**Responsibility:** the generic mechanism behind FogRendererContext — "sometimes minecraft stores some dimension-specific things into static fields... we have to store multiple sets of these static fields" (`StaticFieldsSwappingManager.java:14-17`).

**Key API:** `pushSwapping(newDimension)` (:87-102) — saves the statics into the current dimension's `ContextRecord`, loads the new dimension's record into the statics; `popSwapping()` (:104-111) — inverse; `swapAndInvoke(dim, Runnable)` (:113-117); `updateOuterDimensionAndChangeContext(newDim)` (:148-159) — teleport-time re-basing without a push; `setOuterDimension` / `resetChecks` (:63-75); `getCurrentDimension()` (:77-85). Each `ContextRecord` tracks `isHoldingLatestContext` (:29-36) — whether the object or the statics own the truth; `strictCheck` mode `Validate`s that invariant on every transfer (:126-127, :140-141). The `contextMap` is public and managed by the client (`FogRendererContext.update`).

**Vanilla touched:** only `ResourceKey<Level>` as the map key. Pure data structure otherwise.

### 2.6 `context_management/DimensionRenderHelper.java` (~41 LOC, client)

**Responsibility:** per-dimension `LightTexture` (lightmap). The player's own dimension reuses `client.gameRenderer.lightTexture()`; any other dimension gets `new LightTexture(client.gameRenderer, client)` (`DimensionRenderHelper.java:15-27`). `tick()` ticks the texture only when it is not the main one (:29-33); `cleanUp()` closes it under the same condition (:35-39).

**Lifecycle (owned by ClientWorldLoader, adjacent slice):** stored in `ClientWorldLoader.RENDER_HELPER_MAP` (`ClientWorldLoader.java:69-70`), lazily created in `getDimensionRenderHelper` with a dimension-identity `Validate` (:339-352), seeded for the initial dimension at init (:379-382), ticked every client tick with lightmap-texture-conflict detection and recovery logging (:127-145), disposed wholesale by `disposeRenderHelpers` (:149-152) and per-dimension on dynamic dimension removal (:256-259). During portal rendering, `MyGameRenderer.switchAndRenderTheWorld` installs the dest helper's lightmap into the `GameRenderer` via `IEGameRenderer.ip_setLightmapTextureManager` (`MyGameRenderer.java:136-138,173`), and `RenderStates.onTotalRenderEnd` restores the outer one (`RenderStates.java:209-211`).

**Vanilla touched:** `LightTexture` (ctor, `tick`, `close`), `Minecraft.level`, `GameRenderer.lightTexture()`.

### 2.7 `context_management/CloudContext.java` (~83 LOC, client) — **currently inert**

**Responsibility:** cache of built cloud vertex buffers keyed by (cloud block x/y/z, dimension, cloud color within 2.0E-4 distance²) so portal rendering doesn't rebuild the cloud mesh each layer (`CloudContext.java:54-74`); LRU-ish cap of 15 with `dispose()` of evicted `VertexBuffer`s (:76-82); cleanup registered on `IPCGlobal.CLIENT_CLEANUP_EVENT` and `ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` (:30-33).

**IMPORTANT:** its sole consumer, `mixin/client/render/optimization/MixinLevelRenderer_Clouds.java`, is **entirely commented out** with a `// TODO re-implement` marker (`MixinLevelRenderer_Clouds.java:25-148`) — 1.21.x rewrote vanilla cloud rendering, and IP 1.21.3 never re-enabled this optimization. The class is registered (`IPModMainClient.java:97`) but dead weight. Port implication: on 26.2 this is a "re-implement or consciously keep inert" item, not a straight port.

### 2.8 `optimization/GLResourceCache.java` (~50 LOC, client)

**Responsibility:** batch-allocates GL object names to avoid per-call `glGenBuffers`/`glGenVertexArrays` driver overhead: reserves 1000 ids at a time via the injected generator (`GLResourceCache.java:24-41`); two static instances `bufferCache` (GL15::glGenBuffers) and `vertexArrayCache` (GL30::glGenVertexArrays) (:17-18). `init()` is empty (:33-35) — exists to force classloading from `IPModMainClient.init` (`IPModMainClient.java:91`). `getExpectedBufferSize()` (:43-49) is a private, uncalled sizing note.

**Wiring:** `MixinGlStateManager` intercepts `GlStateManager._glGenBuffers()` / `_glGenVertexArrays()` at HEAD and substitutes cached ids when `IPGlobal.cacheGlBuffer` is on (`mixin/client/render/MixinGlStateManager.java:34-56`). The same mixin also hosts the `RenderStates.shouldForceDisableCull` cull override (:22-32).

**Vanilla touched:** `GlStateManager._glGenBuffers/_glGenVertexArrays` (mixin, `remap=false`), raw `GL15/GL30`.

### 2.9 `optimization/SharedBlockMeshBuffers.java` (9 LOC)

`@Deprecated`, empty `init()` (`SharedBlockMeshBuffers.java:3-8`), still invoked at `IPModMainClient.java:99`. Nothing to port.

### 2.10 `renderer/PortalRenderer.java` (~360 LOC, abstract, client)

**Responsibility:** base strategy + all shared policy: which portals to render, how far, at what render distance, what `WorldRenderInfo` to spawn per recursion, portal→matrix helpers, and renderer switching.

**Hook contract (implemented by subclasses, invoked from mixins):**
- `abstract void onBeforeTranslucentRendering(Matrix4f modelView)` (`renderer/PortalRenderer.java:63`) — called inside the main pass just before translucent-item rendering (`MixinLevelRenderer.java:138-148`, anchored at `Sheets.translucentItemSheet()` inside `method_62214`, the `addMainPass` lambda). **This is where all portal rendering happens** — and because it fires again inside the re-entrant world render, it is the recursion point.
- `abstract void onHandRenderingEnded()` (:66) — `GameRenderer.renderLevel` TAIL (`MixinGameRenderer.java:162-167`).
- `void onBeforeHandRendering(Matrix4f modelView)` (:69, default no-op) — after `LevelRenderer.renderLevel` returns, before hand (`MixinGameRenderer.java:169-184`, `@WrapOperation`).
- `abstract void prepareRendering()` / `finishRendering()` (:72-75) — bracket the outer (non-portal) `GameRenderer.renderLevel` call (`MixinGameRenderer.java:103-131`); NOT called for inner layers. Also invoked by `GuiPortalRendering` (`render/GuiPortalRendering.java:70-74`).
- `abstract void renderPortalInEntityRenderer(Portal)` (:78) — from `PortalEntityRenderer` (`render/PortalEntityRenderer.java:35`); only the Iris renderers use it.
- `abstract boolean replaceFrameBufferClearing()` (:82) — redirect of `RenderSystem.clear(int)` in `method_62218` (a `renderLevel` lambda) (`MixinLevelRenderer.java:308-320`); return true to skip vanilla's clear.
- `void onBeginIrisTranslucentRendering(Matrix4f)` (:306, no-op) — Iris hook (`MixinLevelRenderer_BeforeIris.java:22`).

**Portal discovery & culling:**
- `protected List<Portal> getPortalsToRender(Matrix4f modelView)` (:84-121) — lazily builds a `Frustum(modelView, RenderSystem.getProjectionMatrix())` prepared at the main camera position (:85-95); candidates = `GlobalPortalStorage.getGlobalPortals(client.level)` (:101-106) + every `Portal` in `client.level.entitiesForRendering()` (:108-114); filters via `shouldSkipRenderingPortal`; sorts near→far by `getDistanceToNearestPointInPortal(CHelper.getCurrentCameraPos())` (:116-120).
- `private static boolean shouldSkipRenderingPortal(portal, frustumSupplier)` (:123-176) — skip if: `!portal.isPortalValid()`; `!portal.isVisible()` unless `IPGlobal.maxPortalLayer == 0` (force-render invisible portals in that debug mode, comment :128); `RenderStates.getRenderedPortalNum() >= IPGlobal.portalRenderLimit`; `!portal.isRoughlyVisibleTo(cameraPos)` where cameraPos = `TransformationManager.getIsometricAdjustedCameraPos()` (:137-141); while rendering, `outerPortal.cannotRenderInMe(portal)` (:143-149); `distance > getRenderRange()`; if `IPCGlobal.earlyFrustumCullingPortal` and distance > 0.1 (frustum culling broken very close, comment :157), `!frustum.isVisible(portal.getThinBoundingBox())` (:156-164); `PortalRendering.isInvalidRecursionRendering(portal)` (:166-168); `PORTAL_RENDERING_PREDICATE` event fails (:170-173) — a Fabric `EventFactory.createArrayBacked` predicate-AND event (:48-59).
- `static double getRenderRange()` (:178-195) — `effectiveRenderDistance * 16`; clamped to 16 when laggy or `IPGlobal.reducedPortalRendering`; divided by layer for layers > 1 ("do not render deep layers of mirror when far away"); multiplied by outer portal scale (capped 32*16) when outer scale > 2.

**Recursion step:**
- `protected final void renderPortalContent(Portal portal)` (:197-231) — returns if `getPortalLayer() > getMaxPortalLayer()`; resolves dest world via `ClientWorldLoader.getWorld(portal.getDestDim())` (:204); `PortalRendering.onBeginPortalWorldRendering()`; builds the recursion `WorldRenderInfo`: camera pos = `PortalRendering.getRenderingCameraPos()`, `cameraTransformation = portal.getAdditionalCameraTransformation()`, `overwriteCameraTransformation = false` (**stacked, not replaced**), description = `portal.getDiscriminator()` (= `portal.getUUID()`, `portal/Portal.java:1037-1039`), renderDistance = `getPortalRenderDistance(portal)`, no hand, view bobbing on, `doRenderSky = !portal.isFuseView()` (:210-222); `invokeWorldRendering(...)`; `onEndPortalWorldRendering()`; `GlStateManager._enableDepthTest()`; `MyRenderHelper.restoreViewPort()` (:226-228).
- `private static int getPortalRenderDistance(Portal)` (:233-247) — scale > 2 → `destAreaRadiusEstimation * 1.4` blocks capped at 32*16, /16 to chunks, min vanilla RD; `reducedPortalRendering` → RD/3; else vanilla RD.
- `void invokeWorldRendering(WorldRenderInfo)` (:249-256) → `MyGameRenderer.renderWorldNew(worldRenderInfo, Runnable::run)` — the handoff to the world-switch machinery (adjacent slice; it does `WorldRenderInfo.pushRenderInfo` → swap world/renderer/camera/lightmap/fog → re-run `renderLevel` → restore, `MyGameRenderer.java:96-112`).

**Matrix helpers:** `getPortalTransformation(portal)` = rotation · (mirror · scale), each nullable, combined via `combineNullable` (:258-287); `getPortalRotationMatrix` = **conjugated** `portal.getRotation()` quaternion → Matrix4f (:270-279); `getPortalScaleMatrix` = uniform `1/portal.getScale()` **only when** `shouldApplyScaleToModelView(portal)` ≡ `portal.hasScaling() && portal.isFuseView()` (:289-304; rationale comment :291-294: applying scale to model-view for non-fuse-view portals only causes abrupt fog changes; fuse-view needs correct depth values).

**Renderer switching:** `static void switchToCorrectRenderer()` (:310-348) — never switches mid-rendering (:311-314); one-time FABULOUS graphics warning (`GraphicsStatus.FABULOUS`, :316-321); Iris-present branches to the iris renderers per `IPGlobal.renderMode`; otherwise `normal → IPCGlobal.rendererUsingStencil`, `compatibility → rendererUsingFrameBuffer`, `debug → rendererDebug`, `none → rendererDummy` (:342-347). `switchRenderer` logs and reloads Iris pipelines on change (:350-359).

### 2.11 `renderer/RendererUsingStencil.java` (~313 LOC, client)

**Responsibility:** the recursive same-framebuffer stencil renderer. Invariant: **a pixel's stencil value == the portal-layer depth that owns that pixel.** Full mechanism in §3.2.

**Key API (beyond the base contract):**
- `prepareRendering()` (`renderer/RendererUsingStencil.java:87-104`) — ensures the main `RenderTarget` has a stencil attachment via `IPPortingLibCompat.getIsStencilEnabled/setIsStencilEnabled` (:88-90; on Fabric this is IP's own `IEFrameBuffer` stencil toggle, on Forge PortingLib's), binds it, `glClearStencil(0)` + clears `GL_STENCIL_BUFFER_BIT`, enables depth test and `GL_STENCIL_TEST`.
- `replaceFrameBufferClearing()` (:36-46) — inner layers skip the vanilla clear entirely; if the task wants sky, draws a full-screen triangle in the current fog color (`FogRendererContext.getCurrentFogColor`) with depth writes off — this is the "sky background" of an inner layer.
- `doPortalRendering(Matrix4f)` (:53-71) — after rendering the nested portals, either re-arms the stencil for the (still-active) inner world rendering (:63-65) or, at layer 0, `myFinishRendering()` (:111-117): `glStencilFunc(GL_ALWAYS, 2333, 0xFF)`, ops KEEP, disable stencil test (NOT done in `finishRendering()` because outer-world translucency renders after — comment :67-69).
- `static void clampStencilValue(int maximumValue)` (:249-278) and `static void setStencilLimitation(int stencilValue)` (:286-292) — public stencil utilities (also used by Iris renderers).
- `static boolean shouldSkipRenderingInsideFuseViewPortal(Portal)` (:294-312) — inside a fuse-view portal, skip portals whose double transform round-trips the camera (`distanceToSqr < 0.1` ⇒ reverse portal).

**Dependencies (IP):** ViewAreaRenderer (portal-shape mesh draw), FrontClipping (clip plane), MyRenderHelper (`renderScreenTriangle`), PortalRenderInfo (occlusion query + prediction), FogRendererContext, PortalRendering, WorldRenderInfo, IPPortingLibCompat, CHelper.

**Vanilla/GL touched:** `RenderSystem.enableDepthTest/depthMask/getProjectionMatrix`, `GlStateManager._enableDepthTest/_depthMask/_disableDepthTest` (note comment :54-57: must use GlStateManager, not raw glDisable, to keep its state cache coherent), `Minecraft.getMainRenderTarget().bindWrite(false)`, `Minecraft.useShaderTransparency()`, `Profiler.get().popPush/push/pop`, and raw `GL11`: `glClearStencil, glClear, glEnable/glDisable(GL_STENCIL_TEST), glStencilFunc, glStencilOp, glStencilMask, glColorMask, glDepthFunc, glGetInteger(GL_DEPTH_FUNC), glDepthRange, glDepthMask`.

### 2.12 `renderer/RendererUsingFrameBuffer.java` (~136 LOC, client)

**Responsibility:** compatibility renderer — **one layer only** (`renderer/RendererUsingFrameBuffer.java:57-60`), renders the destination world into a `SecondaryFrameBuffer` and then draws it through the portal shape into the main buffer.

**Flow of `doRenderPortal`** (:53-92): occlusion-test the view area via `QueryManager.renderAndGetDoesAnySamplePass` over `ViewAreaRenderer.renderPortalArea` after `FrontClipping.updateInnerClipping(modelView)` (:104-118); push layer; swap Minecraft's main render target to the secondary via `((IEMinecraftClient) client).ip_setFrameBuffer(secondaryFrameBuffer.fb)` + `bindWrite(true)` (:68-71); clear color to magenta (1,0,1,1) + depth, stencil test off (:73-78); `renderPortalContent(portal)`; restore target; pop layer; `CHelper.enableDepthClamp()` → `MyRenderHelper.drawPortalAreaWithFramebuffer(portal, fb, modelView, projection)` (:120-127) → `disableDepthClamp`; `MyRenderHelper.debugFramebufferDepth()`.

`prepareRendering` (:42-51): `secondaryFrameBuffer.prepare()`, depth test on, stencil test off, and **disables** the main target's stencil (`IPPortingLibCompat.setIsStencilEnabled(main, false)` :49).

**Vanilla touched (beyond stencil renderer's set):** `RenderTarget` (main target swap via duck), `GlStateManager._clearColor/_clearDepth/_clear(int)`.

### 2.13 `renderer/RendererDebug.java` (~98 LOC, client) — **stale in this checkout**

Renders only the first portal (`RendererDebug.java:54-56`), clears to magenta and renders content directly to the main buffer (:64-72) after an occlusion query (:77-89). **Consistency warnings:** it declares `@Override onAfterTranslucentRendering(Matrix4f)` (:23-26) which **does not exist** in the abstract `PortalRenderer` (verified against the full base class, `PortalRenderer.java:42-360`) — an un-compilable leftover; and it calls the two-arg `GlStateManager._clear(flags, Minecraft.ON_OSX)` (:66-69) while `RendererUsingFrameBuffer` uses the one-arg `_clear(flags)` (`RendererUsingFrameBuffer.java:75-77`). Treat this file as bit-rotted in the 1.21.3 tree; port its *intent* (debug mode = clear-and-render-first-portal, no stencil) rather than its text.

### 2.14 `renderer/RendererDummy.java` (~49 LOC, client) — **stale in this checkout**

All-no-op renderer used for `renderMode == none` (`PortalRenderer.java:336,346`). Same bit-rot: `@Override onAfterTranslucentRendering` not present in the base (`RendererDummy.java:23-26`), and its private `doRenderPortal(Portal, PoseStack)` (:38-43) still takes a `PoseStack` (never called; each subclass declares its own `doRenderPortal` — the base class does not).

---

## 3. Mechanisms

### 3.1 The recursion model (who calls whom, per frame)

1. **Frame start** — `GameRenderer.render` HEAD (`MixinGameRenderer.java:70-100`): `RenderStates.updatePreRenderInfo(partialTick)` (which also runs `FogRendererContext.update()`), then teleportation management, then `RenderStates.frameIndex++`.
2. **Before the level render call** (`MixinGameRenderer.java:103-116`): `PortalRenderer.switchToCorrectRenderer()`; `IPCGlobal.renderer.prepareRendering()` (stencil renderer: enable+clear stencil).
3. **Vanilla renders the outer world.** Inside `LevelRenderer`'s main pass, just before translucent items, the mixin fires `IPCGlobal.renderer.onBeforeTranslucentRendering(modelView)` (`MixinLevelRenderer.java:138-148`).
4. **Portal pass** — `getPortalsToRender` → for each portal `doRenderPortal` → (stencil path) occlusion query on the view-area mesh → `PortalRendering.pushPortalLayer(portal)` → `renderPortalContent(portal)` builds a `WorldRenderInfo` and calls `MyGameRenderer.renderWorldNew`, which `WorldRenderInfo.pushRenderInfo`s and **re-enters the entire `LevelRenderer.renderLevel` pipeline** with world/renderer/camera/lightmap/fog swapped.
5. **Re-entrancy = recursion.** The inner `renderLevel` hits the same `onBeforeTranslucentRendering` hook; `getPortalsToRender` now runs in the destination world; `renderPortalContent` refuses depth > `getMaxPortalLayer()` (`PortalRenderer.java:200-202`); `isInvalidRecursionRendering` kills A↔B ping-pong (`PortalRendering.java:216-227`); `cannotRenderInMe` and the range/limit checks bound the tree. Un-recursed inner portals still get stencil-incremented view areas but their world is simply not drawn (they show the fog-color triangle).
6. **Camera state for the inner layer** is delivered through the two static stacks, not through parameters: position via `WorldRenderInfo.adjustCameraPos` at `Camera.setup` RETURN (`MixinCamera.java:41-51`); rotation/mirror/scale via `WorldRenderInfo.applyAdditionalTransformations` wrapped around vanilla's camera-rotation matrix build (`MixinGameRenderer.java:304-317` → `TransformationManager.java:88-102`); projection reuse via `redirectGetBasicProjectionMatrix` (`MixinGameRenderer.java:277-302`) — inner layers reuse the captured bob-free `RenderStates.basicProjectionMatrix`.
7. **After the outer level render** (`MixinGameRenderer.java:119-142`): `finishRendering()`, `RenderStates.onTotalRenderEnd()` (lightmap restore), `GuiPortalRendering._onGameRenderEnd()`, optional late light update.

### 3.2 Stencil value management (RendererUsingStencil.doRenderPortal, `RendererUsingStencil.java:119-164`)

Let `outer = PortalRendering.getPortalLayer()` before pushing (0 for the outer world). The invariant is stencil(pixel) == layer that owns the pixel.

1. **Carve the view area** (`renderPortalViewAreaToStencil`, :171-197): `glStencilFunc(GL_EQUAL, outer, 0xFF)` + `glStencilOp(KEEP, KEEP, INCR)` + `glStencilMask(0xFF)` — draw the portal-shape mesh (`ViewAreaRenderer.renderPortalArea(portal, Vec3.ZERO, modelView, RenderSystem.getProjectionMatrix(), true, true, true, true)`; signature: `(portal, fogColor, modelView, projection, doFaceCulling, doModifyColor, doModifyDepth, doClip)`, `render/ViewAreaRenderer.java:25-30`). Pixels of the portal shape that belong to the current layer AND pass depth get stencil `outer+1`. GL_INCR increments once even with overlapping triangles (comment :181-183). `FrontClipping.updateInnerClipping(modelView)` runs first (:187-188).
2. **Occlusion query + prediction:** step 1 executes inside `PortalRenderInfo.renderAndDecideVisibility(portal, runnable)` (`portal/PortalRenderInfo.java:212-257`): with `IPGlobal.offsetOcclusionQuery` (default path), the query result from the **last frame** for the same rendering description (`WorldRenderInfo.getRenderingDescription()` — the UUID chain) is used as this frame's prediction, falling back to a same-frame fetch (a GPU stall, counted in `QueryManager.queryStallCounter`) when there is no prior result, when the portal is frequently mispredicted, or when few stalls have occurred yet (:228-251). If no samples passed → restore stencil state and skip the portal (`RendererUsingStencil.java:137-140`).
3. `PortalRendering.pushPortalLayer(portal)`; `thisPortalStencilValue = outer + 1` (:142-144).
4. **Depth clear inside the shape** (skipped for fuse-view portals): `clearDepthOfThePortalViewArea` (:199-225) — with stencil limited to the new layer, color writes off, `glDepthFunc(GL_ALWAYS)`, `glDepthRange(1, 1)`, draw a full-screen triangle (`MyRenderHelper.renderScreenTriangle()`) → every view-area pixel's depth becomes 1.0 (farthest); then restore depth func (saved via `glGetInteger(GL_DEPTH_FUNC)`) and `glDepthRange(0, 1)`.
5. **Render the inner world:** `setStencilStateForWorldRendering()` (:280-284) arms `glStencilFunc(GL_EQUAL, thisValue, 0xFF)` with ops KEEP (`setStencilLimitation`, :286-292) so the recursive world render writes color/depth ONLY where stencil == this layer; then `renderPortalContent(portal)` recurses (§3.1). Inner clipping planes for terrain/weather are installed per-pass from `PortalRendering.getActiveClippingPlane()` (`MixinLevelRenderer.java:195-201, 371-375`).
6. `PortalRendering.popPortalLayer()` — deliberately **before** depth restore, "for clipping, see ViewAreaRenderer" (:156-157).
7. **Depth restore** (skipped for fuse-view): `restoreDepthOfPortalViewArea` (:227-247) — stencil limited to `thisPortalStencilValue`, `glDepthFunc(GL_ALWAYS)`, re-draw the portal shape with color modification off but depth writes on (`renderPortalArea(..., false, false, true, true)`; the final `doClip=true` matters for scale-box-from-inside depth, comment :243) → the portal quad's own depth replaces the inner world's, so outer-world objects in front of the portal occlude correctly.
8. **Stencil clamp:** `clampStencilValue(outer)` (:249-278) — `glStencilFunc(GL_LESS, outer, 0xFF)` (passes where `outer < stencil`, note on GL ref-vs-stencil semantics :254-255) with `glStencilOp(KEEP, REPLACE, REPLACE)`, depth+color writes off, depth test off, full-screen triangle → every pixel with stencil > outer is written back to `outer`. This erases the entire subtree's stencil footprint in one draw, restoring the invariant for the next sibling portal.
9. After all portals at this layer: if still inside a portal (recursive call), re-arm `GL_EQUAL` for the remainder of that layer's world render; at layer 0, `myFinishRendering()` disables the stencil test (:63-70, :111-117).

### 3.3 The camera transformation stack

The recursion never mutates the vanilla camera entity; it composes:
- **Position:** `PortalRendering.getRenderingCameraPos()` folds `portal.transformPoint` over the layer stack from `RenderStates.originalCamera.getPosition()` (`PortalRendering.java:105-111`); delivered by forcing `Camera.position` post-`setup` (`WorldRenderInfo.java:113-118`, `MixinCamera.java:41-51`).
- **Orientation/mirror/scale:** each layer contributes `portal.getAdditionalCameraTransformation()` (`Portal.java:1578`; built out of `PortalRenderer.getPortalTransformation` = conjugated-rotation · mirror · (1/scale when fuse-view+scaling), `PortalRenderer.java:258-304`). Because `overwriteCameraTransformation=false` for recursion (`PortalRenderer.java:215`), `applyAdditionalTransformations` multiplies the whole stack bottom→top onto vanilla's camera rotation matrix, with each layer's normal matrix determinant-normalized (`WorldRenderInfo.java:120-139`). GUI/cross-portal-view tasks instead use `overwrite=true` to fully replace the rotation.
- **Projection:** inner layers replay the captured `RenderStates.basicProjectionMatrix` (bob-free) instead of recomputing (`MixinGameRenderer.java:277-302`).

### 3.4 Visibility & culling decision chain (per portal, per layer)

In order: validity → visibility flag (unless maxLayer 0) → per-frame count cap (`IPGlobal.portalRenderLimit`) → rough facing/eye-side test (`isRoughlyVisibleTo`) → outer-portal containment (`cannotRenderInMe`) → distance vs `getRenderRange()` → optional early frustum cull vs `getThinBoundingBox()` (skipped within 0.1 blocks) → reverse-recursion guard → Fabric predicate event (`PortalRenderer.java:123-176`). Surviving portals are distance-sorted, then per-portal GPU occlusion queries with one-frame prediction decide actual world rendering (§3.2 step 2). Terrain-section culling inside a portal layer is handled by `VisibleSectionDiscovery` replacing vanilla `setupRender` while a `WorldRenderInfo` is active (`MixinLevelRenderer.java:234-263`, adjacent slice); Sodium cave culling is only allowed within 5 blocks of a non-box portal (`PortalRendering.java:134-148`).

### 3.5 Static-fields swapping (fog)

`StaticFieldsSwappingManager` records, per dimension, a context object and a "who holds the latest data" bit; `pushSwapping(dim)` copies statics→old-record then new-record→statics, `popSwapping()` reverses (`StaticFieldsSwappingManager.java:87-111`); teleports re-base ownership without pushing (:148-159). Fog is the only remaining client (`FogRendererContext.swappingManager`); the swap is pushed inside `MyGameRenderer.switchAndRenderTheWorld` (`MyGameRenderer.java:179`) and inside `getFogColorOf` (`FogRendererContext.java:83`). Port note: on 26.2, fog state moved into `FogParameters`/fog-environment objects — whether any *static* fog state still exists must be re-checked; the *pattern* ports only if the 26.2 statics do.

---

## 4. MC API touchpoint list (dedup, break-risk items for 26.2 mapping)

Rendering pipeline / GameRenderer / LevelRenderer:
1. `GameRenderer.render(DeltaTracker, boolean)` — inject HEAD + before/after the `renderLevel` invoke + redirect of that invoke (`MixinGameRenderer.java:70,103,119,145`).
2. `GameRenderer.renderLevel(DeltaTracker)` — inject TAIL; `@WrapOperation` on the inner `LevelRenderer.renderLevel(GraphicsResourceAllocator, DeltaTracker, boolean, Camera, GameRenderer, LightTexture, Matrix4f, Matrix4f)` call (`MixinGameRenderer.java:162-184`); redirect of `GameRenderer.getProjectionMatrix(float)` ordinal 0 (:277-285); `@WrapOperation` on `Matrix4f.rotation(Quaternionfc)` (:304-311). **26.2: renderLevel and its lambda structure are rewritten by the framegraph/SubmitNodeCollector pipeline.**
3. `GameRenderer.bobView(PoseStack, float)` — 3× `@ModifyArg` on `PoseStack.translate(FFF)` (`MixinGameRenderer.java:213-253`); `GameRenderer.renderItemInHand` — HEAD/RETURN injects (:202-210); `GameRenderer.resize(II)` (:187); `GameRenderer.getMainCamera()`; `GameRenderer.lightTexture()`; `GameRenderer.getDarkenWorldAmount(float)` (`FogRendererContext.java:96`); private fields `lightTexture`, `mainCamera`, `renderHand`, `panoramicMode` (shadow-mutated, `MixinGameRenderer.java:46-63`).
4. `LevelRenderer` main-pass lambda (`method_62214` in `addMainPass`) — injection anchors: `DimensionSpecialEffects.constantAmbientLight()`, `Sheets.translucentItemSheet()`, `MultiBufferSource$BufferSource.endLastBatch()` ordinal 1, `renderSectionLayer(RenderType,DDD,Matrix4f,Matrix4f)` before/after (`MixinLevelRenderer.java:123-232`); clear-redirect in `method_62218` on `RenderSystem.clear(I)` (:308-320); weather lambda `method_62216` in `addWeatherPass` (:364-391); `addSkyPass` → `FramePass.executes(Runnable)` wrap (:416-442); `setupRender(Camera, Frustum, boolean, boolean)` HEAD/RETURN + spectator `@ModifyVariable` (:234-306, 463-474); `allChanged()` HEAD-cancel + TAIL (:394-414); `ClientLevel.pollLightUpdates()` redirect (:478-489). **All lambda names are version-locked synthetic methods — every one must be re-anchored on 26.2.**
5. `LevelRenderer.renderClouds` + fields `prevCloudX/Y/Z`, `cloudBuffer`, `generateClouds`, `ticks` — the CloudContext consumer (commented out; `MixinLevelRenderer_Clouds.java:29-147`).
6. `Camera` — `new Camera()` (`FogRendererContext.java:86`), `setup(BlockGetter, Entity, boolean, boolean, float)` RETURN inject, `getFluidInCamera()` cancel → `FogType.NONE`, `isDetached()` cancel, private `position/level/entity/eyeHeight/eyeHeightOld` shadows (`MixinCamera.java:20-117`), `Camera.getPosition()`.
7. `FogRenderer.setupColor(Camera, float, ClientLevel, int, float)` + private statics `fogRed/fogGreen/fogBlue/targetBiomeFog/previousBiomeFog/biomeChangedTime` (`FogRendererContext.java:91-97`, `MixinFogRenderer.java:11-22`). **26.2: FogRenderer/fog model rewritten (FogParameters) — headline item.**
8. `Frustum(Matrix4f, Matrix4f)` ctor + `prepare(double,double,double)` + `isVisible(AABB)` (`PortalRenderer.java:86-93,160`).
9. `LightTexture(GameRenderer, Minecraft)` ctor, `tick()`, `close()` (`DimensionRenderHelper.java:24-38`).
10. `RenderSystem.getProjectionMatrix()`, `RenderSystem.enableDepthTest/depthMask`, `RenderSystem.clear(int)` (redirect target). **26.2: RenderSystem global-state model largely dismantled (GpuDevice/RenderPass).**
11. `GlStateManager._enableDepthTest/_disableDepthTest/_depthMask/_enableCull/_disableCull/_clearColor/_clearDepth/_clear/_glGenBuffers/_glGenVertexArrays` (renderers + `MixinGlStateManager.java:14-56`; note the cache-coherency rule at `RendererUsingStencil.java:54-57`). **26.2: GlStateManager is gone/hollowed — the raw-GL stencil choreography (`glStencilFunc/Op/Mask, glDepthFunc/Range, glColorMask, glClearStencil`) must be re-hosted (mod's stencil-direct work confirms raw GL survives).**
12. `RenderTarget` — `Minecraft.getMainRenderTarget()`, `bindWrite(boolean)`, stencil-attachment toggling (via `IPPortingLibCompat`, `RendererUsingStencil.java:88-96`, `RendererUsingFrameBuffer.java:49`), main-target swap via `IEMinecraftClient.ip_setFrameBuffer` (`RendererUsingFrameBuffer.java:70-82`).
13. `VertexBuffer.close()` (`CloudContext.java:48`); `PoseStack.last().pose()/normal()` (`WorldRenderInfo.java:123-136`).
14. `Minecraft` — `level` (**read AND write**, `FogRendererContext.java:84,105`), `cameraEntity`, `player`, `hitResult`, `options.getEffectiveRenderDistance()`, `options.graphicsMode()` + `GraphicsStatus.FABULOUS`, `useShaderTransparency()`, `gui.setOverlayMessage(Component, boolean)`, `ON_OSX` (stale RendererDebug), `getProfiler()` (deprecated form, `FogRendererContext.java:70`) vs `Profiler.get()` (`RendererUsingStencil.java:61`, `MixinGameRenderer.java:74`).
15. `ClientLevel.entitiesForRendering()` (portal discovery, `PortalRenderer.java:108`), `ClientLevel.dimension()`.
16. `DeltaTracker.getGameTimeDeltaPartialTick(true)` (`MixinGameRenderer.java:86`).
17. `PlayerInfo.getGameMode()` / `GameType` (`RenderStates.java:101-102`); `LocalPlayer` (debug text :262).
18. `Particle.getBoundingBox()` (`RenderStates.java:240`).
19. `Entity.position()/getBoundingBox().expandTowards/getEyePosition(float)/level().dimension()` (`RenderStates.java:98-131,167`).
20. JOML: `Matrix4f.mul/identity/scale/rotation`, `Matrix3f` determinant normalization (`WorldRenderInfo.java:131-136`), `Quaternionf.conjugate/get` (`PortalRenderer.java:276-278`); raw LWJGL `GL11/GL15/GL30` per item 11.

---

## 5. Registration & wiring

**No registries, no server hooks, no networking.** Everything is static-init + mixin + one Fabric event:

- **Renderer instances:** `IPCGlobal.renderer` (active), `rendererUsingStencil`, `rendererUsingFrameBuffer`, `rendererDummy = new RendererDummy()`, `rendererDebug = new RendererDebug()` (`IPCGlobal.java:16-20`). Stencil/framebuffer instances are constructed **on the render thread** via `Minecraft.getInstance().execute(...)` in `IPModMainClient.init` (`IPModMainClient.java:76-85`), with stencil as the initial active renderer (:84). Selection re-evaluated every frame by `PortalRenderer.switchToCorrectRenderer()` right before the level render (`MixinGameRenderer.java:113`).
- **Client init order** (`IPModMainClient.init`, `IPModMainClient.java:71-135`): `ClientWorldLoader.init` → `ClientTeleportationManager.init` → (render-thread block above) → `GLResourceCache.init()` (:91) → `PortalRenderInfo.init()` (:95) → `CloudContext.init()` (:97, registers `IPCGlobal.CLIENT_CLEANUP_EVENT` + `ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT`) → `SharedBlockMeshBuffers.init()` (:99, no-op).
- **FogRendererContext** is wired lazily by classloading: `MixinFogRenderer`'s static block assigns the three static function fields and calls `FogRendererContext.init()` (`MixinFogRenderer.java:24-47`); `init()` forces `FogRenderer` classloading (`FogRendererContext.java:35-45`); `update()` runs every frame from `RenderStates.updatePreRenderInfo` (`RenderStates.java:110`); `onPlayerTeleport` is invoked by the teleportation slice.
- **DimensionRenderHelper** lifecycle is owned by `ClientWorldLoader` (creation `ClientWorldLoader.java:339-352,379-382`; tick :127-145; disposal :149-152,256-259).
- **Frame hooks** (all mixin, detailed in §3.1): `MixinGameRenderer` (render HEAD / around renderLevel / renderLevel TAIL / hand / bobView / projection / camera-rotation wrap), `MixinLevelRenderer` (translucent hook, clear redirect, clipping around section layers, weather, sky skip, setupRender override, allChanged guard), `MixinCamera` (position override), `MixinGlStateManager` (cull override + GL-name cache), `MixinLevelRenderer_BeforeIris` (iris hook).
- **Fabric API usage:** `EventFactory.createArrayBacked` for `PORTAL_RENDERING_PREDICATE` (`PortalRenderer.java:48-59`); `@Environment(EnvType.CLIENT)` on PortalRendering; (`ClientCommandRegistrationCallback` in IPModMainClient is adjacent-slice). `IPCGlobal.CLIENT_CLEANUP_EVENT` / `IPGlobal.PRE_GAME_RENDER_EVENT` / `PRE_TOTAL_RENDER_TASK_LIST` are IP's own event/task objects, not Fabric's.
- **External entry points into this slice:** `GuiPortalRendering._onGameRenderEnd` drives `prepareRendering → invokeWorldRendering → finishRendering` for map-GUI portals (`render/GuiPortalRendering.java:70-74`); `CrossPortalViewRendering.renderCrossPortalView` calls `IPCGlobal.renderer.invokeWorldRendering` for third-person-across-portal (`render/CrossPortalViewRendering.java:96`); `PortalEntityRenderer` calls `renderPortalInEntityRenderer` (`render/PortalEntityRenderer.java:35`); `ClientTeleportationManager` re-runs `RenderStates.updatePreRenderInfo` after a mid-frame teleport (`teleportation/ClientTeleportationManager.java:388,451`).

---

## Surprises / port-relevant anomalies

1. **RendererDebug and RendererDummy do not compile against their own base class**: both carry `@Override onAfterTranslucentRendering(Matrix4f)` (`RendererDebug.java:23-26`, `RendererDummy.java:23-26`) but `PortalRenderer` declares no such method (full read, `PortalRenderer.java:42-360`); RendererDebug also uses a two-arg `GlStateManager._clear(flags, Minecraft.ON_OSX)` vs the one-arg form elsewhere. These two niche renderers are bit-rotted in the 1.21.3 tree — port intent, not text, and verify against IP's compiled 1.21.3 jar if exactness is demanded.
2. **CloudContext is dead code in 1.21.3**: its only consumer mixin is fully commented out with "TODO re-implement" (`MixinLevelRenderer_Clouds.java:27`). 26.2's cloud renderer differs again; this is a conscious re-implement-or-skip decision, not a port.
3. **`WorldRenderInfo.IsometricParameters` is an empty, unimplemented record** (`WorldRenderInfo.java:25-27,70-71,263-267`) — carry the field for fidelity but expect no behavior.
4. `PortalRenderer.doRenderPortal` is **not** part of the base-class contract — each concrete renderer declares its own `protected doRenderPortal`; only the shared hooks in §2.10 are polymorphic.
5. The stencil clamp trick (`clampStencilValue`) resets an entire recursion subtree's stencil in one full-screen triangle — an easy detail to get wrong (it uses `GL_LESS` with inverted ref-vs-stencil semantics, documented in-code at `RendererUsingStencil.java:254-255`).
