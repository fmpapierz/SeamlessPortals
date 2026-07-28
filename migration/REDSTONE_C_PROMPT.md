# FRESH-SESSION STARTER — (c) THE REDSTONE BRIDGE, then (d) MINECARTS. 2026-07-27.

*Paste this verbatim into a new session.*

---

Build sub-feature (c) for Seamless Portals: REDSTONE SIGNAL ACROSS THE SEAM — a signal on one
side of a portal powers the coincident circuitry on the other side. **First concrete target,
already user-found and reproduced: the powered-rail chain.** A powered rail's mirrored half
shows powered but propagation stops at the seam, because
`PoweredRailBlock.findPoweredRailSignal` walks raw positions in ONE level and never touches
`RailState` — the (b) bridge never sees it. That chain is the (c) primitive's first consumer,
exactly as the rail legs were (b)'s.

Work in the worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`
(branch `redstone/passthrough`). Tree clean, suite green, everything pushed.

READ FIRST, in order:

1. `migration/REDSTONE_NEXT_SESSION.md` — the whole state file: (a)+(b) landed and
   user-confirmed; the ★ SEAM CLIP section (LANDED but **DEFAULT OFF by user decision** — the
   fractional model is the chosen future; do NOT relitigate, do NOT delete the machinery, the
   one-line re-enable is `-PenableSeamClip`); the (b) residuals that queue into (c); the
   HAZARDS list — **read the hazards properly, every line has a scar.**
2. `migration/REDSTONE_RECON.md` §0 (pinned decisions, as amended by the handoff's DECISIONS
   CHANGED + the 2026-07-27 confirmations) and **§4(c)** — the recon's sizing of the redstone
   bridge ("HARD, but the cleanest chokepoint"). Start your design from that chokepoint claim
   and VERIFY it against 26.2 bytecode before building on it.
3. `migration/REDSTONE_B_SPEC.md` top banner + the handoff's ★ (b) STEP 2 section — the shipped
   (b) architecture ((c) consumes the same primitives): `SeamShadow`/`SeamShadowBridge` (the
   local-shadow frame), `SeamRegistry.lookupAcross`/`bindingAcross`/`mapDir`, the phase gate,
   and the AUTHORITY RULE from the adversarial round (machine-derived far state never
   overwrites the player's block).

## §0 PARALLEL-SESSION RULES — READ TWICE

Another session may be running in this repository on iris shaders-ON work, in
`.claude/worktrees/is5-shadow`. Coordinate before large changes to shared render files; never
touch that worktree's files or logs; reading its branch through git objects is fine. (c) is
mostly server-side block logic — the collision surface should be near zero, but
`fabric/build.gradle` lever rows collide textually (trivial). Never run `gradlew --stop`
(machine-wide; kills live games). Java cleanup: own PIDs only, filtered by THIS worktree's
path; `idea64.exe` is a JVM. Build scaffolding (root `build.gradle`, `settings.gradle`,
`gradlew*`, `gradle/`) is UNTRACKED and must stay so — explicit `git add` file lists only.

## §1 WHAT (c) MUST RESPECT — the scars already paid for

- **The DERIVED-STATE AUDIT (handoff section) is (c)'s central hazard.** Redstone dust
  connection state is the worst derived-state case in the game — re-derived before, during and
  after every write, exactly the family that produced (a)'s three defects and (b)'s
  shape-sync/authority findings. Expect to need the (b) pattern: a seam-framed read bridge at a
  RESOLUTION chokepoint (not a state-copy), budgets/counters, and the authority rule.
- **Mirror policy is player-only + exact-only (user decisions, CONFIRMED).** Signal LEVELS are
  not block writes — decide explicitly, with the user, whether signal propagation counts as a
  "machine write" (it should NOT mirror blocks; it carries POWER). The policy seams
  (`SeamWriteSource` × `SeamAlignment` → `SeamMirrorPolicy`) exist so widening is a one-line
  admission — do not inline new conditions.
- **Phase gate:** DISJOINT (boundary-phase) seams do not mirror but DO carry (b) traversal;
  decide (c)'s DISJOINT behaviour deliberately (a wire ending flush at a boundary-phase plane
  is the topology-B analogue of the rail case).
- **Residuals queued into (c)** (handoff, end of the (b) section): the detector-rail
  3-way-junction swallow inside the mirror's `applying` window; the unbind stale-binding
  snapshot (spec §3.5(v)); open items 2 (bind-time reconciliation mirrors non-player blocks —
  needs a design call with the user), 3 (water-displacement placement), 4 (placed-block xray
  flash). Take what wire makes hotter; leave the rest documented.
- **`SeamRailContinuity`'s budget/counter/retry discipline** is the template for any (c)
  cross-reads. RS-only mode (`-PrsOnly`) is the fast iteration gate; the full matrix stands.

## §2 STANDING DISCIPLINE — unchanged

Instrument before theorising. Assert the OUTCOME the user can see (for (c): the LAMP lights /
the cart accelerates — not the signal value your code computed; the pixel-gate precedent shows
how far that can be pushed). Gates lever-aware and coverage-asserting, inversion under the new
lever in the same leg. Verify mixin targets with javap on the loom deobf jar
(`minecraft-merged-deobf-26.2.jar`) BEFORE first launch — it has caught owner drift and
return-count drift twice, and a package drift once. Trust the user's live observations over
your own readings. Every fix DEFAULT-ON behind `-Dseamlessportals.disableX` (probes
DEFAULT-OFF; note the seam clip is the one deliberate exception — enable-lever, user decision);
`-P` rows in BOTH `fabric/build.gradle` blocks; commit via `git commit -F <file>` with explicit
file lists; push every commit.

## §3 GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true -PrsOnly=true
```

is the fast iteration gate (~3.5-4.5 min; includes the rail legs and the seam-clip pixel gate,
which now asserts the OFF branch by default). Full matrix before any commit: the two FULL
suites (canonical + `-PdisableAperturePassthrough`), the inversion configs in the handoff's
GATE MATRIX (including `-PenableSeamClip` for the clip's ON direction), plus whichever new
lever (c) adds, in both directions. Wire behaviour needs LIVE eyes too — ask the user.

## §4 AFTER (c)

(d) minecart traversal (depends on (b), consumes the same continuity primitive; recon §4(d)).
Behind both: the FULL FRACTIONAL MODEL (recon §0.9 + the ★ SEAM CLIP decision) — genuine
partial blocks at the seam; the clip machinery in the tree is its ready-made renderer.
