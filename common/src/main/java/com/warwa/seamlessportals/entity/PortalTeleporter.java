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

    public static boolean teleportEntity(Entity entity, PortalLink link) {
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

        Vec3 currentPos = entity.position();
        Vec3 destPos = link.transformTeleportPosition(currentPos);
        Vec3 destVelocity = link.transformVelocity(entity.getDeltaMovement());

        if (entity instanceof ServerPlayer player) {
            return teleportPlayer(player, destLevel, destPos, destVelocity, link);
        } else {
            return teleportNonPlayer(entity, destLevel, destPos, destVelocity);
        }
    }

    private static boolean teleportPlayer(ServerPlayer player, ServerLevel destLevel,
                                           Vec3 destPos, Vec3 destVelocity, PortalLink link) {
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
