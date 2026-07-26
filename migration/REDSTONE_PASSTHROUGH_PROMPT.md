# REDSTONE / RAIL / MINECART PASSTHROUGH — FRESH-SESSION STARTER (paste verbatim)

---

Start the redstone/rail/minecart passthrough engagement for Seamless Portals.

Work in the worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`
(branch `redstone/passthrough`, based on `claude/nifty-kepler` @ e8e4767).

READ FIRST, in order:
1. This file in full (`migration/REDSTONE_PASSTHROUGH_PROMPT.md`).
2. Memory `redstone-rail-minecart-deferred` — the 2026-07-08 design-workflow findings and the pinned
   geometry. NOTE it is block-era: verify every file:line against current code before asserting.
3. `ENTITY_PORTAL_MIGRATION_BRIEFING.md` (repo root) — porting discipline and the regression checklist.

---

## §0 PARALLEL-SESSION RULES — READ BEFORE RUNNING ANYTHING

**Another Claude Code session is running concurrently in this same repository** on the iris shaders-ON
polish work, in the worktree `.claude/worktrees/is5-shadow` (branch `iris-on/is5-shadow`).

- **NEVER touch `.claude/worktrees/is5-shadow`** — not its files, not its branch, not its
  `fabric/runs/client-sodium/logs/`. That session reads its `latest.log` as primary evidence every run;
  writing there silently corrupts its evidence trail.
- **NEVER run `gradlew --stop`.** It is machine-wide: it stops all daemons of that Gradle version across
  all projects and will kill the other session's live game client with no useful error. This has already
  caused two misdiagnosed "crashes" (2026-07-04, 2026-07-05). There is no project-scoped form.
- **Java process cleanup: your own PIDs only.** Filter `Win32_Process` by command line containing THIS
  worktree's path before any `taskkill`. `idea64.exe` is a JVM and appears in `jps` — a blanket sweep
  once killed the user's IDE. Full rules are in the global `CLAUDE.md`.
- Concurrent live clients are fine on the user's hardware (RTX 4090 / 24 GB); you contend for CPU/GPU,
  not correctness.

## §1 THE FEATURE

The user wants full redstone + rail + minecart functionality through a portal:
- place redstone dust / rails on the portal floor,
- redstone signals cross the plane,
- rails connect across the plane,
- minecarts travel through no-clip, as if the portal were not there.

**Geometry pinned (2026-07-08, do not re-litigate):** "the floor of the obsidian frame" means the
**BOTTOM OPENING ROW at `y = origin.getY()`** — foot level, *inside* the crossing plane. The obsidian
sill at `origin.getY() - 1` is already placeable but sits *outside* the aperture and therefore carries
nothing through. The feature needs the in-plane bottom row.

## §2 WHY IT WAS DEFERRED, AND WHY IT IS NOW UNBLOCKED (verified 2026-07-25)

Deferred on 2026-07-08 for exactly one architectural reason: **do not build this on block portals.**
On the block architecture, placing rail/redstone in the opening self-destructs the portal — vanilla
`NetherPortalBlock.updateShape` → `PortalShape.isEmpty` (air | FIRE | NETHER_PORTAL only) plus
`isComplete()` (`numPortalBlocks == width*height`) treat a rail cell as a shape-breaker, cascading the
opening to AIR, after which the mod's teardown unregisters the portal. A correct block-era Phase 1 needed
four coordinated mixins (PortalShape `isEmpty` + completeness count, `updateShape` cascade guard,
PortalDetector no-truncate, teardown suppression) — **all of which the entity migration eliminates.**

**Gate verified met on this branch:** `Portal extends Entity`
(`common/src/main/java/qouteall/imm_ptl/core/portal/Portal.java:87`) and the `entityPortals` master
switch **defaults to true** (`common/src/main/java/com/warwa/seamlessportals/config/SeamlessPortalsConfig.java`,
see the config-comment block ~line 143). Entity-portal openings are not `nether_portal` blocks, so
opening cells can natively be rail or redstone. The throwaway plumbing layer is gone.

**Favorable fact for the build:** the render opening and plane-crossing do NOT depend on per-cell portal
blocks — the shape renderer builds one quad from `PortalInfo.origin/width/height`, and
`intersectsMovement` uses `origin.Y .. origin.Y+height`. Both survive non-portal opening cells as long as
the portal geometry is preserved. **Re-verify both against current code** — those citations are block-era.

## §3 THIS IS NOVEL WORK, NOT A PORT

**Upstream IP does not implement this.** `CrossPortalRedstoneMediumBlockEntity` is an empty 2-line marker
interface; IP's only minecart mixin is a debug `lerpTo` hook. IP implements neither cross-portal redstone
nor rail/minecart continuity. The project's strict-fidelity rule ("every change is a 1:1 port of IP")
**cannot guide this feature** — every decision here is a design decision, so design panels and user
check-ins carry more weight than usual. Real IP 1.21.3 source for reference:
`C:\Users\warwa\ModDev\ImmersivePortalsMod` (NOT `E:\Immersive Portals - Copy`, which is not upstream).

**The pieces are coupled — there is no cheap "minecarts only."** A minecart travels on RAILS, which needs
placeable floor → rail connection across the plane → traversal. Scope the phases accordingly.

**Known hazards for the redstone signal bridge** (from the original design workflow):
- vanilla reads neighbours by flat `BlockPos` math with **no redirect point** — needs either an evaluator
  mixin or a bridge BlockEntity;
- needs a **value-equality anti-recursion latch** (or signals oscillate across the plane);
- needs a **cross-dimension neighbour-update push**;
- needs an **unloaded-chunk hold-last-value guard**.

## §4 STANDING DISCIPLINE (binding — same as every other engagement in this project)

- **NO GUESSING / DIAGNOSE-FIRST.** Instrument and confirm on the live client *before* designing. Add
  logs for everything including failure paths: once-only liveness on first fire, probe-readable counters,
  once-only WARNs on every silent-skip/throw path. Max 1 Hz on the render thread (per-frame logging on
  the 26.2 render thread costs ~130 ms stalls); the server thread is exempt from the log4j rule but should
  still be event-rate.
- **Read the FULL `latest.log` every run** — for this worktree only.
- **Lever discipline:** every fix DEFAULT-ON behind `-Dseamlessportals.disableX`; every probe DEFAULT-OFF;
  every lever needs a `-P` passthrough row in **BOTH** `fabric/build.gradle` blocks. A/B-attribute both
  directions.
- **Panels for every non-trivial mechanism:** 2–3 independent designers → adjudicate → 2 adversarial
  verifiers with distinct lenses → majority-bound fold → **final-diff verification against the implemented
  code**. The spec-level pass is NOT enough: the two worst near-misses of the polish arc were both
  spec-level-invisible and caught only by final-diff.
- **Model tiers (current user directive):** Opus 5 for design / adjudication / adversarial verify;
  the lighter Opus (4.8) for mechanical recon (javap, decompile, log tabulation).
- **Suite gate before every commit:** `.\gradlew.bat :fabric:runCrossingGametest` (8-leg, no GUI,
  sodium/iris absent). Push every stage commit. `git add` with **explicit file lists only**.
- **26.2 ships UNOBFUSCATED** — javap the merged jar to confirm mixin targets; never guess a signature.
- **Confirm visual interpretations with the user** before acting on a screenshot, and trust the user's
  live observations over your own screenshot or bytecode readings.
- **JAVA_HOME gotcha:** Adoptium auto-updates delete the old JDK dir and break the shell. Current:
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` — verify it exists before blaming a build.
- **Known JVM defect:** a Temurin 25.0.2 C2 JIT bug crashes on `PortalRenderInfo::renderAndDecideVisibility`;
  both run configs carry a `CompileCommand`-exclude mitigation. If you see that hs_err signature, it is
  not your bug.

