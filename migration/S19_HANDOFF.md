# S19 HANDOFF — the peripheral tail (wand / dim stack / alternate dims / config GUI)

**Read this first, then `migration/EXECUTION_PLAN.md` §S19 (the governing spec) and
`ENTITY_PORTAL_MIGRATION_BRIEFING.md`. S18 is CLOSED (user-signed-off 2026-07-18: "ALL those
things work"; C4 DECIDED — `SUBMIT_ORDER_UNIFORM` stays, B live-proven as fallback; ledger of
record = port-note `S18-render-periphery.md` §1-§10). S15-S17 ledgers unchanged. The mission is
unchanged: execute S19→S20 + the polish backlog with COMPLETE IP fidelity, then ask about C2
(Sodium) / C7 (NeoForge). Run autonomously; stop only for live runClient rounds and recorded
checkpoints.**

## 0. STANDING RULES (memory-backed; every one has earned its keep — violations cost live rounds)

1. **NO GUESSING (user rule, emphatic):** instrument exhaustively BEFORE theorizing. Capture
   protocols + lever-gated dumps are ALWAYS the first move on any live defect: enumerate candidate
   mechanisms (Opus fan-out), build the discriminating probe, have the user capture, classify from
   evidence, THEN fix. Trust the USER's observations over screenshot readings — and confirm visual
   interpretations with the user BEFORE acting (they frame portals in black wool). When a fix is
   possible-but-unproven, prefer shipping the discriminator first.
2. **ADD DEBUG INSTRUMENTATION DEEPLY when needed** — the S14/S15 kit: always-on in-memory ring
   rows + event-armed windows + ONE batched log write (NEVER per-frame log4j on the render thread —
   ~130ms stalls). Kit inventory: `TeleportFlashProbe` rows, `RenderChainProbe`, `DrawCallTrace`,
   `LightSectionDump`, the `debug_capture_flash` lever, the `imm_ptl_client_debug` lever registry.
   NEW since S18: the config-load line `crossPortalEntityClipMechanism = X` makes A/B sessions
   self-documenting. Extend rows rather than inventing channels; probes get verified too.
3. **MODEL TIERS (user rule, re-affirmed):** workflow agents = **Opus for simple/mechanical tasks**
   (tracer fan-outs, enumeration, sweeps, doc passes) — ALWAYS set `model: 'opus'` explicitly;
   **Fable ONLY for complicated work** (adversarial verification, hard design, deep mechanism
   traces) — `model: 'fable', effort: 'high'`. Never let agents silently inherit the session
   model. The main loop (design/synthesis/folding) is Fable.
4. **Fable-VERIFY EVERY FIX before the user runs it.** Order: fix → verify → fold → commit →
   READY → user test. S18 track record: 10 verify rounds; TWO FAILs caught real crash classes
   pre-ship (the layer-0 EmptyStackException + stranded-swap chain; the same-dim fabulous particle
   leak + the post-crossing cloud-texture nuke); the round-1 LOG AUDIT caught the one that shipped
   (the fabric-hook NPE — FABRIC API'S OWN INJECTED HANDLERS are part of the verify surface now:
   vanilla methods can carry fabric mixins that assume the real framegraph context). Fold verify
   CORRECTIONS into the port-note; instrumentation-only diffs get a compact single-lens verify.
5. **READY GATE:** the user launches runClient only on explicit READY with all workflows idle +
   compile gate green + THE GAMETEST SUITE GREEN. Casual runs on uncommitted diffs only with an
   honest risk statement.
6. **COMMIT + PUSH EVERY INCREMENT** to origin `claude/nifty-kepler` (backup ref
   `backup/pre-migration-remote-tip`; never force-push without a backup). Commit via
   `git commit -F -` heredoc; end with the Co-Authored-By Claude line. CHECK `git status` after
   multi-path `git add`. Fresh jars on user request: `gradlew :fabric:build -x test`.
