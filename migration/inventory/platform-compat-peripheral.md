# IP Inventory — Platform-specific, Compat, Peripheral (triage slice)

Source of truth: `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall` (IP for MC 1.21/1.21.1 per `fabric.mod.json` `"minecraft": ["1.21", "1.21.1"]`, Mojang mappings).
All paths below are relative to `.../qouteall/` unless absolute. All line numbers verified by reading the files.

**Scope**: `imm_ptl/core/platform_specific/` (13 files, FULL READ), `imm_ptl/core/compat/` (36 files, TRIAGE), `imm_ptl/peripheral/**` (TRIAGE, **excluding `peripheral/portal_generation/`** which is owned by another inventory agent — its 4 files are listed only as cross-references).

**Classification vocabulary** (classification only — the user decides scope):
- **CORE-REQUIRED**: other IP subsystems call into it even when no third-party mod is present; the entity-portal migration cannot boot without an equivalent.
- **OPTIONAL-FEATURE**: self-contained user-facing feature; portals function without it.
- **THIRD-PARTY-COMPAT**: only active when a specific third-party mod is loaded; port only if that mod exists on 26.2.
- **DEAD**: commented-out or unreferenced in IP itself.

---

## 1. Overview

This slice is IP's outer shell: (a) the **Fabric platform layer** (`platform_specific`) — the three Fabric entrypoints that boot the whole mod, the AutoConfig/Cloth-Config config class that fans values into `IPGlobal`, the `O_O` loader-facade that core code calls for environment/mod-presence/version questions, and four Fabric-specific mixins; (b) the **compat layer** (`compat`) — invoker-pattern abstraction classes (`GravityChangerInterface`, `SodiumInterface`, `IrisInterface`, `IPPortingLibCompat`) whose *default no-op implementations are called unconditionally from core teleportation/collision/render code*, plus the mixins and alternate portal renderers that activate when Sodium/Iris/Flywheel/Cardinal-Components are installed; (c) the **peripheral module** (`peripheral`) — user-facing features layered on top of the portal core: the portal wand (create/drag/copy portals), command stick items, the dimension-stack system (global `VerticalConnectingPortal`s linking whole dimensions vertically, with GUI in the world-creation screen), and the alternate dimensions (skyland/chaos/void) with their custom chunk generators.

Architecturally, IP is Fabric-only at this version (no Architectury): `fabric.mod.json` wires `main`/`client`/`server`/`modmenu` entrypoints directly to these classes, and `platform_specific` is the *only* place where `BuiltInRegistries` registration happens — core exposes `registerEntityTypes/registerBlocks(BiConsumer)` callbacks (`imm_ptl/core/IPModMain.java:155-213`) and the entrypoints supply the Fabric registry lambda. For the multiloader port, everything in `platform_specific` maps onto the target mod's existing common/fabric/neoforge split; every Fabric-API touchpoint is flagged in §5.

---

## 2. Class-by-class inventory

### 2A. `imm_ptl/core/platform_specific/` — FULL READ (13 files, ~900 LOC)

#### `IPModEntry.java` (42 LOC) — common entrypoint — **CORE-REQUIRED**
- Implements Fabric `ModInitializer`. `onInitialize()` (`IPModEntry.java:14`):
  1. `IPModMain.init()` (all common core init) then `RequiemCompat.init()` (`:15-16`).
  2. Registers **all 10 portal entity types** via `IPModMain.registerEntityTypes((id, entityType) -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, entityType))` (`:18-20`). The actual list lives in core: `Portal`, `NetherPortalEntity`, `EndPortalEntity`, `Mirror`, `BreakableMirror`, `GlobalTrackedPortal`, `WorldWrappingPortal` (id `border_portal`), `VerticalConnectingPortal` (id `end_floor_portal`), `GeneralBreakablePortal`, `LoadingIndicatorEntity` (`imm_ptl/core/IPModMain.java:162-213`).
  3. Registers `PortalPlaceholderBlock` (id `immersive_portals:nether_portal_block`) via `BuiltInRegistries.BLOCK` (`IPModEntry.java:22`, list at `IPModMain.java:155-160`).
  4. Mod-presence probes: `FabricLoader.isModLoaded("dimthread")` → sets `O_O.isDimensionalThreadingPresent` (`:24-27`); `isModLoaded("gravity_changer_q")` → swaps `GravityChangerInterface.invoker = new OnGravityChangerPresent()` (`:32-35`).
- Deps: core `IPModMain`, compat `GravityChangerInterface`, `RequiemCompat`. Common side.

#### `IPModEntryClient.java` (113 LOC) — client entrypoint — **CORE-REQUIRED**
- Implements `ClientModInitializer`. `onInitializeClient()` (`IPModEntryClient.java:66`): `IPModMainClient.init()`; `initPortalRenderers()`; Sodium probe → `SodiumInterface.invoker = new OnSodiumPresent()` (`:71-77`); Iris probe → `IrisInterface.invoker = new OnIrisPresent()` + `ExperimentalIrisPortalRenderer.init()` + a delayed chat warning task via `IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.oneShotTask(...))` gated by `IPConfig.getConfig().shouldDisplayWarning("iris")` (`:92-105`); `IPModInfoChecking.initClient()` (`:110`).
- `initPortalRenderers()` (`:39-63`): registers **the same `PortalEntityRenderer` for all 9 portal entity types** plus `LoadingIndicatorRenderer` for the loading indicator, via Fabric `EntityRendererRegistry.register(entityType, (EntityRendererProvider) PortalEntityRenderer::new)` (`:52-55`). This is the client half of the entity-portal migration's renderer wiring.
- Client only.

#### `IPModEntryDedicatedServer.java` (14 LOC) — server entrypoint — OPTIONAL-FEATURE
- Implements `DedicatedServerModInitializer`; sole call is `IPModInfoChecking.initDedicatedServer()` (`IPModEntryDedicatedServer.java:10`).

#### `IPConfig.java` (215 LOC) — the config class — **CORE-REQUIRED**
- Cloth-Config AutoConfig data class: `@Config(name = "immersive_portals")` (`IPConfig.java:16`), registered in core via `IPGlobal.configHolder = AutoConfig.register(IPConfig.class, GsonConfigSerializer::new)` (`imm_ptl/core/IPModMain.java:146`) with a save listener that re-runs `onConfigChanged()` (`IPModMain.java:147-150`).
- ~45 config fields (client render options `maxPortalLayer`, `compatibilityRenderMode`…; common options `netherPortalMode`, `endPortalMode`, `indirectLoadingRadiusCap` (1..32, the dest-loading cap the current mod already mirrors), `scaleLimit`, `portalWandUsableOnSurvivalMode`…; invisible options incl. `dimStackPreset` as a raw `JsonObject` (`:144-146`)).
- **`onConfigChanged()` (`:157-209`) is the single fan-out that copies every field into `IPGlobal`/`IPCGlobal` statics + `BlockPortalShape.defaultLengthLimit`** — core never reads `IPConfig` directly in hot paths.
- Key API: `static IPConfig getConfig()` (`:148`), `saveConfigFile()` (`:152`), `shouldDisplayWarning(String key)` (`:211`, consults `enableWarning` + `disabledWarnings` set).
- **Cross-module surprise**: defaults call `IPFeatureControl.enableVanillaBehaviorChangingByDefault()` from the *peripheral* module (`IPConfig.java:11,89-98`) — core→peripheral dependency (jar-in-jar detection changes defaults to vanilla-behavior-preserving).

#### `IPConfigGUI.java` (13 LOC) / `IPModMenuConfigEntry.java` (15 LOC) — OPTIONAL-FEATURE
- `IPConfigGUI.createClothConfigScreen(Screen parent)` returns `AutoConfig.getConfigScreen(IPConfig.class, parent).get()` (`IPConfigGUI.java:10-12`). Client only.
- `IPModMenuConfigEntry` implements ModMenu's `ModMenuApi.getModConfigScreenFactory()` (`IPModMenuConfigEntry.java:10-13`), wired via the `modmenu` entrypoint in `fabric.mod.json`. Also invoked from `ClientDebugCommand` (`imm_ptl/core/commands/ClientDebugCommand.java:409`).

#### `O_O.java` (254 LOC) — loader facade — **CORE-REQUIRED**
Static utility facade used by **21 files** across core/peripheral. Everything here must exist per-loader in the port. Full method inventory:
- `isForge()` → constant `false` (`O_O.java:39-41`); `isQuilt()` checks `quilted_fabric_api` mod (`:237-239`); `isDedicatedServer()` via `FabricLoader.getEnvironmentType() == EnvType.SERVER` (`:79-81`); `isDevEnv()` (`:251-253`); `getGameDir()` (`:57-59`).
- **Teleportation compat hooks**: `onPlayerChangeDimensionClient(from,to)` → `RequiemCompat.onPlayerTeleportedClient()` (`:43-48`), called from `ClientTeleportationManager` (`imm_ptl/core/teleportation/ClientTeleportationManager.java:526`); `onPlayerTravelOnServer(player, fromWorld, toWorld)` → `RequiemCompat.onPlayerTeleportedServer` (`:50-55`), called from `ServerTeleportationManager.java:477`.
- `isObsidian(BlockState)` — identity compare against `Blocks.OBSIDIAN.defaultBlockState()` (`:61-65`); used by intrinsic nether portal generation.
- **Fabric chunk events**: `postClientChunkLoadEvent/postClientChunkUnloadEvent(LevelChunk)` fire `ClientChunkEvents.CHUNK_LOAD/CHUNK_UNLOAD` (`:67-77`) — called for chunks in *secondary* client dimensions so other mods' chunk listeners see them.
- **`createMyClientChunkManager(ClientLevel, loadDistance)` → `new ImmPtlClientChunkMap(...)` (`:87-90`)** — the factory that swaps the client chunk cache class for portal dimensions.
- Mod checks/versions: `getIsPehkuiPresent()` (`:92-94`); `isModLoadedWithinVersion(modId, start, end)` using Fabric `Version.parse` (`:110-140`); `getImmPtlVersion()` returning a network-config `ModVersion` — **casts to Fabric-internal `SemanticVersionImpl`** (`:142-164`); `getImmPtlVersionStr()` (`:166-170`); `shouldUpdateImmPtl(latest)` (`:172-191`); `getModIconLocation(modid)` parsing Fabric icon paths (`:201-227`); `getModName` (`:229-234`); `getLoadedModIds()` (`:241-244`).
- URLs: `getImmPtlModInfoUrl()` versioned by `SharedConstants.getCurrentVersion().getName()` (`:96-108`), `getModDownloadLink()`, `getIssueLink()` (`:193-199`).
- `postPortalSpawnEventForge(PortalGenInfo)` — empty on Fabric (`:83-85`); `allowTeleportingEntity(Entity, Portal)` → always `true` on Fabric, comments name `ForgeHooks.onTravelToDimension()` as the Forge equivalent (`:246-249`) — **the NeoForge port must wire the real hook here**.
- State: `public static boolean isDimensionalThreadingPresent` (`:37`).