## §5 REFERENCES

- Memory `redstone-rail-minecart-deferred` — the full deferral rationale and geometry.
- Saved design workflow from the original 2026-07-08 investigation (revive if useful):
  `C:\Users\warwa\.claude\projects\C--Users-warwa-ModDev-Portals-Portal-26-2\8b30ec25-238e-4b3c-af6a-51052a58d725\workflows\scripts\redstone-rail-through-portal-wf_cadf50d5-74d.js`
- `migration/EXECUTION_PLAN.md` — the governing stage plan and progress ledger.
- Upstream IP 1.21.3 source: `C:\Users\warwa\ModDev\ImmersivePortalsMod`.

## §6 SUGGESTED FIRST MOVES (do not skip to design)

1. **Re-verify the block-era claims** in §2 against current entity-portal code — specifically that an
   opening cell can now hold an arbitrary block without triggering portal teardown. This is the whole
   premise; prove it before building on it.
2. **Live smoke test, instrumented:** place a rail and a redstone dust in the bottom opening row of a live
   portal and log what happens (portal survives? block persists across reload? does anything unregister?).
   That single test settles more than any amount of static reading.
3. Only then: scope the phases (placeable floor → rail connection → signal bridge → minecart traversal)
   and run the first design panel.

---

**Ask the user before starting anything beyond recon** — this is novel design work, and the user should
choose the phase order and how far to take it.
