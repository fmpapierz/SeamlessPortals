# THE FRACTIONAL SEAM MODEL — DESIGN v1

**Branch `redstone/passthrough`, worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`.** Opened 2026-08-02 from the
design panel that `FRACTIONAL_HANDOFF.md` §0 called for.

> **THIS SUPERSEDES `FRACTIONAL_HANDOFF.md` §0.** That document's two gating questions are ANSWERED
> below (§0). Six of its factual claims are CORRECTED below (§1) — the handoff stays useful as
> scoping material, but where the two disagree, this file is right and carries the evidence.

---

## ★★ §0 DECISIONS TAKEN — USER, 2026-08-02

| # | question | **decision** |
|---|---|---|
| **A** | the FRACTION — arbitrary or exactly ½? | **GENUINE ARBITRARY FRACTION.** The renderer reads the real plane; `SeamPhase` grows a third answer. |
| **B** | the STORAGE, and which consumers read it | **Position-keyed side table. Cut applies to COLLISION *and* SUPPORT/CONDUCTION** (tiers i+ii, §4). The block reports partial everywhere gameplay looks. |
| **B′** | occlusion / lighting (tier iii) | **DEFERRED TO FUTURE POLISH** — explicitly out of v1 scope. §9. |
| **C** | sequencing | **GATE → STORAGE → COLLISION → flip `keptDir` LAST.** The render flip is not free (§1.2); the hole-inversion is only defensible once the far half genuinely does not exist. |

**Accepted with decision B, stated at the time and re-stated here:** rails pop off a partial support
block. Shipped rail-survival assertions go red **by design** and are rewritten to assert the new
truth, inverting under the lever. See §5 and §7.

---

## §1 CORRECTIONS TO THE HANDOFF — six, all grounded

### 1.1 ★ "Arbitrary fractions are unreachable by policy" — **FALSE, and the gap is live today**

The exact-only gate tests only that the portal transform's **translation** is integral
(`SeamMap.latticeAligned`, `SeamMap.java:285-288`). The source's own worked example admits an
arbitrary phase pair: `SeamMap.java:278-279` — *"planes at (Z0+0.3, Z1+0.7) → integral ✔"*.

So a plane at `blockCoord+0.3` is:
1. **admitted** by the mirror policy (EXACT), then
2. **classified COINCIDENT** by `phaseOf` — threshold `d < 0.25` from the cell centre
   (`SeamMap.java:261-265`), so the whole window `f ∈ (0.25, 0.75)` is COINCIDENT, then
3. **accepted** by the clip predicate (`SeamClipRenderer.java:153-160`), then
4. **cut at 0.5 anyway** — `int coordHalf = 2 * componentAlong(pos, keptDir.getAxis()) + 1;`
   (`SeamClipRenderer.java:354-356`), whose own comment says *"the plane passes through the cell
   centre"*.

A 0.2-block silent mis-cut, with no assertion anywhere. The one gametest that looks like it pins the
half-cut (`CrossingSmoke.java:950-962`) only asserts `plane ∈ [cellLo, cellLo+1]` — it would pass a
0.3 plane.

**Reachability.** Not theoretical. `/portal set_portal_position` reaches it directly. The wand
reaches it whenever cursor alignment ≠ 2: `IPConfig.portalWandCursorAlignment = 2` is only the
*default* (`IPConfig.java:91`, comment *"zero for no align"*), `set_cursor_alignment` accepts any
integer ≥ 0 (`ClientDebugCommand.java:955-961`), `alignOnBlocks` returns the raw hit position
unsnapped at 0 (`WandUtil.java:65-67`), and at gridCount 3 the grid lands on thirds
(`Helper.alignToBoxSurface:1310-1312`).

**What actually confines cuts to ½ today** is two accidents, not policy: `IntBox.getCenterVec`
(`IntBox.java:176-179`) making every obsidian-frame plane exactly `blockCoord+0.5`, and the one
hardcoded line above.

⇒ **Decision A is not "build a speculative capability". It is "stop silently mis-rendering inputs we
already accept".**

### 1.2 ★ "The declined pop is mechanically ONE expression" — **HALF WRONG**

`cameraSideWindowPossible` is the **whether**-to-cut gate, not the **which-half** gate. Today when it
returns false the cell draws **WHOLE** (`SeamClipRenderer.java:335-337` → the `ambient` list) — that
whole-block fallback *is* the hole prevention.

The two entangle in one direction only: with a single binding the gate passes exactly when
`side > 0`, which is exactly when `keptDir` is already `f`. So it *reads* as flip-servant. But in the
only case where it is decisive its effect is "keep **both** halves", not "keep the other half".

Under `keptDir = f` with the gate deleted, a camera behind a single-faced portal now has the
**camera-near** half removed; `gl_ClipDistance` generates no cap face, so the camera looks into the
block's hollow interior, and `RectangularPortalShape.roughTestVisibility` (`localPos.z() > 0`)
guarantees no window is ever drawn there to supply it. The permanent hole the previous panel added
this gate to prevent is **not retired — it is inverted and moved nearer the camera.**

**The near-plane `|side| < ADJUSTMENT` guard *does* fall away cleanly** (no flip ⇒ no degeneracy),
retiring `SEAM_CLIP_DESIGN.md` §6's last residual line.

⇒ **This is why decision C puts the flip LAST.** Once collision and state genuinely end at the plane,
"a half block seen from behind a one-way portal" is the correct semantics rather than a defect. Doing
it first would ship a known-visible hole.

Mechanical size, for the record: ~5 executable lines in `drawForPass` plus deleting
`cameraSideWindowPossible` (`SeamClipRenderer.java:162-184`, 23 lines). `center` at :329 **must
stay** — `onPlane` at :338 still reads it. Second-order: with `clipThis` unconditional the MAIN
pass's `ambient` list becomes permanently empty, so draw-group count rises to one per distinct
`PlaneKey` — watch under a large filled aperture.

### 1.3 ★ The support hazard — **verdict right, reason inverted, failure mode opposite**

`RIGID_SUPPORT_SHAPE = Shapes.join(Shapes.block(), Block.column(12,0,16), ONLY_FIRST)` = the block
**MINUS** the central 12×12 column, i.e. **the 2px perimeter frame** (`SupportType.java:28-31`).
`isSupporting` = `RIGID_SUPPORT_SHAPE ⊆ faceShape`. The handoff says a half-cut "no longer covers the
central 12×12" — the opposite of the truth, and a designer reading it would conclude "keep the middle
and I'm safe".

Consequences that matter for decision A:
- **RIGID fails at EVERY fraction f < 1** (the far border strip is always lost). There is no "shallow
  enough" cut. **Arbitrary fractions buy nothing on the support axis.**
- **CENTER** (`Block.column(2,0,10)`) survives to `f ≥ 9/16`. So a ¾ cut kills rails, repeaters,
  comparators, dust, doors, ladders, buttons, levers — while torches, candles, lanterns and pressure
  plates live. That asymmetry is a real lever the handoff collapses.
- **`canSupportRigidBlock` → `isFaceSturdy` → the CACHED `faceSturdy[]`** (`Block.java:325-327` →
  `BlockBehaviour.java:867-868`). So a *position-keyed* cut is **invisible** to it unless intercepted:
  the naive failure is **rails FLOATING, not popping**. Meanwhile `SnowLayerBlock` reads the live
  collision shape and *would* pop. **That inconsistency is the real hazard**, and it is why decision B
  routes tier (ii) explicitly rather than hoping.
- **`BaseRailBlock` has a SECOND gate**: `shouldBeRemoved` (`BaseRailBlock.java:92-103`), reached from
  `neighborChanged`, re-tests RIGID below and — for `ASCENDING_*` — on the block the slope climbs
  into. It calls `dropResources` + `removeBlock`, i.e. it **destroys** the rail. A cut on a
  *neighbour* drops a sloped rail.
- **CARPET is not in the blast radius** — `CarpetBlock.canSurvive` is `!level.isEmptyBlock(pos.below())`,
  no support test. The handoff's example list is wrong on that one.

### 1.3a ★ SETTLED BY MEASUREMENT 2026-08-02 — and it changes how decision B should be read

The question flagged as contested — two source traces (`VoxelShape.calculateFace:221-234` →
`SliceShape`) said `getFaceShape(UP)` of a half-height box is `Shapes.empty()`, i.e. a bottom slab
supports nothing, contradicting remembered game behaviour — **has been measured, at outcome level,
with coverage asserted.** `rsSeamCollisionGate` arm 2, live run:

```
bottom slab at BlockPos{-3994,-57,-3994}:
  predicate isFaceSturdy RIGID=false CENTER=false
  OUTCOME on a real placed slab (placed=true) rail.canSurvive=false torch.canSurvive=false
