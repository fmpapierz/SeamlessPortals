# IP Common Mixins — Triage Inventory

**Slice:** `qouteall/imm_ptl/core/mixin/common/**` (74 Java files: 8 root + chunk_sync 10, collision 8, container_gui 3, debug 9, entity_sync 6, interaction 4, mc_util 4, miscellaneous 5, networking 1, other_sync 4, portal_generation 4, position_sync 7, registry 1).
**Source root (all citations below are relative to this unless another absolute path is given):**
`C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/imm_ptl/core/mixin/common/`
**IP baseline:** MC 1.21.3, Mojang mappings, Fabric.

Legend: **B** = behavior mixin (injects/overwrites), **A** = accessor/invoker-only, **D** = duck-interface implementation (adds `ip_*` methods/fields to a vanilla class), **∅** = registered but currently inert (body fully commented out or empty).
Side: everything in this slice is loaded in the `"mixins"` (common) section of `imm_ptl.mixins.json` (`.../src/main/resources/imm_ptl.mixins.json:6-81`), i.e. applied in both client and dedicated-server runtimes; "server" below means the injected code only ever runs on the logical server, "client-exec" means it runs on the client despite living in common.

---

## 1. Overview

This slice is the **server half of Immersive Portals' architecture**. IP's core trick on the server is that a player must simultaneously receive and stay synced with content from *multiple dimensions* (the dimension they stand in plus every dimension visible through nearby portals). Vanilla's server is built on the opposite assumption — one dimension per player, near-chunks only — so this slice surgically disables three vanilla subsystems and reroutes them into IP-owned managers: (1) **chunk sync** (`ChunkMap`/`DistanceManager`/`PlayerChunkSender` → `ImmPtlChunkTracking` + `ImmPtlChunkTickets` + `PlayerChunkLoading`), (2) **entity sync** (`ChunkMap.TrackedEntity`/`ServerEntity` → `EntitySync` driven off the same watch records), and (3) **player position sync** (every move/teleport packet is stamped with a dimension so the server can reject or re-route stale-dimension movement). All clientbound game packets that describe world content are wrapped by **PacketRedirection** into a custom payload (`"i:r"`) carrying the source dimension, so the client can apply them to the correct `ClientLevel` (the client-side unwrap for the payload class itself is also in this slice, `networking/MixinClientboundCustomPayloadPacket`, because the packet class is common code).

The remaining directories are smaller feature hooks that ride on the entity-portal design: **collision** (routes `Entity.move` through `PortalCollisionHandler` so entities collide with blocks on both sides of a portal), **interaction/container_gui** (cross-portal block breaking/using and container reach checks via `BlockManipulationServer`), **portal_generation** (item-based custom portal ignition hooks), **mc_util** (early-abort entity-section traversal primitives used by `McHelper` entity queries), plus assorted fixes (weather bleed, cross-dimension aggro, ender-pearl cross-dimension, dragon/fishing-hook teleport bans) and a set of mostly-inert debug mixins. Nearly every duck interface implemented here (`ducks/IE*`) is the API through which the rest of IP (`chunk_loading`, `teleportation`, `network`, `portal`) touches vanilla internals.

---

## 2. Class-by-class inventory

### 2.1 Root (`common/`) — 8 files

| Mixin | Target | Kind | Injections (@At) + purpose | Owning subsystem |
|---|---|---|---|---|
| `MixinClipContext` (91 LOC) | `net.minecraft.world.level.ClipContext` | B+D (`IERayTraceContext`) | Duck setters `ip_setStart`/`ip_setEnd` re-point the `@Mutable @Final` `from`/`to` fields (`MixinClipContext.java:28-56`) so portal-aware raytraces can re-segment one `ClipContext` across a portal; `@Inject getBlockShape` HEAD cancellable — returns `Shapes.empty()` for `PortalPlaceholderBlock` in OUTLINE mode when a `Portal` entity intersects the block, via `McHelper.traverseEntitiesByBox` (`:60-90`) | raytrace / block manipulation |
| `MixinConnection_Debug` (48 LOC) | `net.minecraft.network.Connection` | B (debug) | `@Inject exceptionCaught` HEAD — logs the first 5 netty exceptions at info level (vanilla only logs at debug) (`MixinConnection_Debug.java:27-36`) | debug |
| `MixinDedicatedServer` (9 LOC) | `DedicatedServer` | ∅ | Empty class body (`MixinDedicatedServer.java:6-9`) | — |
| `MixinLevel` (78 LOC) | `net.minecraft.world.level.Level` | B+D (`IEWorld`) | `@Inject prepareWeather` TAIL — zeroes `rainLevel/oRainLevel/thunderLevel/oThunderLevel` when `dimension() == Level.NETHER` (fixes overworld rain changing nether fog) (`MixinLevel.java:46-54`); ducks: `ip_getLevelData` (`:56-59`), `portal_setWeather` (writes the 4 gradient fields, `:61-67`), `portal_getEntityLookup` → shadowed `getEntities()` (`:69-72`), `portal_getThread` → `thread` field (`:74-77`, used by `PacketRedirection` thread check) | multiworld awareness |
| `MixinLivingEntity` (27 LOC) | `LivingEntity` | B | `@Inject tick` RETURN — clears `lastHurtByMob` when the attacker's `level()` differs from the victim's; also checks `getLastHurtMob()` but clears via `setLastHurtByPlayer(null)` (asymmetry in IP source, `MixinLivingEntity.java:12-25`) — stops cross-dimension aggro/combat-tracker staleness after crossing | teleportation aftercare |
| `MixinMinecraftServer` (31 LOC) | `MinecraftServer` | B+D (`IEMinecraftServer`) | `@Unique IPPerServerInfo ipPerServerInfo = new IPPerServerInfo()` — per-server-instance state container (`MixinMinecraftServer.java:15-16`); `@Inject runServer` RETURN → fires `IPGlobal.SERVER_CLEANUP_EVENT` (`:18-24`); duck `ip_getPerServerInfo` (`:26-29`) | lifecycle |
| `MixinServerChunkCache` (21 LOC) | `ServerChunkCache` | A+D (`IEServerChunkCache`) | Duck `ip_getDistanceManager` exposing the private `distanceManager` field (`MixinServerChunkCache.java:12-19`) | chunk loading |
| `MixinServerLevel` (77 LOC) | `ServerLevel` | B+D (`IEServerWorld`) | `@Redirect` in `tick(BooleanSupplier)` of `List.isEmpty()` — returns `false` when `ImmPtlChunkTracking.shouldLoadDimension(dim)`, forcing playerless-but-portal-watched dimensions to keep ticking (`MixinServerLevel.java:41-54`); `@Inject toString` HEAD — safe debug string (`:57-62`); `@Inject tickNonPassenger` HEAD → `((IEEntity)entity).ip_tickCollidingPortal()` — the server-side per-entity portal-collision tick, deliberately placed "right before setting last tick pos to this tick pos" (`:64-71`); duck `ip_getEntityManager` → `entityManager` (`PersistentEntitySectionManager`) (`:73-76`) | chunk loading + collision |

### 2.2 `chunk_sync/` — 10 files (LOAD-BEARING)

| Mixin | Target | Kind | Injections (@At) + purpose |
|---|---|---|---|
| `IEChunkMap_Accessor` | `ChunkMap` | A | `@Invoker("getChunks")` → `Iterable<ChunkHolder> ip_getChunks()` (`IEChunkMap_Accessor.java:10-11`) |
| `IEChunkTaskPriorityQueueSorter` | `ChunkTaskPriorityQueueSorter` | A | `@Accessor("mailbox")` → `ProcessorMailbox<StrictQueue.IntRunnable>` (`IEChunkTaskPriorityQueueSorter.java:11-12`) — **declared but unused**: `ip_getMailBox()` has zero call sites anywhere in IP; `ImmPtlChunkTickets` mentions the mailbox only in a javadoc comment (`ImmPtlChunkTickets.java:50`) and its actual throttle flush goes through `ip_getMainThreadExecutor` (`ImmPtlChunkTickets.java:163-185`) |
| `IEDistanceManager` (mixin accessor — distinct from the duck `ducks/IEDistanceManager`) | `DistanceManager` | A | `@Accessor tickets` → `Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>>`, `@Accessor mainThreadExecutor` → `Executor`, `@Accessor ticketThrottler` → `ChunkTaskPriorityQueueSorter` (`IEDistanceManager.java:15-22`) |
| `IEServerCommonPacketListenerImpl` | `ServerCommonPacketListenerImpl` | A | `@Accessor connection` → `Connection` (`IEServerCommonPacketListenerImpl.java:10-11`) |
| `MixinChunkHolder` | `ChunkHolder` | B+D (`IEChunkHolder` — an *empty marker* duck, `ducks/IEChunkHolder.java:3-5`) | see below |
| `MixinChunkMap_C` (priority **1100**) | `ChunkMap` | B+D (`IEChunkMap`, partial) | see below |
| `MixinDistanceManager` | `DistanceManager` | B+D (`ducks/IEDistanceManager`) | see below |
| `MixinPlayerChunkSender` | `PlayerChunkSender` | B (wholesale disable) | see below |
| `MixinPlayerTicketTracker` | `DistanceManager$PlayerTicketTracker` (string target) | B (wholesale disable) | see below |
| `MixinServerGamePacketListenerImpl_ChunkSync` | `ServerGamePacketListenerImpl` | B | see below |

