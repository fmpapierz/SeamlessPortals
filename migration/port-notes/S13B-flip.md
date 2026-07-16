# S13-B — THE FLIP: the ported `qouteall` tree joins the shipping build, flag-gated

**Stage S13-B of the entity-portal migration — THE FLIP + FIRST LIGHT bring-up (flag-gated).** S13-B
takes the held-inert closure that S13-A landed and turns it into a shipping, flag-gated engine:
`ip_scc_closed` is flipped `true` (the permanent flip), the whole ported `qouteall` tree joins the
`:common`/`:fabric`/`:neoforge` compile, the IP mixin configs register flag-gated, every block-era
driver gains its `!entityPortals` gate (the exclusivity contract goes live), the IP runtime init is
wired behind the flag, the entity/block/argument/payload registries go UNCONDITIONAL for save-safety,
and the carried IP math tests un-hold. After this stage the **USER runs first light** (rung 1,
`migration/S13-FIRST-LIGHT-TEST.md`).

This note consolidates the four S13-B working fragments — `S13B-flip.md` (the flip + `:fabric`/
`:neoforge` translation-slip burn-down), `S13B-wire1.md` (mixin registration + exclusivity enforcement
+ flag-gated init), `S13B-wire2.md` (unconditional registrations + renderer seam + test un-hold), and
`S13B-wire3.md` (the four post-verify defect fixes P1–P4) — plus the ratified `EXCLUSIVITY_LEDGER.md`
population, into the single stage record. The fragments are deleted at this note's landing.

**Governing law:** `EXECUTION_PLAN.md` S13 steps 1–6 + commits (c); entry ticket
`S13A-closure-sources.md §7` (the P1/P2/P3 FLIP-BLOCKER LEDGER); `EXCLUSIVITY_LEDGER.md` (the one-driver-
per-session census, enforced this stage); `DEPENDENCY_ORDER.md §4.2` (init order). **Citation
conventions:** `IP:` = 1.21.3 `ImmersivePortalsMod/src/main/java/qouteall`, `26.2:` = `mc262-ref`
(Mojang mappings), `MOD:` = the live `com.warwa` substrate, api-map = `migration/api-map/`.

**ZERO IP-logic deviation.** Every source delta is a 26.2-API translation applied per the api-maps and
verified against the REAL 26.2 fabric-api jars actually resolved by `:fabric` (networking-api
**6.3.3+72073ef09c**, attachment **2.2.16+aa0fd6fe9c**, pinned from `fabric-api-0.152.1+26.2.pom`),
carried in-line as a `// 26.2:` comment.

**CRITICAL SAFETY CONTRACT (honored — see §10).** This stage edits LIVE `com.warwa` code (the
`!entityPortals` exclusivity gates). Every live edit is a PURE GATE: flag-OFF (the shipping default)
evaluates the gate `false` and falls through to the existing block-era path **byte-for-byte**. The flag
default is OFF; the S17 default-flip is a later stage. The user's flag-OFF baseline test (Part 0) proves
the closure landed inert.

---

## 1. THE FLIP — plan step 1 (`ip_scc_closed=true`) + translation-slip burn-down

`gradle.properties`: `ip_scc_closed=false → true` (committed, permanent). From this point the
`IpHeldPaths` task-level `exclude` is no longer applied on ANY compile task — the whole `qouteall` tree
joins the shipping compile (`:common`, both loader `compileJava`, `:common:compileTestJava`), and the
D1.3 TEST exclusions for `Mesh2DTest`/`HelperTest` drop automatically (same flag → plan step 6's
un-hold happens for free). **`IpHeldPaths.groovy` itself is byte-untouched** — the flag alone un-holds
the whole tree AND (via `TEST_HELD_PATHS` gating) the two carried IP tests.

The flip surfaced the documented FLIP-BLOCKER tail (S13-A §7) plus one residual `:neoforge` slip. All
burned down against the api-maps and the resolved jars, never by simplifying:

### P1 — `PayloadTypeRegistry` config-registration rename (`:fabric`; network.md headline-5 / F1)

fabric-networking **v6** renamed `configurationS2C()/configurationC2S()` →
`clientboundConfiguration()/serverboundConfiguration()` (`.register(TYPE,CODEC)` unchanged). Confirmed
by `javap` on the resolved 6.3.3 jar (both return `PayloadTypeRegistry<FriendlyByteBuf>`). Fixed the
SOURCE and the `:common` fabricStubs shell TOGETHER (single consumer `ImmPtlNetworkConfig`) so the
`:common` probe stays honest and `:fabric` links against the real type.
- `qouteall/imm_ptl/core/network/ImmPtlNetworkConfig.java` — `configurationS2C()→clientboundConfiguration()`
  (S2C = clientbound), `configurationC2S()→serverboundConfiguration()` (C2S = serverbound).
- `common/src/fabricStubs/…/networking/v1/PayloadTypeRegistry.java` — two static methods renamed to match.

### P2 — `AttachmentChange.partitionAndSendPackets` → `AttachmentSync.trySync` (`:fabric`; chunk-loading.md row 53)

