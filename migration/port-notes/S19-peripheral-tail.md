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

## §2 S19-A2 — wand overlay re-expression (LANDED)

Design (recon wf_c0545c7c-56c overlay lens): ONE new client mixin
`MixinLevelRenderer_PortalWand` @Inject(submitFeatures, RETURN) — the 26.2 re-site of IP's
MixinDebugRenderer (DebugRenderer.render is GONE; submitFeatures is the same "after all features
submitted" slot; single exit, target bytecode-shape verified). The mixin does ONE
`submitCustomGeometry(new PoseStack(), RenderTypes.lines(), (pose, buffer) -> clientRender(...))`
— lines() carries TRANSLUCENT blend → translucent bucket → after-terrain draw, matching IP's
late slot. clientRender + the three mode render() methods keep IP's body shape with
`VertexConsumer` replacing `BufferSource` (verify: statement-for-statement IP modulo declared
deltas; Drag/Copy keep their isRendering() guards, Creation has none — IP-exact). 26.2-forced
(F6): `RenderType.debugLineStrip` is GONE — renderCircle re-expresses as discrete line pairs
(verified visually lossless: the alpha-0 jump vertices only produced zero-length strip
segments), and the two `renderPlane(isLineStrip=true)` call sites flip to IP's own discrete
branch.

### 2.1 Verify round 1 (wf_88355dbb-8f3, 2 Fable lenses) — FAIL → all fixed. TWO REAL CATCHES:

1. **THE setLineWidth BLOCKER (both lenses independently):** 26.2 lines() vertex format is
   POSITION_COLOR_NORMAL_LINE_WIDTH; BufferBuilder hard-THROWS "Missing elements in vertex" on
   the next addVertex if an element is unfilled — NO mod line emitter set the width, so the
   first frame the overlay had any geometry would crash the render thread inside prepareFrame
   (and on dest passes: the throw escapes before PreparedFrame binds → "already in use" wedge
   behind the swallow latch). The claimed "proven template" (renderPortalShapeMeshDebug) is
   gated by default-FALSE `debugRenderPortalShapeMesh` and NEVER ran live — it carried the same
   latent bug (**healed for free by the emitter-level fix**); the live S18 lines() user
   (submitDestBlockOutline) goes through ShapeOutlineFeatureRenderer which sets width
   explicitly — a different feature path. FIX: putLine gains an explicit-width form; the
   width-less overloads read `windowRenderState.appropriateLineWidth` (the 26.2 successor of
   1.21.3's LineStateShard OptionalDouble.empty() window-scaled default — IP's lines() visual);
   boxEdge sets it inline; the debugLineStrip(1)-derived primitives (renderCircle + the flipped
   renderPlane discrete calls) pin width 1.0 (IP's strip width).
2. **The renderCircle NORMAL correction:** IP's strip format had no normal element —
   setNormal(plane normal) was INERT data; on 26.2 lines() the Normal element is the shader's
   LINE DIRECTION (rendertype_lines.vsh) — the plane normal (perpendicular to every segment)
   collapses to NaN when the circle faces the camera (the normal usage angle) → circle
   vanishes when looked at. FIX: route the loop through the canonical putLine (segment-direction
   normal, normal-matrix transformed, width 1.0).

Corrections folded: camPos null-refusal in the mixin (S18 null-is-no-information — sibling S18
consumers refuse the same); mixin javadoc same-dim overclaim fixed.

Substrate verdicts (all PASS, evidence in the verify record): translucent custom-geometry bucket
DRAINED at every fill site (vanilla main :174/:176/:434; SecondaryWorldRenderCore :1387/:1398;
PortalContextSwitch :988/:993 — no S14.40-class leak); per-pass cameras + isRendering()
semantics IP-parity across main / in-frame dest / FBO / layer-0 (layer-0 un-bracketed =
IP-parity too); drain window same-frame (no Animated/partialTick drift); mid-packet-frame safe
(player-null guard suffices; level use is dimension()-key comparison only); FABULOUS routing
vanilla-consistent (ITEM_ENTITY_TARGET, the block-outline precedent).

### 2.2 Named deviations + ledger (S19-A2)

- **Same-dim portal views: wand overlay ABSENT** (renderPortalEntitiesSameDim never calls
  submitFeatures; IP's nested renderLevel showed the overlay in every pass class; cross-dim
  views have it). Polish candidate, LOW — the spectral-glow residual class.
- Strip cosmetics: discrete joints vs strip joints at 40-400 segments — negligible.
- `putLineToLineStrip` + `renderSphere` retained-but-unreachable (renderSphere also still
  carries strip semantics + no width) — **B11 reachability class for the S20 sweep**; any
  future revival must fix both before use. `WandUtil.renderPortalAreaGridNew` is likewise
  caller-less (round-2 verify note) but routes through putLine — correct if revived; same
  sweep list, bookkeeping only.
- PortalEntityRenderer's debug-mesh path healed by the emitter fix (was the same latent crash
  behind the debug flag).

## §3 S19-A (d) LIVE-ROUND SCRIPT (wand + creative tab; commits 6ce907d + 67fabcb)

Flag-ON, normal runClient. The 8-leg suite has proven ONLY the startup surface + the overlay
mixin's no-wand early-out — everything below is live-round-only:

1. **Creative tab**: open creative inventory → an "Immersive Portals" tab exists (wand icon).
   Contents in order: 3 portal wands (Create/Drag/Copy in the name), ~40 command sticks
   (enchant-glint, per-stick names), portal helper block. (This exercises displayItems — the
   duplicate-stack + fabric-pagination classes the suite cannot reach.)
2. **CREATE mode**: hold the wand → cursor cube snaps to block corners (the A2 overlay);
   right-click 3 corners (first side: left-bottom, right-bottom, left-top — area grid appears
   after the 3rd) then the second side's corners → portal pair created. Constraint circle +
   plane overlays appear during placement. Shift+use cycles mode; shift+left-click prints the
   settings chat (alignment links clickable).
3. **DRAG mode**: point at the created portal → flowing selection frame; left-click an
   anchor/edge to lock, right-click-drag corners; width/height lock glyphs + line segments
   animate; undo via the chat-printed control; finish. (Server RPC path + permission check.)
4. **COPY mode**: copy the portal (right-click), the clipboard follows the cursor as a pending
   frame; confirm placement; also cut (left-click) + clear.
5. **Command stick**: use e.g. "Delete Portal" on a test portal → executes.
6. **Left-click guard**: the wand cannot break blocks (attack cancelled).
7. **HUD text**: wand feedback lines paint at bottom-center (CustomTextOverlay).
8. **Overlay through portals**: CREATE-mode markings visible through a CROSS-DIM portal
   window; EXPECTED ABSENT in same-dim portal views (the §2.2 named deviation — confirm
   acceptable or route to polish).
9. **(e) regression re-run (wand touches crossing paths)**: crossing items 1 (walk through
   nether portal) + 2 (throw an item through) after the wand session.
10. OPTIONAL flag-OFF spot check: flip entityPortals=false, /give a wand + a stick → both
    inert (no tab either); flip back.

## §3.5 S19-A LIVE-ROUND RESULTS (2026-07-18, user-confirmed)

- **Creative tab: WORKS** ("creative tab is there"). **Wands: ALL MODES WORK** ("all wands work
  good"). **Obsidian/nether portal crossing: WORKS** (regression items exercised).
- **THE TELEPORT CRASH = a JVM C2 JIT DEFECT, not the mod**: hs_err_pid318288 —
  EXCEPTION_ACCESS_VIOLATION inside jvm.dll on "C2 CompilerThread2" while tier-4-compiling
  `PortalRenderInfo::renderAndDecideVisibility` (Temurin 25.0.2+10; pure jvm.dll frames — not
  the render thread, not the driver, not mod logic; fired when the method crossed the compile
  threshold after a few teleports). MITIGATION SHIPPED: `-XX:CompileCommand=exclude` for that
  one method in the loom client run config (fabric/build.gradle) — re-test on JDK updates;
  remove when a fixed Temurin lands. LEDGER: if the same silent hard-crash recurs, a second
  hot method needs the same flag (check the new hs_err's CompileTask line).
- Post-mitigation session: **no crash** (user-confirmed).

## §5 S19-C — dim stack: LANDED (live-round-proven; 2 GUI defects found live + fixed probe-first)

Recon `wf_9aecac4e-330` (4 Opus tracers). Landed: **C1** the 4 extract-model draw bodies
(Opus agent, mapping-spec'd; 1 legit correction — JOML's 2D pivot rotate is `rotateAbout`,
`rotateAround` is 3D-only); **C2** the mutable-children path (26.2 made
`AbstractSelectionList.children()` final+unmodifiable — NEW accessor mixin
`IEAbstractSelectionList` (children field + repositionEntries invoker) +
`DimListWidget.portal_children()` forwarding view; 7 IP mutation sites re-pointed one-token);
**C3** the create-world entry (`MixinCreateWorldScreen_CVB` — no-capture <init> inject,
drift-proof vs IP's ctor-arg capture; `MixinCreateWorldScreenMoreTab_CVB` — outer-ref
`this$0` javap-pinned in the 26.2 deobf jar + MixinExtras `@Local` RowHelper replacing IP's
LocalCapture; `IECreateWorldScreen` duck; AW `accessible class CreateWorldScreen$MoreTab` =
IP's own accesswidener precedent + the neoforge AT twin); **C4** the server half
(`MixinMinecraftServer_DimStack_CVB` — bare-name `createLevels` + 5-arg `setInitialSpawn`
INVOKE descriptor, both pinned vs mc262-ref, the shipping `MixinMinecraftServer_Misc`
precedent; `MixinChunkStatusTasks_BedrockReplacement` 1:1 zero changes;
`DimStackManagement.init()` into PeripheralModMain at IP's slot).

**LIVE-ROUND DEFECTS (both the same 26.2 class — vanilla split row geometry across two
methods and 1.21.3 recomputed row positions per-frame, 26.2 caches them):**
1. Rows off-screen-left: entries added pre-init baked getRowLeft() of a 0-wide list
   (x=-150). Probe-proven (screen layout was CORRECT — 688x274, every button IP-exact; only
   rows wrong). FIX: init uses vanilla's combined `updateSizeAndPosition`
   (setSize+setPosition+repositionEntries).
2. All rows at one y (overlaid text): `repositionEntries` staggers by getHeight() but ONLY
   `addEntry` sets height — raw-list inserts left height 0. FIX: `portal_children()` add/set
   replicate addEntry's height init from the protected `defaultEntryHeight`.
   (SelectDimensionScreen safe — builds its list inside init() with real bounds.)

**USER CONFIRMED: "all worked"** — rows render correctly; the working-stack flow
(overworld+nether, Finish, apply) exercised. Alt-dim default entries (bright_void/skyland)
can't apply until S19-D — expected, ledgered.

### 5.1 Verify record (wf_b94c1d5e-5f8, 3 Fable lenses — GUI PASS / mixins
PASS_WITH_CORRECTIONS / server PASS; folds applied)

- **CORRECTION folded**: the MoreTab javadoc claimed loom-AP remapping protects the this$0
  shadow — WRONG mechanism (this repo has no remap machinery); the PROVEN mechanism is
  stronger: **26.2 ships UNOBFUSCATED** (raw Mojang jar javap'd — dev name IS production
  name; IP's 1.21.3 field_42178 intermediary indirection has no 26.2 analogue). Bytecode
  also proved: single MoreTab <init> (bare-selector single-fires), single RowHelper LVT slot
  (un-ordinal @Local unambiguous).
- **Fold**: the C2-exclusion vmArg extended to the crossing-gametest run (same JVM + the
  suite teleports enough to heat the method — gate-flakiness prevention). LEDGER: production
  jars carry no mitigation (users on affected JVMs — revisit at release packaging).
- **Verified-clean highlights**: all 4 draw bodies statement-exact vs IP (the icon-flip
  rotateAbout algebraically identical to IP's 3D quat at 180°; the edit screen's
  renderBackground drop is toward-vanilla — IP's 1.21.3 call was itself a double-draw);
  both live fixes complete across ALL 8 portal_children sites incl. plain add(E) →
  add(size,e) and the swap double-set (harmless); resize/reopen/scroll cycles structurally
  sound (initialized-latch → repositionElements → re-lay correct); EditBox workarounds
  verbatim; both server mixins bytecode-anchored; **fresh-world-guard parity PROVEN via the
  1.21.1 bytecode baseline**; dual createLevels-RETURN handler pair IP-identical
  (order-nondeterministic, no dependency).
- **LEDGERED (IP-faithful, do NOT fix)**: stale list selection after Remove (1.21.3-identical;
  26.2 attaches only clamped-cosmetic scrollToEntry effects); the armed-preset residue
  (create-world CANCEL leaves dimStackToApply set → next world open applies it — byte-IP;
  future "old world grew a dim-stack portal" reports map HERE).
- **LEDGERED (bookkeeping)**: flag-OFF latent CCE through portal_children (entry-point-gated
  today; resolves when the flag dies — S20 sweep item); SERVER_DIMENSIONS_LOAD_EVENT is a
  dormant dimlib-stub shell (doubly inert; **named S19-D re-entry condition**);
  DimensionStackAPI static-inits fabric EventFactory → joins the C7 NeoForge landmine list;
  serverRemoveDimStack/clearDimStackPortals + fresh-world bedrock replacement statically
  sound but not live-exercised (one-command live checks, polish-round candidates).

## §6 S19-D — THE R13g DESIGN (DECIDED): load-window static, dynamic half deferred

Recon `wf_528b5c3b-7d1` (4 tracers; the REAL DimLib v1.1.0+mc1.21.11 source found on disk at
`C:\Users\warwa\ModDev\Immersive Portals\ImmersivePortals1.21.11\dimlib-source` + a 1.21.11
IP PORT with an adapted IENoiseRouterData at `...\immersive-portals-port` — the noNewCaves
re-derivation source).

**THE MECHANISM (IP ground truth)**: alt dims are created ON DEMAND — DimLib's
SERVER_DIMENSIONS_LOAD fires at createLevels HEAD inside a direct-registration window
(unfreeze the LEVEL_STEM MappedRegistry → register → refreeze; the stems become ServerLevels
in the SAME createLevels pass). DimStackManagement's load-event handler fires
DIMENSION_STACK_PRE_UPDATE(dimStackToApply) → AlternateDimensions.addAltDimsIfUsedInDimStack
→ addDimensionIfNotExists per referenced dim. Post-createLevels, IP routes adds to
addDimensionDynamically (runtime ServerLevel construction + client resync).

**26.2 CONSTRAINTS (pinned)**: dims fixed at world open (no vanilla runtime-add);
LEVEL_STEM/DIMENSION_TYPE load at world open; DIMENSION_TYPE syncs to clients at
LOGIN only. The load-window path is 26.2-compatible (it runs AT world open, before the
createLevels iteration; clients join after → login sync covers everything; the existing
join-time DimIdSyncPacket needs zero new machinery).

**DECIDED: land the LOAD-WINDOW half now (S19-D); DEFER the dynamic half.**
- Landed: real DimensionTemplate (record + VOID_TEMPLATE) + DimensionAPI.addDimensionIfNotExists
  with the direct-window path (ported from the on-disk DimLib, re-derived vs 26.2
  MappedRegistry) + the createLevels-HEAD load-event mixin + suppress-experimental-warning
  chain + codecs (UNCONDITIONAL seam — level.dat serializes generator configs = D3
  save-parity) + AlternateDimensions/FormulaGenerator init wiring (flag-ON) + dimension_type
  JSONs + icons + the client mixins (horizon 1:1 SAME; fog re-sited — 26.2 FogRenderer
  rewritten) + the 4 IE worldgen mixins (noNewCaves re-derived from the 1.21.11 port).
- **NAMED DEVIATION (R13g-PHASE-2, backlog)**: runtime `/portal dimension_stack` with a
  NOT-YET-EXISTING alt dim will NOT create it (IP does, via addDimensionDynamically — pinned:
  updateDimStack:271-273 fires PRE_UPDATE with the REAL info). Our addDimensionIfNotExists
  logs + skips outside the window → DimStackInfo.apply's missing-dim guard aborts with chat
  feedback; the create-world path (IP's primary UX) fully works. PHASE-2 analysis recorded:
  both dimension_types ALWAYS ship (login-synced), so phase-2 needs "only" LevelStem
  registry mutation + mid-session ServerLevel construction (border/savedData/LevelLoadListener
  coupling — the real risk) + the EXISTING DimensionIntId.onServerDimensionChanged resync.
- Weather-sync tick: check IEWorld.portal_setWeather vs 26.2's public setRainLevel/
  setThunderLevel (toward-vanilla candidate) at implementation.

### 6.1 S19-D IMPLEMENTED (3 scoped agents wf_25f5c69b-983 + orchestrator wiring) + VERIFY
(wf_c18735d7-449, 3 Fable lenses: core PASS_WITH_CORRECTIONS / worldgen FAIL→fixed /
assets PASS_WITH_CORRECTIONS — all folds applied)

**Landed**: the real dimlib load-window (DimensionTemplate record + VOID_TEMPLATE;
DimensionAPI with a REAL SERVER_DIMENSIONS_LOAD_EVENT + window-gated addDimensionIfNotExists;
DimensionImpl unfreeze→register→refreeze via MixinMappedRegistry frozen-flag duck — 26.2
frozen is non-final private, no AW needed; MixinMinecraftServer_DimLib createLevels-HEAD;
static-volatile latch simplification, documented); the noNewCaves MOD-SIDE re-derivation
(26.2 deleted it; simpleRouter zeroes temperature/vegetation which skyland's OVERWORLD
MultiNoiseBiomeSource needs → field-for-field 1.21.11 reproduction via new
postProcess/SHIFT_X/SHIFT_Z invokers; the 26.2 postProcess ordering delta = inherited
toward-vanilla, value-equivalent per verify); weather duck → 26.2 public
setRainLevel/setThunderLevel (toward-vanilla, behavior-identical per verify); the
dimension_type JSONs TRANSLATED to the wholesale-rewritten 26.2 codec (every field grounded
vs DimensionType DIRECT_CODEC + EnvironmentAttributes defaults); 5 icons sha1-copied;
MixinClientLevelData_CVB 1:1 (target :1210 SAME); MixinFogRenderer_A_CVB re-sited to
computeFogColor's unique camera-y void-darkness read (clamp 32 exactly zeroes the term);
MixinWorldDimensions+MixinPrimaryLevelData suppress chain (bake has exactly ONE
Lifecycle.experimental — redirect require=1 sound); new seamlessportals-ip-dimlib.mixins.json
(4 entries) + full wiring; the D3_UNCONDITIONAL_WORLDGEN_ACCESSORS carve-out in the weave
plugin; codecs + FormulaGenerator.init on the UNCONDITIONAL seam.

**VERIFY CATCHES (fixed)**: (1) BLOCKER — flag-OFF reopen of a CHAOS world NPE'd at first
chunk gen (FormulaGenerator tables flag-ON-only vs the unconditional codec) → init moved to
the unconditional seam (pure math; IP-faithful flag-ON call kept, idempotent). (2) the
dimension_type JSONs omitted the 26.2-RELOCATED overworld ambience attributes → black
horizon fog + invisible clouds + silence + darker nights in ALL alt dims → the vanilla
overworld attribute block added to both (fog #c0d8ff, clouds, ambient-light color, music,
mood sounds, sky color, cloud height). (3) natural=true half-translation →
nether_portal_spawns_piglin:true added (IP parity). (4) fog-comment overclaim softened.

**LEDGERED**:
- **THE UNBOUND-HOLDER DEPENDENCY (empirically proven)**: pure-vanilla
  unfreeze→register→refreeze leaves the LevelStem Holder UNBOUND (26.2 register never binds;
  only freeze() does, and it early-returns when already frozen) → createLevels would throw
  "Trying to access unbound value". IT WORKS because fabric-registry-sync's
  MappedRegistryMixin binds at register RETURN (bytecode-verified; NeoForge's patched
  register also binds). Upstream DimLib has the IDENTICAL dependency (1.21.1/1.21.11
  javap'd) — IP-faithful, not a port bug. WATCH ITEM: fabric-api registry-sync must stay in
  the runtime; S20 hardening candidate = explicit bindValue after register.
- Flag-OFF reopen of an alt-dim world: experimental/backup screen appears (suppression
  chain flag-gated; world opens after confirm — recoverable), alt dims render with void
  darkness + no weather mirror (client CVBs + tick flag-ON) — the D3 degradation family.
- NeoForge C7: MixinMinecraftServer_DimLib → DimensionAPI static-init EventFactory →
  NoClassDefFoundError at createLevels flag-ON — MIXIN-reachable, named on the C7 list.
- Dedicated-server fresh-world PRESET can't create alt dims (preset read AFTER the load
  window) — verified IP-PARITY (upstream-identical), not a port gap.
- Third createLevels injector coexistence verified sound; the RETURN-handler order
  nondeterminism (Misc int-ids vs DimStack apply) re-checked benign with 3 configs.
- Live-round additions: skyland-through-a-portal fog/horizon check (S18 decomposed passes
  vs the Minecraft.level gate); the suite CANNOT prove alt-dim creation/generation/visuals
  (dimension_type DECODE is suite-proven — every world open decodes the JSONs).

### 6.2 S19-D LIVE-ROUND RESULTS (2026-07-19, user-confirmed)

**PASS**: skyland biome VARIETY (the noNewCaves re-derivation works); fog below islands
(the computeFogColor re-site works); chaos terrain; the stack layer portals; save-relog
no-crash; crossing regression. `/portal dimension_stack` runtime-add refusal = the
documented PHASE-2 deviation behaving as designed (item 8).

**USER-ROUTED POLISH ITEMS (all captured with candidate mechanisms — instrument-first when
picked up):**
1. **Bright dims darken at night** (item 2). Candidate: the ambience fix added the
   OVERWORLD's `visual/ambient_light_color #0a0a0a` (near-black night ambient) to BOTH
   dimension types — on 26.2 that attribute may scale the night lightmap over the
   ambient_light=15 floor; also the `#minecraft:in_overworld` timeline drives day/night sky
   scaling. Candidates: brighter ambient_light_color for surface_type_bright, or fixed_time
   /timeline surgery. Discriminate live before fixing.
2. **void/bright_void NOT empty — has terrain/biomes** (item 5). Candidates: the
   FlatLevelGeneratorSettings translation (structure-overrides/biome args), the template →
   generator binding, or the Add-path constructing a different stem than the template.
   Instrument the created LevelStem's generator class + settings at registration.
3. **THE DIM-PERSISTENCE GAP** (item 7 — the big one): save-relog while INSIDE an alt dim →
   player dumped to overworld; `/execute in` any alt dim → "unknown dimension"; clouds
   missing in OW afterward. DIAGNOSIS: the alt-dim LevelStems DO NOT SURVIVE REOPEN — the
   load-window registers dims only when the dim-stack PRE_UPDATE fires (dimStackToApply !=
   null = CREATE-time only); on reopen the event adds nothing, and the runtime-registered
   stems evidently do not round-trip through level.dat by themselves. Upstream DimLib has a
   PERSISTENCE HALF the port did not land: it records added-dim configs and RE-ADDS them
   inside SERVER_DIMENSIONS_LOAD on every boot (see the dimlib-source for the exact storage
   — likely level.dat custom data via its PrimaryLevelData hook). **The fix = port that
   half**: persist added dims at registration, re-register them in the load event on every
   world open. (The missing-clouds-in-OW tail = likely the S18 per-dim cloud-renderer
   null-texture class resurfacing through the reopen edge — re-check after the persistence
   fix; may vanish with it.) Recon open-question #26 ("persistence across restarts —
   undeterminable from stubs") is now ANSWERED live: NOT persisted.

## §7 S19-E GROUND-TRUTH UPDATE (USER-SUPPLIED 2026-07-18) + the directed design

**The "dead on 26.2" premise behind C2/F21 is OVERTURNED — user-supplied CurseForge links,
versions pinned by fetch:**
- **Sodium 0.9.1 for Fabric 26.2** — full RELEASE, Jul 8 2026 (0.9.0 release Jun 16).
- **Iris 1.11.2 for Fabric 26.2** — full RELEASE, Jul 8 2026.
- **ModMenu 20.0.0-beta.4** — already a real :fabric dep (§4).
- **Cloth Config 26.2.155+fabric** — RELEASE Jun 18 2026 (found in the follow-up sweep) —
  **the §4 S19-B blocker is GONE**: real cloth (bundling me.shedaniel.autoconfig) can replace
  the F21 AutoConfig functional no-op → the IP config screen + the fabric.mod.json "modmenu"
  entrypoint swap become landable. NOTE: the F21 AutoConfig surface is SHIPPED RUNTIME code
  (IPConfig.register runs through it) — replacing it is a real migration step with its own
  verify, not a dep swap.

**USER DIRECTIVE for S19-E: use the REAL Sodium/Iris 26.2 wiring, not the old stub stuff.**
Design consequences:
1. Real artifacts on the compile classpath (the modmenu-20.0.0 implementation-dep precedent;
   TerraformersMC/shedaniel/Modrinth mavens); **RETIRE the F21 sodium/iris ipStubs** — real
  mods + stubs on one classpath = the §4 SHADOWING hazard class (the exact S19-B catch).
2. On*Present detection against the real mod ids; the flag-ON+Sodium interaction becomes
   TESTABLE in dev (expectation: the renderer substrate conflicts — the S18-era warning
   "users must NOT run Sodium with entityPortals ON" stands until real compat lands).
3. HONEST DEPTH LIMIT: IP's sodium compat targets 0.6-era internals; upstream IP never
   ported to 26.2/0.9.x — there is NO 1:1 ground truth for Sodium 0.9.1 compat. Landing the
   wiring/detection/gating = S19-E; the compat DEPTH (re-engineering IP's sodium render-path
   integration against 0.9.1) = C2 (ask-first; COVERAGE INFO-3 full-depth 27-file pass).
   Surface the C2-entry question at S19-E open with the wiring landed.

## §4 S19-B — ModMenu config GUI: CLOTH-CONFIG-BLOCKED, compile shape landed

**Scout correction (the first scout claim "ModMenu has no verified 26.2 build" was WRONG —
caught in-stage by the reachability grep):** ModMenu EXISTS for 26.2
(`com.terraformersmc:modmenu:20.0.0-beta.4`, a real `:fabric` implementation dep, in the dev
runtime), and the BLOCK-ERA mod already ships a working "modmenu" entrypoint —
`ModMenuIntegration` → the hand-built `SeamlessConfigScreen`. That stays the live entrypoint
in BOTH flag states (the D3 baseline; flag-ON it edits block-era settings — harmless,
retires with the S20 block-era deletion / C2 swap).

The IP-side blocker is CLOTH-CONFIG only: `IPModMenuConfigEntry` →
`IPConfigGUI.createClothConfigScreen` → `AutoConfig.getConfigScreen(...).get()`, and the
shipped F21 AutoConfig surface returns NULL from getConfigScreen ("Cloth's GUI has no MC
26.2 build"). LANDED: the 1:1 `IPModMenuConfigEntry` class (unwired — wiring it = guaranteed
NPE on the config button) + two **fabricStubs** shells (`ModMenuApi`, `ConfigScreenFactory`).
PLACEMENT RULE (the in-stage catch): the shells started in ipStubs, which IS on the :fabric
compile classpath → they sat NEXT TO the real ModMenu classes (the documented fabricStubs
SHADOWING hazard); moved to fabricStubs (:common-only + neoforge fabricStubsClasspath;
:fabric resolves REAL ModMenu — proven by the block-era integration compiling against it).
**C2 re-entry checklist**: real cloth-config dep replaces the AutoConfig no-op → swap
`IPModMenuConfigEntry` into the fabric.mod.json "modmenu" list → live-test the screen.
S19-B otherwise CLOSED into C2. Zero runtime reachability today (grep-proven: no callers, no
entrypoint reference).
