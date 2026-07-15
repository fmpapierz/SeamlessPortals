# S11-C — R3 renderers (the final third of U9): CrossPortalEntityRenderer + the S11 render leaf classes

**Stage S11-C** closes U9 (EXECUTION_PLAN §901-991, commit line (c)-5:
"`CrossPortalEntityRenderer` (on the R3 compile surface) + `GuiPortalRendering` + `ViewAreaRenderer`",
extended by the round-3 owning-stage assignments §912-923 to the full leaf set below). It ports the
one class the whole migration flagged as *cannot compile verbatim* — IP's `CrossPortalEntityRenderer`,
whose clip bracketing is forced out through mid-batch `BufferSource.endBatch()` splits and a private
`LevelRenderer.renderEntity` duck (both GONE on 26.2, render-core G2/G3/G23) — onto the R3
compile-surface designed this stage in `port-notes/S11-R3-clip-bracketing.md`, plus the remaining S11
render classes re-expressed onto the mod's proven 26.2 submit → prepare → execute mechanics.

**ZERO deviation for IP logic; re-expression only onto 26.2 render APIs per the api-maps.** All authored
classes are held/inert until S13 (`ip_scc_closed` held-source filter); **no live
`com.warwa.seamlessportals` render file was edited** (`git diff HEAD -- common/.../com/warwa` empty);
shipping `:common` + `:fabric` stay GREEN; `:common:test` (DQuaternion gate) GREEN.

**Citation conventions:** `IP:` = 1.21.3 source (`C:/Users/warwa/ModDev/ImmersivePortalsMod/...`),
`26.2:` = `mc262-ref`, `MOD:` = `common/src/main/java/com/warwa/seamlessportals` (the live block-era
render, byte-untouched), `MOD-qouteall:` = the held `qouteall.imm_ptl.core.*` ports. Every 26.2 API/sign
was re-verified in `mc262-ref` this run (D4.4). This note assembles the three working fragments
(`fragments/S11C-crossportal.md`, `S11C-entityrenderers.md`, `S11C-viewgui.md`), which are deleted on
assembly.

---

## 1. The R3 design decision + the A/B switch contract (D7 deliverable)

**The R3 compile-level design round is delivered in its own document:
`migration/port-notes/S11-R3-clip-bracketing.md`** (this stage's D7 deliverable; API_RISKS R3;
EXECUTION_PLAN §929-940). That document is authoritative for the mechanism design; this section states
only the landed decision and the binding switch contract.

### 1.1 The decision

IP's per-entity clip needs three behaviours off `collidedEntities` (S11-R3 §0): CASE 1 outer-clipped
main-pass draw, CASE 2 destination-side projection with the colliding portal's inner clip, CASE 3
whole-pass inner clip during a portal-view render. CASE 3 is already covered by the mod's live persistent
plane store (`MOD:FrontClipping.java:211-215`, runtime-proven). CASES 1/2 need a per-entity draw-call
bracket that 26.2's submit → prepare → execute model does not give for free (there is no mid-batch
flush). Two mechanisms were fully designed against `mc262-ref`:

- **Mechanism A — `SUBMIT_ORDER_UNIFORM` (the landed DEFAULT).** Route the clipped entity's submits into
  a dedicated **order band** in the pass's `SubmitNodeStorage`; a small `executePhase` HEAD/RETURN mixin
  (S12) pushes the entity's captured clip plane onto the proven `FrontClipping` store for exactly that
  band's phase groups; the always-on `GlCommandEncoderClipMixin` uploads it per draw. Order boundaries
  ARE draw-call boundaries on 26.2 (S11-R3 §1.1) — the 26.2-native equivalent of IP's `endBatch` split,
  with ZERO new GL surface.
- **Mechanism B — `ISOLATED_STORAGE_BRACKET`.** Submit the clipped entity into its own scratch
  `SubmitNodeStorage`, then `capture → feed the plane → ownDispatcher.renderAllFeatures(scratch) →
  restore` around an immediate draw — the closest analog of IP's "draw NOW". Requires a second
  `FeatureRenderDispatcher` + a dedicated `RenderBuffers` (the main `PreparedFrame` throws
  `IllegalStateException("PreparedFrame already in use")` if re-entered,
  `26.2:FeatureRenderDispatcher.java:187-190`) and an unresolved GPU-safe call-site.

**DEFAULT = A** on the S11-R3 §5 evidence (rides the already-proven per-draw upload hook; public
vanilla-native order surface; keeps every submit in its correct framegraph pass; one small mixin vs B's
second dispatcher + buffers + call-site decision).

### 1.2 The A/B switch contract (BINDING C4 rider — surfaced to the user at S18)

Checkpoint C4's rider (2026-07-14, BINDING): **neither mechanism is hard-committed; the user will
personally live A/B-test BOTH at S18.** Honoured as landed by:

- **Both mechanisms are always compiled and always wired** — the seam class `PerEntityClipBracket` owns
  both; `CrossPortalEntityRenderer` never branches on the mechanism (D3: the ported IP body stays
  flag-clean; the seam dispatches). Each path is inert when not selected (A: two map-miss branches per
  phase; B: an empty deferred-bracket list).
- **The switch is the live-read field `IPGlobal.crossPortalEntityClipMechanism`**
  (`MOD-qouteall:IPGlobal.java:67-68`, enum `PerEntityClipBracket.Mechanism`, default
  `SUBMIT_ORDER_UNIFORM`), read every frame by `PerEntityClipBracket.getMechanism()`
  (`:85`). Flipping it is a **no-restart, no-re-porting** switch — the rider's "documented one-line
  switch," satisfied as landed.
