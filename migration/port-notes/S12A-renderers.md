# S12-A — the concrete renderer family (U10 first half): stencil + FBO renderers, Iris shells, R3 client-mixin installs

**Stage S12-A of the entity-portal migration.** The concrete `PortalRenderer` family the renderMode
config dispatch (D5/A1) selects — abstract base + all five concrete renderers + the never-loaded Iris
compile-shells — plus the S11 render-foundation's explicit S12 install handoffs (the R4 `ImmPtlViewArea`
redirect, the four R3 per-entity clip mixins, the `IPConfig` C4-rider field, the S12 forward-ref ducks).
**The CUTOVER_SPEC §2 (SS2) 16-row reversed-Z checklist is EXECUTED here** — every depth/stencil constant
carries an inline `R5 Row N` derivation citing its row. ZERO IP-logic deviation; every GONE-26.2 API
translated per the api-maps; every sign SIGN-derived against a CUTOVER_SPEC row. All files held/inert until
S13 (`ip_scc_closed` filter, held path `render/**` + `mixin/**` + `platform_specific/**`); ALL mixins land
UNREGISTERED (no `mixins.json` edit); shipping `:common:compileJava` GREEN.

**Citation conventions:** `IP:` = 1.21.3 source (`ImmersivePortalsMod/src/main/java/qouteall`), `26.2:` =
`mc262-ref`, `MOD:` = the live proven `com.warwa.seamlessportals` render substrate (KEEP), `MOD-qouteall:`
= the held `qouteall.imm_ptl.core.*` ports authored in this migration.

---

## 0. Headline — the pre-commit MAJOR (P1), FIXED before this note was written

Both adversarial verifiers flagged ONE major pre-commit defect: `MyRenderHelper.renderScreenTriangle` was
first re-expressed as a SINGLE-pipeline draw through `getPortalAreaRenderType(true,false,false)` =
`DepthStencilState(GREATER_THAN_OR_EQUAL, writeDepth=false)` + full color write. That nullified R5 Rows
7/15/16 for its **real** S13-live consumers — `RendererUsingStencil` (the cutover-core stencil driver,
`renderMode=normal` DEFAULT), NOT the never-loaded Iris shells a false fragment claim had blamed:

- **Row 7** `clearDepthOfThePortalViewArea`: needs ALWAYS-pass + depth WRITE + color OFF. With
  `writeDepth=false` the caller's `glDepthRange(0,0)` FAR clear wrote NOTHING to depth (the sole raw-constant
  flip in the class was dead code), and the pipeline's color-on splatted white into the opening.
- **Row 15** `clampStencilValue`: needs depth-test DISABLED + color OFF. The pipeline re-enabled GEQUAL
  testing, so the `z=0` triangle FAILED wherever the just-restored portal-plane depth `> 0.5` (portal near
  camera = the common case), leaving stencil values unclamped exactly where recursion needs them.
- **Row 16** `replaceFrameBufferClearing`: the fog fill became GEQUAL-gated on the Row-7 clear that never
  landed — a cascade.

**FIX (parent ruling 2 / Verifier-1 prescription):** `renderScreenTriangle` now takes a
`MyRenderHelper.ScreenTrianglePurpose` and draws the proven stencil-gated full-screen screenquad
(`core/screenquad` — positions from `gl_VertexID`, no matrices, so the NDC-vs-world-projection hazard the
old POSITION_COLOR mesh carried never arises), selecting a per-purpose pipeline:

| Purpose | R5 Row | Pipeline | Semantics |
|---|---|---|---|
| `DEPTH_CLEAR` | 7 | `PortalRenderTypes.portalScreenDepthClear()` (CONSUMED) | ALWAYS_PASS + depth WRITE + `ColorTargetState.WRITE_NONE` — so the caller's `glDepthRange(0,0)` FAR value LANDS (`applyPipelineState` touches neither `glDepthRange` nor stencil) |
| `STENCIL_ONLY` | 15 | `SCREEN_TRIANGLE_STENCIL_ONLY` (NEW, added in `RendererUsingStencil`) | depth test OFF + `WRITE_NONE` — neither substrate pipeline fits (`portalScreenDepthClear` writes depth, `portalCompositeBlit` writes color) |
| `COLOR_FILL` | 16 | `PortalRenderTypes.portalCompositeBlit()` (CONSUMED) | depth-off + full color, fog color on a 1×1 texture (mirrors `MOD:StencilPortalRenderer.ensureScreenFillTexture`) |

All five callers pass intent. This mirrors the runtime-proven analogs
`MOD:StencilPortalRenderer.drawScreenDepthClearStencilGated` / `drawScreenFillStencilGated`.

**Doc corrections shipped with the fix** (the defect was masked by FALSE consumer prose): the
`MyRenderHelper.renderScreenTriangle` javadoc + the S12-A header comment ("Iris shells only" →
`RendererUsingStencil` is the PRIMARY consumer); and the S12A-fbo fragment's false "all callers are Iris
shells" claim (§4 item 3) — corrected in this note's §5.

---

## 1. Files authored (git working tree)

**Slice A — the stencil renderer core** (verbatim held paths, `render/renderer/`):

| File | IP source | Disposition |
|---|---|---|
| `render/renderer/PortalRenderer.java` | `IP:.../renderer/PortalRenderer.java` | NEW — abstract base + `switchToCorrectRenderer` 4-mode dispatch + `PORTAL_RENDERING_PREDICATE` |
| `render/renderer/RendererUsingStencil.java` | `IP:.../renderer/RendererUsingStencil.java` | NEW — renderMode=normal DEFAULT; **the R5 reversed-Z heart** |

**Slice B — the FBO renderer family + Iris shells + `MyRenderHelper` blit family:**

| File | IP source | Disposition |
|---|---|---|
| `render/SecondaryFrameBuffer.java` | `IP:render/SecondaryFrameBuffer.java` | NEW — `TextureTarget` re-expressed onto 26.2 |
| `render/renderer/RendererUsingFrameBuffer.java` | `IP:.../RendererUsingFrameBuffer.java` | NEW — renderMode=compatibility (A1, reachable) |
| `render/renderer/RendererDummy.java` | `IP:.../RendererDummy.java` | NEW — renderMode=none |
| `render/renderer/RendererDebug.java` | `IP:.../RendererDebug.java` | NEW — renderMode=debug |
| `render/MyRenderHelper.java` (EDIT) | `IP:render/MyRenderHelper.java:172-320` | S11-B DEFERRED blit-draw family authored here, incl. the `renderScreenTriangle` per-purpose re-expression (§0) |