fabric-data-attachment-api **2.2.16**: `AttachmentChange` is now a `record(targetInfo,type,value)` with
NO partition/send member; the send half moved to `AttachmentSync.trySync(List<AttachmentChange>,
ServerPlayer)`. `AttachmentTargetImpl.fabric_computeInitialSyncChanges(ServerPlayer,Consumer<…>)`
SURVIVES. **Re-derived against GROUND TRUTH, not the stub:** IP's `onSendPacket` is an `@IPVanillaCopy`
of Fabric's own chunk-sender mixin, now `net/fabricmc/fabric/mixin/attachment/PlayerChunkSenderMixin.
sendInitialAttachmentData` (2.2.16 sources), whose body is exactly `fabric_computeInitialSyncChanges`
→ `if (!changes.isEmpty()) AttachmentSync.trySync(changes, player)`. So the faithful port is the single
call swap, byte-faithful to Fabric's 26.2 internal, zero logic change.
- `qouteall/imm_ptl/core/chunk_loading/PlayerChunkLoading.java:225` — call swap (+ `AttachmentSync` import).
- `common/src/fabricStubs/…/sync/AttachmentChange.java` — dropped `partitionAndSendPackets`.
- `common/src/fabricStubs/…/sync/AttachmentSync.java` — NEW shell: `static void trySync(List,ServerPlayer)`.

### P3 — `addTask`/`completeTask` interface-injection via cast (`:fabric`; S10A §4/§7, F8)

`ServerConfigurationPacketListenerImpl.addTask/completeTask` are fabric interface-injection methods.
S10A §4/§7 EMPIRICALLY verified they do NOT auto-resolve even at `:fabric` (this build's minimal loom
does not APPLY the injection to the recompiled common source). Resolution = an explicit cast to the
injected interface, RENAMED in 26.2 `FabricServerConfigurationNetworkHandler` →
**`FabricServerConfigurationPacketListenerImpl`** (`javap`: `addTask(ConfigurationTask)`,
`completeTask(ConfigurationTask.Type)`; injected onto `class_8610`). A cast of a non-final class to an
interface is compile-legal on BOTH classpaths; the runtime mixin makes the listener implement it.
`Context.packetListener()` is still correct in 6.3.3 (not the older `networkHandler()`) — no slip there.
- `ImmPtlNetworkConfig.java:194,:218` — casts (+ import).
- `common/src/fabricStubs/…/networking/v1/FabricServerConfigurationPacketListenerImpl.java` — NEW shell.

### P5 (residual slip burned) — `:neoforge` `net.fabricmc.*` unresolved → fabricStubs seam for non-fabric loaders

The flip surfaced **571 `package net.fabricmc.* does not exist` errors** on `:neoforge`: pre-flip
`:neoforge` never compiled the `qouteall` tree, and `fabricStubs` was `:common`-only by design (S10A §6
— a `PayloadTypeRegistry` stub on the `:fabric` classpath would SHADOW the real type `FabricPlatformHelper`
uses). NeoForge has no fabric-api to shadow, so the same shells are exposed to non-fabric loaders via a
new consumable `fabricStubsClasspath` configuration + `fabricStubsJar` task (mirrors the existing
`ipStubsClasspath` mechanism), wired `if (project.name != 'fabric')` — `:fabric` still resolves the real
fabric-api (no shadow). Pure build wiring, no IP source touched.
- `common/build.gradle` — `fabricStubsClasspath` config + `fabricStubsJar` (`s10a-fabricstubs` classifier).
- `buildSrc/…/multiloader-loader.gradle` — the non-fabric `compileOnly` wiring.

## 2. FIRST GREEN BUILD — the migration's GREEN GATE

The GREEN GATE runs `--rerun-tasks --no-daemon` in ONE invocation and is the acceptance bar for the
whole stage (re-run after every WIRE and after the WIRE-3 fixes). Log `scratchpad/s13b_fix_rerun.log`
(also `s13b_wire1_green.log`, `s13b_wire2_green.log`, `s13b_greengate.log`).

```
:common:compileJava :fabric:compileJava :neoforge:compileJava :common:test --rerun-tasks --no-daemon
```

| Gate | Result |
|---|---|
| `:common:compileJava` | exit 0 |
| `:fabric:compileJava` (real fabric-api — P1/P2/P3 proving ground) | exit 0 |
| `:neoforge:compileJava` (fabricStubs seam — P5) | exit 0 |
| `:common:test` | exit 0 — **48 tests, 0 failures / 0 errors / 0 skipped** |
| **Overall** | **BUILD SUCCESSFUL, 20/20 tasks executed, 0 `error:`** |

`:common:test` compiled + ran the flip-un-held IP math harness for the first time: `Mesh2DTest` (2) +
`HelperTest` (1), alongside `DQuaternionTest` (27), `PlaneTest` (11), `EventSeamTest` (6),
`InfraSmokeTest` (1). **Zero translation defects across the whole closure — the compiler is the
authority (D1) and it is green on all three loaders.**

## 3. MIXIN REGISTRATION CENSUS — plan step 2 (flag-gated via `SeamlessMixinConfigPlugin`)

`SeamlessMixinConfigPlugin.shouldApplyMixin` now returns `false` for EVERY `qouteall.*` mixin when
`!EntityPortalsFlag.isOn()`, so the entire IP set is skipped flag-OFF (block-era `com.warwa` mixins
unaffected — they stay registered and are gated at runtime). This is the load-time half of the
one-driver-per-session contract. The populated configs are referenced in `fabric.mod.json` (`"mixins"`)
+ `neoforge.mods.toml` (`[[mixins]]`); the Fabric-only config is referenced in `fabric.mod.json` ONLY.

| Config (package) | Populated | Count | Manifest ref |
|---|---|---|---|
| `seamlessportals-ip-core-common.mixins.json` (`qouteall.imm_ptl.core.mixin`) | 73 common IP core mixins on disk | 73 | both |
| `seamlessportals-ip-client.mixins.json` (`qouteall.imm_ptl.core.mixin`) | 58 client mixins on disk MINUS `render.isometric.MixinGameRenderer_Isometric` | 57 | both |
| `seamlessportals-ip-qmisc.mixins.json` (`qouteall.q_misc_util.mixin`) | complete at S7.1 (3 `mixins` + 2 `client`); now REFERENCED | 5 | both |
| `seamlessportals-ip-fabric.mixins.json` (`qouteall.imm_ptl.core.platform_specific.mixin.common`) | the 2 `_MA` platform mixins (WIRE 3 P2) | 2 | Fabric ONLY |
| `seamlessportals-ip-peripheral.mixins.json` (`qouteall.imm_ptl.peripheral.mixin`) | left EMPTY + UNREFERENCED (S19 deferral, §7 P3) | 0 | none |

**Registration lists are the on-disk truth, cross-checked against IP's own `imm_ptl.mixins.json`.** The
only IP-common mixin NOT on disk is `chunk_sync.IEChunkTaskPriorityQueueSorter` (target GONE on 26.2).
Client: the on-disk set drops IP's retired/target-gone entries (`CoreShadersAccessor`,
`MixinLevelRenderer_BeforeIris`, `MixinMultiBufferSourceBufferSource`, `MixinRenderSystem_Clipping`,
`framebuffer.MixinMainTarget`/`MixinRenderTarget`, `shader.MixinCompiledShader`/`MixinShaderInstance`,
`sync.MixinClientboundPlayerPositionPacket`, `sync.MixinReceivingLevelScreen`) and ADDS the new 26.2
R3/R4 installs (`MixinEntityRenderState`, `MixinGameRenderState`, `MixinLevelExtractor`,
`MixinLevelRenderer_CrossPortalEntity`, `MixinPreparedFrame`).

**Deliberately left UNREGISTERED (with reason):** `render.isometric.MixinGameRenderer_Isometric` (empty
DEFERRED no-op stub — `getProjectionMatrix` GONE; isometric-debug re-anchor is S13-deferred; a no-op, so
held UNREGISTERED per disposition); the 4 peripheral alt-dim `@Accessor`/`@Invoker` interfaces
(`common.alternate_dimension.{IEChunkAccess_AlternateDim, IEChunkGenerator_AlternateDim, IENoiseRouterData,
IENoiseGeneratorSettings}`) — **S19-gated** (dynamic-dimension runtime is C1/S19-deferred; ratified §7 P3).

**Flag-OFF safety of registration:** referencing the populated configs is byte-equivalent flag-OFF
because the plugin skips every `qouteall.*` mixin when the flag is off — the identical conditional-skip
pattern already proven by the Sodium skip on the same plugin (`"required": true` gates config PRESENCE,
not per-mixin application, so skipping all mixins is not an error).

## 4. EXCLUSIVITY GATE CENSUS — plan step 3 (`EXCLUSIVITY_LEDGER.md` populated + ENFORCED)

Every block-era driver gained its `!entityPortals` PURE GATE in this stage; the S3 pump host gained its
live flag dispatch. From this commit onward exactly one driver set runs per session in either flag
state. The gate mechanism is the load-time master switch `EntityPortalsFlag.isOn()`, exposed as
`SeamlessPortalsConfig.isEntityPortals()`. **Full row-by-row census + verified transitivity proofs are
in `EXCLUSIVITY_LEDGER.md §1/§1.1/§1.2/§4/§5.1`** (status columns filled this stage); the summary:

### 4a. Loader-entrypoint structural gate (one gate covers many drivers)

`SeamlessPortalsModFabric.onInitialize` + `SeamlessPortalsClientFabric.onInitializeClient` now
`if (isEntityPortals()) { <IP init> } else { <block-era registrations, UNCHANGED> }`. Flag-OFF runs the
exact current sequence (config load + payload TYPE registration stay unconditional above the branch).
This single structural gate suppresses ledger rows **5, 6, 8, 9, 10, 11, 13, 14 (Phase-2), 16, B2** and
the block-era network handlers (`registerServerHandlers`/`registerClientHandlers`) flag-ON.
`git diff -w` confirms the `else` branches preserve the block-era registrations byte-for-byte (only
cosmetic FQN→import on the unconditional `loadFrom`).

### 4b. Pump host LIVE flag dispatch (row 12 + A4)

`MinecraftFramePumpMixin.seamlessportals$preRenderPump` — the S3 flag-dispatch scaffold is ACTIVATED.
Flag ON runs the ported IP pre-render chain (relocated `IP:MixinGameRenderer:86-96`:
`RenderStates.updatePreRenderInfo` → `StableClientTimer.update` → `ClientPortalAnimationManagement.update`
→ `ClientTeleportationManager.manageTeleportation(false)` → `IPGlobal.PRE_GAME_RENDER_EVENT` →
`MyRenderHelper.earlyRemoteUpload()` if `IPCGlobal.earlyRemoteUpload`). Flag OFF runs the block-era
`checkCameraCrossingPerFrame` + `frameUpkeep` unchanged. Verified the ported `MixinGameRenderer` does
NOT contain this chain (it handles `bobView`/`extract`/`renderItemInHand`) → no double-pump.

### 4c. Per-mixin runtime gates (13 pure `!entityPortals` gates)

Rows **1** (`LocalPlayerMixin` — both injects), **2** (`EntityMixin.checkPortalCrossing`, gate placed
AFTER the §2 cooldown tick-down + BEFORE detection), **4/B8** (`ProjectileMixin` + `ThrownEnderpearlMixin`
HEAD), **17** (`GameRendererMixin.beforeRender` HEAD — TAIL `endSecondaryFrames` is §3 substrate,
ungated), **14-Phase1** (`GameRendererPortalPrepareMixin`), **A1** (`PortalShapeFormMixin`), **A2**
(`NetherPortalBlockMixin` — all 4 injects + `getRenderShape` returns vanilla `MODEL` flag-ON), **A3**
(`LevelChunkSetBlockStateMixin`), **A5** (`QuadParticleGroupMixin` — flag-ON returns the vanilla frustum
result), **A7** (`MainProjectionBobMixin` — via `@Shadow bobHurt`/`bobView`, both `@Redirect`s CALL the
real method flag-ON so IP's bob injects scale it; the D5 A2 collision resolved), **A9**
(`ClientLevelMixin.onChunkLoaded` SCAN only; the `doAddParticle` bypass + `disconnect` teardown survive
both states), **B3** (`ServerLevelBlockUpdateMixin`).

### 4d. Transitive (no gate — proven by their only callers being gated)

Rows **3** (via 2), **7** (via 2/3/5/6), **13** (via 3), **15** (via 14), **A4** (sender row 10),
**A6** (via 14/15), **A8** (via 1/2/12), **B1** (via 8), **B5** (via 13), **B6/B10** (post-swap window
never opens flag-ON), **B9** (`handlePortal` cancel active both states), **B11** (dormant/dead legacy).

### 4e. Explicit S13 decisions (recorded, NOT silently)

- **B4 `HandleRespawnMixin`: NO gate** — crossing paths idle flag-ON; player-reuse must keep serving
  genuine death/vanilla respawns in both states (gating would break vanilla respawn flag-ON).
- **B7 `NetherPortalUninteractableMixin`: NO gate** (conservative) — vanilla portal BLOCKS can still form
  flag-ON until S16's structural suppression; re-checked at S16 alongside A1/A2.
- **`ApplyLightDataGuardMixin`/`ChunkLightLambdaGuardMixin` tension: NO gate** — chunk-feed light
  infrastructure kept active in BOTH states (substrate, not a portal driver).
- **§2 `handlePortal` cancel: unchanged** — stays active both states (config-gated on
  `isSeamlessTeleportation`, NOT `entityPortals`) until S16's structural `findEmptyPortalShape` swap.

## 5. INIT-ORDER RECORD — plan step 4 (`DEPENDENCY_ORDER §4.2`, flag-gated)

| Entrypoint (flag-ON branch) | Sequence |
|---|---|
| `SeamlessPortalsModFabric.onInitialize` (server/common) | `ImplRemoteProcedureCall.init()` → `MiscNetworking.init()` → `DimensionIntId.init()` → `IPModMain.init()` (internally: networking → global portals → teleport → collision → commands → `ServerTaskList` → `CustomPortalGenManager` → config) |
| `SeamlessPortalsClientFabric.onInitializeClient` (client) | `ImplRemoteProcedureCall.initClient()` → `MiscNetworking.initClient()` → `IPModMainClient.init()` (internally: teleport-client → renderers on the render thread → collision-client → networking-client → `DimensionIntId.initClient`) |

Config load + bespoke payload TYPE registration stay UNCONDITIONAL (identical both states). NeoForge
entrypoint left unchanged — its IP integration is deliberately unwired (S07 §6) + hard-gated OFF (§7 P4).

**DimLib PROMOTED to shipped runtime** (removes the S10A §5 `NoClassDefFoundError`/NPE landmine):
`qouteall.dimlib.api.DimensionAPI` + `qouteall.dimlib.DimensionTemplate` MOVED `ipStubs → common/src/main/java`
(removed from `ipStubs` to avoid a duplicate class). `DimensionAPI`'s event-holder fields are now real
never-firing objects (were `null`), so the `register(...)`/`addPhaseOrdering(...)` calls made at RUNTIME
flag-ON are safe no-ops (never fire under static dimensions). Inert flag-OFF; deleted at S20.

## 6. UNCONDITIONAL REGISTRATIONS + RENDERER WIRING — plan step 5 (D3 save-safety)

Governing rule (D3, `EXECUTION_PLAN` L204-207): *registries must not differ between flag states or world
saves break on flips.* All wired on **Fabric only**; NeoForge IP integration stays deferred (§7 P4).

- **6a. Entity types + placeholder block — TRULY unconditional (above the flag branch).** The Portal
  entity-type family (10 types: `Portal` + `NetherPortalEntity`/`EndPortalEntity`/`Mirror`/`BreakableMirror`
  /`GlobalTrackedPortal`/`WorldWrappingPortal`/`VerticalConnectingPortal`/`GeneralBreakablePortal` +
  `LoadingIndicatorEntity`) via the S0 `registerEntityTypes` seam (`IPModMain::registerEntityTypes`), and
  `PortalPlaceholderBlock` via a direct `Registry.register(BuiltInRegistries.BLOCK,…)`. These are the ONLY
  genuinely save-relevant registries (entities in chunk data, block states in `.mca`); IP splits them OUT
  of `init()`, so no double-registration hazard. Registered but never SPAWNED/PLACED flag-OFF.
- **6b. Entity renderers — UNCONDITIONAL, and MANDATORY so.** `PortalEntityRenderer` (×9 portal family) +
  `LoadingIndicatorRenderer` (`LoadingIndicatorEntity`) registered UNCONDITIONALLY in
  `SeamlessPortalsClientFabric.registerPortalEntityRenderers()` (1:1 with `IP:IPModEntryClient.initPortalRenderers`,
  raw-cast pattern — one `PortalEntityRenderer` services every portal subtype). **KEY CONSTRAINT
  discovered this stage:** `Minecraft.selfTest()` runs when `IS_RUNNING_IN_IDE` (`26.2:Minecraft.java:714-715`
  = the user's `runClient`) and `EntityRenderers.validateRegistrations()` (`26.2:Minecraft.java:1089-1092`)
  THROWS `IllegalStateException("…game data is foobar…")` if any registered entity type lacks a renderer.
  Since the entity types are unconditional, the renderers MUST be too or the **flag-OFF baseline runClient
  crashes at load**. Verified flag-OFF-safe: both renderer ctors are trivial `super(context)` (no
  `IPCGlobal.renderer`/flag-ON state); `PortalPlaceholderBlock.getRenderShape() == INVISIBLE` so the
  block-model self-test skips it.
- **6c. Argument types + payloads — flag-OFF `else`-branch MIRROR (no double registration).** The 3
  command argument types + the IP payload TYPES (6 play: RPC C2S/S2C, DimIdSync, Teleport,
  GlobalPortalSync, PortalSync; 2 config: S2CConfigStart/C2SConfigComplete) are registered flag-ON inside
  `IPModMain.init`/the q_misc inits (which THROW on a duplicate key), so they are MIRRORED **TYPE-only**
  in the flag-OFF `else` branch. The two branches are MUTUALLY EXCLUSIVE → each session registers exactly
  once, registries are identical between flag states (D3 parity), no double registration. **Only the
  registry half is mirrored** — the payload HANDLERS / configuration-connection events / argument-typed
  COMMANDS are IP-driver BEHAVIOR and are NOT mirrored (flag-OFF channels are inert idle channels). No ID
  collisions: IP uses `imm_ptl:`/`iportal:`, block-era `ModPayloads` uses `seamlessportals:`.

## 7. POST-VERIFY DEFECT BURN-DOWN (WIRE 3) — P1–P4

The post-WIRE verification pass surfaced four defects; all fixed with ZERO deviation and flag-OFF
byte-equivalence preserved (compiled-output confirmed: both `_MA` mixins + shipped AutoConfig in
`build/classes/java/main`; zero `ipStubs` autoconfig classes remain).

- **P1 — flag-ON boot blocker RESOLVED (shipped functional AutoConfig).** `IPModMain.init()` →
  `loadConfig()` → `AutoConfig.register(IPConfig.class,…)` resolved against the compileOnly `ipStubs`
  autoconfig shell (no MC 26.2 runtime provider) → flag-ON boot NPE at `configHolder.registerSaveListener`.
  **Fix:** PROMOTED the whole `me.shedaniel.autoconfig` package `ipStubs → common/src/main/java` (7 files
  created, 7 deleted from `ipStubs`), mirroring the DimLib promotion. Now a functional Gson-backed
  reimplementation: `AutoConfig.register` returns a live `ConfigManager` that has ALREADY loaded
  `config/immersive_portals.json`, so `registerSaveListener`/`getConfig` no longer NPE and config
  load/save is faithful. `getConfigScreen` returns `null` (Cloth GUI S19-deferred, client-only, off the
  first-light path; `Screen` appears only in that method's descriptor so a dedicated server never resolves
  it). Inert flag-OFF (only IPConfig/IPConfigGUI/IPModMain reference it, all flag-ON). Deleted at S20.
- **P2 — silently-dead IP cargo WIRED (the 2 `imm_ptl_fabric` platform `_MA` mixins).** IP's
  `imm_ptl_fabric.mixins.json` registers 4 `platform_specific` mixins; the S00 A.9 disposition was never
  executed. Grep proved ZERO callers repo-wide for
  `ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers` and
  `CustomPortalGenManager.onBefore/onAfterConventionalDimensionChange` → flag-ON, relog/death-respawn
  skipped the cross-dim tracker detach and vanilla dimension changes skipped the custom-portal-gen hooks.
  **Fix:** ported the 2 COMMON mixins with ZERO IP-logic change, registered Fabric-only + plugin-gated:
  `MixinPlayerManager_MA` (`PlayerList.respawn`/`remove` → `removePlayerFromChunkTrackersAndEntityTrackers`,
  ports as-is per platform-compat-peripheral.md:72); `MixinServerPlayerEntity_MA`
  (`changeDimension(DimensionTransition)`→`teleport(TeleportTransition)` with the mandated cross-dim guard
  `transition.newLevel() != level()`, portal-generation.md:46 row 17; `teleportTo` gains
  `Set<Relative>`+`resetCamera`; full descriptor targets the real `ServerPlayer`-returning override, NOT
  the synthetic `Entity` bridge; `player.server` reads the already-widened accessible field). The 2 CLIENT
  mixins dispositioned: `MixinFabricClientPlayNetworkAddon` = EMPTY in IP, nothing to port;
  `MixinFabricInvalidateRenderStateCallback` = DEFERRED to S14 verify (`@Pseudo` Fabric-API-internal
  `lambda$static$0` target unverifiable without the loader classpath, platform-compat-peripheral.md:113;
  rung-1-inert since same-dim reuses the main `ClientLevel`).
- **P3 — the 4 peripheral alt-dim mixins: S19 deferral RATIFIED + doc corrected.** The 4
  `@Accessor`/`@Invoker` interfaces stay UNREGISTERED (`seamlessportals-ip-peripheral.mixins.json` empty +
  unreferenced) — the safest option: their only consumers (`NormalSkylandGenerator` etc.) are C1/S19-gated,
  so no cast can `ClassCastException` before S19, and registering would risk a `require:1` flag-ON crash on
  unverified 26.2 targets for zero S13–S18 benefit. Corrected the FALSE `S13A-closure-sources.md §2.2.1`
  claim ("no registration site, IP has none either"): IP's `imm_ptl_peripheral.mixins.json:7-10` registers
  ALL FOUR including `IENoiseGeneratorSettings` (a `CORRECTION` block was added there pointing here).
- **P4 — false NeoForge safety claim + `NoClassDefFoundError` landmine FIXED (Fabric hard-gate).** WIRE-1
  §4 asserted the flag reader "returns `false` there (no FabricLoader)" on NeoForge; actually
  `EntityPortalsFlag.resolveConfigDir()` falls back to `./config`, so `entityPortals=true` in a NeoForge
  config WOULD flip the mixin plugin ON — where the IP mixins reference `net.fabricmc.*` compileOnly stubs
  absent at runtime → `NoClassDefFoundError`. **Fix:** a reflective `isFabricLoaderPresent()`
  (`Class.forName("net.fabricmc.loader.api.FabricLoader")`) now hard-gates resolution in `readFromDisk()`
  / `seedIfUnset` — on plain NeoForge the flag force-resolves `false` regardless of config, gating BOTH the
  plugin AND every runtime gate to the block-era path (a superset of the verifier's plugin-side suggestion).
  Fabric (and Fabric-API-bridging environments like Sinytra Connector) unchanged in both flag states. This
  only ever makes the flag *more* OFF, so flag-OFF behavior is byte-equivalent. The WIRE-1 §4 claim was
  corrected in place.

## 8. TESTS RECORD — plan step 6

`IpHeldPaths.TEST_HELD_PATHS` had explicit `**/Mesh2DTest.java` + `**/HelperTest.java` exclusions (D1.3
— both need the held `Helper → McHelper → SCC` chain). **Dropped** both lines + reworded the javadoc.
Functionally the flip already un-held them (`applyHolding` skips the whole list while `ip_scc_closed=true`),
so this is the sanctioned S13 step-6 cleanup making the un-hold explicit; the whole holding machinery is
deleted wholesale at S20. **IntBox:** IP ships NO IntBox test — its entire test tree is exactly
`Mesh2DTest` + `HelperTest` (grep-confirmed against `IP:src/test` + the repo). Authoring one would
violate the faithful-port rule (invent nothing IP lacks); IntBox is now un-held + compilable and is
exercised transitively (`Mesh2D` imports `Helper` + uses `IntBox`). `:common:test` is the full carried
harness green with **zero** test-translation slips.

## 9. VERIFICATION TIER

| Build (`--rerun-tasks --no-daemon`, one invocation) | Result | Log |
|---|---|---|
| `:common:compileJava :fabric:compileJava :neoforge:compileJava :common:test` | **BUILD SUCCESSFUL, 20/20 tasks, 0 `error:`** | `scratchpad/s13b_fix_rerun.log` |
| `:common:test` | **48 / 0 failures / 0 errors / 0 skipped** (DQuaternion 27, Plane 11, EventSeam 6, Mesh2D 2, Helper 1, InfraSmoke 1) | same |

Compiled-output ground truth confirmed post-fix: both `_MA` mixins + the shipped AutoConfig package are
in `build/classes/java/main`; zero `ipStubs` autoconfig classes remain (no duplicate-class hazard on the
`:common` compile classpath). **No geometry sign flip** (S13-A §5), **no codec deviation** (S13-A §2.3),
**no `IpHeldPaths.groovy` edit**, **no `gradle.properties` edit beyond the flip**. The GREEN GATE is the
migration's **FIRST GREEN BUILD** on all three loaders. The live flag-ON rung-1 runClient is the USER's
first-light checkpoint (`migration/S13-FIRST-LIGHT-TEST.md`); flag-OFF byte-equivalence is structurally
guaranteed (every gate is a pure `!entityPortals` fall-through; the IP mixin set is skipped by the plugin
flag-OFF; init wiring lives in never-entered `if (entityPortals)` branches).

## 10. SAFETY CONTRACT — honored

- **Zero live block-era runtime path changed.** All `com.warwa` edits are PURE GATES: flag-OFF evaluates
  `!entityPortals` false and falls through to the byte-identical block-era path. `git diff -w` confirms the
  loader `else` branches preserve every block-era registration byte-for-byte (SERVER_STARTING/
  END_SERVER_TICK/prewarm/blockMirrorFlush, AFTER_TRANSLUCENT_TERRAIN/END_CLIENT_TICK pumps/
  registerClientHandlers), and WIRE 2 introduced ZERO content deletions.
- **Flag-OFF adds only D3-sanctioned inert registry entries** (never spawned/handled/used) + the shipped
  AutoConfig/DimLib/`_MA` mixins, all reachable only flag-ON (plugin skips all `qouteall.*` mixins
  flag-OFF; AutoConfig/DimLib only referenced by flag-ON IP code). No flag-ON double-registration.
- **NeoForge is pinned to the block-era baseline** by the P4 Fabric hard-gate.
- **`gradle.properties` `ip_scc_closed=true` is this stage's committed flip; `IpHeldPaths.groovy` is
  byte-untouched; no `git commit` performed by this stage's work; game not run.**

## 11. COMMIT GROUPING for the orchestrator

Plan (c) prescribes commits 2–6 for S13-B (commit 1 = S13-A closure sources, already committed). The
WIRE-3 defect fixes (P1–P4) fold into the concern they belong to. Recommended grouping (all in one build-
green tree; the orchestrator commits — this stage does NOT):

**Commit 2 — "S13.2: THE FLIP — `ip_scc_closed=true` + translation-slip burn-down to FIRST GREEN BUILD"**
`gradle.properties`; `common/…/network/ImmPtlNetworkConfig.java` (P1 renames + P3 casts + imports);
`common/…/chunk_loading/PlayerChunkLoading.java` (P2 + import); `common/src/fabricStubs/…/networking/v1/
PayloadTypeRegistry.java` (P1); `common/src/fabricStubs/…/sync/AttachmentChange.java` (P2); **NEW**
`common/src/fabricStubs/…/sync/AttachmentSync.java` (P2), `common/src/fabricStubs/…/networking/v1/
FabricServerConfigurationPacketListenerImpl.java` (P3); `common/build.gradle` (P5 `fabricStubsClasspath`);
`buildSrc/…/multiloader-loader.gradle` (P5 neoforge wiring).

**Commit 3 — "S13.3: mixin registration + exclusivity gates + flag-gated init (WIRE 1) + P1/P2/P4 fixes"**
**NEW** `common/…/com/warwa/seamlessportals/EntityPortalsFlag.java` (+ the P4 Fabric hard-gate); **NEW**
`common/src/main/java/qouteall/dimlib/api/DimensionAPI.java` + `qouteall/dimlib/DimensionTemplate.java`
(promoted; **DELETE** the `ipStubs` counterparts); **NEW** `common/src/main/java/me/shedaniel/autoconfig/**`
(7 files, P1; **DELETE** the 7 `ipStubs` counterparts); **NEW** `common/…/qouteall/imm_ptl/core/
platform_specific/mixin/common/{MixinPlayerManager_MA, MixinServerPlayerEntity_MA}.java` (P2); **NEW**
`common/src/main/resources/seamlessportals-ip-fabric.mixins.json` (P2); modified
`config/SeamlessPortalsConfig.java`, `mixin/SeamlessMixinConfigPlugin.java`,
`seamlessportals-ip-core-common.mixins.json`, `seamlessportals-ip-client.mixins.json`,
`fabric.mod.json`, `neoforge.mods.toml`; the loader entrypoints `fabric: SeamlessPortalsModFabric.java` +
`SeamlessPortalsClientFabric.java`; the pump host `mixin/client/MinecraftFramePumpMixin.java`; the 13
driver-gate mixins (`mixin/EntityMixin.java`, `ProjectileMixin.java`, `ThrownEnderpearlMixin.java`,
`PortalShapeFormMixin.java`, `NetherPortalBlockMixin.java`, `ServerLevelBlockUpdateMixin.java`,
`LevelChunkSetBlockStateMixin.java`, `mixin/client/{LocalPlayerMixin, GameRendererMixin,
GameRendererPortalPrepareMixin, MainProjectionBobMixin, QuadParticleGroupMixin, ClientLevelMixin}.java`);
`migration/EXCLUSIVITY_LEDGER.md` (§1/§1.1/§1.2/§4/§5.1 filled).

**Commit 4 — "S13.4: unconditional registrations + entity-renderer seam (WIRE 2)"**
`fabric: SeamlessPortalsModFabric.java` (unconditional entity-type + block registration above the branch;
argument-type + IP-payload-TYPE mirror in the `else`), `fabric: SeamlessPortalsClientFabric.java`
(unconditional `registerPortalEntityRenderers()` + the private method); `migration/EXCLUSIVITY_LEDGER.md`
(§5.1 unconditional-registration item checked).

**Commit 5 — "S13.5: un-hold Mesh2DTest + HelperTest (D1 exclusions drop)"**
`buildSrc/…/IpHeldPaths.groovy` (drop the 2 `TEST_HELD_PATHS` lines + javadoc).

**Commit 6 — "S13.6: port-note S13B-flip.md + P3 doc correction + first-light test script"**
`migration/port-notes/S13B-flip.md` (this note); `migration/port-notes/S13A-closure-sources.md` (§2.2.1
`CORRECTION` block, P3); `migration/S13-FIRST-LIGHT-TEST.md` (the USER runClient script); deletion of
`fragments/S13B-flip.md` + `fragments/S13B-wire1.md` + `fragments/S13B-wire2.md` + `fragments/S13B-wire3.md`.

> Alternatively the P1/P2/P4 WIRE-3 fixes can be a distinct 5th "S13.x fix" commit if the orchestrator
> prefers WIRE boundaries over concern boundaries; either way the tree must be GREEN GATE-green at the
> final commit. Commits 3–5 may be squashed to one "mixins + gates + init + registrations + tests"
> commit per plan (c)'s "commit 3" grouping if a coarser history is wanted — all are one green tree.

## 12. HANDOFF — first light (USER-gated)

The tree is green + flag-gated. The stage exit is the USER's flag-ON first-light runClient
(`migration/S13-FIRST-LIGHT-TEST.md`): Part 0 flag-OFF baseline sanity (must be UNCHANGED), Part 1
flag-ON rung-1 (same-dimension command portals in a NEW dedicated superflat creative world). The C4
rider (auto-memory): bring up the A/B clip-mechanism switch whenever the user live-tests entities
through portals (S13/S15/S17/S18). Known deferrals carried past this stage:
`MixinFabricInvalidateRenderStateCallback` (S14 verify), the 4 alt-dim mixins + dim_stack/alternate-
dimension runtime (S19), `PeripheralModEntry`/`…Client` init cargo (S16 registry / S19 GUI, S13-A §7.2),
the FORCED render shells (S19 redesigns). Next stage after first light: S14 (cross-dimension portals,
rung 2).

## 13. S13-E — WEAVE-LEVEL COLLISION SWEEP (first-light attempt-3 fix + exhaustive one-pass audit)

First-light **attempt 3** crashed at boot, not at runtime: a WEAVE-LEVEL driver collision. The always-on
block-era mixin `ClientPacketListenerLocalPlayerFallbackMixin` and IP's `client.sync.MixinClientPacketListener`
both `@Redirect` the SAME `ClientLevel.getEntity(int)` call inside `ClientPacketListener.handleSetEntityData`.
Mixin applies the first `@Redirect` and SKIPS the second at equal priority; the skipped injector's
`require:1` (config `injectors.defaultRequire`) then throws `InvalidInjectionException` and kills boot —
BEFORE any `!entityPortals` runtime gate can run. The §1 runtime gates are a runtime-BODY mechanism; they
cannot prevent a transform-time weave conflict.

**Fix (commit `855fad5`).** A new `ENTITY_PORTALS_SUPERSEDED_MIXINS` set in `SeamlessMixinConfigPlugin` —
the mirror of the existing `qouteall.*`-skip — makes `shouldApplyMixin` return `false` for the block-era
half **when `entityPortals` is ON**. This is the load-time (weave-layer) half of the one-driver contract,
enforced at the same seam that gates the IP set. Byte-inert flag-OFF (the set is only consulted flag-ON).
The single entry `ClientPacketListenerLocalPlayerFallbackMixin` is superseded by IP's `MixinClientPacketListener`
`redirectGetEntityById` (IP resolves entities across the per-dim `ClientWorldLoader` client worlds — a
strict superset of the block-era single-`ClientLevel` local-player fallback; the block-era mixin's own
IP-parity javadoc names this counterpart). Ledger row **W1** (`EXCLUSIVITY_LEDGER.md §7.1`).

**The exhaustive one-pass sweep.** Because a runtime `!entityPortals` gate does NOT prevent a weave
collision, every site where the two mixin SETS touch one injection point had to be audited. LEFT = the
registered `com.warwa` set (`seamlessportals-common.mixins.json`, 51 `client` + 16 `mixins`); RIGHT = the
registered flag-ON IP set (`ip-core-common` 73 + `ip-client` 57 + `ip-qmisc` 5 + `ip-fabric` 2;
`ip-peripheral` empty; `MixinGameRenderer_Isometric` unregistered). Every mixin's `@Mixin` target was
extracted on both sides; the intersection is **18 shared vanilla target classes**; for each, the
per-instruction injection sites were compared under Mixin weave semantics (two redirect-family injectors on
one instruction = FATAL; `@Overwrite` vs anything = semantic; `@Inject`-family = composes). Full row-by-row
census + verdicts: `EXCLUSIVITY_LEDGER.md §7.2`; findings log `scratchpad/s13e-weave-collision-sweep.log`.

**Result — ZERO additional collisions.** The `handleSetEntityData` collision (row W1) is the ONLY fatal
(or `@Overwrite`-class) weave collision in the entire LEFT×RIGHT product, and it is the one already fixed
by `855fad5`. Redirect-family-vs-redirect-family on a shared method occurs at exactly two sites: W1
(fatal, fixed) and `LevelRenderer.compileSections` (cw `@Redirect` on a `GETFIELD` vs IP `@ModifyVariable`
on a local `STORE` — different instructions, both weave; **safe**). No `@Overwrite` in the IP set lands on
any method a `com.warwa` mixin injects (`ChunkMap.onChunkReadyToSend` ≠ the block-era `markChunkPendingToSend`);
`com.warwa` has zero `@Overwrite`/`@WrapMethod`. Every other shared-target overlap is `@Inject`-family that
composes or is guard-inert flag-ON:
- `Minecraft.updateLevelInEngines` — KEEP `MinecraftMixin` + IP `MixinMinecraft`, both `@Inject` HEAD;
  IP's own javadoc explicitly acknowledges the mod hook; mod body operates on empty block-era state flag-ON.
- `ParticleEngine.extract` — KEEP `ParticleEnginePortalSkipMixin` + IP `MixinParticleEngine`, both `@Inject`
  HEAD cancellable; cw guard `PortalContextSwitch.isRenderingPortal` is false flag-ON (§1 row 15) → inert.
- `MultiPlayerGameMode` (B7), `ClientPacketListener.handleMovePlayer`/`handleForgetLevelChunk` (B6 inert
  flag-ON), `handleLevelChunkWithLight` (`@ModifyArg` vs `@Inject`), `handleAddEntity`
  (`@Inject`+`@Inject`), `GameRenderer.renderItemInHand` (`@ModifyArg` vs `@Inject`), A7 bob (resolved).

**The 13 gated block-era drivers** (§1 §4c) were re-checked at the weave layer flag-ON and are all clear —
their targets are either untouched by IP or the two sets touch disjoint methods/instructions; none needs a
suppression-set entry (`EXCLUSIVITY_LEDGER.md §7.3`). **The KEEP substrate** contacts the IP set at three
targets (`GlStateManager`, `LevelExtractor`, `ParticleEngine`) and **none breaches** — all disjoint or
`@Inject`-coexist (`§7.4`).

**Discipline.** Docs-only stage: the sweep produced NO new suppression-set entries (the `855fad5` set is
complete), hence NO code change beyond that prior commit — `SeamlessMixinConfigPlugin` is untouched by
S13-E. No gradle run (nothing recompiles; the plugin/mixin JSON are byte-identical to the `855fad5` tree),
no `git commit`, game not run. Deliverables: `EXCLUSIVITY_LEDGER.md §7` (suppression set W1 + per-entry
superseder/coverage + the full 18-class shared-target census + the `@Overwrite`/13-driver/KEEP sub-audits)
+ this record.

## 14. S13-F — FIRST IN-WORLD CROSSER FIXES (attempt-4 live-world defects + duck/registry one-pass sweep)

The boot-time landmines fixed by the intervening committed stages (S13-C weave anchors → attempt-1;
S13.8 self-identity → attempt-2; S13-E `@Redirect` collision → attempt-3; S13.12 `MixinFrustum` null
guard → render-frame NPE) got first-light **attempt 4** all the way to **a LIVE, TICKING WORLD** — player
logged in, the IP login protocol + sea-level sync + the `ImmPtlClientChunkMap` all working. Attempt 4 then
detonated the **first two IN-WORLD (post-login, per-tick) defects** — one server-tick, one client-tick.
Both share a single root SHAPE: **an IP consumer whose IMPLEMENTOR/ASSIGNMENT half was landed decoupled
from the consumer** (a duck cast with no registered implementor; a registry-static declared but never
registered). S13-F fixes both AND runs the two exhaustive one-pass censuses that shape mandates, so
attempt 5 cannot detonate on a sibling of either class.

### 14.1 CRASH 1 (server tick) — `TICKET_TYPE` declared but never registered (registry-phase static)

`NullPointerException` in `net.minecraft.server.level.Ticket.<init>` ("type" is null), via
`ImmPtlChunkTickets.addTicket` (`:249-251`) → `((IEDistanceManager) dm).ip_getTicketStorage()
.addTicketWithRadius(TICKET_TYPE, …)`, driven from the `DistanceManager.runAllUpdates` mixin
(`flushThrottling`). **Root:** `ImmPtlChunkTickets.TICKET_TYPE` (`:69`) is `public static TicketType`,
DECLARED but never assigned at runtime. On 1.21.3 IP static-inited it with `TicketType.create(…)`; on 26.2
that API is GONE (api-map chunk-loading #23 — `TicketType` is now a `BuiltInRegistries.TICKET_TYPE`-registered
`record(long timeout, int flags)` and `register(...)` is PRIVATE), so a bare unregistered instance NPEs the
moment `TicketStorage` builds a `Ticket` from it. Its own javadoc (`:56-67`, R10(i)) already mandated the
registration run at REGISTRY PHASE through the KEEP'd `com.warwa.seamlessportals.mixin.TicketTypeInvoker` —
that wiring was simply never landed.

**Fix (crash-1).** `fabric: SeamlessPortalsModFabric.onInitialize:66-79` assigns
`ImmPtlChunkTickets.TICKET_TYPE = TicketTypeInvoker.seamlessportals$invokeRegister("imm_ptl",
TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION)` — **UNCONDITIONAL** (above the
`if (isEntityPortals())` branch, D3 registries-unconditional). Shape `FLAG_LOADING|FLAG_SIMULATION` (=6),
`NO_TIMEOUT`, no persist = the exact 26.2 translation of IP's load+entity-tick ticket (vanilla `DRAGON`'s
shape). **Correct phase — proven by the block-era pattern:** 26.2 `TicketType.register` = `Registry.register(
BuiltInRegistries.TICKET_TYPE, name, new TicketType(…))`, and the shipping block-era mod already registers
THREE of its own ticket types through the SAME `TicketTypeInvoker` at the mod-init window (`PortalChunkTracker
:268` `seamlessportals_chunk_residency`, `PortalEntityTracker:198/:225` `seamlessportals_mirror_view`/
`_portal_prewarm`) — those trackers are instantiated as fields of `SeamlessPortalsModFabric` (`:30-31`), so
their class-init registration fires at mod-object construction, the same window this assignment runs in. If
the block-era registration ships working at that phase, this one does too. Registered in both flag states
(harmless — ticket types are a code registry, never world-saved; `addTicket` only runs flag-ON) and the id
`imm_ptl` cannot collide with the block-era `seamlessportals_*` names. Files: `SeamlessPortalsModFabric.java`
(the unconditional assignment); `TicketTypeInvoker` (KEEP, already registered in `seamlessportals-common
.mixins.json:77`) forwards the private `register`.

### 14.2 CRASH 2 (client tick, fatal) — `IEWorldRenderer` duck cast with no registered implementor

`ClassCastException: LevelRenderer cannot be cast to qouteall.imm_ptl.core.ducks.IEWorldRenderer` at
`ImmPtlViewArea.lambda$init$1` (`:122`) — the `POST_CLIENT_TICK_EVENT` handler casting each of
`ClientWorldLoader`'s `LevelRenderer`s to `IEWorldRenderer` to call `ip_getBuiltChunkStorage()`. **Root:** the
duck interface EXISTS and has live consumers (`ImmPtlViewArea.init` ×2, `MyGameRenderer`, `ClientWorldLoader`,
`ClientDebugCommand`) but **NO registered mixin implemented it on 26.2's `LevelRenderer`.** IP's monolithic
`MixinWorldRenderer implements IEWorldRenderer` was split across 26.2 slices during the render port and the
**duck-implement half was never landed** — only the injection halves (R3 clip, R4 install) made it across.

**Fix (crash-2).** The registered R4-install mixin `qouteall…mixin/client/render/MixinLevelRenderer` (already
`@Mixin(LevelRenderer.class)`, already in `seamlessportals-ip-client.mixins.json`) now
`implements IEWorldRenderer` and lands **every SURVIVING member 1:1** with IP's original
(`IP:MixinLevelRenderer:520-578`), by plain `@Shadow` of the private 26.2 `LevelRenderer` fields — verified
against `26.2:LevelRenderer.java`: `entityRenderDispatcher`:103 (`private final` → `@Shadow @Final`),
`renderBuffers`:105 (`private final` → `@Shadow @Final @Mutable`), `visibleSections`:119 (`private final` →
`@Shadow @Final @Mutable`), `viewArea`:121 (`private`, non-final → `@Shadow`). **Plain `@Shadow` resolves
private target fields natively — no AW+AT needed** (the verbatim IP pattern). Two members diverge by 26.2
necessity, both documented: `ip_myRenderEntity` stays RETIRED (its `MultiBufferSource` param type is GONE,
S11-C — the duck already dropped it); and `cullingFrustum` is GONE on 26.2 (the culling frustum is a
per-render-pass local, no `LevelRenderer` field), so `portal_getFrustum`/`portal_setFrustum` back onto a
mixin-owned `@Unique Frustum ip_cullingFrustum` — the same "mixin owns the storage the removed vanilla field
supplied" idiom `ImmPtlViewArea` uses for its G25 grid fields; harmless because nothing external mutates a
26.2 `LevelRenderer` frustum, so `MyGameRenderer`'s save/restore round-trips. File:
`MixinLevelRenderer.java`. **Class-shape note:** IP's was `public abstract class` (for the abstract
`@Shadow renderEntity`); with that member retired the port is a `public class` (no abstract shadow), legal.

### 14.3 THE ONE-PASS SWEEP — census (A) DUCK-IMPLEMENTORS + census (B) REGISTRY-PHASE STATICS

Because BOTH crashers are "the second half of a two-part landing was dropped," the shape mandates sweeping
BOTH classes exhaustively so attempt 5 cannot hit a sibling. **Result: exactly ONE additional latent crasher
of the duck class (`IEFrameBuffer`), fixed here; zero of the registry class beyond `TICKET_TYPE`.**

**(A) DUCK-IMPLEMENTOR CENSUS.** Every interface under `qouteall/imm_ptl/core/ducks/**`,
`qouteall/q_misc_util/**/ducks`, and every `IE*` in mixin/compat/peripheral packages — for each, (i) its
consumers (casts/invocations) and (ii) its implementor (a REGISTERED mixin). A reachable-flag-ON consumer
with no registered implementor = crasher.

| Class | Count | Implementor / verdict | Crash? |
|---|---|---|---|
| `ducks/**` implemented by a SEPARATE `implements IEXxx` mixin | 34 | all 34 implementors registered in `ip-core-common`/`ip-client`/`ip-qmisc` (`IEChunkMap` on both `MixinChunkMap_C`+`_E`); incl. the 2 S13-F fixes | **CLEAR** |
| `ducks/**` with NO implementor AND NO consumers (dead) | 5 | `IEPlayerEntity`, `IEPlayerListEntry`, `IEShader`, `IESimpleRegistry`, `IEWorldChunk` — zero casts/imports repo-wide | CLEAR (inert) |
| `@Accessor`/`@Invoker` `IE*` in mixin packages (self-implementing) | 19 | all registered (`ip-client`/`ip-core-common`/`ip-qmisc`) | CLEAR |
| `@Accessor`/`@Invoker` `IE*` UNREGISTERED — deferred sets | 8 | Iris(2)+Sodium(2)+peripheral alt-dim(4); consumers only in `iris_/sodium_compatibility` (compat-gated OFF in vanilla) + `NormalSkylandGenerator` (C1/S19-gated) → **no reachable vanilla flag-ON consumer** | CLEAR (deferred) |

The two duck-class fixes:
- **`IEWorldRenderer` → `MixinLevelRenderer`** (crash-2, §14.2). Consumer reachable per-tick.
- **`IEFrameBuffer` → NEW `MixinRenderTarget`** (the sweep's one latent sibling). Consumer
  `IPPortingLibCompat.getIsStencilEnabled/setIsStencilEnabled` (`:40,:63`), driven by
  `RendererUsingStencil.prepareRendering` / `RendererUsingFrameBuffer.finishRendering` — reachable flag-ON
  **the instant a portal renders** (first-light rung 1 = same-dim command portals, so it WOULD have fired).
  IP implemented it on `framebuffer.MixinRenderTarget` via an `isStencilBufferEnabled` field + a
  `createBuffers` `@ModifyArgs` stencil-format inject; that whole FBO-creation model is GONE on 26.2 (S13B §3,
  the mixin was RETIRED). **26.2 re-expression (documented disposition, not a 1:1 field port):** the mod
  substrate (`com.warwa…stencil.RenderTargetMixin` on `FrameBufferCache` + `GlConstMixin`) makes every render
  FBO stencil-capable UNCONDITIONALLY, so `ip_getIsStencilBufferEnabled()` → `true` (correctly skips
  `IPPortingLibCompat`'s "if not enabled, enable+reload" body) and `ip_setIsStencilBufferEnabledAndReload()` →
  no-op (no per-target stencil field, no reload — the buffer exists regardless). `@Mixin(RenderTarget.class)`
  propagates to `MainTarget`/`TextureTarget` (main + secondary FBOs). Pure interface-impl mixin (no
  injectors → no injection-point collision); it is the SOLE mixin on `RenderTarget.class`, and the block-era
  `stencil/RenderTargetMixin` targets `FrameBufferCache` (a different class) — no weave contact. Registered
  flag-ON in `ip-client.mixins.json`, skipped flag-OFF by the plugin. File: NEW
  `qouteall/imm_ptl/core/mixin/client/render/MixinRenderTarget.java`.

**(B) REGISTRY-PHASE INIT CENSUS.** Every ported static assigned via a registration/bootstrap call — verify a
live assignment site is wired in the S13 init order; unassigned-with-reachable-consumer = fix. (No
`AttachmentType` statics exist in the ported tree; the only `TicketType` static is `TICKET_TYPE`.)

| Static | Assignment site | Phase / reachability | Verdict |
|---|---|---|---|
| `ImmPtlChunkTickets.TICKET_TYPE` | `SeamlessPortalsModFabric:78` (UNCOND) | mod-init, registry-phase | **FIXED (crash-1)** — was unassigned |
| `IPCGlobal.renderer` / `rendererUsingStencil` / `rendererUsingFrameBuffer` | `IPModMainClient:81-84` | client init flag-ON (via `SeamlessPortalsClientFabric:55`); reached when a portal renders | WIRED ✓ |
| `FogRendererContext.swappingManager` / `copyContext*` | `FogRendererContext.init()` ← registered `MixinFogRenderer` (`ip-client:26`) | client render, fog pass | WIRED ✓ |
| `ImmPtlViewArea.init()` (POST_CLIENT_TICK + unload-signal registration) | `IPModMainClient:119` | client init flag-ON | WIRED ✓ (its cast now safe via §14.2) |
| `IPGlobal.configHolder` | `AutoConfig.register` in `loadConfig()` (S13B §7 P1) | flag-ON `IPModMain.init` | WIRED ✓ |
| `ImmPtlNetworkConfig.immPtlVersion` | `ImmPtlNetworkConfig:212` | flag-ON init | WIRED ✓ |
| `DimensionIntId.clientRecord` | `MiscNetworking:117` (on `DimIdSyncPacket`) | client, post-join sync | WIRED ✓ |
| entity types / placeholder block / arg types / IP payloads | `SeamlessPortalsModFabric` WIRE-2 (§6) | mod-init, UNCOND (D3) | WIRED ✓ |

### 14.4 LESSON — a duck/registry consumer and its landing must ship together

**A duck interface needs its implementor landed WITH it; a registry-phase static needs its registration
wired WITH the declaration.** Both attempt-4 crashers were the same structural failure: the second half of a
two-part landing was decoupled from the first and dropped. `IEWorldRenderer` shipped its consumers (and its
whole `ducks/` declaration) but not the `implements` mixin; `TICKET_TYPE` shipped its declaration and its
`addTicket` consumer but not the `register` call. Neither is visible to `javac` or to `:common:test` — a duck
cast type-checks against the interface, and a `public static` field reads as assigned — so both survive every
green build and only detonate at the runtime cast/deref. **Rule going forward: when an IP mixin is split or a
member retired during the port, the duck-implement half must be re-homed onto a REGISTERED mixin in the same
change (or, if the implementor is deferred, its consumers must be deferred too and proven unreachable
flag-ON). When a registry-static's `create/register` API changes, its assignment must be re-wired at the
correct registry phase in the same change.** The one-pass sweep is the standing guard: any `(IEXxx)` cast
must resolve to a registered implementor, and any bootstrap-assigned static must have a live wired assignment
site — verified by census, because neither can be caught by the compiler.

**Discipline.** ZERO IP-logic deviation (the two `@Shadow`/`@Unique` re-expressions and the `MixinRenderTarget`
substrate disposition are forced 26.2 adaptations, each documented in-line + here). AW+AT PAIRED where needed
(none needed — plain `@Shadow` resolves the private `LevelRenderer` fields). Registrations UNCONDITIONAL (D3
— `TICKET_TYPE`); duck-implement behavior flag-gated (the two new/edited mixins live in `ip-client.mixins.json`,
skipped flag-OFF by `SeamlessMixinConfigPlugin`; flag-OFF byte-inert). Files touched:
`fabric: SeamlessPortalsModFabric.java` (crash-1), `qouteall…MixinLevelRenderer.java` (crash-2), NEW
`qouteall…MixinRenderTarget.java` + `seamlessportals-ip-client.mixins.json` (`IEFrameBuffer` sibling). Sweeps
**2/2**. Per the S13-F task directive: **no gradle run, no `git commit`, game not run** — the orchestrator
ships `:common`/`:fabric`/`:neoforge` + `:common:test` and commits.
