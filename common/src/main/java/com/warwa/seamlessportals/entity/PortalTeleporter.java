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
import net.minecraft.world.entity.Relative;
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
        Vec3 destPos = link.transformPosition(currentPos);
        Vec3 destVelocity = link.transformVelocity(entity.getDeltaMovement());

        SeamlessPortalsConstants.LOGGER.debug("Teleporting {} from {} to {} (dim: {} -> {})",
            entity.getName().getString(),
            currentPos, destPos,
            entity.level().dimension().identifier(),
            destDim.identifier());

        if (entity instanceof ServerPlayer player) {
            return teleportPlayer(player, destLevel, destPos, destVelocity, link);
        } else {
            return teleportNonPlayer(entity, destLevel, destPos, destVelocity);
        }
    }

    private static boolean teleportPlayer(ServerPlayer player, ServerLevel destLevel,
                                           Vec3 destPos, Vec3 destVelocity, PortalLink link) {
        // Teleport the player without the loading screen using teleportTo
        float destYaw = link.transformYaw(player.getYRot());
        player.teleportTo(destLevel, destPos.x, destPos.y, destPos.z,
            Set.of(), destYaw, player.getXRot(), false);

        player.setDeltaMovement(destVelocity);
        player.setPortalCooldown(EntityPortalCollision.getTeleportCooldown());

        SeamlessPortalsConstants.LOGGER.info("Player {} teleported seamlessly to {} in {}",
            player.getName().getString(), destPos, destLevel.dimension().identifier());

        // Re-send portal data for the new dimension to the client
        // This ensures the client can render portals in the new dimension
        MinecraftServer server = destLevel.getServer();
        if (server != null) {
            PortalManager portalManager = PortalManager.getServerInstance();
            portalManager.sendDimensionLinksToPlayer(destLevel.dimension(), player);
        }

        return true;
    }

    private static boolean teleportNonPlayer(Entity entity, ServerLevel destLevel,
                                              Vec3 destPos, Vec3 destVelocity) {
        // Use TeleportTransition for non-player entity dimension change
        TeleportTransition transition = new TeleportTransition(
            destLevel, destPos, destVelocity,
            entity.getYRot(), entity.getXRot(),
            TeleportTransition.DO_NOTHING
        );

        Entity newEntity = entity.teleport(transition);

        if (newEntity != null) {
            newEntity.setPortalCooldown(EntityPortalCollision.getTeleportCooldown());
            return true;
        }

        SeamlessPortalsConstants.LOGGER.warn("Failed to teleport entity {} to {}",
            entity.getName().getString(), destLevel.dimension().identifier());
        return false;
    }
}