Iris compile-shells under `compat/iris_compatibility/` (**all NEVER LOAD on 26.2** — no Iris build,
`IrisInterface.isIrisPresent()`=false forever; `PortalRenderer.switchToCorrectRenderer` touches their
`.instance`/`.debugModeInstance` only inside the never-taken `isIrisPresent()` branch):

| File | Disposition |
|---|---|
| `IEIrisNewWorldRenderingPipeline.java` | VERBATIM (self-compiling duck) |
| `IEIrisShadowRenderTargets.java` | VERBATIM (empty marker) |
| `ShadowMapSwapper.java` | VERBATIM (body entirely commented, as IP) |
| `IPIrisHelper.java` | raw-FBO-id blits → 26.2 `copyDepthFrom`/`blitAndBlendToTexture` analogs |
| `IrisCompatibilityPortalRenderer.java` | shell — `.instance`/`.debugModeInstance` |
| `IrisPortalRenderer.java` | shell — raw-FBO-id blit blocks COMMENTED (no 26.2 analog) |
| `ExperimentalIrisPortalRenderer.java` | shell — compiled against the extended iris stubs; draws `renderScreenTriangle` DEPTH_CLEAR+STENCIL_ONLY (never-run) |

F21 iris stub classpath extensions (`common/src/ipStubs/java/net/irisshaders/iris/` — the "iris half
consumed here"):

| Stub | Added |
|---|---|
| `pipeline/PipelineManager.java` (EDIT) | `Optional<WorldRenderingPipeline> getPipeline()` (`:14-15`, returns `Optional.empty()`) |
| `pipeline/IrisRenderingPipeline.java` (NEW) | `implements WorldRenderingPipeline`; `public boolean isBeforeTranslucent` |
| `uniforms/SystemTimeUniforms.java` (NEW) | `static COUNTER` with `beginFrame()` |

**Slice C — the S11 install handoffs** (mixins UNREGISTERED, `platform_specific`/`ducks` edits):

| File | Δ | Task | Disposition |
|---|---|---|---|
| `mixin/client/render/MixinLevelRenderer.java` | NEW | 1 | R4 `ImmPtlViewArea` install `@Redirect` of `new ViewArea` in `invalidateCompiledGeometry` |
| `ducks/IEEntityRenderState.java` | NEW | 2 | R3 per-entity clip-context tag duck interface |
| `mixin/client/render/MixinEntityRenderState.java` | NEW | 2 | tag-duck HOLDER (`@Unique` fields, implements the duck) |
| `mixin/client/render/MixinLevelExtractor.java` | NEW | 2 | tag-duck SETTER (`@WrapOperation` on `extractEntity`) |
| `mixin/client/render/MixinLevelRenderer_CrossPortalEntity.java` | NEW | 2 | `submitEntities` HEAD/TAIL anchors + per-entity submit `@WrapOperation` |
| `mixin/client/render/MixinPreparedFrame.java` | NEW | 2 | `executePhase` HEAD/RETURN clip bracket (Mechanism A) |
| `mixin/client/render/IERenderSystem.java` | NEW | 5 | VERBATIM IP duck — `RenderSystem.modelViewStack` accessor |
| `mixin/client/render/IESectionRenderDispatcher.java` | NEW | 5 | VERBATIM IP duck — `SectionRenderDispatcher.fixedBuffers` accessor |
| `platform_specific/IPConfig.java` | EDIT | 3 | `crossPortalEntityClipMechanism` field + null-guard + assignment + config-screen entry |
| `buildSrc/.../IpHeldPaths.groovy` | EDIT | — | hold `ducks/IEEntityRenderState.java` by name (PARENT-SANCTIONED, §7) |

**No `com.warwa` edit; no `.accesswidener`/NeoForge-AT edit; no `mixins.json` edit.** AW/AT pairs across
all three slices: **ZERO** (every consumed vanilla/substrate member is public non-final, a public getter,
or reached via existing `com.warwa` accessors). `CoreShadersAccessor` **NOT landed** (retired — §3, task 5).
The renderMode enum (`IPGlobal.RenderMode`) + the `IPCGlobal` renderer holder slots were landed earlier;
this stage supplies the concrete dispatch TARGETS, closing D5/A1.

---

## 2. The R5 reversed-Z 16-row EXECUTION table (row → `RendererUsingStencil.java` line → constant WRITTEN)

**26.2 ground truth (derivation basis):** depth CLEAR `0.0` = FAR; default depth COMPARE
`GREATER_THAN_OR_EQUAL`; `glDepthRange` FAR = `(0,0)` / NEAR = `(1,1)` / default `(0,1)`; `GL_ALWAYS` + ALL
stencil ops/funcs direction-INDEPENDENT. **The ONE raw-constant flip that lands in this class is Row 7.**
Line numbers are the CURRENT post-fix positions (the P1 fix shifted them ~9 lines from the pre-fix fragment
draft — re-read this run).

| Row | IP op (site) | `RendererUsingStencil.java` line | Constant WRITTEN | Verdict |
|---|---|---|---|---|
| 1 | `glClearStencil(0)`+`glClear(GL_STENCIL_BUFFER_BIT)` (`prepareRendering :98-99`) | :179, :180 | `glClearStencil(0)` / `glClear(GL_STENCIL_BUFFER_BIT)` | **UNCHANGED** — stencil clear only; reversed-Z depth buffer preserved |
| 2 | `_enableDepthTest()`+`glEnable(GL_STENCIL_TEST)` (`:101-102`) | :184 (+ doPortalRendering enable/mask :117-118) | `GlStateManager._enableDepthTest()` | **UNCHANGED** — toggles, not comparisons |
| 3 | `glStencilFunc(GL_EQUAL, outerLayer, 0xFF)` (`renderPortalViewAreaToStencil :177`) | :264 | `glStencilFunc(GL_EQUAL, outerPortalStencilValue, 0xFF)` | **UNCHANGED** — stencil comparison |
| 4 | view-area stencil-write depth-pass op + IMPLICIT depth compare (`:180` + mesh draw) | :276 (op) + :287 (`renderPortalArea` draw) | `glStencilOp(GL_KEEP,GL_KEEP,GL_INCR)` | **op UNCHANGED**; the LEQUAL→GEQUAL compare flip is NOT a constant here — it lives in the mesh PIPELINE (`MyRenderHelper.getPortalAreaRenderType` → `DepthStencilState(GREATER_THAN_OR_EQUAL, writeDepth)`, authored S11-B). Write mask follows IP's `writeDepth = doModifyDepth && !fuseView` (CUTOVER_SPEC Row 4 Fable-corrected) |
| 5 | `glStencilMask(0xFF)` (`:185`) | :282 | `glStencilMask(0xFF)` | **UNCHANGED** |
| 6 | `glDepthFunc(GL_ALWAYS)` (`clearDepthOfThePortalViewArea :214`) | :312 | `glDepthFunc(GL_ALWAYS)` | **UNCHANGED (ALWAYS)** — passes regardless of direction |
| **7** | **`glDepthRange(1, 1)`** (`clearDepthOfThePortalViewArea :217`) | **:321** | **`glDepthRange(0, 0)`** | **FLIP** — IP wrote window 1.0 = FAR (1.21.3); 26.2 reversed-Z FAR = 0.0. Pushes the opening to FAR so dest terrain, drawn next under GEQUAL, ALL passes. Proven: `MOD:StencilPortalRenderer.java:401`. **The only raw-constant flip in this class.** The paired `renderScreenTriangle` (:327) now passes `DEPTH_CLEAR` (`portalScreenDepthClear` = ALWAYS_PASS + depth WRITE + color OFF), so this FAR value ACTUALLY LANDS (`applyPipelineState` never touches `glDepthRange`). **[FIXED S12-A P1 — see §0.]** |
| 8 | restore `glDepthFunc(originalDepthFunc)` (`:223`) | :332 | `glDepthFunc(originalDepthFunc)` | **UNCHANGED** — save/restore of queried prior func |
| 9 | restore `glDepthRange(0, 1)` (`:224`) | :335 | `glDepthRange(0, 1)` | **UNCHANGED** — default full NDC→window mapping, direction-independent. Proven: `MOD:StencilPortalRenderer.java:403` restores (0,1) NOT (0,0) |
| 10 | `setStencilLimitation` (`:288-291`) | :422, :425 (invoked from restoreDepthOfPortalViewArea + setStencilStateForWorldRendering) | `glStencilFunc(GL_EQUAL, stencilValue, 0xFF)` / `glStencilOp(GL_KEEP,GL_KEEP,GL_KEEP)` | **UNCHANGED** — stencil |
| 11 | `glDepthFunc(GL_ALWAYS)` (`restoreDepthOfPortalViewArea :235`) | :348 | `glDepthFunc(GL_ALWAYS)` | **UNCHANGED (ALWAYS)** |
| 12 | re-render view-area mesh at REAL projected depth (`:237-244`) | :359 (`renderPortalArea(...,false,true,true)`) | *no explicit depth constant* (mesh at real projected depth) | **IP VERBATIM** — keeps IP op #12 (exact projected depth), NOT the mod's LIVE block-era flat-NEAR-shield `glDepthRange(1,1)` (`MOD:StencilPortalRenderer.java:472/408`), the OPPOSITE direction from the Row-7 FAR clear. **S13 WATCH ITEM** (S11-B note §8): the GEQUAL mesh pipeline clobbers the raw `glDepthFunc(GL_ALWAYS)` bracket — keeping exact-projected-depth at S13 needs an ALWAYS_PASS pipeline variant (not authored). Recorded, not fixed (inert until S13). |
| 13 | restore `glDepthFunc(originalDepthFunc)` (`:246`) | :369 | `glDepthFunc(originalDepthFunc)` | **UNCHANGED** |
| 14 | `clampStencilValue` (`:258-261`) | :384, :387 | `glStencilFunc(GL_LESS, maximumValue, 0xFF)` / `glStencilOp(GL_KEEP,GL_REPLACE,GL_REPLACE)` | **UNCHANGED** — `GL_LESS` here is `ref < stencil` (STENCIL compare), NOT depth — does not flip |
| 15 | `clampStencilValue` depth-off + color-off + `renderScreenTriangle` + restore (`:264-277`) | :392, :395, :397, :403, :405, :409 | `glDepthMask(false)` / `glColorMask(false×4)` / `_disableDepthTest()` → draw → restore | **UNCHANGED** — stencil-only pass, depth masked off + test disabled + color off. The `renderScreenTriangle` (:403) now passes `STENCIL_ONLY` (depth-off + `WRITE_NONE` screenquad), so the depth test IS disabled AND color IS masked at the draw; the caller's raw `glStencilOp(KEEP,REPLACE,REPLACE)` clamp survives (stencil untouched by `applyPipelineState`). **[FIXED S12-A P1 — see §0.]** |
| 16 | `replaceFrameBufferClearing` (`:40-43`) | :90, :94, :98 | `_depthMask(false)` / `renderScreenTriangle(fogColor)` / `_depthMask(true)` | **UNCHANGED** — color-only dest sky/fog fill, no depth compare (fog COLOR source = R9). The `renderScreenTriangle(fogColor)` (:94) now passes `COLOR_FILL` (`portalCompositeBlit`, depth-OFF + full color), so the fill is depth-INDEPENDENT (never reversed-Z GEQUAL-gated by leftover portal-plane depth). **[FIXED S12-A P1 — see §0.]** |

**Not a numbered row:** `myFinishRendering` `glStencilFunc(GL_ALWAYS, 2333, 0xFF)` (:198) — end-of-render
stencil-sentinel reset, direction-independent; UNCHANGED. `2333` is IP's verbatim sentinel.

**Row-2.2 depth-clamp** (CUTOVER_SPEC §2.2): NOT touched in these classes — the raw-GL `GL32.GL_DEPTH_CLAMP`
toggle lives in `ViewAreaRenderer`/`CHelper` (S11-B), verbatim.

**CUTOVER_SPEC corrections made this stage:** the only correction is the P1 consumer-prose fix (§0/§5) — the
16 row VERDICTS themselves are UNCHANGED from the twice-Fable-verified §2.1 checklist; this stage transplants
those signed-off verdicts (no new sign derivation). The one CUTOVER_SPEC-side note carried forward: Row 6
"Port to `CompareOp.ALWAYS_PASS`" is satisfied not by a constant in `RendererUsingStencil` (which keeps IP's
raw `glDepthFunc(GL_ALWAYS)` at :312) but by the `DEPTH_CLEAR` purpose pipeline's `ALWAYS_PASS` at the
`renderScreenTriangle` draw — the two together realize the ALWAYS-pass depth clear.

---

## 3. Substrate consumption record (which live KEEP pieces each renderer CONSUMES — never duplicates)

The mod's proven stencil substrate (`GlBackendMixin`/`GlConstMixin`/`RenderTargetMixin`/`GlStateManagerMixin`
+ `StencilState` + `StencilPortalRenderer` + `PortalContextSwitch` + `PortalRenderTypes`) is KEEP (always-on,
both flag states). The held IP renderer classes CONSUME it:

