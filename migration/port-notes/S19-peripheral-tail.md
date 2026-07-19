# S19 — Peripheral tail (U13): wand runtime, creative tab, ModMenu GUI, dim stack, alternate dims, compat

**Stage record of record for S19 (C1 DECIDED: BUILD). Feature order (user-picked at stage open,
2026-07-18): 1) wand runtime + creative tab → 2) ModMenu GUI → 3) dim-stack GUI → 4) R13g design
+ alternate-dims runtime → 5) compat On*Present layers.**

## §1 S19-A1 — wand + command stick + creative tab: registration & runtime wiring

Recon: workflow `wf_c0545c7c-56c` (5 Opus tracers: IP registration ground truth, repo wand state,
26.2 creative-tab/asset APIs, overlay-render surface, RPC chain). Verify: workflow
`wf_7e348eaa-89b` (3 Fable lenses) — **1 pre-ship BLOCKER caught** (§1.4), all folds applied.

### 1.1 What landed (all 1:1 IP unless named)

- **Items registered**: `immersive_portals:portal_wand` + `immersive_portals:command_stick` ride
  the S16 unconditional D3 seam (SeamlessPortalsModFabric) next to portal_helper. 26.2-forced:
  both static `instance` Properties gain `.setId(...)` (Item ctor throws "Item id not set" at
  class-load otherwise — Item.java:135,641).
- **DataComponentTypes** (`iportal:portal_wand_data`, `iportal:command_stick_data`): IP registered
  them inside each item's `init()`; here they are SPLIT out to `registerDataComponents()` on the
  UNCONDITIONAL seam. D3 save-parity (components persist on saved stacks; unregistered type ⇒
  stack parse failure on a flag-OFF load) **and network-mandatory** (verify find: fabric-registry-
  sync marks DATA_COMPONENT_TYPE synced-by-rawID — flag-gated registration would shift raw ids and
  break mixed-state joins). Events stay in `init()` (flag-ON).
- **Creative TAB** (`immersive_portals:general`): IP PeripheralModMain:40-51 verbatim content
  (icon=wand, title=`imm_ptl.item_group`, displayItems = 3 wand mode-variants → built-in command
  sticks → portal helper). 26.2-forced: `FabricItemGroup.builder()` (fabric-item-group-api-v1) is
  GONE — fabric-api 0.152.1+26.2 replaces it with `FabricCreativeModeTab.builder()`
  (fabric-creative-tab-api-v1 5.0.14), same builder contract (real impl read at verify: build() is
  side-effect-free; extends the vanilla Builder). Registered **FLAG-ON only** (tabs are not world
  state; CREATIVE_MODE_TAB is unsynced — join-safe; flag-OFF must not surface entity-portal UI).
  New compileOnly fabricStubs shell `net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab`
  (S10-A family; :fabric compiles against REAL fabric-api and is green ⇒ shell matches).
- **PeripheralModMain** extended to IP shape minus held features: init() += PortalWandItem.init,
  CommandStickItem.init, PortalWandInteraction.init, registerCommandStickTypes (IP order; held:
  FormulaGenerator/DimStackManagement/AlternateDimensions/DimensionAPI-suppress → S19-C/D; verify
  confirmed no landed entry silently needs a held one). NEW initClient() = IPOuterClientMisc +
  PortalWandItem.initClient + ClientPortalWandPortalDrag.init (IP :53-60 complete), wired flag-ON
  in SeamlessPortalsClientFabric. Client-order deviation named in-code: IP runs its peripheral
  client entry BEFORE core; ours runs after (S16 "one-branch shape" precedent) — verify proved
  order-insensitive.
- **IPOuterClientMisc**: NEW 1:1 port (S16 hold landed). Verify: whitespace-identical to IP; all
  deps real (IPConfig.getConfig/saveConfigFile/dimStackPreset, CLIENT_PORTAL_SPAWN_EVENT,
  CHelper.printChat, McHelper.getLinkText, IPGlobal.gson).
- **MixinMinecraft_PortalWand** (left-click driver): 1:1 port; 26.2 target BYTECODE-verified
  (javap of the real merged jar): exactly ONE `LocalPlayer.getItemInHand` INVOKE inside
  startAttack (offset 114 / :1640), owner+descriptor byte-exact, player-null impossible at the
  point (dominating deref :1627), cancel semantics identical at the 26.2 caller (:1940).
  Registered in the peripheral mixins json client list; weave-gated flag-OFF by the qouteall.*
  rule in SeamlessMixinConfigPlugin. **Named fidelity delta**: 26.2's new spectator early-return
  precedes the injection point — spectators bypass the wand hook (1.21.3 fired it); favors
  vanilla, no fix.
- **Assets** (6 files): textures + models/item jsons sha1-identical copies from IP;
  `items/*.json` descriptors are the 26.2-forced addition (portal_helper chain pattern). Lang
  already complete (46/46 wand + 46/46 command keys; `item.immersive_portals.command_stick`
  absent in IP too — per-stack Data names).

