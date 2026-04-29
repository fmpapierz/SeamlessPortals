package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.stream.Stream;

/**
 * E3 — Compute the set of {@link SeamlessChunkLoader}s for a player.
 *
 * <p>Direct port of IP 1.19's {@code ChunkVisibility.getBaseChunkLoaders},
 * trimmed to what our MVP needs:
 * <ul>
 *   <li><b>Player direct loader</b> — square around player's
 *       {@code chunkPosition} in their current dim, radius = server
 *       view distance. {@code isDirectLoader = true}: graph records
 *       these for bookkeeping but our chunk-data sync skips packet
 *       send (vanilla's {@link net.minecraft.server.network.PlayerChunkSender}
 *       owns own-dim delivery — we don't double-send).</li>
 *   <li><b>Portal-derived indirect loaders</b> — for each
 *       {@link PortalLink} within {@link #PORTAL_DETECT_RANGE_CHUNKS}
 *       chunks of the player whose destination is a different
 *       dimension: a square in the destination dim around
 *       {@code link.transformPosition(playerPos)} with radius
 *       {@link #INDIRECT_LOADER_RADIUS_CHUNKS}. {@code isDirectLoader = false}:
 *       these records DO get chunk-data packets sent via redirection.</li>
 * </ul>
 *
 * <p><b>Differences from IP 1.19:</b>
 * <ul>
 *   <li>No perf-level scaling. IP's {@code PerformanceLevel} reduces
 *       radii on slow clients; we hardcode the "good" preset.</li>
 *   <li>No recursive portal walking (no chains-through-chains).
 *       IP walks one extra portal level for "portal visible through
 *       portal" via {@code getGeneralPortalIndirectLoader}; we skip
 *       it for MVP — adds complexity for an uncommon case.</li>
 *   <li>No {@code portal.broadcastToPlayer} per-player filtering.
 *       Our {@code PortalLink} is server-authoritative; every player
 *       sees every link by default.</li>
 *   <li>No global portals (we don't have IP's global-portal system).</li>
 *   <li>No scaling-portal radius bump. {@code getCoordinateScale()} is
 *       1.0 for our linked portals; our {@code transformPosition}
 *       handles cross-scale natively.</li>
 * </ul>
 */
public final class SeamlessChunkVisibility {

    private SeamlessChunkVisibility() {}

    /**
     * Block-distance around player to consider a portal "tracked".
     * Trimmed from 16 → 8 because each tracked portal forces +9 chunks
     * of work per update; on the integrated server every chunk gen
     * counts. Players need to be within ~128 blocks of a portal for
     * the cross-dim pre-load to be useful anyway.
     */
    public static final int PORTAL_DETECT_RANGE_CHUNKS = 8;

    /**
     * Hard upper-bound on the cross-dim radius regardless of
     * performance level. The ACTUAL radius used per-portal is
     * {@code Math.min(serverRD, perfLevel.maxIndirectRadius(), this)}
     * — see {@link #computeAdaptiveRadius}.
     *
     * <p>Now that {@link SeamlessServerPerformanceMonitor} runs the
     * feedback loop, this hard cap is redundant — the perf-level
     * scaling auto-drops to {@code medium}'s 8 chunks at >40 ms
     * tick time and {@code bad}'s 4 chunks at >80 ms. We leave it
     * high (12) so the system can scale up when the server is
     * idle.
     *
     * <p>Plus the per-tick ticket-add throttle in
     * {@link SeamlessChunkTrackingGraph#flushPendingLoading} (cap
     * 4 ticket-adds per tick across all players) prevents any one
     * adaptive-radius scale-up from saturating the server thread,
     * regardless of how many records the loader iteration produces.
     * Records that can't be ticketed this tick stay queued for the
     * next tick.
     */
    /**
     * <b>SP integrated server's hard ceiling.</b> Empirically validated:
     *
     * <ul>
     *   <li>12 → 27-second freezes from accumulated deferred-delivery
     *       backlog.</li>
     *   <li>8 → 17-second freezes during heavy teleport play.</li>
     *   <li>6 → 4-6 second freezes on fresh-portal first visits.</li>
     *   <li>4 → ≤3 second on first visit; gen-free for repeat
     *       teleports within 60-second unload-delay window.</li>
     * </ul>
     *
     * <p>SP integrated server can promote ~10-15 chunks/sec through
     * to FULL status. With radius 4 = 81 chunks per portal × 2 dim
     * directions = 162 chunks max. Worst case ≈ 12-second total
     * gen burden, distributed via {@link
     * SeamlessChunkTrackingGraph#TICKET_ADD_BUDGET_PER_TICK} into
     * 4 ticket-adds per tick = 40 ticks (2 s) of pure ticket work,
     * with chunk gen happening async on workers behind that.
     *
     * <p>The {@link SeamlessServerPerformanceMonitor} provides
     * additional safety — when tick time exceeds 40ms, we drop to
     * {@code medium} (radius 8 from PerformanceLevel — but capped
     * at 4 here) → 3 → 2.
     *
     * <p>Higher radius is achievable on MP / dedicated server where
     * the server thread doesn't share CPU with the client.
     */
    public static final int MAX_INDIRECT_LOADER_RADIUS_CHUNKS = 2;