| IP renderer class | KEEP substrate consumed |
|---|---|
| `RendererUsingStencil` | `StencilState.gameFboId` (raw-GL FBO bind for stencil ops); `GlStateManager._enableDepthTest/_depthMask/_disableDepthTest`; raw `GL11/GL30` stencil/depth-range idiom; `MyRenderHelper.getPortalAreaRenderType` (GEQUAL mesh pipeline, S11-B) via `ViewAreaRenderer.renderPortalArea`; `MyRenderHelper.renderScreenTriangle` (per-purpose, §0); `PortalRenderInfo.renderAndDecideVisibility`; `FrontClipping.updateInnerClipping` |
| `MyRenderHelper.renderScreenTriangle` (all 3 purposes) | `PortalRenderTypes.portalScreenDepthClear()` (Row 7) + `PortalRenderTypes.portalCompositeBlit()` (Row 16); `core/screenquad` proven full-screen draw; a NEW `SCREEN_TRIANGLE_STENCIL_ONLY` pipeline (Row 15, added because neither substrate pipeline fits) |
| `MyRenderHelper.drawScreenFrameBuffer` / `drawPortalAreaWithFramebuffer` | `PortalRenderTypes.portalCompositeBlit()` (depth-off composite) + vanilla `RenderTarget.blitAndBlendToTexture` |
| `RendererUsingFrameBuffer` | `SecondaryFrameBuffer` (TextureTarget) + `drawPortalAreaWithFramebuffer` (the `portalCompositeBlit` composite) |
| `PortalRenderer` (base) | `getCurrentProjectionMatrix()` helper reading `gameRenderState().levelRenderState.cameraRenderState.projectionMatrix` — the proven `MOD:StencilPortalRenderer.buildMainFrustum:66-68` idiom; `com.warwa.seamlessportals.event.{Event,EventFactory}` (F12/B1 loader-seam) for `PORTAL_RENDERING_PREDICATE` |

