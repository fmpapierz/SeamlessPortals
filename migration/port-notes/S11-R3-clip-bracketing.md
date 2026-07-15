# S11 — R3 compile-level design round: per-entity clip bracketing (D7 deliverable)

**Stage S11-C, plan-mandated design round (EXECUTION_PLAN S11 §929-940; API_RISKS R3).** IP's
`CrossPortalEntityRenderer` (428 lines) cannot compile verbatim on 26.2: its clip bracketing is
forced out via mid-batch `BufferSource.endBatch()` splits
(`IP:imm_ptl/core/render/CrossPortalEntityRenderer.java:123,139,208,308`) and its projection draw
goes through the private-`LevelRenderer.renderEntity` duck (`ip_myRenderEntity`, `:301-306`) — both
target GONE APIs (render-core G2/G3/G23). This document is the compile-level design: BOTH candidate
mechanisms fully designed against `mc262-ref` source, the evidence-based default, the A/B switch the
user will live-test at S18 (checkpoint C4 rider, BINDING: neither mechanism is hard-committed; both
stay wireable via one config switch), the compile-surface contract the Translate slice ports the
class onto, and the S18 runtime trail.

**Citation conventions:** `IP:` = 1.21.3 source (`C:/Users/warwa/ModDev/ImmersivePortalsMod/...`),
`26.2:` = `mc262-ref`, `MOD:` = `common/src/main/java/com/warwa/seamlessportals`, `MOD-qouteall:` =
the held qouteall-path ports. All 26.2/mod line numbers below re-verified by direct source read this
run (D4.4).

---

## 0. What IP actually needs (the semantic contract, mechanism-independent)

Three distinct behaviors, all keyed off the `collidedEntities` WeakHashMap (entities currently
colliding with a portal, maintained by verbatim-portable tick logic `IP::63-78`):

- **CASE 1 — outer-clipped main-pass draw** (`beforeRenderingEntity`/`afterRenderingEntity`,
  `IP::110-143`): when NOT portal-rendering, an entity halfway through a portal must render in the
  main pass **clipped by the portal plane** (the crossed part must not draw on this side). IP:
  flush the batch (`endBatch` :123), set `FrontClipping.setupOuterClipping`, let the entity draw,
  flush again (:139), disable clipping. The essential requirement: **that ONE entity's draws form
  their own draw-call bracket with the clip plane active.**
- **CASE 2 — projected entity** (`renderEntityProjections`/`renderProjectedEntity`/`renderEntity`,
  `IP::147-314`): an entity halfway through a portal has a **projection on the destination side** —
  the same entity drawn AGAIN, repositioned via the camera-pos substitution trick (`IP::283-292`:
  `newCameraPos = entityInstantPos − transformPoint(entityInstantPos) + cameraPos`) plus a
  scale/rotation anchor transform on the pose (`setupEntityProjectionRenderingTransformation`,
  `IP::316-337` — pure PoseStack math, ports verbatim), clipped by the colliding portal's
  **inner** plane, drawn immediately (`endBatch` :308) via the renderEntity duck.
- **CASE 3 — whole-pass inner clipping during a portal-view render**
  (`onBeginRenderingEntitiesAndBlockEntities`, `IP::80-89`): when portal-rendering, the whole
  entity pass is clipped by the active clipping plane. **Already covered on 26.2** — the plane
  store persists for the entire dest pass and the always-on `MOD:GlCommandEncoderClipMixin` uploads
  it to every draw (the live `setupInnerClippingForEntities` dest-entity pass is runtime-proven,
  `MOD:render/FrontClipping.java:211-215`). No new mechanism needed; the ported method keeps its
  body (a `FrontClipping.setupInnerClipping` call through the S11-B bridge) and simply no longer
  needs a batch split.

The visibility gates (`shouldRenderEntityNow`, `shouldRenderPlayerDefault`,
`shouldRenderPlayerNormally`, `getRenderingCameraPos`, `hasIntersection`) are pure logic and
**port 1:1** (§4).

### 0.1 The shared 26.2 substrate both mechanisms ride (all runtime-proven live)

- **GLSL clip plumbing:** `MOD:render/ShaderCodeTransformation.java` injects
  `out float gl_ClipDistance[1]` + `uniform vec4 seamlessportals_ClipPlane` (`UNIFORM_NAME`, `:41`)
  and a `gl_ClipDistance[0] = dot(sp_viewPos.xyz, plane.xyz) + plane.w` write after the canonical
  `gl_Position = ProjMat * ModelViewMat * vec4(pos,1)` line (`:78-105`), applied to all
  vanilla-namespaced vertex shaders by the always-on KEEP
  `MOD:mixin/client/ShaderManagerCompilationCacheMixin` (`:37-56`). Entity/feature shaders match the
  canonical pattern — proven by the live dest-entity clip.
