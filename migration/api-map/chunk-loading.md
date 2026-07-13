# 26.2 API Map — Chunk Loading / Tracking

**Inventory:** `migration/inventory/chunk-loading.md` (touchpoint numbers below match its §4 list).
**26.2 ground truth:** decompiled vanilla at `C:/Users/warwa/ModDev/mc262-ref` (Mojang mappings). All 26.2 citations are relative to that root. IP citations are into the IP 1.21.3 tree.
**Verdict counts:** 33 SAME · 10 CHANGED · 8 GONE (of 51 vanilla touchpoints) + 4 FABRIC-API.

**Headline findings (read first):**
1. **The ticket system was rewritten** (touchpoints #17/#18/#23). `TicketType` is now a registry-registered `record TicketType(long timeout, int flags)` — `TicketType.create(String, Comparator)` and the generic `Ticket<T>` key are gone. Ticket storage moved off `DistanceManager` into a per-level `TicketStorage` SavedData (`net/minecraft/world/level/TicketStorage.java:33`). Region-radius add/remove survive as `TicketStorage.addTicketWithRadius/removeTicketWithRadius` with **identical level arithmetic** (`33 - radius`).
2. **Tickets now carry behavior flags** — `FLAG_LOADING` drives chunk generation/full status (→ `ChunkHolder` futures), `FLAG_SIMULATION` drives entity ticking (→ `inEntityTickingRange`), `FLAG_KEEP_DIMENSION_ACTIVE` drives dimension keep-alive, `FLAG_PERSIST` makes tickets survive restarts. IP's `"imm_ptl"` ticket needs **LOADING|SIMULATION (=6), no persist, no timeout** — exactly the shape of vanilla `DRAGON = register("dragon", 0L, 6)` (`net/minecraft/server/level/TicketType.java:19`).
3. **Dimension keep-alive changed mechanism** (#39): the `players List.isEmpty()` check IP redirects in `ServerLevel.tick` no longer exists; 26.2 uses an `emptyTime` counter reset by `chunkSource.hasActiveTickets()` = "any ticket with FLAG_KEEP_DIMENSION_ACTIVE" (`net/minecraft/server/level/ServerLevel.java:408-417`).
4. **Per-dimension time is gone** (#34/#43): `ClientboundSetTimePacket(gameTime, dayTime, doDaylightCycle)` was replaced by the server-global **WorldClock** system (`record ClientboundSetTimePacket(long gameTime, Map<Holder<WorldClock>, ClockNetworkState> clockUpdates)`); `GameRules.RULE_DAYLIGHT` became the global `GameRules.ADVANCE_TIME`.
5. **`ClientChunkCache` was reworked** (#45): fields went private, and it now feeds **per-frame chunk/section delta sets** (`addedLoadedChunks`/`removedEmptySections`/…) that `LevelExtractor` hands to `SectionOcclusionGraph`. An `ImmPtlClientChunkMap` replacement MUST reproduce this delta feed and the new `onSectionEmptinessChanged` override or portal-view terrain rendering desyncs (this is the exact class of bug already fought in the current mod's SOG-desync fix).
6. `ChunkPos` is now a `record` with renames: `asLong(x,z)`→`pack(x,z)`, `toLong()`→`pack()`, `new ChunkPos(long)`→`unpack(long)`, field access `.x`/`.z`→`.x()`/`.z()` (cross-cutting). `ResourceLocation` is renamed `Identifier` (`net/minecraft/resources/Identifier`), relevant to payload/ticket registration.

---

## GONE (8)

### #17 `DistanceManager.addRegionTicket(TicketType, ChunkPos, int, T)` / `removeRegionTicket(...)`
| | |
|---|---|
| **IP usage** | `ImmPtlChunkTickets.addTicket` → `distanceManager.addRegionTicket(TICKET_TYPE, chunkPosObj, getLoadingRadius(), chunkPosObj)` (`ImmPtlChunkTickets.java:239-241`); `purge` → `removeRegionTicket(...)` (`:266-271`); `removeAllTicketsInWorld` → `removeRegionTicket(..., ticket.getTicketLevel(), ...)` (`:298-308`) |
| **Verdict** | **GONE** — no `addRegionTicket`/`removeRegionTicket` anywhere on 26.2 `DistanceManager` (full read of `net/minecraft/server/level/DistanceManager.java:34-320`; the only ticket-adding members are the internal player paths `addPlayer`/`removePlayer` `:109-129`) |
| **26.2 replacement** | `TicketStorage.addTicketWithRadius(TicketType type, ChunkPos chunkPos, int radius)` → `new Ticket(type, ChunkLevel.byStatus(FullChunkStatus.FULL) - radius)` (`net/minecraft/world/level/TicketStorage.java:138-141`) and `removeTicketWithRadius(...)` (`:214-217`). Public wrappers exist on `ServerChunkCache`: `addTicket(Ticket, ChunkPos)` (`net/minecraft/server/level/ServerChunkCache.java:481-483`), `addTicketWithRadius` (`:501-503`), `removeTicketWithRadius` (`:505-507`), plus `addTicketAndLoadWithRadius` (`:485-499`, asserts `type.doesLoad()` and `!type.canExpireIfUnloaded()`). |
| **Migration note** | Level arithmetic is unchanged: `ChunkLevel.byStatus(FullChunkStatus.FULL)` = 33 (`net/minecraft/server/level/ChunkLevel.java:48-55`), so radius 2 → level 31 = ENTITY_TICKING, exactly as 1.21.3's `33 - distance`. `getLoadingRadius()` 2|1 ports as-is. Removal identity is now **type + level per chunk** (`TicketStorage.isTicketSameTypeAndLevel`, `:181-183`) — no key object; IP's `removeRegionTicket(TICKET_TYPE, pos, getLoadingRadius(), pos)` maps to `removeTicketWithRadius(TICKET_TYPE, pos, getLoadingRadius())`. **Trap:** re-adding an existing (type,level) ticket only resets its timeout and returns false (`:147-156`) — harmless for IP since its type has no timeout. |

### #23 `TicketType.create(String, Comparator)` / `Ticket<T>` / `SortedArraySet<Ticket<?>>`
| | |
|---|---|
| **IP usage** | `TICKET_TYPE = TicketType.create("imm_ptl", Comparator.comparingLong(ChunkPos::toLong))` (`ImmPtlChunkTickets.java:60-61`); ticket-set iteration with `getType()`/`getTicketLevel()` (`:298-308`) |
| **Verdict** | **GONE** — `TicketType` is now `public record TicketType(long timeout, @TicketType.Flags int flags)` (`net/minecraft/server/level/TicketType.java:10`); no `create`, no comparator, no generic key. `Ticket` is non-generic: `Ticket(TicketType type, int ticketLevel)` (`net/minecraft/server/level/Ticket.java:23-25`) with `getType()` `:48`, `getTicketLevel()` `:52`, and a persistence `CODEC` `:11-18`. Ticket lists are plain `List<Ticket>` (`TicketStorage.java:43`) — `SortedArraySet` is out of the ticket path. |
| **26.2 replacement** | Register the type: `Registry.register(BuiltInRegistries.TICKET_TYPE, name, new TicketType(timeout, flags))` — vanilla's own `register` at `TicketType.java:27-29`; registry declared at `net/minecraft/core/registries/BuiltInRegistries.java:337` (key `Registries.TICKET_TYPE`, `net/minecraft/core/registries/Registries.java:250`). Flags: `FLAG_PERSIST=1, FLAG_LOADING=2, FLAG_SIMULATION=4, FLAG_KEEP_DIMENSION_ACTIVE=8, FLAG_CAN_EXPIRE_IF_UNLOADED=16` (`TicketType.java:12-16`); `NO_TIMEOUT = 0L` (`:11`). |
| **Migration note** | **Flag choice for `"imm_ptl"`: `NO_TIMEOUT` + `FLAG_LOADING | FLAG_SIMULATION` (=6).** Rationale: (a) 1.21.3 `addRegionTicket` fed BOTH the loading tracker and the ticking tracker, and IP polls `getEntityTickingChunkFuture` — in 26.2, `FLAG_LOADING` tickets drive `ChunkHolder` ticket levels/futures via `LoadingChunkTracker` (`net/minecraft/server/level/LoadingChunkTracker.java:14,35-44`), while `FLAG_SIMULATION` tickets drive `inEntityTickingRange` via `SimulationChunkTracker` (`net/minecraft/server/level/SimulationChunkTracker.java:16`, `DistanceManager.java:135-137`). Without SIMULATION, portal-loaded chunks would never entity-tick and `EntitySync.tick`'s gate would never fire. (b) **No `FLAG_PERSIST`:** `TicketStorage` is SavedData and persists flagged tickets across restarts (`TicketStorage.java:71-79`) — IP's tickets must NOT persist (documented non-persistence, `ChunkLoader.java:116`). (c) `FLAG_KEEP_DIMENSION_ACTIVE` is a design decision under #39 below. Model: `DRAGON = register("dragon", 0L, 6)` (`TicketType.java:19`). Registration is now a **real registry op** — must run during bootstrap/registry phase via the mod's multiloader registration path, not lazy static init. |

### #18 `DistanceManager.getTickets(long)` (accessor), `tickets` field, `ticketThrottler` field
| | |
|---|---|
| **IP usage** | `IEDistanceManager.portal_getTicketSet` wrapping private `DistanceManager.getTickets(long)` (`MixinDistanceManager.java:57-60`), used by `removeAllTicketsInWorld` (`ImmPtlChunkTickets.java:284-312`); accessors for `tickets`/`mainThreadExecutor`/`ticketThrottler` (`IEDistanceManager.java:15-22`) |
| **Verdict** | **GONE** as DistanceManager members — DistanceManager holds no ticket map; it has `private final TicketStorage ticketStorage` (`DistanceManager.java:40`), `private final ThrottlingChunkTaskDispatcher ticketDispatcher` (`:44`), `private final Executor mainThreadExecutor` (`:46`) |
| **26.2 replacement** | `TicketStorage.getTickets(long)` is **public** (`TicketStorage.java:130-132`) — no accessor mixin needed. Bulk removal: `TicketStorage.removeTicketIf(TicketPredicate, @Nullable removedTickets)` (`:308-361`) — the tracker listeners fire **once per chunk per tracker class** (loading/simulation), after that chunk's per-ticket removal loop, with the post-removal recomputed level (`TicketStorage.java:328-349`), NOT per removed ticket. For IP's use the observable semantics are identical anyway: at most one `imm_ptl` ticket per (type,level) exists per chunk (`addTicket` dedups on same type+level, `:147-156`). The clean re-implementation of `removeAllTicketsInWorld` is `ticketStorage.removeTicketIf((ticket, pos) -> ticket.getType() == IMM_PTL_TICKET_TYPE, null)`. Reaching the storage: `ServerChunkCache.ticketStorage` is private (`ServerChunkCache.java:66`, created from `savedDataStorage.computeIfAbsent(TicketStorage.TYPE)` `:103`) → one accessor/duck on `ServerChunkCache` (or on `DistanceManager.ticketStorage`) replaces the old `IEDistanceManager` accessor set. `mainThreadExecutor` still exists if needed (`DistanceManager.java:46`). |
| **Migration note** | The old iterate-and-`removeRegionTicket(level)` loop can be ported 1:1 via `getTickets(pos)` + `removeTicket(long, Ticket)` (`TicketStorage.java:223-267`), but `removeTicketIf` is the vanilla-blessed bulk path; either preserves semantics since removal keys on (type, level). |

### #24 `ChunkTaskPriorityQueueSorter` + `ProcessorMailbox<StrictQueue.IntRunnable>` (mailbox accessor)
| | |
|---|---|
| **IP usage** | `IEChunkTaskPriorityQueueSorter.java:9-13` (declared accessor; referenced only by the throttling javadoc, `ImmPtlChunkTickets.java:38-55`) |
| **Verdict** | **GONE** — no `ChunkTaskPriorityQueueSorter` and no `ProcessorMailbox` in 26.2 (verified: `net/minecraft/util/thread/` contains only `AbstractConsecutiveExecutor`, `BlockableEventLoop`, `ConsecutiveExecutor`, `ParallelMapTransform`, `PriorityConsecutiveExecutor`, `ReentrantBlockableEventLoop`, `StrictQueue`, `TaskScheduler`) |
| **26.2 replacement** | The vanilla throttling role is `ChunkTaskDispatcher implements ChunkHolder.LevelChangeListener` (`net/minecraft/server/level/ChunkTaskDispatcher.java:17`) and `ThrottlingChunkTaskDispatcher extends ChunkTaskDispatcher` (`net/minecraft/server/level/ThrottlingChunkTaskDispatcher.java:12`), scheduled through `TaskScheduler.wrapExecutor(...)` (`DistanceManager.java:53-54`). |
| **Migration note** | IP never calls the accessor — it exists only as documentation of the vanilla machinery IP's own throttler replaces. Port decision: **drop `IEChunkTaskPriorityQueueSorter` entirely** and update the `ImmPtlChunkTickets` javadoc to name the 26.2 classes. No functional surface. |

### #39 `ServerLevel.tick` players-`List.isEmpty()` (keep-alive redirect target)
| | |
|---|---|
| **IP usage** | `MixinServerLevel.java:40-54` — redirects the `isEmpty()` check inside `ServerLevel.tick` to return false when `ImmPtlChunkTracking.shouldLoadDimension(dim)` |
| **Verdict** | **GONE** — 26.2 `ServerLevel.tick` contains no players-`isEmpty()` skip. The skip is now: `boolean isActive = this.chunkSource.hasActiveTickets(); if (isActive) this.resetEmptyTime(); if (runs) this.emptyTime++; if (this.emptyTime < 300) { …tick entities/blockEntities… }` (`net/minecraft/server/level/ServerLevel.java:408-418,452-453`) |
| **26.2 replacement** | `ServerChunkCache.hasActiveTickets()` = `ticketStorage.shouldKeepDimensionActive()` (`ServerChunkCache.java:477-479`) = any ticket whose type has `FLAG_KEEP_DIMENSION_ACTIVE` (`TicketStorage.java:118-128`). Players keep dimensions active through their `PLAYER_SIMULATION` ticket (flags 12 = SIMULATION|KEEP_DIMENSION_ACTIVE, `TicketType.java:21`, added in `DistanceManager.addPlayer` `:115`). |
| **Migration note** | Two faithful options — **needs a design pick**: (a) add `FLAG_KEEP_DIMENSION_ACTIVE` to the imm_ptl ticket type (flags 14) — zero mixin, but couples keep-alive to `enableImmPtlChunkLoading` (tickets are config-gated, `ImmPtlChunkTickets.java:234-236`, while IP's watch-record keep-alive is NOT gated — a real behavior deviation when the config is off); or (b) keep the mixin shape: redirect the `hasActiveTickets()` call in `ServerLevel.tick` (`ServerLevel.java:408`) to OR-in `shouldLoadDimension(dim)` — preserves IP's exact gating semantics. **(b) is the zero-deviation port**; (a) is the vanilla-native simplification. Note the 26.2 skip has a 300-tick (15 s) grace and only skips the entity/blockEntity phase (chunkSource/blockTicks still tick, `:384-404`), which is *weaker* than the 1.21.3 skip — verify downstream assumptions (IP's dead-player fallback etc.) against this. |

### #34 `ClientboundSetTimePacket(long gameTime, long dayTime, boolean doDaylightCycle)`
| | |
|---|---|
| **IP usage** | `WorldInfoSender.sendWorldInfo` — redirected `new ClientboundSetTimePacket(world.getGameTime(), world.getDayTime(), RULE_DAYLIGHT)` per visible non-overworld skylight dimension + overworld-time sync for players outside the overworld (`WorldInfoSender.java:26-38,47-95`) |
| **Verdict** | **GONE** — the packet is now `public record ClientboundSetTimePacket(long gameTime, Map<Holder<WorldClock>, ClockNetworkState> clockUpdates)` (`net/minecraft/network/protocol/game/ClientboundSetTimePacket.java:14`). There is no `dayTime` long and no daylight boolean. |
| **26.2 replacement** | Time is a **server-global clock system**: `ServerClockManager` (SavedData on the server, `net/minecraft/world/clock/ServerClockManager.java:24-53`) ticks all registered `WorldClock`s when the global `GameRules.ADVANCE_TIME` is on (`:65-71`); full sync = `clockManager().createFullSyncPacket()` (`:129-131`), sent on login via `PlayerList.sendLevelInfo` (`net/minecraft/server/players/PlayerList.java:642-645`) and broadcast globally (`MinecraftServer.java:2133`); periodic game-time sync = `MinecraftServer.forceGameTimeSynchronization` broadcasting `new ClientboundSetTimePacket(overworld().getGameTime(), Map.of())` to ALL players (`MinecraftServer.java:1162-1167`). Client handling is dimension-agnostic: `handleSetTime` sets `this.level.setTimeFromServer(gameTime)` and updates the **connection-global** `clockManager` (`net/minecraft/client/multiplayer/ClientPacketListener.java:1129-1136`). Dimensions bind to a clock via `DimensionType.defaultClock : Optional<Holder<WorldClock>>` (`net/minecraft/world/level/dimension/DimensionType.java:45,103`); clocks registered per-datapack (`net/minecraft/world/clock/WorldClocks.java:9-15` — overworld, the_end). |
| **Migration note** | `WorldInfoSender`'s time half is **structurally obsolete**: vanilla already syncs one global gameTime + all clock states to every player regardless of dimension — there is no per-dimension time left to sync, and the "player not in overworld still needs overworld time" problem no longer exists. The faithful port keeps `WorldInfoSender` but reduces its payload to the **weather** game events (#35, all SAME) — the redirected time packet must be deleted, not translated (any hand-built `ClientboundSetTimePacket` with `Map.of()` would just duplicate vanilla's global sync; a wrong per-dim variant could desync the connection-global clock manager). Mark the removal with a port-note comment citing this section. `ServerLevel.getDayTime()` is likewise gone (no `dayTime` on `Level` — verified by grep; day-position now flows from clocks/`environmentAttributes`). |

### #43 `GameRules.RULE_DAYLIGHT`
| | |
|---|---|
| **IP usage** | `WorldInfoSender.java:56-58` (`world.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)`) |
| **Verdict** | **GONE** — no daylight rule in 26.2 (`net/minecraft/world/level/gamerules/GameRules.java` — grep "DAYLIGHT" empty; note the class moved to the new `gamerules` package) |
| **26.2 replacement** | `GameRules.ADVANCE_TIME = registerBoolean("advance_time", …)` (`net/minecraft/world/level/gamerules/GameRules.java:24`), consumed **globally**: `server.getGlobalGameRules().get(GameRules.ADVANCE_TIME)` (`ServerClockManager.java:66`; `MinecraftServer.getGlobalGameRules()` = overworld's rules, `MinecraftServer.java:2137-2140`). GameRules read API changed: `getBoolean(RULE)` → `get(GameRule<Boolean>)` (usage `ServerLevel.java:369`). |
| **Migration note** | Only relevant to the deleted time-sync path (#34) — disappears with it. If any other slice reads daylight-cycle state, it must read `ADVANCE_TIME` via global game rules. |

### #37 (partial) `MinecraftServer.getProfiler()`
| | |
|---|---|
| **IP usage** | Profiler push/pop around tracking tick (`ImmPtlChunkTracking.java:362-399`) |
| **Verdict** | **GONE** — no `getProfiler()` on 26.2 `MinecraftServer` (grep: only `getProfilerResults`, `MinecraftServer.java:2312`) |
| **26.2 replacement** | Thread-bound static profiler: `ProfilerFiller profiler = Profiler.get()` (vanilla usage `MinecraftServer.java:1163`; `ServerLevel.java` uses the same pattern via `Profiler.get()`/`Zone`). |
| **Migration note** | Mechanical: replace `server.getProfiler().push/pop` with `Profiler.get().push/pop` on the server thread. All other #37 members are SAME (see below). |

### #55 `ChunkMap.getChunks()` (`@Invoker` target of `IEChunkMap_Accessor`)
| | |
|---|---|
| **IP usage** | `IEChunkMap_Accessor.ip_getChunks()` — `@Mixin(ChunkMap.class)` + `@Invoker("getChunks") Iterable<ChunkHolder>` (`imm_ptl/core/mixin/common/chunk_sync/IEChunkMap_Accessor.java:8-12`); consumed by the `report_chunk_ticket_stat` debug command (`imm_ptl/core/commands/PortalDebugCommands.java:459`). This is the one file of the 10-file chunk_sync mixin package that the first inventory/api-map pass silently skipped; added by the verification pass. |
| **Verdict** | **GONE** — `ChunkMap.getChunks()` does not exist in 26.2 (no `getChunks` anywhere in `net/minecraft/server/level/ChunkMap.java` — grep empty) |
| **26.2 replacement** | The holder data lives in the fields `updatingChunkMap`/`visibleChunkMap` (`net/minecraft/server/level/ChunkMap.java:127-128`); replacement = an `@Accessor` on `visibleChunkMap` + `.values()` (what 1.21.3's `getChunks()` returned). |
| **Migration note** | A blindly ported `@Invoker` with a missing target fails at mixin application — hard crash at startup — so this entry is load-bearing even though the only consumer is a debug command. Either port the accessor as the `visibleChunkMap` `@Accessor` in this slice, or explicitly hand it to the debug-commands slice; do not drop it silently. |

---

## CHANGED (10)

### #6 `ChunkMap.onChunkReadyToSend(LevelChunk)` — `@Overwrite` no-op
| | |
|---|---|
| **IP usage** | `MixinChunkMap_C.java:76-79` — no-op'd so vanilla never marks chunks pending on the vanilla sender |
| **Verdict** | **CHANGED** — signature is now `private void onChunkReadyToSend(ChunkHolder chunkHolder, LevelChunk chunk)` (`net/minecraft/server/level/ChunkMap.java:690-701`), called from `prepareTickingChunk` (`:681,:683`) |
| **26.2 equivalent** | Same role: iterates `playerMap.getAllPlayers()` and calls `markChunkPendingToSend(player, chunk)` for players whose `ChunkTrackingView` contains the chunk (`:693-697`). **But it gained a tail:** `this.level.getChunkSource().onChunkReadyToSend(chunkHolder)` (`:699`) which registers holders with pending broadcasts into `ServerChunkCache.chunkHoldersToBroadcast` (`ServerChunkCache.java:586-590`), the set that drives every `ChunkHolder.broadcastChanges` call (`broadcastChangedChunks`, `ServerChunkCache.java:353-365`); plus `debugSynchronizers().registerChunk` (`:700`). |
| **Migration note** | A blind whole-method no-op now also suppresses the broadcast-set registration — block updates accumulated while a chunk loads would never flush (IP's own block-update path relies on `broadcastChanges`, inventory §3.6). The faithful port must **cancel only the player loop** (the vanilla-sender marking) and keep/replay `level.getChunkSource().onChunkReadyToSend(chunkHolder)` + `debugSynchronizers().registerChunk(chunk)` — e.g. overwrite with exactly that tail instead of an empty body. |

### #12 `ChunkMap.TrackedEntity` (inner class; `removePlayer`, `updatePlayers` redirect)
| | |
|---|---|
| **IP usage** | `MixinChunkMap_E.java:48,:90`; `EntitySync.update/tick` drive `ip_updateEntityTrackingStatus`/`ip_sendChanges` per `TrackedEntity` (`EntitySync.java:23-73`) |
| **Verdict** | **CHANGED** — class survives but is now `private class TrackedEntity implements ServerEntity.Synchronizer` (`ChunkMap.java:1320`) with the send-to-tracking methods as interface impls (`:1344-1366`) |
| **26.2 equivalent** | `removePlayer(ServerPlayer)` `:1374-1381`, `updatePlayer(ServerPlayer)` `:1383-1406`, `updatePlayers(List<ServerPlayer>)` `:1425-1429` all survive. The per-tick driver `ChunkMap.tick()` survives (`:1181-1212`) — but its sendChanges gate changed: `if (sectionPosChanged || trackedEntity.entity.needsSync || this.distanceManager.inEntityTickingRange(newPos.chunk().pack()))` (`:1202-1204`). |
| **Migration note** | Two consequences for the EntitySync port: (1) new `entity.needsSync` flag and the section-pos-changed condition are part of the vanilla logic `EntitySync.tick` re-implements — the 26.2 vanilla-copy must adopt the **full** `:1202` gate, not the old ticking-range-only gate, or moved/flagged entities in non-entity-ticking chunks stop syncing (this interacts directly with the known remote_entity_move dedup work). (2) `removePlayer` gained a `debugSynchronizers().dropEntity` branch (`:1377-1379`) — include it in any overwrite. `updatePlayer`'s visibility math is otherwise the same shape (`getPlayerViewDistance`, `broadcastToPlayer`, `isChunkTracked`, `:1386-1392`). |

### #33 Redirection-wrapper packet plumbing (`ClientboundCustomPayloadPacket` / `CustomPacketPayload(.Type)` / `GameProtocols.CLIENTBOUND_TEMPLATE.bind` / bundles)
| | |
|---|---|
| **IP usage** | `PacketRedirection.java:135-174,:234-289` (owned by network slice; load-bearing for chunk sending) |
| **Verdict** | **CHANGED** (classes survive; id type + template type changed) |
| **26.2 equivalent** | `ClientboundCustomPayloadPacket` = record with `GAMEPLAY_STREAM_CODEC` (`net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket.java:16-23`); `CustomPacketPayload` + `Type` intact, but `Type` wraps **`Identifier`** (renamed from `ResourceLocation`): `createType(String)` → `new CustomPacketPayload.Type<>(Identifier.withDefaultNamespace(id))` (`net/minecraft/network/protocol/common/custom/CustomPacketPayload.java:20-22`); `GameProtocols.CLIENTBOUND_TEMPLATE` is now a `SimpleUnboundProtocol<ClientGamePacketListener, RegistryFriendlyByteBuf>` (`net/minecraft/network/protocol/game/GameProtocols.java:131`) with `bind(Function<ByteBuf, B>)` (`net/minecraft/network/protocol/SimpleUnboundProtocol.java:9`); `ClientboundBundlePacket` (`net/minecraft/network/protocol/game/ClientboundBundlePacket.java:7`) and `BundleDelimiterPacket` (`net/minecraft/network/protocol/BundleDelimiterPacket.java:5`) survive; `RegistryFriendlyByteBuf` survives (`net/minecraft/network/RegistryFriendlyByteBuf.java:7`). |
| **Migration note** | Detailed mapping belongs to the network-slice map; for this slice it suffices that the `i:r` wrapper strategy (payload id + inner vanilla packet bytes, decode via bound protocol) remains implementable on the same classes, modulo the `Identifier` rename. |

### #37 `MinecraftServer` members (non-profiler)
| | |
|---|---|
| **IP usage** | throughout (`ImmPtlChunkTracking.java:175-186,:362-399`; `ImmPtlChunkTickets.java:178`; `PerformanceLevel.java:25-40`) |
| **Verdict** | **CHANGED** overall (getProfiler GONE — see GONE section; the rest SAME) |
| **26.2 equivalent** | `getAllLevels()` `net/minecraft/server/MinecraftServer.java:1197`; `getLevel(ResourceKey<Level>)` `:1189`; `getPlayerList()` `:1383`; `overworld()` `:1185`; `isRunning()` `:703`; `tickRateManager()` `:1815`; `getAverageTickTimeNanos()` `:1819`. New relevant member: `clockManager()` `:1219`. |
| **Migration note** | Only the profiler call needs rewriting (`Profiler.get()`). |

### #40 `ServerLevel` / `Level` misc members
| | |
|---|---|
| **IP usage** | inventory #40 list (`WorldInfoSender`, tracking core) |
| **Verdict** | **CHANGED** overall (`getDayTime` GONE, `getGameRules().getBoolean` API changed; the rest SAME) |
| **26.2 equivalent** | `dimension()` `net/minecraft/world/level/Level.java:960`; `getChunkSource()` `net/minecraft/server/level/ServerLevel.java:1186`; `getLightEngine()` `Level.java:352-353`; `getGameTime()` `net/minecraft/world/level/LevelAccessor.java:41`; `isRaining()` `Level.java:865-867` (now `canHaveWeather() && getRainLevel(1.0F) > 0.2`); `getRainLevel(float)` `Level.java:847`; `getThunderLevel(float)` `Level.java:837-839`; `dimensionType().hasSkyLight()` `net/minecraft/world/level/dimension/DimensionType.java:31`. **GONE:** `getDayTime()` (no such member on `Level`/`ServerLevel` — replaced by the WorldClock system, see #34). **CHANGED:** game-rule reads are `getGameRules().get(GameRule<T>)` (`ServerLevel.java:366,369`). |
| **Migration note** | `getDayTime`/daylight only fed the deleted time-sync (#34). Weather members all survive for `sendWorldInfo`'s rain/thunder half. |

### #41 `ServerPlayer` members (`.server` field access)
| | |
|---|---|
| **IP usage** | `oldPlayer.server` / `player.server` (`ImmPtlChunkTracking.java:59,:157,:175`); plus `getId()/isRemoved()/requestedViewDistance()/chunkPosition()/position()/level()/.connection` (`ImmPtlChunkTracking.java:371-372,:253`, `McHelper.java:244`, `ChunkVisibility.java:30-33`) |
| **Verdict** | **CHANGED** — `.server` is now **`private final MinecraftServer server`** (`net/minecraft/server/level/ServerPlayer.java:232`); everything else SAME |
| **26.2 equivalent** | No `getServer()` exists on `ServerPlayer` or `Entity` (verified by grep — vanilla's own out-of-class pattern is `this.level().getServer()`, `ServerPlayer.java:501`). Replacement: `player.level().getServer()` (`ServerPlayer.level()` returns `ServerLevel`; `ServerLevel.getServer()` — `ServerLevel.java:1278`) or an accessor mixin on the private field. SAME members: `requestedViewDistance()` — `ServerPlayer.java:1878-1880`; `connection` public — `ServerGamePacketListenerImpl` usage `PlayerChunkSender.java:46`; `isRemoved` usage `ServerPlayer.java:1094`; `chunkPosition()` usage `ServerLevel.java:434`; `getId()` usage `ChunkMap.java:1151`; `position()` usage `ChunkMap.java:1385`. |
| **Migration note** | Three call sites in `ImmPtlChunkTracking` alone (`:59,:157,:175`) — mechanical rewrite to `player.level().getServer()`. Watch for the same pattern across other slices (it was a public field in 1.21.3). |

### #42 `ChunkPos` statics and fields
| | |
|---|---|
| **IP usage** | `ChunkPos.asLong(x,z)`, `getX/getZ(long)`, `toLong()`, `new ChunkPos(long)` — throughout the slice |
| **Verdict** | **CHANGED** — now `public record ChunkPos(int x, int z)` (`net/minecraft/world/level/ChunkPos.java:19`) |
| **26.2 equivalent** | `asLong(x,z)` → `pack(int,int)` `:73-75`; `toLong()` → `pack()` `:69-71`; `new ChunkPos(long)` → `unpack(long)` `:49-51`; `getX(long)` `:85-87` / `getZ(long)` `:89-91` **unchanged**; field reads `.x`/`.z` → record accessors `.x()`/`.z()` (external use; e.g. `ChunkMap.java:1128`); `toLong(BlockPos)` → `pack(BlockPos)` `:81-83`. New helper `distanceSquared` used by the vanilla sender (`PlayerChunkSender.java:92`). |
| **Migration note** | Cross-cutting mechanical rename over the whole slice (`ImmPtlChunkTracking`, `ChunkLoader`, `ImmPtlChunkTickets`, `PlayerChunkLoading`, `DimensionalChunkPos`). |

### #45 `ClientChunkCache` (subclassed by `ImmPtlClientChunkMap`)
| | |
|---|---|
| **IP usage** | subclass overriding `drop`, `getChunk`, `replaceBiomes`, `replaceWithPacketData`, `updateViewCenter`, `updateViewRadius`, `gatherStats`, `getLoadedChunksCount`, `onLightUpdate`; inherited `level`, `emptyChunk`; ctor `super(clientWorld, 1)` (`ImmPtlClientChunkMap.java:43-239`) |
| **Verdict** | **CHANGED** — heavily reworked (`net/minecraft/client/multiplayer/ClientChunkCache.java`) |
| **26.2 equivalent** | Ctor `ClientChunkCache(ClientLevel, int serverChunkRadius)` `:41-46` (SAME shape; `super(clientWorld, 1)` still works). Overridables: `drop(ChunkPos)` `:62-70`; `getChunk(int,int,ChunkStatus,boolean)` `:72-81`; `replaceBiomes(int,int,FriendlyByteBuf)` `:88-100`; **`replaceWithPacketData(int, int, FriendlyByteBuf, Map<Heightmap.Types, long[]>, Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput>)` `:102-128` — heightmaps arg changed `CompoundTag` → `Map<Heightmap.Types, long[]>`, and it returns `@Nullable LevelChunk`**; `updateViewCenter(int,int)` `:134-137`; `updateViewRadius(int)` `:139-159`; `gatherStats()` `:166-168`; `getLoadedChunksCount()` `:171-173`; **`onLightUpdate(LightLayer, SectionPos)` `:176-178` now routes to `Minecraft.getInstance().levelExtractor.setSectionDirty(...)`** (single global extractor — the per-dimension routing IP does must target the per-dimension renderer's extractor instead; cross-slice contract with the render map, `MIGRATION_API_MAP.md` LevelExtractor section). **Fields `level` and `emptyChunk` are now PRIVATE** (`:36,:39`) — the subclass can no longer inherit them; it must hold its own copies (`emptyChunk` construction model: `new EmptyLevelChunk(level, new ChunkPos(0,0), registryAccess biome holder)` `:43`). |
| **Migration note (critical, new vanilla surface):** | 26.2 `ClientChunkCache` **publishes per-frame delta sets consumed by the renderer**: `addedEmptySections()/removedEmptySections()/addedLoadedChunks()/removedLoadedChunks()` + `flipUpdateTrackingSets()` (`:180-202`), maintained by `Storage.onChunkAdded/onChunkRemoved/refreshEmptySections` (`:274-312`), plus the new `ChunkSource` override `onSectionEmptinessChanged(int,int,int,boolean)` (`:205-207`). `LevelExtractor.extract` pulls these into `ChunkLoadingRenderState` and flips the buffers (`net/minecraft/client/renderer/extract/LevelExtractor.java:138-142`), and `SectionOcclusionGraph` applies them (`net/minecraft/client/renderer/SectionOcclusionGraph.java:146-147`). **`ImmPtlClientChunkMap`'s hash-map storage must reproduce this exact delta protocol per dimension** (add/remove loaded-chunk longs, empty-section transitions incl. `refreshEmptySections` on packet-replace of an existing chunk, double-buffered flip) or every renderer bound to a secondary dimension sees phantom/missing chunks in its occlusion graph — this is the same failure class as the already-fought SOG desync. Also: `replaceWithPacketData` no longer fires a load event by itself beyond `level.onChunkLoaded(pos)` (`:126`); the existing-chunk path calls `storage.refreshEmptySections(chunk)` (`:123`) — mirror both branches. |

### #46 `ClientLevel.<init>` + mutable `chunkSource` swap
| | |
|---|---|
| **IP usage** | `MixinClientLevel.java:97-110` — ctor RETURN inject swaps `chunkSource` to `ImmPtlClientChunkMap` |
| **Verdict** | **CHANGED** — ctor signature is now `ClientLevel(ClientPacketListener, ClientLevel.ClientLevelData, ResourceKey<Level>, Holder<DimensionType>, int serverChunkRadius, int serverSimulationDistance, LevelExtractor levelExtractor, boolean isDebug, long biomeZoomSeed, int seaLevel)` (`net/minecraft/client/multiplayer/ClientLevel.java:238-263`) |
| **26.2 equivalent** | `private final ClientChunkCache chunkSource` (`:252` assignment, decl `:169`) — still a private final field initialized in the ctor; the `@Mutable @Shadow` + RETURN-inject swap pattern still applies, only the target descriptor changes. Note the ctor now takes and stores a **`LevelExtractor`** (`:245,:255`) — the ClientLevel↔extractor identity coupling that the current mod already fought (levelExtractor orphaning) is now vanilla-structural; the swap must be coordinated with the render slice's per-dimension extractor management. |
| **Migration note** | Mechanical mixin retarget + the extractor-identity caution above. |

### #48 `LevelChunk` ctor / `replaceWithPacketData` / `replaceBiomes`
| | |
|---|---|
| **IP usage** | `ImmPtlClientChunkMap.java:150,:183,:135` |
| **Verdict** | **CHANGED** (one signature) |
| **26.2 equivalent** | Ctor `LevelChunk(Level, ChunkPos)` `net/minecraft/world/level/chunk/LevelChunk.java:98-100` (SAME); **`replaceWithPacketData(FriendlyByteBuf, Map<Heightmap.Types, long[]>, Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput>)` `:519-521` — heightmaps `CompoundTag` → `Map<Heightmap.Types, long[]>`**; `replaceBiomes(FriendlyByteBuf)` `:541-544` (SAME). |
| **Migration note** | The heightmap map comes off the packet; adjust the `ImmPtlClientChunkMap.replaceWithPacketData` override's pass-through types. |

---

## SAME (33)

**Server chunk system**

| # | Touchpoint (IP usage) | 26.2 citation | Note |
|---|---|---|---|
| 1 | `ServerChunkCache.chunkMap` public field (`ImmPtlChunkTracking.java:60-62`, `PlayerChunkLoading.java:148`) | `public final ChunkMap chunkMap` — `net/minecraft/server/level/ServerChunkCache.java:64` | ctor wiring `:104-118` now passes `TicketStorage` |
| 2 | `ChunkMap.getVisibleChunkIfPresent(long)` shadow (`MixinChunkMap_C.java:29`) | `protected @Nullable ChunkHolder getVisibleChunkIfPresent(long)` — `net/minecraft/server/level/ChunkMap.java:255` | was private; now protected — shadow/duck still valid |
| 3 | `ChunkMap.getUpdatingChunkIfPresent(long)` shadow (`MixinChunkMap_E.java:42`) | `public @Nullable ChunkHolder getUpdatingChunkIfPresent(long)` — `ChunkMap.java:251` | now **public** — shadow no longer needed |
| 4 | `ChunkMap.getPlayerViewDistance(ServerPlayer)` (+ vanilla copy `McHelper.java:241-245`) | `private int getPlayerViewDistance(ServerPlayer)` = `Mth.clamp(player.requestedViewDistance(), 2, this.serverViewDistance)` — `ChunkMap.java:817-819` | copy stays byte-identical; `requestedViewDistance()` at `ServerPlayer.java:1878` |
| 5 | `ChunkMap.applyChunkTrackingView` cancel (`MixinChunkMap_C.java:61-70`) | `private void applyChunkTrackingView(ServerPlayer, ChunkTrackingView)` — `ChunkMap.java:1109-1120` | `ChunkTrackingView` intact (`net/minecraft/server/level/ChunkTrackingView.java:7`); cancel still suppresses vanilla view diffs + `ClientboundSetChunkCacheCenterPacket` (`:1114`) |
| 7 | `ChunkMap.tick()` cancel (`MixinChunkMap_E.java:82-85`) | `protected void tick()` — `ChunkMap.java:1181-1212` | body changed (see #12 CHANGED) but the cancel target survives; it also covers per-player `updateChunkTracking` `:1182-1184` as in 1.21.3 |
| 8 | `ChunkMap.entityMap` shadow (`MixinChunkMap_E.java:30-32`) | `private final Int2ObjectMap<ChunkMap.TrackedEntity> entityMap` — `ChunkMap.java:146` | same type |
| 9 | `ChunkMap.updatePlayerStatus(ServerPlayer, boolean)` shadow (`MixinChunkMap_E.java:34-35`) | `private void updatePlayerStatus(ServerPlayer, boolean)` — `ChunkMap.java:1033` | |
| 10 | `ChunkMap.addEntity`/`removeEntity` inject points (`MixinChunkMap_E.java:44-77`) | `protected void addEntity(Entity)` `ChunkMap.java:1140-1164`; `protected void removeEntity(Entity)` `:1166-1179` | same structure (EnderDragonPart skip, trackingRange×16, updatePlayers on add) |
| 11 | `ChunkMap.getDistanceManager()` (`EntitySync.java:33`) | `public DistanceManager getDistanceManager()` — `ChunkMap.java:845` | |
| 13 | `ChunkHolder.getTickingChunk()` (`PlayerChunkLoading.java:155`, `ImmPtlChunkTracking.java:651`) | `public @Nullable LevelChunk getTickingChunk()` — `net/minecraft/server/level/ChunkHolder.java:86-88` | also new `getChunkToSend()` `:90-92` (ticking chunk gated on light `sendSync`) — the mod already uses the ChunkMap-level variant (`ChunkMap.java:836-839`) for send-eligibility; **consider it for `doChunkSending`'s holder check** (fidelity call: IP checks `getTickingChunk()`; vanilla's sender uses `getChunkToSend`, `PlayerChunkSender.java:95,101` — porting IP's copy against the vanilla original means adopting `getChunkToSend`) |
| 14 | `ChunkHolder.getEntityTickingChunkFuture()` + `ChunkResult.isSuccess()` (`ImmPtlChunkTickets.java:194-201`) | `public CompletableFuture<ChunkResult<LevelChunk>> getEntityTickingChunkFuture()` — `ChunkHolder.java:78-80`; `ChunkResult.isSuccess()` — `net/minecraft/server/level/ChunkResult.java:21` | future completes when the **loading**-tracker level reaches ≤31 (`ChunkHolder.updateFutures`, `:303-313`; `ChunkLevel.fullStatus` `ChunkLevel.java:38-46`) — hence FLAG_LOADING requirement in #23 |
| 15 | `ChunkHolder.broadcastChanges(LevelChunk)` (`ImmPtlChunkTracking.java:653`) | `public void broadcastChanges(LevelChunk)` — `ChunkHolder.java:174-218` | driver is now `ServerChunkCache.broadcastChangedChunks` over `chunkHoldersToBroadcast` (`ServerChunkCache.java:74,:353-365`, fed at `:463,:472,:588`); calling `broadcastChanges` directly from `syncBlockUpdateToClientImmediately` still works — it drains the same per-holder dirty state |
| 16 | `ChunkHolder.broadcast` packet-arg redirect + `PlayerProvider.getPlayers(ChunkPos, boolean)` redirect (`MixinChunkHolder.java:30-62`) | `private void broadcast(List<ServerPlayer>, Packet<?>)` — `ChunkHolder.java:236-238`; `interface PlayerProvider { List<ServerPlayer> getPlayers(ChunkPos, boolean borderOnly); }` `:341-343`; call sites `:178,:191` | `ChunkMap` is still the `PlayerProvider` impl (`ChunkMap.java:111,:1123-1134`) — IP's decision not to mixin `ChunkMap.getPlayers` still holds |
| 19 | `DistanceManager.runAllUpdates(ChunkMap)` RETURN inject (`MixinDistanceManager.java:46-55`) | `public boolean runAllUpdates(ChunkMap)` — `net/minecraft/server/level/DistanceManager.java:64-107` | still the point where `ChunkHolder.updateFutures` runs (`:79-81`) — the post-`runAllUpdates` `flushThrottling` rationale (`ImmPtlChunkTickets.java:151-162`) carries over unchanged |
| 20 | `DistanceManager.removePlayer(SectionPos, ServerPlayer)` + `playersPerChunk` NPE guard (`MixinDistanceManager.java:28-44`) | `public void removePlayer(SectionPos, ServerPlayer)` — `DistanceManager.java:118-129`; `playersPerChunk` field `:37` | `this.playersPerChunk.get(chunkPos)` then unconditional `.remove(player)` (`:121-122`) — **still NPEs if absent; the guard mixin is still required** |
| 21 | `DistanceManager$PlayerTicketTracker.onLevelChange(JII)`, `onLevelChange(JIZZ)`, `updateViewDistance`, `runAllUpdates` cancels (`MixinPlayerTicketTracker.java:11-25`) | all four exist with identical descriptors: `onLevelChange(long,int,int)` `DistanceManager.java:253`, `onLevelChange(long,int,boolean,boolean)` `:267`, `updateViewDistance(int)` `:257`, `runAllUpdates()` `:290` | internals now go through `ticketStorage.addTicket/removeTicket` of `PLAYER_LOADING` tickets (`:269-285`) — cancelling still disables vanilla player-view chunk loading. Note `DistanceManager.addPlayer` separately adds a `PLAYER_SIMULATION` ticket (`:115`, flags 12 = SIMULATION\|KEEP_DIMENSION_ACTIVE, no LOADING — `TicketType.java:21`) exactly as 1.21.3's addPlayer fed the ticking tracker; IP left that alive then (verified: zero references to `TickingTracker`/`tickingTicketsTracker` anywhere in the IP tree) — leave it alive now |
| 22 | `DistanceManager.inEntityTickingRange(long)` (`EntitySync.java:62`) | `public boolean inEntityTickingRange(long)` — `DistanceManager.java:135-137` | **semantics now backed by `SimulationChunkTracker`** (SIMULATION-flag tickets only) — reinforces FLAG_SIMULATION in #23; vanilla gate usage: `ServerLevel.java:434`, `ChunkMap.java:1202` |
| 25 | `ThreadedLevelLightEngine` via `IEChunkMap.ip_getLightingProvider` (`MixinChunkMap_C.java:48-51`) | `public class ThreadedLevelLightEngine extends LevelLightEngine` — `net/minecraft/server/level/ThreadedLevelLightEngine.java:26`; `ChunkMap.getLightEngine()` `ChunkMap.java:247` | |

**Chunk sending / networking**

| # | Touchpoint (IP usage) | 26.2 citation | Note |
|---|---|---|---|
| 26 | `PlayerChunkSender` members, all overwritten/cancelled (`MixinPlayerChunkSender.java:30-75`), logic vanilla-copied in `PlayerChunkLoading` | `markChunkPendingToSend(LevelChunk)` `net/minecraft/server/network/PlayerChunkSender.java:40`; `dropChunk(ServerPlayer, ChunkPos)` `:44`; `sendNextChunks(ServerPlayer)` `:50`; `onChunkBatchReceivedByClient(float)` `:114`; `isPending(long)` `:128`; ctor `(boolean memoryConnection)` `:36`; per-tick call site `MinecraftServer.java:1143-1146` (`player.connection.chunkSender.sendNextChunks(player)` inside tickChildren, followed by `connection.resumeFlushing()`) | Vanilla-copy refresh for `PlayerChunkLoading`: (a) `dropChunk` gained an `player.isAlive()` guard before sending the forget packet (`:45-47`); (b) `sendChunk` now also calls `level.debugSynchronizers().startTrackingChunk(connection.player, pos)` (`:83`); (c) chunk eligibility uses `chunkMap.getChunkToSend` (`:95,:101` → `ChunkMap.java:836-839`, gated on light-send sync) rather than raw ticking chunk; (d) batching/quota math (`:50-74,:114-126`) is otherwise line-for-line the same as IP's copy |
| 27 | `ClientboundLevelChunkWithLightPacket(LevelChunk, LevelLightEngine, BitSet, BitSet)` (`PlayerChunkLoading.java:201-204`) | ctor — `net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket.java:22-31` | identical shape; vanilla call `PlayerChunkSender.java:77` |
| 28 | `ClientboundForgetLevelChunkPacket(ChunkPos)` (`ImmPtlChunkTracking.java:267-269,:510,:533`) | `record ClientboundForgetLevelChunkPacket(ChunkPos pos)` — `net/minecraft/network/protocol/game/ClientboundForgetLevelChunkPacket.java:9` | |
| 29 | `ClientboundChunkBatchStartPacket.INSTANCE` / `ClientboundChunkBatchFinishedPacket(int)` (`PlayerChunkLoading.java:166,:183`) | `INSTANCE` — `ClientboundChunkBatchStartPacket.java:9`; `record ClientboundChunkBatchFinishedPacket(int batchSize)` — `ClientboundChunkBatchFinishedPacket.java:8` | |
| 30 | `ServerboundChunkBatchReceivedPacket.desiredChunksPerTick()` + `handleChunkBatchReceived` inject (`MixinServerGamePacketListenerImpl_ChunkSync.java:17-29`) | `record ServerboundChunkBatchReceivedPacket(float desiredChunksPerTick)` — `ServerboundChunkBatchReceivedPacket.java:8`; `ServerGamePacketListenerImpl.handleChunkBatchReceived` — `net/minecraft/server/network/ServerGamePacketListenerImpl.java:2167-2169` | |
| 31 | `ServerGamePacketListenerImpl.send(Packet)`, `.player` (`PlayerChunkLoading.java:112-118`, `ImmPtlChunkTracking.java:263`) | `public ServerPlayer player` — `ServerGamePacketListenerImpl.java:239`; `send(Packet<?>)` inherited — `net/minecraft/server/network/ServerCommonPacketListenerImpl.java:156` (+ listener overload `:160`) | `chunkSender` field public final at `:240` |
| 32 | `ServerCommonPacketListenerImpl.connection` accessor + `Connection.isMemoryConnection()` (`IEServerCommonPacketListenerImpl.java:8-12`, `ImmPtlChunkTracking.java:141-144`) | `protected final Connection connection` — `ServerCommonPacketListenerImpl.java:40`; `isMemoryConnection()` — `net/minecraft/network/Connection.java:403` | accessor duck ports unchanged |
| 35 | `ClientboundGameEventPacket` + `START_RAINING`/`RAIN_LEVEL_CHANGE`/`THUNDER_LEVEL_CHANGE` (`WorldInfoSender.java:64-94`) | ctor `(Type, float)` — `net/minecraft/network/protocol/game/ClientboundGameEventPacket.java:37-40`; constants `:15,:21,:22` (+`STOP_RAINING` `:16`) | the weather half of `sendWorldInfo` ports 1:1 |
| 36 | `PlayerList` members — login hook (`MixinPlayerList.java:52-61`), respawn/disconnect cleanup (`MixinPlayerManager_MA.java:15-32`), broadcast rerouting (`MixinPlayerList.java:64-136`), `McHelper.getViewDistanceOnServer` (`McHelper.java:232-234`) | `placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)` — `net/minecraft/server/players/PlayerList.java:142`; `remove(ServerPlayer)` `:303`; `respawn(ServerPlayer, boolean keepAllPlayerData, Entity.RemovalReason removalReason)` `:389`; `broadcastAll(Packet)` `:479`; `broadcastAll(Packet, ResourceKey<Level>)` `:485`; `broadcast(@Nullable Player, double, double, double, double, ResourceKey<Level>, Packet)` `:603`; `getViewDistance()` `:687`; `sendLevelInfo(ServerPlayer, ServerLevel)` `:642` | **All signatures SAME — including `respawn`.** The `Entity.RemovalReason` param already existed in 1.21.3: IP's own `@Inject` handler mirrors the 3-arg target — `MixinPlayerManager_MA.onPlayerRespawn(ServerPlayer, boolean, Entity.RemovalReason, CallbackInfoReturnable<ServerPlayer>)` (`MixinPlayerManager_MA.java:15-24`). The respawn-HEAD cleanup mixin ports with **no descriptor change**. Behavioral note (informational, no IP code change): `sendLevelInfo` now sends the global clock full-sync `server.clockManager().createFullSyncPacket()` (`:645`) instead of per-level time — interacts with #34; after IP teleports the player between dims **without** vanilla respawn, no clock re-sync is needed (clocks are connection-global) |

**Server core / level**

| # | Touchpoint (IP usage) | 26.2 citation | Note |
|---|---|---|---|
| 38 | `ServerTickRateManager.nanosecondsPerTick()` (`PerformanceLevel.java:26-29`) | `public long nanosecondsPerTick()` — base class `net/minecraft/world/TickRateManager.java:28-30` (`ServerTickRateManager` extends it) | |
| 44 | `Mth.clamp` (`PlayerChunkLoading.java:236`, `McHelper.java:244`) | `net/minecraft/util/Mth.java:94` (int; float/long overloads adjacent) | |

**Client**

| # | Touchpoint (IP usage) | 26.2 citation | Note |
|---|---|---|---|
| 47 | `ClientLevel.onChunkLoaded(ChunkPos)`, `unload(LevelChunk)` (`ImmPtlClientChunkMap.java:80,:162`) | `onChunkLoaded(ChunkPos)` — `net/minecraft/client/multiplayer/ClientLevel.java:511-514`; `unload(LevelChunk)` — `:505-509` | unload also stops entity-section ticking + disables light (`:507-508`) — keep call order in the `drop` override |
| 49 | `ClientboundLevelChunkPacketData.BlockEntityTagOutput` (`ImmPtlClientChunkMap.java:143`) | `public interface BlockEntityTagOutput` — `net/minecraft/network/protocol/game/ClientboundLevelChunkPacketData.java:162`; consumer getter `:95` | |
| 50 | `ChunkStatus`, `LightLayer`, `SectionPos` (`ImmPtlClientChunkMap.java:106,:236`) | `ChunkStatus` — `net/minecraft/world/level/chunk/status/ChunkStatus.java:15`; `LightLayer` — `net/minecraft/world/level/LightLayer.java:3`; `SectionPos` — `net/minecraft/core/SectionPos.java:16` | |

---

## FABRIC-API (4) — route through the mod's loader abstraction (common/fabric/neoforge)

No `migration/inventory/current-mod-core.md` exists yet; flagged per instructions.

| # | Touchpoint | IP usage | Note |
|---|---|---|---|
| 51 | `ServerTickEvents.END_SERVER_TICK` | `ImmPtlChunkTracking.java:45`, `ServerPerformanceMonitor.java:18`, `WorldInfoSender.java:19` | **FABRIC-API** — map to the mod's common end-of-server-tick event abstraction (NeoForge: `ServerTickEvent.Post`). Ordering caution: the tracking tick must run in the same tick phase relative to `MinecraftServer.tickChildren`'s `sendNextChunks` loop (`MinecraftServer.java:1143-1146`) as on Fabric — chunk records created this tick are sent by the hijacked sender the same tick on login via `immediatelyUpdateForPlayer`, otherwise later. |
| 52 | `PayloadTypeRegistry.playS2C()` | `PacketRedirection.java:64` | **FABRIC-API** — network slice owns the mapping; loader-abstracted payload registration (NeoForge `PayloadRegistrar`). |
| 53 | Fabric attachment-sync internals `AttachmentTargetImpl.fabric_computeInitialSyncChanges` / `AttachmentChange.partitionAndSendPackets` | `PlayerChunkLoading.java:212-227` (compensates for cancelling Fabric's `ChunkDataSenderMixin` in `sendNextChunks`) | **FABRIC-API (internals!)** — this is a copy of Fabric-internal chunk-attachment sync. On the Fabric side it must be re-verified against the 26.2 Fabric API's chunk-sending mixin target (vanilla `sendNextChunks` survived at `PlayerChunkSender.java:50`, so Fabric likely still mixes in there); on NeoForge the equivalent is its own attachment sync (or nothing if the mod uses no synced chunk attachments). Loader-specific shim, not common code. |
| 54 | `qouteall.dimlib.api.DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT` | `ImmPtlChunkTracking.java:48-50`, `ImmPtlChunkTickets.java:72-74`, `EntitySync.java:16` | **Third-party (DimLib), not vanilla** — needs the mod's own dynamic-dimension-removal hook or a DimLib port decision (out of this slice's scope; flag to the dimension-management slice). |

---

## Cross-cutting port notes (this slice)

1. **`ImmPtlChunkTickets` rewrite surface:** `markForLoading`/queues/throttling logic is engine-agnostic and ports as-is; only `addTicket`/`purge`/`removeAllTicketsInWorld` re-target `TicketStorage` (via `ServerChunkCache` wrappers or a storage accessor), and `getChunkHolder`→`ChunkMap.getVisibleChunkIfPresent` (`ChunkMap.java:255`) plus `getEntityTickingChunkFuture` polling (`ChunkHolder.java:78`) are unchanged. The `IEDistanceManager` accessor set shrinks to (at most) one `ticketStorage` accessor.
2. **Registry-phase requirement:** the `"imm_ptl"` `TicketType` is now a `BuiltInRegistries.TICKET_TYPE` registration (`BuiltInRegistries.java:337`) — wire through the mod's multiloader registry bootstrap, not static init at first use.
3. **`isEmpty`-keep-alive decision (#39)** and **flags choice (#23)** are the two fidelity decision points; everything else in this slice is mechanical.
4. **Client storage replacement (#45)** is the largest genuinely new work item: the delta-set protocol (`ClientChunkCache.java:180-207`, consumed at `LevelExtractor.java:138-142` → `SectionOcclusionGraph.java:146-147`) did not exist in 1.21.3 and has no IP analog to copy — it must be designed to feed each per-dimension renderer/extractor pair. Coordinate with the render-slice map (`MIGRATION_API_MAP.md`, LevelExtractor/SectionOcclusionGraph sections).
5. **`WorldInfoSender`** shrinks to weather-only (#34/#43); document the intentional deletion of the time half against the zero-deviation rule (the deviation is forced: the vanilla feature it patched is now global and dimension-independent).
