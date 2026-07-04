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
    public static void handleClientInitiatedCrossing(ServerPlayer player, String portalIdString,
                                                     int swapSeq) {
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

        performCrossing(player, link, swapSeq);
    }

    /** Server-initiated crossings (no client seq to echo): swapSeq = -1, always honored. */
    public static void performCrossing(ServerPlayer player, PortalLink link) {
        performCrossing(player, link, -1);
    }

    /**
     * Do the authoritative cross-dim move + notify the client.
     *
     * <p>Uses vanilla {@code ServerPlayer.teleportTo} for the heavy lifting
     * (entity-list transfer, chunk tracker update, other-players' entity
     * tracking, advancement triggers, etc.) but suppresses the single packet
     * we don't want: {@link net.minecraft.network.protocol.game.ClientboundRespawnPacket}.
     *
     * @param swapSeq the client's crossing sequence number to echo in the reconcile
     *                ({@code -1} = server-initiated; the client always honors it)
     */
    public static void performCrossing(ServerPlayer player, PortalLink link, int swapSeq) {
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
        float destYaw = link.transformYaw(player.getYRot());
        // Landing is placed `clearance` PAST the dest portal on the side the player FACES
        // (destYaw), so they emerge cleanly in front of it and pressing forward walks AWAY —
        // no immediate re-cross (the OW↔nether oscillation / "land embedded, walk forward,
        // teleport again"). Yaw, not velocity: the transform negates velocity depth but keeps
        // yaw, so the velocity side would face the portal.
        Vec3 destPos = link.transformTeleportPosition(srcPos, destYaw);
        Vec3 destVel = link.transformVelocity(player.getDeltaMovement());
        float destPitch = player.getXRot();

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS SERVER-CROSSING] {} {} -> {} in {} (portal {})",
            player.getName().getString(),
            String.format("(%.1f,%.1f,%.1f)", srcPos.x, srcPos.y, srcPos.z),
            String.format("(%.1f,%.1f,%.1f)", destPos.x, destPos.y, destPos.z),
            destDim.identifier(),
            link.getSource().getPortalId().toString());

        // NB: vanilla's ServerPlayer.teleportTo WILL send ClientboundRespawnPacket.
        // We deliberately let it through — fully suppressing the packet breaks the
        // client's per-dim protocol state (chunk packet decode fails with
        // IndexOutOfBoundsException). Instead, on the CLIENT side,
        // HandleRespawnMixin detects that SeamlessClientTeleport already
        // performed the visual swap and cancels handleRespawn as a no-op —
        // the protocol state still advances, but the visual-swap logic is
        // elided. Server side: nothing special, just the vanilla teleport.
        player.teleportTo(destLevel, destPos.x, destPos.y, destPos.z,
            Set.<Relative>of(), destYaw, destPitch, false);

        player.setDeltaMovement(destVel);

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
                destVel.x, destVel.y, destVel.z,
                swapSeq);
        PlatformHelper.getInstance().sendToClient(player, reconcile);

        // Re-send portal data for the new dimension (mirrors legacy flow).
        PortalManager.getServerInstance().sendDimensionLinksToPlayer(destDim, player);
    }
}