    public static final int NEAR_PORTAL_RANGE_BLOCKS_SQ = 5 * 5;
    public static final int APPROACHING_PORTAL_RANGE_BLOCKS_SQ = 16 * 16;

    /**
     * Build the stream of loaders for a player. Result is
     * {@code .distinct()}-filtered so two portals targeting the same
     * destination chunk grid don't double-add records to the graph.
     */
    public static Stream<SeamlessChunkLoader> getBaseChunkLoaders(ServerPlayer player) {
        Vec3 playerPos = player.position();
        ResourceKey<Level> playerDim = player.level().dimension();

        SeamlessChunkLoader playerDirect = playerDirectLoader(player);

        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return Stream.of(playerDirect);
        }

        List<PortalLink> nearbyLinks = PortalManager.getServerInstance()
            .getLinksInRange(
                playerDim,
                BlockPos.containing(playerPos.x, playerPos.y, playerPos.z),
                PORTAL_DETECT_RANGE_CHUNKS * 16);

        Stream<SeamlessChunkLoader> portalLoaders = nearbyLinks.stream()
            .map(link -> portalIndirectLoader(player, link, server))
            .filter(loader -> loader != null);

        return Stream.concat(Stream.of(playerDirect), portalLoaders).distinct();
    }

    /**
     * Player's own-dim direct loader. {@code isDirectLoader = true} so
     * the chunk-data sync skips packet send and the loading-ticket pass
     * skips ticket add (vanilla owns both). Used by the graph to track
     * "what the player is watching" for entity-tracker integration and
     * for {@code addAdditionalDirectLoadingTickets} after teleport.
     */
    public static SeamlessChunkLoader playerDirectLoader(ServerPlayer player) {
        ChunkPos pp = player.chunkPosition();
        return new SeamlessChunkLoader(
            new DimChunkPos(player.level().dimension(), pp),
            getRenderDistanceOnServer(player.level().getServer()),
            true);
    }

    private static SeamlessChunkLoader portalIndirectLoader(
            ServerPlayer player, PortalLink link, MinecraftServer server) {
        ResourceKey<Level> destDim = link.getDestination().getDimension();
        // Skip same-dim portals — own-dim is handled by playerDirectLoader.
        if (destDim.equals(player.level().dimension())) {
            return null;
        }
        ServerLevel destLevel = server.getLevel(destDim);
        if (destLevel == null) return null;

        Vec3 playerPos = player.position();
        Vec3 transformed = link.transformPosition(playerPos);
        ChunkPos destChunk = ChunkPos.containing(
            BlockPos.containing(transformed.x, transformed.y, transformed.z));

        int radius = computeAdaptiveRadius(player, playerPos, link, server);
        if (radius <= 0) return null;

        return new SeamlessChunkLoader(
            new DimChunkPos(destDim, destChunk), radius, false);
    }

    /**
     * Adaptive loader radius based on player→portal distance.
     * Mirrors IP 1.19's {@code ChunkVisibility.getDirectLoadingDistance}.
     * Now safe to use full-RD when close because vanilla's parallel
     * chunk-tracking is cancelled by ChunkMapTickMixin.
     *
     * <p>Scaled by the player's {@link PerformanceLevel} —
     * {@link PerformanceLevel#maxIndirectRadius()} caps how far a
     * struggling client gets pre-loaded.
     */
    private static int computeAdaptiveRadius(
            ServerPlayer player, Vec3 playerPos, PortalLink link, MinecraftServer server) {
        int rd = getRenderDistanceOnServer(server);
        PerformanceLevel perfLevel =
            SeamlessChunkTrackingGraph.getPlayerInfo(player).performanceLevel();
        int maxRadius = Math.min(rd, perfLevel.maxIndirectRadius());

        Vec3 portalCenter = link.getSource().getCenter();
        double distSq = playerPos.distanceToSqr(portalCenter);

        if (distSq < NEAR_PORTAL_RANGE_BLOCKS_SQ) return maxRadius;
        if (distSq < APPROACHING_PORTAL_RANGE_BLOCKS_SQ) return Math.max(2, (maxRadius * 2) / 3);
        if (distSq < 32 * 32) return Math.max(2, maxRadius / 3);
        return 1;
    }

    /**
     * Server's view distance — what every player gets for own-dim
     * chunks. Mirrors IP's {@code McHelper.getRenderDistanceOnServer}.
     * Defaults to 10 if the lookup fails (rare; only on early init).
     */
    public static int getRenderDistanceOnServer(MinecraftServer server) {
        if (server == null) return 10;
        try {
            return server.getPlayerList().getViewDistance();
        } catch (Throwable ignored) {
            return 10;
        }
    }
}
