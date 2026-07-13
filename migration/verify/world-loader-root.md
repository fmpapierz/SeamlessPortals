# Adversarial verification: ClientWorldLoader + root helpers slice (re-run 2026-07-12)

Documents verified (as revised after the 2026-07-10 verification pass):
- INV = `migration/inventory/world-loader-root.md`
- MAP = `migration/api-map/world-loader-root.md`

Method: every selected claim re-derived from primary source. IP ground truth:
`C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall` (paths below relative to
`qouteall/imm_ptl/core/` unless noted). 26.2 ground truth: `C:/Users/warwa/ModDev/mc262-ref`
(paths relative to that root).

**Overall: 42 claims checked, 39 CONFIRMED, 3 REFUTED (all minor, all in MAP). Severity: minor.**
No refuted claim misdirects architecture, geometry, or an API fate.

**Prior-pass reconciliation:** the 2026-07-10 pass refuted 3 claims. Its R1 (INV claimed a
`runningTask()` override) **was fixed** in the current INV (lines 577-581 now correctly state
"runningTask() is NOT overridden"). Its R2 (`LevelRenderer.close()` unmapped in MAP) and R3
(`Gui.setScreen` off-thread log overstated) **remain unaddressed** and are re-reported below.

**Scope completeness:** directory listing of the IP core root shows exactly 11 root `.java` files
(CHelper, ClientWorldLoader, IPCGlobal, IPGlobal, IPMcHelper, IPMixinPlugin, IPModMain, IPModMainClient,
IPPerServerInfo, McHelper, ScaleUtils) — all covered by INV §2.1–2.11. **No files silently skipped.**
No internal contradictions found between INV and MAP (spot-diffed ctor shapes, the swap-set list, event
wiring, and every verdict that appears in both).

---

## REFUTED (with corrections) — all in MAP

### R1. MAP (GONE row "Minecraft.setScreen"): "`Gui.setScreen` logs an error if called off-thread (`Gui.java:224`)" — overstated
Ground truth (`net/minecraft/client/gui/Gui.java:223-225`): the log is gated on
`SharedConstants.IS_RUNNING_IN_IDE && Thread.currentThread() != this.minecraft.getRunningThread()`.
In a production client an off-thread `setScreen` logs **nothing** and proceeds unguarded.
Correction: IDE-only diagnostic; do not rely on it as a runtime off-thread tripwire.
(Carried forward from the 2026-07-10 pass — not fixed.)

### R2. MAP (CHANGED row "LevelRenderer ctor", migration note): "Secondary-renderer creation must follow `Minecraft.java:638-650` — including … reload-listener registration (:650)" — template stops one line short
Ground truth: vanilla registers a SECOND reload listener at `Minecraft.java:651`:
`this.resourceManager.registerReloadListener(this.levelRenderer.cloudRenderer());`.
A secondary renderer built from the doc's stated range would silently miss cloud-resource reloads.
Correction: the vanilla template is `Minecraft.java:638-651` — LevelRenderer ctor (:638-648), paired
LevelExtractor (:649), extractor reload listener (:650), **and the cloudRenderer reload listener (:651)**.
(New finding this pass.)

### R3. MAP: `LevelRenderer.close()` touchpoint has no GONE/CHANGED/SAME row — coverage hole
INV lists `.close()` (ClientWorldLoader.java:228, secondary-renderer disposal in `disposeWorldRenderer`)
as a version-sensitive LevelRenderer touchpoint (INV §4 renderer-hotspot list and closing notes), but MAP
maps only setLevel/onResourceManagerReload/allChanged/tick — `close()` is unmapped.
Ground truth: it survives — `LevelRenderer implements AutoCloseable`
(`net/minecraft/client/renderer/LevelRenderer.java:95`); `public void close()` :773, body now calls
`this.resetLevelRenderData()` :774 (method at :878) — changed internals vs the 1.21.3 GPU-teardown shape.
Correction: add a CHANGED row: `close()` exists; semantics now “reset render data,” not buffer disposal.
(Carried forward from the 2026-07-10 pass — not fixed.)

---

## OMISSIONS FLAGGED (not stated-claim refutations; for the design stage)

