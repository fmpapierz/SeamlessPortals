# S14 HANDOFF 3 — resume point after the usage cutoff (2026-07-17 ~22:25)

**Read this first, then `migration/EXECUTION_PLAN.md` (the governing plan) and the S14C round
notes 5→7. The mission is unchanged: execute S14→S20 + the polish backlog with COMPLETE IP
fidelity, then ask about C2 (Sodium) / C7 (NeoForge). Run autonomously; stop only for live
runClient rounds and recorded checkpoints.**

## 0. Standing rules (unchanged, memory-backed)
- **NO GUESSING** (user rule, emphatic): instrument exhaustively BEFORE theorizing; levers +
  capture protocols first. Trust the USER's observations over screenshot interpretation.
- Ultracode workflows with **Fable adversarial verification for every fix**; Opus for tracer
  fan-outs (model-tier policy memory). Every fix-verify round this stage caught real defects.
- Commit + push EVERY increment to origin `claude/nifty-kepler`. Commit via `git commit -F` or
  here-string; end with the Co-Authored-By line.
- READY gate: the user launches runClient only on explicit READY with all workflows idle.
- Confirm visual interpretations with the user before acting; they frame portals in black wool.
- Java process rules: only kill JVMs whose command line contains THIS project's path; NEVER
  `gradlew --stop`; never touch idea64.
- Build gate: `.\gradlew.bat :common:compileJava :fabric:compileJava --console=plain` (or
  `build.bat`).

## 1. WHERE THINGS STAND (S14 rung 2, task #2 in_progress)

**Rung-2 scorecard:** steps 1–4 PASS, 5 PASS (loader collapse verified — the report simply stops
listing collapsed loaders; the "COLLAPSED message" expectation was script wording), 6 PASS
(weather state sync; window rain visuals = S18-deferred), 8 PASS (window-shines-through-water-fog
= **user-verified authentic IP** via side-by-side → logged as a planned deviation in
`ENTITY_PORTAL_MIGRATION_BRIEFING.md` §5), 9 PASS. Wedge defect (rounds 2–6): **CLOSED,
user-confirmed** ("worked"). Step 5+7's far-walk terrain wipe: **CLOSED, user-confirmed
2026-07-17 ("all good now")** after the full S14.42–44 verify ladder; bonus same round:
entities now visible THROUGH the window. **THE ACTIVE ITEM = the teleport flash** (§2).

**Closed this session:**
- **S14.40/41 — the sky wedges (CLOSED, user-confirmed live):** painter = vanilla
  `ChunkCullingDebugRenderer`'s UNGATED captured-frustum rainbow visualization (6 alpha-0.25
  frustum-plane quads + black wireframe), emitted by the dest extract because the driver sets a
  captured frustum on the virtual camera (the applyFrustum-skip trick, SecondaryWorldRenderCore
  ~:388) and the shell swaps it into `gameRenderer.mainCamera` (MyGameRenderer ip_setCamera).
  Fix: the gizmo trio (DebugRenderer.emitGizmos + GameTestBlockHighlightRenderer.emitGizmos +
  extractGizmos) SKIPS BY DEFAULT during `SecondaryWorldRenderCore.isDestExtracting`
  (`MixinLevelExtractor_DestSubLevers`); A/B lever `debug_allow_dest_extract_gizmos`. The
  round-5 particle theory was REFUTED as the wedge painter but the particle guard STAYS
  (`MixinParticleEngine`, lever `debug_allow_dest_particle_extract`) — its real symptom is
  main-world particle wipe (block-era-documented). PATTERN (2-for-2, sweep-verified NO third):
  block-era substrate guards (`ParticleEnginePortalSkipMixin`, `DebugRendererPortalSkipMixin`)
  were inert flag-ON — their gates are block-era-only. Port-notes: S14C-round5 (superseded
  header), S14C-round6 (CLOSED).
- **S14.42 — the far-walk terrain wipe (fix in tree, PRE-VERIFY commit):** root cause =
  `sog-loadedchunks-netdrop`, three tracers converged + adversarial ranker HIGH (workflow
  wf_20020335-d7c; full ranked output preserved at
  `%LOCALAPPDATA%/Temp/claude/C--Users-warwa-ModDev-Portals-Portal-26-2/c0dc8003-3d2b-477b-9db3-ff49c7419754/tasks/wwprd718r.output`).
  Mechanism + fix design: port-note **`S14C-round7-terrain-wipe.md`** (read in full). Fix =
  `tickSecondaryDeltaPump` (POST_CLIENT_TICK; drains secondary delta windows ≤1 tick;
  clears-in-place, NEVER flips — a second flip caller would break the feed's window-identity
  guard) + `applyLoadedDeltasResolved` (added∩removed resolves by live hasChunk; pump=in-place,
  Step-5 feed=copy-on-intersection). Probe: `RenderChainProbe` self-arms ~20s @1Hz on every
  promote (`sogLoaded << ldChunks` = the poison signature) + one-shot switch
  `debug_dump_render_chain` + enriched promote log.

## 2. THE RESUME TASK — finish the S14.43 RE-verify round

**UPDATE:** verify round 1 COMPLETED before cutoff (FAIL — 3 BLOCKERs), folded at `4a53e0d`.
**Round 2 re-run COMPLETED (`wf_4089f91b-610`): PASS×2 lenses** — all round-1 fixes confirmed;
one MAJOR (the cold-promote residual is REACHABLE) **folded immediately** as
`SecondaryWorldRenderCore.preResolvePromotedWindow` (promote-time in-place truth-resolution of
the toDim's CURRENT window, resolution-only, called at the top of
`promoteAndDemoteOnPlayerDimensionChange`; also closes the warm promote-instant LOW residual);
3 MINORs ledgered. Full record: port-note `S14C-round7-terrain-wipe.md` §0/§0b.