- **Per-draw upload:** `MOD:mixin/client/GlCommandEncoderClipMixin.java:30-40` — `@Inject` at
  `GlCommandEncoder.trySetup(GlRenderPass, Collection)Z` RETURN (the private method every draw path
  funnels through: `26.2:com/mojang/blaze3d/opengl/GlCommandEncoder.java:617`, called from
  `:405/:497/:517/:548`), uploading `FrontClipping.getPlaneX/Y/Z/W()` to the location-cached
  uniform. **Both mechanisms use this same upload** — they differ only in WHEN the plane store holds
  the entity's plane relative to which draws execute.
- **The single view-space plane store + Snapshot push/pop:** `MOD:render/FrontClipping.java` —
  `capture()/restore(Snapshot)/disable()` (`:73-89,222-228`), plane form
  `planeXYZ = R·n_world, planeW = signed camera distance` (view-space; the S11-B qouteall
  `FrontClipping` BRIDGE feeds this store with IP's plane semantics, column-form `M·v` — the S11-B
  port-note §3.1 anti-"fix" guard applies to any code this design adds).
- **26.2 entity draw model** (the reason a design round exists at all): entities extract to
  `EntityRenderState` on the extract phase (`26.2:.../extract/LevelExtractor.java:232-249`), submit
  per-state into a `SubmitNodeCollector` (`26.2:LevelRenderer.java:653-662` `submitEntities` — the
  per-entity SUBMIT boundary that replaces IP's per-entity RENDER boundary), get sorted/prepared
  into groups (`26.2:.../feature/FeatureRenderDispatcher.java:77-110`), and draw batched inside
  framegraph passes (`PreparedFrame.executeSolid/executeTranslucent...`, `:197-256`). There is no
  mid-batch flush; **the draw-call boundary must come from the submit-side data model, not from a
  flush call.**

---

## 1. Mechanism A — per-draw clip uniform keyed off SUBMIT-ORDER partitioning (the default)

> **AMENDMENT (implemented — see fragment `S11C-crossportal.md` §2 + `PerEntityClipBracket.OffsetStorage`).**
> Direct 26.2 compile corrected TWO premises of the sketch below; the mechanism's INTENT is unchanged, the
> plumbing is the real data model. Read every "a fresh order `k`" / "one order per entity" / "counter
> starting at 1" below as the order-**BAND** + `OffsetStorage` realization:
> 1. **`SubmitNodeCollection` is NOT a `SubmitNodeCollector`.** It `implements OrderedSubmitNodeCollector`
>    (26.2:SubmitNodeCollection.java:53); `SubmitNodeCollector extends OrderedSubmitNodeCollector` and ADDS
>    `order(int)` (26.2:SubmitNodeCollector.java:9-10). So a per-order collection lacks `order(int)` and
>    cannot be passed to `EntityRenderDispatcher.submit(…, SubmitNodeCollector)`. The collector is instead
>    **`OffsetStorage`** — a `SubmitNodeStorage` subclass overriding ONLY `order(int)` to redirect into a
>    target (main) storage's dedicated band (`order(r) → main.order(base + r)`; the direct collector methods
>    all delegate to `this.order(0)` → `main.order(base)`, 26.2:SubmitNodeStorage.java:40-148).
> 2. **One entity spans SEVERAL relative orders** — 26.2 uses `order()` for INTRA-entity layering
>    (eyes/emissive → `order(1)`, some inner layers → `order(-1)`, banner/shield masks → `order(size+1)`).
>    So "one order per entity" would silently drop an entity's layered submits. Each clipped entity therefore
>    claims a **collision-free absolute BAND** `[base + minRel, base + maxRel]` (base from
>    `INITIAL_CLIP_ORDER = 1000`, spaced by `CLIP_ORDER_STRIDE = 64` — both well above vanilla's ~`[-1, 20]`),
>    and the clip plane registers against exactly the collections the entity touched
>    (`OffsetStorage.touched`). This is a strictly MORE faithful realization of the endBatch-split intent —
>    no IP logic changed. Mechanism B is unaffected (it submits into its own whole `SubmitNodeStorage`, which
>    IS a `SubmitNodeCollector`).

### 1.1 The key 26.2 fact: order boundaries ARE draw-call boundaries

`SubmitNodeStorage` partitions submits by an integer **order**:
`Int2ObjectAVLTreeMap<SubmitNodeCollection> submitsPerOrder`
(`26.2:SubmitNodeStorage.java:33`), `order(int)` returning the per-order `SubmitNodeCollection`
(`:35-37`) — and `SubmitNodeCollection` **implements the collector interface**
(`26.2:SubmitNodeCollection.java:53` `implements OrderedSubmitNodeCollector`), so a per-order
collection can be passed anywhere a `SubmitNodeCollector` is expected (vanilla's own storage-level
methods delegate to `order(0)`, `SubmitNodeStorage.java:40-148`).

Each `SubmitNodeCollection` owns its OWN `FeatureRenderPhase` instances
(`SubmitNodeCollection.java:54-68` — `solid`, `shadows`, `translucentModels`, `nameTags`, ... all
`new` per collection). `PhaseSubmitGrouper` is constructed **per phase object**
(`FeatureRenderDispatcher.java:81` `drainPhases(phase -> phase.sortInto(new PhaseSubmitGrouper(frame,
phase)))`), groups land in `groupsByPhase` keyed by phase identity (`:135`), and execution walks
`submitsPerOrder` collection-by-collection calling `executePhase(collection.<phase>, context)`
(`executeSolid` `:197-204`, `executeTranslucent` `:206-229`, `executeAlwaysOnTop` `:249-256`), which
executes exactly that phase object's groups (`:258-266`). **Therefore: submits placed under a
dedicated order execute as their own group(s), in their own draw calls, at a deterministic point in
each pass — the 26.2-native equivalent of IP's endBatch split.** A one-entity order contains only
that entity's submits (model + flame + shadow + nametag, all routed through the same collector by
`EntityRenderDispatcher.submit`, `26.2:EntityRenderDispatcher.java:147-184`).

