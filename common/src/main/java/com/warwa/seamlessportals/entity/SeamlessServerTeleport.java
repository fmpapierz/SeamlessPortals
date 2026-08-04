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
 *   <li>{@link #handleClientInitiatedCrossing(ServerPlayer, String, int, double)} —
 *       packet entry point. Validates proximity + link + the crossing-direction
 *       hint, then calls {@link #performCrossing}.</li>
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
                                                     int swapSeq, double exitDepthSign) {
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

        // Validate: the player is reasonably near the source portal. Measure
        // against the portal's BOUNDING BOX (inflated by a generous margin), NOT
        // its center point (2026-07-08 fix). A fixed 8-block CENTER radius falsely
        // rejected legitimate crossings near the edge of a LARGE/TALL portal — a
        // 21-tall portal's top row is ~10 blocks from center, beyond the old
        // radius — which left the client teleported (the visual swap already
        // happened) while the server REFUSED, a permanent client-in-dest /
        // server-in-source desync. The box scales with the portal, so the margin
        // stays uniform from the aperture regardless of portal size; the 8-block
        // inflate still absorbs the several-block client-server position skew at
        // the crossing tick while rejecting far-away spoofed crossings.
        PortalInfo srcPortal = link.getSource();
        if (!srcPortal.getBoundingBox().inflate(8.0).contains(player.position())) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS SERVER-CROSSING] Rejected: player {} at {} is not within 8 blocks of source portal box {}",
                player.getName().getString(), player.position(), srcPortal.getBoundingBox());
            return;
        }

        // Sanitize the client's crossing-direction hint: anything that is not a clean
        // ±1 (0, NaN, corrupt) falls back to server-side derivation. A wrong sign from
        // a hostile client only moves that client to the other side of the dest portal
        // plane (within the 8-block-validated area) — no privilege gained.
        double exitSign = Math.signum(exitDepthSign);
        if (exitSign == 0.0 || Double.isNaN(exitSign)) {
            exitSign = link.crossingDepthSignFromState(player.position(), player.getKnownMovement());
        }

        performCrossing(player, link, swapSeq, exitSign);
    }

    /** Server-initiated crossings (no client seq to echo): swapSeq = -1, always honored.
     *  No detection segment exists on this path — derive the crossing direction from the
     *  client-reported movement ({@code getKnownMovement}; server player physics is not
     *  simulated, so {@code getDeltaMovement} is stale), then the position's plane side. */
    public static void performCrossing(ServerPlayer player, PortalLink link) {
        double exitSign = link.crossingDepthSignFromState(player.position(), player.getKnownMovement());
        performCrossing(player, link, -1, exitSign);
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
     * @param exitDepthSign crossing-direction sign on the source depth axis (±1) —
     *                from the client's detection segment (payload) or the server-side
     *                fallback derivation; keys the motion-continuous exit side + velocity
     */
    public static void performCrossing(ServerPlayer player, PortalLink link, int swapSeq,
                                       double exitDepthSign) {
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
        // Landing is placed `overshoot` PAST the dest portal on the side the crossing
        // MOTION continues toward (exitDepthSign — client detection segment via the
        // payload, or the server-side fallback), moving away from the plane — no
        // immediate re-cross from either entry direction. The old yaw-keyed side
        // assumed face-first crossings and flipped backward/strafe walkers to a
        // forward-walker exit (plus wrong-side landings that ping-ponged under held
        // input). Velocity is the plain same-sign mapping, so it agrees with the
        // landing side by construction.
        Vec3 destPos = link.transformTeleportPosition(srcPos, exitDepthSign);
        Vec3 destVel = link.transformVelocityMotion(player.getKnownMovement());
        float destPitch = player.getXRot();

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS SERVER-CROSSING] {} {} -> {} in {} (portal {}, exitSign {})",
            player.getName().getString(),
            String.format("(%.1f,%.1f,%.1f)", srcPos.x, srcPos.y, srcPos.z),
            String.format("(%.1f,%.1f,%.1f)", destPos.x, destPos.y, destPos.z),
            destDim.identifier(),
            link.getSource().getPortalId().toString(),
            String.format("%+.0f", exitDepthSign));

        // NB: vanilla's ServerPlayer.teleportTo WILL send ClientboundRespawnPacket.
        // We deliberately let it through — fully suppressing the packet breaks the
        // client's per-dim protocol state (chunk packet decode fails with
        // IndexOutOfBoundsException). Instead, on the CLIENT side,
        // HandleRespawnMixin detects that SeamlessClientTeleport already
        // performed the visual swap and cancels handleRespawn as a no-op —
        // the protocol state still advances, but the visual-swap logic is
        // elided. Server side: nothing special, just the vanilla teleport.
        //
        // Chunk hand-off BEFORE the teleport (player.level() is still the old
        // dim): arms one-shot suppression of vanilla's re-send for the chunks
        // our tracker already streamed for the dest dim, and seeds the old
        // dim's sent-record so redirected streaming resumes incrementally
        // instead of cold-restarting at burst rate. Kills ~85% of the
        // per-crossing render-thread freeze (chunk decode + light re-init).
        com.warwa.seamlessportals.chunk.PortalChunkTracker.onPlayerCrossing(
            player, player.level().dimension(), destDim,
            new net.minecraft.world.level.ChunkPos(
                net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(destPos.x)),
                net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(destPos.z))));

        // RELATIVE rotation + velocity (union(DELTA, ROTATION), yaw/pitch 0):
        // the accompanying ClientboundPlayerPositionPacket must not disturb the
        // client's rotation or momentum. With the old all-absolute teleport the
        // packet (a) set absolute yaw — the client's UNWRAPPED yaw can differ
        // from the server's wrapped value by ±360, and while the camera angle is
        // identical mod 360, LocalPlayer's lagged hand-sway fields chase the
        // numeric jump at 0.5/tick → a ~36°-decaying first-person hand swing
        // (XTRACE 2026-07-05: swayY +360.17 → +180.09 → +90.12 right at the
        // packet's arrival) — and (b) zeroed deltaMovement → the walk-bob
        // amplitude collapsed and re-accelerated (the BOB DIP frames). Vanilla
        // itself uses exactly this relative set for "move without disturbing
        // rotation/momentum" (ServerPlayer:1680). The client's own transform
        // already applied the crossing's yaw/velocity change at the visual swap;
        // for the rare server-first fallback, rotation arrives via our reconcile
        // payload (doVisualSwap carries the lagged fields), never this packet.
        player.teleportTo(destLevel, destPos.x, destPos.y, destPos.z,
            Relative.union(Relative.DELTA, Relative.ROTATION), 0.0F, 0.0F, false);

        // The relative teleport leaves the SERVER player's rotation at its
        // pre-crossing value — set the transformed rotation explicitly (rotation
        // is client-authoritative; the next client move packet confirms it).
        player.setYRot(destYaw);
        player.setXRot(destPitch);
        player.setYHeadRot(destYaw);

        player.setDeltaMovement(destVel);

        // FORCE A SHARED-FLAGS RESYNC (2026-07-06, the stuck-elytra companion fix):
        // entity-data syncs are one-shot (packDirty clears dirtiness at send time),
        // and around a crossing the client has a window where player-addressed
        // SetEntityData packets can be lost (see
        // ClientPacketListenerLocalPlayerFallbackMixin — which closes the delivery
        // hole; this is the belt-and-braces healer). Re-dirtying the flags byte
        // guarantees one authoritative resync per crossing, sent by the NEW dim's
        // ServerEntity after the respawn packet — healing any prior divergence of
        // fall-flying/sprint/sneak/fire in either direction.
        net.minecraft.network.syncher.EntityDataAccessor<Byte> flagsId =
            com.warwa.seamlessportals.mixin.EntityFlagsAccessor.seamlessportals$getSharedFlagsId();
        player.getEntityData().set(flagsId, player.getEntityData().get(flagsId), true);

        // ★ RE-SEND SEAM OCCUPANCY (RS-SEAM-EMPTINESS discriminator, 2026-08-03): the crossing's
        // client-side world swap runs ClientWorldLoader.cleanUp(), discarding every per-dim
        // ClientLevel and the occupancy duck maps with them — after which every seam cell draws
        // as a whole cube. The Fabric AFTER_PLAYER_CHANGE_WORLD hook covers vanilla dimension
        // changes; this direct call covers the mod's own crossing path regardless of which
        // vanilla internals the loader wraps. Masks REPLACE on apply — duplicates are harmless.
        com.warwa.seamlessportals.passthrough.SeamOccupancySavedData.resendAllToPlayer(player);

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
