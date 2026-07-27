# SEAM CLIP — DESIGN v2 (2026-07-27, post-adversarial-panel)

Cut source-dimension geometry at the portal plane on the MAIN camera pass, so a seam block's far
half stops drawing when viewed from the side, and everything beyond the plane comes from the
mirrored copy (visible only through the window). Draw-time `gl_ClipDistance` via the existing
`seamlessportals_ClipPlane`, per the verified OPEN ITEM 1 design.

**v2 = v1 + the folds from a 4-lens adversarial panel (geometry / engine / discipline /
alternatives; 8+5+7+3 findings, all grounded file:line).** The architecture survived — the
alternatives lens confirmed Route B (whole-section bracketed redraw) is *logically incapable*
(global planes cut every block uniformly; keep-near + keep-far redraw reconstructs everything),
Route C (per-vertex flag) infeasible, and nothing in-tree was missed. Every v1→v2 change is marked
★PANEL.

## 0. Granularity correction to the handoff's framing

OPEN ITEM 1 says "seam SECTIONS need their own bracketed draw". Section granularity is wrong:
a 16³ section containing a seam cell also contains the obsidian FRAME (which straddles the plane
and must NOT be cut — each side owns a complete frame) and ordinary terrain behind the plane.
Also there is no per-section terrain draw to bracket at all — 26.2 draws terrain monolithically
per layer group (REF LevelRenderer.java:409,438 → `ChunkSectionsToRender.renderGroup`, one
`trySetup` per hash bucket). The clip must land on **seam-CELL geometry only**:

1. **EXCLUDE qualifying seam-cell blocks from the compiled section mesh** — ★PANEL mechanism:
   the compile's region reports those cells as AIR (`RenderSectionRegion.getBlockState`), which
   both skips their tessellation AND un-culls neighbor faces against them. The un-cull is
   load-bearing: it closes the x-ray tunnel through neighbor faces that were culled against the
   seam block (reachable on FRAMELESS portals — see §1a). NOT quad clamping; no view-dependent
   geometry is baked, so the same-dim two-camera argument that killed compile-time clamping does
   not apply.
2. **Draw those few blocks dynamically each frame** (per-frame re-tessellation against the live
   level), each draw bracketed with its cell's clip plane through the live per-draw upload
   (`GlCommandEncoderClipMixin` at `GlCommandEncoder.trySetup` RETURN — fires for our own
   immediate draws too, verified).

Seam cells are few (a portal opening ≈ 2×3 cells; tens loaded at once) — same cost class as
vanilla moving pistons. ★PANEL: a user-built LARGE filled aperture (e.g. 10×10) within draw range
re-tessellates hundreds of cells per pass per frame — accepted for v1, residual in §6 with the
escape hatch (per-cell cached MeshData + AABB frustum test).

## 1. Which cells, which plane, which half

- **Predicate (per level, client side):** cell ∈ `SeamIndexHolder(level).seamCells` with at least
  one binding that is **COINCIDENT phase AND mirror-admitted (`binding.destPos() != null`)**
  (★PANEL: query-only bindings — exact-only-declined portals — have NO mirrored counterpart
  anywhere; clipping them saws a block in half with no supplier. The suite's own portal A is that
  case), holding a REAL block: `!state.isAir() && state.getBlock() != PortalPlaceholderBlock
  .instance`, `state.getRenderShape() == MODEL`, `!state.hasBlockEntity()` (★PANEL: the AIR
  report would otherwise drop the BE from the compile's BE collection — chest goes invisible).
  DISJOINT bindings skipped — nothing straddles there (SeamMap.java:243-245). Fluids: the
  region's `getFluidState` reads the SectionCopy directly, NOT through `getBlockState` (REF
  RenderSectionRegion.java:47-54), so a waterlogged qualifying block keeps its water in the mesh —
  fluids render whole, per the job spec.
- **Plane:** for a COINCIDENT cell the plane passes through the CELL CENTER with normal along
  `binding.srcFacing()` — pure arithmetic, no Portal entity lookup at draw time. EXACT alignment
  implies scale 1 (signed-axis-permutation + integral translation), so the unscaled plane-rotate
  path suffices; keep the det≈1 guard anyway (S13-L convention).
