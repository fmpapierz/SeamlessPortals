# S00-seam-inventory — named hook points + loader-neutral seams (Stage S0)

**Stage:** S0 (Substrate + decisions, U0) · **Written:** 2026-07-13
**Governing plan:** `migration/EXECUTION_PLAN.md` §3 S0(a) ("Loader-neutral seams" + "Tick/login/
frame seam inventory") and "Immediately-next actions" items 4–5. Hook-point source of truth:
`migration/DEPENDENCY_ORDER.md` §4.2 (runtime ordering constraints). Registration rows:
`migration/api-map/portal-core.md` §4 F1–F5; `migration/api-map/q-misc-util.md` F1–F6;
`migration/api-map/current-mod-core.md` §3 (PlatformHelper KEEP + widen).

**The rule this file enforces (plan §3 S0(a)):** later stages wire IP's runtime ordering into the
NAMED seams below — never ad-hoc call sites. Every entry records (i) the hook and its IP-side
ordering contract, (ii) the 26.2 anchor (from the corpus api-maps), (iii) which mod file hosts it,
(iv) which stage lands the code and which stage wires it live. Per D2 (S00-U0-decisions.md), seam
code is mod-owned (`com.warwa.seamlessportals.*`); ported code under `qouteall.*` never contains
flag dispatch or loader types.

---

## Part A — DEPENDENCY_ORDER §4.2 hook-point inventory

### A1. Init sequence — common/server side

- **IP contract (§4.2 bullet 1):** `MiscUtilModEntry` first (`ImplRemoteProcedureCall.init` →
  `MiscNetworking.init` → `DimensionIntId.init`, q-misc-util.md:468-471), then `IPModMain.init`
  (networking :67-69 → global portals :83 → teleport manager :87 → collision :89 → commands
  :103-108 → `ServerTaskList.init` :112 → `CustomPortalGenManager.init` :114 → config :146;
  ducks-api-misc.md §5, world-loader-root.md §5).
- **26.2 anchor:** none (loader entrypoint, not a vanilla injection). IP's entrypoint classes
  (`MiscUtilModEntry`, `IPModEntry`) are NOT ported — their cargo is absorbed here
  (EXECUTION_PLAN Appendix A.9; S00-U0-decisions D2 corollary).
- **Host:** `fabric/src/main/java/com/warwa/seamlessportals/fabric/SeamlessPortalsModFabric.onInitialize`
  (+ NeoForge: `SeamlessPortalsModNeoForge` ctor / `onCommonSetup`). The init call block is added
  ONCE, in the §4.2 order above, flag-gated per D3 (mod code dispatches; IP bodies untouched).
- **Stages:** init-sequence code (IPModMain et al.) lands S10 (held); wired live behind
  `entityPortals` at S13 step 4.

### A2. Init sequence — client side

- **IP contract (§4.2 bullet 1):** `MiscUtilModEntryClient` (`ImplRemoteProcedureCall.initClient`
  → `MiscNetworking.initClient`) then `IPModMainClient` (teleport client :74 → renderers ON THE
  RENDER THREAD :76-85 → collision client :93 → networking client :125-126 →
  `DimensionIntId.initClient` :134; world-loader-root.md §5).
- **26.2 anchor:** none (loader client entrypoint). `IPModEntryClient` is not ported; its
  `initPortalRenderers` cargo (IPModEntryClient.java:41-61) rides the B4 renderer seam.
- **Host:** `fabric/src/main/java/com/warwa/seamlessportals/fabric/SeamlessPortalsClientFabric.onInitializeClient`
  (+ NeoForge `onClientSetup` — currently deliberately unwired; the module's client integration is
  maintained on Fabric, see the note in `SeamlessPortalsModNeoForge`).
- **Stages:** IPModMainClient lands S10 (held); wired S13 step 4; renderer registrations S13
  step 5 via B4.

### A3. Login order — `DimIdSyncPacket` mid-login slot + global-portal sync

- **IP contract (§4.2 bullet 2):** `DimIdSyncPacket` sent mid-`placeNewPlayer`, BEFORE the
  difficulty packet (network.md §5); global-portal sync at `onPlayerLoggedIn` AFTER it
  (`GlobalPortalStorage.onPlayerLoggedIn`, IP GlobalPortalStorage.java:135, invoked from IP
  `mixin/common/other_sync/MixinPlayerList.java:48`). Dynamic-dim ordering (dim-int-id map updates
  before global-portal storage reacts) is a DimLib-phase concern (q-misc-util.md F5/F6) — DimLib
  is out of scope; the static path preserves the invariant by the A1 init order + this login order.
- **26.2 anchor:** `PlayerList.placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)`
  (26.2 `server/players/PlayerList.java:142`); INVOKE point = `new ClientboundChangeDifficultyPacket(...)`
  (`:185`, ctor descriptor unchanged — network.md S17, verified there).
- **Host:** the ported `qouteall/q_misc_util/mixin/dimension/MixinPlayerList_Misc` (the mid-login
  slot) + ported `qouteall/imm_ptl/core/mixin/common/other_sync/MixinPlayerList` (the logged-in
  sync) — both 1:1 ports, not seams; listed here because the ORDER is the §4.2 constraint.
- **Stages:** `MixinPlayerList_Misc` lands S7 (held; registered S13 — S00-U0-decisions D2
  corollary); `other_sync/MixinPlayerList` lands S10 (core-common mixin JSON), registered S13.
  NOTE: `MixinPlayerList_Misc` is in package `qouteall.q_misc_util.mixin` — it needs the
  q_misc_util mixin JSON created AT S7 (see C1 below; a mixin config resolves entries relative to
  ONE package, so the three S0 JSONs cannot list it).

### A4. Client tick order — ONE injection point

- **IP contract (§4.2 bullet 3):** from a single injection: `ClientWorldLoader.tick()` →
  `RenderStates.setPartialTick(0)` → `StableClientTimer.tick()`/`update(...)` →
  `ClientPortalAnimationManagement.tick()` → `manageTeleportation(true)` →
  `POST_CLIENT_TICK_EVENT` (world-loader-root.md §5; verified IP
  `mixin/client/MixinMinecraft.onAfterClientTick`, MixinMinecraft.java:119-142 — @Inject at
  `Minecraft.tick` INVOKE `ClientLevel.tick(BooleanSupplier)` shift AFTER).
- **26.2 anchor:** intact — `Minecraft.tick()` (26.2 `Minecraft.java:1758`) still calls
  `this.level.tick(() -> true)` at `:1819` (world-loader-root.md §5 "remote-tick surface
  unchanged" rows :130-132, :157).
- **Host:** the ported `MixinMinecraft` (S12 client mixins, held) carries IP's injection; the
  MOD-OWNED dispatch point is the existing END_CLIENT_TICK block in
  `SeamlessPortalsClientFabric.onInitializeClient` (today it drives the block-era per-tick pumps —
  drainChunks/compile pipelines/etc.). At S13 the flag decides which chain runs (D3 shared-host
  rule; both never run in one session — EXCLUSIVITY_LEDGER).
- **Stages:** chain classes land S8 (teleportation) / S10 (ClientWorldLoader, StableClientTimer
  wiring); MixinMinecraft lands S12; live flag dispatch S13 steps 3–4.

### A5. Frame order — pre-render pump (the R2 anchor)

- **IP contract (§4.2 bullet 4):** `RenderStates.updatePreRenderInfo(partialTick)` →
  `StableClientTimer.update(...)` → `ClientPortalAnimationManagement.update()` →
  `manageTeleportation(false)` → `PRE_GAME_RENDER_EVENT` — MUST precede portal rendering each
  frame (teleportation-collision.md §5; verified IP
  `mixin/client/render/MixinGameRenderer.onFarBeforeRendering`, MixinGameRenderer.java:70-100;
  the A4-upkeep analog block sits at :86-96).
- **26.2 anchor (R2, decided + soaked at S3):** fidelity default = inside `Minecraft.renderFrame(Z)V`
  BEFORE the `gameRenderer.update(...)` call (26.2 `Minecraft.java:1290`; panorama/screenshot path
  `Minecraft.java:2779-2781` bypasses it, reproducing IP's non-firing there —
  portal-animation.md #14). Documented fallback: `GameRenderer.update` HEAD (current-mod-render
  §2.2, the existing `GameRendererFrameCrossingMixin` site). Decision + evidence land in
  `migration/port-notes/S03-frame-anchor.md`.
- **Host:** the S3-relocated MOD-OWNED pre-render pump host mixin (today
  `common/.../mixin/client/GameRendererFrameCrossingMixin.java`; also receives the A4 upkeep cargo
  from `GameRendererPortalPrepareMixin` at S3). At S13 this host becomes the flag dispatch point:
  old block-era pump vs the ported IP call chain above.
- **Stages:** S3 (relocation + live soak, EXECUTION_PLAN §3 S3) → S13 step 3 (flag dispatch).

### A6. Server tick order

- **IP contract (§4.2 bullet 5):** `ImmPtlChunkTracking.tick` (watch updates; internally →
  `EntitySync.update`/`tick`, IP ImmPtlChunkTracking.java:395-398) → `ImmPtlChunkTickets.tick` →
  entity sync (mixin-common.md §5); `ServerTeleportationManager` ticks on the same
  END_SERVER_TICK (IP ServerTeleportationManager.java:66). In IP these are Fabric
  `ServerTickEvents.END_SERVER_TICK` registrations INSIDE core classes (also WorldInfoSender.java:19,
  CollisionHelper.java:432, ServerTaskList.java:11, ServerPerformanceMonitor.java:18) — Fabric
  types in would-be common code (R13h): at their landing stages these translate to loader-neutral
  event objects built with the B1 EventFactory (each translation recorded against its slice-map
  row), FIRED from the host below in IP's registration order.
- **26.2 anchor:** none needed (loader tick event; the mod already holds the position).
- **Host:** the existing END_SERVER_TICK lambda in `SeamlessPortalsModFabric.onInitialize`
  (today: block-era chunkTracker/entityTracker/preWarm/mirror-flush) + NeoForge
  `SeamlessPortalsModNeoForge.onServerTick`.
- **Stages:** chain classes land S8 (teleportation) / S9 (chunk loading + entity sync), held;
  flag dispatch S13 steps 3–4 (block-era calls gain `!entityPortals` gates per the ledger).

### A7. RPC FQN strings are wire protocol (§4.2 bullet 6 — constraint, not a hook)

`McRemoteProcedureCall` addresses handlers by literal FQN strings
(e.g. `qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos`).
No seam exists or may exist here; the constraint is answered wholesale by D2 verbatim `qouteall.*`
package retention (S00-U0-decisions.md D2 rationale 1).

---

## Part B — S0 seam code landed (this stage, commit 2)

### B1. Event objects — `com.warwa.seamlessportals.event.{Event, EventFactory, ArrayBackedEvent}`

Loader-neutral analog of Fabric's `Event`/`EventFactory.createArrayBacked`, mirroring the exact
semantics IP's `Helper.createRunnableEvent/createConsumerEvent/createBiConsumerEvent` rely on
(IP Helper.java:1424-1455; q-misc-util.md F4 — "highest-impact Fabric-API item in the slice";
plan §3 S0(a); R13h): synchronous registration-order dispatch, exception propagation (remaining
listeners skipped), null rejection, duplicate dispatch, snapshot-on-registration. Semantics are
regression-tested by `common/src/test/java/com/warwa/seamlessportals/event/EventSeamTest.java`.
**S2 delegation contract (recorded in the class javadoc):** the ported `qouteall.q_misc_util.Helper`
keeps its factory bodies shape-verbatim and swaps only the Fabric imports for this package — the
one approved loader-seam substitution hunk under the D4.3 diff gate. Fabric's phase system
(`addPhaseOrdering`, F5) is intentionally absent — only used against DimLib's external event (F6,
out of scope); widen here if a stage ever proves the need.

### B2. Payload registration + receivers — `PlatformHelper` widening

`registerClientboundPayload` / `registerServerboundPayload` /
`registerServerPayloadHandler` / `registerClientPayloadHandler`
(common `network/PlatformHelper.java`; Fabric impl `fabric/.../FabricPlatformHelper.java`;
NeoForge queue-and-drain parity `neoforge/.../NeoForgePlatformHelper.java`). Absorbs the Fabric
API v6 renames (R13h; network.md headline 5: `playS2C/playC2S` → `clientboundPlay()/serverboundPlay()`).
Threading contract: handlers run on the receiving side's MAIN thread (Fabric v6 play-payload
contract, verified against fabric-networking-api-v1 6.3.3 sources; NeoForge parity via
`enqueueWork`). Consumed at S7 (`ImmPtlNetworking.init/initClient`, `MiscNetworking.init/initClient`
poured into this seam — portal-core.md F4). NOTE (portal-core.md F3): `ServerPlayNetworking.createS2CPacket`
is GONE in v6; `Portal.getAddEntityPacket`'s replacement is the plain vanilla
`new ClientboundCustomPayloadPacket(payload)` — constructible from common code directly, NO seam
method needed (decided here so S6/S7 don't re-litigate it).

### B3. Entity-type registration callback — `PlatformHelper.registerEntityTypes`

`void registerEntityTypes(Consumer<BiConsumer<Identifier, EntityType<?>>>)` mirrors how IP's
Fabric entrypoint consumes `IPModMain.registerEntityTypes(BiConsumer)` (IP IPModEntry.java:18-20;
world-loader-root.md §5; portal-core.md F1 — entity types themselves are built loader-neutrally
with the vanilla `EntityType.Builder`, no Fabric builder). Fabric impl applies the source
immediately (`Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type)` — 26.2
`net.minecraft.core.Registry:111` takes `Identifier`); NeoForge impl queues +
`drainEntityTypeRegistrations(sink)` for its registry-event window. Wired at S13 step 5,
UNCONDITIONAL in both flag states (D3 registries-unconditional rule). The S13 call site is
`PlatformHelper.getInstance().registerEntityTypes(IPModMain::registerEntityTypes)`.
(Block registration — `IPModMain.registerBlocks`, the `PortalPlaceholderBlock` — is NOT seamed at
S0; the plan's S0 list does not include it. Add the parallel method at S13 when the placeholder
block registers; same shape.)

### B4. Entity-RENDERER registration — `PlatformHelper.registerEntityRenderer`

`<E extends Entity> void registerEntityRenderer(EntityType<? extends E>, EntityRendererProvider<E>)`
mirrors the Fabric `EntityRendererRegistry.register` calls in IP's
`IPModEntryClient.initPortalRenderers` (IPModEntryClient.java:41-61; portal-core.md F2; plan
§3 S0(a) + Appendix A.9 — the entrypoint class is not ported, so this seam carries its cargo).
Client dist only. Fabric impl calls `EntityRendererRegistry.register` (present in fabric-api
0.152.1+26.2, deprecated-but-functional, handles pre/post-dispatcher registration); NeoForge impl
queues + `drainEntityRendererRegistrations(sink)` for `EntityRenderersEvent.RegisterRenderers`
when that module's client path is wired. Wired at S13 step 5: `PortalEntityRenderer` for the
9-type Portal family + `LoadingIndicatorRenderer` for `LoadingIndicatorEntity` — without this,
rung 1 renders no portal at all (EXECUTION_PLAN §3 S13 step 5).

---

## Part C — mixin-config skeletons (this stage; D1: created, NOT registered)

Three empty IP-side mixin configs in `common/src/main/resources/`, named per the mod's existing
convention (`seamlessportals-<name>.mixins.json`), per plan §3 S0(a) "core-common / client /
peripheral, mirroring IP's plugin split" (current-mod-core.md §8: `IPMixinPlugin` /
`IPPeripheralMixinPlugin`; the `SeamlessMixinConfigPlugin` is the KEEP'd gate for all of them —
D3 load-time flag read). All three declare `"plugin": "com.warwa.seamlessportals.mixin.SeamlessMixinConfigPlugin"`
now (inert while unregistered) so S13's registration commit only ADDS the fabric.mod.json /
neoforge.mods.toml entries + the plugin's `entityPortals` gate.

