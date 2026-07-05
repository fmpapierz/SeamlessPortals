package com.warwa.seamlessportals.portal;

import net.minecraft.world.phys.Vec3;

public class PortalLink {
    private final PortalInfo source;
    private final PortalInfo destination;
    private final double coordinateScale;

    public PortalLink(PortalInfo source, PortalInfo destination) {
        this.source = source;
        this.destination = destination;
        this.coordinateScale = source.getType().getCoordinateScale();
    }

    public Vec3 transformPosition(Vec3 sourcePos) {
        return PortalTransform.transformPoint(source, destination, source.getType(), sourcePos);
    }

    public Vec3 transformTeleportPosition(Vec3 sourcePos) {
        return PortalTransform.transformTeleportPoint(source, destination, source.getType(), sourcePos);
    }

    /**
     * Teleport landing pushed OUT past the destination portal plane so the entity emerges
     * clear of the portal instead of embedded on it (which caused the OW↔nether teleport
     * oscillation). Exit direction derives from the transformed source velocity. Both client
     * (provisional swap) and server (authoritative) call this with the same inputs so they
     * land at the same place. See {@link PortalTransform#applyExitClearance}.
     */
    public Vec3 transformTeleportPosition(Vec3 sourcePos, float destYaw) {
        Vec3 destPos = PortalTransform.transformTeleportPoint(source, destination, source.getType(), sourcePos);
        return PortalTransform.applyExitClearance(destination, destPos, destYaw);
    }

    public Vec3 transformVelocity(Vec3 velocity) {
        return PortalTransform.transformVector(source, destination, source.getType(), velocity);
    }

    /** Yaw-preserving velocity transform for PLAYER teleports: the depth sign follows the
     *  yaw-facing exit side (same rule as applyExitClearance's landing side), so the player
     *  keeps moving the way they face instead of drifting back toward the portal. */
    public Vec3 transformVelocityFacing(Vec3 velocity, float destYaw) {
        return PortalTransform.transformVelocityFacing(source, destination, source.getType(), velocity, destYaw);
    }

    public float transformYaw(float sourceYaw) {
        return PortalTransform.transformYaw(source, destination, sourceYaw);
    }

    public PortalInfo getSource() { return source; }
    public PortalInfo getDestination() { return destination; }
    public double getCoordinateScale() { return coordinateScale; }
}