The mod-additive `armCompileScheduling` mechanism (A3, no IP analog) is NOT inserted into the verbatim IP
shell — it is DOCUMENTED at the dest-pass seam `PortalRenderer.renderPortalContent` (right before
`invokeWorldRendering`, `newWorld`=destLevel in scope): the S13 driver must call
`VisibleSectionDiscovery.armCompileScheduling(newWorld, sut, cache, schedSet, budgetNs)` before the
renderLevel-internal discovery (else 26.2 strands sections `dirty=false+UNCOMPILED` — memories
`ow-holes-consumed-compile-queue` / `walking-limbo-seed-overclaim`); it auto-disarms in the discovery
`finally`. The arm CALL lives in the driver core (wired at S13 with `sut`/`cache`/`schedSet`/`budgetNs`).

### 26.2 GONE-API re-expressions (all api-map-sanctioned; NO IP logic deviated)

| IP 1.21.3 call | 26.2 re-expression | Map |
|---|---|---|
| `RenderSystem.getProjectionMatrix()` | `getCurrentProjectionMatrix()` helper (above); fallback `RenderStates.basicProjectionMatrix` else identity | G27/G19 |
| `RenderSystem.enableDepthTest()`/`depthMask(b)`/`colorMask` | `GlStateManager._enableDepthTest()`/`_depthMask(b)`/`GL11.glColorMask` (depth WRAPPERS GONE from RenderSystem; GlStateManager forms survive at `com.mojang.blaze3d.opengl`) | G6/G12 |
| `Minecraft.getMainRenderTarget()` | `client.gameRenderer.mainRenderTarget()` | G14/C7 |
| `RenderTarget.viewWidth/viewHeight` | `RenderTarget.width/height` | G12 |
| `new TextureTarget(w,h,useDepth,ON_OSX)` | `new TextureTarget(label,w,h,useDepth,GpuFormat.RGBA8_UNORM)` | C37/G13 |
| `fb.checkStatus()` / `fb.bindWrite(boolean)` / `fb.resize(w,h,ON_OSX)` | DROPPED (GpuDevice validates) / DROPPED (`ip_setFrameBuffer` swap IS the routing) / `fb.resize(w,h)` | G12/G8/C5 |
| `GlStateManager._clearColor/_clearDepth/_clear(flags,ON_OSX)` | `CommandEncoder.clearColorAndDepthTextures(...)` | G7 |
| `RenderTarget.bindWrite(false)` (stencil) | raw-GL `GL30.glBindFramebuffer(GL_FRAMEBUFFER, StencilState.gameFboId)` when `!=0` (bindWrite GONE) | G12 |
| `fb.blitToScreen(w,h)` | `fb.blitAndBlendToTexture(mainColorView, mainDepthView)` | G12 |
| `Minecraft.useShaderTransparency()` / `options.graphicsMode()==FABULOUS` | `gameRenderState().useShaderTransparency()` (GraphicsStatus + Fabulous GONE) | R13i / §6.4 |
| `gameRenderer.getMainCamera().getPosition()` | `mainCamera().position()` | C-rename |
| `net.fabricmc.fabric.api.event.{Event,EventFactory}` | `com.warwa.seamlessportals.event.{Event,EventFactory}` (F12/B1 loader-seam; probe caught the initial verbatim-fabric slip) | F12 |
| `RenderTarget.frameBufferId` + `glBlitFramebuffer` (Iris) / `bufferSource().endBatch()` (Iris) | NO 26.2 analog — blit/endBatch blocks COMMENTED | G8 |