- **`ReentrantBlockableEventLoop` ctor changed**: 26.2 ctor is `(String name, boolean propagatesCrashes)`
  (`util/thread/ReentrantBlockableEventLoop.java:6-8`). IP's `MixinMinecraft_RedirectedPacket` extends it
  with a 1-arg `super(string)` (:18-20) — that shape will not compile on 26.2. Low impact because MAP's
  GONE row already mandates redesigning the whole requeue mechanism, but neither doc records the ctor change.
  (MAP's ":11-16 methods intact" statement is itself accurate — verified.)
- **`updateLevelInEngines(level, stopSound)` also calls `this.updateTitle()`** (`Minecraft.java:2205`) —
  MAP's body list omits it. Cosmetic completeness nit.
- **`LevelExtractor` package**: actual path is `net/minecraft/client/renderer/extract/LevelExtractor.java`
  (subpackage `extract`); MAP cites the bare filename. Matters when writing mixin target strings.
- Trivial ±1-line drifts (substance unaffected): `LevelHeightAccessor.getMinY` doc :9 / actual :8;
  `Minecraft` class declaration doc :261 / actual :260.
- INV/MAP note on shared map data: IP 1.21.3 declares the shared map as `Map<String, MapItemSavedData>`
  (ClientWorldLoader.java:415) while 26.2 keys it by `MapId` (`ClientLevel.java:160`) — MAP's SAME row has
  the 26.2 side right; the accessor duck's key generic changes at port time.

---

## CONFIRMED CLAIMS (evidence per claim)

### A. Geometry / transform / behavior (re-derived from IP source)

| # | Claim (doc) | Evidence |
|---|---|---|
| 1 | INV §3.1: `withSwitchedWorld` swaps exactly `Minecraft.level`, particle-engine level (duck), `Minecraft.levelRenderer` (duck), `ClientPacketListener.level` (duck) + `isWorldSwitched`; `finally` restore; if `CLIENT.level` changed during the supplier, logs "Respawn packet should not be redirected" and adopts the changed level/renderer as restore target; never touches `CLIENT.player.level()` | ClientWorldLoader.java:551-583 (saves :551-554, sets :560-564, respawn case :570-575, restore :577-581); javadoc :540-542. Swap set is exactly the 4 refs + flag. |
| 2 | INV §3.1: `tickRemoteWorldRandomTicksClient` — portals within **10 blocks** collected BEFORE the switch; first portal with `getDestDim() == newWorld.dimension()`; `center = portal.transformPoint(player.position())` (origin→dest); camera teleported via `IECamera.portal_setPos`; `animateTick` every **second** game tick; `CLIENT.particleEngine.tick()` under the switch; camera restored | ClientWorldLoader.java:155 (`CHelper.getClientNearbyPortals(10)` before the switch at :157), :181-186, :191, :193-197 (`getGameTime() % 2 == 0`), :200, :202. |
| 3 | INV §3.3/§2.3: recursive `rayTrace` — cap `portals.size() > maxPortalLayer` → synthesized `BlockHitResult.miss` with face = `Direction` **maximizing** `getUnitVec3().dot(end-start)`; `world.clip`; nearest `Portal::isInteractable`; recurse only when portal intersection nearer than block hit; ClipContext mutated via duck: start←`transformPoint(intersection)`, end←`transformPoint(end)`; recursion in `getDestinationWorld()` under `withSwitchedContext` (client=switch, server=plain); context restored | IPMcHelper.java:204-217, :220, :222-229, :234 (early return when block hit closer — sign correct), :240-244, :247-251, :253-255, :181-188. |
| 4 | INV §2.3: `foreachNearbyPortals` global-portal filter `getDistanceToNearestPointInPortal(pos) < range*2`; `rayTracePortals` midpoint search, chunk radius `ceil(dist/2/16)`, sorted by squared distance from start | IPMcHelper.java:43-53 (:46), :133-138, :150, :158-165. |
| 5 | INV §3.4/§2.11: ScaleUtils — id `iportal:scaling`; `ADD_MULTIPLIED_TOTAL` of `newScale-1.0`; read-back `amount()+1.0`; `refreshDimensions()` on set/remove/setBase; snap-to-1 within 1e-4; illegal = `> scaleLimit \|\| < 1/(2*scaleLimit)`, server-side clamp→1 + "Scale out of range"; scaling anchored at the **eye point** (eye + last-tick eye snapshotted, restored + bbox update); client teleport scales camera smoothed Y; server teleport scales vehicle too | ScaleUtils.java:26-27, :115-119, :94, :107/:122/:131, :177-179, :184-186, :154-159, :148-149 + :164-169, :40-44, :52-54. |
| 6 | INV §2.2: `adjustVehicle` — `setPos` + 6-arg `lerpTo(x,y,z,yRot,xRot,0)` interpolation-kill + `setPosAndLastTickPos` + velocity restore; `setPosAndLastTickPos` writes `setPosRaw` + BOTH `xOld/yOld/zOld` and `xo/yo/zo` | McHelper.java:305-332 (:320-324, :326-328, :330); :247-258. |
| 7 | INV §3.1: secondary world creation — `chunkLoadDistance=3`; `new LevelRenderer(CLIENT, ERD, BERD, CLIENT.renderBuffers())`; dim type via `dimIdToDimTypeId.get` + `registryOrThrow(DIMENSION_TYPE).getHolderOrThrow`; fresh `ClientLevelData(difficulty, hardcore, isFlat)`; 1.21.3 10-arg ClientLevel ctor in the documented order (incl. `CLIENT::getProfiler` and the `LevelRenderer` param); mapData shared **by reference**; TickRateManager shared; `setLevel` → `onResourceManagerReload`; `CLIENT_WORLD_LOAD_EVENT` after registration; `finally` clears `isCreatingClientWorld` | ClientWorldLoader.java:389-487 (:402, :404-409, :420, :434-436, :440-444, :445-456, :415+:459, :462, :464, :466, :468-484, :479-482). |
| 8 | INV §3.1: lightmap conflict detection + `DimensionRenderHelper` semantics (current dim shares `gameRenderer.lightTexture()`, never ticks/closes it; other dims own `new LightTexture(gameRenderer, client)`) | ClientWorldLoader.java:127-145; render/context_management/DimensionRenderHelper.java:17-26, :29-33, :35-39. |
| 9 | INV §2.2 helper-bag details: `isServerChunkFullyLoaded` (ticking chunk AND `areEntitiesLoaded`); `getDoesRegionFileExist` (`region/r.X.Z.mca` on-disk probe); `getWallBox` (`AABB::minmax` reduce of collision-shape bounds); height wrappers on 1.21.3 `getMinBuildHeight/getMaxBuildHeight/getMinSection/getMaxSection`; `getMaxContentYExclusive = logicalHeight()+minY`; `readTextResource` uses `Minecraft.getInstance()` in the common class; `invokeCommandAs` = `createCommandSourceStack().withPermission(2).withSuppressedOutput()` + `performPrefixedCommand`; `getPlayerLoadDistance` = `Mth.clamp(requestedViewDistance(), 2, serverVD)` | McHelper.java — all bodies read directly and matched (region probe, minmax reduce, heights, `withPermission(2)`, clamp :244). |
| 10 | INV §2.2: `performMultiThreadedFindingTaskOnServer` — `Util.backgroundExecutor()`, per-tick poll via `ServerTaskList.of(server).addTask`, watcher-false → `future.cancel(true)` | McHelper.java:144-201 (:168, :170, :191-196). |

### B. Wiring / lifecycle / mixins (IP source)

| # | Claim | Evidence |
|---|---|---|
| 11 | INV: `ClientWorldLoader.tick()` injected in `Minecraft.tick()` at `ClientLevel.tick(BooleanSupplier)` INVOKE, `shift=AFTER`; then StableClientTimer, `ClientPortalAnimationManagement.tick()`, `manageTeleportation(true)`, `POST_CLIENT_TICK_EVENT` at :139 | mixin/client/MixinMinecraft.java:119-127 (:124 `At.Shift.AFTER`), :128-141, :139; javadoc :86-103. |
| 12 | INV: cleanup at HEAD of `updateLevelInEngines`; `CLIENT_CLEANUP_EVENT` :163, `CLIENT_EXIT_EVENT` when null :167, then `cleanUp()`; `levelRenderer` `@Mutable` :48-51, reassigned via `ip_setWorldRenderer` :207-209; `useShaderTransparency` HEAD-cancellable :178-183 | MixinMinecraft.java:156-175, :48-51, :207-209, :178-183. |
| 13 | INV §4 (revised): `MixinMinecraft_RedirectedPacket` — `wrapRunnable` HEAD-cancellable wrap (:23-38); `scheduleExecutables()` override (:47-59) returns false while processing a redirected message on-thread; **`runningTask()` NOT overridden**, only invoked at :58 | mixin/client/sync/MixinMinecraft_RedirectedPacket.java:23-38, :49-59 (no runningTask override in the file — prior-pass R1 is fixed in INV). |
| 14 | INV §3.2: `PacketRedirectionClient.handleRedirectedPacket(int, ...)` — off-netty re-submit via `minecraft.execute`; int dim id ("dimension id map is only stable in client thread"); ThreadLocal set → `withSwitchedWorldFailSoft` → finally-restore | network/PacketRedirectionClient.java:32-33, :40-44, :45-77. |
| 15 | INV §3.2/§5: `DimIdSyncPacket.handle` stores `DimensionIntId.clientRecord` + builds immutable `dimIdToDimTypeId` (:121); registered via `ClientPlayNetworking.registerGlobalReceiver` (:135-140); map nulled on `CLIENT_EXIT_EVENT` (ClientWorldLoader.java:98-100); events built by `Helper.createRunnableEvent` :1424 / `createConsumerEvent` :1435 | q_misc_util/MiscNetworking.java:99-141; q_misc_util/Helper.java:1424, :1435. |
| 16 | INV: `allChanged` mixin — HEAD-cancel while `WorldRenderInfo.isRendering()`; TAIL → `_onWorldRendererReloaded()` unless creating a world; cascade guards re-entrancy + `PortalRendering.isRendering()` + creation, reloads other dims under `withSwitchedWorld` | mixin/client/render/MixinLevelRenderer.java:394-400, :403-414; ClientWorldLoader.java:503-538. |
| 17 | INV §2.7/§5: `IPPerServerInfo` `@Unique` field on MinecraftServer (~:16), duck `ip_getPerServerInfo` (~:27); `SERVER_CLEANUP_EVENT` invoker ~:23 (RETURN of `runServer`); the 5 documented fields | mixin/common/MixinMinecraftServer.java:15-29; IPPerServerInfo.java:13-25. |
| 18 | INV §2.8: `IPModMain.init()` order + only TWO task-list processor registrations (POST_CLIENT_TICK→CLIENT_TASK_LIST, PRE_GAME_RENDER→PRE_GAME_RENDER_TASK_LIST); `registerEntityTypes` = exactly the 10 documented id→ENTITY_TYPE pairs; `registerBlocks` = single `nether_portal_block` | IPModMain.java:62-127, :155-213 (all ids/statics match, incl. lower-case `LoadingIndicatorEntity.entityType`). |
| 19 | INV §2.9: `IPModMainClient.init()` — order as documented; stencil/framebuffer renderers constructed on render thread via `Minecraft.getInstance().execute` (:76-85) with `renderer = rendererUsingStencil` :84; `CLIENT_CLEANUP_EVENT → forceClearTasks` :130-132; `DimensionIntId.initClient` :134; NVIDIA/Quilt warnings as delayed one-shot tasks :38-69 | IPModMainClient.java:36-135 read in full. |
| 20 | INV §2.5/§2.6: IPGlobal knob defaults + lines (spot-checked `maxNormalPortalRadius=32` :19, `maxPortalLayer=5` :42, `indirectLoadingRadiusCap=8` :44, events/task-lists :26-40); IPCGlobal renderer singletons :16-20, `isClientRemoteTickingEnabled=true` :26 (gate verified at ClientWorldLoader.java:112), cleanup/exit events + javadoc semantics | IPGlobal.java:17-60; IPCGlobal.java:16-50. |
| 21 | INV §2.5: `PRE_TOTAL_RENDER_TASK_LIST` processed at MixinGameRenderer.java:76; `PRE_GAME_RENDER_EVENT` invoked at :93 | mixin/client/render/MixinGameRenderer.java:76, :93. |
| 22 | INV §2.10: `IPMixinPlugin.shouldApplyMixin` — porting_lib → skip `MixinRenderTarget`/`MixinMainTarget`; rest no-op | IPMixinPlugin.java:23-30. |
| 23 | INV §2.4: CHelper — all members + lines match (glGetError cap 100 :73; depth clamp gated by `enableClippingMechanism` :138-148; `printChat` → `gui.getChat().addMessage` :94; `openLinkConfirmScreen` uses `client.setScreen` + `Util.getPlatform().openUri(new URI(link))` :102-114; dimension icon fallback :156-191) | CHelper.java read in full. |

### C. 26.2 GONE verdicts (rename-hunted)

| # | Claim | Evidence |
|---|---|---|
| 24 | `LevelRenderer.tick()` GONE; destruction progress now on `ClientLevel`, consumed by LevelExtractor | grep `public void tick()` in LevelRenderer.java → zero; ClientLevel.java:178/:435/:808; renderer/extract/LevelExtractor.java:279, :324. |
| 25 | `Minecraft.renderBuffers` GONE; `GameRenderer.renderBuffers()` :184; LevelRenderer self-serves (:145) | grep `renderBuffers` in Minecraft.java → zero; GameRenderer.java:184; LevelRenderer.java:145. |
| 26 | `Minecraft.getProfiler()` GONE → thread-local `Profiler.get()` (pattern Minecraft.java:1768) | grep → only `metricsRecorder.getProfiler()`/`getProfilerPieChart()` (different receivers); Minecraft.java:1768 (also :1166, :1232). |
| 27 | `Minecraft.setScreen` GONE → `Gui.setScreen` :222 (field :74, getter :218), `setScreenAndShow` :2184, `gui` :290 | all `setScreen` uses in Minecraft.java are `this.gui.setScreen(...)`; Gui.java:74/:218/:222; Minecraft.java:290, :2184. (Off-thread-log nuance → R1.) |
| 28 | `Minecraft.useShaderTransparency()` GONE → `GameRenderState.useShaderTransparency()` :17-19; callers WeatherEffectRenderer.java:129, LevelRenderer.java:835 | grep in Minecraft.java → zero; state/GameRenderState.java:17; both caller cites exact. |
| 29 | `LightTexture` GONE → `Lightmap` (:25/:44/:52/:57) + `LightmapRenderStateExtractor` (:22/:30 `(GameRenderer, Minecraft)`/:35/:47) + `LightmapRenderState`; GameRenderer owns privately :112-113; getters return only `GpuTextureView` (:661/:665) — no Lightmap-object getter; driven tick :258 / extract :386 / render :423 | `find LightTexture.java` → none; grep `class LightTexture` → none; all cited lines exact. |
| 30 | Packet requeue GONE as mechanism → `PacketUtils.ensureRunningOnSameThread(Packet, T, PacketProcessor)` (:21-26, ServerLevel convenience :17-19, throw :24 after `scheduleIfPossible` :23); `PacketProcessor.scheduleIfPossible` :26, `processQueuedPackets` :34-40, `ListenerAndPacket.handle` → `packet.handle(listener)` :51; Minecraft field :369 / built :730 / drained :1170 / getter :2922; `wrapRunnable` :2668 never sees packets; `BlockableEventLoop.scheduleExecutables` = `!isSameThread()` :49-50; `ReentrantBlockableEventLoop` methods :11-16 intact | network/protocol/PacketUtils.java:17-26; network/PacketProcessor.java:26-62 (plain queue drain — no event-loop task bookkeeping, so `runningTask()` indeed unset); Minecraft.java:369/:730/:1170/:2668/:2922; util/thread/BlockableEventLoop.java:49-50; ReentrantBlockableEventLoop.java:11-16 (but see ctor omission note). |
| 31 | `Entity.lerpTo` 6-arg GONE → `getInterpolation()` :2550 (@Nullable), `InterpolationHandler.interpolateTo(Vec3,float,float)` :49, `cancel()` :111, active check :2522 | Entity.java:2522, :2550; InterpolationHandler.java:49, :111. |
| 32 | `Entity.createCommandSourceStack()` GONE (generic); only `createCommandSourceStackForNameResolution(ServerLevel)` :3616 on Entity; manual-construction pattern SignBlockEntity.java:216-222 with `LevelBasedPermissionSet.GAMEMASTER`; `withPermission(PermissionSet)` :261, `withSuppressedOutput` :240 | Entity.java:3616; SignBlockEntity.java:216-222 (verbatim); CommandSourceStack.java:240, :261. |
| 33 | `displayClientMessage` GONE repo-wide → `sendSystemMessage` :1783 / `sendOverlayMessage` :1788; `Entity.sendSystemMessage` gone (Player :1342 / ServerPlayer :1783 only) | `grep -rl displayClientMessage` over mc262-ref → zero files; ServerPlayer.java:1783/:1788; Player.java:1342; Entity.java: no match. |
| 34 | `GlUtil.getVendor()` GONE (class now only `selectBufferBindTarget`); vendor via `DeviceInfo.vendorName()` :10 (backendName :13) | com/mojang/blaze3d/opengl/GlUtil.java (full read); com/mojang/blaze3d/systems/DeviceInfo.java:10, :13. |
| 35 | Depth clamp: zero matches for `depthClamp\|DEPTH_CLAMP\|DepthClamp` in all of mc262-ref | repo-wide grep → zero. |

### D. 26.2 CHANGED/SAME verdicts (signatures re-checked against IP call sites)

| # | Claim | Evidence |
|---|---|---|
| 36 | 26.2 `ClientLevel` ctor `(ClientPacketListener, ClientLevelData, ResourceKey, Holder<DimensionType>, int, int, LevelExtractor, boolean, long, int seaLevel)` :238-249; **seaLevel gap**: only source is `CommonPlayerSpawnInfo.seaLevel()` (login :504, respawn :1259); `DimensionType` has no seaLevel | ClientLevel.java:238-249 (exact); ClientPacketListener.java:504, :507-518 (levelExtractor :514), :1259-1272; DimensionType.java grep `seaLevel` → zero (logicalHeight :37 intact); CommonPlayerSpawnInfo.java:25. The UNKNOWN-NEEDS-DESIGN protocol flag is sound. |
| 37 | 26.2 `LevelRenderer` 9-arg ctor :131; vanilla template Minecraft.java:638-648 + LevelExtractor :649 + reload listener :650 (but see R2 for :651); shared `LevelRenderState` captured :151; `LevelExtractor implements ResourceManagerReloadListener` :71, ctor :89, `setLevel` :393 (+`shouldResetLevelRenderData` :402 → `resetLevelRenderData()` :105-107), `onResourceManagerReload` :389, `allChanged` :406 (vanilla call Minecraft.java:1047); per-frame extract entry GameRenderer.java:389; `Minecraft.levelExtractor` :280; `ClientLevel.levelExtractor` :152 + dirty-forwarding :791-804 | LevelRenderer.java:131-152; Minecraft.java:638-651, :1047, :280; renderer/extract/LevelExtractor.java:71/:89/:105-107/:389/:393/:402/:406; GameRenderer.java:389; ClientLevel.java:152, :791-808. |
| 38 | `updateLevelInEngines` private 1-arg :2191-2193 → 2-arg :2195-2206 (body: cond. sound stop, `setCameraEntity(null)`, extractor/particles/gameRenderer setLevel :2202-2204); public `setLevel` :2059-2062 from handleLogin (ClientPacketListener.java:519); null paths :2146/:2177; `ParticleEngine.setLevel` public :131 (field :31); `Camera.setLevel` :491 via `GameRenderer.setLevel` :705→:710; `mainCamera()` :657; `Camera.position()` :359 / `setPosition` :349,:353 (protected) / `reset` :484; `GameRenderer.resetData` :642, `mainRenderTarget()` :673 (vanilla use Minecraft.java:660), `lightmap()`/`levelLightmap()` :661/:665 | All cites opened and exact. (`updateTitle()` :2205 omission noted above.) |
| 39 | Bulk SAME/CHANGED re-check (~45 rows sampled): `LevelHeightAccessor` **inclusivity flip** (`getMaxY()=getMinY()+getHeight()-1`; `getMaxSectionY()=blockToSectionCoord(getMaxY())` — both INCLUSIVE; `+1` corrections needed exactly as MAP warns); `ChunkPos.pack` :69/:73/:81 + getRegion :128-140; `Identifier.fromNamespaceAndPath` :40 / `parse` :44; `util.Util.backgroundExecutor()` → `TracingExecutor` :252; `ClickEvent` interface :19 + `OpenUrl(URI)` :126 + `RunCommand` :137; `ChatComponent.addMessage` private :255 / `addClientSystemMessage` :243 / `addServerSystemMessage` :247 / `Hud.getChat` :1264 / `Gui.hud` :72; `TrackedEntity` private class :1320 implements `ServerEntity.Synchronizer`, `sendToTrackingPlayers` :1345 / `sendToTrackingPlayersAndSelf` :1352; `RegistryAccess.lookupOrThrow` :21 / `HolderGetter.getOrThrow` :11 / `Registry.getValue` :66-68 / `getId` :64 / `keySet` :91 / `get(int)` :132 / `Registry extends HolderLookup.RegistryLookup` :25; `Attributes.SCALE` :89; `Entity.saveWithoutId(ValueOutput)` :2059 / `load(ValueInput)` :2133; `EntityType.create(Level, EntitySpawnReason)` :298 / `Builder.of` :479 / `build(ResourceKey)` :590; Minecraft anchors (level :335, player :336, gui :290, gameThread :348, getInstance :2517, extends `ReentrantBlockableEventLoop<Runnable>` :260, tick :1758, tickEntities :1797, `level.tick(() -> true)` :1819, particleEngine.tick :1840; `BlockableEventLoop.isSameThread` :43 / `execute` :98); ClientLevel anchors (mapData `Map<MapId,…>` :160 + copy-only helpers :992/:996 → accessor duck still required, tickRateManager :155, tick :299, tickEntities :453, pollLightUpdates :281, animateTick :560, entitiesForRendering :449, getServerSimulationDistance :1091, ClientLevelData :1157 + private isFlat :1151); ClientPacketListener (level :390, getLevel :2616, levels :2624, registryAccess :2628); server rows (ServerChunkCache.chunkMap :64; ChunkMap visibleChunkMap :128 / getVisibleChunkIfPresent :255 / entityMap :146 / getPlayerViewDistance :817; MinecraftServer getLevel :1189 / getPlayerList :1383 / getRunningThread :1488 / getCommands :1689 / storageSource :217; getDimensionPath :518; PlayerList.getViewDistance :687; ServerPlayer.requestedViewDistance :1878, field :257; Commands.performPrefixedCommand :312); entity-storage ducks (LevelEntityGetterAdapter sectionStorage :11 / ctor :13 / get(UUID) :24; `EntitySectionStorage.forEachAccessibleNonEmptySection(AABB, AbortableIterationConsumer<EntitySection<T>>)` :37; ServerLevel getAllEntities :1636 / areEntitiesLoaded :1759); ClipContext from/to private :22-23, getTo :40 / getFrom :44 | Every cited line opened and matched (only the ±1 drifts noted above). |
| 40 | MAP header counts "GONE 14 · CHANGED 26" and §4 fact sheet (swap-set constraints 1-8; remote-tick surface intact) | §1 = exactly 14 rows, §2 = exactly 26 rows; §4 facts each independently re-verified in rows 30, 37, 38; remote-tick surface (ClientLevel.java:281/:299/:453/:560 + Minecraft.java:1819 anchor) confirmed. |
| 41 | MAP: `LevelRenderer.close()` fate (unmapped — see R3): 26.2 `close()` :773 exists, body `resetLevelRenderData()` :774 (:878), class implements AutoCloseable :95 | LevelRenderer.java:95, :773-774, :878. |
| 42 | Prior-pass omission re-check: `ReentrantBlockableEventLoop` ctor `(String, boolean)` (IP mixin's 1-arg super won't compile) and `updateTitle()` in `updateLevelInEngines` — both still true, both still undocumented | ReentrantBlockableEventLoop.java:6-8; MixinMinecraft_RedirectedPacket.java:18-20; Minecraft.java:2205. |
