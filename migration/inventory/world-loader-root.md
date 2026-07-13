# Inventory: ClientWorldLoader + root helpers (`qouteall.imm_ptl.core.*` root files)

Slice scope: the 11 root files of `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/imm_ptl/core/`
(IP for MC 1.21.3, Mojang mappings). All `file:line` citations below are relative to that directory unless
a full package path is given. Verified by full reads on 2026-07-10.

---

## 1. Overview

This slice is the **root of IP's client multi-world architecture plus the shared utility/config layer that every
other IP subsystem imports**. `ClientWorldLoader` is the heart: on the client it maintains one *real*
`ClientLevel` + one *real* `LevelRenderer` + one `DimensionRenderHelper` (per-dimension lightmap) **per dimension
simultaneously** — there is no promote/demote of a single level; secondary dimensions are first-class
`ClientLevel` objects created lazily on demand, ticked every client tick, and rendered by their own
`LevelRenderer`. All vanilla code that assumes "the one `Minecraft.level`" is handled by a scoped context switch
(`withSwitchedWorld`) that temporarily repoints `Minecraft.level`, `Minecraft.levelRenderer`, the particle
engine's level, and the network handler's level, then restores them. Server→client packets for non-current
dimensions are wrapped by the network layer and re-dispatched *inside* that context switch, so vanilla packet
handlers mutate the correct `ClientLevel` without knowing IP exists.

The rest of the slice is infrastructure: `McHelper` (~965 LOC of vanilla-API helper methods — entity queries
without chunk loading, position/interpolation setters, chunk presence checks, codecs, server access),
`IPMcHelper` (portal-aware queries and cross-portal ray tracing), `CHelper` (client-only helpers), `IPGlobal` /
`IPCGlobal` (the global config-knob and event surface every subsystem reads), `IPPerServerInfo` (per-
`MinecraftServer` state container), `IPModMain` / `IPModMainClient` (init sequences + entity-type/block
registration), `IPMixinPlugin` (mixin filtering), and `ScaleUtils` (portal scaling via the vanilla `SCALE`
attribute).

---

## 2. Class-by-class inventory

### 2.1 `ClientWorldLoader` — client-only, ~634 LOC
`@Environment(EnvType.CLIENT)` (ClientWorldLoader.java:54).

**Responsibility:** owns the per-dimension client world/renderer/lightmap maps; creates secondary
`ClientLevel`s; ticks remote worlds; performs the world context switch; disposes everything on
disconnect/loading-screen travel; dynamically removes dimensions the server unregisters.

**State (all static):**
- `CLIENT_WORLD_MAP: Map<ResourceKey<Level>, ClientLevel>` (private) — ClientWorldLoader.java:65
- `WORLD_RENDERER_MAP: Map<ResourceKey<Level>, LevelRenderer>` (public) — :67
- `RENDER_HELPER_MAP: Map<ResourceKey<Level>, DimensionRenderHelper>` (public) — :69
- `dimIdToDimTypeId: @Nullable Map<ResourceKey<Level>, ResourceKey<DimensionType>>` (public) — :72; populated
  by the server's dim-id sync packet (`q_misc_util/MiscNetworking.java:121`), nulled on client exit (:98-100)
- flags: `isInitialized` :76, `isCreatingClientWorld` :78, `isClientRemoteTicking` (public) :80,
  `isWorldSwitched` :82, `isReloadingOtherWorldRenderers` :500
- events: `CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT: Event<Consumer<ResourceKey<Level>>>` :60,
  `CLIENT_WORLD_LOAD_EVENT: Event<Consumer<ClientLevel>>` :62 (Fabric array-backed events created by
  `q_misc_util/Helper.java:1424,1435`)
- `LOG_LIMIT = new CountDownInt(20)` :58 — rate-limits remote-tick exception logging

**Key public API:**
- `static void init()` :84 — registers dynamic-dimension-removal + exit-cleanup listeners
- `static boolean getIsInitialized()` :103; `static boolean getIsCreatingClientWorld()` :107;
  `static boolean getIsWorldSwitched()` :605
- `static void tick()` :111 — remote world/renderer ticking + lightmap conflict detection
- `static void cleanUp()` :208 — full teardown
- `static void disposeRenderHelpers()` :149
- `static @NotNull LevelRenderer getWorldRenderer(ResourceKey<Level>)` :277
- `static @NotNull ClientLevel getWorld(ResourceKey<Level>)` :308 — get-or-create; throws on invalid dim
- `static @Nullable ClientLevel getOptionalWorld(ResourceKey<Level>)` :328 — null on invalid dim
- `static DimensionRenderHelper getDimensionRenderHelper(ResourceKey<Level>)` :339
- `static void initializeIfNeeded()` :357
- `static Set<ResourceKey<Level>> getServerDimensions()` :489 — `CLIENT.player.connection.levels()` :491
- `static Collection<ClientLevel> getClientWorlds()` :494
- `static void _onWorldRendererReloaded()` :503 — cascade `allChanged()` to other renderers
- `static <T> T withSwitchedWorld(ClientLevel, Supplier<T>)` :544; `static void withSwitchedWorld(ClientLevel, Runnable)` :585;
  `static void withSwitchedWorldFailSoft(ResourceKey<Level>, Runnable)` :592
- `RemoteCallables.checkBiomeRegistry(Map<String,Integer>)` :610 — server-invoked biome-id validation

**IP dependencies:** ducks `IECamera, IEClientPlayNetworkHandler, IEClientWorld, IEMinecraftClient,
IEParticleManager, IEWorld, IEWorldRenderer`; accessors `IEClientLevelData, IEClientLevel_Accessor`;
`portal.Portal`; `render.context_management.DimensionRenderHelper`, `render.context_management.PortalRendering`;
`q_misc_util.Helper`, `q_misc_util.my_util.CountDownInt`; external **DimLib** `qouteall.dimlib.api.DimensionAPI`
(:29, :85 — `CLIENT_DIMENSION_UPDATE_EVENT` delivers the current server dimension collection).

**Vanilla classes touched:** `Minecraft` (level/levelRenderer/particleEngine/gameRenderer fields, `isPaused`,
`isSameThread`, `getProfiler`, `getEntityRenderDispatcher`, `getBlockEntityRenderDispatcher`, `renderBuffers`,
`getResourceManager`, `getConnection`), `ClientLevel` (construction + tick methods + accessors),
`ClientLevel.ClientLevelData` (construction), `LevelRenderer` (construction, `setLevel`,
`onResourceManagerReload`, `tick`, `close`, `allChanged`), `ClientPacketListener` (`registryAccess`, `levels`,
`getLevel`), `Camera` (via duck), `RegistryAccess`/`Registry`/`Registries`, `Holder<DimensionType>`,
`MapItemSavedData`, `LocalPlayer`.

### 2.2 `McHelper` — common, ~965 LOC
Static helper bag ("mc related helper methods", McHelper.java:80). Grouped signatures:

**ResourceLocation:** `newResourceLocation(String, String)` :89 (`ResourceLocation.fromNamespaceAndPath`),
`newResourceLocation(String)` :93 (`ResourceLocation.parse`).

**Server access (several `@Deprecated` because they use the `MiscHelper.getServer()` singleton):**
- `@Deprecated IEChunkMap getIEChunkMap(ResourceKey<Level>)` :98 — casts `ServerChunkCache.chunkMap`
- `@Deprecated List<ServerPlayer> getRawPlayerList()` :105
- `@Deprecated ServerLevel getOverWorldOnServer()` :114; `long getServerGameTime()` :126
- `@Deprecated ServerLevel getServerWorld(ResourceKey<Level>)` :846;
  `@NotNull ServerLevel getServerWorld(MinecraftServer, ResourceKey<Level>)` :850 — throws on missing dim

**Entity position / interpolation control (used heavily by teleportation):**
- `Vec3 lastTickPosOf(Entity)` :109 — reads `entity.xo/yo/zo`
- `void setPosAndLastTickPos(Entity, Vec3 pos, Vec3 lastTickPos)` :247 — `setPosRaw` + writes both
  `xOld/yOld/zOld` **and** `xo/yo/zo` (:252-258)
- `void setPosAndLastTickPosWithoutTriggeringCallback(...)` :261 — via duck `IEEntity.ip_setPositionWithoutTriggeringCallback`
- `Vec3 getEyePos(Entity)` :275 / `getLastTickEyePos(Entity)` :280 / `setEyePos(Entity, Vec3, Vec3)` :285 —
  all gravity-mod-aware via `GravityChangerInterface.invoker.getEyeOffset` (:276)