### 1.2 D3 flag-OFF guards (NOT in IP; die with the flag at S20)

Three behavior chokepoints guarded on `EntityPortalsFlag.isOn()`:
1. `PortalWandItem.use()` head → PASS (vanilla's exact component-less default — flag-ON
   byte-identical behavior).
2. `PortalWandInteraction.checkPermission` head → false (ALL 7 RemoteCallables funnel through it
   — verified 7/7; covers forged RPC; the RPC C2S handler isn't even registered flag-OFF).
3. `CommandStickItem.doUse()` head → return (**the verify BLOCKER**, §1.4).

Rationale: items exist in both flag states (D3), so a flag-OFF /give'd or save-carried wand/stick
must be INERT — the wand's RPC chain would otherwise create entity portals on the block-era
substrate (persisting hybrid state into the save).

### 1.3 The 26.2-forced re-site of IP's overlay caller (S19-A2, designed — see §2)

### 1.4 Verify record (wf_7e348eaa-89b — 3 Fable lenses)

- **Lens mixin-target: PASS** (real-bytecode verification; the 1.21.3 comparison used 1.21.1
  bytecode as nearest available ancestor — ledgered; IP's own shipped 1.21.3 mixin is
  injection-spec-identical, so multiplicity drift is implausible).
- **Lens lifecycle-D3: FAIL → fixed.** BLOCKER: the "no guard on CommandStickItem" initial design
  choice rested on a FALSE premise — `IPGlobal.easeCommandStickPermission` raw default is TRUE
  (IPGlobal.java:99, IP-faithful) and its normalization to false runs only in the flag-ON config
  path (IPModMain.init → onConfigChanged), so flag-OFF ANY player (survival, non-op) holding a
  persisted stick would execute its stored command at GAMEMASTER level — privilege escalation
  strictly beyond IP and beyond flag-ON, reachable via the exact save-flip scenario D3 protects.
  Fix = guard §1.2(3). Verified-clean bonus ledger: build()-side-effect-free tab impl; single
  registration site per component id; freeze-phase legality (fabric's documented onInitialize
  pattern + uniform freeze); PASS-guard semantics exact.
- **Lens fidelity-crash: PASS.** Ran `:neoforge:compileJava` itself — GREEN (the diff's stub
  reaches neoforge via fabricStubsClasspath). Comment corrections folded (registration-only
  overstatement; "inert flag-OFF" overstatement).

### 1.5 New ledger items

- **NEOFORGE RUNTIME LANDMINE (C7 ledger)**: PeripheralModMain.TAB's static init now
  hard-references fabric-api → `NoClassDefFoundError` at class-load on NeoForge RUNTIME (stub is
  compileOnly; nothing loads the class there today — grep-verified zero neoforge refs). Must be
  re-seamed (loader-neutral tab seam) when C7 NeoForge parity lands. Sits next to the
  fabricStubs-removal S20 item.
- **Suite honesty (8-leg gate)**: the suite exercises the STARTUP surface of this diff (any
  registration crash fails all legs) but CANNOT exercise: creative-tab displayItems generation
  (first runs at creative-GUI open — duplicate-stack IllegalStateException class + fabric
  pagination are live-round-only), wand click flows, RPC+permission path, item model/texture
  resolution, all flag-OFF guards. Flag-OFF boot of the new registrations is smoke-covered by the
  TITLE-CARD leg (pinned flag-OFF) — which is NOT part of the 8-leg gate.
- 1.21.3 vanilla bytecode unavailable locally (1.21.1 used as comparison baseline for the mixin
  caller semantics).

## §2 S19-A2 — wand overlay re-expression (design pinned at recon; lands next)

Design (from recon wf_c0545c7c-56c overlay lens + pins): ONE new client mixin
`MixinLevelRenderer_PortalWand` @Inject(submitFeatures, RETURN) — the 26.2 re-site of IP's
MixinDebugRenderer (DebugRenderer.render is GONE; submitFeatures is the same "after all features
submitted" slot; it also runs for the mod's dest passes via invokeSubmitFeatures, faithfully
reproducing IP's nested-pass overlay behavior; Drag/Copy self-guard via PortalRendering
.isRendering() as in IP). The mixin does ONE
`submitCustomGeometry(new PoseStack(), RenderTypes.lines(), (pose, buffer) -> clientRender(...))`
(the proven PortalEntityRenderer:88-96 template; lines() carries TRANSLUCENT blend → translucent
bucket → after-terrain draw, matching IP's late slot). clientRender + the three mode render()
methods keep IP's body shape with `VertexConsumer` replacing `BufferSource`. 26.2-forced (F6
class): `RenderType.debugLineStrip` is GONE — `WireRenderingHelper.renderCircle` re-expresses as
discrete line pairs (the alpha-0 jump vertices were strip-break plumbing) and the two
`renderPlane(..., isLineStrip=true)` call sites flip to IP's own discrete branch (false).
