# DEPENDENCY ORDER — subsystem graph and port sequence

Derived from the 16 inventory docs in `migration/inventory/` plus direct spot-checks against IP source
(`C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`, cited as `IP:` below).
Every doubted edge below was re-verified by opening the IP file; citations are `IP:<path>:<line>`.

**Headline finding (read first):** at strict `javac` granularity, IP is **one giant strongly-connected
component**. About a dozen "hub" classes (`Helper`, `McHelper`, `O_O`, `IPConfig`, `IPPerServerInfo`,
`PortalAPI`, `Portal`, `PortalRenderer`, `GlobalPortalStorage`, `ImmPtlNetworking`, `PortalManipulation`,
`PortalWandInteraction`) each import across subsystem boundaries in both directions, so **no subsystem-level
unit compiles green in isolation**. The port order below is therefore (a) a *review/landing* order inside the
SCC, with one declared **first-green-build checkpoint** where the SCC closes, followed by (b) a genuinely
layered tail (peripheral, compat, cutover) where each unit does compile against only prior units. All cycles
are enumerated in §3 with citations; none may be "fixed" by code changes (zero-deviation rule) — they are
facts to schedule around, not bugs.

---

## 1. Units (subsystem-level, mapped to inventory docs)

| Unit | Contents | Inventory doc |
|---|---|---|
| `q_misc_util` | math/geometry (`my_util/`), `Helper`, `MyTaskList`, signals, loggers, `Animated`, `dimension/` | q-misc-util.md |
| `world-loader-root` | `ClientWorldLoader`, `McHelper`, `CHelper`, `IPMcHelper`, `IPGlobal`/`IPCGlobal`, `IPPerServerInfo`, `IPModMain[Client]`, `ScaleUtils` | world-loader-root.md |
| `portal-core` | `Portal` + subclasses, `PortalState`, `PortalExtension`, `PortalManipulation`, `PortalUtils`, `shape/`, `PortalRenderInfo`, `PortalPlaceholderBlock`, `LoadingIndicatorEntity` | portal-core.md |
| `portal-animation` | `PortalAnimation`, drivers, `UnilateralPortalState`, `StableClientTimer`, `ClientPortalAnimationManagement` | portal-animation.md |
| `teleportation` | `ClientTeleportationManager`, `ServerTeleportationManager`, `TeleportationUtil`, `PortalCollisionHandler`, `CollisionHelper`, `CrossPortalSound` | teleportation-collision.md |
| `network` | `PacketRedirection[Client]`, `ImmPtlNetworking`, `ImmPtlNetworkConfig`, `MiscNetworking`, RPC (`ImplRemoteProcedureCall`/`McRemoteProcedureCall`) | network.md |
| `chunk-loading` | `ImmPtlChunkTracking`, `ChunkVisibility`, `ImmPtlChunkTickets`, `PlayerChunkLoading`, `EntitySync`, `ImmPtlClientChunkMap`, `WorldInfoSender`, perf monitors | chunk-loading.md |
| `render-core` | `MyGameRenderer`, `MyRenderHelper`, `ViewAreaRenderer`, `FrontClipping`, `ShaderCodeTransformation`, `TransformationManager`, `ImmPtlViewArea`, `VisibleSectionDiscovery`, `FrustumCuller`, `CrossPortalEntityRenderer`, `GlQueryObject`/`QueryManager`, `GuiPortalRendering` | render-core.md |
| `render-sub` | `context_management/*` (`RenderStates`, `WorldRenderInfo`, `PortalRendering`, `FogRendererContext`, `DimensionRenderHelper`, `StaticFieldsSwappingManager`), `renderer/*` (`PortalRenderer` abstract, `RendererUsingStencil`, `RendererUsingFrameBuffer`, debug renderers) | render-sub.md |
| `portal-generation` | `BlockPortalShape`, `NetherPortalGeneration`, `NetherPortalMatcher`, `FrameSearching`, `CustomPortalGeneration` + forms/triggers + `CustomPortalGenManager`, `BreakablePortalEntity` family, `global_portals/` (`GlobalPortalStorage` etc.), `IntrinsicPortalGeneration` | portal-generation.md |
| `ducks-api-misc` | 36 duck interfaces, `api/` (`PortalAPI`, `ImmPtlEntityExtension`), `commands/`, `block_manipulation/`, `mc_utils/` (`ServerTaskList`), `miscellaneous/` | ducks-api-misc.md |
| `mixin-common` | 74 server/common mixins (chunk sync, entity sync, position sync, collision, interaction) | mixin-common.md |
| `mixin-client` | ~62 client mixins (multiworld half + render half) | mixin-client.md |
| `platform-compat-peripheral` | `platform_specific/` (entrypoints, `IPConfig`, `O_O`), `compat/` (invoker bases + Sodium/Iris layers), `peripheral/` (wand, dim stack, alt dims, `IPFeatureControl`) | platform-compat-peripheral.md |
| *(target side)* current-mod core/render | what survives / what the port replaces — constrains U0, U7, U10–U11, U13 | current-mod-core.md, current-mod-render.md |

---

## 2. Dependency graph (subsystem level, with notable class-level edges)

