# REDSTONE / RAIL / MINECART PASSTHROUGH — NEXT-SESSION HANDOFF

**Branch `redstone/passthrough`**, worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`. Tree is clean and green.
The per-step commit table below carries the tips; this header no longer names one, because it was
stale by four commits the last time it was read.

## READ FIRST, in order
1. `migration/REDSTONE_RECON.md` **§0** — every user decision, pinned, with the reasoning.
2. `migration/REDSTONE_A_SPEC.md` — the (a) spec. **Its top banner overrides the body** where they differ.
3. This file.

## ★★ DECISIONS CHANGED 2026-07-26 — THESE OVERRIDE `REDSTONE_RECON.md` §0 ★★

**Read this before §0, and do not "restore" the old rules from §0 alone.** Both changes are the
USER's, taken live on 2026-07-26, and both contradict text that still stands unedited in the recon.
Commit `7766010` exists because a design panel once made change #2 *without* the user's word.

| # | change | supersedes | lever |
|---|---|---|---|
| 1 | **Only PLAYER writes mirror.** Placing and breaking by hand mirror; pistons, dispensers, gravity, fluid spread, `/setblock`, `/fill` are classified and declined. | §0.8 "non-item writes → ACCEPT BEST-EFFORT" | `-PdisableSeamPlayerOnly` |
| 2 | **Only EXACTLY-ALIGNED seams mirror.** Offset (sub-block phase) seams are declined; offset support is deferred. | §0.7 "phase-offset target = GREATEST OVERLAP … deliberately reproduces the half-block jog" | `-PdisableSeamExactOnly` |

The architecture was built so both are *policy*, not inlined conditions —
`SeamWriteSource` (who wrote it) × `SeamAlignment` (how it lines up) → `SeamMirrorPolicy` (the only
place that decides). **Widening later is editing two methods in `SeamMirrorPolicy`.** `MACHINE`,
`COMMAND`, `FLUID` and `OFFSET` are already named and classified rather than lumped into "no", so
each is a one-line admission when its turn comes. The user's stated requirement was that this
generalise "to everything else easily, including offset mirroring" — that is what those seams are for.

## ALSO LANDED 2026-07-26

- **Same-dimension portals update live at any distance** — both defects fixed, user-confirmed
  ("WORKS, SAME DIM DISTANCE PORTALS ARE AUTO UPDATING!"). See the CLOSED section below.
- **Same-frame mirroring**, place AND break — the mirrored half now appears in the same frame as the
  player's own block, like vanilla. Lever `-PdisableSeamPrediction`. User-confirmed both directions.

## ★★ NEXT ENGAGEMENT IS NOT REDSTONE — see `migration/CART_RENDER_RIDE_HANDOFF.md`

Two user-reported defects found live 2026-07-28 AFTER (d) landed, which **invert across the two
topologies**: SAME-DIM portals lose the empty cart from the window as it crosses (terrain still
draws), while CROSS-DIM portals break RIDING on teleport (forced dismount, or the player spazzes
in place over a sometimes-blank background). Rendering defect in the same-dim path, riding defect
in the cross-dim path; two independent investigations. Starter prompt:
`migration/CART_RENDER_RIDE_PROMPT.md`.

## WHERE THINGS STAND (2026-07-28, latest session)

- **★★ (d) MINECART TRAVERSAL ACROSS THE SEAM — LANDED AND GATED (2026-07-28, this session).**
  Instrument-first per recon §5.5: the ordering question was MEASURED before any design
  (probe legs + `SeamCartProbe`, `-PseamCartProbe`), the answer overturned half the recon's
  fear (COINCIDENT crossings were already CLEAN STOCK), and the fix is one READ bridge at the
  cart's rail-resolution chokepoints. See the ★ (d) section below for the measured record, the
  levers (`-PdisableSeamCartRail` master), the two RS-CART gates, and the deferred items
  (ridden-cart rotated-binding velocity, slopes, NewMinecartBehavior). **Live user round still
  pending** — empty carts and ridden carts both want live eyes.
- **★★ (c) STEP 1 — REDSTONE SIGNAL ACROSS THE SEAM — COMPLETE AND USER-CONFIRMED LIVE
  (2026-07-28: "dude it works perfect now").** The powered-rail chain crosses the seam in both
  topologies and both directions, the seam lamp lights from a far-side source, and the live
  round surfaced and closed TWO real defects (the POWER-WAKE and BREAK-UNMARK — see the ROOT
  CAUSE record in the ★ (c) STEP 1 section). Tip `8b06934`, 9-configuration matrix green,
  everything pushed. The three provisional design decisions still want the user's EXPLICIT word
  (the live confirmation validates the behavior; the decisions table below records what was
  decided on their behalf). Spec + panel record: `migration/REDSTONE_C_SPEC.md` (its ★★ PANEL
  FOLD banner overrides its body). ⚠ User-world note: seam rails placed BEFORE the break-unmark
  fix may carry old stale marks — one break + re-place per rail heals permanently.
  **Next-session starter prompt: `migration/REDSTONE_D_PROMPT.md`** — (d) minecarts is the
  standing next item; (c) step 2 (wire/dust) is the documented alternative.
- **★ THE SEAM CLIP IS LANDED, GATED — AND DEFAULT OFF BY USER DECISION (2026-07-27)** — see
  the ★ SEAM CLIP section below. OPEN ITEM 1 is CLOSED as built; the user tested the
  walk-around live and DECLINED the view-dependent doorway semantics in favour of the FUTURE
  FRACTIONAL MODEL (recon §0.9). The clip machinery is kept intact as that model's renderer;
  one-line re-enable `-PenableSeamClip=true` (or flip the initializer in
  `AperturePassthroughLever.DISABLE_SEAM_CLIP`). The suite's first PIXEL gate
  (`rsSeamClipGate`) asserts BOTH directions and is green either way round.
- Sub-feature **(a)** complete and user-verified.
- **(b) step 2 — RAILS CONNECT ACROSS THE PLANE — LANDED AND GATED** (2026-07-27, this session).
  See the ★ (b) STEP 2 section below for what works, the levers, the two user decisions (BOTH
  CONFIRMED live 2026-07-27), and the deferred items. `REDSTONE_B_SPEC.md` now carries a top banner
  listing where the shipped code deviates from its body — the code and this handoff override the
  spec. **The user chose the SEAM CLIP (renderer) as the next session's work** — starter prompt at
  `migration/REDSTONE_CLIP_PROMPT.md`; (c) redstone and (d) minecarts queue behind it.
- **The suite has an RS-ONLY mode now** (`-PrsOnly`, default OFF) — open item 5 is closed. It skips
  the crossing/teleport legs and leg 5's world reopen; every RS gate, portal staging and 6a/6b still
  run. ~3.5 min instead of ~6+. Full matrix still mandatory before a commit.
- **The same-dim live-update bug is FIXED and user-confirmed** — see the CLOSED section below. It was
  never a mirror defect; it was a client render-path defect that any block change behind a same-dim
  portal hit, and the mirror was only how it was noticed.

## ★ (d) MINECART TRAVERSAL ACROSS THE SEAM (landed 2026-07-28)

**What works, gate-verified both lever directions** (`rsCartLegCoincident` + `rsCartLegDisjoint`,
run in RS-only too; outcome-asserted on the CART the user sees — position/onRails/rolled-on
distance in the FAR world after the cascade — coverage-asserted on `SeamCartContinuity` counters,
runaway ceiling in the same leg):

- **Topology A (obsidian, COINCIDENT, cross-dim):** an empty cart rides a rail line straight
  through the portal and keeps rolling on the nether continuation. **Measured CLEAN STOCK — the
  (d) fix is not even needed here** (bridge fires zero times): the plane is mid-block, so the
  one stranded tick still resolves the seam cell's own near rail, and the teleport lands the
  cart on the far rail at riding height. The arm is REGRESSION coverage (asserts the same
  outcome in every matrix row), not the fix's proof.
- **Topology B (boundary-phase, DISJOINT, same-dim):** the fix's proof and its inversion. Stock
  (measured, deterministic): the single stranded tick `comeOffTrack`s at the behind-cell, the
  cart leaves rail height, and the teleport transfers the corrupted Y — arrival at
  y=99.99999998867511, EPSILON BELOW the far rail's cell, so `getCurrentBlockPosOrRailBelow`
  floors into the stone below forever and the cart halts 0.4 blocks past the far plane, off-rail
  beside a good rail. Fix ON: arrival y=100.0625 (riding height), rolls +6 cells, onRails=true,
  bridgeHits=5. Inversion `-PdisableSeamCartRail` reproduces the halt on demand.

**THE INSTRUMENT DECIDED THE DESIGN (recon §5.5 question 5, answered by measurement 2026-07-28):**
detection runs inside `Portal.tick` (eye-segment test, entity-order dependent), the teleport
itself at `END_SERVER_TICK` (`ServerTaskList`) — so a crossing cart is stranded in the near level
for AT MOST ONE behavior tick. `comeOffTrack` fires before the teleport only on DISJOINT seams
(behind-cell has no near rail); the damage is not the derail itself but the Y-corruption it
hands the teleport. The full tick-by-tick traces are in the RS-CART SAMPLE/EVT lines
(`-PseamCartProbe`).

- **RIDDEN carts now arrive ON THE RAIL, exactly** (`rsCartLegRiddenProbe` cross-dim +
  `rsCartLegRiddenSameDimProbe` same-dim, both probe-gated): player mounts, rides through, cart
  arrives still ridden, on rails, zero `comeOffTrack`, and
  `arrivalErrorVsRidingHeight=+0.0000`. Getting there took TWO fixes and a correction of my own
  first write-up — see the ★ RIDDEN section below. A ridden cart never uses the entity pipeline
  at all (`startTeleportingRegularEntity` skips it on the vehicle/player-cluster gate; the cart
  is carried by the PLAYER's client-first crossing), so the "≤1 stranded tick" bound the one-cell
  reach is sized for does not apply to it by construction.

**The mechanism** (`SeamCartContinuity` + 3 mixins): a seam-framed READ bridge, never a state
copy — every `Level.getBlockState` in `OldMinecartBehavior` (6 javap-counted sites: tick,
moveAlongTrack, getPos ×2, getPosOffs ×2) and `AbstractMinecart.getCurrentBlockPosOrRailBelow`
(2 sites) routes through `railAwareState`: LOCAL-FIRST (a local rail always answers; the bridge
can only ADD a rail vanilla would miss), and only when the queried cell is the THROUGH-IMAGE of
an adjacent bound seam cell does `SeamShadowBridge.shadowFor` + `SeamShadow.readLocal` answer
with the far continuation's state rotated into the near frame (cold far reads AIR + counted
decline). The stranded tick therefore stays ON RAILS AT RIDING HEIGHT, which is the entire fix:
the teleport then transfers an uncorrupted Y and the far side re-mounts.

**★ THE REACH IS BOUNDED BY THREE THINGS, AND TWO OF THEM WERE PAID FOR IN BLOOD.** "One cell
past the plane" is not one condition:

| guard | what it stops | lever | how it was found |
|---|---|---|---|
| **depth** — the owner must be a bound seam cell | the cell past THAT has no seam owner, so a never-teleporting cart derails at cell 2, vanilla-like | (the master) | by construction |
| **direction** — `crossingOnly`: only `step == binding.crossDir()` may answer | `continuationToward` answers BOTH axis directions on COINCIDENT (the far world's CO-LOCATED approach cell — a fallback (b)'s SHAPE resolver legitimately wants); read as PHYSICAL PRESENCE it conjures rails in FRONT of the plane | `-PdisableSeamCartCrossOnly` | adversarial panel, two independent lenses, before commit |
| **occupancy** — the MID-CROSSING MARK: the cart's own CENTRE must have been inside that seam cell within the last 10 ticks | direction alone is not enough on a BI-FACED portal, and **every obsidian frame is a four-entity cluster**: each face's own `crossDir` points the opposite way, so both axis directions pass the direction test for one binding or the other. A cart resting one cell clear of the aperture over open air HOVERED on the far world's track | `-PdisableSeamCartStraddle` | `rsCartLegPhantomRail` — the gate written for the panel's finding caught the member of the family that survived the panel's own fix |

The occupancy guard is HISTORY, not geometry, and both halves of that were paid for: a cart
stranded mid-crossing and a cart parked beside a portal occupy the same cell and satisfy the same
direction test — only one was ever ON the seam (hence the mark); and the mark reads the cart's
CENTRE, not its box, because a 0.98-wide box reaches 0.49 into the neighbouring cell (hence the
half-block phantom band the panel found). The grace does not widen the reach: the DEPTH bound
still demands the queried cell be the immediate neighbour of a bound seam cell.

⚠ **The direction guard has no fixture of its own.** Its reproduction needs a COINCIDENT
SINGLE-FACED portal with a STRADDLING cart reading backward; RS-CART-C's obsidian frame is
bi-faced, so the straddle test is what its lever inverts. Defence in depth, honestly labelled —
do not read `-PdisableSeamCartCrossOnly` passing as evidence that guard is covered.

**One accepted consequence, scoped rather than removed:** the bridged state also feeds
`moveAlongTrack`'s POWERED_RAIL branch, so a stranded tick can apply ONE tick of the far rail's
power to the cart's own velocity — that is the motion we want carried across and it escapes
nothing. The other action it could have driven, `tick`'s ACTIVATOR_RAIL branch, is suppressed at
its own call site (a far activator rail would otherwise eject a passenger / prime TNT / run a
command block IN THE NEAR LEVEL at a locally-air cell). Panel finding; the guard is a no-op under
vanilla, where that branch is only reachable with the state read from that same cell.

Levers: `disableSeamCartRail` (master (d) A/B switch; the bridge also dies under (b)'s
`disableSeamShadow`, which it consumes — same dependency as the (c) walk),
`disableSeamCartCrossOnly` and `disableSeamCartStraddle` (the two guards above), probe
`seamCartProbe` (per-tick SAMPLE lines per watched cart, COME-OFF-TRACK events with the failing
cell, teleport-path EVT lines from `ServerTeleportationManager` naming every skip gate, the
VEHICLE-CARRY lines that time a ridden crossing, and bridge-hit lines). ⚠ The probe's own
resolution reads are BRACKETED out of the counters (`inProbeRead`) — unbracketed, the instrument
would satisfy the gates' own coverage assertion, which is the fifth member of this engagement's
false-reading family.

### ★ THE RIDDEN CROSSING — two fixes, and a correction of my own first reading

**The user's live round (2026-07-28) is the source of truth here, and it split the ridden path
from the empty one cleanly.** Their 40km same-dim pair at `(3,104,0)→(10,19,40000)`:

- **EMPTY carts: eight crossings, both directions, ZERO derails, every arrival at exactly
  `y=…063` — rail riding height.** (d) working, nothing to do.
- **RIDDEN carts: every arrival at `y=…250`** — 0.1875 high, five out of five, both directions.

**Fix 1 — the ridden window (mine).** The first build required the cart to still be TOUCHING the
seam cell at the instant of the read. A ridden crossing takes ~3 ticks (client detect → payload →
server), by which time the cart has travelled ~1.6 blocks and cleared the cell by 0.10 — so the
bridge went quiet one tick early and the cart derailed at the seam. Replaced by a MID-CROSSING
MARK: the cart's own CENTRE having been inside that seam cell within {@code GRACE_TICKS}=10.
⚠ The mark is set from the cart's centre, deliberately NOT its collision box — a minecart is 0.98
wide, so a box test admitted any cart whose centre came within 0.49 of the boundary, a half-block
band inside a cell the cart never leaves, and the hover gate passed that build **by 0.02 blocks of
spawn placement** (adversarial panel round 2). RS-CART-C now spawns 0.30 from the boundary,
INSIDE that old band, so it fails against the old rule.

✅ **BOTH FIXES USER-CONFIRMED LIVE (2026-07-28, second round, same world).** The probe log shows
`attachment=(0.0, 0.4125, 0.0)` — the corrected two-term offset — and EVERY `VEHICLE-CARRIED`
landing at exactly `19.063` / `71.063`, rail riding height, in both directions. Zero
`COME-OFF-TRACK` and zero bridge hits in the entire session: their pair is COINCIDENT, where
crossings already worked stock, so the ridden arrival is the whole of what they gained.
⚠ That round also caught the instrument, not the feature: the live SAMPLE channel emitted 40,104
lines plus 4,962 truncation notices — 96% of a 46k-line log — because the panel's "make SAMPLE
work live" fix shipped without a rate limit. Now: seam-adjacent carts every tick (the crossing
window, and rare), everything else one heartbeat line per second.

**Fix 2 — the arrival height (SHARED MACHINERY, user-authorised 2026-07-28: "change shared
vehicle-crossing machinery as part of (d) to make it totally seamless").**
`McHelper.getVehicleOffsetFromPassenger` returned only `passenger.getVehicleAttachmentPoint`,
but vanilla places a rider at
`vehicle.getPassengerRidingPosition(passenger) − passenger.getVehicleAttachmentPoint(vehicle)`
(26.2 `Entity.positionRider:2380`), and `getPassengerRidingPosition` is the vehicle's position
PLUS the vehicle's own passenger-attachment offset. Inverting needs BOTH terms:
`vehiclePos = passengerPos + passengerVehicleAttach − vehiclePassengerAttach`. The missing term
is exactly the 0.1875. Measured proof in the `CARRY-TERMS` probe line: the rider's real offset
from the cart is **0.4125** while the returned attachment was **0.6**.
⚠ **This spans every ridden vehicle** — boats, horses, striders — on BOTH the client and server
crossing paths, which is why it is lever-gated (`-PdisableSeamVehicleAttach`) and why it needed
the user's word rather than my judgement.

⚠ **A CORRECTION WORTH KEEPING, AND IT BIT TWICE.** I first reported the cross-dim ridden arm as
landing correctly at `72.0625` and concluded the hop was same-dim-only. It was not: that reading
was taken ~70 ticks after arrival, by which time the cart had FALLEN back onto the rail. The
`CARRY-TERMS` line shows the cross-dim carry placing it at `72.250` too. **A settled reading is
not an arrival reading.** The first repair — latching the first POLLED position after arrival —
was still wrong, and its own inversion caught it: an off-rail arrival is snapped back by
`moveAlongTrack` within ONE tick, so even a 2-tick poll read `100.0625` while the carry had
plainly placed the cart at `100.25`. **The gate now asserts the PLACEMENT itself**
(`SeamCartContinuity.lastVehicleCarry`, recorded always-on at the carry site) and inverts
cleanly: `+0.0000` with the fix, `+0.1875` without — with `firstPolledY=100.0625` in BOTH rows,
preserved in the log as the evidence that the polled form could not have failed. Third time this
engagement has paid for *hook the last thing the engine mutates, not what it looks like
afterwards*.

Two comments I wrote in `ServerTeleportationManager` also stated the opposite of what the code
does (claiming the cart's near-level position is carried across, when it is discarded and only
the attachment is used); the panel caught both and they are corrected in place.

### The adversarial panel round (2026-07-28) — what it caught, and what its own fix missed

A 4-lens panel ran over the diff after the first green matrix (10 of its 20 refuters died on a
model rate limit; the surviving verdicts are recorded). Confirmed and FIXED before commit:

1. **The phantom rail** (two independent lenses) — the direction guard above. Real: verified by
   hand against `SeamRegistry.continuationToward:120-124` before touching code.
2. **The activator-rail action branch** fed by bridged far state — scoped at its call site.
3. **The probe polluting its own gates' coverage counters** — bracketed.
4. **The new legs leaked forceloads, portals and terrain** into every later leg — full teardown
   added to all of them (the suite is already flagged as slow, and a surviving obsidian frame
   stays matchable: that has made a later leg link to the wrong portal once before).
5. **The ridden-cart window** — flagged as the most likely first live action, and the reason
   RS-CART-D exists. Measured clean single-player; see the ⚠ above.

**★ AND THE LESSON WORTH KEEPING: the panel's fix was not the whole fix.** Closing the direction
hole left the same defect reachable by another route — bi-faced clusters make BOTH axis
directions a legitimate `crossDir` — and it was the GATE WRITTEN FOR THE PANEL'S FINDING that
caught it, on its first run, by reporting `onRails=true` for a cart that should have fallen. Two
things earned that: the gate asserted the OUTCOME (did the cart fall?) rather than "did the bridge
decline?", and it was built to fail loudly rather than to confirm the fix. A gate written to
confirm a fix would have been green and wrong.

### Found on the way in, deliberately NOT (d)'s scope — do not rediscover

- **The ×2 slow-minecart velocity boost at teleport is IP's OWN deliberate kludge** —
  `Portal.transformVelocityRelativeToPortal`: any cart slower than ~0.7 gets `result.scale(2)`,
  comment "avoid cannot push minecart out of nether portal". Measured in both arms
  (0.192→0.384, 0.177→0.354). Inherited, bounded by the 0.4 rail clamp, left as-is.
- **The ridden-cart vehicle path carries velocity through NBT untransformed**
  (`teleportVehicleAcrossDimensions` — `restoreFrom`, no `transformEntityVelocity` call; the
  empty-cart path transforms BEFORE the recreate, so it is correct). Only observable on ROTATED
  bindings; far-side `moveAlongTrack` re-projects onto the far rail axis so the symptom is a
  stutter/reversal, not a derail. Take it with the rotated-binding live round.
- **Slopes at the seam**: `yWindow` stays 1; an ascending rail INTO the plane is (b)-supported
  for shape, but cart traversal of a seam slope is untested and the lane-snap y-math is not
  bridged for it. Deferred.
- **`NewMinecartBehavior` (MINECART_IMPROVEMENTS, off by default)** is only partially covered
  (the `getCurrentBlockPosOrRailBelow` wrap fires; its own resolution sites are not wrapped).
  If Mojang flips the flag default, (d) needs a NewMinecartBehavior pass.
- **Stalled-cart restart at the seam**: `isRedstoneConductor(pos.west()/east()/…)` probes in the
  powered-rail stall branch read the near level raw. A cart parked EXACTLY at a seam-boundary
  powered rail may not restart from a far-side conductor. Cosmetic, deferred.

## ★ (c) STEP 1 — REDSTONE SIGNAL ACROSS THE SEAM (landed 2026-07-27)

**What works, gate-verified** (`rsSignalLegCoincident` arms A+L, `rsSignalLegDisjoint` arm B —
run under `-PrsOnly` too; every arm outcome-asserted on the FAR world's `POWERED`/`LIT` block
state after the full vanilla cascade, coverage-asserted on the bridge counters, lever-aware in
every matrix direction):

- **Topology A (obsidian, COINCIDENT):** a golden-rail chain through the aperture powers the far
  side's own continuation rails and unpowers them again when the source drops; the deepest far
  rail's walk crosses the seam back to the source-side power source (`walkCrossed` coverage).
- **Topology B (boundary-phase, DISJOINT):** same, with NO mirror involved — the near seam-cell
  flip queues a cross-seam re-evaluation (`dispatchDelivered` coverage), and the far rail's own
  walk sees the near chain through the bridge.
- **The seam LAMP (arm L):** a lamp pair at the seam cell lights when a redstone block appears
  beside its FAR half and goes dark ~8-10 ticks after it is removed — the far-originated path:
  far half lights vanilla-locally → the AUTHORITY RULE reverts it (machine write at the mirror
  half) → D1 wakes the player half → its union read sees the far source → it lights itself →
  shape sync propagates. The authority rule is obeyed, never amended: the player half computes,
  the mirror half copies.

**The mechanism** (`SeamSignalContinuity` + 2 mixins, spec §2): (c) NEVER mirrors a block — it
bridges READS and forwards UPDATE DISPATCH, and each level's own vanilla logic re-derives its own
blocks. (1) **R-UNION**: `hasNeighborSignal` at a bound seam cell unions the far image's
neighborhood (hand-rolled with per-read chunk guards down through the conductor fan-out — never
loads a chunk). (2) **R-WALK**: `PoweredRailBlock.findPoweredRailSignal`'s step probes redirect
through `SeamShadowBridge.shadowFor` into the far level, re-entering VANILLA walk code there —
depth cap rides across dimensions, multi-portal chains re-enter the wrap, and the whole ≤8×3 far
envelope is residency-checked before redirecting. (3) **D1 DISPATCH**: a settled change at a seam
cell queues `neighborChanged(counterpart, block, null)` (exact vanilla default-path shape — 26.2
carries no fromPos and null Orientation everywhere), flushed at tick end with budget/dedupe/
retry-on-warm; the skip for the mirror's own echo is by IDENTITY of the written cell
(`beginMirrorWrite`/`endMirrorWrite` brackets in `SeamMirror`), NOT by the global `applying` flag
— the panel's BLOCKER: the global skip swallowed genuine cascade flips at OTHER seam cells inside
the mirror's inline far fan-out.

Levers: `disableSeamSignal` (master), `disableSeamSignalDispatch` (reads stay, far side goes
stale), probe `seamSignalProbe`. Note the WALK also dies under (b)'s `disableSeamShadow` (it
consumes the (b) primitive); the union and dispatch ride the (a) registry and stay live there —
the lamp arm proves that positively in the shadow-off matrix row.

### ★ THE LIVE ROUND (2026-07-27, same day) — real user failure, NOT YET REPRODUCED headlessly

The user's live report: on a `/portal`-built same-dim pair, signal STOPPED at the seam, STUCK
ON after the source was cut, and behaved half-dependently (which half the player placed, and
from which side, decided whether it worked); levers lagged near a second signal-carrying
portal. The armed probe log (fabric/runs/client, 2026-07-27 session): reads AND dispatch alive
(1,987 walk crossings, 661/661 dispatches queued/delivered, ZERO cold declines) but a sustained
write→revert→dispatch PING-PONG at the pair — the two halves persistently DISAGREED about
shouldPower (bursts of up to 6 dispatch rounds/second to alternating halves).

**Excluded by one-variable-at-a-time reproduction (all green, probe-gated legs kept in-tree):**
same-dim COINCIDENT mid-block pair with Y-offset (`rsSignalSameDimCoincidentRepro`); a rail
line THREADING a second nether portal's aperture (`rsSignalTwoSeamLineRepro`); the BI-FACED
four-entity cluster with deterministically-adversarial binding order (flipped twins spawned
first, coverage-asserted). A "crossing-preference" fix built on the cluster-order theory was
REFUTED by its own inversion gate — flipped twins SHARE the portal transform, so both bindings
answer along-axis queries identically; first-match was never wrong — and reverted same-day
(lever retired; see the RETIRED note in `AperturePassthroughLever`).

**★★ ROOT CAUSE FOUND AND FIXED — TWO DEFECTS (2026-07-28, the round after the exclusions
below):** the user's poke round produced the one-line answer — `[RS-MIRROR-AUTHORITY] LIVE —
suppressed …` at the very cell the dispatch had just delivered to — and the user's polarity
correction ("my PLAYER-PLACED source half was the dark one") split it in two:

1. **The authority rule swallows the power question.** (a)'s mirror-authority mixin cancels
ALL of `BaseRailBlock.neighborChanged` at provenance-marked cells — written when the only
re-derivations were shape and support-deletion, it also ate "should I be powered?": signal
entering a coincident pair from the MIRROR half's side died at the seam. **FIX: the
POWER-WAKE** — a notification at a suppressed rail queues its COUNTERPART through the
tick-end dispatch queue; nothing ever writes the mirrored cell, the authority rule stands.
⚠ The FIRST build evaluated the mirror half in place and LOOPED ~500k same-drain iterations
(flip → revert → `updateNeighborsAt(pos.below())` re-notifies the same rail) until vanilla's
chain cap broke it — and the suite PASSED, because nothing watched volume. Hence the RUNAWAY
CEILING in ARM R (walk-crossing delta < 10k). Lever `-PdisableSeamPowerWake`.

2. **Stale provenance — the user's actual polarity.** The break path cleared only the
COUNTERPART's mirror-created mark, never the broken cell's own; after a place-from-far →
break → re-place-from-near cycle the near cell stayed marked, and the authority rule
suppressed the PLAYER'S OWN re-placed rail — including the placement-time self-notification
carrying the power evaluation: dark at placement, deaf to neighbors, "only placing on the
DEST half works". Restored invariant: **a pair carries AT MOST ONE marked half** (a
doubly-marked pair is totally deaf — the power-wake alone only ping-pongs pokes between two
suppressed halves). **FIX: a break at a seam cell clears THAT CELL'S OWN mark**,
unconditionally on write source. Lever `-PdisableSeamBreakUnmark`.

Gates: ARM R (reverse entry) + ARM S (the user's ritual, with the provenance-clean assertion)
in `rsSignalLegCoincident`, each inverting under its lever. Poke traffic sane: 93 pokes /
walkCrossed=150 per RS suite. **Live user confirmation still pending.** The exclusion record
below is kept as the method's history.

**Still-open suspects, after the second exclusion round (HISTORICAL — resolved above):** the REAL command path is ALSO green
(`rsSignalCommandPairRepro` drives `/portal make_portal` + `/portal
complete_bi_way_bi_faced_portal` through the actual gametest player — look-derived orientation,
height-2 aperture, bi-faced cluster=2, north-south line — signal carried and released). What
remains: (1) ★ **CLIENT DISPLAY STALENESS AT 40km** — the user's far end is ~40,000 blocks away,
observed only THROUGH the portal window; if far-end block data/meshes go stale at that range,
the SERVER may have been right all along while the user watched a stale picture ("stuck
powered" = stale powered mesh; the actions that "fix" it — break/replace the seam rail,
through-portal placements — are exactly the ones that force a far-cell client resync). The
dispatch ping-pong bursts may simply be the user's own rapid lever toggling. **THE DECIDING
LIVE EXPERIMENT (zero code): reproduce "signal stops at the seam", touch nothing, teleport
through to the far end and look at the rails up close — powered on arrival ⇒ display
staleness (server right); dark at arm's length ⇒ real server failure, variable unknown.**
(2) the real client interaction layer (aim-half placement, prediction, real lever flips);
(3) true far-cold states. Next live round runs with `-PseamSignalProbe` +
`-PseamReconcileProbe` + `-PseamAimProbe` armed.

**Also settled by the round:** the user's "boundary-phase seams don't carry signal" is the
EXACT-ONLY decline working as decided — a wand/`/portal` boundary-phase pair has the source
plane at .5 and the dest flush, a non-integral translation = OFFSET seam (the user's own
2026-07-26 exact-only decision; the wand cannot build an aligned boundary-phase pair — the
gate's topology-B fixture does it programmatically). Queue it with the offset/fractional work.

### ⚠ PROVISIONAL DECISIONS TAKEN AT LANDING — NEED THE USER'S WORD (the (b) precedent)

| # | decision | rationale | where |
|---|---|---|---|
| 1 | **Signal is NOT a "machine write"** — (c) carries POWER through reads+dispatch and never mirrors a block; the far half's own evaluator writes are ordinary un-bracketed refinements the existing shape-sync/authority machinery already classifies. The `SeamWriteSource`×`SeamAlignment`×`SeamMirrorPolicy` seams are untouched. | the prompt's own parenthetical; keeps player-only intact | spec §0.1 |
| 2 | **DISJOINT seams DO carry signal** (the (b) traversal precedent; the phase gate still blocks mirroring there) | a wire flush at a boundary plane is the topology-B rail case | spec §0.2 |
| 3 | **Junction/curve switching from far signal is NOT shipped** (the panel showed it unreachable-and-sticky as designed; it is also a policy-adjacent geometry widening) | deferred to the D2 family with a wake-up rule | spec §6.8 |

### Deferred/residual (all recorded in spec §6, panel-verified)

Wire-to-wire decay (raw `getBlockState` in the evaluator — needs its own chokepoint); the
general `SignalGetter` interface-mixin route (feasible per bytecode gates, needs a smoke test +
worldgen guard); diode/comparator far-BLOCKSTATE visibility; detector-rail rail-graph dispatch;
D2 delivery-forwarding (conductor relays, no-flip cases, edge-carrying pulses); the two-portal
in-bracket cascade gate arm; independently-built pairs (converge via D1+union; diverge by design
under the dispatch attribution lever); cold-far disagreement flicker (bounded, converges on
warm); experimental-redstone Orientation remapping.

**⚠ FOR THE LIVE ROUND:** far-side crossing needs the FAR level's own bindings, which exist only
while the far portal entities TICK — the first gate run failed exactly there (OW forceloaded,
nether side never bound; the leg now forceloads the nether counterpart and poll-asserts its
binding). In live play this rides IP's destination chunk loading; **watch for one-way signal on
far portals whose chunks are loaded but not entity-ticking.**

## ★ (b) STEP 2 — RAILS ACROSS THE SEAM (landed 2026-07-27)

**What works, gate-verified in five configurations** (full canonical; full
`-PdisableAperturePassthrough`; rsOnly + each of `-PdisableSeamShadow`, `-PdisableSeamPhaseGate`,
`-PdisableSeamShapeSync` — every leg lever-aware, every inversion asserted):

- **Topology B (boundary-phase seam):** a rail laid at the near cell CONNECTS to the far side's own
  track — straight through (RS-RAIL-B B1: `EAST_WEST` whose only possible source is the far rail;
  `crossHits` coverage-asserted) and curves (B2: `NORTH_EAST` from a local lateral + the cross arm,
  far side's own shape never overridden). Laying rail toward an occupied far cell is ALLOWED (the
  phase gate keeps `mayPlace` out of the way).
- **Topology A (obsidian):** the seam rail resolves onto the nether continuation through the bridge
  and, after the second aperture rail rewrites it through vanilla `connectTo`, the mirrored half
  stays byte-identical (`nether(D) == ow(S).rotate(R)`) — the SHAPE SYNC path end-to-end. Stable
  across 40 idle ticks; write budgets asserted ZERO everywhere.

**The mechanism** (`common/.../passthrough/`): `SeamShadow` (local shadow coordinate frame; far
reads/writes only at the `getBlockState`/`setBlock` boundary; cold far chunk reads as AIR + retry
queue), `SeamShadowBridge.shadowFor` (the one entry point), `SeamRailContinuity` (budgets, counters,
reseed-on-bind, retries), `MixinRailStateSeam` (owner = LOCAL-FIRST R1′, proxy = shadow-framed;
the stamp re-checks locality — see the spec banner for why the spec's version was wrong),
`MixinBaseRailBlockSeamSlope` (slope support bridged, additive-only). Levers: `disableSeamShadow`
(master), `disableSeamRailWrite`, `disableSeamRailSlope`, `disableSeamRailReseed`, probe
`seamRailProbe`.

**The primitive's direction convention was FIXED on the way in** — `bindingAcross` matched
`srcFacing == dir`, right on obsidian clusters only via the flipped twin, inverted for any
single-binding cell; `continuationCell()` pointed the COINCIDENT crossing at the co-located
FALLBACK cell behind the far plane. Zero consumers existed, so nothing had shipped wrong. The
step-1 gate now pins the continuation DIRECTION against `portal.getContentDirection()` — the old
formula fails it. New surface: `SeamBinding.crossDir()`, `continuationToward(Direction)`,
`seamContinuous`; `findDestinationPortal` disambiguates bi-faced pairs by IP's own
`isReversePortal` dot test (`disableSeamReverseDisambig`).

### ✅ TWO USER DECISIONS — taken provisionally at landing, CONFIRMED BY THE USER 2026-07-27

**Sign-off obtained in the live round after the landing.** The user tested rails live ("WORKS,
rails connect through the portal!"), the probe log confirmed 131 genuine cross-seam neighbour
resolutions in both directions, both policies were put to the user explicitly, and both were
KEPT. They now stand on the same footing as player-only and exact-only. The same round settled a
third point: the powered-rail observation (mirrored half shows powered, no propagation past the
seam) is the DESIGNED boundary of (b) — `PoweredRailBlock.findPoweredRailSignal` walks raw
positions in one level and never touches `RailState`; power across the seam is (c), and the
powered-rail chain is its first concrete target. The table below is the original decision record:

| # | decision | why (b) needs it | lever restoring the old rule |
|---|---|---|---|
| 1 | **DISJOINT (boundary-phase) seams do not mirror** (`SeamMirror.isPhaseGated`, all four mirror paths). The spec §3.6 recommends it and quotes the user's own phrasing that topology B is unmirrored, but no explicit sign-off exists. Without it: whole-block duplicates, `mayPlace` denies joining the far track, and (b)'s far write mirror-backs. COINCIDENT (all obsidian) untouched. | reasons 1–3 in `SeamMirror.isPhaseGated`'s javadoc | `-PdisableSeamPhaseGate` |
| 2 | **SHAPE SYNC** — a same-block STATE refinement of a seam cell re-mirrors even un-bracketed, IF the counterpart already holds the same block. Vanilla `connectTo:205` rewrites neighbours inside the OTHER cell's placement with no player bracket; player-only declined the re-mirror and the pair's halves diverged (likely the residue of the user's "sometimes not curving"). Creation/removal keep player-only in full. This WIDENS the 2026-07-26 player-only decision. | RS-RAIL-A's cross-side invariant fails without it; inverts under the lever | `-PdisableSeamShapeSync` |

### Latent gate red found and fixed (worth knowing how)

The step-1 seam-map gate's "destination consistent with SeamMap" check predates exact-only and
demanded a destination from every arithmetically-mirrorable portal — but exact-only binds
policy-declined portals QUERY-ONLY (`destPos == null`). Test portal A's dest hangs a half-block off
in Y, so the check fails on it… and never had: **in the full suite the player is in the nether by
gate time and the spawn-area portals sit in UNLOADED chunks — `getEntitiesOfClass` never returned
them.** RS-only mode keeps the player at spawn and examined portal A for the first time ever.
A/B-attributed against a throwaway `eac7db0` worktree (old tip: gate PASS, 4 portals examined —
all obsidian; new run: portal A examined, latent red exposed). The gate now asserts the policy both
ways: a declined seam must be query-only, an admitted one must carry the SeamMap destination —
symmetric under `-PdisableSeamExactOnly`.

### The adversarial panel round (2026-07-27) — four confirmed defects, fixed before commit

An 18-agent panel (4 lenses, every finding adversarially verified) ran over the diff after the
first green matrix. Confirmed and FIXED:

1. **Shape sync forged mirror provenance onto the PLAYER's half** (3 lenses independently; major).
   The refinement path fell through to the unconditional `mirrorCreatedCells().add`, so a far-side
   `connectTo` rewrite syncing D→S marked the player's own rail mirror-created — a later frame
   break then DELETED it (violating the pinned break rule) and mirror authority suppressed its
   support pops. Fixed with the AUTHORITY RULE (`SeamMirror.onSeamCellChanged` note): a refinement
   never touches provenance; at the PLAYER half it propagates to a provenance-marked counterpart
   only; at the MIRROR half it REVERTS from the player half (`revertMirrorHalf`, counter
   `shapeReverted`) — the derived-state rule applied, machine-derived far shapes never overwrite
   the player's block.
2. **The shape-sync pair test was block-equality, not provenance** — two independently-built halves
   (bind-time reconciliation explicitly preserves those) could clobber each other. The propagate
   path now requires the destination to be provenance-marked.
3. **`-PdisableSeamRailWrite` misrouted proxy writes into the SOURCE world** as phantom rails
   behind the portal (the spec's own §3.2 carried the bug: `s == null || LEVER → op.call`). Writes
   are now DROPPED for proxies under the lever, and RS-RAIL-B gained a misroute canary (the local
   cell behind the plane must stay air — asserted in every configuration).
4. **The cold-far retry queue gated on the wrong chunk** (the owner's own, loaded by construction)
   → once-per-tick busy re-resolution per cold seam cell. Retry entries now carry `waitFor` (the
   far chunk that actually went cold), and out-of-build-height mappings are counted but never
   queued (permanent, not warmable).

Residuals confirmed-as-minor and DOCUMENTED, not fixed: a DETECTOR rail's far-side 3-way-junction
rewrite landing inside the mirror's `applying` window is swallowed and the pair diverges until the
next source-side change (plain rails unaffected); and under the authority rule a far-side player
cannot CURVE the shared slot from their side (the source side's resolution is the authority — the
far side joins by laying track the source side's bridge reads). Both are candidates for the (c)
design pass.

- **Unbind snapshot (spec §3.5(v), lens A B-9)** — `unbind` still enumerates current geometry, so a
  portal whose geometry changes before unbind leaks stale bindings until dispose. Pre-existing
  (a)-era gap; (b)'s writes through a stale binding are bounded to shape changes of EXISTING far
  rails. Take it with (c) if wire makes it hotter.
- **Cold-far-chunk leg (spec B14)** — the retry queue is implemented (`SeamRailContinuity`,
  `declinedCold`/`retriesServed` counters) but has no dedicated gate leg; portals hold their far
  side resident, so the path is hard to reach in the suite.
- **Offset (non-integral) seams stay fully declined** for rails exactly as for mirroring — same
  user decision, same future work.

## ★ THE SEAM CLIP (landed 2026-07-27, this session) — closes OPEN ITEM 1

**★★ USER DECISION 2026-07-27 (live round after the landing): DEFAULT OFF.** The user walked
around a portal with a seam block and the view-dependent cut read wrong to them: the far half is
absent from every out-of-window viewpoint on your side, and the visible half SWAPS the instant
the camera crosses the plane's lateral extension (arc-verified frame-by-frame,
`rsSeamClipArcEvidence`, commit `7c57241` — inherent to one clip plane per draw, NOT a defect).
Their chosen direction is the **FULL FRACTIONAL MODEL** (recon §0.9): each dimension holds a
genuine partial block — geometry, collision and state ending at the plane — which retires both
the invisible-solid far half AND the crossing pop. The clip stays in the tree as that model's
RENDERER (an arbitrary-fraction cut was the design requirement from day one). One-line
re-enable: `-PenableSeamClip=true` / flip `DISABLE_SEAM_CLIP`'s initializer. **Do not
"fix" the default back on; do not delete the machinery.**

**Full design + adversarial-panel record: `migration/SEAM_CLIP_DESIGN.md` (v2).** Read that file
before touching this feature; every choice below has a panel finding behind it.

**What shipped** (`com.warwa.seamlessportals.render.SeamClipRenderer` + two mixins):

1. **Mesh exclusion** — qualifying seam cells (COINCIDENT phase AND mirror-admitted
   `destPos != null` AND real non-placeholder MODEL block AND no block entity) read as AIR during
   section compile. Snapshot at `RenderRegionCache.createRegion` RETURN (main thread, full 3×3×3
   region bounds) onto a region duck; `RenderSectionRegion.getBlockState` HEAD answers AIR. The
   AIR report UN-CULLS neighbour faces — that is what makes the FRAMELESS side view correct
   (rays land on real faces instead of tunnelling through culled-face gaps).
2. **Dynamic draw** — those cells re-tessellate against the live level each pass
   (`tesselateBlock` → `putBlockBakedQuad`, camera-relative floats, BLOCK format) and draw via
   `PortalRenderTypes.drawMesh` with `RenderTypes.*MovingBlock()` — a first-in-tree combination,
   now pixel-proven. MAIN pass at `BEFORE_TRANSLUCENT_TERRAIN` (with the MANDATORY
   `PortalRendering.isRendering()` guard — the event fires inside the full-pipeline twin's nested
   render); DEST pass via ONE line in `SecondaryWorldRenderCore.renderDestWorld` after the 10.6
   opaque draw (⚠ HOT FILE of the is5-shadow session; the line assumes the CURRENT 10.5 inner-clip
   arming semantics, −ADJUSTMENT). Kept half = CAMERA side + 0.01 overlap (mirror of the inner
   clip's −0.01). NO hook in `renderDestWorldFullPipeline` — M4 + FullPipelineClipState defeat
   own-plane brackets there, and the feature self-gates OFF under sodium/iris anyway (no meshing
   hook in the compat layer).
3. **Lifecycle dirtying** — client-side bind/unbind queues covering sections + block-neighbour
   sections; POST_CLIENT_TICK flush direct-`compileAsync`s them (SameDimRemesh's coord-pinned
   RECIPE but SeamClip-OWN accounting — sharing its COMPILED set would have made the RS-DELIVERY
   arm-3 verdict un-failable, a panel finding).

**The gate** (`rsSeamClipGate`, runs in RS-only too): frameless EXACT same-dim portal, gold block
in the aperture edge cell, blue backdrop, camera east of the window edge so sight lines cross the
plane OUTSIDE the quad; crosshair aimed at world points; 9×9 patch sampled just below centre
(26.2 has no `Options.hideGui`); glowstone + night vision pin the lighting (fresh random seed per
run). Fix ON: far NOT-gold + near gold + `cellsDrawn/cellsExcluded > 0`. Lever: far GOLD
(defect reproduced) + counters 0. First run of each direction passed exactly so
(farGold 0.00→1.00 on the lever).

**Residuals** (all recorded in design §6): fluids/BE blocks never clip; sodium/iris self-gate;
translucent ordering; single-plane limit for unrelated cells through a window (bounded to an
uncut far half, never foreground junk); portal-not-rendered shows behind-content; 1-frame
transients around place/bind; moved-portal old cells recompile on natural dirtying only; large
filled apertures re-tessellate per frame (escape hatch documented); floor-portal coplanar dither
(pre-existing); crumbling/outline stay full-cube.

**The walk-around look (user live report, 2026-07-27, arc-verified same session):** walking
around a portal, the far half is absent from every out-of-window viewpoint on your side of the
plane, and the visible half SWAPS the moment the camera crosses the plane's lateral extension
(bi-faced: half↔half, each side's window supplying the complement; single-faced from behind:
whole cube, the no-window fallback). Verified frame-by-frame by `rsSeamClipArcEvidence`
(screenshots lever, 8 arc shots, asserts nothing) — the pop is the geometry of view-dependent
camera-side keep, not a defect; it is unavoidable with one clip plane per draw (any fixed-half
choice breaks the behind view). **User judgment: DECLINED — hence the ★★ default-OFF decision
above; the fractional model is the chosen future.**

**For (c) redstone:** the clip predicate deliberately equals the mirror's admission predicate.
If (c) widens what mirrors (e.g. machine writes someday), the clip follows automatically through
`SeamRegistry` — no clip-side edit needed.

## ★ OPEN ITEMS, in the order they were raised

### 1. CLIP A MIRRORED BLOCK AT THE SEAM — ✅ LANDED 2026-07-27, see the ★ SEAM CLIP section.
The design analysis below is kept as the record the implementation started from; where they
differ, the shipped code + `SEAM_CLIP_DESIGN.md` override (notably: seam-CELL granularity, not
seam-SECTION; exclusion-by-AIR-report, not a tesselateBlock skip; and the frameless side view is
the FEATURE, not an artifact).

### (original OPEN ITEM 1 record follows)

Today a mirrored aperture block renders as a **whole cube** in the source world. Nothing clips source
terrain at the plane — through the portal it looks right only because the stencil+depth **overwrite**
paints the destination over that region. Stand to the side and the far half is simply drawn.
Wanted: the source copy is cut at the plane, and everything beyond comes from the mirrored copy.

**No per-block half-models are needed, and they are the wrong answer anyway** — an offset seam needs
an arbitrary cut fraction, not a fixed half, so real-time clipping is what generalises to the offset
work; fixed half-models do not. Two routes, adversarially verified:

| route | correct? | cost |
|---|---|---|
| **Compile-time** — clamp quads in `SectionCompiler.compile` → `ModelBlockRenderer.tesselateBlock` (geometry is still unpacked floats in block-local `[0,1]`) | ✘ **Permanently wrong for SAME-DIM portals**: one dimension = one `ViewArea`, whose meshes are drawn in the SAME frame by the main camera and the portal camera on opposite sides of the plane. Whichever half is baked, one view is wrong. Also view-dependent generally (an obsidian frame has portals on both sides), and **silently no-ops under Sodium**. | cheap |
| **Draw-time** — `gl_ClipDistance` via the existing `seamlessportals_ClipPlane` | ✔ correct in every view, free view-dependence, **already has a Sodium path** in this tree | bigger |

**★ The intended mechanism is already written and dead.**
`FrontClipping.setupOuterClipping` — javadoc: *"clips source-dim geometry PAST the portal plane …
used on the main camera pass"* — **is never invoked on the live path.** The main pass explicitly
resets the plane to keep-all at `renderLevel` HEAD, and the only surviving main-pass clip is the
per-ENTITY bracket. Start there.

The obstacle for draw-time: the plane is GLOBAL per draw, so seam sections need their own bracketed
draw or a per-vertex "clippable" flag. Recommended route regardless — compile-time cannot be made
correct on same-dim portals, which is where the user tests.

⚠ **Fluids and block entities will not clip either way** — they bypass the block-quad path
(`SectionCompiler` routes fluids to `FluidRenderer.Output` and only *collects* block entities).
⚠ Also note the recorded gotcha: an oblique clip plane must not be coplanar with the geometry it
cuts, which is exactly what a cut-at-the-plane face is. Draw-time clipping needs the existing
`ADJUSTMENT` epsilon; compile-time sidesteps it but loses on the points above.

### 2. BIND-TIME RECONCILIATION MIRRORS NON-PLAYER BLOCKS (user-found, live)

`/portal complete_bi_way_bi_faced_portal` over natural terrain now mirrors those blocks into the
source world; before the player-only change it did not. Cause: the policy gates the live write
DRIVER, but not `SeamMirror.reconcileApertureOnBind`, which carries blocks already sitting in the
aperture when a portal binds — at bind time there IS no write source to consult. Options: drop
bind-time reconciliation (loses §0.4's "relight over a surviving rail"); persist source-side
provenance; or reconcile only cells already in `mirrorCreatedCells`. **Needs a design call.**

### 3. SOLID BLOCKS SHOULD PLACE INTO A WATER-OCCUPIED FAR HALF (polish)

Refuse-on-conflict treats water as occupied. Vanilla lets a solid block displace water, so the seam
should too. `SeamMirror.destinationIsFree` accepts only air/placeholder today. Note the user's
finding that the far half is normally **unoccupiable**, so water was the only way to construct the
conflict case at all.

### 4. PLACED BLOCK FLASHES XRAY/TRANSPARENT FOR A SPLIT SECOND (polish)

Appeared after same-frame mirroring. Suspect a section rebuild racing the prediction — neighbour
state momentarily stale, so face culling is computed wrong for one frame. Watch the PLACED block,
not the mirrored one.

### 5. THE SUITE IS SLOW — ✅ RESOLVED 2026-07-27: `-PrsOnly`

The RS-only lever is implemented (`AperturePassthroughLever.RS_ONLY`, rows in both build.gradle
blocks). Skips legs 1, 2, 3, 4, 7 and leg 5's world close-and-reopen; keeps portal staging, 6a/6b
(the seam gate's involution coverage), every RS gate and the rail legs; still ends in the same
`ALL LEGS PASS` plus a loud not-a-full-suite banner. ⚠ Side effect worth remembering: RS-only keeps
the player at spawn, so the seam-map gate examines the spawn-area portals the full suite leaves
unloaded — it has MORE gate coverage there, not less (this is what exposed the latent query-only
red). The full matrix remains mandatory before a commit.

## GATE MATRIX (as of 2026-07-27)

| configuration | proves |
|---|---|
| `-PapertureTeardownTest -PseamMirrorProbe` | canonical (a)+(b) |
| `-PapertureTeardownTest -PdisableAperturePassthrough` | the master lever restores stock IP |
| `-PapertureTeardownTest -PrsOnly -PdisableSeamShadow` | (b) inversion — both rail legs revert to vanilla shapes |
| `-PapertureTeardownTest -PrsOnly -PdisableSeamPhaseGate` | phase-gate inversion — the veto refuses the far-occupied placement again |
| `-PapertureTeardownTest -PrsOnly -PdisableSeamShapeSync` | shape-sync inversion — the pair's halves diverge after a neighbour rewrite |
| `-PapertureTeardownTest -PseamDeliveryTest` | same-dim remesh, near AND far arms |
| `… -PseamDeliveryTest -PdisableSameDimRemesh` | remesh inversion (both arms) |
| `… -PdisableSeamPlayerOnly` | player-only inversion |
| `… -PdisableSeamExactOnly` | exact-only inversion |
| `… -PdisableSeamPrediction` | same-frame inversion |
| `-PapertureTeardownTest -PseamMirrorProbe -PrsOnly -PenableSeamClip` | seam-clip ON — the cut renders (farGold 0.00), mechanism live. Default runs assert the OFF branch (whole cube, mechanism idle) |
| `-PapertureTeardownTest -PrsOnly -PdisableSeamSignal` | (c) master inversion — both signal legs reproduce "propagation stops at the seam"; the mirrored half still shows powered (the user's (b)-era baseline) |
| `-PapertureTeardownTest -PrsOnly -PdisableSeamSignalDispatch` | (c) dispatch inversion — far side can SEE power but is never TOLD to look: arm B far rails stale, arm L both lamp halves dark (the authority revert holds) |
| `-PapertureTeardownTest -PrsOnly -PdisableSeamCartRail` | (d) master inversion — RS-CART-B reproduces the measured halt (cart off-rail, epsilon below the far rail line, stopped); RS-CART-A still passes (COINCIDENT works stock) |
| `-PapertureTeardownTest -PrsOnly -PdisableSeamCartStraddle` | (d) occupancy inversion — RS-CART-C reproduces the HOVER (cart resting one cell clear of the aperture rides the far world's rail: dropped 0.038 vs 1.100, bridgeReads 249 vs 5) |
| `-PapertureTeardownTest -PrsOnly -PdisableSeamCartCrossOnly` | (d) direction lever — all cart arms still pass; the straddle test independently covers RS-CART-C's bi-faced fixture, so this row proves the lever is wired, not that the guard is exercised (see the ⚠ above) |
| `… -PrsOnly -PseamCartProbe` | arms RS-CART-D (cross-dim ridden) and RS-CART-E (SAME-DIM ridden, the user's own topology, which ASSERTS the arrival height) plus every SAMPLE/EVT/COME-OFF-TRACK/CARRY-TERMS/bridge-hit line. Both move the real player and restore them in a `finally` |
| `… -PrsOnly -PseamCartProbe -PdisableSeamVehicleAttach` | ridden-carry inversion — RS-CART-E reproduces the arrival hop (the vehicle's own passenger-attachment offset above riding height) |

Iteration tip: add `-PrsOnly` to any RS-focused configuration (~3.5 min instead of ~6+). The five
configurations run green on 2026-07-27 before commit were: rows 1–5 of this table (rows 1–2 as full
suites).

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

### ✅ CLOSED — BOTH DEFECTS FIXED AND USER-CONFIRMED LIVE (2026-07-26)

Same-dimension portals now update live at any distance. **User's words: "WORKS, SAME DIM DISTANCE
PORTALS ARE AUTO UPDATING!"** Fix lives in `com.warwa.seamlessportals.render.SameDimRemesh`, lever
`-PdisableSameDimRemesh`, four gate configurations green with both arms inverting.

**Defect A** (near/occluded — the dirty mark is never consumed): per-tick sweep of sections behind
same-dim portals; anything still dirty at end of tick is by definition unconsumed, so it is compiled
directly via `RenderSection.compileAsync`.

**Defect B** (far — the mark is refused, and `getRenderSection` cannot even find the section): fall
back to `provideBuiltChunkByChunkPos`. **This is IP's own answer** — IP's `ImmPtlViewArea.setDirty`
is an override of vanilla's, and its whole body routes through that accessor, so every dirty mark at
any distance hit the unbounded coord-pinned store. 26.2 moved dirty tracking out of `ViewArea` into
the bounded `SectionUpdateTracker`, leaving that override nothing to attach to: the port kept
unbounded LOOKUP and lost unbounded DIRTYING. Restored at the one place 26.2 leaves available.

**Read the section below anyway.** It is the record of how four attempts went wrong, and every one
failed in a way a future change can repeat.

---

## ★ THERE ARE **TWO** DEFECTS, AND THE PRIMARY ONE IS VISIBILITY, NOT DISTANCE

**Corrected 2026-07-26 by a live round, after the first diagnosis below got the subsystem right and
the gate wrong.** Both defects are confirmed live and fixing either alone leaves the other.

| # | defect | who it bites | stage that fails |
|---|---|---|---|
| **A** | **the dirty mark is never CONSUMED** — `LevelExtractor.java:152` only walks `levelRenderer.visibleSections()`, so a section visible ONLY through a same-dim portal is flagged and then ignored forever | **the reported bug**; any distance, including a portal whose ends are 60 blocks apart | **7** (marked, never rebuilt) |
| **B** | **the dirty mark is DISCARDED** — `SectionUpdateTracker.setDirty` drops a section outside its render-distance window | only past render distance (±16 chunks at rd 16) | **6** (never marked) |

**The evidence that separates them is a same-position A/B from live play** — the strongest single
piece of evidence in this engagement, and it arrived by accident rather than by design:

```
trace #1  (11,108,100)  6 ACCEPTED   7 SCHEDULED     -> DELIVERED AND REBUILT
trace #3  (11,108,100)  6 ACCEPTED   7 NOT-REACHED   -> MARKED DIRTY BUT NEVER REBUILT
```
**Same section, 12 seconds apart, opposite outcomes.** Distance, window, dimension, phase and mirror
logic are all identical and therefore all excluded. The only difference is where the player stood
and looked. That is `visibleSections()` and nothing else.

Defect B is real too and was measured separately (`(10,108,999)`, ~900 blocks, `6 DROPPED`), but it is
NOT what the user has been reporting. **The first write-up below claimed B was the bug. It was not.**

⚠ **Do not read a `7 SCHEDULED` on an out-of-window cell as "B is harmless".** `RotatingSectionStorage`
creates its entries already dirty, so a section ENTERING the window is rebuilt for being new; that
rebuild is incidental and says nothing about the write. Traces #4/#6 versus #8 — same far cell, two
`SCHEDULED` and one `NOT-REACHED` — are that coin-flip, not a working path.

✅ Stage 7's section granularity is sound, not the stage-4 mistake repeated: a rebuild necessarily
recompiles the whole section, so if it was scheduled the cell's new state IS in the resulting mesh,
whatever triggered it.

### ✅ DEFECT A IS FIXED — `com.warwa.seamlessportals.render.SameDimRemesh`

**Landed, gated in both directions, four gate configurations green.** Lever
`-Dseamlessportals.disableSameDimRemesh=true` (`-PdisableSameDimRemesh`), default ON.

*The mechanism.* A section still flagged dirty at the END OF A TICK is precisely one no extract
consumed — extract runs at least once per tick and clears everything it takes. So "still dirty now"
IS the operational test for defect A, needing no bookkeeping. A per-tick sweep, bounded to sections
behind same-dimension portals, schedules those directly: a `SectionUpdateRenderState` appended to the
main `LevelRenderState`, drained by the main frame's own `compileSections` — exactly how vanilla
hands sections to itself. **No IP-core render file is edited**, nothing repositions a camera, touches
a `ViewArea` or re-flips a delta window (the three things `SecondaryWorldRenderCore` skipped the
same-dim extract to avoid, and which are all still avoided).

*Proof, not hope.* `RS-DELIVERY-TEST` arm 3 asserts **the specific section holding the written cell**
was scheduled, and inverts under the lever:
```
fix ON : SAME-DIM REMESH PASS — the section holding BlockPos{2659,40,2600} was scheduled
fix OFF: SAME-DIM REMESH INVERSION PASS — no rebuild scheduled ... the defect reproduced on demand
```

⚠ **Two things this cost, recorded because both are recurring traps.**
1. The FIRST implementation queued every `setDirty` near a portal and drained it. Chunk loading
   dirties whole regions, so the queue saturated (`droppedOverCap=13784`) and **dropped the very
   write under test** — while the gate PASSED, because it only asserted "some rebuilds happened".
   The count assertion was replaced with `didScheduleSectionAt(the written cell)`, which is what
   caught it. *A count is not evidence about a particular cell.*
2. The FIRST fixture put the destination 600 blocks away and asserted there. That is defect B, not A,
   and it is unfixable by this route — see below. The fixture is now NEAR (60 blocks, in window and
   in the ViewArea grid) and **OCCLUDED** (a sealed chamber 50 blocks underground), so the main
   camera's occlusion BFS cannot reach it. Both properties are load-bearing.

### ❌ DEFECT B IS NOT FIXED, and not by this route

At 600 blocks the sweep and the refusal queue both find nothing to schedule:
`ViewArea.getRenderSection` returns **null**, because a same-dimension pass never calls
`repositionCamera` on the ViewArea (`SecondaryWorldRenderCore:663`, `if (!sharedState && viewArea != null)`)
and so no columns are ever created out there. Measured: `scheduled == sweptDirty` with
`refusedSeen=2842` — every refused entry dequeued and scheduled nothing.

There is no mesh to refresh because there is no render section, which also means **far same-dim
portal windows likely draw no vanilla terrain at all** — consistent with the render core's own note
that a same-dim plain-row window shows none. Fixing B therefore means giving same-dim passes ViewArea
columns at the destination, i.e. the reposition the core deliberately avoids. **That is a separate
design problem, not a follow-up patch.** Its symptom is distinct from A's and only appears past
render distance.

---

### The measured answer for defect B (the first diagnosis — subsystem right, gate wrong)

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

**The asymmetry to close, and it is a HALF-FINISHED EXISTING MIGRATION, not a new problem.**
`ImmPtlViewArea` (`qouteall/imm_ptl/core/render/ImmPtlViewArea.java`) already exists precisely
because vanilla's bounded `RotatingSectionStorage` could not hold far same-dim sections — its own
javadoc says it "owns an UNBOUNDED coord-pinned store (`columnMap` + `presets`) instead of vanilla's
fixed `RotatingSectionStorage`", and it overrides `getRenderSectionAt` / `getRenderSection` to read
it. That work retired the >71-chunk collision bug (leg 7).

But that same javadoc records the other half of 26.2's split and it was never followed through:
*"dirty tracking externalised to `SectionUpdateTracker`"*. **`SectionUpdateTracker` still wraps a
plain bounded `RotatingSectionStorage`.** So the render sections exist out there and can be drawn —
they simply can never be told they are stale.

⇒ **The fix is the mirror of a change this codebase has already made once.** Either give the tracker
an unbounded store the way `ImmPtlViewArea` gave the view area one, or bypass it for sections the
window does not cover. Prefer whichever keeps ONE writer: `ClientWorldLoader` hands trackers between
per-dim extractors on promote/demote (`:996-1030`), and a second dirty path would have to survive
that handoff.

**Shape of the fix** — the block-era precedent is `RemoteBlockUpdater.java:86-124`: do not go through
the tracker, take the `RenderSection` straight out of the (unbounded) `ViewArea` and schedule its
compile. Note `extractor.setSectionDirtyWithNeighbors` is NOT enough — it routes through the same
window and is dropped identically. That block-era code is dead under entity portals and needs its
entity-portal equivalent; `LevelRendererAccessorMixin.seamlessportals$getViewArea` and
`ViewAreaInvokerMixin` already exist.

Drive it from the CLIENT's block-update application (where stage 5 lands), not from the server.

### The instruments, and one warning about them

- `SeamDeliveryProbe` + 6 mixins, `-PseamDeliveryProbe=true` (default OFF). **Seven** stages, retired
  on a timer and printed in full so a stage that never ran says `NOT-REACHED`. Stage 7 hooks
  `SectionDirtyState.setNotDirty`, whose ONLY call site repo-wide is the scheduling branch at
  `LevelExtractor.java:167` — so it fires if and only if a rebuild was scheduled, which makes
  silence there evidence rather than absence of evidence. **Stage 6 alone is not enough and reading
  it as "delivered" is how the first write-up went wrong.**
- ⚠ **Live runs never print the probe's own coverage line.** `SeamDeliveryProbe.counters()` has one
  call site repo-wide (`CrossingSmoke.java`, gametest only), so `droppedOverCap` and the
  `ZERO WRITES TRACED` guard are invisible under `runClient`. Live tracing is capped at 64 concurrent
  traces retiring over 40 ticks (~32 writes/s) and drops SILENTLY above that. Hand placement is far
  below it, but wire `counters()` into `SERVER_STOPPING` before trusting a heavy live run.
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

- **★ VERIFY MIXIN WRAP TARGETS AGAINST THE BYTECODE, NOT THE DECOMPILE.** `javap -c -p -classpath
  %USERPROFILE%\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar <class>`
  before first launch. Two drifts caught this session: an unqualified static call from a subclass
  emits the ENCLOSING class as owner (`BaseRailBlock.canSupportRigidBlock`, not `Block.` — the (b)
  spec had it wrong), and `RailState.getRail` has FOUR `areturn`s where the source shows three (a
  ternary). `require`/`allow` made both LOAD-TIME failures instead of silent no-weaves — that
  discipline paid for itself twice in one file.
- **A GATE ONLY COVERS THE ENTITIES THE RUN KEEPS LOADED.** `getEntitiesOfClass` silently skips
  unloaded chunks: the full suite's seam-map gate never examined the spawn-area test portals because
  leg 4 had moved the player to the nether by then — a latent red sat there from 072ba4d until the
  RS-only mode (player stays at spawn) examined them. When a scan-based gate passes, ask what the
  scan could NOT see; forceload the fixtures a gate is supposed to judge.
- **`-P` property names must not start with lowercase letters that read as part of the flag** —
  `-ProsOnly=true` sets property `rosOnly`, silently. It cost one full misattributed run. Use
  `-PrsOnly=true` and check the leg banner actually says RS-ONLY MODE.
- **26.2 has no `Options.hideGui`** — the field is gone (only `ScreenEffectRenderer` carries the
  name as a parameter). A pixel-sampling gate must sample AROUND the crosshair (the seam-clip
  gate samples `max(20, h/24)` px below centre) rather than hiding the GUI.
- **A gametest world is a FRESH RANDOM SEED every run.** Anything colour- or light-sensitive must
  pin its own lighting (the seam-clip gate stages glowstone + night vision) or it flakes per-seed.
- **`ClientGameTestContext` commands run in the OVERWORLD context, and in the full suite the
  player is in the NETHER by the RS legs** — prefix world-touching commands with
  `execute in minecraft:overworld run`, tp the player explicitly, ASSERT the arrival dimension,
  and restore their whereabouts in the `finally` (the seam-clip gate's recipe).

- **★ ASSERT THE OUTCOME THE USER CAN SEE, NOT THE REQUEST YOUR CODE ISSUED.** Three gates in a row
  passed on a fix that did nothing:
  1. *"some rebuilds were scheduled"* — passed while the queue had saturated and **dropped the very
     write under test**;
  2. *"THIS section was scheduled"* — passed while the scheduling was appended to a list the next
     frame's `reset()` cleared **unread**, i.e. structurally inert;
  3. *"THIS section's mesh was REPLACED"* — the first that could fail for the reason the user saw.
  Each sat one step short of reality. Find the last object the engine mutates before the pixels, and
  hook that. **Also: a fix that logs only on failure makes "did nothing" and "never ran"
  indistinguishable in a live log** — that cost a whole extra round.
- **★ GATES MUST BE LEVER-AWARE.** When player-only landed, every RS gate wrote with `/setblock` and
  went red — correctly. But a gate whose verdict does not INVERT under its own disable lever cannot
  tell "the fix works" from "the defect never existed here". Two gates had to be taught both
  directions on the same run.
- **★ A FIXTURE THAT FAILS FOR THE WRONG REASON IS WORSE THAN ONE THAT PASSES.** A test destination
  moved 60→600 blocks did fail — reproducing a *different* defect, which was then written up as the
  answer. Change one variable, not two.
- **The client and the integrated server are DIFFERENT THREADS.** `SeamWriteContext` began as a
  plain static documented "server-thread confined" — true until client brackets were added. Now a
  `ThreadLocal`. Any new shared static in this feature must assume both.
- **`git add -A` sweeps in the untracked build scaffolding.** Done by accident on 2026-07-26,
  reverted in `3f29e08`. Use explicit file lists.

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
  a 60-block destination and failed with a 600-block one. "The test passes" was true and meaningless.
  When a fixture is built to reproduce a reported failure and does not, suspect the fixture before
  concluding the report was wrong.
- **★ AND THE CONVERSE, WHICH COST MORE: A FIXTURE THAT FAILS FOR THE WRONG REASON.** Moving that
  destination to 600 blocks DID reproduce a failure — a real one, defect B — and it was written up as
  the answer. It was not the user's bug at all: theirs (defect A) fires at 60 blocks, and the 600-block
  fixture had been silently exercising a second, rarer defect that happens to present identically.
  **A reproduction that fails is not thereby a reproduction of the reported failure.** The thing that
  caught it was the user playing normally and the probe recording the SAME CELL succeeding and then
  failing — an A/B no designed fixture had produced. Prefer a discriminator that holds everything
  constant but one variable; a fixture that changes distance AND visibility together cannot separate them.
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