- `void updateBoundingBox(Entity)` :373 — `setPos(getX(),getY(),getZ())`; `void updatePosition(Entity, Vec3)` :377
- `AABB getBoundingBoxWithMovedPosition(Entity, Vec3)` :888
- `Vec3 getVehicleOffsetFromPassenger(Entity vehicle, Entity passenger)` :299 — returns
  `passenger.getVehicleAttachmentPoint(vehicle)` (vanilla-copy of `Entity#positionRider`, TODO for non-default gravity :297)
- `void adjustVehicle(Entity)` :305 — repositions vehicle at passenger; calls `vehicle.setPos` + 6-arg
  `vehicle.lerpTo(x, y, z, yRot, xRot, 0)` (:321-324) to kill interpolation, then restores velocity (:330)

**Entity queries (section-storage based, never load chunks):**
- `<T> List<T> findEntities(Class<T>, LevelEntityGetter<Entity>, 6×int chunk bounds, Predicate<T>)` :509
- `<T,R> @Nullable R traverseEntities(Class<T>, LevelEntityGetter<Entity>, 6×int, Function<T,R>)` :535 — the
  core primitive; asserts ranges (<1000 chunks, :545-546), builds `EntityTypeTest.forClass` (:548), pulls the
  `EntitySectionStorage` cache via duck `IELevelEntityGetterAdapter.getCache()` (:550-551), traverses via ducks
  `IESectionedEntityCache.ip_traverseSectionInBox` / `IEEntityTrackingSection.ip_traverse` (:553-562)
- `<T> void foreachEntities(...)` :591 (javadoc: like `EntitySectionStorage#forEachAccessibleNonEmptySection`
  but without hardcoded max entity radius, :586-590)
- `<T> List<T> findEntitiesRough(Class<T>, Level, Vec3 center, int radiusChunks, Predicate<T>)` :610 — radius
  clamped to [1,32] chunks (:618-624); gets lookup via duck `IEWorld.portal_getEntityLookup()` (:630)
- `<T> List<T> findEntitiesByBox(Class<T>, Level, AABB, double maxEntityRadius, Predicate<T>)` :642
- `<T> void foreachEntitiesByBox(...)` :655; `foreachEntitiesByBoxApproximateRegions(...)` :667 (box expanded
  by maxEntityRadius then `>>4` to sections, :670-684)
- `<E,R> R traverseEntitiesByApproximateRegion(...)` :687; `<E,R> R traverseEntitiesByBox(...)` :565
- `<T> void foreachEntitiesByPointAndRoughRadius(Class<T>, Level, Vec3, int, Consumer<T>)` :707;
  `traverseEntitiesByPointAndRoughRadius(...)` :720
- `<ENTITY> List<ENTITY> getEntitiesNearby(Level, Vec3, Class, double range)` :204 (rough, range/16+1 chunks);
  `getEntitiesNearby(Entity, Class, double)` :219
- `<T> List<T> getEntitiesRegardingLargeEntities(Level, AABB, double, Class<T>, Predicate<T>)` :381
- `@Deprecated Stream<ENTITY> getServerEntitiesNearbyWithoutLoadingChunk(...)` :358
- `@Nullable Entity getEntityByUUID(Level, UUID)` :937 — `portal_getEntityLookup().get(uuid)`
- `Iterable<Entity> getWorldEntityList(Level)` :818 — client: `CHelper.getWorldEntityList`; server:
  `ServerLevel.getAllEntities()`

**Chunk access without loading:**
- `LevelChunk getServerChunkIfPresent(ResourceKey<Level>, int x, int z)` :334 and
  `(ServerLevel, int, int)` :345 — duck `IEChunkMap.ip_getChunkHolder(ChunkPos.asLong(x,z))` then
  `ChunkHolder.getTickingChunk()` (:342, :354); returns null if absent
- `boolean isServerChunkFullyLoaded(ServerLevel, ChunkPos)` :482 — ticking chunk present **and**
  `world.areEntitiesLoaded(chunkPos.toLong())` (:491)
- `interface ChunkAccessor { LevelChunk getChunk(int x, int z); }` :496; `getChunkAccessor(Level)` :500 —
  client: `world::getChunk`; server: if-present accessor
- `boolean getDoesRegionFileExist(ResourceKey<Level>, BlockPos)` :412 — probes
  `server.storageSource.getDimensionPath(dim)/region/r.X.Z.mca` on disk (:415-420); javadoc: MC has no clean
  chunk-exists API (:407-411)

**View distances:** `int getLoadDistanceOnServer(MinecraftServer)` :232
(`getPlayerList().getViewDistance()`); `@IPVanillaCopy int getPlayerLoadDistance(ServerPlayer)` :241 — copy of
`ChunkMap#getPlayerViewDistance`: `Mth.clamp(player.requestedViewDistance(), 2, serverViewDistance)` (:244).

**Entity tracking / networking:**
- `void resendSpawnPacketToTrackers(Entity)` :446 — duck `IEChunkMap.ip_resendSpawnPacketToTrackers`
- `void sendToTrackers(Entity, Packet<?>)` :450 — `IEChunkMap.ip_getEntityTrackerMap().get(entity.getId())`
  then `ChunkMap.TrackedEntity.broadcastAndSend(packet)` (:458); silently returns if tracker missing (:453-456)
- `void spawnServerEntity(Entity)` :835 — `level().addFreshEntity(entity)`; logs error if it returns false;
  javadoc "It will spawn even if the chunk is not loaded" (:832-834)
- `Portal copyEntity(Portal)` :398 — `portal.getType().create(portal.level())` + round-trip through
  `saveWithoutId(new CompoundTag())` / `load(...)` (:399-404)

**Async find + server tasks:**
- `<T> void performMultiThreadedFindingTaskOnServer(MinecraftServer, Stream<T>, Predicate<T>, IntPredicate
  taskWatcher, Consumer<T> onFound, Runnable onNotFound, Runnable finalizer)` :130 — runs the stream search on
  `Util.backgroundExecutor()` (:168), polls completion each server tick via `ServerTaskList.of(server).addTask`
  (:170); taskWatcher aborts by returning false (cancels future, :191-197). Used by nether portal matching.
- `void sendMessageToFirstLoggedPlayer(MinecraftServer, Component)` :800 — task-list poll until a player logs in
- `void invokeCommandAs(Entity, List<String>)` :435 — `createCommandSourceStack().withPermission(2)
  .withSuppressedOutput()` + `server.getCommands().performPrefixedCommand` (:436-443)
- `void validateOnServerThread()` :431 — compares `Thread.currentThread()` to `server.getRunningThread()`
- `void serverLog(ServerPlayer, String)` :118

**World geometry / heights:**
- `@Nullable AABB getWallBox(Level, IntBox)` :463 / `(Level, Stream<BlockPos>)` :469 — union of block collision
  shape bounds via `getCollisionShape(world, pos).bounds().move(...)` reduced by `AABB::minmax` (:470-478)
- `int getMinY(LevelAccessor)` :864 (`getMinBuildHeight`), `getMaxYExclusive` :868 (`getMaxBuildHeight`),
  `getMaxContentYExclusive` :872 (`dimensionType().logicalHeight() + minY`), `getMinSectionY` :876
  (`getMinSection`), `getMaxSectionYExclusive` :880 (`getMaxSection`), `getYSectionNumber` :884

**Codec/serialization:** `serializeToJson(T, Codec<T>)` :747, `decodeFailHard(Codec, DynamicOps, Serialized)`
:760, `getElementFailHard(DynamicOps, Serialized, String)` :771, `encode(...)` :781,
`decodeElementFailHard(...)` :790, `MyDecodeException` :753.

**Text/UI:** `MutableComponent getLinkText(String)` :423 (`ClickEvent.Action.OPEN_URL` + underline),
`Component compoundTagToTextSorted(CompoundTag, String, int)` :860 (delegates to `MyNbtTextFormatter`),
`Component getDimensionName(ResourceKey<Level>)` :946 (translation key `dimension.<ns>.<path>`, falls back to
"a dimension of <mod name>" via `O_O.getModName`, :946-964), `ResourceLocation dimensionTypeId(ResourceKey<Level>)` :743.

**Gravity / quaternion pass-throughs:** `getWorldVelocity`/`setWorldVelocity`/`getEyeOffset` :912-922
(GravityChanger); `getAxisWFromOrientation`/`getAxisHFromOrientation`/`getNormalFromOrientation` :924-934
(`DQuaternion`).

**Resources:** `String readTextResource(ResourceLocation)` :896 — **uses `Minecraft.getInstance()` inside the
common class** (:900); only safe to call on client.