| File | Package | Populated by | Registered |
|---|---|---|---|
| `seamlessportals-ip-core-common.mixins.json` | `qouteall.imm_ptl.core.mixin` (`"mixins"` array — the `common.*` tree) | S10 (74 common mixins, listed as they land, held classes) | S13 step 2 |
| `seamlessportals-ip-client.mixins.json` | `qouteall.imm_ptl.core.mixin` (`"client"` array — the `client.*` tree) | S12 (~62 client mixins) | S13 step 2 |
| `seamlessportals-ip-peripheral.mixins.json` | `qouteall.imm_ptl.peripheral.mixin` | S13 (alternate_dimension accessor interfaces), S16 (ignition mixins), S19 (rest, user decision) | S13 step 2 |

**C1 — known follow-ups the three files CANNOT cover** (a mixin config resolves entries relative
to ONE `"package"`):

1. **q_misc_util mixin JSON — created at S7.** The q_misc_util mixin tree
   (`qouteall.q_misc_util.mixin.*`: `MixinMinecraftServer_Misc`, `dimension.MixinPlayerList_Misc`,
   `client.MixinGui_Overlay`; `IELevelStorageAccess_Misc` lands S4 per plan S4(a)) lands at S7 and
   needs its own config (IP: `q_misc_util.mixins.json`, no plugin — ours gets the same
   SeamlessMixinConfigPlugin gate for D3 uniformity). Registered S13 step 2 with the other three.
