# CART RENDER + RIDE — HANDOFF (opened 2026-07-28, after (d) landed)

**Branch `redstone/passthrough`**, worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`. Tree clean at `afaff9d`,
matrix green, everything pushed. This file covers ONE new engagement with TWO user-reported
defects. (d) itself is complete and user-confirmed — see `REDSTONE_NEXT_SESSION.md`.

---

## ★ THE TWO DEFECTS, AND WHY THE PAIRING IS THE BIGGEST CLUE

Both are the user's live observations, 2026-07-28, after (d) landed. **They invert across the two
portal topologies**, which means neither is "minecarts are broken" — each is a topology-specific
path failing:

| | SAME-DIM portal | CROSS-DIM portal |
|---|---|---|
| **empty cart, rendering in the window** | ✘ **cart DISAPPEARS as it crosses the seam** | ✔ renders properly |
| **riding a cart through** | ✔ works | ✘ **breaks on teleport** — forced dismount, or the player spazzes in place (background sometimes correct terrain, sometimes white/blank) |

So: the rendering defect is in the SAME-DIM (`sharedState`) path, and the riding defect is in the
CROSS-DIM path. Those are different subsystems with different owners, and they should be worked as
two independent investigations — do not look for one root cause.

⚠ **NEITHER IS IN A PROBE LOG YET.** Both live rounds this session (`%TEMP%\rscart_live.log`,
`rscart_live2.log`) contain **zero cross-dim carries** and **zero dimension changes** — the user's
cross-dim ridden test is not in the captured logs, and no render diagnostics were armed for the
disappearing cart. **First job for each defect is to reproduce it with instruments on**, per the
house rule that has decided every question in this engagement.

---

## ⚠ DO THIS FIRST — A ONE-COMMAND A/B THAT COULD IMPLICATE THIS SESSION'S OWN CHANGE

This session changed SHARED vehicle-crossing machinery (user-authorised):
`McHelper.getVehicleOffsetFromPassenger` now returns the true two-term inverse of vanilla's rider
placement. **That helper is called on the CLIENT crossing path too**
(`ClientTeleportationManager:552`), not just the server. The ridden cross-dim defect could
therefore be pre-existing OR introduced here, and nothing in the tree distinguishes them yet.

```bash
.\gradlew.bat :fabric:runClient -PseamCartProbe=true -PdisableSeamVehicleAttach=true
```

Ride a cart through a CROSS-DIM portal under that lever (it restores the old one-term offset).
- Still breaks ⇒ pre-existing, and the (d) change is exonerated.
- Behaves ⇒ the change is implicated and the fix must account for the client path.

Do this before designing anything. It costs one launch and it splits the search space in half.

---

## DEFECT A — SAME-DIM: THE CART DISAPPEARS IN THE PORTAL WINDOW

### The strong hypothesis (evidence-backed, NOT yet verified — verify before building)

**There is a documented, previously-fixed defect in this tree with EXACTLY this symptom, on the
other topology.** Read `common/src/main/java/com/warwa/seamlessportals/mixin/client/LevelRendererEntityVisibilityMixin.java`
in full — its javadoc is the whole argument:

- `LevelExtractor.extractVisibleEntities` keeps an entity only if
  `LevelRenderer.isSectionCompiledAndVisible(pos)`.
- That returns `renderSection.getVisibility(now) >= 0.3F`, where
  `getVisibility = (now − uploadedTime) / fadeDuration` — a cosmetic fade measured from when the
  section's mesh was last UPLOADED.
- A portal pass compiles/uploads its sections on demand, so `uploadedTime` is always "just now" →
  visibility < 0.3 → **every entity culled, while TERRAIN still draws** (the terrain path never
  consults `getVisibility`). Proven at the time by
  `DIAG-RENDER destLevel.entities=28 extracted.entityRenderStates=0`.

That is the user's symptom precisely: terrain fine, cart invisible, and only in the region the
portal window shows.

**Why it would still bite same-dim.** The mixin bypasses the fade gate only when
`PortalContextSwitch.isRenderingPortal || SecondaryWorldRenderCore.isDestExtracting`. And
`isDestExtracting` is set in the **cross-dim branch only** —
`SecondaryWorldRenderCore:727` gates the dest extract on `!sharedState`, setting the flag at
`:759`. Same-dim entities go through a *different* path entirely:
`renderPortalEntitiesSameDim` (branch at `:1161`, `sharedState && !debugSkipSameDimEntities`).
**If neither flag is set during that pass, the fade gate applies and the cart is culled** — and
a cart that has just crossed sits in a region whose sections `SameDimRemesh` compiled moments
ago, i.e. exactly the freshly-uploaded case.

⇒ **VERIFY FIRST, one log line:** is `PortalContextSwitch.isRenderingPortal` true inside
`renderPortalEntitiesSameDim`, and does the cart survive that pass's extraction? Log the entity
count in vs out, the way `DIAG-RENDER` did. If in>0 and out==0, the hypothesis is confirmed and
the fix is to extend the bypass bracket to the same-dim entity pass.

### Already excluded — do not re-investigate

**The same-dim entity pass's dead-latch did NOT fire.** `SecondaryWorldRenderCore` counts
swallowed throws in that pass (`sameDimEntityThrowCount`) and **dead-latches the whole pass for
the session after 3** (`:2324`, degrading to "no same-dim entities at all"). That would have been
a clean explanation. It is ruled out: neither live log contains a swallow line. Re-check per
session — it un-latches in `cleanUp` — but it was not the cause on 2026-07-28.

### Existing levers and instruments

`IPGlobal.debugSkipSameDimEntities` and `IPGlobal.debugSkipPortalEntities` already exist and
A/B the two entity passes. `rsSeamClipGate` in `CrossingSmoke.java` is the suite's PIXEL gate and
is the working recipe for asserting what is actually on screen (9×9 patch sample below centre —
26.2 has no `Options.hideGui`; pin lighting with glowstone + night vision because every gametest
world is a fresh random seed).

---

## DEFECT B — CROSS-DIM: RIDING BREAKS ON TELEPORT

### Why same-dim works and cross-dim does not — the asymmetry is structural

- **Same-dim** never calls `changePlayerDimension` at all. `ServerTeleportationManager.teleportPlayer`
  takes the same-dim branch (`setEyePos` + `updateBoundingBox`), then `McHelper.adjustVehicle`
  drags the cart to the player. Nothing is recreated. Gated by `rsCartLegRiddenSameDimProbe`
  (RS-CART-E) and confirmed live.
- **Cross-dim** runs the whole sequence: `ip_stopRidingWithoutTeleportRequest` → player moved →
  `teleportVehicleAcrossDimensions` → `ip_startRidingWithoutTeleportRequest` → `adjustVehicle`.

### The prime suspect: client and server hold DIFFERENT vehicle objects under one network id

- **Server** (`ServerTeleportationManager.teleportVehicleAcrossDimensions`) **RECREATES** the
  vehicle: `entity.getType().create(toWorld)`, `restoreFrom(old)`, **`setId(oldEntity.getId())`**,
  `old.remove(CHANGED_DIMENSION)`, `toWorld.addDuringTeleport(new)`.
- **Client** (`ClientTeleportationManager:551-562`) does **NOT** recreate — it moves the EXISTING
  client entity with `moveClientEntityAcrossDimension(vehicle, toWorld, vehiclePos)`, then
  `player.startRiding(vehicle, true, false)`.

Two different objects sharing an id, with the passenger link re-established independently on each
side, is a textbook source of "forced dismount" and "rider spazzing in place" (client prediction
and server state disagreeing about who is riding what, each correcting the other every tick).

**The white/blank background** is a second, probably separable symptom: it smells like the
half-cutover client state that this very file warns about — see the comment around the
promote-gap light drain in `ClientTeleportationManager` ("a throwing light lambda here would
otherwise abort `changePlayerDimension` between the promote and the `gameRenderer.setLevel`,
stranding a half-cutover client"). If a throw lands mid-cutover the client can end up with a
level/renderer mismatch. **Check the log for swallowed exceptions during the crossing before
theorising** — that code logs rather than crashes, so the evidence will be there if it happened.

### ⚠ THE GATE THAT SHOULD HAVE CAUGHT THIS, AND WHY IT DID NOT

`rsCartLegRiddenProbe` (RS-CART-D) drives **exactly this crossing** — a real player, riding, cross-dim
— and reports `playerCrossed=true stillRidden=true onRails=true advance=9.01 comeOffTracks=0`.
It is green while the user's identical action visibly breaks, because **every assertion in it
reads SERVER state**. It cannot see a client-side dismount, a spazzing camera, or a blank frame.

The suite is a **client** gametest: `context.runOnClient` / `computeOnClient` are available, and
`rsSeamClipGate` already samples pixels. **The fix for this defect is not complete until RS-CART-D
asserts on the CLIENT** — at minimum `mc.player.getVehicle() != null` and the client cart's
position/level after the crossing, and ideally a pixel or camera-stability check for the spaz.
This is the fourth time this engagement has paid for *assert the outcome the user can see*; the
first three are in `REDSTONE_NEXT_SESSION.md`'s HAZARDS list.

---

## STANDING DISCIPLINE (unchanged, and it decided every question this session)

- **Instrument before theorising.** The (d) design was decided by a measurement that overturned
  half the recon; the phantom-rail fix was found by a gate written for a different finding; the
  ridden hop was found by a probe line printing every term of a placement.
- **Assert the outcome the user can see** — and ask WHICH SIDE OF THE WIRE their eyes are on.
  Also: physics can paper over an outcome (an off-rail cart is snapped back within one tick), so
  hook the write, not the settled state.
- **Gates must invert** under their own lever, in the same leg, and assert their own COVERAGE.
- **A fixture that fails for the wrong reason is worse than one that passes** — RS-CART-C spent a
  round resting on the frame's obsidian sill instead of measuring anything, and said so.
- javap every mixin target on `minecraft-merged-deobf-26.2.jar` before first launch.
- Every fix DEFAULT-ON behind `-Dseamlessportals.disableX`, `-P` rows in **both**
  `fabric/build.gradle` blocks, `git commit -F` with explicit file lists, push every commit.
- Build scaffolding (root `build.gradle`, `settings.gradle`, `gradlew*`, `gradle/`) is UNTRACKED
  and must stay so.
- Another session may run in `.claude/worktrees/is5-shadow` (iris shaders-ON). Never
  `gradlew --stop`. Java cleanup: own PIDs only, filtered by THIS worktree's path.

## GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true -PrsOnly=true
```
Fast iteration (~3 min). Add `-PseamCartProbe=true` for the two RIDDEN legs (RS-CART-D cross-dim,
RS-CART-E same-dim) and the SAMPLE/EVT/CARRY-TERMS channels. Full matrix before any commit: the
two FULL suites, the (b) inversions, the (c) inversions, and the (d) levers
(`-PdisableSeamCartRail`, `-PdisableSeamCartStraddle`, `-PdisableSeamVehicleAttach`).

## STILL OPEN FROM (c) — the user's explicit word, explained to them 2026-07-28

1. signal is not a "machine write"; 2. DISJOINT seams carry signal; 3. junction-switching
deferred. My recommendation given: keep 1 and 2, leave 3 deferred. Not yet answered.
