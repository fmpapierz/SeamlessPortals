# THE FRACTIONAL SEAM MODEL — HANDOFF

**Branch `redstone/passthrough`, worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`. Tree clean at `093097e`,
matrix green, everything pushed.** Opened 2026-08-01, **by explicit user decision** after (e)
closed both cart defects.

> **★★★ §0 IS SUPERSEDED — 2026-08-02. The design panel ran; both gating questions are ANSWERED.
> Read `migration/FRACTIONAL_DESIGN.md` FIRST.** Decisions: (A) **genuine arbitrary fraction**;
> (B) **position-keyed side table, cut applies to collision AND support/conduction**, occlusion/
> lighting deferred to polish; (C) **gate → storage → collision → render flip LAST**.
> ⚠ **SIX factual claims in this file are CORRECTED there** — §0.1's "unreachable by policy" (false:
> a 0.3 plane is admitted, binned COINCIDENT and silently mis-cut TODAY), §1's "the pop is ONE
> expression" (half wrong), §2's support reason (inverted — RIGID needs the PERIMETER, and a
> position-keyed cut makes rails FLOAT, not pop), §2's is5-shadow "sync FIRST" (no conflict today),
> §5's "nobody has ever run either lever" (the phase gate HAS been run green), and §1's Sodium
> "sleeper" (backwards). Where the two files disagree, `FRACTIONAL_DESIGN.md` is right and carries
> the evidence. Everything below stays as scoping material.

> **★★ USER DECISION 2026-08-01: FRACTIONAL FIRST.** A survey recommended (c) step 2 (wire/dust)
> instead; the user chose fractional. That alternative is fully briefed at
> `migration/REDSTONE_C2_HANDOFF.md` and stays available. **The survey's case against fractional is
> not discarded — it is §6 of this document, because it is the best scoping material that exists.**

---

## ★★ §0 THIS IS A DESIGN PANEL BEFORE IT IS AN IMPLEMENTATION

**There is no fractional spec anywhere in the tree.** Recon §0.9 is six lines under "FUTURE PLANS …
do not build now, do not lose"; `SEAM_CLIP_DESIGN.md` says in its own header it is the RENDERER
only; `REDSTONE_NEXT_PROMPT.md:85` says fractional "deserves its own design panel". Do not open a
file and start editing. **Two decisions gate everything, and both need the user's word.**

### 0.1 THE FRACTION — arbitrary, or exactly ½? *The single biggest lever on the size of this work.*

The docs claim arbitrary fractions were the day-one requirement — half-models were rejected for the
clip because "an offset seam needs an arbitrary cut fraction, not a fixed half". **The code does not
compute that:**

- `SeamMap.phaseOf` is **BINARY** — COINCIDENT when the plane is within 0.25 of the cell centre,
  DISJOINT otherwise, with an explicit comment that anything between "is a phase this build does
  not model" (`SeamMap.java:250-267`).
- EXACT-only mirroring (user decision 2026-07-26) means **every cut in shipped geometry is exactly
  ½ or 0**.
- The shipped clip's `PlaneKey` stores `2*blockCoord+1` — **halves only**.

So "arbitrary fraction" is currently unreachable *by policy*, and building for it is speculative
work. **Ask the user: does fractional mean genuine arbitrary fractions (which requires the offset
work in §5 to land first or alongside), or exactly-½ cuts on COINCIDENT seams (much smaller, ships
sooner, and covers every obsidian portal in the game)?**

### 0.2 THE STORAGE — three options, each with a named disqualifier, none chosen

| option | precedent | disqualifier |
|---|---|---|
| **New BlockStates** | — | blows up the state space, needs a model per block, and is **impossible for arbitrary/modded blocks**. Also changes `isRedstoneConductor`/`isSuffocating` **globally** for that state (§2). |
| **Position-keyed side table** | strong — `SeamIndexHolder` already hangs `seamCells`/`mirrorCreatedCells` `LongSet`s off the `Level`; `SeamJournal`/`SeamFrameLink` are per-level `SavedData` with Codecs | **invisible to `BlockStateBase.Cache`**, which is what support, suffocation and redstone conduction read (§2). |
| **BlockEntity** | — | blocked today on both sides: `SeamMirror.mayPlace` refuses any `state.hasBlockEntity()` outright, and the clip predicate excludes BE blocks because reporting AIR drops the BE from the compile's collection. |

**A side table is the only one that survives "any block, any cell"** — but it buys the §2 cache
trap. That tension is the design panel's central question.

---

## §1 THE FOUR FRONTS, IN COST ORDER

### (i) RENDER — cheapest by far, and the machinery is already built and pixel-gated

`SeamClipRenderer` (756 lines, landed, DEFAULT OFF) already does the cut in three arms: mesh
exclusion (snapshot at `RenderRegionCache.createRegion` RETURN → `RenderSectionRegion.getBlockState`
answers AIR, which also un-culls neighbour faces), per-frame dynamic re-tessellation of those cells
drawn through a `gl_ClipDistance` plane, and lifecycle dirtying with its own accounting. There is a
working pixel gate (`rsSeamClipGate`) and an 8-shot arc-evidence leg.

**★ The declined pop is mechanically ONE expression.** `SeamClipRenderer.java:325-345`:

```java
double side = f · (camPos − cellCenter);
Direction keptDir = side >= 0 ? f : f.getOpposite();   // ← view-dependent
```

Under a fixed source-side cut that becomes `keptDir = f`, and the two gates that exist *only* to
serve a camera-derived choice — `cameraSideWindowPossible` and the near-plane `|side| < ADJUSTMENT`
guard — both fall away.

**★ THE SLEEPER.** Compile-time quad clamping was rejected **twice** for one reason: "one dimension
= one ViewArea, drawn in the same frame by two cameras on opposite sides of the plane". That is an
argument against baking something **view-dependent**. A fixed per-cell cut is view-INdependent, so
baking it is no longer obviously wrong — **and that path would also retire the Sodium self-gate the
dynamic renderer cannot escape.** Worth costing in the panel.

### (ii) COLLISION — one clean funnel, one leaky bypass, one cached trap

- **The funnel.** Every entity-vs-block collision in 26.2 converges on `BlockCollisions:93`:
  `this.context.getCollisionShape(blockState, this.collisionGetter, this.pos)` then `.move(this.pos)`.
  **That call has the POSITION** — exactly what a per-cell cut needs. `CollisionGetter.getBlockCollisionsFromContext`
  is the sole constructor of `BlockCollisions` on the collision path, and
  `CollisionContext.getCollisionShape` has exactly three impls (`EntityCollisionContext`,
  `MinecartCollisionContext`, `PositionCollisionContext`). One hook there covers movement,
  `noCollision`, `isUnobstructed`, the anticheat's `getPreMoveCollisions` and IP's own
  `collideBoundingBox`.
- **The leak.** `BlockStateBase.getCollisionShape(BlockGetter, BlockPos)` (2-arg,
  `BlockBehaviour.java:665-667`) returns `this.cache.collisionShape` directly — it never reaches the
  block and never sees a `CollisionContext`. **Suffocation, pathfinding and support use it.**
- **The trap.** `BlockStateBase.Cache` computes `collisionShape`, `faceSturdy[]` and
  `isCollisionShapeFullBlock` **once per BLOCKSTATE** against `EmptyBlockGetter`/`BlockPos.ZERO`. So
  position-keyed partiality is **invisible** to support and redstone conduction, while state-keyed
  partiality changes them **globally**.

**★ The tree already contains a plane-splitter, and it is one method.**
`CollisionHelper.clipVoxelShape(shape, planePos, planeNormal)` clips a `VoxelShape` at an arbitrary
plane and handles the straddling case correctly (`clipBox` → `Shapes.joinUnoptimized(..., AND)`),
`CollisionHelper.java:144-187`. It has exactly two live callers, both entity-side, and **it is never
applied to a block's own shape in its own world**. That asymmetry — dest side clipped, source side
not — *is* the concrete shape of "whole cube in both worlds".

**What the seam does today:** `PortalCollisionHandler.processThisSideCollisionShape` explicitly
REFUSES to cut any block shape that STRADDLES the plane ("if the box is not fully behind the plane,
keep it" — a workaround for diagonal portals), and a coincident seam block is by definition exactly
that shape. Blocks fully *past* the plane are removed via a 10-block-deep exclusion sweep, which is
why walking through feels right; **the straddling cell is the single case the mechanism steps over.**

### (iii) STATE STORAGE — the genuinely undesigned front

Nothing in the tree represents a fraction. See §0.2.

### (iv) MIRROR WRITE PATH — smallest delta; it may need almost nothing

Driver: `LevelChunkSetBlockStateMixin.seamlessportals$driveSeamMirror` at `LevelChunk.setBlockState`
RETURN (upstream of every `markAndNotifyBlock` filter) → `SeamMirror.onSeamCellChanged` →
`applyToDestination` writes `newState.rotate(binding.stateRotation())` with
`UPDATE_ALL | UPDATE_SKIP_ON_PLACE`. Under EXACT/COINCIDENT geometry **the two halves are still
complements of ONE block**, so the mirror still writes the same rotated state — what changes is that
each side must record *which half it owns*. Side table ⇒ one extra line per write and per clear.
BlockState ⇒ `mayPlace`'s BE/multi-cell refusals and `destinationIsFree`'s `isAir() || placeholder`
test both need widening. `mirrorCreatedCells` is already the right shape to carry a per-cell
attribute, and `SeamBinding` already carries everything a per-cell cut plane needs (`srcFacing` = the
plane normal, `destDim`/`destPos`, `stateRotation`, `phase`, `seamContinuous`).

---

## §2 ★ HAZARDS — the sharpest one first, and it breaks a shipped feature

**A vertically half-cut cube fails `SupportType.RIGID`.** Its UP face is 16×8 and no longer covers
the central 12×12 region, so `Block.canSupportRigidBlock` fails — and `BaseRailBlock.canSurvive` is
*exactly* `canSupportRigidBlock(level, pos.below())`. **A rail sitting on a partial support block
pops off.** That lands squarely on the "build a line of blocks through the opening and lay rail on
top" case that §0.2 of the original recon exists for, and which (b) shipped. **Fractional would
break it, not enable it.** Decide in the panel how a partial block reports support.

- **`isRedstoneConductor` and `isSuffocating` are both derived from `isCollisionShapeFullBlock`**
  (`BlockBehaviour.java:871-873`, `:988-990`), i.e. from the per-state cache. State-keyed partiality
  changes them globally; position-keyed partiality leaves them wrong in the other direction.
- **There is NO gametest anywhere asserting collision at a seam.** The change would ship with zero
  regression coverage on the exact property it alters. **Build that gate first** (§4).
- **Persistence after teardown is undesigned.** `SeamRegistry` is derived from live `Portal` entities
  each tick, so the cut plane vanishes with the portal — while §0.9's stated motivation is that a
  frame break becomes **lossless** with both halves surviving. What defines a surviving half's
  geometry after teardown is undecided; `SeamFrameLink`'s dormant-link pattern is the only precedent.
- **★ SHARED FILE / CONCURRENT SESSION.** The clip's dest arm is ONE line at
  `SecondaryWorldRenderCore.java:1135`, and its own comment says it **ASSUMES the current Step-10.5
  inner-clip arming semantics (−ADJUSTMENT)**. That file is HOT in `.claude/worktrees/is5-shadow`
  (iris shaders-ON). **Sync with that session on the arming contract BEFORE building, not at merge
  time.**
- **Fluids and block entities never clip.** The region's `getFluidState` reads the SectionCopy
  directly and is not routed through the AIR report, so a waterlogged seam block keeps its water
  whole. BE blocks are excluded by predicate on both the clip and mirror sides.
- **Hit outline and crumbling overlay stay full-cube.** `ClipContext.getBlockShape` is the outline
  chokepoint and IP already has a mixin there (`MixinClipContext`) — that is the hook if the panel
  wants them cut too.
- **Sodium/iris turns the dynamic renderer off entirely** (no meshing hook in the compat layer), so
  under Sodium geometry and collision would DISAGREE unless the compile-time path in §1(i) is taken.

---

## §3 WHAT THE CLIP CANNOT DO — the gap fractional must close

The landed clip is **render-only, view-dependent, half-only, COINCIDENT-only**:

- the far half remains a **solid, targetable, collidable block** — the "invisible-solid far half";
- the kept half is chosen per draw from the camera side, so it **swaps** as the camera crosses the
  plane's lateral extension — the pop the user declined live on 2026-07-27;
- `PlaneKey` expresses **multiples of ½ along an axis only**;
- OFFSET / DISJOINT / query-only / BE / fluid cells are excluded by predicate;
- 19 residuals recorded in `SEAM_CLIP_DESIGN.md` §6.

---

## §4 FIRST STEPS — instrument-first, and the first instrument does not exist yet

1. **Ask the user §0.1 and §0.2.** Both change what gets built. Do not guess.
2. **Sync with `is5-shadow` on the `SecondaryWorldRenderCore` arming contract** before any render
   work (§2).
3. **BUILD THE COLLISION GATE FIRST — there is none anywhere in the suite.** A leg that stands an
   entity against a seam block and asserts where it is stopped, on the CLIENT and the SERVER, with a
   lever. It must be red-able: today it should assert the WHOLE-CUBE behaviour (that is the current
   truth), so that when fractional lands the same leg inverts. **Assert the fixture's precondition,
   not just its outcome** — RS-CART-F caught two wrong-reason fixtures that way (see the HAZARDS
   list). Also gate `SupportType.RIGID`/rail-survival at a seam cell before touching shapes, because
   that is what §2 says will break.
4. **Then the render arm**, which is nearly free: flip `keptDir = side >= 0 ? f : f.getOpposite()`
   to `keptDir = f`, delete `cameraSideWindowPossible` and the near-plane guard, and re-run
   `rsSeamClipGate` + the arc-evidence leg. This alone answers the user's declined-pop complaint and
   is worth doing early for live eyes.
5. **Then collision**, hooked at the `BlockCollisions:93` funnel, with the §2 leak and trap handled
   explicitly rather than discovered.
6. **Then state storage and persistence.**

---

## §5 THE OFFSET JOB — fractional's *other* purpose, and it is multi-front

`SeamAlignment.OFFSET`'s javadoc names fractional as the user-approved long-term answer, and
`SeamMirrorPolicy:51-70` says "**To widen later:** implement the destination resolution for
`SeamAlignment#OFFSET` … and admit it here". **That is not the whole job.** A survey found:

