# S11-A — Render-context foundation (first third of U9)

Stage S11-A of the entity-portal migration: the **render-context foundation** — the hardest, most
26.2-divergent stage. Ports IP's render-context stack, sign-sensitive render math, and GL utilities
to the verbatim held path `common/src/main/java/qouteall/imm_ptl/core/{render,render/optimization,
mc_utils}/`, and RECONCILES the four PORT-FORWARD carriage classes onto the mod's proven 26.2
mechanisms. All held IP classes are **inert until S13**; **no live block-era render file
(`com.warwa.seamlessportals`) is edited**; the always-on stencil-substrate KEEP mixins stay bound to
their live sources in both flag states.

Assembled from the three FixGaps slice fragments (context / math / carriage) + the FixGaps/verify
build evidence (scratchpad `s11a_probe.log`, `probe.log`, `shipping_build.log`, `test_build.log`,
`held_uar.txt`, `live_uar.txt`, Jul 14 19:30–19:48).

**Slice split.** S11-A owns the *foundation* third of U9:
- **Slice A — `context_management/*`** (7 classes) + one coupled `ducks/IEGameRenderer` retype.
- **Slice C — render math + GL utilities** (8 classes: `TransformationManager`, `FrustumCuller`,
  `GlQueryObject`, `QueryManager`, `optimization/GLResourceCache`, `ForceMainThreadRebuild`,
  `optimization/SharedBlockMeshBuffers`, `mc_utils/WireRenderingHelper`).
- **Slice B — PORT-FORWARD carriage reconciliation MAP** (documentation only; **no source
  authored** — the four carriage classes are authored fresh in S11-B/S12 per this map).

The heavy render units `MyGameRenderer`, `MyRenderHelper`, `ImmPtlViewArea`, `VisibleSectionDiscovery`,
`CrossPortalEntityRenderer`, `GuiPortalRendering`, `ViewAreaRenderer`, and the `CUTOVER_SPEC.md` are
**S11-B** (EXECUTION_PLAN S11 commits 3–5). This note is EXECUTION_PLAN S11 commit-6 (sign notes,
A3 read, carriage map) scoped to the S11-A foundation.

Ground truth: `migration/api-map/{render-core,render-sub,current-mod-render}.md`,
`MIGRATION_API_MAP.md`, `migration/API_RISKS.md` (R5/R6/R9), and `mc262-ref` source reads (every 26.2
API fact below was verified against `mc262-ref`; every sign/winding constant re-derived, D4.4).

---

## 1. Diff-gate / reconcile record — per class

**Footprint (git working tree):** 15 NEW held classes + 1 MODIFIED duck. No `com.warwa` render file
touched; no `.accesswidener` / NeoForge-AT / `build.gradle` edit (§6).

### Slice A — `context_management/*` + coupled duck

| Class | Disposition | Decision |
|---|---|---|
| `StaticFieldsSwappingManager` | NEW (§5) | **VERBATIM 1:1.** Pure generic container, zero vanilla-render surface. R9 line 276: "nothing left to swap for fog (compiles as-is)". |
| `WorldRenderInfo` | NEW (§5) | **VERBATIM 1:1.** `PoseStack.Pose.pose()`/`.normal()` survive (PoseStack.java:105/109); `Options.getEffectiveRenderDistance()` survives. No divergence. |
| `PortalRendering` | NEW (§5) | **VERBATIM** + 1 rename `Camera.getPosition()`→`position()`. Replaces the mod's old `isRenderingPortal` boolean flag (block-era §1.2) with IP's push/pop layer stack. |
| `RenderStates` | NEW (§5) | **VERBATIM** + 3 mechanical renames (§ 26.2 table). |
| `CloudContext` | NEW (§5) | **VERBATIM** + 1 field retype `VertexBuffer`→`GpuBuffer`; javadoc re-anchored to `CloudRenderer`. |
| `FogRendererContext` | NEW (§5) | **RE-EXPRESSED onto 26.2 fog + FLAGGED (R9).** `getFogColorOf` onto instance `setupFog`; per-dim buffer ownership deferred to S11-B CUTOVER_SPEC (§4). |
| `DimensionRenderHelper` | **PORT-FORWARD / RECONCILE** | **Re-homed the mod's proven 26.2 Lightmap mechanics** into IP's per-instance contract — NOT a fresh re-port of IP's `LightTexture` shape (§3). |

Coupled reconcile outside the slice dir:
`ducks/IEGameRenderer.ip_setLightmapTextureManager(LightTexture→Lightmap)` — S11-owned per
`S04-ducks-roots-facade.md:181` + the S10B `ClientWorldLoader.java:166` note. This is the ONE modified
(`M`) file; it retires the ducks-package `LightTexture` compile error that was S10 forward-ref debt.

### Slice C — render math + GL utilities