2. **compat mixin JSON — only if needed, S19-gated.** IP's `imm_ptl_compat.mixins.json`
   (`qouteall.imm_ptl.core.compat.mixin`: sodium/iris/flywheel) is never registered on 26.2 —
   the plugin's sodium/iris-present gates stay false (S00-U0-decisions F21); the file is created
   only if S19/C2 revives runtime compat.
3. IP's `imm_ptl_fabric.mixins.json` (platform_specific) is NOT mirrored — its classes are
   entrypoint-adjacent and absorbed by the Part B seams (Appendix A.9).

---

## Part D — test infrastructure (this stage)

- `common/build.gradle`: junit-bom 5.13.4 + junit-jupiter (`testImplementation`),
  junit-platform-launcher (`testRuntimeOnly`, Gradle 9 requirement), `test { useJUnitPlatform() }`,
  `mavenCentral()` appended after plugin repos. `:common:compileTestJava` already carries its OWN
  held-paths list (`IpHeldPaths.TEST_HELD_PATHS`, D1.3 — landed with S0 commit 1).
- `common/src/test/java/com/warwa/seamlessportals/InfraSmokeTest.java` — the permanent infra
  smoke test (plan §3 S0(a)).
- `common/src/test/java/com/warwa/seamlessportals/event/EventSeamTest.java` — pins the B1
  semantics contract.
- Growth path: S2 adds the authored DQuaternion/Plane invariant tests (live) + the carried
  Mesh2DTest/HelperTest (held via TEST_HELD_PATHS exclusions until S13) — EXECUTION_PLAN §1 D4.5,
  §3 S2(a).