Legend: **C** = compile-time (import), **R** = runtime-function (works only if the other subsystem runs).
An edge marked C is implicitly R unless noted. Partial dependencies name the exact classes.

### 2.1 `q_misc_util` (the base — but not a clean base)
- → everything is its *consumer* (import census: `Helper` 86 importers, `DQuaternion` 27, `IntBox` 21, `Plane` 20, `MyTaskList` 14 — q-misc-util.md:22-25).
- **C → world-loader-root**: `Helper` imports `McHelper` for one call (`IP:q_misc_util/Helper.java:29`, used at `:509` — `McHelper.newResourceLocation`). `DimensionIntId` imports `IPCGlobal`, `IPPerServerInfo`, `McHelper` (`IP:q_misc_util/dimension/DimensionIntId.java:18-20`). `MiscNetworking` imports `ClientWorldLoader` (q-misc-util.md:30-31).
- **R → external**: DimLib (`DimensionAPI` events, DimensionIntId.java:16) — the target repo must supply an equivalent dynamic-dimension event source or a static-dimension stub of the *event wiring* only (dimensions themselves are vanilla).
- Pure-math subset (`my_util/` except `Animated` consumers) has **zero** imm_ptl imports — the only genuinely standalone layer in the mod.

### 2.2 `world-loader-root`
- **C → portal-core**: `McHelper` imports `Portal` (`IP:imm_ptl/core/McHelper.java:59`); `CHelper` → `Portal`, `animation.StableClientTimer` (`IP:imm_ptl/core/CHelper.java:25-26`); `ClientWorldLoader` → `Portal` (`IP:imm_ptl/core/ClientWorldLoader.java:39`); `IPMcHelper` → `Portal`, `GlobalPortalStorage`, `render.CrossPortalEntityRenderer` (`IP:imm_ptl/core/IPMcHelper.java:22-24`).
- **C → render-sub**: `ClientWorldLoader` → `DimensionRenderHelper`, `PortalRendering` (`IP:imm_ptl/core/ClientWorldLoader.java:40-41`).
- **C → platform**: `IPGlobal` → `IPConfig` (`IP:imm_ptl/core/IPGlobal.java:8`); `McHelper` → `O_O`, `compat.GravityChangerInterface`, `mc_utils.ServerTaskList`, ducks (`IP:imm_ptl/core/McHelper.java:48-58`).
- **C → teleportation, portal-generation, peripheral**: `IPPerServerInfo` fields import `ServerTeleportationManager`, `CustomPortalGenManager`, `peripheral.wand.PortalWandInteraction`, `DimIntIdMap` (`IP:imm_ptl/core/IPPerServerInfo.java:5-10`).
- **R**: `ClientWorldLoader.tick/cleanUp` driven by `MixinMinecraft` (world-loader-root.md §5); secondary `ClientLevel` content arrives only via network redirection + chunk-loading — ClientWorldLoader *compiles and creates worlds* without them, but the worlds stay empty (R-only edge → network, chunk-loading).

### 2.3 `portal-core` (+ `portal-animation`, inseparable)
- **C → world-loader-root**: `Portal` uses `McHelper`, `CHelper`, `ClientWorldLoader`, `IPGlobal` (portal-core.md:126).
- **C → network**: `Portal.createSyncPacket()` constructs `ImmPtlNetworking.PortalSyncPacket` (`IP:imm_ptl/core/portal/Portal.java:904-919` — verified).
- **C → render-sub (client)**: `Portal.getAdditionalCameraTransformation()` calls `PortalRenderer.getPortalTransformation` (`IP:imm_ptl/core/portal/Portal.java:1575-1580` — verified).
- **C → render-core (client)**: `PortalRenderInfo` imports `GlQueryObject`, `QueryManager`, `RenderStates`, `WorldRenderInfo` (`IP:imm_ptl/core/portal/PortalRenderInfo.java:13-16` — verified). **Shapes also import render-core directly**: `RectangularPortalShape` → `ViewAreaRenderer.outputFullQuad` (`IP:imm_ptl/core/portal/shape/RectangularPortalShape.java:155-157`) and `BoxPortalShape` → `ViewAreaRenderer` (`IP:imm_ptl/core/portal/shape/BoxPortalShape.java:17,199-239` — verified) plus `FrustumCuller` for the culling funcs (`RectangularPortalShape.java:222-241`).
- **C → teleportation-collision (common, NOT client-only)**: the shape strategy calls collision statics — `RectangularPortalShape` imports `CollisionHelper` and `PortalCollisionHandler` and calls `PortalCollisionHandler.getMovementForPushingEntityOutOfPortal` (`IP:imm_ptl/core/portal/shape/RectangularPortalShape.java:12-13,204`) and `CollisionHelper.clipBox` (`:271`) — verified. This is a server-relevant compile edge from portal-core into U6's collision classes.
- **C → teleportation**: `PortalExtension.rectifyClusterPortals` calls `ServerTeleportationManager.teleportRegularEntityTo` (`IP:imm_ptl/core/portal/PortalExtension.java:9,330,369,412` — verified).
- **C → ducks-api-misc**: `Portal.createSyncPacket` uses `PortalAPI.serverDimKeyToInt` (Portal.java:915); `canTeleportEntity` uses `ImmPtlEntityExtension` (portal-core.md:106); deprecated `PortalManipulation.raytracePortals` delegates to `commands.PortalCommand.raytracePortals` (portal-core.md:192).
- **C → compat**: `IrisInterface.invoker` in `renderViewAreaMesh`/`isRoughlyVisibleTo` (portal-core.md:115,117).
- `portal-core ↔ portal-animation` is mutual: `Portal.animation` field (`Portal.java:192`) vs animation's imports of `Portal`/`PortalState`/`PortalExtension` (portal-animation.md:51). Treat as ONE unit.
- **R note**: the C-edges into render (`PortalRenderer`, `PortalRenderInfo`, `IrisInterface`) are client-only code paths — a dedicated server runs portal-core without ever executing them. Compile still requires the classes to exist.

