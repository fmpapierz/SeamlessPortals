# S20 FRESH-SESSION STARTER — the block-era deletion (paste the block below verbatim)

> This file exists because `migration/S20_HANDOFF.md` was written before the iris shaders-ON
> engagement began. The handoff remains the authority on **what** S20 is; everything here is the
> **delta** since it was written. Read this first, then the handoff in full.

---

Execute S20 — the block-era deletion + survivor audit + the final 12-point regression.

Worktree: `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\s20`
(branch `s20/block-deletion`, based on `claude/nifty-kepler` @ e8e4767).

READ FIRST, in order:
1. This file.
2. `migration/S20_HANDOFF.md` **in full** — §0 must-do-firsts are ORDER-CRITICAL, §1 what dies,
   §2 the B11 rule, §3 what survives, §4 the 12-point regression, §5 the era after.
3. `migration/S19_HANDOFF.md` §0 — ALL standing rules, unchanged and binding.
4. `migration/EXECUTION_PLAN.md` §S20.

---

## §A PARALLEL SESSIONS — READ BEFORE RUNNING ANYTHING

**Two other Claude Code sessions may be live in this same repository.**

- `.claude/worktrees/is5-shadow` (branch `iris-on/is5-shadow`) — the iris shaders-ON polish/ACT
  engagement. **NEVER touch it**: it reads `fabric/runs/client-sodium/logs/latest.log` as primary
  evidence every run, and writing there silently corrupts its evidence trail.
- `.claude/worktrees/redstone` (branch `redstone/passthrough`) — the redstone/rail/minecart feature.
- **NEVER run `gradlew --stop`.** It is machine-wide and will kill another session's live game client
  with no useful error (two misdiagnosed "crashes" already: 2026-07-04, 2026-07-05). There is no
  project-scoped form.
- **Java process cleanup: your own PIDs only** — filter `Win32_Process` by command line containing THIS
  worktree's path before any `taskkill`. `idea64.exe` is a JVM and shows up in `jps`; a blanket sweep
  once killed the user's IDE. Full rules in the global `CLAUDE.md`.

**Merge risk is LOW, measured, not assumed:** `iris-on/is5-shadow` is 17 commits ahead of
`claude/nifty-kepler` across 45 files under `common/src/main`, and **zero** of them touch the S20 kill
list (`PortalWorldManager`, `PortalContextSwitch`, `StencilPortalRenderer`, `SeamlessPortalsConfig`,
`SodiumBridge`, `RemoteBlockUpdater`). The `redstone/passthrough` branch also came off
`claude/nifty-kepler` and will want a rebase after S20 lands — which HELPS it, since S20 removes the
block-era plumbing that made redstone hard.

## §B CORRECTIONS TO THE HANDOFF (it is stale in three places)

1. **§0.1 (the block-era FBO precedent) — the premise has moved on.** The handoff says to mine
   `PortalWorldManager` + `PortalContextSwitch`'s mirror-FBO path because the shaders-ON engagement
   uses it as architectural precedent. That engagement has since progressed well past that point: it
   now runs on the `IrisCompatOn262Renderer` full-pipeline path with its own large doc corpus
   (`migration/IRIS_SHADERS_ON_HANDOFF.md`, `POLISH_ROUNDS_CLOSED_HANDOFF.md`,
   `PER_DEST_STATE_RECON.md` and the polish notes, all on `iris-on/is5-shadow`). **Verify the current
   dependence yourself before deciding** — do not take this paragraph as licence to skip the check.
   If it holds, "accept git-history mining" is the cheap and correct call: the code stays in history,
   and nothing in the live tree depends on it. **Record the decision either way** — the handoff
   requires that, and a silent skip is the failure mode.
2. **§6's "Fable-verify every increment" is stale.** Current user directive: **Opus 5** for
   design / adjudication / adversarial verify, and the lighter **Opus (4.8)** for mechanical recon
   (javap, decompile, log tabulation). Depth is still user-endorsed — do NOT calibrate down.
3. **§4's 12-point regression predates the current sodium/iris state.** The shaders-ON stack has moved
   a long way (six shipped polish fixes plus an ACT probe kit on `iris-on/is5-shadow`, none of it
   merged). Scope the sodium/iris matrix rows against what is actually on YOUR branch, and do not
   report a matrix row as passing on the strength of work that lives on another branch.