#### `RequiemCompat.java` (102 LOC) — THIRD-PARTY-COMPAT (Requiem mod)
- Pure-reflection integration (`Class.forName("ladysnake.requiem.api.v1.RequiemPlayer")` etc., `RequiemCompat.java:38-48`); active only if `isModLoaded("requiem")` (`:32`).
- On player crossing: moves the possessed `Mob` to the player's dimension — client via `ClientTeleportationManager.moveClientEntityAcrossDimension` (`:73-77`), server via `ServerTeleportationManager.of(player.server).changeEntityDimension(possessedEntity, dim, McHelper.getEyePos(player), false)` (`:91-96`).
- SKIP-candidate flag: Requiem's existence on 26.2 is unknown; the *hook points* in the teleportation managers carry over regardless (they route through `O_O`).

#### `IEClientWorld_MA.java` (5 LOC) — **DEAD**
- Empty interface, zero references anywhere in the codebase (verified by grep). Do not port.

#### `mixin/client/MixinFabricClientPlayNetworkAddon.java` (9 LOC) — **DEAD (empty)**
- `@Mixin(net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon.class)` with an **empty body** (`MixinFabricClientPlayNetworkAddon.java:6-9`). Registered in `imm_ptl_fabric.mixins.json` but does nothing. Historical leftover.

#### `mixin/client/MixinFabricInvalidateRenderStateCallback.java` (24 LOC) — Fabric-API compat — CORE-REQUIRED (Fabric side)
- `@Pseudo @Mixin(InvalidateRenderStateCallback.class, remap=false)`, injects into the event lambda `lambda$static$0` and **cancels the Fabric "invalidate render state" event while `ClientWorldLoader.getIsCreatingClientWorld()`** (`MixinFabricInvalidateRenderStateCallback.java:14-23`) — prevents other mods' reload callbacks from firing mid-secondary-world-construction. The target mod's port needs the equivalent guard for whatever event fires on renderer reload.

#### `mixin/common/MixinPlayerManager_MA.java` (33 LOC) — **CORE-REQUIRED**
- `@Mixin(PlayerList.class)`: at `respawn` HEAD and `remove` HEAD calls `ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player)` (`MixinPlayerManager_MA.java:15-32`) — detaches the player from IP's cross-dimension chunk/entity trackers on respawn/disconnect. (`_MA` = "mod adapter"; in this Fabric-only version these live under platform_specific but are loader-agnostic vanilla mixins.)

#### `mixin/common/MixinServerPlayerEntity_MA.java` (59 LOC) — **CORE-REQUIRED**
- `@Mixin(ServerPlayer.class)`: hooks `changeDimension(DimensionTransition)` HEAD (`MixinServerPlayerEntity_MA.java:19-25`) and `teleportTo(ServerLevel,DDDFF)` HEAD when the target world differs (`:28-43`). Both call `onBeforeDimensionTravel` (`:45-58`): if a `CustomPortalGenManager` exists (`IPPerServerInfo.of(player.server)`), calls its `onBeforeConventionalDimensionChange(player)`, removes the player from ImmPtl chunk tracking, and schedules `onAfterConventionalDimensionChange(player)` on `ServerTaskList` — this is how datapack custom portals survive *vanilla* dimension changes. Note `DimensionTransition` is a 1.21 API (renamed/reshaped in later versions; 26.2 mapping needed).

### 2B. `imm_ptl/core/compat/` — TRIAGE (36 files, ~3000 LOC)

#### Top level

| Class | LOC | What it integrates | Classification |
|---|---|---|---|
| `IPCompatMixinPlugin` (`compat/IPCompatMixinPlugin.java`) | 75 | Mixin config plugin for `imm_ptl_compat.mixins.json`; `shouldApplyMixin` gates by classname substring → mod presence: `IrisSodium*` needs both, `Iris*`→iris, `Sodium*`→sodium, `Flywheel*`→flywheel, `CardinalComp*`→`cardinal-components-base`; **anything else returns `false`** (`:23-53`) | CORE-REQUIRED (the gating mechanism itself) |
| `GravityChangerInterface` (`compat/GravityChangerInterface.java`) | 169 | Gravity Changer (`gravity_changer_q`). **Invoker pattern**: `public static Invoker invoker` (`:20`) with a no-op base class returning vanilla values — `getEyeOffset` = `(0, eyeHeight, 0)` (`:27-29`), `getGravityDirection` = `DOWN` (`:31`), `getWorldVelocity` = `getDeltaMovement` (`:52-58`), `transformPlayerToWorld/WorldToPlayer` = identity (`:60-74`), `getExtraCameraRotation` = null (`:47-50`). `OnGravityChangerPresent` (`:89-168`) delegates to `GravityChangerAPI` + `RotationUtil`, returns `DQuaternion` camera rotation for non-DOWN gravity (`:131-137`). | **CORE-REQUIRED (base Invoker)** — called from `PortalCollisionHandler`, `McHelper`, `TransformationManager`, `ClientTeleportationManager`, `ServerTeleportationManager` (grep-verified). `OnGravityChangerPresent` subclass is THIRD-PARTY-COMPAT. |
| `IPPortingLibCompat` (`compat/IPPortingLibCompat.java`) | 68 | Porting Lib (Forge-ports library that adds its own `port_lib$stencilEnabled` field to `RenderTarget`). `getIsStencilEnabled`/`setIsStencilEnabled(RenderTarget, bool)` route to porting_lib's field via reflection if present (`:34-43`, `:46-66`), **else to IP's own `IEFrameBuffer` duck** (`ip_getIsStencilBufferEnabled` / `ip_setIsStencilBufferEnabledAndReload`). | **CORE-REQUIRED (as the stencil-enable abstraction)** — called by core `RendererUsingStencil`, `RendererUsingFrameBuffer`, all three Iris renderers, and `IPModMain.init()` (`IPModMain.java:99`). The porting_lib branch is THIRD-PARTY-COMPAT; the duck branch is the real mechanism (the 26.2 mod already has an equivalent duck). |
| `IPModInfoChecking` (`compat/IPModInfoChecking.java`) | 400 | Online mod-info service: fetches `https://qouteall.fun/immptl_info/<mcversion>.json` with `java.net.http.HttpClient` **off-thread** (`:97-142`, note `:95` "do not run it on render thread"; client fetch wrapped in `Util.backgroundExecutor().execute` `:146`), parses `ImmPtlInfo` (update version, severely-incompatible mod list, incompatible shaderpack list), then queues chat warnings via `IPGlobal.CLIENT_TASK_LIST` delayed until `Minecraft.getInstance().level != null` (`:169-226`). Dedicated-server variant on `ServerLifecycleEvents.SERVER_STARTED` messages the first logged-in player (`:283-369`). `checkShaderpack()` (`:376-399`) compares `IrisInterface.invoker.getShaderpackName()` against the fetched incompatible list; called from core `PortalRenderer`. Also a >20-mods generic warning (`:229-244`). | OPTIONAL-FEATURE (telemetry-ish; gated by `checkModInfoFromInternet` config) |
| `IPFlywheelCompat` (`compat/IPFlywheelCompat.java`) | 20 | Flywheel presence log only. **`isFlywheelPresent` is declared `false` and never set to `true`** (`:11-18`) — vestigial. | DEAD-ish (see flywheel mixins below) |

#### `compat/sodium_compatibility/` + `compat/mixin/sodium/` — Sodium 0.6.0 integration (fabric.mod.json `breaks` pins `sodium: [<0.6.0, >0.6.0]` — exactly 0.6.0)

All client-side. **THIRD-PARTY-COMPAT** — port only if Sodium exists for 26.2 (and then against its current internals; every target class below is a Sodium-internal that will have changed).

