# PASTE PROMPT — next engagement: three portal-edge artifacts (MB / bloom / seam)

Copy everything below the line into a fresh session.

---

Continue the Seamless Portals iris shaders-ON work in the worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`
(branch `iris-on/is5-shadow`, tip `ba256ea`, pushed).

READ FIRST, in order:
1. `migration/MB_BLOOM_SEAM_HANDOFF.md` — THE GOVERNING DOC. The three reports verbatim, what is
   already in the tree, the four discriminators to run before theorising, the hazards inherited from
   the recursion arc, and the discipline.
2. `migration/MB_SMEAR_VERDICT.md` — read this properly, not skimmed. It records TWO submitted
   mechanisms both being refuted on their decisive step, in opposite directions. It is the local
   proof of how easy it is to be confidently wrong in this exact subsystem.
3. `migration/MB_SMEAR_HANDOFF.md` §00 (the closed smear arc and `IrisDestPrevCamera`) and §1c (the
   original bloom-ring commission, never executed — report B is probably it).
4. `migration/C2_JIT_PROTECTION.md` §7 — the traps list. Every entry there cost a wrong conclusion.
5. Memory: `mb-smear-open`, `instrument-answers-a-different-question`, `recursion-shaders-on-open`,
   `no-guessing-deep-debug-logs`, `measure-at-the-draw-lessons`, `temurin-c2-jit-crash`.

## THE TASK — three user-reported edge artifacts

**A.** Third person, player silhouetted against a portal, Motion Blur on: a border/edge traces the
player carrying colour/lighting that belongs to the portal window behind them.

**B.** A thin sliver of light around the portal window's edge where it meets an obsidian frame /
touching / clipping blocks. **Reported with Motion Blur ON *and* OFF.**

**C.** Motion Blur on: a windowed portal in an obsidian frame appears to SHIFT slightly against its
frame, making the seam visible — possibly terrain showing through at the seam. The user is explicitly
unsure of this mechanism; treat "terrain showing through" as a hypothesis, not a finding.

They may share a mechanism or be three bugs. **Establishing which is the first job, not an
assumption.**

## START HERE — four discriminators, each one live run, before any theory

1. Does **A** survive `-PdisableIrisDestPrevCamera`? If yes, the velocity chain is not involved and
   the whole IS5-MB line is irrelevant to it.
2. Does **B** really survive Motion Blur being off? The user says so — CONFIRM it. It is the most
   valuable single fact in the set: it separates B from A and C and points at bloom/composite.
3. Does **B** survive disabling the C3-BLOOM aperture mask (check
   `IPGlobal.isIrisBloomApertureMaskActive` for the live lever)? If the sliver vanishes, this is a
   mask-coverage bug at the aperture edge, not a bloom bug.
4. Is **C** just **B** seen in motion? Both sit at the frame/window boundary and both need blocks
   touching the aperture. Test before treating them as separate.

Plus one cheap constraint: do B and C reproduce on a portal in **open air** with nothing touching the
aperture? If they need the frame, that bounds the mechanism hard.

## HAZARDS FROM THE RECURSION ARC (landed 2026-08-02 — this is new since the last MB work)

- Portal recursion now runs to `maxPortalLayer` shaders-ON (default 5, the user runs 10), so
  **composite chains now run PER NESTED LAYER**.
- **`IrisBloomApertureMask.armed` is a single slot and now nests** — an inner layer's disarm retires
  the outer layer's arm. Flagged in review, NOT fixed. If the bloom mask is implicated in B, fix the
  nesting first or every measurement will lie.
- **The 1 Hz probes and `IrisCompositeCensus` are not layer-keyed** — an inner pass can consume the
  outer's rate-limit slot and be filed under the wrong window. Layer-key before trusting per-window
  attribution.

## DISCIPLINE (non-negotiable)

No guessing — instrument first. Read the FULL `latest.log` each run, and beware that Minecraft
ROTATES it at startup (grepping too early matches the previous run). **Verify every lever on the LIVE
PROCESS** (`Get-CimInstance Win32_Process`, match the worktree path + `KnotClient`) — legs here have
been voided by levers that silently never reached the JVM. **Verify the instrument before believing
it**: in the last arc a probe sampled after its state had unwound, a grep targeted an overlay that
writes no log line, and a match pattern was broad enough to catch every mod's lambdas — all three
printed a confident, wrong, innocent answer. Ask of every probe what input would make it print the
GUILTY answer. GL state only at `GlCommandEncoder.trySetup` RETURN. ONE VARIABLE PER LEG. Every fix
A/B-proven in BOTH directions with a lever that reproduces the defect on command. A leg without its
landing proof is VOID, not a refutation.

`.\gradlew.bat :fabric:runCrossingGametest` before every commit — and capture enough output to read
the leg lines, not just `BUILD SUCCESSFUL` (a `tail` filter once discarded them and produced a
meaningless gate). Push every stage commit; `git add` explicit file lists only; Java cleanup by
own-project PID only, never `gradlew --stop`.

Run the client:
`.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true`

## AFTER THIS

- The **C2 upstream bug report** (`migration/C2_JIT_BUG_REPORT.md`) is STALE — it predates the
  2026-08-02 findings: the on-demand reproduction recipe, the profile-dependence mechanism, the
  refuted offline reproducer, and the proof that `dontinline` fixes it. Rewrite before filing at
  https://bugreport.java.com/ (Java SE / hotspot / compiler).
- A parked `GL_INVALID_OPERATION: Invalid format` from iris `RenderTargets.copyPreHandDepth` during a
  nested cross-dim render (3 occurrences, only at nether pipeline creation).
- `maxPortalLayer` is never synced to dedicated servers, so the portal-chain chunk loader follows the
  server's value there.
