# S20 HANDOFF — the block-era deletion + survivor audit + the final 12-point regression

**Read this first, then `migration/S19_HANDOFF.md` §0 (ALL standing rules — unchanged and
binding) and `migration/EXECUTION_PLAN.md` §S20. S19 is CLOSED; C2 is CLOSED (2026-07-20 —
ledger = port-note `C2-sodium-iris.md` §5). S20 = the biggest deletion of the migration and
its biggest live round.**

## 0. MUST-DO-FIRST (order-critical)

1. **Mine the block-era FBO precedent BEFORE deleting it** — the iris shaders-ON engagement
   (`migration/IRIS_SHADERS_ON_HANDOFF.md`, user-routed to this era) references
   `PortalWorldManager` + `PortalContextSwitch`'s mirror-FBO path as its working
   architectural precedent. Either extract a reference write-up (the render sequence, the
   FBO lifecycle, the SodiumBridge arm mechanics) into a migration doc first, or accept
   git-history mining later — decide and record.
2. **The SodiumFogOverrideMixin pre-deletion gate-audit** (C2 P8 evidence: it FIRES flag-ON
   under sodium with activeOverride=false — reachable but inert): PROVE flag-ON sodium fog
   does not depend on it before deletion (port-note §5.3).

## 1. WHAT DIES (the sweep, per the disposition tables + the accumulated ledgers)

- **The entityPortals FLAG itself** + everything keyed on it: the SeamlessMixinConfigPlugin
  qouteall gate (weave-level exclusivity), the D3 guard family (the wand/stick flag-OFF
  guards; the ModMenu flag-switch in `ModMenuIntegration` → swap fabric.mod.json's
  "modmenu" entrypoint to the 1:1 `IPModMenuConfigEntry`; the datafix carve-out becomes
  naturally unconditional; the D3_UNCONDITIONAL sets collapse), the TITLE-CARD pinned
  flag-OFF gametest leg.
- **The whole block-era com.warwa portal system**: StencilPortalRenderer,
  PortalWorldManager, PortalContextSwitch, SeamlessClientChunkMap, RemoteBlockUpdater,
  PortalDimensionManager, SeamlessConfigScreen + SeamlessPortalsConfig (block-era),
  SodiumCompat/SodiumBridge/SodiumFogOverride(+Mixin) — after §0.2 —, the block-era
  fabric client driver branch.
- **The C2 gate scaffolding** (user decision C2-5(b)): ExperimentalCompatGate +
  warnAndForcePortalRenderingOff + the IPConfig.onConfigChanged force-guard — BUT the D7
  iris-failure fallback needs SOME warn path: re-shape it before deleting (the fallback
  becomes a plain loud log + renderMode untouched? design it, don't drop it silently).
- **The holding machinery**: IpHeldPaths/ip_scc_closed, the ipStubs/fabricStubs source sets
  + their gradle wiring (gravity_changer stubs die with them — GravityChangerInterface
  stays invoker-dead like IP), the fabricStubs ModMenu shells (the real ModMenu is the
  :fabric dep).
- **The C4 loser cleanup**: the ISOLATED_STORAGE_BRACKET fallback machinery (C4 decided
  SUBMIT_ORDER_UNIFORM at S18) + the config-load mechanism log line.

## 2. THE B11 RULE (formal, 4 scalps)

EVERY "dormant/inert" label is DISTRUSTED: per-entry positive reachability verification
before deletion. The accumulated B11 sweep list: putLineToLineStrip, renderSphere,
renderPortalAreaGridNew, ParticleEnginePortalSkipMixin, IENoiseGeneratorSettings,
DimensionTemplate.init, the flag-OFF latent CCE via portal_children, the D3 flag-OFF
degradations (experimental screen on alt-dim reopen; void darkness), + everything the
EXCLUSIVITY_LEDGER marks dormant. Also verify the C2 compat family entries that reference
block-era symbols (SodiumRendererRepoint's fallbacks etc.) survive the deletion cleanly.

## 3. WHAT SURVIVES (the permanent substrate — do NOT delete)

The S15 pump family; the S18 additions (clouds/weather isolation, the particle
isolated-extract family, the layer-0 hardening); the whole C2 compat family (the compat
json + IPCompatMixinPlugin — gate 2 collapses to always-true —, the D1 swap machinery, the
clip transport, the endFrame walk, the D11 feed — becomes the only state —, the D5/D8/D7
shapes); the ImmPtlViewArea rebuild; user-KEPT improvements (seamless pearls, panic
transfer, the S19 additions). S20-era hardening candidates: explicit bindValue (the
unbound-holder watch), the safe-read-phase refcount, getPipelineNullable.

## 4. THE FINALE: the full 12-point regression (the biggest remaining live round)

Per the briefing's checklist + the accumulated additions: crossing 1-12, the wand suite,
dim stack, alternate dims, the sodium matrix re-run (plain/sodium/iris×pack states), the
config screen, datapack generation, mirrors, global portals, scaled/rotating portals,
save-relog cycles, and the 8-leg suite (which loses its TITLE-CARD flag-OFF leg with the
flag — re-shape the suite accordingly).

## 5. AFTER S20 (the user-routed era)

The polish ladder: **the dim-persistence gap TOP** (port DimLib's persistence half —
`migration/S19E_HANDOFF.md` §3 diagnosis; dimlib source on disk), the **iris shaders-ON
engagement** (`IRIS_SHADERS_ON_HANDOFF.md` — its own opening prompt inside), the culling
perf re-entry (port-note §5.2 item 2), sign-through-portal, config small-window cutoff,
terrain-behind-portal (IP-inherited), bright-dims night darkness, void-not-empty,
R13g-PHASE-2, the S19_HANDOFF §3 backlog. Release packaging: cloth JiJ + the JIT-flag
story. Then C7 (NeoForge) ask-first — its landmine list is fully catalogued.

## 6. OPENING PROMPT (paste-ready)

> Read `migration/S20_HANDOFF.md` in full (it chains to `migration/S19_HANDOFF.md` §0 for
> ALL standing rules — follow every one), then execute S20: FIRST the §0 must-do-firsts
> (the block-era FBO-precedent extraction for the shaders-ON engagement; the
> SodiumFogOverrideMixin gate-audit), then the deletion per §1 with the §2 B11 per-entry
> reachability audit (never trust a dormant label), honoring the §3 survivor list, then the
> §4 full 12-point regression as the close-out live round. Worktree isolation for the
> sweep; Fable-verify every increment; commit+push each; suite green throughout. Then open
> the §5 era with the dim-persistence fix.
