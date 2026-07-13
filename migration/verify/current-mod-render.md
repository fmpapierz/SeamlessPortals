# Adversarial verification: current mod render inventory + disposition

Docs under review:
- INV = `migration/inventory/current-mod-render.md`
- MAP = `migration/api-map/current-mod-render.md`

> **ROUND 2 (2026-07-12), against the REVISED docs: CLEAN.** Both docs were revised after Round 1
> to fold in V1–V7. Round 2 independently re-derived 24 load-bearing claims of the revised docs
> (including every Round-1 correction now embedded in them: the JOML column-form ruling was
> re-run empirically from scratch, the FrustumCuller/PortalShape chains re-read, the bobView
> machinery re-read, the mixin census re-counted). **0 refuted.** Details in the "Round 2" section
> at the end of this file. Round-1 content below is preserved because the docs cite V1/V5/V7 by number.

Verifier method: every checked claim re-derived from source (IP 1.21.3 at
`C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`, decompiled 26.2 at
`C:/Users/warwa/ModDev/mc262-ref`, mod at `C:/Users/warwa/ModDev/Portals/Portal 26.2`). The one
pure-math claim (JOML multiply order) was verified **empirically** by compiling and running a test
against the actual `joml-1.10.5.jar` from the gradle cache. 36 claims checked; 29 confirmed, 7 refuted.

**Severity: MAJOR** — three refuted claims (V1, V2, V3) would misdirect port geometry/architecture.

---

## REFUTED

### V1. JOML `Vector4f.mul(Matrix4f)` is NOT the row-vector form — it is M·v (column form)
- **Claim (INV §2.6):** "the JOML idiom used here: `Vector4f.mul(Matrix4f)` (`:147`, `:181`) —
  flagged as the row-vector form in `IP_DEVIATIONS_ANALYSIS.md:88-89`; it happens to be correct here
  only because `viewRotation` is a pure rotation fed with w=0."
- **Claim (MAP §1.6):** "re-verify the JOML `Vector4f.mul(Matrix4f)` **row-vector idiom**
  (`render/FrontClipping.java:147,181`) against IP during the port."
- **Verdict: REFUTED (both docs).** Empirical test against `joml-1.10.5.jar`
  (`C:/Users/warwa/.gradle/caches/minecraft/libraries/org/joml/joml/1.10.5/joml-1.10.5.jar`):
  for `M = rotationZ(90°)`, `v = (1,0,0,0)`:
  - `new Vector4f(v).mul(M)` → `(0, 1, 0, 0)`
  - `M.transform(new Vector4f(v))` → `(0, 1, 0, 0)` — **identical**
  - `new Vector4f(v).mulTranspose(M)` → `(0, -1, 0, 0)` — the actual row-vector form
  - `new Vector4f(1,2,3,1).mul(translation(5,6,7))` → `(6, 8, 10, 1)` — translation column applied,
    proving column-vector semantics.
  `Vector4f.mul(Matrix4fc)` computes **M·v** (column form), byte-identical to `Matrix4f.transform`.
  The row form is `mulTranspose`. So: (a) the "row-vector" characterization is false — its origin,
  `IP_DEVIATIONS_ANALYSIS.md:88-89` ("`Vector4f.mul(Matrix4f)` = v * M (row-vector) — WRONG for
  OpenGL"), is itself wrong; (b) the "correct only because pure rotation" rationale is false — for a
  pure rotation the row form gives the **inverse** rotation, not the same result; the code is correct
  because `mul` IS the column form (w=0 merely drops the translation column, which is intended for a
  plane normal). **Port danger:** a porter told this idiom is "accidentally correct row-form" may
  "fix" it (transpose / `mulTranspose`), introducing an inverse-rotation clip-plane bug — precisely
  the geometry-sign bug class this project has been burned by twice.

### V2. IP's view-area mesh is built via `PortalShape.renderViewAreaMesh`, NOT from `Portal.getFourVerticesLocal`
- **Claim (MAP §1.3):** "mesh built by `buildPortalViewAreaTrianglesBuffer(...)` (`:120`, invoked at
  `:94`) from the Portal entity's vertices — `Portal.getFourVerticesLocal(double shrinkFactor)`
  (`Portal.java:1132`), rotated/cullable variants `:1156/:1167`". (INV §2.3 makes the softer version:
  "IP `ViewAreaRenderer` builds the same mesh from the Portal entity's four vertices".)
- **Verdict: REFUTED (MAP; INV imprecise).** Actual chain read from source:
  `ViewAreaRenderer.buildPortalViewAreaTrianglesBuffer` (`ViewAreaRenderer.java:120`) calls
  `portal.renderViewAreaMesh(originRelativeToCamera, vertexOutput)` (`ViewAreaRenderer.java:142`);
  `Portal.renderViewAreaMesh` (`Portal.java:877`) delegates to
  `getPortalShape().renderViewAreaMesh(...)` (`Portal.java:889`) — **shape-polymorphic**:
  `RectangularPortalShape.renderViewAreaMesh` (`portal/shape/RectangularPortalShape.java:143-158`)
  builds the quad from `UnilateralPortalState.getAxisW()/getAxisH()` scaled by width/2, height/2 via
  `ViewAreaRenderer.outputFullQuad`; `BoxPortalShape.java:189` and `SpecialFlatPortalShape.java:156`
  provide different meshes. `getFourVerticesLocal*` never appears in `ViewAreaRenderer.java`
  (grep: zero matches); its consumer is `Portal.getOuterFrustumCullingVertices`
  (`Portal.java:1395-1399`) — a frustum-culling API, not the mesh source. **Port impact:** porting
  `ViewAreaRenderer` against `getFourVerticesLocal` misses the `PortalShape` abstraction that
  "COMPLETE IP fidelity" requires (box/special-flat portals draw different view-area meshes).

