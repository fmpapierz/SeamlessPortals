# S18 HANDOFF — trailing render periphery + R3 runtime delivery

**Read this first, then `migration/EXECUTION_PLAN.md` §S18 (the governing spec) and
`ENTITY_PORTAL_MIGRATION_BRIEFING.md`. S17 is CLOSED (the cutover flip user-signed-off
2026-07-18; `entityPortals` default = TRUE; the (d) round 13/13; ledgers of record =
port-notes `S15-entity-traffic.md`, `S16-portal-generation.md`, `S17-cutover-flip.md`).
The mission is unchanged: execute S18→S20 + the polish backlog with COMPLETE IP fidelity,
then ask about C2 (Sodium) / C7 (NeoForge). Run autonomously; stop only for live runClient
rounds and recorded checkpoints.**

## 0. STANDING RULES (memory-backed; every one has earned its keep — violations cost live rounds)

1. **NO GUESSING (user rule, emphatic):** instrument exhaustively BEFORE theorizing. Capture
   protocols + lever-gated dumps are ALWAYS the first move on any live defect: enumerate
   candidate mechanisms (Opus fan-out), build the discriminating probe, have the user
   capture, classify from evidence, THEN fix. Trust the USER's observations over screenshot
   readings — and confirm visual interpretations with the user BEFORE acting (they frame
   portals in black wool). When a fix is possible-but-unproven, prefer shipping the
   discriminator first.
2. **ADD DEBUG INSTRUMENTATION DEEPLY when needed** — the S14/S15 kit: always-on in-memory
   ring rows + event-armed windows + ONE batched log write (NEVER per-frame log4j on the
   render thread — ~130ms stalls). Kit inventory: `TeleportFlashProbe` rows
   (`ms/dim/fog/sky/visSec/compQ/pMs/dMs/vy/dp/dpMs/fs/sde/rcLog`), `RenderChainProbe`,
   `DrawCallTrace` (per-pass GL attribution), `LightSectionDump`, the `debug_capture_flash`
   lever, the `imm_ptl_client_debug` lever registry (ClientDebugCommand — incl.
   `debug_skip_same_dim_entities`, `debug_skip_portal_entities`,
   `debug_allow_dest_particle_extract`). Extend rows rather than inventing channels;
   probes get verified too (probe bugs corrupted measurements twice in S14).
3. **MODEL TIERS (user rule, re-affirmed):** workflow agents = **Opus for simple/mechanical
   tasks** (tracer fan-outs, enumeration, sweeps, doc passes) — ALWAYS set `model: 'opus'`
   explicitly; **Fable ONLY for complicated work** (adversarial verification, hard design,
   deep mechanism traces) — `model: 'fable', effort: 'high'`. Never let agents silently
   inherit the session model. The main loop (design/synthesis/folding) is Fable.
4. **Fable-VERIFY EVERY FIX before the user runs it.** Order: fix → verify → fold → commit
   → READY → user test. Track record: the verify layer caught real blockers at S15 (the
   pearl momentum wipe), S16 (missing assets + the handlePortal ledger misclassification —
   FAIL×2 on one commit), S17 (stale docs) — plus the E7 fidelity rework and my own
   sweep-gate miss. Fold verify CORRECTIONS into the port-note; instrumentation-only diffs
   get a compact single-lens verify.
5. **READY GATE:** the user launches runClient only on explicit READY with all workflows
   idle + compile gate green + THE GAMETEST SUITE GREEN. Casual runs on uncommitted diffs
   only with an honest risk statement (visual-only worst case vs crash-class — say which).
6. **COMMIT + PUSH EVERY INCREMENT** to origin `claude/nifty-kepler` (backup ref
   `backup/pre-migration-remote-tip`; never force-push without a backup). Commit via
   `git commit -F -` heredoc; end with the Co-Authored-By Claude line. CHECK `git status`
   after multi-path `git add` (the typo lesson). Fresh jars on user request:
   `gradlew :fabric:build -x test` → `fabric/build/libs/seamlessportals-fabric-1.0.0.jar`.
