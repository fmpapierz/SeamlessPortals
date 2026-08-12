# Continuation prompt — paste into a fresh session

```
Read migration/SEAM_SWEEP_HANDOFF.md in the worktree
E:\Immersive Portals - Copy\.claude\worktrees\particle-seam-regression-c2f79c
(branch claude/particle-seam-regression-c2f79c, tip 09775b8) BEFORE doing anything else — it is
the current-state map for the seam full-functionality sweep. Also read the memory entries "Seam
regression sweep 2026-08-10", "S20 env gotchas", "Symmetric evaluation", and "Contract
restatement". Prior state maps: migration/SEAM_FUNCTIONALITY_HANDOFF.md (particle-arc closure)
and migration/PARTICLE_SEAM_HANDOFF.md (saga) — do not re-derive their history.

STATE: five fixes landed and are green + live-confirmed (F2 dest outline flicker, F3 full wire
continuity + the 42-second-stall revert loop, F7 margin-teleport removal, F1-round-1 dest destroy
burst, F8 half-scoped seam redstone). TWO fixes are written, compiling, and UNCOMMITTED because
the suite is RED on one new gate arm they add: F1 round 2 (destroy crumbs never teleport via any
branch — believed correct, the red run's own particle legs show consumed=64) and F4 (side-table
fragments get a real power lifecycle). Five modified files are listed in handoff §0 — do NOT
stash, checkout, or reset them.

TASK 1 — GET THE SUITE GREEN, then commit F1-r2 + F4.
The failing gate is ARM T in rsWireLegCoincident: "RS-WIRE F4: the second object's circuit
(SB→DA) never carried", with secondaryRefreshes=1 in the dump — the refresh runs but returns
early, so the fragment's POWER is never written. 26 legs passed before it. Handoff §4b ranks
three hypotheses; #1 is that the ARM's own fragmentHalfAtB derivation is wrong (a FIXTURE bug —
it derives the dest fragment's half from the SOURCE binding's rotated step, while
refreshSecondary matches against the DEST cell's own binding facing). Do not guess: add a
probe-gated one-line dump inside refreshSecondary naming the early-return reason and the resolved
binding's srcFacing, run with -PseamSignalProbe=true, and let the log say which branch returns.
Two earlier builds of this fix were each corrected by exactly one honest log line; the first one
ran 2 million refreshes before its ceiling caught it.

TASK 2 — F5 + F6, the user's next item (largest remaining piece). Full fractional entity
crossing: plane-exact clip plus a counterpart while straddling, ridden path to the same bar in
the same round, and check whether a rider (mob/player) leaks too; plus smoothness for ALL
entities through ALL portals — no snap, no interpolation kill, no velocity lurch, correctly
clipped throughout. Handoff §5 carries the verified mechanisms for both (whole-entity teleport at
eye-crossing, the ~0.44-block arrival protrusion, IP's tick-cadence portalCollisions bookkeeping
gap, the END_SERVER_TICK overshoot, the arrival rewind, the interpolation-kill, and the ×2
slow-cart velocity kludge) so you do not need to re-derive them. FIRST STEP: one lag-free retest
with -PseamCartProbe armed — the original sighting had no cart-probe lines, so the crossing could
not be typed empty-vs-ridden, and part of that session's hitch was the dust stall since fixed.

Then TASK 3 — the doc debt in handoff §6 (the ruling-7c TODO entry).

HARD RULES: evidence before fixes; suite green (ALL LEGS PASS in that run's own log plus the full
leg sequence — never exit code alone, vacuous exit-0 passes exist) before any commit; one
live-verify round per landing; javap the deobf jar before touching any vanilla signature; check
for existing wraps before adding redirects; @Unique mixin field initialisers must make no
cross-class static calls; every new mechanism gets a VOLUME ceiling in its gate, not just a
correctness assert; never gradlew --stop; kill only processes whose command line contains THIS
worktree's path. Warn the user before launching the suite — a test window opens on their desktop
and must not be closed; expect the launch to queue if they have a client open.
```