### V3. IP's inner-cull corners come from `FrustumCuller.getRectPortalFourVerticesCounterClockwise`, not `Portal.getFourVerticesLocal`
- **Claim (MAP §1.9):** "Re-source the four corners from the Portal entity
  (`Portal.getFourVerticesLocal`, `Portal.java:1132`) instead of the axis branch."
- **Verdict: REFUTED.** `FrustumCuller.getFlatPortalInnerFrustumCullingFunc`
  (`FrustumCuller.java:176`) sources corners via
  `getRectPortalFourVerticesCounterClockwise(portal.getThisSideState())` (`FrustumCuller.java:179`;
  method defined at `:143`, a **FrustumCuller static taking `UnilateralPortalState`**, order comment
  `// 2 1 / 3 0` at `:141-142`), then transforms each with `portal.transformPoint(v[i])` into dest
  space (`:183-187`) with a Mirror-flip special case (`:190-195`). `getFourVerticesLocal` is not
  involved anywhere in `FrustumCuller.java`.

### V4. `getRectPortalFourVerticesCounterClockwise` is misattributed to `Portal`
- **Claim (INV §2.9):** "corners come from IP `Portal.getRectPortalFourVerticesCounterClockwise`
  (dest-space via transform)."
- **Verdict: REFUTED (attribution).** The method is `FrustumCuller.getRectPortalFourVerticesCounterClockwise(UnilateralPortalState)`
  (`FrustumCuller.java:143`); no such member exists in `Portal.java` (grep: zero matches). The
  dest-space-via-`transformPoint` half of the sentence is correct (`FrustumCuller.java:183-187`).

### V5. "IP does not skip bob" (INV) is wrong — MAP's correction verified
- **Claim (INV §3.2, `MainProjectionBobMixin` row):** "KEEP (behavioral choice; re-evaluate vs IP,
  **which does not skip bob**)".
- **Verdict: REFUTED (INV); MAP §2.1's correction CONFIRMED from source.** IP distance-scales the
  world bobView translate: three `@ModifyArg`s on `PoseStack.translate(FFF)` inside `bobView`
  multiplying by `RenderStates.getViewBobbingOffsetMultiplier()` unless rendering the hand
  (`mixin/client/render/MixinGameRenderer.java:212-253`, hand flag set at `:202-210`); multiplier =
  `viewBobFactor * PortalRendering.getExtraModelViewScaling()` gated by `IPGlobal.viewBobbingReduce`
  (default `true`, `IPGlobal.java:121`) (`RenderStates.java:183-196`); `viewBobFactor` = 0 inside
  1 block of a portal, `minPortalDistance - 1` in the 1..2 band, 1 otherwise, fast-down/slow-up lerp
  (`RenderStates.java:157-181`, `:198-204`). IP never touches `bobHurt` (grep of
  `MixinGameRenderer.java`: zero matches) — also as MAP states.

### V6. `ViewArea.getRenderSection(long)` is protected, not private (cross-doc contradiction)
- **Claim (INV §5):** "private `ViewArea.getRenderSection(long)` (invoker)". MAP §3.3 says
  "protected".
- **Verdict: INV REFUTED / MAP confirmed.** `mc262-ref net/minecraft/client/renderer/ViewArea.java:91`:
  `protected SectionRenderDispatcher.@Nullable RenderSection getRenderSection(long sectionNode)`.
  Consequence unchanged (invoker still needed), but the docs contradict each other and INV is the
  wrong one.

### V7. Two client mixin files silently unaccounted for in the slice
- **Claim (INV §3 + §3.5 tail):** the inventory covers all render-related mixins and lists the
  render-adjacent exclusions "for the dependency map".
- **Verdict: REFUTED (completeness).** Directory listing of
  `common/src/main/java/com/warwa/seamlessportals/mixin/client/` (56 files incl. `compat/` +
  `stencil/`) vs the 44 audited + 10 named exclusions leaves **two files mentioned nowhere in either
  doc**:
  - `ClientLevelChunkSourceAccessor.java` — `@Mutable` accessor for `ClientLevel.chunkSource`, used
    to install the unbounded `SeamlessClientChunkMap` on portal SECONDARY levels in
    `PortalWorldManager.createRenderer` (its own javadoc) — that is render-view world plumbing,
    arguably in-slice.
  - `LivingEntitySprintCancelDiagMixin.java` — sprint-cancel diagnostic (crossing slice), plausibly
    out-of-slice but not listed among the exclusions either.
  Neither is counted in the MAP §6 tally (44), so the tally's completeness claim inherits the gap.

---

## CONFIRMED (spot-check log; every citation re-opened)

