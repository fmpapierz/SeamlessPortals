package com.warwa.seamlessportals.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
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
        // Deterministic UUID from portal identity. The client-first seamless
        // teleport sends the source portal id to the server for validation;
        // client and server must agree on the id for a given portal.
        // Previous behavior (UUID.randomUUID()) made them mismatch.
        // Width/height excluded so resizes don't change the id.
        this.portalId = UUID.nameUUIDFromBytes(
            (dimension.identifier().toString()
                + "@" + origin.getX() + "," + origin.getY() + "," + origin.getZ()
                + "/" + axis.getName()
            ).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        // axis = the direction the portal blocks stretch along (width direction).
        // axis=X: width along X, thin along Z (portal face in XY plane)
        // axis=Z: width along Z, thin along X (portal face in YZ plane)
        if (axis == Direction.Axis.X) {
            return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + width, origin.getY() + height, origin.getZ() + 1
            );
        } else {
            return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + 1, origin.getY() + height, origin.getZ() + width
            );
        }
    }

    private Vec3 computeCenter() {
        return boundingBox.getCenter();
    }

    private Vec3 computeNormal() {
        // Normal = perpendicular to the portal face (depth direction, the direction
        // players look through). This is the axis NOT containing the portal blocks.
        // axis=X: portal face in XY plane → normal along Z
        // axis=Z: portal face in YZ plane → normal along X
        if (axis == Direction.Axis.X) {
            return new Vec3(0, 0, 1);  // depth along Z
        } else {
            return new Vec3(1, 0, 0);  // depth along X
        }
    }

    public boolean containsPoint(Vec3 point) {
        return boundingBox.inflate(0.1).contains(point);
    }

    public boolean isPointOnPortalPlane(Vec3 point, double tolerance) {
        // axis = width direction. Plane crossing is along the DEPTH (thin) axis.
        // axis=X → thin along Z → check Z
        // axis=Z → thin along X → check X
        if (axis == Direction.Axis.X) {
            return Math.abs(point.z - center.z) < tolerance;
        } else {
            return Math.abs(point.x - center.x) < tolerance;
        }
    }

    public boolean intersectsMovement(Vec3 from, Vec3 to) {
        // axis = width direction. Plane crossing is along the DEPTH (thin) axis.
        // axis=X → thin along Z → check Z coordinates
        // axis=Z → thin along X → check X coordinates
        double planeCoord = (axis == Direction.Axis.X) ? center.z : center.x;
        double fromCoord = (axis == Direction.Axis.X) ? from.z : from.x;
        double toCoord = (axis == Direction.Axis.X) ? to.z : to.x;

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
        // axis = width direction. Check bounds along the WIDTH axis.
        // axis=X → width along X → check X coordinates
        // axis=Z → width along Z → check Z coordinates
        if (axis == Direction.Axis.X) {
            return point.x >= origin.getX() && point.x <= origin.getX() + width;
        } else {
            return point.z >= origin.getZ() && point.z <= origin.getZ() + width;
        }
    }

    public Vec3 getIntersectionPoint(Vec3 from, Vec3 to) {
        // axis = width direction. Plane crossing is along the DEPTH (thin) axis.
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
