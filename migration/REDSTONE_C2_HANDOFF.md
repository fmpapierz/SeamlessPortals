# (c) STEP 2 — REDSTONE WIRE/DUST ACROSS THE SEAM — HANDOFF

**Branch `redstone/passthrough`, worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`. Tree clean at `55b7611`,
matrix green, everything pushed.** Opened 2026-08-01 after (e) closed both cart defects.

---

## ★★ WHY THIS AND NOT THE FRACTIONAL MODEL

The (e) session's closing recommendation was **fractional first**, on three arguments. A four-reader
survey of the actual tree **refuted all three**, and the ordering flipped. Recording that here so
nobody re-derives the wrong answer from the same intuitions:

| the argument for fractional-first | why it does not survive |
|---|---|
| "Fractional unblocks (b)/(c), which are stuck on exact-only" | **Only for wand-built pairs.** An obsidian pair is COINCIDENT and EXACT by construction — recon §2: no block face ever coincides with the plane on an obsidian frame — so every vanilla nether portal already mirrors, already carries rails, already carries signal, and would already carry dust. The user's live "boundary-phase pairs don't carry signal" is specifically their `/portal`- and wand-built `.5`-vs-flush pairs. |
| "Render context is fresh from (e)" | **It is fresh where the work is cheap and cold where it is hard.** (e) lived in the entity-visibility gate and dest-pass plumbing. Fractional's render arm is terrain meshing plus the clip-plane uniform — already built and pixel-gated. Its expensive fronts are COLLISION and STATE STORAGE: server-side, greenfield (zero `VoxelShape`/`Shapes.*` references anywhere in the passthrough package), untouched by anything (e) learned. |
| "Do the risky one while context holds" | **Shared-file risk cuts the other way.** The seam clip's dest arm is one line at `SecondaryWorldRenderCore.java:1135` whose own comment says it ASSUMES the current Step-10.5 inner-clip arming semantics — and that file is HOT in `.claude/worktrees/is5-shadow` (iris shaders-ON). Fractional render work now builds on an arming convention another live session may be rewriting. Step 2 touches **zero** render files. |

**And the deciding point: fractional has no spec.** §0.9 is six lines under "do not build now, do
not lose"; `SEAM_CLIP_DESIGN.md`'s own header says it is the RENDERER only;
`REDSTONE_NEXT_PROMPT.md:85` says it "deserves its own design panel". Its honest first step is a
design panel, which is a different kind of session and should be scheduled as one. Step 2's risk is
execution risk with one unproven mechanism that a single launch settles.

**The fair case against, kept because it is real:** every step-2 mechanism gets built against a
seam model the user has said they want replaced, and fractional "changes what a 'seam cell' means
everywhere". Wire is the worst derived-state case in the game, and step 2's new surface is exactly
connection shape and the diagonal shape-update routes — all of which must be re-derived once a seam
cell is a partial block. **Building them twice is real waste.** If the user's priority is the
visible seam rather than circuit breadth, fractional-first is defensible; see §5.

---

## §0 BEFORE ANY CODE — TWO THINGS, IN THIS ORDER

### 0.1 THREE DECISIONS THE USER HAS NEVER SIGNED, AND STEP 2 EXERCISES ALL THREE

Tabled at `REDSTONE_NEXT_SESSION.md:436-442`, spec §0.1/§0.2/§6.8, memory `rs_c_signal_landed.md`.
A recommendation was given 2026-07-28 (keep 1 and 2, leave 3 deferred); **no answer was received.**

1. **Signal is NOT a "machine write"** — (c) carries power through reads + dispatch and never
   mirrors a block, so the player-only policy is untouched.
2. **DISJOINT seams DO carry signal** — *this is exactly the wire-flush-at-a-boundary-plane case
   step 2 is about.* Do not build step 2 without it.
3. **Junction/curve switching from far signal is NOT shipped** — deferred to the D2 family.

### 0.2 MEASURE THE OFFSET PREMISE — THE INSTRUMENT THAT SIZES THE *OTHER* ENGAGEMENT

**Nobody has ever run the suite under the offset levers.** `-PdisableSeamExactOnly` and
`-PdisableSeamPhaseGate` both exist, wired in both `fabric/build.gradle` blocks. Half a day of
measurement answers the user's live complaint either way, and produces the input the fractional
design panel does not currently have.

Run BOTH together (exact-only alone leaves the phase gate declining the boundary side). A fixture
already exists: `CrossingSmoke.java:1061` — "portal A's dest hangs a half-block off in Y".

Record what actually breaks: does the pair mirror BOTH ways; does column pairing go many-to-one
(`SeamMap.enumerateColumns`, `MIN_OVERLAP 0.5`, can go 3-against-2 under lateral offset); do server
and client agree on the dest cell (`resolveDestCell` diverges when the far ClientLevel is cold); do
(b) rails and (c) signal light up at all.

⚠ **Expect to fix the assertion, not the code:** the STRICT branch at `CrossingSmoke.java:1094`
demands `destPos == SeamMap.mirrorCell`, and the arithmetic `resolveDestCell` exists precisely to
override under offset.

**Outcome:** either the user's complaint is a lever flip plus residue (cheap), or it is genuinely
the partial block, and the panel gets a measured scope instead of a guess.

---

## §1 ★ THE CLAIM THAT WAS REFUTED — DO NOT REPEAT IT

`REDSTONE_NEXT_SESSION.md` states **"widening later is editing two methods in
`SeamMirrorPolicy`"**. That is **true for the WRITE-SOURCE axis** (`mirrors(SeamWriteSource)`, two
call sites, admitting a source is near one line) and **FALSE for the ALIGNMENT axis**:

- `SeamMirrorPolicy.mirrors(SeamAlignment)` has one production call site, at `SeamRegistry.bind`.
- A decline there sets `mirrorable = false`, which nulls `destDim`/`destPos` **and clears
  `seamContinuous`**.
- Every (b) rail, (c) signal and (d) cart path gates on
  `binding.isMirrorable() && binding.seamContinuous()`.

So an OFFSET seam is **not "query-only"** as the code comment claims — it is dead for (b), (c),
(d), frame links and the seam-clip renderer alike. Flipping that predicate does not "add a mirror
path"; it switches **five never-exercised subsystems on at once**, on geometry where the
destination is genuinely ambiguous.

And the canonical offset case (one plane mid-block, one on a boundary) then hits a **second,
independent gate** — `SeamMirror.isPhaseGated`, five call sites, living in `SeamMirror` not in the
policy class — which classifies the two sides differently, making mirroring **one-directional** and
break-clears-both **asymmetric**.

---

## §2 WHAT STEP 1 SHIPPED, AND WHY IT IS INERT FOR DUST

Step 1's scope (spec §0.4) was **powered rails + redstone lamp only, via per-consumer call-site
wraps**. Three mechanisms in `SeamSignalContinuity`, plus two added by the live round:

| # | mechanism | what it does |
|---|---|---|
| R-UNION | read bridge | unions the far image's neighbour signal at a bound seam cell; additive-only, hand-rolled 6-neighbour scan, per-read chunk-residency guards, never loads a chunk |
| R-WALK | `@WrapOperation` ×2 | the `isSameRailWithPower` probes inside `PoweredRailBlock.findPoweredRailSignal` redirect through `SeamShadowBridge.shadowFor` into the far level, re-entering vanilla walk code natively |
| D1 | dispatch | a settled seam-cell change queues `neighborChanged(counterpart, block, null)`, flushed at server tick end — cap 256, 64/tick, per-tick dedupe, retry-on-warm |
| POWER-WAKE | live fix | (a)'s mirror-authority mixin cancelled the power question at provenance-marked cells; the fix **FORWARDS the poke to the counterpart, never evaluates in place** |
| BREAK-UNMARK | live fix | a break at a seam cell clears **that cell's own** provenance mark; invariant — a pair carries at most one marked half |

**★ F11 — THE WHOLE OF THAT IS INERT FOR DUST-TO-DUST.** Step 1 bridged the `SignalGetter` family
at per-consumer call sites. **Wire decay never calls those methods at all**: the evaluator reads the
neighbour's `getBlockState` + `POWER` property RAW. So step 2 is not an extension of step 1's
bridge — it needs a new chokepoint at `getBlockState` level.

**There is NO step-2 spec.** Two paragraphs of route notes (spec §6.2, §6.3), one verified blocking
fact (F11), one hazard rule (F8), and a three-line pointer in `REDSTONE_D_PROMPT.md`. Step 1's spec
is 301 lines by comparison. **A step-2 session starts with a design pass, not an implementation.**

---

## §3 WHAT STEP 2 ACTUALLY REQUIRES

1. **Dust-to-dust decay** — a new chokepoint inside `RedstoneWireEvaluator.getIncomingWireSignal`
   (or an evaluator substitution). *The user-visible case; do this first.*
2. **Connection shape** — `getConnectionState` / `getConnectingSide`, plus
   `updateIndirectNeighbourShapes` diagonal routes and the shape-update channel. **A second
   dispatch channel step 1 never touched.**
3. **Probably the general `SignalGetter` default-method interface mixin** — covers
   doors/dispensers/pistons/TNT in one hook instead of more per-consumer wraps.

⚠ **Every wire line number in the docs is recon-era and unverified for 26.2.** The verification
fleet already found the recon MATERIALLY WRONG on step 1's central claim (F3:
`findPoweredRailSignal` contains zero `Level` reads). **javap the loom deobf jar first** —
`getIncomingWireSignal`, `getConnectingSide`, `getConnectionState`,
`updateIndirectNeighbourShapes`, `shouldSignal`, `getBlockSignal`, and confirm
`DefaultRedstoneWireEvaluator` vs `RedstoneWireEvaluator` naming and hierarchy.

---

## §4 HAZARDS — every line has a scar or a bytecode proof behind it

- **★ THE `shouldSignal` SINGLETON (F8) — the sharpest correctness hazard in the feature.**
  `RedStoneWireBlock.shouldSignal` is a plain mutable boolean **on the global block singleton**,
  toggled around `getBestNeighborSignal` with **no try/finally** (bytecode confirms no exception
  table) and an unconditional restore. **A bridged read that throws latches it false and MUTES ALL
  WIRE GLOBALLY, in every dimension, until a restart.** Hard rules: bridged reads must be
  side-effect-free and exception-free; **no far-level EVALUATION may run synchronously inside a
  read window.** Pure far-level READS inside the window are safe.
- **★ THE VOLUME SCAR.** Step 1's first build evaluated the mirror half in place instead of
  forwarding and looped ~500,000 same-drain iterations until vanilla's chain cap broke it — **and
  the suite PASSED, because every gate watched correctness and nothing watched volume.** Any step-2
  wake at a suppressed cell must FORWARD to the counterpart; every new leg needs a runaway ceiling.
- **Derived state.** Dust connection state is re-derived before, during and after every write — the
  worst derived-state case in the game. Same family that produced (a)'s three defects and (b)'s
  shape-sync findings. Still open and related: a mirrored cell can be re-derived or DELETED by the
  destination afterwards, with an item-duplication route via `shouldBeRemoved`. The proposed rule
  ("a mirrored cell's validity and shape are the SOURCE cell's") is unimplemented and needs the
  user's word — **wire may force that conversation.**
- **Worldgen blast radius.** A `SignalGetter` injection also rewires worldgen reads. The
  `WorldGenRegion` instanceof guard is not optional and belongs **in the smoke test**, not added
  later.
- **Interface DEFAULT methods have no in-repo prior art.** Interface mixins with injectors are live
  here for STATIC methods only (F10). One no-op logging injector + one launch settles the whole
  shape of step 2, and step 1's shipped wraps stay correct either way (union is idempotent).
- **Far-side binding liveness.** Far bindings exist only while the far portal entities **TICK**,
  not merely while chunks are loaded. Live symptom: one-way signal on far portals whose chunks are
  warm but not entity-ticking. Gates must forceload the far counterpart **and poll-assert the
  binding** — the first (c) gate run failed exactly there.
- **Sub-tick pulses are structurally missed.** D1 re-derives from SETTLED state at tick end, so a
  far one-tick observer pulse that rises and schedules off inside one tick may never light the near
  half, where vanilla adjacency would flash 4+ ticks. Edge-carrying dispatch is D2 territory
  (§6.10) — **do not promise it in step 2, and say so up front.**
- **Unbind leaks stale bindings.** `unbind` still enumerates CURRENT geometry, so a portal whose
  geometry changes before unbind leaks stale bindings until dispose. Pre-existing (a)-era gap,
  documented as "take it with (c) if wire makes it hotter". **Expect wire to make it hotter.**
- **Any new hook on the `LevelChunkSetBlockState` driver must use the IDENTITY bracket**
  (`beginMirrorWrite`/`endMirrorWrite`), never the global `SeamMirror.isApplying()` flag — that was
  the panel's recorded BLOCKER finding in step 1.

---

## §5 THE FRACTIONAL MODEL — DEFERRED TO ITS OWN DESIGN PANEL, WITH ITS HAZARDS BANKED

Do not start it as an implementation session. What the survey found, so the panel starts informed:

- **The fraction itself is undecided.** `SeamMap.phaseOf` is BINARY (COINCIDENT within 0.25 of cell
  centre, else DISJOINT, with an explicit comment that intermediate phases are "a phase this build
  does not model"), and exact-only means every cut in shipped geometry is exactly ½ or 0. Meanwhile
  the shipped clip's `PlaneKey` stores `2*blockCoord+1` — **halves only** — so the design doc's
  claim that arbitrary fractions were the day-one requirement **is not what the code computes**.
  Whether fractional must support arbitrary fractions or may keep exactly-½ has never been
  reconciled, and it is **the single biggest lever on the size of that engagement.**
- **A vertically half-cut cube fails `SupportType.RIGID`** (its UP face no longer covers the central
  12×12), so `Block.canSupportRigidBlock` fails and **`BaseRailBlock.canSurvive` pops a rail off a
  partial support block** — the exact (b) use case. Fractional would *break* "build blocks through
  the opening and lay rail on top", not unblock it.
- **State-keyed partiality kills `isRedstoneConductor` and `isSuffocating`**, both derived from the
  per-state `isCollisionShapeFullBlock` cache. **Position-keyed partiality is invisible** to that
  cache and to the 2-arg `BlockStateBase.getCollisionShape(BlockGetter, BlockPos)` bypass used by
  suffocation, pathfinding and support. Three storage options, each with a named disqualifier.
- **There is NO gametest anywhere asserting collision at a seam.** Fractional would ship with zero
  regression coverage on the exact property it alters.
- **Persistence after teardown is undesigned.** `SeamRegistry` is derived from live Portal entities
  each tick, so the cut plane vanishes with the portal — while §0.9's stated motivation is that a
  frame break becomes LOSSLESS with both halves surviving. `SeamFrameLink`'s dormant-link pattern is
  the only precedent.
- **What it WOULD retire, and cheaply:** the declined pop is mechanically one expression —
  `SeamClipRenderer.java:334`, `Direction keptDir = side >= 0 ? f : f.getOpposite()`. Under a fixed
  source-side cut that becomes `keptDir = f`, and the two gates that exist only to serve a
  camera-derived choice (`cameraSideWindowPossible`, the near-plane `|side| >= ADJUSTMENT` guard)
  both fall away. 756 lines of landed, pixel-gated renderer sit behind one `-PenableSeamClip`.
- **The sleeper:** a fixed per-cell cut is **view-independent**, so the twice-recorded rejection of
  compile-time quad clamping ("one dimension = one ViewArea, drawn in the same frame by two cameras
  on opposite sides") no longer applies — and that path would also retire the Sodium self-gate the
  dynamic renderer cannot escape.

---

## §6 STANDING DISCIPLINE

Instrument before theorising. Assert the outcome the user can see, **on the side of the wire their
eyes are on**. Hook the write, not the settled state. Gates lever-aware, coverage-asserting, and
inverting under their own lever in the same leg. **Assert the fixture's PRECONDITION, not just its
outcome** — RS-CART-F caught two wrong-reason fixtures that way. A fixture that fails for the WRONG
reason is worse than one that passes. javap every mixin target on
`minecraft-merged-deobf-26.2.jar` before first launch. Every fix DEFAULT-ON behind
`-Dseamlessportals.disableX`; `-P` rows in BOTH `fabric/build.gradle` blocks; `git commit -F` with
explicit file lists; push every commit. Build scaffolding (root `build.gradle`, `settings.gradle`,
`gradlew*`, `gradle/`) is UNTRACKED and must stay so.

**Probe discipline (five defects in the (e) session, one shape each):** does it fire on the stack
you are already looking at; can it throw during construction/removal; can routine volume starve the
rare line; is the quantity you assert on still true when you read it?

**Never run `gradlew --stop`** (machine-wide; kills other sessions' live games). **Never run two
Gradle jobs against this project at once** — the (e) session deadlocked its own matrix that way.
Java cleanup: own PIDs only, filtered by THIS worktree's path; `idea64.exe` is a JVM.

## §7 GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true -PrsOnly=true
```
Fast iteration (~3 min). Full matrix before any commit: the two FULL suites, the (b) inversions,
the (c) inversions (`-PdisableSeamSignal`, `-PdisableSeamSignalDispatch`, `-PdisableSeamPowerWake`,
`-PdisableSeamBreakUnmark`), the (d) levers, the (e) levers
(`-PdisableDestEntitySectionExact`, `-PdisableCrossDimPositionCodecSync`), plus whichever new lever
this work adds, both directions. **Wire behaviour needs LIVE eyes — spec §5 already says so.**
