package qouteall.imm_ptl.core.chunk_loading;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongPredicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IEDistanceManager;
import qouteall.imm_ptl.core.ducks.IEServerChunkCache;
import qouteall.imm_ptl.core.ducks.IEWorld;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.RateStat;

import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/**
 * Each {@link ImmPtlChunkTickets} manages ImmPtl chunk ticket for one dimension.
 * <p>
 * It re-implements player chunk loading throttling which is much simpler than vanilla's.
 * In vanilla, each chunk that get loaded by player has a chunk ticket.
 * The chunk tickets are not added immediately, but added by a throttled mechanism.
 * The throttling will reduce the world generation and chunk loading workload when the player moves fast,
 * and prioritize the chunks near player.
 * <p>
 * In vanilla, it uses {@link ChunkTaskPriorityQueue} that has 4 slots of "acquired" chunk positions.
 * If the acquired chunk slots are full, it will stop processing task, until a slot releases.
 * The {@link ChunkTaskPriorityQueueSorter} uses a {@link ProcessorMailbox}
 * (the mailbox is similar to a one-thread thread pool but uses threads from the worker thread pool)
 * to do a lot of message-passing (it enqueues at least 5 messages just to add one ticket).
 * In {@link DistanceManager.PlayerTicketTracker} it sends message for acquiring and releasing.
 * The chunk positions to release are passed into {@link DistanceManager#ticketsToRelease}.
 * A callback for sending message for releasing will be added to these chunk's future.
 */
@SuppressWarnings("JavadocReference")
public class ImmPtlChunkTickets {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /**
     * R10 (api-map chunk-loading #23): 26.2 {@link TicketType} is a registry-registered
     * {@code record TicketType(long timeout, int flags)} - {@code TicketType.create(String, Comparator)}
     * and the generic {@code Ticket<T>} key are GONE. This ticket's shape is
     * {@code FLAG_LOADING | FLAG_SIMULATION} (=6), {@code NO_TIMEOUT}, no {@code FLAG_PERSIST} -
     * exactly vanilla's {@code DRAGON = register("dragon", 0L, 6)}.
     * <p>
     * Registration is now a real registry op that must run at REGISTRY PHASE (not lazy static
     * init). It is performed by mod-owned code (D2 - {@code qouteall.*} holds no loader/mod glue)
     * through the KEEP'd {@code com.warwa.seamlessportals.mixin.TicketTypeInvoker}, unconditional
     * in both flag states (D3 registries-unconditional rule); that seam assigns this field. See
     * {@code migration/fragments/S09-tracking.md} (R10).
     */
    public static TicketType TICKET_TYPE;
    
    // for debugging
    @SuppressWarnings("FieldMayBeFinal")
    private static boolean enableDebugRateStat = false;
    private static final RateStat debugRateStat = new RateStat("imm_ptl_chunk_ticket");
    
    // the fields of ImmPtlChunkTickets should avoid referencing ServerLevel
    public static final WeakHashMap<ServerLevel, ImmPtlChunkTickets> BY_DIMENSION = new WeakHashMap<>();
    
    public static void init() {
        DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT.register(
            ImmPtlChunkTickets::onDimensionRemove
        );
        
        IPGlobal.SERVER_CLEANUP_EVENT.register(ImmPtlChunkTickets::cleanup);
    }
    
    public static class ChunkTicketInfo {
        public int lastUpdateGeneration;
        public int distanceToSource;
        /** §2g (verify-fold FIX-1, gameplay lens): TRUE iff any non-player-direct loader (portal
         *  dest/indirect/additional) marked this chunk this generation. The despawn suppressor
         *  keys on THIS, not raw membership — the player's own view-distance square is ALSO in
         *  this map (foreachBaseChunkLoaders row 1 = playerDirectLoader), and suppressing there
         *  would disable vanilla's >128 monster churn around every player. */
        public boolean portalFed;

        public ChunkTicketInfo(int lastUpdateGeneration, int distanceToSource) {
            this.lastUpdateGeneration = lastUpdateGeneration;
            this.distanceToSource = distanceToSource;
        }
    }
    
    private final Long2ObjectOpenHashMap<ChunkTicketInfo> chunkPosToTicketInfo = new Long2ObjectOpenHashMap<>();
    
    private final ArrayList<LongLinkedOpenHashSet> chunksToAddTicketByDistance = new ArrayList<>();
    