**IP dependencies:** ducks (`IEChunkMap, IEEntity, IEEntityTrackingSection, IESectionedEntityCache, IEWorld`),
`mc_utils.MyNbtTextFormatter`, `mc_utils.ServerTaskList`, `miscellaneous.IPVanillaCopy`,
`mixin.common.mc_util.IELevelEntityGetterAdapter`, `platform_specific.O_O`, `portal.Portal`,
`compat.GravityChangerInterface`, `q_misc_util` (Helper, MiscHelper, DQuaternion, IntBox).

### 2.3 `IPMcHelper` — common (one client-only method), ~332 LOC
Portal-aware helpers layered over `McHelper`.

- `void foreachNearbyPortals(Level, Vec3, int range, Consumer<Portal>)` :40 — global portals from
  `GlobalPortalStorage.getGlobalPortals(world)` filtered by `getDistanceToNearestPointInPortal(pos) < range*2`
  (:43-49), then entity portals via `McHelper.foreachEntitiesByPointAndRoughRadius(Portal.class, ...)` (:51-53)
- `List<Portal> getNearbyPortalList(Entity, double, Predicate<Portal>)` :57 /
  `(Level, Vec3, double, Predicate)` :64; `Stream<Portal> getNearbyPortals(Entity, double)` :77 /
  `(Level, Vec3, double)` :82; `void traverseNearbyPortals(Level, Vec3, int, Consumer<Portal>)` :86 —
  all include global portals
- `void onClientEntityTick(Entity)` :106 — forwards to `CrossPortalEntityRenderer.onEntityTickClient`
  (comment: "avoid dedicated server crash" :105)
- `List<Tuple<Portal,Vec3>> rayTracePortals(Level, Vec3 start, Vec3 end, boolean includeGlobalPortals,
  Predicate<Portal> filter)` :122 — searches the line midpoint with chunk radius `ceil(dist/2/16)` (:133-138),
  intersects via `portal.rayTrace(start, end)` (:150), sorts by squared distance from start (:158-165)
- `Tuple<BlockHitResult,List<Portal>> rayTrace(Level, ClipContext, boolean includeGlobalPortals)` :274 —
  recursive cross-portal ray trace (private overload :194): beyond `IPGlobal.maxPortalLayer` returns a
  synthesized `BlockHitResult.miss` whose face is the `Direction` maximizing `getUnitVec3().dot(diff)`
  (:204-217); otherwise `world.clip(context)` (:220), takes the nearest interactable portal
  (`Portal::isInteractable` filter :223), and if the portal is closer than the block hit, mutates the
  `ClipContext` start/end through `portal.transformPoint` via duck `IERayTraceContext.ip_setStart/ip_setEnd`
  (:240-244), recurses in `portal.getDestinationWorld()` under `withSwitchedContext` (:247-251), restores the
  context afterwards (:253-255)
- `<T> T withSwitchedContext(Level, Supplier<T>)` :181 — client side: `ClientWorldLoader.withSwitchedWorld`;
  server side: runs directly (:182-187)
- `boolean hitResultIsMissedOrNull(HitResult)` :287
- `MutableComponent getTextWithCommand(MutableComponent, String)` :291 (`ClickEvent.Action.RUN_COMMAND`);
  `Component getDisableWarningText(String)` :303; `Component getDisableUpdateCheckText()` :310
- `@Environment(EnvType.CLIENT) boolean isNvidiaVideocard()` :317 — `GlUtil.getVendor()` contains "nvidia"
- `FriendlyByteBuf bytesToBuf(byte[])` :322 (`Unpooled.wrappedBuffer`); `byte[] bufToBytes(FriendlyByteBuf)` :327
- `public static final LimitedLogger limitedLogger = new LimitedLogger(20)` :37

**IP dependencies:** `ClientWorldLoader`, `IPGlobal`, `McHelper`, duck `IERayTraceContext`, `portal.Portal`,
`portal.global_portals.GlobalPortalStorage`, `render.CrossPortalEntityRenderer`, `q_misc_util.my_util.LimitedLogger`.

### 2.4 `CHelper` — client-only, ~193 LOC
`@Environment(EnvType.CLIENT)` (CHelper.java:38).

- `PlayerInfo getClientPlayerListEntry()` :45 — `getConnection().getPlayerInfo(player.getGameProfile().getId())`
- `Level getClientWorld(ResourceKey<Level>)` :51 — delegates to `ClientWorldLoader.getWorld`
- `@Nullable List<Portal> getClientGlobalPortal(Level)` :56 — duck `IEClientWorld.ip_getGlobalPortals()`
- `Stream<Portal> getClientNearbyPortals(double range)` :65 — `IPMcHelper.getNearbyPortals(player, range)`
- `void checkGlError()` :69 — gated by `IPGlobal.doCheckGlError`, stops after 100 reports (:73);
  `void doCheckGlError()` :79 — `GL11.glGetError()` + stack trace
- `void printChat(String)` :88 / `printChat(Component)` :93 — `gui.getChat().addMessage`
- `void openLinkConfirmScreen(Screen parent, String link)` :97 — `ConfirmLinkScreen` +
  `Util.getPlatform().openUri(new URI(link))`
- `Vec3 getCurrentCameraPos()` :118 — `gameRenderer.getMainCamera().getPosition()`
- `Iterable<Entity> getWorldEntityList(Level)` :122 — `ClientLevel.entitiesForRendering()` (:128)
- `double getSmoothCycles(long unitTicks)` :131 — `(StableClientTimer.getStableTickTime() % unitTicks +
  getStablePartialTicks()) / unitTicks`
- `void disableDepthClamp()` :138 / `enableDepthClamp()` :144 — raw `GL11.glDisable/glEnable(GL32.GL_DEPTH_CLAMP)`
  gated by `IPGlobal.enableClippingMechanism`
- `@Nullable ResourceLocation getDimensionIconPath(ResourceKey<Level>)` :156 — tries
  `<ns>:textures/dimension/<path>.png` via `getResourceManager().getResource`, falls back to the mod icon via
  `O_O.getModIconLocation` (:155-191)

**IP dependencies:** `ClientWorldLoader`, `IPMcHelper`, `IPGlobal`, `McHelper`, duck `IEClientWorld`,
`platform_specific.O_O`, `portal.Portal`, `portal.animation.StableClientTimer`, `q_misc_util.Helper`.

### 2.5 `IPGlobal` — common, ~173 LOC. THE config-knob + event surface
All fields `public static`, read directly by every subsystem. Config file values are copied onto these statics by
`platform_specific/IPConfig.onConfigChanged()` (IPConfig.java:157-197+), registered as a save listener and run
once at startup by `IPModMain.loadConfig()` (IPModMain.java:146-152). Fields NOT set from config keep their
hardcoded defaults.

**Events / task lists:**
- `configHolder: ConfigHolder<IPConfig>` :17 (AutoConfig/Cloth)
- `POST_CLIENT_TICK_EVENT: Event<Runnable>` :26 — fires right after client-world tick, *earlier* than Fabric's
  `END_CLIENT_TICK` (:22-25); invoked at mixin/client/MixinMinecraft.java:139
- `PRE_GAME_RENDER_EVENT: Event<Runnable>` :28 — invoked at mixin/client/render/MixinGameRenderer.java:93
- `CLIENT_TASK_LIST: MyTaskList` :31 — processed on POST_CLIENT_TICK (registered IPModMain.java:71); force-
  cleared at loading screens (IPModMainClient.java:130-132)
- `PRE_GAME_RENDER_TASK_LIST: MyTaskList` :34 — processed on PRE_GAME_RENDER (registered IPModMain.java:73); never cleared
- `PRE_TOTAL_RENDER_TASK_LIST: MyTaskList` :35 — processed at MixinGameRenderer.java:76
- `SERVER_CLEANUP_EVENT: Event<Consumer<MinecraftServer>>` :37 — invoked at mixin/common/MixinMinecraftServer.java:23
- `gson: Gson` :40 (= `MiscHelper.gson`)

