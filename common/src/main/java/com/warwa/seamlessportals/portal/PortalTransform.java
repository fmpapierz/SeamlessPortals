package com.warwa.seamlessportals.portal;

import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Canonical portal-space transformation utilities.
 *
 * Following IP's architecture, rendering and teleportation must use the SAME
 * transform path. If the view uses one transform and teleport uses another,
 * the player will never land where the portal view showed.
 */
public final class PortalTransform {

    private PortalTransform() {
    }

    public static Vec3 transformPoint(PortalInfo source, PortalInfo destination, PortalType type, Vec3 sourcePos) {
        Vec3 sourceCenter = source.getCenter();
        Vec3 destinationCenter = destination.getCenter();
        Vec3 offset = sourcePos.subtract(sourceCenter);

        // Convert player offset to portal-local coordinates (depth/width/height),
        // then convert back to world coordinates in the destination portal's frame.
        //
        // No coordinate scaling: the 8:1 nether scaling is already baked into the
        // portal centers (destination portal placed at coords/8 by PortalForcer).
        // The local offset from the portal face should be preserved 1:1.
        //
        // No axis swap: toLocalCoords/fromLocalCoords handle the axis rotation.
        // Source width maps to dest width, source height maps to dest height.
        //
        // 1:1 position mapping — no negation. The camera should be at the
        // same relative position to the destination portal as the player is
        // to the source portal. Oblique near-plane clipping (applied to the
        // projection matrix) handles making it look like a window.
        LocalCoords local = toLocalCoords(source.getAxis(), offset);
        return destinationCenter.add(fromLocalCoords(destination.getAxis(), local));
    }

    public static Vec3 transformVector(PortalInfo source, PortalInfo destination, PortalType type, Vec3 vector) {
        // Same logic as transformPoint but without the center offset.
        // Depth negated: walking INTO source = walking OUT OF destination.
        LocalCoords local = toLocalCoords(source.getAxis(), vector);
        local = new LocalCoords(-local.depth(), local.width(), local.height());
        return fromLocalCoords(destination.getAxis(), local);
    }

    public static float transformYaw(PortalInfo source, PortalInfo destination, float sourceYaw) {
        if (source.getAxis() == destination.getAxis()) {
            return sourceYaw;
        }
        return sourceYaw + ((source.getAxis() == Direction.Axis.Z) ? 90.0f : -90.0f);
    }

    public static float yawDelta(PortalInfo source, PortalInfo destination) {
        if (source.getAxis() == destination.getAxis()) {
            return 0.0f;
        }
        return (source.getAxis() == Direction.Axis.Z) ? 90.0f : -90.0f;
    }

    private static LocalCoords toLocalCoords(Direction.Axis axis, Vec3 vector) {
        if (axis == Direction.Axis.X) {
            return new LocalCoords(vector.z, vector.x, vector.y);
        }
        return new LocalCoords(vector.x, vector.z, vector.y);
    }

    private static Vec3 fromLocalCoords(Direction.Axis axis, LocalCoords local) {
        if (axis == Direction.Axis.X) {
            return new Vec3(local.width(), local.height(), local.depth());
        }
        return new Vec3(local.depth(), local.height(), local.width());
    }

    private static LocalCoords applyCoordinateScale(PortalType type, net.minecraft.resources.ResourceKey<Level> sourceDim, LocalCoords local) {
        if (type != PortalType.NETHER) {
            return local;
        }

        double scale = type.getCoordinateScale();
        if (sourceDim == Level.OVERWORLD) {
            return new LocalCoords(local.depth() / scale, local.width() / scale, local.height());
        }
        if (sourceDim == Level.NETHER) {
            return new LocalCoords(local.depth() * scale, local.width() * scale, local.height());
        }
        return local;
    }

    private record LocalCoords(double depth, double width, double height) {
    }
}