1. **IP renderer hierarchy + line cites (MAP §1.1):** `public abstract class PortalRenderer`
   (`renderer/PortalRenderer.java:42`); `getPortalsToRender(Matrix4f)` `:84`; `renderPortalContent`
   `:197`; `invokeWorldRendering` `:249`; `getPortalTransformation/getPortalRotationMatrix/
   getPortalScaleMatrix` `:259/:271/:290`; `switchToCorrectRenderer` `:310`.
   `RendererUsingStencil extends PortalRenderer` (`:32`); `renderPortals(Matrix4f)` `:73-79`;
   `doRenderPortal` `:119`; `clearDepthOfThePortalViewArea` `:199`; `restoreDepthOfPortalViewArea`
   `:227`; `clampStencilValue` `:249`; `setStencilLimitation(int)` `:286` (GL_EQUAL, value, 0xFF —
   semantics match the mod's `StencilPortalRenderer.setStencilLimitation`, mod `:480-484`).
2. **IP stencil-enable path (MAP §1.12/§3.1):** `IPPortingLibCompat.get/setIsStencilEnabled` at
   `RendererUsingStencil.java:87-89` inside `prepareRendering`; `IEFrameBuffer` duck =
   `ip_getIsStencilBufferEnabled`/`ip_setIsStencilBufferEnabledAndReload` (`ducks/IEFrameBuffer.java:3-6`).
3. **MyGameRenderer (MAP §1.2/§1.10):** `renderWorldNew(WorldRenderInfo, Consumer<Runnable>)` `:96`;
   `switchAndRenderTheWorld(ClientLevel, Vec3, Vec3, Consumer<Runnable>, int, boolean)` `:114`
   (signature matches exactly); `acquireRenderBuffersObject` `:76-77` / `returnRenderBuffersObject`
   `:91`, used at `:189-193`, returned at `:262-264` (acquire is additionally gated by
   `IPGlobal.useSecondaryEntityVertexConsumer` — worth noting in the port);
   `PortalRendering.shouldEnableSodiumCaveCulling()` consumed at `:126`.
4. **context_management cites (MAP §1.2):** `PortalRendering` class `:28`, `pushPortalLayer` `:33`,
   `getPortalLayer` `:58`, `isRendering` `:62`; `WorldRenderInfo:23`; `FogRendererContext:21`;
   `CloudContext:17`; `StaticFieldsSwappingManager<Context>:18`.
5. **TransformationManager (MAP §1.17):** class `:33`; `managePlayerRotationAndChangeGravity(Portal)`
   `:134-225`, entire body gated `if (portal.getRotation() != null)` `:138` (no-op for unrotated
   portals — confirmed); "keep immediate final rotation unchanged, to keep teleportation seamless"
   comment `:205`; `> 0.1` degree threshold before starting the animation delta `:217`;
   `processTransformation` `:88-102`. INV's `CameraTransitionHandler` DELETE justification stands.
6. **IP pre-render block (MAP §2.2/§2.3):** `MixinGameRenderer` `@Inject(method="render", at=HEAD)`
   `:70`; block runs `RenderStates.updatePreRenderInfo` (`:87`),
   `ClientPortalAnimationManagement.update()` (`:91`), `ClientTeleportationManager.manageTeleportation(false)`
   (`:92`), `MyRenderHelper.earlyRemoteUpload()` (`:94-96`). Per-tick `manageTeleportation(true)` at
   `MixinMinecraft.java:137`; `manageTeleportation(boolean)` decl at
   `teleportation/ClientTeleportationManager.java:121`. `RenderStates.updatePreRenderInfo` `:87`;
   `ForceMainThreadRebuild.onPreRender()` called at `RenderStates.java:121`;
   `ForceMainThreadRebuild` class at `ForceMainThreadRebuild.java:12`.
7. **A1 evidence (MAP §4):** `IPGlobal.RenderMode {normal, compatibility, debug, none}`
   (`IPGlobal.java:147-152`); `IPCGlobal` holds `renderer`, `rendererUsingStencil`,
   `rendererUsingFrameBuffer`, `rendererDummy`, `rendererDebug` (`IPCGlobal.java:16-20`); both live
   renderers constructed at client init (`IPModMainClient.java:81-84`).
8. **26.2 stencil persistence substrate (INV item 1):** `GlCommandEncoder.applyPipelineState`
   (`mc262-ref com/mojang/blaze3d/opengl/GlCommandEncoder.java:771`) handles only
   depthTest/depthMask/polygonOffset from `DepthStencilState`; file-wide grep for `stencil` matches
   only the `DepthStencilState` type name — no `glStencil*` calls anywhere. `DepthStencilState` is a
   depth-only record `(CompareOp depthTest, boolean writeDepth, float, float)`
   (`com/mojang/blaze3d/pipeline/DepthStencilState.java:8`), default compare
   `GREATER_THAN_OR_EQUAL` — also confirms the reversed-Z claim (INV item 5; mod GEQUAL pipeline at
   `PortalRenderTypes.java:110`).
9. **26.2 direct-draw facts (INV items 2/7, §5):** `prepareChunkRenders(Matrix4fc)` public at
   `mc262-ref LevelRenderer.java:509`; `compileSections(CameraRenderState)` **private** at `:608`;
   `renderGroup(TRANSLUCENT)` inside the main pass at `:438`; the 8-arg
   `render(GraphicsResourceAllocator, DeltaTracker, boolean, CameraRenderState, Matrix4fc,
   GpuBufferSlice, Vector4f, boolean)` at `:156-165` — exact match to INV §5.
   `ChunkSectionLayerGroup.outputTarget()`: `TRANSLUCENT → levelRenderer.translucentTarget()`,
   default (incl. OPAQUE) → `gameRenderer.mainRenderTarget()` (`ChunkSectionLayerGroup.java:29-37`).
10. **26.2 GONE verdicts (INV/MAP §3.3):** `cullTerrain` — repo-wide grep of `mc262-ref`: **zero
    files**; `LevelRenderer.update(Camera` — zero matches. Rename-hunt consistent with the docs'
    story: the frustum cull now lives in `LevelExtractor.extract` → `this.applyFrustum(cullFrustum)`
    (`mc262-ref net/minecraft/client/renderer/extract/LevelExtractor.java:129-130`), private
    `applyFrustum(Frustum)` at `:373` with a wrong-thread guard `:375`. NOTE (citation precision):
    the file is in the `renderer/extract/` subpackage, not `renderer/` as both docs' shorthand
    implies — line number correct.
11. **Frame-order claim (INV item 16, MAP §2.2):** `GameRenderer.update(DeltaTracker)`
    (`mc262-ref GameRenderer.java:372-377`) does `this.mainCamera.update(deltaTracker)` and `extract`
    runs right after (`:379+`) — update HEAD is indeed "before the frame's camera is positioned",
    validating the `GameRendererFrameCrossingMixin` placement rationale.
12. **Mod-side anchors:** `STENCIL_DIRECT = true` (`StencilPortalRenderer.java:41`);
    `setStencilLimitation` EQUAL(layer)/KEEP/mask 0x00 (`:480-484`); dest camera =
    `PortalTransform.transformPoint` + axis-conditional yaw ±90° (`PortalContextSwitch.java:1041-1056`;
    srcAxis==Z → +90 else −90, only when axes differ); `withSwitchedWorld(ClientLevel, LevelRenderer,
    RenderTarget, Camera, Lightmap, Runnable)` at `:569` swapping the WHOLE `particleEngine`
    (`:584-594`); `PortalShapeRenderer` axis-branch geometry exactly as described — axis==X → XY quad
    at `z = origin.z + 0.5`, axis==Z → ZY quad at `x = origin.x + 0.5` (`:515-541`).
13. **Mixin registration ground truth (INV §6):** `seamlessportals-common.mixins.json` registers
    `client.LevelRendererCullTerrainMixin` (line 37) and does NOT contain `GlTextureViewMixin`,
    `MinecraftRenderTargetMixin`, `LevelRendererDiagMixin`, `GameRendererObliqueClipMixin` (grep —
    only the CullTerrain hit). `SeamlessMixinConfigPlugin.SODIUM_INCOMPATIBLE_MIXINS` = exactly
    `client.LevelRendererCullTerrainMixin` (`SeamlessMixinConfigPlugin.java:41-43`).
14. **MainProjectionBobMixin 26.2 signatures (MAP §2.1):** two `@Redirect`s on `renderLevel`
    targeting `GameRenderer.bobHurt(CameraRenderState, PoseStack)` (`:35-46`) and
    `GameRenderer.bobView(CameraRenderState, PoseStack)` (`:48-59`), both empty bodies —
    unconditional no-op as both docs state. `GameRendererObliqueClipMixin` pass-through
    `return self.getBuffer(matrix)` (`:47-56`) — parked as stated.
15. **Fabric wiring (INV §6):** `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx ->
    StencilPortalRenderer.renderPortals())` (`fabric/.../SeamlessPortalsClientFabric.java:26-28`).
16. **VisibleSectionDiscovery (MAP §1.5/A3):** IP class `:35`; `discoverVisibleSections` `:45`;
    `skipFrustumTest` semantics at `:143`/`:160`; case-insensitive grep for `compile|rebuild|dirty`
    in the IP file: **zero matches** (exit 1) — the mod's folded-in compile scheduling is indeed a
    non-IP extension.
17. **FrontClipping IP cites (MAP §1.6):** class `:22`; `disableClipping` `:31`;
    `updateInnerClipping(PoseStack)`/`(Matrix4f)` `:49`/`:54`; `setupInnerClipping(Plane, Matrix4f,
    double)` `:66-67`; `setupOuterClipping(PoseStack, Portal)` `:122`; plane getters `:167`/`:171`.
18. **MyRenderHelper (MAP §5/§3.2):** class `:61`; `renderScreenTriangle` overloads from `:216`;
    `lateUpdateLight` `:441`; `earlyRemoteUpload` `:458`.
19. **Duck interfaces (MAP §3):** `IEGameRenderer.ip_setLightmapTextureManager(LightTexture)` at
    `ducks/IEGameRenderer.java:8`; `IEMinecraftClient` `ip_setFrameBuffer/ip_setWorldRenderer/
    ip_setRenderBuffers` (`:8-16`); `IECamera` `ip_resetState/portal_setPos/...` (`:7-18`);
    `IEParticleManager.ip_setWorld(ClientLevel)` (`:5-6`) — MAP's name is right; INV §3.5's
    "`IEParticleManager.setWorld_`" is a trivial misspelling of the same member;
    `IEWorldRenderer` has `ip_getBuiltChunkStorage`, `ip_get/setRenderBuffers`, `portal_getFrustum`,
    `portal_getChunkInfoList` (`ducks/IEWorldRenderer.java:14-41`).
20. **FrustumCuller cites (MAP §1.9):** class `:22`; `getRectPortalFourVerticesCounterClockwise`
    `:143`; `getFrustumPlanesFromFourVerticesCounterClockwise` `:156`;
    `getFlatPortalInnerFrustumCullingFunc` `:176`; `record Frustum4Planes` `:269`;
    `testBoxTwoVertices` `:321` (note: it is `@Deprecated` in IP — MAP does not mention that).
21. **Portal vertex-method line cites (MAP §1.3):** `getFourVerticesLocal(double)` `Portal.java:1132`;
    `getFourVerticesLocalRotated` `:1156`; `getFourVerticesLocalCullable` `:1167` — lines correct;
    their ROLE is what V2/V3 refute.
22. **ShaderCodeTransformation (MAP §1.7):** IP class `:15`; YAML-driven config load `:59`;
    `transform(CompiledShader.Type, String, String)` `:72`.
23. **DimensionRenderHelper (MAP §1.8):** IP class `:9`; per-dim `lightmapTexture` (main dim reuses
    the game's `lightTexture()`, others get `new LightTexture`) `:13-27`; `tick()` guard `:29-33`;
    `cleanUp()` closes non-main `:35-39`.
24. **Scope, render classes:** directory listing of `common/.../render/` = exactly the 19 classes
    inventoried, none skipped. MAP §6 tally internally consistent for the 63 items it covers
    (19 = 7 KEEP + 6 PORT-FORWARD + 3 REPLACE + 3 DELETE; 44 mixins = 34 + 1 + 9) — see V7 for the
    two files outside the tally.

---

## Notes (not refutations)

- `mc262-ref` `LevelExtractor` path: `net/minecraft/client/renderer/extract/LevelExtractor.java`
  (both docs cite it as if directly under `renderer/`; line numbers are correct).
- MAP §1.2 cites `MyGameRenderer.java:52` for the class decl (actual `:51`) and `:77` for
  `acquireRenderBuffersObject` (signature at `:76`) — off-by-one nits only.
- IP's `acquireRenderBuffersObject` swap is gated by `IPGlobal.useSecondaryEntityVertexConsumer`
  (`MyGameRenderer.java:189-193`) — a config gate the PORT-FORWARD row for `PortalRenderBuffersPool`
  should carry into the port spec.
- The empirical JOML test source is preserved at the session scratchpad (`JomlTest.java`) if the
  result needs re-running.

---
---

# Round 2 (2026-07-12) — fresh adversarial pass against the REVISED docs

The docs under review incorporated Round 1's refutations; nobody had independently re-checked the
corrections that were folded in. Round 2 re-derived them (and the other load-bearing claims) from
source with no trust in Round 1's text. Method identical: open the cited file, read the lines;
geometry claims re-derived/re-run, GONE verdicts rename-hunted, greps re-executed.

**Result: 24 claims checked, 24 confirmed, 0 refuted. Severity: CLEAN.**

## Confirmed (Round 2 log)

### Geometry / sign / transform (re-derived, not trusted)

- **R2.1 — JOML column-form ruling (INV §2.6, MAP §1.6) re-run empirically from scratch.**
  Fresh test (`JomlVerify.java`, this session's scratchpad) against
  `C:/Users/warwa/.gradle/caches/minecraft/libraries/org/joml/joml/1.10.5/joml-1.10.5.jar`:
  for `M = rotationZ(90°)`, `v=(1,0,0,0)`: `v.mul(M)` → `(0,1,0,0)` = `M.transform(v)`;
  `v.mulTranspose(M)` → `(0,−1,0,0)` (row form = inverse rotation);
  `(1,2,3,1).mul(translation(5,6,7))` → `(6,8,10,1)` (translation column applied ⇒ column semantics);
  `(1,2,3,0).mul(translation)` → `(1,2,3,0)` (w=0 drops the translation column, as the docs state).
  The docs' DANGER note (do NOT "fix" to `mulTranspose`) is correct. Mod call sites re-opened:
  `render/FrontClipping.java` — `new Vector4f(...,0f).mul(viewRotation)` at `:147` and `:182`
  (docs cite `:181`, the constructor line — off-by-one nit only). The quoted contrary text really
  exists at `IP_DEVIATIONS_ANALYSIS.md:88` ("v * M (row-vector) — WRONG") and really is wrong.
- **R2.2 — FrustumCuller corner chain (MAP §1.9, INV §2.9).**
  `FrustumCuller.getRectPortalFourVerticesCounterClockwise(UnilateralPortalState)` defined at
  `FrustumCuller.java:143` (static; `// 2 1 / 3 0` comment at `:141-142`; ±halfWidth/±halfHeight via
  `thisSideState.transformLocalToGlobal`, `:146-153`); called at `:179` with
  `portal.getThisSideState()`; each corner `portal.transformPoint(v[i]).subtract(cameraPos)` at
  `:183-188`; `instanceof Mirror` order-flip at `:190-195`; planes built by
  `getFrustumPlanesFromFourVerticesCounterClockwise` `:156` (W=0, camera-origin, `:166-172`);
  `getFlatPortalInnerFrustumCullingFunc` at `:176`. Grep: `getRectPortalFourVerticesCounterClockwise`
  exists ONLY in `FrustumCuller.java` (:143, :179, :208) — not in `Portal.java`. All exact.
- **R2.3 — ViewAreaRenderer mesh chain is shape-polymorphic (MAP §1.3, INV §2.3).**
  `buildPortalViewAreaTrianglesBuffer` at `ViewAreaRenderer.java:120`, invoked at `:94`; calls
  `portal.renderViewAreaMesh(originRelativeToCamera, vertexOutput)` at `:142`;
  `Portal.renderViewAreaMesh` at `Portal.java:877` (Mirror offset special-case `:880-887`) delegates
  to `getPortalShape().renderViewAreaMesh(...)` at `:889-894`;
  `RectangularPortalShape.renderViewAreaMesh` at `RectangularPortalShape.java:143-158` builds
  `localXAxis = getAxisW()·w/2`, `localYAxis = getAxisH()·h/2` → `ViewAreaRenderer.outputFullQuad`;
  overloads at `BoxPortalShape.java:189` and `SpecialFlatPortalShape.java:156` (plus the base at
  `PortalShape.java:100`). Grep: `getFourVerticesLocal` has zero hits in `ViewAreaRenderer.java`;
  its consumer is `Portal.getOuterFrustumCullingVertices` (`Portal.java:1396-1400`, returns
  `getFourVerticesLocalCullable(0)` at `:1399`) — docs' `:1395-1399` cite is right.
- **R2.4 — Mod-side geometry anchors.** `PortalShapeRenderer.java:515-541`: axis==X → XY quad at
  `z = origin.z + 0.5`; axis==Z → ZY quad at `x = origin.x + 0.5` — exactly as INV §2.3 states.
  Dest camera at `PortalContextSwitch.java:1041-1056`: `PortalTransform.transformPoint` +
  `yawOffset = (srcAxis != destAxis) ? (srcAxis==Z ? +90 : −90) : 0` — exactly as both docs state.

### IP behavior / flow claims

- **R2.5 — bobView machinery (MAP §2.1, INV §3.2 row).** `MixinGameRenderer.java`: hand flag set
  `:202-210`; three `@ModifyArg`s on `PoseStack.translate(FFF)` indices 0/1/2 inside `bobView` at
  `:213-253`, each `f * RenderStates.getViewBobbingOffsetMultiplier()` unless rendering the hand.
  `RenderStates.getViewBobbingOffsetMultiplier()` `:184-196`: returns 1 if `!IPGlobal.viewBobbingReduce`,
  0 if view bobbing disabled in WorldRenderInfo, else `viewBobFactor * PortalRendering.getExtraModelViewScaling()`.
  `updateViewBobbingFactor` `:158-182`: 0 when `minPortalDistance < 1`, `minPortalDistance − 1` in
  the 1..2 band, 1 otherwise (portal search radius 16); `setViewBobFactor` `:198-205` = immediate
  down / `lerp(0.1)` up (fast-down/slow-up as stated); driven from `updatePreRenderInfo` at `:114`.
  `IPGlobal.viewBobbingReduce = true` at `IPGlobal.java:121`. Grep `bobHurt` across ALL of
  `qouteall/`: **zero matches** — "IP never touches bobHurt" holds repo-wide, not just in that mixin.
- **R2.6 — TransformationManager supersedes CameraTransitionHandler (MAP §1.17).**
  `managePlayerRotationAndChangeGravity(Portal)` at `TransformationManager.java:135-224` (docs say
  `:135-225`, off by one on the close brace); whole body gated `if (portal.getRotation() != null)`
  at `:138`; pitch/yaw recompute `:143-203`; "keep immediate final rotation unchanged, to keep
  teleportation seamless" comment at `:205`; animation starts only when
  `newAnimationDelta.getRotatingAngleDegrees() > 0.1` at `:215-219`; `processTransformation`
  `:88-102`; `getAnimationProgress` `:108-126`. Confirmed rotation-only — no position lerp anywhere
  in the class, so the CameraTransitionHandler DELETE rationale stands.
- **R2.7 — IP pre-render block + teleport anchors (MAP §2.2/§2.3).** `MixinGameRenderer`
  `@Inject(method="render", at=HEAD)` at `:70`; inside: `RenderStates.updatePreRenderInfo` `:87`,
  `ClientPortalAnimationManagement.update()` `:91`, `ClientTeleportationManager.manageTeleportation(false)`
  `:92`, `earlyRemoteUpload` (gated `IPCGlobal.earlyRemoteUpload`) `:94-96`. Per-tick
  `manageTeleportation(true)` at `MixinMinecraft.java:137`; decl at
  `ClientTeleportationManager.java:121`. 26.2 frame-order fact re-checked:
  `GameRenderer.update(DeltaTracker)` does `this.mainCamera.update(deltaTracker)`
  (`mc262-ref GameRenderer.java:372-377`) with `extract` after — update-HEAD is before the frame
  camera is positioned, validating `GameRendererFrameCrossingMixin` placement.
- **R2.8 — A1 renderer-mode architecture (MAP §4 A1).** `IPGlobal.RenderMode {normal, compatibility,
  debug, none}` at `IPGlobal.java:147-152`; `IPCGlobal.java:16-20` holds `renderer`,
  `rendererUsingStencil`, `rendererUsingFrameBuffer`, `rendererDummy`, `rendererDebug`; both live
  renderers constructed + stencil set active at `IPModMainClient.java:81-84`;
  `PortalRenderer.switchToCorrectRenderer()` at `PortalRenderer.java:310-348` switches per mode
  (normal→stencil, compatibility→`rendererUsingFrameBuffer`, debug, none→dummy; called per frame
  from `MixinGameRenderer.java:113`); `RendererUsingFrameBuffer` class at `:23` with
  `SecondaryFrameBuffer` field (`SecondaryFrameBuffer.java:9`). A1's premise is solid.
- **R2.9 — RendererUsingStencil cites (MAP §1.1).** extends PortalRenderer `:32`;
  `renderPortals(Matrix4f)` `:73-79`; `prepareRendering` w/ `IPPortingLibCompat.get/setIsStencilEnabled`
  `:87-89`; `doRenderPortal` `:119`; `clearDepthOfThePortalViewArea` `:199`;
  `restoreDepthOfPortalViewArea` `:227`; `clampStencilValue` `:249`; `setStencilLimitation(int)`
  `:286` (GL_EQUAL/value/0xFF). Mod counterpart `StencilPortalRenderer.setStencilLimitation`
  `:480-484` = EQUAL(layer)/KEEP/mask 0x00 — semantics match as claimed.
- **R2.10 — MyGameRenderer cites (MAP §1.2/§1.10).** class decl `MyGameRenderer.java:52`;
  `acquireRenderBuffersObject` `:77`; `returnRenderBuffersObject` `:91`; `renderWorldNew` `:96`;
  `switchAndRenderTheWorld` `:114`; acquire used at `:190-191` **gated by
  `IPGlobal.useSecondaryEntityVertexConsumer`** (the Round-1 note the PORT-FORWARD row should carry);
  returned at `:264`.
- **R2.11 — context_management + misc IP class cites.** `PortalRendering` class `:28`,
  `pushPortalLayer` `:33`, `getPortalLayer` `:58`, `isRendering` `:62`; `WorldRenderInfo:23`;
  `FogRendererContext:21`; `CloudContext:17`; `StaticFieldsSwappingManager<Context>:18`;
  `RenderStates` class `:41`; `TransformationManager` class `:33`; `MyRenderHelper` class `:61`,
  `renderScreenTriangle` `:216`, `lateUpdateLight` `:441`, `earlyRemoteUpload` `:458`;
  `PortalEntityRenderer extends EntityRenderer<Portal>` `:19`; `CrossPortalEntityRenderer` `:41`;
  `ForceMainThreadRebuild.onPreRender()` called at `RenderStates.java:121`.
- **R2.12 — FrontClipping IP cites (MAP §1.6).** class `:22`; `disableClipping` `:31`;
  `updateInnerClipping(PoseStack)` `:49` / `(Matrix4f)` `:54`; `setupInnerClipping(Plane, Matrix4f,
  double)` `:66-67`; `setupOuterClipping(PoseStack, Portal)` `:122`; plane getters `:167`/`:171`.
- **R2.13 — DimensionRenderHelper / ShaderCodeTransformation IP cites (MAP §1.7/§1.8).**
  `DimensionRenderHelper` class `:9`; `lightmapTexture` field + main-dim-reuse ctor `:13-27`;
  `tick()` guard `:29-33`; `cleanUp()` `:35-39`. `ShaderCodeTransformation` class `:15`; YAML config
  load `:59`; `transform(CompiledShader.Type, String, String)` `:72`.
- **R2.14 — VisibleSectionDiscovery (MAP §1.5, A3).** IP class `:35`; `discoverVisibleSections`
  `:45`; `skipFrustumTest` at `:143` (param) and `:160` (seed-skip use). Re-ran grep
  `compile|rebuild|dirty` (case-insensitive) on the IP file: **0 matches** — the mod's folded-in
  budgeted compile scheduling (mod `VisibleSectionDiscovery.java:170` decl; scheduling block
  `:266-279` with one-shot `schedSet`, `compileAsync`, budget check) is a non-IP extension, as A3 frames it.
- **R2.15 — "IP never does oblique clipping" (MAP §1.18).** Re-ran case-insensitive grep for
  `oblique` across all of `qouteall/`: **zero matches.** The mod-side finding note exists at
  `PortalContextSwitch.java:2620-2627` as cited.
- **R2.16 — Duck interfaces (MAP §3 header).** `IEFrameBuffer` `:3-6`
  (`ip_getIsStencilBufferEnabled`/`ip_setIsStencilBufferEnabledAndReload`); `IEGameRenderer` `:7-14`
  (`ip_setLightmapTextureManager` at `:8`, plus `ip_getDoRenderHand`, `ip_setCamera`,
  `ip_setIsRenderingPanorama`).

### 26.2 "exists/GONE" verdicts (re-opened / rename-hunted)

- **R2.17 — Direct-draw substrate (INV items 1/2/7, §5).** `prepareChunkRenders(Matrix4fc)` public
  at `mc262-ref LevelRenderer.java:509`; `compileSections(CameraRenderState)` **private** at `:608`;
  `renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, chunkLayerSampler)` inside the main pass at
  `:438`; 8-arg `render(GraphicsResourceAllocator, DeltaTracker, boolean, CameraRenderState,
  Matrix4fc, GpuBufferSlice, Vector4f, boolean)` at `:156-165`.
  `ChunkSectionLayerGroup.outputTarget()` (`chunk/ChunkSectionLayerGroup.java:31-38`):
  `TRANSLUCENT → levelRenderer.translucentTarget()`, default (incl. OPAQUE) →
  `gameRenderer.mainRenderTarget()` — INV item 2 confirmed.
- **R2.18 — Stencil-persistence substrate (INV items 1/5).** `GlCommandEncoder.applyPipelineState`
  region (`:771+`): handles only depthTest/depthMask/polygonOffset from `DepthStencilState`; no
  `glStencil*` anywhere in the depth-state handling. `DepthStencilState` is the depth-only record
  `(CompareOp depthTest, boolean writeDepth, float, float)` with `DEFAULT = GREATER_THAN_OR_EQUAL`
  (`com/mojang/blaze3d/pipeline/DepthStencilState.java:8-9`) — also re-confirms reversed-Z.
- **R2.19 — GONE verdicts rename-hunted.** `cullTerrain`: repo-wide grep of `mc262-ref` — zero
  files. `LevelRenderer.update(Camera...)`: zero matches. The replacement story checks out:
  `LevelExtractor.extract` calls `this.applyFrustum(cullFrustum)` at
  `extract/LevelExtractor.java:130`, private `applyFrustum(Frustum)` at `:373` with wrong-thread
  guard `:374-376`. `GlTextureView.createFbo`: `GlTextureView.java` still EXISTS in 26.2 but has no
  `createFbo`; the only `createFbo` in the GL backend is private `FrameBufferCache.createFbo`
  (`FrameBufferCache.java:21`, cached call at `:18`) — precisely the docs' "single 26.2 FBO factory"
  unification story, so `GlTextureViewMixin` DELETE and `stencil/RenderTargetMixin` KEEP both stand.
- **R2.20 — `ViewArea.getRenderSection(long)` protected** at
  `mc262-ref net/minecraft/client/renderer/ViewArea.java:91` — matches the revised docs (the
  Round-1 V6 correction is properly folded in; INV §5 now says protected).

### Mod-side anchors, wiring, completeness

- **R2.21 — Mod anchors.** `STENCIL_DIRECT = true` (`StencilPortalRenderer.java:41`);
  `MAX_PORTALS_RENDERED = 4` (`:54`); STENCIL_DIRECT upkeep block (staged-upload flush, adoption
  prune, bridge repaint pump via `needsFrustumUpdate.set(true)`) at `:190-215`;
  `isRenderingPortal` (`PortalContextSwitch.java:79`); `withSwitchedWorld` decl (`:569`);
  `GameRendererMixin` render-TAIL block `:33-46` with `lateUpdateSecondaryLight()` at `:42` +
  `endSecondaryRenderFrames` + `endFramePooled` (and its javadoc citing IP `lateUpdateLight` —
  consistent with IP's actual anchor, the post-`renderLevel` inject at `MixinGameRenderer.java:136-141`
  gated by `IPCGlobal.lateClientLightUpdate`).
- **R2.22 — MainProjectionBobMixin (MAP §2.1).** Two `@Redirect`s in `renderLevel` targeting
  `GameRenderer.bobHurt(CameraRenderState, PoseStack)` (target string at `:39`) and
  `GameRenderer.bobView(CameraRenderState, PoseStack)` (target string at `:52`), both empty bodies
  (`:35-59`) — unconditional no-op, proving the 26.2 signatures, exactly as MAP claims.
- **R2.23 — Registration + wiring (INV §6).** `seamlessportals-common.mixins.json`: grep for the
  five contested names hits ONLY `client.LevelRendererCullTerrainMixin` (line 37) —
  `GlTextureViewMixin`/`MinecraftRenderTargetMixin`/`LevelRendererDiagMixin`/`GameRendererObliqueClipMixin`
  are unregistered as stated. `SODIUM_INCOMPATIBLE_MIXINS = Set.of("...client.LevelRendererCullTerrainMixin")`
  at `mixin/SeamlessMixinConfigPlugin.java:41-43`. Fabric hook
  `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> StencilPortalRenderer.renderPortals())`
  at `SeamlessPortalsClientFabric.java:26-28`.
- **R2.24 — Completeness + tally arithmetic.** `common/.../render/` = exactly the 19 inventoried
  classes (fresh `ls`, name-by-name match). `mixin/client/` = 56 `.java` files (fresh `find`);
  reconciles as 44 audited + 10 named exclusions (`HandleRespawnMixin`, `LocalPlayerMixin`,
  `ChunkPacketGuardMixin`, 6× `ClientPacketListener*` — Accessor/AddEntityAdopt/ForgetGuard/
  LocalPlayerFallback/PositionInvoker/TeleportTolerance — and `NetherPortalUninteractableMixin`)
  + the 2 V7 files, both of which now carry real citations that check out
  (`ClientLevelChunkSourceAccessor.java:15-21` accessor + javadoc; `LivingEntitySprintCancelDiagMixin.java:24-49`
  setSprinting HEAD diagnostic). MAP §6 tally re-added by hand from the rows: mixins 34 KEEP + 1
  REPLACE + 9 DELETE = 44 (per-section 4/6/8/4/12 KEEP, 1/2/3/0/3 DELETE, 1 REPLACE); classes
  7 KEEP + 6 PORT-FORWARD + 3 REPLACE + 3 DELETE = 19. Internally consistent.

## Round-2 nits (not refutations — all within or adjacent to the cited ranges)

- `render/FrontClipping.java`: second `Vector4f.mul` is at `:182` (docs cite `:181`, the
  constructor line).
- `TransformationManager.managePlayerRotationAndChangeGravity` ends at `:224` (MAP cites `:135-225`).
- INV §3.2 cites the bob trio as `MixinGameRenderer.java:212-253`; `:212` is the comment line, the
  first annotation is `:213` (MAP's cite).
- `mc262-ref` `LevelExtractor` remains in the `renderer/extract/` subpackage (Round-1 note; both
  docs still shorthand it as `renderer/` — line numbers correct).