| Class | LOC | Responsibility |
|---|---|---|
| `SodiumInterface` (`sodium_compatibility/SodiumInterface.java`) | 96 | Invoker pattern again (`invoker`, `:50`). Key ops when present: `createNewContext(renderDistance)` → `SodiumRenderingContext` (`:59-61`); **`switchContextWithCurrentWorldRenderer(context)`** — grabs `SodiumWorldRenderer` via Sodium's `LevelRendererExtension.sodium$getWorldRenderer()`, calls `scheduleTerrainUpdate()`, then swaps the render-section context through the `IESodiumRenderSectionManager` duck (`:64-76`); `markSpriteActive(TextureAtlasSprite)` via `SpriteUtil` (`:79-81`); `onClientChunkLoaded/Unloaded` → `ChunkTrackerHolder.get(world).onChunkStatusAdded/Removed(x, z, ChunkStatus.FLAG_HAS_BLOCK_DATA)` (`:84-93`). Also holds `public static @Nullable FrustumCuller frustumCuller` (`:22`) used by the Viewport mixin. **The no-op base Invoker is CORE-REQUIRED** (called from ~16 core render files incl. `ClientWorldLoader`-adjacent code, `MyGameRenderer`, `FrustumCuller`). |
| `SodiumRenderingContext` | 14 | Per-dimension saved Sodium state: `SortedRenderLists renderLists` + `int renderDistance` (`:5-13`). |
| `IESodiumRenderSectionManager` (duck) | 5 | `ip_swapContext(SodiumRenderingContext)`. |
| `mixin/sodium/IESodiumWorldRenderer` | 12 | `@Accessor("renderSectionManager")` on `SodiumWorldRenderer`. |
| `MixinSodiumRenderSectionManager` | 54 | Implements the duck: **swaps `renderLists` + `renderDistance` fields with the context** (`:30-42`); forces `isSectionVisible()` to `true` whenever `RenderStates.portalsRenderedThisFrame != 0` (visibility data poisoned by portal passes; used for entity culling) (`:48-53`). |
| `MixinSodiumRenderRegion` | 60 | `@Overwrite getRenderList()`: during portal rendering returns a **separate `ChunkRenderList` per portal layer** (lazily built `ObjectArrayList`) so the recursion's frame-counter reset doesn't clobber the outer world's translucent pass (`:37-59`, reason documented in the `@Overwrite` javadoc). |
| `MixinSodiumOcclusionCuller` | 127 | The Sodium equivalent of IP's modified visible-section iteration: at `findVisible` HEAD, if portal rendering, asks `portal.getPortalShape().getModifiedVisibleSectionIterationOrigin(portal, cameraPos)` and overrides the BFS start point via two `@Redirect`s of `Viewport.getChunkCoord()` (`:39-107`); disables occlusion culling then (`PortalRendering.shouldEnableSodiumCaveCulling()` `:49`); tolerates initial frustum-test failures until the first in-frustum section (`:112-127`). |
| `MixinSodiumViewport` | 39 | `@Redirect` of `Frustum.testAab` inside `isBoxVisible`: additionally rejects boxes that `SodiumInterface.frustumCuller.canDetermineInvisibleWithCameraCoord(...)` proves invisible (portal-view culling) (`:12-31`). |
| `MixinSodiumWorldRenderer` | 27 | At `setupTerrain` HEAD creates/updates `SodiumInterface.frustumCuller` with the camera pos (`:16-27`). |
| `MixinSodiumDefaultShaderInterface` | 52 | Binds optional uniform **`iportal_ClippingEquation`** at shader init, sets it in `setupState` from `FrontClipping.getActiveClipPlaneEquationAfterModelView()` (or `0,0,0,1` when clipping off) (`:23-53`). |
| `MixinSodiumShaderLoader` | 38 | Wraps `ShaderLoader.getShaderSource` to run IP's `ShaderCodeTransformation.transform` over Sodium's vertex/fragment GLSL (injects the clipping uniform) (`:17-38`). |
| `MixinSodiumFlawlessFrames` | 18 | Forces Sodium's `FlawlessFrames.isActive()` true while `ForceMainThreadRebuild.isCurrentFrameForceMainThreadRebuild()` (synchronous chunk rebuild on demand) (`:13-18`). |

#### `compat/iris_compatibility/` + `compat/mixin/iris/` — Iris 1.8.0 integration (fabric.mod.json pins iris exactly 1.8.0)

All client-side. **THIRD-PARTY-COMPAT** — port only if Iris (or successor) exists on 26.2; also note MC 26.2's own renderer rewrite makes these renderers' raw-GL/FBO assumptions doubly stale.

| Class | LOC | Responsibility |
|---|---|---|
| `IrisInterface` (`iris_compatibility/IrisInterface.java`) | 96 | Invoker pattern (`invoker`, `:95`). Present-impl: `isShaders()` → `Iris.getCurrentPack().isPresent()` (`:57-59`); `isRenderingShadowMap()` → `ShadowRenderer.ACTIVE` (`:62-64`); **get/setPipeline(LevelRenderer, Object)** via reflection on the *Iris-injected* field `LevelRenderer.pipeline` (`:45-49`, `:67-81`) — this is how IP gives each dimension's `LevelRenderer` its own Iris pipeline; `reloadPipelines()` → `Iris.getPipelineManager().destroyPipeline()` (`:84-86`); `getShaderpackName()` (`:90-92`). **No-op base is CORE-REQUIRED** (checked in ~16 core render files, e.g. `MyGameRenderer`, `RenderStates`). |
| `IrisPortalRenderer` | 328 | The full shader-mode portal renderer (`PortalRenderer` subclass, singleton `instance` `:44`). Maintains `SecondaryFrameBuffer[] deferredFbs`, one per portal layer +1 (`:71-80`); each prepared with stencil enabled via `IPPortingLibCompat.setIsStencilEnabled` (`:86`). Per frame at `onBeforeHandRendering`: blits MC depth into the layer's deferred FB (`GL30.glBlitFramebuffer`, `:127-133`), **on GL error self-downgrades to `IPGlobal.RenderMode.compatibility` with a chat notice** (`:135-141`); draws portal view areas increasing stencil (`:276-304` via `ViewAreaRenderer.renderPortalArea` + `PortalRenderInfo.renderAndDecideVisibility`), recurses via `PortalRendering.pushPortalLayer` + `renderPortalContent` (`:242-247`), composites inner layers back with stencil-equal test (`:259-273`), final blit to screen (`:208-225`). NVIDIA-vs-AMD depth-format quirk handled at `prepareRendering` (`IPCGlobal.useSeparatedStencilFormat = !IPMcHelper.isNvidiaVideocard()`, `:69`). `invokeWorldRendering` → `MyGameRenderer.renderWorldNew(worldRenderInfo, Runnable::run)` (`:306-314`). |
| `IrisCompatibilityPortalRenderer` | 210 | Simpler single-`SecondaryFrameBuffer` fallback renderer (no portal-in-portal); `instance` + `debugModeInstance` (`:27-29`); disables stencil use on the main target (`:72-74`). |
| `ExperimentalIrisPortalRenderer` | 365 | Newer approach using **the vanilla framebuffer's own depth+stencil** (comment `:36-37`). Hooks Iris translucent phase: `onBeginIrisTranslucentRendering` ends the buffer-source batch, runs portal rendering, then re-enables Iris world rendering via duck `IEIrisNewWorldRenderingPipeline.ip_setIsRenderingWorld(true)` (`:58-68`). `restoreDepthOfPortalViewArea` re-renders the portal shape with `GL_ALWAYS` depth func (`:96-118`). |
| `IPIrisHelper` | 101 | Raw-GL helpers: `copyDepthStencil` via `glBlitFramebuffer` with depth/stencil masks (`:16-51`); `newCopyDepthStencil`/`copyColor` via `GL43C.glCopyImageSubData` on the targets' depth/color texture ids (`:57-99`). |
| `IEIrisNewWorldRenderingPipeline` (duck, 7 LOC) | | `ip_setIsRenderingWorld(boolean)`. |
| `IEIrisShadowRenderTargets` (duck, 5 LOC) | | Empty (its methods commented out). **DEAD**. |
| `ShadowMapSwapper` | 117 | **Entirely commented out** ("only compilable with the next version of Iris", `:4`). **DEAD**. |
| `mixin/iris/MixinIrisClearPass` | 25 | Cancels Iris `ClearPass.execute` while portal-rendering under `ExperimentalIrisPortalRenderer` (`:16-24`). |
| `MixinIrisFinalPassRenderer` | 23 | Disables `GL_STENCIL_TEST` at `renderFinalPass` HEAD when `IPCGlobal.debugEnableStencilWithIris` (`:16-22`). |
| `MixinIrisIris` | 28 | **All injections commented out** — empty shell. DEAD. |
| `MixinIrisRenderingPipeline` | 54 | Cancels `finalizeLevelRendering` during portal rendering (`:22-30`); injects after `CompositeRenderer.renderAll()` in `beginTranslucents` to call `ExperimentalIrisPortalRenderer.onAfterIrisDeferredCompositeRendering()` (`:32-44`); implements the `ip_setIsRenderingWorld` duck against the `@Shadow isRenderingWorld` field (`:17,46-49`). |
| `MixinIrisShadowRenderTargets` | 32 | All commented out. DEAD. |
| `MixinIrisSodiumShader` | 104 | Same `iportal_ClippingEquation` uniform pattern for Iris's Sodium shader programs (`bindUniformOptional` at ctor, set from `FrontClipping` in `setupState`, `:27-60`); requires both mods (plugin gate). |
| `MixinIrisTransformPatcher` | 85 | Runs `ShaderCodeTransformation.transform(CompiledShader.Type.VERTEX, "iris_"+name, code)` over Iris's patched shader-pack vertex shaders at `transformInternal` RETURN (`:24-40`). |

#### `compat/mixin/flywheel/` (3 files, ~70 LOC) — **DEAD even upstream**
All three are `@Pseudo` mixins into **1.18-era Flywheel package names** (`com.jozufozu.flywheel.core.crumbling.CrumblingRenderer`, `...core.compile.ProgramCompiler`, `...core.QuadConverter`); modern Flywheel (1.20+) moved to `dev.engine_room.flywheel`, so on 1.21 these never match a real class. Each cancels a renderer-reload/invalidation callback while `ClientWorldLoader.getIsCreatingClientWorld()` (e.g. `MixinFlywheelCrumblingRenderer.java:16-24`) — same guard idea as `MixinFabricInvalidateRenderStateCallback`. Honest SKIP-candidates; the *pattern* (suppress third-party reload hooks during secondary-world creation) is the thing to remember.

#### `compat/mixin/cardinal_comp/MixinCardinalCompComponentKey.java` (55 LOC) — THIRD-PARTY-COMPAT, likely stale
- `@Pseudo @Mixin(targets = "dev.onyxstudios.cca.api.v3.component.ComponentKey")` — **old CCA package** (current Cardinal Components uses `org.ladysnake.cca`), so probably never applies on 1.21 either. `@WrapOperation` around `ComponentProvider.toComponentPacket(...)` inside `syncWith`: if the provider is an `Entity` or `BlockEntity`, wraps the sync packet with `PacketRedirection.createRedirectedMessage(server, dimension, packet)` (`:22-55`) so CCA component syncs reach clients watching that entity **through a portal** (dimension-tagged redirection). The mixin plugin gates it on `cardinal-components-base` (`IPCompatMixinPlugin.java:48-51`).