### 2.4 `teleportation`
- **C → portal-core** (`Portal`, `PortalState`, `UnilateralPortalState`, `PortalShape` raytrace — teleportation-collision.md:83), **world-loader-root**, **portal-animation** (`ClientPortalAnimationManagement`/`StableClientTimer`, the four animation state snapshots — portal-animation.md:145), **compat** (`GravityChangerInterface` unconditional — platform-compat-peripheral.md:245), **render** (`TransformationManager` rotation continuity, `MyGameRenderer.vanillaTerrainSetupOverride`, `RenderStates`, `FogRendererContext` — teleportation-collision.md:38, client-only paths).
- **C → chunk-loading**: `ServerTeleportationManager` → `ImmPtlChunkTracking` (teleportation-collision.md:65). **One-directional** — verified: `chunk_loading/` has **zero** imports of `teleportation` (grep of `IP:imm_ptl/core/chunk_loading/`, this pass).
- **C → network**: C2S `TeleportPacket` handled by `ImmPtlNetworking` → `ServerTeleportationManager.onPlayerTeleportedInClient` (teleportation-collision.md:160); S2C entity-pos fix via `McRemoteProcedureCall` string-addressing `ClientTeleportationManager.RemoteCallables.updateEntityPos` (teleportation-collision.md §5 — the FQN is wire protocol; renames break compat).
- **R**: needs mixin-common (`ip_tickCollidingPortal` from `tickNonPassenger` HEAD, position-sync mixins) and mixin-client (`manageTeleportation` call sites, ordered per-frame: `RenderStates.updatePreRenderInfo` → `ClientPortalAnimationManagement.update()` → `manageTeleportation(false)` — teleportation-collision.md §5).

### 2.5 `network`
- **C → portal-core** (`ImmPtlNetworking` → `Portal`, `IP:imm_ptl/core/network/ImmPtlNetworking.java:33`), **portal-generation** (→ `GlobalPortalStorage`, `:34`), **teleportation** (→ `ServerTeleportationManager`, `:35`), **world-loader-root** (→ `ClientWorldLoader`, `McHelper`, `:29-31`), **ducks-api-misc** (→ `PortalAPI`, `:32`).
- **C → q_misc_util** dimension int-ids everywhere; `PacketRedirectionClient` decodes via `DimensionIntId.getClientMap()` (q-misc-util.md:404-408).
- **R ordering**: dim-id sync must arrive before any redirected packet or global-portal sync — enforced by `MixinPlayerList_Misc` sending `DimIdSyncPacket` mid-login before the difficulty packet (q-misc-util.md §2.6) and DimLib early-phase `iportal:early_phase` "dimension int id updates before global portal storage update" (q-misc-util.md:430-433).

### 2.6 `chunk-loading`
- **C → portal-core**: `ChunkVisibility` uses a 10-method slice of `Portal` (`getIsGlobal`, `transformPoint`, `getDestDim`… — chunk-loading.md:73) — needs Portal + GlobalPortalStorage, **not** the renderer, not teleportation.
- **C → network**: `PacketRedirection` for every send (chunk-loading.md:55); RPC for `acceptClientPerformanceInfo` (chunk-loading.md:333).
- **C → mixin-common ducks**: `IEChunkMap`, `IEServerCommonPacketListenerImpl` (chunk-loading.md:55).
- **R → world-loader-root + render**: `ImmPtlClientChunkMap.onLightUpdate` needs per-dimension `LevelRenderer`s from `ClientWorldLoader` (chunk-loading.md:345) — client half only.
- **R**: driven by `ServerTickEvents.END_SERVER_TICK → ImmPtlChunkTracking.tick`, which *also drives* `EntitySync.update/tick` (mixin-common.md §5) — entity sync is functionally a chunk-loading appendage.