7. **Build gate:** `.\gradlew.bat :common:compileJava :fabric:compileJava --console=plain`
   (+ `:neoforge:compileJava` when NeoForge files are touched). Green before every verify
   launch and every commit.
8. **THE GAMETEST SUITE = the D4.6 re-run gate:** `gradlew :fabric:runCrossingGametest` —
   8 legs (same-dim item, cross-dim item, F3 hurt-cow, pearl+relatives-net, generation
   negative-coords 6a + nether-side 6b, >71-chunk same-dim store leg 7, datapack leg 5
   with world-reopen). ~60s. Run before every READY and after every render-substrate
   change. It seeds `entityPortals=true` + `initialScreenShown=true` into its run dir; the
   TITLE-CARD run config is pinned flag-OFF (drives the block-era flow).
9. **CHECKPOINTS:** C1 is DECIDED — **S19 WILL BE BUILT** ("we are not skipping s19");
   C2 (Sodium 0.9.1 retarget) / C7 (NeoForge) are ask-first and come ONLY AFTER S20 +
   polish. **C4 is THIS STAGE'S decision:** both clip mechanisms live-compared by the USER
   at the S18 test (surface `IPGlobal.crossPortalEntityClipMechanism`:
   `SUBMIT_ORDER_UNIFORM` (A) vs `ISOLATED_STORAGE_BRACKET` (B) via
   `config/immersive_portals.json` + restart, or the config GUI); C4 re-confirmed on their
   comparison verdict. NOTE: B's draw call site (`drawBracketedEntitiesIfAny` — currently
   ZERO callers) is S18 WORK — wire it BEFORE the comparison or B drops tagged entities.
10. **Java process rules (global CLAUDE.md):** only kill JVMs whose command line contains
    THIS project's path; NEVER `gradlew --stop`; never touch idea64.
11. **IP SIDE-BY-SIDE is the classification court:** the user's runnable original =
    `C:\Users\warwa\curseforge\minecraft\Instances\1.21.1 fabric ip` (MC 1.21.1, IP 6.0.6).
    One same-scenario glance settles "bug or IP behavior" — it closed 5+ disputes so far.
    Use it BEFORE diagnosis cycles on anything ambiguous.
12. **Port discipline:** every change is a 1:1 IP port unless ledgered (briefing §5). IP
    splits per-frame routines across tick + render-end — find BOTH halves. Per-entity-type
    crossing behavior exists in EVERY crossing path. Every packet-handler mixin needs
    isSameThread. Old→new copies under PLAYER REUSE get identity-audited. **User-KEPT
    improvements over IP (do NOT "fix back"): seamless ender pearls; cross-dim panic
    transfer.**
13. **THE GATE-AUDIT RULE (4 scalps: S13 wedge gizmos, S14 particle extract, S16
    SectionCompilerMixin swirls, S17 continueDestroyBlock):** never trust a
    "dormant/inert" label — positively verify reachability at the file. ALL
    EXCLUSIVITY_LEDGER B11 "dormant" entries are formally DISTRUSTED: per-entry
    reachability check before any S20 deletion.
14. **26.2 renders frames MID-PACKET** (`setScreenAndShow` → `renderFrame`): every
    per-frame consumer must SKIP (never assert) on the transient `mc.player`/`mc.level`
    mismatch frame — a packet-context throw = netty disconnect (the S15 pearl freeze).

## 1. NEW INVARIANTS SINCE THE S15 HANDOFF (memory s15-rung3-lessons + the port-notes)