### 2C. `imm_ptl/peripheral/**` — TRIAGE (~60 files owned here, ~9700 LOC incl. excluded portal_generation)

#### Package classification summary

| Package | Classification | One-line |
|---|---|---|
| `peripheral/` root (5 files) | Mixed (see below) | Module entry + creative-tab items |
| `peripheral/platform_specific/` (3) | CORE-REQUIRED (entry) | Fabric entrypoints + jar-in-jar feature gate |
| `peripheral/dim_stack/` (11) | OPTIONAL-FEATURE (large) | Dimension stack: global vertical portals between whole dimensions + world-creation GUI |
| `peripheral/alternate_dimension/` (9) | OPTIONAL-FEATURE | Skyland/chaos/void dimensions with custom chunk generators |
| `peripheral/wand/` (9) | OPTIONAL-FEATURE (flagship tool) | Portal wand: client-side visual create/drag/copy, server-side validated application |
| `peripheral/portal_generation/` (4) | **EXCLUDED — owned by another agent** | Intrinsic nether-portal generation forms |
| `peripheral/mixin/**` (18) | Follows owning feature | CVB (“change vanilla behavior”) mixins |
| `peripheral/ducks/` (1) | dim_stack support | `IECreateWorldScreen` |

#### `peripheral/` root

- **`PeripheralModMain.java` (137 LOC) — the peripheral hub.** Static fields: `portalHelperBlock` (`FabricBlockSettings.of().noOcclusion()...`, `PeripheralModMain.java:34-35`), `portalHelperBlockItem` (`:37-38`), creative tab `TAB` built with **Fabric `FabricItemGroup.builder()`** displaying wand + command sticks + helper block (`:40-51`). `init()` (`:62-81`): `FormulaGenerator.init()`, `IntrinsicPortalGeneration.init()` *(excluded package)*, `DimStackManagement.init()`, `AlternateDimensions.init()`, `DimensionAPI.suppressExperimentalWarningForNamespace("immersive_portals")` (dimlib), `PortalWandItem.init()`, `CommandStickItem.init()`, `PortalWandInteraction.init()`, `CommandStickItem.registerCommandStickTypes()`. `initClient()` (`:53-60`): `IPOuterClientMisc.initClient()`, `PortalWandItem.initClient()`, `ClientPortalWandPortalDrag.init()`. Registration fan-outs `registerItems/registerBlocks/registerChunkGenerators/registerBiomeSources/registerCreativeTabs(BiConsumer)` (`:83-136`) — chunk generator codecs `immersive_portals:error_terrain_generator` + `normal_skyland_generator`, biome source `chaos_biome_source`.
- `PortalHelperItem.java` (51 LOC) — `BlockItem` for the (deprecated) portal-helper frame block; on use prints a deprecation notice pointing to `/portal shape sculpt` (`PortalHelperItem.java:23-40`). OPTIONAL-FEATURE.
- **`CommandStickItem.java` (370 LOC)** — item that runs a stored command as the player **with permission level 2** (`player.createCommandSourceStack().withPermission(2)` → `Commands.performPrefixedCommand`, `:128-141`); gated by `IPGlobal.easeCommandStickPermission || player.hasPermissions(2) || isCreative` (`:148-155`). Stores `Data(command, nameTranslationKey, descriptionTranslationKeys)` in a **registered `DataComponentType` `iportal:command_stick_data`** with codec (`:41-53`, registered `:199-203`). `init()` also connects `PortalCommand.createCommandStickCommandSignal` to hand out sticks (`:205-215`). ~40 built-in stick presets (`:226-354`). OPTIONAL-FEATURE (but the `/portal` command subsystem references it).
- `IPOuterClientMisc.java` (123 LOC) — client-side one-shot info messages persisted in **`<gameDir>/imm_ptl_state.json`** (own gson file, `:65-67`); upgrades legacy dim-stack preset into `IPConfig.dimStackPreset` (`:83-97`); registers `Portal.CLIENT_PORTAL_SPAWN_EVENT` listener to show the wiki link once to creative players (`:99-113`). OPTIONAL-FEATURE.
- `IPPeripheralMixinPlugin.java` (51 LOC) — mixin plugin for `imm_ptl_peripheral.mixins.json`; `shouldApplyMixin` returns `true` unconditionally (the CVB gating is commented out, `:22-30`).

#### `peripheral/platform_specific/`

- `PeripheralModEntry.java` (31 LOC) — Fabric `ModInitializer`: pipes all five `PeripheralModMain.register*` fan-outs into `Registry.register(BuiltInRegistries.BLOCK/ITEM/CHUNK_GENERATOR/BIOME_SOURCE/CREATIVE_MODE_TAB, ...)` then `PeripheralModMain.init()` (`:12-30`).
- `PeripheralModEntryClient.java` (22 LOC) — `ClientModInitializer`: `BlockRenderLayerMap.INSTANCE.putBlock(portalHelperBlock, RenderType.cutout())` (Fabric API, `:9-14`) then `PeripheralModMain.initClient()`.
- `IPFeatureControl.java` (22 LOC) — `isProvidedByJarInJar()` via `FabricLoader.getModContainer("iportal").getContainingMod().isPresent()` (`:11-17`); `enableVanillaBehaviorChangingByDefault()` = `!jarInJar` (`:19-21`). Consumed by core `IPConfig` defaults and `IPModMain.init()` logging (`IPModMain.java:121-127`). CORE-REQUIRED-adjacent (config defaults).

#### `peripheral/dim_stack/` — OPTIONAL-FEATURE (11 files, ~2050 LOC)

Server logic:
- **`DimStackManagement.java` (333 LOC)** — statics `dimStackToApply` (staged config from GUI/preset) and `bedrockReplacementMap` (`:40-41`). `init()` registers on dimlib's `DimensionAPI.SERVER_DIMENSIONS_LOAD_EVENT` to fire the pre-update event when a stack is staged (`:48-53`). Server lifecycle (called from `MixinMinecraftServer_DimStack_CVB`): `onServerEarlyInit` builds the bedrock-replacement map before overworld spawn chunks generate (`:63-79`); `onServerCreatedWorlds` applies the staged stack or reloads replacements from each world's `GlobalPortalStorage` (`:81-93`). **`replaceBedrock(world, chunk)` rewrites every BEDROCK block in a generating chunk to the configured replacement** (full-chunk xyz scan, `:130-160`). `/portal dimension_stack` flow: server collects candidate dims (`server.levelKeys()` + `DIMENSION_STACK_CANDIDATE_COLLECTION_EVENT`, `:162-176`) → `McRemoteProcedureCall.tellClientToInvoke(...clientOpenScreen)` (`:184-189`) → client GUI → `tellServerToInvoke(...serverSetupDimStack/serverRemoveDimStack)` with permission-2 checks (`:219-268`). Presets stored as JSON in `IPConfig.dimStackPreset` (`:307-332`).
- **`DimStackInfo.java` (284 LOC)** — gson-serialized stack config (`loop`, `gravityTransform`, `List<DimStackEntry>`). `createConnectionBetween(a, b, gravityChange)` (`:52-106`) computes min/max content Y per world (`McHelper.getMinY`/`getMaxContentYExclusive`, overridable per entry), creates a `VerticalConnectingPortal` (ceil/floor per flip state, scale ratio `b.scale/a.scale`, horizontal rotation delta) + `PortalAPI.createReversePortal`, marks both **fuse-view** (`:47-50`), optionally `setTeleportChangesGravity(true)`, and adds them as **global portals** (`PortalAPI.addGlobalPortal`). `apply(server)` validates all dims exist, refuses if global portals already exist, detects connection conflicts via `getPortalInfoMap` (`:135-146`), chains adjacent entries (+ loop), then seeds `GlobalPortalStorage.bedrockReplacement` per world (`:161-177`).
- `DimStackEntry.java` (48 LOC) — per-dim record: `dimensionIdStr`, `scale`, `flipped`, `horizontalRotation`, `topY/bottomY`, `bedrockReplacementStr` (default `minecraft:obsidian`), `connectsPrevious/Next` (`:11-22`).
- **`DimensionStackAPI.java` (69 LOC)** — public API: two **Fabric `EventFactory.createArrayBacked` events**: `DIMENSION_STACK_CANDIDATE_COLLECTION_EVENT` (collect extra dimension keys for the GUI, `:34-46`) and `DIMENSION_STACK_PRE_UPDATE_EVENT` (fired before a stack is applied — the hook alternate dimensions use to add themselves, `:60-68`).

Client GUI (all `@Environment(CLIENT)`):
- `DimStackScreen` (349 LOC, `extends Screen`) — the stack editor (toggle/loop/gravity buttons, list widget, preset save) (`DimStackScreen.java:20-80`).
- `DimStackGuiController` (259 LOC) — MVC controller; `entryCountLimit = 64` (`:22`); constructed with a dimension-list supplier + finish callback (used both from the create-world screen and the in-game RPC path) (`:33-44`).
- `DimStackGuiModel` (16 LOC) — `isEnabled` + `DimStackInfo`.
- `DimEntryWidget` (195 LOC, `extends ContainerObjectSelectionList.Entry<DimEntryWidget>`) / `DimListWidget` (89 LOC, `extends AbstractSelectionList<DimEntryWidget>`) — list rows (show dim icon via `O_O.getModIconLocation`) and the reorderable list.
- `SelectDimensionScreen` (84 LOC, `Screen`) / `DimStackEntryEditScreen` (316 LOC, `Screen`) — dimension chooser and per-entry property editor.

#### `peripheral/alternate_dimension/` — OPTIONAL-FEATURE (9 files, ~1500 LOC)

