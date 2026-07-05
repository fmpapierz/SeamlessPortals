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
     * OVERSHOOT-PRESERVING landing (2026-07-05, replaces the fixed 0.5-block exit
     * clearance): the landing depth past the destination plane MIRRORS how far the
     * entity was past the source plane at detection — cross 0.03 past the source
     * plane, land 0.03 past the dest plane. Because the portal window's parallax is
     * a 1:1 translation ({@code transformPoint}), this makes the crossing visually
     * CONTINUOUS: what you saw at distance d through the window is at distance d
     * after the swap. The old constant 0.5 threw the player ~0.45 blocks forward on
     * every crossing (the user-visible "jumps me to a further position").
     *
     * <p>{@code MIN}: never land exactly on the plane (float-noise re-cross safety)
     * and stay past the camera near-plane (~0.05) so an instant 180° turn renders
     * the dest portal window cleanly. Binds only at high fps (typical per-frame
     * detection overshoot at walking speed is 0.03-0.07) — a ≤5 cm remap,
     * imperceptible. {@code MAX}: a pathological late detection (stalled frame,
     * server-side lag) must not land the entity deep past the portal where the
     * clear exit area is no longer guaranteed; 0.5 = the old clearance behaviour.
     *
     * <p>Safety context: landing this close to the plane is only safe with the full
     * crossing stack in place — plane-crossing segment detection with its baseline
     * reset at the swap (the landing itself is never a segment), swapSeq-ordered
     * reconciles (ACK-only), and tolerant vanilla teleport-packet application. The
     * 0.5 clearance predates all of that hardening.
     */
    public static final double MIN_EXIT_OVERSHOOT = 0.08;
    public static final double MAX_EXIT_OVERSHOOT = 0.5;

    /** |depth past the source portal plane| of a source-dim position (the crossing overshoot). */
    public static double sourceDepthOvershoot(PortalInfo source, Vec3 sourcePos) {
        Vec3 offset = sourcePos.subtract(source.getCenter());
        LocalCoords local = toLocalCoords(source.getAxis(), offset);
        return Math.abs(local.depth());
    }

    /**
     * Place the landing exactly {@code overshoot} (clamped) past the destination portal
     * plane on the side the entity is FACING, so pressing "forward" walks it AWAY —
     * no immediate re-cross. The exit side must come from the yaw, NOT the velocity or
     * {@code computeNormal} (direction-agnostic) — see {@link #exitDepthSign}. Width
     * (lateral) + height are kept from the base transform, so the entity emerges at the
     * same spot along/up the portal.
     *
     * <p>Depth-axis mapping mirrors {@link #fromLocalCoords}: dest axis X → depth is world Z;
     * dest axis Z → depth is world X.
     */
    public static Vec3 applyExitOvershoot(PortalInfo destination, Vec3 destPos, float destYaw, double overshoot) {
        double depth = Math.max(MIN_EXIT_OVERSHOOT, Math.min(MAX_EXIT_OVERSHOOT, overshoot));
        Vec3 center = destination.getCenter();
        double sign = exitDepthSign(destination.getAxis(), destYaw);
        if (destination.getAxis() == Direction.Axis.X) {
            // axis X → portal spans X, depth (perpendicular) is world Z
            return new Vec3(destPos.x, destPos.y, center.z + sign * depth);
        }
        return new Vec3(center.x + sign * depth, destPos.y, destPos.z);
    }

    /**
     * Which side of the destination portal plane the entity FACES (the exit side), as the
     * sign of the depth-axis world coordinate. Single source of truth shared by
     * {@link #applyExitClearance} (landing side) and {@link #transformVelocityFacing}
     * (velocity direction) — the two MUST agree or the entity lands on one side while
     * moving toward the other (the observed ~0.02-block backward drift after crossing).
     */
    private static double exitDepthSign(Direction.Axis destAxis, float destYaw) {
        double yawRad = Math.toRadians(destYaw);
        if (destAxis == Direction.Axis.X) {
            // axis X → depth is world Z; facing Z = cos(yaw)
            return Math.cos(yawRad) >= 0 ? 1.0 : -1.0;
        }
        // axis Z → depth is world X; facing X = -sin(yaw)
        return -Math.sin(yawRad) >= 0 ? 1.0 : -1.0;
    }

    public static Vec3 transformVector(PortalInfo source, PortalInfo destination, PortalType type, Vec3 vector) {
        // Same logic as transformPoint but without the center offset.
        // Depth negated: walking INTO source = walking OUT OF destination.
        //
        // NOTE (player teleports use transformVelocityFacing instead): this blanket depth
        // negation is CONSISTENT with transformTeleportPoint's landing side (entity lands at
        // −ε moving −depth = away from the plane), so projectiles/entities are fine. Player
        // landings are OVERRIDDEN to the yaw-facing side by applyExitClearance, so a player's
        // velocity must use the SAME yaw rule or it points back at the portal.
        LocalCoords local = toLocalCoords(source.getAxis(), vector);
        local = new LocalCoords(-local.depth(), local.width(), local.height());
        return fromLocalCoords(destination.getAxis(), local);
    }

    /**
     * Velocity transform for YAW-PRESERVING teleports (players). Width/height map exactly
     * like {@link #transformVector}, but the depth component's SIGN follows the yaw-facing
     * exit side — the same rule {@link #applyExitClearance} uses for the landing — with the
     * magnitude preserved. The player therefore keeps moving the way they face ("walking
     * forward stays walking forward"); the old blanket negation sent them drifting BACKWARD
     * toward the portal for the 1-2 ticks until input re-accelerated (XTRACE 2026-07-05:
     * pl z reversing ~0.02 blocks right after the swap on a same-facing link).
     */
    public static Vec3 transformVelocityFacing(PortalInfo source, PortalInfo destination, PortalType type, Vec3 vector, float destYaw) {
        LocalCoords local = toLocalCoords(source.getAxis(), vector);
        double sign = exitDepthSign(destination.getAxis(), destYaw);
        LocalCoords out = new LocalCoords(sign * Math.abs(local.depth()), local.width(), local.height());
        return fromLocalCoords(destination.getAxis(), out);
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