7. **Build gate:** `.\gradlew.bat :common:compileJava :fabric:compileJava --console=plain`
   (+ `:neoforge:compileJava` when NeoForge files are touched). Green before every verify launch
   and every commit.
8. **THE GAMETEST SUITE = the per-stage gate:** `gradlew :fabric:runCrossingGametest` — 8 legs,
   ~60s. Run before every READY and after every render-substrate change. It seeds
   `entityPortals=true` + `initialScreenShown=true`; the TITLE-CARD run config is pinned flag-OFF.
   HONESTY RULE from S18: state what the suite CANNOT exercise (it is first-person, no-GUI —
   S19's wand/GUI features are all live-round territory; say so at every READY).
9. **CHECKPOINTS:** C1 DECIDED (S19 IS BUILT — "we are not skipping s19"); **C4 DECIDED
   (S18 A/B: SUBMIT_ORDER_UNIFORM stays; B wired as fallback until the S20 loser cleanup)**;
   C2 (Sodium 0.9.1 retarget) / C7 (NeoForge) are ask-first and come ONLY AFTER S20 + polish.
   **Per-feature ordering for S19 is a stage-open SCOPE QUESTION for the user** (not a re-ask of
   C1): surface the feature list + proposed order, let them pick priorities.
10. **Java process rules (global CLAUDE.md):** only kill JVMs whose command line contains THIS
    project's path; NEVER `gradlew --stop`; never touch idea64.
11. **IP SIDE-BY-SIDE is the classification court:** the user's runnable original =
    `C:\Users\warwa\curseforge\minecraft\Instances\1.21.1 fabric ip` (MC 1.21.1, IP 6.0.6). One
    same-scenario glance settles "bug or IP behavior" — S18 settled melee-through-portal
    (IP-inherited, blocks-only) from source alone; use the court BEFORE diagnosis cycles.
12. **Port discipline:** every change is a 1:1 IP port unless ledgered (briefing §5). IP splits
    per-frame routines across tick + render-end — find BOTH halves. Every packet-handler mixin
    needs isSameThread. Old→new copies under PLAYER REUSE get identity-audited. **User-KEPT
    improvements over IP (do NOT "fix back"): seamless ender pearls; cross-dim panic transfer.**
13. **THE GATE-AUDIT RULE (4 scalps):** never trust a "dormant/inert" label — positively verify
    reachability at the file. ALL EXCLUSIVITY_LEDGER B11 "dormant" entries are formally DISTRUSTED:
    per-entry reachability check before any S20 deletion. NEW S18 entry for that sweep:
    `ParticleEnginePortalSkipMixin` (block-era gate, flagged B11-class).
14. **26.2 renders frames MID-PACKET:** every per-frame consumer must SKIP (never assert) on the
    transient `mc.player`/`mc.level` mismatch frame.
15. **26.2 references:** decompiled vanilla = `C:\Users\warwa\ModDev\mc262-ref` (MOJANG mappings);
    IP 1.21.3 source = `C:\Users\warwa\ModDev\ImmersivePortalsMod`; the research corpus =
    `migration/` (16 slices, DEPENDENCY_ORDER, API_RISKS).

## 1. NEW INVARIANTS SINCE THE S18 HANDOFF (memory `s18-lessons` + port-note §1-§10)

- **Dest (portal) passes run NO framegraph** — features draw through ONE synchronous
  `renderAllFeatures(storage)` in `SecondaryWorldRenderCore`; Fabric Level render events NEVER
  fire for dest passes; mod-code call sites beat mixins there. Consequences already ledgered:
  no `executeOutline`/glow post-chain in portal views (the spectral-glow residual); afterTerrain
  submits drain before dest translucent terrain.
- **The LAYER-0 exposure class:** `renderDestWorld` now has full-frame layer-0 callers
  (CrossPortalViewRendering, GuiPortalRendering). Every stack-peek, layer-keyed capture, and
  renderLevel-scoped per-frame capture must be layer-0-safe. The S18.2 folds (Step-10.5 gate,
  try/finally shell restore, mainChunkSampler null-refusal, projection-capture null, stencil exit)
  are the pattern.
- **FABRIC API'S INJECTED HANDLERS are a hazard class:** vanilla methods (e.g.
  `submitBlockOutline`) carry fabric-rendering-v1 mixins that eagerly deref Fabric's per-frame
  context — null outside the real framegraph → NPE on decomposed portal passes. The mod-side
  vanilla-copy through PUBLIC collector APIs is the fix pattern (`submitDestBlockOutline`).
  LATENT LEDGERED: `submitFeatures(false)` still fires Fabric's COLLECT_SUBMITS with an unprepared
  context (safe with zero handlers; a third-party registrant could NPE → C2/compat item).