### 1.2 Design

1. **Per-frame order allocation.** `CrossPortalEntityRenderer` (the qouteall port) keeps a
   frame-scoped `nextClipOrder` counter starting at **1** (order 0 = all vanilla submits; our orders
   execute after order 0 within each pass — a draw-order perturbation of exactly the same class as
   IP's own endBatch splits) and an `IdentityHashMap<FeatureRenderPhase<?>, FrontClipping.Snapshot>`
   **phase→plane registry**. Both reset at the `submitEntities`-HEAD anchor (the ported
   `onBeginRenderingEntitiesAndBlockEntities`).
2. **CASE 1 (outer clip):** the collided entity's state is tagged at extraction (§3.2 duck). At the
   per-entity submit boundary (§3.3 seam mixin), instead of vanilla
   `dispatcher.submit(state, cam, x, y, z, poseStack, storage)`, the seam calls
   `dispatcher.submit(state, cam, x, y, z, poseStack, storage.order(k))` with a fresh order `k`,
   computes the outer clip plane in view space (IP's `setupOuterClipping` math via the S11-B bridge
   feed, producing a `Snapshot(planeXYZ, planeW, enabled=true)` WITHOUT touching the live store),
   resolves `storage.order(k)`'s phase objects, and registers each in the phase→plane registry.
3. **CASE 2 (projection):** at the `submitEntities`-TAIL anchor (the ported
   `onEndRenderingEntitiesAndBlockEntities` → `renderEntityProjections`), for each qualifying
   collided entity (verbatim IP filters): `state = dispatcher.extractEntity(entity,
   RenderStates.getPartialTick())` (`26.2:EntityRenderDispatcher.java:132` — public; extraction here
   is render-thread, same as IP's immediate render), apply the verbatim pose transform
   (`setupEntityProjectionRenderingTransformation`) + camera-pos substitution, then
   `dispatcher.submit(state, cameraRenderState, state.x − newCameraPos.x, …,
   poseStack, storage.order(k))` and register the **inner** clip plane
   (`collidingPortal.getInnerClipping()`) for that order's phases. This **kills the renderEntity
   duck** — `extractEntity` + `submit` are public (render-core G3). `CameraRenderState` comes from
   `gameRenderer.gameRenderState().levelRenderState.cameraRenderState` (the exact source
   `26.2:LevelRenderer.java:151` uses).
4. **Execution bracket (ONE new mixin):** `@Inject` HEAD+RETURN on the private
   `FeatureRenderDispatcher$PreparedFrame.executePhase(FeatureRenderPhase, FeatureFrameContext)`
   (`26.2:FeatureRenderDispatcher.java:258-266`). HEAD: if the phase is in the registry —
   `prev = FrontClipping.capture(); FrontClipping.restore(registeredSnapshot)` (which also does the
   raw `glEnable(GL_CLIP_DISTANCE0)`, `MOD:FrontClipping.java:85-89`). RETURN: `restore(prev)`.
   Every draw the group issues then funnels through `trySetup` → the always-on
   `GlCommandEncoderClipMixin` uploads the entity's plane; draws of unregistered phases see the
   untouched ambient store. Registry empty (no collided entities, or mechanism B active) → the
   mixin is two map-miss branches per phase per frame — inert.
5. **Scoping/threading (HARDENED against the mid-framegraph nested pass — Verifier-1 P2):** submit and
   execute both run on the render thread. The naive "one static registry cleared at each
   `submitEntities`-HEAD" is UNSAFE: the proven dest-pass invoke site is MID-main-framegraph
   (`StencilPortalRenderer.renderOnePortal` at `AFTER_TRANSLUCENT_TERRAIN`, `PortalContextSwitch:821`), so a
   nested dest `LevelRenderer` pass's `submitEntities`-HEAD fires BETWEEN the outer main pass's submit and
   its late-phase execute (`executeTranslucentAfterTerrain`/`executeAlwaysOnTop`). A global
   `phaseRegistry.clear()` there would WIPE the outer pass's not-yet-executed registrations (its clipped
   entity's late-phase submits would then draw UNCLIPPED); a global `deferredBrackets.clear()` would delete
   the outer pass's Mechanism-B brackets before its draw site runs (those entities would VANISH). So the
   seam scopes state **per-`LevelRenderer`-instance** keyed by the pass's `SubmitNodeStorage` identity
   (`PassState`): each pass owns its own band counter + `registeredPhases` list + `deferredBrackets`;
   `onFrameSubmitBegin(storage)` resets ONLY that pass's state and removes ONLY its own prior-frame phases
   from the (now non-destructively-shared) global `phaseRegistry`. Phase objects are storage-unique (each
   order owns distinct phase instances) so the global registry holds every live pass's entries at once,
   keyed by identity, with no cross-pass collision; `SubmitNodeCollection` objects persist in the AVL map
   across frames once used (`drainPhases` removes only never-filled collections,
   `SubmitNodeStorage.java:154-167`), so phase identity is stable. `passStates` is bounded by the number of
   live `LevelRenderer` instances (main + cached secondaries); a discarded secondary renderer's `PassState`
   (and its last-frame `phaseRegistry` entries) would otherwise linger — the **S13 wiring must evict a
   removed dimension's storage** at the client-cleanup / `CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` hook
   (alongside `collidedEntities.clear()`) so the identity maps don't accumulate dead entries across
   dimension churn. `nextClipOrder` is per-`PassState` (submits never overlap across passes — a nested
   pass's submit runs during the outer pass's EXECUTE, not its submit). Mechanism B's draw hook is likewise
   per-storage (`drawBracketedEntitiesIfAny(storage)`).
6. **View-space plane correctness:** the main pass draws with `ModelViewMat` = the view rotation
   (`26.2:LevelRenderer.java:170-172` pushes `modelViewMatrix` onto the model-view stack; submit
   poses are camera-relative world), so `sp_viewPos = R·p_rel` and the registered
   `Snapshot(R·n, c)` evaluates IP's exact kept half-space `n·p_rel + c > 0` — identical derivation
   to the S11-B bridge feed (S11B-render-drivers.md §3.1; column-form guard applies).

### 1.3 Fidelity notes (deviations register, both mechanisms share these)

- **Tighter-than-IP clip scope (strict improvement, still register-worthy):** IP's `endBatch`
  flushed EVERYTHING buffered so far, so IP's clip technically applied to any stragglers buffered
  after the flush; A/B scope the clip to EXACTLY the one entity's submits — a more precise
  realization of the same intent. No visible regression direction.
- **Draw-order perturbation:** the clipped entity draws after order-0 entities within each phase
  (A) / at the bracket point (B). IP's endBatch splits perturbed draw order the same way.
  Translucency sorting across orders is coarser than within one phase — same class of artifact IP
  accepted.
- **CASE 1 multi-collision loop (`IP::119-126`):** IP re-set the plane per colliding portal with a
  flush between; net effect = LAST portal's plane clips the entity's own draw. The port keeps the
  verbatim loop; registration overwrites → same last-wins semantics.

## 2. Mechanism B — one-entity `SubmitNodeStorage` + `renderAllFeatures` bracketed by clip state

### 2.1 Design

1. **Own storage + own dispatcher (compile-critical fact):** `FeatureRenderDispatcher` holds ONE
   reusable `PreparedFrame` and `begin()` **throws `IllegalStateException("PreparedFrame already in
   use")`** if re-entered (`26.2:FeatureRenderDispatcher.java:35,187-190`). During the main pass the
   main dispatcher's frame IS in use (`LevelRenderer.render` `:176` prepare → `:253` close), so
   **mechanism B MUST NOT call `renderAllFeatures` on
   `gameRenderer.featureRenderDispatcher()`** — it constructs its OWN
   `FeatureRenderDispatcher(renderBuffers, modelManager, atlasManager, font, gameRenderState)`
   (public ctor `:37-58`) fed by a DEDICATED `RenderBuffers` from `MyGameRenderer`'s inline pool
   (S11-A B5), whose `StagedVertexBuffer` is fence-recycled by the existing `endFramePooled()`
   discipline (S11-A B6; memory `gpu-buffer-leak-endframe`).
2. **Bracket:** for each clipped render (CASE 1 after suppressing the vanilla submit via the §3.2
   tag; CASE 2 for each projection): fill a scratch one-entity `SubmitNodeStorage` via the same
   public `extractEntity` + `submit(state, cam, x, y, z, poseStack, scratchStorage)` path, then
   `prev = FrontClipping.capture(); feed the entity's plane (bridge); ownDispatcher.renderAllFeatures(scratchStorage);
   FrontClipping.restore(prev)` (`renderAllFeatures` = prepare → executeSolid → executeTranslucent →
   executeTranslucentAfterTerrain → executeAlwaysOnTop → close, `:112-119`). The plane store holds
   the entity's plane for the whole bracket; the SAME always-on `GlCommandEncoderClipMixin` uploads
   it to each of the bracket's draws — this is the "raw-GL clip state" bracketing of API_RISKS R3(b),
   realized through the proven store rather than dead fixed-function GL.
3. **Call site (the mechanism's open runtime question, trails to S18):** the bracket issues
   immediate draws, so it must run where the main render target + depth are bound and the frame's
   depth buffer is populated for correct occlusion. Candidates (named, not decided — S18 wiring):
   (i) inside the main pass lambda after the entity phases execute (inject after
   `featureFrame.executeTranslucent()` in `addMainPass` — closest to IP's "end of entity
   rendering"); (ii) after `frame.execute(...)` in `LevelRenderer.render` (`:239-249`) — the
   pattern the mod's raw stencil-direct draws already prove GPU-safe, at the cost of drawing after
   weather/clouds. `stagedVertexBuffer.upload()` runs inside `prepareFrame` (`:106-108`) — a
   mid-frame GL upload, same class as the proven pooled-buffer usage.
4. **Semantics:** closest analog of IP's "immediately invoke draw call" (`consumers.endBatch()`
   :308). Pass fidelity is coarser than A: the bracket draws solid+translucent+alwaysOnTop
   back-to-back at one point instead of inside their proper framegraph passes (fabulous-mode
   per-pass targets are bypassed — under fabulous the entity's translucent parts miss the
   weighted-blend chain).

### 2.2 Why B is NOT the default (but stays fully wireable)

- Two extra moving parts A does not need: a second `FeatureRenderDispatcher` + dedicated
  `RenderBuffers` lifecycle, and an unresolved GPU-safe call-site decision.
- Pass-fidelity loss (fabulous/translucency chain bypass) vs A keeping every submit in its correct
  vanilla pass.
- B's one genuine advantage: total isolation from vanilla's sort/prepare pipeline (immune to any
  future reordering of order-collection execution) and IP-shaped "draw NOW" semantics. That is
  exactly why it stays implementable behind the switch — if A's order bracketing shows artifacts
  live (e.g. per-order group execution interacting badly with outline/fabulous paths), B is the
  fallback the user can flip to mid-test.

## 3. The compile-surface contract (what the Translate slice ports onto)

### 3.1 The seam class: `qouteall.imm_ptl.core.render.PerEntityClipBracket` (NEW, mod-additive)

One class owns BOTH mechanisms and the switch; `CrossPortalEntityRenderer` never branches on the
mechanism (D3: the ported IP body stays flag-clean; the seam dispatches).

```java
public class PerEntityClipBracket {
    public enum Mechanism { SUBMIT_ORDER_UNIFORM, ISOLATED_STORAGE_BRACKET }
    /** Live-read each frame from IPGlobal (config-backed) — the S18 A/B switch. */
    public static Mechanism getMechanism();

    /** frame reset — called from the submitEntities-HEAD anchor */
    public static void onFrameSubmitBegin(SubmitNodeStorage storage);

    /** CASE 1: submit a tagged main-pass entity with an OUTER clip plane.
     *  A: routes to storage.order(k) + registers phases; B: records for the bracket draw. */
    public static void submitMainPassEntityClipped(
        EntityRenderDispatcher dispatcher, EntityRenderState state,
        CameraRenderState cam, double camX, double camY, double camZ,
        PoseStack poseStack, SubmitNodeStorage storage, Portal collidingPortal);

    /** CASE 2: submit one projected entity with an INNER clip plane (null = draw UNCLIPPED — registers an
     *  explicitly DISABLED snapshot, NOT the ambient dest clip; matches IP :101/:184-204, Verifier-1 P1).
     *  Coords already camera-substituted by the caller (IP math verbatim in CPER). */
    public static void submitProjectedEntityClipped(
        EntityRenderDispatcher dispatcher, EntityRenderState state,
        CameraRenderState cam, Vec3 newCameraPos,
        PoseStack poseStack, SubmitNodeStorage storage, @Nullable Plane innerClipPlane);

    /** A-mechanism execute bracket, called by the executePhase mixin (§1.2.4). */
    public static @Nullable FrontClipping.Snapshot beginPhaseIfRegistered(FeatureRenderPhase<?> phase);
    public static void endPhase(@Nullable FrontClipping.Snapshot prev);

    /** B-mechanism deferred bracket, called from the S18-chosen PER-PASS draw-site hook with the pass's
     *  main storage (per-storage so a nested pass doesn't consume the outer pass's brackets — Verifier-1 P2). */
    public static void drawBracketedEntitiesIfAny(SubmitNodeStorage storage);
}
```

Plane→view-space conversion reuses the S11-B qouteall `FrontClipping` bridge feed
(`feedViewSpacePlane` internals; column-form `M·v`, `planeW = c` — do NOT re-derive).

### 3.2 The `EntityRenderState` tag duck (NEW, mod-additive, S12 mixin set)

`IEEntityRenderState { void ip_setClipContext(Entity e, Portal p); @Nullable ... ip_getClipContext(); }`
— a mixin adding two fields to `EntityRenderState`. Set at extraction: `@Inject` in
`LevelExtractor.extractVisibleEntities` after the `extractEntity` call
(`26.2:.../extract/LevelExtractor.java:243-244`) when `CrossPortalEntityRenderer` reports the entity
collided (CASE 1 trigger, `IP::115`). Frame-scoped: states are cleared right after submit
(`26.2:LevelRenderer.java:282`), so no retention concern. This is the extract-vs-render phase
assignment (CUTOVER_SPEC §4.2): the clip DECISION is captured at extract; the GL effect happens at
draw.

### 3.3 The anchor mixins (NEW, S12 client-mixin set; replaces IP's `MixinWorldRenderer` entity-loop hooks)

| IP hook (1.21.3 WorldRenderer) | 26.2 anchor | Calls |
|---|---|---|
| begin of entity+BE rendering | `LevelRenderer.submitEntities` HEAD (`26.2:LevelRenderer.java:653`) | `CrossPortalEntityRenderer.onBeginRenderingEntitiesAndBlockEntities(...)` → `PerEntityClipBracket.onFrameSubmitBegin(storage)` |
| per-entity `renderEntity` wrap | `@WrapOperation` on the `EntityRenderDispatcher.submit` call inside `submitEntities` (`:660`) | tagged state → `CrossPortalEntityRenderer.beforeRenderingEntity`-equivalent path → `submitMainPassEntityClipped`; untagged → vanilla `original.call(...)` |
| end of entity+BE rendering | `LevelRenderer.submitEntities` TAIL (before the `:282` state clear) | `CrossPortalEntityRenderer.onEndRenderingEntitiesAndBlockEntities(...)` → `renderEntityProjections` (verbatim filters) → `submitProjectedEntityClipped` per projection |
| (B only) draw site | S18-chosen (§2.1.3), per-pass | `PerEntityClipBracket.drawBracketedEntitiesIfAny(storage)` |

All anchors are per-`LevelRenderer`-instance, so secondary/portal-pass renderers get the same
behavior — matching IP, whose hooks lived on every WorldRenderer instance.

### 3.4 What the ported `CrossPortalEntityRenderer` looks like, method by method

| IP member | Fate |
|---|---|
| `collidedEntities`, `init`, `cleanUp`, `onClientTick`, `onEntityTickClient`, `isRenderingEntityNormally/Projection` flags | **VERBATIM** (events + IEEntity ducks exist in held source) |
| `onBeginRenderingEntitiesAndBlockEntities` (`:80-89`) | VERBATIM logic; the `setupInnerClipping` call goes through the S11-B `FrontClipping` bridge (CASE 3 — no batch split needed) |
| `isCrossPortalRenderingEnabled` (`:91-96`) | VERBATIM (`IrisInterface.invoker` from S4 compat; `IPGlobal.correctCrossPortalEntityRendering` config) |
| `onEndRenderingEntitiesAndBlockEntities` (`:98-108`) | VERBATIM minus the implicit draw-flush; calls `renderEntityProjections` |
| `beforeRenderingEntity`/`afterRenderingEntity` (`:110-143`) | logic VERBATIM (collidedEntities check + portalCollisions loop + clip DECISION); the two `endBatch()` lines (`:123,:139`) are REPLACED by the seam's bracket guarantee — the methods now compute/hand the clip portal to `submitMainPassEntityClipped` instead of mutating live GL mid-batch |
| `renderEntityProjections`/`hasIntersection`/`renderProjectedEntity` (`:147-216`) | VERBATIM logic (Mirror skip, dim match, flipped/reverse-portal + isHidden rough checks). **else-branch** (`:205-215`, not portal-rendering): `:206-208`'s `disableClipping`+`endBatch` collapse into the seam bracket; `setupInnerClipping(:210-212)`'s plane becomes the ARGUMENT to `submitProjectedEntityClipped` (null `getInnerClipping()` → DISABLED snapshot = unclipped, matching IP's `setupInnerClipping(null)→disableClipping`). **isRendering-branch** (`:184-204`): IP sets NO clip and inherits `onEnd`'s `:101 disableClipping`, so the projection draws **UNCLIPPED** — the port passes `null`, which the seam registers as an EXPLICITLY DISABLED snapshot (NOT the ambient dest CASE-3 inner clip; Verifier-1 P1) |
| `renderEntity` (`:218-314`) | **the duck kill**: all gating (LocalPlayer valve, `renderYourselfInPortal`, `getDoRenderPlayer`, bounding-box camera check, `isOtherSideBoxInside` intersect) VERBATIM; then `state = dispatcher.extractEntity(entity, RenderStates.getPartialTick())` + pose transform + `submitProjectedEntityClipped(...)` replaces `ip_myRenderEntity` + `consumers.endBatch()` (`:301-308`) |
| `setupEntityProjectionRenderingTransformation` (`:316-337`) | **VERBATIM** (pure PoseStack math) |
| `shouldRenderPlayerDefault`/`shouldRenderEntityNow`/`shouldRenderPlayerNormally`/`getRenderingCameraPos` (`:339-427`) | **VERBATIM** (consumed by the §4 gate mixins) |

Expected forward-refs the probe will show for the ported class until later stages (documented debt,
not slips): `render.renderer.PortalRenderer` (`IP::34,:386` — U10/S12),
`collision.PortalCollisionHandler`/`PortalCollisionEntry` + `ducks.IEEntity` (held units),
`compat.iris_compatibility.IrisInterface` (S4, already held).

## 4. The surviving 1:1 pieces (render-core S35 + G3)

- **Visibility-gate mixin — signature-identical port.** IP's `MixinEntityRenderDispatcher`
  (`IP:.../mixin/client/render/MixinEntityRenderDispatcher.java:13-32`): `@Inject` HEAD cancellable
  on `EntityRenderDispatcher.shouldRender(Entity, Frustum, DDD)Z` → `cir.setReturnValue(false)` when
  `!CrossPortalEntityRenderer.shouldRenderEntityNow(entity)`. 26.2 target is **signature-identical**
  (`26.2:EntityRenderDispatcher.java:127-130`) and vanilla calls it on the extraction path
  (`LevelExtractor.isEntityVisible` `:254`) — the gate now suppresses EXTRACTION (state never
  created), which is strictly upstream of IP's draw suppression: same visibility outcome. **Authored 1:1
  NOW at S11-C** as `mixin/client/render/MixinEntityRenderDispatcher.java`, held **UNREGISTERED**
  (`seamlessportals-ip-client.mixins.json "client":[]` untouched — it compiles as ordinary annotated Java in
  the probe); it is REGISTERED into the client-mixin set at S12 and activated flag-ON at S13. (Adjacent and
  unchanged: IP `MixinCamera:80-85`'s `isDetached` → `shouldRenderPlayerDefault` gate — also 1:1, S12.)
- **The public `extractEntity` + `submit` path** (`26.2:EntityRenderDispatcher.java:132-145,147-184`)
  replaces `IEWorldRenderer.ip_myRenderEntity` — the duck's `WorldRenderer` half is **DELETED at S11-C** from
  the held `IEWorldRenderer` surface (nothing references `ip_myRenderEntity`; its `MultiBufferSource`
  parameter type is GONE on 26.2, so keeping it stranded 2 permanent probe errors — the deletion retires
  them). The mixin-client "MixinMultiBufferSourceBufferSource TARGET-GONE" row is likewise retired at S12: no
  buffer-source mixin is needed on 26.2 at all.

## 5. Evidence-based DEFAULT + the C4-rider A/B switch

**DEFAULT = Mechanism A (`SUBMIT_ORDER_UNIFORM`).** Evidence:

1. The per-draw upload hook A rides is **already always-on and runtime-proven** — including on
   entity draws (the live dest-entity clip, `MOD:FrontClipping.java:211-215`), which retires the
   only shader-side unknown for BOTH mechanisms.
2. Order partitioning is a **public, vanilla-native surface designed for exactly this**
   (`SubmitNodeStorage.order(int)`; `SubmitNodeCollection implements OrderedSubmitNodeCollector`);
   group boundaries provably cannot span orders (per-collection phase objects + per-phase groupers,
   §1.1) — the endBatch-split semantics fall out of the data model with ZERO new GL surface.
3. A keeps every submit in its correct framegraph pass (solid/translucent/nametag/fabulous
   interplay preserved); B provably cannot (§2.1.4).
4. A needs one small mixin (`executePhase` HEAD/RETURN) vs B's second dispatcher + buffers + an
   unresolved call-site decision (`PreparedFrame already in use` guard,
   `26.2:FeatureRenderDispatcher.java:187-190`, forbids the cheap version of B outright).

**The switch (BINDING C4 rider — surfaced to the user at S18):** the landed switch is the **live-read
`IPGlobal.crossPortalEntityClipMechanism` field** (enum `SUBMIT_ORDER_UNIFORM` (default) |
`ISOLATED_STORAGE_BRACKET`), read live per frame by `PerEntityClipBracket.getMechanism()` — both paths are
always compiled, both sets of hooks are registered (each inert when not selected), so the A/B flip is a
no-restart, no-re-porting switch. This static field is the rider's "documented one-line switch" (it
satisfies C4 as landed). **NOT YET landed:** an `IPConfig` field + assignment (mirroring
`correctCrossPortalEntityRendering` at `IPConfig:40/:179`) and the matching `@ConfigEntry.Gui.EnumHandler`
in-game config-screen entry — that persistence/GUI wiring is deferred to **S12** (it rides the client-mixin/
config-screen set, and `IPConfig` currently carries pre-existing autoconfig-dependency debt in the probe;
adding an enum field there is safest done alongside that wiring, not in S11-C). Until then, flip the
`IPGlobal` field directly (code edit / debug toggle) for the S18 A/B test.

> **S18 A/B instructions:** stand an entity (and yourself, third-person) halfway through a portal;
> inspect both sides + the projection. Flip
> `crossPortalEntityClipMechanism: SUBMIT_ORDER_UNIFORM ↔ ISOLATED_STORAGE_BRACKET` via the
> `IPGlobal.crossPortalEntityClipMechanism` field (the S12 `IPConfig` config-screen entry once wired) and
> re-inspect the same scene. Judge: clip-edge
> correctness at the portal plane, translucent parts (e.g. slime, tinted glass armor trim, nametag
> see-through), fabulous graphics mode, mirrors, and any draw-order flicker. The loser's code is NOT
> removed until the checkpoint decision is recorded (post-C4 cleanup rides S20 with the rest of the
> holding machinery).

## 6. What trails to S18 runtime (none of it blocks the Translate slice compiling)

1. **The A/B live verdict itself** (checkpoint C4) — per the rider, BOTH stay wired until then.
2. **B's draw call-site choice** (§2.1.3 candidates i/ii) — decided when B is first wired live;
   compile surface is call-site-independent (`drawBracketedEntitiesIfAny(storage)`, per-pass).
3. **Order-execution artifacts under A** — outline/fabulous interaction of per-order group
   execution (watch: entity outlines via `executeOutline` `:231-238`, weighted translucency).
4. **CASE 3 margin parity** — whether the ported inner-clip pass adopts the mod's proven
   straddling-entity `margin` (`setupInnerClippingForEntities`, the 2026-07-08 "animal clips out of
   existence" fix) or IP's tight plane; fidelity default = IP tight plane, the margin is a
   documented mod-improvement toggle to evaluate live (the memory's "entities invisible THROUGH the
   portal window" open item is expected to be RESOLVED by this class going live — verify at S18).
5. **Iris/shadow interplay** — `isCrossPortalRenderingEnabled` returns false under Iris (verbatim);
   nothing to verify until an Iris build exists for 26.2 (F21).
6. **Performance** — per-order IdentityHashMap lookups per phase per frame (A) are O(1) map hits;
   confirm no measurable cost with zero collided entities (the common case exits at the empty-map
   check).

---

**Plan-amendment check: NONE required.** The design fits S11's D7 slot exactly as EXECUTION_PLAN
§929-940 prescribes; the C4 rider is honored by the live-read enum switch (a small ADDITION to the
original "pick one mechanism" wording, already anticipated by the checkpoint's own text); the class
port + seam class land in S11-C's Translate slice on this contract; the four NEW mixins (§3.2/§3.3
anchors + §1.2.4 executePhase bracket) ride the S12 client-mixin set alongside the 1:1 S35 gate.