- **`AlternateDimensions.java` (245 LOC)** — defines dimension-type keys `immersive_portals:surface_type` / `surface_type_bright` and level keys `skyland`, `bright_skyland`, `chaos`, `void`, `bright_void` (`:35-68`). Four **dimlib `DimensionTemplate`s** producing `LevelStem`s with custom generators (`:70-107`), registered by name (`:123-134`). `init()` also registers a `DIMENSION_STACK_CANDIDATE_COLLECTION_EVENT` supplier (`:111-115`), a `DIMENSION_STACK_PRE_UPDATE_EVENT` listener that lazily `DimensionAPI.addDimensionIfNotExists(...)` for any alt dim used by the stack (`:117-119`, `:137-184`), and **Fabric `ServerTickEvents.END_SERVER_TICK`** weather sync: every alternate dim copies overworld rain/thunder via the `IEWorld.portal_setWeather` duck (`:121`, `:227-244`). `createSkylandGenerator`/`createErrorTerrainGenerator`/`createVoidGenerator` build generators from registry lookups (void = `FlatLevelSource` with one air layer, `:210-225`).
- `NormalSkylandGenerator.java` (234 LOC) — `extends NoiseBasedChunkGenerator` **because `ChunkMap`'s constructor uses `instanceof NoiseBasedChunkGenerator` to seed `RandomState`** (javadoc `:55-59`); MAP_CODEC with 5 registry getters + seed (`:62-72`); pairs a `MultiNoiseBiomeSource` overworld biome layout (`:115-119`) with a skyland noise router built as **`IENoiseRouterData.ip_noNewCaves(dfGetter, noiseGetter, ip_slideEndLike(ip_getFunction(dfGetter, BASE_3D_NOISE_END), 0, 128))`** (`NormalSkylandGenerator.java:133-139`), spliced into a copy of the registry-loaded `FLOATING_ISLANDS` settings (`:124-147`) with seaLevel overwritten to 0 (`:142`). The router is **NOT** built from `NoiseRouterData.end`: the `ip_end` invoker is a dead declaration (`IENoiseRouterData.java:15-18`, never called anywhere in IP — grep-verified); `NormalSkylandGenerator.java:127` references only a *different*, commented-out `IENoiseGeneratorSettings.ip_end`.
- `ErrorTerrainGenerator.java` (161 LOC) — `extends DelegatedChunkGenerator`; chaos terrain from random formulas; per-4×4-chunk-region `RegionErrorTerrainGenerator` in a Guava `LoadingCache` (`:72`, `regionChunkNum=4, averageY=64, maxY=128` `:64-66`); delegates decoration to an internal `NoiseBasedChunkGenerator` with `FLOATING_ISLANDS` settings over a `ChaosBiomeSource` (`:46-62`).
- `DelegatedChunkGenerator.java` (172 LOC) — abstract `ChunkGenerator` forwarding most methods to a delegate (structure state, biomes, carvers…).
- `ChaosBiomeSource.java` (141 LOC) — `extends BiomeSource`; random biome per position from the biome registry; `MAP_CODEC` (`:21,127`).
- `FormulaGenerator.java` (192 LOC) — random math-expression trees (`TriNumFunction` with normalize by mean/stddev, `:10-31`; CPS-style tri-to-tri functions to avoid `Vec3` garbage, `:37-40`); seeds the chaos terrain.
- `RegionErrorTerrainGenerator.java` (104 LOC) — per-region formula + `Composition` interface mapping `(worldY, funcValue, …) -> BlockState` (`:10-19`), uses `net.minecraft.util.LinearCongruentialGenerator`.
- `ErrorTerrainComposition.java` (189 LOC) — the concrete compositions (mountain, classicalSolid, classicalHollow…) over stone/water/air (`:17-50`).
- `RandomSelector.java` (67 LOC) — weighted random pick helper.

#### `peripheral/wand/` — OPTIONAL-FEATURE (9 files, ~3900 LOC; the largest peripheral feature)

