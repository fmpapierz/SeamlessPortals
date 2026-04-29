package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.network.SeamlessPacketRedirection;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stage 3 — server-side per-tick cross-dim chunk watch tracker.
 *
 * <p>Mirrors a focused subset of IP's {@code NewChunkTrackingGraph +
 * ChunkDataSyncManager} (1.19) — we do NOT replace vanilla's chunk
 * distribution graph. Vanilla still owns same-dim chunk delivery
 * (player's own dim chunks go via the normal player-chunk-cache path).
 * Our tracker only adds CROSS-DIM chunks: chunks in dim X that a player
 * in dim Y should "see" because there's a portal Y→X within range.
 *
 * <p><b>Per server tick, for each player:</b>
 * <ol>
 *   <li>Find every {@link PortalLink} in the player's CURRENT dim within
 *       {@link #PORTAL_DETECT_RANGE_BLOCKS} blocks of the player.</li>
 *   <li>For each link whose destination is a DIFFERENT dim:
 *     <ul>
 *       <li>Compute the destination chunk-grid center via
 *           {@link PortalLink#transformPosition} on the player's
 *           position.</li>
 *       <li>Add a {@link TicketType#PORTAL} ticket of radius
 *           {@link #DEST_CHUNK_RADIUS} on the destination
 *           {@link ServerLevel} so the dest chunks force-load + stay
 *           loaded for ~15 seconds without ticking entities. (Vanilla
 *           uses the same ticket for ordinary nether-portal entry —
 *           {@code Entity.placePortalTicket}.)</li>
 *       <li>For each chunk in the {@code [-r, r]^2} grid, send a
 *           {@link ClientboundLevelChunkWithLightPacket} via
 *           {@link SeamlessPacketRedirection#sendRedirected} so the
 *           client unwraps it under {@code withSwitchedWorld(cached)}
 *           and stores it in the cached
 *           {@link com.warwa.seamlessportals.chunk.SeamlessClientChunkMap}.
 *           Rate-limited to {@link #CHUNK_PACKETS_PER_TICK_PER_PLAYER}
 *           per tick to avoid bursting the client's network thread.</li>
 *     </ul>
 *   </li>
 *   <li>Refresh the watch timestamp for each (player, dim, chunkPos)
 *       triple covered above.</li>
 * </ol>
 *
 * <p><b>Stale purge.</b> Triples not refreshed for
 * {@link #STALE_TICK_THRESHOLD} ticks → send
 * {@link ClientboundForgetLevelChunkPacket} via redirection so the
 * cached level drops the chunk; remove the watch record.
 *
 * <p><b>What it does and doesn't do compared to IP's
 * {@code NewChunkTrackingGraph}:</b>
 * <ul>
 *   <li>Has only ONE level of recursion (player → portal → dest) — IP
 *       walks portals one more level (dest → inner-portal → dest-of-inner).
 *       Acceptable trade-off: portal-chains-of-portals are rare and
 *       Stage 3 v1 needs to land first.</li>
 *   <li>No perf-level scaling. Fixed radius. IP has
 *       {@code PerformanceLevel.getVisiblePortalRangeChunks} that drops
 *       to 0 on potato hardware; we'd add later.</li>
 *   <li>Does not touch entity-tracking signals. Cross-dim entity
 *       visibility through portals is handled by our existing
 *       {@code PortalEntityTracker}.</li>
 *   <li>Does not maintain a global chunk-loader graph; we tick
 *       per-player and chunk-tickets self-expire.</li>
 * </ul>
 *
 * <p><b>Force-load semantics.</b>
 * {@link TicketType#PORTAL} has a 300-tick lifetime (15 s) and distance
 * 15. Re-adding the ticket each tick keeps it fresh; once a portal
 * leaves range the ticket times out and the chunk unloads naturally.
 * Distance 15 means the chunk is loaded but not entity-ticking — exactly
 * what we want (we just need to read the chunk to send it; we don't
 * need mobs to spawn or block-entities to tick).
 */
public class CrossDimChunkTracker {

    /**
     * Half-side of the destination-chunk grid around each portal's
     * transformed center. {@code 2 → 5×5 = 25 chunks per portal}.
     * Conservative starting value — increases visible cross-dim
     * coverage at the cost of more bandwidth + more chunks force-loaded.
     * (Started at 3 = 49 chunks; the integrated server hit "Can't keep
     * up! Running 5664ms behind" on first join when a fresh nether had
     * to generate 49 chunks. Dropping to 2 halves that gen burden.)
     */
    private static final int DEST_CHUNK_RADIUS = 2;

    /**
     * Max chunk packets sent per player per server tick. Limits initial
     * burst when a player first comes into range of a portal.
     * {@code 4/tick × 20 tps = 80 chunks/s} — RD-2 portal's worth of
     * chunks fully loaded in ~0.6 s.
     */
    private static final int CHUNK_PACKETS_PER_TICK_PER_PLAYER = 4;

    /**
     * Block-distance from the player to consider a portal "tracked".
     * 16 chunks ≈ within typical RD; only portals close enough to be
     * visible to the player matter.
     */
    private static final int PORTAL_DETECT_RANGE_BLOCKS = 16 * 16;

    /**
     * Ticks since last refresh before a watched (player, dim, chunkPos)
     * triple is purged + a forget-chunk packet is sent. 60 ticks = 3 s.
     * Long enough to span brief player-out-of-portal-range moments
     * (looking away then back); short enough to release client memory
     * promptly when a portal's no longer relevant.
     *
     * <p>Must be greater than {@link #UPDATE_INTERVAL} so a single
     * skipped player-update tick doesn't immediately purge.
     */
    private static final int STALE_TICK_THRESHOLD = 60;

    /**
     * Player-update throttle. Each tick we only run
     * {@link #updateForPlayer} for players whose
     * {@code id % UPDATE_INTERVAL == gameTime % UPDATE_INTERVAL}.
     * Spreads the chunk-gen / chunk-send work across ticks instead of
     * doing it every tick for every player.
     *
     * <p>IP uses {@code updateInterval=13} but has proper streaming
     * infrastructure. We use 20 (1 Hz per player) for more headroom on
     * the integrated server. {@link TicketType#PORTAL} has a 300-tick
     * lifetime so tickets stay fresh with 280 ticks of headroom.
     * Stale-purge still runs every tick (cheap).
     */
    private static final int UPDATE_INTERVAL = 20;

    /**
     * Ticks of silence after a player's dim changes during which
     * {@link #updateForPlayer} is a no-op for that player. Prevents the
     * tracker from competing with the seamless teleport flow + the
     * vanilla post-teleport chunk burst for server-thread time. Two
     * seconds is enough for the teleport burst to drain on most
     * configurations; tracker resumes after.
     */
    private static final int TELEPORT_SILENCE_TICKS = 40;

    /**
     * Per-player nested watch map:
     * {@code UUID → (destDim → (chunkPosLong → lastWatchTimeTick))}.
     * Single source of truth for what cross-dim chunks each player has
     * been told about.
     */
    private final Map<UUID, Map<ResourceKey<Level>, Map<Long, Long>>> watch = new HashMap<>();

    /**
     * Per-player {@code (lastSeenDim, lastDimChangeTick)}. Used to
     * silence {@link #updateForPlayer} for {@link #TELEPORT_SILENCE_TICKS}
     * ticks after the player's dim changes — the tracker stays out of
     * the way of the post-teleport chunk burst.
     */
    private final Map<UUID, DimChangeRecord> playerDimState = new HashMap<>();

    private record DimChangeRecord(ResourceKey<Level> dim, long lastChangeTick) {}

    public void tick(MinecraftServer server) {
        long gameTime = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ResourceKey<Level> currentDim = player.level().dimension();
            DimChangeRecord prev = playerDimState.get(player.getUUID());
            if (prev == null || !prev.dim().equals(currentDim)) {
                // First seen, or dim changed — start the silence window.
                playerDimState.put(player.getUUID(),
                    new DimChangeRecord(currentDim, gameTime));
                continue;
            }
            // Within post-teleport silence window — skip heavy work.
            if (gameTime - prev.lastChangeTick() < TELEPORT_SILENCE_TICKS) {
                continue;
            }
            // IP-style throttle: spread per-player heavy work across
            // UPDATE_INTERVAL ticks so the integrated server doesn't
            // get hammered with N-players × M-portals worth of
            // ticket adds + chunk fetches every tick.
            if (player.getId() % UPDATE_INTERVAL != gameTime % UPDATE_INTERVAL) {
                continue;
            }
            try {
                updateForPlayer(server, player, gameTime);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS STAGE3] updateForPlayer threw for {}: {}",
                    player.getName().getString(), t.toString());
            }
        }
        purgeStale(server, gameTime);
    }

    private void updateForPlayer(MinecraftServer server, ServerPlayer player, long gameTime) {
        ResourceKey<Level> playerDim = player.level().dimension();
        Vec3 playerPos = player.position();
        BlockPos playerBlock = BlockPos.containing(playerPos);

        Map<ResourceKey<Level>, Map<Long, Long>> playerWatch =
            watch.computeIfAbsent(player.getUUID(), u -> new HashMap<>());

        List<PortalLink> nearbyLinks = PortalManager.getServerInstance()
            .getLinksInRange(playerDim, playerBlock, PORTAL_DETECT_RANGE_BLOCKS);

        int sentThisTick = 0;
        for (PortalLink link : nearbyLinks) {
            ResourceKey<Level> destDim = link.getDestination().getDimension();
            // Never redirect own-dim chunks: those are handled by vanilla's
            // player-chunk-cache + go to mc.level (active), not the cache.
            if (destDim == playerDim) continue;

            ServerLevel destLevel = server.getLevel(destDim);
            if (destLevel == null) continue;

            Vec3 destCenter = link.transformPosition(playerPos);
            int destCx = (int) Math.floor(destCenter.x / 16.0);
            int destCz = (int) Math.floor(destCenter.z / 16.0);

            Map<Long, Long> dimWatch = playerWatch.computeIfAbsent(
                destDim, d -> new HashMap<>());

            // Force-load destination region with a PORTAL ticket so the
            // chunks come into server memory (or stay loaded). Vanilla's
            // own portal entry uses the same ticket type with radius 3
            // (Entity.placePortalTicket).
            destLevel.getChunkSource().addTicketWithRadius(
                TicketType.PORTAL,
                new ChunkPos(destCx, destCz),
                DEST_CHUNK_RADIUS);

            for (int dx = -DEST_CHUNK_RADIUS; dx <= DEST_CHUNK_RADIUS; dx++) {
                for (int dz = -DEST_CHUNK_RADIUS; dz <= DEST_CHUNK_RADIUS; dz++) {
                    int cx = destCx + dx;
                    int cz = destCz + dz;
                    long chunkLong = ChunkPos.pack(cx, cz);
                    Long lastTime = dimWatch.get(chunkLong);

                    if (lastTime == null) {
                        // Never sent. Try to send if rate budget remains.
                        if (sentThisTick >= CHUNK_PACKETS_PER_TICK_PER_PLAYER) continue;
                        if (sendChunkRedirected(player, destLevel, destDim, cx, cz)) {
                            dimWatch.put(chunkLong, gameTime);
                            sentThisTick++;
                        }
                        // If chunk not loaded server-side yet (getChunkNow
                        // returned null), leave the entry absent so we
                        // retry next tick once the ticket has loaded it.
                    } else {
                        // Already sent — refresh timestamp so purge skips it.
                        dimWatch.put(chunkLong, gameTime);
                    }
                }
            }
        }
    }

    private boolean sendChunkRedirected(
            ServerPlayer player, ServerLevel destLevel,
            ResourceKey<Level> destDim, int cx, int cz) {
        LevelChunk chunk = destLevel.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return false;

        ClientboundLevelChunkWithLightPacket packet =
            new ClientboundLevelChunkWithLightPacket(
                chunk, destLevel.getLightEngine(), null, null);
        SeamlessPacketRedirection.sendRedirected(player, destDim, packet);
        return true;
    }

    private void purgeStale(MinecraftServer server, long gameTime) {
        Iterator<Map.Entry<UUID, Map<ResourceKey<Level>, Map<Long, Long>>>> playerIt =
            watch.entrySet().iterator();
        while (playerIt.hasNext()) {
            Map.Entry<UUID, Map<ResourceKey<Level>, Map<Long, Long>>> playerEntry = playerIt.next();
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
            if (player == null) {
                // Player offline — drop all their records. Their cached
                // levels die with the connection on the client side.
                playerIt.remove();
                continue;
            }
            ResourceKey<Level> playerActiveDim = player.level().dimension();
            for (Map.Entry<ResourceKey<Level>, Map<Long, Long>> dimEntry
                    : playerEntry.getValue().entrySet()) {
                ResourceKey<Level> dim = dimEntry.getKey();
                Map<Long, Long> dimWatch = dimEntry.getValue();

                // Critical: if the player just teleported INTO the dim
                // we were caching for them, the cached level was
                // promoted to mc.level by the seamless teleport. The
                // chunks in our watch records now refer to ACTIVE-dim
                // chunks, owned by vanilla's send path. Sending forget
                // packets for them would unload chunks the player
                // needs and cause collision desync. Drop the records
                // silently — the (now active) dim handles its own
                // chunk lifecycle.
                if (dim.equals(playerActiveDim)) {
                    dimWatch.clear();
                    continue;
                }

                Iterator<Map.Entry<Long, Long>> chunkIt = dimWatch.entrySet().iterator();
                while (chunkIt.hasNext()) {
                    Map.Entry<Long, Long> ce = chunkIt.next();
                    if (gameTime - ce.getValue() > STALE_TICK_THRESHOLD) {
                        long chunkLong = ce.getKey();
                        ChunkPos cp = ChunkPos.unpack(chunkLong);
                        try {
                            SeamlessPacketRedirection.sendRedirected(
                                player, dim,
                                new ClientboundForgetLevelChunkPacket(cp));
                        } catch (Throwable t) {
                            SeamlessPortalsConstants.LOGGER.debug(
                                "[SEAMLESS STAGE3] forget send failed {} {}: {}",
                                dim.identifier(), cp, t.toString());
                        }
                        chunkIt.remove();
                    }
                }
            }
        }
    }

    public void clear() {
        watch.clear();
        playerDimState.clear();
    }
}
