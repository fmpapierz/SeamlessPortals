package com.warwa.seamlessportals.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public class PortalInfo {
    private final UUID portalId;
    private final PortalType type;
    private final ResourceKey<Level> dimension;
    private final BlockPos origin;
    private final Direction.Axis axis;
    private final int width;
    private final int height;
    private final AABB boundingBox;
    private final Vec3 center;
    private final Vec3 normal;

    public PortalInfo(PortalType type, ResourceKey<Level> dimension, BlockPos origin,
                      Direction.Axis axis, int width, int height) {
        this.portalId = UUID.randomUUID();
        this.type = type;
        this.dimension = dimension;
        this.origin = origin;
        this.axis = axis;
        this.width = width;
        this.height = height;
        this.boundingBox = computeBoundingBox();
        this.center = computeCenter();
        this.normal = computeNormal();
    }

    private AABB computeBoundingBox() {
        if (axis == Direction.Axis.X) {
            return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + 1, origin.getY() + height, origin.getZ() + width
            );
        } else {
            return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + width, origin.getY() + height, origin.getZ() + 1
            );
        }
    }

    private Vec3 computeCenter() {
        return boundingBox.getCenter();
    }

    private Vec3 computeNormal() {
        if (axis == Direction.Axis.X) {
            return new Vec3(1, 0, 0);
        } else {
            return new Vec3(0, 0, 1);
        }
    }

    public boolean containsPoint(Vec3 point) {
        return boundingBox.inflate(0.1).contains(point);
    }

    public boolean isPointOnPortalPlane(Vec3 point, double tolerance) {
        if (axis == Direction.Axis.X) {
            return Math.abs(point.x - center.x) < tolerance;
        } else {
            return Math.abs(point.z - center.z) < tolerance;
        }
    }

    public boolean intersectsMovement(Vec3 from, Vec3 to) {
        double planeCoord = (axis == Direction.Axis.X) ? center.x : center.z;
        double fromCoord = (axis == Direction.Axis.X) ? from.x : from.z;
        double toCoord = (axis == Direction.Axis.X) ? to.x : to.z;

        if ((fromCoord - planeCoord) * (toCoord - planeCoord) > 0) {
            return false;
        }

        double t = (planeCoord - fromCoord) / (toCoord - fromCoord);
        if (t < 0 || t > 1) return false;

        Vec3 intersection = from.lerp(to, t);

        return intersection.y >= origin.getY() && intersection.y <= origin.getY() + height
            && isWithinPortalWidth(intersection);
    }

    private boolean isWithinPortalWidth(Vec3 point) {
        if (axis == Direction.Axis.X) {
            return point.z >= origin.getZ() && point.z <= origin.getZ() + width;
        } else {
            return point.x >= origin.getX() && point.x <= origin.getX() + width;
        }
    }

    public Vec3 getIntersectionPoint(Vec3 from, Vec3 to) {
        double planeCoord = (axis == Direction.Axis.X) ? center.x : center.z;
        double fromCoord = (axis == Direction.Axis.X) ? from.x : from.z;
        double toCoord = (axis == Direction.Axis.X) ? to.x : to.z;

        double t = (planeCoord - fromCoord) / (toCoord - fromCoord);
        return from.lerp(to, t);
    }

    public UUID getPortalId() { return portalId; }
    public PortalType getType() { return type; }
    public ResourceKey<Level> getDimension() { return dimension; }
    public BlockPos getOrigin() { return origin; }
    public Direction.Axis getAxis() { return axis; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public AABB getBoundingBox() { return boundingBox; }
    public Vec3 getCenter() { return center; }
    public Vec3 getNormal() { return normal; }
}