**SIGN NOTE (SPIKE-R1 reversed-Z frustum hazard):** `getPortalsToRender`'s cull `Frustum` is built from the
reversed-Z projection — **SAFE**: it only feeds `frustum.isVisible(portal box)`, NEVER
`SectionOcclusionGraph.addSectionsInFrustum`/`offsetToFullyIncludeCameraCube` (the deterministic hang). The
live `StencilPortalRenderer.buildMainFrustum` proves it (runs per frame today). Inline-documented.

---

## 4. Slice C installs — R4 ViewArea redirect + 4 R3 mixins + IPConfig field + ducks

### Task 1 — the R4 `ImmPtlViewArea` install redirect (`MixinLevelRenderer`)

- **Construction site (re-verified `26.2:LevelRenderer.java:796,819-827`):** IP redirected `new ViewArea`
  inside 1.21.3 `allChanged`; on 26.2 it moved to `invalidateCompiledGeometry(ClientLevel, Options, Camera,
  BlockColors)`, the exact 7-arg `new ViewArea(sectionRenderDispatcher, level.getMinY(), level.getMaxY(),
  level.getMinSectionY(), level.getMaxSectionY(), options.getEffectiveRenderDistance(),
  sectionOcclusionGraph)`.
- **`@Redirect` NEW target** `(SectionRenderDispatcher;IIIII;SectionOcclusionGraph;)ViewArea` (5 ints);
  `method="invalidateCompiledGeometry"` unambiguous. The handler appends the enclosing method's FIRST param
  (`ClientLevel level`) as the 8th ctor arg → `ImmPtlViewArea`'s 8-arg ctor (`ImmPtlViewArea.java:139-149`),
  sourcing the `Level` the 26.2 super ctor dropped (`ClientLevel extends Level`).
- **Gate = the GLOBAL flag `IPCGlobal.useHackedChunkRenderDispatcher`** (verbatim IP,
  `IP:MixinLevelRenderer.java:335`) — NEVER `mc.levelRenderer == this` identity (SPIKE-R4 §4-S1: the mod swaps
  `Minecraft.levelRenderer` per portal-render frame). Flag-OFF → vanilla `new ViewArea` unchanged.
- `ImmPtlViewArea.init()` wiring rides the client-init seam at S13 (documented in the mixin javadoc). Plain
  `@Redirect` shipped for fidelity; the `@WrapOperation` variant is the one-line S12 fallback if a
  third-party `new ViewArea` redirect conflicts post-cutover.
- **Scope split:** IP's monolithic `MixinLevelRenderer` also carried `renderEntity` `@WrapOperation`/weather/
  occlusion casts; on 26.2 the per-entity clip hooks re-express onto `MixinLevelRenderer_CrossPortalEntity`
  (task 2). This mixin carries ONLY the R4 install (multiple mixins on one target compose).

### Task 2 — the four R3 mod-additive mixins (S11-R3 §3.2/§3.3/§1.2.4)

26.2 targets re-verified: `LevelExtractor.extractVisibleEntities` (`:221`, `extractEntity` `:243`),
`LevelRenderer.submitEntities` (`:653`, per-entity `entityRenderDispatcher.submit` `:660`, `submitNodeStorage`
`:107`), `FeatureRenderDispatcher.PreparedFrame.executePhase(FeatureRenderPhase, FeatureFrameContext)`
(`:258-266`), `CameraRenderState.viewRotationMatrix` (`Matrix4f`, `:30`), `EntityRenderState` (non-final `:16`).

| Mission bullet | File(s) | Hook |
|---|---|---|
| EntityRenderState tag duck | `IEEntityRenderState` + `MixinEntityRenderState` (holder) + `MixinLevelExtractor` (setter) | duck holds two `@Unique` fields on `EntityRenderState`; `@WrapOperation` on `LevelExtractor.extractEntity` set-or-CLEARs `(entity, portal)` when `((IEEntity)e).ip_isCollidingWithPortal()` (the exact predicate filling `collidedEntities`, `CrossPortalEntityRenderer:126`) |
| submitEntities HEAD/TAIL anchors | `MixinLevelRenderer_CrossPortalEntity` | HEAD → `PerEntityClipBracket.onFrameSubmitBegin((SubmitNodeStorage)output)` + `CrossPortalEntityRenderer.onBeginRenderingEntitiesAndBlockEntities(cameraRenderState.viewRotationMatrix)`; TAIL → `onEndRenderingEntitiesAndBlockEntities(entityRenderDispatcher(), cameraRenderState, poseStack, storage)` |
| WrapOperation on per-entity submit | `MixinLevelRenderer_CrossPortalEntity` | `@WrapOperation` on `EntityRenderDispatcher.submit(...)`: a TAGGED state (`ip_getClipContextEntity()` non-null) → `CrossPortalEntityRenderer.submitMainPassEntity(...)`; `handled==true` skips vanilla, else `original.call(...)` |
| executePhase bracket | `MixinPreparedFrame` | HEAD `prev = beginPhaseIfRegistered(phase)`, RETURN `endPhase(prev)`; `prev` in a `@Unique` field per `PreparedFrame` instance (executePhase non-re-entrant within one instance; nested dest passes use distinct instances) |

**Why the tag duck exists (design §3.2):** 26.2 detached `EntityRenderState`s do not carry the `Entity`, but
the per-entity submit `@WrapOperation` only receives the state — the tag carries the `Entity` across the
extract→submit boundary (CUTOVER_SPEC §4.2). Set-or-CLEAR on every extraction guards a reused/pooled state
carrying a stale context. The SETTER uses `@WrapOperation` (not bare `@Inject`+`@Local`) because the
`EntityRenderState` return is on the stack at `INVOKE:AFTER`; MixinExtras is on the probe classpath.
`output instanceof SubmitNodeStorage` is the pass's own storage (`26.2:LevelRenderer.java:281` passes
`this.submitNodeStorage` as the collector — the per-`LevelRenderer` key the seam's nested-pass scoping needs;
Verifier-1 P2). **Runtime finalization deferred to S13 (design §6):** the exact `viewRotationMatrix` ↔ pushed
`modelViewMatrix` equivalence, CASE-3 submit-anchor timing, and `executePhase` RETURN-on-throw hardening are
named S13 rung-1 checks (see §6). [SECONDARY carry from Verifier-1: the S12A-installs fragment cited
`26.2:LevelRenderer.java:174,281` for the submit-storage identity — `:281` re-confirmed this run; `:174` not
re-verified this pass; `:281` is the load-bearing citation.]

