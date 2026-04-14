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
