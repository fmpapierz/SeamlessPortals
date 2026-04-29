package com.warwa.seamlessportals.entity;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side half of the IP-style client-initiated seamless teleport.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #performCrossing(ServerPlayer, PortalLink)} — called either from
 *       the new client-initiated packet handler or from the existing
 *       {@code EntityMixin.tick} server-side fallback detection (via
 *       {@link PortalTeleporter}). Does the cross-dim move AND sends
 *       {@link ModPayloads.ClientboundSeamlessMovePayload} back.</li>
 *   <li>{@link #handleClientInitiatedCrossing(ServerPlayer, String)} — packet
 *       entry point. Validates proximity + link, then calls
 *       {@link #performCrossing}.</li>
 * </ul>
 *
 * <p><b>Why we do NOT suppress {@code ClientboundRespawnPacket}.</b> An earlier
 * iteration had a mixin that dropped the respawn packet when a thread-local
 * flag was set, on the theory that the client had already swapped and didn't
 * need the packet. That broke the client's per-dim protocol state and caused
 * {@code ClientboundLevelChunkWithLightPacket} to fail to decode with
 * {@code IndexOutOfBoundsException}. The respawn packet is the vanilla
 * "reset per-dim protocol state" signal; dropping it leaves the reader stuck
 * on the old dim's framing assumptions.
 *
 * <p>Current approach: let vanilla send the respawn packet normally. The
 * client-side {@link com.warwa.seamlessportals.mixin.client.HandleRespawnMixin}
 * detects that {@link com.warwa.seamlessportals.client.SeamlessClientTeleport}
 * already performed the visual swap and short-circuits {@code handleRespawn}
 * as a no-op. Protocol state still advances; the visible-swap logic is elided.
 */
public final class SeamlessServerTeleport {

    private SeamlessServerTeleport() {}

    /**
     * Entry from a client-initiated {@link ModPayloads.ClientPortalCrossingPayload}.
     * Validates that the player is actually near the source portal and that
     * the portal link exists, then performs the server-side teleport.
     */
    public static void handleClientInitiatedCrossing(ServerPlayer player, String portalIdString) {
        if (player.isRemoved()) return;

        UUID portalId;
        try {
            portalId = UUID.fromString(portalIdString);
        } catch (IllegalArgumentException e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS SERVER-CROSSING] Invalid portal id: {}", portalIdString);
            return;
        }

        PortalManager mgr = PortalManager.getServerInstance();
        Optional<PortalLink> linkOpt = mgr.getLinkForPortal(portalId);
        if (linkOpt.isEmpty()) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS SERVER-CROSSING] No link for portal {} (requested by {})",
                portalId, player.getName().getString());
            return;
        }
        PortalLink link = linkOpt.get();

        // Validate: player's current server-side dimension matches the link's
        // source dimension. Reject cross-dim spoof attempts (client claiming to
        // cross a portal in a dim they're not in).
        if (!player.level().dimension().equals(link.getSource().getDimension())) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS SERVER-CROSSING] Rejected: player {} is in {}, link source is {}",
                player.getName().getString(),
                player.level().dimension().identifier(),
                link.getSource().getDimension().identifier());
            return;
        }

        // Validate: player is reasonably near the source portal. Generous
        // radius — client-server position skew can be several blocks during
        // the tick-before-crossing moment.
        PortalInfo srcPortal = link.getSource();
        Vec3 portalCenter = srcPortal.getCenter();
        double d2 = player.position().distanceToSqr(portalCenter);
        double maxD2 = 64.0; // 8-block radius
        if (d2 > maxD2) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS SERVER-CROSSING] Rejected: player {} is {} blocks from portal center (max {})",
                player.getName().getString(), Math.sqrt(d2), Math.sqrt(maxD2));
            return;
        }

        // IP loading-indicator parity: block teleport while the link's
        // dest chunks are still loading server-side. Without this gate
        // vanilla {@code teleportTo} triggers on-demand chunk-gen
        // during the teleport flow → 4-6 second server thread freeze
        // → user gets "stuck" mid-portal.
        if (!link.isLinkReady()) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS SERVER-CROSSING] Link not ready (chunks still loading); rejecting cross from {}",
                player.getName().getString());
            return;
        }

        performCrossing(player, link);
    }

    /**
     * Do the authoritative cross-dim move + notify the client.
     *
     * <p><b>Uses vanilla {@code ServerPlayer.teleportTo}</b>. Note that
     * MC 26.1.2's {@code teleport(TeleportTransition)} for cross-dim
     * does:
     * <ol>
     *   <li>Send {@code ClientboundRespawnPacket}.</li>
     *   <li>Send {@code ClientboundChangeDifficultyPacket}.</li>
     *   <li>Send {@code sendPlayerPermissionLevel}.</li>
     *   <li>{@code oldLevel.removePlayerImmediately} → {@code revive}.</li>
     *   <li>{@code setServerLevel(newLevel)}.</li>
     *   <li>{@code connection.teleport()} (position update).</li>
     *   <li>{@code newLevel.addDuringTeleport} → {@code addPlayer} →
     *       {@code updatePlayerStatus(player, true)} → which calls
     *       {@code updateChunkTracking} (NOT the per-tick one we
     *       cancel via ChunkMapTickMixin's surgical @Redirect — this
     *       is the JOIN-path call which we DON'T cancel) → queues
     *       ALL RD chunks to PlayerChunkSender + sends
     *       SetChunkCacheCenterPacket. The CHUNK QUEUEING here is
     *       fast (LongSet adds); the EXPENSIVE part is the
     *       distanceManager.addPlayer ticket which schedules chunk
     *       gen.</li>
     *   <li>Triggers advancements, sends additional level info packets.</li>
     * </ol>
     *
     * <p>The user-visible freeze comes from step 7's chunk-gen.
     * Mitigated by our portal-create pre-load
     * ({@link com.warwa.seamlessportals.portal.PortalManager#registerLinkChunkLoadersIfNotPresent})
     * which keeps dest chunks at FULL status before the teleport.
     * If pre-load completed: chunks are already gen'd → addDuringTeleport's
     * ticket-add finds them ready instantly → no gen burden.
     */
    public static void performCrossing(ServerPlayer player, PortalLink link) {
        ResourceKey<Level> destDim = link.getDestination().getDimension();
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        ServerLevel destLevel = server.getLevel(destDim);
        if (destLevel == null) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS SERVER-CROSSING] Destination level {} not found",
                destDim.identifier());
            return;
        }

        Vec3 srcPos = player.position();
        Vec3 destPos = link.transformTeleportPosition(srcPos);
        Vec3 destVel = link.transformVelocity(player.getDeltaMovement());
        float destYaw = link.transformYaw(player.getYRot());
        float destPitch = player.getXRot();

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS SERVER-CROSSING] {} {} -> {} in {} (portal {})",
            player.getName().getString(),
            String.format("(%.1f,%.1f,%.1f)", srcPos.x, srcPos.y, srcPos.z),
            String.format("(%.1f,%.1f,%.1f)", destPos.x, destPos.y, destPos.z),
            destDim.identifier(),
            link.getSource().getPortalId().toString());

        // Timing diagnostics: phase-by-phase elapsed in millis.
        // If link.linkReady is true, chunks should already be FULL
        // server-side and every phase below should be <5 ms. Any
        // phase over 50 ms = lag bug to investigate.
        long tStart = System.nanoTime();
        long tPreloadStart = tStart;

        // SYNCHRONOUSLY force-load dest chunks BEFORE the teleport.
        // Use {@code getChunk(x, z, ChunkStatus.FULL, true)} which
        // BLOCKS the server thread until the chunk reaches FULL
        // status. This shifts the chunk-gen cost from "during the
        // teleport" (where it freezes the player mid-portal) to
        // "right at portal cross" (where the user expects a brief
        // pause and the player isn't visually stuck).
        //
        // Radius 6 (13×13 = 169 chunks) covers most of the player's
        // typical view distance after teleport, so vanilla's
        // {@code addDuringTeleport → updatePlayerStatus →
        // distanceManager.addPlayer} doesn't trigger any further
        // chunk-gen — all chunks within ~6 chunks of teleport target
        // are already FULL.
        //
        // Plus add SeamlessLoadingTicket to keep them loaded across
        // the teleport (vanilla's player ticket alone wouldn't keep
        // them at FULL status; our ticket guarantees they stay
        // loaded for the unload-delay window).
        try {
            net.minecraft.world.level.ChunkPos destChunk =
                net.minecraft.world.level.ChunkPos.containing(
                    net.minecraft.core.BlockPos.containing(destPos.x, destPos.y, destPos.z));
            int preTeleportRadius = 6;
            net.minecraft.server.level.ServerChunkCache cache = destLevel.getChunkSource();
            for (int dx = -preTeleportRadius; dx <= preTeleportRadius; dx++) {
                for (int dz = -preTeleportRadius; dz <= preTeleportRadius; dz++) {
                    int cx = destChunk.x() + dx;
                    int cz = destChunk.z() + dz;
                    com.warwa.seamlessportals.chunk.SeamlessLoadingTicket
                        .addTicketIfNotLoaded(destLevel,
                            new net.minecraft.world.level.ChunkPos(cx, cz));
                    // SYNCHRONOUSLY ensure chunk is at FULL status.
                    // The {@code true} flag (loadOrGenerate) blocks
                    // until the chunk is fully generated. This is
                    // the heavy work; we pay it HERE so that vanilla
                    // teleportTo's machinery (addDuringTeleport →
                    // chunk-tracking-view → markChunkPendingToSend)
                    // sees pre-loaded chunks and doesn't pause.
                    cache.getChunk(cx, cz,
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
                        true);
                }
            }
        } catch (Throwable t) {
            // Pre-load failure shouldn't block teleport — vanilla's
            // teleportTo will gen any missing chunks itself.
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS SERVER-CROSSING] Pre-teleport sync force-load failed: {}",
                t.toString());
        }

        long tPreloadEnd = System.nanoTime();
        long tTeleportStart = tPreloadEnd;

        player.teleportTo(destLevel, destPos.x, destPos.y, destPos.z,
            Set.<Relative>of(), destYaw, destPitch, false);

        player.setDeltaMovement(destVel);

        long tTeleportEnd = System.nanoTime();

        // Block EntityMixin.tick fallback from re-detecting this crossing
        // on the next server tick while the player is still inside the dest
        // portal's bounding box.
        com.warwa.seamlessportals.entity.SeamlessTeleportState state =
            (com.warwa.seamlessportals.entity.SeamlessTeleportState) (Object) player;
        state.seamlessportals$setJustTeleported(true);

        // Reconciliation / fallback-swap packet. Hot path: client already did
        // the visual swap → this is just a position confirmation. Fallback:
        // client never detected the crossing → this triggers the deferred
        // visual swap.
        ModPayloads.ClientboundSeamlessMovePayload reconcile =
            new ModPayloads.ClientboundSeamlessMovePayload(
                link.getSource().getPortalId().toString(),
                destDim.identifier().toString(),
                destPos.x, destPos.y, destPos.z,
                destYaw, destPitch,
                destVel.x, destVel.y, destVel.z);
        PlatformHelper.getInstance().sendToClient(player, reconcile);

        long tReconcileEnd = System.nanoTime();

        // Re-send portal data for the new dimension (mirrors legacy flow).
        PortalManager.getServerInstance().sendDimensionLinksToPlayer(destDim, player);

        long tEnd = System.nanoTime();
        double preloadMs = (tPreloadEnd - tPreloadStart) / 1_000_000.0;
        double teleportMs = (tTeleportEnd - tTeleportStart) / 1_000_000.0;
        double reconcileMs = (tReconcileEnd - tTeleportEnd) / 1_000_000.0;
        double dimLinksMs = (tEnd - tReconcileEnd) / 1_000_000.0;
        double totalMs = (tEnd - tStart) / 1_000_000.0;
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS SERVER-CROSSING TIMING] preload={}ms teleportTo={}ms reconcile={}ms dimLinks={}ms total={}ms",
            String.format("%.2f", preloadMs),
            String.format("%.2f", teleportMs),
            String.format("%.2f", reconcileMs),
            String.format("%.2f", dimLinksMs),
            String.format("%.2f", totalMs));
    }
}