### 2.7 `render-core` / `render-sub`
- **C → portal-core** (everything takes `Portal`/`PortalLike`/`Mirror`/shapes), **world-loader-root** (`ClientWorldLoader` per-dim renderers), **ducks** (`IEGameRenderer`, `IEWorldRenderer`, `IEMinecraftClient`… — render-core.md:33), **compat** (Sodium/Iris interfaces + `IPPortingLibCompat` — render-sub.md:173).
- **C → chunk-loading**: `ImmPtlViewArea` and `VisibleSectionDiscovery` import `chunk_loading` (verified by grep of `IP:imm_ptl/core/render/`, this pass — `ImmPtlClientChunkMap` access).
- **C inside render**: abstract `PortalRenderer` imports `MyGameRenderer`, `MyRenderHelper`, `TransformationManager`, `PortalRendering`, `RenderStates`, `WorldRenderInfo`, `GlobalPortalStorage`, **and the Iris compat renderers** (`IP:imm_ptl/core/render/renderer/PortalRenderer.java:17-35` — verified). The Iris renderer subclasses in `compat/iris_compatibility/` extend `PortalRenderer` — compat render shells are compile-mandatory even with no Iris on 26.2 (platform-compat-peripheral.md:245 states the same for all invoker bases).
- **R**: needs network + chunk-loading feeding secondary worlds; needs mixin-client hooks (framegraph lambdas etc.). Whole subsystem is the 26.2 rewrite hotspot; the target repo's proven mechanics (stencil substrate, extract↔compileSections pairing, reversed-Z) transplant here (current-mod-render.md §1, disposition table).

### 2.8 `portal-generation`
- **C → portal-core** (`Portal`, `PortalManipulation`, `PortalPlaceholderBlock`, `LoadingIndicatorEntity`), **chunk-loading** (`ImmPtlChunkTracking.addGlobalAdditionalChunkLoader`, `ChunkLoader` — portal-generation.md §5), **network** (`GlobalPortalStorage` → `ImmPtlNetworking`, `IP:imm_ptl/core/portal/global_portals/GlobalPortalStorage.java:39` — verified; also → `PortalAPI` `:37`), **world-loader-root** (`McHelper`, `CHelper`, `ClientWorldLoader`), **q_misc_util** (`MyTaskList` combinators drive the async pipeline via `ServerTaskList`).
- **NOT → teleportation** (generation never teleports; `PortalExtension`'s cluster move is portal-core's edge).
- **R**: ignition mixins (`MixinAbstractFireBlock_CVB`, `MixinFlintAndSteelItem_CVB` — in *peripheral* mixin package, portal-generation.md §5) + trigger mixins in core; datapack dynamic registries at server start.

### 2.9 `ducks-api-misc`
- Duck **interfaces** compile against vanilla only — they are the cheapest thing in the whole graph and kill dozens of forward edges when ported first. Their *implementations* are the mixins (mixin-common/mixin-client units).
- `PortalAPI` is a hub: imports `ChunkLoader`, `ImmPtlChunkTracking`, `PacketRedirection`, `PortalManipulation`, `GlobalPortalStorage`, `ServerTeleportationManager`, `DimensionIntId` (`IP:imm_ptl/core/api/PortalAPI.java:19-30` — verified). Since `Portal` and `MiscNetworking` call into it, `PortalAPI` sits *inside* the SCC.
- `block_manipulation/`, `commands/` depend on portal-core + network(RPC) + world-loader; `PortalCommand` is referenced *from* `PortalManipulation` (portal-core.md:192), pulling commands into the SCC.

### 2.10 `mixin-common` / `mixin-client`
- Mixin **classes** are compile leaves (nothing imports them except accessor-interface consumers); they depend on their driving subsystem's manager classes. They are the *runtime* enablers: without mixin-common, chunk-loading/entity-sync/teleport-validation never fire; without mixin-client, ClientWorldLoader never ticks and the renderer never enters.
- Land each mixin with its driving subsystem, not as a standalone late unit — a manager without its mixin taps is dead code at runtime, and a mixin without its manager fails to compile.

### 2.11 `platform-compat-peripheral`
- `O_O` imports `McHelper`, `ImmPtlClientChunkMap`, `ImmPtlNetworkConfig`, `Portal`, `PortalGenInfo` (`IP:imm_ptl/core/platform_specific/O_O.java:25-29` — verified). `IPConfig` imports `IPGlobal`, `BlockPortalShape` (generation), `IPFeatureControl` (**peripheral**) (`IP:imm_ptl/core/platform_specific/IPConfig.java:9-11` — verified).
- `PortalWandInteraction` (peripheral) ← imported by `IPPerServerInfo` (core!) and itself imports `IPPerServerInfo`, `CommandStickItem` (`IP:imm_ptl/peripheral/wand/PortalWandInteraction.java:20-30` — verified). This is the single worst layering violation in IP: **core does not compile without two peripheral files** (`PortalWandInteraction`, `IPFeatureControl`) + `CommandStickItem`.
- Everything else in peripheral (wand items/GUI, dim stack, alt dims) and compat (`On*Present` subclasses, Sodium/Iris mixins) is genuinely downstream and optional (platform-compat-peripheral.md §5, appendix).

---

## 3. Cycles (verified; these force unit merges or scheduled co-porting)

Each cycle is stated as the class-level edges that close it. "Merge" = the members must land in the same
green-build closure; the sub-order within a merge is for review only.

