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

        // MOTION-SIGNED LANDING (2026-07-08, the "arrows disappear in the portal /
        // no arrow on the other side" fix — the projectile-path analog of the
        // item/mob fix in PortalTeleporter). The old pairing —
        // transformPosition(intersection) [lands AT the plane, no overshoot] +
        // transformVelocity(...) [transformVector, depth-NEGATED] + a
        // depth-negated remaining-movement add — emerged the arrow embedded in
        // the destination portal-block column moving BACKWARD into the frame, so
        // it stuck inGround behind the wall (unreachable, invisible). Mirror the
        // proven player/item landing instead: sign the exit by the crossing
        // segment, land PAST the plane on the motion side (applyExitOvershoot,
        // ≥0.08 in open air), and keep velocity same-sign (transformVelocityMotion:
        // magnitude-preserving, no 8x coordinate scale, moving AWAY from the plane
        // into the destination). The arrow now emerges on the reachable exit side
        // flying continuously — visible through the portal for the same reason
        // items are. No remaining-movement term (it double-counted the overshoot
        // and the working item path omits it entirely). IP-faithful: IP crosses
        // arrows as regular entities (no depth negation, motion-direction exit).
        double exitSign = link.crossingDepthSign(from, to);
        if (exitSign == 0.0 || Double.isNaN(exitSign)) {
            exitSign = link.crossingDepthSignFromState(intersection, projectile.getDeltaMovement());
        }
        Vec3 destPos = link.transformTeleportPosition(intersection, exitSign);
        Vec3 destVelocity = link.transformVelocityMotion(projectile.getDeltaMovement());

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
            // 2-tick anti-jitter dedup, matching items/mobs (PortalTeleporter).
            // Was 20; the recreated projectile already emerges ≥0.08 past the
            // plane moving away under plane-segment detection, so it cannot
            // re-cross — the long cooldown only delayed its through-portal
            // visibility. The TeleportTransition already placed it at destPos
            // with destVelocity, so no post-teleport reposition is needed (the
            // old remaining-movement setPos double-counted the overshoot).
            newProj.setPortalCooldown(2);
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
            newPearl.setPortalCooldown(2);
        }

        SeamlessPortalsConstants.LOGGER.debug("Ender pearl crossed portal, owner: {}",
            owner != null ? owner.getName().getString() : "unknown");
    }
}
