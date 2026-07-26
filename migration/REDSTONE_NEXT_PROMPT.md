# REDSTONE / RAIL / MINECART PASSTHROUGH — NEXT-SESSION STARTER (paste verbatim)

---

Continue the redstone/rail/minecart passthrough engagement for Seamless Portals.

Work in the worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`
(branch `redstone/passthrough`, tip `cdec39f`). Tree is clean, suite is green, everything is pushed.

READ FIRST, in order:
1. `migration/REDSTONE_NEXT_SESSION.md` — state, the OPEN BUG, and a hazards list that was earned the
   hard way. Read the hazards section properly; each line cost real debugging time.
2. `migration/REDSTONE_RECON.md` **§0** — every binding user decision, with the reasoning.
3. `migration/REDSTONE_B_SPEC.md` — the (b) design. **Its top banner overrides its body.** See the
   re-check note below before implementing anything from it.

---

## §0 PARALLEL-SESSION RULES

Another Claude Code session may be running in this repository on the iris shaders-ON work, in
`.claude/worktrees/is5-shadow`.

- **NEVER touch `.claude/worktrees/is5-shadow`** — not its files, not its branch, not its logs. That
  session reads its `latest.log` as primary evidence.
- **NEVER run `gradlew --stop`.** It is machine-wide and will kill the other session's live game.
- **Java process cleanup: your own PIDs only**, filtered by this worktree's path. `idea64.exe` is a
  JVM and appears in `jps`.
- Build scaffolding (`build.gradle`, `settings.gradle`, `gradlew*`, `gradle/`) is **UNTRACKED in git**.
  A fresh worktree cannot build until they are copied in from the main checkout. This is a known
  footgun, deliberately not committed.

## §1 WHAT IS DONE

**Sub-feature (a) is COMPLETE and user-verified.** The portal aperture is ordinary building space at
any height; blocks placed there mirror across the seam; breaking either half breaks both with one item
on the side broken; conflicts refuse before any write; a frame break keeps the player's block and
clears the mirror; frame breaks mirror instantly while frame repairs stage until ignition, which
restores the far frame through a persisted dormant link with no portal alive; and a block aimed into an
aperture stays in the player's dimension while reach-through an empty aperture still works.

**Sub-feature (b) step 1 is done:** the cross-seam neighbour primitive (`SeamBinding.continuationCell`,
`SeamRegistry.bindingAcross` / `stateAcross`, `SeamMap.phaseOf`), gated on BOTH topologies —
24 coincident + 1 boundary-phase check.

Five gates run in the suite. Run it both ways before every commit:
```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PdisableAperturePassthrough=true
```

## §2 START HERE — THE OPEN BUG, AND DO NOT GUESS AT IT

**Same-dimension man-made portals do not show mirrored writes live.** Obsidian portals fine, man-made
cross-dim fine. Server state is always correct — blocks persist, mirror writes fire, zero failures.

**Three fixes were attempted from three different theories, and all three were wrong**, the last one
making it worse than the original. Full state table in `REDSTONE_NEXT_SESSION.md`.

**DO NOT WRITE A FOURTH FIX FROM A FOURTH THEORY.** Every attempt so far reasoned from symptom to
mechanism and shipped a change; none measured where the update is actually lost. The first task is a
probe: log, per mirrored write, whether `sendBlockUpdated` was reached and whether the client received
a change for that position. Hypotheses are listed in the handoff in order of promise — the leading one
is that the portal VIEW's mesh is never invalidated, the same class the block-era
`RemoteBlockUpdater` + `rebuildSectionAsync` fixed for cross-dim fluid flow.

## §3 THEN: FINISH (b)

Rails do not yet CONNECT across the plane. The primitive exists; nothing consumes it. The consumer is
`RailState` — a captured single `Level`, and `hasConnection` comparing X/Z only. `REDSTONE_B_SPEC.md`
has the design, **but re-check it as you go**:
- It was written against an (a) carrying three defects since fixed, so any section reasoning about
  mirrored-state behaviour may describe the broken version.
- Its F2 re-entrancy analysis is **stale** — mirror writes now use `UPDATE_SKIP_ON_PLACE`.
- Its §3.3 `shouldBeRemoved` wraps are probably **dead for mirror-created cells**, because mirror
  authority cancels `neighborChanged` before reaching them.
- One verifier in that panel was reading a **NeoForge-patched decompile**; the fold withdrew its
  citations. Verify against `C:\Users\warwa\ModDev\mc262-ref`.

## §4 DEFERRED, RECORDED, DO NOT LOSE

- **Fractional seam blocks.** A block straddling a mixed-phase seam has its far half arrive as a WHOLE
  block, giving a 1.5-length run with no 0.5 offset. Making it a real half needs new block states,
  models and collision, and changes what a "seam cell" means everywhere. User-approved as a future
  goal; deserves its own design panel.
- **(c) redstone bridge and (d) minecart traversal.** Both consume the (b) primitive. The user named
  dust and repeaters explicitly, so the neighbour API is deliberately not rail-specific.

## §5 STANDING DISCIPLINE

- **Instrument before theorising.** This is the project's rule and the open bug is what ignoring it
  costs.
- **Gates must assert their own COVERAGE**, not just their result. Five instruments gave false
  readings this engagement; "zero checks ran" caught two of them.
- **Beware code that looks like a check and isn't** — a `require = 0` mixin that weaves nothing, a
  comparison against itself. Neither the compiler nor the suite can tell those from real checks.
- **Check panel findings against the USER DECISIONS doc, not only against source.** A verified proof
  can still carry a conclusion that is not yours to make; that happened once and silently disabled a
  feature the user had asked for.
- Every fix DEFAULT-ON behind `-Dseamlessportals.disableX`; every probe DEFAULT-OFF; a `-P` row in
  BOTH `fabric/build.gradle` blocks; A/B-attribute both directions.
- **Trust the user's live observations over your own readings.** Nearly every defect this engagement
  was found by them playing, not by analysis.
- Commit messages via `git commit -F <file>`; `git add` with explicit file lists; push every commit.
