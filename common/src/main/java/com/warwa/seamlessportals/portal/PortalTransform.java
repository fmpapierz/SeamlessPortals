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
        Vec3 destCenter = destination.getCenter();
        Vec3 offset = sourcePos.subtract(sourceCenter);

        // Depth/width use center-relative mapping (horizontal alignment).
        // Height uses floor-relative mapping (origin Y) so different-height
        // portals keep the ground level consistent instead of shifting the
        // view up or down.
        LocalCoords local = toLocalCoords(source.getAxis(), offset);
        LocalCoords horizontal = new LocalCoords(local.depth(), local.width(), 0);
        Vec3 hResult = fromLocalCoords(destination.getAxis(), horizontal);

        double heightFromFloor = sourcePos.y() - source.getOrigin().getY();

        return new Vec3(
            destCenter.x() + hResult.x(),
            destination.getOrigin().getY() + heightFromFloor,
            destCenter.z() + hResult.z()
        );
    }

    /**
     * Transform a position for TELEPORTATION. Same as transformPoint but negates
     * depth so that walking INTO the source portal places you walking OUT OF the
     * destination portal (behind it, not in front of it).
     */
    public static Vec3 transformTeleportPoint(PortalInfo source, PortalInfo destination, PortalType type, Vec3 sourcePos) {
        Vec3 sourceCenter = source.getCenter();
        Vec3 destCenter = destination.getCenter();
        Vec3 offset = sourcePos.subtract(sourceCenter);

        // Negate depth: walking INTO source = walking OUT OF destination.
        // Height uses floor-relative mapping (same as transformPoint).
        LocalCoords local = toLocalCoords(source.getAxis(), offset);
        LocalCoords horizontal = new LocalCoords(-local.depth(), local.width(), 0);
        Vec3 hResult = fromLocalCoords(destination.getAxis(), horizontal);

        double heightFromFloor = sourcePos.y() - source.getOrigin().getY();

        return new Vec3(
            destCenter.x() + hResult.x(),
            destination.getOrigin().getY() + heightFromFloor,
            destCenter.z() + hResult.z()
        );
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
