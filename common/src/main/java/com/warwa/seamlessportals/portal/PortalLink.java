package com.warwa.seamlessportals.portal;

import net.minecraft.world.phys.Vec3;

public class PortalLink {
    private final PortalInfo source;
    private final PortalInfo destination;
    private final double coordinateScale;

    /**
     * IP loading-indicator parity: while {@code false}, this link
     * blocks both client-first seamless teleport and server-side
     * teleport-handling. Set to {@code true} once
     * {@code SeamlessLinkReadinessTracker} verifies all
     * pre-load chunks reach FULL status server-side.
     *
     * <p>Server-side: tracker polls per tick, flips when ready,
     * broadcasts {@code LinkReadinessPayload} to all clients.
     *
     * <p>Client-side: mirrored from the payload. Default {@code true}
     * for client-side links restored from server (in case the
     * payload arrives before the load confirmation, fail-open is
     * safer than locking out teleport forever).
     */
    private volatile boolean linkReady = false;

    public PortalLink(PortalInfo source, PortalInfo destination) {
        this.source = source;
        this.destination = destination;
        this.coordinateScale = source.getType().getCoordinateScale();
    }

    public boolean isLinkReady() { return linkReady; }
    public void setLinkReady(boolean ready) { this.linkReady = ready; }

    public Vec3 transformPosition(Vec3 sourcePos) {
        return PortalTransform.transformPoint(source, destination, source.getType(), sourcePos);
    }

    public Vec3 transformTeleportPosition(Vec3 sourcePos) {
        return PortalTransform.transformTeleportPoint(source, destination, source.getType(), sourcePos);
    }

    public Vec3 transformVelocity(Vec3 velocity) {
        return PortalTransform.transformVector(source, destination, source.getType(), velocity);
    }

    public float transformYaw(float sourceYaw) {
        return PortalTransform.transformYaw(source, destination, sourceYaw);
    }

    public PortalInfo getSource() { return source; }
    public PortalInfo getDestination() { return destination; }
    public double getCoordinateScale() { return coordinateScale; }
}