### Task 3 — `IPConfig.crossPortalEntityClipMechanism` (closes the S11-C C4-rider config task)

Mirrors the `correctCrossPortalEntityRendering` template (client category + `@Gui.Tooltip`) with a
`@ConfigEntry.Gui.EnumHandler(BUTTON)` (like `netherPortalMode`/`endPortalMode`), a null-guard in
`onConfigChanged` (mirroring the `netherPortalMode` guard), and the
`IPGlobal.crossPortalEntityClipMechanism = crossPortalEntityClipMechanism;` assignment. Verified in source
this run: field `IPConfig.java:51-52` (default `PerEntityClipBracket.Mechanism.SUBMIT_ORDER_UNIFORM`),
null-guard `:179-181`, assignment `:196`. **Server-safe enum reference (FQN mirrors `IPGlobal`):** naming
`qouteall.imm_ptl.core.render.PerEntityClipBracket.Mechanism.SUBMIT_ORDER_UNIFORM` loads only the nested enum
class file — it never force-loads the `@Environment(CLIENT)` `PerEntityClipBracket` render class (the enum's
`<clinit>` has no client dependency). Probe-confirmed: the `Mechanism` reference resolves with ZERO errors.
Persists the C4-rider A/B switch to the in-game config screen (deferral V1 P3 recorded in S11-R3 §5 / S11-C
§7.2). **Cost:** +4 `me.shedaniel.autoconfig`/`ConfigEntry` package-does-not-exist errors — the SAME
pre-existing autoconfig-dependency debt IPConfig already carries (U11 closure; resolves at S13), not a new
closure-set violation.

### Task 5 — the S12 forward-ref ducks: LAND two, RETIRE one

- **`IERenderSystem` — LANDED VERBATIM.** Target `RenderSystem.modelViewStack` present
  (`26.2:RenderSystem.java:60`, getter `:208`). MyGameRenderer re-expressed the model-view-stack swap onto
  the public `getModelViewStack()` (G28, S11B §1.1), so the duck resolves ZERO current references — available
  for the sibling renderer slices.
- **`IESectionRenderDispatcher` — LANDED VERBATIM.** Target `SectionRenderDispatcher.fixedBuffers` present
  (`26.2:.../SectionRenderDispatcher.java:46`, type `SectionBufferBuilderPack`). Swap unwired (§5, task 4).
- **`CoreShadersAccessor` — RETIRED, NOT landed.** Its target `CoreShaders.register(...)` is **G9-GONE**
  (`CoreShaders.java` AND `ShaderProgram.java` do NOT exist in `mc262-ref`; only `ShaderDefines.java`
  survives). Landing it verbatim would add +2 non-closure-set errors. The custom core shaders it registered
  are re-expressed as mod `RenderPipelines` (the `PortalRenderTypes` substrate + `MyRenderHelper.getPortalAreaRenderType`
  family, S11B §1.2). Supersedes the ledger's provisional naming. **The 2 `mixin.client.accessor` probe
  errors are NOT CoreShadersAccessor** — they are `ClientWorldLoader`'s `IEClientLevelData`/
  `IEClientLevel_Accessor` forward-refs (a different slice).

---

## 5. Swap-DROP verdicts (MyGameRenderer `switchAndRenderTheWorld` swaps S11-B dropped) + FBO/renderMode/Iris

### Task 4 — the MyGameRenderer swap-DROP verdicts (S11B §1.1), source-verified this run

| IP 1.21.3 swap | 26.2 verdict (verified) | Disposition |
|---|---|---|
| `getSectionRenderDispatcher().ip_setFixedBuffers(...)` secondary-buffer isolation | **Field PRESENT** — `SectionRenderDispatcher.fixedBuffers` (`26.2:.../SectionRenderDispatcher.java:46`). SWAP NOT load-bearing (mod isolates via the `RenderBuffers` pool swap alone). | **Accessor LANDED** (`IESectionRenderDispatcher`), swap UNWIRED — land ONLY if the CUTOVER_SPEC §1.4 stranded-upload runtime check (S13/S14) requires it |
| `setRenderHand(...)` + `ip_getDoRenderHand()` save/restore | **GONE** — 26.2 `GameRenderer` has NO hand flag; hand renders via `renderItemInHand(cameraState,…)` (`GameRenderer.java:336,572`). | **Non-load-bearing DROP** — maps to the existing `WorldRenderInfo.doRenderHand` seam → gate the `renderItemInHand` call at S13. No accessor needed. |
| `blockEntityRenderDispatcher.level = newWorld` | **GONE** — `BlockEntityRenderDispatcher` has no `level` field. | **Non-load-bearing DROP** — dim context is `prepare(destCameraPos)` in the extract (driver-core). |
| `portal_get/setTransparencyShader(...)` per-dim fabulous `PostChain` swap | **26.2-SUPERSEDED** — 26.2 translucency is framegraph-driven (`getTransparencyChain()` reads `gameRenderState().useShaderTransparency()`, `26.2:LevelRenderer.java:834-838`). | **Non-load-bearing DROP** — the R13i `useShaderTransparency` HEAD-cancel override (CUTOVER_SPEC §6.4) is the S12-B client-mixin re-expression, not a per-dim shader swap. |

**Net:** three DROPs confirmed non-load-bearing (no accessor); the fourth (`fixedBuffers`) has a live 26.2
field so the VERBATIM accessor is landed for optional S13/S14 wiring, swap stays unwired.

### FBO / renderMode / Iris-shell record