- **NOT yet landed (deferred to S12, verifier-sanctioned):** the `IPConfig` persistence field +
  assignment (mirroring `correctCrossPortalEntityRendering` at `IPConfig:40/:179`) and the matching
  `@ConfigEntry.Gui.EnumHandler` in-game config-screen entry. `IPConfig` currently carries pre-existing
  autoconfig-dependency probe debt, so adding an enum field there is safest done alongside the S12
  client-mixin/config-screen wiring, not in S11-C. Until then the S18 tester flips the `IPGlobal` field
  directly. This deferral is recorded in S11-R3 §5 and is the disposition of verifier V1 P3.

> **S18 A/B instructions (from S11-R3 §5):** stand an entity (and yourself, third-person) halfway
> through a portal; inspect both sides + the projection under each mechanism. Judge clip-edge
> correctness at the portal plane, translucent parts, fabulous graphics mode, mirrors, draw-order
> flicker. The loser's code is not removed until the C4 decision is recorded (post-C4 cleanup rides
> S20). **The open memory item "entities invisible THROUGH the portal window" is expected to be RESOLVED
> by this class going live — verify at S18** (S11-R3 §6.4).

---

## 2. CrossPortalEntityRenderer — surface mapping + SIGN notes (Slice A)

IP's 428-line class ported 1:1 onto the R3 compile surface. `MOD-qouteall:render/CrossPortalEntityRenderer.java`
(532 lines with the expanded fidelity javadoc). The two GONE mechanisms (`endBatch` splits + the
`renderEntity` duck) are re-expressed onto the seam; **every IP LOGIC path is byte-preserved.**

### 2.1 Member-by-member fate (design §3.4)

| IP member (`IP:…`) | Fate on 26.2 |
|---|---|
| `client`, `collidedEntities`, `isRenderingEntity{Normally,Projection}`, `init`, `cleanUp`, `onClientTick`, `onEntityTickClient` | **VERBATIM** (events + `IEEntity` duck all landed) |
| `onBeginRenderingEntitiesAndBlockEntities(Matrix4f)` (`:80-89`) | **VERBATIM body** — CASE 3 whole-pass inner clip via the S11-B `FrontClipping` bridge (no batch split). The S12 HEAD anchor calls this AND, separately, `PerEntityClipBracket.onFrameSubmitBegin(storage)` (kept out of this method to preserve the verbatim body) |
| `isCrossPortalRenderingEnabled` (`:91-96`) | **VERBATIM** (`IrisInterface.invoker` from S4; `IPGlobal.correctCrossPortalEntityRendering`) |
| `onEndRenderingEntitiesAndBlockEntities` (`:98-108`) | logic **VERBATIM** minus the implicit draw-flush; **signature adapted** to `(EntityRenderDispatcher, CameraRenderState, PoseStack, SubmitNodeStorage)` so CASE 2 routes through the seam |
| `beforeRenderingEntity`/`afterRenderingEntity` (`:110-143`) | **FUSED** into `submitMainPassEntity(...)`; the collidedEntities + portalCollisions decision is VERBATIM; the two `endBatch()` splits (`:123,:139`) + mid-batch `setupOuterClipping` collapse into the seam's per-order draw-call separation; returns `boolean handled` for the S12 submit `@WrapOperation` anchor |
| `renderEntityProjections`/`renderProjectedEntity`/`hasIntersection` (`:147-216`) | logic **VERBATIM** (Mirror skip, dim match, flipped/reverse + isHidden rough checks); the `:206-214` disable/endBatch/setupInnerClipping collapse — the inner clip plane becomes the ARGUMENT threaded to the submit (else-branch → `collidingPortal.getInnerClipping()`; isRendering-branch → `null`, see §2.3) |
| `renderEntity` (`:218-314`) | **the duck kill** (§2.2). All gating (LocalPlayer valve, `renderYourselfInPortal`, `getDoRenderPlayer`, first-person valve, bounding-box camera check, `isOtherSideBoxInside`) + camera-pos substitution + pose transform **VERBATIM**; `((IEWorldRenderer)levelRenderer).ip_myRenderEntity(...)` + `consumers.endBatch()` (`:301-308`) → `dispatcher.extractEntity(entity, partialTick)` + `PerEntityClipBracket.submitProjectedEntityClipped(...)` |
| `setupEntityProjectionRenderingTransformation` (`:316-337`) | **VERBATIM** (pure PoseStack math) |
| `shouldRenderPlayerDefault`/`shouldRenderEntityNow`/`shouldRenderPlayerNormally`/`getRenderingCameraPos` (`:339-427`) | **VERBATIM** (consumed by the §5 gate mixins; 26.2 renames `getMainCamera().getPosition()` → `mainCamera().position()`) |

**26.2 signature adaptations are forced by the submit model, NOT IP-logic deviations:** the WorldRenderer
entity-loop hooks IP fed from immediate-mode rendering now receive the submit context (dispatcher / cam /
storage). The IP logic inside every one is byte-preserved.

### 2.2 The duck kill (render-core G3)

