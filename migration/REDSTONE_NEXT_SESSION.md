# REDSTONE / RAIL / MINECART PASSTHROUGH — NEXT-SESSION HANDOFF

**Branch `redstone/passthrough`**, worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`. Tree is clean and green.
The per-step commit table below carries the tips; this header no longer names one, because it was
stale by four commits the last time it was read.

## READ FIRST, in order
1. `migration/REDSTONE_RECON.md` **§0** — every user decision, pinned, with the reasoning.
2. `migration/REDSTONE_A_SPEC.md` — the (a) spec. **Its top banner overrides the body** where they differ.
3. This file.

## WHERE THINGS STAND (2026-07-26, latest session)

- Sub-feature **(a)** complete and user-verified.
- Sub-feature **(b) step 1** done — the cross-seam neighbour primitive exists.
- **(b) step 2 — rails CONNECTING across the plane — NOT STARTED.** Nothing consumes the primitive
  yet. This is the next feature work; see `REDSTONE_B_SPEC.md` and the re-check notes on it.
- **The same-dim live-update bug is DIAGNOSED but NOT FIXED** — see the section below. The cause is
  measured and reproduced headlessly; the fix is a client render-path change that has not been
  written. It is NOT a mirror defect and NOT a blocker for (b).

## ★ SUB-FEATURE (a) IS COMPLETE — 2026-07-26, tip `7070101`

All 8 spec steps plus frame mirroring and the targeting fix are landed, gated in both lever
directions, and pushed. **Next engagement is (b) rail connection across the plane**, which consumes
the `SeamMap` / `SeamRegistry` primitive built here via `lookupAcross` / `mapDir` / `seamGroup`.

What works, user-verified live:
- The aperture is ordinary building space at any height; blocks placed there mirror across the seam
  and read as one block spanning it.
- Breaking either half breaks both, dropping an item only on the side broken. No duplication.
- A conflict refuses the placement outright, before any world write.
- A frame break keeps the player's block and clears the mirror (provenance).
- Frame **breaks** mirror instantly; frame **repairs** stage until ignition, which then restores the
  far frame through the persisted dormant link with no portal alive, and relinks to the same partner.
- A block aimed into an aperture stays in the player's own dimension, while reaching THROUGH an empty
  aperture still works.

Not built: (b) rail connection, (c) redstone bridge, (d) minecart traversal. Rails are still just
blocks that mirror — they do not connect or carry carts.

**`SeamJournal` has never executed.** It guards a state with no known reachable path (see below). It
is insurance, not verified code.

## STATE: steps 0–4 of 8 landed, gated, pushed

| step | what | commit |
|---|---|---|
| 0 | levers (4 fix + 5 probe, rows in BOTH build.gradle blocks) | `8890bac` |
| 1 | `SeamMap` — the phase-agnostic seam arithmetic | `8890bac` |
| 2 | `SeamRegistry` + `SeamIndexHolder` duck index + signal seeding | `f12b3df` |
| 3 | 4 IP-core edits: aperture becomes buildable, integrity frame-only | `7983b0e` |
| 4 | ignition rules + `ApertureOccupancy` + 2 data tags | `a83f0c8` |

**Working today:** rails and blocks can be placed in a lit portal's opening by hand, the portal
survives, blocks render half-clipped at the plane, and a frame containing a track can be lit and re-lit.

**NOT built: mirroring.** A block placed on one side does not appear on the other. The user must
place a second block from the far side; the two half-clipped blocks merely *look* like one.

## REMAINING, in the user's chosen order

1. **Step 5 — placement veto.** `MixinBlockPlaceContext` + `MixinBlockItem` → `SeamMirror.mayPlace`.
   Enforces refuse-on-conflict, incl. block-entities, multi-cell blocks and fluids.
   **Hard part:** refusing requires reading the DESTINATION world, which may be unloaded. The spec
   offers no clean answer; if none emerges, it goes back to the user (force-load / refuse / optimistic).
2. **Step 6 — mirror driver.** Second `@Inject` on `LevelChunkSetBlockStateMixin` (site rationale
   already written at `:36-72`), deferred end-of-tick flush via `ServerTaskList`, `withApplying`
   recursion guard, cluster dedupe. **Writes provenance** (see below).
3. **Step 7 — MOSTLY UNNECESSARY, see below.** Keep only IP-core edit 10 (re-ignition guard,
   defence-in-depth). The `SeamJournal` looks like machinery for an unreachable state.
4. **Targeting fix** — evidence-specified, see below.
5. **Frame mirroring** — new user scope, needs its own design pass.

## THE TARGETING FIX — settled by live round #1, do NOT re-litigate

Probe evidence found **two modes needing different treatment**:
- *Real block in a seam cell:* `localDist=2.331 portalDist=2.419 margin=0.112 → THROUGH-PORTAL`.
  Local hit was CLOSER and still lost, purely to the hardcoded `+ 0.2` at
  `BlockManipulationClient.java:81`. **This is the defect** — the player's block lands in the wrong dimension.
- *Empty aperture cell:* `localDist=NONE(23333)` (placeholder sentinel, `:104-109`) → portal wins.
  **CORRECT, MUST BE PRESERVED** — it is what lets a player reach through an open portal.

⇒ Fix is narrow: **local wins only when the seam cell holds a real, non-placeholder block.** A blanket
"seam cells win" fixes the rail and breaks cross-portal interaction in the same commit.

## FRAME MIRRORING — new scope, design pass required

User rule: breaking obsidian on one side breaks the corresponding obsidian on the other; repairing and
lighting one side ALSO repairs and lights the other, and they re-link.

**The hard part is not the mirroring.** Seam bindings are DERIVED from live `Portal` entities. Once
both portals tear down, nothing knows source frame ↔ dest frame, so a repair has nothing to mirror
through. Needs a persisted **DORMANT LINK** surviving teardown — a concept the spec lacks — and extends
the mirror beyond the aperture to the frame ring, which every §0.7 binding rule assumed would not happen.

## THE STEP-7 JOURNAL LOOKS UNREACHABLE — do not build it on faith

The spec's `SeamJournal` exists to make a mirror write durable when the FAR side is cold. Two live
rounds plus the user's own observation suggest that state cannot be reached:

- **Writing INTO a cold destination is already handled.** Both `SeamMirror.mayPlace` and
  `applyToDestination` force-load the destination chunk. (Their earlier DISAGREEMENT about this was a
  real bug — the veto loaded and approved, the driver saw "not loaded" and dropped the write,
  manufacturing the source-only half refuse-on-conflict forbids.)
- **A change ORIGINATING on a cold side cannot happen.** A player must be within reach to break a
  block, which puts them at that portal, which means its chunk ticks and the portal is bound. And if a
  chunk is not ticking, nothing else changes there either — no pistons, no fluid flow, no gravity.
  The user tried to construct the case and correctly concluded it was impossible: *"i can only break
  blocks that are within arms reach."*

**Status is "no reachable path found", NOT "proven safe."** The counterexample to watch for is half a
rail surviving alone with no counterpart. If that is ever seen, the journal comes back.

Still worth taking from step 7: **IP-core edit 10**, the re-ignition guard
(`SeamRegistry.isSeamCell` in `NetherPortalGeneration.checkPortalGeneration`). It is small and guards
the bug family that already bit once — two portal pairs binding the same aperture cell with different
destinations.

## ★ THE DERIVED-STATE AUDIT (2026-07-26) — the root modelling error in (a), and what is still open

**Three defects surfaced in (a) AFTER it was called complete, and they are ONE FAMILY.** (a) mirrors
BLOCK STATE, and block state is not an inert value — **the game re-derives it**, before the write,
during the write, and after it. Every mirrored block kind inherits this; redstone dust and repeaters
have exactly this shape of derived connection state, so **(c) will hit it hardest**.

| # | defect | status |
|---|---|---|
| 1 | mirror wrote the **pre-resolution** state — resolution happens in a NESTED `setBlock` (`LevelChunk.java:326-327` dispatches `onPlace` inside its own body) and the OUTER inject re-mirrored its stale parameter | **FIXED** — mirror reads the live state |
| 2 | `isMirrorable` never tested the **translation** term of the affine transform | **FIXED** — `SeamMap.latticeAligned` |
| 3 | the mirrored copy **re-resolved itself** against the destination's neighbours on placement | **FIXED** — write with `Block.UPDATE_SKIP_ON_PLACE` (512) |

### STILL OPEN — found by the audit, NOT yet fixed

`UPDATE_SKIP_ON_PLACE` only suppresses the **placement-time** re-derive. The destination can still
rewrite or delete a mirrored block afterwards:

- **`BaseRailBlock.neighborChanged`** (REF, verified) → `updateState` → `updateDir` → re-resolution.
  Any neighbour update in the DESTINATION dimension re-derives the mirrored rail's shape against
  destination neighbours, so the halves can diverge again after placement.
- **`BaseRailBlock.shouldBeRemoved`** (REF, verified) → `canSupportRigidBlock(level, pos.below())`
  evaluated in the DESTINATION, then `dropResources` + `removeBlock`.
  ⚠ **THIS IS AN ITEM-DUPLICATION ROUTE.** The source rail still exists; the destination drops a rail
  item. One placement, two rails. Reachable whenever the destination cell lacks support the source
  cell has.
- **`canSurvive`** — same support test, same asymmetry.

**PROPOSED RULE (not yet implemented, needs the user's word):** *a mirrored cell's validity and shape
are the SOURCE cell's* — the destination must not independently re-derive or delete a block it did not
author. Provenance (`mirrorCreatedCells`) already identifies exactly those cells. That one rule closes
all three open paths together, rather than patching each.

Note this also means the (b) spec was written against an (a) that had defects 1–3, so any part of it
reasoning about mirrored-state behaviour may be reasoning about the broken version.

## ★ SOLVED (DIAGNOSED, NOT YET FIXED) — SAME-DIM MAN-MADE PORTALS DO NOT SHOW MIRRORED WRITES LIVE

**2026-07-26. Cause FOUND BY MEASUREMENT, reproduced headlessly, and it is none of the three
things that were guessed.** The fix is not yet written; the diagnosis below is exact and the
reproduction is in the suite.

### The measured answer

**The block reaches the client perfectly. The client just never redraws it.**

`SectionUpdateTracker.setDirty` (REF `SectionUpdateTracker.java:26-31`) is:
```java
SectionDirtyState section = this.storage.getValue(sectionX, sectionY, sectionZ);
if (section != null) { section.setDirty(playerChanged); }
```
`storage` is a `RotatingSectionStorage` sized by RENDER DISTANCE and re-centred on the CAMERA. A
section outside that window returns `null` and **the remesh request is discarded** — no log, no
exception, no return value. The `ClientLevel` holds the new block; its mesh is never rebuilt.

**Why this is dimension-asymmetric, which is the entire shape of the bug.** Each `ClientLevel` has
its own `LevelExtractor` and therefore its own tracker:
- **CROSS-dim destination** → the destination dimension's tracker, centred on that dimension's
  portal-view camera → the mark lands. *(Measured: nether tracker `71c7ff68`, 2704 sections.)*
- **SAME-dim destination** → shares the ONE tracker the player's own view uses, centred on the
  PLAYER → a destination further than render distance away is outside the window and is dropped.
  *(Measured: overworld tracker `267828b4`, 4056 sections = 13×13×24, i.e. render distance 6.)*

This accounts for every observation, including the ones that made the earlier models look plausible:
obsidian fine (cross-dim); man-made cross-dim fine (cross-dim); man-made same-dim broken; *"invisible
until teleport"* — arriving re-centres the tracker and the section is meshed fresh; *"dest→source
instant"* — the source is next to the player, inside the window; *"sometimes only works 1 way"* —
whichever end happens to be inside the player's window works.

It also explains why the client-sync push (`3a85cf7`, `8620c9c`) could not have helped and why
narrowing it was not the regression it appeared to be: **the data always arrived.** The push was
operating on a stage that was never failing.

### The evidence, verbatim

`-PseamDeliveryTest=true -PseamDeliveryProbe=true`, arm 3 (wand-shaped same-dim cluster, destination
600 blocks away):
```
trace #4 BlockPos{x=3199, y=90, z=2600} in minecraft:overworld
  — ★ DATA DELIVERED BUT NO REMESH — the client holds the block and will not redraw it
  1 WRITE     : setBlock=true wanted=air readBack=air destChunkFullStatus=ENTITY_TICKING sameLevel=true
  2 NOTIFY    : sendBlockUpdated REACHED (flags=3)
  3a HOLDER   : ServerChunkCache.blockChanged REACHED
  3b ACCEPT   : ChunkHolder.blockChanged accepted (getTickingChunk non-null)
  5 CLIENT    : ClientboundBlockUpdatePacket state=air applied to ClientLevel minecraft:overworld
  6 REMESH    : ★ DROPPED — section is OUTSIDE the tracker's rotating window ... windowSections=4056