- **The handoff's "widening is editing two methods in `SeamMirrorPolicy`" is REFUTED for the
  alignment axis.** A decline at `SeamRegistry.bind` sets `mirrorable = false`, which nulls
  `destDim`/`destPos` **and clears `seamContinuous`** — and every (b) rail, (c) signal and (d) cart
  path gates on `isMirrorable() && seamContinuous()`. So an OFFSET seam is **not "query-only"** as
  the code comment claims: it is dead for (b), (c), (d), frame links and the clip renderer. Flipping
  the predicate switches **five never-exercised subsystems on at once**.
- **A SECOND independent gate exists** — `SeamMirror.isPhaseGated`, five call sites, in `SeamMirror`
  not in the policy class — which classifies the canonical offset pair COINCIDENT on one side and
  DISJOINT on the other, making mirroring **one-directional** and break-clears-both **asymmetric**.
- **Column pairing can go 3-against-2** under lateral offset (`SeamMap.enumerateColumns`,
  `MIN_OVERLAP 0.5`).
- **`resolveDestCell` diverges between client and server** when the far ClientLevel is cold.

**★ CHEAP MEASUREMENT THAT SIZES ALL OF THAT, AND NOBODY HAS EVER RUN IT.**
`-PdisableSeamExactOnly` and `-PdisableSeamPhaseGate` both exist, wired in both `build.gradle`
blocks, and no gametest has ever run under either. Run them TOGETHER (exact-only alone leaves the
phase gate declining the boundary side) against the offset fixture that already exists at
`CrossingSmoke.java:1061` ("portal A's dest hangs a half-block off in Y"). Record what actually
breaks. ⚠ Expect to fix the STRICT assertion at `CrossingSmoke.java:1094` (it demands
`destPos == SeamMap.mirrorCell`, and the arithmetic `resolveDestCell` exists precisely to override
under offset) — **fix the assertion, not the code.**