**Per-mixin mechanism (chunk_sync):**

- **`MixinChunkHolder`** — Two hooks that convert vanilla's per-chunk broadcasting into IP's multi-dimension form. (1) `@ModifyVariable` on `broadcast` at HEAD (argsOnly) rewrites the outgoing `Packet<?>` into `PacketRedirection.createRedirectedMessage(server, level.dimension(), packet)` — every block-update/light-update packet a chunk holder broadcasts is dimension-stamped; it obtains the `ServerLevel` by casting the shadowed `levelHeightAccessor` field (`MixinChunkHolder.java:25-42`). (2) `@Redirect` in `broadcastChanges` of `ChunkHolder$PlayerProvider.getPlayers(ChunkPos, boolean)` → `ImmPtlChunkTracking.getPlayersViewingChunk(dim, x, z, boundaryOnly)` so block changes go to *everyone watching the chunk through any portal*, not just players vanilla-tracking it (`:49-62`). A comment records the design decision to NOT mixin `ChunkMap.getPlayers` so IP tracking *coexists* with vanilla tracking instead of deeply replacing it (`:44-48`).

- **`MixinChunkMap_C`** — Implements the chunk-facing half of the `IEChunkMap` duck: `ip_getPlayerViewDistance` → shadowed `getPlayerViewDistance(ServerPlayer)`, `ip_getWorld` → `level`, `ip_getLightingProvider` → `lightEngine`, `ip_getChunkHolder` → shadowed `getVisibleChunkIfPresent(long)` (`MixinChunkMap_C.java:38-56`). Behavior: `@Inject applyChunkTrackingView` HEAD `ci.cancel()` — vanilla's per-player square chunk-tracking-view (the thing that decides which chunk packets a player gets) is disabled outright; packet decisions live in `PlayerChunkLoading` instead (`:58-70`). `@Overwrite onChunkReadyToSend(LevelChunk)` → `ImmPtlChunkTracking.onChunkProvidedDeferred(chunk)` — when a chunk finishes loading to full, IP (not vanilla) is told it became sendable (`:72-79`).

- **`MixinDistanceManager`** — (1) `@Inject removePlayer(SectionPos, ServerPlayer)` HEAD pre-populates `playersPerChunk.computeIfAbsent(...)` to avoid an NPE, because IP calls remove for players vanilla never added in that dimension (`MixinDistanceManager.java:36-44`). (2) `@Inject runAllUpdates(ChunkMap)` RETURN → `ImmPtlChunkTickets.get(world).flushThrottling(world)` when `IPConfig.getConfig().enableImmPtlChunkLoading` — after vanilla drains its ticket updates, IP flushes its own throttled ticket additions into the same cycle (`:46-55`). (3) Duck `portal_getTicketSet(long)` → shadowed `getTickets(long)` (`:57-60`).

- **`MixinPlayerChunkSender`** — Disables the entire vanilla chunk-sending pipeline; class javadoc: "its implementation is based on single-dimension loaded and near-loading-only assumption" (`MixinPlayerChunkSender.java:17-20`). `@Overwrite markChunkPendingToSend` / `dropChunk` / `onChunkBatchReceivedByClient(float)` → empty no-ops (`:30-42, 62-65`); `@Overwrite isPending(long)` → logs an error with stack trace and returns false ("This should not be called", `:71-75`); `@Inject sendNextChunks(ServerPlayer)` HEAD cancellable → `ImmPtlChunkTracking.getPlayerInfo(serverPlayer).doChunkSending(serverPlayer)` then cancel — the vanilla per-connection-tick send cadence is kept as the *timer*, but the body is IP's (`:48-56`). Deliberately an inject-cancel rather than overwrite because Fabric API also mixins `sendNextChunks` (`net.fabricmc.fabric.mixin.attachment.ChunkDataSenderMixin`, comment `:44-47`).

- **`MixinPlayerTicketTracker`** — `@Inject` HEAD cancellable on **four** methods at once — `onLevelChange(JII)V`, `updateViewDistance`, `onLevelChange(JIZZ)V`, `runAllUpdates` — cancelling all of them when `enableImmPtlChunkLoading` (`MixinPlayerTicketTracker.java:11-25`). This kills vanilla's player-ticket propagation (the level-graph that turns "player stands here" into loading tickets); `ImmPtlChunkTickets` issues tickets instead. Targets the package-private inner class by string name.

- **`MixinServerGamePacketListenerImpl_ChunkSync`** — `@Inject handleChunkBatchReceived` at `INVOKE PlayerChunkSender.onChunkBatchReceivedByClient(F)` → forwards `packet.desiredChunksPerTick()` to `ImmPtlChunkTracking.getPlayerInfo(player).onChunkBatchReceivedByClient(...)` (`MixinServerGamePacketListenerImpl_ChunkSync.java:17-29`). This keeps **vanilla's chunk-batch flow control** (client-reported desired chunks/tick) feeding IP's sender; the original vanilla call still executes but is a no-op due to the `MixinPlayerChunkSender` overwrite.

### 2.3 `entity_sync/` — 6 files (LOAD-BEARING)

| Mixin | Target | Kind |
|---|---|---|
| `MixinChunkMap_E` | `ChunkMap` | B+D (`IEChunkMap`, entity half) |
| `MixinPersistentEntitySectionManager` | `PersistentEntitySectionManager` | D (empty marker `IEServerEntityManager`) |
| `MixinServerEntity` (priority **1200**) | `ServerEntity` | B+D (`IEEntityTrackerEntry`) |
| `MixinServerGamePacketListenerImpl_Redirect` | **`ServerCommonPacketListenerImpl`** (name is misleading) | B |
| `MixinServerPlayer` | `ServerPlayer` | D (`IEServerPlayerEntity`) |
| `MixinTrackedEntity` | `ChunkMap$TrackedEntity` | B+D (`IETrackedEntity`) |

**Per-mixin mechanism (entity_sync):**

- **`MixinChunkMap_E`** — (1) `@Redirect` in `addEntity` of `TrackedEntity.updatePlayers(List)` → `((IETrackedEntity)trackedEntity).ip_updateEntityTrackingStatus()` — a newly-added entity's watcher set is computed from IP watch records immediately, not from vanilla's player list (`MixinChunkMap_E.java:44-55`). (2) `@Inject removeEntity(Entity)` HEAD cancellable — if `ServerTeleportationManager.of(server).isTeleporting(entity)`, the tracker is removed from `entityMap` *without* broadcasting remove packets (a teleporting entity should stay visible on clients across the dimension change); a teleporting *player* additionally gets `ip_stopTrackingToAllPlayers()` + `updatePlayerStatus(player,false)` (`:57-77`). (3) `@Inject ChunkMap.tick()` HEAD `ci.cancel()` — the whole vanilla entity-tracking tick is disabled; `EntitySync` replaces it (`:82-85`). Duck impls: `ip_onPlayerUnload` (remove player from every tracker, `:87-92`), `ip_onDimensionRemove` (`:94-99`), `ip_resendSpawnPacketToTrackers` (validate tracked, `:101-106`), `ip_getEntityTrackerMap` → `entityMap` (`:108-111`), `ip_getUpdatingChunkIfPresent` (`:113-116`).

- **`MixinServerEntity`** — Redirects all three packet-send sites inside the per-entity sync object to dimension-stamped sends: `removePairing`, `addPairing`, and `broadcastAndSend` each `@Redirect ServerGamePacketListenerImpl.send(Packet)` → `PacketRedirection.sendRedirectedPacket(handler, packet, entity.level().dimension())` (`MixinServerEntity.java:44-90`), with optional debug logging behind `IPGlobal.entityTrackDebug`/`entityUntrackDebug`. `@Inject sendChanges` HEAD asserts a force-redirect context is active (`PacketRedirection.validateForceRedirecting()`, `:36-42`) — `sendChanges` may only run inside `EntitySync`'s `withForceRedirect`. A second `@Inject sendChanges` HEAD cancels entirely for `Portal` entities: `VecDeltaCodec` encodes 1/4096-block deltas, insufficient precision for portals, whose position is instead synced by `Portal.reloadAndSyncToClient()` (`:92-105`). Duck `ip_updateTrackedEntityPosition()` → `positionCodec.setBase(entity.trackingPosition())` — used before spawn-packet resend so the next delta packet isn't computed against a stale base (`:107-110`).

