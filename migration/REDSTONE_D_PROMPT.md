# FRESH-SESSION STARTER — (d) MINECART TRAVERSAL (default) or (c) STEP 2 WIRE/DUST. 2026-07-28.

*Paste this verbatim into a new session.*

---

Build sub-feature (d) for Seamless Portals: MINECART TRAVERSAL ACROSS THE SEAM — a cart riding
a rail line through a portal crosses seamlessly and keeps following the far side's track.
Crossing itself is ALREADY SOLVED AND LIVE (recon §4(d): the entity teleport path carries carts;
ridden carts ride the player's crossing); **what is missing is RAIL FOLLOWING around the
crossing instant** — `OldMinecartBehavior`'s five same-`Level` sites resolve rails in the near
dimension while the cart still ticks there, so it derails/`comeOffTrack`s at the plane. (b)'s
`SeamShadow` local-frame primitive and (c)'s signal bridge are the substrate — (d) consumes
`SeamShadowBridge.shadowFor` with a taller `yWindow` exactly as (b) did for shapes and (c) did
for the powered-rail walk.

Work in the worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`
(branch `redstone/passthrough`). Tree clean, 9-configuration matrix green, everything pushed.

**IF THE USER PREFERS (c) STEP 2 INSTEAD** (redstone WIRE/dust across the seam): the designed
route is in `REDSTONE_C_SPEC.md` §6.2/§6.3 — wire-to-wire decay does NOT flow through
`SignalGetter` (raw `getBlockState` in `RedstoneWireEvaluator.getIncomingWireSignal`), so dust
needs its own chokepoint; the general `SignalGetter` default-method interface mixin is feasible
per bytecode gates but needs a one-launch smoke test and a WorldGenRegion guard. Ask which,
then proceed; do not build both in one session.

READ FIRST, in order:

1. `migration/REDSTONE_NEXT_SESSION.md` — the whole state file: (a)+(b)+(c) landed and
   user-confirmed; the ★★ (c) ROOT CAUSE record (TWO live defects: the POWER-WAKE — the mirror
   authority swallowed the power question, fixed by FORWARDING the poke to the counterpart, and
   BREAK-UNMARK — stale provenance, a break now voids the broken cell's own mark; the restored
   invariant: A PAIR CARRIES AT MOST ONE MARKED HALF); the SEAM CLIP section (DEFAULT OFF by
   user decision — do NOT relitigate, `-PenableSeamClip` re-enables); the HAZARDS list — every
   line has a scar, including the NEW ones: a suite can pass a 500k-iteration loop if nothing
   watches VOLUME, and PS5.1 `Get-Content`/`Set-Content` mojibakes BOM-less UTF-8 sources (use
   the Edit tool; `.NET File` APIs with `UTF8Encoding($false)` if scripting is unavoidable).
2. `migration/REDSTONE_RECON.md` §0 (pinned decisions as amended by every later handoff
   section) and **§4(d)** — the five `OldMinecartBehavior` same-Level sites, the
   `getCurrentBlockPosOrRailBelow` transform problem, the `setPos`-bypasses-`Entity.move` trap,
   and the open ordering question (does `shouldEntityTeleport` fire before `comeOffTrack`?
   recon §5.5 has the probe design — INSTRUMENT FIRST). Verify every claim against 26.2
   bytecode before building on it: the fleet found the recon materially wrong for (c) (the walk
   reads live in `isSameRailWithPower`, not `findPoweredRailSignal`; all signal reads are
   `SignalGetter` interface defaults with NO Level overrides).
3. `migration/REDSTONE_C_SPEC.md` (top PANEL FOLD banner first) + the handoff's ★ (c) STEP 1
   section — the shipped signal architecture (d) sits beside: R-UNION/R-WALK reads,
   D1 dispatch with tick-end flush + budgets + per-tick dedupe, the walk-DIED and no-redirect
   probes, and the authority interplay (d) must NOT re-break: machine-derived far state never
   overwrites the player's block; the mirror half never self-writes — pokes are FORWARDED.

## §0 PARALLEL-SESSION RULES — READ TWICE

Another session may run in this repository (`.claude/worktrees/is5-shadow`, iris shaders-ON).
Coordinate before large changes to shared render files; never touch that worktree. Never run
`gradlew --stop` (machine-wide; kills live games). Java cleanup: own PIDs only, filtered by
THIS worktree's path; `idea64.exe` is a JVM. Build scaffolding (root `build.gradle`,
`settings.gradle`, `gradlew*`, `gradle/`) is UNTRACKED and must stay so — explicit `git add`
file lists only.

## §1 WHAT (d) MUST RESPECT — the scars already paid for

- **Decisions pinned and user-confirmed:** player-only + exact-only mirroring; DISJOINT seams
  carry traversal and signal but never mirror; the AUTHORITY RULE (+ its two 2026-07-28
  refinements above). THREE (c) decisions were taken provisionally and still want the user's
  explicit word: signal-is-not-a-machine-write; DISJOINT-carries-signal; junction-switching
  deferred. Put them to the user when convenient; do not silently widen anything (`7766010`).
- **The cart's rail resolution is the same derived-state family** — expect the (b)/(c) pattern:
  a seam-framed READ bridge at the resolution chokepoint (never a state copy), local-first,
  budgets/counters, cold-far declines + retry-on-warm, exception-proof reads (the
  `shouldSignal` singleton rules in spec F8 apply to ANY read that can run inside vanilla's
  evaluation brackets).
- **The crossing instant is (d)'s own hard problem** (recon §4(d)): the cart's lookahead
  resolves in the far dimension while `this.level()` is still the near one; velocity must be
  re-aligned to the far rail axis through the binding rotation (`SeamRegistry.mapDir`); the
  `setPos` sites bypass `Entity.move` so IP's collision redirect never sees them. The teleport
  handoff (`ServerTeleportationManager`) is LIVE machinery — instrument its ordering against
  `comeOffTrack` before designing (recon §5.5), on the gametest, not by reading.
- **Volume is a gate dimension now**: any new evaluation/dispatch path gets a runaway ceiling
  in its leg (walk/lookahead delta bounds), and read the counters line of green runs.

## §2 STANDING DISCIPLINE — unchanged

Instrument before theorising. Assert the OUTCOME the user can see (for (d): the CART ARRIVES
and keeps rolling on the far track — position/velocity in the far world after the cascade, not
any value your code computed). Gates lever-aware and coverage-asserting, inversion under the
new lever in the same leg; fixtures fail for the RIGHT reason (support blocks under every rail
— a popped rail invisibly voids an arm). javap every mixin target on
`minecraft-merged-deobf-26.2.jar` BEFORE first launch (owner drift: the lamp `tick` receiver is
`ServerLevel`, not `Level`). Trust the user's live observations over your own readings — their
one polarity correction split (c)'s live bug in two. Every fix DEFAULT-ON behind
`-Dseamlessportals.disableX`; `-P` rows in BOTH `fabric/build.gradle` blocks; commit via
`git commit -F <file>` with explicit file lists; push every commit.

## §3 GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true -PrsOnly=true
```

is the fast iteration gate (~2.5 min; includes the rail legs, all four RS-SIGNAL arms
(A/B/R/S), the probe-gated repro legs under `-PseamSignalProbe`, and the seam-clip OFF-branch
pixel gate). Full matrix before any commit: the two FULL suites (canonical +
`-PdisableAperturePassthrough`), the (b) inversions (`-PdisableSeamShadow`,
`-PdisableSeamPhaseGate`, `-PdisableSeamShapeSync`), the (c) inversions (`-PdisableSeamSignal`,
`-PdisableSeamSignalDispatch`, `-PdisableSeamPowerWake`, `-PdisableSeamBreakUnmark`), plus
whichever new lever (d) adds, both directions. Cart behaviour needs LIVE eyes — ask the user.

## §4 AFTER (d)

The FULL FRACTIONAL MODEL (recon §0.9 + the seam-clip decision): genuine partial blocks at the
seam — geometry, collision and state ending at the plane; the clip machinery in the tree is its
ready-made renderer, and it retires the offset-seam decline (the user's wand-built
boundary-phase pairs — `.5`-vs-flush — carry nothing today by the exact-only decision). Also
queued: (c) step 2 wire/dust; D2 delivery-forwarding; the deferred §6 items in
`REDSTONE_C_SPEC.md`; the (b) residuals (detector 3-way swallow, unbind snapshot).