    private final LongOpenHashSet waitingForLoading = new LongOpenHashSet();
    
    private boolean isValid = true;
    
    public final int throttlingLimit = 4;
    
    private ImmPtlChunkTickets() {
    
    }
    
    // it takes in world instead of dimension id, to ensure dimension really exists
    public static ImmPtlChunkTickets get(ServerLevel world) {
        return BY_DIMENSION.computeIfAbsent(world, k -> new ImmPtlChunkTickets());
    }
    
    public void markForLoading(long chunkPos, int distanceToSource, int generation, boolean portalFed) {
        Validate.isTrue(distanceToSource >= 0);

        ChunkTicketInfo info = chunkPosToTicketInfo.get(chunkPos);

        if (info == null) {
            info = new ChunkTicketInfo(generation, distanceToSource);
            info.portalFed = portalFed;
            chunkPosToTicketInfo.put(chunkPos, info);
            getQueueByDistance(distanceToSource).add(chunkPos);
        }
        else {
            if (generation != info.lastUpdateGeneration) {
                info.lastUpdateGeneration = generation;
                // §2g: first touch of a new generation = fresh truth (a portal removed mid-play
                // stops feeding within one generation, bounded by the 13-tick purge cadence).
                info.portalFed = portalFed;
                int oldDistanceToSource = info.distanceToSource;
                info.distanceToSource = distanceToSource;
                if (getQueueByDistance(oldDistanceToSource).remove(chunkPos)) {
                    getQueueByDistance(distanceToSource).add(chunkPos);
                }
            }
            else {
                // §2g: OR-merge within one generation (multi-loader/multi-player overlap — the
                // player-direct square crossing a portal dest square keeps portalFed=true).
                info.portalFed |= portalFed;
                if (distanceToSource < info.distanceToSource) {
                    int oldDistanceToSource = info.distanceToSource;
                    info.distanceToSource = distanceToSource;
                    if (getQueueByDistance(oldDistanceToSource).remove(chunkPos)) {
                        getQueueByDistance(distanceToSource).add(chunkPos);
                    }
                }
            }
        }
    }
    
    private LongLinkedOpenHashSet getQueueByDistance(int distanceToSource) {
        return Helper.arrayListComputeIfAbsent(
            chunksToAddTicketByDistance,
            distanceToSource,
            LongLinkedOpenHashSet::new
        );
    }
    
    public void tick(ServerLevel world) {
        flushThrottling(world);
    }
    
    /**
     * This method is called during ticking and {@link DistanceManager#runAllUpdates(ChunkMap)} .
     * <p>
     * Only calling this method during ticking will make it throttled too slow.
     * <p>
     * This method uses the chunk holder's future, so it should be called after
     * {@link DistanceManager#runAllUpdates(ChunkMap)}
     * (as it calls {@link ChunkHolder#updateFutures(ChunkMap, Executor)}).
     * Before updating the future, the chunk's entity ticking future may be a future that immediately returns {@link ChunkHolder.ChunkLoadingFailure} result.
     * Each task to {@link net.minecraft.server.level.ServerChunkCache.MainThreadExecutor} will trigger
     * {@link DistanceManager#runAllUpdates(ChunkMap)}.
     */
    public void flushThrottling(ServerLevel world) {
        if (Thread.currentThread() != ((IEWorld) world).portal_getThread()) {
            LOGGER.error("Called in a non-server-main (or server-world) thread.", new Throwable());
            return;
        }
        
        if (enableDebugRateStat) {
            debugRateStat.update();
        }
        
        if (!isValid) {
            LOGGER.error("flushing when invalid {}", world);
            return;
        }
        
        if (!world.getServer().isRunning()) {
            // important: don't add chunk ticket when server is saving
            // https://github.com/iPortalTeam/ImmersivePortalsMod/issues/1455
            return;
        }
        
        DistanceManager distanceManager = getDistanceManager(world);
        Executor mainThreadExecutor = ((qouteall.imm_ptl.core.mixin.common.chunk_sync.IEDistanceManager) distanceManager).ip_getMainThreadExecutor();
        
        // clear the already loaded chunks
        waitingForLoading.removeIf((long chunkPos) -> {
            ChunkHolder chunkHolder = getChunkHolder(world, chunkPos);
            if (chunkHolder == null) {
                return true;
            }
            
            ChunkResult<LevelChunk> resultNow = chunkHolder.getEntityTickingChunkFuture()
                .getNow(null);
            
            if (resultNow == null) {
                return false;
            }
            
            if (!resultNow.isSuccess()) {
                LOGGER.error(
                    "Chunk loading failure {} {}",
                    world, ChunkPos.unpack(chunkPos)
                );
            }
            
            return true;
        });
        
        // flush the pending-add-ticket queues
        for (LongLinkedOpenHashSet queue : chunksToAddTicketByDistance) {
            if (queue != null) {
                while (!queue.isEmpty()) {
                    if (waitingForLoading.size() >= throttlingLimit) {
                        return;
                    }
                    
                    long chunkPos = queue.removeFirstLong();
                    if (chunkPosToTicketInfo.containsKey(chunkPos)) {
                        addTicket(distanceManager, chunkPos);
                        
                        waitingForLoading.add(chunkPos);
                    }
                    else {
                        LOGGER.warn("Chunk {} is not in the queue", ChunkPos.unpack(chunkPos));
                    }
                }
            }
        }
    }
    