- **Kept half = the CAMERA side**, decided per draw per pass (flip the normal toward the camera).
- **★PANEL camera-front-side gate:** clip a cell only if SOME binding satisfies
  `dot(camPos − cellCenter, srcFacing) > 0` — i.e. a camera-side window can exist. Bi-faced
  clusters always pass. A SINGLE-faced portal viewed from behind fails → block drawn UNCLIPPED
  (portals never render from behind — RectangularPortalShape.roughTestVisibility z>0 — so no
  window would ever supply the missing half; v1 would have left a permanent hole).
- **★PANEL multi-plane dedupe:** overlapping wand portals can give one cell two COINCIDENT
  bindings with NON-parallel planes (the re-ignition guard covers NetherPortalGeneration only).
  One draw has one plane: pick deterministically the first camera-front-side binding; residual
  in §6.
- **★PANEL near-plane guard:** when |signed camera distance to a cell's plane| < ADJUSTMENT
  (crossing frames), draw that cell unclipped this frame (flip sign is degenerate and the window
  quad projects to ~zero area).
- **Epsilon:** push the plane `ADJUSTMENT = 0.01` PAST the flip point (away from the camera): the
  kept half overlaps the plane by 0.01 — the exact mirror of the dest pass's `-ADJUSTMENT` inner
  clip (SecondaryWorldRenderCore.java:1098-1101, verified). Panel-verified: no gap, and no
  cross-pass z-fight because the stencil partitions pixels and the window's depth is cleared
  before the dest overwrite. ★PANEL scope note: the "no coplanar faces" claim holds for
  VERTICAL-plane portals; for ±Y (floor) portals, bottom-slab/4-layer-snow/sculk tops lie exactly
  in the plane and dither against the depth-tested window quad — but they already do so TODAY
  without the clip (same faces, same quad); not a new artifact, noted in §6.

### 1a. Why the composite stays correct (ray argument, v2 — panel-corrected)

For any camera: every straight ray reaching a clipped cell's REMOVED volume either
(a) crosses the plane INSIDE the aperture — the window region, where the stencil dest pass
overwrites the pixel with the mirrored copy's complementary half (verified incl. the full-quad
EDGE_OUTSET 0.01); or (b) is occluded by the cell's own kept-half surface (verified for full
cubes AND partial blocks); or (c) crosses the plane OUTSIDE the aperture. For (c):
- **Framed (obsidian) portals:** the closed opaque frame ring occludes — panel-verified, incl.
  the rail case (a frame block under a rail only ADDS rendered geometry).