- **renderMode dispatch (D5/A1) CLOSED:** `switchToCorrectRenderer` (Slice A `PortalRenderer`) now has all
  four concrete targets — normal=`RendererUsingStencil`, compatibility=`RendererUsingFrameBuffer` (reachable),
  debug=`RendererDebug`, none=`RendererDummy`. Iris branch (`.instance`/`.debugModeInstance`) resolves to the
  three landed shells but is NEVER taken (`isIrisPresent()`=false forever).
- **FBO-mode depth (R5 Row 12 FBO variant):** `drawPortalAreaWithFramebuffer` draws through
  `portalCompositeBlit` whose depth state is `Optional.empty()` = depth-test OFF (`PortalRenderTypes.java:199`),
  so the composite is NOT reversed-Z GEQUAL-gated and writes NO separate NEAR shield — the FBO's own depth
  carries the dest scene; the row-12 STEP-3.5 NEAR-shield-direction question does not arise on the
  depth-test-off composite path.
- **Depth CLEAR 1.0 → 0.0 flip** (FAR): the secondary-FBO / main-frame magenta+depth clears in
  `RendererUsingFrameBuffer.doRenderPortal`, `RendererDebug.doRenderPortal`,
  `IrisCompatibility.prepareRendering`, `IrisPortalRenderer.prepareRendering` (same flip
  `GuiPortalRendering.java:96` already ships). Color values (magenta/red) direction-independent.
- **`onAfterTranslucentRendering` @Override DROPPED** in RendererDummy/RendererDebug: IP's `@Override`
  overrides nothing (neither IP's nor Slice A's `PortalRenderer` declares it, IP never CALLS it — grep: zero
  dot-call sites); the no-op BODY is kept for source-shape fidelity. Not a LOGIC deviation.
- **Iris raw-FBO blit blocks COMMENTED** (`IrisPortalRenderer` depth/stencil FBO-id blits; `ExperimentalIris`
  bufferSource endBatch; `IPIrisHelper` glCopyImageSubData): no 26.2 core-profile analog, never-run — mirrors
  IP's own discipline of commenting unportable Iris internals.
- **`renderScreenTriangle` consumers — CORRECTED prose (the P1 masking claim):** the PRIMARY consumer is
  `RendererUsingStencil` (S13 cutover-core, renderMode=normal DEFAULT) at all three choreography sites (Row
  16 `:94` / Row 7 `:327` / Row 15 `:403`); the never-loaded `ExperimentalIrisPortalRenderer` also draws it
  (Rows 7/15). The earlier "all callers are Iris shells" fragment claim was FALSE and masked the P1 defect —
  corrected here and in the fragment before deletion.
- **S18 / A1 GPU-refinements (documented, NOT logic deviations):** `drawPortalAreaWithFramebuffer` composites
  the FBO full-opening via `portalCompositeBlit` — IP's PORTAL-AREA screen-space masking (the GONE
  `PORTAL_DRAW_FB_IN_AREA` custom shader, G9) is the S18 refinement; `drawScreenFrameBuffer`'s per-call
  blend/alpha-mode (GONE `BLIT_SCREEN` shaders, G29/G40) + the Iris deferred-FBO target are the never-run Iris
  gap. Their absence at cutover is status quo, not regression (CUTOVER_SPEC §6.5).

---

## 6. Probe triage vs 177 + forward-ref ledger for S12-B / S13

**AUTHORITATIVE build:** `:common:compileJava -Pip_scc_closed=true` (the machine-checked forward-ref debt
ledger). Logs in the session scratchpad (`s12a_probe.log` / `s12a_shipping.log` / `s12a_test.log`).

| Build | Result |
|---|---|
| Shipping `:common:compileJava` (no flag) | **BUILD SUCCESSFUL** (UP-TO-DATE) — all held files excluded; live block-era render byte-for-byte untouched (zero `com.warwa`/`buildSrc/build.gradle` edit) |
| `:common:test` | **BUILD SUCCESSFUL** |
| Probe — Slice-A landing (baseline reference) | 177 javac / 178 grep — NET-NEUTRAL vs the S11-C baseline (177 javac): the 2 stencil files' 12 forward-ref errors exactly balanced the `PortalRenderer`/`RendererUsingStencil` TYPE forward-refs they RESOLVED |
| **Probe — FINAL S12-A (all slices + the P1 fix)** | **161 javac / 162 grep** (the +1 = documented Gradle problems-report phantom, S11B note SS7). ZERO errors originate from `MyRenderHelper`/`RendererUsingStencil`/`ExperimentalIrisPortalRenderer` or ANY of the 7 renderer-family files; whole-probe signature-category sweep = **0** (no incompatible-types / cannot-be-applied / does-not-override / private-access / ambiguous). No error mentions `renderScreenTriangle`/`ScreenTrianglePurpose` (all 3 `RendererUsingStencil` call sites RESOLVE). |

**Delta 177 → 161:** the 16-error reduction is dominated by the renderer-family slices resolving forward-refs
they had themselves introduced at Slice-A landing (`PortalRenderer` 11→1 remaining, `RendererUsingStencil`
3→0, plus `IPModMainClient`/`RenderStates` renderer-type refs). Slice-C's install files are additive
infrastructure (resolve zero existing forward-refs) and add only the +4 documented autoconfig-debt on
`IPConfig`; the load-bearing guarantee is the per-file cleanliness + ZERO signature sweep, not the shared
integer.

**Probe error breakdown (all 161 are forward-ref / package-does-not-exist debt — NO translation slip):**

| Error class | Count | Resolves at |
|---|---|---|
| `me.shedaniel.autoconfig` / `ConfigEntry.*` (bulk of `IPConfig.java` 65 + `IPModMain`) | ~63 | F21 autoconfig stub / U11 closure — **S13** |
| `qouteall.imm_ptl.core.commands` (`PortalCommand`, argument types) | 8 | S13 closure set |
| `qouteall.imm_ptl.core.block_manipulation` / `BlockManipulationServer` | 10 | S13 closure set |
| `qouteall.imm_ptl.core.portal.custom_portal_gen` + `BreakablePortalEntity` | 6 | U12 closure — S13 |
| `qouteall.imm_ptl.peripheral.wand` | 1 | S13 wand shell |
| `mixin.client.accessor` (`IEClientLevelData`/`IEClientLevel_Accessor`, ClientWorldLoader) | 2 | S13 client mixins |
| `mixin.client.{sync,particle}` / `debug` | 3 | S13 / S12-B |
| `IPModInfoChecking` (`PortalRenderer.java:20` import + `:420` call) | 2 | **S12-B** (mission-named) |
| remaining `cannot find symbol` (RenderStates / OverlayRendering / MyGameRenderer / ImmPtlViewArea / CrossPortalViewRendering / IPGlobal forward-refs into the above) | rest | S13 closure set |