    private static void addTicket(DistanceManager distanceManager, long chunkPos) {
        if (!IPConfig.getConfig().enableImmPtlChunkLoading) {
            return;
        }
        
        ChunkPos chunkPosObj = ChunkPos.unpack(chunkPos);
        // R10 (api-map chunk-loading #17): DistanceManager.addRegionTicket is GONE;
        // add through TicketStorage. Level arithmetic (33 - radius) is unchanged.
        ((IEDistanceManager) distanceManager).ip_getTicketStorage().addTicketWithRadius(
            TICKET_TYPE, chunkPosObj, getLoadingRadius()
        );
        
        if (enableDebugRateStat) {
            debugRateStat.hit();
        }
    }
    
    public void purge(
        ServerLevel world,
        LongPredicate shouldKeepLoadingFunc
    ) {
        DistanceManager distanceManager = getDistanceManager(world);
        
        chunkPosToTicketInfo.long2ObjectEntrySet().removeIf(e -> {
            long chunkPos = e.getLongKey();
            ChunkTicketInfo ticketInfo = e.getValue();
            
            boolean keepLoading = shouldKeepLoadingFunc.test(chunkPos);
            
            if (!keepLoading) {
                waitingForLoading.remove(chunkPos);
                
                boolean pendingTicketAdding = getQueueByDistance(ticketInfo.distanceToSource)
                    .remove(chunkPos);
                
                if (!pendingTicketAdding) {
                    ChunkPos chunkPosObj = ChunkPos.unpack(chunkPos);
                    // R10 (api-map chunk-loading #17): removeRegionTicket - TicketStorage.removeTicketWithRadius.
                    ((IEDistanceManager) distanceManager).ip_getTicketStorage().removeTicketWithRadius(
                        TICKET_TYPE, chunkPosObj, getLoadingRadius()
                    );
                }
                return true;
            }
            else {
                return false;
            }
        });
    }
    
    public int getLoadedChunkNum() {
        return chunkPosToTicketInfo.size();
    }

    // §2g once-only latch for the invalid-query silent-skip WARN (belt-and-braces:
    // onDimensionRemove removes the BY_DIMENSION entry BEFORE invalidating, so a query
    // should never see isValid=false).
    private static boolean invalidQueryWarned = false;

    /**
     * §2g O(1) PORTAL-FED chunk membership for the despawn suppressor + probe (verify-fold
     * FIX-1: raw map membership would match every player's own view-distance square — the
     * playerDirectLoader also feeds this map — and suppressing there would disable vanilla's
     * >128 monster churn around every player; only portal/indirect/additional-loader-fed
     * chunks qualify). Raw BY_DIMENSION.get — NEVER get(world), which computeIfAbsent-CREATES:
     * the despawn path must not allocate ticket managers. Server-thread only (the same thread
     * as every other user of these structures) — WeakHashMap expunge on get is safe. The map
     * is a slight SUPERSET of actually-ticketed chunks (entries exist from markForLoading
     * before the throttled addTicket, and persist ticketless when
     * enableImmPtlChunkLoading=false — callers must gate on that config); staleness is bounded
     * by one purge generation (13 ticks) and only ever WIDENS suppression.
     */
    public static boolean isChunkPortalFed(ServerLevel world, int cx, int cz) {
        ImmPtlChunkTickets t = manager(world);
        return t != null && isPortalFedIn(t, cx, cz);
    }

