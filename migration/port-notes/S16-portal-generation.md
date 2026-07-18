# S16 — Portal generation (U12 runtime remainder) — rung 4

Stage spec: `migration/EXECUTION_PLAN.md` §S16. Plan of record: audit+design workflow
`wf_2bd55e2f-3a3` (4 Opus audits + Fable ordered plan). Commit series S16.1→S16.5.

## 1. Ground state (audited 2026-07-18)

The ENTIRE U12 generation head + all 12 form-package files + the 4 S10 trigger mixins were
already compiled AND flag-ON-wired (`IPModMain.init:114` → `CustomPortalGenManager.init`).
The missing layer was exactly IP's peripheral generation surface: `IntrinsicPortalGeneration`
+ its 3 intrinsic forms + the 2 CVB ignition mixins + any peripheral entrypoint — and the
peripheral mixin config was EMPTY and not even listed in `fabric.mod.json` (5 of 6 configs).
Default `netherPortalMode=adaptive` → the live path is the DILIGENT forms.

## 2. S16.1 — forms/triggers runtime verification (commit `9a1fb6a`)

Boot evidence via the crossing gametest: `[S16 gen-state] forms registered: 8; datapack
entries: 0 (+0 legacy); manager buckets: useItem=0 throwItem=0 conv=0` — proves the codec
chain class-inits at runtime, real-Fabric `DynamicRegistries` registration works on the
`:fabric` runtime (the named first-runtime-risk cleared), the manager builds, and the 4
trigger mixins weave under `defaultRequire:1`. `MixinMinecraftServer_P`/`MixinPlayerList_P`
confirmed inert-by-design (bodies commented, matching IP — reload rides Fabric lifecycle
events). The one-shot boot probe stays as a once-per-start INFO line (the demoted form; it
is also leg 5's assert vehicle) — retired with the probe family at S20.

## 3. S16.2 — the peripheral layer + THE D3 SUPPRESSION SWAP (commit `066bc1a`)

**Verify:** `wf_91b049a9-0c1` (2 Fable lenses) FAIL×2 → both blockers folded → compact
re-verify `wf_889ebd19-422` PASS zero blockers. The verify layer caught BOTH: a missing
deliverable and a wrong ledger claim of mine.

**Ported 1:1** (IP `peripheral/`): `IntrinsicPortalGeneration` (+ the 3 intrinsic forms),
`PortalHelperItem`, minimal `PeripheralModMain`, `MixinAbstractFireBlock_CVB` (both
redirects), `MixinFlintAndSteelItem_CVB` (all 3 branches). Wiring: peripheral mixins json
populated + added to `fabric.mod.json`; `PeripheralModMain.init()` in the flag-ON branch;
portal-helper block+item registered UNCONDITIONALLY via the D3 seam (world-save safety;
/give-only until the S19 creative TAB).

**Named 26.2-forced adaptations:**
1. `createPortalBlocks(world)` — 26.2 takes `LevelAccessor` (PortalShape.java:175).
2. `FabricBlockSettings.of()` GONE → `BlockBehaviour.Properties.of()...setId(...)` (the
   PortalPlaceholderBlock pattern); BlockItem `Item.Properties` needs
   `setId + useBlockDescriptionPrefix` (vanilla Items.registerBlock pattern).
3. `displayClientMessage(false)` → `sendSystemMessage` (the S13 form-file pattern).
4. `appendHoverText` gained `TooltipDisplay` + `Consumer<Component>` (Item.java:322).
5. **IP's client `BlockRenderLayerMap...cutout()` is 26.2-OBSOLETE**: render layers are
   sprite-derived (`ChunkSectionLayer`: `hasTransparent() ? CUTOUT : SOLID`,
   `force_translucent` for translucents; `ItemBlockRenderTypes` is gone) — the assets alone
   carry the render layer.
6. IP's `models/item/portal_helper.json` (parent-model form) → the 26.2 item-definition
   format `assets/immersive_portals/items/portal_helper.json` (`minecraft:model` entry
   pointing at the block model — the vanilla glass.json shape).

**Verify blocker 1 (assets):** the portal-helper block/item shipped with ZERO assets
(missing-texture item + magenta-checkerboard block). Folded: IP's blockstate + block model +
texture copied verbatim; the 26.2 item definition authored (adaptation 6).

**Verify blocker 2 (THE LEDGER MISCLASSIFICATION — the important one):** my first-cut
disposition kept the block-era `handlePortal` cancel active flag-ON as
"retained-redundant". The verify REFUTED it with IP source evidence: **IP 1.21.3 has NO
handlePortal suppression anywhere — the crouch escape hatch exists precisely to give the
player a WORKING vanilla portal.** Folded (the D3 swap, corrected form):
- `handlePortal` cancel DEMOTED to `!entityPortals && isSeamlessTeleportation` — flag-ON,
  crouch-hatch/legacy vanilla portal blocks teleport VANILLA-STYLE per IP.
- Gated identically: the paired cooldown tick-down (vanilla handlePortal decrements itself
  — an ungated tick-down would double-decrement flag-ON), B9 `PortalForcerMixin` (REACHABLE
  flag-ON post-demotion; its block-era PortalDetector feed would double-handle), B7
  `NetherPortalUninteractableMixin` (creative-break suppression diverges from IP's
  plain-vanilla crouch portals).
- A1 `PortalShapeFormMixin` checked AT THE FILE (not assumed): it hooks
  `createPortalBlocks` TAIL — the exact crouch-hatch call — but is already flag-gated
  (`:39`), so crouch blocks do NOT feed the block-era detector.
- Client survival of the vanilla respawn path flag-ON CODE-VERIFIED by the re-verify (the
  S15 pump transient-frame guard + the ported onSetWorld cleanup) — but never exercised
  live: **the (d) round includes one crouch-hatch ignite + vanilla walk-through.**
- The re-verify also caught my own stale first-cut comments/ledger lines contradicting the
  folds (§5.2 checkbox, the CVB header, EntityMixin's D3 comment, B7/B9 rows) — all
  corrected. EXCLUSIVITY_LEDGER §2 cell + B7/B9 rows + §5.2 hold the final disposition.

**Held to S19** (all named; C1 guarantees the landing): FormulaGenerator, DimStack init,
AlternateDimensions, DimensionAPI namespace suppression, PortalWandItem, CommandStickItem,
PortalWandInteraction, the creative TAB, initClient (IPOuterClientMisc + wand client),
`MixinEnderEyeItem_CVB` (end-portal CVB — named explicitly so end-portal behavior isn't
dropped silently), the alternate_dimension/dfu/dim_stack peripheral commons + 8 peripheral
client mixins.

## 4. S16.3 — datapack custom generation end-to-end (gametest leg 5)

A DEV-ONLY datapack (written by the test into its own throwaway world — never shipped
resources, D3-safe) with a `classical` form + `use_item` trigger. **GREEN 2026-07-18
14:48**: `datapack entries: 1 (+0 legacy); manager buckets: useItem=1` → `leg 5 PASS`,
`ALL LEGS PASS`. Two 26.2 lessons earned on the way (both log-proven, folded into the
test's comments):
1. **26.2 pack.mcmeta**: formats >81 REQUIRE `min_format`/`max_format` — the legacy single
   `pack_format` fails metadata parsing ("missing mandatory fields") and the pack is never
   discovered (vanilla example: `data/minecraft/datapacks/minecart_improvements`).
2. **Dynamic-registry entries load at WORLD OPEN only** — `/datapack enable` + `/reload`
   re-ran `onDataPackReloaded` and rebuilt the manager, but the registry stayed empty
   (vanilla semantics: reload covers tags/recipes/functions, never dynamic-registry
   content). The test therefore writes the pack, CLOSES the world, and reopens the same
   save via `TestWorldSave.open()` — entries decode at open. Same rule applies to players:
   a custom-generation datapack must be present when the world is (re)opened.
   Bonus coverage: the reopen lands the player in the NETHER (leg-4 exit state) — the
   open-into-secondary-dim path survived cleanly.

Boundary honestly stated: leg 5 proves the datapack PLUMBING (registries → codec → open →
buckets); the form/perform machinery is exercised by the intrinsic flint path in the live
round. **Record-only item per §S16(a):** the defaulted-registry `entity_type` lookup
(`getValue` → pig fallback, matching 1.21.3) — code verified correct at
`GlobalPortalStorage.java:312-328` (portal-generation api-map note 3); no change.

## 5. (d) live round — see the READY gate

R13d runtime verification (tall frame near world min/max Y) and the breakable-family
checks ((d).2-4) are live-round items; commit 4 lands fixes only if the round finds them.
