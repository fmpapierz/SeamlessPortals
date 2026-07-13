# Adversarial Verification — Common Mixins Triage

**Docs verified:**
- `migration/inventory/mixin-common.md` (inventory)
- `migration/api-map/mixin-common.md` (api-map)

**Ground truth used:** IP source `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/imm_ptl/core/mixin/common/**` (+ `chunk_loading`, `commands`, `ducks`), 26.2 decompiled `C:/Users/warwa/ModDev/mc262-ref`, and a fresh directory listing of the slice.

**Result: 39 claims checked, 35 confirmed, 4 refuted (all minor). No refuted claim misdirects architecture, geometry, or an API fate. Severity: minor.**

---

## 1. Scope check (silently-skipped files)

Fresh `find` of the slice returns **74** .java files: root 8, chunk_sync 10, collision 8, container_gui 3, debug 9, entity_sync 6, interaction 4, mc_util 4, miscellaneous 5, networking 1, other_sync 4, portal_generation 4, position_sync 7, registry 1. Every file appears in both docs' tables — **nothing was skipped**.

**REFUTED (minor) — inventory:** header says "73 Java files" (`migration/inventory/mixin-common.md:3`) while its own per-directory counts on the same line sum to 74 and the directory truly holds 74. The api-map already noticed this (`migration/api-map/mixin-common.md:3`). Correction: 74 files; also §5 "all 73 classes are listed" (inventory `:315`) should read 74.

---

## 2. Claim-by-claim verification

Verdicts: ✅ CONFIRMED (re-derived from source), ❌ REFUTED (correction given).

### Root mixins