1. **`q_misc_util` ↔ `world-loader-root`** — `Helper` → `McHelper` (`IP:q_misc_util/Helper.java:29,509`); `DimensionIntId` → `IPCGlobal`/`IPPerServerInfo`/`McHelper` (`IP:q_misc_util/dimension/DimensionIntId.java:18-20`); `MiscNetworking` → `ClientWorldLoader`; reverse: `McHelper` → `Helper` (`McHelper.java:60`) + 86 importers.
2. **`world-loader-root` ↔ `portal-core`** — `McHelper.java:59` / `CHelper.java:25` / `ClientWorldLoader.java:39` / `IPMcHelper.java:22` → `Portal`; `Portal` → `McHelper`/`CHelper`/`ClientWorldLoader`/`IPGlobal` (portal-core.md:126).
3. **`portal-core` ↔ `portal-animation`** — `Portal.animation` field (Portal.java:192) vs animation → `Portal`/`PortalState`/`PortalExtension` (portal-animation.md:51).
4. **`portal-core` ↔ `teleportation`** — `PortalExtension` → `ServerTeleportationManager.teleportRegularEntityTo` (`IP:imm_ptl/core/portal/PortalExtension.java:9,330`); teleportation → `Portal`/`PortalState`/`PortalExtension` throughout.
5. **`portal-core` ↔ `network`** — `Portal.createSyncPacket` → `ImmPtlNetworking.PortalSyncPacket` (`Portal.java:904-919`); `ImmPtlNetworking` → `Portal` (`ImmPtlNetworking.java:33`).
6. **`portal-core`(client) ↔ `render-core`/`render-sub`** — `Portal.getAdditionalCameraTransformation` → `PortalRenderer.getPortalTransformation` (`Portal.java:1575-1580`); `PortalRenderInfo` → `GlQueryObject`/`QueryManager`/`RenderStates`/`WorldRenderInfo` (`PortalRenderInfo.java:13-16`); render → `Portal` everywhere.
7. **`world-loader-root` ↔ `render-sub`** — `ClientWorldLoader` → `DimensionRenderHelper`/`PortalRendering` (`ClientWorldLoader.java:40-41`); render → `ClientWorldLoader`.
8. **`world-loader-root`/`core` ↔ `platform_specific`** — `IPGlobal` → `IPConfig` (`IPGlobal.java:8`); `McHelper` → `O_O` (`McHelper.java:58`); `O_O` → `McHelper`/`ImmPtlClientChunkMap`/`ImmPtlNetworkConfig`/`Portal`/`PortalGenInfo` (`O_O.java:25-29`).
9. **core ↔ `peripheral`** — `IPPerServerInfo` → `PortalWandInteraction` (`IPPerServerInfo.java:8`); `PortalWandInteraction` → `IPPerServerInfo`/`McHelper`/`Portal`/`CommandStickItem` (`PortalWandInteraction.java:20-30`); `IPConfig` → `IPFeatureControl` (`IPConfig.java:11`).
10. **`network` ↔ `portal-generation`(global portals)** — `ImmPtlNetworking` → `GlobalPortalStorage` (`ImmPtlNetworking.java:34`); `GlobalPortalStorage` → `ImmPtlNetworking`/`PortalAPI` (`GlobalPortalStorage.java:37,39`).
11. **`portal-core` → `api` → `chunk-loading`/`teleportation` (closes back via Portal)** — `Portal.createSyncPacket` → `PortalAPI.serverDimKeyToInt` (Portal.java:915); `PortalAPI` → `ChunkLoader`/`ImmPtlChunkTracking`/`PacketRedirection`/`ServerTeleportationManager`/`GlobalPortalStorage` (`PortalAPI.java:19-30`); `ChunkVisibility` → `Portal` (chunk-loading.md:73).
12. **`render-sub` ↔ `compat`** — abstract `PortalRenderer` imports the Iris compat renderers (`PortalRenderer.java:22-25`), which extend `PortalRenderer`.
13. **`portal-core` ↔ `commands`** — `PortalManipulation.raytracePortals` (deprecated) → `PortalCommand.raytracePortals` (portal-core.md:192); `PortalCommand` → `PortalManipulation`/`Portal`.
14. **`platform_specific` ↔ `portal-generation`** — `IPConfig` → `BlockPortalShape` (`IPConfig.java:10`); `O_O` → `PortalGenInfo` (`O_O.java:29`); generation → `O_O`/`IPConfig` via `IPGlobal` defaults.
15. **`portal-core`(shapes) ↔ `teleportation-collision`** — `RectangularPortalShape` → `PortalCollisionHandler.getMovementForPushingEntityOutOfPortal` / `CollisionHelper.clipBox` (`IP:imm_ptl/core/portal/shape/RectangularPortalShape.java:12-13,204,271` — verified); collision → `Portal`/shapes throughout (`PortalCollisionHandler.getActiveCollisionBox` folds `PortalShape.transformEntityActiveCollisionBox`, teleportation-collision.md §2.6). Unlike cycle 4 (client-only render refs), this one is **common-side** — U4 cannot compile server code without U6's two collision classes, so `CollisionHelper` + `PortalCollisionHandler` (+ `PortalCollisionEntry`) should be **co-ported into U4's closure** (they depend only on U1–U4 material: Portal, shapes, `Range`, `GravityChangerInterface` base, `IEEntity` duck).

**One-directional edges verified NOT to be cycles** (useful for ordering): `teleportation → chunk-loading`
(chunk_loading imports zero teleportation classes — grep verified); `portal-animation → teleportation` absent
(grep verified; teleportation consumes animation, not vice versa); `chunk-loading → render` is runtime-only on
the client light-update path (chunk-loading.md:345); generation → teleportation absent.

