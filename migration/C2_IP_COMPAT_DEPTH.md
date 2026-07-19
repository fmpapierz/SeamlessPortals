# C2 phase-1 ground truth: IP 1.21.3 sodium/iris compat, full injection-level depth (COVERAGE INFO-3 pass)

**Produced 2026-07-19 by workflow wf_eb56ca0c-52c (4 Opus tracers over the IP upstream tree only).
This is the FIDELITY BASELINE for the C2 re-engineering against Sodium 0.9.1 / Iris 1.11.2 / MC 26.2.
Baseline compiled against Sodium 0.6.0 / Iris 1.8.0. Risk notes are UNVERIFIED speculation until the
phase-2 javap pass over the real 0.9.1/1.11.2 jars.**


---

## TRACER A â€” Sodium compat mixins, full injection-level depth (IP 1.21.3 baseline, compiled against Sodium 0.6.0)

### 0. Gating, registration, and the invoker substrate (applies to ALL 9)

- **Config file**: `src/main/resources/imm_ptl_compat.mixins.json` â€” `package = qouteall.imm_ptl.core.compat.mixin`, `compatibilityLevel JAVA_17`, `plugin = qouteall.imm_ptl.core.compat.IPCompatMixinPlugin`, `injectors.defaultRequire = 1`, `required = true`. All 9 files are registered as `sodium.<ClassName>`.
- **Plugin gate** (`IPCompatMixinPlugin.shouldApplyMixin`): pure class-NAME-substring test. `mixinClassName.contains("IrisSodium")` â†’ needs BOTH sodium+iris; else `contains("Iris")` â†’ iris; else `contains("Sodium")` â†’ `fabricLoader.isModLoaded("sodium")`. All 9 here match on the literal substring `"Sodium"` in the class name (even `IESodiumWorldRenderer`), so they apply iff mod id `sodium` is loaded. `getRefMapperConfig()` returns null. NOTE the substring test means any future rename must keep `Sodium` in the simple name or the gate silently drops the mixin.
- **Runtime invoker swap** (`IPModEntryClient.onInitializeClient`, line 76): when `sodium` is loaded, `SodiumInterface.invoker = new SodiumInterface.OnSodiumPresent()`. Default `SodiumInterface.invoker` is the no-op base `Invoker` (returns false / null / does nothing). The whole compat layer is therefore double-gated: mixin-application gate (loader) AND the invoker polymorphism gate. `FrustumCuller.getCanDetermineInvisibleFunc` early-returns null unless `SodiumInterface.invoker.isSodiumPresent()`.
- **Two-way context-swap driver** (`MyGameRenderer` renderPortalContentWithContextSwitched, lines 213/214 + 237): before invoking `renderLevel` for the portal's dest world it calls `SodiumInterface.invoker.createNewContext(renderDistance)` then `switchContextWithCurrentWorldRenderer(newSodiumContext)`; after render it calls `switchContextWithCurrentWorldRenderer(newSodiumContext)` AGAIN (swap is its own inverse) to restore. `OnSodiumPresent.switchContextWithCurrentWorldRenderer` reaches Sodium via `((LevelRendererExtension) mc.levelRenderer).sodium$getWorldRenderer()`, calls `swr.scheduleTerrainUpdate()`, then `((IESodiumWorldRenderer) swr).ip_getRenderSectionManager()`, casts the RSM to `IESodiumRenderSectionManager` and calls `ip_swapContext(context)`, then `scheduleTerrainUpdate()` again. So files #1, #2 and the SodiumInterface class form one mechanism.

---

### 1. IESodiumWorldRenderer.java (accessor interface)

- **Target**: `@Mixin(value = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.class, remap = false)`, declared as a public **interface** (accessor mixin, no implementation body).
- **Injection**: `@Accessor("renderSectionManager") RenderSectionManager ip_getRenderSectionManager();`
  - Binds to Sodium field `net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer#renderSectionManager` of type `net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager`. `remap=false` (Sodium is a mod, its members are not in the MC mapping tables). No `@At`, no slice â€” plain field-get accessor.