Client-side interaction (three modes, dispatched by `PortalWandItem`):
- **`PortalWandItem.java` (317 LOC)** — item with a **registered `DataComponentType` `iportal:portal_wand_data`** holding `Mode` (CREATE/DRAG/COPY, codec `:148-153`, registered `:40-44`). `init()` also registers Fabric **`AttackBlockCallback`** (wand can't break blocks, `:46-52`) and hooks `BlockManipulationServer.canDoCrossPortalInteractionEvent` (`:54-56`). `initClient()` registers Fabric **`ClientTickEvents.END_CLIENT_TICK`** per-tick display update + `IPCGlobal.CLIENT_CLEANUP_EVENT` resets (`:59-77`). Shift-right-click cycles mode **server-side** (`:186-194`); left-click intercepted client-side by `MixinMinecraft_PortalWand`. `clientRender(...)` dispatches per-mode rendering with `PoseStack` + `MultiBufferSource.BufferSource` (`:293-315`) — **1.21 immediate-render types; on 26.2 this whole path must be re-expressed in the submit pipeline.**
- **`ClientPortalWandPortalDrag.java` (1269 LOC)** — drag-mode state machine: selected portal UUID, `lockedAnchor` (`PortalLocalXYNormalized`), width/height locks, a bag of `Animated<T>` (q_misc_util animation holders keyed on `RenderStates.renderStartNanoTime`) for cursor/rect/planes (`:60-152`); `DraggingContext` record (`:158-172`); `init()` connects `ClientPortalAnimationManagement.clientAnimationUpdateSignal` to re-apply drag each animation update (`:174-178`); tick auto-undoes when the wand leaves the hand (`:268-284`); server round-trips via `McRemoteProcedureCall` → `PortalWandInteraction.RemoteCallables.requestApplyDrag/undoDrag/finishDragging`.
- `ClientPortalWandPortalCreation.java` (352 LOC) — cursor snapping (`WandUtil.alignOnBlocks`) + 6-click `ProtoPortal` staging; `finish()` → RPC `finishPortalCreation` with the `ProtoPortal` (`:190-196`).
- `ClientPortalWandPortalCopy.java` (362 LOC) — select portal → copy/cut RPC (`copyCutPortal`), then placement preview → `confirmCopyCut(origin, orientation)` (`:221-269`).
- `ProtoPortal.java` (253 LOC) / `ProtoPortalSide.java` (182 LOC) — gson/RPC-serializable staged two-sided portal spec (dimension key + leftBottom/rightBottom/leftTop `Vec3`s per side), with validity checks and completion math.
- `PortalCorner.java` (295 LOC) — enum LEFT_BOTTOM…RIGHT_TOP with the corner-drag solvers: `performDragWithNoLockedCorner` (translate), `performDragWith1LockedCorner`, `getDraggingConstraintWith2LockedCorners`, `performDragWith2LockedCorners` (`:51-229`) producing `UnilateralPortalState`s.
- `WandUtil.java` (244 LOC) — `getPortalByUUID` (`:47-60`); **`alignOnBlocks`**: snaps the cursor to the nearest 1/n grid point on nearby block collision-box surfaces (iterates a 3×3×3 `IntBox`, `blockState.getCollisionShape(world,pos).toAabbs()`, adds a full-cube AABB fallback so hoppers/air are alignable, `:62-93`); wire-grid render helpers via core `WireRenderingHelper` (`:96-243`).
- **`PortalWandInteraction.java` (878 LOC) — the server half.** Per-server instance in `IPPerServerInfo` (`of(server)`, `:51-53`). `RemoteCallables` (client→server RPC surface, every one permission-checked): `finishPortalCreation(player, ProtoPortal)`, `requestApplyDrag(player, portalId, cursorPos, DraggingInfo)`, `undoDrag`, `finishDragging`, `copyCutPortal(player, portalId, isCut)`, `confirmCopyCut(player, origin, orientation)`, `clearPortalClipboard` (`:55-118`). `DraggingSession` keeps the portal's original `PortalState` for undo (`:287-314`); `init()` prunes sessions on **Fabric `ServerTickEvents.END_SERVER_TICK`** (player removed / wand not held, `:316-343`). `DraggingInfo` (locked anchor, dragging anchor, width/height locks, `:345-381`); `applyDrag` (`:384`), `validateDraggedPortalState` (`:493`, `SIZE_LIMIT = 64` `:41`), `performDragWithOneLockedAnchor` (`:551`). Portal-creation handler validates the proto-portal then builds `Portal` + `PortalExtension`/`PortalManipulation` state server-side (`:120-…`).

#### `peripheral/portal_generation/` — **EXCLUDED (another agent's slice)**
Files: `IntrinsicPortalGeneration.java`, `IntrinsicNetherPortalForm.java`, `DiligentNetherPortalForm.java`, `PortalHelperForm.java`. Referenced from this slice by `PeripheralModMain.init()` (`PeripheralModMain.java:65`), `MixinAbstractFireBlock_CVB`, `MixinFlintAndSteelItem_CVB`.

#### `peripheral/mixin/**` + `ducks/` (18 mixins + 1 duck)

Client (`imm_ptl_peripheral.mixins.json` "client" list):
- `MixinBossHealthOverlay_CVB` (19 LOC) — forces `shouldCreateWorldFog()` false (boss fog not synced through portals; keeps end-portal crossing seamless) (`:14-18`). OPTIONAL (end-portal polish).
- `MixinSplashManager_CVB` (33 LOC) — easter-egg splash-text swaps (`:22-32`). OPTIONAL/cosmetic.
- `MixinClientLevelData_CVB` (28 LOC) — `getHorizonHeight` → `-10000` in alternate dimensions (no sky-darkness band below y=63) (`:14-27`). Belongs to alternate_dimension.
- `MixinFogRenderer_A_CVB` (36 LOC) — redirects `Camera.getPosition()` inside `FogRenderer.setupColor` to clamp y ≥ 32 in alternate dims (no black fog under islands) (`:16-36`). Belongs to alternate_dimension.
- `MixinDebugRenderer` (40 LOC) — injects at `DebugRenderer.render` RETURN to draw the wand overlays (`PortalWandItem.clientRender`) with the vanilla `PoseStack`/`MultiBufferSource.BufferSource` args (`:17-38`). Belongs to wand. **26.2 note: `DebugRenderer.render`'s signature/pipeline changed with the renderer rewrite.**
- `MixinMinecraft_PortalWand` (36 LOC) — intercepts `Minecraft.startAttack` before the item check; if holding the wand, routes to `PortalWandItem.onClientLeftClick` and cancels vanilla attack (`:20-36`). Belongs to wand.

Common:
- `IEChunkAccess_AlternateDim` (12 LOC) — `@Accessor("noiseChunk")` setter on `ChunkAccess`. / `IEChunkGenerator_AlternateDim` (17 LOC) — `@Mutable @Accessor("featuresPerStep")` on `ChunkGenerator`. / `IENoiseGeneratorSettings` (23 LOC) — **empty (all commented)**. / `IENoiseRouterData` (40 LOC) — `@Invoker`s for `NoiseRouterData.end/noNewCaves/slideEndLike/getFunction` + `@Accessor BASE_3D_NOISE_END` (`:14-36`); **the `end` invoker (`ip_end`, `:15-18`) is a dead declaration — never called anywhere in IP** (only `noNewCaves`/`slideEndLike`/`getFunction`/`BASE_3D_NOISE_END` are consumed, all from `NormalSkylandGenerator.java:133-139`). All belong to alternate_dimension.
- `MixinItemStackComponentizationFix` (55 LOC) — DFU hook at `fixItemStack` RETURN migrating old NBT command-stick/wand items to the new data components (`:21-54`). Belongs to items; only matters for upgrading pre-1.20.5 worlds.
- `MixinChunkStatusTasks_BedrockReplacement` (27 LOC) — at `ChunkStatusTasks.generateSpawn` HEAD calls `DimStackManagement.replaceBedrock(worldGenContext.level(), chunkAccess)` (`:19-26`). Belongs to dim_stack. **Note: injection point is the 1.21 chunk-gen task pipeline; must be re-found on 26.2.**
- `MixinMinecraftServer_DimStack_CVB` (47 LOC) — two injections into `MinecraftServer.createLevels`: before `setInitialSpawn` → `DimStackManagement.onServerEarlyInit`; at RETURN → `onServerCreatedWorlds` (`:28-47`). Belongs to dim_stack.
- `MixinEnderEyeItem_CVB` (87 LOC) — replaces `EnderEyeItem.useOn` when `IPGlobal.endPortalMode != vanilla`: fills the frame, then on complete pattern places 3×3 `PortalPlaceholderBlock` and calls `EndPortalEntity.onEndPortalComplete(world, pos)` instead of vanilla end-portal blocks (`:27-87`). Belongs to end-portal feature (core `EndPortalEntity`).
- `MixinAbstractFireBlock_CVB` (73 LOC) — redirects `PortalShape.findEmptyPortalShape` inside `BaseFireBlock.onPlace`: mode-`disabled` → empty; mode-`vanilla` → passthrough; else if fire adjacent to obsidian → `IntrinsicPortalGeneration.onFireLitOnObsidian` and **suppresses the vanilla portal** (`:21-46`); also redirects `Optional.isPresent` in `BaseFireBlock.isPortal` to allow fire on obsidian sides for horizontal portals (`:60-73`). Bridges to the excluded portal_generation slice.
- `MixinFlintAndSteelItem_CVB` (83 LOC) — `FlintAndSteelItem.useOn` HEAD: glass+`enableMirrorCreation` → `BreakableMirror.createMirror`; portal-helper block → `IntrinsicPortalGeneration.activatePortalHelper`; obsidian → crouching-ignite / `onFireLitOnObsidian` (`:29-83`). Bridges to portal_generation + core `BreakableMirror`.
- `ducks/IECreateWorldScreen` (5 LOC) + `MixinCreateWorldScreen_CVB` (126 LOC) + `MixinCreateWorldScreenMoreTab_CVB` (39 LOC) — dim_stack world-creation GUI: ctor-RETURN loads the dim-stack preset into `DimStackManagement.dimStackToApply` (`MixinCreateWorldScreen_CVB.java:57-69`); `ip_openDimStackScreen()` builds a `DimStackGuiController` whose dim list merges `uiState.getSettings()` selected + datapack dimensions + the collection event (`:71-125`); the MoreTab mixin adds the "dimension stack" `Button` to the tab's `GridLayout.RowHelper`, referencing the outer screen through the synthetic field `field_42178` (`MixinCreateWorldScreenMoreTab_CVB.java:19-37`) — **synthetic-field name is version-fragile**.

---

## 3. Mechanisms (depth notes)

### 3.1 Boot & registration flow (Fabric)
`fabric.mod.json` declares entrypoints — main: `[PeripheralModEntry, IPModEntry, MiscUtilModEntry]`, client: `[PeripheralModEntryClient, IPModEntryClient, MiscUtilModEntryClient]`, server: `[IPModEntryDedicatedServer]`, modmenu: `[IPModMenuConfigEntry]` — and five mixin configs (`imm_ptl_peripheral`, `imm_ptl`, `imm_ptl_fabric`, `imm_ptl_compat`, `q_misc_util`) plus accesswidener `imm_ptl.accesswidener`. Order matters: Fabric calls `main` entrypoints in list order, so **peripheral registers its items/generators before core registers entity types**; both before any world load. Config is loaded inside `IPModMain.init()` → `loadConfig()` (`IPModMain.java:130-153`), i.e. during `IPModEntry.onInitialize`, so `IPConfig` values (and the `IPFeatureControl` jar-in-jar defaults) are live before entity types register. The client entrypoint then binds every portal entity type to `PortalEntityRenderer` (one renderer class for all portal types — the migration's client rendering entry).

### 3.2 The invoker-pattern compat abstraction (the load-bearing part of `compat`)
Four mods are abstracted behind a static `invoker` field holding a no-op base instance, swapped at entrypoint time if the mod is present (`IPModEntry.java:32-35`, `IPModEntryClient.java:71-95`). Core code calls the invoker **unconditionally**: e.g. teleportation math routes all velocity/eye-offset through `GravityChangerInterface.invoker.getWorldVelocity/transformPlayerToWorld` (call sites in `ServerTeleportationManager`, `ClientTeleportationManager`, `PortalCollisionHandler`); render code checks `IrisInterface.invoker.isShaders()`/`isRenderingShadowMap()` and `SodiumInterface.invoker.isSodiumPresent()` per-frame. **Porting consequence: the base `Invoker` classes are core dependencies and must be ported even if none of the third-party mods exist on 26.2; only the `On*Present` subclasses and the mixins are skippable.**

### 3.3 Per-dimension renderer-state swapping (Sodium/Iris)
IP keeps one `ClientLevel` per dimension alive and swaps renderer internals when the rendered dimension changes:
- Sodium: each dimension owns a `SodiumRenderingContext{SortedRenderLists, renderDistance}`; `switchContextWithCurrentWorldRenderer` swaps those two fields inside the live `RenderSectionManager` by *exchange* (old values go back into the context) (`SodiumInterface.java:64-76`, `MixinSodiumRenderSectionManager.java:30-42`), bracketed by `scheduleTerrainUpdate()`.
- Iris: each dimension's pipeline object is stored/retrieved through reflection on the Iris-added `LevelRenderer.pipeline` field (`IrisInterface.java:45-81`).
- During portal recursion Sodium additionally needs: per-layer `ChunkRenderList`s (`MixinSodiumRenderRegion`), BFS origin override + occlusion-culling disable from `PortalShape.getModifiedVisibleSectionIterationOrigin` (`MixinSodiumOcclusionCuller`), the extra portal-frustum cull (`MixinSodiumViewport` + `FrustumCuller`), and clip-plane support injected into Sodium/Iris terrain shaders as the `iportal_ClippingEquation` uniform, fed from `FrontClipping.getActiveClipPlaneEquationAfterModelView()` each `setupState` (`MixinSodiumDefaultShaderInterface.java:40-53`, `MixinIrisSodiumShader.java:44-60`) with GLSL rewritten by `ShaderCodeTransformation` at shader-load time (`MixinSodiumShaderLoader.java:17-38`, `MixinIrisTransformPatcher.java:24-40`).

### 3.4 Iris portal rendering (deferred-FBO stencil compositing)
With shaders on, IP can't use its normal stencil renderer (Iris owns the framebuffers), so `IrisPortalRenderer` keeps its own `SecondaryFrameBuffer` **per recursion layer**, each with a stencil attachment: copy MC depth into layer FB → draw portal shape with `glStencilOp(..., GL_INCR)` to claim pixels (`:286-288`) → recurse (`renderPortalContent` renders the destination world into the *main* MC framebuffer) → composite the inner layer's FB back into the outer layer's FB where stencil == innerLayer (`:259-273`) → at layer 0 blit to screen (`:222`). On any GL error it permanently downgrades to `IPGlobal.RenderMode.compatibility` (`:135-141`, single non-recursive FB, `IrisCompatibilityPortalRenderer`). `ExperimentalIrisPortalRenderer` instead reuses the vanilla framebuffer's depth+stencil (possible since Iris 1.8: comment `:36-37`) and splices portal rendering into Iris's translucent phase via `MixinIrisRenderingPipeline`, canceling Iris `ClearPass`/`finalizeLevelRendering` during recursion. **All three are raw-GL (LWJGL GL11/30/43) and predate the 26.2 submit->prepare->execute pipeline; on 26.2 they must be reconceived, not line-ported** (cf. `MIGRATION_API_MAP.md` headline + this repo's own stencil-direct work).

### 3.5 Dimension stack
A dimension stack is a list of dimensions connected floor↔ceiling with **global `VerticalConnectingPortal`s** (fuse-view, optionally gravity-transforming, scale = ratio of entry scales), stored per world in `GlobalPortalStorage` — not entity-portals in chunks, so nothing to migrate per-chunk. Application timing is delicate: `MixinMinecraftServer_DimStack_CVB` calls `onServerEarlyInit` *before* overworld spawn-chunk generation so `bedrockReplacementMap` is ready for `MixinChunkStatusTasks_BedrockReplacement.generateSpawn`, and `onServerCreatedWorlds` after all levels exist to actually create the portals (`DimStackInfo.apply` refuses to run if global portals already exist, `DimStackInfo.java:126-132`). New stacks staged from three sources: create-world GUI (via `DimStackManagement.dimStackToApply` static), in-game `/portal dimension_stack` command (client GUI over `McRemoteProcedureCall`), or a saved preset (JSON in `IPConfig.dimStackPreset`; on dedicated servers the preset auto-applies at first boot if no dim-stack portals exist, `DimStackManagement.java:95-112`). Two public Fabric events let other mods add candidate dimensions or react before application (`DimensionStackAPI.java:34-68`).

### 3.6 Portal wand (client-authoritative editing, server-validated)
All pointing/snapping/preview math runs client-side (cursor aligned to block-collision-box grid points, `WandUtil.alignOnBlocks`; animated overlays drawn from inside `DebugRenderer.render`), and every state-changing action is an RPC into `PortalWandInteraction.RemoteCallables` where the server re-validates permission (`checkPermission` → creative/OP or `portalWandUsableOnSurvivalMode`), re-validates geometry (`validateDraggedPortalState`, 64-block size limit), executes against the real `Portal` entity, and keeps an undo copy (`DraggingSession.originalState`). Dragging locks are expressed in **portal-local normalized XY** (`PortalLocalXYNormalized`) so they survive portal movement; corner-drag solutions for 0/1/2 locked corners are closed-form in `PortalCorner`. The drag preview also re-applies during portal animation updates via `ClientPortalAnimationManagement.clientAnimationUpdateSignal` (`ClientPortalWandPortalDrag.java:174-178`). This package is the biggest consumer of `q_misc_util` RPC (`McRemoteProcedureCall.tellServerToInvoke/tellClientToInvoke` by method-path string).

### 3.7 Alternate dimensions
Registered as dimlib `DimensionTemplate`s (not JSON dimensions), instantiated lazily when a dim stack references them (`DIMENSION_STACK_PRE_UPDATE_EVENT` listener) — so they don't exist in a save unless used. Custom `ChunkGenerator` codecs are registered in `BuiltInRegistries.CHUNK_GENERATOR/BIOME_SOURCE` so the `LevelStem`s serialize. The chaos ("error terrain") generator is deterministic per 4×4-chunk region from seeded random math-formula trees; skyland pairs overworld biomes with a router of `noNewCaves(slideEndLike(getFunction(BASE_3D_NOISE_END), 0, 128))` built via the `IENoiseRouterData` invokers into `NoiseRouterData` (`NormalSkylandGenerator.java:133-139` — the end/end-islands router is not involved); weather is mirrored from the overworld each server tick through the `IEWorld.portal_setWeather` duck.

---

## 4. MC API touchpoint list (dedup; anything version-fragile)

**Registries / data components**
1. `Registry.register` + `BuiltInRegistries.ENTITY_TYPE` (`IPModEntry.java:19`), `.BLOCK` (`IPModEntry.java:22`, `PeripheralModEntry.java:13`), `.ITEM`, `.CHUNK_GENERATOR`, `.BIOME_SOURCE`, `.CREATIVE_MODE_TAB` (`PeripheralModEntry.java:16-27`), `.DATA_COMPONENT_TYPE` (`CommandStickItem.java:199-203`, `PortalWandItem.java:40-44`).
2. `DataComponentType.builder().persistent(codec)` + `ItemStack.get/set/getOrDefault(componentType)` (`CommandStickItem.java:50-53,121,211`; `PortalWandItem.java:150-153`).
3. `ResourceKey.create(Registries.DIMENSION / DIMENSION_TYPE, ...)` (`AlternateDimensions.java:35-68`); `RegistryAccess.registryOrThrow(...).asLookup()` (`AlternateDimensions.java:192-208`); `RegistryOps.retrieveGetter` in codecs (`ErrorTerrainGenerator.java:38-44`, `NormalSkylandGenerator.java:62-72`).

**Entity / client rendering (heavy 26.2 exposure)**
4. `EntityRendererProvider` + Fabric `EntityRendererRegistry.register` (`IPModEntryClient.java:52-61`).
5. `MultiBufferSource.BufferSource`, `PoseStack`, `VertexConsumer`, `RenderType.cutout()`/lines — wand overlay + block render layer (`PortalWandItem.java:295`, `WandUtil.java:96-243`, `PeripheralModEntryClient.java:10-13`, `MixinDebugRenderer.java:20-27`). **GONE/reshaped in 26.2's SubmitNodeCollector pipeline.**
6. `DebugRenderer.render(PoseStack, BufferSource, camXYZ)` injection point (`MixinDebugRenderer.java:18-27`).
7. `Minecraft.startAttack` (`MixinMinecraft_PortalWand.java:20-28`); `Minecraft.getInstance().player/level/options.keyUse/keyShift/keyAttack/keyChat` (`PortalWandItem.java:229-237,298`).
8. `RenderTarget`: `frameBufferId`, `viewWidth/viewHeight`, `width/height`, `bindWrite/unbindWrite`, `blitToScreen`, `destroyBuffers`, `checkStatus`, `getDepthTextureId/getColorTextureId`, `resize` (`IrisPortalRenderer.java:120-167,222`, `IPIrisHelper.java:41-98`, `IPPortingLibCompat.java:58-60`). Plus `GlStateManager._clearColor/_clearDepth/_clearStencil/_glBindFramebuffer/_colorMask/_enableDepthTest` and LWJGL `GL11/GL30/GL30C/GL43C` (`glClear`, `glBlitFramebuffer`, `glCopyImageSubData`, `glStencilFunc/Op`, `glGetInteger(GL_DEPTH_FUNC)`). **The entire raw-GL family is at odds with 26.2's RenderPass model.**
9. `RenderSystem.getProjectionMatrix()` (`IrisPortalRenderer.java:296`); `Minecraft.ON_OSX` (`IPPortingLibCompat.java:59`).
10. `FogRenderer.setupColor` + `Camera.getPosition()` (`MixinFogRenderer_A_CVB.java:17-25`) — **FogRenderer reworked in 26.2**; `ClientLevel.ClientLevelData.getHorizonHeight(LevelHeightAccessor)` (`MixinClientLevelData_CVB.java:14-17`); `BossHealthOverlay.shouldCreateWorldFog` (`MixinBossHealthOverlay_CVB.java:14`).
11. `LevelRenderer` (reflection target for Iris pipeline field, `IrisInterface.java:46`); `TextureAtlasSprite` (`SodiumInterface.java:37`); `CompiledShader.Type` (`MixinSodiumShaderLoader.java:34`, `MixinIrisTransformPatcher.java:36`).

**Networking / server lifecycle**
12. `ClientboundCustomPayloadPacket` + `Packet<ClientGamePacketListener>` wrapping (`MixinCardinalCompComponentKey.java:30-53`).
13. `PlayerList.respawn(ServerPlayer, boolean, Entity.RemovalReason)` / `PlayerList.remove` (`MixinPlayerManager_MA.java:16-31`).
14. `ServerPlayer.changeDimension(DimensionTransition)` + `ServerPlayer.teleportTo(ServerLevel,DDDFF)` (`MixinServerPlayerEntity_MA.java:19-43`) — **`DimensionTransition` is 1.21-specific; renamed in later MC.**
15. `MinecraftServer.createLevels(ChunkProgressListener)` + `setInitialSpawn` invoke point (`MixinMinecraftServer_DimStack_CVB.java:28-47`); `MinecraftServer.getAllLevels/getLevel/levelKeys/registryAccess/getWorldData().worldGenOptions()` (`DimStackManagement.java:117,165-171`); `Commands.performPrefixedCommand` + `CommandSourceStack.withPermission(2)` (`CommandStickItem.java:128-141`); `player.hasPermissions(2)` (`DimStackManagement.java:222,249`).
16. `SharedConstants.getCurrentVersion().getName()` (`O_O.java:98`); `Util.backgroundExecutor()` (`IPModInfoChecking.java:146`).

**Chunk / worldgen**
17. `ClientChunkCache` (superclass of `ImmPtlClientChunkMap`, created at `O_O.java:88-90`); `LevelChunk.getLevel()` (`O_O.java:68-76`).
18. `ChunkStatusTasks.generateSpawn(WorldGenContext, ChunkStep, StaticCache2D<GenerationChunkHolder>, ChunkAccess)` (`MixinChunkStatusTasks_BedrockReplacement.java:19-26`) — **1.21 chunk-pipeline types (`WorldGenContext`, `ChunkStep`) are new/renamed across versions.**
19. `ChunkAccess.getBlockState/setBlockState/getMinBuildHeight/getMaxBuildHeight` (`DimStackManagement.java:143-158`); `ChunkAccess.noiseChunk` accessor (`IEChunkAccess_AlternateDim.java:9-11`).
20. `ChunkGenerator` (subclassing + `featuresPerStep` accessor `IEChunkGenerator_AlternateDim.java:13-16`), `NoiseBasedChunkGenerator` (subclassed — `instanceof` dependency in `ChunkMap` ctor, `NormalSkylandGenerator.java:55-59`), `BiomeSource` (subclassed, `ChaosBiomeSource.java:21`), `FlatLevelSource`/`FlatLevelGeneratorSettings`/`FlatLayerInfo` (`AlternateDimensions.java:210-225`), `NoiseRouterData.end/noNewCaves/slideEndLike/getFunction` + `BASE_3D_NOISE_END` invokers (`IENoiseRouterData.java:14-36`; the `end` invoker is a dead declaration, never called), `NoiseGeneratorSettings.FLOATING_ISLANDS` (`ErrorTerrainGenerator.java:52-53`), `LevelStem`, `DimensionType`, `RandomState`, `Blender`, `Heightmap`, `LinearCongruentialGenerator` (`RegionErrorTerrainGenerator.java:3`).
21. `ServerLevel.getRainLevel/getThunderLevel` + IP duck `IEWorld.portal_setWeather` (`AlternateDimensions.java:237-244`); `ServerLevel.getEntity(UUID)` (`PortalWandInteraction.java:73,305`).

**Blocks / items / interaction**
22. `BaseFireBlock.onPlace` + `PortalShape.findEmptyPortalShape(LevelAccessor, BlockPos, Direction.Axis)` + `BaseFireBlock.isPortal` (`MixinAbstractFireBlock_CVB.java:21-73`) — **`net.minecraft.world.level.portal.PortalShape` is exactly the vanilla class the block-portal system replaces; 26.2 signature check needed.**
23. `FlintAndSteelItem.useOn(UseOnContext)` (`MixinFlintAndSteelItem_CVB.java:29`); `EnderEyeItem.useOn` + `EndPortalFrameBlock.HAS_EYE/getOrCreatePortalShape` + `BlockPattern.BlockPatternMatch` + `Level.globalLevelEvent(1038)/levelEvent(1503)` + `Block.pushEntitiesUp` (`MixinEnderEyeItem_CVB.java:27-87`).
24. `BlockItem`/`Item.useOn/use/appendHoverText(TooltipContext)/isFoil/getName/getDescriptionId` (`PortalHelperItem.java`, `CommandStickItem.java`, `PortalWandItem.java`) — **`getDescriptionId(ItemStack)` and tooltip APIs shift across versions.**
25. `BlockState.getCollisionShape(world, pos)` + `VoxelShape.toAabbs()` (`WandUtil.java:75-77`); `Blocks.OBSIDIAN/BEDROCK/AIR/STONE/WATER.defaultBlockState()`.
26. `ItemStackComponentizationFix.ItemStackData.is/removeTag/setComponent/moveTagToComponent` (`MixinItemStackComponentizationFix.java:25-52`) — DFU internal.
27. `CreativeModeTab.Output.accept` + `CreativeModeTab` registration (`PeripheralModMain.java:40-51`, `CommandStickItem.java:218-224`).

**GUI (dim stack)**
28. `Screen`, `Button.builder(...).width/build`, `GridLayout.RowHelper.addChild` (`MixinCreateWorldScreenMoreTab_CVB.java:32-37`), `AbstractSelectionList` / `ContainerObjectSelectionList.Entry` + `NarratableEntry`/`GuiEventListener` (`DimListWidget.java:11`, `DimEntryWidget.java:27-78`), `GuiGraphics`, `Font`, `AlertScreen`.
29. `CreateWorldScreen` ctor + `CreateWorldScreen.MoreTab` (synthetic outer-ref field `field_42178`, `MixinCreateWorldScreenMoreTab_CVB.java:20-22`), `WorldCreationUiState.getSettings()`, `WorldCreationContext.worldgenLoadContext/selectedDimensions/datapackDimensions/options` (`MixinCreateWorldScreen_CVB.java:94-113`), `WorldDimensions.dimensions()`, `WorldOptions`.
30. `SplashManager.apply` + `splashes` field (`MixinSplashManager_CVB.java:17-28`).

**Fabric API (per multiloader port, each needs a loader-neutral equivalent)**
31. `ModInitializer`/`ClientModInitializer`/`DedicatedServerModInitializer` entrypoints; `FabricLoader.getInstance()` (isModLoaded, getModContainer, getGameDir, getEnvironmentType, isDevelopmentEnvironment, getAllMods) — used across `O_O`, entry classes, `IPCompatMixinPlugin`, `IPFeatureControl`; **Fabric-internal `SemanticVersionImpl`** (`O_O.java:147`).
32. `ClientChunkEvents.CHUNK_LOAD/CHUNK_UNLOAD` (`O_O.java:67-77`); `ServerLifecycleEvents.SERVER_STARTED` (`IPModInfoChecking.java:284`); `ServerTickEvents.END_SERVER_TICK` (`AlternateDimensions.java:121`, `PortalWandInteraction.java:317`); `ClientTickEvents.END_CLIENT_TICK` (`PortalWandItem.java:60`); `AttackBlockCallback.EVENT` (`PortalWandItem.java:46`); `EventFactory.createArrayBacked` (`DimensionStackAPI.java:34-68`); `InvalidateRenderStateCallback` + Fabric-internal `ClientPlayNetworkAddon` (platform mixins); `EntityRendererRegistry`; `FabricItemGroup.builder()` (`PeripheralModMain.java:41`); `FabricBlockSettings.of()` (`PeripheralModMain.java:35`); `BlockRenderLayerMap.INSTANCE.putBlock` (`PeripheralModEntryClient.java:10`).
33. ModMenu `ModMenuApi`/`ConfigScreenFactory`; Cloth Config `AutoConfig.register/getConfigScreen`, `ConfigData`, `@ConfigEntry` annotations, `GsonConfigSerializer` (`IPConfig.java`, `IPConfigGUI.java`, `IPModMain.java:146`).
34. dimlib (qouteall's separate mod, hard dependency `"dimlib": "*"`): `DimensionAPI.SERVER_DIMENSIONS_LOAD_EVENT/addDimension/addDimensionIfNotExists/suppressExperimentalWarningForNamespace`, `DimensionTemplate` (`DimStackManagement.java:48`, `AlternateDimensions.java:110-183`, `PeripheralModMain.java:71`).
35. `java.net.http.HttpClient` (`IPModInfoChecking.java:120`) — JDK, not MC, but sandbox/policy-relevant.

---

## 5. Registration & wiring summary

- **Entity types**: registered only in `IPModEntry.onInitialize` via `IPModMain.registerEntityTypes` callback → `BuiltInRegistries.ENTITY_TYPE` (`IPModEntry.java:18-20`; ids at `IPModMain.java:162-213`). No Fabric `FabricEntityTypeBuilder` here — the `EntityType`s themselves are built in core (other slice); this layer only does `Registry.register`.
- **Entity renderers**: `IPModEntryClient.initPortalRenderers` — Fabric `EntityRendererRegistry`, all portal types → `PortalEntityRenderer::new`, loading indicator → `LoadingIndicatorRenderer::new` (`IPModEntryClient.java:39-63`).
- **Blocks/items/tabs/generator codecs**: `PeripheralModEntry` (five `BuiltInRegistries` targets, `:12-27`) + core's `PortalPlaceholderBlock` in `IPModEntry` (`:22`). Client block render layer via `BlockRenderLayerMap` (`PeripheralModEntryClient.java:9-14`).
- **Config**: Cloth AutoConfig registered in `IPModMain.loadConfig` (`IPModMain.java:146-152`); GUI exposed through ModMenu entrypoint (`IPModMenuConfigEntry`); every change fans out via `IPConfig.onConfigChanged` into `IPGlobal`.
- **Packets**: none registered in this slice — networking registration lives in core (`ImmPtlNetworking.init()`/`ImmPtlNetworkConfig.init()`/`PacketRedirection.init()` called from `IPModMain.init()` at `IPModMain.java:67-69`, other agent's slice). This slice only *wraps* packets (`MixinCardinalCompComponentKey`) and uses `McRemoteProcedureCall` (q_misc_util) for wand/dim-stack RPC.
- **Ticking**: no dedicated tick loops; everything hangs off Fabric events (`ServerTickEvents.END_SERVER_TICK` for wand-session pruning + alt-dim weather; `ClientTickEvents.END_CLIENT_TICK` for wand display) or IP task lists (`IPGlobal.CLIENT_TASK_LIST`, `ServerTaskList`).
- **Mixin configs**: `imm_ptl_fabric.mixins.json` (package `qouteall.imm_ptl.core.platform_specific.mixin`; 2 common + 2 client, `defaultRequire: 1`); `imm_ptl_compat.mixins.json` (plugin `IPCompatMixinPlugin` gates all 20 by mod presence, default-deny); `imm_ptl_peripheral.mixins.json` (plugin `IPPeripheralMixinPlugin`, currently allow-all; 10 common + 8 client).
- **Cleanup/reset events**: wand/drag state resets on `IPCGlobal.CLIENT_CLEANUP_EVENT` (`PortalWandItem.java:74-76`) and `IPGlobal.SERVER_CLEANUP_EVENT` (`PortalWandInteraction.java:341-342`) — the port must fire equivalents on disconnect/server-stop.

---

## Appendix: honest SKIP-candidate list (classification only — user decides)

| Item | Reason |
|---|---|
| `IEClientWorld_MA`, `MixinFabricClientPlayNetworkAddon` | Empty/dead in IP itself. |
| `ShadowMapSwapper`, `IEIrisShadowRenderTargets`, `MixinIrisShadowRenderTargets`, `MixinIrisIris`, `IENoiseGeneratorSettings` | Fully commented-out bodies. |
| `compat/mixin/flywheel/*` (3), `IPFlywheelCompat` | Target 1.18-era Flywheel package names (`com.jozufozu.flywheel.core.*`); cannot match modern Flywheel; `isFlywheelPresent` never set true. |
| `MixinCardinalCompComponentKey` | Targets pre-rename CCA package `dev.onyxstudios.cca`; stale on 1.21-era CCA (`org.ladysnake.cca`). The *packet-redirection-wrapping* idea matters if CCA compat is ever wanted. |
| All Sodium/Iris compat (interfaces' `On*Present` impls + 16 live mixins + 3 Iris renderers) | Pinned to Sodium 0.6.0 / Iris 1.8.0 internals for MC 1.21.1; both mods' 26.2 internals (if they exist) will differ, and 26.2's own pipeline rewrite invalidates the raw-GL renderer approach. Base `Invoker` classes are NOT skippable. |
| `RequiemCompat` | Reflection-only; needs Requiem on 26.2. The `O_O` hook points carry over regardless. |
| `IPModInfoChecking` | Online update/incompat checker for qouteall's info service; irrelevant to a private port. Referenced by both non-main entrypoints and `PortalRenderer.checkShaderpack`. |
| `PortalHelperItem` + portal-helper block | Deprecated in IP itself in favor of `/portal shape sculpt` (`PortalHelperItem.java:26-35`). |
| `MixinSplashManager_CVB` | Cosmetic easter egg. |
| `O_O.isQuilt/getModIconLocation/getImmPtlModInfoUrl/shouldUpdateImmPtl` etc. | Only serve ModMenu/dim-stack-GUI icons and the info checker. |
