# SEAM FULL-FUNCTIONALITY SWEEP — HANDOFF (2026-08-11, updated post-ARM-T)

Branch `claude/particle-seam-regression-c2f79c`, code tip **`b58000c`**, worktree
`E:\Immersive Portals - Copy\.claude\worktrees\particle-seam-regression-c2f79c`.

This is the CURRENT-STATE map for the post-closure regression sweep. Predecessors, still valid
for everything they cover: `migration/SEAM_FUNCTIONALITY_HANDOFF.md` (the r27–r46 particle-arc
closure) and `migration/PARTICLE_SEAM_HANDOFF.md` (the saga). Do not re-derive their history.

---

## 0. THE ONE THING TO READ FIRST

**F5+F6 (the cart crossing arc) LANDED at `02fd8de`** (2026-08-16), suite green (`ALL LEGS
PASS`, 22:31 run), live-verified over five user rounds. The full mechanism ledger is in that
commit's message: riders (3 parts), conserved arrival + client REBASE, the straddle pin
(`SeamStraddleBracket`), the cell-lookup disarm (`isSeamContinuous` must resolve
`seamCell(onPlane(origin))`, NEVER `containing(origin)`), the in-pass projection clip (seam
faces thread the real plane; IP's null stays for framed portals), the straddle-side pass gate,
and the camera-side-scoped ADJUSTMENT. The 2026-08-12 "per-entity clip dead on GPU" suspicion
was REFUTED by a 5-agent static verify (workflow `wf_516e0df3-e27`): the chain is alive on the
vanilla pipeline (dead under Iris shaders-ON by design — known-open).

**Same-dim crossing contract is user-clean**: direction asymmetry gone, window flash gone,
sliver bleed gone, riders solid. OPEN: (a) the "cart visible a couple seconds in source side a"
sighting — every seam-owned draw path exhaustively exonerated by log scans; on 2026-08-16 it was
observed on the CROSS-DIM rig only (the round-4 log has no same-dim crossings); user owes the
answer whether it ever occurs same-dim (time marker: type anything in chat — wall-clock
timestamps land in the log); (b) **CROSS-DIM arc, user-deferred**: smoking gun already captured
— `REBASE via portal 1999 visual=(716.28, 173.06, -127.5) server=(35.28, 118.06, -58.5)`
(round-4 log, 22:20:20, id=3712) — the rebase transform applied in the wrong frame writes
garbage client visuals; likely also the couple-seconds ghost there. Start the cross-dim session
at `ClientTeleportationManager.RemoteCallables.updateEntityPos`'s rebase branch.

All seven earlier sweep fixes remain landed and suite-green (2026-08-11 20:16 run). ARM T's red
was resolved evidence-first — the full story is §4, kept as a post-mortem because it earned two
new machine-wide lessons (the seam-axis half FLIP, and `updateNeighborsAt` semantics).

**NOT yet live-verified: F1 (both rounds) and F4** — each landing is owed its one live-verify
round (F1: break a seam block from both sides, watch the burst play to completion at both ends;
F4: stage a two-object cell, power each circuit separately and together). The 2026-08-11 live
round also flagged seam-block breaking + redstone power + rail signal for contract-first
focused runs (see the memory entry "Seam regression sweep 2026-08-10").

---

## 1. ENVIRONMENT (unchanged, still true)

- Build scaffolding (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`, root `settings.gradle`, root
  `build.gradle`) is UNTRACKED and already present in this worktree. If it ever goes missing,
  copy from the REDSTONE worktree
  `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone` — **never** from the
  `E:\` parent repo (26.1.2-era, wrong, cost one failed build).
- Suite: `.\gradlew.bat :fabric:runCrossingGametest -PapertureCensusProbe=true
  -PapertureTeardownTest=true -PseamFractionalProbe=true` (~6–8 min).
  Add `-PseamSignalProbe=true -PseamMirrorProbe=true` when diagnosing redstone.
- **Green = the `ALL LEGS PASS` line in THAT run's own log + the full leg sequence.** Never exit
  code alone (vacuous exit-0 passes are real). The log ROTATES mid-run — reconstruct from
  `fabric/runs/gametest-crossing/logs/*.log.gz` + `latest.log` before judging.
- The gametest opens a REAL WINDOW on the user's desktop. Warn them; closing it voids the run.
- The gradle daemon SERIALIZES: a suite launched while a live client runs queues 40–50 min.
  **Never `gradlew --stop`** (machine-wide rule). Kill only PIDs whose command line contains
  THIS worktree's path.
- Client: `.\gradlew.bat :fabric:runClient -PseamFractionalProbe=true -PseamMapProbe=true
  -PseamSignalProbe=true`. `seamMapProbe` narrates alignment refusals — without it a non-exact
  pair fails SILENTLY and reads as total breakage.
- **Exact-aligned pair recipe** (the user's policy declines non-lattice pairs query-only). Dest
  coords must share the source plane's fractional phase — from a block-centre stance,
  `X.5 <whole Y> Z.5`. The suite's own same-dim-far fixture:
  `/tp @s 8600.5 100 8603.5 180 27` → `/portal make_portal 1 2 minecraft:overworld 8600.5 51.0
  8660.5` → re-aim → `/portal complete_bi_way_bi_faced_portal`.
- javap the deobf jar before using ANY vanilla signature:
  `C:\Users\warwa\ModDev\Portals\Portal 26.2\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-95f25a8320\26.2\minecraft-merged-95f25a8320-26.2.jar`

---

## 2. THE CONTRACT + EVERY USER RULING (2026-08-10/11)

Stitched space: source side A ∪ dest side B are ONE real place. Existence is the mechanism,
rendering a consequence, nothing camera-dependent. Blocks mirror as real state; light and fire
spread across; the mirrored half emits its own particles; bleed standard is plane-exact clipping;
all three topologies (same-dim near, same-dim far, cross-dim).

Rulings taken this sweep, verbatim in intent:

1. **Wire continuity: FULL**, one round, no stopgap staging. "When power crosses the seam ... the
   lit dust should also propagate down the stream" — the far dust is real dust that receives,
   lights, and propagates onward by itself.
2. **Two-object cells: FULL participation.** "SA to DB should carry signal normally, and SB to DA
   should carry signal normally as well at the same time even if they overlap each other on the
   seam." Secondaries both RECEIVE and EMIT.
3. **Break burst:** the dest end fires its OWN native burst for its half, and the source burst
   must play at normal speed to full completion.
4. **Entity crossing (F5):** full fractional crossing — plane-exact clip + a counterpart while
   straddling. Ridden path same bar, same round; check whether a rider (mob/player) leaks too.
5. **Smoothness (F6):** ALL entities through ALL portals. "Fully smooth and continuous and
   correctly clipped." Observed on an empty cart; the user believes it happens ridden too.
6. **F7 margin band:** governance is the SEAM CELLS' alone — "we don't need an 8 block buffer
   zone." (Landed: margin teleports removed; the index still feeds the billboard clip.)
7. **July-28 provisional signal decisions — CLOSED this session:** (a) power-only transmission,
   never block-mirroring to fake it → **KEEP**; (b) signal crosses BOTH seam geometries
   (coincident + boundary-phase) → **KEEP**; (c) far-side rail curve/junction switching →
   **deferred as future polish, documented as a TODO** — recorded as item 2b in
   `migration/REDSTONE_C_SPEC.md` §6.

---

## 3. WHAT LANDED (all suite-green at the time of commit)

| commit | fix | mechanism |
|---|---|---|
| `c46bb43` | **F2** dest outline flicker | Translucent-MODEL targets (dust is `force_translucent`; also slime/ice/honey) routed their dest outline into the `afterTerrain` phase — the one phase r43's clip exemption never covered, so it drew under the re-armed inner clip with a garbage lines-shader clip write. `submitDestBlockOutline` now forces `afterTerrain=false` (bucket choice is order-irrelevant in-pass). **Do NOT instead exempt the `afterTerrain` phase** — the window particle draws live there and the re-arm is what stops the r42 bleed. |
| `584c138` | **F3** wire continuity + the loop's tombstone | `MixinRedStoneWireBlockSeamAuthority` (wire twin of the rail authority: `neighborChanged` HEAD-cancel + `updateShape` identity at provenance-marked cells, poke FORWARDED to the tick-end queue, never evaluated in place) killed the mirror-revert loop that tripped vanilla's 1M-chained-update cap **81×** and ran the server **837 ticks behind** (the whole 42 s stall was dust). `MixinRedstoneWireEvaluatorSeam` bridges `getIncomingWireSignal`'s four raw reads (spec F11). `MixinRedStoneWireBlockSeamSignal` bridges the block-power intake + the 4-arg `getConnectingSide`'s three reads. |
| `968663d` | **F7** margin teleports retired | Oscillating classes (`FallingLeavesParticle` swirls sinusoidally every tick) re-qualified on every sway through the r46 margin fallback: one leaf logged **124 lifetime crossings**, blinking at up to 19 Hz. Fallback removed; the margin INDEX stays registered for `SeamParticleQuadClip` so the plane-exact billboard cut survives. Green run: `ofWhichMarginRing=0` across all TP lines, max crossings 4→2. |
| `7bac675` | **F1 round 1** dest burst | The counterpart clear was a silent `setBlockAndUpdate` — no destroy effect ever fired at the far end. Now fires vanilla `levelEvent(2001)` for the cleared state. (The crumb half of F1 was wrong — see §4.) |
| `09775b8` | **F8** power respects the half | The user's live leak: source-A seam dust powered **dest side A and source side B**. A seam occupant is a whole vanilla block, so its RAW-read family ignored the cut (round-27 `blocksSignalTowards` covers only the `getSignal` family — which is exactly why the leak was dust-shaped). Landed rule: a cut cell with a derivable PRIMARY half participates per-half — raw reads from its empty side see the Secondary or air; its own reads use exactly ONE candidate per seam-axis direction (owned side local-only, empty side far-only); far six-scans skip the counterpart's behind-plane neighbour; wire/lamp/rail local intake skips the empty-half direction; `MixinDiodeBlockSeamHalf` gates the two raw wire-POWER paths (`getInputSignal`, `getAlternateSignal`'s two `getControlInputSignal` calls). **Unclaimed cells (mask 0) stay whole-cell vanilla** — that carve-out is what keeps every command-staged fixture byte-identical. One gate point: `SeamFractional.emptyHalfDir`. One lever: `-PdisableSeamHalfScope`. |

| `52aa99f` | **F1 round 2** crumbs never teleport | The user's live probe showed round 1's birth-cull missed the real drain: the OWNED-cell material-continuation branch (`ownedCont=29–31` teleports/s through the whole animation). The cull moved to AFTER binding selection, so a `TerrainParticle` is consumed whichever branch chose to move it; crumbs staying in their own half are never touched. Green run: `consumed=64` both topologies, max crossings 2, pingPongers 0. |
| `b58000c` | **F4** the second object's power lifecycle | `SeamWireBridge.refreshSecondary`: evaluate the fragment from BOTH of its circuit's sides over the IDENTICAL physical set, store into the side table, sync the counterpart with the MIRRORED half, broadcast, fan `{pos} ∪ shell`. Woken from the authority-cancel branch (marked) + `neighborChanged` TAIL (unmarked) in both authority mixins. ARM T + `secondaryRefreshes ≤ 5000` ceiling. Three-build saga in §4. |

**Live-verified by the user:** F2, F3, F7 ("all good"), and F8's targeted leak ("seam redstone not
sending power incorrectly anymore; good").
**NOT yet live-verified:** F1 (both rounds), F4.

### Levers added this sweep
`-PdisableSeamWire` / `-Dseamlessportals.disableSeamWire` (wire continuity A/B; the authority
mixin honours it too, so OFF deliberately reproduces the revert loop for diagnosis)
`-PdisableSeamHalfScope` / `-Dseamlessportals.disableSeamHalfScope` (F8 A/B)
Both have `-P` rows in BOTH `fabric/build.gradle` blocks.

### Gates added this sweep (in `rsWireLegCoincident`, CrossingSmoke)
- **RS-WIRE base arms F/S/O/R** — forward, stability (20-tick flap detector), off, reverse, plus
  a bridge-must-move coverage assert and a `shapeReverted ≤ 20` **volume ceiling**.
- **ARM H (F8)** — re-stages the pair WITH a claimed half, asserts continuity survives the claim
  (the over-void failure mode) and that both behind-plane directions stay dark under forward AND
  reverse power, plus the symmetric intake row.
- **ARM T (F4)** — the red one; see §4.
- `pollOrDump` wrapper: every wire poll dumps full cell states + all counters on timeout.

---

## 4. THE ARM T SAGA — RESOLVED 2026-08-11 (kept as a post-mortem; landed in `b58000c`)

**How it resolved (evidence-first, zero guessed fixes).** The recommended probe was added
(`[F4-REFRESH]` in `SeamWireBridge`, one line per refresh exit branch, probe-gated and
volume-bounded — it fires only for cells that HOLD a secondary), the suite ran with
`-PseamSignalProbe=true`, and the log overturned **all three ranked hypotheses below** in one
line: the refresh at S' fired and WROTE (`WROTE power=14 via srcFacing=south`). The fixture was
right, the wake reached, the binding matched. Two real defects, found in sequence:

1. **The seam-axis half FLIP (probe run).** `refreshSecondaryInner` used `mapDir(b, secDir)` as
   the counterpart fragment's own side and half — but crossing the seam MIRRORS which side of the
   plane matter is on (`claimCrossingHalf` had the flip all along; this path didn't). The sync
   stamped the cellSA fragment onto the primary's claimed half (probe: `secHalf 1→2`, dump:
   `secS=[…, half=2]`), the far scan read the OTHER object's circuit side, and the fan skip
   pointed backwards. Fixed at all three sites: the mapped secDir names the far PRIMARY side.
2. **The dropped `{pos}` in the fan (found by the parallel verification workflow
   `wf_18eea323-8f1`, then confirmed by a falsifiable run).** `Level.updateNeighborsAt(P)`
   notifies the six cells AROUND P, never P itself (bytecode: `MultiNeighborUpdate.runNext`
   executes at `sourcePos.relative(dir)`). Vanilla wire fans `{pos} ∪ pos.relative(6)`; the
   refresh fan had copied the shell and dropped `{pos}`, so **no face-adjacent wire was ever
   poked** — `behindNear` could not re-evaluate no matter what was written. The half-flip-only
   run confirmed the prediction exactly (correct sync half, `behindNear` still dark), then both
   fans got the leading `{pos}` entry and the suite went green: `ALL LEGS PASS`, RS-WIRE PASS,
   `secondaryRefreshes=8`.

Original state analysis below, kept verbatim for the record — note that all three ranked
hypotheses were WRONG (structurally sound guesses, each individually exonerated by the
workflow's static traces and the probe): the standing "do not guess, let the log speak" rule is
what prevented three more wrong builds.

### 4a. F1 round 2 (believed correct — confirmed, landed in `52aa99f`)
The user re-reported after F1 round 1: *"seam block break particles are still too fast/suppressed,
reanalyze the cause."* Their live probe was decisive: round 1 culled only **open-cell BIRTH**
crossers, but the actual drain was the **owned-cell material-continuation branch** —
`ownedCont=29–31 teleports/s` sustained through the whole animation. Round 2 moves the cull to
*after binding selection*, so a `TerrainParticle` is consumed **whatever branch** chose to move it;
crumbs staying in their own half are now never touched.

Evidence it works, from the red run's own log (these legs ran BEFORE the failure):
`[RS-PTCL-MEASURE cross-dim] LIFETIME: teleports=585 consumed=64` and
`[same-dim] teleports=907 consumed=64` — crumbs consumed instead of teleported, and
`[RS-SEAM-FIRE-LIGHT] PASS` (flame/smoke behaviour untouched, as intended).

### 4b. F4 two-object participation — the blocker (resolved above; original analysis verbatim)
`SeamWireBridge.refreshSecondary` gives a side-table fragment a power lifecycle: evaluate from
both of its circuit's sides, store into the side table, sync the object's counterpart fragment,
broadcast (the payload carries full state ids, so lit fragments render), and fan neighbour updates.
Woken from the authority-cancel branch (marked cells) and a new `neighborChanged` TAIL hook
(unmarked), in **both** the wire and rail authority mixins. Depth-guarded (`refreshDepth < 4`).

**Build 1 failed with a runaway:** `secondaryRefreshes=1,999,974`, `decayReads=96,008,024`,
`unionReads=10,000,320` in one leg. Cause: the two ends evaluated over **different** input sets
(each read its own far-continuation cell), disagreed, and fought through the mutual sync at tick
speed. **Fix applied:** both ends now scan the IDENTICAL physical set (each end's own-side
neighbours, wire/block split so wire neighbours take the decay path — replicating vanilla's
`shouldSignal` latch semantics). Runaway is gone: `secondaryRefreshes=1`, `decayReads=11,080`.
A `secondaryRefreshes ≤ 5000` ceiling now guards this channel in the gate.

**Build 2 (current red):**
```
[CROSSING SMOKE] RS-WIRE F4: the second object's circuit (SB→DA) never carried within 200 ticks.
DUMP: ... S'.marked=true | wire{decayReads=11080 decayHits=125 connReads=875 connHits=7
      halfGated=99 secondaryRefreshes=1}
```
26 legs passed before it. `secondaryRefreshes=1` is the tell: the refresh runs but **returns early
almost every time**, so the fragment's POWER is never written.

**Ranked hypotheses (evidence-first, verify before changing anything):**
1. **The ARM's `fragmentHalfAtB` is derived wrong — a FIXTURE bug, not a code bug (most likely).**
   The arm computes `fragmentHalfAtB = SeamOccupancy.halfOf(farStep.getOpposite())` from the
   SOURCE binding's rotated step, but `refreshSecondary` matches
   `SeamOccupancy.halfOf(cand.srcFacing()) == sec.half()` against the DEST level's OWN binding at
   `destPos`, whose `srcFacing` lives in the dest frame. If those disagree, the binding lookup
   yields `null` → early return → no power, exactly as observed. **Remember the standing lesson:
   a fixture that fails is not thereby reproducing the reported failure.** Check this first.
2. **The wake never reaches the fragment's cell.** At `S'` (marked) the HEAD cancel path calls
   `refreshSecondary`; at `cellSA` (unmarked) the TAIL hook does. Verify both actually fire for
   the arm's specific pokes — `require = 1` guarantees weaving, not firing.
3. **The primary-half discriminator disagrees with the staged mask.** ARM H claimed
   `halfOf(approachDir)` at `cellSA`; the arm then sets a fragment on the other half. Confirm
   `SeamFractional.primaryHalf` returns what the arm assumes at BOTH cells (single-bit mask vs
   `BOTH` + Secondary).

**Recommended next move:** add a probe-gated one-line dump inside `refreshSecondary` naming the
early-return reason (no secondary / not a participant / no half-matched binding / no change) plus
the resolved binding's `srcFacing`, run the suite with `-PseamSignalProbe=true`, and let it say
which branch returns. Do not guess — this is the third build of this fix and the first two were
each corrected by one honest log line.

---

## 5. REMAINING QUEUE

### F5 + F6 — fractional entity crossing (the user's explicit next item, largest piece)
Both were investigated with evidence on 2026-08-10 (workflow `wf_520742f2-a05`, all mechanisms
adversarially verified). Findings, so the next session does not re-derive them:

- **F5 (cart visible on the wrong side for a split second).** The server teleports the cart as a
  WHOLE entity when its EYE segment crosses (`ServerTeleportationManager.shouldEntityTeleport`,
  a cart's eye is its centre), and arrival is placed at plane-hit + 0.05
  (`getRegularEntityTeleportedEyePos`), so the 0.98-wide box lands protruding **~0.44 blocks
  behind the dest plane** for 1–2 ticks. IP's CASE-1 main-pass per-entity clip
  (`CrossPortalEntityRenderer.submitMainPassEntity` → `PerEntityClipBracket`, live because Iris is
  absent) fires only for entities with a current `portalCollisions` entry, and that bookkeeping
  refreshes at tick cadence — so the arriving cart renders UNCLIPPED for up to ~2 client ticks
  (cross-dim arrivals are a fresh entity object, the worst case). CASE-2
  `renderEntityProjections` draws the counterpart correctly once bookkeeping catches up — and
  **CASE-1 returns false during portal-view rendering**, so through-portal views were never
  covered either. *Small fix:* seed the arrival portal into `PortalCollisionHandler` /
  `collidedEntities` at snap-apply time (deterministic `portalId` exists) so the first rendered
  frame is already clipped. *The user asked for the big fix:* plane-exact clip + a counterpart at
  the far end for the whole straddle, ridden path included.
- **F6 (hitch).** Three stacked structural discontinuities, all on every crossing at healthy TPS:
  (1) the teleport is queued to `END_SERVER_TICK`, so the cart is broadcast up to ~0.8 blocks PAST
  the plane (ridden waits ~3 ticks for the rider's client-first round trip → ~1.59 blocks);
  (2) arrival then REWINDS that overshoot to plane + 0.05; (3) the client applies it with a
  deliberate interpolation-kill (`updateEntityPos` → `setBase` + `snapTo` +
  `InterpolationHandler.cancel`, IP-inherited and deliberately re-ported). Plus
  `Portal.transformVelocityRelativeToPortal` **doubles** any velocity with `lengthSqr < 0.5`
  (`Portal.java:1145`) — max rail speed 0.4 always qualifies, so empty carts lurch (ridden carts
  skip that path). Nothing regressed; it has been this way through every "seamless" confirmation,
  which gated placement correctness, not frame continuity. *Directions:* conserve the overshoot
  server-side (`transformPoint` of the actual position instead of plane+0.05); REBASE the
  interpolation through the portal transform instead of cancelling (the render-side analogue of
  the rs(e) `VecDeltaCodec` rebase rule) — a seam dest is loaded by construction, which is why IP
  cancels; exempt the ×2 slow-cart kludge at seams. ⚠ The ×2 kludge is a recorded keep-as-is
  decision from the (d) landing — the user has now authorised changing it at seams; global
  removal still needs care (its purpose is nether-portal push-out).
- **First step before writing F5/F6 code:** one lag-free retest with `-PseamCartProbe` armed. The
  2026-08-10 walk had ZERO cart-probe lines (lever unarmed), so the observed crossings could not
  be placed in time or typed empty-vs-ridden, and part of that session's hitch was the F3 dust
  stall (server 837 ticks behind).

### Recorded v1 scope cuts (F8) — not bugs, listed in the F8 commit
In-plane lateral adjacency of two claimed cells with OPPOSITE halves can still connect; observer
shape-pulses toward the empty side; strong-power fan-in through the support block below.

### Known-opens inherited (unchanged)
Dest-light gate vacuous on bright nether baselines; Iris portal-pass hardware clip un-injected
(CPU quad clip covers particles); smoke wandering >8 in-plane cells before crossing (the old
margin band's original driver — **watch for this live now that F7 removed margin teleports**; the
principled fix if it returns is birth-side tagging, a user decision); alignment refusal is silent
in-game (chat-notice chip still pending the user's word); `SeamFractional.particleSpawnPos` and
`particleHiddenFromEmptySide` are dead code awaiting a cleanup round; skylight column sources
treat a cut cell as whole.

---

## 6. DOC DEBT (discharged)

- ~~Owed~~ **DONE:** ruling 7(c) is recorded as item 2b in `migration/REDSTONE_C_SPEC.md` §6
  (far-side rail curve/junction switching = future polish), together with the two KEEP rulings.
  §6 item 2 (wire-to-wire decay, F11) is also now marked LANDED there, with its residual
  (`updateIndirectNeighbourShapes` diagonals + the shape-update channel) still deferred.
- `AperturePassthroughLever.DISABLE_SEAM_POWER_WAKE`'s javadoc was corrected this session (it had
  described the reverted in-place-evaluation build).

---

## 7. HARD RULES (carried forward, all earned)

- Evidence before fixes, always. Three fixes this session were corrected by one honest log line
  each; two of them would have shipped wrong.
- **Suite green before any commit.** Green = `ALL LEGS PASS` in that run's own log + the leg
  sequence, never exit code alone.
- One live-verify round per landing; the user's eyes are the real assert.
- javap the deobf jar before touching any vanilla signature; check for existing wraps before
  adding redirects (the repo rule) — F8's panel found my proposed emission site was already
  covered, stricter, by round 27.
- `@Unique` mixin field initialisers must not make cross-class static calls (ctor class-init
  deadlock froze world creation twice).
- Never watch correctness without watching VOLUME. Every new mechanism gets a ceiling in its gate.
  This session that rule caught a 2M-iteration runaway on its first run.
- Never `gradlew --stop`; kill only PIDs whose command line contains this worktree's path.
- Adversarially verify a SEMANTIC rule before implementing it. The F8 panel
  (`wf_bcc95ef6-045`) corrected the draft rule in six load-bearing ways — including one that would
  have broken a feature that was already working.
