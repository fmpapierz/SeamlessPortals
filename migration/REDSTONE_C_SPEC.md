# SPEC — SUB-FEATURE (c) STEP 1: REDSTONE SIGNAL ACROSS THE SEAM (powered-rail chain + seam lamp)

> ## ★★ PANEL FOLD (2026-07-27, 4 adversarial lenses + first gate run) — OVERRIDES THE BODY WHERE THEY DIFFER ★★
>
> 1. **BLOCKER (2 lenses independently): the D1 `isApplying()` skip swallowed genuine cascade
>    flips at OTHER seam cells** — the mirror's far write drains the far level's neighbor cascade
>    INLINE inside the applying bracket (`CollectingNeighborUpdater.addAndRun` runs immediately
>    when that level's updater is idle, always true cross-dim), and any far seam cell flipping in
>    that window was invisible to shape sync AND D1: permanent pair divergence. **FIXED: skip by
>    IDENTITY** — `SeamMirror` brackets each of its three writes with
>    `SeamSignalContinuity.beginMirrorWrite/endMirrorWrite`, and D1 skips only that exact cell.
>    (The shape-sync half of the same window is a PRE-EXISTING (a)/(b) latent gap; with identity-D1
>    the affected pair converges via the counterpart's own re-evaluation next tick. Dedicated
>    two-portal gate coverage deferred — §6.7.)
> 2. **MAJOR: the cold-chunk guard was one hop shallow** — `getSignal` on a CONDUCTOR fans out to
>    its six neighbors unguarded (`getDirectSignalTo`), and the far walk continuation ran vanilla
>    reads unguarded to depth 8; either could blocking-load cold chunks on the server thread (the
>    B-4b scar). **FIXED**: the union hand-rolls the conductor branch with per-read residency
>    guards (`guardedFarSignal`), and the walk pre-gates its whole ≤8×3 far envelope's chunk
>    columns before redirecting, declining + retrying when any is cold.
> 3. **MAJOR: retry-on-warm woke the wrong cell** — a walk's cold decline recorded the seam cell,
>    which is already POWERED and so never flips/notifies on re-evaluation. **FIXED**: the M1 wrap
>    records the WALK ORIGIN (the outermost wrap's current cell IS the evaluating rail) and
>    declines retry to it.
> 4. **MAJOR: M2 (BaseRailBlock.updateDir junction switching) DROPPED from step 1** — it is
>    near-unreachable from far-side changes (`RailBlock.updateState` gates on the notifier being a
>    signal source with `countPotentialConnections()==3` evaluated far-side), STICKY when it fires
>    (no wake-up on far power dropping — vanilla reverts, this would not), and a policy-adjacent
>    geometry widening that deserves explicit user sign-off. Moved to §6.8.
> 5. **First gate run (red, two real findings): the far level resolves across through ITS OWN
>    registry, which binds only while the FAR-side portal entities TICK** — the OW forceload alone
>    left the nether side unbound and the far rails could not cross (walkCrossed frozen). The leg
>    now forceloads the nether counterpart and poll-asserts the far-side binding as setup coverage.
>    ⚠ LIVE-PLAY NOTE for the user round: far-side crossing in live play depends on IP's
>    destination chunk loading making far portal entities tick. And the leg's `pollOrFail` now
>    THROWS instead of recording — the first build let a later coverage check overwrite the real
>    verdict.
> 6. Corrections absorbed into the body: shape sync §0.3 is PROVENANCE-SCOPED (independently-built
>    pairs converge via D1+union only — and diverge by design under `-PdisableSeamSignalDispatch`,
>    §6.9); lamp OFF-convergence is ~8-10 ticks (two chained 4-tick schedules bridged by a
>    tick-end flush), not "a tick or two"; F2's owner rule is PER-SITE (the lamp `tick` owner is
>    ServerLevel); F12's `:32` site is `getStateForPlacement`, not onPlace; queue eviction now
>    un-dedupes the evicted target (a drop remains a counted, gate-asserted-zero loss).
> 7. **Checked and HELD** (attacked, survived): the walk rotation/forward′ math for all four
>    quarter-turns × both directions; the COINCIDENT backward-step redirect (the far fallback cell
>    is REQUIRED, not a double-count); the §2.4 revert-dance trace end-to-end incl. bracket timing;
>    additive-only for golden rails; @Local-in-@WrapOperation (repo precedent); same-dim far reads
>    mid-drain; flush drain discipline; zero-portal hot-path cost; null-Orientation fidelity; the
>    8-depth cap crossing dimensions unchanged.

**Date 2026-07-27. Status: post-panel, implementation landed with the fold applied.** Sources of truth: 26.2 ref
`C:\Users\warwa\ModDev\mc262-ref`, bytecode `minecraft-merged-deobf-26.2.jar` (javap-verified this
session by a 5-agent verification fleet; every citation below was re-checked against source or
bytecode — the recon's §4(c) is superseded where they differ, see §1). The shipped (b) architecture
(`SeamShadow`/`SeamShadowBridge`/`SeamRegistry`/`SeamRailContinuity`, handoff ★ (b) STEP 2) is the
substrate; nothing in it changes.

---

## §0 DECISIONS THIS SPEC TAKES (provisional — flag to the user, per the (b) precedent)

1. **Signal is NOT a "machine write" and NEVER mirrors blocks.** (c) carries POWER by (i) bridging
   READS at resolution chokepoints and (ii) forwarding UPDATE DISPATCH across the seam — each
   level's own vanilla logic then re-derives its own blocks. The only block-state changes (c) causes
   are vanilla evaluators writing their own cells; those are un-bracketed refinements the EXISTING
   machinery already classifies (shape sync propagates them at the player half; the authority rule
   reverts them at the mirror half). (c) adds no new write path and no new mirror policy — the
   `SeamWriteSource`/`SeamAlignment`/`SeamMirrorPolicy` seams are untouched.
2. **DISJOINT (boundary-phase) seams DO carry signal** — the (b) traversal precedent: a wire/rail
   flush at the plane powers the far side's flush counterpart. No mirroring there (phase gate
   stands); signal crosses via read + dispatch only.
3. **Byte-identity of a COINCIDENT pair is preserved by the EXISTING shape sync**, which already
   mirrors `POWERED`/`LIT` refinements (user-observed: "mirrored half shows powered"). (c) relies on
   it and must not fight the authority rule: the transient revert-then-repropagate dance at the
   mirror half (§2.4) is accepted, converging within a tick or two.
4. **Scope of step 1:** the powered-rail chain (user's reproduced target) + the seam lamp
   (`RedstoneLampBlock`, the cheapest non-rail consumer that demonstrates "circuitry"), via
   per-consumer call-site wraps. The general `SignalGetter` interface-mixin route, wire-to-wire
   decay, diodes, conductor strong-power and the delivery-forwarding hook (D2) are DESIGNED but
   DEFERRED (§6).

## §1 VERIFIED FACTS THE DESIGN STANDS ON (vs recon §4(c))

| # | fact | authority |
|---|---|---|
| F1 | All 8 signal reads (`getSignal`, `getDirectSignal`, `getDirectSignalTo`, `getControlInputSignal`, `hasSignal`, `hasNeighborSignal`, `getBestNeighborSignal`, `getBestOwnOrNeighbourSignal`) exist ONLY as `default` methods on `SignalGetter`; `Level`/`ServerLevel`/`ClientLevel` bytecode holds NONE of them. A Level-targeted @Inject cannot exist. | javap all four classes |
| F2 | Rail call sites are `invokevirtual` with owner `net/minecraft/world/level/Level` — wrap target strings must name owner `Level`, never `SignalGetter`. | javap PoweredRailBlock (#117), BaseRailBlock (#130) |
| F3 | **`findPoweredRailSignal` contains ZERO Level reads in 26.2** — its only calls are BlockPos math and two `invokevirtual isSameRailWithPower` (offsets 252, 288). The walk's `getBlockState` (#91, offset 2) and `hasNeighborSignal` (#117, offset 118) live in `isSameRailWithPower`. The recon-era assumption that the walk reads live in `findPoweredRailSignal` is WRONG for 26.2. | javap PoweredRailBlock |
| F4 | The walk (`PoweredRailBlock.java:30-125`): `findPoweredRailSignal(Level,BlockPos,BlockState,boolean forward,int depth)` caps at depth 8 (:31), steps one cell along the shape axis (forward: NORTH_SOUTH→z++, EAST_WEST→x−−), then `isSameRailWithPower(level, stepped, forward, depth, dirShape)` and, if `checkBelow`, the same at y−1 (:100-102). `isSameRailWithPower` requires same block (:107 `state.is(this)`), axis-compatible shape (:112-113), `POWERED=true` (:114), then `hasNeighborSignal || recurse depth+1` (:117). | ref + javap |
| F5 | Rail re-evaluation is per-rail and independent: `BaseRailBlock.neighborChanged:80-90` (server-gated) → `PoweredRailBlock.updateState:128-140`: `shouldPower = hasNeighborSignal(pos) || walk(true) || walk(false)`; a flip writes flags **3** and fires `updateNeighborsAt(pos)` (via flag 1), `pos.below()`, and `pos.above()` if slope. Rails that do not flip notify nobody. | ref :128-140, Level.java:250-251 |
| F6 | `SeamMirror.applyToDestination` writes the far half with flags **515** (UPDATE_ALL \| UPDATE_SKIP_ON_PLACE) — bit 1 SET, so a mirrored seam-cell change already fans out far-dimension neighbor updates. COINCIDENT dispatch is free WHEN the mirror/shape-sync path actually writes. | SeamMirror.java:491-493 |
| F7 | The default (non-experimental) redstone pipeline is **Orientation-null end to end** (`ExperimentalRedstoneUtils.initialOrientation` returns null without the feature flag; `CollectingNeighborUpdater.runNext:141` passes null; 26.2 `neighborChanged` carries **no fromPos** of any kind). A synthetic cross-seam `neighborChanged(pos, block, null)` is exact vanilla fidelity with nothing to coordinate-translate. | ref ExperimentalRedstoneUtils.java:9-23, CollectingNeighborUpdater.java:137-158, BlockBehaviour.java:162-163, javap ServerLevel |
| F8 | `RedStoneWireBlock.shouldSignal` is a plain instance boolean on the global wire singleton, toggled `false→true` around `getBestNeighborSignal` with **no try/finally** (bytecode: no exception table) and an **unconditional** restore. Hard rules: any bridged READ must be side-effect-free and exception-free (an escape latches `shouldSignal=false`, muting all wire globally); no far-level EVALUATION may run synchronously inside a read. Pure far-level READS inside the window are safe (they inherit the correct `shouldSignal` semantics from the shared singleton). | javap RedStoneWireBlock |
| F9 | `Level.neighborUpdater` is typed as the CONCRETE `CollectingNeighborUpdater` (Level.java:110); the interface default `updateNeighborsAtExceptFromFacing` is dead on live servers. The universal delivery point is static `NeighborUpdater.executeUpdate:59-61`. Client `Level` neighbor dispatch is a no-op (empty stubs; ClientLevel does not override). | ref + javap; grep |
| F10 | Interface mixins with injectors are LIVE in this repo for **static** interface methods (`MixinBlockGetter`→`traverseBlocks`, `MixinContainer`→`stillValidBlockEntity` with @WrapOperation+@Local). Runtime is sponge-mixin 0.8.7/MixinExtras 0.5.4; `INJECTORS_IN_INTERFACE_MIXINS` auto-enabled at JAVA_25. Injection into interface **default** methods has NO in-repo prior art — feasible per bytecode gates, but needs a smoke test before anything is built on it. | loader jar + repo grep |
| F11 | Wire-to-wire decay (`RedstoneWireEvaluator.getIncomingWireSignal:29-47`) reads neighbor `getBlockState` + `POWER` property RAW — it never calls `getSignal`. Bridging the SignalGetter family does NOT carry wire decay across a seam. | ref |
| F12 | `RedstoneLampBlock` reads `level.hasNeighborSignal` at :32 (onPlace), :39 (neighborChanged), :51 (tick) — per-method wrap counts to be re-verified by javap at implementation. | ref + fleet |
| F13 | `DetectorRailBlock.updatePowerToConnected:115-122` dispatches `neighborChanged` along RAIL-GRAPH connections (`RailState.getConnections()`) — a third notification route. Detector-adjacent seams are NOT step-1 scope (§6). | ref |

## §2 ARCHITECTURE — three mechanisms, one new class

New class `com.warwa.seamlessportals.passthrough.SeamSignalContinuity` (template:
`SeamRailContinuity` — budgets, counters, tick-end flush, retry-on-warm). All mutable state
server-thread confined. Every public entry is exception-guarded (F8: catch Throwable → warn-once →
vanilla answer).

### 2.1 R-UNION — the seam-cell neighbor-signal union (READ)

`SeamSignalContinuity.hasNeighborSignalAcross(Level level, BlockPos pos) → boolean`, called by the
consumer wraps as `local || across`. **Purely additive** — it can only add power vanilla would
miss, never remove any; vanilla non-regression is a theorem for all-local circuits (the (b) R1′
argument).

Gates, in order: master+signal levers, `entityPortals`, `level instanceof ServerLevel`, server
`isSameThread`, `SeamRegistry.sectionHasSeam`, `lookup(pos) != null`. Then per binding
(mirrorable, `seamContinuous` — the (b) traversability gate, conservative for step 1), cluster-
deduped by target like the mirror driver:

- **COINCIDENT** (far image `destPos` = the pair's other half): contribution = a hand-rolled,
  per-neighbor-chunk-guarded `hasNeighborSignal(farLevel, destPos)`: for each of the 6 directions,
  `farLevel.hasChunkAt(n) && farLevel.getSignal(n, d) > 0`. Hand-rolled because vanilla
  `hasNeighborSignal` would blocking-load cold border chunks (the (b) B-4b hazard); a cold neighbor
  contributes 0 and enqueues a warm-up retry (§2.3).
- **DISJOINT** (far flush counterpart `F = continuationToward(crossDir)`): contribution =
  `farLevel.hasChunkAt(F) && farLevel.getSignal(F, mapDir(binding, crossDir)) > 0` — exactly the
  one directional probe vanilla would make if F were the neighbor across the plane.

No far-level writes, no dispatch, no exceptions escape (F8). Counters: `unionReads`, `unionHits`,
`declinedCold`.

### 2.2 R-WALK — the powered-rail walk crosses the seam (READ)

Hook: `@WrapOperation` on the TWO `isSameRailWithPower` invocations inside
`findPoweredRailSignal` (F3; `require = 2, allow = 2`), with MixinExtras
`@Local(argsOnly = true) BlockPos currentPos` (the walk's current rail — the only BlockPos
parameter; @Local-in-@WrapOperation has repo precedent in `MixinContainer`).

Handler: `boolean local = op.call(original args)`; if true → true (LOCAL-FIRST, additive). Else if
`currentPos` is a bound seam cell (fast section gate), obtain
`SeamShadowBridge.shadowFor(level, owner, currentPos, steppedPos, 1)` — the unchanged (b) entry
point; `yWindow=1` covers the walk's y±1 stepped probes; the shadow's own horizontal-axis-step and
`continuationToward` logic decides whether this step crosses (both topologies, both directions on
COINCIDENT). If a shadow exists and `farResident(steppedPos)`:

```
farPos   = shadow.toFar(steppedPos)
dirShape' = axis-rotate(dirShape, shadow.srcToDst())      // EAST_WEST↔NORTH_SOUTH on quarter turns
stepDir' = shadow.srcToDst().rotate(horizontal step dir)
forward' = (dirShape' == NORTH_SOUTH) ? stepDir' == SOUTH : stepDir' == WEST   // vanilla's own
                                                          // convention, F4: NS fwd→z++, EW fwd→x−−
return op.call(receiver, shadow.farLevel(), farPos, forward', searchDepth, dirShape')
```

The far continuation re-enters vanilla `isSameRailWithPower` natively in the far level — its
internal `getBlockState`/`hasNeighborSignal` need no translation, its recursion re-enters
`findPoweredRailSignal` whose wraps fire again at any further seam (multi-portal chains work),
and vanilla's own `searchDepth` carries the global 8-cap across dimensions for free (F4). Cold far
→ decline + retry enqueue + false. Counters: `walkCrossReads`, `walkCrossHits`.

**Why not wrap `isSameRailWithPower`'s internals:** those reads lack the walk context (which seam
cell the step left from); the call sites in `findPoweredRailSignal` are the one place both the
current cell and the stepped target are in scope.

### 2.3 D1 — cross-seam dispatch on seam-cell change (DISPATCH)

**The gap it closes:** F5 — a far-side rail re-evaluates only when *notified*, and vanilla
notifications stop at the plane. F6 covers COINCIDENT when the mirror writes; D1 covers DISJOINT
(phase-gated, no mirror write), shape-sync-declined pairs (independently built halves), and the
authority-revert echo (§2.4).

Hook: the existing driver — `LevelChunkSetBlockStateMixin.seamlessportals$driveSeamMirror`, one
added call `SeamSignalContinuity.onSeamCellChanged(serverLevel, pos, settled)` beside
`SeamMirror.onSeamCellChanged`. Inside: skip when levers off, when `SeamMirror.isApplying()` (the
mirror's own writes — the originating side already queued what is needed; and the revert path is
bracketed too), or when no binding qualifies. For each qualifying binding (mirrorable +
seamContinuous, cluster-deduped): queue `{destDim, counterpartPos, sourceBlock}` where
counterpartPos = `destPos` (COINCIDENT) or `continuationToward(crossDir)` (DISJOINT).

**Queue discipline (F8's hard rule — dispatch NEVER runs inline):** bounded ArrayDeque (cap 256,
drop-oldest + counter), per-tick dedupe set keyed `{dim,pos}`, flushed from
`AperturePassthroughInit`'s existing `onServerTickEnd` alongside `SeamRailContinuity`. Flush
delivers `farLevel.neighborChanged(counterpartPos, sourceBlock, null)` (the javap-confirmed 3-arg
ServerLevel overload; null Orientation is exact vanilla default-path fidelity, F7) — the far
level's own `CollectingNeighborUpdater` then owns re-entrancy and chain limits. Cold far chunk →
requeue with `waitFor` (the (b) panel's fix — wait on the chunk that is actually cold). Budget
`MAX_DISPATCH_PER_TICK = 64` (trips counted and asserted zero in gates; a cross-seam clock
legitimately re-queues every tick — that is vanilla-equivalent behavior, bounded by the budget).

**Termination:** a flush delivery can cause far-side state changes that re-enter D1 — that is the
converging vanilla cascade (rails/lamps only write on actual flips, F5). Ping-pong is broken by:
flips converge to the fixed point of the (static) source configuration; `isApplying` excludes
mirror echoes; the per-tick dedupe bounds any cycle to one delivery per cell per tick; the budget
backstops all of it.

### 2.4 The authority-rule interaction — accepted transient, not a fight

A far-originated signal (lever beside the far half) makes the far half's own evaluator write it
un-bracketed → at a MIRROR half the existing authority rule REVERTS it (correct: machine-derived
far state never overwrites the player's block) → D1 (queued by the pre-revert write, which is NOT
`applying`) delivers to the player half → the player half re-evaluates with R-UNION/R-WALK, sees
the far source, flips itself → shape sync propagates player→mirror. Net: both halves correct, one
server-side flicker at the far half within the same tick(s). The player half is always the
computing half; the mirror half is always the copy — the authority rule is obeyed, not amended.

## §3 MIXINS (all `require`-counted; javap EVERY target before first launch — house rule)

| # | mixin | target | sites (bytecode-expected) |
|---|---|---|---|
| M1 | `MixinPoweredRailBlockSeamSignal` | `PoweredRailBlock` | `updateState`: 1× `Level.hasNeighborSignal` → R-UNION; `isSameRailWithPower`: 1× `Level.hasNeighborSignal` → R-UNION; `findPoweredRailSignal`: 2× `isSameRailWithPower` (self-call) → R-WALK with `@Local(argsOnly)` BlockPos |
| ~~M2~~ | ~~`MixinBaseRailBlockSeamSignal`~~ | — | **DROPPED by the panel fold** (top banner #4, §6.8) — junction switching from far signal is deferred, not shipped |
| M3 | `MixinRedstoneLampBlockSeamSignal` | `RedstoneLampBlock` | 3 wraps, javap'd: `getStateForPlacement` 1× owner `Level`; `neighborChanged` 1× owner `Level`; `tick` 1× owner **`ServerLevel`** (the tick parameter is typed ServerLevel — owner drift caught by the bytecode rule before first launch) → R-UNION |
| — | `LevelChunkSetBlockStateMixin` (existing) | — | +1 line: `SeamSignalContinuity.onSeamCellChanged` (D1) |

All wrap target strings owner `Lnet/minecraft/world/level/Level;` (F2). Every handler opens with
the runtime `entityPortals` check (config plugin gates only `qouteall.*`). Registered in
`seamlessportals-common.mixins.json`.

## §4 LEVERS (rows in BOTH `fabric/build.gradle` blocks)

| lever | default | meaning |
|---|---|---|
| `-PdisableSeamSignal` → `seamlessportals.disableSeamSignal` | ON (fix) | MASTER (c) off-switch: no union, no walk crossing, no dispatch — signal stops at the seam exactly as stock (b) |
| `-PdisableSeamSignalDispatch` → `…disableSeamSignalDispatch` | ON (fix) | dispatch only: reads stay, far side goes STALE until independently touched (the attribution split: "can the far side see power" vs "is it told to look") |
| `-PseamSignalProbe` → `…seamSignalProbe` | OFF (probe) | per-event logs + `SeamSignalContinuity.counters()` |

## §5 GATES — `rsSignalLeg`, runs in RS-only, lever-aware both directions, coverage-asserted

**Arm A (COINCIDENT, obsidian):** fresh fixture (clear of every existing one, RS-RAIL-A recipe):
ignited obsidian frame; golden-rail line source-approach → seam cell (placed `writeAsPlayer`,
mirror creates the far half) → far continuation ×2 (far side's own rails, direct writes); support
stone; power source = `/setblock` redstone block LATERAL to the far end of the source approach
(not a seam cell — mirror policy untouched). ON → poll ≤ 60 ticks → assert **the far world's
continuation rails hold `POWERED=true`** (the state the user sees lit and the cart accelerates on
— asserted in the FAR level, after the full vanilla cascade, not any value (c) computed) + walk
coverage moved (`walkCrossHits > before`) + budgets zero. OFF (remove the block) → far rails
`POWERED=false`. **Inversion `-PdisableSeamSignal`:** source side powers (incl. the seam cell and,
via shape sync, its mirrored half — the user's reported baseline), far continuation stays
`POWERED=false` (the reported defect reproduced on demand), counters zero.

**Arm B (DISJOINT, same-dim bi-way pair, RS-RAIL-B recipe at fresh coords):** golden rail flush at
the near cell + approach + redstone block; far side's own golden rails at the continuation. ON →
far rails power (through D1 + R-WALK; `dispatchDelivered > 0` asserted). OFF direction asserted.
**Inversion `-PdisableSeamSignalDispatch`:** near side powers, far stays `POWERED=false` stale —
the dispatch gap reproduced (reads alone cannot wake the far side). (Arm B is where the dispatch
lever inverts; in Arm A the mirror's flags-515 write already notifies the far side, so the
dispatch lever alone does not invert there — asserting it there would be a gate that cannot fail.)

**Arm L (lamp, COINCIDENT):** lamp pair at a seam cell (placed as player), redstone torch beside
the FAR half (far side's own scenery). Assert the SOURCE half's `LIT=true` (union read + D1 +
revert dance end-to-end) and both halves lit; torch removed → both unlit. Under
`-PdisableSeamSignal`: source half stays unlit (defect on demand).

Leg prints its working (shapes/powered per cell + counters) per the house gate rules; cleanup in
`finally`; fixtures forceloaded; every wait is a polled precondition, never a bare tick count.
Full matrix before commit: the two FULL suites, the (b) inversion rows, and the two new levers
each way. Wire behaviour needs LIVE eyes — ask the user after landing.

## §6 DESIGNED BUT DEFERRED (recorded so nothing is lost)

1. **D2 — delivery-forwarding hook** (static `NeighborUpdater.executeUpdate`, interface-static
   mixin, repo-precedented): forwards a neighborChanged DELIVERY into a seam cell (no state
   change) to the counterpart. Only needed for conductor strong-power relays and no-flip cases —
   none reachable in step-1 scope. Guards designed: `forwarding` bracket, `isApplying`, dedupe.
2. **Wire-to-wire decay** (F11): needs `getBlockState`-level bridging in
   `RedstoneWireEvaluator.getIncomingWireSignal` (or an evaluator substitution) + wire
   connection-shape (`getConnectionState`) + `updateIndirectNeighbourShapes` diagonal routes +
   the shape-update channel. The `shouldSignal` singleton rules (F8) are already written for it.
3. **General consumers via a `SignalGetter` default-method interface mixin** (F1/F10): one hook
   covers doors, dispensers, pistons, TNT, note blocks… Feasible per bytecode gates; needs a
   one-launch smoke test; needs a WorldGenRegion/instanceof guard (a SignalGetter injection also
   rewires worldgen reads). The per-consumer wraps shipped in step 1 remain correct under it
   (union is idempotent).
4. **Diode/comparator seams** need far-side BLOCKSTATE visibility (`getInputSignal`'s wire-POWER
   fallback reads getBlockState), not just signal values.
5. **Detector rail** `updatePowerToConnected` rail-graph dispatch (F13) + the (b) residual
   3-way-junction swallow.
6. **Experimental redstone** (`REDSTONE_EXPERIMENTS`): non-null direction-absolute Orientations
   would need remapping through the binding rotation (48 interned values, table lookup). Feature
   is off by default; out of scope.
7. **Two-portal in-bracket cascade gate arm** (panel BLOCKER's dedicated coverage): two portal
   pairs whose far rails connect, asserting both counterparts converge when a cascade flip lands
   inside the mirror's applying bracket. The identity-D1 fix strictly widens dispatch coverage
   (it cannot regress the swallow case), but no suite arm yet constructs the two-portal geometry.
8. **Junction/curve switching from far signal** (the dropped M2): needs a wake-up rule for
   far signal-source changes adjacent to a seam cell (D2 family) or it is unreachable-and-sticky
   (`RailState.place`'s powered flag inverts curve priority; vanilla reverts on power-off, a
   stuck union would not). Also a policy-adjacent geometry widening — take it to the user.
9. **Independently-built pairs** (bind-time-reconciliation-preserved, neither half
   provenance-marked): shape sync and the authority revert both require provenance, so such a
   pair's POWERED/LIT convergence rides D1+union alone — and under
   `-PdisableSeamSignalDispatch` it visibly diverges (one lit half, one dark). Documented
   behavior of an attribution lever, not a supported configuration; no gate arm builds this
   fixture yet (it requires pre-ignition rails or two-sided player writes).
10. **Level-sampled pulses**: D1 re-derives from settled state at tick end, so a sub-tick far
    pulse (observer one-tick) that rises and schedules off within one tick may not light the
    near half at all, where vanilla adjacency would flash ≥4 ticks. Edge-carrying dispatch is
    D2 territory.
11. **Cold-far disagreement flicker**: while a far chunk the union/walk needs is cold, a
    COINCIDENT pair's halves can compute different `shouldPower` and the
    write→revert→D1-delivery cycle repeats once per tick until warm-up (bounded by dedupe +
    budget; converges on warm via the queued retries). Visible as counter churn, never a hang.