**Union**: cycles 1–15 chain into a single SCC:
`q_misc_util ↔ core-roots ↔ portal-core(+animation) ↔ {network, teleportation, chunk-loading(via PortalAPI/ChunkVisibility), render-context(client), platform O_O/IPConfig, GlobalPortalStorage, generation-shape shells, PortalCommand, PortalWandInteraction}`.
Outside the SCC (pure downstream): concrete renderers (`RendererUsingStencil`/`RendererUsingFrameBuffer` —
nothing imports them except the client entrypoint), all mixin *classes*, the generation async pipeline
(`FrameSearching`, `NetherPortalGeneration` internals), `block_manipulation`, debug/misc, and nearly all of
`peripheral` (wand items/GUI, dim stack, alt dims) and `compat` `On*Present` layers.

---

## 4. Port order

Ground rules baked into the sequence:
- The target repo is a multiloader (common/fabric/neoforge) codebase; IP is Fabric-only. Loader-facing
  constructs (Fabric `Event`/`EventFactory` via `Helper.createRunnableEvent` etc. — q-misc-util.md:321-323;
  `PayloadTypeRegistry`; `FabricEntityTypeBuilder` — portal-core.md:26; `ServerPlayNetworking.createS2CPacket`
  — portal-core.md:397) need a common-side answer BEFORE the units that use them land. That is U0.
- Duck interfaces and compat invoker **bases** are ported maximally early — they compile against vanilla only
  and cut the most forward edges (ducks-api-misc.md §1; platform-compat-peripheral.md:245).
- Mixins land WITH their driving subsystem (see §2.10).
- Within the SCC (U1–U11) each unit lists its **forward refs** — classes from later units that javac needs.
  The build is expected red from U1 until the SCC closes at the U11 checkpoint; if the user wants earlier green
  builds, the only fidelity-preserving option is to hold un-closed units in an excluded source set until their
  closure lands (build engineering, not code change).