Half a day here tells the panel whether OFFSET needs the partial block at all, or just the two
levers flipped plus residue.

---

## §6 THE CASE AGAINST DOING THIS FIRST — kept, because it is the honest scoping

A survey recommended (c) step 2 instead. The user overrode it; these remain true and are the risks
this engagement carries:

- **The offset decline only bites WAND-BUILT pairs.** An obsidian pair is COINCIDENT and EXACT by
  construction (recon §2: no block face ever coincides with the plane on an obsidian frame), so
  every vanilla nether portal already mirrors, already carries rails, already carries signal. The
  user's live "boundary-phase pairs don't carry signal" is specifically their `/portal`- and
  wand-built `.5`-vs-flush pairs.
- **Render context is fresh where fractional is CHEAP and cold where it is HARD.** (e) lived in the
  entity-visibility gate and dest-pass plumbing; fractional's expensive fronts are collision and
  state storage — server-side, greenfield, zero `VoxelShape`/`Shapes.*` references anywhere in the
  passthrough package.
- **Wire's connection-shape surface must be re-derived once a seam cell is partial**, so doing (c)
  step 2 afterwards means some of it gets built twice. That cuts both ways and is why the ordering
  was arguable at all.
- **Fractional's risk is DESIGN risk, not execution risk** — hence §0.