**Knobs (name : default : line):**
`maxNormalPortalRadius=32` :19 · `maxPortalLayer=5` :42 · `indirectLoadingRadiusCap=8` :44 ·
`lagAttackProof=true` :46 · `renderMode=RenderMode.normal` :48 · `doCheckGlError=true` :50 ·
`renderYourselfInPortal=true` :52 · `activeLoading=true` :54 · `netherPortalFindingRadius=128` :56 ·
`teleportationDebugEnabled=false` :58 · `correctCrossPortalEntityRendering=true` :60 ·
`disableTeleportation=false` :62 · `looseMovementCheck=false` :64 · `pureMirror=false` :66 ·
`portalRenderLimit=200` :68 · `cacheGlBuffer=true` :70 · `reducedPortalRendering=false` :72 ·
`useSecondaryEntityVertexConsumer=true` :74 · `cullSectionsBehind=true` :76 · `offsetOcclusionQuery=true` :78 ·
`cloudOptimization=true` :80 · `crossPortalCollision=true` :82 · `netherPortalOverlay=false` :84 ·
`debugDisableFog=false` :86 · `scaleLimit=30` :88 · `easeCreativePermission=true` :90 ·
`easeCommandStickPermission=true` :91 · `enableDepthClampForPortalRendering=true` :93 ·
`enableServerCollision=true` :95 · `enableSharedBlockMeshBuffers=true` :97 · `saveMemoryInBufferPack=true` :99 ·
`enableDatapackPortalGen=true` :101 · `enableCrossPortalView=true` :103 · `enableClippingMechanism=true` :105 ·
`enableWarning=true` :107 · `enableMirrorCreation=true` :109 · `lightVanillaNetherPortalWhenCrouching=true` :111 ·
`enableNetherPortalEffect=true` :113 · `tickOnlyIfChunkLoaded=true` :115 ·
`allowClientEntityPosInterpolation=true` :117 · `alwaysOverrideTerrainSetup=false` :119 ·
`viewBobbingReduce=true` :121 · `enableClientPerformanceAdjustment=true` :123 ·
`enableServerPerformanceAdjustment=true` :124 · `enableCrossPortalSound=true` :126 ·
`checkModInfoFromInternet=true` :128 · `enableUpdateNotification=true` :130 ·
`logClientPlayerCollidingPortalUpdate=false` :132 · `chunkPacketDebug=false` :134 ·
`entityUntrackDebug=false` :136 · `entityTrackDebug=false` :137 · `clientPortalLoadDebug=false` :138 ·
`debugRenderPortalShapeMesh=false` :140 · `moveDebugTextToTop=false` :143 · `boxPortalSpecialIteration=true` :145

**Enums:** `RenderMode {normal, compatibility, debug, none}` :147; `NetherPortalMode {normal, vanilla, adaptive,
disabled}` :155 (default `adaptive` :170); `EndPortalMode {normal, toObsidianPlatform, scaledView,
scaledViewRotating, vanilla}` :162 (default `normal` :172).

### 2.6 `IPCGlobal` — client-only, ~52 LOC
`@Environment(EnvType.CLIENT)` (IPCGlobal.java:13).

- Renderer singletons: `renderer: PortalRenderer` :16 (the active one), `rendererUsingStencil` :17,
  `rendererUsingFrameBuffer` :18, `rendererDummy = new RendererDummy()` :19, `rendererDebug = new RendererDebug()` :20.
  Stencil/framebuffer instances are constructed on the render thread in IPModMainClient.java:81-84 and
  `renderer` defaults to `rendererUsingStencil`.