```

**Predicate and outcome AGREE. The source traces were right.** In this build a bottom slab does not
support a rail or a torch on its top face. (The contrary recollection was of older versions and does
not apply here.) Three consequences:

1. **A HORIZONTAL cut is strictly worse than a vertical one.** A vertical cut at least keeps CENTER
   alive to `f ≥ 9/16`; a horizontal bottom-half cut zeroes the UP face shape outright, so
   *everything* resting on a floor-portal seam cell pops, not just rails.
2. **★ Decision B is NOT a special-case breakage — it is the rule every partial block already
   follows.** "A partial block does not support things" is the engine's existing, uniform behaviour.
   Rails popping off a fractional seam cell is therefore consistent with slabs, not an exception
   carved out for portals. That is a real point in decision B's favour and it was not available when
   the decision was taken.
3. **And it names the exact mechanism the §5 union lever needs.** The way vanilla makes a partial
   block still support things is to override `getBlockSupportShape` back to `Shapes.block()` —
   precisely what `SOUL_SAND` does (§4a). So the union reading is not a new code path; it is one
   override on the tier-(i) hook that is already being built.

⚠ Worth a live look regardless: if slabs really do not carry rails in this build, that is a vanilla
property the user may want to see confirmed with their own eyes before we build on it.

### 1.4 "There is NO collision gate anywhere" — **true, and it understates the exposure**

Confirmed: no gametest anywhere asserts collision at a seam. But the suite holds **four live
rail-survival assertions** — `CrossingSmoke.java:1805, 1835, 2177, 3286` — plus **two fixture-placement
decisions justified in prose by today's whole-cube support** (`CrossingSmoke.java:3830, 3835-3841`:
gates run on the bottom opening row *because* a mid-height rail sits on the noCollision placeholder
and pops). So the true position is not "no coverage" but **"no coverage on the property being
changed, and four green gates that break as collateral, plus two fixtures that become unsound with
nothing firing"** — the wrong-reason-pass hazard this project has already been bitten by.

**Prediction the new gate must settle, not assume:** 1805 and 2177 lay rail on `contC.below()` =
stone in a *continuation* cell (not a seam cell), so they may well stay green; 1835 and 3286 assert
the seam-cell rail itself. Which of the four actually go red is a measurement.

### 1.5 The `is5-shadow` shared file — **no conflict today; the handoff's "sync FIRST" is mis-aimed**

The decomposed Step-10.5 arm that the clip's dest arm depends on is **byte-identical on both
branches**. `is5-shadow` has zero uncommitted edits to the file; its IS5-SEAM relax/suspend landed
only on the **full-pipeline** arm (`is5 :1764-1800`), ~500 lines from the redstone hunk
(`:1126-1139`). Git merges them cleanly.

**Do not block the fractional model on a sync.** The real risk is one step away and would be
**silent**: `is5-shadow`'s new helpers `shouldSuspendInnerClipForCrossing` /
`innerClipCorrectionForCrossing` are generic and its census is explicitly an "arm-SITE census"; the
decomposed arm is the only other arm site. If the relax is extended there,
`SeamClipRenderer.onDestPassAfterOpaqueTerrain` (`SeamClipRenderer.java:289-291`) **re-derives
`activePlane` itself and never sees the suspend flag** — it would keep classifying cells "ambient" on
the premise that the inner clip performs the far-half cut, while GL clipping is off. No assertion, no
counter, no compile break.

⇒ **Mitigation, one parameter, do it when the dest arm is next touched:** the fractional dest arm
takes the correction/suspend state as an **argument** from the arm site, or asserts it. Also note
`is5-shadow` splits `destViewMatrix` into a cull matrix and a bobbed `destDrawViewMatrix` — at merge
the fractional arm must pick deliberately, not inherit `:1136`'s argument.

Minor: the "ONE line at :1135" is one statement wrapped across :1135-1136; the ASSUMES comment is at
:1128-1134. And the clip has a **second** arm the handoff omits — the main pass at
`SeamlessPortalsClientFabric.java:168`.

### 1.6 §4's cheap measurement — **half refuted; the command and the expectation are both wrong**

- **`-PdisableSeamPhaseGate` HAS been run, green**, 2026-07-27, gate matrix row 4
  (`REDSTONE_NEXT_SESSION.md:506-508, 759, 775-777`). The suite contains three lever-aware branches
  for it (`CrossingSmoke.java:1380, 2629, 4901`) including an inversion assertion with an observed
  failure string. `REDSTONE_NEXT_SESSION.md:73` contradicts its own file.
- **Only `-PdisableSeamExactOnly` has never run.** One consumer in the tree
  (`SeamMirrorPolicy.java:62`), zero lever-aware test branches.
- **Running both together REDUCES coverage** — RS-RAIL-B (`:1504-1522`) and RS-SIGNAL-B
  (`:2629-2636`) self-skip under the phase-gate lever. And it changes nothing about portal A, whose
  plane sits at a half-integer Z ⇒ COINCIDENT ⇒ `isPhaseGated` never fires on it.
- **The predicted red at `CrossingSmoke.java:1094` will probably not fire.** `resolveDestCell`
  (`SeamRegistry.java:483-486`) returns `SeamMap.mirrorCell` **verbatim** when `reverse == null`, and
  portal A is one-way (`spawnTestPortal:682-692` creates one entity; the suite calls it "a ONE-WAY,
  single-entity portal" at `:3923`). The gate computes the same pure function on the same inputs.
  **Green here is not evidence the offset path works — it is evidence the fixture cannot
  discriminate.** A discriminating fixture needs a REVERSE portal at the offset destination.
- **Correct invocation:** `-PapertureTeardownTest=true -PrsOnly=true -PdisableSeamExactOnly=true`.
  Without `-PrsOnly` the spawn-area portals sit in unloaded chunks at gate time and
  `getEntitiesOfClass` never returns them (`REDSTONE_NEXT_SESSION.md:556-561`).
- Levers are **fabric-only**; `neoforge/build.gradle` has no `seamlessportals` `-D` wiring at all.

### 1.7 Found in passing — **a live latent defect, unrelated to fractional**

`SeamIndexHolder.java:45-46` says mirror provenance is *"persisted alongside the pending-clear
journal"*. **It is not.** Exactly three classes extend `SavedData` (`SeamJournal`, `SeamFrameLink`,
IP's `GlobalPortalStorage`); `mirrorCreatedCells` has no Codec, no `SavedDataType`, no save/load path,
and `SeamIndexHolderMixin.java:15-17` says outright the fields *"die with"* the Level. **So the
shipped, user-confirmed frame-break rule is already non-durable across a world reload.** Fix it on its
own merits — §8 explains why the fractional model must not inherit it.

Also: the handoff's *"`mirrorCreatedCells` is already the right shape to carry a per-cell attribute"*
is **wrong** — it is a bare `LongOpenHashSet`, membership only. The real precedent is `seamCells`,
already a `Long2ObjectOpenHashMap<SeamCell>` carrying a per-cell **value**
(`SeamIndexHolderMixin.java:25-26`).

### 1.8 The "sleeper" (compile-time quad clamping) — correctness half real, **Sodium half backwards**

All **four** recorded rejections give **two** reasons; the second is *"silently no-ops under Sodium"*
(`REDSTONE_NEXT_SESSION.md:701, 710-712`; `REDSTONE_CLIP_PROMPT.md:34-37`; `REDSTONE_B_PROMPT.md:48-51`).
Quad clamping would live at `SectionCompiler.java:61-68` — the **vanilla** compile path Sodium
replaces wholesale. Making the cut view-independent changes nothing about Sodium reachability. The
sleeper defeats reason #1 and then claims reason #2's win; it does not have it.

Its correctness half is real and **stronger** than argued: a baked cut is an intersection of
half-spaces, so it fixes the multi-plane-cell residual (`SEAM_CLIP_DESIGN.md:74-77`) and the dest
pass's straddling arbitration exactly, and removes per-frame re-tessellation entirely. Costs it does
not price: UV re-interpolation on clipped quads (`BakedQuad` stores packed longs), and fluids/BEs
still bypass the block-quad path.

**The only path that would genuinely retire the Sodium gate is the MODEL route** (real partial block
models consumed by any mesher) — recon §0.9's own "custom block states, collision, models". That is
NOT v1. ⚠ And it is **unverified** that Sodium 0.9.1 consumes vanilla `BlockStateModel`/`BakedQuad`;
no Sodium sources exist in this tree. Do not cost it on that assumption.

---

## §2 THE MODEL

A **fractional seam cell** is a cell carrying at least one mirror-admitted binding whose portal plane
passes through the cell's interior. The cell's block volume is divided by that plane. **This
dimension owns the half on the `srcFacing` side; the far dimension owns the complement.** Under EXACT
alignment the two halves are complements of one whole block by construction.

**The stored quantity — `planeOffset`.** The plane's coordinate along `srcFacing.getAxis()`, minus the
cell's lower-corner coordinate on that axis. A `double` in `(0, 1)`.

```
kept thickness = (srcFacing.getAxisDirection() == POSITIVE) ? (1 - planeOffset) : planeOffset
```

`planeOffset` is a **pure function of portal geometry**, which has a large consequence for §3.

**`SeamPhase` grows a third answer.** Today `{COINCIDENT, DISJOINT}` (`SeamMap.java:250`) with
`phaseOf` binning `d < 0.25`. Under decision A the phase becomes:

- `DISJOINT` — plane on the cell boundary (`planeOffset ≈ 0` or `≈ 1`). Nothing straddles. Unchanged.
- `COINCIDENT` — plane at the cell centre within ε. **Retained as its own case**, because 12 production
  branch sites switch on "bisected vs flush" and the ½ case is every obsidian portal — keeping it
  distinct keeps those paths on their proven arithmetic and keeps `PlaneKey` a cheap exact map key for
  the overwhelmingly common case.
- `FRACTIONAL` — **new.** Plane strictly inside the cell but not at its centre. Carries `planeOffset`.

⚠ **The 12 fan-out sites each need a third answer, not a fallthrough** (`SeamMirror` ×4,
`SeamSignalContinuity` ×2, `SeamClipRenderer` ×2, `SeamCartContinuity`, `SeamShadowBridge`,
`SeamMirrorClient`, `SeamRegistry.continuationToward`). A `switch` without a `FRACTIONAL` arm that
silently takes the `DISJOINT` branch is the off-by-one-cell failure `SeamMap.java:247-248` warns about.
**Make the switches exhaustive so the compiler finds every site.**

---

## §3 STORAGE — the side table

**Decision B: position-keyed side table.** The two alternatives are dead, not merely disfavoured:

- **New BlockStates — DEAD.** `BlockStateBase.Cache` is built **once, eagerly**, in the `Blocks`
  static initializer, with **no invalidation path anywhere in vanilla** (`initCache`,
  `BlockBehaviour.java:490-495`). State-keyed partiality is immutable at runtime: every fraction would
  need pre-registering permanently, for every block, and is impossible for modded blocks. Under
  decision A (arbitrary) this is not even finite.
- **BlockEntity — DEAD.** Three independent gates, which would all have to move together:
  `SeamMirror.mayPlace`'s `hasBlockEntity()` refusal (`SeamMirror.java:89-93`), the clip predicate
  (`SeamClipRenderer.java:144`), and `mayPlace`'s separate multi-cell (`DOUBLE_BLOCK_HALF`/`BED_PART`,
  `:178-181`) and non-empty-fluid (`:86-88`) refusals.

**Shape.** `planeOffset` goes on `SeamBinding` (`SeamRegistry.java:62-70`, currently 7 components) as
an 8th component. Nothing new is needed to *hold* it — `seamCells` is already
`Long2ObjectOpenHashMap<SeamCell>`, and `SeamCell` already models up to **two** bindings per cell
(`SeamRegistry.java:139`), which any per-binding field must respect.

**Sync — no packet needed.** `seamCells` is derived **independently on each side** from IP's
`SERVER_PORTAL_TICK_SIGNAL` / `CLIENT_PORTAL_TICK_SIGNAL` (`AperturePassthroughInit.java:58-63`) with
per-side fingerprint maps (`:42-43`). That holds for anything derivable from portal geometry — and
`planeOffset` is. ⚠ **This is exactly why the fraction lives on the binding and not in
`mirrorCreatedCells`:** the latter is written only server-side (`LevelChunkSetBlockStateMixin.java:117`
gates on `instanceof ServerLevel`), so the client's copy is permanently empty and any attribute stored
there **would** need a packet.

**Persistence after teardown — v1 position, stated because §0.9 asks for losslessness.** When the
portal dies the plane dies, so a partial half has no geometry source. v1 does **not** persist
fractional geometry: on teardown the surviving half reverts to a **whole** block in its own dimension
and the mirror half clears — today's shipped frame-break rule, lossless from the placer's view. That
rule needs **provenance**, which §1.7 shows is currently non-durable. **Making provenance a real
`SavedData` is therefore a prerequisite, and it is a pre-existing bug.** `SeamFrameLink`'s dormant-link
pattern is the template. "Both halves survive as partials with no portal" stays future work.

---

## §4 THE ROUTING TABLE — which consumers read the side table

The consumer surface splits three ways by whether a position is available and whether the cache
short-circuits first. **v1 routes tiers (i) and (ii). Tier (iii) is deferred (§9).**

| tier | methods | position? | cache-first? | v1 |
|---|---|---|---|---|
| **(i)** | `getCollisionShape(level,pos,ctx)` (3-arg, `BlockBehaviour.java:669-671`); `getBlockSupportShape(level,pos)` (`:677-678`) | yes | **no** | **ROUTE** |
| **(ii)** | `isFaceSturdy` (79 sites, `:867-868`); `isCollisionShapeFullBlock` (34 sites, `:871-872`); `getCollisionShape(level,pos)` (2-arg, 56 sites, `:665-666`); `isRedstoneConductor` (`:605-607`); `isSuffocating` (`:779-781`) | yes | **yes** (except the two predicates) | **ROUTE** |
| **(iii)** | `isSolidRender()` (`:645`); `getOcclusionShape()`; `getFaceOcclusionShape(Direction)` | **no** | n/a | **DEFER** |

**Tier (i) is free.** The 3-arg form has **no cache branch even for an empty context** — the single
most important correction to the panel's framing. So the movement funnel (`BlockCollisions:93` →
`EntityCollisionContext:64` → 3-arg), raytracing (`ClipContext.Block.COLLIDER`) and block picking are
**all fully hookable and cannot be defeated**.

**Tier (ii) needs interception ahead of the `cache != null` ternary** — three redirects
(`isFaceSturdy`, `isCollisionShapeFullBlock`, 2-arg `getCollisionShape`) buy the behaviour of ~169
call sites. ⚠ **`isRedstoneConductor`/`isSuffocating` are the cheap door**: they are per-block
`StatePredicate` fields whose signature is `test(state, level, pos)` (`BlockBehaviour.java:1284-1286`)
— position-carrying, **not cached**. They are position-blind only because their *defaults* delegate to
the cached full-block test (`:988-989`). ⚠⚠ **But ~34 vanilla blocks override `isRedstoneConductor`**
in `Blocks.java` (mostly `Blocks::never`, two `Blocks::always`), so **intercepting only
`isCollisionShapeFullBlock` silently misses every one of them.** Intercept at the predicate seam too.

⚠ **Dead ends, do not spend time on them.** `getBlockSupportShape` looks like the support hook but is
**unreachable from `isFaceSturdy` for any non-dynamicShape block** (4 call sites, 3 inside
`SupportType.isSupporting`). And `isPathfindable` passes `EmptyBlockGetter.INSTANCE` **and**
`BlockPos.ZERO` (`BlockBehaviour.java:138, 140`) — no position exists to key on; position-dependent
pathfinding needs the CALLER changed, not the accessor. Same at the `Cache` constructor itself.

**Not the mechanism:** `Properties.dynamicShape()` (7 vanilla blocks) leaves `cache == null` and makes
every tier-(ii) method take its live branch. It is an existence proof that the live branch is
supported and exercised — but it is **per-BLOCK and global**, so it cannot express per-position
partiality and would forfeit the cache for every state of every affected block. Use it as evidence,
not as the design.

**Cost note:** the mod has **zero** `VoxelShape`/`Shapes.*` and **zero** `StateDefinition` references
today. This whole layer is new code. But `CollisionHelper.clipVoxelShape`
(`CollisionHelper.java:144-187`) already splits a shape at an **arbitrary** plane and handles
straddling (`clipBox` → `joinUnoptimized(AND)`) — reusable near-verbatim, with three adaptations:
pass `planePos` in the **same coordinate space** as the shape (block-local 0..1, not the world-space
shapes today's two callers pass), map the `@Nullable` return to `Shapes.empty()`, and
`.optimize()` + cache the result because a block-shape site is queried far more often than a per-tick
movement filter.

---

## §4a ★ THE VANILLA TEMPLATE — `SOUL_SAND` already is what this model needs

Found 2026-08-02 while building the gate. **`Blocks.SOUL_SAND` ships every piece of the tier-(i)/(ii)
split, decoupled deliberately, in vanilla:**

```java
// SoulSandBlock.java:15, 27-33      — collision is PARTIAL (14/16 high) …
private static final VoxelShape SHAPE = Block.column(16.0, 0.0, 14.0);
protected VoxelShape getCollisionShape(...)     { return SHAPE; }
// … while SUPPORT is explicitly restored to WHOLE:
protected VoxelShape getBlockSupportShape(...)  { return Shapes.block(); }