- **Per-dim renderer isolation** for vanilla renderers with shared MappableRingBuffers
  (CloudRenderer): once-per-dim-per-frame cap, mirror reload-loaded data from the MAIN instance
  (**null = NO-INFORMATION** — post-crossing `client.levelRenderer` is permanently the per-dim
  renderer with a null cloud texture), per-instance endFrame in the TAIL walk, close on cleanup,
  fabulous skip. Weather has NO ring buffer (plain glBufferSubData) — isolation there is defensive.
- **Particles (the S18 architecture):** IP's SINGLE global engine holds multi-world level-tagged
  particles (remote animateTick + redirected level events spawn them — already live). Rendering =
  `IEParticleManager.ip_extractIsolated`: fresh caller-owned QuadParticleRenderStates + the REVIVED
  `RenderStates.shouldRenderParticle` (world + isOnDestinationSide-0.5) + the >4-portals skip +
  fabulous gate. The MAIN-pass world filter = `MixinQuadParticleGroup`. The S14.40 HEAD-cancel
  stays armed on the vanilla path. Do NOT build per-dim particle engines flag-ON — that plan was
  overturned by trace (it would deviate from IP).
- **The renderer's `visibleSections` FIELD is the per-pass section list** (portal_getChunkInfoList
  aliases it; Step-9 discovery refills it in place) — the same-dim BE extract rides it; the
  identity chain is verified for all four pass classes.
- **C4 mechanics:** both clip mechanisms stay wired until S20; the config line logs the active one.
- **dpMs is TOP-LEVEL-ONLY since S18.6** — never compare pre/post-S18 kit rows arithmetically;
  dp= still counts nested passes.

## 2. S19 SCOPE (EXECUTION_PLAN §S19 — read it in full; C1 DECIDED: BUILD)

**Open the stage with the SCOPE QUESTION to the user (rule 9): the feature list + a proposed
order; they pick priorities.** The compile shells for ALL of this landed at S4/S13 (constraint 7);
S19 is runtime/GUI bring-up + registration only:

1. **Portal wand runtime** (~3,900 LOC compiled since S13, 9-file package): item registration,
   overlays re-express via Gizmos/`submitCustomGeometry` (R6), interaction runtime
   (`PortalWandInteraction`), `CommandStickItem`. Live test: create/drag a custom portal.
2. **Dim stack GUI/runtime** (~2,050 LOC, all 11 files compiled): `DimStackManagement`,
   `DimStackGuiController` → screens/widgets (R13e extract model), `MixinCreateWorldScreen_CVB`
   wiring. Live test: dim-stack GUI from the create-world screen.
3. **Alternate dims RUNTIME** (~1,500 LOC compiled incl. the NoiseBasedChunkGenerator AW/AT):
   BLOCKED BEHIND the **R13g dynamic-dimension DESIGN** (now real scope) + the **F11 DimLib-stub
   runtime surface** (`DimensionAPI` + `DimensionTemplate` event wiring — static-dimension stub;
   dynamic registries load at WORLD OPEN only, the S16 lesson).