| Class | LOC | Disposition | Result |
|---|---|---|---|
| `render/TransformationManager` | 322 | NEW (supersedes DELETE `CameraTransitionHandler` §1.17) | **VERBATIM + 4 mechanical 26.2 renames + 1 landed-dep reconcile** (§2) |
| `render/FrustumCuller` | 402 | **PORT-FORWARD** `PortalInnerCull`→`FrustumCuller` (§1.9) | **VERBATIM + 1 camera rename**; winding math byte-for-byte (§2) |
| `render/GlQueryObject` | 102 | NEW (R6 raw-GL stays) | **VERBATIM + `ON_OSX` translation** |
| `render/QueryManager` | 66 | NEW (R6 raw-GL stays) | **VERBATIM + `ON_OSX` translation** |
| `render/optimization/GLResourceCache` | 50 | NEW | **VERBATIM 1:1** (0 changed lines) |
| `render/ForceMainThreadRebuild` | 46 | NEW (A3 read, §5) | **VERBATIM 1:1** (0 changed lines) |
| `render/optimization/SharedBlockMeshBuffers` | 9 | NEW (`IPModMainClient:29`) | **VERBATIM 1:1** (`@Deprecated` empty) |
| `mc_utils/WireRenderingHelper` | 731 | NEW (`PortalEntityRenderer:14` + wand) | **VERBATIM + `renderLineBox` reconstruction** (§2) |

### 26.2 API translations (verified in mc262-ref; consistent across slices A + C)

| IP call | 26.2 replacement | Source | Files |
|---|---|---|---|
| `Camera.getPosition()` | `Camera.position()` | Camera.java:359 | PortalRendering, RenderStates, TransformationManager, FrustumCuller |
| `GameRenderer.getMainCamera()` | `GameRenderer.mainCamera()` (public) | GameRenderer.java:657 | RenderStates, TransformationManager (×2), FrustumCuller |
| `Gui.setOverlayMessage(Component,boolean)` | moved to split-out `Hud`; reach via public field `Gui.hud` | Hud.java:1225, Gui.java:72 | RenderStates |
| `Minecraft.getProfiler()` | static `Profiler.get()` → `ProfilerFiller` | Profiler.java:47 | FogRendererContext |
| `net.minecraft.client.renderer.LightTexture` | `Lightmap` (GPU) + `LightmapRenderState`/extractor (state) | render-core G4, Lightmap.java:44-93 | DimensionRenderHelper, IEGameRenderer duck, RenderStates (`.lightmapTexture`) |
| `com.mojang.blaze3d.vertex.VertexBuffer` | `com.mojang.blaze3d.buffers.GpuBuffer` (abstract AutoCloseable) | GpuBuffer.java:11,43; render-core G8 | CloudContext |
| static `FogRenderer.setupColor(...)` + 6 fog statics | instance `FogRenderer.setupFog(Camera,int,DeltaTracker,float,ClientLevel)`→`FogData`, read `FogData.color` (Vector4f) | render-sub G2, FogRenderer.java:167-186, FogData.java:15 | FogRendererContext |
| `GameRenderer.getDarkenWorldAmount(pt)` | `GameRenderer.bossOverlayWorldDarkening(pt)` (public) | GameRenderer.java:653 (setupFog site :630-639) | FogRendererContext |
| `Camera.setup(BlockGetter,Entity,detached,mirrored,pt)` | `Camera.update(DeltaTracker)` (reads level/entity already set; detached/mirrored in private `alignWithEntity`; pt from tracker) | render-core **G21**; Camera.java:93,:249 | TransformationManager |
| `Minecraft.ON_OSX` (public boolean, GONE) | `Util.getPlatform() == Util.OS.OSX` (the exact former definition; the private twin now in `InputQuirks`, not usable) | Util.java:533/1205; InputQuirks.java:9 | GlQueryObject, QueryManager |
| `LevelRenderer.renderLineBox(...)` (GONE — debug wireframes moved to immediate-mode `Gizmos`) | private `renderLineBox` reconstruction: 12 edges via `VertexConsumer.addVertex(Pose,f,f,f).setColor(...).setNormal(Pose,f,f,f)` | VertexConsumer.java:94/41/108; `renderLineBox` absent across all 7055 ref files | WireRenderingHelper |
| (DeltaTracker source, several) | `Minecraft.getDeltaTracker()` | Minecraft.java:2693 | FogRendererContext, TransformationManager |

**Diff-gate verdict:** every non-verbatim change above is a mechanical 26.2 API rename or an
api-map-sanctioned re-expression. The only *representation* changes are the two RECONCILE items
(`DimensionRenderHelper` §3, `FogRendererContext` §4) and the one landed-dep reconcile
(`TransformationManager` `Tuple`→`Pair`, §2). No IP *logic* deviated.

---

## 2. SIGN-DERIVATION table (D4.4 — the deliverable of the sign-sensitive slice)

**R5 reversed-Z does NOT touch S11-A.** None of the 15 held classes clears depth, sets a depth
comparison, writes a stencil op, or uploads a clip plane — those live in `RendererUsingStencil`
(U10/S12, `clearDepthOfThePortalViewArea`/`restoreDepthOfPortalViewArea`) and the already-live
`FrontClipping`. S11-A is pure quaternion/matrix/plane math + camera/fog/lightmap STATE + raw-GL
query/resource pooling. Every geometry constant was ported **byte-for-byte** and re-derived below to
confirm the 26.2 renames introduced no sign flip.

### TransformationManager — rotation composition (HIGH sign-sensitivity), values preserved