    /**
     * §2g 3x3-dilated portal-fed membership. HONEST RATIONALE (verify-fold FIX-2 — the
     * original "the +1 ring hosts entityTickList members" claim was REFUTED: level-32 ring
     * chunks map to Visibility.TRACKED, not TICKING, and never run checkDespawn): the dilation
     * buys (i) one-generation suppression CONTINUITY when a square partially shrinks (an edge
     * chunk's entry purged while a neighbor stays held — the mob's own chunk may briefly leave
     * the map while still entity-ticking from the neighbor's ticket propagation), and (ii) a
     * 1-ring over-suppression when a THIRD-PARTY source holds a ring chunk entity-ticking —
     * a deviation in the SAFE direction (retention, recoverable) that this comment owns.
     * The probe's heldCenter/portalHeldNear pair is the empirical adjudicator. Center probed
     * first (the common hit); the manager lookup is hoisted (verify-fold FIX-3 micro).
     */
    public static boolean isChunkPortalFedNear(ServerLevel world, int cx, int cz) {
        ImmPtlChunkTickets t = manager(world);
        if (t == null) return false;
        if (isPortalFedIn(t, cx, cz)) return true;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx | dz) != 0 && isPortalFedIn(t, cx + dx, cz + dz)) return true;
            }
        }
        return false;
    }

    /** §2g raw (portal-fed-agnostic) membership — probe diagnostics only. */
    public static boolean isChunkHeld(ServerLevel world, int cx, int cz) {
        ImmPtlChunkTickets t = manager(world);
        return t != null && t.chunkPosToTicketInfo.containsKey(ChunkPos.pack(cx, cz));
    }

    private static ImmPtlChunkTickets manager(ServerLevel world) {
        ImmPtlChunkTickets t = BY_DIMENSION.get(world);
        if (t == null) return null;
        if (!t.isValid) {
            if (!invalidQueryWarned) {
                invalidQueryWarned = true;
                LOGGER.warn("[portal-despawn] membership query on invalidated ticket manager {}",
                    world.dimension().identifier());
            }
            return null;
        }
        return t;
    }

    private static boolean isPortalFedIn(ImmPtlChunkTickets t, int cx, int cz) {
        ChunkTicketInfo info = t.chunkPosToTicketInfo.get(ChunkPos.pack(cx, cz));
        return info != null && info.portalFed;
    }
    
    public static void onDimensionRemove(ServerLevel world) {
        ImmPtlChunkTickets dimTicketManager = BY_DIMENSION.remove(world);
        
        if (dimTicketManager == null) {
            return;
        }
        
        removeAllTicketsInWorld(world, dimTicketManager);
    }
    
    private static void removeAllTicketsInWorld(ServerLevel world, ImmPtlChunkTickets dimTicketManager) {
        DistanceManager ticketManager = getDistanceManager(world);
        
        // R10 (api-map chunk-loading #18/#23): the 1.21.3 per-chunk portal_getTicketSet
        // iterate-and-removeRegionTicket loop is GONE (DistanceManager holds no
        // SortedArraySet<Ticket<?>>; getTickets moved to TicketStorage, Ticket is non-generic).
        // Bulk-remove every imm_ptl ticket by TYPE via the vanilla-blessed removeTicketIf - this
        // matches IP's filter (t.getType() == TICKET_TYPE) exactly and is level-agnostic, so it is
        // robust to getLoadingRadius() having changed between add and remove.
        ((IEDistanceManager) ticketManager).ip_getTicketStorage().removeTicketIf(
            (ticket, pos) -> ticket.getType() == TICKET_TYPE, null
        );
        
        dimTicketManager.isValid = false;
    }
    
    public static int getLoadingRadius() {
        if (IPGlobal.activeLoading) {
            return 2;
        }
        else {
            return 1;
        }
    }
    
    public static ChunkHolder getChunkHolder(ServerLevel world, long chunkPos) {
        return ((IEChunkMap) (world.getChunkSource()).chunkMap).ip_getChunkHolder(chunkPos);
    }
    
    public static DistanceManager getDistanceManager(ServerLevel world) {
        return ((IEServerChunkCache) world.getChunkSource()).ip_getDistanceManager();
    }
    
    private static void cleanup(MinecraftServer server) {
        for (ImmPtlChunkTickets immPtlChunkTickets : BY_DIMENSION.values()) {
            immPtlChunkTickets.isValid = false;
        }
        BY_DIMENSION.clear();
    }
}