## §C THE ONE DELETION THAT WOULD BE A REGRESSION

**`SodiumFogOverride` + `SodiumFogOverrideMixin` MUST NOT BE DELETED.** An older disposition table
marks them dormant ("fires flag-ON with activeOverride=false — reachable but inert"). That was
**resolved the other way at IS2**: `renderDestWorldFullPipeline` now brackets its Step-5 extract with
`SodiumFogOverride.activate(destFogData)` / `clear()`, so sodium's `cullTerrain` — which resolves fog
AT CULL TIME inside the extract via the `sodium$getFogParameters` duck — deterministically reads DEST
fog. They are flag-ON **load-bearing** with a ledgered duck-ordering dependency (fog computed and
override armed BEFORE the extract). See `S20_HANDOFF.md` §0.2 and §3, and port-note
`IS-iris-shaders-on.md` §3.2 FIX-F.

This is the shape of the whole risk in S20: **the danger is not deleting too little, it is deleting
something a later stage quietly made load-bearing.** Treat it as the worked example.

## §D SUGGESTED SHAPE (the user has opted into multi-agent orchestration)

The §2 B11 rule — *every "dormant/inert" label is DISTRUSTED; per-entry positive reachability
verification before deletion* — is naturally a fan-out:

1. **Discover** — enumerate the §1 kill list into concrete files/symbols.
2. **Per-entry reachability** (parallel, one agent per symbol/cluster) — prove positively whether
   anything still reaches it, on THIS branch, with evidence. A "dormant" label is not evidence.
3. **Barrier + survivor audit (adversarial)** — the `SodiumFogOverride` case shows the failure mode;
   have independent verifiers try to prove each proposed deletion is still load-bearing.
4. **Delete in increments**, suite green + commit + push per increment.
5. **§4 regression** as the close-out live round.

Do the deletions as a single writer (agents racing on the same files conflict); use the fan-out for
discovery, reachability proof, and adversarial audit.

## §E STANDING DISCIPLINE (binding, from S19_HANDOFF §0)

- **NO GUESSING / diagnose-first**; instrument before designing; logs for every path including
  failures; ≤1 Hz on the render thread; read the FULL `latest.log` for **this worktree** every run.
- **Suite gate before every commit:** `.\gradlew.bat :fabric:runCrossingGametest` (8-leg, no GUI).
  Note §4: the suite **loses its TITLE-CARD flag-OFF leg** when the `entityPortals` flag dies —
  re-shape the suite rather than letting a leg silently vanish.
- **Push every stage commit.** `git add` with **explicit file lists only**.
- **Lever discipline:** fixes DEFAULT-ON behind `-Dseamlessportals.disableX`; probes DEFAULT-OFF;
  every lever needs a `-P` row in **BOTH** `fabric/build.gradle` blocks.
- **Panels for every non-trivial mechanism**, plus a **final-diff pass against the implemented code** —
  the spec-level pass is not enough; the two worst near-misses of the polish arc were both
  spec-level-invisible.
- **26.2 ships UNOBFUSCATED** — javap the merged jar to confirm targets; never guess a signature.
- **JAVA_HOME:** `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` (Adoptium updates delete
  the old dir and break the shell — verify before blaming a build).
- **Known JVM defect:** a Temurin 25.0.2 C2 JIT bug crashes on
  `PortalRenderInfo::renderAndDecideVisibility`; both run configs carry a `CompileCommand`-exclude
  mitigation. That hs_err signature is not your bug.
- Trust the user's live observations over your own screenshot or bytecode readings.

## §F THE HANDOFF'S OWN OPENING INSTRUCTION (§6, still current apart from §B.2)

> Execute S20: FIRST the §0 must-do-firsts, then the deletion per §1 with the §2 B11 per-entry
> reachability audit (never trust a dormant label), honoring the §3 survivor list, then the §4 full
> 12-point regression as the close-out live round. Worktree isolation for the sweep; verify every
> increment; commit+push each; suite green throughout. Then open the §5 era with the dim-persistence
> fix.

---

**Ask the user before starting the §4 regression** — it is the biggest live round of the migration and
needs their time, not just yours.