- **★PANEL Frameless portals (wand — the user's primary test config):** nothing occludes; the
  ray sees the removed volume absent and lands on whatever source terrain is behind — WHICH IS
  THE REQUESTED SEMANTICS ("the far half stops drawing when viewed from the side"; the far half
  is dest-owned and visible only through the window, like a doorway seen from the side). The
  §0 un-cull guarantees such rays land on real drawn faces (neighbor tops/sides now rendered),
  never tunnel through culled-face gaps into solid terrain. v1's blanket "no x-ray reachable"
  claim was refuted for frameless portals; v2's mechanism change makes the frameless behavior
  correct-by-construction instead. **Flag for the user's live look: on a frameless portal, a seam
  block viewed from the side now visibly ends at the plane. That is the feature as requested —
  confirm it reads right in-game.**

Degenerate case (portal window not rendered: occlusion-culled or recursion cap): removed half
shows what's behind; with the AIR report the cell no longer registers opaque in the VisGraph, so
behind-terrain is compiled/visible more eagerly than v1 (★PANEL wording fix; same acceptance).

## 2. Mesh exclusion (vanilla path)

- **Snapshot at region creation (main thread):** `@Inject` at RETURN of
  `RenderRegionCache.createRegion(ClientLevel, long)` (REF :16-37; ALL call sites are game-thread
  — panel-enumerated: vanilla LevelExtractor.extract, LevelExtractorCreateRegionBudgetMixin,
  RemoteBlockUpdater, PortalWorldManager, SameDimRemesh, VisibleSectionDiscovery ×2,
  SecondaryWorldRenderCore, PortalContextSwitch). Compute the qualifying-cell LongSet for the
  region's FULL 3×3×3 bounds (neighbor compiles must also see AIR for the un-cull) and stash it
  immutably on the region via a duck (`@Unique` field mixin). Null/absent set = zero cost.
- **The AIR report:** `@Inject(at=HEAD, cancellable)` on `RenderSectionRegion.getBlockState`:
  if the duck set contains the pos → return AIR. Covers the compile loop read (skips
  tessellation, VisGraph opaque, BE collection — predicate excludes BEs so nothing is lost) AND
  `ModelBlockRenderer.shouldRenderFace` neighbor reads (the un-cull) AND FluidRenderer neighbor
  reads (water face against the cell — correct). First line short-circuits on a null set.
  Composes with the flag-gated block-era `SectionCompilerMixin` redirects (they pass through
  flag-ON and route through the same method).
- **Lifecycle dirtying — ★PANEL: own path, own accounting.** On client-side `SeamRegistry.bind`/
  `unbind` (guarded `level.isClientSide()`), queue the touched cells; flush at POST_CLIENT_TICK:
  for each covering section, direct `compileAsync` via the SameDimRemesh section-lookup RECIPE
  (ViewAreaInvoker + `provideBuiltChunkByChunkPos` fallback + neighbor gate) but with
  SeamClip-OWN counters and sets — v1's "reuse the SameDimRemesh machinery" would have poisoned
  `didCompileSectionAt` (bind-time compile lands in COMPILED ~40 ticks before RS-DELIVERY arm
  3's write, making that verdict un-failable). SameDimRemesh's accounting is not touched. The
  direct-compile path also closes the panel's indefinite-staleness case (bind/unbind of a portal
  visible only through a far same-dim window — tracker marks are never consumed out there;
  defect-A family). Block place/break in a seam cell needs nothing: vanilla dirtying recompiles,
  and the new snapshot reads the new state (1-frame double-draw transient, §6).
- **Sodium:** no meshing hook exists in the compat layer — under sodium the exclusion cannot
  apply, so the feature self-gates OFF entirely (one log line; also true for iris, which implies
  sodium). Sodium is absent from dev client + the gametest suite. Follow-up in the handoff.

## 3. The dynamic seam draw

Per pass, group qualifying visible cells of THAT PASS's level by (plane-key), one BufferBuilder
per touched layer per group, draw with the plane bracketed:

- **Tessellation:** `new ModelBlockRenderer(ambientOcclusion, /*cull*/ true, blockColors)` +
  `tesselateBlock(output, x, y, z, level, pos, state, model, seed)` against the REAL ClientLevel
  (client BlockAndTintGetter — same culling/AO/light algorithm as the section path; panel:
  ClientLevel implements it, thread-safe on the render==tick thread, RandomSource fine, no
  BlockModelLighter hazard). x/y/z = `(float)(pos − camPos)` fed to
  `VertexConsumer.putBlockBakedQuad(x, y, z, quad, instance)` (★PANEL: the section-path writer;
  NOT the pose-taking `putBakedQuad`). Route per quad by `quad.materialInfo().layer()` with the
  `forceOpaque → SOLID` reroute, mirroring SectionCompiler:61-68. BLOCK format (Position/Color/
  UV0/UV2) matches the movingBlock pipelines' vertex binding.
- **Draw:** `PortalRenderTypes.drawMesh(renderType, mesh)` with `RenderTypes.solidMovingBlock()/
  cutoutMovingBlock()/translucentMovingBlock()` per layer. Panel-verified: prepare() binds the
  block atlas (Sampler0) AND lightmap (Sampler2), uploads DynamicTransforms with
  ModelOffset=(0,0,0) and ModelViewMat = the RenderSystem stack top AT prepare() TIME — so the
  pass modelView MUST be installed BEFORE drawMesh (ViewAreaRenderer push/set/finally-pop idiom).
  `core/block.vsh` matches the clip-injection pattern; with camera-relative Positions +
  ModelOffset 0 + the same matrix fed to the plane rotation, the injected clip evaluates the
  identical half-space as terrain draws. ★PANEL note: drawMesh × lightmap/atlas render types is a
  first-in-tree combination — if the pixel gate shows bad shading, the recorded fallback is Route
  A (submitCustomGeometry + Mechanism-A order-band bracket; panel-verified viable, ~25-line
  wrapper on PerEntityClipBracket internals).
- **Bracket:** `prev = FrontClipping.capture()` → `restore(planeSnapshot)` → drawMesh →
  `restore(prev)`. Plane snapshot built from the before-model-view equation
  `n·(P−cam) + c ≥ 0`, `c = n·(cam−center) + ADJUSTMENT` (n = camera-flipped srcFacing), rotated
  to view space by the column-form idiom (anti-fix guard: `new Vector4f(n,0).mul(modelView)`,
  never mulTranspose), det≈1 guarded.

### Draw sites

- **MAIN pass:** the `BEFORE_TRANSLUCENT_TERRAIN` event (next to PerEntityClipBracket's
  registration). Runs after opaque terrain + entities, before translucent terrain and the portal
  driver — near halves are depth-buffered before the stencil pass computes window visibility
  (panel-verified event identity + ordering). **★PANEL mandatory guard:
  `PortalRendering.isRendering()` early-return** — the event is class-woven and DOES fire inside
  the full-pipeline twin's nested `destRenderer.render()` with `mc.level` swapped to the dest
  world (both existing handlers at this seam carry the same guard). Ambient is keep-all; each
  group brackets its own outer plane.
- **DEST pass (through-window):** ONE guarded call in `SecondaryWorldRenderCore.renderDestWorld`
  immediately after the opaque-terrain block (:1122-1126), before entities (:1146) — inside the
  armed inner-clip window (disarmed only in the finally :1197). ⚠ HOT FILE of the parallel iris
  session — the insertion is a single line calling a hook method in the NEW SeamClip file
  (★PANEL), flagged in the commit message, which also records the assumption "ambient inner clip
  armed with −ADJUSTMENT at this point" so whichever session merges second re-verifies the gate.
  **★PANEL: NO hook in the full-pipeline twin** — no post-opaque-terrain insertion point exists
  there (single nested `render()` call), M4 disarms the live store mid-pass so the ambient
  assumption is false, the FROZEN FullPipelineClipState override would silently defeat any
  own-plane bracket, and the twin is reachable only under iris ⇒ sodium ⇒ the feature is already
  self-gated OFF. If sodium support ever lands, the dest arm must integrate with
  FullPipelineClipState, not the live store.
  Per cell of the dest level:
  - cell's plane ≈ the ACTIVE portal's clipping plane (the D cells of the link being rendered):
    draw with the AMBIENT plane untouched — the pass's inner clip performs exactly the far-half
    cut (adjustment matched; panel-verified for EXACT bindings).
  - otherwise (unrelated seam cell): **★PANEL rule — own-plane bracket ONLY if the cell's AABB
    lies wholly on the content side of the active plane; anything straddling or camera-side draws
    under the AMBIENT plane instead** (v1 bracketed straddling cells with their own plane, which
    abandons the inner clip and paints foreground junk into the depth-cleared window — the exact
    geometry class the inner clip exists to remove).
  This covers the SAME-DIM case: the shared ViewArea's meshes lack both S and D cells; the main
  pass draws S with the real camera's flip, the dest pass draws D under ambient; in the dest pass
  S reappears as an "unrelated" cell with the virtual-camera rules. Nested layers: the hook runs
  once per layer with that layer's camera/matrix/plane (panel-verified recursion path).
- **Enumeration:** per pass, iterate the pass level's `seamCells` (render thread == tick thread —
  panel-verified single-thread loop in 26.2 `Minecraft.runTick`), distance-capped 64 blocks,
  state-filtered per §1.