---

## §7 STANDING DISCIPLINE

Instrument before theorising. Assert the outcome the user can see, **on the side of the wire their
eyes are on**. Hook the write, not the settled state. Gates lever-aware, coverage-asserting, and
inverting under their own lever in the same leg. **Assert the fixture's PRECONDITION, not just its
outcome.** A fixture that fails for the WRONG reason is worse than one that passes. javap every
mixin target on `minecraft-merged-deobf-26.2.jar` before first launch. Every fix DEFAULT-ON behind
`-Dseamlessportals.disableX`; `-P` rows in BOTH `fabric/build.gradle` blocks; `git commit -F` with
explicit file lists; push every commit. Build scaffolding is UNTRACKED and stays so.

**Probe checklist** (five instrument defects in the (e) session, one shape each): does it fire on
the stack you are already looking at; can it throw during construction/removal; can routine volume
starve the rare line; is the quantity you assert on still true when you read it?

**Never run `gradlew --stop`.** **Never run two Gradle jobs against this project at once** — the (e)
session deadlocked its own matrix that way. Java cleanup: own PIDs only, filtered by THIS worktree's
path; `idea64.exe` is a JVM.

## §8 GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true -PrsOnly=true
```
Fast iteration (~3 min). The clip has its own rows: `-PenableSeamClip=true` asserts the cut,
default asserts the whole-cube branch. Full matrix before any commit: the two FULL suites, the (b)
inversions, the (c) inversions, the (d) levers, the (e) levers (`-PdisableDestEntitySectionExact`,
`-PdisableCrossDimPositionCodecSync`), plus every new lever both directions.
**Rendering and collision both need LIVE eyes — ask the user.**
