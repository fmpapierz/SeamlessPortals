package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks per-link chunk-load readiness. Mirrors the IP 1.19
 * {@code NetherPortalGeneration.startGeneratingPortal} task pattern,
 * trimmed to what we need: poll each link's destination + source
 * chunks, mark ready when all are FULL, broadcast to clients.
 *
 * <p><b>Why per-link readiness gates teleport.</b> Even with our
 * portal-create pre-load + pre-teleport sync force-load, fresh
 * chunks take time to gen. If the user crosses the portal before
 * those chunks reach FULL status, vanilla {@code teleportTo}'s
 * machinery has to wait — manifesting as a 4-6 second freeze on
 * the server thread (the teleport handler can't return until vanilla
 * has finished its work).
 *
 * <p>The IP indicator-entity pattern is essentially this: don't
 * allow the player to cross until chunks are confirmed loaded. We
 * implement it as a flag on {@link PortalLink#isLinkReady} that:
 * <ul>
 *   <li>Server-side: gates {@code SeamlessServerTeleport
 *       .handleClientInitiatedCrossing}.</li>
 *   <li>Client-side: gates {@code SeamlessClientTeleport.tick}
 *       client-first cross detection.</li>
 * </ul>
 *
 * <p>Once ready, both sides release the gate. Subsequent crosses
 * are gen-free because the chunks are kept loaded by our
 * {@link SeamlessLoadingTicket}.
 */
public final class SeamlessLinkReadinessTracker {

    private SeamlessLinkReadinessTracker() {}

    /**
     * Per-tick polling: for every PortalLink that's not yet ready,
     * check if all pre-load chunks (radius {@link #READINESS_RADIUS}
     * around BOTH endpoints) are at FULL status. If so, flip the
     * link's flag and broadcast.
     *
     * <p>Cheap when there are no pending links — early exit.
     */
    public static void tick(MinecraftServer server) {
        if (server == null) return;
        java.util.Collection<PortalLink> allLinks =
            PortalManager.getServerInstance().getAllLinksSnapshot();
        for (PortalLink link : allLinks) {
            if (link.isLinkReady()) continue;
            if (areAllChunksReady(server, link)) {
                link.setLinkReady(true);
                broadcastReadiness(server, link);
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS LINK-READY] Link {} <-> {} chunks fully loaded; teleport unblocked",
                    link.getSource().getOrigin(),
                    link.getDestination().getOrigin());
            }
        }
    }

    /**
     * Half-side of the chunk grid we require at FULL status before
     * marking a link ready. Matches the post-teleport area where
     * vanilla's player-ticket and chunk-tracking-view will demand
     * loaded chunks.
     *
     * <p>Larger radius = more chunks need gen before link is usable
     * = longer wait after portal creation. 4 = 81 chunks, ~3-5
     * seconds typical wait on SP integrated server for fresh dim.
     */
    private static final int READINESS_RADIUS = 4;

    private static boolean areAllChunksReady(MinecraftServer server, PortalLink link) {
        return endChunksReady(server, link.getSource())
            && endChunksReady(server, link.getDestination());
    }

    private static boolean endChunksReady(MinecraftServer server, PortalInfo end) {
        ServerLevel level = server.getLevel(end.getDimension());
        if (level == null) return false;
        BlockPos origin = end.getOrigin();
        ChunkPos center = ChunkPos.containing(origin);
        for (int dx = -READINESS_RADIUS; dx <= READINESS_RADIUS; dx++) {
            for (int dz = -READINESS_RADIUS; dz <= READINESS_RADIUS; dz++) {
                int cx = center.x() + dx;
                int cz = center.z() + dz;
                // getChunkNow returns null if not at FULL status on
                // the main thread — exactly what we want to detect.
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void broadcastReadiness(MinecraftServer server, PortalLink link) {
        ResourceKey<Level> srcDim = link.getSource().getDimension();
        BlockPos srcOrigin = link.getSource().getOrigin();
        ModPayloads.LinkReadinessPayload payload =
            new ModPayloads.LinkReadinessPayload(
                srcDim.identifier().toString(),
                srcOrigin,
                true);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                PlatformHelper.getInstance().sendToClient(player, payload);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.debug(
                    "[SEAMLESS LINK-READY] broadcast to {} failed: {}",
                    player.getName().getString(), t.toString());
            }
        }
    }
}
