package com.warwa.seamlessportals.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Portal geometry data. All calculations follow IP's portal coordinate system.
 *
 * NetherPortalBlock.AXIS gives the WIDTH direction:
 *   axis=X → width along X, portal face perpendicular to Z (thin in Z)
 *   axis=Z → width along Z, portal face perpendicular to X (thin in X)
 *
 * Origin is the bottom corner with the most-negative width-axis coordinate,
 * found by PortalDetector.findPortalOrigin().
 */
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

    /**
     * Bounding box: width extends along the AXIS direction, thin in the perpendicular direction.
     *   axis=X → X spans [origin.x, origin.x+width], Z spans [origin.z, origin.z+1]
     *   axis=Z → Z spans [origin.z, origin.z+width], X spans [origin.x, origin.x+1]
     */
    private AABB computeBoundingBox() {
        if (axis == Direction.Axis.X) {
            // Width along X, thin in Z
            return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + width, origin.getY() + height, origin.getZ() + 1
            );
        } else {
            // Width along Z, thin in X
            return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + 1, origin.getY() + height, origin.getZ() + width
            );
        }
    }

    private Vec3 computeCenter() {
        return boundingBox.getCenter();
    }

    /**
     * Normal points perpendicular to the portal face (the thin direction).
     *   axis=X → face is in XY plane, perpendicular to Z → normal = (0, 0, 1)
     *   axis=Z → face is in ZY plane, perpendicular to X → normal = (1, 0, 0)
     */
    private Vec3 computeNormal() {
        if (axis == Direction.Axis.X) {
            return new Vec3(0, 0, 1); // face perpendicular to Z
        } else {
            return new Vec3(1, 0, 0); // face perpendicular to X
        }
    }

    public boolean containsPoint(Vec3 point) {
        return boundingBox.inflate(0.1).contains(point);
    }

    /**
     * Check if point is on the portal plane (thin axis).
     *   axis=X → plane is at fixed Z → check Z distance
     *   axis=Z → plane is at fixed X → check X distance
     */
    public boolean isPointOnPortalPlane(Vec3 point, double tolerance) {
        if (axis == Direction.Axis.X) {
            return Math.abs(point.z - center.z) < tolerance; // plane perpendicular to Z
        } else {
            return Math.abs(point.x - center.x) < tolerance; // plane perpendicular to X
        }
    }

    /**
     * Check if a movement vector crosses the portal plane.
     * The plane coordinate is the thin axis (perpendicular to face).
     *   axis=X → plane at fixed Z
     *   axis=Z → plane at fixed X
     */
    public boolean intersectsMovement(Vec3 from, Vec3 to) {
        // Plane coordinate is the THIN axis (perpendicular to face)
        double planeCoord = (axis == Direction.Axis.X) ? center.z : center.x;
        double fromCoord = (axis == Direction.Axis.X) ? from.z : from.x;
        double toCoord = (axis == Direction.Axis.X) ? to.z : to.x;

        if ((fromCoord - planeCoord) * (toCoord - planeCoord) > 0) {
            return false; // both on same side of plane
        }

        double t = (planeCoord - fromCoord) / (toCoord - fromCoord);
        if (t < 0 || t > 1) return false;

        Vec3 intersection = from.lerp(to, t);

        return intersection.y >= origin.getY() && intersection.y <= origin.getY() + height
            && isWithinPortalWidth(intersection);
    }

    /**
     * Check if point is within the portal width (along the WIDTH axis).
     *   axis=X → width along X → check X
     *   axis=Z → width along Z → check Z
     */
    private boolean isWithinPortalWidth(Vec3 point) {
        if (axis == Direction.Axis.X) {
            return point.x >= origin.getX() && point.x <= origin.getX() + width; // width along X
        } else {
            return point.z >= origin.getZ() && point.z <= origin.getZ() + width; // width along Z
        }
    }

    public Vec3 getIntersectionPoint(Vec3 from, Vec3 to) {
        // Plane coordinate is the THIN axis
        double planeCoord = (axis == Direction.Axis.X) ? center.z : center.x;
        double fromCoord = (axis == Direction.Axis.X) ? from.z : from.x;
        double toCoord = (axis == Direction.Axis.X) ? to.z : to.x;

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