- **Loop-back (sharedState) passes are their own resource class:** anything gated
  `!sharedState` vanishes exactly when layer-2 recursion re-enters the home dim. The
  isolated same-dim entity pipeline (core-owned FeatureRenderDispatcher/RenderBuffers(0)/
  SubmitNodeStorage trio — the shared main dispatcher's PreparedFrame throws mid-frame) is
  the fix pattern; same-dim BLOCK ENTITIES + PARTICLES remain omitted = an S18 item.
- **Vanilla native teleports flag-ON must be REROUTED, not tolerated** — and the seamless
  replacement MUST pass vanilla's relatives through
  (`connection.teleport(PositionMoveRotation.of(transition), transition.relatives())` via
  the R8-stamped overwrite). `forceTeleportPlayer`'s 5-arg packet path sends EMPTY
  relatives and WIPES momentum + snaps rotation.
- **26.2 render layers are sprite-derived** (`ChunkSectionLayer`: transparency → CUTOUT;
  `force_translucent` → TRANSLUCENT; ItemBlockRenderTypes is GONE; BlockRenderLayerMap is
  obsolete). Item models = `assets/<ns>/items/<name>.json` definitions. pack.mcmeta
  formats >81 need `min_format`/`max_format`. **Dynamic-registry entries load at WORLD
  OPEN only** — /reload cannot add them.
- **The window-residual family is CLOSED** (S17 hardening): capture-point resolution at
  `flipUpdateTrackingSets` + the setLevel re-arm assertion. The pump family + resolver +
  preResolvePromotedWindow + frontier gate + light-cadence plugs remain PERMANENT
  substrate (S20 must keep them).
- **The S16 D3 disposition (corrected form):** vanilla portal-block FORMATION is
  structurally suppressed flag-ON (MixinAbstractFireBlock_CVB); the block-era handlePortal
  cancel is DEMOTED flag-OFF-only — crouch-hatch/legacy vanilla portals teleport
  VANILLA-STYLE per IP (live-proven), with PortalForcerMixin/B7 gated against block-era
  double-handling.

## 2. S18 SCOPE (EXECUTION_PLAN §S18 — read it in full before starting)

**R3 GOES LIVE** (the headline): wire the CrossPortalEntityRenderer delivery so entities
STRADDLING the portal plane render two-sided (the S15/S17 hand-item sliver, animal
threshold clip both directions, damage flash in portal views, cross-portal punch/reach).
Mechanism A (per-draw clip uniform, submit-order keyed) is live today; **Mechanism B needs
its draw call site wired (`PerEntityClipBracket.drawBracketedEntitiesIfAny` — zero callers;
under B, tagged crossing entities currently VANISH from dest passes)**. Then the C4 LIVE
A/B: the user compares both and C4 is re-confirmed on their verdict (rule 9). Also wire
`PerEntityClipBracket.ownRenderBuffers` into the per-frame endFrame path (the
ClientWorldLoader core-owned registry exists — S15's gpu-buffer-leak rule).

**The trailing render periphery at runtime:** `GuiPortalRendering`, `OverlayRendering`
(breakable-portal overlays), mirrors runtime verification, `renderMode` family, A2
sign-off.

**The ledgered S18 items (all named in prior PROGRESS blocks/port-notes):**
1. **Dest clouds** (S13-J deviation): the shared CloudRenderer ring-buffer hazard
   (mid-submit rotation = "Cannot wait on a fence for the current submit" — the
   crash-2026-07-16 class); needs IP's CloudContext-style per-dim isolation or a
   core-owned buffer (the fog-buffer pattern).
2. **Dest break particles** + same-dim block entities/particles in portal views (the S15
   F1 deviation boundary).
3. **Dest targeted-block outline** — note `LevelRendererBlockOutlineMixin` covers only the
   block-era tracker (S17 sweep finding); the IP-side outline needs its own delivery.
4. **The multi-portal parity gap** (S14.52): pass-internal superlinear cost (dp=5 avg
   21.6ms → dp=11 ~9.2ms/pass; user bar: "ours a little more laggy with multiple portals
   than IP"). Candidates: IP nested-portal aperture/frustum culling parity, per-pass fixed
   overhead, AND fix the dpMs bracket to top-level-only (it double-counts nested passes).
5. **Dest weather isolation** (window rain from S14 step 6).
6. **Vehicle presentation gap** (S15): minecart ~1s vanish at crossing + passenger
   flicker + fast-speed stutter (the recreate remove→add client gap).
7. **F18 Vulkan degrade runtime check** (if a Vulkan-capable run is available — else
   record why not).
8. **Row-4 fuse-view write-mask spot-check** (S17 sign-off residual).
9. Re-check `SectionOcclusionGraphPartialUpdateSkipMixin` + `LevelExtractorFlashBridgeMixin`
   inertness IF any new flag-ON code touches `PortalContextSwitch.isRenderingPortal` /
   `armPromoteBridge` (S17 sweep condition).

**Watch items for the S18 live rounds:** the C4 A/B (both mechanisms, rule 9); the
particle lever (`debug_allow_dest_particle_extract`) during rain/particle scenes; the
MEDIUM amplifier (portal-cone terrain compiles a beat late after far-walk crossings —
expected, self-healing); straddle judged against the NEW two-sided standard once R3 is
live.

## 3. THE LADDER AFTER S18

- **S19 (C1 DECIDED: BUILD — "we are not skipping s19"):** wand runtime (~3,900 LOC
  compiled at S13), dim-stack GUI/runtime wiring, alternate dims RUNTIME + the R13g
  dynamic-dimension DESIGN (now real scope) + the F11 DimLib-stub runtime surface, compat
  On*Present layers, ModMenu config GUI integration, the S16 held-out peripheral cargo
  (FormulaGenerator, DimStackManagement, AlternateDimensions, PortalWandItem,
  CommandStickItem, PortalWandInteraction, the creative TAB — retires the portal-helper
  /give-only state — and `MixinEnderEyeItem_CVB` + the peripheral commons/client mixins).
  Per-feature ordering surfaced at stage open (scope question, not a re-ask).
- **S20:** delete the block-era system per the disposition tables; survivor audit; flag +
  ledger + holding-machinery removal; final full regression. HONOR: the permanent
  substrate list (§1 above); the B11 per-entry reachability mandate (rule 13); S20 cannot
  "delete" what flag-OFF still needs until the flag itself dies. Removal candidates: the
  probes/levers/DrawCallTrace/the S16 gen-state boot line.
- **Polish backlog (briefing §5):** underwater-fog composite (user-approved deviation),
  full-32-RD keep-loaded toggle (default OFF), portal-aware panic/escape pathfinding
  (user-spotted S15), redstone/rails/minecarts, crossing completeness (#11: server-side
  player fallback detector, projectile path unify), first-visit worldgen graduated
  loading, light-only server forward, the global-portal convert chat confirmation. Then
  ask C2/C7 (rule 9; the S17 sweep wants a NeoForge platform-parity pass at C7; Sodium
  0.9.1+mc26.2 exists — the F21 stubs get retargeted, users must NOT run Sodium with
  entityPortals ON until then).

## 4. Next-session opening prompt (paste-ready)

> Read `migration/S18_HANDOFF.md` in full, then execute S18 per
> `migration/EXECUTION_PLAN.md` §S18: R3 goes live (two-sided straddle render; wire
> Mechanism B's draw site BEFORE the C4 A/B), the trailing render periphery
> (GuiPortalRendering/OverlayRendering/mirrors/renderMode/A2), and the ledgered items
> (dest clouds/break particles/block outline, the multi-portal parity gap, dest weather,
> the vehicle presentation gap), with all §0 standing rules (NO GUESSING/instrument-first
> + deep debug logs when needed, Opus for simple agent tasks and Fable only for
> verify/design, Fable-verify every fix, commit+push every increment, the 8-leg gametest
> suite green before every READY gate, C4 A/B surfaced with switch instructions at the
> live test, C1-DECIDED-BUILD/C2/C7 per the checkpoint rules, the IP side-by-side court,
> the 4-scalp gate-audit rule). Proceed autonomously S18→S20 + polish.
