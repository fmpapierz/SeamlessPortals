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
        Vec3 sourceCenter = source.getCenter();
        Vec3 destCenter = destination.getCenter();

        Vec3 offset = sourcePos.subtract(sourceCenter);

        if (source.getType() == PortalType.NETHER) {
            if (source.getDimension() == net.minecraft.world.level.Level.OVERWORLD) {
                offset = new Vec3(offset.x / coordinateScale, offset.y, offset.z / coordinateScale);
            } else {
                offset = new Vec3(offset.x * coordinateScale, offset.y, offset.z * coordinateScale);
            }
        }

        return destCenter.add(offset);
    }

    public Vec3 transformVelocity(Vec3 velocity) {
        if (source.getAxis() == destination.getAxis()) {
            return velocity;
        }
        return new Vec3(velocity.z, velocity.y, velocity.x);
    }

    public PortalInfo getSource() { return source; }
    public PortalInfo getDestination() { return destination; }
    public double getCoordinateScale() { return coordinateScale; }
}
