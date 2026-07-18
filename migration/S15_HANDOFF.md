# S15 HANDOFF — entity traffic + F2/F3 + gametest (rung 3)

**Read this first, then `migration/EXECUTION_PLAN.md` §S15 (the governing spec) and
`ENTITY_PORTAL_MIGRATION_BRIEFING.md`. S14 is CLOSED (rung 2 user-signed-off 2026-07-18;
its defect ledger of record = `migration/port-notes/S14C-round8-teleport-flash.md` §1-§16).
The mission is unchanged: execute S15→S20 + the polish backlog with COMPLETE IP fidelity,
then ask about C2 (Sodium) / C7 (NeoForge). Run autonomously; stop only for live runClient
rounds and recorded checkpoints.**

## 0. STANDING RULES (memory-backed; every one has earned its keep — violations cost live rounds)

1. **NO GUESSING (user rule, emphatic):** instrument exhaustively BEFORE theorizing. Capture
   protocols + lever-gated dumps are ALWAYS the first move on any live defect: enumerate
   candidate mechanisms (Opus fan-out), build the discriminating probe, have the user capture,
   classify from evidence, THEN fix. Trust the USER's observations over screenshot readings —
   and confirm visual interpretations with the user BEFORE acting on them (they frame portals
   in black wool). When a fix is possible-but-unproven, prefer shipping the discriminator
   first (the shadow saga's dirty=true/false dump routed three different fixes correctly).
2. **ADD DEBUG INSTRUMENTATION DEEPLY when needed** — the S14 kit pattern: always-on in-memory
   ring rows + event-armed windows + ONE batched log write (NEVER per-frame log4j on the
   render thread — ~130ms stalls; memory render-thread-logging-log4j-stall). Reuse the
   existing kit: `TeleportFlashProbe` rows (`ms/dim/fog/sky/visSec/compQ/pMs/dMs/vy/dp/dpMs/
   fs/rcLog`), `RenderChainProbe`, `DrawCallTrace` (per-pass GL attribution),
   `LightSectionDump` (`debug_dump_light_section`), the `debug_capture_flash` lever, and the
   `imm_ptl_client_debug` lever registry (ClientDebugCommand ~:598-800). Extend rows rather
   than inventing new channels; probes get verified too (probe bugs corrupted measurements
   twice: the rcLog false-flag, the dpMs nested double-count).
3. **MODEL TIERS (user rule, re-affirmed twice):** workflow agents = **Opus for
   simple/mechanical tasks** (tracer fan-outs, enumeration, code sweeps, doc passes) —
   ALWAYS set `model: 'opus'` explicitly on those; **Fable ONLY for complicated work**
   (adversarial verification, hard design, deep mechanism traces) — `model: 'fable',
   effort: 'high'`. Never let agents silently inherit the session model. The main loop
   (design/synthesis/folding) is Fable.
4. **Fable-VERIFY EVERY FIX before the user runs it** (ultracode workflows). The order is
   fix → verify → fold → commit → READY → user test. Verify rounds caught real BLOCKERs in
   S14 roughly every other round (incl. two fixes that would have shipped broken and one that
   was REVERTED as a remesh-storm risk). Fold verify CORRECTIONS into the port-note — a
   verify that corrects the trace's mechanism is evidence, not noise. Instrumentation-only
   diffs get a compact single-lens verify.
5. **READY GATE:** the user launches runClient only on explicit READY with all workflows
   idle + compile gate green. Casual runs on uncommitted diffs are allowed ONLY with an
   honest risk statement (visual-only worst case vs crash-class — say which; the one time it
   was crash-class the user was told to wait).
6. **COMMIT + PUSH EVERY INCREMENT** to origin `claude/nifty-kepler` (memory
   push-commits-to-github; backup ref `backup/pre-migration-remote-tip`; never force-push
   without a backup). Commit via `git commit -F -` heredoc; end with the Co-Authored-By
   Claude line. CHECK `git status` after multi-path `git add` — one typo path silently
   dropped a commit's code half once (05dc174/b034395).
7. **Build gate:** `.\gradlew.bat :common:compileJava :fabric:compileJava --console=plain`
   (or `build.bat`). Green before every verify launch and every commit.
8. **CHECKPOINTS:** C1 (S19, default skip), C2 (Sodium), C7 (NeoForge) are ASK-FIRST — never
   start unprompted. C4 is DECIDED: keep BOTH clip mechanisms switchable — **surface the
   `IPGlobal.crossPortalEntityClipMechanism` A/B switch at EVERY entity live test in this
   stage** (user decision memory c3-c4).
9. **Java process rules (global CLAUDE.md):** only kill JVMs whose command line contains
   THIS project's path; NEVER `gradlew --stop`; never touch idea64.
10. **IP SIDE-BY-SIDE is the classification court:** the user's runnable original IP =
    `C:\Users\warwa\curseforge\minecraft\Instances\1.21.1 fabric ip` (MC 1.21.1, IP 6.0.6,
    no Sodium/Iris; logs readable directly). One same-scenario glance settles "bug or IP
    behavior" — it closed 5 disputes in S14. Use it BEFORE diagnosis cycles on anything
    ambiguous.
11. **Port discipline:** every change is a 1:1 IP port unless ledgered as an approved
    deviation (briefing §5). IP splits per-frame routines across tick + render-end — find
    BOTH halves. Any per-entity-type crossing behavior must exist in EVERY crossing path
    (until the paths unify). Every packet-handler mixin needs isSameThread. Any new
    old→new entity copy under PLAYER REUSE must be identity-audited (self-copy traps), and
    any entity holding a live reference to a crossing player must be audited (the boost
    rocket injected velocity cross-dim through a retained ref).

## 1. NEW INVARIANTS FROM S14 (the expensive lessons — memory s14-live-rung-lessons)

- **Light cadence:** vanilla keeps pollLightUpdates+runLightUpdates ADJACENT and BEFORE any
  dirty-mark consumer. Any dim the mod drives must preserve that adjacency (tickRemoteWorld
  does now; the promote plugs the crossing frame). Re-sent light corrections publish
  SILENTLY (no onLightUpdate callbacks) — their drain-time mark is the ONLY heal signal.
- **One-shot dirty marks:** any consumer of SectionUpdateTracker marks must never run
  between a light DRAIN and its PUBLISH, must honor vanilla's first-compile gate
  (`dirty && (compiled || hasAllNeighbors)`), and must never consume marks belonging to the
  MAIN extract (the armed fold's isMainDimArm guard).
- **Tracker/extractor identity is everything** (nether-block-freeze class): a fresh
  SectionUpdateTracker is ALL-DIRTY by construction — never construct one casually; reuse
  the live tracker across promote/demote (continuity is in ClientWorldLoader).
- **Gate audits are 3-for-3:** every block-era guard checked so far was inert flag-ON.
  Positively verify reachability before trusting ANY block-era-era guard.
- **Extractors that don't run every frame accumulate multi-frame delta windows** that break
  vanilla's order-dependent set application — consumers must be truth-resolved or drained
  on a fixed cadence (the S14.42 pump family, all still in place as permanent substrate).

## 2. S15 SCOPE (EXECUTION_PLAN §S15 — read it in full before starting)

Contents: non-player crossings via the ONE unified path
(`Portal.SERVER_PORTAL_TICK_SIGNAL` → `getEntitiesToTeleport` → `teleportRegularEntity`);
**F2/F3 reproduce-then-apply** with dated register evidence in commit messages; **gametest**
crossing smoke beside TitleCardCapture. NOTE: **F2 (attached-firework skip) effectively
PRE-COMPLETED in S14.47** — it reproduced live on the IP path (log-proven: the
ServerTeleportationManager teleport attempts) and the fix (shouldEntityTeleport skip + the
changePlayerDimension orphan-discard sweep) is applied + Fable-verified + user-confirmed;
S15's job is only to RECORD that as the F2 register entry. F3 (preserveTransientHurtState)
still needs the reproduce-then-apply pass on the ported path.

The (d) runClient protocol is in the plan (items/arrows/cow-panic/elytra/straddle/vehicle/
gametest). Surface the C4 A/B switch at every one of these tests.

**S15 watch list (user-spotted in S14, this stage's scope):**
- The player CANNOT see their own body through recursive portals (IP shows it; ours only a
  split-second during crossing) — the render-player-itself feature in portal views.
- Entities are INVISIBLE in recursive (layer ≥2) portal views — layer-1 entity rendering
  works (S14-confirmed through-window visibility). Both = dest-pass entity rendering at
  nested layers.
- The round-5 particle guard's A/B lever (`debug_allow_dest_particle_extract`) during
  rain/particle scenes.
- The MEDIUM amplifier: portal-cone terrain compiles a beat late after far-walk crossings —
  expected, self-healing, NOT a defect.
- Straddle rendering is judged to the CURRENT status-quo standard (full two-sided render is
  S18; the deferred-polish family is in memory entity-vanish-cooldown-mirror-gate).

## 3. THE LADDER AFTER S15 (each stage: read its EXECUTION_PLAN section first)

- **S16**: nether portal generation (U12 remainder) — rung 4, obsidian portals, effort XL.
- **S17**: THE DEFAULT FLIP + the 12-point regression checklist (briefing). Pre-flip:
  re-check the block-era-guard sweep verdict; the S17 hardening ledger from S14 (port-note
  round-7 §5 + round-8): the capture-point resolver mixin (closes the main-dim LOW residual
  + the sub-tick re-poison race), the setLevel re-arm assertion.
- **S18**: R3 runtime + trailing render periphery — now FOUR ledgered items: dest clouds
  (S13-J deviation), dest break particles (deferred item ②), dest targeted-block outline,
  the multi-portal PARITY GAP (dpMs-attributed pass-internal, recursion-scaled; candidates:
  IP nested-portal aperture/frustum culling parity, per-pass overhead, fix the dpMs bracket
  to top-level-only). Plus dest weather isolation; C4 A/B again.
- **S19**: C1 DECIDED 2026-07-18 — **BUILD, not skipped** (user: "we are not skipping
  s19"); wand + dim stack + alternate dims + compat layers + ModMenu GUI runtime bring-up;
  R13g dynamic-dimension design becomes real scope. Per-feature ordering surfaced at stage
  open. **S20**: cleanup honoring every §Ledger
  (the PUMP + RESOLVER + preResolvePromotedWindow + the frontier gate + the light-cadence
  plugs are PERMANENT substrate; the probes/levers/DrawCallTrace are removal candidates).
- **Polish backlog** (briefing §5): incl. the underwater-fog composite deviation and the NEW
  full-32-RD keep-loaded in-game toggle (block-era parity, default OFF). Then ask C2/C7.

## 4. Next-session opening prompt (paste-ready)

> Read `migration/S15_HANDOFF.md` in full, then execute S15 per
> `migration/EXECUTION_PLAN.md` §S15: the unified entity path, F2 (record the S14.47
> pre-completion) / F3 reproduce-then-apply, the gametest, and the §2 watch-list items,
> with all §0 standing rules (NO GUESSING/instrument-first, Opus for simple agent tasks and
> Fable only for verify/design, Fable-verify every fix, commit+push every increment, READY
> gates for my runClient rounds, C4 A/B surfaced at every entity test, C1/C2/C7 ask-first).
> Proceed autonomously S15→S20 + polish.