| # | Claim (doc) | Verdict | Evidence |
|---|---|---|---|
| 2 | inventory: `MixinLevel` injects `prepareWeather` TAIL zeroing 4 gradient fields for NETHER; ducks for `levelData`/weather/`getEntities()`/`thread` | ✅ | IP `MixinLevel.java:46-54` (inject), `:56-77` (ducks) — exactly as described |
| 3 | api-map: `Level.prepareWeather()` GONE; now `private void prepareWeather(WeatherData)` on ServerLevel `:702`, driven from ctor; gated by `canHaveWeather()` | ✅ | 26.2 grep: no `prepareWeather` in `Level.java`; `ServerLevel.java:702` (private, `WeatherData` param), ctor call `:272-273` behind `canHaveWeather()`; `Level.java:857` declares `canHaveWeather()`; weather advance gate `ServerLevel.java:713` |
| 4 | api-map: gradient packets are dimension-scoped but the rain-state-flip broadcasts are un-dimensioned (cross-dim bleed IP guards against) | ✅ | `ServerLevel.java:774-784`: `broadcastAll(packet, this.dimension())` for RAIN/THUNDER_LEVEL_CHANGE; `:786-795`: flip branch `broadcastAll(...)` with **no** dimension arg (doc's ranges `:774-783`/`:785-795` are off by one line; immaterial) |
| 5 | api-map: duck targets survive — `thread` `:113`, gradient fields `:118-121`, `levelData` `:126`, `getEntities()` protected abstract `:1000` | ✅ | `Level.java:113,118-121,126,1000` — all exact |
| 6 | inventory: `MixinServerLevel` redirects `List.isEmpty()` in `tick(BooleanSupplier)`; `tickNonPassenger` HEAD → `ip_tickCollidingPortal` | ✅ | IP `MixinServerLevel.java:41-54`, `:64-71` |
| 7 | api-map: that `List.isEmpty()` call is GONE from 26.2 `tick`; gate is now `chunkSource.hasActiveTickets()` `:408` (`resetEmptyTime` `:410`, `emptyTime++` `:414`, `< 300` `:417`) | ✅ | `ServerLevel.java:408-417` exact; grep confirms the only `isEmpty()` hits in the file (`:633,:671,...`) are in other methods (`findLightningTargetAround`, `updateSleepingPlayerList`); `hasActiveTickets()` declared `ServerChunkCache.java:477` |
| 8 | inventory: `MixinLivingEntity` asymmetry — checks `getLastHurtMob()` but clears via `setLastHurtByPlayer(null)` | ✅ | IP `MixinLivingEntity.java:15-23` (1-arg null clear at `:22`) |
| 9 | api-map: `lastHurtByMob` now `EntityReference<LivingEntity>` `:256`; 1-arg `setLastHurtByPlayer` gone → `(Player,int)` `:632` / `(UUID,int)` `:636`; attacker resolves cross-dimension via `getEntityInAnyDimension` | ✅ | 26.2 `LivingEntity.java:256,616,632,636,645-647,650`; `EntityReference.java:80-81` routes through `level::getEntityInAnyDimension`; `Level.java:785` |

### chunk_sync

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 10 | api-map: `onChunkReadyToSend` re-signatured `(ChunkHolder, LevelChunk)` `:690` and now ALSO feeds `ServerChunkCache.onChunkReadyToSend(chunkHolder)` (broadcast queue) which the overwrite must preserve; `applyChunkTrackingView` survives `:1109` | ✅ | `ChunkMap.java:690-701` (per-player sends `:693-697`, `this.level.getChunkSource().onChunkReadyToSend(chunkHolder)` `:699`, debugSynchronizers `:700`); `ServerChunkCache.java:586-588` adds to `chunkHoldersToBroadcast` (field `:74`, drained `:356-363`); `applyChunkTrackingView` `ChunkMap.java:1109` |
| 11 | api-map: `IEChunkMap_Accessor` TARGET-GONE — no `getChunks`; storage is `updatingChunkMap`/`visibleChunkMap` `:127-128`; `allChunksWithAtLeastStatus` `:708`; only IP call site `PortalDebugCommands.java:459` | ✅ | grep of `ChunkMap.java`: zero `getChunks`; fields `:127-128`; `:708`; IP grep: sole active call site `PortalDebugCommands.java:459` (second at `:540` is commented out) |
| 12 | api-map: `ChunkTaskPriorityQueueSorter` + `ProcessorMailbox` both deleted; replaced by `ChunkTaskDispatcher`/`ThrottlingChunkTaskDispatcher`; reachable as `DistanceManager.ticketDispatcher` `:44` | ✅ | dir listing: `ChunkTaskDispatcher.java` + `ThrottlingChunkTaskDispatcher.java` present, no sorter; `net/minecraft/util/thread/` has no `ProcessorMailbox`; `DistanceManager.java:44` `private final ThrottlingChunkTaskDispatcher ticketDispatcher` |
| 13 | inventory: `IEChunkTaskPriorityQueueSorter`'s mailbox accessor "lets `ImmPtlChunkTickets` post into the vanilla ticket-throttle mailbox" | ❌ **REFUTED (minor)** | The accessor `ip_getMailBox()` (`IEChunkTaskPriorityQueueSorter.java:11-12`) has **zero call sites** anywhere in IP (case-insensitive grep of the whole `qouteall` tree). `ImmPtlChunkTickets` never touches it — the mailbox is mentioned only in a javadoc comment (`ImmPtlChunkTickets.java:50`); the actual throttle flush is `flushThrottling` via `ip_getMainThreadExecutor` (`ImmPtlChunkTickets.java:163-185`). The api-map states this correctly (internal contradiction between the two docs; api-map wins). Correction: the accessor is declared-but-unused; carrying it is optional dead surface |
| 14 | api-map: `IEDistanceManager` — `mainThreadExecutor` survives `:46`; `tickets` moved to `TicketStorage.tickets` `:43`; `getTickets(long)` → `TicketStorage:130`; region tickets → `addTicketWithRadius` `:138` / `removeTicketWithRadius` `:214`; `TicketStorage extends SavedData` `:33`; `Ticket` non-generic | ✅ | `DistanceManager.java:37-47` (no `tickets` field; `ticketStorage` `:40`, `ticketDispatcher` `:44`, `mainThreadExecutor` `:46`); `TicketStorage.java:33,43,130,138,147,214`; `new Ticket(TicketType.PLAYER_SIMULATION, ...)` `DistanceManager.java:115`; grep: no `addRegionTicket`/`removeRegionTicket` in `DistanceManager.java` |
| 15 | api-map: `removePlayer` still NPE-able (HEAD pre-populate still needed) `:118-122`; `runAllUpdates(ChunkMap)` `:64`; BUT `portal_getTicketSet` has "one call site" (`ImmPtlChunkTickets.removeAllTicketsInWorld`) | ✅ / ❌ **REFUTED (minor)** | NPE path confirmed: `DistanceManager.java:121` `playersPerChunk.get(chunkPos)` then `:122` `.remove(player)` with no null check; `runAllUpdates` `:64`. **"One call site" is wrong:** `portal_getTicketSet` has **three** active non-mixin call sites — `ImmPtlChunkTickets.java:299`, `PortalDebugCommands.java:466`, and `PortalDebugCommands.java:732`. Correction: the type retarget (`SortedArraySet<Ticket<?>>` → `List<Ticket>`) must also update both debug-command sites |
| 16 | api-map: `MixinPlayerChunkSender` PORTS-CLEAN — all five targets same shape | ✅ | `PlayerChunkSender.java:40` `markChunkPendingToSend(LevelChunk)`, `:44` `dropChunk(ServerPlayer, ChunkPos)`, `:50` `sendNextChunks(ServerPlayer)`, `:114` `onChunkBatchReceivedByClient(float)`, `:128` `isPending(long)` — exact |
| 17 | api-map: `PlayerTicketTracker` survives (now `private`) with all four cancelled methods; **WARNING** — `DistanceManager.addPlayer` now adds a `PLAYER_SIMULATION` ticket directly, uncovered by cancelling the tracker | ✅ | `DistanceManager.java:241` `private class PlayerTicketTracker`; `onLevelChange(long,int,int)` `:253`, `updateViewDistance(int)` `:257`, `onLevelChange(long,int,boolean,boolean)` `:267`, `runAllUpdates()` `:290-291`; the warning is real: `addPlayer` `:109-116` adds `new Ticket(TicketType.PLAYER_SIMULATION, getPlayerTicketLevel())` at `:115`, removed at `:127` — a player-driven loading path that does NOT flow through the tracker |
| 18 | api-map: `handleChunkBatchReceived` `:2167` with anchor `chunkSender.onChunkBatchReceivedByClient(packet.desiredChunksPerTick())` `:2169` | ✅ | `ServerGamePacketListenerImpl.java:2167-2169` exact |
| 19 | inventory+api-map: `MixinChunkHolder` — `@ModifyVariable broadcast` HEAD + `getPlayers` redirect; `levelHeightAccessor`→ServerLevel cast holds because ChunkMap passes the level as 3rd ctor arg | ✅ | IP `MixinChunkHolder.java:30-42` (cast at `:36`), `:49-62`, coexist comment `:44-48`; 26.2 `ChunkHolder.java:35` (field), `:174` `broadcastChanges`, `getPlayers(this.pos, true/false)` `:178`/`:191`, `private void broadcast(List<ServerPlayer>, Packet<?>)` `:236`, `PlayerProvider.getPlayers(ChunkPos, boolean)` `:341-342`; `ChunkMap.java:386` `new ChunkHolder(ChunkPos.unpack(node), level, this.level, ...)` — 3rd arg is the `ServerLevel` |

### entity_sync

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 20 | api-map: `sendChanges()` `:88` (both HEAD injects fine); `removePairing`/`addPairing` still send via `player.connection.send(...)`; **`broadcastAndSend` GONE**; everything routed through `ServerEntity.Synchronizer` `:357-362`; NEW `sendToTrackingPlayersFiltered` used by `sendChanges` | ✅ | `ServerEntity.java:88` `sendChanges()`; filtered path used at `:92-94` (passenger-set delta); `removePairing` `:260-263`, `addPairing` `:265-270` (both `player.connection.send`); grep: zero `broadcastAndSend` in the file; `interface Synchronizer` `:357-363` with the three methods |
| 21 | inventory: `MixinServerEntity` — 3 redirects to `sendRedirectedPacket`, force-redirect assert at `sendChanges` HEAD, Portal-skip cancel, `ip_updateTrackedEntityPosition` | ✅ | IP `MixinServerEntity.java:36-42` (assert), `:44-59`/`:61-76`/`:78-90` (redirects on `removePairing`/`addPairing`/`broadcastAndSend`, target string `ServerGamePacketListenerImpl.send(Packet)`), `:96-105` (Portal skip, 1/4096 comment `:92-94`), `:107-110` (codec re-base) |
| 22 | api-map: `MixinTrackedEntity` renames — `broadcast`→`sendToTrackingPlayers` `:1344-1349` (site `connection.send` `:1347`), `broadcastAndSend`→`sendToTrackingPlayersAndSelf` `:1351-1357` (site `:1355`), NEW `sendToTrackingPlayersFiltered` `:1359-1366`; overwrite targets `updatePlayer` `:1383` / `updatePlayers` `:1425` survive; members survive; `removePlayer`/`updatePlayer` now touch `debugSynchronizers()` | ✅ | `ChunkMap.java:1320` `private class TrackedEntity implements ServerEntity.Synchronizer`; every cited line verified exact: `:1321-1325` (fields), `:1344-1366` (three send paths), `:1368` `broadcastRemoved`, `:1374` `removePlayer` (debugSynchronizers `:1378`), `:1383-1406` `updatePlayer` (debugSynchronizers `:1397-1400`), `:1412` private `getEffectiveRange`, `:1425` `updatePlayers` |
| 23 | inventory: pairing rule — `recWatches` requires record exists, `isLoadedToPlayer`, entity != player, and `rec.distanceToSource * 16 + 8 <= getEffectiveRange()` | ✅ | IP `MixinTrackedEntity.java:213-237` — formula at `:236` verbatim; `ip_updateEntityTrackingStatus` `:146-191`; constant-re-evaluation rationale comment `:137-145` |
| 29 | api-map: `MixinServerPlayer` — 2-arg `startRiding(Entity,boolean)` GONE (1-arg final `Entity.java:2406`, 3-arg `:2414`); ServerPlayer overrides the 3-arg form + `removeVehicle`, **no longer overrides `stopRiding`**; therefore `ip_stopRidingWithoutTeleportRequest`'s `super.stopRiding()` bypass is INEFFECTIVE (virtual `removeVehicle()` dispatch reaches the packet-sending override) | ✅ | IP `MixinServerPlayer.java:33-40` (`super.stopRiding()` / `super.startRiding(v, true)`); 26.2: `Entity.java:2406` (final 1-arg), `:2414` (3-arg), `Entity.stopRiding` `:2476-2478` = `this.removeVehicle()` (virtual); grep of `ServerPlayer.java`: no `stopRiding` override; `startRiding` override `:2122-2135` (sends `connection.teleport` `:2125` + passenger packet `:2130`), `removeVehicle` override `:2138-2150` (sends effect-remove + passenger packets). The dispatch chain and the "bypass must retarget `removeVehicle`" conclusion are correct |

### position_sync

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 24 | api-map: `ClientboundPlayerPositionPacket` TARGET-GONE — record + composite `STREAM_CODEC`, no `write(FriendlyByteBuf)` | ✅ | Whole file read: `record ClientboundPlayerPositionPacket(int id, PositionMoveRotation change, Set<Relative> relatives)` `:12`, `StreamCodec.composite` `:13-21`, `of` `:23-25`, `handle` `:32-34` — no write method exists |
| 25 | api-map: the four `ServerboundMovePlayerPacket$*` static `read(FriendlyByteBuf)` factories survive (unlike the S2C packet) — PORTS-CLEAN; IP's S2C write inject exists as claimed | ✅ | 26.2 `ServerboundMovePlayerPacket.java`: classes `:100/:136/:176/:206`, `read` `:113/:149/:185/:215`, codecs via `Packet.codec(...)` `:101,:137,:177,:207`; IP `MixinPlayerPositionLookS2CPacket.java:15` (non-@Unique field), `:27-30` (write RETURN inject appending `writeResourceKey`) |
| 26 | api-map: `teleport(DDDFF,Set<RelativeMovement>)` GONE → `teleport(DDDFF)` `:1257` delegating to `teleport(PositionMoveRotation, Set<Relative>)` `:1261-1270`; `RelativeMovement` deleted; anti-cheat renamed `isEntityCollidingWithAnythingNew` `:1243-1255` using `getPreMoveCollisions`; accept-teleport anchor now `absSnapTo`; new `updateAwaitingTeleport` `:1223` | ✅ | `ServerGamePacketListenerImpl.java:1257-1259, 1261-1270` exact (packet built via `.of(...)` `:1269`); `:1243-1255` (`getPreMoveCollisions` `:1245`, `Shapes.joinIsNotEmpty(...BooleanOp.AND)` `:1249`); `handleAcceptTeleportPacket` `:531`, `absSnapTo` `:539-546`, new `hasChangedDimension()` `:550`; `:1223` `updateAwaitingTeleport`; dir listing: `RelativeMovement.java` absent, `Relative.java` + `PositionMoveRotation.java` present; `handleMovePlayer` `:1064` with 3-arg `ensureRunningOnSameThread` anchor `:1065` |
| 27 | inventory: IP move-packet guard — null dim → scheduled disconnect; mismatch → ignore + `ip_wrongMovePacketCount`, force-teleport after >10; teleport overwrite stamps `ip_setPlayerDimension` | ✅ | IP `MixinServerGamePacketListenerImpl.java:114-116` (unique field), `:119-173` (guard; disconnect `:135-140`, `>10` force-teleport `:157-166`), `:179-225` (`@Overwrite @IPVanillaCopy` teleport; removal-reason early return `:187-193`, `ip_dimOfAwaitingPosition` `:209`, dimension stamp `:222`) |
| 28 | api-map: `MixinServerGamePacketListenerImpl_Redirect` — `send` 2nd param now `@Nullable ChannelFutureListener` `:160`; inner anchor `Connection.send(Packet, ChannelFutureListener, boolean)` invoked `:168`, declared `Connection.java:281` | ✅ | `ServerCommonPacketListenerImpl.java:160,168`; `Connection.java:277,281` — exact |

### collision / interaction / container_gui / misc

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 30 | api-map: `MixinEntity`'s `checkInsideBlocks` `getBoundingBox()` redirect anchor GONE — rearchitected into movement-segment form (`:1269` list form → per-segment `:1299` building box via `makeBoundingBox(to).deflate(1.0E-5F)` `:1302`, walking via `BlockGetter.forEachBlockIntersectedBetween` `:1307`) | ✅ | `Entity.java:1269-1307` verified exact — the box is now derived per-segment, no `getBoundingBox()` call to redirect; the "no direct anchor, needs design" conclusion stands |
| 31 | api-map: `MixinProjectile` TARGET-GONE — `getOwner()` is now `EntityReference.getEntity(this.owner, this.level())` and resolution is cross-dimension natively | ✅ | `Projectile.java:61-63`; `EntityReference.java:80-81` (`level::getEntityInAnyDimension`); `Level.java:785`; `ThrownEnderpearl.java:74` uses `serverLevel.getEntityInAnyDimension(uuid)` (the `findOwnerIncludingDeadPlayer` role). IP redirect confirmed at `MixinProjectile.java:15-34` (extends MixinEntity `:12`) |
| 32 | api-map: `MixinThrownEnderPearl` — class moved to `throwableitemprojectile/`, `onHit` `:85` survives, and 26.2 vanilla `onHit` already teleports the owner cross-dimension to the pearl's level (players `:119-127`, non-players `:133-135`) | ✅ | `throwableitemprojectile/ThrownEnderpearl.java:85` (`onHit`); player branch `player.teleport(new TeleportTransition(level, teleportPos, ...))` `:119-123` + aftermath `:124-128`; non-player `owner.teleport(...)` `:133-135`; `level` = the pearl's `ServerLevel` (`:101`) — cross-dim when owner is elsewhere. Semantics flag justified |
| 33 | api-map: `Player.canInteractWithBlock(BlockPos,D)` GONE → `isWithinBlockInteractionRange(BlockPos,double)` `:1993`; `AbstractContainerMenu.stillValid` lambda survives `:93-95`; IP anchors on intermediary `method_17696` | ✅ | grep `Player.java`: zero `canInteractWithBlock`, `:1993` `isWithinBlockInteractionRange`; `AbstractContainerMenu.java:93-95` — lambda body uses `player.isWithinBlockInteractionRange(pos, 4.0)`; IP `MixinAbstractContainerMenu.java:17` (`method = "method_17696"`), `:20` (old target), `:33` (`validateReach` fallback) |
| 34 | api-map: `ContainerOpenersCounter` — `getPlayersWithContainerOpen` GONE → `getEntitiesWithContainerOpen(Level,BlockPos)` returning `List<ContainerUser>`, radius `maxInteractionRange + 4.0`, membership via `hasContainerOpen`, `isOwnContainer` still abstract `:26` | ✅ substance / ❌ **REFUTED line citations (minor)** | Names, return type, radius formula, and `isOwnContainer` `:26` all confirmed — but the cited lines are wrong: `getEntitiesWithContainerOpen` is at `ContainerOpenersCounter.java:51-58` (not `:76-83`), the `+ 4.0` radius at `:52` (not `:77`), `private boolean hasContainerOpen(Entity, BlockPos)` at `:60-63` (not `:85-89`), and the recheck/`maxInteractionRange` recompute at `:66-94` (not `:91-97`). Correction as stated |
| 35 | api-map: `HashMapPalette.write` gained a param (`(FriendlyByteBuf, IdMap<T>)` `:78`) but IP's descriptor-less `method = "write"` re-binds; `IdMap.getId` invocation still inside `:83` | ✅ | 26.2 `HashMapPalette.java:78-85` (`globalMap.getId(this.values.byId(i))` `:83`); IP `MixinHashMapPalette.java:13` (no descriptor), `:16` (`IdMap.getId(Object)I` target) |
| 36 | api-map: `AbstractMinecart.lerpTo` TARGET-GONE — replaced by `InterpolationHandler`; `getInterpolation()` `:341`; class moved to `vehicle/minecart/` | ✅ | `vehicle/minecart/AbstractMinecart.java:341-342` `public InterpolationHandler getInterpolation()`; grep: no `lerpTo` in the file |
| 37 | api-map: `MixinItemEntity_P` — shadowed `thrower` now `EntityReference<Entity>` `:52`; `Level.getProfiler()` GONE | ✅ | `ItemEntity.java:52` `private @Nullable EntityReference<Entity> thrower`; grep `Level.java`: zero `getProfiler` |
| 38 | api-map: `MixinClientboundCustomPayloadPacket` PORTS-CLEAN — record `:15`, `handle(ClientCommonPacketListener)` | ✅ | `ClientboundCustomPayloadPacket.java:15` (record), `:32-34` (`handle` — doc cites `:33`, the body line; declaration `:32`) |

---

## 3. Internal contradictions between the docs

- **Mailbox accessor purpose**: inventory `:41` says the accessor "lets `ImmPtlChunkTickets` post into the vanilla ticket-throttle mailbox"; api-map `:31` says "the declared duck method has no non-mixin call sites". **api-map is right** (see claim 13). Inventory's purpose statement is the error.
- **File count 73 vs 74**: inventory header wrong; api-map's correction is right (see claim 1).
- No other contradictions found; the api-map's per-mixin descriptions of IP behavior consistently match the inventory's, and both match IP source where checked.

## 4. Refuted-claims summary

| Doc | Claim | Correction |
|---|---|---|
| inventory | Slice is "73 Java files" (`:3`, `:315`) | 74 files (its own per-directory counts and the directory listing both give 74) |
| inventory | Mailbox accessor "lets ImmPtlChunkTickets post into the vanilla ticket-throttle mailbox" (`:41`) | `ip_getMailBox()` has zero call sites in all of IP; `ImmPtlChunkTickets` references the mailbox only in a javadoc comment (`ImmPtlChunkTickets.java:50`). Declared-but-unused |
| api-map | `portal_getTicketSet`'s "one call site `ImmPtlChunkTickets.removeAllTicketsInWorld`" (`:36`) | Three active call sites: `ImmPtlChunkTickets.java:299`, `PortalDebugCommands.java:466`, `PortalDebugCommands.java:732` — the `List<Ticket>` retarget must cover the debug commands too |
| api-map | `ContainerOpenersCounter` line citations `:76-83` / `:85-89` / radius `:77` / recheck `:91-97` (`:83`) | Actual: `getEntitiesWithContainerOpen` `:51-58`, radius `+4.0` `:52`, `hasContainerOpen` `:60-63`, recheck `:66-94`. Method names, `List<ContainerUser>` return type, and semantics in the doc are correct |

## 5. Notes (not refutations)

- api-map's rain-broadcast ranges (`ServerLevel.java:774-783` / `:785-795`) are each off by ~1 line (actual `:774-784` / `:786-795`); the dimension-scoped-vs-unscoped split it describes is exactly right.
- api-map cites `Synchronizer` at `ServerEntity.java:357-362`; the interface spans `:357-363`. Immaterial.
- api-map's `EntityReference` resolution cite `:78-81`; the two resolver lines are `:80-81`. Immaterial.
- The clear-call rewrite for `MixinLivingEntity` ("`setLastHurtByPlayer(null)` must become the 2-arg form") is directionally right, but note both 2-arg overloads wrap the arg in `EntityReference.of(...)` (`LivingEntity.java:632-641`) — whether `of(null)` yields a null reference must be checked at port time (design detail, flagged for the porter).
- `PlayerTicketTracker.runAllUpdates` is declared at `DistanceManager.java:290-291` (api-map cites `:290`); the other three method lines (`:253/:257/:267`) are exact.
