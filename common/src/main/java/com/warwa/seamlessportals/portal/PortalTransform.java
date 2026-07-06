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
     * The crossing direction as the sign of the SOURCE-plane depth axis, derived from the
     * detected movement segment (the ground truth of the crossing: {@code from} is on the
     * entry side, {@code to} past the plane on the exit side). Fallback for a degenerate
     * segment: the side of {@code to} itself (past the plane at detection).
     *
     * <p>This replaces the old yaw-keyed {@code exitDepthSign} (2026-07-05): keying the
     * exit to the FACING assumed every crossing is face-first, so walking BACKWARD or
     * strafing through exited the player on the wrong side moving the wrong way —
     * "come out facing forwards", plus wrong-side landings that re-crossed immediately
     * under held input (the seq12-89 oscillation burst in the 18:32 run). IP keys nothing
     * to yaw: position, velocity, and view go through one rigid transform. For a FORWARD
     * walker this sign is provably identical to the old yaw rule on both axis pairings
     * (facing == motion ⇒ sign(cos yaw) == sign(motion depth); cross-axis:
     * −sin(yaw∓90°) == ±cos(yaw)), so only the broken backward/strafe cases change.
     */
    public static double crossingDepthSign(PortalInfo source, Vec3 moveFrom, Vec3 moveTo) {
        LocalCoords delta = toLocalCoords(source.getAxis(), moveTo.subtract(moveFrom));
        if (delta.depth() != 0.0) {
            return Math.signum(delta.depth());
        }
        LocalCoords pos = toLocalCoords(source.getAxis(), moveTo.subtract(source.getCenter()));
        return pos.depth() >= 0.0 ? 1.0 : -1.0;
    }

    /**
     * Crossing-direction sign when no movement segment is available (server side without a
     * client payload: server-first fallback crossings). Motion first — for a
     * {@code ServerPlayer}, pass {@code getKnownMovement()} (the client-reported movement),
     * not {@code getDeltaMovement()} (server-side player physics is not simulated) — then
     * the side of the position itself (past the plane once the crossing really happened).
     */
    public static double crossingDepthSignFromState(PortalInfo source, Vec3 position, Vec3 velocity) {
        LocalCoords vel = toLocalCoords(source.getAxis(), velocity);
        if (Math.abs(vel.depth()) > 1.0e-7) {
            return Math.signum(vel.depth());
        }
        LocalCoords pos = toLocalCoords(source.getAxis(), position.subtract(source.getCenter()));
        return pos.depth() >= 0.0 ? 1.0 : -1.0;
    }

    /**
     * Place the landing exactly {@code overshoot} (clamped) past the destination portal
     * plane on the side the crossing MOTION continues toward ({@code exitDepthSign}, from
     * {@link #crossingDepthSign}) — the same-sign mapping of the side the entity exited
     * toward at the source. Same-sign is the view-consistent choice: the window parallax
     * is {@code transformPoint} (same-sign depth, a 1:1 translation on a same-axis link),
     * so the landing continues the walk exactly where the window showed it. The velocity
     * ({@link #transformVelocityMotion}) keeps the same depth sign, so the entity always
     * moves AWAY from the plane after landing — no immediate re-cross from either
     * crossing direction. Width (lateral) + height are kept from the base transform, so
     * the entity emerges at the same spot along/up the portal.
     *
     * <p>Depth-axis mapping mirrors {@link #fromLocalCoords}: dest axis X → depth is world Z;
     * dest axis Z → depth is world X.
     */
    public static Vec3 applyExitOvershoot(PortalInfo destination, Vec3 destPos, double exitDepthSign, double overshoot) {
        double depth = Math.max(MIN_EXIT_OVERSHOOT, Math.min(MAX_EXIT_OVERSHOOT, overshoot));
        Vec3 center = destination.getCenter();
        double sign = exitDepthSign >= 0.0 ? 1.0 : -1.0;
        if (destination.getAxis() == Direction.Axis.X) {
            // axis X → portal spans X, depth (perpendicular) is world Z
            return new Vec3(destPos.x, destPos.y, center.z + sign * depth);
        }
        return new Vec3(center.x + sign * depth, destPos.y, destPos.z);
    }

    public static Vec3 transformVector(PortalInfo source, PortalInfo destination, PortalType type, Vec3 vector) {
        // Same logic as transformPoint but without the center offset.
        // Depth negated: walking INTO source = walking OUT OF destination.
        //
        // NOTE (player teleports use transformVelocityMotion instead): this blanket depth
        // negation is CONSISTENT with transformTeleportPoint's landing side (entity lands at
        // −ε moving −depth = away from the plane), so projectiles/entities are fine — an
        // internally consistent MIRRORED pair. Player landings use the motion-signed
        // overshoot (applyExitOvershoot + crossingDepthSign, the SAME-SIGN/view-consistent
        // pair), so a player's velocity must be same-sign too or it points back at the portal.
        LocalCoords local = toLocalCoords(source.getAxis(), vector);
        local = new LocalCoords(-local.depth(), local.width(), local.height());
        return fromLocalCoords(destination.getAxis(), local);
    }

    /**
     * Velocity transform for MOTION-CONTINUOUS teleports (players): the plain same-sign
     * local mapping — depth, width, and height all keep their local components (IP's
     * {@code transformLocalVec} analog; on a same-axis link this is the identity, matching
     * the window's 1:1 {@code transformPoint} parallax — you exit moving exactly as the
     * window showed you moving). Depth sign therefore equals the motion sign, agreeing
     * with the segment-signed landing side of {@link #applyExitOvershoot} +
     * {@link #crossingDepthSign} for any crossing direction (forward, backward, strafe).
     * (Not a hard invariant: the landing uses the DETECTION SEGMENT's sign, the velocity
     * the instantaneous movement — a mid-crossing knockback can oppose them, which
     * resolves as one terminating bounce back through the portal, not an oscillation.)
     *
     * <p>Replaces {@code transformVelocityFacing} (yaw-forced depth sign, 2026-07-05):
     * for a forward walker the outputs are identical (facing == motion); for a backward
     * walker the yaw rule REVERSED the velocity — "walk in backwards, come out moving
     * forwards".
     */
    public static Vec3 transformVelocityMotion(PortalInfo source, PortalInfo destination, PortalType type, Vec3 vector) {
        LocalCoords local = toLocalCoords(source.getAxis(), vector);
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
