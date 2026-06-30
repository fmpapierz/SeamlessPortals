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

    /**
     * Distance (blocks) a teleported entity is pushed OUT past the destination portal
     * plane along the exit/travel direction, so it emerges clear of the portal instead of
     * embedded ON it. Set to 0 to disable the push (restores the old on-plane landing).
     *
     * <p>Root bug this fixes: crossing is detected by PLANE-crossing, so at the crossing
     * instant the source-relative depth is ≈0; {@link #transformTeleportPoint} negates that
     * (~0) and the entity lands exactly on the destination portal plane. Sitting on the
     * plane, the entity's very next movement re-straddles it → instant re-cross (the OW↔nether
     * teleport oscillation). IP avoids this by transforming the entity's already-overshot
     * current position; we push by a small explicit clearance instead.
     */
    public static final double TELEPORT_EXIT_CLEARANCE = 0.5;

    /**
     * Push a teleport landing OUT of the destination portal along its depth axis, in the
     * direction the ALREADY-TRANSFORMED velocity points (the exit/travel direction), so the
     * entity emerges clear of the portal plane rather than embedded on it.
     *
     * <p>The exit SIGN is taken from {@code destVel}, NOT from a static portal normal:
     * {@link PortalInfo#computeNormal()} is direction-agnostic (always +Z/+X regardless of
     * which face the entity entered), so using it would push the entity to the WRONG side for
     * one of the two travel directions. The transformed velocity always points the way the
     * entity is actually moving through the portal, so it is correct both ways.
     *
     * <p>Depth-axis mapping mirrors {@link #fromLocalCoords}: dest axis X → depth is world Z;
     * dest axis Z → depth is world X.
     */
    public static Vec3 applyExitClearance(PortalInfo destination, Vec3 destPos, float destYaw) {
        double clearance = TELEPORT_EXIT_CLEARANCE;
        if (clearance == 0.0) return destPos;
        // OVERRIDE the depth coordinate (don't just nudge it): place the entity exactly
        // `clearance` past the portal plane on the side it is FACING, so pressing "forward"
        // walks it AWAY from the portal — no immediate re-cross. The exit side must come from
        // the yaw, NOT the velocity: the transform preserves yaw but NEGATES velocity depth, so
        // they point opposite ways; placing on the velocity side leaves the entity FACING the
        // portal → "move forward → teleport again" (observed). And NOT from computeNormal (it is
        // direction-agnostic). Width (lateral) + height are kept from the base transform, so the
        // entity emerges at the same spot along/up the portal, just cleanly in front of it.
        // MC yaw: 0=+Z, 90=-X, 180=-Z, 270=+X → forward = (-sin(yaw), 0, cos(yaw)).
        Vec3 center = destination.getCenter();
        double yawRad = Math.toRadians(destYaw);
        if (destination.getAxis() == Direction.Axis.X) {
            // axis X → portal spans X, depth (perpendicular) is world Z; facing Z = cos(yaw)
            double sign = Math.cos(yawRad) >= 0 ? 1.0 : -1.0;
            return new Vec3(destPos.x, destPos.y, center.z + sign * clearance);
        }
        // axis Z → depth is world X; facing X = -sin(yaw)
        double sign = -Math.sin(yawRad) >= 0 ? 1.0 : -1.0;
        return new Vec3(center.x + sign * clearance, destPos.y, destPos.z);
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