- **Mechanism**: Sodium keeps `SodiumWorldRenderer.renderSectionManager` private. IP needs the live RSM instance to swap its per-frame render lists. This accessor exposes it. Called from `SodiumInterface.OnSodiumPresent.switchContextWithCurrentWorldRenderer`.
- **Purpose / what breaks without it**: no way to reach the RSM â†’ `ip_swapContext` cannot be invoked â†’ the per-portal-layer render-list swap (file #2) is impossible â†’ Sodium would render the outer world's cached section lists while IP is drawing the portal's dest world (garbage terrain inside portals, and corruption of the outer world's transparent pass on return). It's the pure plumbing that makes the context swap reachable.
- **IP-core reached**: none directly; consumed by `SodiumInterface`.
- **Sodium types/members bound (0.6.0)**: `net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer` (target); field `renderSectionManager : Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;`.

---

### 2. MixinSodiumRenderSectionManager.java (context swap + entity-cull disable)

- **Target**: `@Mixin(value = net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager.class, remap = false)`, class mixin `implements IESodiumRenderSectionManager` (the duck interface `qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumRenderSectionManager`, single method `void ip_swapContext(SodiumRenderingContext)`).
- **Shadows**:
  - `@Shadow @Final @Mutable private int renderDistance;` â€” makes Sodium's final field writable (the `@Mutable` strips final so IP can swap it).
  - `@Shadow private @NotNull SortedRenderLists renderLists;` â€” `net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists`.
- **Interface impl `ip_swapContext(SodiumRenderingContext context)`** (`@Override`, not an injector): asserts `context.renderDistance != 0` and `context.renderLists != null` (Validate), then **three-way tmp swap** of `renderLists` and `renderDistance` between the live RSM and the passed `SodiumRenderingContext`. Because swap is symmetric, calling it twice (before + after portal render in MyGameRenderer) restores the RSM to its original lists/distance.
- **Injector**: `@Inject(method = "isSectionVisible", at = @At("HEAD"), cancellable = true)`
  - `private void onIsSectionVisible(int x, int y, int z, CallbackInfoReturnable<Boolean> cir)` â€” target `RenderSectionManager#isSectionVisible(III)Z`.
  - Body: `if (RenderStates.portalsRenderedThisFrame != 0) cir.setReturnValue(true);` â€” force-returns visible=true whenever any portal was rendered this frame.
- **Mechanism**: The swap gives each rendered world (outer world, and each portal-dest render) its own `SortedRenderLists` + `renderDistance` so Sodium's per-frame section lists for the dest world don't clobber the outer world's. `isSectionVisible` override: after a portal is drawn, Sodium's cached section-visibility bitset reflects the LAST world rendered, not the player's world; that stale visibility is used by MC to cull entities, so IP forces "visible" to avoid wrongly culling entities.
- **Purpose / IP quote**: > "The section visibility information will be wrong if rendered a portal. Just cancel this optimization. isSectionVisible() is currently only used for culling entities." Without the swap: transparent-terrain corruption in the outer world after a same-world portal (ties to file #3's rationale). Without the `isSectionVisible` override: entities near the camera vanish (culled against a portal-dest visibility set) whenever portals were rendered that frame.
- **IP-core reached**: `RenderStates.portalsRenderedThisFrame` (int; incremented in `PortalRendering.onBeginPortalWorldRendering`, reset each frame in `RenderStates.updatePreRenderInfo`). Reads `SodiumRenderingContext` (fields `renderLists`, `renderDistance`; constructor seeds `renderLists = SortedRenderLists.empty()`).
- **Sodium types/members bound (0.6.0)**: `RenderSectionManager` (target); field `renderDistance : I` (finalâ†’mutable); field `renderLists : Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/SortedRenderLists;`; method `isSectionVisible(III)Z`; `SortedRenderLists` incl. static `SortedRenderLists.empty()` (used by `SodiumRenderingContext` ctor).

---

### 3. MixinSodiumRenderRegion.java (per-portal-layer ChunkRenderList â€” @Overwrite)

- **Target**: `@Mixin(value = net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion.class, remap = false)`.
- **Shadow**: `@Shadow @Final private ChunkRenderList renderList;` â€” `net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList`.
- **Unique field**: `@Unique private @Nullable ObjectArrayList<ChunkRenderList> chunkRenderListsForPortalRendering = null;` (fastutil `it.unimi.dsi.fastutil.objects.ObjectArrayList`).
- **Injector**: `@Overwrite public ChunkRenderList getRenderList()` (full replacement of `RenderRegion#getRenderList()Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList;`; `@author qouteall @reason` block present as required for @Overwrite).
  - Body: if `!PortalRendering.isRendering()` return the shadowed vanilla `renderList`. Else lazily alloc the `ObjectArrayList`, compute `index = PortalRendering.getPortalLayer() - 1`, and `Helper.arrayListComputeIfAbsent(list, index, () -> new ChunkRenderList(this_))` â€” one distinct `ChunkRenderList` per portal recursion layer, casting `this` back to `RenderRegion` for the ctor arg.
- **Mechanism**: `Helper.arrayListComputeIfAbsent` (verified: pads the list with nulls up to `index`, lazily supplies a new element if null). Each portal layer (layer 1 = inside first portal, layer 2 = portal-in-portalâ€¦) gets its own `ChunkRenderList` keyed by `layer-1`, so the frame-counter reset inside `SortedRenderLists.Builder#add(RenderSection)` for one layer does not blow away another layer's list.
- **Purpose / IP quote**: > "With ImmPtl, the world rendering process is as follows: 1. render solid things 2. render portal recursively (will increase frame counter) 3. render transparent things. When rendering the world in portal (to-same-world portal), the frame counter increases, then in SortedRenderLists.Builder#add(RenderSection) it will reset the ChunkRenderList, which makes upcoming transparent block rendering in outer world to break. So use separate ChunkRenderList for each portal rendering layer." Without it: to-same-world portals corrupt/blank the outer world's transparent (water/glass) terrain pass.
- **IP-core reached**: `PortalRendering.isRendering()`, `PortalRendering.getPortalLayer()` (stack size of `portalLayers`). `q_misc_util.Helper.arrayListComputeIfAbsent`.
- **Sodium types/members bound (0.6.0)**: `RenderRegion` (target); field `renderList : Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList;`; method `getRenderList()`; ctor `ChunkRenderList.<init>(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;)V`; internal dependency on `SortedRenderLists.Builder#add(RenderSection)` reset semantics (behavioral, not a bind).

---

### 4. MixinSodiumOcclusionCuller.java (iteration-origin retarget + tolerant frustum â€” the deepest file)

- **Target**: `@Mixin(net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller.class)` (abstract class mixin; note NO `remap=false` at class level â€” set per-member instead).
- **Shadows**:
  - `@Shadow(remap = false) protected abstract RenderSection getRenderSection(int x, int y, int z);` â€” `OcclusionCuller#getRenderSection(III)Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;`.
  - `@Shadow(remap = false) public static boolean isWithinFrustum(Viewport viewport, RenderSection section)` (body throws; shadow of static `OcclusionCuller#isWithinFrustum(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;)Z`).
- **Unique state**: `@Unique private @Nullable SectionPos ip_modifiedStartPoint;` and `@Unique private static boolean ip_tolerantInitialFrustumTestFail;` (note: static â€” one is instance-scoped, one class-scoped; both reset at the top of each `findVisible`).
- **Injector A â€” `@ModifyVariable`** on `findVisible`:
  - `@ModifyVariable(method = "findVisible", at = @At("HEAD"), argsOnly = true, remap = false)` targeting the `boolean useOcclusionCulling` argument. Handler signature captures full arg list: `(boolean originalValue, OcclusionCuller.Visitor visitor, Viewport viewport, float searchDistance, boolean useOcclusionCulling, int frame)`. So Sodium 0.6.0 `findVisible` descriptor â‰ˆ `(Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller$Visitor;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;FZI)V`.
  - Logic: `doUseOcclusionCulling = PortalRendering.shouldEnableSodiumCaveCulling()`. Reset both unique fields. If `PortalRendering.isRendering()`: get `portal = PortalRendering.getRenderingPortal()`, `cameraPos = CHelper.getCurrentCameraPos()`, and `ip_modifiedStartPoint = portal.getPortalShape().getModifiedVisibleSectionIterationOrigin(portal, cameraPos)`. If non-null: force `doUseOcclusionCulling = false`, fetch `getRenderSection(startPoint.x/y/z)`, and if that section exists but `!isWithinFrustum(viewport, section)` set `ip_tolerantInitialFrustumTestFail = true`. Returns the (possibly overridden) boolean into the LVT.
- **Injector B â€” `@Redirect`** in `init`:
  - `@Redirect(method = "init", at = @At(value="INVOKE", target="Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;getChunkCoord()Lnet/minecraft/core/SectionPos;", remap = true), remap = false)`. Note `target` `remap=true` because `SectionPos` is a vanilla MC type in the descriptor; outer `remap=false` for the Sodium owner. Redirect returns `ip_modifiedStartPoint` if set, else `instance.getChunkCoord()`.
- **Injector C â€” `@Redirect`** in `initWithinWorld`:
  - Same shape, `method = "initWithinWorld"`, same `Viewport.getChunkCoord()` target, `remap = false` (no inner remap flag this time). Same body.
- **Injector D â€” `@Inject`** on `isWithinFrustum` RETURN:
  - `@Inject(method = "isWithinFrustum", at = @At("RETURN"), cancellable = true, remap = false)`, static handler `(Viewport viewport, RenderSection section, CallbackInfoReturnable<Boolean> cir)`. If `ip_tolerantInitialFrustumTestFail`: read `cir.getReturnValueZ()`; if the real test says within-frustum, clear the tolerant flag (frustum tests become normal again); then unconditionally `cir.setReturnValue(true)`.
- **Mechanism**: When rendering through a portal whose shape defines a preferred iteration origin (only `BoxPortalShape` with `facingOutwards` + `IPGlobal.boxPortalSpecialIteration`), IP retargets Sodium's BFS flood-fill start section from the camera's chunk to the shape-provided `SectionPos` (injectors B/C swap the seed used by `init`/`initWithinWorld`). Because that seed can start outside the view frustum (which would make Sodium's graph-search halt immediately), injector A pre-detects the out-of-frustum seed and arms `ip_tolerantInitialFrustumTestFail`; injector D then makes `isWithinFrustum` report "true" until the search first reaches a genuinely in-frustum section, at which point tolerance disarms and normal culling resumes. Injector A also disables Sodium's own occlusion (cave) culling for this pass unless `shouldEnableSodiumCaveCulling()` (only within 5 blocks of a non-box portal â€” cave culling can wrongly cull things behind the portal dest otherwise).
- **Purpose / IP quotes**: A: "update the iteration start point modification value"; B/C: "apply start point modification"; D: "when iteration start point become a position that's outside of frustum make it tolerant early frustum test failures to avoid wrongly halting iteration". `PortalRendering.shouldEnableSodiumCaveCulling` doc: > "cave culling can optimize 20% when you are very close to the portal â€¦ if something is behind the portal destination, cave culling may cull wrongly â€¦ only enable cave culling when close to the portal." Without these: box-portal (non-flat/"scale box") interiors under-render â€” Sodium's flood fill starts at the camera chunk which may be outside the box's interior region and immediately terminates, leaving the box interior black/empty; and with cave culling left on, portal-dest geometry gets wrongly occluded.
- **IP-core reached**: `PortalRendering.shouldEnableSodiumCaveCulling()`, `.isRendering()`, `.getRenderingPortal()`; `CHelper.getCurrentCameraPos()`; `Portal.getPortalShape()`; `PortalShape.getModifiedVisibleSectionIterationOrigin(Portal, Vec3)` (default null; overridden in `BoxPortalShape` returning a clamped `SectionPos` inside the box's inner section range, gated by `IPGlobal.boxPortalSpecialIteration` && `facingOutwards`). This is the ONLY consumer of `getModifiedVisibleSectionIterationOrigin`.
- **Sodium types/members bound (0.6.0)**: `OcclusionCuller` (target); nested `OcclusionCuller$Visitor`; `getRenderSection(III)LRenderSection;`; static `isWithinFrustum(Viewport,RenderSection)Z`; `findVisible(Visitor,Viewport,F,Z,I)V` (arg indices/order load-bearing for @ModifyVariable + @Local-free capture); `init(...)` and `initWithinWorld(...)` both calling `Viewport.getChunkCoord()Lnet/minecraft/core/SectionPos;`; `net.caffeinemc.mods.sodium.client.render.viewport.Viewport`; `RenderSection`.

---

### 5. MixinSodiumViewport.java (advanced frustum culling hook â€” @Redirect)

- **Target**: `@Mixin(value = net.caffeinemc.mods.sodium.client.render.viewport.Viewport.class, remap = false)`.
- **Injector**: `@Redirect(method = "isBoxVisible", at = @At(value="INVOKE", target="Lnet/caffeinemc/mods/sodium/client/render/viewport/frustum/Frustum;testAab(FFFFFF)Z"))`.
  - `private boolean redirectTestAab(Frustum instance, float minX, float minY, float minZ, float maxX, float maxY, float maxZ)`.
  - Body: `inFrustum = instance.testAab(...)` (call the real thing). If `inFrustum` and `SodiumInterface.frustumCuller != null`: `canDetermineInvisible = SodiumInterface.frustumCuller.canDetermineInvisibleWithCameraCoord(minâ€¦,maxâ€¦)`; return `!canDetermineInvisible`. Else return `inFrustum`.
- **Mechanism**: intercepts every section-box frustum test Sodium does in `Viewport.isBoxVisible`. IP layers its own portal-aware "advanced/super-advanced" frustum culler on top: even if the box passes the vanilla frustum, IP can additionally declare it invisible via the portal-derived plane frusta computed in `FrustumCuller`. The box coords passed are **camera-relative floats** (hence `canDetermineInvisibleWithCameraCoord`).
- **Purpose / what breaks**: this is the performance path â€” inside a portal, only the sub-frustum subtended by the portal opening is visible; outside a portal, an outer culling frustum (box behind the portal plane and inside the portal's screen frustum) can be culled. Without it, IP gets no Sodium-side culling of sections that are geometrically hidden by the portal, i.e. heavy over-draw of the dest world (major FPS loss); it is not a correctness break but a large perf regression. `FrustumCuller` itself no-ops (returns null func) when Sodium absent, when Iris shadow map is rendering, or when `IPCGlobal.doUseAdvancedFrustumCulling` is off.
- **IP-core reached**: `SodiumInterface.frustumCuller` (static `@Nullable FrustumCuller`, set each frame by file #6); `FrustumCuller.canDetermineInvisibleWithCameraCoord(floatÃ—6)` â†’ delegates to `canDetermineInvisibleFunc` (a `BoxPredicateF`) built from portal-shape inner/outer culling funcs (`FrustumCuller.getFlatPortalInnerFrustumCullingFunc` / `â€¦Outerâ€¦`, `Frustum4Planes.isFullyOutside/isFullyInside`, `isFullyBehindPlane`). Gated by `IPCGlobal.doUseAdvancedFrustumCulling`, `useSuperAdvancedFrustumCulling`, `IrisInterface.invoker.isRenderingShadowMap()`, `SodiumInterface.invoker.isSodiumPresent()`.
- **Sodium types/members bound (0.6.0)**: `Viewport` (target); method `isBoxVisible(...)` (host of the redirect); `net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum#testAab(FFFFFF)Z` (redirected call).

---

### 6. MixinSodiumWorldRenderer.java (per-frame FrustumCuller construction â€” @Inject)

- **Target**: `@Mixin(value = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.class, remap = false)` (same target class as file #1, separate mixin).
- **Injector**: `@Inject(method = "setupTerrain", at = @At("HEAD"))`.
  - `private void onUpdateChunks(Camera camera, Viewport viewport, boolean spectator, boolean updateChunksImmediately, CallbackInfo ci)` â€” so Sodium 0.6.0 `setupTerrain` descriptor â‰ˆ `(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;ZZ)V` (with a wildcard `frame`/other int params elided â€” captured args are positional-leading).
  - Body: `SodiumInterface.frustumCuller = new FrustumCuller();` then `cameraPos = camera.getPosition(); frustumCuller.update(cameraPos.x, y, z);`.
- **Mechanism**: every time Sodium sets up terrain for a pass (once for the outer world, and once per portal-dest render because `renderLevel` â†’ Sodium `setupTerrain`), IP builds a fresh `FrustumCuller` bound to that pass's camera. `FrustumCuller.update` computes `canDetermineInvisibleFunc` = the correct inner/outer portal culling predicate for the current `PortalRendering` state (inner func while rendering a portal, outer func for the nearest cullable portal otherwise), stores camera coords for worldâ†”camera conversions.
- **Purpose / what breaks**: this is the producer feeding file #5's consumer. Without it, `SodiumInterface.frustumCuller` stays null / stale, and file #5's redirect degrades to plain vanilla frustum testing â€” same perf regression as removing #5. Rebuilding it at `setupTerrain HEAD` guarantees the culler matches the exact camera/portal-layer of the pass Sodium is about to build.
- **IP-core reached**: `FrustumCuller` (ctor + `update(double,double,double)`); writes `SodiumInterface.frustumCuller`. `FrustumCuller.update` internally reads `IPCGlobal.doUseAdvancedFrustumCulling/useSuperAdvancedFrustumCulling`, `IrisInterface.invoker.isRenderingShadowMap()`, `PortalRendering.isRendering()/getRenderingPortal()`, `TransformationManager.isIsometricView`, `CHelper.getClientNearbyPortals`.
- **Sodium types/members bound (0.6.0)**: `SodiumWorldRenderer` (target); method `setupTerrain(Camera, Viewport, boolean, boolean, â€¦)` â€” the exact arg tail is the fragile bit for re-porting.

---

### 7. MixinSodiumDefaultShaderInterface.java (@Pseudo clipping-uniform on Sodium's terrain shader)

- **Target**: `@Pseudo @Mixin(value = net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface.class, remap = false)`. `@Pseudo` = tolerate the class being absent/renamed (Sodium/Iris variants) without a hard mixin failure.
- **Unique field**: `@Unique private GlUniformFloat4v uIPClippingEquation;` (`net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v`). (`GlUniformFloat3v` imported but unused.)
- **Injector A â€” `@Inject`** on ctor:
  - `@Inject(method = "<init>", at = @At("RETURN"), remap = false)` (the `require = 0` override is commented out, so it inherits `defaultRequire=1` â€” must bind or fail; combined with `@Pseudo` this means: if the class exists it MUST have this ctor). Handler `(ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci)` â†’ so `DefaultShaderInterface.<init>` descriptor â‰ˆ `(Lnet/caffeinemc/mods/sodium/client/render/chunk/shader/ShaderBindingContext;Lnet/caffeinemc/mods/sodium/client/render/chunk/shader/ChunkShaderOptions;)V`.
  - Body: `this.uIPClippingEquation = context.bindUniformOptional("iportal_ClippingEquation", GlUniformFloat4v::new);` â€” grabs (optionally, may be null if the shader lacks the uniform) the custom `vec4` uniform IP injects into Sodium's terrain shader source (see file #8).
- **Injector B â€” `@Inject`** on `setupState`:
  - `@Inject(method = "setupState", at = @At("RETURN"), remap = false)`, handler `(CallbackInfo ci)`. Body: if `uIPClippingEquation != null`: if `FrontClipping.isClippingEnabled` â†’ `equation = FrontClipping.getActiveClipPlaneEquationAfterModelView()` and `uIPClippingEquation.set(new float[]{(float)eq[0..3]})`; else `uIPClippingEquation.set(new float[]{0,0,0,1})` (disabled = plane that never clips).
- **Mechanism**: Sodium's chunk terrain does not go through vanilla `GL_CLIP_PLANE0`, so IP's front-clipping (which slices geometry in front of the portal plane so the dest world doesn't poke through) must be done in-shader. IP injects a `uniform vec4 iportal_ClippingEquation` into the Sodium terrain shader (file #8) and here binds+uploads it every `setupState`. The equation is the AFTER-model-view form (`getActiveClipPlaneEquationAfterModelView`, produced by `FrontClipping.transformClipEquation` = inverse-transpose model-view applied to the plane).
- **Purpose / what breaks**: without it, when standing near a portal the dest-world terrain rendered by Sodium is NOT clipped at the portal plane â†’ the near/dest geometry bleeds through the portal frame and in front of the aperture (the classic "world pokes through the portal" artifact). `{0,0,0,1}` is the identity "keep everything" plane used when clipping is off. Whole thing is a no-op if `IPGlobal.enableClippingMechanism` / `IPCGlobal.useFrontClipping` are off (FrontClipping guards) or if the uniform wasn't present (`uIPClippingEquation == null`).
- **IP-core reached**: `FrontClipping.isClippingEnabled` (static boolean), `FrontClipping.getActiveClipPlaneEquationAfterModelView()` (double[4], set by `setupInnerClipping`/`setupOuterClipping`). Depends on the parallel vanilla-shader path `IEShader.ip_getClippingEquationUniformLocation()` + `ShaderCodeTransformation.shouldAddUniform` machinery for consistency of the uniform name `iportal_ClippingEquation`.
- **Sodium types/members bound (0.6.0)**: `DefaultShaderInterface` (target, @Pseudo); `ShaderBindingContext#bindUniformOptional(Ljava/lang/String;Ljava/util/function/â€¦;)Lâ€¦GlUniform;` (functional-factory overload taking `GlUniformFloat4v::new`); `ChunkShaderOptions`; `net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v` (+ its `set(float[])`); method `setupState()`. Uniform name literal `"iportal_ClippingEquation"`.

---

### 8. MixinSodiumShaderLoader.java (inject clipping uniform into shader source â€” @WrapOperation)

- **Target**: `@Mixin(value = net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader.class)` (NOTE: NO class-level `remap=false` here; set per-injector).
- **Injector â€” MixinExtras `@WrapOperation`**:
  - `@WrapOperation(method = "loadShader", at = @At(value="INVOKE", target="Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderLoader;getShaderSource(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/String;", remap = true), remap = false)`. `target remap=true` (the method's `ResourceLocation` param is vanilla), outer `remap=false`.
  - `private static String wrapGetShaderSource(ResourceLocation name, Operation<String> operation, @Local(argsOnly=true) ShaderType shaderType)` â€” MixinExtras `Operation<String>` (the wrapped call) plus a `@Local(argsOnly=true)` capture of `loadShader`'s `ShaderType` argument (`net.caffeinemc.mods.sodium.client.gl.shader.ShaderType`).
  - Body: `shaderSource = operation.call(name)` (call the real `getShaderSource`), then `shaderSource = ShaderCodeTransformation.transform(shaderType == ShaderType.VERTEX ? CompiledShader.Type.VERTEX : CompiledShader.Type.FRAGMENT, name.toString(), shaderSource)`; return transformed source.
- **Mechanism**: intercepts Sodium loading each terrain shader's GLSL, runs IP's `ShaderCodeTransformation.transform` over it. That applies YAML-configured regex patches (`immersive_portals:shaders/shader_transformation.yaml`) that inject the `uniform vec4 iportal_ClippingEquation;` declaration and the discard/clip logic into Sodium's vertex+fragment source. Maps Sodium's `ShaderType.{VERTEX,FRAGMENT}` onto IP's `CompiledShader.Type` (`com.mojang.blaze3d.shaders.CompiledShader.Type`).
- **Purpose / what breaks**: this is the *source-side* half whose *runtime* half is file #7. Without the injected uniform + clip code, `DefaultShaderInterface` binds a uniform that does not exist (`uIPClippingEquation` = null) and no in-shader clipping happens â†’ dest terrain bleeds through the portal (same visual break as #7, they are a pair: #8 writes the uniform into GLSL, #7 feeds it each frame).
- **IP-core reached**: `ShaderCodeTransformation.transform(CompiledShader.Type, String, String)` (reads the parsed YAML `configs`; init gated by `IPGlobal.enableClippingMechanism`); `ShaderCodeTransformation.ShaderType` maps vs `CompiledShader.Type`.
- **Sodium types/members bound (0.6.0)**: `ShaderLoader` (target); method `loadShader(...)` hosting the wrapped `ShaderLoader.getShaderSource(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/String;`; enum `net.caffeinemc.mods.sodium.client.gl.shader.ShaderType` (`VERTEX`/`FRAGMENT`, captured via `@Local argsOnly`). MixinExtras: `com.llamalad7.mixinextras.injector.wrapoperation.{WrapOperation,Operation}`, `com.llamalad7.mixinextras.sugar.Local`.

---

### 9. MixinSodiumFlawlessFrames.java (force main-thread rebuild via FlawlessFrames â€” @Inject)

- **Target**: `@Mixin(value = net.caffeinemc.mods.sodium.client.util.FlawlessFrames.class, remap = false)`.
- **Injector**: `@Inject(method = "isActive", at = @At("HEAD"), cancellable = true)`.
  - `private static void onIsActive(CallbackInfoReturnable<Boolean> cir)` â€” target static `FlawlessFrames#isActive()Z`.
  - Body: `if (ForceMainThreadRebuild.isCurrentFrameForceMainThreadRebuild()) cir.setReturnValue(true);`.
- **Mechanism**: Sodium's `FlawlessFrames.isActive()` normally reflects the Fabric "flawless frames" (recording) API; when true Sodium builds all needed chunk meshes synchronously on the main thread instead of async, guaranteeing a complete frame. IP piggybacks on this: whenever IP has requested a forced main-thread rebuild for the current frame, it makes Sodium believe flawless-frames is active so the required chunks are rebuilt this frame.
- **Purpose / what breaks**: after a portal crossing / camera warp, async Sodium meshing leaves the newly-entered region as blank/uncompiled chunks for several frames (the "blank curtain" class of artifact). Forcing synchronous rebuild for the flagged frames eliminates that pop-in. IP's own note flags it is unreliable: > "this is sometimes effective but not always effective (maybe because of lighting or uploading delay?)". `ForceMainThreadRebuild` counts down `forceMainThreadRebuildForFrames` in `onPreRender`; other IP code calls `forceMainThreadRebuildFor(n)` to arm it.
- **IP-core reached**: `ForceMainThreadRebuild.isCurrentFrameForceMainThreadRebuild()` (boolean latched in `onPreRender`, armed by `forceMainThreadRebuildFor`, reset on `IPCGlobal.CLIENT_CLEANUP_EVENT`).
- **Sodium types/members bound (0.6.0)**: `net.caffeinemc.mods.sodium.client.util.FlawlessFrames` (target); static `isActive()Z`.

---

### Cross-file dependency summary (for C2 re-engineering)
- **Context-swap chain**: #1 (accessor) + #2 (`ip_swapContext` + `isSectionVisible`) + `SodiumInterface.OnSodiumPresent` + `MyGameRenderer` swap sites. Needs `SodiumWorldRenderer.renderSectionManager`, `LevelRendererExtension.sodium$getWorldRenderer`, `RenderSectionManager.{renderLists, renderDistance, isSectionVisible, scheduleTerrainUpdate}`, `SortedRenderLists.empty()`.
- **Culling chain**: #6 (produce `FrustumCuller` at `setupTerrain`) â†’ #5 (consume in `Viewport.isBoxVisible`â†’`Frustum.testAab`) + #4 (OcclusionCuller iteration origin/tolerant frustum). Needs `Viewport.{isBoxVisible,getChunkCoord}`, `Frustum.testAab(FFFFFF)Z`, `OcclusionCuller.{findVisible,init,initWithinWorld,isWithinFrustum,getRenderSection,Visitor}`, `SodiumWorldRenderer.setupTerrain`.
- **Clipping chain**: #8 (inject uniform into GLSL at `ShaderLoader.loadShader`) â†’ #7 (bind+upload at `DefaultShaderInterface.<init>`/`setupState`). Needs `ShaderLoader.getShaderSource`, `ShaderType`, `DefaultShaderInterface`, `ShaderBindingContext.bindUniformOptional`, `GlUniformFloat4v`.
- **Chunk-freshness**: #9 alone (`FlawlessFrames.isActive`).
- **Also touched by SodiumInterface (not in the 9 but same compat surface)**: `SpriteUtil.markSpriteActive`, `ChunkTrackerHolder.get(world).onChunkStatus{Added,Removed}`, `ChunkStatus.FLAG_HAS_BLOCK_DATA`, `LevelRendererExtension.sodium$getWorldRenderer`.

### Risk notes (sodiumMixins)
- ALL Sodium FQNs/descriptors above are ground-truth for Sodium 0.6.0 as IP 1.21.3 compiled against them; they are UNVERIFIED for Sodium 0.9.1 and MUST be re-javapped from the 0.9.1 jar before porting. Sodium's package/class layout churns between minors.
- MC 26.2 wholesale-rewrote the vanilla renderer; #6 `SodiumWorldRenderer.setupTerrain(Camera, Viewport, boolean, boolean)` and #4 `OcclusionCuller.findVisible(Visitor, Viewport, float, boolean, int)` are the two most fragile signatures â€” arg count/order is load-bearing for @ModifyVariable/@Inject captures and very likely changed. UNVERIFIED.
- #4 injectors B/C redirect `Viewport.getChunkCoord()Lnet/minecraft/core/SectionPos;` inside `init`/`initWithinWorld`. Sodium's occlusion-culler graph-search was refactored in the 0.6â†’0.9 line (async/region changes); method names `init`/`initWithinWorld`/`findVisible` and the `getChunkCoord` seed call may no longer exist or may have moved. HIGH re-map risk. UNVERIFIED.
- #8/#7 depend on Sodium still loading terrain GLSL through `ShaderLoader.getShaderSource` and constructing a `DefaultShaderInterface(ShaderBindingContext, ChunkShaderOptions)` with a `bindUniformOptional(String, factory)` API. Sodium's shader-uniform binding (`GlUniformFloat4v`, `ShaderBindingContext`) is a known churn area; `bindUniformOptional` may be renamed/removed. Also the shader-transformation regex YAML (`immersive_portals:shaders/shader_transformation.yaml`) is written against 0.6.0 GLSL text and will silently no-op if Sodium's shader source strings changed. UNVERIFIED â€” must diff the 0.9.1 terrain shaders.
- #2 relies on `RenderSectionManager.renderDistance` being a FINAL int field (stripped via @Mutable) and `renderLists` being a `SortedRenderLists` field, plus `SortedRenderLists.empty()`. If 0.9.1 moved render-list ownership off the RSM (e.g. into a per-frame/region structure) the whole context-swap design breaks and needs re-siting. UNVERIFIED.
- #3 @Overwrite of `RenderRegion.getRenderList()` assumes RenderRegion owns a single `ChunkRenderList renderList` and that `SortedRenderLists.Builder.add(RenderSection)` resets it per frame-counter. @Overwrite is brittle across versions (any signature/behavior change = hard break, no soft-fail). If 0.9.1 changed region/render-list ownership this must be redesigned, not remapped. UNVERIFIED.
- #7 is @Pseudo (soft target) but the ctor @Inject inherits defaultRequire=1 (the `require=0` line is commented out): if `DefaultShaderInterface` exists in 0.9.1 but its ctor signature changed, this FAILS HARD rather than skipping. Consider whether the 26.2 port wants require=0 restored. UNVERIFIED.
- #5 redirects `Frustum.testAab(FFFFFF)Z` inside `Viewport.isBoxVisible`. Iris 1.11.2 wraps/replaces Sodium's Viewport/Frustum in its shadow-pass path; verify the redirect still lands and that `IrisInterface.invoker.isRenderingShadowMap()` (which FrustumCuller consults) is still wired for Iris 1.11.2 â€” the Iris shadow-map detection API is a separate porting dependency. UNVERIFIED.
- #9 `FlawlessFrames.isActive()Z` (package `net.caffeinemc.mods.sodium.client.util`) is low-churn but still must be confirmed present in 0.9.1; the Fabric flawless-frames provider wiring is unchanged in IP but the Sodium side could have relocated. UNVERIFIED.
- SodiumInterface's non-mixin binds (`SpriteUtil.markSpriteActive`, `ChunkTrackerHolder`, `ChunkStatus.FLAG_HAS_BLOCK_DATA`, `LevelRendererExtension.sodium$getWorldRenderer`) live outside the 9 files but are part of the same compat contract and equally need 0.9.1 re-verification â€” flag for the sibling tracer covering sodium_compatibility/*. UNVERIFIED.
- The IPCompatMixinPlugin gate is a literal `mixinClassName.contains("Sodium")` substring match â€” every ported class MUST keep `Sodium` in its simple name or it will be silently skipped even when Sodium is loaded. This is a portability footgun to preserve deliberately.
- MixinExtras (`@WrapOperation`, `@Local`) in #8 is a hard dependency; confirm the 26.2 toolchain bundles a MixinExtras version compatible with the target Mixin/Sponge version. UNVERIFIED but low risk.


---

â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
TRACER B â€” MIXIN/IRIS FULL-DEPTH (COVERAGE INFO-3) â€” IP 1.21.3 baseline
Source root (READ-ONLY): C:\Users\warwa\ModDev\ImmersivePortalsMod
â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

â”€â”€ GLOBAL WIRING (how these mixins are gated & activated) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
Config: src/main/resources/imm_ptl_compat.mixins.json
  package = qouteall.imm_ptl.core.compat.mixin ; compatibilityLevel JAVA_17 ;
  plugin  = qouteall.imm_ptl.core.compat.IPCompatMixinPlugin ; injectors.defaultRequire = 1.
  All 7 iris mixins are listed: iris.MixinIrisClearPass, iris.MixinIrisFinalPassRenderer,
  iris.MixinIrisIris, iris.MixinIrisRenderingPipeline, iris.MixinIrisShadowRenderTargets,
  iris.MixinIrisSodiumShader, iris.MixinIrisTransformPatcher.

Plugin gating (IPCompatMixinPlugin.shouldApplyMixin, by mixin-CLASS-NAME substring):
  - name contains "IrisSodium"  -> require sodium AND iris loaded  (only MixinIrisSodiumShader)
  - else name contains "Iris"   -> require iris loaded             (the other 6 iris mixins)
  - (Sodium / Flywheel / CardinalComp handled by their own substrings)
  IMPORTANT PORT NOTE: the substring test is ORDER-SENSITIVE. "IrisSodium" is checked
  BEFORE "Iris", so MixinIrisSodiumShader is correctly classified sodium+iris. Any C2
  rename must preserve this precedence, or a sodium-absent/iris-present install will try to
  apply the sodium-typed mixin and hard-fail (defaultRequire=1). All iris mixins use
  remap=false (Iris/Sodium are non-Mojang, unmapped names).

Runtime activation vs. mixin application are DIFFERENT gates â€” critical for the port:
  * APPLICATION happens whenever iris(+sodium) is loaded (plugin above). The bytecode is
    always woven into Iris internals when Iris is present.
  * EXECUTION of most bodies is additionally guarded at runtime by
    `IPCGlobal.renderer instanceof ExperimentalIrisPortalRenderer` (or a debug flag).
  Renderer selection: PortalRenderer.switchToCorrectRenderer() (PortalRenderer.java ~L316):
    if Iris present AND Iris.getCurrentPack().isPresent() (shaders on):
        if IPCGlobal.experimentalIrisPortalRenderer (default=false, IPCGlobal.java:37)
            -> ExperimentalIrisPortalRenderer.instance
        else renderMode switch -> IrisPortalRenderer / IrisCompatibilityPortalRenderer / dummy
  So the ExperimentalIrisPortalRenderer-gated mixin bodies are LIVE only when the
  experimental flag is on AND a shaderpack is active. MixinIrisTransformPatcher and
  MixinIrisSodiumShader are NOT renderer-gated â€” they run for ALL iris(+sodium) shader
  renderers (they inject the clipping uniform the whole clipping mechanism relies on).

â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
FILE 1 â€” MixinIrisRenderingPipeline.java   (2019 bytes)
Target: @Mixin(value = net.irisshaders.iris.pipeline.IrisRenderingPipeline.class, remap=false)
        implements IEIrisNewWorldRenderingPipeline
â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
@Shadow private boolean isRenderingWorld;   (mutated via the duck interface below)
Commented-out (dormant, ported faithfully): `//@Shadow private ShadowRenderTargets shadowRenderTargets;`
and its `//@Override ip_getShadowRenderTargets()` accessor â€” the shadow-map-swap feature is
shelved (see FILE 5 & ShadowMapSwapper).

INJECTOR 1 â€” onFinalizeLevelRendering
  @Inject(method="finalizeLevelRendering", at=@At("HEAD"), cancellable=true)
  Body: if (IPCGlobal.renderer instanceof ExperimentalIrisPortalRenderer && PortalRendering.isRendering())
           ci.cancel();
  MECHANISM: cancels Iris's own end-of-level finalize pass while IP is recursively rendering a
  portal's world (PortalRendering.isRendering() true inside doPortalRendering).
  PURPOSE: Iris finalizeLevelRendering does composite/tonemap/present work that must run ONCE
  for the outermost frame. Without the cancel, each recursive portal-world invocation would
  trigger Iris to finalize/present mid-recursion -> corrupted deferred buffers / double present.
  DEPENDS ON: IPCGlobal.renderer, PortalRendering.isRendering() (IP recursion context).

INJECTOR 2 â€” onAfterDeferredCompositeRendering
  @Inject(method="beginTranslucents",
          at=@At(value="INVOKE",
                 target="Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V",
                 shift=At.Shift.AFTER))
  Body: if (IPCGlobal.renderer instanceof ExperimentalIrisPortalRenderer r)
           r.onAfterIrisDeferredCompositeRendering();
  MECHANISM: right after Iris runs its deferred composite pass (CompositeRenderer.renderAll)
  inside beginTranslucents, call back into the experimental renderer.
  onAfterIrisDeferredCompositeRendering() (ExperimentalIrisPortalRenderer:255) does:
     clampStencilValue(getPortalLayer()); setStencilStateForWorldRendering();
  PURPOSE: the deferred composite draws a full-screen quad and would run for the whole screen;
  IP has raised the stencil around portal view-areas to CONFINE that composite to the portal
  region. After it runs, IP re-clamps the stencil back down and restores the per-layer stencil
  func so subsequent (translucent) world drawing is masked to the current portal layer.
  DEPENDS ON: the whole stencil machinery (clampStencilValue, setStencilStateForWorldRendering,
  PortalRendering.getPortalLayer()), and on the EXACT internal call
  `CompositeRenderer.renderAll()V` existing inside `beginTranslucents`.

DUCK INTERFACE IMPL (IEIrisNewWorldRenderingPipeline):
  @Override public void ip_setIsRenderingWorld(boolean cond){ isRenderingWorld = cond; }
  PURPOSE: lets IP flip Iris's private `isRenderingWorld`. Used by ExperimentalIrisPortalRenderer
  .invokeWorldRendering (sets it FALSE after IP's own world render â€” comment: "Avoid Iris from
  force-disabling depth mask") and .onBeginIrisTranslucentRendering (sets it TRUE â€” "Resume Iris
  world rendering"). This is the single most fragile private-field coupling in the set.

â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
FILE 2 â€” MixinIrisClearPass.java   (972 bytes)
Target: @Mixin(value = net.irisshaders.iris.targets.ClearPass.class, remap=false)
â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
INJECTOR â€” onExecute
  @Inject(method="execute", at=@At("HEAD"), cancellable=true)
  Signature: private void onExecute(org.joml.Vector4f par1, CallbackInfo ci)
  Body: if (IPCGlobal.renderer instanceof ExperimentalIrisPortalRenderer && PortalRendering.isRendering())
           ci.cancel();
  MECHANISM: suppresses Iris framebuffer ClearPass while rendering inside a portal.
  PURPOSE: mirror of ExperimentalIrisPortalRenderer.replaceFrameBufferClearing() (which returns
  skipClearing=PortalRendering.isRendering()). Clearing a render-target mid-portal-recursion would
  wipe the world/portal contents already accumulated for the outer layer. Comment on the vanilla
  side: "no need to clear the portal area. normally the sky will override it."
  DEPENDS ON: ClearPass.execute(Vector4f) signature (one Vector4f clear-color arg).

â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
FILE 3 â€” MixinIrisFinalPassRenderer.java   (796 bytes)
Target: @Mixin(value = net.irisshaders.iris.pipeline.FinalPassRenderer.class, remap=false)
â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
INJECTOR â€” onRenderFinalPass
  @Inject(method="renderFinalPass", at=@At("HEAD"))     [NOT cancellable, NO renderer gate]
  Body: if (IPCGlobal.debugEnableStencilWithIris) GL11.glDisable(GL_STENCIL_TEST);
  MECHANISM: at the head of Iris's final present pass, optionally disable GL stencil test.
  PURPOSE: DEBUG-ONLY dormancy. IPCGlobal.debugEnableStencilWithIris defaults false
  (IPCGlobal.java:39) with no in-code toggle wired -> this body is effectively inert. It exists so
  that when experimenting with leaving the stencil test enabled through Iris, the final blit isn't
  itself stencil-masked. Ported for completeness, not for live behavior.
  DEPENDS ON: only a static boolean + LWJGL GL11 â€” no Iris/Sodium internals beyond the method name.

â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
FILE 4 â€” MixinIrisIris.java   (977 bytes)  â€” FULLY DORMANT
Target: @Mixin(value = net.irisshaders.iris.Iris.class, remap=false)
â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
Class body has NO active injectors â€” only two commented-out blocks (ported dormancy verbatim):
  (a) `// only overworld` â€” a commented @Inject(method="getCurrentDimension", HEAD, cancellable)
      onGetCurrentDimension(CallbackInfoReturnable<DimensionId>) that, when the experimental
      renderer was active, would force-return DimensionId.OVERWORLD. INTENT: make Iris always treat
      the current dimension as overworld so a single shaderpack dimension config is used across
      portal-rendered dims. Disabled (labelled "// test").
  (b) `// it cannot recognize sodium from jitpack` â€” a commented @Inject(method="isSodiumInvalid",
      HEAD, cancellable) onIsSodiumInvalid(CallbackInfoReturnable<Boolean>) that in a dev env
      (FabricLoader.isDevelopmentEnvironment()) would force-return false, i.e. tell Iris "Sodium is
      valid." INTENT: dev-only workaround for Iris rejecting a jitpack-sourced Sodium build.
  The mixin is still listed/applied (so the class loads against Iris.class) but weaves nothing.
  PORT IMPLICATION: keep as an empty applied mixin OR drop from the json â€” but note the two
  intents are latent design levers (dimension spoofing; sodium-validity override) that a 0.9.1/
  1.11.2 port may need to revive if Iris re-introduces per-dimension shader state or Sodium-version
  validation.

â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
FILE 5 â€” MixinIrisShadowRenderTargets.java   (1066 bytes)  â€” FULLY DORMANT
Target: @Mixin(value = net.irisshaders.iris.shadows.ShadowRenderTargets.class, remap=false)
        implements IEIrisShadowRenderTargets
â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
No active injectors. The class exists ONLY to declare `implements IEIrisShadowRenderTargets`,
and that interface (IEIrisShadowRenderTargets.java) is itself EMPTY â€” its one method
`// ShadowMapSwapper getShadowMapSwapper();` is commented out. Entire body commented (ported
dormancy verbatim):
  - `// ShadowMapSwapper ip_shadowMapSwapper;`
  - `//@Inject(method="<init>", at=@At("RETURN")) void onInit(int resolution,
     InternalTextureFormat[] formats, CallbackInfo ci){ ip_shadowMapSwapper = new
     ShadowMapSwapper(resolution, (ShadowRenderTargets)(Object)this); }`
  - `//@Inject(method="destroy", HEAD) onDestroy -> ip_shadowMapSwapper.dispose()/null`
  - `//@Override getShadowMapSwapper(){ return ip_shadowMapSwapper; }`
  PURPOSE (intended, shelved): attach an IP ShadowMapSwapper to each Iris ShadowRenderTargets to
  save/restore the shadow map across recursive portal world renders (see ShadowMapSwapper.java,
  4071 bytes â€” a per-resolution FBO/texture cache with acquireStorage/copyFromIrisShadowRenderTargets
  /copyToIrisShadowRenderTargets/restitute). The matching consumer in ExperimentalIrisPortalRenderer
  .invokeWorldRendering (L124-138, L153-156) is ALSO fully commented out. So shadow-map preservation
  across portal recursion is a designed-but-DISABLED feature end-to-end.
  PORT IMPLICATION: faithful port keeps all halves dormant. The live consequence today: shaderpack
  shadow maps are NOT preserved across portal-world recursion (a known visual limitation the experimental
  path accepts). Reviving on 1.11.2 requires the `<init>(int resolution, InternalTextureFormat[] formats)`
  ShadowRenderTargets ctor signature to still match â€” HIGH churn risk.

â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
FILE 6 â€” MixinIrisSodiumShader.java   (4228 bytes)  â€” sodium+iris gated
Target: @Mixin(value = net.irisshaders.iris.pipeline.programs.SodiumShader.class, remap=false)
â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
@Unique private GlUniformFloat4v uIPClippingEquation;
   (net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v)

INJECTOR 1 â€” onInit
  @Inject(method="<init>", at=@At("RETURN"))
  Full ctor signature captured (all must match for the inject to bind):
     (IrisRenderingPipeline pipeline, SodiumPrograms.Pass pass, ShaderBindingContext context,
      int handle, BlendModeOverride blendModeOverride, List bufferBlendOverrides,
      CustomUniforms customUniforms, Supplier flipState, float alphaTest,
      boolean containsTessellation, CallbackInfo ci)
  Body:
     this.uIPClippingEquation = context.bindUniformOptional("iportal_ClippingEquation", GlUniformFloat4v::new);
     logs "Found/not found iportal_ClippingEquation in program {handle}".
  MECHANISM: at the end of each Sodium-under-Iris shader program construction, look up (optionally
  bind) the custom uniform `iportal_ClippingEquation` that IP's shader transform injected into the
  terrain/water vertex shaders (see FILE 7 + yaml below). Binding happens through Sodium's own
  ShaderBindingContext.bindUniformOptional so it participates in Sodium's uniform state batching.
  DEPENDS ON: Sodium's ShaderBindingContext.bindUniformOptional(String, Supplier) + GlUniformFloat4v
  ctor-ref; Iris's SodiumShader ctor shape; the uniform actually being present in the transformed GLSL.

INJECTOR 2 â€” onSetup
  @Inject(method="setupState", at=@At("RETURN"), remap=false)
  Body:
     if (uIPClippingEquation != null) {
        if (FrontClipping.isClippingEnabled) {
           double[] eq = FrontClipping.getActiveClipPlaneEquationAfterModelView();
           uIPClippingEquation.set(new float[]{(float)eq[0],(float)eq[1],(float)eq[2],(float)eq[3]});
        } else {
           uIPClippingEquation.set(new float[]{0,0,0,1});  // disabled plane => never clips
        }
     }
  MECHANISM: every time Sodium binds this program's uniform state, push IP's current oblique
  front-clip plane (in AFTER-modelview space) into the shader uniform, or a no-op plane {0,0,0,1}
  when clipping is off.
  PURPOSE: this is the CORE reason Sodium+Iris terrain gets clipped at the portal plane. The GLSL
  (injected by FILE 7 for iris_gbuffers_terrain/water) computes gl_ClipDistance[0] =
  dot((iris_ModelViewMatrix*getVertexPosition()).xyz, eq.xyz)+eq.w, so terrain behind the portal
  plane is discarded. Feeds FrontClipping.getActiveClipPlaneEquationAfterModelView() (a static
  double[4] snapshot, FrontClipping.java:171) updated per-portal via FrontClipping.updateInnerClipping.
  DEPENDS ON: FrontClipping.isClippingEnabled + getActiveClipPlaneEquationAfterModelView();
  GlUniformFloat4v.set(float[]).

COMMENTED-OUT (dormant, verbatim-ported) â€” the PRE-rewrite raw-GL variant:
  A whole alternate implementation using `private int uIPClippingEquation;` + manual
  GL20C.glGetUniformLocation(shaderId,"imm_ptl_ClippingEquation") in an `ip_init(int shaderId)`, an
  `@Inject(<init> RETURN, require=0)` with the OLD Sodium ctor shape
  (int handle, ShaderBindingContextExt contextExt, SodiumTerrainPipeline pipeline,
   ChunkShaderOptions options, boolean isTess, boolean isShadowPass, BlendModeOverride,
   List bufferOverrides, float alpha, CustomUniforms, CallbackInfo), and a setupState @Inject using
  raw GL21.glUniform4f. NOTE the uniform NAME differs: dormant path uses "imm_ptl_ClippingEquation";
  live path uses "iportal_ClippingEquation". This dormant block is the direct evolutionary record of
  how the Sodium/Iris uniform-binding API changed (raw location int -> GlUniformFloat4v + bindUniformOptional;
  ctor arg list overhaul). For the C2 0.9.1 port this block is the single best "shape of API drift" reference.

â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
FILE 7 â€” MixinIrisTransformPatcher.java   (3052 bytes)  â€” iris gated, @Pseudo
Target: @Pseudo @Mixin(value = net.irisshaders.iris.pipeline.transform.TransformPatcher.class, remap=false)
â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
Note the @Pseudo â€” TransformPatcher may be absent/renamed; the mixin tolerates the target not
existing and binds loosely by name.

INJECTOR â€” onTransformInternal  (STATIC inject)
  @Inject(method="transformInternal", at=@At("RETURN"), cancellable=true)
  Signature: private static void onTransformInternal(
        String name, Map<PatchShaderType,String> inputs,
        Parameters parameters, CallbackInfoReturnable<Map<PatchShaderType,String>> cir)
  Body:
     Map<PatchShaderType,String> map = cir.getReturnValue();
     String code = map.get(PatchShaderType.VERTEX);
     if (code != null) {
        String transformed = ShaderCodeTransformation.transform(
             CompiledShader.Type.VERTEX, "iris_" + name, code);
        map.put(PatchShaderType.VERTEX, transformed);   // note: cir not re-set; map mutated in place
     }
  MECHANISM: after Iris's own GLSL transform runs, intercept the returned per-PatchShaderType code
  map, take the VERTEX entry, run IP's regex ShaderCodeTransformation over it keyed by "iris_"+name,
  and write the result back into the same map. cancellable=true is declared but the code mutates the
  existing returned map rather than calling cir.setReturnValue â€” it relies on the map being the live
  return object (works because Iris returns the mutable map by reference).
  PURPOSE: inject the `iportal_ClippingEquation` uniform + gl_ClipDistance write into Iris's
  transformed terrain/water vertex shaders. The keying is: TransformPatcher.transformInternal is
  called with name e.g. "gbuffers_terrain"/"gbuffers_water"; "iris_"+name -> "iris_gbuffers_terrain"
  /"iris_gbuffers_water", which the yaml config "For iris+sodium" matches.
  DEPENDS ON: ShaderCodeTransformation.transform(CompiledShader.Type, String, String) and the yaml
  resource assets/immersive_portals/shaders/shader_transformation.yaml. The yaml block that this
  injector drives (verbatim from the resource):
     type: vs ; affectedShaders: [iris_gbuffers_terrain, iris_gbuffers_water]
     transform 1: `void main(){` -> prepend `uniform vec4 iportal_ClippingEquation; void main(){`
     transform 2 (regex `\}(?![\S\s]*\})` = last brace) -> insert before final }:
        gl_ClipDistance[0] = dot((iris_ModelViewMatrix*getVertexPosition()).xyz,
                                  iportal_ClippingEquation.xyz) + iportal_ClippingEquation.w; }
  So FILE 7 injects the GLSL uniform; FILE 6 binds+feeds it at runtime. They are a PAIR â€” port them
  together. (ShaderCodeTransformation only loads the yaml when IPGlobal.enableClippingMechanism; if
  disabled, transform() returns input unchanged and shouldAddUniform() is false.)

COMMENTED-OUT (dormant, verbatim-ported) â€” the older whole-file patch approach:
  @Shadow Optional<String> terrainSolidVertex / terrainCutoutVertex / translucentVertex; a @Unique
  boolean immptlPatched guard; and `//@Inject(method="patchShaders", at=@At("RETURN"))
  onPatchShaderEnds(ChunkVertexType, CallbackInfo)` that mapped each of the three Optional<String>
  vertex sources through ShaderCodeTransformation.transform(Program.Type.VERTEX,
  "iris_sodium_terrain_vertex", code), with an "iris terrain shader ImmPtl patched twice" error on
  re-entry. INTENT: earlier Iris exposed the three sodium terrain vertex sources as shadowable fields
  patched once at patchShaders RETURN; the newer API funnels everything through transformInternal's
  return map instead. This dormant block documents the OLD hook name (patchShaders) + the OLD single
  shader key ("iris_sodium_terrain_vertex") vs. the new per-name keying.

â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
CORE-SIDE (non-gated) â€” MixinLevelRenderer_BeforeIris.java
Path: src/main/java/qouteall/imm_ptl/core/mixin/client/render/MixinLevelRenderer_BeforeIris.java
Target: @Mixin(value = net.minecraft.client.renderer.LevelRenderer.class, priority = 900)
â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
This is a VANILLA-targeting mixin (in the core mixin config, always applied â€” not gated by iris),
but it is the ENTRY POINT of the whole experimental-Iris portal draw.
INJECTOR â€” iris$beginTranslucents
  @Inject(method="renderLevel", at=@At(value="CONSTANT", args="stringValue=translucent"))
  Signature: (DeltaTracker, boolean, Camera, GameRenderer, LightTexture,
              Matrix4f modelView, Matrix4f matrix4f2, CallbackInfo ci)
  Body:  IPCGlobal.renderer.onBeginIrisTranslucentRendering(modelView);
  MECHANISM: priority=900 (LOWER number = HIGHER priority; applied EARLIER). The header comment is
  precise: "inject it after Iris, run before Iris". Because Iris ALSO injects at the same
  `CONSTANT stringValue="translucent"` marker in renderLevel, priority ordering makes IP's callback
  fire at that marker BEFORE Iris's translucent handling for the same location. The injection point
  is the string constant "translucent" (the profiler push / render-type name) in vanilla
  LevelRenderer.renderLevel â€” i.e. the boundary between opaque and translucent terrain.
  PURPOSE: this is where the experimental renderer does its stencil-based portal draw, wedged in
  after opaque terrain but before Iris starts translucents.
  CALL TARGET: PortalRenderer.onBeginIrisTranslucentRendering(Matrix4f) â€” base impl is EMPTY (no-op,
  PortalRenderer.java:306). Only ExperimentalIrisPortalRenderer overrides it, so for every other
  renderer (rendererUsingStencil / IrisPortalRenderer / IrisCompatibilityPortalRenderer / dummy)
  this hook is inert. The call is UNCONDITIONAL (no instanceof guard here) precisely because the
  virtual dispatch does the gating.

â”€â”€ THE onBeginIrisTranslucentRendering CHAIN (ExperimentalIrisPortalRenderer:57) â”€â”€
  onBeginIrisTranslucentRendering(modelView):
    (1) client.renderBuffers().bufferSource().endBatch();
        // comment: "Iris's buffers are deferred, changing a render layer won't cause it to draw...
        //           TODO switch to a separate buffer source for Iris" â€” flush pending vanilla batches
        //           so entity/particle geometry is committed before the portal stencil work.
    (2) doPortalRendering(modelView):
          RenderSystem.enableDepthTest(); RenderSystem.depthMask(true);
          Profiler.popPush("render_portal_total"); renderPortals(modelView);
    (3) ((IEIrisNewWorldRenderingPipeline)(Object) Iris.getPipelineManager().getPipeline().get())
            .ip_setIsRenderingWorld(true);   // "Resume Iris world rendering" (see FILE 1 duck)

  renderPortals(modelView) (:175):
     - getPortalsToRender(modelView)  (base PortalRenderer)
     - for each portal: doRenderPortal(portal, modelView) -> collect reallyRenderedPortals
     - setStencilStateForWorldRendering()
     - for each reallyRendered non-fuseView portal: renderPortalViewAreaToStencil(...) again
       (comment: "draw the portal areas again to increase stencil to limit the area of Iris
        deferred composite rendering") â€” THIS is the stencil region that INJECTOR 2 of FILE 1
        (onAfterDeferredCompositeRendering) later clamps back down.
     - setStencilStateForWorldRendering()

  doRenderPortal(portal, modelView) (:206) â€” the per-portal stencil recursion:
     - RendererUsingStencil.shouldSkipRenderingInsideFuseViewPortal guard
     - outerPortalStencilValue = PortalRendering.getPortalLayer()
     - renderAndDecideVisibility(portal, () -> renderPortalViewAreaToStencil(portal, modelView))
       [occlusion query gate â€” this is the PortalRenderInfo.renderAndDecideVisibility that the S19
        Temurin C2 JIT crash was tied to]
     - if visible: PortalRendering.pushPortalLayer(portal);
         if !fuseView: clearDepthOfThePortalViewArea(portal)  (full-screen tri, depth=1 in masked area)
         setStencilStateForWorldRendering(); renderPortalContent(portal);  [recurses world render]
         if !fuseView: restoreDepthOfPortalViewArea(portal, modelView)  (redraw portal area depth,
              GL_ALWAYS, ViewAreaRenderer.renderPortalArea with clip=true)
         clampStencilValue(outerPortalStencilValue); PortalRendering.popPortalLayer();

  renderPortalViewAreaToStencil(portal, modelView) (:267):
     glStencilFunc(GL_EQUAL, outerPortalStencilValue, 0xFF);
     glStencilOp(GL_KEEP, GL_KEEP, GL_INCR); glStencilMask(0xFF);
     FrontClipping.updateInnerClipping(modelView);   // <-- refreshes the clip plane FILE 6 reads
     ViewAreaRenderer.renderPortalArea(portal, Vec3.ZERO, modelView, RenderSystem.getProjectionMatrix(),
                                       true, false, true, true);

  invokeWorldRendering(worldRenderInfo) (:120) â€” the recursive world draw wrapper:
     pipeline = Iris.getPipelineManager().getPipeline().get();
     SystemTimeUniforms.COUNTER.beginFrame();  // "is it necessary?"
     super.invokeWorldRendering(worldRenderInfo);
     SystemTimeUniforms.COUNTER.beginFrame();  // "make Iris update the uniforms"
     if (pipeline instanceof IrisRenderingPipeline p) p.isBeforeTranslucent = true;  // PUBLIC field,
        // set directly (NO mixin) â€” "this is important to hand rendering"
     ((IEIrisNewWorldRenderingPipeline)(Object) pipeline).ip_setIsRenderingWorld(false);  // FILE 1 duck
        // "Avoid Iris from force-disabling depth mask"
     (the ShadowMapSwapper acquire/copy blocks around this are commented out â€” see FILE 5)

  Other overrides feeding the Iris path:
     replaceFrameBufferClearing() -> PortalRendering.isRendering()  (mirror of FILE 2's ClearPass cancel;
        called from core MixinLevelRenderer.java:317)
     prepareRendering() -> enable stencil on main render target via IPPortingLibCompat
        .getIsStencilEnabled/setIsStencilEnabled(client.getMainRenderTarget()), bindWrite(false),
        glClearStencil(0)/glClear(STENCIL_BUFFER_BIT), enableDepthTest, glEnable(GL_STENCIL_TEST)
     finishRendering()/myFinishRendering() -> reset stencil func/op, glDisable(GL_STENCIL_TEST)
     onBeforeTranslucentRendering(modelView) -> EMPTY (the experimental path uses onBegin*Iris* instead)
     onHandRenderingEnded() -> nothing ; renderPortalInEntityRenderer(portal) -> nothing

â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
SUPPORTING TYPES (dependency ledger for the C2 port)
â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
- IEIrisNewWorldRenderingPipeline  : duck iface, 1 live method ip_setIsRenderingWorld(boolean);
   commented ip_getShadowRenderTargets(). Implemented by FILE 1.
- IEIrisShadowRenderTargets        : EMPTY iface (its 1 method commented). Implemented by FILE 5.
- IrisInterface (Invoker / OnIrisPresent) : reflection-based presence layer. OnIrisPresent uses
   Iris.getCurrentPack(), ShadowRenderer.ACTIVE, Iris.getCurrentPackName(), Iris.getPipelineManager()
   .destroyPipeline(), and REFLECTS LevelRenderer's private field "pipeline"
   (Field LevelRenderer.class.getDeclaredField("pipeline")) to get/set the WorldRenderingPipeline.
   This is the non-mixin coupling that MC 26.2's renderer rewrite most likely breaks (field name).
- IPIrisHelper : GL blit/copy helpers (copyDepthStencil via glBlitFramebuffer using
   RenderTarget.frameBufferId; newCopyDepthStencil/copyColor via GL43C.glCopyImageSubData using
   RenderTarget.getDepthTextureId/getColorTextureId). Used by the frame-buffer stencil path.
- ShadowMapSwapper : dormant (paired with FILE 5) shadow-map cache.
- ShaderCodeTransformation.transform(CompiledShader.Type, String, String) + yaml (drives FILE 7).
- FrontClipping.isClippingEnabled / getActiveClipPlaneEquationAfterModelView() (double[4]) /
   updateInnerClipping(Matrix4f) (drives FILE 6, refreshed in renderPortalViewAreaToStencil).
- IPCGlobal flags: experimentalIrisPortalRenderer=false (selects this whole path),
   debugEnableStencilWithIris=false (FILE 3 gate).

â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
DORMANCY SUMMARY (ported-faithful, no live behavior)
â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
FULLY dormant classes: MixinIrisIris (2 commented injects: getCurrentDimension->OVERWORLD,
  isSodiumInvalid->false), MixinIrisShadowRenderTargets (whole ShadowMapSwapper lifecycle commented;
  empty marker iface).
Dormant fragments inside live classes: MixinIrisRenderingPipeline (shadowRenderTargets shadow+accessor),
  MixinIrisSodiumShader (entire raw-GL "imm_ptl_ClippingEquation" variant + old ctor shape),
  MixinIrisTransformPatcher (patchShaders/@Shadow Optional<String> triple-vertex variant).
Debug-flag dormant at runtime: MixinIrisFinalPassRenderer (debugEnableStencilWithIris=false).
Consumer-side dormancy: ExperimentalIrisPortalRenderer.invokeWorldRendering shadow-map blocks.


### Risk notes (irisMixins)
- MixinIrisRenderingPipeline â€” HIGHEST RISK. Three separate Iris-internal couplings that a 0.6->0.9.1 rewrite is very likely to move (UNVERIFIED until 0.9.1 javap): (a) private field `isRenderingWorld` (shadowed + duck-set) â€” Iris may have renamed/removed it or changed how it force-disables depth mask; (b) method `finalizeLevelRendering` (HEAD cancel); (c) method `beginTranslucents` and, inside it, the exact INVOKE `net/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V` â€” Iris 1.11.2 restructured its composite/translucent pass around MC's new render-pass/FrameGraph model, so both the method name AND the internal renderAll() call site are prime movers. If beginTranslucents no longer wraps a single CompositeRenderer.renderAll(), INJECTOR 2 must be re-anchored to wherever the deferred composite now runs.
- MixinIrisClearPass â€” target net.irisshaders.iris.targets.ClearPass and method execute(Vector4f). UNVERIFIED whether the `targets` package / ClearPass class survive in 1.11.2, and whether execute still takes a single org.joml.Vector4f (clear color). MC 26.2's move toward RenderPass-based clears could replace ClearPass entirely; if so the frame-clear suppression must move to the new clear mechanism (the mirror ExperimentalIrisPortalRenderer.replaceFrameBufferClearing() still works, so worst case this mixin is dropped and clearing is suppressed only core-side).
- MixinIrisFinalPassRenderer â€” target net.irisshaders.iris.pipeline.FinalPassRenderer#renderFinalPass. Debug-only (debugEnableStencilWithIris defaults false) so a broken bind is low-impact, but the class/method may be renamed. Safe to leave with require=1 only if the target still exists; consider require=0 or dropping for the port since it is inert.
- MixinIrisIris â€” fully commented; zero live risk. But note the two shelved intents (getCurrentDimension->OVERWORLD dimension spoof; isSodiumInvalid->false dev override) reference method names that likely changed in 1.11.2. If C2 ever needs the dimension spoof (per-dimension shader state) or a Sodium-version override, verify those method names fresh â€” do not trust the commented signatures.
- MixinIrisShadowRenderTargets â€” fully dormant; the empty marker + implements is harmless. The commented `<init>(int resolution, InternalTextureFormat[] formats)` ShadowRenderTargets ctor and `destroy()` are almost certainly stale on 1.11.2. Keep dormant; only revive with fresh javap if shadow-map-across-recursion is wanted.
- MixinIrisSodiumShader â€” DUAL RISK (Iris + Sodium). Target net.irisshaders.iris.pipeline.programs.SodiumShader. The live @Inject(<init>) hard-codes a 10-arg ctor (IrisRenderingPipeline, SodiumPrograms.Pass, ShaderBindingContext, int, BlendModeOverride, List, CustomUniforms, Supplier, float, boolean) â€” this ctor shape changed once already (the commented block proves it) and is a prime mover for 1.11.2. Sodium 0.9.1 side: net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v and ShaderBindingContext.bindUniformOptional(String, Supplier) may have moved packages or changed API (Sodium 0.6->0.9.1 spans the MC 26.2 renderer rewrite). setupState(RETURN) also assumed. HIGH likelihood this file needs a full re-derivation of both the ctor descriptor and the Sodium uniform-binding API.
- MixinIrisTransformPatcher â€” @Pseudo (tolerant of absence). Target net.irisshaders.iris.pipeline.transform.TransformPatcher#transformInternal(String, Map<PatchShaderType,String>, Parameters). Iris reworks its shader-transform pipeline frequently; transformInternal's name/signature and the PatchShaderType / Parameters types are UNVERIFIED for 1.11.2. Also the driven yaml keys (iris_gbuffers_terrain / iris_gbuffers_water) assume Iris still names its programs gbuffers_terrain/gbuffers_water and still exposes iris_ModelViewMatrix + getVertexPosition() in the GLSL â€” any change to Iris's sodium-terrain GLSL entry points breaks the injected gl_ClipDistance write even if the mixin binds. FILE 6 and FILE 7 MUST be re-verified as a pair.
- MixinLevelRenderer_BeforeIris â€” targets VANILLA LevelRenderer#renderLevel at @At(CONSTANT, stringValue="translucent"). MC 26.2 wholesale-rewrote the renderer; renderLevel's method shape (the captured params DeltaTracker/boolean/Camera/GameRenderer/LightTexture/Matrix4f/Matrix4f) and, critically, whether a bare string constant "translucent" still exists at the opaque->translucent boundary are BOTH likely changed. The priority=900 "after-Iris-in-json, run-before-Iris-at-runtime" ordering trick also depends on Iris still injecting at the same marker â€” if either side moves to a RenderPass/FrameGraph node, this entry point needs a completely new anchor. This is the linchpin: if it does not bind, the entire experimental-Iris portal draw never fires.
- Non-mixin couplings that break silently (no require=1 to catch them): IrisInterface.OnIrisPresent reflects LevelRenderer's private field "pipeline" by exact name â€” MC 26.2's renderer rewrite very plausibly renamed/removed it, and Iris 1.11.2 may store the pipeline elsewhere; this get/setPipeline reflection failing degrades to Helper.noError swallowing the error (returns null) => renderer selection silently misbehaves. Also ExperimentalIrisPortalRenderer sets the PUBLIC field IrisRenderingPipeline.isBeforeTranslucent directly and calls Iris.getPipelineManager().getPipeline().get(), SystemTimeUniforms.COUNTER.beginFrame(), Iris.getCurrentPack()/getCurrentPackName(), ShadowRenderer.ACTIVE â€” all Iris public API that should be re-confirmed against 1.11.2.
- Whole-path caveat (not a per-file API risk but a scope note): every ExperimentalIrisPortalRenderer-gated body only runs when IPCGlobal.experimentalIrisPortalRenderer==true AND a shaderpack is active. Default is false, so the DEFAULT Iris-with-shaders path is IrisPortalRenderer/IrisCompatibilityPortalRenderer (separate files, not in this tracer's 7). FILE 6 (SodiumShader clipping) and FILE 7 (TransformPatcher) are the exception â€” they are renderer-agnostic and feed the clipping uniform for ALL iris shader renderers, so they are load-bearing even when the experimental flag is off. Prioritize FILES 6+7 and MixinLevelRenderer_BeforeIris for the C2 port; the experimental-only stencil mixins (FILES 1,2) can follow once the experimental renderer itself is validated on 26.2.


---


# TRACER C â€” Iris Portal Renderers + Helpers (IP 1.21.3, Sodium 0.6 / Iris 1.8). Full-depth injection-level pass.

All paths are under `C:\Users\warwa\ModDev\ImmersivePortalsMod\src\main\java\`. Nothing under Portal 26.2 was touched.

================================================================
## PART 0 â€” RENDERER SELECTION + LIFECYCLE DRIVER MAP (the frame skeleton)
================================================================

### 0.1 Renderer selection state machine
`qouteall/imm_ptl/core/render/renderer/PortalRenderer.java` â†’ `switchToCorrectRenderer()` (lines 310-348), called every frame from `MixinGameRenderer.onBeforeRenderingCenter` (see 0.3) immediately before `prepareRendering()`.

Decision tree:
- Guard: if `PortalRendering.isRendering()` return (never switch mid-recursion).
- FABULOUS graphics â†’ one-shot chat warning (`imm_ptl.fabulous_warning`), continues.
- `IPModInfoChecking.checkShaderpack()`.
- If `IrisInterface.invoker.isIrisPresent()` AND `IrisInterface.invoker.isShaders()` (a shaderpack is actually active):
  - If `IPCGlobal.experimentalIrisPortalRenderer` â†’ `ExperimentalIrisPortalRenderer.instance` (return).
  - Else switch on `IPGlobal.renderMode`: `normal`â†’`IrisPortalRenderer.instance`; `compatibility`â†’`IrisCompatibilityPortalRenderer.instance`; `debug`â†’`IrisCompatibilityPortalRenderer.debugModeInstance`; `none`â†’`rendererDummy`.
- Else (no Iris, or Iris present but shaders off) â†’ the non-Iris renderers (`rendererUsingStencil`/`rendererUsingFrameBuffer`/`rendererDebug`/`rendererDummy`).

`switchRenderer()` (350-359): only acts when the target differs; stores into `IPCGlobal.renderer`; **and if shaders active, calls `IrisInterface.invoker.reloadPipelines()` â†’ `Iris.getPipelineManager().destroyPipeline()`.** So every renderer swap while a pack is loaded force-destroys the Iris pipeline (rebuilt lazily next frame). This is the mechanism that makes the runtime `/imm_ptl_client_debug` toggle of `experimentalIrisPortalRenderer` (ClientDebugCommand.java:639) take effect.

`experimentalIrisPortalRenderer` default = false (`IPCGlobal.java:37`); it is a **debug/experimental opt-in**, never on by default. So the SHIPPING Iris paths are IrisPortalRenderer (normal) and IrisCompatibilityPortalRenderer (compat/debug).

### 0.2 Invoker install / gating
- `IrisInterface.invoker` starts as the no-op base `Invoker` (all false/null). In `platform_specific/IPModEntryClient.java:92-95`, when `FabricLoader.isModLoaded("iris")`, it is replaced with `new IrisInterface.OnIrisPresent()` and `ExperimentalIrisPortalRenderer.init()` (empty) is called. So all Iris behavior is class-load-gated behind the invoker swap, and NO `net.irisshaders.*` type is touched unless Iris is present.
- Compat mixins (`imm_ptl_compat.mixins.json`, plugin `compat/IPCompatMixinPlugin.java`) are gated by **class-name substring** in `shouldApplyMixin`: name contains `IrisSodium` â†’ require sodium AND iris; contains `Iris` â†’ require iris; contains `Sodium` â†’ require sodium. All Iris mixins are `remap = false` (Iris ships its own non-obfuscated names); the shader/sodium-bridge ones are additionally `@Pseudo` where the target may be absent (`MixinIrisTransformPatcher`, `MixinSodiumDefaultShaderInterface`).

### 0.3 Lifecycle callback â†’ driving injection point (THE C2 RE-TARGET SURFACE)
Base contract declared abstract in `PortalRenderer.java` (63-82, 306). Callers:

| Callback | Driver mixin + injection point |
|---|---|
| `prepareRendering()` | `mixin/client/render/MixinGameRenderer.java` `onBeforeRenderingCenter` â€” `@Inject` in `render` at `INVOKE GameRenderer.renderLevel(DeltaTracker)` (HEAD-of-call). Preceded in same method by `switchToCorrectRenderer()`. NOT fired during nested portal renders. |
| `finishRendering()` | `MixinGameRenderer.onAfterRenderingCenter` â€” `@Inject render` at same `renderLevel` INVOKE, `shift=AFTER`. |
| `onBeforeHandRendering(modelView)` | `MixinGameRenderer.wrapRenderLevel` â€” `@WrapOperation` on `LevelRenderer.renderLevel(GraphicsResourceAllocator, DeltaTracker, boolean, Camera, GameRenderer, LightTexture, Matrix4f, Matrix4f)` inside `GameRenderer.renderLevel`; fires AFTER `original.call(...)`. **Fires on EVERY nested world render** (portal content recursion runs through the same GameRenderer.renderLevel â†’ LevelRenderer.renderLevel path), which is how IrisPortalRenderer + IrisCompatibility get their per-layer entry. |
| `onHandRenderingEnded()` | `MixinGameRenderer.onRenderCenterEnded` â€” `@Inject renderLevel` at `TAIL`. |
| `onBeforeTranslucentRendering(modelView)` | `mixin/client/render/MixinLevelRenderer.java` `onMyBeforeTranslucentRendering` â€” `@Inject` in `method_62214` (the addMainPass lambda) at `INVOKE Sheets.translucentItemSheet()`. The vanilla/stencil translucent hook. Also does `updateFogColor/resetFogState/resetDiffuseLighting/FrontClipping.disableClipping`. (Note: an earlier `onBeforeTranslucentRendering` call at the `constantAmbientLight()` point is COMMENTED OUT, line 133.) |
| `onBeginIrisTranslucentRendering(modelView)` | `mixin/client/render/MixinLevelRenderer_BeforeIris.java` â€” a SEPARATE `@Mixin(LevelRenderer.class, priority = 900)` whose sole `@Inject` is `renderLevel` at `@At(value="CONSTANT", args="stringValue=translucent")`. Priority 900 (< default 1000) so IP's callback runs BEFORE Iris's own injection at the same string constant. Only `ExperimentalIrisPortalRenderer` overrides this. |
| `replaceFrameBufferClearing()` | `MixinLevelRenderer.redirectClearing` â€” `@Redirect` in `method_62218` (a renderLevel lambda) on `RenderSystem.clear(I)`. Returns true â†’ skip the vanilla clear. |
| `onAfterIrisDeferredCompositeRendering()` (Experimental only) | `compat/mixin/iris/MixinIrisRenderingPipeline.java` `onAfterDeferredCompositeRendering` â€” `@Inject` in `IrisRenderingPipeline.beginTranslucents` at `INVOKE CompositeRenderer.renderAll()`, `shift=AFTER`. |

Two more Experimental-only Iris cancellations:
- `MixinIrisRenderingPipeline.onFinalizeLevelRendering` â€” `@Inject finalizeLevelRendering HEAD cancellable`; cancels iff `renderer instanceof ExperimentalIrisPortalRenderer && PortalRendering.isRendering()`.
- `MixinIrisClearPass.onExecute` â€” `@Inject ClearPass.execute(Vector4f) HEAD cancellable`; same guard. Stops Iris re-clearing its GBuffer targets during nested portal-world rendering.
- `MixinIrisFinalPassRenderer.onRenderFinalPass` â€” `@Inject renderFinalPass HEAD`; if `IPCGlobal.debugEnableStencilWithIris` disables `GL_STENCIL_TEST`. (debug only)

================================================================
## PART 1 â€” PER-RENDERER FULL RENDER-LOOP WALKTHROUGHS
================================================================

Shared substrate:
- `SecondaryFrameBuffer` (render/SecondaryFrameBuffer.java): thin wrapper over a `TextureTarget(w,h, hasDepth=true, ON_OSX)`, auto-resized to the main FBO size each `prepare()`. Field `fb` is the `RenderTarget`.
- `IPPortingLibCompat.setIsStencilEnabled(rt, bool)` / `getIsStencilEnabled`: toggles stencil attachment. Uses porting_lib's `port_lib$stencilEnabled` field by reflection if porting_lib present, else the `IEFrameBuffer` duck (`ip_setIsStencilBufferEnabledAndReload`). Setting it **resizes/reloads the FBO** to add/remove the stencil buffer. This is load-bearing: Iris renderers flip stencil on/off on the MAIN target.
- `MyRenderHelper.drawScreenFrameBuffer(rt, doBlend, doModifyAlpha)` (render/MyRenderHelper.java:306): IP's manual full-screen blit of a color texture using core shaders `CoreShaders.BLIT_SCREEN` (blend) or the IP-registered `BLIT_SCREEN_NOBLEND` (`ShaderProgram`, DefaultVertexFormat.BLIT_SCREEN). The blend path uses the premultiplied func `blendFuncSeparate(ONE, ONE_MINUS_SRC_ALPHA, ZERO, ONE)` so an FBO cleared to zero-color/zero-alpha composites like direct translucent draw.
- `MyRenderHelper.renderScreenTriangle(...)`: full-screen triangle pair via `CoreShaders.POSITION_COLOR`, identity ortho. Used to clamp/clear depth in stencil regions.
- `ViewAreaRenderer.renderPortalArea(portal, fogColor, modelView, proj, doFaceCulling, doModifyColor, doModifyDepth, doClip)` (render/ViewAreaRenderer.java:25): draws the portal's geometry (the "view area" quad/mesh) with those four boolean switches.
- `PortalRenderInfo.renderAndDecideVisibility(portal, runnable)`: wraps the runnable in a GL occlusion query; returns whether any sample passed (visibility gate).

â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
### 1A. IrisCompatibilityPortalRenderer  (compat/debug mode â€” the most robust, ONE portal layer)
File: `compat/iris_compatibility/IrisCompatibilityPortalRenderer.java`
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
No stencil, no shader clipping reliance; uses `glCopyImageSubData` (GL 4.3) + a portal-area compositing shader. Two singletons: `instance` (isDebugMode=false) and `debugModeInstance` (true). One `SecondaryFrameBuffer deferredBuffer`. Stores `passingModelView` between the translucent hook and the hand hook.

Frame anatomy:
1. `prepareRendering()` (before world render): `deferredBuffer.prepare()`; set its clear color `(1,0,0,0)`; `clear()`; **disable stencil on the MAIN target**; `bindWrite(false)` the main target. (Portal content will render into the MAIN fbo with Iris's normal deferred pipeline; the deferred buffer only holds the composite.)
2. World + Iris pipeline render normally to main FBO.
3. `onBeforeTranslucentRendering(modelView)` (vanilla translucent hook): if not already recursing, save `passingModelView = modelView`; `glDisable(GL_STENCIL_TEST)`.
4. `onBeforeHandRendering(modelView)` (after full LevelRenderer.renderLevel, before hand) â€” the workhorse:
   a. `IPIrisHelper.newCopyDepthStencil(main, deferredBuffer.fb)` then `IPIrisHelper.copyColor(main, deferredBuffer.fb)` â€” **`GL43C.glCopyImageSubData` copies the finished main-FBO depth texture then color texture into the deferred buffer** (the commented alt is a `glBlitFramebuffer` COLOR|DEPTH). This snapshots the world.
   b. `renderPortals(passingModelView)` â†’ for each visible portal `doRenderPortal`.
   c. Re-bind main; `drawScreenFrameBuffer(deferredBuffer.fb, blend=false, modifyAlpha=false)` â€” blit the composited deferred buffer back onto the main FBO.
5. `finishRendering()` / `onHandRenderingEnded()`: just `glDisable(GL_STENCIL_TEST)` / nothing.

`doRenderPortal(portal, modelView)`:
- Guard `if (PortalRendering.isRendering()) return;` â†’ **hard one-layer limit** (no portal-in-portal).
- `testShouldRenderPortal`: bind deferred buffer, `renderAndDecideVisibility` drawing the portal area (`doFaceCulling=true, doModifyColor=false, doModifyDepth=false, doClip=true`) to seed the occlusion query only.
- Bind MAIN; `pushPortalLayer`; `renderPortalContent(portal)` (nested world into MAIN fbo); `popPortalLayer`.
- `CHelper.enableDepthClamp()`.
- Non-debug: bind deferred buffer, `MyRenderHelper.drawPortalAreaWithFramebuffer(portal, main, modelView, proj)` â€” draws ONLY the portal-shaped triangles, sampling the main FBO color via the IP core shader `PORTAL_DRAW_FB_IN_AREA` (uniforms `DiffuseSampler`, `w`, `h`, MODEL_VIEW, PROJECTION), i.e. stamps the just-rendered portal-world image into the portal region of the deferred buffer.
- Debug: instead `drawScreenFrameBuffer(main, true, true)` (full-screen, shows the raw portal world).
- `disableDepthClamp`; restore colorMask; bind main.

GL-calls summary: `glCopyImageSubData Ã—2` (depth, color), one occlusion query, one nested world render into main, one portal-area triangle draw sampling main, one full-screen blit back. **No stencil at all.** This is why it survives Iris versions that don't expose stencil on the main target.

â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
### 1B. IrisPortalRenderer  (normal mode, DEFERRED multi-layer, stencil)
File: `compat/iris_compatibility/IrisPortalRenderer.java`
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
The default shipping renderer when a pack is active. Renders portals AFTER the entire Iris deferred pipeline finishes (in the hand hook), using an ARRAY of stencil-enabled deferred FBs, one per portal layer. Key insight: Iris (1.8) uses the vanilla main FBO's depth; IP keeps the MAIN target stencil-DISABLED (so it stays Iris-compatible) and does all stencil work in its own `deferredFbs[]`.

Fields: `SecondaryFrameBuffer[] deferredFbs` (size = maxPortalLayer+1); `portalRenderingNeeded` (this frame); `nextFramePortalRenderingNeeded`. Constructor registers `PRE_GAME_RENDER_EVENT â†’ updateNeedsPortalRendering()` which shifts nextFrameâ†’this: **portal rendering has ONE FRAME of latency** (a portal seen this frame is only fully composited next frame; frame 0 just flags need).

`replaceFrameBufferClearing()` â†’ false (let vanilla/Iris clear normally).

`prepareRendering()` (before world render):
- `IPCGlobal.useSeparatedStencilFormat = !IPMcHelper.isNvidiaVideocard()` â€” **GPU-vendor branch**: Nvidia's `glCopyImageSubData` can convert depth32â†’depth24stencil8; AMD cannot, needs depth32stencil8 (separated). Drives the deferred FB depth/stencil format.
- If `deferredFbs.length != maxPortalLayer+1`: destroy + reallocate the array.
- For each deferred FB: `prepare()`, `setIsStencilEnabled(fb, true)`, `bindWrite`, clear color `(1,0,1,0)` / depth 1 / stencil 0, `checkStatus`, `unbindWrite`.
- `setIsStencilEnabled(main, false)`; bind main (no clear). (Main stays non-stencil for Iris.)

`onBeforeHandRendering(modelView)` â†’ `doMainRenderings(modelView)` (fires per nested world render; `portalLayer = PortalRendering.getPortalLayer()`):
- If `portalRenderingNeeded`:
  1. **Depth blit mainâ†’deferredFbs[portalLayer]**: `glBindFramebuffer(READ, main); glBindFramebuffer(DRAW, deferred[layer]); glBlitFramebuffer(..., GL_DEPTH_BUFFER_BIT, GL_NEAREST)`. If `glGetError()!=GL_NO_ERROR` â†’ **auto-fallback**: `IPGlobal.renderMode = compatibility` + chat "Switched to compatibility portal rendering mode. Portal-in-portal won't be rendered". (This is the AMD/depth-format safety valve.)
  2. `initStencilForLayer(layer)`: layer 0 â†’ clear deferred[0] stencil; layer>0 â†’ `glBlitFramebuffer` STENCIL from deferred[layer-1]â†’deferred[layer] (propagate the parent mask down).
  3. Bind deferred[layer]; `glEnable(STENCIL_TEST); glStencilFunc(GL_EQUAL, layer, 0xFF); glStencilOp(KEEP,KEEP,KEEP)`; `drawScreenFrameBuffer(main, blend=false, modifyAlpha=true)` â€” copies the finished main-FBO world COLOR into deferred[layer] where stencil==layer (seeds the layer image). Disable stencil; unbind; bind main.
- `renderPortals(modelView)` â†’ each portal `doRenderPortal`.
- If `portalLayer==0` â†’ `finish()`.
- Bind main(true).

`doRenderPortal(portal, modelView)`:
- `nextFramePortalRenderingNeeded = true`. If `!portalRenderingNeeded` return (frame-0 flag only).
- `tryRenderViewAreaInDeferredBufferAndIncreaseStencil`: `initStencilForLayer(layer)`; bind deferred[layer]; `glStencilFunc(EQUAL, layer, 0xFF); glStencilOp(KEEP,KEEP,GL_INCR)`; enable depth test; `renderAndDecideVisibility(portal, renderPortalArea(all four booleans true))` â€” draws the portal quad into deferred[layer] and **increments stencil to layer+1 inside the portal silhouette**; returns false (invisible) â†’ abort.
- `pushPortalLayer(portal)`; **bind MAIN**; `renderPortalContent(portal)` (nested world into main; recursion re-enters doMainRenderings at layer+1 which seeds deferred[layer+1] from the main image masked by the propagated stencil). `innerLayer` = pushed layer; `popPortalLayer` â†’ `outerLayer`.
- If `innerLayer > maxPortalLayer` return.
- Bind deferred[outerLayer]; `glStencilFunc(EQUAL, innerLayer, 0xFF); glStencilOp(KEEP,KEEP,KEEP)`; `drawScreenFrameBuffer(deferred[innerLayer], blend=true, modifyAlpha=false)` â€” **composite the inner layer's image into the outer deferred FB where stencil==innerLayer** (premultiplied blend). Disable stencil; unbind.

`finish()` (only at layer 0, only if portals rendered and needed): bind main; `deferredFbs[0].fb.blitToScreen(main.viewWidth, main.viewHeight)` (vanilla RenderTarget#blitToScreen) â†’ the fully composited image onto the screen.

Net: a per-layer deferred-FB stack; each portal's dest world is rendered through the FULL Iris pipeline into the main FBO, snapshotted into a stencil-masked deferred layer, and composited up the stack. Portal-in-portal supported up to `maxPortalLayer`. Stencil lives entirely in IP's own FBs (main untouched for Iris).

â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
### 1C. ExperimentalIrisPortalRenderer  (FORWARD, main-FBO stencil, INTEGRATED into Iris translucent pass)
File: `compat/iris_compatibility/ExperimentalIrisPortalRenderer.java`
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
Opt-in (`experimentalIrisPortalRenderer=true`). Premise (class comment): "Iris now uses the vanilla framebuffer's depth texture and supports stencil, so forward-shading portal rendering is possible." It renders portals **inside** the Iris frame, at the moment translucents begin, directly into the main FBO with stencil, rather than post-compositing.

`prepareRendering()`: if main target stencil disabled â†’ enable it (`setIsStencilEnabled(main,true)` â†’ reload). Bind main; `glClearStencil(0); glClear(STENCIL_BUFFER_BIT)`; enable depth test + `GL_STENCIL_TEST`. **This renderer PUTS STENCIL ON THE MAIN TARGET** (unlike 1B) â€” the core Iris-version dependency.

`replaceFrameBufferClearing()`: returns `PortalRendering.isRendering()` â€” skip clearing during nested portal world render (sky overwrites the portal region anyway).

`onBeginIrisTranslucentRendering(modelView)` (driven by MixinLevelRenderer_BeforeIris at the `translucent` constant, before Iris) â€” the main entry:
- `client.renderBuffers().bufferSource().endBatch()` (flush opaque/entity batches; comment: TODO separate buffer source for Iris â€” Iris buffers are deferred so a layer change doesn't force a draw).
- `doPortalRendering(modelView)` â†’ `RenderSystem.enableDepthTest(); depthMask(true); Profiler popPush("render_portal_total"); renderPortals(modelView)`.
- `((IEIrisNewWorldRenderingPipeline) Iris.getPipelineManager().getPipeline().get()).ip_setIsRenderingWorld(true)` â€” restore Iris's "rendering world" flag (it was set false during the nested world render to stop Iris force-disabling depth mask; see 1C invokeWorldRendering).

`renderPortals(modelView)`:
- `getPortalsToRender`; for each `doRenderPortal` collecting those that really rendered.
- `setStencilStateForWorldRendering()`; then for each really-rendered non-fuse portal `renderPortalViewAreaToStencil` AGAIN â€” **re-stamp the portal area into stencil to bound Iris's deferred composite pass to the portal region**; `setStencilStateForWorldRendering()`.

`doRenderPortal(portal, modelView)` (returns true if drawn):
- `RendererUsingStencil.shouldSkipRenderingInsideFuseViewPortal(portal)` guard (reverse-fuse-portal detection).
- `outer = getPortalLayer()`.
- `renderAndDecideVisibility(portal, renderPortalViewAreaToStencil(...))`; if none passed â†’ `setStencilStateForWorldRendering(); return false`.
- `pushPortalLayer`; if non-fuse `clearDepthOfThePortalViewArea` (depthâ†’1 in the stencil region via `renderScreenTriangle` with `glDepthFunc(ALWAYS)`, `glDepthRange(1,1)`, colorMask off); `setStencilStateForWorldRendering`; `renderPortalContent(portal)`; if non-fuse `restoreDepthOfPortalViewArea` (redraw portal area `doModifyDepth=true, doClip=true` with `glDepthFunc(ALWAYS)` to write the portal plane depth back); `clampStencilValue(outer)`; `popPortalLayer`.

`renderPortalViewAreaToStencil`: `glStencilFunc(EQUAL, outer, 0xFF); glStencilOp(KEEP,KEEP,GL_INCR); glStencilMask(0xFF)`; `FrontClipping.updateInnerClipping(modelView)`; `renderPortalArea(doFaceCulling=true, doModifyColor=false, doModifyDepth=true, doClip=true)`.

`clampStencilValue(max)` (also static in RendererUsingStencil): `glStencilFunc(GL_LESS, max, 0xFF); glStencilOp(KEEP, GL_REPLACE, GL_REPLACE)` with depth/color masks off + a screen triangle â€” collapses any stencil > max back down to max (bounds recursion overflow).

`setStencilStateForWorldRendering()`: `glStencilFunc(GL_EQUAL, getPortalLayer(), 0xFF); glStencilOp(KEEP,KEEP,KEEP)` â€” restricts subsequent world draws to the current layer's region.

`onAfterIrisDeferredCompositeRendering()` (from MixinIrisRenderingPipeline after `CompositeRenderer.renderAll()`): `clampStencilValue(getPortalLayer()); setStencilStateForWorldRendering()` â€” re-bound stencil after Iris's composite pass may have disturbed it.

`invokeWorldRendering(worldRenderInfo)` (override): 
- `SystemTimeUniforms.COUNTER.beginFrame()` (before) â€” nudge Iris frame counter;
- `super.invokeWorldRendering` (â†’ renderWorldNew â†’ nested renderLevel);
- `SystemTimeUniforms.COUNTER.beginFrame()` (after) â€” force Iris to update uniforms;
- if pipeline `instanceof IrisRenderingPipeline` set `isBeforeTranslucent = true` (public field; "important to hand rendering");
- `((IEIrisNewWorldRenderingPipeline) pipeline).ip_setIsRenderingWorld(false)` â€” stop Iris force-disabling depth mask outside the nested render.
- (A whole `ShadowMapSwapper` block that would snapshot/restore Iris shadow depth textures around the nested render is COMMENTED OUT â€” see 1D.)

`finishRendering()` â†’ `myFinishRendering()`: `glStencilFunc(GL_ALWAYS, 2333, 0xFF); glStencilOp(KEEP,KEEP,KEEP); glDisable(GL_STENCIL_TEST); enableDepthTest`.

This renderer mirrors the non-Iris `RendererUsingStencil` (render/renderer/RendererUsingStencil.java) method-for-method (same `renderPortalViewAreaToStencil`, `clearDepthOfThePortalViewArea`, `clampStencilValue`, `setStencilStateForWorldRendering`) but is spliced into the Iris deferred pipeline via the translucent-begin hook and the composite-after hook, plus the ClearPass/finalizeLevelRendering cancellations.

â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
### 1D. IPIrisHelper / ShadowMapSwapper / IE interfaces (helpers)
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
`IPIrisHelper.java`:
- `copyDepthStencil(from,to,copyDepth,copyStencil)` â€” `glBlitFramebuffer` with DEPTH and/or STENCIL mask (throws if neither).
- `newCopyDepthStencil(from,to)` â€” `GL43C.glCopyImageSubData` of the DEPTH texture (`from.getDepthTextureId()`â†’`to.getDepthTextureId()`, full wÃ—hÃ—1). Used by IrisCompatibility.
- `copyColor(from,to)` â€” same for the COLOR texture (`getColorTextureId`). 
- `isCopyImageSubDataSupported()` = `GL.getCapabilities().glCopyImageSubData != 0` (defined but unused here).
Depends on the `RenderTarget` ducks `getDepthTextureId()` / `getColorTextureId()`.

`IEIrisNewWorldRenderingPipeline.java`: interface `void ip_setIsRenderingWorld(boolean)` (+ commented `ip_getShadowRenderTargets()`), implemented by `MixinIrisRenderingPipeline` onto `IrisRenderingPipeline` (shadows its private `boolean isRenderingWorld`).

`IEIrisShadowRenderTargets.java`: EMPTY interface (its only member `getShadowMapSwapper()` is commented out).

`ShadowMapSwapper.java`: ENTIRELY commented out ("only compilable with the next version of Iris"). Would have double-buffered Iris shadow depth textures (`DepthTexture`, `DepthBufferFormat`, `ShadowRenderTargets.getDepthTexture()`) across nested portal renders via `glCopyImageSubData`, with an acquire/restitute pool (limit 3). **This is a dormant feature stub** â€” shadows from a portal-dest world currently leak into/over the main shadow map; the swapper was the intended fix, deferred to a future Iris. For C2 this is a KNOWN GAP, not something to port.

Correspondingly `MixinIrisShadowRenderTargets` is an empty implementor (all injection commented), and the `ip_getShadowRenderTargets` path in ExperimentalIrisPortalRenderer.invokeWorldRendering is commented out.

================================================================
## PART 2 â€” ShaderCodeTransformation + the `iportal_ClippingEquation` CONTRACT
================================================================
File: `render/ShaderCodeTransformation.java`. Resource: `assets/immersive_portals/shaders/shader_transformation.yaml`.

### 2.1 Machinery
- `init()`: gated on `IPGlobal.enableClippingMechanism`. Loads the YAML via snakeyaml (`me.shedaniel.cloth.clothconfig.shadowed...Yaml` â€” cloth-config's shadowed snakeyaml) into `ConfigsObj{ List<Config> }`. Each `Config{ comment; ShaderType type (vs|fs); Set<String> affectedShaders; List<TransformationEntry{comment,pattern,replacement}>; boolean debugOutput }`.
- `transform(CompiledShader.Type type, String shaderId, String inputCode)`: if `configs==null` (mechanism disabled) return input unchanged. `getConfig` finds first Config whose `type` matches and whose `affectedShaders.contains(shaderId)`. For each entry: `result = result.replaceAll(entry.pattern, replacement)` (Java regex). Returns transformed code.
- `matches(ShaderType, CompiledShader.Type)`: maps `FRAGMENTâ†”fs`, `VERTEXâ†”vs`.
- `shouldAddUniform(shaderName)`: true if ANY config's affectedShaders contains the name (used to decide uniform registration for vanilla shaders).

### 2.2 The 4 transform configs (all `type: vs`)
1. **Vanilla terrain** (`rendertype_solid, rendertype_cutout, rendertype_cutout_mipped, rendertype_translucent`): inserts before `void main(){`:
   `uniform vec4 iportal_ClippingEquation;` and inside main:
   `gl_ClipDistance[0] = dot(Position.xyz + ChunkOffset, iportal_ClippingEquation.xyz) + iportal_ClippingEquation.w;`
   â†’ uses `Position + ChunkOffset` = camera-relative WORLD space (BEFORE modelview rotation).
2. **Entities / particle / portal_area** (`rendertype_entity_*`, `rendertype_beacon_beam`, `portal_area`, `particle`, â€¦): same but `dot(Position.xyz, iportal_ClippingEquation.xyz) + .w` (no ChunkOffset; Position already camera-relative).
3. **Iris+Sodium terrain** (`iris_gbuffers_terrain, iris_gbuffers_water`): two entries â€” (a) insert `uniform vec4 iportal_ClippingEquation;` before main; (b) match the LAST `}` via `\}(?![\S\s]*\})` and prepend `gl_ClipDistance[0] = dot((iris_ModelViewMatrix * getVertexPosition()).xyz, iportal_ClippingEquation.xyz) + iportal_ClippingEquation.w;` â†’ uses VIEW space (post-modelview).
4. **Sodium (no Iris)** (`sodium:blocks/block_layer_opaque.vsh`): same two-entry shape but `dot((u_ModelViewMatrix * vec4(position,1.0)).xyz, ...)` â†’ VIEW space.

### 2.3 The contract (invariant across all four)
`iportal_ClippingEquation` is a `vec4` plane `(nx,ny,nz,d)`. The shader writes `gl_ClipDistance[0] = dot(P, n) + d`; positive = KEPT side (normal points to the non-clipped/visible side). Default (clipping off) the CPU sets `(0,0,0,1)` â†’ distance = +1 everywhere â†’ nothing clipped.
- **Coordinate space split**: vanilla/entity shaders evaluate in camera-relative world space, so the CPU supplies `FrontClipping.getActiveClipPlaneEquationBeforeModelView()`. Sodium/Iris-Sodium shaders evaluate in view space, so the CPU supplies `FrontClipping.getActiveClipPlaneEquationAfterModelView()` (the before-eqn transformed by `inverse-transpose(modelView)`; see FrontClipping.transformClipEquation).

### 2.4 Who injects the code, who sets the uniform
- **Iris shader code** â† `compat/mixin/iris/MixinIrisTransformPatcher.java` (`@Pseudo`, `TransformPatcher.transformInternal`, `@At RETURN`, cancellable): takes the returned `Map<PatchShaderType,String>`, pulls `PatchShaderType.VERTEX`, runs `ShaderCodeTransformation.transform(VERTEX, "iris_"+name, code)`, puts it back. (name â†’ e.g. `gbuffers_terrain` â†’ keyed as `iris_gbuffers_terrain`, matching config 3.) A large commented-out alternative shows the earlier Iris API (`patchShaders(ChunkVertexType)`, `terrainSolidVertex`/`terrainCutoutVertex`/`translucentVertex` Optionals) â€” REMOVED path.
- **Iris+Sodium uniform bind/set** â† `compat/mixin/iris/MixinIrisSodiumShader.java` (`SodiumShader.class`, remap=false):
  - `@Inject <init> RETURN` (long ctor sig: `IrisRenderingPipeline, SodiumPrograms.Pass, ShaderBindingContext, int handle, BlendModeOverride, List bufferBlendOverrides, CustomUniforms, Supplier flipState, float alphaTest, boolean containsTessellation`): `uIPClippingEquation = context.bindUniformOptional("iportal_ClippingEquation", GlUniformFloat4v::new)` (Sodium's uniform type), logs found/not-found.
  - `@Inject setupState RETURN`: if the uniform is present, set it from `FrontClipping.getActiveClipPlaneEquationAfterModelView()` (or `(0,0,0,1)` when clipping off).
  - Commented block below shows the pre-1.8 form (raw `GL20C.glGetUniformLocation`, `GL21.glUniform4f`).
- **Plain-Sodium shader code** â† `compat/mixin/sodium/MixinSodiumShaderLoader.java` (`ShaderLoader.loadShader`, `@WrapOperation` on `getShaderSource(ResourceLocation)`, `@Local ShaderType`): runs the transform keyed by `name.toString()` (matches config 4's `sodium:blocks/block_layer_opaque.vsh`).
- **Plain-Sodium uniform bind/set** â† `compat/mixin/sodium/MixinSodiumDefaultShaderInterface.java` (`@Pseudo`, `DefaultShaderInterface`): same bind-in-`<init>` / set-in-`setupState` pattern, `getActiveClipPlaneEquationAfterModelView`.
- **Vanilla-shader uniform** (not an Iris path but part of the same contract): `FrontClipping.updateClippingEquationUniformForCurrentShader` uses the `IEShader.ip_getClippingEquationUniformLocation()` duck + raw `GL20.glUniform4f` with the BEFORE-modelview equation.

### 2.5 FrontClipping (the equation source) â€” `render/FrontClipping.java`
- `isClippingEnabled`, `activeClipPlaneEquationBeforeModelView`, `activeClipPlaneAfterModelView`.
- `setupInnerClipping(Plane clipping, Matrix4f modelView, double adjustment)`: gated on `IPCGlobal.useFrontClipping`; computes before-eqn via `getClipEquationInner(pos, normal, adj)` (plane through `clippingPoint`, normal to kept side, camera-relative), and after-eqn via `transformClipEquation` (= `inverse-transpose(modelView)Â·eqn`), then `glEnable(GL_CLIP_PLANE0)`.
- `updateInnerClipping(modelView)`: when rendering, setup from `PortalRendering.getActiveClippingPlane()`; else disable.
- Driven each layer by `MixinLevelRenderer.onBeforeRenderingLayer` (setupInnerClipping with `-ADJUSTMENT` nudge) / `onAfterRenderingLayer` (disable), and `beforeRenderingWeather`/`afterRenderingWeather`.
- `GL_CLIP_PLANE0` fixed-function enable is the vanilla (non-shader) clip; the shader `gl_ClipDistance[0]` path is the shader-pipeline equivalent â€” both driven off the same equation.

================================================================
## PART 3 â€” PIPELINE SWAP STATE MACHINE PER PORTAL-WORLD
================================================================

### 3.1 The reflected `LevelRenderer.pipeline` field
`IrisInterface.OnIrisPresent` (compat/iris_compatibility/IrisInterface.java) reflects `LevelRenderer.class.getDeclaredField("pipeline")` (a field ADDED by Iris via its own mixin onto LevelRenderer, typed `WorldRenderingPipeline`), `setAccessible(true)`. `getPipeline(lr)`/`setPipeline(lr,obj)` read/write it (wrapped in `Helper.noError`). Base `Invoker` returns null / no-ops. Other members: `isIrisPresent`, `isShaders()=Iris.getCurrentPack().isPresent()`, `isRenderingShadowMap()=ShadowRenderer.ACTIVE`, `reloadPipelines()=Iris.getPipelineManager().destroyPipeline()`, `getShaderpackName()=Iris.getCurrentPackName()`.

### 3.2 renderWorldNew nested-world swap (`render/MyGameRenderer.java` switchAndRenderTheWorld)
Each portal-content world render is a full saveâ†’swapâ†’renderâ†’restore of MC client state. Iris-relevant steps, in order:
- Save `Object irisPipeline = IrisInterface.invoker.getPipeline(worldRenderer)` (the DEST dimension's LevelRenderer pipeline field) â€” line 168.
- Swap client.level, lightmap, particle world, camera, render buffers, sodium context, etc.
- **`IrisInterface.invoker.setPipeline(worldRenderer, null)`** (line 221) â€” NULL the dest LevelRenderer's pipeline field for the duration of the nested render (Iris resolves the active pipeline via its global `PipelineManager`, keyed by the CURRENT dimension, not this field; nulling avoids a stale cross-dimension pipeline reference on the LevelRenderer object).
- If dimension not yet rendered this frame: `helper.lightmapTexture.updateLightTexture(0)`.
- `invokeWrapper.accept(() -> gameRenderer.renderLevel(timer))` â€” the nested render (profiler "render_portal_content"). Sodium context re-switched around it.
- Restore everything, then **`IrisInterface.invoker.setPipeline(worldRenderer, irisPipeline)`** (line 273) â€” put the saved pipeline back.
Note: the base (no-Iris) `Invoker` makes all of this inert (getâ†’null, setâ†’no-op), so the same code path is Iris-agnostic.

### 3.3 The Experimental per-frame integration (see 1C)
State toggled through the frame:
- `IrisRenderingPipeline.isRenderingWorld` (private, via `ip_setIsRenderingWorld`): set FALSE right after a nested world render returns (invokeWorldRendering) to keep Iris from force-disabling the depth mask during IP's stencil/composite work; set TRUE again at `onBeginIrisTranslucentRendering` end.
- `IrisRenderingPipeline.isBeforeTranslucent` (public): forced TRUE after nested render â€” needed for correct HAND rendering under Iris.
- `SystemTimeUniforms.COUNTER.beginFrame()` bracketing the nested render â€” forces Iris uniform recompute for the switched view.
- `ClearPass.execute` and `IrisRenderingPipeline.finalizeLevelRendering` CANCELLED while `isRendering()` (MixinIrisClearPass, MixinIrisRenderingPipeline) â€” stops Iris from clearing GBuffer targets / finalizing mid-recursion.
- `onAfterIrisDeferredCompositeRendering` re-clamps stencil after `CompositeRenderer.renderAll()`.

### 3.4 Sodium co-driver notes (context for the Iris+Sodium bridge)
- `MixinLevelRenderer.onSetupTerrainBegin/End` (ip_allowOverrideTerrainSetup = `!SodiumInterface.isSodiumPresent() && !IrisInterface.isRenderingShadowMap()`) â€” IP's own visible-section discovery only runs when NEITHER Sodium is present NOR a shadow pass is active; with Sodium, Sodium owns terrain setup.
- `MixinSodiumRenderRegion.getRenderList()` (@Overwrite) â€” per-portal-layer `ChunkRenderList` array (indexed by `getPortalLayer()-1`) so the frame-counter increment during recursion doesn't reset the outer world's render list.
- `MixinSodiumViewport.redirectTestAab` â€” IP's frustum culler bolted onto Sodium's `Frustum.testAab`.
These matter to C2 because Iris-with-Sodium routes terrain through Sodium's chunk shaders (config 3/4 + MixinIrisSodiumShader), not the Iris gbuffer programs directly.

================================================================
## PART 4 â€” EXACT IRIS 1.8 / SODIUM 0.6 INTERNALS ASSUMED (the port-fidelity checklist)
================================================================
Every one of these is a hard coupling C2 must re-locate against Iris 1.11.2 / Sodium 0.9.1.

Iris (`net.irisshaders.iris.*`):
- `Iris`: `getPipelineManager().getPipeline().get()` (Optional<WorldRenderingPipeline>); `getPipelineManager().destroyPipeline()`; `getCurrentPack()` (Optional); `getCurrentPackName()`.
- `pipeline.WorldRenderingPipeline` (interface type of the reflected LevelRenderer `pipeline` field).
- `pipeline.IrisRenderingPipeline`: **private field `boolean isRenderingWorld`** (shadowed); **public field `boolean isBeforeTranslucent`**; method `finalizeLevelRendering()`; method `beginTranslucents()` which internally calls `CompositeRenderer.renderAll()`.
- `pipeline.CompositeRenderer#renderAll()` (injection anchor).
- `pipeline.FinalPassRenderer#renderFinalPass()`.
- `targets.ClearPass#execute(org.joml.Vector4f)`.
- `shadows.ShadowRenderer.ACTIVE` (static boolean); `shadows.ShadowRenderTargets` (+ commented getDepthTexture/getDepthTextureNoTranslucents, `<init>(int, InternalTextureFormat[])`, `destroy()`).
- `uniforms.SystemTimeUniforms.COUNTER.beginFrame()`.
- `pipeline.transform.TransformPatcher#transformInternal(String name, Map<PatchShaderType,String>, Parameters)` (@Pseudo); `pipeline.transform.PatchShaderType.VERTEX`; `pipeline.transform.parameter.Parameters`.
- `pipeline.programs.SodiumShader` â€” ctor `(IrisRenderingPipeline, SodiumPrograms.Pass, ShaderBindingContext, int, BlendModeOverride, List, CustomUniforms, Supplier, float, boolean)`; `setupState()`. `pipeline.programs.SodiumPrograms.Pass`. `uniforms.custom.CustomUniforms`. `gl.blending.BlendModeOverride`.
- The Iris-added `LevelRenderer.pipeline` field name literally `"pipeline"` (reflection).
- The `translucent` STRING CONSTANT inside `LevelRenderer.renderLevel` (MixinLevelRenderer_BeforeIris anchor) â€” must still exist and be the point Iris keys its own translucent injection to.

Sodium (`net.caffeinemc.mods.sodium.client.*`):
- `gl.shader.uniform.GlUniformFloat4v` (+ `GlUniformFloat3v`); `render.chunk.shader.ShaderBindingContext#bindUniformOptional(String, Factory)`; `render.chunk.shader.DefaultShaderInterface` (`<init>(ShaderBindingContext, ChunkShaderOptions)`, `setupState()`); `render.chunk.shader.ChunkShaderOptions`.
- `gl.shader.ShaderLoader#loadShader` + `getShaderSource(ResourceLocation)`; `gl.shader.ShaderType.VERTEX`.
- Sodium shader resource id `sodium:blocks/block_layer_opaque.vsh` with GLSL symbols `u_ModelViewMatrix`, `vec3 position`.
- Iris gbuffer GLSL symbols `iris_ModelViewMatrix`, `getVertexPosition()`.

MC / Blaze3D (`com.mojang.blaze3d.*`, `net.minecraft.*`):
- `shaders.CompiledShader.Type.{VERTEX,FRAGMENT}` (transform keying).
- `RenderTarget` ducks: `frameBufferId`, `viewWidth/viewHeight/width/height`, `bindWrite(bool)`, `unbindWrite()`, `getColorTextureId()`, `getDepthTextureId()`, `blitToScreen(int,int)`, `checkStatus()`, `resize()`, `setClearColor/clear`.
- Core shaders infra: `CoreShaders.{BLIT_SCREEN, POSITION_COLOR}`, `ShaderProgram`, `CompiledShaderProgram` (`bindSampler`, `getUniform`, `MODEL_VIEW_MATRIX`, `PROJECTION_MATRIX`, `apply/clear`), `CoreShadersAccessor.register(...)`, `DefaultVertexFormat.{BLIT_SCREEN, POSITION_COLOR}`; IP-registered `blit_screen_noblend`, `portal_area`, `portal_draw_fb_in_area`.
- `LevelRenderer.renderLevel` obfuscated lambda names `method_62214` (addMainPass), `method_62216` (addWeatherPass), `method_62218` (a renderLevel lambda) â€” framegraph-era; the injection anchors `Sheets.translucentItemSheet()`, `DimensionSpecialEffects.constantAmbientLight()`, `MultiBufferSource$BufferSource.endLastBatch()`, `LevelRenderer.renderSectionLayer(...)`.
- GL: `GL43C.glCopyImageSubData`, `GL30.glBlitFramebuffer/glBindFramebuffer`, `GL11` stencil/depth/clip-plane fixed-function (`GL_CLIP_PLANE0`, `gl_ClipDistance[0]`).


### Risk notes (irisRenderers)
- GLOBAL / MC 26.2 renderer rewrite (UNVERIFIED â€” 0.9.1 jars not yet javapped): the entire driver layer keys off 1.21.3 framegraph internals. MixinLevelRenderer anchors `method_62214`/`method_62216`/`method_62218` and INVOKE targets `Sheets.translucentItemSheet()`, `DimensionSpecialEffects.constantAmbientLight()`, `MultiBufferSource$BufferSource.endLastBatch()`, `LevelRenderer.renderSectionLayer(RenderType,DDDLMatrix4f;Matrix4f;)` will almost certainly have moved/renamed under 26.2's rewritten LevelRenderer. Every renderer's entry timing depends on these â€” re-derive from a 26.2 javap before trusting any @At.
- MixinLevelRenderer_BeforeIris (UNVERIFIED): the `@At(CONSTANT, stringValue="translucent")` anchor and `priority=900` ordering-before-Iris assumption both depend on (a) MC still emitting a `"translucent"` profiler/string constant in renderLevel and (b) Iris 1.11.2 still injecting its translucent-begin at that same constant. If Iris moved to a different anchor in 1.11.2, ExperimentalIrisPortalRenderer's whole entry point breaks and the before/after ordering is undefined.
- ExperimentalIrisPortalRenderer + MixinIrisRenderingPipeline (UNVERIFIED, HIGH RISK): couples to IrisRenderingPipeline private `isRenderingWorld`, public `isBeforeTranslucent`, methods `finalizeLevelRendering()`/`beginTranslucents()`, and `CompositeRenderer.renderAll()`. Iris 1.9â†’1.11 substantially refactored the pipeline/composite classes; these member names are the single most likely thing to have been renamed or removed. Note this renderer is DEBUG-OPT-IN (experimentalIrisPortalRenderer=false by default) â€” C2 can defer it and ship the deferred (IrisPortalRenderer) + compat paths first.
- MixinIrisSodiumShader (UNVERIFIED, HIGH RISK): the SodiumShader ctor descriptor `(IrisRenderingPipeline, SodiumPrograms.Pass, ShaderBindingContext, int, BlendModeOverride, List, CustomUniforms, Supplier, float, boolean)` is Iris-1.8-exact. Iris 1.11.2's Irisâ†”Sodium 0.9.1 bridge (SodiumShader/SodiumPrograms) was rewritten for Sodium's new shader/uniform API; the ctor arity, `bindUniformOptional`, and `GlUniformFloat4v` type are all likely changed. This is the Iris+shaders+Sodium terrain-clipping seam.
- MixinIrisTransformPatcher (UNVERIFIED): `@Pseudo` targeting `TransformPatcher.transformInternal(String, Map<PatchShaderType,String>, Parameters)` at RETURN. Iris's transform pipeline (PatchShaderType, Parameters, transformInternal signature) may have changed in 1.11.2. The commented-out `patchShaders(ChunkVertexType)` block shows this API already churned once (pre-1.8). Verify the method name/signature and that `"iris_"+name` still maps to `iris_gbuffers_terrain`/`iris_gbuffers_water` shader ids.
- ShaderCodeTransformation GLSL contract (UNVERIFIED): the regex replacements assume specific GLSL symbols still exist in the shader sources â€” `Position`+`ChunkOffset` (vanilla terrain), `iris_ModelViewMatrix`+`getVertexPosition()` (Iris gbuffers), `u_ModelViewMatrix`+`vec3 position` + resource id `sodium:blocks/block_layer_opaque.vsh` (Sodium 0.6). Sodium 0.9.1 renamed/relocated its chunk shaders and uniforms; the `sodium:blocks/block_layer_opaque.vsh` id and `u_ModelViewMatrix` name are very likely stale. Also `gl_ClipDistance[0]` requires the transformed programs to have a matching `gl_ClipDistance` output enabled (GL clip-distance 0). Note ShaderCodeTransformation loads snakeyaml from cloth-config's shadowed package (`me.shedaniel.cloth.clothconfig.shadowed...Yaml`) â€” that shadow path is cloth-version-specific and ties into the S19-E cloth-config 26.2.155 swap.
- MixinSodiumShaderLoader / MixinSodiumDefaultShaderInterface (UNVERIFIED): ShaderLoader.loadShader/getShaderSource(ResourceLocation) with @Local ShaderType, and DefaultShaderInterface(ShaderBindingContext, ChunkShaderOptions)+setupState â€” all Sodium 0.6 internals. Sodium 0.9.1 reworked the shader loading + ChunkShaderInterface; expect renamed classes/methods. These are the no-Iris Sodium clipping path (still needed even when the target is plain Sodium without shaders).
- IrisPortalRenderer depth handling (UNVERIFIED but mechanism-portable): relies on `glBlitFramebuffer(GL_DEPTH_BUFFER_BIT)` mainâ†’deferred FBs and the Nvidia-vs-AMD `glCopyImageSubData` depth-format conversion branch (useSeparatedStencilFormat). This is GL-level and version-robust, BUT depends on RenderTarget ducks `getDepthTextureId()`/`getColorTextureId()`/`frameBufferId` and on IP's own stencil-enable duck (IPPortingLibCompat/IEFrameBuffer `ip_setIsStencilBufferEnabledAndReload`) surviving 26.2's RenderTarget. The auto-fallback to compatibility renderMode on GL error is a good safety net to preserve. porting_lib is likely absent on 26.2 (isPortingLibPresent=false path â†’ the IEFrameBuffer duck is the live one).
- IrisCompatibilityPortalRenderer (LOWER RISK): pure GL (glCopyImageSubData depth+color) + IP core shaders (portal_draw_fb_in_area, blit_screen_noblend) + depth clamp; no Iris/Sodium internal types at all. Only depends on RenderTarget texture-id ducks and CoreShadersAccessor.register surviving 26.2. This is the safest first Iris path to stand up for C2 and validates the swap machinery end-to-end.
- ShadowMapSwapper / IEIrisShadowRenderTargets / MixinIrisShadowRenderTargets are DORMANT (fully commented out in 1.21.3 â€” 'only compilable with next Iris'). Portal-dest shadow maps are a KNOWN UNSOLVED gap, not a regression to port. For C2: do not attempt to revive; if shadow bleed appears in nested portal worlds under Iris 1.11.2, it is pre-existing IP behavior. ShadowRenderer.ACTIVE (used by isRenderingShadowMap and the terrain-setup gate) is the only live shadow coupling and must still resolve.
- Pipeline swap via reflected LevelRenderer field `"pipeline"` (UNVERIFIED): IrisInterface.OnIrisPresent hard-reflects `LevelRenderer.getDeclaredField("pipeline")` â€” an Iris-mixin-ADDED field. If Iris 1.11.2 renames that injected field or changes its type from WorldRenderingPipeline, getPipeline/setPipeline silently no-op via Helper.noError (swallowed reflection error) and nested-world pipeline save/restore in MyGameRenderer.renderWorldNew stops working â€” a silent failure mode to watch. Consider replacing reflection with a duck interface in C2.
- switchRenderer force-calls Iris.getPipelineManager().destroyPipeline() on every renderer swap when a pack is active (UNVERIFIED method name). If destroyPipeline was renamed in 1.11.2, renderer switching (incl. the debug experimental toggle and the normalâ†”compatibility auto-fallback) will throw or fail to rebuild the pipeline. Also SystemTimeUniforms.COUNTER.beginFrame() (Experimental) â€” verify SystemTimeUniforms still exists and COUNTER is still the frame counter in 1.11.2.


---


# TRACER D â€” IP-core call-site + dependency map for the Sodium/Iris compat facades

Two facades, both in `qouteall.imm_ptl.core.compat`:
- `sodium_compatibility/SodiumInterface.java` â€” `static Invoker invoker` (default no-op `Invoker`, swapped to `OnSodiumPresent` at `IPModEntryClient.onInitializeClient` L76 when Fabric reports `sodium` loaded) + `static @Nullable FrustumCuller frustumCuller`.
- `iris_compatibility/IrisInterface.java` â€” `static Invoker invoker` (default no-op `Invoker`, swapped to `OnIrisPresent` at `IPModEntryClient` L94 when `iris` loaded).

Both use the "polymorphic no-op invoker" pattern: IP-core always calls `invoker.foo()` unconditionally; the default subclass returns false/null/no-op, and the `On*Present` subclass carries the real Sodium/Iris-linked bytecode (so the Sodium/Iris classes only load when the corresponding `On*Present` class is instantiated). The compat MIXINS (`compat/mixin/sodium/*`, `compat/mixin/iris/*`) are separately gated by `IPCompatMixinPlugin.shouldApplyMixin` on class-name substring (`Sodium`â†’sodium loaded, `Iris`â†’iris loaded, `IrisSodium`â†’both) via `imm_ptl_compat.mixins.json` (package `qouteall.imm_ptl.core.compat.mixin`, defaultRequire=1).

=====================================================================
## A. SodiumInterface FACADE CONTRACT TABLE
=====================================================================

### A1. `boolean isSodiumPresent()`  (default false / OnSodiumPresent true)
Pure read-only capability flag. Six call sites, all gates:

- **IPModMainClient.java:43** (`showNvidiaVideoCardWarning`, inside a delayed one-shot client task; NOT per-frame). Shows the NVIDIA driver warning only if `!isSodiumPresent()`. Contract: honest presence report; cosmetic only.
- **FrustumCuller.java:108** (`getCanDetermineInvisibleFunc`, called from `FrustumCuller.update`, which runs per `Frustum.prepare` (vanilla) or per `setupTerrain` (sodium)). In the NON-portal-rendering branch, OUTER frustum culling is enabled **only if `isSodiumPresent()`**. The surrounding comment is load-bearing: "don't do outer frustum culling when sodium is not present â€” in vanilla it rebuilds the visible-section list lazily â€¦ outer frustum culling causes artifacts; the inner frustum culling is controlled by ImmPtl and is correct." Contract: `true` MUST mean "an eager, every-frame terrain/visible-section rebuild is in effect so outer culling is safe."
- **MixinLevelRenderer.java:266** (`ip_allowOverrideTerrainSetup` = `!isSodiumPresent() && !IrisInterface.isRenderingShadowMap()`). Gates whether IP takes over vanilla terrain discovery (`VisibleSectionDiscovery.discoverVisibleSections`) inside `setupRender` (L249 head-inject cancels vanilla setup while portal-rendering; L280 tail re-runs it `vanillaTerrainSetupOverride` times). Contract: `false` (sodium present) MUST mean "Sodium owns terrain/visible-section discovery; IP must NOT run its vanilla `VisibleSectionDiscovery` path."
- **MixinLevelRenderer.java:502** (`onIsChunkCompiled`/`isSectionCompiled` HEAD, only while `PortalRendering.isRendering()`). IP overrides `isSectionCompiled` via `ImmPtlViewArea` **only if `!isSodiumPresent()`**. Contract: `false` MUST mean "vanilla section-compile bookkeeping is not the source of truth (Sodium's is)."
- **MixinSectionRenderDispatcher.java:39** (`redirectSectionBufferBuilderPool`, only during `SectionRenderDispatcher.<init>` while `ClientWorldLoader.getIsCreatingClientWorld()`). Allocates a private `SectionBufferBuilderPool` per dimension **only if `!isSodiumPresent()`** (comment: "not enabled in Sodium as Sodium does not use this"). Contract: `false` MUST mean "the vanilla SectionRenderDispatcher pool machinery is unused."
- (Facade-internal) SodiumInterface itself is the read target of all of the above.

### A2. `Object createNewContext(int renderDistance)`  (default null / OnSodiumPresent `new SodiumRenderingContext(renderDistance)`)
- **MyGameRenderer.java:213** (`switchAndRenderTheWorld`, once per portal-content world render). `renderDistance` originates from `WorldRenderInfo.renderDistance` (via `renderWorldNew`â†’`switchAndRenderTheWorld` arg). `SodiumRenderingContext` = `{ SortedRenderLists renderLists (seeded EMPTY via SortedRenderLists.empty()); int renderDistance }`. Contract: produce an opaque per-render container pre-seeded with an EMPTY visible-section render-list and the given render distance, usable as the swap partner in A3. Default null is fine because A3 default is a no-op.

### A3. `void switchContextWithCurrentWorldRenderer(Object context)`  (default no-op / OnSodiumPresent = symmetric swap)
THE central Sodium contract. **MyGameRenderer.java:214 and :237** â€” called TWICE with the SAME `newSodiumContext` object, straddling the `renderLevel` invocation (L229-235):
- Impl reads `Minecraft.getInstance().levelRenderer` (already repointed to the DEST `worldRenderer` at L171) â†’ `LevelRendererExtension.sodium$getWorldRenderer()` â†’ `swr.scheduleTerrainUpdate()` â†’ `IESodiumWorldRenderer.ip_getRenderSectionManager()` (accessor for private field `renderSectionManager`) â†’ `IESodiumRenderSectionManager.ip_swapContext(context)` â†’ `swr.scheduleTerrainUpdate()`.
- `ip_swapContext` (MixinSodiumRenderSectionManager L29-40) **swaps** the manager's `@Shadow @Final @Mutable int renderDistance` and `@Shadow SortedRenderLists renderLists` with the context's fields (`Validate` guards renderDistanceâ‰ 0, renderListsâ‰ null).
- Sequencing in the swap: L214 swaps the fresh EMPTY lists INTO the dest manager (stashing the dest's real primary render lists into the context); `renderLevel` at L229 POPULATES that empty list for the portal camera; L237 swaps AGAIN, restoring the dest's primary lists to the manager and dropping the portal lists into the (discarded) context.
Contract: MUST be a symmetric in-place swap of the current world-renderer's per-frame Sodium visible-section state (`renderLists` + `renderDistance`) with the passed context, bracketed by terrain-update scheduling so the manager re-derives visibility for the portal camera; calling twice restores the dimension's primary state. NOTE the handle asymmetry vs Iris: Sodium swap targets the IMPLICIT `Minecraft.getInstance().levelRenderer`; Iris pipeline ops (A/B below) target the EXPLICIT `worldRenderer` arg. Both resolve to the same dest object at runtime, but via different lookups â€” the C2 impl must preserve which handle each uses.

### A4. `void markSpriteActive(TextureAtlasSprite sprite)`  (default no-op / OnSodiumPresent `SpriteUtil.markSpriteActive(sprite)`)
- **OverlayRendering.java:151** (`renderBreakablePortalOverlay`, per-quad while rendering a breakable/nether-portal overlay block model; only when overlay present and not shaders). Called immediately before `buffer.putBulkData` for each `BakedQuad`. Purpose: tell Sodium's animated-texture tracker the sprite is in use so Sodium keeps animating it (Sodium only ticks sprites it has seen active). Contract: mark the sprite as active for Sodium's sprite-animation bookkeeping; no-op is acceptable degradation (overlay animation may freeze) when Sodium absent.

### A5. `void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ)`  (default no-op / OnSodiumPresent `ChunkTrackerHolder.get(world).onChunkStatusAdded(x,z,ChunkStatus.FLAG_HAS_BLOCK_DATA)`)
- **ImmPtlClientChunkMap.java:164** (`replaceWithPacketData`, main-thread only, per chunk packet). IP uses a CUSTOM client chunk map (not vanilla `ClientChunkCache`), so Sodium's own chunk-load hook never fires â€” IP must manually notify Sodium's `ChunkTrackerHolder` that block data arrived, else Sodium never schedules a mesh build for that chunk. Fires right after `level.onChunkLoaded` + `O_O.postClientChunkLoadEvent`. Contract: feed Sodium's per-level chunk tracker a "block-data present" status add for (x,z).

### A6. `void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ)`  (default no-op / OnSodiumPresent `ChunkTrackerHolder.get(world).onChunkStatusRemoved(x,z,FLAG_HAS_BLOCK_DATA)`)
- **ImmPtlClientChunkMap.java:81** (`drop`, main-thread only, per chunk unload). Mirror of A5; fires after `O_O.postClientChunkUnloadEvent` + `level.unload(chunk)`. Contract: feed Sodium's tracker a status-remove so it drops the mesh.

### A7. `static @Nullable FrustumCuller frustumCuller`  (Sodium-only portal-cull bridge)
NOT an invoker method â€” a static field on SodiumInterface, the bridge between IP's portal frustum math and Sodium's box-visibility test.
- **WRITER: MixinSodiumWorldRenderer.java:23-25** (`setupTerrain` HEAD, per Sodium terrain-setup pass). Constructs a FRESH `FrustumCuller` and `update(cameraPos)`s it every setupTerrain. Never explicitly cleared â€” overwritten each pass.
- **READER: MixinSodiumViewport.java:28-33** (`Viewport.isBoxVisible` â†’ `@Redirect` of `Frustum.testAab`, per box tested during Sodium culling). If Sodium's own frustum says visible AND `frustumCuller != null`, additionally apply `frustumCuller.canDetermineInvisibleWithCameraCoord(...)` and return visible only if IP's portal culler does NOT determine it invisible.
Lifetime: born at first `setupTerrain`, refreshed each `setupTerrain`, read during that pass's box tests; no teardown. Contract: the C2 impl needs an equivalent hook to (a) recompute the portal `FrustumCuller` against the camera at the start of Sodium's terrain-visibility pass, and (b) AND-in IP's `canDetermineInvisibleâ€¦` at Sodium's per-box frustum test. `FrustumCuller.getCanDetermineInvisibleFunc` itself reads facades: `IrisInterface.isRenderingShadowMap()` (â†’ no culling under shadow pass), `PortalRendering.isRendering()` (inner vs outer), and `SodiumInterface.isSodiumPresent()` (outer only if sodium).

### A8. Sodium-contract helpers NOT on the invoker (but the C2 Sodium path must satisfy them)
- **`RenderStates.portalsRenderedThisFrame`** (int; incremented `PortalRendering.onBeginPortalWorldRendering` L90, reset 0 in `RenderStates.updatePreRenderInfo` L108). READ by **MixinSodiumRenderSectionManager.java:49** (`isSectionVisible` HEAD, cancellable): if `!=0`, force-return `true`. Comment: "section visibility info will be wrong if rendered a portal â€¦ isSectionVisible is currently only used for culling entities." Contract: when any portal rendered this frame, Sodium's section-visibility query (used for entity culling) must be neutralized to always-visible.
- **`PortalRendering.shouldEnableSodiumCaveCulling()`** (PortalRendering.java:134). Read at **MyGameRenderer.java:126** (disables `client.smartCull`) and **MixinSodiumOcclusionCuller.java:46** (feeds `useOcclusionCulling` for `OcclusionCuller.findVisible`). Returns false for BoxPortalShape, else true only when camera <5 blocks from portal (comment: cave culling saves ~20% up close, negligible at 5 blocks, and mis-culls things behind the dest unless very close).
- **`PortalShape.getModifiedVisibleSectionIterationOrigin(portal, cameraPos)`** (BoxPortalShape.java:366 / default PortalShape.java:199). Read at **MixinSodiumOcclusionCuller.java:55** (Sodium path â€” overrides the BFS start section, redirects `Viewport.getChunkCoord` in `init`/`initWithinWorld`, and tolerates the initial frustum-test failure so iteration doesn't halt) AND at **VisibleSectionDiscovery.java:71** (the vanilla non-Sodium path). Contract: the Sodium occlusion-cull BFS must start iteration from the portal-derived section, not the camera section.

=====================================================================
## B. IrisInterface FACADE CONTRACT TABLE
=====================================================================

### B1. `boolean isIrisPresent()`  (default false / true)
- **PortalRenderer.java:325** (`switchToCorrectRenderer`, on renderer re-evaluation, not per-frame â€” called from render setup). Outer gate: if iris present AND `isShaders()`, pick an Iris-specific portal renderer (Experimental/Iris/IrisCompatibility per `renderMode`); else fall through to stencil/framebuffer renderers.
- **MixinRenderSystem_Clipping.java:39** (`iportal_onShaderSet`, per `RenderSystem.setShader` RETURN â€” very hot). When iris NOT present, IP manages its front-clipping plane uniform for cross-portal entity/projection/weather rendering; when iris present, `FrontClipping.unsetClippingUniform()` (IP delegates clipping to Iris). Contract: honest presence.
- **CrossPortalEntityRenderer.java:92** (`isCrossPortalRenderingEnabled`). If iris present â†’ cross-portal entity rendering DISABLED entirely (returns false regardless of `IPGlobal.correctCrossPortalEntityRendering`). Contract: `true` disables IP's entity-projection cross-portal path.

### B2. `boolean isShaders()`  (default false / `Iris.getCurrentPack().isPresent()`)
- **PortalRenderer.java:326 & :355** â€” L326 chooses Iris renderer path (see B1); L355 (`switchRenderer`) if shaders active, call `reloadPipelines()` after switching the active `PortalRenderer` (pipeline must be rebuilt when the renderer changes under shaders).
- **OverlayRendering.java:53** (`onRenderPortalEntity`). If shaders, print a one-time "Portal overlay cannot be rendered with shaders" and return (overlay rendering skipped under shaders).
- **Portal.java:882** (`renderViewAreaMesh`, per mirror mesh render). Under shaders (or `IPGlobal.pureMirror`) the mirror mesh is offset +0.01 in front instead of âˆ’0.01 behind ("rendering portal behind translucent objects with shader is broken").
- **Portal.java:1382** (`isRoughlyVisibleTo`, per portal per frame visibility cull). Passes `isShaders()` into `PortalShape.roughTestVisibility` (shaders change the visibility tolerance).
- **MixinLevelRenderer.java:427** (`wrapAddSkyPassExecute`, per sky pass). While portal-rendering with `doRenderSky==false`, skip vanilla sky rendering EXCEPT when `isShaders()` (shaders need the sky pass to run). Contract: `true` means "let the sky/pipeline pass proceed even when IP would otherwise skip it."

### B3. `boolean isRenderingShadowMap()`  (default false / `ShadowRenderer.ACTIVE`)
Read many places to make IP behave as a no-op / passthrough during Iris's shadow-map pass:
- **FrustumCuller.java:85** (`getCanDetermineInvisibleFunc`): return null (no portal frustum culling) during shadow pass.
- **MixinFrustum.java:62** (`onSetOrigin`/vanilla `Frustum.prepare` TAIL): skip updating IP's `portal_frustumCuller` during shadow pass.
- **MixinLevelRenderer.java:267** (`ip_allowOverrideTerrainSetup`): don't override vanilla terrain setup during shadow pass.
- **CrossPortalEntityRenderer.java:371** (`shouldRenderEntityNow`): during shadow pass, always render the entity (no cross-portal filtering).
- **MixinBlockEntityRenderDispatcher.java:20**: COMMENTED OUT (dead) â€” note only.
Contract: `true` MUST mean "Iris is rendering its shadow map; all IP portal-culling / terrain-override / entity-filtering must be inert."

### B4. `Object getPipeline(LevelRenderer worldRenderer)` / `void setPipeline(LevelRenderer, Object)`  (default null / no-op; OnIrisPresent = reflect the private `LevelRenderer.pipeline` field â†’ `WorldRenderingPipeline`)
The Iris pipeline save/null/restore around portal-content rendering, all in `switchAndRenderTheWorld`:
- **MyGameRenderer.java:168** `irisPipeline = getPipeline(worldRenderer)` â€” capture the DEST worldRenderer's current Iris pipeline BEFORE the swap (note: uses the explicit `worldRenderer` arg, resolved at L132, NOT `Minecraft.getInstance().levelRenderer`).
- **MyGameRenderer.java:221** `setPipeline(worldRenderer, null)` â€” null it so the portal content renders down the vanilla (non-shader) path during `renderLevel`.
- **MyGameRenderer.java:273** `setPipeline(worldRenderer, irisPipeline)` â€” restore after render.
Comment on the OnIrisPresent impl: "pipeline switching is unnecessary when using shaders but still necessary with shaders disabled." Contract: get/set of a per-LevelRenderer Iris pipeline handle so IP can temporarily detach Iris for the nested portal render and restore it. Default null/no-op is the fallback when Iris absent. HIGH FRAGILITY: implemented by reflecting a field literally named `pipeline` on `LevelRenderer` â€” see risk notes.

### B5. `void reloadPipelines()`  (default no-op / `Iris.getPipelineManager().destroyPipeline()`)
- **PortalRenderer.java:356** (`switchRenderer`, only when the active `PortalRenderer` instance actually changes AND `isShaders()`). Destroys Iris's pipeline so it rebuilds for the new renderer. Contract: force Iris to drop its cached world-rendering pipeline.

### B6. `@Nullable String getShaderpackName()`  (default null / `Iris.getCurrentPackName()`)
- **IPModInfoChecking.java:381** (`checkShaderpack`, called from `PortalRenderer.switchToCorrectRenderer` L323 on renderer re-eval). Compares against `lastShaderpackName`; if changed and matches a server-supplied `incompatibleShaderpacks` list, prints an incompatibility warning. Contract: current shaderpack display name or null; cosmetic/advisory only.

### B7. Commented / dead
- **RenderStates.java:159** â€” commented `if (!IrisInterface.invoker.isIrisPresent())` (dead).
- **MixinBlockEntityRenderDispatcher.java:20** â€” commented `isRenderingShadowMap()` (dead).

=====================================================================
## C. MyGameRenderer.switchAndRenderTheWorld â€” FULL CONTEXT-SWAP ORDER (L114-285)
=====================================================================
Called once per dest-world portal-content render. Ordering the C2 design must reproduce exactly:
1. L126 `shouldEnableSodiumCaveCulling()` â†’ if false, `client.smartCull=false`.
2. L132 resolve DEST `worldRenderer = ClientWorldLoader.getWorldRenderer(newDimension)`.
3. L147-166 stash all old state (chunk-info list, frustum, buffers, transparency shader, fixed buffers, projection/modelview) and install a fresh `newChunkInfoList` on the OLD renderer.
4. **L168 `irisPipeline = IrisInterface.getPipeline(worldRenderer)`** (capture, explicit dest handle).
5. L171 `ip_setWorldRenderer(worldRenderer)` â€” repoint `Minecraft.getInstance().levelRenderer` to dest. L172 `client.level = newWorld`. (lightmap, BE dispatcher, noPhysics, hand, fog swap, particle world, hitResult, camera set L173-187.)
6. L189-211 optional secondary RenderBuffers swap (`IPGlobal.useSecondaryEntityVertexConsumer`).
7. **L213 `newSodiumContext = SodiumInterface.createNewContext(renderDistance)`.**
8. **L214 `SodiumInterface.switchContextWithCurrentWorldRenderer(newSodiumContext)`** â€” swap EMPTY lists into the (now-current) dest manager.
9. L216 `portal_setTransparencyShader(null)`; L218-219 fresh modelview stack.
10. **L221 `IrisInterface.setPipeline(worldRenderer, null)`.**
11. L224-226 lightmap update if dimension not yet rendered.
12. **L229-235 `client.gameRenderer.renderLevel(...)`** â€” the nested portal-content render (profiler "render_portal_content"); populates the swapped-in Sodium list.
13. **L237 `SodiumInterface.switchContextWithCurrentWorldRenderer(newSodiumContext)`** â€” swap back (restore dest's primary list, discard portal list).
14. L241-271 recover ALL old state (renderer, level, lightmap, camera, buffers, frustum, projection, modelview) in reverse.
15. **L273 `IrisInterface.setPipeline(worldRenderer, irisPipeline)`** (restore).
16. L275-280 re-`prepare` entity dispatcher; L284 `client.smartCull=true`.
Key invariant: Sodium context swap-in (8) and swap-out (13) BRACKET the render (12) with the SAME context; Iris capture (4)/null (10)/restore (15) bracket it too. Iris ops key off the explicit `worldRenderer`; Sodium ops key off the implicit current `Minecraft.getInstance().levelRenderer`.

=====================================================================
## D. PortalRenderer.switchToCorrectRenderer / switchRenderer (L310-359)
=====================================================================
- `switchToCorrectRenderer()` (L310): early-return if `PortalRendering.isRendering()`; FABULOUS graphics warning; `IPModInfoChecking.checkShaderpack()` (â†’ B6). Then: if `isIrisPresent()` AND `isShaders()` â†’ pick Iris renderer by `experimentalIrisPortalRenderer` flag / `IPGlobal.renderMode` (normalâ†’IrisPortalRenderer, compatibilityâ†’IrisCompatibilityPortalRenderer, debugâ†’debugModeInstance, noneâ†’rendererDummy) and RETURN. Else pick vanilla-path renderer by `renderMode` (normalâ†’rendererUsingStencil, compatibilityâ†’rendererUsingFrameBuffer, debugâ†’rendererDebug, noneâ†’rendererDummy).
- `switchRenderer(renderer)` (L350): if the active `IPCGlobal.renderer` changed, log + assign, and if `isShaders()` call `reloadPipelines()` (â†’ B5).
Contract dependency: `isIrisPresent`, `isShaders`, `reloadPipelines`. This is where the Iris-vs-vanilla portal renderer fork lives; the C2 design's renderer selection hinges on `isShaders()` being truthful.

=====================================================================
## E. FrustumCuller construction/usage (two independent paths)
=====================================================================
`FrustumCuller` (render/FrustumCuller.java) computes a `BoxPredicateF canDetermineInvisibleFunc` in `update()`â†’`getCanDetermineInvisibleFunc()`, reading: `IPCGlobal.doUseAdvancedFrustumCulling`, `IrisInterface.isRenderingShadowMap()` (â†’null), `PortalRendering.isRendering()` (inner cull func from the rendering portal's shape) vs else (outer cull, gated on `IPCGlobal.useSuperAdvancedFrustumCulling` AND `SodiumInterface.isSodiumPresent()`).
- **Vanilla path**: `MixinFrustum` (mixin/client/render/optimization) holds its OWN `portal_frustumCuller` field per `net.minecraftâ€¦Frustum`, updated in `prepare` TAIL (guard: skip under iris shadow), read in `cubeInFrustum` HEAD, copied in the copy-ctor. Independent of `SodiumInterface.frustumCuller`.
- **Sodium path**: the static `SodiumInterface.frustumCuller` (see A7) â€” set in `MixinSodiumWorldRenderer.setupTerrain`, read in `MixinSodiumViewport` redirect.
The C2 design must wire BOTH: vanilla `Frustum.cubeInFrustum`/`prepare` (26.2 renderer-rewrite target) AND Sodium's `Viewport.isBoxVisible`/`SodiumWorldRenderer.setupTerrain`.

=====================================================================
## F. Machinery each OnSodiumPresent method depends on (for C2 re-mapping)
=====================================================================
- `switchContextWithCurrentWorldRenderer` depends on: `LevelRendererExtension.sodium$getWorldRenderer()`, `SodiumWorldRenderer.scheduleTerrainUpdate()`, `IESodiumWorldRenderer.ip_getRenderSectionManager()` (@Accessor for private field `renderSectionManager` on `SodiumWorldRenderer`), `IESodiumRenderSectionManager.ip_swapContext` (MixinSodiumRenderSectionManager, which @Shadows `int renderDistance` [Final+Mutable] and `SortedRenderLists renderLists`), `SortedRenderLists`.
- `createNewContext` depends on `SortedRenderLists.empty()`.
- `markSpriteActive` depends on `SpriteUtil.markSpriteActive`.
- `onClientChunk(Un)loaded` depend on `ChunkTrackerHolder.get(level)` + `onChunkStatusAdded/Removed` + `ChunkStatus.FLAG_HAS_BLOCK_DATA`.
- `isSodiumPresent` â€” trivial.
Sodium compat mixins in the config (all gated `contains("Sodium")`): `IESodiumWorldRenderer`, `MixinSodiumDefaultShaderInterface`, `MixinSodiumFlawlessFrames`, `MixinSodiumOcclusionCuller`, `MixinSodiumRenderRegion`, `MixinSodiumRenderSectionManager`, `MixinSodiumShaderLoader`, `MixinSodiumViewport`, `MixinSodiumWorldRenderer`. OnIrisPresent depends on: `Iris.getCurrentPack()/getCurrentPackName()`, `Iris.getPipelineManager().destroyPipeline()`, `ShadowRenderer.ACTIVE`, `WorldRenderingPipeline`, reflected `LevelRenderer.pipeline` field.

=====================================================================
## G. Full call-site index (file:line â†’ method â†’ firing condition)
=====================================================================
SODIUM:
- ImmPtlClientChunkMap.java:81 â†’ onClientChunkUnloaded â†’ per chunk drop (main thread)
- ImmPtlClientChunkMap.java:164 â†’ onClientChunkLoaded â†’ per chunk packet apply (main thread)
- IPModMainClient.java:43 â†’ isSodiumPresent â†’ delayed one-shot (nvidia warning)
- FrustumCuller.java:108 â†’ isSodiumPresent â†’ per FrustumCuller.update (outer-cull gate)
- OverlayRendering.java:151 â†’ markSpriteActive â†’ per overlay quad
- MyGameRenderer.java:213 â†’ createNewContext â†’ per portal-content render
- MyGameRenderer.java:214 & 237 â†’ switchContextWithCurrentWorldRenderer â†’ bracket renderLevel
- MixinLevelRenderer.java:266 â†’ isSodiumPresent â†’ per setupRender (terrain-override gate)
- MixinLevelRenderer.java:502 â†’ isSodiumPresent â†’ per isSectionCompiled while portal-rendering
- MixinSectionRenderDispatcher.java:39 â†’ isSodiumPresent â†’ per SectionRenderDispatcher.<init> during world creation
- IPModEntryClient.java:76 â†’ invoker assignment (OnSodiumPresent)
- SodiumInterface.frustumCuller: WRITE MixinSodiumWorldRenderer.java:23,25 / READ MixinSodiumViewport.java:28,30
IRIS:
- IPModInfoChecking.java:381 â†’ getShaderpackName â†’ checkShaderpack (renderer re-eval)
- PortalRenderer.java:325 isIrisPresent, 326 isShaders, 355 isShaders, 356 reloadPipelines â†’ switch renderer
- OverlayRendering.java:53 isShaders â†’ overlay entity render
- Portal.java:882 isShaders â†’ mirror mesh offset; Portal.java:1382 isShaders â†’ rough visibility
- MyGameRenderer.java:168 getPipeline, 221 setPipeline(null), 273 setPipeline(restore)
- MixinLevelRenderer.java:267 isRenderingShadowMap (terrain-override gate), 427 isShaders (sky-skip except iris)
- MixinRenderSystem_Clipping.java:39 isIrisPresent â†’ per setShader (clipping uniform)
- FrustumCuller.java:85 isRenderingShadowMap; CrossPortalEntityRenderer.java:92 isIrisPresent, 371 isRenderingShadowMap
- MixinFrustum.java:62 isRenderingShadowMap â†’ per Frustum.prepare
- IPModEntryClient.java:94 â†’ invoker assignment (OnIrisPresent)
- Commented/dead: RenderStates.java:159, MixinBlockEntityRenderDispatcher.java:20


### Risk notes (coreCallsites)
- UNVERIFIED (0.9.1 jars not yet javapped): The entire SodiumRenderingContext swap (createNewContext + switchContextWithCurrentWorldRenderer + MixinSodiumRenderSectionManager.ip_swapContext) rests on Sodium's RenderSectionManager still exposing swappable `SortedRenderLists renderLists` + `int renderDistance` fields. Sodium 0.6->0.9 rewrote the chunk render pipeline; these field names/types and even the per-frame-render-list model are the highest-risk assumption. If Sodium 0.9.1 no longer stores a single mutable SortedRenderLists on the manager, the whole swap approach for C2 must be redesigned, not ported.
- UNVERIFIED: `LevelRendererExtension.sodium$getWorldRenderer()` and `SodiumWorldRenderer.renderSectionManager` (the @Accessor target in IESodiumWorldRenderer) and `SodiumWorldRenderer.scheduleTerrainUpdate()` / `setupTerrain(Camera,Viewport,boolean,boolean)` signatures are Sodium-0.6-era. MC 26.2's vanilla renderer rewrite likely changed how Sodium attaches to LevelRenderer (the whole sodium$ extension mechanism may differ), and setupTerrain's arg list is a mixin target that must be re-javapped.
- UNVERIFIED: Sodium occlusion-cull hooks (MixinSodiumOcclusionCuller) target `OcclusionCuller.findVisible(Visitor,Viewport,float,boolean,int)`, `OcclusionCuller.init/initWithinWorld`, `Viewport.getChunkCoord()`, `OcclusionCuller.isWithinFrustum(Viewport,RenderSection)`. Sodium's occlusion/BFS internals are volatile across versions; every one of these method names/descriptors needs re-verification against 0.9.1 and may have moved or been inlined.
- UNVERIFIED: `MixinSodiumViewport` redirects `Frustum.testAab(FFFFFF)Z` inside `Viewport.isBoxVisible`, and `MixinSodiumRenderSectionManager` injects `isSectionVisible(int,int,int)`. Both are the portal-frustum + entity-cull neutralization hooks; their existence/signatures in 0.9.1 gate A7 and A8 and must be re-javapped.
- UNVERIFIED: Sodium sprite/chunk-tracker API â€” `SpriteUtil.markSpriteActive`, `ChunkTrackerHolder.get(level).onChunkStatusAdded/Removed`, `ChunkStatus.FLAG_HAS_BLOCK_DATA`. These package/class names (net.caffeinemc.mods.sodium.client...) are stable-ish but the ChunkTracker status-flag enum and SpriteUtil location commonly move between Sodium releases.
- UNVERIFIED: Iris getPipeline/setPipeline reflect a private field literally named `pipeline` on net.minecraft LevelRenderer (Helper.noError swallows failures silently -> returns null / no-ops). MC 26.2's renderer rewrite may rename/remove LevelRenderer or relocate the Iris pipeline handle; because failures are swallowed, a broken reflection would degrade to 'Iris never detached during portal render' with NO error -> shader corruption in portal views. This needs explicit re-verification, not silent-null tolerance, on 26.2.
- UNVERIFIED: Iris API surface for 1.11.2 â€” `Iris.getCurrentPack()`, `Iris.getCurrentPackName()`, `Iris.getPipelineManager().destroyPipeline()`, `ShadowRenderer.ACTIVE`, `WorldRenderingPipeline`. Iris 1.8->1.11 refactored package layout (net.irisshaders.iris.*); ShadowRenderer.ACTIVE static and the PipelineManager API are the most likely to have moved.
- UNVERIFIED (26.2 vanilla targets, not compat but load-bearing for the facade contract): MixinLevelRenderer targets `setupRender(Camera,Frustum,boolean,boolean)`, `isSectionCompiled(BlockPos)`, `addSkyPass` via framegraph `FramePass.executes(Runnable)`, `pollLightUpdates`. MixinFrustum targets `Frustum.cubeInFrustum/prepare/offsetToFullyIncludeCameraCube/calculateFrustum`. MixinSectionRenderDispatcher targets `RenderBuffers.sectionBufferPool`. Per the project's own 26.2 renderer-rewrite notes (framegraph, addSkyPass), several of these have already shifted and gate whether `isSodiumPresent()`/`isRenderingShadowMap()` branches even reach their intended code.
- The gating model is split: the `invoker` swap (IPModEntryClient L76/L94) is driven by FabricLoader.isModLoaded, while the compat MIXINS are gated separately by IPCompatMixinPlugin.shouldApplyMixin on class-name substring. For C2, the OnSodiumPresent/OnIrisPresent methods and their supporting compat mixins must be kept in lockstep â€” a facade method whose backing mixin (e.g. MixinSodiumRenderSectionManager for ip_swapContext) fails to apply on 0.9.1 will NPE/no-op at runtime with the invoker still reporting present=true.
- Handle asymmetry to preserve in C2: Sodium context swap targets the IMPLICIT Minecraft.getInstance().levelRenderer (repointed to dest at MyGameRenderer L171 BEFORE the L214 swap), whereas Iris getPipeline/setPipeline target the EXPLICIT worldRenderer arg captured at L132. Any refactor that unifies these handles could break one path if the 26.2 current-levelRenderer swap timing changes.
- SodiumInterface.frustumCuller has no teardown â€” it is overwritten every setupTerrain and read within the same pass. On 26.2 if Sodium's terrain-setup entry point is called differently (e.g. multiple times per frame, or off the expected thread) the stale-vs-fresh timing of this static could mis-cull; the C2 impl should re-confirm the write happens exactly once at the head of each visibility pass, before any isBoxVisible read.

