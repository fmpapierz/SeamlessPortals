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

        // Check teleport cooldown
        if (entity.getPortalCooldown() > 0) return Optional.empty();

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
        ResourceKey<Level> dimension = entity.level().dimension();
        PortalManager manager = entity.level().isClientSide()
            ? PortalManager.getClientInstance()
            : PortalManager.getServerInstance();

        PortalTracker tracker = manager.getTracker(dimension);
        Vec3 entityPos = entity.position();

        for (PortalInfo portal : tracker.getPortalsInRange(entity.blockPosition(), 2.0)) {
            if (portal.containsPoint(entityPos)) {
                return true;
            }
        }
        return false;
    }

    public static int getTeleportCooldown() {
        return TELEPORT_COOLDOWN_TICKS;
    }
}