`IEWorldRenderer.ip_myRenderEntity` (the private `LevelRenderer.renderEntity` duck) is DEAD. The
projection draw is now `EntityRenderState state = dispatcher.extractEntity(entity,
RenderStates.getPartialTick())` (`26.2:EntityRenderDispatcher.java:132` — public; extraction here is
render-thread, same as IP's immediate render) + `EntityRenderDispatcher.submit(state, cam, x, y, z,
poseStack, collector)` (`:147` — public). The dead duck method **is DELETED from
`MOD-qouteall:ducks/IEWorldRenderer.java` at S11-C** (grep-verified: nothing references it; its
`MultiBufferSource` parameter type is GONE on 26.2, so keeping it stranded 2 permanent probe errors —
deleting it, plus its now-unused `MultiBufferSource`/`PoseStack` imports, retired them; this is the
179 → 177 javac reduction, verifier V2 P1). The `MixinMultiBufferSourceBufferSource` "TARGET-GONE" row
is likewise retired: no buffer-source mixin is needed on 26.2.

### 2.3 The R3 mechanism realization — a recorded design-premise correction (`OffsetStorage`)

The S11-R3 §1.1 sketch asserted "`SubmitNodeCollection` implements the collector interface, so a
per-order collection can be passed anywhere a `SubmitNodeCollector` is expected … a one-entity order
contains only that entity's submits." **Direct 26.2 compile disproved BOTH halves; corrected without
changing the mechanism's intent** (S11-R3 §1 AMENDMENT block records this too):

1. **`SubmitNodeCollection` is NOT a `SubmitNodeCollector`.** It `implements OrderedSubmitNodeCollector`
   (`26.2:SubmitNodeCollection.java:53`); `SubmitNodeCollector extends OrderedSubmitNodeCollector` and
   ADDS `order(int)` (`26.2:SubmitNodeCollector.java:9-10`). A per-order collection lacks `order(int)`
   and cannot be passed to `EntityRenderDispatcher.submit(…, SubmitNodeCollector)` (`:147-148`) — the
   initial compile slip (`incompatible types: SubmitNodeCollection cannot be converted to
   SubmitNodeCollector`).
2. **One entity spans SEVERAL relative orders.** 26.2 uses `order()` for INTRA-entity layering:
   eyes/emissive/collar/wind → `order(1)`, some inner layers → `order(-1)`
   (`SulfurCubeInnerLayer.java:53`), banner/shield masks → `order(size+1)` (`BannerRenderer.java:209`,
   `ShieldSpecialRenderer.java:76`). "One order per entity" would silently drop layered submits.

**The fix — `OffsetStorage`** (`SubmitNodeStorage` subclass, `PerEntityClipBracket.java:303`): overrides
ONLY `order(int)` to redirect into a target (main) storage's dedicated **band** — `order(r)` →
`main.order(base + r)`; every direct collector method delegates to `this.order(0)`
(`26.2:SubmitNodeStorage.java:40-148`) → `main.order(base)`. One entity's ENTIRE multi-order structure
lands under a collision-free absolute band `[base + minRel, base + maxRel]`, preserving intra-entity
ordering, keeping every submit in its correct framegraph pass; the clip plane registers against exactly
the collections the entity touched (`OffsetStorage.touched`). `INITIAL_CLIP_ORDER = 1000` (well above
vanilla's ~`[-1, ~20]`) spaced by `CLIP_ORDER_STRIDE = 64` — no collision with vanilla or between clipped
entities. This is a strictly MORE faithful realization of the endBatch-split intent than the single-order
sketch — no IP logic changed; the plumbing was corrected to the real 26.2 data model. Mechanism B is
unaffected (it submits into its own whole `SubmitNodeStorage`, which IS a `SubmitNodeCollector`).

### 2.4 SIGN notes (D4.4)

- **View-space plane capture (the only added sign surface).** `FrontClipping.captureOuterClipping` /
  `captureInnerClipping` (`MOD-qouteall:render/FrontClipping.java:248/:270`) reuse IP's EXACT plane
  source + kept-half-space math (`getClipEquationOuter`/`getClipEquationInner`, correction 0) and the
  EXACT column-form rotation of the S11-B bridge feed (`toViewSpaceSnapshot`, `:291`):
  `planeXYZ = R·n` via `new Vector4f(nx,ny,nz,0).mul(viewRotation)` = **M·v (column form)** — the S11-A
  anti-"fix" guard applies (**never `mulTranspose`**: it inverts the rotation and sign-flips the plane;
  the `IP_DEVIATIONS_ANALYSIS` "row-vector" note is WRONG). `planeW = c`. The `w=0` normal drops any
  translation column, so passing a full model-view still rotates the normal correctly. These methods
  **RETURN a Snapshot instead of feeding the live store** (the seam pushes it around the entity's own
  draws, not into the ambient store at submit time) — zero live edits to the `MOD:FrontClipping` store.
- **The view rotation R** is `CameraRenderState.viewRotationMatrix` (`26.2:CameraRenderState.java:30`) —
  the world→view rotation the 26.2 model-view stack applies to camera-relative submit poses at draw
  (`LevelRenderer.render:170-172` pushes `modelViewMatrix`). Its exact equivalence to the pushed matrix +
  the scaling-portal edge (non-unit `nView` under a scaling model-view) are S18 runtime checks
  (S11-R3 §6; S11-B §3.1 — the mod's rotation-only `viewRotation` refinement is the driver-core answer,
  not "fixed" here).
- **The projection camera-pos substitution is byte-preserved.** The seam submits at `state.{x,y,z} −
  newCameraPos.{x,y,z}`; since `state.{x,y,z}` (extracted, interpolated world pos) ≈ `entityInstantPos`
  and `newCameraPos = entityInstantPos − transformPoint(entityInstantPos) + cameraPos`, the render
  position collapses to `transformPoint(entityInstantPos) − cameraPos` — the destination-side projection
  relative to the main camera, identical to IP feeding `newCameraPos` into `ip_myRenderEntity`. The clip
  plane's `c` (main-camera-relative) cancels the main-camera term, so the kept half-space is
  `n·(projectedWorld − clipPoint) > 0`, IP-faithful.
- **No depth/winding constant** is introduced (R5 reversed-Z lives in `RendererUsingStencil`, S12).

### 2.5 CASE-2 clip-side fidelity (verifier V1 P1 — the highest-priority sign/fidelity flip)

IP disables clipping at the top of `onEndRenderingEntitiesAndBlockEntities` (`IP::101`
`FrontClipping.disableClipping()`) BEFORE `renderEntityProjections` (`:107`); the **isRendering-branch**
of `renderProjectedEntity` (`:184-204`) then sets NO clip, so IP draws those projections **UNCLIPPED**
(the flipped/reverse/isHidden rough checks substitute for clipping — IP's own ":186-187 two culling
planes workaround" comment). On 26.2 the ambient plane store during a dest pass is the **persistent
CASE-3 inner clip** (S11-R3 §0.1: the store persists for the entire dest pass), so registering "nothing"
would clip these projections — the OPPOSITE of IP. **Fix:** the isRendering-branch passes `innerClipPlane
= null`, which the seam registers as an **EXPLICITLY DISABLED Snapshot** (`DISABLED_CLIP = new
Snapshot(0,0,0,1, enabled=false)`, `PerEntityClipBracket.java:109/:227`) — NOT the ambient dest clip.
`com.warwa`'s `restore()` honours `enabled=false` (raw `glDisable(GL_CLIP_DISTANCE0)`), so the band draws
unclipped exactly as IP. Applied under BOTH mechanisms (A: band phases register `DISABLED_CLIP`; B: the
bracket restores it). The else-branch (`:205-215`, not portal-rendering) still threads
`collidingPortal.getInnerClipping()` — and IP's own `setupInnerClipping(null) → disableClipping` maps to
the same DISABLED snapshot when `getInnerClipping()` is null, so that path is faithful too. The false
IP-parity comments were corrected in `CrossPortalEntityRenderer:293`, the
`PerEntityClipBracket.submitProjectedEntityClipped` javadoc + `DISABLED_CLIP` comment, the
`FrontClipping.captureInnerClipping` javadoc, S11-R3 §3.4, and this fragment's §1 row.

### 2.6 Nested-pass scoping (verifier V1 P2 — MAJOR-latent live regression, hardened in-class)

The dest pass is invoked MID-main-framegraph (`StencilPortalRenderer.renderOnePortal` at
`AFTER_TRANSLUCENT_TERRAIN`, `PortalContextSwitch:821`), so a nested dest `LevelRenderer` pass's
`submitEntities`-HEAD fires BETWEEN the outer main pass's submit and its late-phase execute. A naive
"one static registry cleared at each `submitEntities`-HEAD" is UNSAFE: a global `phaseRegistry.clear()`
there wipes the outer pass's not-yet-executed registrations (Mechanism A: its clipped entity's
late-phase submits then draw UNCLIPPED); a global `deferredBrackets.clear()` deletes the outer pass's
Mechanism-B brackets before its draw site runs (those entities VANISH). **Hardened:** all frame state is
now per-`SubmitNodeStorage` (`PassState`: `nextClipOrder` + registered-phase list + `deferredBrackets`,
`PerEntityClipBracket.java:131-137`); the global `phaseRegistry` is cleared **non-destructively** —
`onFrameSubmitBegin(storage)` removes ONLY the ending pass's own prior-frame phases (`:172`
`phaseRegistry.remove(phase)`), safe because phase objects are storage-unique (each order owns distinct
phase instances, stable across frames since `drainPhases` removes only never-filled collections).
`drawBracketedEntitiesIfAny(storage)` is per-pass. **S13 wiring requirement (recorded in S11-R3 §1.2.5):**
a discarded secondary renderer's `PassState` must be evicted at the
`CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` hook (alongside `collidedEntities.clear()`) so the identity maps
don't accumulate dead entries across dimension churn.

---

## 3. The other S11 render leaf classes (Slices B + C)

### 3.1 PortalEntityRenderer — IP `render()` → `submit()` shim (G4)

`MOD-qouteall:render/PortalEntityRenderer.java` (114). 1.21.3's
`EntityRenderer.render(T, yaw, partialTick, PoseStack, MultiBufferSource, light)` + `getTextureLocation`
is GONE; the base is `EntityRenderer<T, S extends EntityRenderState>` with `createRenderState()` /
`extractRenderState(T,S,float)` (game-thread) / `submit(S, PoseStack, SubmitNodeCollector,
CameraRenderState)` (render-thread). IP's whole `render()` body maps 1:1 onto `submit()`:

| IP `render()` op (`:35-48`) | 26.2 form in `submit()` |
|---|---|
| `IPCGlobal.renderer.renderPortalInEntityRenderer(portal)` | verbatim (reads `state.portal`); forward-ref `PortalRenderer` (U10/S12). No-op in every shipping renderer (grep-verified) — retained as the IP hook |
| `if (OverlayRendering.shouldRenderOverlay(portal)) OverlayRendering.onRenderPortalEntity(portal, matrixStack, bufferSource)` | `onRenderPortalEntity(portal, poseStack, submitNodeCollector)` — `MultiBufferSource` arg → `SubmitNodeCollector` |
| `if (debug && !isRendering()) { … WireRenderingHelper.renderPortalShapeMeshDebug(matrixStack, c, portal); }` | `submitCustomGeometry(poseStack, RenderTypes.lines(), (pose,buffer) -> { PoseStack local=new PoseStack(); local.last().set(pose); WireRenderingHelper.renderPortalShapeMeshDebug(local, buffer, portal); })` — the closure re-seeds a fresh `PoseStack` from the one snapshotted pose because the helper takes a full `PoseStack` (debug-only, off by default) |
| `super.render(...)` | `super.submit(state, poseStack, submitNodeCollector, camera)` |

**NEW required render state:** `PortalEntityRenderState extends EntityRenderState` (nested static, one
`public Portal portal` field) — the same "own the field the old base supplied" pattern as `ImmPtlViewArea`
(S11-B §1). `getTextureLocation` (IP returned `null`) is **DROPPED, not re-homed** (absent from the 26.2
base contract).

### 3.2 OverlayRendering — the GONE block-model/vertex family re-expressed (G33/G34)

`MOD-qouteall:render/OverlayRendering.java` (219). TRAILING periphery (CUTOVER_SPEC §6.5); consumers
`BreakablePortalEntity`/`BlockPortalShape` are the S13 (U12) closure slice. Every GONE 1.21.3 API is
re-expressed onto its 26.2 equivalent so the probe carries ONLY the documented `BreakablePortalEntity`
forward-refs, never a GONE-symbol translation slip:

| IP call | 26.2 re-expression | Map |
|---|---|---|
| `Sheets.translucentCullBlockSheet()` | `RenderTypes.translucentMovingBlock()` (block-atlas translucent WITH cull on `RenderPipelines.TRANSLUCENT_BLOCK`, the falling/moving-block lineage matching IP's `FallingBlockRenderer` template) | G33 |
| `blockRenderManager.getBlockModel(state)` → `BakedModel` | `getModelManager().getBlockStateModelSet().get(state)` → `BlockStateModel` | G34 |
| `model.getQuads(state, dir, random)` ×3 | `model.collectParts(random, parts)` → `part.getQuads(dir)` per `BlockStateModelPart` (`state` param baked in; `random` consumed once by `collectParts`) | G34 |
| `Direction.getNearest(nx,ny,nz)` | `Direction.getApproximateNearest(nx,ny,nz)` (double-arg `getNearest` GONE) | C (rename) |
| `buffer.putBulkData(pose, quad, {1,1,1,1}, 1,1,1, opacity, {14680304×4}, NO_OVERLAY, true)` | `buffer.putBakedQuad(pose, quad, quadInstance)` with `QuadInstance.setColor(ARGB.white(opacity))` + `setLightCoords(14680304)` + `setOverlayCoords(NO_OVERLAY)` | G34 |
| `quad.getSprite()` | `quad.materialInfo().sprite()` | C |
| `vertexConsumerProvider.getBuffer(layer)` + per-quad loop | `submitCustomGeometry(matrixStack, layer, (pose,buffer)->{…})` — one call per area block | headline 1 |

**Color fidelity (the one sign-adjacent check):** IP's `brightness={1,1,1,1}` (identity AO/shade
multiplier) × `(r,g,b)=(1,1,1)` with `a=opacity` = white-with-opacity; `ARGB.white((float)opacity)` +
`putBakedQuad` (no shade multiply) gives the **identical result**. Light `14680304` + `NO_OVERLAY` carry
verbatim. Stock vanilla translucent pipeline → its reversed-Z compare is the 26.2 default
`GREATER_THAN_OR_EQUAL` automatically; **no manual depth/sign surface** (IP did none in the overlay path).
One `submitCustomGeometry` per area block because the snapshot captures ONE pose and IP mutated the
`PoseStack` per block (`translate(blockPos − portalPos)` + optional `overlay.rotation()`). IP's dead
`cameraPos` local (`:111`) kept **verbatim**.

### 3.3 LoadingIndicatorRenderer — empty submit

`MOD-qouteall:render/LoadingIndicatorRenderer.java` (47). IP's `render()` body is entirely commented out
(label rendering disabled). Faithful port: `createRenderState()` → `new EntityRenderState()`;
`submit(...)` **overridden empty** (also suppressing the base leash/name-tag submission, reproducing IP's
`render` that never called `super`). `getTextureLocation` DROPPED. Compiles with ZERO errors
(`LoadingIndicatorEntity` held S6).

### 3.4 CrossPortalViewRendering — VERBATIM + 3 renames + 1 GONE re-expression

`MOD-qouteall:render/CrossPortalViewRendering.java` (186). IP third-person/bob-through-portal cross-portal
view path (pinned by `MixinGameRenderer.java:33,:155`, U10/S12). Byte-for-byte except:

| IP call | 26.2 re-expression | Map |
|---|---|---|
| `client.cameraEntity` (field, 3 sites) | `client.getCameraEntity()` (field GONE, getter survives) | C (field→getter) |
| `camera.getPosition()` | `camera.position()` | 26.2:Camera.java:359 |
| `camera.setup(level, entity, thirdPerson, frontView, partialTick)` | `camera.setLevel(client.level); camera.setEntity(entity); camera.update(client.getDeltaTracker());` | G21 |

**The `setup`→`update` FRESH-camera re-expression** (the one non-trivial hunk): `update()` is guarded
`if (minecraft.player != null && this.level != null)` and reads `this.entity`, so a fresh `new Camera()`
must have level+entity installed FIRST. detached/mirrored are no longer parameters — `update()` →
`alignWithEntity()` reads them from `options.getCameraType()`, the EXACT booleans IP computes
(`isThirdPerson()==!getCameraType().isFirstPerson()`, `isFrontView()==getCameraType().isMirrored()`), so
behavior-preserving. `ip_setCameraY(cameraY, lastCameraY)` is primed BEFORE `update()` (26.2 `update()`
does not recompute eye offset; `alignWithEntity()` consumes the primed `eyeHeight`/`eyeHeightOld`).
partialTick: `RenderStates.getPartialTick()` == `deltaTracker.getGameTimeDeltaPartialTick(true)`. **No
sign/winding surface** — the Vec3 math + `dest-world clip(ClipContext)` (surviving 5-arg ctor) is
byte-for-byte IP.

### 3.5 GuiPortalRendering — VERBATIM + the 26.2 FBO/RenderTarget re-expression

`MOD-qouteall:render/GuiPortalRendering.java` (164). Renders a `WorldRenderInfo` into a caller-supplied
`RenderTarget`, deferred to the game-render tail. The 1.21.3 immediate-mode bind/clear model is ALL GONE
(render-core G12/G14; render-sub G8/C7/C8):

| IP call | 26.2 re-expression | Map |
|---|---|---|
| `client.getMainRenderTarget()` (2 sites) | `client.gameRenderer.mainRenderTarget()` | G14/C7 |
| `framebuffer.bindWrite(true)` (×2) | **DROPPED** — 26.2 `RenderTarget` has no bind/FBO-id; the `ip_setFrameBuffer` swap of `GameRenderer.mainRenderTarget` routes the render (LevelRenderer re-reads it each frame; the mod's SecondaryFrameBuffer path already crossed this) | G8/C5 |
| `framebuffer.setClearColor(0,0,0,0); framebuffer.clear(true)` | `useDepth` → `clearColorAndDepthTextures(getColorTexture(), new Vector4f(0,0,0,0), getDepthTexture(), 0.0)`; else `clearColorTexture(getColorTexture(), new Vector4f(0,0,0,0))` | G12/G39/C8 |
| `GlStateManager._colorMask(true,true,true,true)` | **DROPPED** (4-boolean overload GONE; device clear writes all 4 channels unconditionally) | G6 |
| `renderTarget.resize(w, h, true)` | `renderTarget.resize(w, h)` (3rd `clearError` boolean removed) | C-rename |

**SIGN (R5) — the depth clear value.** IP's `clear(true)` cleared depth to 1.0 = FAR under 1.21.3
normal-Z; on 26.2 reversed-Z, FAR = **0.0** (`GameRenderer.java:404-408`; render-sub cross-fact 3 / C8).
The re-expression clears depth to `0.0`. The `useDepth` branch mirrors IP's `RenderTarget._clear` (color
always; depth iff `useDepth`); no defensive null-guard added (kept IP's fail-fast). Everything else
verbatim (`RenderStates.basicProjectionMatrix` reset, `CHelper.checkGlError`, `ip_resetState`, the
`Validate.isTrue` invariants, the `ip_setFrameBuffer` swap/restore, `MyRenderHelper.restoreViewPort`, the
`renderingTasks` HashMap + `_onGameRenderEnd`/`_init`). Imports: removed `GlStateManager`; added
`RenderSystem` + `org.joml.Vector4f`.

---

## 4. PortalEntityRenderer S13-step-5 registration handoff (B4 seam; Appendix A.9)

The renderer registrations ride the **S0 renderer-registration seam** — mod-owned
`PlatformHelper.registerEntityRenderer(EntityType<? extends E>, EntityRendererProvider<E>)` (already
present, `MOD:network/PlatformHelper.java:96`; S00-seam-inventory B4) — **NOT** the unported Fabric
entrypoint `IPModEntryClient.initPortalRenderers` (Appendix A.9). Wired at **S13 step 5**, client-dist
only, UNCONDITIONAL in both flag states (D3 registries-unconditional), mirroring
`IP:IPModEntryClient.java:41-61`:

- `PortalEntityRenderer::new` for the **9-type Portal entity family** (portal / nether_portal variants /
  mirror / global-portal types — the `IPModMain.registerEntityTypes` set),
- `LoadingIndicatorRenderer::new` for `LoadingIndicatorEntity`.

Without this wiring, S13 rung 1 renders no portal at all (EXECUTION_PLAN §3 S13 step 5). The registered
renderers already implement the 26.2 3-method contract (§3.1), so the seam call needs no adaptation.
**This slice does NOT wire it** (S13 owns that); the exact call shape is documented here for the S13
executor.

---

## 5. Per-file diff-gate record

**Footprint (git working tree):** 8 NEW held classes (all `??` untracked) + 3 EDIT (`M`); ZERO
`com.warwa` edits; ZERO `.accesswidener` / NeoForge `.at` / `build.gradle` edits.

| File (`common/src/main/java/qouteall/imm_ptl/core/…`) | Δ | Lines | Disposition |
|---|---|---|---|
| `render/CrossPortalEntityRenderer.java` | NEW | 532 | Slice A — IP 428 ported 1:1 onto the R3 seam; the two GONE mechanisms re-expressed |
| `render/PerEntityClipBracket.java` | NEW | 391 | Slice A — the R3 seam (S11-R3 §3.1); owns BOTH mechanisms + the A/B switch + `OffsetStorage`/`PassState`/`DISABLED_CLIP` |
| `render/PortalEntityRenderer.java` | NEW | 114 | Slice B — `render()`→`submit()` shim + nested `PortalEntityRenderState` |
| `render/OverlayRendering.java` | NEW | 219 | Slice B — GONE block-model/vertex family re-expressed (G33/G34) |
| `render/LoadingIndicatorRenderer.java` | NEW | 47 | Slice B — empty submit (IP render() was commented out) |
| `render/CrossPortalViewRendering.java` | NEW | 186 | Slice C — verbatim + 3 renames + the `setup`→`update` FRESH-camera hunk |
| `render/GuiPortalRendering.java` | NEW | 164 | Slice C — verbatim + the FBO/RenderTarget re-expression + R5 depth-clear-0.0 |
| `mixin/client/render/MixinEntityRenderDispatcher.java` | NEW | 48 | shouldRender gate (render-core S35), 1:1, held-**UNREGISTERED** (§6) |
| `render/FrontClipping.java` | EDIT | 334 | added NON-mutating `captureOuterClipping`/`captureInnerClipping`/`toViewSpaceSnapshot` (return a Snapshot, feed nothing live); additive |
| `ducks/IEWorldRenderer.java` | EDIT | 36 | DELETED the dead `ip_myRenderEntity` duck + its GONE `MultiBufferSource`/`PoseStack` imports (retired 2 probe errors, V2 P1) |
| `IPGlobal.java` | EDIT | 181 | added `crossPortalEntityClipMechanism` field (the C4-rider A/B switch; nested enum is server-safe) |

**The shouldRender gate (render-core S35) — 1:1, held-UNREGISTERED.**
`MixinEntityRenderDispatcher.java` is a signature-identical port of IP's: `@Inject` HEAD cancellable on
`EntityRenderDispatcher.shouldRender(Entity, Frustum, DDD)Z` → `cir.setReturnValue(false)` when
`!CrossPortalEntityRenderer.shouldRenderEntityNow(entity)`. 26.2's target is signature-identical
(`26.2:EntityRenderDispatcher.java:127-130`; erased descriptor matches IP); vanilla's extraction path
calls it (`LevelExtractor.isEntityVisible:254`), so the gate now suppresses EXTRACTION — strictly
upstream of IP's draw suppression, same visibility outcome (the former G24 "GONE" misclassification is
withdrawn). **Authored 1:1 NOW at S11-C, held UNREGISTERED** (`seamlessportals-ip-client.mixins.json`
`"client":[]` untouched — it compiles as ordinary annotated Java in the probe); registered into the
client-mixin set at S12, activated flag-ON at S13. Adjacent `MixinCamera:80-85`'s `isDetached` →
`shouldRenderPlayerDefault` gate is likewise 1:1, S12.

**`git diff HEAD -- common/…/com/warwa` = empty. Shipping `:common` + `:fabric` compileJava =
BUILD SUCCESSFUL** (all held files excluded from the live block-era compile). **`:common:test` = BUILD
SUCCESSFUL** (DQuaternion gate held). Logs: `scratchpad/s11c/{shipping,test,probe}.log`.

---

## 6. Probe vs the U9 union (delta vs 176) + U9-COMPLETE

**Probe (`:common:compileJava -Pip_scc_closed=true`, `--rerun-tasks`) = BUILD FAILED, 177 (javac's own
summary) / 178 (grep).** The +1 grep-vs-javac gap is the documented `Portal.java:69` Gradle
problems-report repeat (S11-B §7 phantom — not a distinct error; compare like-for-like).

**Delta vs the S11-B landed baseline (176 grep / 175 javac): +2 grep / +2 javac.** The raw integer ROSE
by 2 — this is a **reduction toward the U9 union in the ledger sense the plan defines**, not the raw
integer, because these are the LAST U9 leaf classes and they legitimately introduce their own
plan-predocumented forward-refs to not-yet-ported U10/U11/U12 classes. Exact reconciliation:

- **+10 new plan-predocumented forward-refs** materializing as the leaf classes are authored:
  - **+2** `render.renderer.PortalRenderer` (§975) — `CrossPortalEntityRenderer.java:37` (import) +
    `:490` (`PortalRenderer.client`); resolves U10/S12.
  - **+2** `commands.PortalCommand` (§985) — `CrossPortalViewRendering.java:15` (import) + `:83` (call);
    resolves U11/S13.
  - **+6** `nether_portal.BreakablePortalEntity` (+`.OverlayInfo`) (§986) — `OverlayRendering.java`;
    resolves S13 U12 closure slice.
- **−6 resolved prior-stage forward-refs** — the new S11-C classes existing resolves earlier holders'
  unresolved refs (e.g. `GuiPortalRendering` resolves `IPModMainClient`'s 2; the slice-C class resolves
  its holders; etc.), tallied by the verifier gate-criterion note.
- **−2 retired dead-duck errors** (V2 P1) — deleting `IEWorldRenderer.ip_myRenderEntity` removed its 2
  `MultiBufferSource` cannot-find-symbol errors.
- **Net: +10 − 6 − 2 = +2 javac** (175 → 177). The intermediate pre-fix figure was 179 javac (+4); the
  V2 P1 deletion took it to the landed 177.

**Whole-probe signature-category sweep (private-access / incompatible-types / cannot-be-applied /
does-not-override / no-suitable-method / ambiguous) = ZERO.** Every S11-C-authored error is a
cannot-find-symbol / package-does-not-exist forward-ref — no translation slip. `PerEntityClipBracket`,
`FrontClipping`, `MixinEntityRenderDispatcher`, `PortalEntityRenderer`, `LoadingIndicatorRenderer`,
`GuiPortalRendering` compile with ZERO errors of their own; the 2 `CrossPortalEntityRenderer` +
2 `CrossPortalViewRendering` + 6 `OverlayRendering` are the documented forward-refs above.
`IPGlobal`'s 2 probe errors (`:4` `me.shedaniel.autoconfig`, `:17` `ConfigHolder<IPConfig>`) are
**pre-existing** autoconfig-dependency debt — the added `crossPortalEntityClipMechanism` field is
error-free (its nested `PerEntityClipBracket.Mechanism` enum is server-safe: naming the constant never
force-loads the client render class, mirroring IP's nested `IPGlobal.RenderMode`).

**U9 IS COMPLETE.** Every class in the U9 row of the corpus (EXECUTION_PLAN §903-928) is now authored
and held: the S11-A render-context foundation, the S11-B render drivers, and — with this S11-C slice —
`CrossPortalEntityRenderer`, `GuiPortalRendering`, `ViewAreaRenderer` (S11-B), plus the round-3
owning-stage assignments `CrossPortalViewRendering`, `PortalEntityRenderer`, `OverlayRendering`,
`LoadingIndicatorRenderer`. The residual 177 probe errors are entirely forward-refs into U10
(`PortalRenderer` family, S12), U11 (`PortalCommand`, S13), and the U12 closure slice
(`BreakablePortalEntity`, S13) — all plan-documented debt that resolves as those stages land, with a
ZERO signature-category sweep confirming no U9 translation slip remains. The next probe milestone is the
first green build at U11 (S13 SCC closure).

---

## 7. FixGaps / AW-AT record

### 7.1 AW/AT — ZERO pairs

Every consumed/overridden 26.2 member is public non-final:
`EntityRenderDispatcher.extractEntity/submit/shouldRender`, `SubmitNodeStorage.order` + the
`OffsetStorage` override surface, `SubmitNodeCollection.allPhases` + public phase fields,
`FeatureRenderDispatcher` public ctor / `renderAllFeatures`, `CameraRenderState.viewRotationMatrix`,
`EntityRenderState.x/y/z`, `createRenderState`/`extractRenderState`/`submit` (overridable),
`SubmitNodeCollector.submitCustomGeometry`, `VertexConsumer.putBakedQuad`, `QuadInstance` setters,
`ModelManager.getBlockStateModelSet`, `BlockStateModelSet.get`, `BlockStateModel.collectParts`,
`BlockStateModelPart.getQuads`, `Direction.getApproximateNearest`, `ARGB.white`, `PoseStack.Pose.set`,
`RenderTypes.lines`/`translucentMovingBlock`, `GameRenderer.mainRenderTarget`,
`Camera.setLevel`/`setEntity`/`update`/`position`, `CameraType.isFirstPerson`/`isMirrored`,
`RenderTarget.useDepth`/`getColorTexture`/`getDepthTexture`/`resize`,
`RenderSystem.getDevice().createCommandEncoder().clear*`, `Level.clip`, and the `com.warwa` FrontClipping
`capture/restore/disable/Snapshot`. **No new accessor, no widener hunk** — the S13 SCC-closure diff-gate
expects NO AW/AT from S11-C.

### 7.2 The 5 verifier defects — all fixed, zero-deviation, re-verified

| # | Sev | Defect | Fix |
|---|---|---|---|
| V1 P1 | MAJOR | CASE-2 isRendering-branch projections would draw under the ambient dest inner clip (opposite of IP's UNCLIPPED `:101`/`:184-204`) because a null plane registered "nothing" | Register an EXPLICITLY DISABLED Snapshot (`DISABLED_CLIP = Snapshot(…, enabled=false)`) under BOTH mechanisms; `restore()` honours `enabled=false`; corrected the false IP-parity comments (§2.5) |
| V1 P2 | MAJOR-latent | Global static frame state destructively cleared at every `submitEntities`-HEAD would wipe an outer main pass's registrations/brackets across the mid-framegraph nested dest pass | All frame state scoped per-`SubmitNodeStorage` (`PassState`); global `phaseRegistry` cleared non-destructively (removes only the ending pass's own phases); `drawBracketedEntitiesIfAny` per-pass; S13 eviction requirement recorded (§2.6) |
| V1 P3 | MINOR | The C4-rider "config toggle" claim vs no `IPConfig` field | Resolved via the verifier-sanctioned doc-amendment: the live-read `IPGlobal.crossPortalEntityClipMechanism` field is named as the landed interim switch; the `IPConfig` config-screen entry is an explicit S12 task (§1.2; S11-R3 §5) |
| V2 P1 | doc + duck | S11-R3 §4 claimed the duck was already deleted (it was not) — 2 live probe errors | DELETED the dead `ip_myRenderEntity` + its GONE `MultiBufferSource`/`PoseStack` imports (retired the 2 errors, the 179→177 reduction); aligned S11-R3 §4 (§2.2) |
| V2 P2 | doc | S11-R3 §1.1/§1.2 still described the superseded single-order sketch | Added the §1 AMENDMENT block describing the order-BAND + `OffsetStorage` mechanism (§2.3) |

No IP logic changed by any fix; all re-verified against IP source + `mc262-ref`. No S12 classes ported
early (`PortalRenderer`/`RendererUsingStencil`/`RendererUsingFrameBuffer`/renderer mixins remain
forward-refs); no `com.warwa` file, `buildSrc`, or `build.gradle` edited; no git commit; game not run.

---

## 8. VERIFICATION TIER

Assembled on **Opus 4.8** (`claude-opus-4-8[1m]`). Per `migration-model-tier-policy`, **S11 is a
designated Fable adversarial-verify stage** — the escalation is spent on the hard render stages, and it
was exercised: two verify rounds ran against this slice and surfaced **5 defects, all fixed pre-commit**
(§7.2), re-verified against IP source and `mc262-ref`. Post-fix state: shipping `:common:compileJava` =
BUILD SUCCESSFUL (live block-era render byte-untouched); probe `:common:compileJava -Pip_scc_closed=true`
= 177 errors (javac authoritative, DOWN 2 from the 179 landed, forward-ref-only); `:common:test` = BUILD
SUCCESSFUL; signature-category sweep = ZERO; `git diff HEAD -- com.warwa` empty.

**Remaining runtime-verification trail (S13/S18 live checkpoints; none blocks the held S11-C assembly):**

1. **The A/B live verdict itself (checkpoint C4)** — BOTH mechanisms stay wired until the user tests them
   at S18; the "entities invisible THROUGH the portal window" open memory item is expected RESOLVED (§1.2,
   S11-R3 §6.4).
2. **Mechanism B's draw call-site** (S11-R3 §2.1.3 candidates i/ii) + its dedicated `RenderBuffers`
   lifecycle (route through `MyGameRenderer`'s inline pool + `endFramePooled()`).
   `drawBracketedEntitiesIfAny(storage)` is the call-site-independent, per-pass hook.
3. **`viewRotationMatrix` ↔ pushed `modelViewMatrix` equivalence** + the scaling-portal non-unit `nView`
   edge (§2.4; S11-B §3.1).
4. **CASE-3 timing** (the extract-vs-render phase assignment, CUTOVER_SPEC §4) — the submit-time
   `setupInnerClipping`/`disableClipping` bridge anchors vs the driver's persistent store; S13 finalizes.
5. **Per-`PassState` scoping under real per-frame recursion** (§2.6) — confirm the live nested-pass
   behavior at S13 rung 1; wire the `CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` `PassState` eviction.
6. **Order-execution artifacts under A** — outline/fabulous interaction of per-order group execution;
   translucency sort across bands (S11-R3 §6.3).
7. **The FRESH-camera `setup`→`update` re-expression** (§3.4) — does `setEntity(cameraEntity)` + the
   primed `eyeHeight` reproduce IP's third-person pull-back (incl. `getMaxZoom` clip vs the SOURCE level).
8. **GuiPortalRendering** — the R5 depth-clear-to-0.0 branch + the `bindWrite`-drop /
   `ip_setFrameBuffer`-routes-render equivalence under the actual S13 GUI-portal render pass (S18 IP-view
   refinement).
9. **The S13-step-5 renderer registration** (§4) — client-dist, unconditional, through the B4 seam;
   without it rung 1 renders no portal.

The four NEW runtime mixins the design defines (`IEEntityRenderState` tag duck + the two
`submitEntities` anchors + the `executePhase` HEAD/RETURN bracket, S11-R3 §3.2/§3.3/§1.2.4) ride the S12
client-mixin set alongside the 1:1 `MixinEntityRenderDispatcher` gate (§6). **U9 is complete; S11-C
closes the render-foundation third of the migration on the R3 compile surface, zero live regression.**