- Knobs: `maxIdleChunkRendererNum=500` :22 · `doUseAdvancedFrustumCulling=true` :24 ·
  `useHackedChunkRenderDispatcher=true` :25 · **`isClientRemoteTickingEnabled=true`** :26 (gates
  `ClientWorldLoader.tick`'s remote-world ticking) · `useFrontClipping=true` :27 ·
  `doDisableAlphaTestWhenRenderingFrameBuffer=true` :28 · `lateClientLightUpdate=true` :29 ·
  `earlyRemoteUpload=true` :30 · `useSuperAdvancedFrustumCulling=true` :32 · `earlyFrustumCullingPortal=true` :33 ·
  `useSeparatedStencilFormat=false` :35 · `experimentalIrisPortalRenderer=false` :37 ·
  `debugEnableStencilWithIris=false` :39
- Events: `CLIENT_CLEANUP_EVENT: Event<Runnable>` :44 — "client exits world or doing conventional dimension
  travel (with loading screen)" (:41-43); `CLIENT_EXIT_EVENT: Event<Runnable>` :50 — world exit only (:47-49).
  Both fired from mixin/client/MixinMinecraft.java:163,167 (HEAD of `Minecraft.updateLevelInEngines`).

### 2.7 `IPPerServerInfo` — server-side (per `MinecraftServer`), ~26 LOC
Container attached to every `MinecraftServer` instance by `mixin/common/MixinMinecraftServer.java:16`
(`IPPerServerInfo ipPerServerInfo = new IPPerServerInfo();` as an added field) and retrieved via
`IPPerServerInfo.of(server)` :23 → duck `IEMinecraftServer.ip_getPerServerInfo()`.

Fields: `taskList: MyTaskList` :13 · `dimIntIdMap: @Nullable DimIntIdMap` :15 (integer dim ids for packet
redirection) · `customPortalGenManager: @Nullable CustomPortalGenManager` :17 ·
`teleportationManager = new ServerTeleportationManager()` :19 ·
`portalWandInteraction = new PortalWandInteraction()` :21 (peripheral module).

### 2.8 `IPModMain` — common entry, ~214 LOC
Called from `platform_specific/IPModEntry.java:15` (the Fabric `ModInitializer`).

- `init()` :62 — order: `loadConfig()` → `ImmPtlNetworking.init()` / `ImmPtlNetworkConfig.init()` /
  `PacketRedirection.init()` (:67-69) → registers task-list processors (:71-73) → portal shape statics
  `RectangularPortalShape/SpecialFlatPortalShape/BoxPortalShape.init()` (:75-77) → `ImmPtlChunkTracking` :79,
  `WorldInfoSender` :81, `GlobalPortalStorage` :83, `EntitySync` :85, `ServerTeleportationManager` :87,
  `CollisionHelper` :89, `PortalExtension` :91, `GcMonitor.initCommon` :93, `ServerPerformanceMonitor` :95,
  `ImmPtlChunkTickets` :97, `IPPortingLibCompat` :99, `BlockManipulationServer` :101 →
  `CommandRegistrationCallback.EVENT` for `PortalCommand` (:103-105) + argument types (:106-108) →
  `DebugUtil` :110, `ServerTaskList` :112, `CustomPortalGenManager` :114 → animation driver types
  `RotationAnimation/NormalAnimation.init()` (:117-118) → jar-in-jar notice via
  `IPFeatureControl.enableVanillaBehaviorChangingByDefault()` (:121-127)
- `loadConfig()` :130 — renames legacy `immersive_portals_fabric.json` → `immersive_portals.json` (:132-143);
  `AutoConfig.register(IPConfig.class, GsonConfigSerializer::new)` (:146); save listener calls
  `ipConfig.onConfigChanged()` returning `InteractionResult.SUCCESS` (:147-150); applies once at startup (:151-152)
- `registerBlocks(BiConsumer<ResourceLocation, PortalPlaceholderBlock>)` :155 — one block:
  `immersive_portals:nether_portal_block` → `PortalPlaceholderBlock.instance`
- `registerEntityTypes(BiConsumer<ResourceLocation, EntityType<?>>)` :162 — **the complete portal entity-type
  list** (id → static ENTITY_TYPE field): `immersive_portals:portal`→`Portal.ENTITY_TYPE` :164-167,
  `nether_portal_new`→`NetherPortalEntity` :169-172, `end_portal`→`EndPortalEntity` :174-177,
  `mirror`→`Mirror` :179-182, `breakable_mirror`→`BreakableMirror` :184-187,
  `global_tracked_portal`→`GlobalTrackedPortal` :189-192, `border_portal`→`WorldWrappingPortal` :194-197,
  `end_floor_portal`→`VerticalConnectingPortal` :199-202, `general_breakable_portal`→`GeneralBreakablePortal`
  :204-207, `loading_indicator`→`LoadingIndicatorEntity.entityType` :209-212

### 2.9 `IPModMainClient` — client entry, ~137 LOC
Called from `platform_specific/IPModEntryClient.java:67` (the Fabric `ClientModInitializer`).

- `init()` :71 — order: `ClientWorldLoader.init()` :72 → `ClientTeleportationManager.init()` :74 →
  **deferred to render thread** via `Minecraft.getInstance().execute` (:76-85): `ShaderCodeTransformation.init`,
  `MyRenderHelper.init`, construct `rendererUsingStencil`/`rendererUsingFrameBuffer`, set
  `IPCGlobal.renderer = rendererUsingStencil` → `DubiousThings` :87, `CrossPortalEntityRenderer` :89,
  `GLResourceCache` :91, `CollisionHelper.initClient` :93, `PortalRenderInfo` :95, `CloudContext` :97,
  `SharedBlockMeshBuffers` :99, `GcMonitor.initClient` :101 → `ClientCommandRegistrationCallback` for
  `ClientDebugCommand` (:103-105) → NVIDIA-without-Sodium chat warning (:38-53, uses
  `IPMcHelper.isNvidiaVideocard` + `SodiumInterface`) and Quilt warning (:55-69), both as delayed one-shot
  tasks on `CLIENT_TASK_LIST` gated on `Minecraft.level != null` → `StableClientTimer` :113,
  `ClientPortalAnimationManagement` :115, `VisibleSectionDiscovery` :117, `ImmPtlViewArea` :119,
  `IPFlywheelCompat` :121, `GuiPortalRendering._init` :123, `ImmPtlNetworking.initClient` :125,
  `ImmPtlNetworkConfig.initClient` :126, `ForceMainThreadRebuild` :128 → registers
  `CLIENT_CLEANUP_EVENT → IPGlobal.CLIENT_TASK_LIST.forceClearTasks()` (:130-132) →
  `DimensionIntId.initClient` :134

### 2.10 `IPMixinPlugin` — mixin config plugin, ~51 LOC
Implements `IMixinConfigPlugin` (IPMixinPlugin.java:11). Only non-trivial member:
`shouldApplyMixin` :23 — when Forge Porting Lib is loaded (`FabricLoader.getInstance().isModLoaded("porting_lib")`),
skips any mixin whose class name contains `MixinRenderTarget` or `MixinMainTarget` (:24-29). Everything else
is a no-op.

### 2.11 `ScaleUtils` — common (one client-only method), ~188 LOC
Portal scale changes implemented as a **vanilla `Attributes.SCALE` attribute modifier** with fixed id
`iportal:scaling` (`IPORTAL_SCALING`, ScaleUtils.java:26-27).

- `@Environment(EnvType.CLIENT) void onClientPlayerTeleported(Portal)` :30 — if
  `portal.hasScaling() && portal.isTeleportChangesScale()`: scales the local player (:38) and multiplies the
  camera's smoothed Y offsets via duck `IECamera.ip_setCameraY(cameraY*scaling, lastCameraY*scaling)` (:40-44).
  Called from teleportation/ClientTeleportationManager.java:370.
- `void onServerEntityTeleported(Entity, Portal)` :48 — scales entity and, if present, its vehicle (:52-54).
  Called from teleportation/ServerTeleportationManager.java:204 and :579.
- `@Nullable AttributeInstance getScaleAttr(Entity)` :58 — `LivingEntity.getAttributes().getInstance(Attributes.SCALE)`
- `double getScale(Entity)` :65 (`LivingEntity.getScale()`, 1.0 for non-living);
  `double getBaseScale(Entity)` :72; `double getIPortalScaling(Entity)` :80 — `modifier.amount() + 1.0` (:94)
- `void setIPortalScaling(Entity, double)` :97 — |scale-1|<0.0001 → `removeModifier(IPORTAL_SCALING)`; else
  `addOrReplacePermanentModifier(new AttributeModifier(IPORTAL_SCALING, newScale-1.0,
  Operation.ADD_MULTIPLIED_TOTAL))` (:115-119); **always calls `entity.refreshDimensions()`** to update the
  cached eyeHeight (:107, :122)
- `void setBaseScale(Entity, double)` :125
- `computeThirdPersonScale` :135 / `computeBlockReachScale` :139 / `computeMotionScale` :143 — all currently
  `getScale(entity)` (hook points used by mixins elsewhere)
- private `doScalingForEntity(Entity, Portal)` :147 — captures eye pos + last-tick eye pos BEFORE the scale
  change, applies new scale, then restores eye pos + bounding box (:148-170), so the entity scales around its
  eyes, not its feet. Server side rejects illegal scale (→1 + "Scale out of range" message, :154-159)
- private `transformScale(Portal, double)` :173 — `oldScale * portal.getScaling()`, snapped to 1 within 1e-4
  to avoid accumulation (:176-181); private `isScaleIllegal(double)` :184 — `> IPGlobal.scaleLimit` or
  `< 1/(scaleLimit*2)`

---

## 3. Mechanisms

### 3.1 Client world lifecycle (ClientWorldLoader)

**Lazy initialization.** Nothing happens at login until some code asks for a world/renderer.
`initializeIfNeeded()` (ClientWorldLoader.java:357-386) validates `CLIENT.level`, `CLIENT.levelRenderer`,
`CLIENT.player` non-null and `player.level() == CLIENT.level`, then seeds all three maps with the *vanilla*
current level, renderer, and a new `DimensionRenderHelper(CLIENT.level)` under the player's dimension key
(:376-382). So the current dimension's entries are always the vanilla objects, never copies.

**Secondary world creation** (`createSecondaryClientWorld`, :389-487):
1. Validates dim ∈ `getServerDimensions()` (= `CLIENT.player.connection.levels()`, :489-492), else throws (:393-396).
2. Sets `isCreatingClientWorld = true` + profiler push `create_world` (:398-400).
3. `chunkLoadDistance = 3` — deliberately tiny: "my own chunk manager doesn't need it" (:402); IP's client
   chunk map is replaced elsewhere to be unbounded (not array-based).
4. Constructs a **new vanilla `LevelRenderer`**: `new LevelRenderer(CLIENT, CLIENT.getEntityRenderDispatcher(),
   CLIENT.getBlockEntityRenderDispatcher(), CLIENT.renderBuffers())` (:404-409) — note the **shared
   `RenderBuffers`** across all dimensions.
5. Resolves the dimension type: `dimIdToDimTypeId.get(dimension)` (:420) then
   `registryManager.registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(dimensionTypeKey)` (:434-436).
   `dimIdToDimTypeId` comes from the server's `DimIdSyncPacket` (q_misc_util/MiscNetworking.java:104-125) — the
   client can NOT read dimension types of not-yet-visited dims from any local source.
6. Builds a **separate** `ClientLevel.ClientLevelData(difficulty, hardcore, isFlat)` copied from the current
   level's data (:429-444; isFlat read via accessor `IEClientLevelData.ip_getIsFlat`). Comment: "day time is
   not shared between worlds" (:438-439) — each world receives its own time via redirected time packets.
7. Constructs the `ClientLevel` **reusing the one and only `ClientPacketListener`** (`CLIENT.player.connection`,
   :413) — `new ClientLevel(mainNetHandler, properties, dimension, dimensionTypeHolder, chunkLoadDistance=3,
   simulationDistance, CLIENT::getProfiler, worldRenderer, CLIENT.level.isDebug(),
   CLIENT.level.getBiomeManager().biomeZoomSeed)` (:445-456). Note this 1.21.3 ctor takes the `LevelRenderer`
   directly.
8. Cross-world sharing: **map data map is shared by reference** (`ip_getMapData`/`ip_setMapData` accessor on the
   `ClientLevel.mapData` field, :415, :459) and the **`TickRateManager` is shared** (duck
   `IEClientWorld.ip_setTickRateManager(CLIENT.level.tickRateManager())`, :462).
9. `worldRenderer.setLevel(newWorld)` (:464) then `worldRenderer.onResourceManagerReload(CLIENT.getResourceManager())`
   (:466) — the reload is what allocates the renderer's GPU-side state for the new world.
10. Registers both maps, logs, fires `CLIENT_WORLD_LOAD_EVENT` (:468-484); `finally` clears
    `isCreatingClientWorld` (:479-482).

**Per-tick remote ticking** (`tick()`, :111-147; called from mixin/client/MixinMinecraft.java:128-141, injected
in `Minecraft.tick()` AFTER the vanilla `ClientLevel.tick(BooleanSupplier)` call — i.e. after the current
world ticked, before `POST_CLIENT_TICK_EVENT`):
- Gated by `IPCGlobal.isClientRemoteTickingEnabled` (:112). Sets the public flag `isClientRemoteTicking` around
  the loop (:113, :124) so other code can detect remote ticking.
- For every world ≠ `CLIENT.level`: `tickRemoteWorld(world)` (:114-118). For every renderer ≠
  `CLIENT.levelRenderer`: `worldRenderer.tick()` (:119-123).
- `tickRemoteWorld` (:154-174) runs inside `withSwitchedWorld`: `newWorld.tickEntities()`,
  `newWorld.tick(() -> true)`, then (if not paused) `tickRemoteWorldRandomTicksClient`, then
  `newWorld.pollLightUpdates()` (:159-166). All exceptions swallowed with a `CountDownInt(20)`-limited error
  log (:168-172) — a broken remote world must not crash the client.
- `tickRemoteWorldRandomTicksClient` (:178-206): among portals within 10 blocks of the player
  (collected BEFORE the switch, :155), finds the first whose `getDestDim()` equals this remote world (:181-183),
  computes `center = portal.transformPoint(player.position())` (:185-186), temporarily teleports the **camera
  object** there (`IECamera.portal_setPos`, :191), calls `newWorld.animateTick((int)center.x, ...)` every second
  game tick ("it costs some CPU time", :193-198) — this is what makes nether particles/random-tick visuals
  appear through the portal — then ticks `CLIENT.particleEngine` (:200) (which, due to the world switch, is
  currently bound to the remote world) and restores the camera (:202).
- **Lightmap conflict detection** (:127-145): every tick, each `DimensionRenderHelper` ticks its own
  `LightTexture` (DimensionRenderHelper.java:29-33 — the helper for the current dim shares
  `gameRenderer.lightTexture()` and skips ticking it; helpers for other dims own a private `LightTexture`,
  DimensionRenderHelper.java:15-27). If a helper for a *different* world is found holding the *current*
  `gameRenderer.lightTexture()` (stale after the current dimension changed), ALL render helpers are disposed and
  lazily recreated (:130-144).

**Render-state per dimension:** one `LevelRenderer` per dim (WORLD_RENDERER_MAP), one `DimensionRenderHelper`
(lightmap texture) per dim (RENDER_HELPER_MAP, created on demand at :339-354). Renderer reload cascade: a mixin
at the TAIL of `LevelRenderer.allChanged` (mixin/client/render/MixinLevelRenderer.java:403-414, skipped while a
world is being created) calls `_onWorldRendererReloaded()` (:503-538), which — unless re-entrant, portal
rendering is in progress, or a client world is being created — iterates every OTHER dimension and calls
`CLIENT.levelRenderer.allChanged()` for it under `withSwitchedWorld` (:521-535). The comment at :530-531 notes
`levelRenderer` "field is actually mutable" (mixin `@Mutable` on the final field, MixinMinecraft.java:48-51).

**The world context switch** (`withSwitchedWorld`, :544-583) — the single most load-bearing routine:
- Saves: `CLIENT.level`, `CLIENT.levelRenderer`, `networkHandler.getLevel()`, and the previous
  `isWorldSwitched` (:551-554).
- Sets: `CLIENT.level = newWorld`; particle engine's world via duck `IEParticleManager.ip_setWorld`;
  `Minecraft.levelRenderer` via duck `IEMinecraftClient.ip_setWorldRenderer`; the `ClientPacketListener`'s
  level field via duck `IEClientPlayNetworkHandler.ip_setWorld`; `isWorldSwitched = true` (:560-564).
- Does **not** touch `CLIENT.player.level()` — "It will not switch the dimension of client player" (:540-542).
- `finally` restore, with one special case: if `CLIENT.level` changed *during* the supplier (a respawn packet
  was handled inside the switch), it logs "Respawn packet should not be redirected" and adopts the changed
  level/renderer as the restore target instead of the saved ones (:570-575).
- `withSwitchedWorldFailSoft(dim, runnable)` (:592-603) resolves via `getOptionalWorld` and logs+ignores
  invalid dims — used by packet redirection, where a dim may have been dynamically removed.

**Cleanup on disconnect / conventional dimension travel:** hooked at HEAD of
`Minecraft.updateLevelInEngines(ClientLevel)` (mixin/client/MixinMinecraft.java:156-175): if initialized, fires
`IPCGlobal.CLIENT_CLEANUP_EVENT`; additionally fires `CLIENT_EXIT_EVENT` when the new level is null; then calls
`ClientWorldLoader.cleanUp()` (:208-223): every `LevelRenderer` gets `setLevel(null)` and, if it is not the
current vanilla renderer, `close()` + duck `IEWorldRenderer.portal_fullyDispose()` (:225-231); every
`ClientLevel`'s renderer back-reference is nulled (`IEClientWorld.ip_resetWorldRendererRef`, :213-215); maps
cleared; render helpers disposed (each closes its private `LightTexture`, DimensionRenderHelper.java:35-39);
`isInitialized = false`.

**Dynamic dimension removal:** `init()` (:84-101) subscribes to DimLib's `CLIENT_DIMENSION_UPDATE_EVENT`; any
loaded client world whose key is no longer in the server's dimension list is removed via
`disposeDimensionDynamically` (:233-274): asserts it is not the current/player dimension and on-thread
(:234-244), disposes + unmaps renderer/world/render-helper (:246-259), logs errors if the dimension still had
loaded chunks or entities (:263-269), calls `CLIENT.gameRenderer.resetData()` (:271), fires
`CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` (:273).

### 3.2 Client packet-dim routing model

Server-side, packets targeting a non-player dimension are wrapped into a redirect envelope carrying an
**integer dimension id** (`network/PacketRedirection`, outside this slice). Client-side flow:

1. `PacketRedirectionClient.handleRedirectedPacket(int dimensionIntId, Packet, handler)`
   (network/PacketRedirectionClient.java:45-77) may be called on the netty thread; if so it re-submits itself
   via `minecraft.execute` (:70-76). The dim id travels as an int because "the dimension id map is only stable
   in client thread" (:40-44).
2. On the client thread it resolves `ResourceKey<Level>` via `DimensionIntId.getClientMap().fromIntegerId`
   (:52-53), sets the `ThreadLocal<ResourceKey<Level>> clientTaskRedirection` (:32-33, :56), and handles the
   packet inside `ClientWorldLoader.withSwitchedWorldFailSoft(dimension, () -> packet.handle(handler))`
   (:59-64), restoring the ThreadLocal in `finally` (:66-68).
3. **Re-submission safety:** vanilla handlers call `PacketUtils.ensureRunningOnSameThread`, which re-queues the
   packet as a task. `MixinMinecraft_RedirectedPacket` (mixin/client/sync/MixinMinecraft_RedirectedPacket.java)
   injects at HEAD of `Minecraft.wrapRunnable(Runnable)` (:23-38): if `clientTaskRedirection` is set, the task
   is wrapped so that when it later runs, it runs under `withSwitchedWorldFailSoft(redirectedDimension, ...)`.
   So the dimension context survives the vanilla thread-hop protocol.
4. **No-delay guarantee:** the same mixin overrides `ReentrantBlockableEventLoop.scheduleExecutables()` on
   `Minecraft` (:47-59): while processing a redirected message on-thread, it returns false so nested
   `execute()` calls run immediately instead of being deferred (vanilla would defer while `runningTask()`).
5. Consequently every vanilla `ClientPacketListener` handler observes `this.level`, `Minecraft.level`,
   `Minecraft.levelRenderer`, and the particle engine already pointing at the packet's dimension — the
   redirected packets mutate the right `ClientLevel` with zero per-handler patches.

The dim-type map needed for world creation arrives via `MiscNetworking.DimIdSyncPacket.handle`
(q_misc_util/MiscNetworking.java:99-126): it stores `DimensionIntId.clientRecord` and builds the immutable
`ClientWorldLoader.dimIdToDimTypeId` from an NBT string map (dimension id → dimension type id).

### 3.3 Cross-portal ray tracing (IPMcHelper)

`rayTrace(world, clipContext, includeGlobalPortals)` (IPMcHelper.java:274-280 → private :194-258) recursion:
depth-capped by `IPGlobal.maxPortalLayer` (synthesized miss at cap, :204-217); per level: vanilla `world.clip`
(:220) + nearest interactable portal intersection (:222-229); if the portal beats the block hit, the SAME
`ClipContext` object is mutated (duck `IERayTraceContext.ip_setStart/ip_setEnd` with
`portal.transformPoint(...)`, :240-244) and recursion continues in `portal.getDestinationWorld()` under
`withSwitchedContext` (client: real world switch; server: plain call, :181-188); the context is restored before
returning (:253-255) so callers see an unmutated context. Returns the hit plus the ordered portal chain, which
callers use to transform directions.

### 3.4 Scale application (ScaleUtils)

Scale is a permanent `ADD_MULTIPLIED_TOTAL` modifier of `newScale - 1.0` on `Attributes.SCALE` under id
`iportal:scaling` (ScaleUtils.java:115-119); reading back inverts this (`amount() + 1.0`, :94). Application
order in `doScalingForEntity` (:147-171): snapshot eye pos + last-tick eye pos → multiply old scaling by
`portal.getScaling()` (snap to 1 within 1e-4, :173-182) → server-side legality clamp (:154-159,
`scale > scaleLimit || scale < 1/(2*scaleLimit)`, :184-186) → `setIPortalScaling` (with
`entity.refreshDimensions()` to refresh cached eye height, :107/:122) → restore eye pos and bounding box
(:164-169), i.e. the entity is scaled about its eye point. Client player teleport additionally scales the
camera's smoothed Y (`IECamera.ip_setCameraY`, :40-44). Server teleport also scales the vehicle (:52-54).

---

## 4. MC API touchpoint list (deduplicated; version-sensitive)

### Minecraft (client core)
- `Minecraft.level` — public mutable field, directly reassigned (ClientWorldLoader.java:560, :577)
- `Minecraft.levelRenderer` — **final field made `@Mutable` and reassigned via duck**
  (mixin/client/MixinMinecraft.java:48-51, :207-209; ClientWorldLoader.java:562)
- `Minecraft.particleEngine` — internal level field reassigned via duck `IEParticleManager.ip_setWorld`
  (ClientWorldLoader.java:561); `ParticleEngine.tick()` (:200)
- `Minecraft.updateLevelInEngines(ClientLevel)` — inject HEAD (cleanup hook, MixinMinecraft.java:156-160)
- `Minecraft.tick()` — inject after `ClientLevel.tick(BooleanSupplier)` (MixinMinecraft.java:119-127)
- `Minecraft.wrapRunnable(Runnable)` — inject HEAD, cancellable (MixinMinecraft_RedirectedPacket.java:23-28)
- `ReentrantBlockableEventLoop.scheduleExecutables()` — overridden on Minecraft
  (MixinMinecraft_RedirectedPacket.java:47-59). `runningTask()` is NOT overridden — it is only invoked inside
  that override (`return this.runningTask() || !onThread;`, :58). There is no `runningTask` override to
  re-create in the port.
- `Minecraft.useShaderTransparency()` — inject HEAD cancellable (MixinMinecraft.java:178-183)
- `Minecraft.isSameThread()`, `.execute(Runnable)`, `.isPaused()`, `.getProfiler()` (push/pop),
  `.getConnection()`, `.player`, `.getResourceManager()`, `.renderBuffers()`,
  `.getEntityRenderDispatcher()`, `.getBlockEntityRenderDispatcher()`, `.setScreen(Screen)`,
  `.gui.getChat().addMessage(Component)`, `.getInstance()`
- `Minecraft.mainRenderTarget`, `.renderBuffers`, `.screen`, `.gameThread` — shadowed/mutated by
  `IEMinecraftClient` duck (MixinMinecraft.java:40-72, :196-219)

### ClientLevel / level data
- **`ClientLevel` constructor** `(ClientPacketListener, ClientLevelData, ResourceKey<Level>,
  Holder<DimensionType>, int chunkLoadDistance, int simulationDistance, Supplier<ProfilerFiller>,
  LevelRenderer, boolean isDebug, long biomeZoomSeed)` (ClientWorldLoader.java:445-456)
- **`ClientLevel.ClientLevelData` constructor** `(Difficulty, boolean hardcore, boolean isFlat)` (:440-444);
  its private `isFlat` field (accessor mixin IEClientLevelData)
- `ClientLevel.mapData` private field — read/replaced by accessor mixin (IEClientLevel_Accessor;
  ClientWorldLoader.java:415, :459)
- `ClientLevel.tickEntities()` (:159), `.tick(BooleanSupplier)` (:160), `.pollLightUpdates()` (:166),
  `.animateTick(int,int,int)` (:195-197), `.getGameTime()` (:193), `.dimension()`,
  `.getChunkSource().getLoadedChunksCount()` (:263), `.getEntityCount()` (:267),
  `.getServerSimulationDistance()` (:432), `.isDebug()` (:454), `.getBiomeManager().biomeZoomSeed` (:455),
  `.tickRateManager()` (:462), `.entitiesForRendering()` (CHelper.java:128)
- `TickRateManager` — shared across worlds via duck (ClientWorldLoader.java:462)
- `MapItemSavedData` — the shared map-data map value type

### LevelRenderer (⚠ 26.2 renderer rewrite hotspot)
- **constructor** `LevelRenderer(Minecraft, EntityRenderDispatcher, BlockEntityRenderDispatcher, RenderBuffers)`
  (ClientWorldLoader.java:404-409)
- `.setLevel(@Nullable ClientLevel)` (:226, :464), `.onResourceManagerReload(ResourceManager)` (:466),
  `.tick()` (:121), `.close()` (:228), `.allChanged()` (:532; also mixin injection target,
  MixinLevelRenderer.java:403)
- `RenderBuffers` — shared instance passed to every secondary renderer

### Networking / packets
- `ClientPacketListener.levels()` → `Set<ResourceKey<Level>>` (ClientWorldLoader.java:491)
- `ClientPacketListener.registryAccess()` (:431, :615), `.getLevel()` (:553), `.getPlayerInfo(UUID)` (CHelper.java:46)
- `ClientPacketListener`'s internal level field — reassigned via duck `IEClientPlayNetworkHandler.ip_setWorld`
  (ClientWorldLoader.java:563)
- `Packet.handle(listener)` raw dispatch (PacketRedirectionClient.java:62), `PacketUtils.ensureRunningOnSameThread`
  (behavioral dependency, :80-87), `ClientboundBundlePacket` / `ClientboundCustomPayloadPacket` (redirect
  special cases, :83-86)
- `FriendlyByteBuf` + `Unpooled.wrappedBuffer` (IPMcHelper.java:322-331)

### Rendering / GL
- `GameRenderer.lightTexture()` (ClientWorldLoader.java:131; DimensionRenderHelper.java:21,30,36),
  `.getMainCamera()` (:188), `.resetData()` (:271)
- `LightTexture` constructor `(GameRenderer, Minecraft)`, `.tick()`, `.close()` (DimensionRenderHelper.java:24,31,37)
- `Camera.getPosition()` (:189; CHelper.java:119); camera pos mutated via duck `IECamera.portal_setPos`
- `GlUtil.getVendor()` (IPMcHelper.java:319)
- raw LWJGL: `GL11.glGetError`, `GL11.glDisable/glEnable(GL32.GL_DEPTH_CLAMP)` (CHelper.java:80, :140, :146)

### Entity API
- direct field writes: `Entity.xo/yo/zo`, `Entity.xOld/yOld/zOld` (McHelper.java:109, :252-258)
- `Entity.setPosRaw(x,y,z)` (:252), `.setPos(x,y,z)` (:320, :374), `.position()`, `.getBoundingBox()`
  (`.intersects`, `.move`), `.getDeltaMovement()/.setDeltaMovement(Vec3)` (:313, :330),
  **`.lerpTo(x,y,z,yaw,pitch,steps)` 6-arg** (:321-324), `.getYRot()/.getXRot()`, `.getVehicle()` (:306),
  **`.getVehicleAttachmentPoint(Entity)`** (:300), `.level()`, `.getId()` (:452),
  `.getType().create(Level)` (:399), `.saveWithoutId(CompoundTag)` / `.load(CompoundTag)` (:403-404),
  `.refreshDimensions()` (ScaleUtils.java:107,122,131), `.sendSystemMessage(Component)` (ScaleUtils.java:156),
  `.createCommandSourceStack()` (McHelper.java:436), `.getServer()` (:437), `.addFreshEntity` via
  `Level.addFreshEntity(Entity)` (:838)
- `Entity#positionRider` (vanilla-copy reference, McHelper.java:295-298)
- attributes: `Attributes.SCALE`, `LivingEntity.getAttributes().getInstance(...)`, `LivingEntity.getScale()`,
  `AttributeInstance.getModifier(ResourceLocation)/.addOrReplacePermanentModifier/.removeModifier(ResourceLocation)
  /.getBaseValue/.setBaseValue`, `AttributeModifier(ResourceLocation, double, Operation.ADD_MULTIPLIED_TOTAL)`,
  `AttributeModifier.amount()` (ScaleUtils.java:58-133)

### Entity storage / lookup (⚠ internal, duck-accessed)
- `LevelEntityGetter<Entity>` — obtained via duck `IEWorld.portal_getEntityLookup()` (McHelper.java:630, :938);
  `.get(UUID)` (:938)
- `EntitySectionStorage<Entity>` — the private cache inside `LevelEntityGetterAdapter`, extracted by accessor
  (McHelper.java:550-551) and traversed by section-box ducks (:553-562);
  `EntitySectionStorage#forEachAccessibleNonEmptySection` (javadoc-referenced semantics, :588)
- `EntityTypeTest.forClass(Class)` (:548), `AbortableIterationConsumer` (:588 javadoc), `EntityTickList`
  (IEClientWorld duck)
- `ServerLevel.getAllEntities()` (:824), `.areEntitiesLoaded(long)` (:491)

### Server / chunk APIs
- `ServerChunkCache.chunkMap` (McHelper.java:99-101, :348-350); `ChunkMap`'s private chunk-holder and
  entity-tracker maps via duck `IEChunkMap` (:338, :447, :452); `ChunkMap.TrackedEntity.broadcastAndSend(Packet)`
  (:458); `ChunkMap#getPlayerViewDistance(ServerPlayer)` (vanilla-copy, :237-245)
- `ChunkHolder.getTickingChunk()` (:342, :354); `ChunkPos.asLong/getRegionX/getRegionZ/toLong`
- `MinecraftServer.getLevel(ResourceKey)` (:115, :853), `.getPlayerList().getPlayers()/.getViewDistance()`
  (:106, :233), `.getCommands().performPrefixedCommand(CommandSourceStack, String)` (:442),
  `.storageSource` (**field access**, :415), `.getRunningThread()` (:432)
- `LevelStorageSource.LevelStorageAccess.getDimensionPath(ResourceKey<Level>)` (:417)
- `ServerPlayer.displayClientMessage(Component, boolean)` (:123, :811), `.requestedViewDistance()` (:244)
- `CommandSourceStack.withPermission(int).withSuppressedOutput()` (:436)
- `Level.isClientSide()/.getChunk(int,int)/.getBlockState(BlockPos).getCollisionShape(Level, BlockPos)`
  (:471), `.clip(ClipContext)` (IPMcHelper.java:220), `LevelAccessor.getMinBuildHeight()/.getMaxBuildHeight()
  /.getMinSection()/.getMaxSection()/.dimensionType().logicalHeight()` (McHelper.java:864-886)
- `ClipContext.getFrom()/.getTo()` + private from/to mutation via duck `IERayTraceContext`
  (IPMcHelper.java:200-201, :240-244)
- `BlockHitResult.miss(Vec3, Direction, BlockPos)` (:210), `HitResult.Type.MISS` (:288),
  `Direction.getUnitVec3()` (:211), `BlockPos.containing(Vec3)` (:213)

### Registry / resources / misc
- `RegistryAccess.registryOrThrow(Registries.DIMENSION_TYPE / Registries.BIOME)`,
  `Registry.getHolderOrThrow(ResourceKey)` (ClientWorldLoader.java:434-436, :616), `Registry.getId/get/keySet`
  (:622-627), `ResourceKey.create(...)` (MiscNetworking), `ResourceLocation.fromNamespaceAndPath/parse`
  (McHelper.java:90, :94)
- `Holder<DimensionType>`, `ResourceKey<DimensionType>`
- `ResourceManager.getResource(ResourceLocation)` → `Optional<Resource>` / `.open()` (McHelper.java:900-904,
  CHelper.java:164)
- `Util.backgroundExecutor()` (McHelper.java:168), `Util.getPlatform().openUri(URI)` (CHelper.java:106)
- `Mth.clamp` (McHelper.java:244), `SectionPos.of(Vec3 | BlockPos)` (:626, :724), `Vec3`, `AABB.minmax`,
  `VoxelShape.isEmpty()/.bounds()`
- `Component.literal/translatable`, `MutableComponent.withStyle`, `ClickEvent(Action.OPEN_URL |
  Action.RUN_COMMAND)`, `Style.withClickEvent/.withUnderlined`, `ChatFormatting` (McHelper.java:423-429,
  IPMcHelper.java:291-315, IPModMainClient.java:44-47)
- `ConfirmLinkScreen`, `Screen` (CHelper.java:97-116)
- Codecs: `Codec.encode/decode`, `DynamicOps.get`, `JsonOps.INSTANCE`, `DataResult.getOrThrow`
  (McHelper.java:747-798); `CompoundTag`
- `EntityType<?>` (registration values, IPModMain.java:162-213), `InteractionResult.SUCCESS`
  (IPModMain.java:149), `ProfilerFiller.push/pop`
- `GameProfile.getId()` (CHelper.java:47), `PlayerInfo` (CHelper.java:45)

---

## 5. Registration & wiring

**Entry points (Fabric):**
- `platform_specific/IPModEntry.java:15` (`ModInitializer`) → `IPModMain.init()`.
- `platform_specific/IPModEntryClient.java:67` (`ClientModInitializer`) → `IPModMainClient.init()`; the two
  portal renderers are constructed on the render thread via `Minecraft.getInstance().execute`
  (IPModMainClient.java:76-85).

**Entity types & block:** provided as registration *lists* (`IPModMain.registerEntityTypes` /
`registerBlocks`, IPModMain.java:155-213) consumed by the platform layer; entity type ids and their
`ENTITY_TYPE` statics are enumerated in §2.8. This is the canonical list the migration must register.

**Tick wiring (all via mixins, no Fabric tick events for the core loop):**
- `ClientWorldLoader.tick()` ← `mixin/client/MixinMinecraft.onAfterClientTick` (MixinMinecraft.java:119-142),
  injected in `Minecraft.tick()` immediately after vanilla `ClientLevel.tick(BooleanSupplier)`; the same
  injection then runs `StableClientTimer`, `ClientPortalAnimationManagement.tick()`,
  `ClientTeleportationManager.manageTeleportation(true)`, and fires `IPGlobal.POST_CLIENT_TICK_EVENT`
  (order documented in the mixin's javadoc, MixinMinecraft.java:86-103).
- `IPGlobal.CLIENT_TASK_LIST` processed by `POST_CLIENT_TICK_EVENT` (IPModMain.java:71);
  `PRE_GAME_RENDER_TASK_LIST` by `PRE_GAME_RENDER_EVENT` (IPModMain.java:73, fired at
  MixinGameRenderer.java:93); `PRE_TOTAL_RENDER_TASK_LIST` at MixinGameRenderer.java:76.
- Server: `IPGlobal.SERVER_CLEANUP_EVENT` fired from MixinMinecraftServer.java:23; `IPPerServerInfo` is an
  added field on `MinecraftServer` (MixinMinecraftServer.java:16) exposed by duck
  `IEMinecraftServer.ip_getPerServerInfo` (:27).

**Cleanup wiring:** `Minecraft.updateLevelInEngines` HEAD → `CLIENT_CLEANUP_EVENT` (+ `CLIENT_EXIT_EVENT` when
level==null) → `ClientWorldLoader.cleanUp()` (MixinMinecraft.java:156-175). `CLIENT_CLEANUP_EVENT` also clears
`CLIENT_TASK_LIST` (IPModMainClient.java:130-132); `CLIENT_EXIT_EVENT` clears
`ClientWorldLoader.dimIdToDimTypeId` (ClientWorldLoader.java:98-100).

**Renderer-reload wiring:** `LevelRenderer.allChanged` TAIL → `ClientWorldLoader._onWorldRendererReloaded()`
(MixinLevelRenderer.java:403-414); reload of a renderer is *cancelled* entirely while portal rendering is in
progress (MixinLevelRenderer.java:395-400).

**Network wiring for this slice:** `ImmPtlNetworking.init/initClient`, `ImmPtlNetworkConfig.init/initClient`,
`PacketRedirection.init()` are called from the init sequences (IPModMain.java:67-69,
IPModMainClient.java:125-126); the dim-type map is fed by `MiscNetworking.DimIdSyncPacket` registered through
`ClientPlayNetworking.registerGlobalReceiver` (MiscNetworking.java:135-140); `DimensionIntId.initClient()`
(IPModMainClient.java:134) maintains the int-id map used by redirected packets.

**Fabric API surface used by this slice:** `EventFactory.createArrayBacked` (all `Event<...>` objects, via
Helper.java:1424-1444), `CommandRegistrationCallback.EVENT` (IPModMain.java:103),
`ClientCommandRegistrationCallback.EVENT` (IPModMainClient.java:103), `@Environment(EnvType.CLIENT)`,
`FabricLoader.getInstance().isModLoaded` (IPMixinPlugin.java:24). Config: AutoConfig/Cloth
(`AutoConfig.register`, `ConfigHolder.registerSaveListener`, IPModMain.java:146-150). External libs: DimLib
(`DimensionAPI.CLIENT_DIMENSION_UPDATE_EVENT`, ClientWorldLoader.java:85) and the bundled `q_misc_util`
(Helper, MiscHelper, MiscNetworking, DimensionIntId/DimIntIdMap, MyTaskList, LimitedLogger, CountDownInt,
DQuaternion, IntBox).

---

## Notes for the 26.2 mapping stage (facts, not proposals)

- The `ClientLevel` and `LevelRenderer` constructor signatures cited in §3.1 are the 1.21.3 shapes; the 26.2
  renderer rewrite (see MIGRATION_API_MAP.md headline) will hit the `LevelRenderer` ctor,
  `onResourceManagerReload`, `allChanged`, `tick`, `close`, and the `Minecraft.levelRenderer` `@Mutable` mixin
  hardest — every one of those call sites is enumerated above with lines.
- `withSwitchedWorld` swaps exactly four references (level, levelRenderer, particleEngine.level,
  connection.level) plus a flag; any additional per-level state vanilla 26.2 caches (e.g. level-bound render
  state or the level extractor this repo already fought — see MEMORY nether-block-freeze) must be added to the
  swap set with IP-fidelity justification at the mapping stage, not here.
- `Registry.registryOrThrow`/`getHolderOrThrow` and `LevelAccessor.getMinBuildHeight/getMaxBuildHeight/
  getMinSection/getMaxSection` were renamed in MC 1.21.2+→26.x lines; they appear only in the helper layer
  (exact sites cited in §4).