```
Arms 1 (same-dim one-way, dest 100 blocks) and 2 (cross-dim) both read `6 REMESH: ACCEPTED`.

### What the fix has to be, and what it must NOT be

**Not in `SeamMirror`.** This is not a mirror defect. ANY block change in a same-dimension portal's
remote region has it — a fluid flowing, a piston, a second player building. The mirror is only how
it was noticed. A fix inside `SeamMirror` would paper over one caller of a general defect.

**The asymmetry to close:** this port's `ImmPtlViewArea` is UNBOUNDED (the C3 rebuild; that is what
lets a >71-chunk same-dim dest render at all — leg 7), while the vanilla `SectionUpdateTracker`
window it is paired with is still render-distance bounded. An unbounded view area with a bounded
dirty tracker is the defect stated in one line.

**Shape of the fix** — the block-era precedent is `RemoteBlockUpdater.java:86-124`: do not go through
the tracker, take the `RenderSection` straight out of the (unbounded) `ViewArea` and schedule its
compile. Note `extractor.setSectionDirtyWithNeighbors` is NOT enough — it routes through the same
window and is dropped identically. That block-era code is dead under entity portals and needs its
entity-portal equivalent; `LevelRendererAccessorMixin.seamlessportals$getViewArea` and
`ViewAreaInvokerMixin` already exist.

Drive it from the CLIENT's block-update application (where stage 5 lands), not from the server.

### The instruments, and one warning about them

- `SeamDeliveryProbe` + 5 mixins, `-PseamDeliveryProbe=true` (default OFF). Six stages, retired on a
  timer and printed in full so a stage that never ran says `NOT-REACHED`.
- `RS-DELIVERY-TEST` leg, `-PseamDeliveryTest=true` (default OFF). Three arms in one run; arm 3
  builds the wand's real four-entity cluster (`createFlippedPortal`/`createReversePortal`, same
  calls in the same order as `PortalWandInteraction.java:271-283`) with the player moved to stand in
  front of it, destination deliberately beyond render distance.

⚠ **The probe's own first build was wrong and it is worth knowing how**, because the same mistake is
easy to repeat: it opened each trace AFTER `setBlock` returned, but stages 2/3a/3b all run INSIDE
`setBlock`, so it reported them `NOT-REACHED` while the client plainly had the block. An impossible
reading is the instrument confessing — do not rationalise one. Two more false-positive routes were
closed with it: stage 4 was chunk-granular for a cell-granular question, and the verdict assumed a
strictly linear chain that `forceClientSync` deliberately violates by entering at stage 3a.

⚠ **A near fixture hides this bug.** Arm 3 originally used a 60-block destination and passed. Any
same-dim reproduction must put the destination beyond render distance.

### Superseded — the three wrong models, kept as a record

**Three attempts, three different wrong models, none of which measured anything.**

Observed states, in order, all user-reported from live play:

| build | behaviour |
|---|---|
| no client-sync push | source→dest invisible until teleport; dest→source instant; blocks always PERSIST |
| push on all writes (`3a85cf7`) | "worse — sometimes only works 1 way", same-dim only |
| push cross-dim only (`8620c9c`) | **fails BOTH ways** on same-dim |

Constant across all three: **obsidian portals fine, man-made CROSS-dim fine, man-made SAME-dim broken.**
The server state is always correct — blocks persist once seen, and the log shows mirror writes firing
with zero failures and zero warnings (28–154 ops per session depending on how much was placed).

**So this is a DELIVERY/RENDER problem, not a mirror-logic problem**, and the three attempts show the
cause is NOT simply "the block update doesn't reach the client".

Hypotheses as they stood before the measurement, with their verdicts:

1. **The portal VIEW's mesh is not invalidated.** ✅ **RIGHT IN KIND** — and the closest of the
   three. Wrong in one detail that matters: there is no "separate cached state" for a same-dim view.
   There is ONE `ClientLevel`, ONE `LevelExtractor` and ONE mesh, and the invalidation REQUEST is
   what gets discarded, in `SectionUpdateTracker.setDirty`, for being out of window. The named
   precedent (`RemoteBlockUpdater` + `rebuildSectionAsync`) is indeed the right shape of fix.
2. **The `applying` guard is a single STATIC boolean.** ❌ Refuted. Stages 1–5 all fire and the
   server state was never wrong; the guard is not involved.
3. **`UPDATE_SKIP_ON_PLACE` interacting with same-level broadcast.** ❌ Refuted. Stage 2 fires with
   `flags=515` and the client applies the correct state.

**The lesson stands and is now paid for:** every one of the three shipped fixes reasoned from symptom
to mechanism. One probe run, on a fixture that put the destination beyond render distance, settled it.

## HAZARDS EARNED THE HARD WAY — do not rediscover

- **`ApertureOccupancy.areaPredicate()` is load-bearing in THREE systems at once**: flood-fill
  boundary, frame matchability, ignition validity. Two bad entries broke a different one each:
  * `obsidian` in the support tag → destroyed the flood-fill boundary; valid frames stopped lighting.
  * `PortalPlaceholderBlock` in the predicate → destroyed liveness detection; a LIVE portal's frame
    looked matchable and a second portal pair bound the same cell with a different destination.
  Never add a frame material to `aperture_support`. Never admit the placeholder.
- **STAGING FLAW in the spec:** IP-core edit 10 (re-ignition guard) is scheduled at step 7 but guards a
  hazard created at step 4. Root fix is already in; keep edit 10 as defence in depth.
- **AN INSTRUMENT'S ORDERING IS PART OF ITS CORRECTNESS.** The delivery probe's first build opened
  each trace AFTER `Level.setBlock` returned — but three of the six stages it measures run INSIDE
  `setBlock` (`sendBlockUpdated` is called from that method's own body, REF `Level.java:244-248`).
  It reported all three `NOT-REACHED` while the client demonstrably had the block. **An impossible
  reading is the instrument confessing; never rationalise one.** Same family as hazard 5 below (the
  aim probe measuring the path the fix replaced) and it will recur wherever a probe brackets a call
  that does its interesting work internally.
- **A FIXTURE THAT IS TOO CONVENIENT HIDES THE BUG.** The same-dim delivery reproduction passed with
  a 60-block destination and failed with a 600-block one, because the defect is a render-distance
  window. "The test passes" was true and meaningless. When a fixture is built to reproduce a
  reported failure and does not, suspect the fixture before concluding the report was wrong.
- **Instruments must assert their own COVERAGE, not just their result. FIVE false readings this
  engagement, every one of which looked like evidence:**
  1. the teardown probe that only ever logged `intact=true`, so the failure path was never exercised;
  2. a client-side block read returning `void_air` for chunks outside render distance;
  3. the seam gate passing while skipping its involution, because no bi-way pair was in range;
  4. a gate whose `AssertionError` was swallowed by an enclosing `catch (Throwable)` — `ALL LEGS PASS`
     printed while the gate had failed;
  5. the aim probe measuring the code path the targeting fix REPLACED — it logged the decision before
     applying the override, and cried "SEAM CELL LOST" 17 times about hits the fix was keeping local.
  Gates caught 1–4. The USER caught 5, by testing by hand. Reading a stale probe as evidence would
  have sent me rewriting working code.
- **A gate whose setup is invalid produces a CONFIDENT WRONG VERDICT.** Twice the frame-break gate
  accused innocent code — "THE PLAYER'S BLOCK WAS DELETED — provenance is inverted" (actually a rail
  placed with no support, popped by vanilla rules) and "THE MIRROR SURVIVED — duplication" (actually
  asserting before cross-dimension teardown had propagated). Both times the *probe* output
  disambiguated it: `cleared 0 ... provenance set size=0` with every cell already air describes a rule
  that NEVER RAN, not one that ran and answered wrongly. Gates must log their working, not a verdict.
- **Self-consistent tests prove nothing.** The mirror gate read `binding.destPos()` and then verified
  THAT SAME CELL, so it could not see that the mirror was writing one block off from what the far
  portal claimed. That bug survived the involution gate, the mirror gate AND a live user test (a rail
  one block off next to a portal looks right); it took a FOURTH consumer — the frame-break rule — to
  expose it. Cross-side invariants need a test that spans both sides.
- **Wait for preconditions, never a tick count.** Cross-dimension teardown runs through
  `markShouldBreak` into a deferred RETRYING task, so it has no fixed latency.
- **An evidence gametest leg must never perturb a functional leg** — twice: a staged block left in
  portal B's window failed the ender-pearl leg, and a leftover obsidian FRAME inside another leg's
  128-block match radius made leg 6a link to it. Cleanup belongs in a `finally`.
- **A portal has the SAME UUID on client and server.** Keying per-portal state by UUID alone lets one
  side suppress the other's work. `AperturePassthroughInit` keeps two fingerprint maps for this reason.
- **Four portal entities per frame pair**, two coincident per side. Any per-portal driver fires four
  times unless cluster-deduped.
- **Build scaffolding (`build.gradle`, `settings.gradle`, `gradlew*`, `gradle/`) is UNTRACKED in git.**
  A fresh worktree cannot build until they are copied in from the main checkout. Not committed —
  that is the user's call.

## GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PdisableAperturePassthrough=true
```
Both must reach `ALL LEGS PASS`.

The same-dim delivery reproduction is a THIRD command, default-off so it costs the two gates nothing:
```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamDeliveryTest=true -PseamDeliveryProbe=true
```
Read the three `RS-DELIVERY-TEST` arm reports and the `6 REMESH` line of each retired trace. Today
arms 1 and 2 report `ACCEPTED` and arm 3 reports `★ DROPPED`. **When the fix lands, arm 3 must report
`ACCEPTED` too, and must go back to `★ DROPPED` under the fix's disable lever** — the RS-TEARDOWN-TEST
inversion discipline, which is what makes it a proof rather than a hope. The RS-TEARDOWN-TEST verdict **inverts** on the master lever
(enabled → `NO TEARDOWN`; disabled → `TEARDOWN CONFIRMED`) and reports a REGRESSION either way round —
that is the end-to-end proof (a) works and that the lever cleanly restores stock IP.

Live client with probes:
```
.\gradlew.bat :fabric:runClient --no-daemon -PseamAimProbe=true -PapertureCensusProbe=true -PseamReconcileProbe=true
```
