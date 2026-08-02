# STARTER PROMPT — (c) STEP 2: REDSTONE WIRE/DUST ACROSS THE SEAM

Paste the block below to open the next session.

---

Sub-feature **(c) step 2 — redstone WIRE/DUST across the seam** in Seamless Portals.

Work in the worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`
(branch `redstone/passthrough`). Tree clean at `55b7611`, matrix green, everything pushed.

READ FIRST, in order:

1. `migration/REDSTONE_C2_HANDOFF.md` — **the whole brief.** Why this and not the fractional model
   (three intuitions that a survey refuted), the two things to do before any code, what step 1
   shipped and why it is INERT for dust, what step 2 actually needs, and the hazards.
2. `migration/REDSTONE_C_SPEC.md` — step 1's spec. **Its ★★ PANEL FOLD banner overrides its body.**
   §6.2 and §6.3 are the only wire/dust notes that exist.
3. `migration/REDSTONE_NEXT_SESSION.md` — state for everything shipped, and especially the
   **HAZARDS list**, where every line has a scar behind it.

## §0 DO THESE TWO THINGS BEFORE WRITING CODE

**A. Ask me the three (c) decisions I have never signed.** Step 2 exercises all three, and #2 —
"DISJOINT seams DO carry signal" — *is* the wire-flush-at-a-boundary-plane case this work is about.
The table is at `REDSTONE_NEXT_SESSION.md:436-442`. A recommendation was given 2026-07-28 (keep 1
and 2, leave 3 deferred) and never answered. Do not build on top of unsigned policy.

**B. Measure the offset premise — nobody has ever run the suite under the offset levers.**
`-PdisableSeamExactOnly` and `-PdisableSeamPhaseGate` both exist, wired in both `build.gradle`
blocks, and no gametest has ever exercised either. Run them TOGETHER (exact-only alone leaves the
phase gate declining the boundary side) against the offset fixture that already exists at
`CrossingSmoke.java:1061`. Record what actually breaks: does the pair mirror both ways, does column
pairing go many-to-one, do server and client agree on the dest cell, do (b) rails and (c) signal
light up. Expect to fix `CrossingSmoke.java:1094`'s STRICT assertion, not the code.

That half day either answers my live "boundary-phase pairs don't carry signal" complaint for a
lever flip plus residue, or it becomes the measured scope the fractional design panel does not have.
Either result is worth more than starting to build.

## §1 WHAT THIS ENGAGEMENT MUST RESPECT

- **There is NO step-2 spec.** Two paragraphs of route notes, one verified blocking fact, one
  hazard rule. Step 1's spec is 301 lines. **Start with a design pass, not an implementation.**
- **★ Step 1's read bridge is INERT for dust-to-dust (F11).** Step 1 bridged the `SignalGetter`
  family at per-consumer call sites; wire decay never calls those — the evaluator reads the
  neighbour's `getBlockState` + `POWER` RAW. Step 2 needs a NEW chokepoint at `getBlockState`
  level. **Prove that by measurement before designing around it**, with a scoped probe counting
  `getIncomingWireSignal` invocations vs `SignalGetter`-family reads at bound seam cells.
- **★ `RedStoneWireBlock.shouldSignal` is a mutable boolean on the GLOBAL BLOCK SINGLETON** with no
  try/finally (bytecode-confirmed: no exception table). **A bridged read that throws latches it
  false and mutes ALL WIRE, in every dimension, until restart.** Bridged reads must be
  side-effect-free and exception-free; no far-level EVALUATION inside a read window.
- **javap every wire target before trusting a line number.** All of them are recon-era and
  unverified for 26.2, and the recon was already proven MATERIALLY WRONG on step 1's central claim.
- **Volume is a gate concern.** Step 1's first build looped ~500k iterations and the suite PASSED
  because nothing watched volume. Ship a runaway ceiling in every new leg from day one.
- **Do not promise sub-tick pulse fidelity.** D1 re-derives from settled state at tick end; a far
  one-tick pulse can be missed structurally. That is D2 territory — say so up front.

## §2 STANDING DISCIPLINE

Instrument before theorising. Assert the outcome I can see, on the side of the wire my eyes are on.
Hook the write, not the settled state. Gates lever-aware and coverage-asserting, inverting under
their own lever in the same leg — **and assert the fixture's PRECONDITION, not just its outcome**
(RS-CART-F caught two wrong-reason fixtures that way). A fixture that fails for the WRONG reason is
worse than one that passes. Every fix DEFAULT-ON behind `-Dseamlessportals.disableX`; `-P` rows in
BOTH `fabric/build.gradle` blocks; commit via `git commit -F` with explicit file lists; push every
commit. Build scaffolding stays UNTRACKED.

**Probe checklist** (five instrument defects in the last session, one shape each): does it fire on
the stack you are already looking at; can it throw during construction/removal; can routine volume
starve the rare line; is the quantity you assert on still true when you read it?

**Never run `gradlew --stop`. Never run two Gradle jobs against this project at once** — the last
session deadlocked its own matrix that way and I had to kill the windows by hand.

## §3 PARALLEL-SESSION RULES

Another session may run in `.claude/worktrees/is5-shadow` (iris shaders-ON); never touch that
worktree. **Step 2 should touch zero render files** — if you find yourself in
`SecondaryWorldRenderCore`, stop and check with me first. Java cleanup: own PIDs only, filtered by
THIS worktree's path; `idea64.exe` is a JVM.

## §4 GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true -PrsOnly=true
```
Fast iteration (~3 min). Full matrix before any commit: the two FULL suites, the (b) inversions,
the (c) inversions, the (d) levers, the (e) levers (`-PdisableDestEntitySectionExact`,
`-PdisableCrossDimPositionCodecSync`), plus the new lever both directions. **Wire needs LIVE
eyes — ask me.**

## §5 AFTER THIS

The **FRACTIONAL MODEL**, as its own DESIGN PANEL session — not an implementation session. Its
banked hazards are in `REDSTONE_C2_HANDOFF.md` §5: the fraction itself is undecided (the shipped
clip stores halves only), a half-cut cube fails `SupportType.RIGID` so a rail pops off a partial
support block, state-keyed partiality kills `isRedstoneConductor`/`isSuffocating`, there is no
collision gate anywhere in the suite, and persistence after portal teardown is undesigned.
Then: D2 delivery-forwarding, the deferred §6 items in `REDSTONE_C_SPEC.md`, the (b) residuals.
