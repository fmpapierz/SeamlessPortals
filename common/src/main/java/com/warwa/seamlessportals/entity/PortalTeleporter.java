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
        float destYaw = link.transformYaw(player.getYRot());

        // No cooldown — IP-style: depth negation places player behind dest portal,
        // so no re-trigger. EntityMixin dimension tracking handles the rest.
        player.teleportTo(destLevel, destPos.x, destPos.y, destPos.z,
            Set.of(), destYaw, player.getXRot(), false);

        player.setDeltaMovement(destVelocity);

        SeamlessPortalsConstants.LOGGER.info("[SEAMLESS TELEPORT] {} -> {} in {}",
            player.getName().getString(), destPos, destLevel.dimension().identifier());

        // Re-send portal data for the new dimension
        MinecraftServer server = destLevel.getServer();
        if (server != null) {
            PortalManager portalManager = PortalManager.getServerInstance();
            portalManager.sendDimensionLinksToPlayer(destLevel.dimension(), player);
        }

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
        return newEntity != null;
    }
}
