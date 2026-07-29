# FRESH-SESSION STARTER — CART RENDERING + RIDDEN CROSS-DIM CROSSING. 2026-07-28.

*Paste this verbatim into a new session.*

---

Two user-reported defects in Seamless Portals, found live on 2026-07-28 after sub-feature (d)
(minecart traversal) landed. **They invert across the two portal topologies, and that pairing is
the biggest clue — they are two independent investigations, not one root cause:**

| | SAME-DIM portal | CROSS-DIM portal |
|---|---|---|
| **empty cart, seen in the portal window** | ✘ **the cart DISAPPEARS as it crosses the seam** (terrain still draws) | ✔ renders properly |
| **riding a cart through** | ✔ works | ✘ **breaks on teleport** — forced dismount, or the player spazzes in place; background sometimes correct terrain, sometimes white/blank |

So the RENDERING defect lives in the same-dim (`sharedState`) path and the RIDING defect lives in
the cross-dim path.

Work in the worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`
(branch `redstone/passthrough`). Tree clean at `afaff9d`, matrix green, everything pushed.

READ FIRST, in order:

1. `migration/CART_RENDER_RIDE_HANDOFF.md` — **the whole brief for this engagement**: the
   evidence-backed hypothesis for each defect with file/line pointers, what is ALREADY EXCLUDED
   (do not re-investigate it), the existing levers and instruments, and the one-command A/B that
   must run first.
2. `migration/REDSTONE_NEXT_SESSION.md` — the state file for everything already shipped:
   (a) mirroring, (b) rails, (c) redstone signal, (d) minecarts, the SEAM CLIP (DEFAULT OFF by
   user decision — do NOT relitigate), and especially the **HAZARDS list**, where every line has
   a scar behind it.
3. The two defect-specific sources named in the handoff:
   `common/src/main/java/com/warwa/seamlessportals/mixin/client/LevelRendererEntityVisibilityMixin.java`
   (its javadoc documents this exact symptom, already fixed once for the OTHER topology) and
   `qouteall/imm_ptl/core/teleportation/ClientTeleportationManager` around the vehicle block at
   `:551-562` versus `ServerTeleportationManager.teleportVehicleAcrossDimensions`.

## §0 DO THIS FIRST — it may implicate the last session's own change

The previous session changed SHARED vehicle-crossing machinery with the user's explicit
authorisation (`McHelper.getVehicleOffsetFromPassenger` now returns the true two-term inverse of
vanilla's rider placement, fixing a 0.1875 arrival hop for every ridden vehicle). **That helper is
also called on the CLIENT crossing path**, so the ridden cross-dim defect could be pre-existing or
newly introduced, and nothing distinguishes them yet:

```
.\gradlew.bat :fabric:runClient -PseamCartProbe=true -PdisableSeamVehicleAttach=true
```

Ride through a CROSS-DIM portal under that lever (it restores the old one-term offset). Still
broken ⇒ pre-existing. Fixed ⇒ the change is implicated. One launch, and it halves the search.

## §1 WHAT THIS ENGAGEMENT MUST RESPECT

- **Neither defect is in a probe log yet.** Both live rounds contain ZERO cross-dim carries and no
  render diagnostics. **Reproduce with instruments armed before designing anything** — that rule
  decided every question in the last three sessions, including one where it overturned half the
  recon.
- **★ A SERVER-SIDE ASSERTION CANNOT SEE A CLIENT-SIDE SYMPTOM.** The existing ridden gate
  (RS-CART-D) drives exactly the broken crossing and passes green — `stillRidden=true`,
  `onRails=true`, rolled 9 cells — because every assertion reads SERVER state. The suite is a
  CLIENT gametest (`context.runOnClient` / `computeOnClient` are available, and `rsSeamClipGate`
  already samples pixels). **Neither defect is fixed until a gate asserts on the client.**
- **Physics and rendering both paper over outcomes.** An off-rail cart is snapped back within one
  tick, which already made one gate unfailable; hook the write, not the settled state.
- Decisions pinned and user-confirmed: player-only + exact-only mirroring; DISJOINT seams carry
  traversal and signal but never mirror; the AUTHORITY RULE. Three (c) decisions are still
  provisional and were explained to the user on 2026-07-28 — recommendation given (keep 1 and 2,
  leave 3 deferred), answer not yet received. Do not silently widen anything.

## §2 STANDING DISCIPLINE

Instrument before theorising. Assert the outcome the user can see, on the side of the wire their
eyes are on. Gates lever-aware and coverage-asserting, inverting under their own lever in the same
leg. A fixture that fails for the WRONG reason is worse than one that passes — say so and fix the
fixture. javap every mixin target on `minecraft-merged-deobf-26.2.jar` before first launch. Every
fix DEFAULT-ON behind `-Dseamlessportals.disableX`; `-P` rows in BOTH `fabric/build.gradle`
blocks; commit via `git commit -F <file>` with explicit file lists; push every commit. Trust the
user's live observations over your own readings — they have split three bugs this engagement that
the suite called green.

## §3 PARALLEL-SESSION RULES

Another session may run in `.claude/worktrees/is5-shadow` (iris shaders-ON) — coordinate before
large changes to shared render files, and never touch that worktree. **Defect A is squarely in
shared render code**, so check with the user before restructuring `SecondaryWorldRenderCore`.
Never run `gradlew --stop` (machine-wide; kills live games). Java cleanup: own PIDs only, filtered
by THIS worktree's path; `idea64.exe` is a JVM. Build scaffolding (root `build.gradle`,
`settings.gradle`, `gradlew*`, `gradle/`) is UNTRACKED and must stay so — explicit `git add` file
lists only.

## §4 GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true -PrsOnly=true
```

Fast iteration (~3 min). Add `-PseamCartProbe=true` for the two ridden legs (RS-CART-D cross-dim,
RS-CART-E same-dim) plus the SAMPLE/EVT/CARRY-TERMS channels. Full matrix before any commit: the
two FULL suites (canonical + `-PdisableAperturePassthrough`), the (b) inversions
(`-PdisableSeamShadow`, `-PdisableSeamPhaseGate`, `-PdisableSeamShapeSync`), the (c) inversions
(`-PdisableSeamSignal`, `-PdisableSeamSignalDispatch`, `-PdisableSeamPowerWake`,
`-PdisableSeamBreakUnmark`), the (d) levers (`-PdisableSeamCartRail`,
`-PdisableSeamCartStraddle`, `-PdisableSeamVehicleAttach`), plus whichever new lever this work
adds, both directions. Rendering and riding both need LIVE eyes — ask the user.

## §5 AFTER THIS

Queued: the FULL FRACTIONAL MODEL (recon §0.9 + the seam-clip decision — genuine partial blocks at
the seam, which also retires the offset-seam decline); (c) step 2 wire/dust; D2
delivery-forwarding; the deferred §6 items in `REDSTONE_C_SPEC.md`; the (b) residuals.