| Element | Derivation | Verdict |
|---|---|---|
| Composition order | `finalRot = rawCameraRotation · gravity · animationDelta · portalRot` (right-most applies first; all `hamiltonProduct`) — class javadoc unchanged; every product + every `.getConjugated()` (portalRot⁻¹, gravity⁻¹, rawCameraRotation⁻¹) ported verbatim | no operand reorder, no conjugate added/removed |
| Pitch clamp | `finalPitch>90 → 90−(finalPitch−90)`; `finalPitch<−90 → −90+(−90−finalPitch)` (reflect back over the pole) | preserved exactly |
| Mirror matrix | `Matrix4f.reflection(nx,ny,nz,0)` (JOML householder about the plane through origin) — matches the commented-out `1−2·n·nᵀ` form | preserved (JOML API unchanged on 26.2 classpath) |
| Isometric ortho | hand-built 4×4 (`2/(r−l)`, `2/(t−b)`, `−2/(f−n)`, `−(r+l)/(r−l)` translate row, `−2000..2000` near/far) = standard right-handed glOrtho (debug/spectator projection, NOT the portal depth pass) | no reversed-Z involvement; preserved exactly |
| `camera.update` vs `camera.setup` | rename recomputes camera from the just-rewritten pitch/yaw; `update` additionally recomputes fov/cull-frustum/perspective — a *superset* of `setup`, api-map-sanctioned (G21) | no sign surface |

**Landed-dep reconcile (the ONE IP-vs-landed divergence in the dependency set):**
IP `DQuaternion.getPitchYawFromRotation` returns `net.minecraft.util.Tuple<Double,Double>` and IP
`TransformationManager` reads `.getA()`(pitch)/`.getB()`(yaw). The **landed S2 port deliberately
retyped it to `com.mojang.datafixers.util.Pair`** and the authored green-gate `DQuaternionTest.java:333-335`
asserts `.getFirst()/.getSecond()`. Reverting would turn the live `:common:test` gate red — out of
scope. TransformationManager reconciles to the landed API: `getA()`→`getFirst()`(pitch),
`getB()`→`getSecond()`(yaw). **Sign/order-preserving:** IP `new Tuple<>(pitch,yaw)` (A=pitch,B=yaw);
landed `new Pair<>(pitch,yaw)` (First=pitch,Second=yaw), so `finalPitch=getFirst`, `finalYaw=getSecond`
is exact. Flagged as a possible S2 fidelity item (not fixed here).

**Runtime note (not a compile/sign concern):** the bob-field writes (`yBob/xBob/yBobO/xBobO` + `yRotO/xRotO`)
are ported verbatim (still public floats, LocalPlayer.java:144-147), but 26.2's *camera* bob reads
`ClientAvatarState.bob`, so the rotation-continuity write-set may need avatarState fields too — flagged
for S12/S18 runtime verification (render-core S14). Does not affect this port.

### FrustumCuller — winding-sensitive (highest-risk file), byte-for-byte preserved