4. **Compat `On*Present` layers** + gated Sodium/Iris mixins (dead on 26.2 until those mods port —
   C2; the F21 stub classpath).
5. **ModMenu config GUI integration** (`IPConfigGUI` compiled under the F21 autoconfig stub; the
   integration runtime is what lands here).
6. **The creative TAB** (retires the portal-helper /give-only state) + `MixinEnderEyeItem_CVB` +
   the peripheral commons/client mixins (the S16 held-out cargo).

**(d) live tests:** per-feature scripts written at greenlight time (wand: create/drag a custom
portal; dim stack: GUI from create-world; alternate dims: per R13g design outcome).
**(e) Regression:** re-run crossing items 1, 2 after any wand/interaction feature (they touch
crossing paths); the 8-leg suite before every READY (it exercises NONE of the GUI surface — say
so honestly).

## 3. THE LADDER AFTER S19

- **S20:** delete the block-era system per the disposition tables; survivor audit; flag + ledger +
  holding-machinery removal; final full 12-point regression. HONOR: the permanent substrate list
  (S18 additions: the clouds/weather isolation, the particle isolated-extract family, the C4
  mechanism log removal rides the loser cleanup); the B11 per-entry reachability mandate incl.
  `ParticleEnginePortalSkipMixin`; S20 cannot "delete" what flag-OFF still needs until the flag
  dies. The S18.2 layer-0 hardening + the S15 pump family stay.
- **Polish backlog (briefing §5 + the S18 additions):** underwater-fog composite (user-approved
  deviation); full-32-RD keep-loaded toggle; portal-aware panic/escape pathfinding;
  redstone/rails/minecarts; crossing completeness (#11); first-visit worldgen graduated loading;
  light-only server forward; global-portal convert chat confirmation; **NEW FROM S18:** the
  hand/hand-item straddle sliver (user-routed); spectral-glow in portal views (outline post-chain
  re-expression, LOW); cross-portal MELEE entity attack (enhancement beyond IP — IP is blocks-only);
  **the vehicle presentation fix** (capture result: the passenger-guard warn fired ZERO times
  during a ridden crossing → the DISCARD-RECREATE axis is implicated; start from the
  adopt-in-place candidate — a vehicle-scoped predicate on the block-era adopt mixin — plus one
  instrumented ride to bisect; port-note S18 §5/§10); non-self dest block-break packets (no
  general ServerLevel.levelEvent redirect — IP-inherited); particle fresh-state pooling; row-4
  fuse-view spot-check + the dpMs multi-portal re-measure (carried minors). Then ask C2/C7
  (Sodium 0.9.1+mc26.2 exists — the F21 stubs get retargeted; users must NOT run Sodium with
  entityPortals ON until then; the S17 sweep wants a NeoForge platform-parity pass at C7).

## 4. Next-session opening prompt (paste-ready)

> Read `migration/S19_HANDOFF.md` in full, then execute S19 per
> `migration/EXECUTION_PLAN.md` §S19 (C1 DECIDED: BUILD). Open with the per-feature ordering
> SCOPE QUESTION (wand runtime / dim-stack GUI / alternate-dims+R13g design / compat layers /
> ModMenu GUI / creative tab), then proceed autonomously with all §0 standing rules (NO
> GUESSING/instrument-first + deep debug logs when needed, Opus for simple agent tasks and Fable
> only for verify/design, Fable-verify every fix, commit+push every increment, the 8-leg gametest
> suite green before every READY gate — stating honestly what it cannot exercise, the IP
> side-by-side court, the 4-scalp gate-audit rule, the mid-packet-frame rule). S18 is closed
> (C4 DECIDED: SUBMIT_ORDER_UNIFORM); §1 lists the new invariants; §3 lists the S20/polish ladder
> including the vehicle-fix capture result. Proceed autonomously S19→S20 + polish.
