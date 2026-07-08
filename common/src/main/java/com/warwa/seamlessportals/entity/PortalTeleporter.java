package com.warwa.seamlessportals.entity;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public class PortalTeleporter {

    /**
     * Teleport a non-player entity (item, mob, animal) or run the player
     * server-first fallback. {@code moveFrom}/{@code moveTo} are the movement
     * segment that detected the crossing (EntityMixin plane-segment detection),
     * used to sign the exit exactly as the player path does.
     */
    public static boolean teleportEntity(Entity entity, PortalLink link, Vec3 moveFrom, Vec3 moveTo) {
        if (entity.level().isClientSide()) return false;

        PortalInfo destPortal = link.getDestination();
        ResourceKey<Level> destDim = destPortal.getDimension();

        MinecraftServer server = entity.level().getServer();
        if (server == null) return false;

        ServerLevel destLevel = server.getLevel(destDim);
        if (destLevel == null) {
            SeamlessPortalsConstants.LOGGER.warn("Destination level {} not found", destDim.identifier());
            return false;
        }

        if (entity instanceof ServerPlayer player) {
            return teleportPlayer(player, link);
        }

        // NON-PLAYER LANDING (2026-07-08, the "thrown items vanish / burn in
        // lava / can't be picked up" fix). Route items and mobs through the
        // EXACT same motion-signed landing the player uses, instead of the bare
        // depth-negating single-arg transformTeleportPosition + depth-negated
        // transformVelocity this method used before. That old pairing landed a
        // crossed entity ~0.2 blocks PAST the plane on the side OPPOSITE the
        // player's emergence (behind the destination portal-block column + its
        // wall — physically unreachable) with velocity pointing back through the
        // frame, so items drifted into the adjacent lava lake and burned (census:
        // ~59 of one burst destroyed) while survivors sat where the player could
        // never reach them. The player path (SeamlessServerTeleport.performCrossing)
        // does: exitSign = crossingDepthSign(segment); pos =
        // transformTeleportPosition(src, exitSign) [overshoot PAST the plane on the
        // motion side]; vel = transformVelocityMotion(vel) [same-sign, moving AWAY
        // from the plane]. Mirroring it makes a thrown item emerge exactly where
        // the player would — same reachable side, moving into the destination
        // (away from the portal-frame lava) — the IP-faithful behavior (IP applies
        // an exit overshoot along the motion direction to every regular entity).
        double exitSign = link.crossingDepthSign(moveFrom, moveTo);
        if (exitSign == 0.0 || Double.isNaN(exitSign)) {
            exitSign = link.crossingDepthSignFromState(entity.position(), entity.getDeltaMovement());
        }
        Vec3 destPos = link.transformTeleportPosition(entity.position(), exitSign);
        Vec3 destVelocity = link.transformVelocityMotion(entity.getDeltaMovement());
        return teleportNonPlayer(entity, destLevel, destPos, destVelocity);
    }

    private static boolean teleportPlayer(ServerPlayer player, PortalLink link) {
        // IP-style: server-first fallback path (when client-initiated crossing
        // hasn't fired yet). Delegates to SeamlessServerTeleport which uses
        // vanilla teleportTo + sends ClientboundSeamlessMovePayload for
        // reconciliation. The client-first path goes directly from
        // SeamlessClientTeleport to the server via ClientPortalCrossingPayload.
        SeamlessServerTeleport.performCrossing(player, link);
        return true;
    }

    private static boolean teleportNonPlayer(Entity entity, ServerLevel destLevel,
                                              Vec3 destPos, Vec3 destVelocity) {
        TeleportTransition transition = new TeleportTransition(
            destLevel, destPos, destVelocity,
            entity.getYRot(), entity.getXRot(),
            TeleportTransition.DO_NOTHING
        );

        Entity newEntity = entity.teleport(transition);
        if (newEntity != null) {
            // 2-tick anti-jitter dedup (2026-07-08 — was 300 ticks). The old
            // 15s cooldown existed as anti-loop armor for CONTAINMENT
            // detection (a recreated entity spawns inside the dest portal
            // volume and would re-fire every tick); EntityMixin now detects
            // by PLANE-SEGMENT crossing, and the recreated entity starts
            // with a null segment — it structurally cannot re-fire unless it
            // actually moves back through the plane, which is a legitimate
            // crossing. Two ticks only absorbs plane-straddling jitter (an
            // entity resting exactly on the plane), mirroring IP's 1-tick
            // teleportation dedup. This also ends the 15-second window in
            // which every freshly-crossed entity was INVISIBLE through the
            // portal (PortalEntityTracker skips cooldown-flagged entities
            // from the mirror) — the user-visible "items thrown through the
            // portal disappear".
            newEntity.setPortalCooldown(2);
        }
        return newEntity != null;
    }
}