// Blocks.java:2050-2052             — and both predicates are overridden back to true:
.isRedstoneConductor(Blocks::always).isViewBlocking(Blocks::always).isSuffocating(Blocks::always)
```

Three consequences, each load-bearing:

1. **Collision and support ARE decouplable, first-class.** The handoff's *"`getBlockSupportShape`
   defaults to the collision shape, so support and collision cannot be decoupled"* is true only of the
   **default**. Vanilla overrides it deliberately, and rails/torches do sit on soul sand.
2. **It is the exact shape of §5's union reading**, already shipped and load-bearing in the base game
   — partial collision, whole support, whole conduction. That materially strengthens the union option
   from "a defensible alternative" to "the vanilla-sanctioned pattern for this exact situation".
3. **It is the ideal arm-3 witness.** Its cached `isCollisionShapeFullBlock` is **false** (14/16 is not
   a full cube), yet `isRedstoneConductor` returns **true** because the predicate is overridden. So a
   side table that intercepts only `isCollisionShapeFullBlock` **demonstrably misses it** — §4's ~34
   overriding blocks stop being an abstract warning and become a one-block regression test.

⇒ The gate uses `SOUL_SAND` alongside `STONE` in arm 3 precisely so the predicate seam is covered and
not merely asserted about.

---

## §5 THE SUPPORT RULE

**Decision B says a partial block reports partial. Rails pop.** That is the shipped default.

⚠ **§4a materially strengthens the alternative below** — the "union" reading is not a novel invention,
it is `SOUL_SAND`'s shipped behaviour. Worth re-reading before the live round.

⚠ **OPEN — NEEDS THE USER'S WORD BEFORE IT COULD EVER BECOME DEFAULT.** There is a second defensible
reading of "partial" *at a seam specifically*: a COINCIDENT/FRACTIONAL cell is, in `SeamMap`'s own
words, *"one physical slot seen from two sides"*. A rail laid across the seam rests on the **union**
of the two halves, which is a whole cube. Under that reading support would query the union and rails
would survive — while collision still genuinely ends at the plane.

This is **not** being installed as default, because it would substitute my judgement for an explicit
decision. It ships as a **DEFAULT-OFF experiment lever**, `-Dseamlessportals.seamSupportUnion`
(`-PseamSupportUnion`), so the alternative can be seen live in one run and decided on evidence rather
than argument. For cells with **no** counterpart (query-only bindings), partial reporting stands under
both readings and the rail correctly pops.

---

## §6 THE FOUR FRONTS, IN THE ORDER DECISION C FIXES

1. **THE GATE** (§7) — three arms, asserting today's whole-cube truth so it inverts when the model
   lands. Nothing else starts first.
2. **STORAGE** — `planeOffset` on `SeamBinding`; `SeamPhase.FRACTIONAL`; exhaustive switches at the 12
   sites; provenance made durable (§3).
3. **COLLISION + SUPPORT/CONDUCTION** — tier (i) then tier (ii), each with its own lever and its own
   gate arm going green as it lands.
4. **RENDER** — `keptDir = f`, delete the near-plane guard, replace `cameraSideWindowPossible` per
   §1.2, `PlaneKey` `int → double` reading the real plane. Last, and with live eyes.

---

## §7 THE GATE — three arms, built first

New RS leg `rsSeamCollisionGate`, RS-only compatible, own staging, cleanup in `finally`. Template:
`rsPlayerPlaceBracketGate` (`CrossingSmoke.java:4417-4510`) — lever-aware, inverting in the same leg,
precondition-asserting.

**Arm 1 — MOVEMENT.** Stand an entity against a seam block; assert **where it is stopped**, client and
server. Today: stopped at the cell's outer face (whole cube). Under the model: stopped at the plane.
Hook the funnel (`BlockCollisions:93`), not a settled position.

**Arm 2 — SUPPORT / RAIL SURVIVAL.** Rail on a seam-cell support block; assert survival. Today:
survives. Under the model (default): pops. **Also settles the §1.3 contested slab geometry** — assert
`SupportType.RIGID.isSupporting` directly on a known half-height shape and record the answer.

**Arm 3 — SUFFOCATION / REDSTONE CONDUCTION.** Assert `isSuffocating` and `isRedstoneConductor` at a
seam cell, **including at least one block that overrides `isRedstoneConductor`** (§4), so the
predicate seam is covered and not just the cached boolean.

**Discipline, non-negotiable per the standing rules:**
- **Assert the fixture's PRECONDITION**, not just the outcome — the binding is mirror-admitted, the
  cell really is COINCIDENT/FRACTIONAL, the entity really is in contact.
- **Verdict must INVERT under its own lever in the same leg.** A gate that cannot fail cannot tell
  "the fix works" from "the defect never existed here".
- **Assert COVERAGE** — non-zero contacts/queries, or the arm reports vacuous.
- **Forceload the fixtures** (`getEntitiesOfClass` silently skips unloaded chunks).
- Sample around the crosshair, not `Options.hideGui` — it does not exist in 26.2.
- `javap -c -p` every mixin target on `minecraft-merged-deobf-26.2.jar` **before first launch**.
- **Rendering and collision both need LIVE eyes** — the headless gate is necessary, never the verdict.

**Sodium splits the verdict**: the dynamic renderer self-gates OFF under Sodium
(`SeamClipRenderer.java:124-131`), so geometry and collision would disagree there. A default-harness
run certifies one of two shipping configurations. Recorded, not solved, in v1.

---

## §8 HAZARDS CARRIED

- **The four rail assertions and two prose-justified fixtures** (§1.4) — rewrite to the new truth,
  inverting under the lever. Do not let one pass for the wrong reason.
- **`BaseRailBlock.shouldBeRemoved` destroys rather than refuses** (§1.3), and reaches *neighbour*
  cells for `ASCENDING_*`.
- **Snow vs rail inconsistency** (§1.3) — snow reads the live shape, rails read the cache. Whichever
  way §5 resolves, they must agree or it reads as a bug.
- **The `is5-shadow` silent-merge risk** (§1.5) — pass the arming state, do not assume it.
- **Persistence** (§1.7, §3) — do not inherit the non-durable provenance; fix it as a prerequisite.
- **Fluids and BEs never clip** — `getFluidState` reads the SectionCopy directly, BEs excluded by
  predicate on both sides.
- **Hit outline and crumbling overlay stay full-cube** — `ClipContext.getBlockShape` is the chokepoint
  (IP already has `MixinClipContext`) if the panel later wants them cut.
- **`destinationIsFree` does a SYNCHRONOUS chunk load on the placement path**
  (`SeamMirror.java:166-167`) — widening what `mayPlace` inspects widens that exposure.

---

## §9 DEFERRED TO FUTURE POLISH — tier (iii), by user decision

`isSolidRender()`, `getOcclusionShape()` and `getFaceOcclusionShape(Direction)` are **zero-argument,
unconditional field reads** frozen by `initCache` alongside `legacySolid`, `occlusionShapesByFace`,
`propagatesSkylightDown` and `lightDampening`. **There is no position to key a side table on.**

Consequence, recorded so it is not rediscovered: a fractional cell keeps **whole-cube occlusion**, so
the light engine (`LightEngine.java:57-58`, `ChunkSkyLightSources.java:137-138` — both read
`getOcclusionShape()` with no position at all) will light a cut cell as if solid. Closing this needs a
separate ambient-position mechanism, and getting it wrong desyncs lighting. **Out of v1 scope.**

---

## §10 LEVERS AND GATE ROWS

Every fix DEFAULT-ON behind `-Dseamlessportals.disableX`, declared in `AperturePassthroughLever`
(never on a mixin class), with `-P` rows in **BOTH** `fabric/build.gradle` blocks.

| lever | default | gates |
|---|---|---|
| `disableSeamFractional` | fix ON | master A/B for the whole model |
| `disableSeamFractionalCollision` | fix ON | tier (i) — movement/raytrace |
| `disableSeamFractionalSupport` | fix ON | tier (ii) — support/conduction/suffocation |
| `seamSupportUnion` | **OFF** (experiment, §5) | union support reading |
| `seamFractionalProbe` | OFF | 1 Hz latched summary, prefix `[SEAM FRAC]` |

**Fast iteration (~3 min):**
```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true -PrsOnly=true
```
**Full matrix before any commit:** the two FULL suites, the (b) inversions, the (c) inversions, the
(d) levers, the (e) levers (`-PdisableDestEntitySectionExact`, `-PdisableCrossDimPositionCodecSync`),
the clip rows (`-PenableSeamClip=true` asserts the cut; default asserts the whole-cube branch), plus
every new lever **both directions**.

**Never run `gradlew --stop`. Never run two Gradle jobs against this project at once.** Java cleanup:
own PIDs only, filtered by THIS worktree's path; `idea64.exe` is a JVM. Build scaffolding
(`build.gradle`, `settings.gradle`, `gradlew*`, `gradle/`) is UNTRACKED and stays so; `git commit -F`
with explicit file lists; push every commit.

---

## §11 THE OFFSET MEASUREMENT — ★ RUN 2026-08-02, FOR THE FIRST TIME EVER

Per §1.6, run **only** the exact-only lever, RS-only:
```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PrsOnly=true -PdisableSeamExactOnly=true
```

**RESULT — a clean A/B off one line of the seam-map gate's own coverage report:**

| run | registry cross-checks | policy-declined query-only | phase/continuation checks |
|---|---|---|---|
| default | 42 | **9** | **33** |
| `-PdisableSeamExactOnly` | 42 | **0** | **42** |

**All legs pass in both.** Three things this establishes, and one it explicitly does not:

1. **The lever is live and its effect is exactly the documented one.** Nine cells that the exact-only
   policy declines become mirror-admitted — portal A's aperture, the suite's own OFFSET fixture
   (`CrossingSmoke.java:1061`, "portal A's dest hangs a half-block off in Y").
2. **The causal chain in §1.6 is confirmed by the +9.** Phase/continuation checks run only for
   admitted bindings, and they rise by exactly the number of cells that stopped being declined — so
   a decline really does take those cells out of the (b)/(c)/(d) surface wholesale, not merely make
   them "query-only".
3. **★ THE HANDOFF'S PREDICTED RED DID NOT FIRE.** `CrossingSmoke.java:1094` stayed green, exactly as
   §1.6 predicted: portal A is one-way, so `resolveDestCell` returns `SeamMap.mirrorCell` verbatim
   and the gate compares a pure function against itself.
4. **⚠ WHAT IT DOES NOT ESTABLISH: that offset seams work.** Green here means *the fixture cannot
   discriminate the offset destination arithmetic*, which is the §1.6 prediction confirmed, not a
   pass. Nine cells being admitted says nothing about whether they are admitted to the RIGHT cells.

**So the honest sizing answer is unchanged and now evidenced:** a discriminating fixture needs a
REVERSE portal at the offset destination — the only configuration where `resolveDestCell`'s
projection diverges from `SeamMap.mirrorCell`. That fixture does not exist and building it is the
real content of "half a day here sizes the offset job".