| # | Unit | Contents (summary) | Depends on | Forward refs (SCC debt) | Testable when landed? |
|---|---|---|---|---|---|
| **U0** | `loader-plumbing` | Multiloader substrate: event-object answer for `Helper.create*Event`, payload registration abstraction, entity-type registration callback plumbing (mirrors `IPModMain.registerEntityTypes` — world-loader-root.md §5), mixin-config skeletons, keep existing `PlatformHelper` (current-mod-core.md §1 survivor) | vanilla + loaders | none | YES — current mod still runs |
| **U1** | `qmiscutil-substrate` | `my_util/*` (all math), `MyTaskList`, `Signal*`, loggers, `Animated`+`Rendered*`, `GuiHelper`, `DimIntIdMap`, `MiscGlobals`, `Helper` | U0 | `Helper`→`McHelper` (1 call, Helper.java:509) — the ONLY edge out; everything else in U1 compiles standalone | YES for the pure-math subset — JUnit off-game (quaternion/mesh/plane invariants); the best regression net for the two past geometry sign errors |
| **U2** | `ducks-roots-platform-facade` | ALL duck interfaces (36 + q_misc_util ducks + cross-slice accessor interfaces), `IPGlobal`, `IPCGlobal`, `MiscHelper`, `mc_utils` (`ServerTaskList`, `MyNbtTextFormatter`), `IPConfig`+`O_O`, `IPFeatureControl` (peripheral, 1 file, cycle 9), compat invoker bases (`GravityChangerInterface`, `SodiumInterface`, `IrisInterface`, `IPPortingLibCompat`), `IPPerServerInfo`, `DimensionIntId`+`DimensionIdRecord`, `IPMixinPlugin` | U1 | `O_O`→{`Portal` U4, `ImmPtlClientChunkMap` U7, `ImmPtlNetworkConfig` U5, `PortalGenInfo` U9}; `IPConfig`→`BlockPortalShape` (U4 co-port); `IPPerServerInfo`→{`ServerTeleportationManager` U6, `CustomPortalGenManager` U9, `PortalWandInteraction` U11 co-port} | no (interfaces + config shells) |
| **U3** | `mc-helpers` | `McHelper`, `CHelper`, `IPMcHelper`, `ScaleUtils`, mc_util entity-traversal mixins (`IELevelEntityGetterAdapter` et al.) | U1, U2 | `Portal` (U4), `GlobalPortalStorage` (U5), `CrossPortalEntityRenderer` (U10) | no |
| **U4** | `portal-core-shape-animation` | `Portal` family (`Mirror`, `BreakableMirror`, `EndPortalEntity`), `PortalState`, `PortalExtension`, `PortalManipulation`, `PortalUtils`, `shape/*`, `util/*`, whole `animation/` package (incl. `StableClientTimer`, `ClientPortalAnimationManagement`), `PortalPlaceholderBlock`, `LoadingIndicatorEntity`, `PortalRenderInfo`, **co-port** `BlockPortalShape` (cycle 14) and `CollisionHelper`+`PortalCollisionHandler`+`PortalCollisionEntry` (cycle 15 — shapes call their statics; they need only U1–U4 material) | U1–U3 | `ImmPtlNetworking`+`PortalAPI` (U5), `ServerTeleportationManager` (U6), `PortalRenderer` (U10), `QueryManager`/`RenderStates`/`WorldRenderInfo`/`ViewAreaRenderer`/`FrustumCuller` (U9/U10), `PortalCommand.raytracePortals` (U11 co-port) | geometry/NBT round-trip unit tests once SCC closes |
| **U5** | `network-globalportals-api` | `MiscNetworking`, RPC (`ImplRemoteProcedureCall`+`McRemoteProcedureCall`), `PacketRedirection`+`PacketRedirectionClient`, `ImmPtlNetworking`, `ImmPtlNetworkConfig`, **co-port** `global_portals/*` (`GlobalPortalStorage` + global portal types — cycle 10) and `api/` (`PortalAPI`, `ImmPtlEntityExtension` — cycle 11) | U1–U4 | `ServerTeleportationManager` (U6), `ImmPtlChunkTracking`/`ChunkLoader` (U7) | no (wire format inert until senders/receivers run) |
| **U6** | `teleportation-collision` | `TeleportationUtil`, `CrossPortalSound`, `ClientTeleportationManager`, `ServerTeleportationManager` (`PortalCollisionHandler`/`CollisionHelper` already co-ported into U4 per cycle 15 — review them alongside this unit) | U1–U5 | `ImmPtlChunkTracking` (U7), `TransformationManager`+`MyGameRenderer.vanillaTerrainSetupOverride`+`RenderStates`/`FogRendererContext` (U9/U10, client paths) | no |
| **U7** | `chunk-loading-entity-sync` | `ImmPtlChunkTracking`, `ChunkVisibility`, `ImmPtlChunkTickets`, `PlayerChunkLoading`, `EntitySync`, `WorldInfoSender`, `DimensionalChunkPos`, `PerformanceLevel`, `ServerPerformanceMonitor`+`ClientPerformanceMonitor`, `ImmPtlClientChunkMap` (base: the repo's already-ported `SeamlessClientChunkMap` — current-mod-core.md §1) | U1–U6 | client half touches `ClientWorldLoader` per-dim renderers (U8, runtime-only) | no |
| **U8** | `worldloader-client-and-mixin-common` | `ClientWorldLoader` (last core-root file — needed U8-late because of cycle 7's render context refs, co-schedule with U9), `IPModMain`/`IPModMainClient` init sequences, ALL 74 common mixins (chunk/entity/position sync, collision, interaction taps) | U1–U7 | `DimensionRenderHelper`/`PortalRendering` (U9) | no (managers now wired, build still red) |
| **U9** | `render-context-worldswitch` | `context_management/*` (`RenderStates`, `WorldRenderInfo`, `PortalRendering`, `FogRendererContext`, `StaticFieldsSwappingManager`, `DimensionRenderHelper`, `CloudContext`), `MyGameRenderer`, `MyRenderHelper`, `FrontClipping`, `ShaderCodeTransformation`, `TransformationManager`, `GlQueryObject`/`QueryManager`, `ImmPtlViewArea`, `VisibleSectionDiscovery`, `FrustumCuller`, `ForceMainThreadRebuild`, `GLResourceCache`, `CrossPortalEntityRenderer`, `GuiPortalRendering`, `ViewAreaRenderer` — the 26.2 rewrite hotspot; transplant the target repo's proven stencil/extract-compile/reversed-Z mechanics (current-mod-render.md §1 items 1–7) | U1–U8 | `PortalRenderer` family (U10) for a few call sites | no |
| **U10** | `renderers-client-mixins` | `renderer/*` (abstract `PortalRenderer` + Iris-compat renderer shells (cycle 12) + `RendererUsingStencil` + `RendererUsingFrameBuffer` + debug/dummy), all ~62 client mixins (multiworld half + render half) | U1–U9 | `BlockManipulationClient` + `PortalCommand`/argument types (U11 — co-port into the checkpoint closure) | no — needs U11 closure files |
| **U11** | `commands-blockmanip-misc` + **SCC-closure co-ports** | `commands/*` (`PortalCommand`, `ClientDebugCommand`, argument types), `block_manipulation/*`, `miscellaneous/` (GcMonitor, initial screen), `debug/`, plus the two peripheral closure files `PortalWandInteraction`+`CommandStickItem` (cycle 9) and generation shells `PortalGenInfo`+`CustomPortalGenManager` head (cycle 8/14 leftovers) | U1–U10 | none — **⬅ FIRST GREEN BUILD.** | **FIRST RUNNABLE CLIENT**: create portals with `/portal make_portal`, see through (stencil), walk through (client-first teleport), cluster/bi-way via `completeBiWayBiFacedPortal`, cross-portal block interaction. Most of the 12-point regression checklist becomes executable here |
| **U12** | `portal-generation-full` | `NetherPortalGeneration` pipeline, `NetherPortalMatcher`, `FrameSearching`, `FastBlockAccess`, `CustomPortalGeneration` + forms/triggers (datapack registries), `BreakablePortalEntity`/`NetherPortalEntity`/`GeneralBreakablePortal`, `IntrinsicPortalGeneration` + ignition/trigger mixins | U11 (green) | none | YES — E2E: flint-and-steel a nether frame, existing-frame linking, frame-break kills portal, placeholder integrity |
| **U13** | `peripheral-compat-tail` | wand items + client wand code, dim stack + GUI, alternate dims (user decides — SKIP-candidate list in platform-compat-peripheral.md appendix), compat `On*Present` layers + gated mixins (Sodium/Iris — dead on 26.2 until those mods port), ModMenu config GUI | U11 | none | YES — per-feature |
| **U14** | `cutover` | Delete current-mod block-portal machinery per disposition tables (current-mod-core.md §1: `PortalInfo` scan, `SeamlessServerTeleport`/`SeamlessClientTeleport`, `PortalChunkTracker` ACK ledger, bespoke `ModPayloads` redirection; current-mod-render.md disposition: `StencilPortalRenderer`→IP renderers, `PortalContextSwitch`→`MyGameRenderer`+context_management, `CameraTransitionHandler` DELETE, etc.), keep survivors (stencil substrate mixins, `PortalRenderTypes`, diagnostics, `fireSpreadRadiusAroundPlayer` adaptation), run the full 12-point regression checklist from `ENTITY_PORTAL_MIGRATION_BRIEFING.md` | U12 | none | YES — full regression |

### 4.1 Runnable-testable milestones (summary)

1. **After U1**: off-game JUnit for `DQuaternion`/`Mesh2D`/`Plane`/`IntBox` (catches sign errors before any MC boot).
2. **After U11 (first green build)**: full client run; command-created portals exercise portal-core, network
   sync, teleportation, collision, chunk-loading, and the whole render stack — WITHOUT generation. This is the
   primary integration checkpoint; hold here until the regression checklist items that don't need nether
   portals pass.
3. **After U12**: nether-portal E2E (ignition → async dest search → frame fabrication → 4-portal cluster),
   breakable-portal lifecycle, global portals.
4. **After U14**: the old system is gone; final regression.

### 4.2 Runtime ordering constraints the port must preserve (independent of compile order)

- **Init sequences** exactly as IP: `MiscUtilModEntry` (`ImplRemoteProcedureCall.init` → `MiscNetworking.init`
  → `DimensionIntId.init`, q-misc-util.md:468-471) then `IPModMain.init` (networking :67-69 → global portals
  :83 → teleport manager :87 → collision :89 → commands :103-108 → `ServerTaskList.init` :112 →
  `CustomPortalGenManager.init` :114 → config :146; ducks-api-misc.md §5, world-loader-root.md §5) and
  `IPModMainClient` (teleport client :74 → renderers on render thread :76-85 → collision client :93 →
  networking client :125-126 → `DimensionIntId.initClient` :134).
- **Login order**: `DimIdSyncPacket` mid-`placeNewPlayer` BEFORE the difficulty packet; global-portal sync at
  `onPlayerLoggedIn` AFTER it (network.md §5). Dynamic-dim updates: int-id map updates on an early event phase
  BEFORE global-portal storage reacts (DimensionIntId.java:31-44).
- **Client tick order** (one injection point): `ClientWorldLoader.tick()` → `StableClientTimer` →
  `ClientPortalAnimationManagement.tick()` → `manageTeleportation(true)` → `POST_CLIENT_TICK_EVENT`
  (world-loader-root.md §5).
- **Frame order**: `RenderStates.updatePreRenderInfo(partialTick)` → `ClientPortalAnimationManagement.update()`
  → `manageTeleportation(false)` — MUST precede portal rendering each frame (teleportation-collision.md §5).
- **Server tick order**: `ImmPtlChunkTracking.tick` runs watch updates → `ImmPtlChunkTickets.tick` →
  `EntitySync.update`/`tick` (mixin-common.md §5); `ServerTeleportationManager` ticks on the same END_SERVER_TICK.
- **RPC FQN strings are wire protocol** — `qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos`
  and the others in ducks-api-misc.md §5 must keep their exact package+class names, which constrains any
  package remapping decision made at U0.

### 4.3 Compile-vs-runtime cheat sheet (asked-for distinctions)

- **Teleportation needs `Portal` + `McHelper` (+collision, +animation snapshots) to COMPILE, but not the
  renderer**: its render imports (`TransformationManager`, `RenderStates`, `MyGameRenderer`) sit on client-only
  paths; a dedicated server runs crossings with render classes never class-initialized.
- **`Portal` needs `PortalRenderer`/`PortalRenderInfo`/`IrisInterface` to COMPILE but not to FUNCTION
  server-side** (client-only call sites).
- **Chunk-loading needs `Portal` (10-method slice, chunk-loading.md:73) and network to COMPILE and FUNCTION;
  needs the render stack only at RUNTIME on the client light-update path** (chunk-loading.md:345).
- **Render needs everything below it to COMPILE; at RUNTIME additionally needs chunk-loading + network
  actively feeding secondary `ClientLevel`s, else portals show empty worlds** (not a crash — IP loads dest
  gradually by design, see memory `ip-dest-loading-model`).
- **Generation needs chunk-loading at RUNTIME for the async dest prep; does not need teleportation at all.**
- **Mixins**: compile-time leaves, runtime enablers — a subsystem "lands" only when its mixins land with it.