**HARD GATE HELD:** every error is either S12-B (`IPModInfoChecking`) or the S13 closure set (U11 autoconfig/
commands/wand + U12 custom_portal_gen/BreakablePortalEntity + the client-mixin forward-refs landing at S13).
No error references anything outside the gate set.

**Forward-ref ledger for S12-B / S13 (the intra-stage RESOLVED refs are gone):**

| File:line | Missing symbol | Resolves at |
|---|---|---|
| `PortalRenderer.java` :20, :420 | `compat.IPModInfoChecking` (+ `.checkShaderpack`) | **S12-B** (mission-named; leave the import) |
| `RendererUsingStencil.java` :94/:327/:403 | `MyRenderHelper.renderScreenTriangle` | **RESOLVED intra-S12-A** — the per-purpose family is authored; the 3 sites now pass a `ScreenTrianglePurpose` |
| `PortalRenderer.java` Iris `.instance`/`.debugModeInstance` | the 3 Iris shells | **RESOLVED intra-S12-A** — shells landed this slice |
| `IPConfig.java` (+4 new) | `me.shedaniel.autoconfig`/`ConfigEntry` | S13 (same pre-existing autoconfig debt) |

**Deferred items (recorded, NOT defects — carry into S13):**
- **Row-12 GEQUAL clobber** — keeping IP's exact-projected-depth mesh re-render (`:359`) at S13 needs an
  ALWAYS_PASS pipeline variant; the GEQUAL mesh pipeline clobbers the raw `glDepthFunc(GL_ALWAYS)` bracket
  (§2 Row 12). S13 watch item.
- **`MixinPreparedFrame` throw-path** — `executePhase` RETURN-on-throw hardening is a named S13 rung-1 check.
- **R13i `useShaderTransparency` HEAD-cancel override mixin** — **S12-B scope** (per EXECUTION_PLAN, R13i
  re-anchored under the full S12 client-mixin set; CUTOVER_SPEC §6.4 flags it easy-to-miss). S12-B must not
  drop it.
- **R3 runtime finalization** — the `viewRotationMatrix` ↔ pushed `modelViewMatrix` equivalence, CASE-3
  submit-anchor timing, and `armCompileScheduling` wiring are S13 rung-1 checks (§3/§4).

---

## 7. Held-machinery + IpHeldPaths sanction

- **Shipping `:common:compileJava` = BUILD SUCCESSFUL; `:common:test` = BUILD SUCCESSFUL.** Live block-era
  render byte-untouched (no `com.warwa` edit; `buildSrc/build.gradle` untouched).
- **HELD-PATH FIX (mandatory D1 step) — PARENT-SANCTIONED, DO NOT REVERT.** `ducks/IEEntityRenderState.java`
  lands in the `ducks/` **carve-in** dir (only 7 ducks held by name), so it is NOT auto-held; it imports the
  held `qouteall.imm_ptl.core.portal.Portal` (U6) → the first shipping build turned RED. Fixed by adding
  `'qouteall/imm_ptl/core/ducks/IEEntityRenderState.java'` to `IpHeldPaths.MAIN_HELD_PATHS` (`:115-119`) — the
  exact pattern the existing 7 held ducks use (reason (1): "ducks import HELD qouteall classes"). This is the
  D1 holding-machinery ledger, distinct from the protected `buildSrc/build.gradle` (which stays untouched).
  The S12-A verify orchestrator flagged this as a divergence from its "IpHeldPaths untouched" checklist, then
  explicitly SANCTIONED it as necessary-and-correct (NOT a defect): it matches the established 7-duck carve-in
  pattern and is REQUIRED for the shipping build to stay green. All other mixins are under
  `qouteall/imm_ptl/core/mixin/**` (held wholesale) and IPConfig under `platform_specific/**` (held wholesale)
  — no held-path change needed for those.

## Held-UNREGISTERED confirmation

All mixins compile as ordinary annotated Java in the probe; `seamlessportals-ip-client.mixins.json "client":[]`
is untouched. Registered into the S12 client-mixin set + activated flag-ON at S13 per the EXCLUSIVITY_LEDGER
§6 addition.

---

## 8. VERIFICATION TIER

S12 IS a designated Fable adversarial-verify stage (per `migration-model-tier-policy`: the hard render stages
S6/S8/S11/S12/S13). This stage ran the full workflow gate:

- **Two independent adversarial verifiers** flagged the SAME pre-commit MAJOR (P1, the `renderScreenTriangle`
  single-pipeline re-expression nullifying R5 Rows 7/15/16 for its real `RendererUsingStencil` consumers) plus
  the `IpHeldPaths` divergence (sanctioned) — see §0 / §7.
- **The P1 was FIXED** before commit per the parent ruling + Verifier-1 prescription (per-purpose pipelines,
  §0), and re-gated: shipping GREEN, probe 161 with ZERO errors from the renderer family, ZERO signature-
  category slips, no `renderScreenTriangle` refs remaining.
- **The R5 16-row checklist executed here was Fable-verified TWICE at S11-B** (CUTOVER_SPEC §2.1); this stage
  TRANSPLANTS those signed-off verdicts (no new sign derivation — every flip/no-flip cites its S11-B-verified
  row). The single live-render bug class (a silent depth-sign flip) is concentrated in Row 7
  (`glDepthRange(0,0)`, `:321`), whose flip direction + `MOD:StencilPortalRenderer.java:401` proof were both
  Fable-confirmed.
- **Live-render sign-off is at S13/S17** (first light + default flip) — S12-A is inert (held, flag-OFF, never
  loaded), so no runtime verification is possible or required at this stage. The load-bearing S12-A guarantees
  are the per-file probe cleanliness + ZERO signature sweep + the shipping-green invariant, all re-confirmed
  post-fix this run.