**Hardening verify round (`wf_c9c7c2e1-b00`) COMPLETED: PASS×2**; S14.44 @ `70a0350` pushed;
READY issued; **RETEST PASSED (user 2026-07-17: "all good now")** — the wipe thread is DONE.

**NEW ACTIVE ITEM — the teleport flash (was §3-1), now under NO-GUESSING instrumentation:**
user observation: the flash appears ONLY on distant-fog/sky pixels — looking down at nearby
terrain during a crossing shows NO flash (same far-depth pixel class the wedges painted).
User directive: do NOT assume; add full debugging logs to verify everything; agents = Opus for
simple/mechanical tasks, Fable ONLY for complicated (verify/design). ALSO: crossing STUTTER —
user's IP side-by-side (instance `C:\Users\warwa\curseforge\minecraft\Instances\1.21.1 fabric
ip`, MC 1.21.1 + IP 6.0.6, no Sodium/Iris) feels ZERO-lag; ours hitches → same capture kit
profiles it; "inherent mesh cost" may NOT be asserted without numbers.

State: Opus enumeration DONE (`wf_92129b0d-5f7`, 74 sites); capture kit IMPLEMENTED, Fable
kit-verify **PASS** (`wf_a144117b-011`, aliasing CLEAN, 4 MINORs folded), compile GREEN,
**committed as S14.45 + pushed; READY issued for the CAPTURE RUN.** Port-note
**`S14C-round8-teleport-flash.md`** has everything: candidates, row schema, kit-verify-corrected
signatures, capture protocol (§3 — crossings auto-dump `==== TELEPORT-FLASH CAPTURE ====`
blocks; user hands over latest.log + the IP-side horizon-crossing flash check in the 1.21.1
instance). NEXT SESSION RESUME = read the user's capture blocks → classify flash (a/b/c or
GPU-escalation via DrawCallTrace) + stutter profile vs the IP zero-lag bar → THEN fix design
(Fable) with a Fable verify round. Then §3 items (sign-off etc.) and the §4 ladder.

Then: fold findings (BLOCKER/MAJOR before READY) → commit → push → **READY + retest protocol**
(port-note §4): far-walk 200+ → wait ~1 min → return → cross (both directions, repeat step 7's
long walks); expect NO wipe; residue → `/imm_ptl_client_debug debug_dump_render_chain_enable`
WHILE wiped → the log's `sogLoaded` vs `ldChunks` names it.

## 3. Remaining S14 items after the retest passes
1. **Teleport-flash classification** (open since round 2: "teleport flash is a little less") —
   ask the user whether it's still visible post-S14.42; classify vs IP (user can side-by-side).
2. Rung-2 sign-off: one-line PASS/FAIL per step from the user.
3. S14 close-out port-note + update `migration/EXECUTION_PLAN.md` S14 PROGRESS + memory.
4. S20-removal ledger check: rounds 5–7 added levers/probe entries (each port-note has a §Ledger).

## 4. Then the ladder (per EXECUTION_PLAN.md — read each stage section before starting)
- **S15**: entity traffic + F2/F3 reproduce-then-apply + gametest. **Surface the C4 A/B
  clip-mechanism switch** (`IPGlobal.crossPortalEntityClipMechanism`) at every entity live test
  (user decision memory c3-c4). Watch: the round-5 particle guard's A/B lever during rain/particle
  scenes; the MEDIUM amplifier (portal-cone compiles a beat late after far-walk crossings —
  expected, self-healing).
- **S16**: nether portal generation (U12 remainder) — rung 4, obsidian portals.
- **S17**: THE DEFAULT FLIP + full 12-point regression checklist (briefing). Before the flip:
  re-check the block-era-guard sweep verdict (wdbmtoea8 output file, same tasks dir) — 2 guards
  re-keyed, ~10 confirmed block-era-only.
- **S18**: R3 runtime + trailing render periphery — now THREE user-verified IP-parity items
  (round-8 port-note §7): dest CLOUDS in the portal view (S13-J deviation, CloudContext-vs-single-
  ring-buffer isolation), dest break PARTICLES through the window (deferred item ② in
  MixinParticleEngine / per-dim engines), dest targeted-block OUTLINE through the window (new —
  the decomposed dest pass has no hit-outline step). Plus dest weather isolation; C4 A/B again.
- **S19**: ask the user per checkpoint C1 (default skip).
- **S20**: cleanup/deletion — honor every §Ledger in port-notes S14A→S14C-round7 (the PUMP +
  RESOLVER + the two default guards SURVIVE S20 as permanent substrate; levers/probe/DrawCallTrace
  are removal candidates).
- **Polish backlog** (briefing §5, incl. the NEW underwater-fog-composite deviation) → then ask
  the user about C2 (Sodium) + C7 (NeoForge). Do not start either unprompted.

## 5. Next-session opening prompt (paste-ready)

> Read `migration/S14_HANDOFF_3.md` in full, then continue the migration exactly from its §2:
> re-run the interrupted S14.42 verify workflow from the persisted script, fold findings, commit
> + push, then give me READY with the far-walk retest protocol. After my retest passes, finish
> the remaining S14 items (§3) and proceed autonomously S15→S20 + polish per
> `migration/EXECUTION_PLAN.md`, with all standing rules from §0 (NO GUESSING/instrument-first,
> Fable-verify every fix, commit+push every increment, READY gates for my runClient rounds,
> C1/C2/C7 are ask-first).
