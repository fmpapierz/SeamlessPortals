# STARTER PROMPT — THE FRACTIONAL SEAM MODEL

Paste the block below to open the next session.

---

**THE FRACTIONAL SEAM MODEL** in Seamless Portals — genuine partial blocks at the seam: geometry,
collision and state ending at the plane.

Work in the worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`
(branch `redstone/passthrough`). Tree clean at `093097e`, matrix green, everything pushed.

READ FIRST, in order:

1. `migration/FRACTIONAL_HANDOFF.md` — **the whole brief.** The two decisions that gate everything,
   the four fronts in cost order, the hazards, and the offset job.
2. `migration/SEAM_CLIP_DESIGN.md` — the landed RENDERER (v2). Its header says it is the renderer
   only; that is accurate. §6 lists 19 residuals.
3. `migration/REDSTONE_NEXT_SESSION.md` — state for everything shipped, especially the **HAZARDS
   list**, where every line has a scar behind it.

## §0 THIS IS A DESIGN PANEL BEFORE IT IS AN IMPLEMENTATION

There is **no fractional spec** anywhere in the tree — recon §0.9 is six lines under "do not build
now, do not lose", and the tree itself says fractional "deserves its own design panel". Do not open
a file and start editing. **Ask me these two first; both change what gets built:**

**A. THE FRACTION — arbitrary, or exactly ½?** The single biggest lever on the size of this work.
The docs claim arbitrary fractions were the day-one requirement, but the code does not compute
that: `SeamMap.phaseOf` is BINARY (COINCIDENT within 0.25 of cell centre, else DISJOINT, with an
explicit "a phase this build does not model"), exact-only mirroring means every shipped cut is
exactly ½ or 0, and the clip's `PlaneKey` stores halves only. Exactly-½ on COINCIDENT seams is much
smaller, ships sooner, and covers every obsidian portal in the game.

**B. THE STORAGE — new BlockStates, a position-keyed side table, or a BlockEntity?** Each has a
named disqualifier (handoff §0.2). A side table is the only one that survives "any block, any
cell", but it is invisible to `BlockStateBase.Cache` — which is what support, suffocation and
redstone conduction read. That tension is the panel's central question.

## §1 WHAT THIS ENGAGEMENT MUST RESPECT

- **★ A vertically half-cut cube fails `SupportType.RIGID`** (its UP face is 16×8 and no longer
  covers the central 12×12), so `Block.canSupportRigidBlock` fails and `BaseRailBlock.canSurvive`
  **pops a rail off a partial support block** — the exact "build blocks through the opening and lay
  rail on top" case that (b) shipped. **Fractional breaks it unless the panel decides how a partial
  block reports support.**
- **★ THERE IS NO COLLISION GATE ANYWHERE IN THE SUITE.** Build it BEFORE touching shapes, asserting
  today's whole-cube truth so it inverts when fractional lands.
- **★ SYNC WITH `is5-shadow` FIRST.** The clip's dest arm is one line at
  `SecondaryWorldRenderCore.java:1135` whose comment says it ASSUMES the current Step-10.5 arming
  semantics, and that file is HOT in the iris shaders-ON session.
- **The declined pop is ONE expression** — `SeamClipRenderer.java:334`,
  `Direction keptDir = side >= 0 ? f : f.getOpposite()`. A fixed source-side cut makes that
  `keptDir = f` and drops two gates that exist only to serve the camera-derived choice. Cheap, and
  worth doing early for live eyes.
- **The sleeper:** a fixed cut is view-INdependent, so compile-time quad clamping — rejected twice
  because two cameras draw one ViewArea from opposite sides — becomes available again, and that
  path would also retire the Sodium self-gate the dynamic renderer cannot escape. Cost it in the
  panel.
- **Collision has one clean funnel** (`BlockCollisions:93`, `context.getCollisionShape(state,
  getter, pos)` — it has the POSITION), **one leaky bypass** (the 2-arg
  `BlockStateBase.getCollisionShape` returns the cache directly; suffocation, pathfinding and
  support use it), **and one cached trap** (`BlockStateBase.Cache` fixes `faceSturdy[]` and
  `isCollisionShapeFullBlock` per BLOCKSTATE against `EmptyBlockGetter`).
- **The tree already has a plane-splitter:** `CollisionHelper.clipVoxelShape` handles the straddling
  case correctly and has two live callers — **neither applies it to a block's own shape in its own
  world.** That asymmetry *is* "whole cube in both worlds".
- **Persistence after portal teardown is undesigned**, while §0.9's whole motivation is that a frame
  break becomes lossless. `SeamFrameLink`'s dormant-link pattern is the only precedent.

## §2 STANDING DISCIPLINE

Instrument before theorising. Assert the outcome I can see, on the side of the wire my eyes are on.
Hook the write, not the settled state. Gates lever-aware and coverage-asserting, inverting under
their own lever in the same leg — **and assert the fixture's PRECONDITION, not just its outcome**
(RS-CART-F caught two wrong-reason fixtures that way). A fixture that fails for the WRONG reason is
worse than one that passes. javap every mixin target before first launch. Every fix DEFAULT-ON
behind `-Dseamlessportals.disableX`, `-P` rows in BOTH `fabric/build.gradle` blocks, `git commit -F`
with explicit file lists, push every commit. Build scaffolding stays UNTRACKED.

**Probe checklist** (five instrument defects in the last session, one shape each): does it fire on
the stack you are already looking at; can it throw during construction/removal; can routine volume
starve the rare line; is the quantity you assert on still true when you read it?

**Never run `gradlew --stop`. Never run two Gradle jobs against this project at once** — the last
session deadlocked its own matrix that way and I had to kill the windows by hand.

## §3 GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true -PrsOnly=true
```
Fast iteration (~3 min). Clip rows: `-PenableSeamClip=true` asserts the cut, default asserts the
whole-cube branch. Full matrix before any commit, including the (e) levers
(`-PdisableDestEntitySectionExact`, `-PdisableCrossDimPositionCodecSync`).
**Rendering and collision both need LIVE eyes — ask me.**

## §4 OPTIONAL, CHEAP, AND IT SIZES THE OFFSET HALF OF THIS WORK

`-PdisableSeamExactOnly` and `-PdisableSeamPhaseGate` both exist, wired in both build.gradle
blocks, and **no gametest has ever run under either.** Run them together against the offset fixture
at `CrossingSmoke.java:1061` and record what actually breaks. That tells the panel whether OFFSET
seams need the partial block at all, or just the two levers flipped plus residue. ⚠ Expect to fix
the STRICT assertion at `CrossingSmoke.java:1094`, not the code.

## §5 AFTER THIS

(c) step 2 — redstone wire/dust across the seam. Fully briefed and ready at
`migration/REDSTONE_C2_HANDOFF.md` / `REDSTONE_C2_PROMPT.md`. Note that its connection-shape surface
must be re-derived once a seam cell is partial, which is why it queues behind this.
Then D2 delivery-forwarding, the deferred §6 items in `REDSTONE_C_SPEC.md`, the (b) residuals.
