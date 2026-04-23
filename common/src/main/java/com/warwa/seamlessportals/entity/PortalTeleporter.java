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
            // Vanilla cross-dim teleport replaces the entity object — the
            // @Unique seamlessportals$justTeleported flag on the source
            // entity is lost, and the new entity spawns inside the
            // destination portal bounds. Without a cooldown it would be
            // picked up by our EntityMixin on the very next tick,
            // teleported back, creating a new entity each hop. Setting the
            // standard 300-tick (15 s) portal cooldown on the new entity
            // matches vanilla's cross-portal anti-loop behaviour and
            // guarantees the mob has time to walk out of the portal bounds.
            newEntity.setPortalCooldown(300);
        }
        return newEntity != null;
    }
}