## 4. Lever, counters, probe

- `-Dseamlessportals.disableSeamClip` (`-PdisableSeamClip`), DEFAULT-ON fix lever in
  `AperturePassthroughLever` (never on a mixin class), rows in BOTH `fabric/build.gradle` blocks.
  Gates all three arms (snapshot → null set; draws → no-op; dirtying → no-op). ★PANEL wording:
  OFF is *behavior-identical* (mesh output byte-identical; the woven handlers short-circuit on
  one static read — the house standard).
- `SeamClipRenderer.counters()`: `cellsExcluded`, `cellsDrawn`, `drawsIssued`, `destCellsDrawn`,
  `ownPlaneDraws`, `recompilesScheduled` — SeamClip-own, never shared with SameDimRemesh.
- `-Dseamlessportals.seamClipProbe` (default OFF): 1 Hz-latched per-frame summary. Log prefix
  `[SEAM CLIP]` (★PANEL: distinct from the iris session's `IS5-SEAM` lines).

## 5. The gate — assert the PIXELS (both directions) — ★PANEL rebuilt fixture

v1's fixture was geometrically infeasible (framed staging → the side view is frame-occluded; rays
crossing in-window are overwritten by the dest pass EVEN LEVER-OFF, so the inversion never shows
gold — the "reproduction for the wrong reason" class). v2:

New RS leg `rsSeamClipGate`, RS-only compatible, its own staging, cleanup in `finally`:

1. Stage a **FRAMELESS, EXACT-aligned (mirror-admitted), COINCIDENT** portal pair (spawnTestPortal
   with integral dest offset — verify admission via `SeamRegistry` before proceeding; if the
   binding is query-only the fixture is invalid and the leg must say so, not pass vacuously).
   Place GOLD via `writeAsPlayer` in an aperture **EDGE cell**; build a BLUE concrete backdrop
   wall behind the plane positioned so out-of-window sight lines land on it.
2. Camera beyond the window's lateral edge with enough offset that rays to the far-half region
   cross the plane OUTSIDE the quad (panel: in-window crossings are repainted by the dest pass in
   both lever states and prove nothing). Compute the two sample regions by PROJECTING known world
   points through the camera transform — never fixed screen fractions. `hideGui` for the shot.
3. Wait: the gold cell's section had `setSectionMesh` fire AFTER the placement tick (SeamClip-own
   hook — NOT SameDimRemesh's COMPILED set); lever-OFF run waits on the ordinary post-write
   recompile the same way.
4. Screenshot → `ImageIO.read` (AWT already proven in this JVM — MyRenderHelper uses
   BufferedImage at runtime; retry-loop the read over a few ticks in case the PNG write lags).
   Dominant-color vote, tolerant hue distance:
   - fix ON: near region GOLD (coverage — block rendered, camera aimed, dynamic draw ran since
     the mesh no longer contains the block), far region **NOT gold** (blue wall or whatever is
     behind — assert not-gold only, ★PANEL);
   - `-PdisableSeamClip`: far region GOLD (the defect reproduced — inversion).
5. Counter coverage: `cellsDrawn > 0` and `cellsExcluded > 0` fix-ON; both 0 lever-OFF.
6. Placement in the suite: after the rail legs; staging far from leg fixtures; in the FULL suite
   the staging must sit clear of leg 4's pearl path and leg 6a's 128-block frame-match radius
   (blue concrete is inert there; still removed in `finally`).

First gate in the project asserting actual framebuffer pixels — one step beyond `setSectionMesh`.
The user's live look remains the real sign-off (see the §1a frameless-semantics flag).

## 6. Accepted residuals (all panel-reviewed)

- Fluids and block-entity blocks in seam cells: never clipped (fluids render whole from the mesh;
  BE blocks drawn whole — predicate excludes them).
- Sodium/iris runtime: feature self-gates OFF (no meshing hook); follow-up.
- Translucent seam block vs translucent terrain behind it: ordering imperfect; under FABULOUS
  graphics additionally the translucentMovingBlock target diverges (ITEM_ENTITY_TARGET with a
  stale depth copy) — acceptance is for fancy/fast, the stencil pipeline's envelope (★PANEL).
- Unrelated seam cell through a window: single-plane limitation; §3 rule bounds it to an uncut
  far half (never foreground junk).
- Portal window not rendered (culled/recursion cap): removed half shows behind-content.
- 1-frame double-draw on block place in a seam cell; brief double-draw/invisible transients
  around bind/unbind, bounded by the direct-compile dirtying path (★PANEL: no longer indefinite).
- Multi-plane shared cell (crossing wand portals): deterministic single-plane pick (★PANEL).
- Large filled apertures re-tessellate per frame (★PANEL); escape hatch: per-cell MeshData cache
  + AABB frustum test.
- Floor-portal coplanar faces (slab tops at the plane) dither against the window quad — pre-
  existing, unchanged by the clip (★PANEL).
- Crumbling overlay and hit outline on a seam block remain full-cube.
- Camera within ADJUSTMENT of a cell's plane: that cell draws unclipped for the frame (★PANEL).
