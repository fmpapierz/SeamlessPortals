package com.warwa.seamlessportals.entity;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.portal.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public class EntityPortalCollision {

    private static final int TELEPORT_COOLDOWN_TICKS = 20;

    public static Optional<PortalLink> checkPortalCrossing(Entity entity, Vec3 from, Vec3 to) {
        if (entity.level().isClientSide()) return Optional.empty();

        ResourceKey<Level> dimension = entity.level().dimension();
        PortalManager manager = PortalManager.getServerInstance();
        PortalTracker tracker = manager.getTracker(dimension);

        Optional<PortalInfo> portalOpt = tracker.findPortalIntersecting(from, to);
        if (portalOpt.isEmpty()) return Optional.empty();

        PortalInfo portal = portalOpt.get();

        if (!SeamlessPortalsConfig.shouldSeamlessTeleport(portal.getType())) {
            return Optional.empty();
        }

        return manager.getLinkForPortal(portal.getPortalId());
    }

    public static Vec3 computeTeleportPosition(PortalLink link, Vec3 entityPos) {
        return link.transformPosition(entityPos);
    }

    public static Vec3 computeTeleportVelocity(PortalLink link, Vec3 velocity) {
        return link.transformVelocity(velocity);
    }

    public static boolean isInPortalBounds(Entity entity) {
        return findPortalLinkAtEntity(entity).isPresent();
    }

    /**
     * IP-style PLANE-CROSSING detection: return the link for a portal whose plane
     * the entity's movement segment {@code from -> to} passes THROUGH this tick.
     *
     * <p>Unlike {@link #findPortalLinkAtEntity} (bounding-box containment), this
     * does NOT match an entity merely standing or embedded inside a portal. After
     * a crossing the player lands inside the destination portal volume; with
     * containment detection that player satisfies "in portal" every tick and
     * re-teleports forever (the overworld&lt;-&gt;nether oscillation freeze). A
     * plane-crossing test only fires when the segment STRADDLES the thin plane
     * (see {@link PortalInfo#intersectsMovement}), so an embedded-but-not-moving-
     * through player does not re-fire — yet the player can still immediately walk
     * back through (crossing the plane again) to return. Works on both sides.
     */
    public static Optional<PortalLink> findPortalCrossing(Entity entity, Vec3 from, Vec3 to) {
        ResourceKey<Level> dimension = entity.level().dimension();
        PortalManager manager = entity.level().isClientSide()
            ? PortalManager.getClientInstance()
            : PortalManager.getServerInstance();

        PortalTracker tracker = manager.getTracker(dimension);
        if (tracker == null) return Optional.empty();

        Optional<PortalInfo> portalOpt = tracker.findPortalIntersecting(from, to);
        if (portalOpt.isEmpty()) return Optional.empty();

        PortalInfo portal = portalOpt.get();
        if (!SeamlessPortalsConfig.shouldSeamlessTeleport(portal.getType())) {
            return Optional.empty();
        }
        return manager.getLinkForPortal(portal.getPortalId());
    }

    /**
     * Find the portal link for a portal the entity is currently inside.
     * Used for instant teleportation when the entity enters the portal bounding box.
     */
    public static Optional<PortalLink> findPortalLinkAtEntity(Entity entity) {
        ResourceKey<Level> dimension = entity.level().dimension();
        PortalManager manager = entity.level().isClientSide()
            ? PortalManager.getClientInstance()
            : PortalManager.getServerInstance();

        PortalTracker tracker = manager.getTracker(dimension);
        Vec3 entityPos = entity.position();

        for (PortalInfo portal : tracker.getPortalsInRange(entity.blockPosition(), 2.0)) {
            if (portal.containsPoint(entityPos)) {
                if (!SeamlessPortalsConfig.shouldSeamlessTeleport(portal.getType())) {
                    continue;
                }
                Optional<PortalLink> link = manager.getLinkForPortal(portal.getPortalId());
                if (link.isPresent()) {
                    return link;
                }
            }
        }
        return Optional.empty();
    }

    public static int getTeleportCooldown() {
        return TELEPORT_COOLDOWN_TICKS;
    }
}