- **`MixinServerGamePacketListenerImpl_Redirect`** — The blanket redirection tap. Targets `ServerCommonPacketListenerImpl.send(Packet, PacketSendListener)`. (1) `@ModifyVariable` at HEAD: if `PacketRedirection.getForceRedirectDimension() != null`, *every* packet sent through this listener is wrapped via `createRedirectedMessage` (`MixinServerGamePacketListenerImpl_Redirect.java:22-38`) — this is what makes `withForceRedirect(world, ...)` blocks catch packets sent by arbitrary vanilla code paths. (2) `@Inject` at `INVOKE Connection.send(Packet, PacketSendListener, Z)` cancellable: if a `ForceBundleCallback` is installed, the packet is handed to the callback (bundle accumulation) and the real send cancelled (`:40-60`).

- **`MixinServerPlayer`** — Pure duck. `ip_stopRidingWithoutTeleportRequest`/`ip_startRidingWithoutTeleportRequest` call `super.stopRiding()`/`super.startRiding(v, true)` — i.e. the `Player` (not `ServerPlayer`) implementations, bypassing `ServerPlayer`'s overrides that emit teleport/dismount packets during IP-managed mounts (`MixinServerPlayer.java:32-40`). `portal_worldChanged(fromWorld, fromPos)` reproduces vanilla `changeDimension` side effects after an IP teleport: sets the shadowed `enteredNetherPosition` when OW→nether (for return-portal linking) and calls shadowed `triggerDimensionChangeTriggers(fromWorld)` (advancements) (`:45-51`).

- **`MixinTrackedEntity`** — The replacement watcher logic. `@Redirect broadcast` → wraps `ServerPlayerConnection.send` in `PacketRedirection.withForceRedirect(entity.level())` (`MixinTrackedEntity.java:61-77`); `@Redirect broadcastAndSend` → `sendRedirectedPacket` (`:79-94`). `@Overwrite updatePlayer(ServerPlayer)` and `updatePlayers(List)` → no-ops, with a javadoc mapping each vanilla update site to its IP replacement (`ChunkMap#move` and `ChunkMap#tick` → `ImmPtlChunkTracking.tick`; `ChunkMap#addEntity` → `MixinChunkMap_E.redirectUpdatePlayers`; login → `MixinPlayerList.onOnPlayerConnect`) (`:96-130`). The heart is `ip_updateEntityTrackingStatus()` (`:146-191`): fetch `ImmPtlChunkTracking.getWatchRecordForChunk(dim, entity.chunkPosition().x, .z)`; `seenBy.removeIf` any connection whose player no longer `watches(...)` (removePairing under force-redirect); then for every watch record that passes `recWatches`, `seenBy.add` + `addPairing` under force-redirect. `recWatches` requires: record exists, `rec.isLoadedToPlayer` (with a TODO about cross-portal collision needing earlier entity spawn than slow chunk sending allows, `:222-230`), entity != player, and `rec.distanceToSource * 16 + 8 <= getEffectiveRange()` (`:213-237`). Unlike vanilla (which skips updates when neither entity nor player moved), IP re-evaluates constantly because portals can change visibility at any time (comment `:137-145`). Other ducks: `ip_onDimensionRemove` (removePairing all + clear, `:239-245`), `ip_resendSpawnPacketToTrackers` (reset codec base → `entity.getAddEntityPacket(serverEntity)` → wrap redirected → send to all `seenBy`, `:247-260`), `ip_stopTrackingToAllPlayers` → `broadcastRemoved()` (`:262-265`), `ip_sendChanges` → `serverEntity.sendChanges()` (`:267-270`), `ip_get/setLastSectionPos` (`:272-280`).

- **`MixinPersistentEntitySectionManager`** — attaches empty marker duck `IEServerEntityManager` (`MixinPersistentEntitySectionManager.java:7-9`).

### 2.4 `position_sync/` — 7 files (LOAD-BEARING)

| Mixin | Target | Kind |
|---|---|---|
| `MixinPlayerPositionLookS2CPacket` | `ClientboundPlayerPositionPacket` | B+D (`IEPlayerPositionLookS2CPacket`) |
| `MixinServerboundMovePlayerPacket_S` | `ServerboundMovePlayerPacket` (base) | D (`IEPlayerMoveC2SPacket`) |
| `MixinServerboundMovePlayerPacketPos` / `...PosRot` / `...Rot` / `...StatusOnly` | the 4 inner packet classes | B |
| `MixinServerGamePacketListenerImpl` (priority **900**) | `ServerGamePacketListenerImpl` | B+D (`IEServerPlayNetworkHandler`) |

**Per-mixin mechanism (position_sync):**

- **`MixinPlayerPositionLookS2CPacket`** — Adds a (non-`@Unique`-annotated) `ResourceKey<Level> playerDimension` field with duck get/set (`MixinPlayerPositionLookS2CPacket.java:15-25`), and `@Inject write(FriendlyByteBuf)` RETURN → `buf.writeResourceKey(playerDimension)` (`:27-30`) — the dimension is **appended as extra raw bytes after the vanilla payload** of the server→client teleport packet. The matching client-side read is in the client mixin slice (`client.sync.MixinClientboundPlayerPositionPacket` per `imm_ptl.mixins.json:135`). This appended-bytes protocol change is what makes a vanilla client disconnect instantly when connecting through IP (acknowledged at `MixinServerGamePacketListenerImpl.java:132-134`).

- **`MixinServerboundMovePlayerPacket_S`** — the base-class duck field holder: `playerDimension` + `ip_get/setPlayerDimension` (`MixinServerboundMovePlayerPacket_S.java:10-21`). No injections.

- **`MixinServerboundMovePlayerPacketPos/PosRot/Rot/StatusOnly`** — four identical mixins, one per concrete move-packet subclass: `@Inject` the **static** `read(FriendlyByteBuf)` factory at RETURN → `buf.readResourceKey(Registries.DIMENSION)` and store it on the returned packet via the duck (e.g. `MixinServerboundMovePlayerPacketPos.java:16-22`). Server-side decode of the dimension the client-side mixins appended on write. Together with the S2C stamp, every position exchange carries "which dimension this coordinate is in".

- **`MixinServerGamePacketListenerImpl`** — the server position-sync brain; adds `@Unique @Nullable ResourceKey<Level> ip_dimOfAwaitingPosition` documenting vanilla's awaiting-teleport handshake (`MixinServerGamePacketListenerImpl.java:99-116`). Five behaviors:
  1. `@Inject handleMovePlayer` just after `PacketUtils.ensureRunningOnSameThread` (INVOKE, Shift.AFTER), cancellable (`:119-173`): read the packet's stamped dimension. `null` → the client has no IP → schedule a disconnect through `ServerTaskList` (`:131-142`). Mismatch with `player.level().dimension()` → *ignore the move packet* (rate-limited log via `CountDownInt(20)`), increment `ip_wrongMovePacketCount`; after >10 consecutive wrong packets, `ServerTeleportationManager.of(server).forceTeleportPlayer(player, serverDim, serverPos)` re-asserts server authority (`:144-169`). Match → reset the counter. This is the guard that prevents client/server dimension desync from corrupting coordinates.
  2. `@Overwrite teleport(double,double,double,float,float,Set<RelativeMovement>)` (`@IPVanillaCopy`, `:179-225`): vanilla copy plus — early-return if `player.getRemovalReason() != null` (respawn race, `:187-193`); optional `serverTeleportLogging`; sets `ip_dimOfAwaitingPosition = player.level().dimension()` alongside vanilla's `awaitingPositionFromClient`/`awaitingTeleport++` bookkeeping; constructs `ClientboundPlayerPositionPacket` and stamps it via `ip_setPlayerDimension` before sending (`:208-224`).
  3. `@Inject isPlayerCollidingWithAnythingNew` HEAD cancellable (`:227-272`): replaces the vanilla anti-cheat "moved wrongly / pushed into block" test with a portal-aware one when `IPGlobal.crossPortalCollision`: both old and new player AABBs are first clipped by `((IEEntity)player).ip_getActiveCollisionBox(...)` (null → not colliding, return false); then any collision shape of the new box that does **not** intersect the old active box counts as "colliding with something new" (`Shapes.joinIsNotEmpty(shape, oldShape, BooleanOp.AND)` false → true).
  4. `@Inject tick` HEAD (`:275-280`): while `ip_isRecentlyCollidingWithPortal()`, force `clientIsFloating = false` — standing on blocks on the other side of a portal must not trigger the fly-kick.
  5. `@Inject handleAcceptTeleportPacket` at `INVOKE ServerPlayer.absMoveTo` (`:284-322`): when the awaited teleport's dimension differs from the player's current dimension, resolve the dest level and `forceTeleportPlayer(player, dim, awaitingPositionFromClient, false)` — an accept for a cross-dimension teleport moves the player to the right world instead of applying the coords in the wrong one. Plus `@Inject handlePlayerCommand` at PUTFIELD `awaitingPositionFromClient` (`:324-336`): vanilla's stop-sleeping path sets an awaiting position directly, so IP stamps `ip_dimOfAwaitingPosition` there too. Duck: `ip_hasAwaitingTeleport()` → `awaitingPositionFromClient != null` (`:338-341`). Note: `vehicleLastGoodX/Y/Z`, `vehicleFirstGoodX/Y/Z`, `lastVehicle`, `clientVehicleIsFloating`, `isChangingDimension` are shadowed (`:59-81`; `MixinServerPlayer.java:23-24`) but **unused** in current code — leftovers.

