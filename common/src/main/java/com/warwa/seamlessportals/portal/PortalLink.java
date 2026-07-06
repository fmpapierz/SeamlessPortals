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
     * Teleport landing with OVERSHOOT-PRESERVING depth: the entity lands as far past the
     * destination plane as it was past the source plane at detection, on the side its
     * crossing MOTION continues toward ({@code exitDepthSign}, see
     * {@link #crossingDepthSign}/{@link #crossingDepthSignFromState}) — a visually
     * continuous crossing for any entry direction (forward, backward, strafe). Both
     * client (provisional swap) and server (authoritative, sign carried in the crossing
     * payload) call this with the same inputs so they land at the same place.
     * See {@link PortalTransform#applyExitOvershoot}.
     */
    public Vec3 transformTeleportPosition(Vec3 sourcePos, double exitDepthSign) {
        Vec3 destPos = PortalTransform.transformTeleportPoint(source, destination, source.getType(), sourcePos);
        double overshoot = PortalTransform.sourceDepthOvershoot(source, sourcePos);
        return PortalTransform.applyExitOvershoot(destination, destPos, exitDepthSign, overshoot);
    }

    public Vec3 transformVelocity(Vec3 velocity) {
        return PortalTransform.transformVector(source, destination, source.getType(), velocity);
    }

    /** Motion-continuous velocity transform for PLAYER teleports: plain same-sign local
     *  mapping (IP's transformLocalVec analog), so the depth sign is the crossing-motion
     *  sign — always agreeing with the motion-signed landing side of
     *  {@link #transformTeleportPosition(Vec3, double)}. */
    public Vec3 transformVelocityMotion(Vec3 velocity) {
        return PortalTransform.transformVelocityMotion(source, destination, source.getType(), velocity);
    }

    /** Crossing-direction sign (source depth axis) from the detected movement segment. */
    public double crossingDepthSign(Vec3 moveFrom, Vec3 moveTo) {
        return PortalTransform.crossingDepthSign(source, moveFrom, moveTo);
    }

    /** Crossing-direction sign when no segment is available (server-first fallback). */
    public double crossingDepthSignFromState(Vec3 position, Vec3 velocity) {
        return PortalTransform.crossingDepthSignFromState(source, position, velocity);
    }

    public float transformYaw(float sourceYaw) {
        return PortalTransform.transformYaw(source, destination, sourceYaw);
    }

    public PortalInfo getSource() { return source; }
    public PortalInfo getDestination() { return destination; }
    public double getCoordinateScale() { return coordinateScale; }
}
