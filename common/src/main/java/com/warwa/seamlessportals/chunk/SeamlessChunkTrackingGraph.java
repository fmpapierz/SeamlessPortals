package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * E4 — Per-tick (player, dim, chunkPos) chunk-watch graph.
 *
 * <p>Direct port of IP 1.19's {@code NewChunkTrackingGraph}, mapped to
 * MC 26.1.2 APIs and trimmed to what our MVP needs. See
 * {@code memory/full_ip_port_design.md} for the full design rationale.
 *
 * <p><b>Per server tick:</b>
 * <ol>
 *   <li>For each player whose
 *       {@code id % UPDATE_INTERVAL == gameTime % UPDATE_INTERVAL}: run
 *       {@link #updateForPlayer} which iterates
 *       {@link SeamlessChunkVisibility#getBaseChunkLoaders} and adds /
 *       refreshes one {@link PlayerWatchRecord} per
 *       (player, loader-dim, chunkPos) reached.</li>
 *   <li>For every player every tick: run
 *       {@link #flushPendingLoading} which dequeues new records (in
 *       distance-priority order) up to a per-tick rate budget,
 *       emits {@link #beginWatchChunkSignal} for each, and adds
 *       a {@link SeamlessLoadingTicket} for indirect loaders so the
 *       chunk stays loaded server-side.</li>
 *   <li>Every {@code UPDATE_INTERVAL} ticks: run
 *       {@link #updateAndPurge} which removes records whose
 *       {@code lastWatchTime} is older than the unload delay, emits
 *       {@link #endWatchChunkSignal} for each removed record that was
 *       loaded to the player, and drops loading tickets for chunks no
 *       record points to anymore.</li>
 * </ol>
 *
 * <p>Signal listeners (registered by {@code SeamlessChunkDataSync} in
 * E6) translate begin/end signals into chunk-data / forget packets
 * sent via the redirected payload.
 */
public final class SeamlessChunkTrackingGraph {

    private SeamlessChunkTrackingGraph() {}

    /**
     * Per-player update throttle (matches IP 1.19's
     * {@code NewChunkTrackingGraph.updateInterval = 13}). With one
     * player, each player's heavy work runs once every 13 ticks
     * (650 ms apart). With more players it spreads naturally —
     * each player runs at their own offset within the 13-tick window.
     */
    public static final int UPDATE_INTERVAL = 13;

    /**
     * Ticks since last refresh before a record is purged. Must be
     * greater than {@link #UPDATE_INTERVAL} so a single skipped update
     * tick doesn't immediately purge.
     *
     * <p><b>1200 ticks = 60 seconds.</b> Long enough that repeat
     * teleports between OW and nether keep cross-dim chunks loaded
     * server-side: only the FIRST visit triggers chunk-gen lag;
     * subsequent visits within 60 s of leaving are gen-free because
     * tickets and records persist.
     *
     * <p>Memory cost: each chunk loaded via SeamlessLoadingTicket
     * holds a chunk in memory for 60 s after last view. With
     * radius 6 × 2 portals = ~340 chunks/player. ~16 KB each = 5 MB
     * per player held briefly. Acceptable.
     */
    public static final int UNLOAD_DELAY_TICKS = 1200;

    /**
     * Number of player ticks during which the per-player initial
     * delivery burst is in effect (mirrors IP 1.19's
     * {@code if (player.tickCount < 100)}). Restarts on dim-change so
     * post-teleport gets a fresh burst window.
     */
    public static final int INITIAL_BURST_DURATION_TICKS = 100;

    /**
     * Now sourced from {@link PerformanceLevel} per-player. The
     * tracked-on-record {@code lastDimChangeTick} (E13.5) compared
     * against the player's tickCount allows the burst to reset on
     * dim-change.
     */

    /**
     * Window during which the {@code wasSentViaRedirect} latch on a
     * record is honored by {@code PlayerChunkSenderMixin} (E11). After
     * this many ticks, vanilla is allowed to re-send the chunk —
     * essential for recovery from the section-count guard's stale-
     * chunk drops, which would otherwise leave gaps that vanilla
     * never refills (because the latch was set, the chunk wasn't
     * actually applied, but the suppression mixin still blocks the
     * resend). 200 ticks = 10 s.
     *
     * <p>The trade-off: post-window vanilla sends will overwrite
     * already-good chunks and trigger mesh rebuilds. Originally 200
     * ticks (10 s) which caused the second-teleport stutter:
     * after 10 s in dest dim, vanilla started re-sending chunks the
     * client already had, invalidating meshes — visible as 1-3 frames
     * of empty terrain on every subsequent teleport.
     *
     * <p>Bumped to 24000 ticks (20 minutes) to cover any reasonable
     * play session. Chunks are stable unless block-modified, which
     * goes through {@code RemoteBlockUpdater} not full chunk re-send,
     * so suppressing the full-chunk path indefinitely is safe.
     */
    public static final long REDIRECT_SUPPRESS_WINDOW_TICKS = 24000L;

    /**
     * @deprecated Use {@link PerformanceLevel#directDeliveryBudgetPerTick()} via
     * {@link PlayerInfo#performanceLevel} instead.
     */
    @Deprecated
    public static final int DIRECT_BUDGET = 50;

    public static final class PlayerWatchRecord {
        public final ServerPlayer player;
        public final ResourceKey<Level> dimension;
        public final long chunkPos;
        public long lastWatchTime;
        public int distanceToSource;
        public boolean isDirectLoading;
        public boolean isLoadedToPlayer;
        public boolean isValid = true;

        /**
         * Tick-time at which {@link SeamlessChunkDataSync#attemptSend}
         * last successfully sent a chunk-data packet to {@code player}
         * for this record via
         * {@link com.warwa.seamlessportals.network.SeamlessPacketRedirection}.
         * {@code 0L} means "never sent."
         *
         * <p>Read by {@code PlayerChunkSenderMixin} (E11) — vanilla's
         * post-teleport redundant chunk-send is suppressed only while
         * {@code currentTick - redirectSentAtTick < REDIRECT_SUPPRESS_WINDOW_TICKS}.
         * After the window, vanilla is allowed to send again — needed
         * to recover from the {@code ChunkPacketGuardMixin} stale-chunk
         * drops, where the server thinks the chunk was delivered (latch
         * set) but the client dropped it due to a section-count race
         * during teleport.
         */
        public long redirectSentAtTick = 0L;

        public PlayerWatchRecord(
                ServerPlayer player,
                ResourceKey<Level> dimension,
                long chunkPos,
                long lastWatchTime,
                int distanceToSource,
                boolean isDirectLoading,
                boolean isLoadedToPlayer) {
            this.player = player;
            this.dimension = dimension;
            this.chunkPos = chunkPos;
            this.lastWatchTime = lastWatchTime;
            this.distanceToSource = distanceToSource;
            this.isDirectLoading = isDirectLoading;
            this.isLoadedToPlayer = isLoadedToPlayer;
        }

        @Override
        public String toString() {
            return String.format(
                "%s (%d,%d) dist:%d valid:%s loaded:%s direct:%s redirectAt:%d",
                dimension.identifier(),
                ChunkPos.getX(chunkPos),
                ChunkPos.getZ(chunkPos),
                distanceToSource,
                isValid, isLoadedToPlayer, isDirectLoading, redirectSentAtTick);
        }
    }

    /**
     * Per-player ephemeral state that doesn't fit in
     * {@link PlayerWatchRecord} — visible-dimensions set, the
     * distance-priority pending-load queue, and performance level.
     */
    public static final class PlayerInfo {
        public final Set<ResourceKey<Level>> visibleDimensions = new HashSet<>();
        /**
         * Index = chebyshev distance in chunks from a loader's center.
         * Each slot is the FIFO queue of records at that distance
         * waiting to fire {@link #beginWatchChunkSignal}. The
         * {@link #flushPendingLoading} drain runs distance-ascending so
         * close chunks deliver before far chunks each tick.
         */
        public final ArrayList<ArrayDeque<PlayerWatchRecord>> distanceToPendingChunks = new ArrayList<>();

        /**
         * Per-player performance tier. Authoritative source: the
         * server-side {@link SeamlessServerPerformanceMonitor}, which
         * auto-scales based on average tick time. Read fresh on each
         * lookup so the graph picks up performance-level transitions
         * within the same player session.
         *
         * <p>Future work: also accept a client-reported FPS hint via
         * a serverbound payload; server takes
         * {@code Math.max(serverLevel, clientLevel)} (worse of the
         * two) to handle client-side bottlenecks separately.
         */
        public PerformanceLevel performanceLevel() {
            return SeamlessServerPerformanceMonitor.getLevel();
        }

        /**
         * Server gameTime at which this player most recently changed
         * dimension. Used by {@link #flushPendingLoading} to apply the
         * initial-burst delivery budget for {@link
         * #INITIAL_BURST_DURATION_TICKS} ticks after a teleport — so
         * the fresh direct records for the new active dim deliver
         * fast instead of waiting through 100+ ticks of steady rate.
         */
        public long lastDimChangeTick = 0L;

        /**
         * Last-seen dimension. Used to detect dim changes and
         * reset {@link #lastDimChangeTick}.
         */
        public ResourceKey<Level> lastSeenDimension = null;

        public void markPendingLoading(PlayerWatchRecord record) {
            int distance = record.distanceToSource;
            while (distanceToPendingChunks.size() <= distance) {
                distanceToPendingChunks.add(null);
            }
            ArrayDeque<PlayerWatchRecord> deque = distanceToPendingChunks.get(distance);
            if (deque == null) {
                deque = new ArrayDeque<>();
                distanceToPendingChunks.set(distance, deque);
            }
            deque.add(record);
        }
    }

    // ─── State ───────────────────────────────────────────────────────

    private static final Map<ResourceKey<Level>,
            Long2ObjectLinkedOpenHashMap<ArrayList<PlayerWatchRecord>>> data = new HashMap<>();

    private static final WeakHashMap<ServerPlayer, PlayerInfo> playerInfoMap = new WeakHashMap<>();

    /**
     * Global chunk loaders that aren't tied to any single player.
     * Direct port of IP 1.19's {@code NewChunkTrackingGraph
     * .additionalChunkLoaders}.
     *
     * <p><b>What this is for:</b> when a portal is created, IP
     * registers a chunk loader for the destination area so chunks
     * pre-generate in the background. The loader lives until the
     * portal is destroyed; chunks stay loaded server-side that
     * entire time. This is THE mechanism that makes IP's teleports
     * feel instant — the chunk-gen cost is paid at portal
     * <b>creation</b> time (with a "loading" indicator), not at
     * <b>teleport</b> time.
     *
     * <p>Stored as {@code WeakReference} so a loader that's not
     * explicitly removed can be GC'd and the weak ref cleaned up by
     * {@code updateAndPurge}.
     */
    private static final ArrayList<java.lang.ref.WeakReference<SeamlessChunkLoader>>
        additionalChunkLoaders = new ArrayList<>();

    // ─── Signals ─────────────────────────────────────────────────────

    /**
     * Watch-event listener. {@code isDirectLoading} tells the listener
     * whether vanilla owns delivery for this chunk (true → bookkeeping
     * only, listener should not send a chunk-data packet) or we own it
     * (false → cross-dim, listener should send via redirection).
     */
    @FunctionalInterface
    public interface WatchListener {
        void onWatchChange(ServerPlayer player, DimChunkPos pos, boolean isDirectLoading);
    }

    /** Fires when a record transitions from pending → loaded. Sync-listener pattern. */
    public static final List<WatchListener> beginWatchChunkSignal = new ArrayList<>();
    /** Fires when a record transitions from loaded → unloaded (purge). */
    public static final List<WatchListener> endWatchChunkSignal = new ArrayList<>();

    private static void emitBegin(ServerPlayer player, DimChunkPos pos, boolean direct) {
        for (WatchListener listener : beginWatchChunkSignal) {
            try {
                listener.onWatchChange(player, pos, direct);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS GRAPH] beginWatch listener threw: {}", t.toString());
            }
        }
    }

    private static void emitEnd(ServerPlayer player, DimChunkPos pos, boolean direct) {
        for (WatchListener listener : endWatchChunkSignal) {
            try {
                listener.onWatchChange(player, pos, direct);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS GRAPH] endWatch listener threw: {}", t.toString());
            }
        }
    }

    // ─── Public API ──────────────────────────────────────────────────

    private static Long2ObjectLinkedOpenHashMap<ArrayList<PlayerWatchRecord>>
            getChunkRecordMap(ResourceKey<Level> dim) {
        return data.computeIfAbsent(dim, k -> new Long2ObjectLinkedOpenHashMap<>());
    }

    public static PlayerInfo getPlayerInfo(ServerPlayer player) {
        return playerInfoMap.computeIfAbsent(player, k -> new PlayerInfo());
    }

    public static void tick(MinecraftServer server) {
        if (server == null) return;
        long gameTime = server.overworld().getGameTime();
        // Reset per-tick ticket budget. Drained by flushPendingLoading
        // for any player; once exhausted, additional ticket-add work
        // defers to next tick.
        ticketAddsThisTick = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                if (player.getId() % UPDATE_INTERVAL == gameTime % UPDATE_INTERVAL) {
                    updateForPlayer(player, gameTime);
                }
                flushPendingLoading(player);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS GRAPH] tick failed for {}: {}",
                    player.getName().getString(), t.toString());
            }
        }
        if (gameTime % UPDATE_INTERVAL == 0) {
            try {
                updateAndPurge(server, gameTime);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS GRAPH] purge failed: {}", t.toString());
            }
        }
    }

    public static void updateForPlayer(ServerPlayer player, long gameTime) {
        PlayerInfo playerInfo = getPlayerInfo(player);
        playerInfo.visibleDimensions.clear();

        // Detect dim change to reset the initial-burst window. The
        // window restarts whenever the player crosses dimensions so
        // post-teleport delivery starts at the burst rate, not the
        // steady rate.
        ResourceKey<Level> currentDim = player.level().dimension();
        if (playerInfo.lastSeenDimension == null
                || !playerInfo.lastSeenDimension.equals(currentDim)) {
            playerInfo.lastDimChangeTick = gameTime;
            playerInfo.lastSeenDimension = currentDim;
        }

        SeamlessChunkVisibility.getBaseChunkLoaders(player).forEach(loader ->
            updatePlayerForChunkLoader(player, gameTime, loader, playerInfo));
    }

    private static void updatePlayerForChunkLoader(
            ServerPlayer player, long gameTime,
            SeamlessChunkLoader loader, PlayerInfo info) {
        ResourceKey<Level> loaderDim = loader.center.dimension();
        info.visibleDimensions.add(loaderDim);

        Long2ObjectLinkedOpenHashMap<ArrayList<PlayerWatchRecord>> chunkRecordMap =
            getChunkRecordMap(loaderDim);

        loader.foreachChunkPos((dim, x, z, distance) -> {
            long chunkPos = ChunkPos.pack(x, z);
            ArrayList<PlayerWatchRecord> records =
                chunkRecordMap.computeIfAbsent(chunkPos, k -> new ArrayList<>());

            int idx = indexOf(records, r -> r.player == player);
            if (idx == -1) {
                PlayerWatchRecord newRecord = new PlayerWatchRecord(
                    player, dim, chunkPos, gameTime, distance,
                    loader.isDirectLoader, false);
                records.add(newRecord);
                info.markPendingLoading(newRecord);
            } else {
                PlayerWatchRecord rec = records.get(idx);
                if (rec.lastWatchTime == gameTime) {
                    // Same turn — merge with closer-distance update.
                    if (distance < rec.distanceToSource) {
                        rec.distanceToSource = distance;
                        info.markPendingLoading(rec);
                    }
                    rec.isDirectLoading = rec.isDirectLoading | loader.isDirectLoader;
                } else {
                    // First update this turn.
                    if (distance < rec.distanceToSource) {
                        info.markPendingLoading(rec);
                    }
                    rec.distanceToSource = distance;
                    rec.lastWatchTime = gameTime;
                    rec.isDirectLoading = loader.isDirectLoader;
                }
            }
        });
    }

    /**
     * Hard cap on {@link SeamlessLoadingTicket#addTicketIfNotLoaded}
     * calls per tick across ALL players. Server-thread protection:
     * each ticket-add triggers DistanceManager level propagation +
     * eventually a chunk-gen promotion through worker → SERVER thread
     * for the FULL status. Bursting hundreds of new tickets in one
     * tick (e.g. when player walks into adaptive-radius near-portal
     * zone and updateForPlayer queues 100+ records) saturates the
     * server-thread chunk-gen pipeline → 5+ second freezes.
     *
     * <p>This cap rate-limits ticket creation. Records that can't be
     * ticketed this tick stay queued (we don't pop them) and retry
     * next tick. Indirect delivery is gated behind ticket-add (chunk
     * has to be loaded server-side before it can be sent), so this
     * implicitly throttles the whole indirect pipeline.
     *
     * <p>4/tick × 20 tps = 80 tickets/sec ≈ matches the integrated
     * server's chunk-gen+promote rate empirically. Higher values
     * accumulate uncompleted gen tasks and re-introduce freezes.
     */
    private static final int TICKET_ADD_BUDGET_PER_TICK = 4;

    /** Reset each server tick by {@link #tick(MinecraftServer)}. */
    private static int ticketAddsThisTick = 0;

    public static void flushPendingLoading(ServerPlayer player) {
        PlayerInfo info = getPlayerInfo(player);
        int indirectLimit = getIndirectLimitPerTick(player, info);
        int directLimit = getDirectLimitPerTick(player, info);
        int loaded = 0;
        int directLoaded = 0;

        for (int distance = 0; distance < info.distanceToPendingChunks.size(); distance++) {
            ArrayDeque<PlayerWatchRecord> records = info.distanceToPendingChunks.get(distance);
            if (records == null) continue;
            while (!records.isEmpty()
                    && loaded < indirectLimit
                    && directLoaded < directLimit) {
                PlayerWatchRecord rec = records.peekFirst();
                if (rec == null) break;
                if (!rec.isValid || rec.isLoadedToPlayer) {
                    records.pollFirst();
                    continue;
                }

                // For indirect records, check ticket-add budget before
                // committing. If exhausted, leave the record at the
                // head of the queue for retry next tick. This is the
                // architectural fix for SP server-thread saturation
                // when adaptive radius scales up.
                if (!rec.isDirectLoading
                        && ticketAddsThisTick >= TICKET_ADD_BUDGET_PER_TICK) {
                    break;
                }

                records.pollFirst();

                MinecraftServer server = player.level().getServer();
                if (server == null) continue;
                ServerLevel level = server.getLevel(rec.dimension);
                if (level == null) continue;

                rec.isLoadedToPlayer = true;
                emitBegin(player,
                    new DimChunkPos(rec.dimension, ChunkPos.unpack(rec.chunkPos)),
                    rec.isDirectLoading);

                if (!rec.isDirectLoading) {
                    SeamlessLoadingTicket.addTicketIfNotLoaded(level, ChunkPos.unpack(rec.chunkPos));
                    ticketAddsThisTick++;
                    loaded++;
                } else {
                    directLoaded++;
                }
            }
            if (loaded >= indirectLimit && directLoaded >= directLimit) break;
            if (ticketAddsThisTick >= TICKET_ADD_BUDGET_PER_TICK
                    && directLoaded >= directLimit) break;
        }
    }

    /**
     * Whether this player is within the post-join / post-teleport
     * burst window. Inside the window, delivery rates are bumped per
     * {@link PerformanceLevel#initialBurstBudget()}.
     */
    private static boolean isInBurstWindow(ServerPlayer player, PlayerInfo info) {
        if (player.tickCount < INITIAL_BURST_DURATION_TICKS) return true;
        long ticksSinceDimChange =
            McHelperGameTime(player) - info.lastDimChangeTick;
        return ticksSinceDimChange < INITIAL_BURST_DURATION_TICKS;
    }

    private static long McHelperGameTime(ServerPlayer player) {
        MinecraftServer s = player.level().getServer();
        return s == null ? 0 : s.overworld().getGameTime();
    }

    private static int getIndirectLimitPerTick(ServerPlayer player, PlayerInfo info) {
        if (isInBurstWindow(player, info)) return info.performanceLevel().initialBurstBudget();
        return info.performanceLevel().indirectDeliveryBudgetPerTick();
    }

    private static int getDirectLimitPerTick(ServerPlayer player, PlayerInfo info) {
        if (isInBurstWindow(player, info)) return info.performanceLevel().initialBurstBudget();
        return info.performanceLevel().directDeliveryBudgetPerTick();
    }

    private static void updateAndPurge(MinecraftServer server, long currTime) {
        // Purge stale records.
        data.forEach((dim, chunkRecords) -> {
            chunkRecords.long2ObjectEntrySet().removeIf(entry -> {
                long chunkPosLong = entry.getLongKey();
                ArrayList<PlayerWatchRecord> records = entry.getValue();

                records.removeIf(record -> {
                    if (record.player.isRemoved() || shouldUnload(currTime, record)) {
                        if (record.isLoadedToPlayer && !record.player.isRemoved()) {
                            emitEnd(record.player,
                                new DimChunkPos(dim,
                                    ChunkPos.getX(chunkPosLong),
                                    ChunkPos.getZ(chunkPosLong)),
                                record.isDirectLoading);
                        }
                        record.isValid = false;
                        return true;
                    }
                    return false;
                });

                return records.isEmpty();
            });
        });

        // Compute the set of chunks held by any GLOBAL additional
        // chunk loader — these stay ticketed even when no per-player
        // record references them. This is the mechanism IP uses to
        // pre-load destination chunks at portal-creation time.
        java.util.Map<ResourceKey<Level>, it.unimi.dsi.fastutil.longs.LongSet>
            additionalLoaded = new java.util.HashMap<>();
        additionalChunkLoaders.removeIf(weakRef -> weakRef.get() == null);
        for (java.lang.ref.WeakReference<SeamlessChunkLoader> ref : additionalChunkLoaders) {
            SeamlessChunkLoader loader = ref.get();
            if (loader == null) continue;
            ResourceKey<Level> dim = loader.center.dimension();
            ServerLevel world = server.getLevel(dim);
            if (world == null) continue;
            it.unimi.dsi.fastutil.longs.LongSet dimSet = additionalLoaded.computeIfAbsent(
                dim, d -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
            loader.foreachChunkPos((d, x, z, dist) -> {
                long packed = ChunkPos.pack(x, z);
                dimSet.add(packed);
                SeamlessLoadingTicket.addTicketIfNotLoaded(world, ChunkPos.unpack(packed));
            });
        }

        // Drop loading tickets for chunks NO record AND NO global
        // loader points to anymore.
        for (ServerLevel world : server.getAllLevels()) {
            Long2ObjectLinkedOpenHashMap<ArrayList<PlayerWatchRecord>> chunkRecordMap =
                getChunkRecordMap(world.dimension());
            it.unimi.dsi.fastutil.longs.LongSet additionalForDim =
                additionalLoaded.getOrDefault(world.dimension(),
                    it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET);
            LongList toRemove = new LongArrayList();
            LongSortedSet ticketRecord = SeamlessLoadingTicket.getRecord(world);
            ticketRecord.forEach((long pos) -> {
                if (!chunkRecordMap.containsKey(pos) && !additionalForDim.contains(pos)) {
                    toRemove.add(pos);
                }
            });
            toRemove.forEach((long pos) ->
                SeamlessLoadingTicket.removeTicketIfPresent(world, ChunkPos.unpack(pos)));
        }

        // Drop player-info for removed players.
        playerInfoMap.entrySet().removeIf(e -> e.getKey().isRemoved());
    }

    /**
     * Register a chunk loader that lives until explicitly removed
     * (or GC'd). The loader's chunks stay ticket-loaded server-side
     * regardless of whether any player has them in their watch
     * records.
     *
     * <p>Used by {@code PortalManager.onPortalFormed} (E12) to
     * pre-load destination chunks at portal-creation time so the
     * teleport itself is gen-free.
     */
    public static void addGlobalAdditionalChunkLoader(SeamlessChunkLoader loader) {
        additionalChunkLoaders.add(new java.lang.ref.WeakReference<>(loader));
    }

    public static void removeGlobalAdditionalChunkLoader(SeamlessChunkLoader loader) {
        additionalChunkLoaders.removeIf(ref -> {
            SeamlessChunkLoader current = ref.get();
            return current == null || current == loader;
        });
    }

    private static boolean shouldUnload(long currTime, PlayerWatchRecord record) {
        if (record.player.isRemoved()) return true;
        return currTime - record.lastWatchTime > UNLOAD_DELAY_TICKS;
    }

    /** Stream of players who have a loaded watch record for the given chunk. */
    public static Stream<ServerPlayer> getPlayersViewingChunk(
            ResourceKey<Level> dim, int x, int z) {
        ArrayList<PlayerWatchRecord> records = getChunkRecordMap(dim).get(ChunkPos.pack(x, z));
        if (records == null) return Stream.empty();
        return records.stream().filter(r -> r.isLoadedToPlayer).map(r -> r.player);
    }

    /** Returns true iff player has a loaded record for the chunk in the given dim. */
    public static boolean isPlayerWatchingChunk(
            ServerPlayer player, ResourceKey<Level> dim, int x, int z) {
        ArrayList<PlayerWatchRecord> records = getChunkRecordMap(dim).get(ChunkPos.pack(x, z));
        if (records == null) return false;
        for (PlayerWatchRecord r : records) {
            if (r.player == player && r.isLoadedToPlayer) return true;
        }
        return false;
    }

    /**
     * Returns true iff a chunk-data packet was sent to {@code player}
     * for {@code (dim, x, z)} via redirection within the suppression
     * window {@link #REDIRECT_SUPPRESS_WINDOW_TICKS} ending at
     * {@code currentTick}.
     */
    public static boolean wasChunkSentViaRedirect(
            ServerPlayer player, ResourceKey<Level> dim, int x, int z, long currentTick) {
        ArrayList<PlayerWatchRecord> records = getChunkRecordMap(dim).get(ChunkPos.pack(x, z));
        if (records == null) return false;
        for (PlayerWatchRecord r : records) {
            if (r.player != player) continue;
            if (r.redirectSentAtTick == 0L) return false;
            return (currentTick - r.redirectSentAtTick) < REDIRECT_SUPPRESS_WINDOW_TICKS;
        }
        return false;
    }

    /**
     * Mark the record for {@code (player, dim, chunkPos)} with the
     * tick at which it was just sent via redirection. Resets the
     * suppression window. Returns true if a record was found and
     * updated.
     */
    public static boolean markChunkRedirectSent(
            ServerPlayer player, ResourceKey<Level> dim, long packedChunkPos, long currentTick) {
        ArrayList<PlayerWatchRecord> records = getChunkRecordMap(dim).get(packedChunkPos);
        if (records == null) return false;
        for (PlayerWatchRecord r : records) {
            if (r.player == player) {
                r.redirectSentAtTick = currentTick;
                return true;
            }
        }
        return false;
    }

    /**
     * Force-remove all records for a player. Called when a player
     * disconnects so we don't leak records for a dead reference.
     * Mirrors IP 1.19's {@code forceRemovePlayer}.
     */
    public static void forceRemovePlayer(ServerPlayer player) {
        data.forEach((dim, map) -> {
            map.long2ObjectEntrySet().removeIf(entry -> {
                ArrayList<PlayerWatchRecord> records = entry.getValue();
                records.removeIf(r -> {
                    if (r.player == player) {
                        r.isValid = false;
                        return true;
                    }
                    return false;
                });
                return records.isEmpty();
            });
        });
        playerInfoMap.remove(player);
    }

    /**
     * Public accessor for the set of dimensions the player has at
     * least one watched chunk in. Used by
     * {@link SeamlessWorldInfoSender} to decide which dimensions
     * need weather sync.
     */
    public static Set<ResourceKey<Level>> getVisibleDimensions(ServerPlayer player) {
        return getPlayerInfo(player).visibleDimensions;
    }

    /**
     * Re-add direct-loading tickets for a player's nearby own-dim
     * chunks. Called immediately after a teleport so chunks the player
     * is now standing in don't have a 13-tick gap before
     * {@link #updateForPlayer} re-records them. Mirrors IP 1.19's
     * {@code addAdditionalDirectLoadingTickets}.
     *
     * <p>For our system this is mostly a no-op (vanilla owns own-dim
     * tickets), but keep the hook so future cross-dim ticket pinning
     * can plug in.
     */
    public static void addAdditionalDirectLoadingTickets(ServerPlayer player) {
        // Intentionally minimal — vanilla owns own-dim tickets.
    }

    public static void cleanup() {
        data.clear();
        playerInfoMap.clear();
        beginWatchChunkSignal.clear();
        endWatchChunkSignal.clear();
        additionalChunkLoaders.clear();
    }

    private static <T> int indexOf(List<T> list, Predicate<T> predicate) {
        for (int i = 0; i < list.size(); i++) {
            if (predicate.test(list.get(i))) return i;
        }
        return -1;
    }
}
