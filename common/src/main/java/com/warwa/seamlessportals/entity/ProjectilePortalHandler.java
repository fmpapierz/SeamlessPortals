package com.warwa.seamlessportals.entity;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.portal.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public class ProjectilePortalHandler {

    public static boolean handleProjectileTick(Projectile projectile) {
        if (projectile.level().isClientSide()) return false;
        if (projectile.getPortalCooldown() > 0) return false;

        Vec3 currentPos = projectile.position();
        Vec3 movement = projectile.getDeltaMovement();
        Vec3 nextPos = currentPos.add(movement);

        ResourceKey<Level> dimension = projectile.level().dimension();
        PortalManager manager = PortalManager.getServerInstance();
        PortalTracker tracker = manager.getTracker(dimension);

        Optional<PortalInfo> portalOpt = tracker.findPortalIntersecting(currentPos, nextPos);
        if (portalOpt.isEmpty()) return false;

        PortalInfo portal = portalOpt.get();
        if (!SeamlessPortalsConfig.shouldProjectilePassThrough(portal.getType())) return false;

        Optional<PortalLink> linkOpt = manager.getLinkForPortal(portal.getPortalId());
        if (linkOpt.isEmpty()) return false;

        PortalLink link = linkOpt.get();

        return teleportProjectile(projectile, link, portal, currentPos, nextPos);
    }

    private static boolean teleportProjectile(Projectile projectile, PortalLink link,
                                               PortalInfo portal, Vec3 from, Vec3 to) {
        MinecraftServer server = projectile.level().getServer();
        if (server == null) return false;

        PortalInfo destPortal = link.getDestination();
        ResourceKey<Level> destDim = destPortal.getDimension();
        ServerLevel destLevel = server.getLevel(destDim);
        if (destLevel == null) return false;

        Vec3 intersection = portal.getIntersectionPoint(from, to);
        Vec3 remainingMovement = to.subtract(intersection);

        Vec3 destPos = link.transformPosition(intersection);
        Vec3 destVelocity = link.transformVelocity(projectile.getDeltaMovement());

        SeamlessPortalsConstants.LOGGER.debug("Projectile {} crossing portal at {} -> {}",
            projectile.getType().getDescriptionId(), intersection, destPos);

        // Handle ender pearl special case
        if (projectile instanceof ThrownEnderpearl pearl) {
            handleEnderPearlTeleport(pearl, link, destPos, destVelocity, destLevel);
            return true;
        }

        // Use TeleportTransition for dimension change
        TeleportTransition transition = new TeleportTransition(
            destLevel, destPos, destVelocity,
            projectile.getYRot(), projectile.getXRot(),
            TeleportTransition.DO_NOTHING
        );

        Entity newProjectile = projectile.teleport(transition);
        if (newProjectile instanceof Projectile newProj) {
            newProj.setPortalCooldown(EntityPortalCollision.getTeleportCooldown());

            // Apply remaining movement in the new dimension
            Vec3 transformedRemaining = link.transformVelocity(remainingMovement);
            newProj.setPos(
                destPos.x + transformedRemaining.x,
                destPos.y + transformedRemaining.y,
                destPos.z + transformedRemaining.z
            );
            return true;
        }

        return false;
    }

    private static void handleEnderPearlTeleport(ThrownEnderpearl pearl, PortalLink link,
                                                   Vec3 destPos, Vec3 destVelocity,
                                                   ServerLevel destLevel) {
        Entity owner = pearl.getOwner();

        TeleportTransition transition = new TeleportTransition(
            destLevel, destPos, destVelocity,
            pearl.getYRot(), pearl.getXRot(),
            TeleportTransition.DO_NOTHING
        );

        Entity newPearl = pearl.teleport(transition);
        if (newPearl != null) {
            newPearl.setPortalCooldown(EntityPortalCollision.getTeleportCooldown());
        }

        SeamlessPortalsConstants.LOGGER.debug("Ender pearl crossed portal, owner: {}",
            owner != null ? owner.getName().getString() : "unknown");
    }
}