| Element | Derivation | Verdict |
|---|---|---|
| Corner order (CCW) | `getRectPortalFourVerticesCounterClockwise(thisSideState)` emits `{(+w/2,−h/2),(+w/2,+h/2),(−w/2,+h/2),(−w/2,−h/2)}` via `UnilateralPortalState.transformLocalToGlobal` — IP's documented `2 1 / 3 0` layout (idx 0 = right-bottom … 3 = left-bottom) | preserved exactly |
| Side-plane normals | `normal_i = vertices[i+1] × vertices[i]` (cross, then `.normalize()`), W=0 (planes through camera origin). Order `[1]×[0],[2]×[1],[3]×[2],[0]×[3]` | preserved exactly |
| Mirror flip | for `portal instanceof Mirror`, corners reversed `{v3,v2,v1,v0}` before building planes (reverses winding for the mirror's opposite handedness) | preserved exactly |
| Half-space tests | `isFullyBehindPlane` picks corner MOST toward the normal (`planeX>0 ? maxX : minX`), cull when `dot+W < 0`; `isFullyInFrontOfPlane` picks the opposite corner, keep when `dot+W > 0`; `isFullyOutside` = OR of 4 behind; `isFullyInside` = AND of 4 front | preserved exactly |

**Cross-confirmation vs the mod's proven block-era `PortalInnerCull`:** `PortalInnerCull.fromCorners`
uses `cross(v1,v0)=v1×v0` and `behind()` uses `px>0 ? maxX : minX … < 0.0f` — **identical winding +
identical sign** to IP's verbatim `FrustumCuller` on 26.2. The block-era class adds a runtime winding
self-correction (it lacks an oriented state); the entity-era `FrustumCuller` needs none because
`getRectPortalFourVerticesCounterClockwise` + the `Mirror` flip give a known-CCW winding a priori.
Verbatim IP is the correct final form — the self-correction is a compensating hack for the block model
and is **NOT ported** (D5/§1.9). The only edit is the camera-pos read (`mainCamera().position()`) feeding
`getCurrentNearestVisibleCullablePortal`'s nearest-portal selection — **no winding surface.**

### FrontClipping — COLUMN-FORM confirmation (anti-fix guard, source-verified)

Not authored in S11-A (block-era `FrontClipping` stays live; the qouteall port is S11-B, §3), but the
column-form idiom is confirmed here so the guard rides into S11-B:

- **Source-verified:** `com.warwa.seamlessportals.render.FrontClipping.java:146-147` and `:181-182` both
  do `org.joml.Vector4f nView = new Vector4f(nwx,nwy,nwz,0f); nView.mul(viewRotation);`. In JOML,
  `Vector4f.mul(Matrix4fc)` computes **M·v (column form)** — the exact `Matrix4f.transform` semantics.
- **HARD ANTI-FIX GUARD (D4.4):** do **NOT** "fix" this to `mulTranspose` — that yields the inverse
  rotation → clip-plane sign bug (the geometry-sign bug class). The `IP_DEVIATIONS_ANALYSIS.md:88-89`
  "row-vector" note is **WRONG**. This idiom must ride into the qouteall `FrontClipping` port unchanged
  wherever the port keeps the view-space-rotation representation.
- `WorldRenderInfo.applyAdditionalTransformations`: `matrixStack.last().pose().mul(matrix)` is the
  **identical** JOML column-form post-multiply — do NOT transpose. The normal-matrix rescale
  `pow(1/|det|,1/3)` is a determinant→1 normalization, NOT a winding flip.
- `PortalRendering.getRenderingCameraPos`/`getActiveClippingPlane`: compose `portal.transformPoint`/
  `transformLocalVecNonScale` — verbatim point/vector transforms, no introduced depth or sign constant.

### WireRenderingHelper + GL utilities — no depth/clip surface

All portal-frame/plane/circle/sphere/lock/mesh vertex math (axisW/axisH scaling, `facingOffset=normal·0.01`,
CCW rect loop, meridian/parallel trig) ported verbatim through `VertexConsumer.addVertex(matrix,…)`.
The `renderLineBox` reconstruction draws all 12 box edges (4 per axis) with the box color + edge-axis
normal — a complete wireframe (uniform color, so vanilla's per-axis-color subtlety is moot). `GlQueryObject`/
`QueryManager` (occlusion) + `GLResourceCache` (id pools) are raw `GL15/GL30/GL33` + fastutil — no
vanilla-render/geometry surface (R6 "raw GL stays").

---

## 3. PORT-FORWARD carriage reconciliation (Slice B — MAP only, no source authored)

The four carriage classes named in EXECUTION_PLAN S11 "PORT-FORWARD carriage": `FrontClipping`,
`ShaderCodeTransformation`, `PortalRenderBuffersPool`, `lateUpdateLight` (IP host `MyRenderHelper`),
plus the coupled `DimensionRenderHelper` (already re-homed in Slice A). Ground state verified this
pass: **no IP render class exists at the qouteall path yet** (`Glob qouteall/.../render/**` before
Slice C = 0); the mod's proven 26.2 versions live at `com.warwa` paths (or inline).

**Global model — KEEP-BOTH, no alias / no `git mv`.** For each carriage item the mod's *mechanism* is
proven on 26.2, but the mod *class* is a different class from IP's (different API, different types —
block-era `PortalInfo`/`PortalLink` vs IP `Portal`/`Plane` — different consumers), and it stays **LIVE**
under `!entityPortals` until S20. Aliasing/moving would either break the live block-era renderer or
create the "two divergent copies of one live class" hazard. Instead: the mod class stays live at its
mod path (gated `!entityPortals` at S13, DELETE at S20); the **qouteall port is authored fresh in
S11-B/S12** exposing IP's API surface + satisfying the held contracts, with the mod's PROVEN 26.2
MECHANISM transplanted in (the *carriage*, not the file). Legitimate because IP's 1.21.3 clip path
(`GL11.GL_CLIP_PLANE0` fixed-function) is GONE on 26.2 core-profile — a verbatim IP copy cannot exist.

**The shared pivot — the always-on stencil-substrate KEEP mixins (single source of truth).** Two mixins
apply in BOTH flag states (exclusivity-ledger "always-on substrate KEEP") and today bind to the MOD classes:
- `ShaderManagerCompilationCacheMixin` (`getShaderSource` RETURN) → `ShaderCodeTransformation.transformVertex(source)` + `.UNIFORM_NAME`.
- `GlCommandEncoderClipMixin` (`GlCommandEncoder.trySetup` RETURN) → `FrontClipping.getPlaneX/Y/Z/W()` + `ShaderCodeTransformation.UNIFORM_NAME`.

Because these are single, shared, always-on, and only one driver runs per session, the safe carriage
recommendation is that the qouteall `FrontClipping`/`ShaderCodeTransformation` ports **retain the mod's
26.2 STATE REPRESENTATION** (view-space plane `vec4` read by `getPlaneX/W`; uniform
`seamlessportals_ClipPlane`; programmatic GLSL injection) so both mixins keep binding to ONE source of
truth. Do not split into two divergent plane stores feeding one GL uniform.

| Carriage item | Mod-proven source (LIVE, KEEP) | IP held contract to satisfy | Re-home decision |
|---|---|---|---|
| `FrontClipping` | `com.warwa.seamlessportals.render.FrontClipping` (3a2c14e); `gl_ClipDistance[0]` via `ShaderCodeTransformation` injection + `GlCommandEncoderClipMixin` upload | `qouteall.…render.FrontClipping` — public `static boolean isClippingEnabled` (**pinned by** `ClientTeleportationManager.java:462`), `setupInnerClipping(Plane,Matrix4f,double)`, `setupOuterClipping(PoseStack,Portal)`, `updateClippingEquationUniformForCurrentShader`, `getActiveClipPlaneEquation{Before,After}ModelView` | S11-B ports fresh: keep mod's `gl_ClipDistance` mechanism + view-space plane store, **re-source the plane from `Portal`/`Plane`/`PortalRendering`** (not `PortalInfo`/`PortalLink`), add the public `isClippingEnabled`. Column-form guard (§2) rides in. |
| `ShaderCodeTransformation` | `com.warwa.…ShaderCodeTransformation` — **programmatic** GLSL rewrite (`transformVertex(String)`, `UNIFORM_NAME`) | `qouteall.…ShaderCodeTransformation` — `init()` (**pinned by** `IPModMainClient.java:77`), `transform(CompiledShader.Type,String,String)`, `shouldAddUniform(String)`; IP mechanism is **YAML/snakeyaml-driven** | S11-B ports fresh: expose IP's API, keep the **mod's programmatic injection** behind it (IP's 1.21.3 YAML regexes will no-op on 26.2 shaders → silent clip breakage). Forced deviation, endorsed by disposition §1.7. |
| `PortalRenderBuffersPool` | `com.warwa.…PortalRenderBuffersPool` (standalone) — `acquire()`/`release()`/**`endFramePooled()`**, `CAP=2`, `new RenderBuffers(4)` | IP has **no such class** — pool is private inline on `MyGameRenderer` (`acquireRenderBuffersObject`/`returnRenderBuffersObject`, `secondaryRenderBuffers` stack, `new RenderBuffers(0)`) | S11-B folds IP's private pool INLINE into qouteall `MyGameRenderer` **verbatim** + carries `endFramePooled()` as an additive 26.2-required method wired to the render TAIL. Do NOT delegate the qouteall renderer to the mod's standalone pool. |
| `lateUpdateLight` (host `MyRenderHelper`) | inline: `PortalWorldManager.lateUpdateSecondaryLight()` (`:1454`) — iterate `levels`, skip `cached==active` + `!isDestScopeLive(dim)`, `runLightUpdates()` at render TAIL | `qouteall.…MyRenderHelper.lateUpdateLight()` (`IP :441`) — iterate `ClientWorldLoader.getClientWorlds()`, skip `RenderStates.isDimensionRendered`, `runLightUpdates()` at render TAIL | S11-B transplants the mod's proven body re-sourced onto `getClientWorlds()` + `isDimensionRendered`. **Drop the mod-only `isDestScopeLive` perf gate** (IP gates only on `isDimensionRendered`); keep `isDestScopeLive` in the block-era path only. |

**`DimensionRenderHelper` (Slice A — the one carriage item actually authored here):**
- Mod-proven source re-homed: `com.warwa.seamlessportals.render.DimensionRenderHelper` — the proven
  `Lightmap` + `LightmapRenderState` extraction (`updateAndRender(Camera,float)`, block-light flicker,
  `attributeProbe()`-driven sky/ambient/night-vision, `Lightmap.render(state)`).
- Contract satisfied (already fixed by ClientWorldLoader S10, `:166-173,439-486`): ctor `(Level)`;
  `public final Level world`; `public final Lightmap lightmapTexture` (retyped from IP's `LightTexture`);
  `void tick()`; `void cleanUp()`.
- **Ownership change:** the mod's static registry (`helpers`/`getOrCreate`/static `cleanup`) is RETIRED;
  per-dim instances now live in `ClientWorldLoader.RENDER_HELPER_MAP` (IP's ownership model).
- **Main vs secondary (the load-bearing reconcile, `held_uar.txt` vs `live_uar.txt`):** the main-dim
  helper reuses the GameRenderer's own `Lightmap` via
  `((GameRendererAccessorMixin) gameRenderer).seamlessportals$getLightmap()` (the exact identity
  ClientWorldLoader's lightmap-conflict guard compares); secondaries get `new Lightmap()`. The main
  helper carries a `null renderState` and is **never re-driven** — `updateAndRender` gains an
  `if (renderState == null) return;` guard (the sole delta between `held_uar.txt` and the live
  `live_uar.txt` body) so the shared main lightmap is never double-driven.
- **tick-side drive relocation:** IP's `LightTexture.tick()` self-recomputed against the then-swapped
  world; 26.2's recompute is `Lightmap.render(LightmapRenderState)` fed by an extract from the DEST
  virtual camera's `attributeProbe()` — inputs a no-arg `tick()` cannot supply. The drive is
  `updateAndRender(virtualCamera, partialTicks)` at the render-context switch (S13 wiring); `tick()` is
  the IP-contract method ClientWorldLoader calls per frame, now a guarded no-op.
- **§1.8 tick-side weather/skyDarken parity — HELD:** IP's 1.21.3 sky-darken/ambient recompute maps to
  `Camera.attributeProbe()` `SKY_LIGHT_*`/`AMBIENT_LIGHT_COLOR` + `bossOverlayWorldDarkening` on 26.2
  (render-sub G2/G4: the smoothing state moved onto the per-Camera `EnvironmentAttributeProbe`), which
  the mod-proven extraction reads.

**Carriage flags handed to S11-B/S12** (consolidated from Slice B §6):

| ID | Owner | Decision needed |
|----|-------|-----------------|
| B1 | S11-B / S12 | Bind both always-on KEEP mixins to a SINGLE clip-plane source of truth. **Recommend:** qouteall `FrontClipping`/`ShaderCodeTransformation` adopt the mod's `getPlaneX/W` + `UNIFORM_NAME` view-space representation so the mixins are unchanged. Avoid two divergent plane stores. |
| B2 | S11-B | qouteall `FrontClipping.isClippingEnabled` must be **public static boolean** (satisfies held `ClientTeleportationManager.java:462`; mod's `glClipEnabled` is private). |
| B3 | S11-B | `ShaderCodeTransformation` internal mechanism: verbatim IP YAML/snakeyaml (risk: 1.21.3 regexes silently miss 26.2 shaders) vs mod's programmatic injection behind IP's `transform`/`init`/`shouldAddUniform` API. **Recommend the latter**; register as accepted forced deviation. |
| B4 | S0/S11-B/S13 | snakeyaml classpath ONLY if B3 keeps the YAML path (no 26.2 build; adjacent to the F21 autoconfig-stub extension). If mechanism B is chosen, `init()` no-ops and the import is dropped as a D4.3 loader-seam hunk — **no new dep**. Decide with B3. |
| B5 | S11-B | Fold IP's private `acquire/returnRenderBuffersObject` inline into qouteall `MyGameRenderer` + add public `endFramePooled()`; wire from render TAIL. Do NOT delegate to the mod's standalone pool. |
| B6 | S11-B | Register `endFramePooled` as an **additive forced deviation** (IP has no such method) so the verbatim diff-gate does not flag it as an unexplained addition. Add to CUTOVER_SPEC endFrame invariant. |
| B7 | S11-B | qouteall `MyRenderHelper.lateUpdateLight` must NOT carry the mod's `isDestScopeLive` perf gate — IP skips only on `RenderStates.isDimensionRendered`. Keep `isDestScopeLive` in the block-era path only. |

**Anti-deviation guard rail (carry into S11-B/S12 port-notes):**
- `FrontClipping` `Vector4f.mul(Matrix4fc)` = column form M·v, **correct**; never change to `mulTranspose` (D4.4).
- `endFramePooled` (and any per-frame `endFrame()` on pooled/secondary buffers) is **required 26.2 additive** — never drop as "not in IP" (memory `gpu-buffer-leak-endframe`).
- `lateUpdateLight` stays at frame-END, not mid-tick (smooth-lighting artifact; IP's own note).
- **No per-frame `LOGGER` on the render thread in the ports** (memory `render-thread-logging-log4j-stall`). The mod's live classes carry some `LOGGER.info` diagnostics (`FrontClipping.setupOuterClipping` count-gated to 5; `PortalRenderBuffersPool.acquire`; shader-patch dumps) — block-era diagnostics that must NOT be transplanted into the hot-path qouteall ports.

**No source authored in Slice B** — reconciliation captured here, seven flags handed to the owning
stages. The probe is unaffected by the carriage map.

---

## 4. R9 fog ownership — FLAGGED for the S11-B CUTOVER_SPEC

`FogRendererContext` was RE-EXPRESSED onto 26.2, but its LIVE per-layer buffer question is **deferred,
not solved** (per mission — flagged, not solved in S11-A):

- IP's raison d'être (checkpoint/swap vanilla's 6 fog statics) is **moot on 26.2**: `FogRenderer` is a
  rewritten INSTANCE class with no color/biome statics (render-sub G2/G3; smoothing state is now
  `Camera.attributeProbe()`). The context's instance fields (`red/green/blue`, `target/previousBiomeFog`,
  `biomeChangedTime`) are **vestigial snapshots** kept for structural fidelity; `StaticFieldsSwappingManager`
  "compiles as-is" (R9 line 276).
- `getFogColorOf` re-expressed onto instance `setupFog(...).color`. **Ring-buffer-safe for the color
  probe:** `setupFog` only COMPUTES a fresh `FogData` (FogRenderer.java:167-186); it does NOT write the
  single per-frame WORLD ring-buffer slot (that's `updateBuffer`, :188), so this mid-frame color probe
  cannot corrupt later passes. The `FogRenderer` instance is GameRenderer's private field, reached via
  the mod's existing `GameRendererAccessorMixin.seamlessportals$getFogRenderer()`.
- **DEFERRED to S11-B CUTOVER_SPEC (R9 design item — UNKNOWN-NEEDS-DESIGN):** per-dim `FogRenderer`/
  `FogData` **buffer ownership** for the LIVE per-layer fog UBO. The single WORLD ring-buffer slot must
  not be written mid-frame for the dest; a second rendered world needs its own buffer/instance (own
  `FogRenderer` or own `MappableRingBuffer`) — plus `EnvironmentAttributeProbe` + `FogEnvironment`
  isolation (render-sub G2, API_RISKS R9; EXECUTION_PLAN S11 §949-966). This governs the driver core
  (S12/S13), NOT the color probe above. **Lightmap is already solved** by `DimensionRenderHelper` (§3).
- The external hooks (`copyContextFromObject`/`copyContextToObject`/`getCurrentFogColor`, assigned by the
  S12 `MixinFogRenderer`) are retained for the mixin/swap contract; `getCurrentFogColor` is superseded by
  reading `FogData.color` directly and is a pruning candidate at S11-B.

**Handoff line for CUTOVER_SPEC.md (R9 section):** *"Decide per-dim `FogRenderer`/`FogData` buffer
ownership so the WORLD fog ring-buffer slot is never written mid-frame for the secondary; `FogRendererContext`
holds vestigial color-snapshot fields only, the color probe is `setupFog().color` (compute-only, safe),
and lightmap isolation is already solved by `DimensionRenderHelper`."*

---

## 5. A3 DELIVERABLE — `ForceMainThreadRebuild` vs `VisibleSectionDiscovery` comparative read

Scheduled S11 deliverable (render-core A3). **Verdict: the mod's `VisibleSectionDiscovery` budgeted
scheduling is a REQUIRED 26.2 adaptation with no IP counterpart — it CANNOT be re-derived from
`ForceMainThreadRebuild`, and `ForceMainThreadRebuild` is correctly ported verbatim as an inert additive
latch.** They solve different problems:

| | IP `ForceMainThreadRebuild` (46 LOC, ported verbatim here) | Mod `VisibleSectionDiscovery.discoverAndScheduleForPortalView` (block-era, LIVE) |
|---|---|---|
| Mechanism | a per-frame **boolean latch** (`forceMainThreadRebuildForFrames` countdown) | a per-section, time-**budgeted async compile scheduler** folded into the portal-view BFS |
| Granularity | whole-frame "rebuild nearby chunks synchronously" hint | per-section `compileAsync` with inner-cone + frustum cull, dirty-state, one-shot `schedSet` guard, `compileBudgetNs` cap |
| Presupposes | vanilla's OWN rebuild path (setupRender/compileSections) will run the sections | there is NO such vanilla driver for a hand-fed secondary — 26.2's private `compileSections` + one-shot dirty flag strands sections (the OW-holes / consumed-compile-queue root cause) |
| IP's own note | "sometimes effective but not always effective (lighting or uploading delay?)" — additive, non-load-bearing | load-bearing: without it the dest view has holes |

**Consequence: PORT-FORWARD the mod's budgeted scheduling AS-IS** (resolves A3 in favour of the
provisional PORT-FORWARD in current-mod-render §5; the qouteall-named `VisibleSectionDiscovery` lands
S11-B/S12 carrying the mod's mechanism). `ForceMainThreadRebuild` ports verbatim; its 26.2 runtime hook
(a "force main-thread rebuild" toggle read by the section dispatcher) has no direct 26.2 analog and its
effect is intermittent by IP's own admission — runtime relevance is a needs-verification S12/S18 item,
NOT a blocker. Its per-frame `LOGGER.info` fires ONLY on the handful of forced-rebuild frames (gated
`currentFrameForceMainThreadRebuild`), never in steady state — **not** a render-thread-logging-stall
hazard (memory `render-thread-logging-log4j-stall`); kept verbatim per zero-deviation.

---

## 6. Category-(c) resolutions + AW/AT pairs

**No access-widening was required for S11-A.** Confirmed against the working tree: no `.accesswidener`,
no NeoForge `.at`, no `build.gradle`, no accessor-mixin file was modified for this slice.

- **All vanilla 26.2 targets used are already public** or reachable via public field:
  `GameRenderer.mainCamera()` (:657), `GameRenderer.bossOverlayWorldDarkening()` (:653),
  `Camera.position()` (:359), `Camera.update()` (:93), `Minecraft.getDeltaTracker()` (:2693),
  `Profiler.get()` (:47), `Gui.hud` (public field, Gui.java:72), `FogRenderer.setupFog()` (:167),
  `Lightmap.render()` — none needs a widener.
- **Private GameRenderer internals are reached via the mod's PRE-EXISTING `com.warwa` accessor mixin**
  (`GameRendererAccessorMixin`, unchanged): `seamlessportals$getLightmap()` (used by
  `DimensionRenderHelper`), `seamlessportals$getFogRenderer()` (used by `FogRendererContext`),
  `seamlessportals$getMainCamera()`. The held ports call into this existing accessor surface — no new
  accessor added.
- **Raw-GL (R6):** `GlQueryObject`/`QueryManager`/`GLResourceCache` use stable LWJGL `GL15/GL30/GL33`
  + fastutil directly — no vanilla surface, no widener.

**Net category-(c) result:** zero AW/AT pairs; the slice is compile-clean against the existing
accessor/widener surface. (Documented so the S13 SCC-closure diff-gate does not expect an AW/AT hunk
from S11-A.)

---

## 7. Probe-vs-U9-union triage

**Probe = AUTHORITATIVE ledger** (`:common:compileJava` over all held source). Build evidence:

| Build | Task | Result |
|---|---|---|
| Shipping (`s11a_shipping.log` / `shipping_build.log`) | `:fabric:compileJava` (live block-era) | **BUILD SUCCESSFUL, exit 0** — live render intact |
| Test (`test_build.log`) | `:common:test` | **BUILD SUCCESSFUL, exit 0** — `DQuaternionTest` green gate held (the `Pair` reconcile, §2) |
| Probe (`s11a_probe.log` / `probe.log`) | held-source compile | **BUILD FAILED, 195 errors** (expected — the forward-ref ledger) |

**Reduction toward the U9 union:** probe error count dropped **249/260 (S10C) → 195 (S11-A)** — a real
~54-65 error reduction. Resolved this slice: the S10 forward-ref debt on
`context_management.DimensionRenderHelper` (ClientWorldLoader) and the ducks-package `LightTexture`
compile error on `IEGameRenderer`; and Slice C resolved Slice A's same-stage forward-refs to
`ForceMainThreadRebuild` + `QueryManager` (RenderStates no longer errors on them).

**Every S11-A-authored file's remaining errors triaged to documented forward-ref debt — NO translation
slips:**

| File:line | Missing symbol | Resolves at | Documented in |
|---|---|---|---|
| `RenderStates.java:23` | `block_manipulation` pkg (`BlockManipulationClient`) | U11 / S13 | frag forward-ref debt ✓ (EXECUTION_PLAN :981) |
| `RenderStates.java:27` | `mixin.client.particle` pkg (`IEParticle`) | S12 | frag ✓ (EXECUTION_PLAN :981) |
| `RenderStates.java:31,113,139,172` | `render.MyRenderHelper` (+ `.client.*` mis-parse) | S11-B (U9 later commit) | frag ✓ |
| `RenderStates.java:261` | `IEParticle` symbol | S12 | frag ✓ |
| `RenderStates.java:306` | `BlockManipulationClient` symbol | S13 | frag ✓ |
| `PortalRendering.java:17` | `render.VisibleSectionDiscovery` | S11-B (U9 later, `@link` + none-called) | frag ✓ |
| `PortalRendering.java:18,125` | `render.renderer` pkg / `PortalRenderer` | U10 / S12 | frag ✓ |

**Broader probe residue** (all pre-existing forward-ref debt from earlier stages, unchanged by S11-A —
concentrated in the entry-point/config mass that references EVERYTHING held): missing packages
`render.renderer` (10, U10/S12), `commands` (7, U11/S13), `portal.custom_portal_gen` (5, generation),
`block_manipulation` (5, S13), `mixin.client.{accessor,sync,particle}` (2/1/1, S12), `peripheral.wand`
(1, S13), `debug` (1, S13) — top error producers `IPConfig`(61)/`IPModMain`(24)/`IPModMainClient`(22)/
`IPCGlobal`(12) are the held entry points, not S11-A files. **All are documented debt resolving at
S11-B/S12/S13, NOT translation slips** (EXECUTION_PLAN S11 §975-987 forward-ref ledger).

**Triage verdict:** the probe reduced as required toward the U9 union; the residue is the expected
S11-B + U10/U11/U12 forward-ref set; no S11-A-authored class contains an unexplained (non-forward-ref)
compile error.

---

## 8. VERIFICATION TIER

**This assembly + the FixGaps gap-check ran on Opus 4.8 (`claude-opus-4-8`).**

Per `migration-model-tier-policy` (user directive 2026-07-13), **S11 is a designated Fable stage for
adversarial-verify + design** (the hard stages S6/S8/S11/S12/S13). S11-A is the render-context
foundation — the highest sign-sensitivity + most 26.2-divergent third of the render unit — so it is
squarely inside the Fable-verify mandate.

**No evidence of a Fable adversarial-verify pass on the S11-A fragments was found** in the scratchpad
(build logs carry no model marker; the three slice fragments + FixGaps evidence are Opus-tier
work-product). This assembly is Opus. Therefore, per the mission's "queued re-verify if Opus" rule:

> **QUEUED: a Fable (`model:'fable'`) adversarial re-verify of S11-A before S13 SCC-closure consumes
> the held render source.**

Priority re-verify targets (the load-bearing, hardest-to-reverse items):
1. **§2 SIGN-DERIVATION** — the `FrustumCuller` winding chain (corner order + cross-order + Mirror flip
   + half-space corner selection) and `TransformationManager`'s rotation composition/conjugate set. A
   silent sign flip here is a live-render bug class (D4.4) that the flag-OFF shipping build cannot catch.
2. **§3/§4 carriage flags B1-B7 + R9 fog ownership** — the KEEP-mixin single-source-of-truth binding
   and the per-dim fog buffer ownership are UNKNOWN-NEEDS-DESIGN handed to S11-B; a Fable pass should
   pressure-test the recommendations before S11-B commits to them.
3. **§2 landed-dep `Tuple`→`Pair` reconcile** — confirm the sign/order mapping (`getFirst`=pitch)
   against IP `DQuaternion` once more under adversarial read.

Non-load-bearing / already machine-checked (lower re-verify priority): the shipping+test green gates
(§7) and the probe-vs-U9-union triage (§7) are deterministic build facts, not tier-sensitive.

---

## Appendix — S11-A footprint (git working tree)

**Modified (1):** `common/src/main/java/qouteall/imm_ptl/core/ducks/IEGameRenderer.java` (coupled
`LightTexture`→`Lightmap` retype of `ip_setLightmapTextureManager`).

**New held classes (15):**
- `render/context_management/`: `StaticFieldsSwappingManager`, `WorldRenderInfo`, `PortalRendering`,
  `RenderStates`, `CloudContext`, `FogRendererContext`, `DimensionRenderHelper`
- `render/`: `TransformationManager`, `FrustumCuller`, `GlQueryObject`, `QueryManager`, `ForceMainThreadRebuild`
- `render/optimization/`: `GLResourceCache`, `SharedBlockMeshBuffers`
- `mc_utils/`: `WireRenderingHelper`

**Not touched:** any `com.warwa.seamlessportals` render class (live block-era render preserved
identically); the always-on stencil-substrate KEEP mixins (`GlBackendMixin`/`GlConstMixin`/
`RenderTargetMixin`/`GlStateManagerMixin`/`GlCommandEncoderClipMixin`/`ShaderManagerCompilationCacheMixin`)
stay bound to their live sources in both flag states.
