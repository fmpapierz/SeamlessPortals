package com.warwa.seamlessportals.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class PortalTracker {
    private final ResourceKey<Level> dimension;
    private final List<PortalInfo> portals = new CopyOnWriteArrayList<>();

    public PortalTracker(ResourceKey<Level> dimension) {
        this.dimension = dimension;
    }

    public void addPortal(PortalInfo portal) {
        portals.removeIf(p -> p.getOrigin().equals(portal.getOrigin()));
        portals.add(portal);
    }

    public void removePortal(PortalInfo portal) {
        portals.removeIf(p -> p.getPortalId().equals(portal.getPortalId()));
    }

    public void removePortalAt(BlockPos pos) {
        portals.removeIf(p -> p.getBoundingBox().contains(Vec3.atCenterOf(pos)));
    }

    public Optional<PortalInfo> getPortalAt(BlockPos pos) {
        Vec3 posVec = Vec3.atCenterOf(pos);
        for (PortalInfo portal : portals) {
            if (portal.getBoundingBox().inflate(0.5).contains(posVec)) {
                return Optional.of(portal);
            }
        }
        return Optional.empty();
    }

    public List<PortalInfo> getPortalsInRange(BlockPos center, double range) {
        List<PortalInfo> result = new ArrayList<>();
        double rangeSq = range * range;
        Vec3 centerVec = Vec3.atCenterOf(center);
        for (PortalInfo portal : portals) {
            if (portal.getCenter().distanceToSqr(centerVec) <= rangeSq) {
                result.add(portal);
            }
        }
        return result;
    }

    public Optional<PortalInfo> findNearestPortal(BlockPos pos, double maxRange, PortalType type) {
        Vec3 posVec = Vec3.atCenterOf(pos);
        double maxRangeSq = maxRange * maxRange;
        PortalInfo nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (PortalInfo portal : portals) {
            if (portal.getType() != type) continue;
            double distSq = portal.getCenter().distanceToSqr(posVec);
            if (distSq < nearestDistSq && distSq <= maxRangeSq) {
                nearest = portal;
                nearestDistSq = distSq;
            }
        }
        return Optional.ofNullable(nearest);
    }

    public Optional<PortalInfo> findPortalIntersecting(Vec3 from, Vec3 to) {
        for (PortalInfo portal : portals) {
            if (portal.intersectsMovement(from, to)) {
                return Optional.of(portal);
            }
        }
        return Optional.empty();
    }

    public List<PortalInfo> getAllPortals() {
        return Collections.unmodifiableList(portals);
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }
}