### 2.5 `collision/` — 8 files

| Mixin | Target | Kind | Injections (@At) + purpose |
|---|---|---|---|
| `IEEntity_Collision` | `Entity` | A | `@Invoker("collideWithShapes")` static `Vec3 ip_CollideWithShapes(Vec3, AABB, List<VoxelShape>)` (`IEEntity_Collision.java:14-17`) — vanilla's core swept-AABB resolver, called by `PortalCollisionHandler` |
| `MixinAbstractArrow` | `AbstractArrow` | ∅ | Body fully commented out (placeholder-block hit skip; TODO at `MixinAbstractArrow.java:8`) |
| `MixinAbstractMinecartEntity` | `AbstractMinecart` | B (debug) | `@Inject lerpTo` RETURN — when `!IPGlobal.allowClientEntityPosInterpolation`, snap `setPos(x,y,z)` immediately (`MixinAbstractMinecartEntity.java:13-24`) |
| `MixinEntity` (387 LOC) | `Entity` | B+D (`IEEntity`, `ImmPtlEntityExtension`) | **The collision core** — see notes below |
| `MixinPlayer_Collision` | `Player` | B | `@Overwrite canPlayerFitWithinBlocksAndEntitiesWhen(Pose)` — pose-fit test uses `ip_getActiveCollisionBox` (null → fits), `noCollision(deflate 1e-7)` (`MixinPlayer_Collision.java:19-33`); reason comment: "mixin does not allow cancel in redirect" |
| `MixinProjectile` | `Projectile` | B — **extends `MixinEntity`** (mixin inheritance, `MixinProjectile.java:12`) | `@Redirect getOwner`'s `ServerLevel.getEntity(UUID)` → search **all** `server.getAllLevels()` so the owner is found after crossing dimensions (`:15-34`) |
| `MixinThrowableProjectile` | `ThrowableProjectile` | ∅ | Fully commented out (`MixinThrowableProjectile.java:8-44`) |
| `MixinThrownEnderPearl` | `ThrownEnderpearl` | B | `@Inject onHit` at `INVOKE discard()` — if owner is a `ServerPlayer` in a *different* level, connection accepting messages, not sleeping → `ServerTeleportationManager.teleportEntityGeneral(player, pearlPos, pearlLevel)` (cross-dimension pearl teleport; vanilla's own path only works same-dimension) (`MixinThrownEnderPearl.java:17-42`) |

**`MixinEntity` details** (state: one `@Unique @Nullable PortalCollisionHandler ip_portalCollisionHandler`, `MixinEntity.java:40-42`):
- `@Redirect` in `move(MoverType, Vec3)` of `Entity.collide(Vec3)` (`:83-138`): if `!IPGlobal.enableServerCollision` server-side → players pass through unclipped (`return attemptedMove`), non-players frozen (`Vec3.ZERO`); moves with `lengthSqr() > 60*60` skipped entirely (chunk-load DoS guard, rate-limited error log); with no active portal collision entries → vanilla `collide`; otherwise → `ip_portalCollisionHandler.handleCollision(this, attemptedMove)` with a `> 20*20` result-sanity clamp to `Vec3.ZERO`.
- `@Inject fireImmune` HEAD → `true` while the colliding portal is an `EndPortalEntity` (don't burn jumping into end portal; TODO generalize) (`:142-152`).
- `@Redirect getBoundingBox` inside `checkInsideBlocks` → `ip_getActiveCollisionBox(bb)` and companion `@Inject` (INVOKE_ASSIGN + LocalCapture) cancels the method when that box is null (`:154-179`) — block-inside effects (fire, powder snow, portals) only test the portal-clipped box.
- `@Inject isInWall` HEAD → `false` while recently colliding with a portal (no suffocation from the wall behind a portal) (`:182-187`).
- `@Inject setPosRaw` HEAD — debug: logs >10-block player jumps when `IPGlobal.teleportationDebugEnabled` (`:190-214`).
- `@Inject getInBlockState` HEAD → for an upward-facing colliding portal (`getNormal().y > 0`), returns the block at `collidingPortal.transformPoint(position())` in the destination world if loaded and non-air — makes ladder-climbing continue across a floor portal (`:217-239`).
- Duck implementations (`:253-381`): `ip_getCollidingPortal` (first entry of `portalCollisions`), `ip_tickCollidingPortal` (handler `.update(this)` + client-side `IPMcHelper.onClientEntityTick`; placement contract in comment `:264-266`: must run between last-tick-pos update and movement, because `CollisionHelper.getStretchedBoundingBox` uses the tick-pos delta), `ip_notifyCollidingWithPortal` (lazy-create handler + notify), `ip_isCollidingWithPortal`, `ip_isRecentlyCollidingWithPortal`, `ip_unsetRemoved`, `ip_setPositionWithoutTriggeringCallback` (`@IPVanillaCopy` of `setPosRaw` that updates `position`/`blockPosition`/`chunkPosition`/`inBlockState=null` **without** firing `EntityInLevelCallback.onMove`, `:315-340`), `ip_clearCollidingPortal`, `ip_getActiveCollisionBox` (delegates to handler; may return null = fully behind portal plane), handler get/set/getOrCreate, `ip_setWorld` (raw `level` field write used by teleportation, `:378-381`).

### 2.6 `container_gui/` — 3 files

| Mixin | Target | Kind | Injections (@At) + purpose |
|---|---|---|---|
| `MixinAbstractContainerMenu` | `AbstractContainerMenu` | B | `@WrapOperation` on **lambda `method_17696`** (intermediary name! — the `stillValid` helper lambda) of `Player.canInteractWithBlock(BlockPos,D)` → if vanilla reach fails, allow when `BlockManipulationServer.validateReach(player, world, pos)` (cross-portal container stays open) (`MixinAbstractContainerMenu.java:16-34`) |
| `MixinContainer` | `Container` (interface mixin) | B | `@WrapOperation stillValidBlockEntity(BlockEntity,Player,F)` of `canInteractWithBlock` → fall back to `validateReach` against the block entity's own `getLevel()`/`getBlockPos()` (`MixinContainer.java:20-42`) |
| `MixinContainerOpenersCounter` | `ContainerOpenersCounter` | B | `@Inject getPlayersWithContainerOpen` HEAD cancellable — replaces the local-AABB player scan with a check of **all** players on the server via shadowed `isOwnContainer(player)` ("the container could be opened via portal. the player could be anywhere in any dimension") (`MixinContainerOpenersCounter.java:23-38`) |

### 2.7 `debug/` — 9 files (5 inert)

| Mixin | Target | Kind | Purpose |
|---|---|---|---|
| `IEChunkHolder_Debug` | `ChunkHolder` | A | `@Invoker("updateFutures")` `(ChunkMap, Executor)` (`IEChunkHolder_Debug.java:12-13`) |
| `MixinChunkTaskPriorityQueue` | `ChunkTaskPriorityQueue` | ∅ | all commented out |
| `MixinClientboundSectionBlocksUpdatePacket_Debug` | `ClientboundSectionBlocksUpdatePacket` | ∅ | all commented out |
| `MixinDistanceManager_Debug` | `DistanceManager` | ∅ | all commented out |
| `MixinHashMapPalette` | `HashMapPalette` | B | `@Redirect write`'s `IdMap.getId` — throw a descriptive `RuntimeException` when id == -1 instead of corrupting the palette silently (`MixinHashMapPalette.java:12-27`) |
| `MixinLinearPalette` | `LinearPalette` | B | identical fail-fast (`MixinLinearPalette.java:12-27`) |
| `MixinPlayerTicketTracker_Debug` | `DistanceManager$PlayerTicketTracker` | ∅ | all commented out |
| `MixinServerChunkCacheMainThreadExecutor` | `ServerChunkCache$MainThreadExecutor` | ∅ | all commented out |
| `MixinServerLevel_Debug` | `ServerLevel` | B | `@Inject addEntity` HEAD cancellable — reject + error-log adding an entity whose `entity.level() != this` (guards IP's own dimension moves) (`MixinServerLevel_Debug.java:20-28`) |

### 2.8 `interaction/` — 4 files

| Mixin | Target | Kind | Injections (@At) + purpose |
|---|---|---|---|
| `IEClipContext` | `ClipContext` | A | `@Accessor block` / `@Accessor fluid` (`IEClipContext.java:9-13`) |
| `MixinBucketItem` | `BucketItem` | B | `@Redirect use`'s `getPlayerPOVHitResult` → `PortalUtils.portalAwareRayTraceFull(playerLevel, eyePos, viewVec, 5.0, player, OUTLINE, fluid, ..., 1)`; on hit, rewrites the method's `Level` argument via MixinExtras `@Local LocalRef<Level>` to the *hit* world so the bucket acts in the dimension behind the portal; on miss, synthesizes `BlockHitResult.miss` (`MixinBucketItem.java:22-59`; the 5.0 reach is hardcoded with a TODO `:40`) |
| `MixinItem_Interaction` | `Item` | ∅ | fully commented out (`MixinItem_Interaction.java:8-53`) |
| `MixinServerPlayerGameMode` (179 LOC) | `ServerPlayerGameMode` | B | Cross-portal block manipulation. `@Unique ServerLevel ip_destroyPosLevel`; helper `ip_getActualWorld()` returns `BlockManipulationServer.REDIRECT_CONTEXT.get().world()` when a redirect context is active else the real `level` (`MixinServerPlayerGameMode.java:41-52`). `@Redirect` GETFIELD `level` in `incrementDestroyProgress`/`handleBlockBreakAction`/`destroyAndAck`/`destroyBlock` and `ServerPlayer.level()` calls in the first two → `ip_getActualWorld()` (`:54-87`); `@Redirect NEW UseOnContext` in `useItemOn` → construct with the actual world (`:89-104`); `@WrapOperation canInteractWithBlock` in `handleBlockBreakAction` → `true` under redirect context (`:106-123`); `@Inject` at PUTFIELD `destroyPos`/`delayedDestroyPos` records `ip_destroyPosLevel` (`:125-155`); `@Redirect` GETFIELD `level` in `tick` → the recorded destroy world (`:157-171`); `@Inject tick` RETURN clears it when no destroy in progress (`:173-178`) |

### 2.9 `mc_util/` — 4 files

| Mixin | Target | Kind | Purpose |
|---|---|---|---|
| `IELevelEntityGetterAdapter` | `LevelEntityGetterAdapter` | A | `@Accessor sectionStorage` → `EntitySectionStorage<?>`, `@Accessor visibleEntities` → `EntityLookup<?>` (`IELevelEntityGetterAdapter.java:11-15`) |
| `MixinEntitySection` | `EntitySection` | D (`IEEntityTrackingSection`) | `ip_traverse(EntityTypeTest, Function<Sub,R>)` — `@IPVanillaCopy` of `getEntities(...)` that **early-returns** the first non-null `R` (abortable traversal without allocation) over the shadowed `ClassInstanceMultiMap storage` (`MixinEntitySection.java:29-47`) |
| `MixinEntitySectionStorage` | `EntitySectionStorage` | D (`IESectionedEntityCache`) | `ip_traverseSectionInBox(6 ints, Function<EntitySection,R>)` — `@IPVanillaCopy` of `forEachAccessibleNonEmptySection` using the `sectionIds` `LongSortedSet.subSet(SectionPos.asLong(cx,0,0), asLong(cx,-1,-1)+1)` per-X-slab trick, filtering Y/Z, `getStatus().isAccessible()`, early-return (`MixinEntitySectionStorage.java:36-64`). Backs `McHelper.traverseEntitiesByBox` used e.g. by `MixinClipContext` |
| `MixinEntity_U` | `Entity` | B | `@Inject setPosRaw` at `INVOKE EntityInLevelCallback.onMove()` → `IPEntityEventListenableEntity.ip_onEntityPositionUpdated()`; `@Inject setRemoved` RETURN → `ip_onRemoved(reason)` (`MixinEntity_U.java:12-33`). This is the event spine by which the **Portal entity itself** observes its own moves/removal (portal presence caches, chunk loaders) |

### 2.10 `miscellaneous/` — 5 files

| Mixin | Target | Kind | Purpose |
|---|---|---|---|
| `IEEndDragonFight` | `EndDragonFight` | A | `@Accessor needsStateScanning`, `@Invoker scanState` (`IEEndDragonFight.java:10-14`) — used by IP's end-portal replacement logic |
| `MixinBlockGetter` | `BlockGetter` (interface mixin, static method) | B | `@ModifyVariable` static `traverseBlocks` HEAD argsOnly index 1 — clamp raycasts longer than 512 blocks down to 30 blocks (portal-transformed rays can become huge; rate-limited error log) (`MixinBlockGetter.java:19-37`) |
| `MixinEnderDragon` | `EnderDragon` | D (`ImmPtlEntityExtension`) | `imm_ptl_canTeleportThroughPortal → false` (`MixinEnderDragon.java:10-13`) |
| `MixinFishingHook` | `FishingHook` | D (`ImmPtlEntityExtension`) | `imm_ptl_canTeleportThroughPortal → false` (`MixinFishingHook.java:10-13`) |
| `MixinLeashable` | `Leashable` | ∅ | empty; TODO "check whether a mixin for handling cross-world leash is needed" (`MixinLeashable.java:7-8`) |

### 2.11 `networking/` — 1 file

| Mixin | Target | Kind | Purpose |
|---|---|---|---|
| `MixinClientboundCustomPayloadPacket` | `ClientboundCustomPayloadPacket` | B+D (`IECustomPayloadPacket`) — **client-exec** | `@Inject handle(ClientCommonPacketListener)` HEAD cancellable — if the shadowed `payload` is a `PacketRedirection.Payload`, call `redirectPayload.handle((ClientGamePacketListener) listener)` and cancel; comment: "this is run before Fabric API try to handle the packet" (`MixinClientboundCustomPayloadPacket.java:19-37`). This is the **client-side unwrap of every redirected packet** — the hot path of the whole redirection system |

### 2.12 `other_sync/` — 4 files

| Mixin | Target | Kind | Purpose |
|---|---|---|---|
| `IEServerConfigurationPacketListenerImpl` | `ServerConfigurationPacketListenerImpl` | A | `@Accessor gameProfile` (`IEServerConfigurationPacketListenerImpl.java:10-11`) — used by `ImmPtlNetworkConfig` handshake |
| `MixinMapItemSavedData` | `MapItemSavedData` | ∅ | empty (`MixinMapItemSavedData.java:6-9`) |
| `MixinPlayerList` (priority **800**, 137 LOC) | `PlayerList` | B | (1) `@Inject sendLevelInfo` RETURN → `GlobalPortalStorage.onPlayerLoggedIn(player)` unless `ServerTeleportationManager.isFiringMyChangeDimensionEvent` (`MixinPlayerList.java:45-50`); (2) `@Inject placeNewPlayer` TAIL → `ImmPtlChunkTracking.immediatelyUpdateForPlayer(player)` — login immediately builds watch records + entity pairings (`:52-61`); (3) `@Inject broadcastAll(Packet, ResourceKey)` HEAD cancellable → re-implemented as per-player `PacketRedirection.sendRedirectedMessage` (`:64-82`); (4) `@Redirect respawn`'s `ServerPlayer.restoreFrom` → also re-points `newPlayer.connection.player = newPlayer` so the subsequent `teleport(...)` overwrite sees the correct player/dimension (`:84-100`); (5) `@Overwrite broadcast(excluding, x,y,z, distance, dim, packet)` ("mostly for sound events", "make incompat fail fast") → iterates `ImmPtlChunkTracking.getWatchRecordForChunk` for the source chunk; sends redirected packets to every player watching within `(int)distance + 16` chunk radius incl. cross-portal watchers (`:102-136`) |
| `MixinPlayer_Pose` | `Player` | B | `@Inject updatePlayerPose` HEAD cancellable — server-side only, skip pose update while `ip_isRecentlyCollidingWithPortal()` (server's portal collision status is not accurate; prevents wrong crouch/crawl through portals) (`MixinPlayer_Pose.java:12-26`) |

### 2.13 `portal_generation/` — 4 files (2 inert)

| Mixin | Target | Kind | Purpose |
|---|---|---|---|
| `MixinItemEntity_P` | `ItemEntity` | B | `@Inject tick` TAIL — server-side, not-removed, thrower-set item entities are fed to `IPPerServerInfo.of(server).customPortalGenManager.onItemTick(this)` (item-throw portal ignition, e.g. custom portal gen "throw item into frame"); wrapped in profiler push/pop `imm_ptl_item_tick` (`MixinItemEntity_P.java:24-51`) |
| `MixinItemStack` | `ItemStack` | B | `@Inject useOn` RETURN — server-side, forwards `(UseOnContext, InteractionResult)` to `customPortalGenManager.onItemUse` (item-use portal ignition) (`MixinItemStack.java:16-31`) |
| `MixinMinecraftServer_P` | `MinecraftServer` | ∅ | commented-out datapack-reload hook (`MixinMinecraftServer_P.java:8-11`) |
| `MixinPlayerList_P` | `PlayerList` | ∅ | commented-out datapack-reload hook (`MixinPlayerList_P.java:8-14`) |

### 2.14 `registry/` — 1 file

| Mixin | Target | Kind | Purpose |
|---|---|---|---|
| `IERegistryDataLoader` | `RegistryDataLoader` | ∅ | empty interface mixin (`IERegistryDataLoader.java:6-8`) |

---

## 3. Mechanisms

### 3.1 Packet redirection (the spine everything else hangs on)

`PacketRedirection` (`.../qouteall/imm_ptl/core/network/PacketRedirection.java`) wraps any `Packet<ClientGamePacketListener>` into a custom payload with the very short id `"i:r"` (`PacketRedirection.java:47-48`) that carries the source dimension; the client unwraps it and applies the inner packet to the matching `ClientLevel`. Two delivery modes exist and **both are implemented by mixins in this slice**:

1. **Explicit wrap** — call sites that know the packet's dimension wrap it directly with `createRedirectedMessage(server, dim, packet)` (`PacketRedirection.java:135`) or `sendRedirectedPacket(handler, packet, dim)` (`:111-128`, which skips double-wrapping if a force-redirect for the same dim is already active). Mixin hook points: `ChunkHolder.broadcast` (`chunk_sync/MixinChunkHolder.java:30-42`), `ServerEntity.addPairing/removePairing/broadcastAndSend` (`entity_sync/MixinServerEntity.java:44-90`), `TrackedEntity.broadcastAndSend` (`entity_sync/MixinTrackedEntity.java:79-94`), `PlayerList.broadcastAll`/`broadcast` (`other_sync/MixinPlayerList.java:64-82,102-136`), `TrackedEntity.ip_resendSpawnPacketToTrackers` (`entity_sync/MixinTrackedEntity.java:247-260`).
2. **Force-redirect context** — `withForceRedirect(world, runnable)` sets a `ThreadLocal<ResourceKey<Level>>` (`PacketRedirection.java:50-51,67-99`); while set, `entity_sync/MixinServerGamePacketListenerImpl_Redirect.modifyPacket` wraps **every** packet passing through `ServerCommonPacketListenerImpl.send` (`MixinServerGamePacketListenerImpl_Redirect.java:22-38`). This catches packets emitted by vanilla code IP does not individually hook (entity events, sounds, data sync during `sendChanges`). `MixinServerEntity.onTick` *asserts* the context is active at `sendChanges` HEAD (`MixinServerEntity.java:36-42`), which is guaranteed because `EntitySync.update/tick` always runs tracker work inside `withForceRedirect` (`.../chunk_loading/EntitySync.java:27-40,50-67`). A second thread-local `ForceBundleCallback` diverts sends into a bundle instead (`MixinServerGamePacketListenerImpl_Redirect.java:40-60`).
3. **Client unwrap** — `networking/MixinClientboundCustomPayloadPacket.java:24-37` intercepts `ClientboundCustomPayloadPacket.handle` at HEAD (before Fabric API) and dispatches `PacketRedirection.Payload.handle(clientGamePacketListener)`.

### 3.2 Chunk sync replacement

Vanilla pipeline disabled: `ChunkMap.applyChunkTrackingView` cancelled (`chunk_sync/MixinChunkMap_C.java:61-70`), all of `PlayerChunkSender` no-opped (`chunk_sync/MixinPlayerChunkSender.java:30-75`), `DistanceManager$PlayerTicketTracker` cancelled on all four entry methods behind `enableImmPtlChunkLoading` (`chunk_sync/MixinPlayerTicketTracker.java:11-25`). Replacement dataflow:

- **Watch records**: `ImmPtlChunkTracking` keeps per-dimension `chunkPos → (player → PlayerWatchRecord{distanceToSource, isLoadedToPlayer, ...})` maps, rebuilt per player on a spread schedule from portal visibility (`ImmPtlChunkTracking.java:362-399`; per-player update every `updateInterval` ticks or immediately on demand).
- **Tickets/loading**: `ImmPtlChunkTickets` (per dimension, `ImmPtlChunkTickets.get(world)`, `:104`) adds its own tickets; its throttled additions are flushed after vanilla's `DistanceManager.runAllUpdates` via `chunk_sync/MixinDistanceManager.java:46-55`, using the `mainThreadExecutor` accessor (`ip_getMainThreadExecutor`, `IEDistanceManager.java:19`, called at `ImmPtlChunkTickets.java:185`). The `ticketThrottler` and `mailbox` accessors (`IEDistanceManager.java:22`, `IEChunkTaskPriorityQueueSorter.java:11-12`) are declared but have no call sites anywhere in IP — the flush never posts into the vanilla throttle mailbox.
- **Chunk becomes sendable**: `ChunkMap.onChunkReadyToSend` overwritten → `ImmPtlChunkTracking.onChunkProvidedDeferred(chunk)` (`MixinChunkMap_C.java:76-79`).
- **Sending**: vanilla's per-tick `PlayerChunkSender.sendNextChunks` call is hijacked at HEAD into `PlayerChunkLoading.doChunkSending(player)` (`MixinPlayerChunkSender.java:48-56`), which respects vanilla batch flow control because `handleChunkBatchReceived` forwards `desiredChunksPerTick` into `PlayerChunkLoading.onChunkBatchReceivedByClient` (`MixinServerGamePacketListenerImpl_ChunkSync.java:17-29`).
- **Block-change broadcast**: `ChunkHolder.broadcastChanges` asks `ImmPtlChunkTracking.getPlayersViewingChunk` instead of the vanilla `PlayerProvider` (`MixinChunkHolder.java:49-62`), and every broadcast packet is dimension-wrapped (`:30-42`).
- **Dimension lifetime**: playerless dimensions keep ticking while watched (`MixinServerLevel.java:41-54`); `MixinDistanceManager.onHandleChunkLeave` protects `removePlayer` from NPEs for players vanilla never registered in that dim (`MixinDistanceManager.java:36-44`).

### 3.3 Entity sync replacement

`ChunkMap.tick()` is cancelled (`entity_sync/MixinChunkMap_E.java:82-85`) and `TrackedEntity.updatePlayer/updatePlayers` are no-ops (`entity_sync/MixinTrackedEntity.java:118-130`). The replacement, `EntitySync`, is invoked from `ImmPtlChunkTracking.tick` at END_SERVER_TICK: `EntitySync.update(server)` (recompute pairings — only on ticks where watch records changed) then `EntitySync.tick(server)` unconditionally (`ImmPtlChunkTracking.java:394-398`). `update` iterates every dimension's `entityMap` under `withForceRedirect` calling `ip_updateEntityTrackingStatus` (`EntitySync.java:23-44`); `tick` calls `ip_sendChanges` (→ `ServerEntity.sendChanges`) only for entities whose chunk is `distanceManager.inEntityTickingRange` (`EntitySync.java:46-73`). Pairing membership is decided purely by IP watch records + `getEffectiveRange()` (`MixinTrackedEntity.java:146-237`): a player sees an entity iff they hold a watch record for the entity's chunk, the chunk `isLoadedToPlayer`, and `distanceToSource*16+8 <= effectiveRange`. Add/remove pairing packets are emitted under force-redirect so they land in the right client dimension. Teleporting entities skip remove-packet broadcast on `removeEntity` (`MixinChunkMap_E.java:57-77`), and after a teleport the tracker's spawn packet can be resent with a freshly-based `VecDeltaCodec` (`MixinTrackedEntity.java:247-260`, `MixinServerEntity.java:107-110`). Portal entities never use `sendChanges` delta sync at all (1/4096 precision; `MixinServerEntity.java:92-105`).

### 3.4 Position sync (dimension-stamped movement protocol)

Both directions of the position protocol carry a dimension: S2C `ClientboundPlayerPositionPacket` gets `writeResourceKey` appended (`position_sync/MixinPlayerPositionLookS2CPacket.java:27-30`), C2S `ServerboundMovePlayerPacket$*` get `readResourceKey` appended in their static `read` (`MixinServerboundMovePlayerPacketPos.java:16-22` etc.; write side is in client mixins). The server then enforces: move packets whose dimension ≠ the player's server-side dimension are dropped (with force-teleport recovery after 10 strikes) (`MixinServerGamePacketListenerImpl.java:119-173`); the awaiting-teleport handshake tracks which dimension the awaited position belongs to and force-teleports on cross-dimension accept (`:179-225, 284-322`). This is a *wire-format change*: vanilla clients disconnect on the extra bytes (comment `:132-134`), and the server kicks clients that send unstamped move packets (`:131-142`).

### 3.5 Cross-portal collision & interaction hooks

Collision: `Entity.move`'s call to `collide` is routed through `PortalCollisionHandler.handleCollision` whenever the entity has active portal-collision entries (`collision/MixinEntity.java:83-138`); the handler is ticked per entity from `ServerLevel.tickNonPassenger` HEAD (`MixinServerLevel.java:64-71`, ordering contract at `MixinEntity.java:264-266`) and uses the invoker `IEEntity_Collision.ip_CollideWithShapes` for vanilla shape resolution. The "active collision box" (original AABB clipped against portal planes, possibly null) substitutes the raw AABB in: anti-cheat (`MixinServerGamePacketListenerImpl.java:227-272`), inside-block checks (`MixinEntity.java:154-179`), pose fitting (`MixinPlayer_Collision.java:19-33`), suffocation (`MixinEntity.java:182-187`), server pose updates (`MixinPlayer_Pose.java:12-26`), floating kick (`MixinServerGamePacketListenerImpl.java:275-280`).

Interaction: `BlockManipulationServer.REDIRECT_CONTEXT` (a thread-local set while handling IP's remote-interaction packets) swaps the effective `ServerLevel` inside `ServerPlayerGameMode` for break/use paths and defeats reach checks (`interaction/MixinServerPlayerGameMode.java:41-123`), with the destroy-in-progress world remembered across ticks (`:125-178`); container reach validation falls back to `BlockManipulationServer.validateReach` (`container_gui/MixinAbstractContainerMenu.java:16-34`, `MixinContainer.java:20-42`) and open-count scans all players (`MixinContainerOpenersCounter.java:23-38`).

---

## 4. MC API touchpoint list (deduplicated; every member this slice touches that can break across versions)

**Chunk system (server):**
- `ChunkMap` — private `getChunks()` (invoker); `getPlayerViewDistance(ServerPlayer)`; `getVisibleChunkIfPresent(long)`; `getUpdatingChunkIfPresent(long)`; fields `level`, `lightEngine` (`ThreadedLevelLightEngine`), `entityMap` (`Int2ObjectMap<TrackedEntity>`); `updatePlayerStatus(ServerPlayer, boolean)`; `applyChunkTrackingView(ServerPlayer, ChunkTrackingView)` (cancelled); `onChunkReadyToSend(LevelChunk)` (overwritten); `addEntity(Entity)`; `removeEntity(Entity)` (cancelled conditionally); `tick()` (cancelled)
- `ChunkMap$TrackedEntity` — fields `serverEntity`, `entity`, `seenBy` (`Set<ServerPlayerConnection>`), `lastSectionPos`; `broadcast(Packet)`; `broadcastAndSend(Packet)`; `updatePlayer(ServerPlayer)` + `updatePlayers(List)` (overwritten); `broadcastRemoved()`; `getEffectiveRange()`; `removePlayer(ServerPlayer)`
- `ChunkHolder` — `broadcast` (packet arg modified); `broadcastChanges` + `ChunkHolder$PlayerProvider.getPlayers(ChunkPos, boolean)` (redirected); field `levelHeightAccessor` (cast to `ServerLevel`!); private `updateFutures(ChunkMap, Executor)` (invoker)
- `DistanceManager` — fields `tickets`, `mainThreadExecutor`, `ticketThrottler`, `playersPerChunk`; `getTickets(long)`; `removePlayer(SectionPos, ServerPlayer)`; `runAllUpdates(ChunkMap)`; (`inEntityTickingRange(long)` used by `EntitySync`)
- `DistanceManager$PlayerTicketTracker` (package-private inner, string-targeted) — `onLevelChange(JII)V`, `onLevelChange(JIZZ)V`, `updateViewDistance`, `runAllUpdates` (all cancelled)
- `ChunkTaskPriorityQueueSorter` — field `mailbox` (`ProcessorMailbox<StrictQueue.IntRunnable>`); `ChunkTaskPriorityQueue`, `ServerChunkCache$MainThreadExecutor` (inert debug targets)
- `PlayerChunkSender` — `markChunkPendingToSend(LevelChunk)`, `dropChunk(ServerPlayer, ChunkPos)`, `sendNextChunks(ServerPlayer)`, `onChunkBatchReceivedByClient(float)`, `isPending(long)` (all disabled/replaced)
- `ServerChunkCache` — field `distanceManager`; `chunkMap`; `getChunkSource()`
- `Ticket`, `SortedArraySet`, `ChunkTrackingView`, `LevelChunk`, `ThreadedLevelLightEngine` (types in accessor/duck signatures)

**Networking / packets:**
- `ServerCommonPacketListenerImpl` — field `connection`; field `server`; `send(Packet, PacketSendListener)` (arg modified + conditionally cancelled)
- `ServerGamePacketListenerImpl` — fields `player`, `awaitingPositionFromClient`, `awaitingTeleport`, `awaitingTeleportTime`, `tickCount`, `clientIsFloating` (+ shadowed-but-unused `vehicleLastGoodX/Y/Z`, `vehicleFirstGoodX/Y/Z`, `lastVehicle`, `clientVehicleIsFloating`); `handleMovePlayer(ServerboundMovePlayerPacket)`; `teleport(DDDFF, Set<RelativeMovement>)` (**overwritten**, vanilla copy — 1.21.2+ signature changes to `PositionMoveRotation` style must be re-derived from 26.2 source); `isPlayerCollidingWithAnythingNew(LevelReader, AABB, DDD)`; `tick()`; `handleAcceptTeleportPacket` (+ `ServerPlayer.absMoveTo(DDDFF)` as anchor); `handlePlayerCommand` (PUTFIELD `awaitingPositionFromClient` anchor); `handleChunkBatchReceived`; `send(Packet)`; `disconnect(Component)`; `isAcceptingMessages()`
- `ServerPlayerConnection.send(Packet)`; `Connection` — `exceptionCaught(ChannelHandlerContext, Throwable)`, `send(Packet, PacketSendListener, boolean)` (injection anchor)
- `PacketUtils.ensureRunningOnSameThread(Packet, PacketListener, ServerLevel)` (injection anchor)
- `ClientboundPlayerPositionPacket` — constructor `(DDD FF, Set<RelativeMovement>, int)`; `write(FriendlyByteBuf)` (**appended bytes**; 26.2 uses `StreamCodec` — the write/read hook points must be re-found)
- `ServerboundMovePlayerPacket` + inner `Pos`/`PosRot`/`Rot`/`StatusOnly` — static `read(FriendlyByteBuf)` factories (**appended bytes**, same StreamCodec caveat); `getX/getY/getZ(double)`
- `ServerboundAcceptTeleportationPacket`, `ServerboundPlayerCommandPacket`, `ServerboundChunkBatchReceivedPacket.desiredChunksPerTick()`
- `ClientboundCustomPayloadPacket` — field `payload`; `handle(ClientCommonPacketListener)`; `CustomPacketPayload`
- `FriendlyByteBuf.writeResourceKey` / `readResourceKey(Registries.DIMENSION)`
- `ClientboundSectionBlocksUpdatePacket` (inert debug target)

**Entity sync:**
- `ServerEntity` — fields `entity`, `positionCodec` (`VecDeltaCodec`); `sendChanges()`; `addPairing(ServerPlayer)`; `removePairing(ServerPlayer)`; `broadcastAndSend(Packet)`
- `VecDeltaCodec.setBase(Vec3)`; `Entity.trackingPosition()`; `Entity.getAddEntityPacket(ServerEntity)`
- `ServerPlayer` — fields `connection`, `enteredNetherPosition`, `isChangingDimension`; `triggerDimensionChangeTriggers(ServerLevel)`; `restoreFrom(ServerPlayer, boolean)`; `getYRot/getXRot`; `canInteractWithBlock(BlockPos, double)`; `getRemovalReason()`
- `PersistentEntitySectionManager` (marker duck target)

**Entity core / collision:**
- `Entity` — `move(MoverType, Vec3)` + private `collide(Vec3)` (redirected); private static `collideWithShapes(Vec3, AABB, List<VoxelShape>)` (invoker); `fireImmune()`; `checkInsideBlocks()` + `getBoundingBox()`; `isInWall()`; `setPosRaw(DDD)` (+ `EntityInLevelCallback.onMove()` as inner anchor); `setRemoved(RemovalReason)`; `getInBlockState()`; private fields `level`, `position`, `blockPosition`, `chunkPosition`, `inBlockState`; `tickCount`; `unsetRemoved()`; `getName()`; `chunkPosition()`; `level()`; `isRemoved()`; `getServer()`
- `Player` — `canPlayerFitWithinBlocksAndEntitiesWhen(Pose)` (**overwritten**); `updatePlayerPose()`; `getDimensions(Pose).makeBoundingBox(Vec3)`; `canInteractWithBlock`
- `LivingEntity` — `tick()`; `getLastHurtByMob/setLastHurtByMob`; `getLastHurtMob/setLastHurtByPlayer`
- `Projectile.getOwner()`; `ServerLevel.getEntity(UUID)`; `ThrownEnderpearl.onHit(HitResult)`/`discard()`; `AbstractMinecart.lerpTo(DDDFF, int)`; `AbstractArrow`, `ThrowableProjectile`, `FishingHook`, `EnderDragon`, `Leashable` (marker/inert)
- `EntitySection` — field `storage` (`ClassInstanceMultiMap`), `getStatus().isAccessible()`; `EntitySectionStorage` — fields `sectionIds` (`LongSortedSet`), `sections`; `LevelEntityGetterAdapter` — fields `sectionStorage`, `visibleEntities`; `EntityTypeTest.getBaseClass()/tryCast()`; `SectionPos.asLong/y/z/blockToSectionCoord`

**Level / server:**
- `Level` — `prepareWeather()`; fields `rainLevel/oRainLevel/thunderLevel/oThunderLevel`, `levelData` (`WritableLevelData`), `thread`; `getEntities()` (`LevelEntityGetter`); `dimension()`; `isClientSide()`; `hasChunkAt(BlockPos)`; `getBlockState`; `getGameTime()`; `getProfiler()`
- `ServerLevel` — `tick(BooleanSupplier)` (the `List.isEmpty()` redirect on the players list); `tickNonPassenger(Entity)`; `addEntity(Entity)`; `toString()`; fields `serverLevelData`, `entityManager`; `getDataStorage()`; `getChunkSource()`; `getCollisions(Entity, AABB)`
- `MinecraftServer` — `runServer()`; `getAllLevels()`; `getPlayerList()`; `getLevel(ResourceKey)`; `overworld()`; `DedicatedServer` (inert)
- `PlayerList` — `sendLevelInfo(ServerPlayer, ServerLevel)`; `placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)`; `broadcastAll(Packet, ResourceKey)`; `respawn` (`restoreFrom` redirect); `broadcast(Player, DDDD, ResourceKey, Packet)` (**overwritten**); fields `players`, `server`
- `ServerConfigurationPacketListenerImpl` — field `gameProfile`

**Interaction / misc:**
- `ServerPlayerGameMode` — fields `level`, `player`, `destroyPos`, `delayedDestroyPos`, `hasDelayedDestroy`, `isDestroyingBlock`; `incrementDestroyProgress`, `handleBlockBreakAction`, `destroyAndAck`, `destroyBlock`, `useItemOn`, `tick`; `UseOnContext` constructor `(Player, InteractionHand, BlockHitResult)` / `(Level, Player, InteractionHand, ItemStack, BlockHitResult)`
- `ClipContext` — fields `from`, `to` (made `@Mutable`), `block`, `fluid`, `collisionContext`; `getBlockShape(BlockState, BlockGetter, BlockPos)`
- `BlockGetter.traverseBlocks(Vec3, Vec3, C, BiFunction, Function)` (static interface method, `@ModifyVariable`)
- `AbstractContainerMenu` **lambda `method_17696`** (intermediary name — will NOT resolve under Mojang mappings on 26.2; re-anchor required); `Container.stillValidBlockEntity(BlockEntity, Player, float)`; `ContainerOpenersCounter.getPlayersWithContainerOpen(Level, BlockPos)` / `isOwnContainer(Player)`
- `HashMapPalette.write` / `LinearPalette.write` / `IdMap.getId(Object)`
- `EndDragonFight` — field `needsStateScanning`, private `scanState()`
- `ItemEntity` — `tick()`, `getItem()`, field `thrower` (UUID); `ItemStack.useOn(UseOnContext)`; `BucketItem.use` + `getPlayerPOVHitResult(Level, Player, ClipContext.Fluid)`
- `RegistryDataLoader`, `MapItemSavedData` (inert)
- `Shapes.create/empty/joinIsNotEmpty`, `BooleanOp.AND`, `VoxelShape`, `AABB.deflate/move`, `BlockHitResult.miss`, `Direction.getNearest`, `RelativeMovement`, `CommonListenerCookie`, `GameProfile`, `Registries.DIMENSION`

---

## 5. Registration & wiring

- **Mixin registration**: all 74 classes are listed in the `"mixins"` (common) array of `imm_ptl.mixins.json` (`.../src/main/resources/imm_ptl.mixins.json:6-81`), package root `qouteall.imm_ptl.core.mixin`, `defaultRequire: 1` (`:145-147`). The config plugin `qouteall.imm_ptl.core.IPMixinPlugin` (`:5`) only filters two *client render* mixins when `porting_lib` is loaded (`.../qouteall/imm_ptl/core/IPMixinPlugin.java:23-30`) — no common mixin is conditional at apply time. Priorities that matter for ordering: `MixinPlayerList` 800, `MixinServerGamePacketListenerImpl` 900, `MixinChunkMap_C` 1100, `MixinServerEntity` 1200.
- **Runtime managers wired by Fabric events** (the mixins are passive taps; these drive them): `ImmPtlChunkTracking.init()` registers `ServerTickEvents.END_SERVER_TICK → ImmPtlChunkTracking::tick` (`ImmPtlChunkTracking.java:45`), which in turn runs per-player watch updates, `ImmPtlChunkTickets.tick`, then `EntitySync.update` (when watches changed) and `EntitySync.tick` every tick (`:362-399`). `EntitySync.init()` registers `DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT` (`EntitySync.java:15-17`). `ServerTeleportationManager` ticks off `END_SERVER_TICK` too (`ServerTeleportationManager.java:66-67`).
- **Payload registration**: `PacketRedirection.init()` registers the redirect payload with Fabric's `PayloadTypeRegistry.playS2C()` (`PacketRedirection.java:63-65`) — this is Fabric-API-specific plumbing the multiloader port must replace per-loader; the *unwrap* path is loader-neutral (the common mixin `MixinClientboundCustomPayloadPacket`).
- **Per-server state**: `IPPerServerInfo` is instantiated eagerly as a `@Unique` field on `MinecraftServer` (`MixinMinecraftServer.java:15-16`) and reached everywhere via `IPPerServerInfo.of(server)`; server shutdown fires `IPGlobal.SERVER_CLEANUP_EVENT` from `runServer` RETURN (`:18-24`).
- **Config/flag gates** referenced by this slice: `IPConfig.getConfig().enableImmPtlChunkLoading` (`MixinPlayerTicketTracker.java:22`, `MixinDistanceManager.java:51`), `IPConfig.serverTeleportLogging` (`MixinServerGamePacketListenerImpl.java:195`); `IPGlobal.enableServerCollision`, `IPGlobal.crossPortalCollision` (`MixinEntity.java:91,115`; `MixinServerGamePacketListenerImpl.java:233`), `IPGlobal.allowClientEntityPosInterpolation` (`MixinAbstractMinecartEntity.java:21`), `IPGlobal.entityTrackDebug/entityUntrackDebug` (`MixinServerEntity.java:55,72`), `IPGlobal.teleportationDebugEnabled` (`MixinEntity.java:198`), `IPGlobal.maxNormalPortalRadius` (`MixinClipContext.java:78`).
- **Duck interfaces implemented/exposed by this slice** (the cross-subsystem API surface): `IEChunkMap` (split across `MixinChunkMap_C` + `MixinChunkMap_E`), `IETrackedEntity`, `IEEntityTrackerEntry`, `IEServerPlayerEntity`, `IEServerPlayNetworkHandler`, `IEPlayerMoveC2SPacket`, `IEPlayerPositionLookS2CPacket`, `IEEntity` (+ public API `ImmPtlEntityExtension`), `IEWorld`, `IEServerWorld`, `IEServerChunkCache`, `IEMinecraftServer`, `IECustomPayloadPacket`, `IERayTraceContext`, `IESectionedEntityCache`, `IEEntityTrackingSection`, `IEServerEntityManager` (marker), `IEChunkHolder` (marker), `ducks/IEDistanceManager`.

---

## Port-risk notes (facts observed, no solutions proposed)

1. **`entity_sync/MixinServerGamePacketListenerImpl_Redirect` targets `ServerCommonPacketListenerImpl`, not `ServerGamePacketListenerImpl`** (`MixinServerGamePacketListenerImpl_Redirect.java:18-19`) — the class name lies; don't triage by name.
2. **`MixinProjectile` extends `MixinEntity`** (`MixinProjectile.java:12`) — mixin-class inheritance; both apply to the same hierarchy.
3. **position_sync's appended-raw-bytes protocol** hooks `write(FriendlyByteBuf)`/static `read(FriendlyByteBuf)`; MC 26.2 packets are built on `StreamCodec` registration, so these exact injection points must be re-verified against 26.2 decompiled source before porting.
4. **`MixinAbstractContainerMenu` anchors on intermediary lambda name `method_17696`** (`MixinAbstractContainerMenu.java:17`) — Fabric-intermediary-specific; will not resolve under Mojang-mapped 26.2.
5. **`MixinChunkHolder` casts `levelHeightAccessor` to `ServerLevel`** (`MixinChunkHolder.java:36`) — depends on vanilla constructing `ChunkHolder` with the level as its height accessor.
6. **Inert-but-registered files** (safe to carry as empty or consciously drop-list, but they ARE in the JSON): `MixinDedicatedServer`, `MixinAbstractArrow`, `MixinThrowableProjectile`, `MixinItem_Interaction`, `MixinLeashable`, `MixinMapItemSavedData`, `MixinMinecraftServer_P`, `MixinPlayerList_P`, `IERegistryDataLoader`, and 5 of 9 debug mixins.
7. **`MixinLivingEntity` asymmetry**: checks `getLastHurtMob()` but clears `setLastHurtByPlayer(null)` (`MixinLivingEntity.java:20-23`) — faithful port means keeping this as-is.
8. **`teleport(...)` overwrite is a vanilla copy** (`@IPVanillaCopy`, `MixinServerGamePacketListenerImpl.java:179-225`) — must be re-copied from 26.2's method body, not transplanted from 1.21.3.
9. `MixinPlayerChunkSender.sendNextChunks` is inject-cancel (not overwrite) specifically for **Fabric API compatibility** (`MixinPlayerChunkSender.java:44-47`) — the NeoForge side of the multiloader port has a different coexistence story to check.
