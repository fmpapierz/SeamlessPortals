# IP Subsystem Inventory — Chunk Loading / Tracking

**Slice root:** `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/imm_ptl/core/chunk_loading/` (11 files)
**IP version:** MC 1.21.3, Mojang mappings. All `file:line` citations below are into the IP tree unless prefixed otherwise.
**Depth:** FULL read of all 11 slice files + the chunk_sync mixin package (**10 files** — the first pass of this doc silently skipped one, `IEChunkMap_Accessor.java`; it is now included, see §4 #55 and §5) + supporting ducks/wiring.

---

## 1. Overview

This subsystem is IP's replacement for the entire vanilla "which chunks does a player see, and how do chunk packets reach them" pipeline, generalized to **many dimensions per player simultaneously**. On the server it maintains, per chunk per dimension, a map of `PlayerWatchRecord`s (who watches this chunk, at what ring-distance from a loading source, whether the chunk data has actually been sent). Every ~13 ticks per player it recomputes a set of square `ChunkLoader`s — one around the player plus one per nearby portal (direct) plus one per portal visible *through* a portal (indirect) — with graduated radii that shrink with distance to the portal and are clamped by client/server performance tiers and a config cap. Chunk *forcing* is done with IP's own throttled ticket manager (`ImmPtlChunkTickets`, ticket type `"imm_ptl"`), and chunk *sending* with IP's own multi-dimension re-implementation of vanilla `PlayerChunkSender` batching (`PlayerChunkLoading`). Vanilla's `PlayerChunkSender`, `ChunkMap.applyChunkTrackingView`, `ChunkMap.tick()` (entity tracking) and `DistanceManager$PlayerTicketTracker` are all disabled by mixins and coexist-replaced by this slice.

Packets reach the client through **vanilla packet classes** (`ClientboundLevelChunkWithLightPacket`, `ClientboundForgetLevelChunkPacket`, chunk batch start/finish) wrapped in IP's dimension-tagging redirection payload (`PacketRedirection`, custom payload id `i:r`), so the client can route each packet to the correct `ClientLevel`. On the client, `ImmPtlClientChunkMap` replaces the fixed-size 2D chunk storage array of `ClientChunkCache` with hash maps so chunks of remote dimensions (and far-away chunks) can be held without a view-center. This slice is exactly what replaces the mod's current `PortalChunkTracker` / ACK-ledger / redirected-send machinery.

---

## 2. Class-by-class inventory

### 2.1 `ImmPtlChunkTracking` (~669 LOC, server, static singleton)

**Responsibility:** the central per-player cross-dimension chunk watch-record store, the update/purge cadence, and the public query API every other subsystem uses to ask "who can see chunk (dim,x,z)".

**Key constants:**
- `updateInterval = 13` ticks (`ImmPtlChunkTracking.java:41`)
- `defaultDelayUnloadGenerations = 4` (`ImmPtlChunkTracking.java:42`)

**State:**
- `chunkWatchRecords : Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Object2ObjectOpenHashMap<ServerPlayer, PlayerWatchRecord>>>` — per dimension → per chunk-pos-long → per player → record (`ImmPtlChunkTracking.java:120-124`).
- `additionalChunkLoaders : ArrayList<ChunkLoader>` — global (player-independent) loaders (`:126`).
- `playerInfoMap : Object2ObjectOpenHashMap<ServerPlayer, PlayerChunkLoading>` (`:128-129`).
- `generationCounter : int` — monotonic "turn" counter, incremented every 13 ticks (`:131`, `:382`).

**Inner class `PlayerWatchRecord`** (`:78-117`): `player`, `dimension`, `chunkPos` (long), `lastWatchGeneration`, `distanceToSource`, `isLoadedToPlayer`, `isValid`, `isBoundary`. `isBoundary` marks records at exactly `chunkLoader.radius()` (`:199`); light packets are only broadcast to boundary watchers because the client computes light from block data except at the loading boundary (`:87-88`, `:479-480`).

**Key public API:**
- `init()` — registers `ServerTickEvents.END_SERVER_TICK → tick`, `IPGlobal.SERVER_CLEANUP_EVENT → cleanup`, `DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT → onDimensionRemove` (`:44-51`).
- `updateForPlayer(ServerPlayer)` (`:160-237`) — recompute the player's loaders and refresh records (see Mechanisms §3.2).
- `immediatelyUpdateForPlayer(ServerPlayer)` (`:148-158`) — `updateForPlayer` + `doChunkSending` + `EntitySync.update`; the comment at `:153-156` states the ordering constraint: entity add packets must go out early or the player falls through cross-portal collision on login.
- `getPlayerInfo(ServerPlayer) : PlayerChunkLoading` (`:138-146`) — lazily creates per-player info; passes `Connection.isMemoryConnection()` obtained via the `IEServerCommonPacketListenerImpl` accessor on `player.connection`.
- `isPlayerWatchingChunk(player, dim, x, z [, predicate])` (`:401-433`) — true only if the record exists **and** `isLoadedToPlayer` (`:420-422`).
- `isPlayerWatchingChunkWithinRadius(player, dim, x, z, radiusBlocks)` (`:435-445`) — predicate `distanceToSource * 16 <= radiusBlocks`.
- `getPlayersViewingChunk(dim, x, z)` → Stream (`:456-465`); overload with `boolean boundaryOnly` → List (`:467-490`) used by the `ChunkHolder.broadcastChanges` redirect.
- `getWatchRecordForChunk(dim, x, z)` (`:493-498`).
- `addGlobalAdditionalChunkLoader(server, loader)` / `removeGlobalAdditionalChunkLoader` (`:563-591`) — remove is by **object reference identity**, not equality (`:584-586`, `:590`). Add immediately marks all covered chunks for ticket loading (`:577-581`).
- `addPerPlayerAdditionalChunkLoader(player, loader)` / `remove...` (`:597-613`) — add sets `shouldUpdateImmediately = true` (`:602`); remove is also reference-identity (`:605-607`).
- `getVisibleDimensions(player)` (`:615-617`).
- `shouldLoadDimension(dim)` (`:554-561`) — non-empty watch-record map; used to keep dimensions ticking (see §5).
- `forceRemovePlayer(oldPlayer)` (`:500-517`) — drops all records of the player, sending a redirected `ClientboundForgetLevelChunkPacket` per previously-recorded chunk.
- `forceRemoveDimension(world)` (`:519-552`) — unload packets to all valid+loaded watchers, drops the dim's map, removes global and per-player loaders of that dim.
- `removePlayerFromChunkTrackersAndEntityTrackers(oldPlayer)` (`:58-67`) — for every level: `IEChunkMap.ip_onPlayerUnload(oldPlayer)` (entity trackers) then `forceRemovePlayer`.
- `syncBlockUpdateToClientImmediately(ServerLevel, IntBox)` (`:619-658`) — flushes pending chunk sending for players that watch-but-haven't-received chunks in the box, then calls `chunkHolder.broadcastChanges(tickingChunk)` per chunk (`:649-655`).
- `getLoadedChunkNum(dim)` (`:593-595`).
- `RemoteCallables.acceptClientPerformanceInfo(player, level)` (`:660-668`) — RPC target invoked by the client performance monitor.

**Dependencies:** `ChunkVisibility`, `ImmPtlChunkTickets`, `PlayerChunkLoading`, `EntitySync`, `PacketRedirection` (network slice), ducks `IEChunkMap`, `IEServerCommonPacketListenerImpl`, `qouteall.dimlib.api.DimensionAPI`, `IPGlobal`, `q_misc_util.my_util.IntBox`.

**Vanilla touched:** `ServerPlayer`, `ServerLevel`, `ServerChunkCache.chunkMap`, `ChunkHolder.getTickingChunk/broadcastChanges`, `ClientboundForgetLevelChunkPacket`, `ChunkPos.asLong/getX/getZ`, `MinecraftServer.getAllLevels/getLevel/getPlayerList/overworld/getProfiler`.

### 2.2 `ChunkVisibility` (~232 LOC, server, static)

**Responsibility:** compute the set of `ChunkLoader`s for a player — the graduated loading model.

- Constants `portalLoadingRange = 48` (`ChunkVisibility.java:24`) and `secondaryPortalLoadingRange = 16` (`:25`) are **dead** — no other reference anywhere in the IP tree (verified by grep; the actual ranges come from `PerformanceLevel`).
- `playerDirectLoader(player)` (`:27-35`) — loader at the player's own dim/chunk with radius `McHelper.getPlayerLoadDistance(player)` = `Mth.clamp(player.requestedViewDistance(), 2, server view distance)` (`McHelper.java:241-245`, an `@IPVanillaCopy` of `ChunkMap.getPlayerViewDistance`).
- `getDirectLoadingDistance(renderDistance, distanceToPortal)` (`:37-45`): `< 5` blocks → full `renderDistance`; `< 15` → `renderDistance * 2 / 3`; else `renderDistance / 3`.
- `getCappedLoadingDistance(portal, player, target)` (`:47-66`): `cap1` = client-performance cap (`PerformanceLevel.getIndirectLoadingRadiusCap` of the player's reported level), `cap2` = `IPGlobal.indirectLoadingRadiusCap` (default 8, `IPGlobal.java:44`), `cap3` = server-performance cap — **computed but unused**: `cap = Math.min(cap1, cap2)` (`:52-56`). If `portal.getScale() > 2` the cap is doubled (`:58-61`). Result = `min(target, cap)`.
- `getNearbyPortals(world, pos, predicate, radiusChunks, radiusChunksForGlobalPortals)` (`:68-97`): `McHelper.findEntitiesRough` + `GlobalPortalStorage.getGlobalPortals` filtered by `getDistanceToNearestPointInPortal < radiusChunksForGlobalPortals * 16`; if > 100 portals found, logs and returns only the single nearest portal (`:87-94`).
- `getGeneralDirectPortalLoader(player, portal)` (`:99-143`): global portal → loader centered at chunk of `portal.transformPoint(player.position())`, radius `min(IPGlobal.indirectLoadingRadiusCap * 2, max(2, loadDistance − floorDiv(distanceToPortal,16)))` (`:103-111`); normal portal → centered at `portal.getDestPos()` chunk, radius `getCappedLoadingDistance(getDirectLoadingDistance(loadDistance, distance))`, with an up-scaling special case: `scaling > 2 && distance < 5` → `loadDistance = destAreaRadiusEstimation * 1.4 / 16` (`:127-130`).
- `getGeneralPortalIndirectLoader(player, transformedPos, portal)` (`:145-176`): global → radius `min(indirectLoadingRadiusCap, loadDistance/3)` centered at the transformed position; normal → radius `capped(loadDistance / 4)` centered at `getDestPos()`.
- `foreachBaseChunkLoaders(player, func)` (`:182-226`) — the enumeration: (1) player direct loader; (2) for each portal within `visiblePortalRangeChunks` (perf-tiered 8/3/1; global portals within 256 chunks), the direct portal loader — the `portal.broadcastToPlayer(player)` predicate is applied **only to entity portals** (it is passed into `McHelper.findEntitiesRough`, `ChunkVisibility.java:72-78`); **global portals are appended on the distance test alone (`getDistanceToNearestPointInPortal(pos) < radiusChunksForGlobalPortals * 16`) with no `broadcastToPlayer` check** (`:80-85`); (3) unless `isShrinkLoading()`, for each second-level portal near the transformed player position (range `indirectVisiblePortalRangeChunks` 2/1/0; global 32 chunks; same entity-only predicate rule — both passes go through `getNearbyPortals`, `:193-198`, `:212-217`), the indirect loader (`:211-224`). **A faithful port must NOT filter global portals by `broadcastToPlayer` in either pass** — a global portal failing `broadcastToPlayer` still produces direct and indirect loaders.
- `isShrinkLoading()` (`:228-230`) = `ServerPerformanceMonitor.getLevel() != good`.

**Dependencies:** `Portal` (portal core slice: `getIsGlobal`, `transformPoint`, `getDestDim`, `getDestPos`, `getScale`, `getScaling`, `getDistanceToNearestPointInPortal`, `getDestAreaRadiusEstimation`, `broadcastToPlayer`, `getDestinationWorld`), `GlobalPortalStorage`, `McHelper`, `IPGlobal`, `PerformanceLevel`, `ServerPerformanceMonitor`.

### 2.3 `ChunkLoader` (record, ~138 LOC, common)

**Responsibility:** value object `(dimension, x, z, radius)` describing one square chunk-loading region (`ChunkLoader.java:13-18`).

- `foreachChunkPos(ChunkPosConsumer)` (`:48-59`) — iterates the `(2r+1)²` square; `distanceToSource = max(|dx|,|dz|)` (Chebyshev ring distance) (`:55`).
- `foreachChunkPosFromInnerToOuter` (`:61-100`) — spiral traversal (used elsewhere, e.g. nether portal gen).
- `getChunkNum() = (2r+1)²` (`:40-42`); `getLoadedChunkNum(server)` counts fully-loaded chunks via `McHelper.isServerChunkFullyLoaded` (`:27-38`); `isFullyLoaded` (`:44-46`).
- `loadChunksAndDo(server, runnable)` (`:114-128`) — registers itself as a global additional loader, then polls via `ServerTaskList` (`MyTaskList.withDelayCondition`) until fully loaded, then removes the loader and runs. Doc warning: not persistent across server restarts (`:116`).
- `createFastBlockAccess` (`:102-112`) — bridge to `FastBlockAccess` for nether portal gen.

### 2.4 `DimensionalChunkPos` (~48 LOC, common)

Immutable `(dimension, x, z)` with equals/hashCode (`DimensionalChunkPos.java:10-47`). Pure value type, used as `ChunkLoader` center.

### 2.5 `ImmPtlChunkTickets` (~337 LOC, server, one instance per `ServerLevel`)

**Responsibility:** IP's own throttled chunk-ticket manager, replacing vanilla's per-player ticket propagation. The class javadoc (`ImmPtlChunkTickets.java:38-55`) explains why: vanilla's `ChunkTaskPriorityQueue`/`ChunkTaskPriorityQueueSorter`/`ProcessorMailbox` throttling machinery is complex; IP re-implements a simpler equivalent (limit in-flight loads, prioritize near chunks).

**State:**
- `TICKET_TYPE = TicketType.create("imm_ptl", Comparator.comparingLong(ChunkPos::toLong))` (`:60-61`) — no timeout, so tickets persist until explicitly removed.
- `BY_DIMENSION : WeakHashMap<ServerLevel, ImmPtlChunkTickets>` (`:69`); instances deliberately avoid holding a `ServerLevel` reference (`:68`). `get(ServerLevel)` is `computeIfAbsent` (`:104-106`).
- `chunkPosToTicketInfo : Long2ObjectOpenHashMap<ChunkTicketInfo{lastUpdateGeneration, distanceToSource}>` (`:79-89`).
- `chunksToAddTicketByDistance : ArrayList<LongLinkedOpenHashSet>` — pending-ticket queues indexed by ring distance (`:91`).
- `waitingForLoading : LongOpenHashSet` (`:93`); `throttlingLimit = 4` (`:97`); `isValid` flag (`:95`).

**Key API:**
- `markForLoading(long chunkPos, int distanceToSource, int generation)` (`:108-137`) — creates or updates the ticket info; a new generation overwrites distance and moves the pending-queue entry; the same generation only moves it if the distance shrank.
- `tick(world)` → `flushThrottling(world)` (`:147-149`).
- `flushThrottling(world)` (`:163-231`):
  - Guards: must run on the server/world thread (`:164-167` via `IEWorld.portal_getThread`, `IEWorld.java:15`); refuses when `!world.getServer().isRunning()` — do not add tickets while the server saves (issue #1455, `:178-182`).
  - Step 1: drop entries from `waitingForLoading` whose `ChunkHolder.getEntityTickingChunkFuture().getNow(null)` produced a `ChunkResult` (logs on `!isSuccess()`), or whose holder is gone (`:188-209`).
  - Step 2: drain the per-distance queues nearest-first, adding a ticket per chunk, stopping whenever `waitingForLoading.size() >= 4` (`:212-230`).
  - The javadoc (`:151-162`) specifies it must run **after** `DistanceManager.runAllUpdates` because that updates `ChunkHolder` futures; hence the second call site (see §5).
- `addTicket` (`:233-246`) — gated on `IPConfig.getConfig().enableImmPtlChunkLoading` (`IPConfig.java:132`); calls `distanceManager.addRegionTicket(TICKET_TYPE, chunkPosObj, getLoadingRadius(), chunkPosObj)` (`:239-241`).
- `getLoadingRadius()` (`:314-321`) — `2` when `IPGlobal.activeLoading` (default true, `IPGlobal.java:54`), else `1`. This is vanilla `addRegionTicket`'s int "level distance" argument (higher value → lower ticket level → chunk reaches entity-ticking status; the exact level arithmetic is a vanilla internal that MUST be re-verified against 26.2's rewritten ticket system — 26.2 tickets carry load/simulation flags).
- `purge(world, LongPredicate shouldKeepLoading)` (`:248-278`) — for every tracked chunk failing the predicate: remove from `waitingForLoading`; remove from its pending queue; only if it was **not** still pending (i.e., a ticket was actually added) call `distanceManager.removeRegionTicket(TICKET_TYPE, pos, getLoadingRadius(), pos)` (`:266-271`).
- `onDimensionRemove(world)` / `removeAllTicketsInWorld` (`:284-312`) — iterates the actual ticket sets via the `IEDistanceManager.portal_getTicketSet` accessor (`MixinDistanceManager.java:57-60`, wrapping private `DistanceManager.getTickets(long)`), collects tickets with `getType() == TICKET_TYPE`, removes each via `removeRegionTicket(..., ticket.getTicketLevel(), ...)` (`:298-308`); marks instance invalid.
- Static helpers: `getChunkHolder(world, pos)` = `IEChunkMap.ip_getChunkHolder` → `ChunkMap.getVisibleChunkIfPresent` (`:323-325`, `MixinChunkMap_C.java:53-56`); `getDistanceManager(world)` via `IEServerChunkCache.ip_getDistanceManager()` (`:327-329`, `IEServerChunkCache.java:6`).

### 2.6 `PlayerChunkLoading` (~243 LOC, server, one per player)

**Responsibility:** per-player chunk-loading state + the multi-dimension chunk-packet sender; an explicit `@IPVanillaCopy` of `PlayerChunkSender` (`PlayerChunkLoading.java:34-37`, `:89-92`).

**State (`:46-72`):** `visibleDimensions` (cleared/rebuilt each `updateForPlayer`), `additionalChunkLoaders` (per-player API loaders), `distanceToPendingChunks : ArrayList<ObjectArrayList<PlayerWatchRecord>>` indexed by ring distance, `loadedChunks` counter, `shouldUpdateImmediately`, `performanceLevel` (initialized `bad`, `:62`), and vanilla-copied batching fields: `isMemoryConnection`, `desiredChunksPerTick = 9.0F`, `batchQuota`, `unacknowledgedBatches`, `maxUnacknowledgedBatches = 1`.

**Key API:**
- `markPendingLoading(record)` (`:81-87`) — append to the distance-indexed pending list; the same chunk may be queued multiple times at different distances (`:78-80`), dedup happens at send time via `isLoadedToPlayer`.
- `doChunkSending(player)` (`:93-187`) — vanilla `PlayerChunkSender.sendNextChunks` logic generalized:
  - Return if `unacknowledgedBatches >= maxUnacknowledgedBatches` (`:94-96`).
  - Quota: memory connection → 256; else `batchQuota = min(batchQuota + desiredChunksPerTick, max(1, desiredChunksPerTick))`, bail if `< 1` (`:98-110`).
  - Iterate pending lists nearest-distance-first; per record: drop if `!isValid` or already `isLoadedToPlayer`; look up `ChunkHolder` via `IEChunkMap.ip_getChunkHolder`; **skip (keep queued) if holder null or `getTickingChunk()` null** (`:151-160`); otherwise set `isLoadedToPlayer = true`, lazily send one `ClientboundChunkBatchStartPacket.INSTANCE` before the first chunk (`:164-167`), send the chunk, stop at quota.
  - After the loop: `ClientboundChunkBatchFinishedPacket(sentNum)` and `batchQuota -= sentNum` (`:182-186`).
- `sendChunkPacket` (`:193-210`) — inside `PacketRedirection.withForceRedirect(serverLevel, ...)` sends `new ClientboundLevelChunkWithLightPacket(levelChunk, serverLevel.getLightEngine(), null, null)`; i.e. the **vanilla full-chunk packet**, dimension-wrapped.
- `onSendPacket` (`:218-227`) — manual re-implementation of Fabric API's chunk attachment sync (`AttachmentTargetImpl.fabric_computeInitialSyncChanges` + `AttachmentChange.partitionAndSendPackets`) because IP cancels Fabric's `ChunkDataSenderMixin` by cancelling `sendNextChunks` (`:212-216`).
- `onChunkBatchReceivedByClient(float)` (`:233-242`) — vanilla copy: decrement `unacknowledgedBatches`, `desiredChunksPerTick = clamp(value, 0.01, 64)` (NaN → 0.01), reset quota to 1 when all acked, and raise `maxUnacknowledgedBatches` to 10.

### 2.7 `EntitySync` (~79 LOC, server)

**Responsibility:** replaces `ChunkMap.tick()`'s entity-tracker work for players in all dimensions (`EntitySync.java:19-22`); the real per-entity logic lives in the entity-sync slice (`IETrackedEntity`).

- `update(server)` (`:23-44`) — for every level, inside `PacketRedirection.withForceRedirect(world, ...)`, calls `ip_updateEntityTrackingStatus()` on every `ChunkMap.TrackedEntity` from `IEChunkMap.ip_getEntityTrackerMap()` (the `ChunkMap.entityMap` field, `MixinChunkMap_E.java:108-111`).
- `tick(server)` (`:46-73`) — same iteration; calls `ip_sendChanges()` only when `distanceManager.inEntityTickingRange(entity.chunkPosition().toLong())` (`:61-64`).
- `init()` registers an (empty) `SERVER_PRE_REMOVE_DIMENSION_EVENT` handler (`:15-17`, `:75-77`).
- Vanilla `ChunkMap.tick()` is cancelled outright by `MixinChunkMap_E.onTickEntityMovement` (`MixinChunkMap_E.java:82-85`).

### 2.8 `ImmPtlClientChunkMap` (~241 LOC, **client**, one per `ClientLevel`)

**Responsibility:** hash-map-backed `ClientChunkCache` replacement so the client can hold chunks with no view-center/radius bound (`ImmPtlClientChunkMap.java:36-40`).

- Two maps: `chunkMapForMainThread` (unsynchronized, main-thread only) and `chunkMapForOtherThreads` (synchronized) (`:46-53`); `readChunkMap`/`modifyChunkMap` pick by `Thread.currentThread()` (`:86-103`).
- Constructor passes load distance **1** to `super` to keep the unused vanilla array tiny (`:60-65`); main thread obtained via `IEMinecraftClient.ip_getRunningThread()`.
- `drop(ChunkPos)` (`:67-84`) — removes, fires `O_O.postClientChunkUnloadEvent`, `level.unload(chunk)`, Sodium hook, `clientChunkUnloadSignal`.
- `getChunk(x, z, status, create)` (`:105-115`) — map lookup, `emptyChunk` fallback.
- `replaceWithPacketData(x, z, buf, nbt, consumer)` (`:139-170`) — creates `new LevelChunk(level, pos)` if absent, `worldChunk.replaceWithPacketData(...)`, then `level.onChunkLoaded(pos)`, `O_O.postClientChunkLoadEvent`, Sodium hook, `clientChunkLoadSignal`. Deserialization failure prints a report-issue chat message and rethrows (`:176-205`).
- `replaceBiomes` (`:123-137`) — main-thread map variant of vanilla.
- `updateViewCenter` / `updateViewRadius` are **no-ops** (`:213-221`) — this is what removes the "sliding window" behavior entirely.
- `onLightUpdate(LightLayer, SectionPos)` (`:235-239`) — routes to the **per-dimension** renderer: `ClientWorldLoader.getWorldRenderer(level.dimension()).setSectionDirty(...)`.
- Signals `clientChunkLoadSignal` / `clientChunkUnloadSignal` (`:57-58`) — consumed by e.g. `ImmPtlViewArea` (`render/ImmPtlViewArea.java:70`).

### 2.9 `PerformanceLevel` (~93 LOC, common enum)

`good / medium / bad` plus tier tables (`PerformanceLevel.java:6-93`):
- Client level: FPS > 50 && free mem > 800 MB → good; FPS > 30 && > 300 MB → medium; else bad (`:9-22`).
- Server level: average tick time < 0.8× `tickRateManager().nanosecondsPerTick()` → good; < 1× → medium; else bad (`:25-40`).
- `getVisiblePortalRangeChunks`: 8/3/1 (`:42-52`); `getIndirectVisiblePortalRangeChunks`: 2/1/0 (`:54-64`); `getIndirectLoadingRadiusCap`: 32/7/2 (`:66-78`); `getPortalRenderingDistance`: full / max(2, half) / 2 (`:80-92`).

### 2.10 `ServerPerformanceMonitor` (~54 LOC, server)

Ticks on `END_SERVER_TICK` (`ServerPerformanceMonitor.java:17-19`); recomputes the server level at most every 20 seconds (`System.nanoTime` check, `:33-41`); starts at `bad` (`:13`) — i.e. shrink-loading until the first 20-second sample; if `IPGlobal.enableServerPerformanceAdjustment` is false, pinned to `good` (`:24-27`, flag default true `IPGlobal.java:124`).

### 2.11 `WorldInfoSender` (~100 LOC, server)

Registered on `END_SERVER_TICK`; when `gameTime % 100 == 42` (every 5 seconds) (`WorldInfoSender.java:19-21`), for every player: sync overworld time if the player is not in the overworld (`:26-30`), and for every *visible* (per `getVisibleDimensions`) non-overworld skylight dimension, send that dim's info (`:32-38`). `sendWorldInfo` (`:47-95`) sends redirected `ClientboundSetTimePacket(gameTime, dayTime, RULE_DAYLIGHT)` plus `ClientboundGameEventPacket` `START_RAINING` (only if raining), `RAIN_LEVEL_CHANGE`, `THUNDER_LEVEL_CHANGE`. `isNonOverworldSurfaceDimension` = `dimensionType().hasSkyLight() && dim != OVERWORLD` (`:97-99`).

---

## 3. Mechanisms

### 3.1 Update cadence (per-tick vs staggered)

All server-side driving happens in `ImmPtlChunkTracking.tick`, registered on Fabric `ServerTickEvents.END_SERVER_TICK` (`ImmPtlChunkTracking.java:45`):

1. **Per-player loader refresh, staggered:** each player is updated when `playerInfo.shouldUpdateImmediately` OR `(player.getId() % 13) == (gameTime % 13)` (`:371-373`) — so each player refreshes once per 13 ticks, spread across ticks by entity id.
2. **Purge + generation bump, every 13 ticks:** when `gameTime % 13 == 0` (`:379-384`): refresh global additional loaders (re-mark their chunks; collect their covered chunk sets, `:330-360`), run `purge`, then `generationCounter++`.
3. **Ticket flush, every tick:** `ImmPtlChunkTickets.get(world).tick(world)` for all levels (`:386-390`) — plus the extra flush after every `DistanceManager.runAllUpdates` (§3.4).
4. **Entity sync:** `EntitySync.update(server)` only on ticks where any player refresh or purge happened (`:394-396`); `EntitySync.tick(server)` every tick (`:398`).
5. **Chunk packet sending, every tick:** *not* in this method — vanilla calls `PlayerChunkSender.sendNextChunks(player)` per tick per player, and IP's `MixinPlayerChunkSender` hijacks that call into `PlayerChunkLoading.doChunkSending` (`MixinPlayerChunkSender.java:48-56`). (In 26.2 the vanilla call site is `MinecraftServer.tickChildren` → `player.connection.chunkSender.sendNextChunks(player)`, `mc262-ref/net/minecraft/server/MinecraftServer.java:1144`.)
6. **World info:** every 100 ticks at offset 42 (`WorldInfoSender.java:21`).
7. **Client→server performance report:** client samples FPS/memory every second, sends the computed `PerformanceLevel` every 5 seconds via `McRemoteProcedureCall.tellServerToInvoke(...RemoteCallables.acceptClientPerformanceInfo)` (`ClientPerformanceMonitor.java:37-65`, `:92-95`).

### 3.2 The watch-record turn algorithm (`updateForPlayer`)

For one player (`ImmPtlChunkTracking.java:160-237`):
1. Clear `visibleDimensions`, zero `loadedChunks` (remembering the old count is only used implicitly — `lastLoadedChunks` at `:163` is read nowhere else).
2. Collect loaders: `ChunkVisibility.foreachBaseChunkLoaders` into an `ObjectOpenHashSet` (dedup by record equality) + the player's `additionalChunkLoaders` (`:166-173`).
3. For each loader: resolve the `ServerLevel` (on missing dimension: warn and **return** — note this aborts the whole update, `:182-185`), add dim to `visibleDimensions`, then for every chunk position in the square (with Chebyshev `distanceToSource`):
   - `ticketInfo.markForLoading(chunkPos, distanceToSource, generationCounter)` (`:196`) — queue for ticket-based forcing.
   - Upsert the `PlayerWatchRecord` (`:198-234`): a **new** record starts `isLoadedToPlayer = false` and is queued via `playerInfo.markPendingLoading` and counts toward `loadedChunks`; an existing record seen **again in the same generation** (overlapping loaders) only takes the smaller distance (re-queuing at the closer distance) and ANDs `isBoundary`; an existing record seen at a **new generation** counts toward `loadedChunks`, re-queues only if the distance shrank, refreshes `lastWatchGeneration`, and overwrites `isBoundary`.

Note: `markPendingLoading` is also called for already-loaded records whose distance shrank — `doChunkSending` discards them via the `isLoadedToPlayer` check (`PlayerChunkLoading.java:134-137`).

### 3.3 Delayed unloading (`purge`)

Every 13 ticks (`ImmPtlChunkTracking.java:239-308`):
- A record is removed when `generationCounter - lastWatchGeneration > delayUnloadGenerations` where the delay is **4 generations** (~4×13 ticks ≈ 2.6 s of grace) normally, **2** if the player has >1200 loaded chunks, **1** if >2000 (`:258-259`, `:311-328`). Removed players (`player.isRemoved()`) are dropped immediately (`:253-255`).
- If the removed record had `isLoadedToPlayer`, the player receives a redirected `ClientboundForgetLevelChunkPacket` (`:261-272`); the record is marked `isValid = false` so pending-send queues discard it.
- Then per level, `ImmPtlChunkTickets.purge` keeps only chunks still present in the dim's watch-record map or in a global additional loader's chunk set (`:286-307`) — i.e. **ticket lifetime is coupled to watch-record lifetime**, so tickets also get the 4-generation grace.

### 3.4 Ticket throttling

- `markForLoading` only records intent; actual `addRegionTicket` happens in `flushThrottling`, which drains the per-distance queues **nearest ring first** while keeping at most **4 chunks in flight** (`waitingForLoading`, `ImmPtlChunkTickets.java:212-230`). A chunk leaves the in-flight set when its `ChunkHolder.getEntityTickingChunkFuture()` resolves (`:188-209`).
- `flushThrottling` is called (a) once per server tick from `ImmPtlChunkTracking.tick`, and (b) at the RETURN of every `DistanceManager.runAllUpdates(ChunkMap)` (`MixinDistanceManager.java:46-55`) — the javadoc explains ticking-only would throttle too slowly, and it must run after `runAllUpdates` because that's what updates the holders' futures (`ImmPtlChunkTickets.java:151-162`).
- Vanilla's own player-driven ticket machinery is turned off wholesale when `enableImmPtlChunkLoading`: `MixinPlayerTicketTracker` cancels `DistanceManager$PlayerTicketTracker.onLevelChange(JII)`, `onLevelChange(JIZZ)`, `updateViewDistance`, and `runAllUpdates` at HEAD (`MixinPlayerTicketTracker.java:11-25`). A companion NPE guard pre-creates the `playersPerChunk` set in `DistanceManager.removePlayer` (`MixinDistanceManager.java:36-44`).
- Chunk-generation forcing therefore rides entirely on the region-ticket API: `addRegionTicket(TICKET_TYPE, pos, radius 2|1, pos)` / matching `removeRegionTicket`.

### 3.5 Chunk packets to clients through vanilla paths

The pipeline for a chunk of a dimension the player is *not* in:
1. `updateForPlayer` creates a pending `PlayerWatchRecord` (§3.2).
2. Vanilla's per-tick `sendNextChunks` call → `PlayerChunkLoading.doChunkSending` (§2.6): vanilla batch flow control (`ClientboundChunkBatchStartPacket` / `ClientboundChunkBatchFinishedPacket`, quota driven by the client's `ServerboundChunkBatchReceivedPacket.desiredChunksPerTick`, routed to IP by `MixinServerGamePacketListenerImpl_ChunkSync.java:17-29`) — but across **all** dims through one connection-level quota.
3. Each chunk is sent as a **vanilla `ClientboundLevelChunkWithLightPacket`** built from the `ChunkHolder`'s ticking chunk and the level's light engine, wrapped by `PacketRedirection.withForceRedirect` (`PlayerChunkLoading.java:198-209`): while the thread-local force-redirect dimension is set, packets sent through `ServerGamePacketListenerImpl.send` get wrapped into the custom payload `i:r` = `(dimensionIntId varint, vanilla packet bytes)` (`PacketRedirection.java:47-48`, `:67-99`, `:135-174`, `:246-289`).
4. Client side, the payload handler re-enters on the client thread and handles the inner vanilla packet inside `ClientWorldLoader.withSwitchedWorldFailSoft(dimension, ...)` (`PacketRedirectionClient.java:45-77`), so vanilla `ClientPacketListener.handleLevelChunkWithLight` executes against the target `ClientLevel`, landing in `ImmPtlClientChunkMap.replaceWithPacketData` (§2.8).
5. Unloads are redirected `ClientboundForgetLevelChunkPacket`s from `purge`/`forceRemovePlayer`/`forceRemoveDimension`.
6. **No ACK ledger:** `isLoadedToPlayer` is set to true at send time (`PlayerChunkLoading.java:162`); correctness against client drop is handled by the batch-quota flow control plus the fact that redirected packets are handled through the same reliable connection.

### 3.6 Block updates / broadcasts for remote dimensions

- `MixinChunkHolder` wraps every packet passed to `ChunkHolder.broadcast` into a redirected message (`MixinChunkHolder.java:30-42`) and redirects `broadcastChanges`'s `PlayerProvider.getPlayers` to `ImmPtlChunkTracking.getPlayersViewingChunk(dim, x, z, boundaryOnly)` (`:49-62`) — the comment notes IP deliberately does **not** mixin `ChunkMap.getPlayers` so vanilla tracking coexists (`:44-48`). `boundaryOnly` is vanilla's light-update flag; IP maps it onto `PlayerWatchRecord.isBoundary`.
- `PlayerList.broadcast` (range-based, e.g. sounds) is `@Overwrite`n to iterate watch records with the radius predicate and send redirected (`MixinPlayerList.java:107-136`); `PlayerList.broadcastAll(packet, dimension)` is redirected similarly (`:64-82`).
- `syncBlockUpdateToClientImmediately` (used by block manipulation across portals) first forces `doChunkSending` for watchers that haven't received the chunks yet, then triggers `chunkHolder.broadcastChanges` (`ImmPtlChunkTracking.java:619-658`).

### 3.7 Dimension lifecycle & keep-alive

- A dimension with any watch record is prevented from vanilla's "no players → skip tick" fast path: `MixinServerLevel` redirects the `List.isEmpty()` check inside `ServerLevel.tick` to return false when `shouldLoadDimension(dim)` (`MixinServerLevel.java:40-54`).
- Dynamic dimension removal (DimLib event) → `ImmPtlChunkTracking.onDimensionRemove` (unload packets + record removal + loader removal, `ImmPtlChunkTracking.java:69-76`, `:519-552`) and `ImmPtlChunkTickets.onDimensionRemove` (full ticket removal via the real ticket sets, `ImmPtlChunkTickets.java:284-312`).

### 3.8 Player lifecycle

- **Login:** `PlayerList.placeNewPlayer` TAIL → `immediatelyUpdateForPlayer` (`MixinPlayerList.java:52-61`) — loaders computed, chunk packets sent, entity tracking updated in the same tick.
- **Respawn / disconnect:** `PlayerList.respawn` HEAD and `PlayerList.remove` HEAD → `removePlayerFromChunkTrackersAndEntityTrackers(oldPlayer)` (`MixinPlayerManager_MA.java:15-32`); also before a *vanilla* (non-portal) dimension change with a custom portal gen present (`MixinServerPlayerEntity_MA.java:45-58`).
- **IP teleport:** `ServerTeleportationManager.teleportPlayer` ends with `immediatelyUpdateForPlayer(player)` (`ServerTeleportationManager.java:415`), so the destination is loaded/sent immediately on crossing.

### 3.9 Performance adaptation summary

Client tier (reported every 5 s) caps the per-portal indirect radius (32/7/2) and, combined with `IPGlobal.indirectLoadingRadiusCap` (8), forms the loader cap; server tier (sampled every 20 s, starts `bad`) controls portal search ranges (8/3/1, 2/1/0 chunks), disables indirect loaders entirely when not `good` (`isShrinkLoading`), and — although a server-tier cap value is computed in `getCappedLoadingDistance` — that `cap3` is dead code (`ChunkVisibility.java:54-56`).

---

## 4. MC API touchpoint list (deduplicated; each needs a 26.2 mapping)

**Server chunk system**
1. `ServerChunkCache.chunkMap` (public field access) — `ImmPtlChunkTracking.java:60-62`, `PlayerChunkLoading.java:148`.
2. `ChunkMap.getVisibleChunkIfPresent(long)` (private, shadowed) — `MixinChunkMap_C.java:29`.
3. `ChunkMap.getUpdatingChunkIfPresent(long)` (protected, shadowed) — `MixinChunkMap_E.java:42`.
4. `ChunkMap.getPlayerViewDistance(ServerPlayer)` (shadowed; also vanilla-copied in `McHelper.java:241-245`).
5. `ChunkMap.applyChunkTrackingView(ServerPlayer, ChunkTrackingView)` — cancelled (`MixinChunkMap_C.java:61-70`).
6. `ChunkMap.onChunkReadyToSend(LevelChunk)` — `@Overwrite` no-op'd (`MixinChunkMap_C.java:76-79`).
7. `ChunkMap.tick()` — cancelled (`MixinChunkMap_E.java:82-85`).
8. `ChunkMap.entityMap : Int2ObjectMap<ChunkMap.TrackedEntity>` (private field, shadowed) — `MixinChunkMap_E.java:30-32`.
9. `ChunkMap.updatePlayerStatus(ServerPlayer, boolean)` (shadowed) — `MixinChunkMap_E.java:34-35`.
10. `ChunkMap.addEntity` / `ChunkMap.removeEntity` (inject/redirect points) — `MixinChunkMap_E.java:44-77`.
11. `ChunkMap.getDistanceManager()` — `EntitySync.java:33`.
12. `ChunkMap.TrackedEntity` (inner class; `removePlayer`, `updatePlayers` redirect) — `MixinChunkMap_E.java:48`, `:90`.
13. `ChunkHolder.getTickingChunk()` — `PlayerChunkLoading.java:155`, `ImmPtlChunkTracking.java:651`.
14. `ChunkHolder.getEntityTickingChunkFuture()` + `ChunkResult.isSuccess()` — `ImmPtlChunkTickets.java:194-201`.
15. `ChunkHolder.broadcastChanges(LevelChunk)` — `ImmPtlChunkTracking.java:653`.
16. `ChunkHolder.broadcast` (packet arg modified) and `ChunkHolder$PlayerProvider.getPlayers(ChunkPos, boolean)` (redirected) — `MixinChunkHolder.java:30-62`.
17. `DistanceManager.addRegionTicket(TicketType, ChunkPos, int, T)` / `removeRegionTicket(...)` — `ImmPtlChunkTickets.java:239-241`, `:268-270`, `:307`. **High-risk for 26.2** (ticket system rewritten; flags).
18. `DistanceManager.getTickets(long)` (private → accessor), `tickets` field, `mainThreadExecutor` field, `ticketThrottler` field (accessors) — `IEDistanceManager.java` (chunk_sync) `:15-22`, `MixinDistanceManager.java:57-60`.
19. `DistanceManager.runAllUpdates(ChunkMap)` (inject at RETURN) — `MixinDistanceManager.java:46-55`.
20. `DistanceManager.removePlayer(SectionPos, ServerPlayer)` + `playersPerChunk` field — `MixinDistanceManager.java:28-44`.
21. `DistanceManager$PlayerTicketTracker.onLevelChange(JII)`, `onLevelChange(JIZZ)`, `updateViewDistance`, `runAllUpdates` — all cancelled (`MixinPlayerTicketTracker.java:11-25`).
22. `DistanceManager.inEntityTickingRange(long)` — `EntitySync.java:62`.
23. `TicketType.create(String, Comparator)` / `Ticket.getType()` / `Ticket.getTicketLevel()` / `SortedArraySet<Ticket<?>>` — `ImmPtlChunkTickets.java:60-61`, `:298-308`.
24. `ChunkTaskPriorityQueueSorter.mailbox` accessor (`ProcessorMailbox<StrictQueue.IntRunnable>`) — declared `IEChunkTaskPriorityQueueSorter.java:9-13` (referenced by the throttling javadoc; verify if still needed at all in 26.2).
25. `ThreadedLevelLightEngine` (exposed via `IEChunkMap.ip_getLightingProvider`, `MixinChunkMap_C.java:48-51`).
55. `ChunkMap.getChunks()` — `@Invoker` accessor `IEChunkMap_Accessor.ip_getChunks()` (`mixin/common/chunk_sync/IEChunkMap_Accessor.java:8-12`), consumed by the `report_chunk_ticket_stat` debug command (`PortalDebugCommands.java:459`). **GONE in 26.2:** no `getChunks` on `ChunkMap`; the holder data is the `updatingChunkMap`/`visibleChunkMap` fields (`mc262-ref/net/minecraft/server/level/ChunkMap.java:127-128`); replacement = `@Accessor` on `visibleChunkMap` + `.values()`. A blindly ported `@Invoker` with a missing target crashes at mixin apply. (Numbered 55 — appended by the verification pass — to keep the existing #26-#54 cross-references stable; see api-map #55.)

**Chunk sending / networking**
26. `PlayerChunkSender.markChunkPendingToSend`, `dropChunk`, `sendNextChunks`, `onChunkBatchReceivedByClient`, `isPending` — all overwritten/cancelled (`MixinPlayerChunkSender.java:30-75`); its send logic vanilla-copied in `PlayerChunkLoading`. 26.2 call site: `MinecraftServer.java:1144` (mc262-ref).
27. `ClientboundLevelChunkWithLightPacket(LevelChunk, LevelLightEngine, BitSet, BitSet)` ctor — `PlayerChunkLoading.java:201-204`.
28. `ClientboundForgetLevelChunkPacket(ChunkPos)` — `ImmPtlChunkTracking.java:267-269`, `:510`, `:533`.
29. `ClientboundChunkBatchStartPacket.INSTANCE` / `ClientboundChunkBatchFinishedPacket(int)` — `PlayerChunkLoading.java:166`, `:183`.
30. `ServerboundChunkBatchReceivedPacket.desiredChunksPerTick()` + `ServerGamePacketListenerImpl.handleChunkBatchReceived` (inject) — `MixinServerGamePacketListenerImpl_ChunkSync.java:17-29`.
31. `ServerGamePacketListenerImpl.send(Packet)`, `.player` field — `PlayerChunkLoading.java:112-118`, `ImmPtlChunkTracking.java:263`.
32. `ServerCommonPacketListenerImpl.connection` field (accessor) + `Connection.isMemoryConnection()` — `IEServerCommonPacketListenerImpl.java:8-12`, `ImmPtlChunkTracking.java:141-144`.
33. `ClientboundCustomPayloadPacket` / `CustomPacketPayload(.Type)` / `StreamCodec` / `RegistryFriendlyByteBuf` / `GameProtocols.CLIENTBOUND_TEMPLATE.bind` / `ClientboundBundlePacket` / `BundleDelimiterPacket` — the redirection wrapper (`PacketRedirection.java:135-174`, `:234-289`). (Owned by the network slice but load-bearing here.)
34. `ClientboundSetTimePacket(long, long, boolean)` — `WorldInfoSender.java:53-59`.
35. `ClientboundGameEventPacket` + constants `START_RAINING`, `RAIN_LEVEL_CHANGE`, `THUNDER_LEVEL_CHANGE` — `WorldInfoSender.java:64-94`.
36. `PlayerList.placeNewPlayer`, `respawn`, `remove`, `broadcastAll`, `broadcast`, `sendLevelInfo`, `getViewDistance()` — `MixinPlayerList.java`, `MixinPlayerManager_MA.java`, `McHelper.java:232-234`.

**Server core / level**
37. `MinecraftServer.getAllLevels()`, `getLevel(ResourceKey)`, `getPlayerList()`, `overworld()`, `getProfiler()`, `isRunning()`, `tickRateManager()`, `getAverageTickTimeNanos()` — throughout (`ImmPtlChunkTracking.java:175-186`, `:362-399`; `ImmPtlChunkTickets.java:178`; `PerformanceLevel.java:25-40`).
38. `ServerTickRateManager.nanosecondsPerTick()` — `PerformanceLevel.java:26-29`.
39. `ServerLevel.tick`'s players-`List.isEmpty()` (redirect target for keep-alive) — `MixinServerLevel.java:41-54`.
40. `ServerLevel.dimension()`, `getChunkSource()`, `getLightEngine()`, `getGameTime()`, `getDayTime()`, `getGameRules()`, `isRaining()`, `getRainLevel(float)`, `getThunderLevel(float)`, `dimensionType().hasSkyLight()`.
41. `ServerPlayer.getId()`, `isRemoved()`, `requestedViewDistance()`, `chunkPosition()`, `position()`, `level()`, `.server`, `.connection` — `ImmPtlChunkTracking.java:371-372`, `:253`, `McHelper.java:244`, `ChunkVisibility.java:30-33`.
42. `ChunkPos.asLong(x,z)`, `ChunkPos.getX/getZ(long)`, `ChunkPos.toLong()`, `new ChunkPos(long)` — throughout.
43. `GameRules.RULE_DAYLIGHT` — `WorldInfoSender.java:56-58`.
44. `Mth.clamp` — `PlayerChunkLoading.java:236`, `McHelper.java:244`.

**Client**
45. `ClientChunkCache` — subclassed; overridden members: `drop`, `getChunk`, `replaceBiomes`, `replaceWithPacketData`, `updateViewCenter`, `updateViewRadius`, `gatherStats`, `getLoadedChunksCount`, `onLightUpdate`; inherited fields `level`, `emptyChunk`; ctor `super(clientWorld, 1)` — `ImmPtlClientChunkMap.java:43-239`.
46. `ClientLevel.<init>` (inject at RETURN to swap `chunkSource`) + the mutable `chunkSource` field — `MixinClientLevel.java:97-110`.
47. `ClientLevel.onChunkLoaded(ChunkPos)`, `unload(LevelChunk)` — `ImmPtlClientChunkMap.java:80`, `:162`.
48. `LevelChunk(ClientLevel, ChunkPos)` ctor, `replaceWithPacketData(FriendlyByteBuf, CompoundTag, Consumer)`, `replaceBiomes(FriendlyByteBuf)` — `ImmPtlClientChunkMap.java:150`, `:183`, `:135`.
49. `ClientboundLevelChunkPacketData.BlockEntityTagOutput` (consumer type) — `ImmPtlClientChunkMap.java:143`.
50. `ChunkStatus`, `LightLayer`, `SectionPos` — `ImmPtlClientChunkMap.java:106`, `:236`.

**Fabric API (loader-specific; needs multiloader mapping)**
51. `ServerTickEvents.END_SERVER_TICK` — `ImmPtlChunkTracking.java:45`, `ServerPerformanceMonitor.java:18`, `WorldInfoSender.java:19`.
52. `PayloadTypeRegistry.playS2C()` — `PacketRedirection.java:64`.
53. Fabric attachment sync internals `AttachmentTargetImpl.fabric_computeInitialSyncChanges` / `AttachmentChange.partitionAndSendPackets` (replacing the cancelled `ChunkDataSenderMixin`) — `PlayerChunkLoading.java:212-227`.
54. `qouteall.dimlib.api.DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT` — `ImmPtlChunkTracking.java:48-50`, `ImmPtlChunkTickets.java:72-74`, `EntitySync.java:16`.

---

## 5. Registration & wiring

**Common init (`IPModMain.init`)** — plain static-init calls, in order: `ImmPtlChunkTracking.init()` (`IPModMain.java:79`), `WorldInfoSender.init()` (`:81`), `EntitySync.init()` (`:85`), `ServerPerformanceMonitor.init()` (`:95`), `ImmPtlChunkTickets.init()` (`:97`). `PacketRedirection.init()` at `:69` registers the `i:r` payload type.

**Events:**
- `ServerTickEvents.END_SERVER_TICK`: tracking tick (`ImmPtlChunkTracking.java:45`), server perf monitor (`ServerPerformanceMonitor.java:18`), world info (`WorldInfoSender.java:19`).
- `IPGlobal.SERVER_CLEANUP_EVENT` (IP's own event, `IPGlobal.java:37-38`): clears the static maps (`ImmPtlChunkTracking.java:447-451`) and invalidates+clears ticket managers (`ImmPtlChunkTickets.java:331-336`).
- `DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT` (DimLib): tracking + tickets + entity-sync dimension removal.

**Mixins (this slice's wiring surface):**
- `mixin/common/chunk_sync/` (10 files): `MixinPlayerChunkSender` (disable vanilla sender, reroute `sendNextChunks`), `MixinServerGamePacketListenerImpl_ChunkSync` (batch-ack reroute), `MixinDistanceManager` (post-`runAllUpdates` flush + NPE guard + ticket-set accessor), `MixinPlayerTicketTracker` (disable vanilla player tickets), `MixinChunkHolder` (broadcast redirection to IP tracking), `MixinChunkMap_C` (duck impl + disable `applyChunkTrackingView`/`onChunkReadyToSend`), accessors `IEDistanceManager`, `IEServerCommonPacketListenerImpl`, `IEChunkTaskPriorityQueueSorter`, and `IEChunkMap_Accessor` (`@Invoker("getChunks")` on `ChunkMap`, `IEChunkMap_Accessor.java:8-12`; consumed by `PortalDebugCommands.java:459` — its target is GONE in 26.2, see §4 #55).
- `mixin/common/entity_sync/MixinChunkMap_E`: implements the `IEChunkMap` entity-side duck methods used by this slice (`ip_onPlayerUnload`, `ip_onDimensionRemove`, `ip_getEntityTrackerMap`) and cancels `ChunkMap.tick` (`MixinChunkMap_E.java:82-116`).
- `mixin/common/MixinServerLevel` (dimension tick keep-alive via `shouldLoadDimension`, `:41-54`).
- `mixin/common/other_sync/MixinPlayerList` (login hook `:52-61`; dimension/range broadcast rerouting `:64-136`).
- `platform_specific/mixin/common/MixinPlayerManager_MA` (respawn/disconnect cleanup) and `MixinServerPlayerEntity_MA` (vanilla dim-change cleanup with custom portal gen).
- Client: `mixin/client/MixinClientLevel` ctor-inject swaps `chunkSource` to `O_O.createMyClientChunkManager(...)` → `new ImmPtlClientChunkMap(world, loadDistance)` (`MixinClientLevel.java:97-110`, `O_O.java:87-90`).

**Config/globals gating behavior:**
- `IPConfig.enableImmPtlChunkLoading` (default true, `IPConfig.java:132`) gates ticket adding (`ImmPtlChunkTickets.java:234-236`), the extra flush (`MixinDistanceManager.java:51`), and the vanilla-tracker cancellation (`MixinPlayerTicketTracker.java:22`). Note: watch records / packet sending are **not** gated — only the forcing.
- `IPGlobal.indirectLoadingRadiusCap` (default 8, `IPGlobal.java:44`), `IPGlobal.activeLoading` (default true, `:54`), `IPGlobal.enableClientPerformanceAdjustment` / `enableServerPerformanceAdjustment` (`:123-124`).

**RPC:** the client's `ClientPerformanceMonitor.updateAndSend` invokes `ImmPtlChunkTracking.RemoteCallables.acceptClientPerformanceInfo` by name through `q_misc_util`'s `McRemoteProcedureCall` (`ClientPerformanceMonitor.java:92-95`) — the RPC framework is a `q_misc_util` dependency of this slice.

**No entity types, no registries** — this slice registers nothing in MC registries except the `TicketType` (created statically, not registry-registered in 1.21.3: `TicketType.create`, `ImmPtlChunkTickets.java:60-61`; note 26.2 moved ticket types toward a registry — verify in mapping stage) and the `i:r` payload type via Fabric's `PayloadTypeRegistry`.

---

## Notable fidelity flags (read before porting)

1. **Dead code to keep as-is:** `ChunkVisibility.portalLoadingRange`/`secondaryPortalLoadingRange` (`ChunkVisibility.java:24-25`) and the unused server-perf `cap3` in `getCappedLoadingDistance` (`:54`) — port verbatim per the zero-deviation rule.
2. **Missing-dimension early `return` (not `continue`) in `updateForPlayer`** (`ImmPtlChunkTracking.java:182-185`) aborts the remaining loaders for that player this turn. Faithful port must reproduce this.
3. `updateForPlayer`'s `lastLoadedChunks` local (`:163`) is write-only.
4. The 26.2 ticket system (region tickets, flags, `TicketType` structure) and `PlayerChunkSender` call-site differences are the highest-risk mapping items (touchpoints #17, #21, #23, #26).
5. `ImmPtlClientChunkMap.onLightUpdate` depends on per-dimension `LevelRenderer`s from `ClientWorldLoader` (render slice) — cross-slice contract.
