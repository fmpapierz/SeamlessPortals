# Continuation prompt — paste into a fresh session

```
Read migration/SEAM_SWEEP_HANDOFF.md in the worktree
E:\Immersive Portals - Copy\.claude\worktrees\particle-seam-regression-c2f79c
(branch claude/particle-seam-regression-c2f79c, tip b58000c) BEFORE doing anything else — it is
the current-state map for the seam full-functionality sweep. Also read the memory entries "Seam
regression sweep 2026-08-10", "S20 env gotchas", "Symmetric evaluation", and "Contract
restatement". Prior state maps: migration/SEAM_FUNCTIONALITY_HANDOFF.md (particle-arc closure)
and migration/PARTICLE_SEAM_HANDOFF.md (saga) — do not re-derive their history.

STATE: ALL SEVEN sweep fixes are landed and suite-green at b58000c (ALL LEGS PASS, 2026-08-11
20:16 run). Tree clean bar the untracked wrapper. The ARM T saga is resolved — handoff §4 is the
post-mortem (the seam-axis half FLIP + the dropped {pos} in updateNeighborsAt fan; all three
ranked hypotheses were wrong, the probe named the truth in one line). STILL OWED: one live-verify
round for F1 (both rounds — break a seam block from each side, the burst must play to completion
at BOTH ends at normal speed) and F4 (stage a two-object cell, power each circuit separately and
together — SA→DB and SB→DA simultaneously).

TASK 1 — the F1+F4 live-verify round with the user, then record their verdict.

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
Client command:
.\gradlew.bat :fabric:runClient -PseamFractionalProbe=true -PseamMapProbe=true
  -PseamSignalProbe=true -PseamCartProbe=true

HARD RULES: evidence before fixes; suite green (ALL LEGS PASS in that run's own log plus the full
leg sequence — never exit code alone, vacuous exit-0 passes exist) before any commit; one
live-verify round per landing; javap the deobf jar before touching any vanilla signature; check
for existing wraps before adding redirects; @Unique mixin field initialisers must make no
cross-class static calls; every new mechanism gets a VOLUME ceiling in its gate, not just a
correctness assert; never gradlew --stop; kill only processes whose command line contains THIS
worktree's path. Warn the user before launching the suite — a test window opens on their desktop
and must not be closed; expect the launch to queue if they have a client open.
```
