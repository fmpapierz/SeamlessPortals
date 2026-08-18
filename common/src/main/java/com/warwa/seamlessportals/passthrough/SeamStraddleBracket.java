package com.warwa.seamlessportals.passthrough;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.collision.PortalCollisionEntry;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * F6 — THE STRADDLE PIN (user-confirmed contract, 2026-08-11, points 1/3/7).
 *
 * <p>The render bracket for an entity crossing a seam is the face it is CROSSING — but IP's
 * bookkeeping is EYE-side-based at both ends: the per-tick prune deletes the crossing face's
 * entry the moment the (lag-trailing) visual eye passes the plane, and the per-tick sweep then
 * registers the CO-LOCATED OPPOSITE face (whose front the eye is still on), which wins the
 * last-entry-wins clip. Live signature, the user's own words: the cart "clips incorrectly,
 * renders on source side A for a second and disappears" as it arrives back through the return
 * portal.
 *
 * <p>The pin is geometry-true and side-symmetric: <b>while an entity's crossing of a seam face
 * is IN PROGRESS — its box overlaps the face's column and has not yet fully emerged on the
 * face's front side — a booked entry for that face persists (and stays fresh), and the face's
 * co-located twin may not register.</b> The window is wider than a literal plane-straddle: a
 * rebased arrival visual TRAILS the server position by up to ~1.6 blocks (ridden worst case),
 * so between the arrival-face seed and the first real straddle the box sits wholly BEHIND the
 * plane — exactly where the eye-side and box-proximity prunes would delete the entry and the
 * twin (whose FRONT is that un-emerged side) would book a "normal approach", flipping the clip
 * to keep the wrong half (the pop-in flash). The moment the box fully clears the plane on the
 * front side, ordinary IP bookkeeping resumes untouched. Non-seam portals are wholly unaffected.
 *
 * <p>Bounded by construction: pure per-call geometry — no state, no registry, no fan-out.
 */
public final class SeamStraddleBracket {

    private SeamStraddleBracket() {}

    /** Signed-distance epsilon: a boundary-touching box does not count as straddling. */
    private static final double EPS = 0.01;

    /**
     * How far BEHIND the plane the pin window reaches: the ridden client-visual trail
     * (~1.6 blocks) plus a cart length of slack. Purely a bound — the entry only exists at all
     * because a crossing seeded or swept it, so the reach never captures bystanders.
     */
    private static final double MAX_TRAIL = 3.0;

    /** Signed distance of the box face NEAREST the plane along {@code n} (min over the box). */
    private static double spanMin(AABB box, Vec3 n, Vec3 o) {
        return n.x * ((n.x >= 0 ? box.minX : box.maxX) - o.x)
            + n.y * ((n.y >= 0 ? box.minY : box.maxY) - o.y)
            + n.z * ((n.z >= 0 ? box.minZ : box.maxZ) - o.z);
    }

    /** Signed distance of the box face FARTHEST past the plane along {@code n} (max over the box). */
    private static double spanMax(AABB box, Vec3 n, Vec3 o) {
        return n.x * ((n.x >= 0 ? box.maxX : box.minX) - o.x)
            + n.y * ((n.y >= 0 ? box.maxY : box.minY) - o.y)
            + n.z * ((n.z >= 0 ? box.maxZ : box.minZ) - o.z);
    }

    /** Does this entity's box straddle this face's plane (with lateral aperture overlap)? */
    public static boolean straddles(Entity entity, Portal face) {
        AABB box = entity.getBoundingBox();
        if (!face.getBoundingBox().inflate(0.5).intersects(box)) {
            return false;
        }
        Vec3 n = face.getNormal();
        Vec3 o = face.getOriginPos();
        return spanMin(box, n, o) < -EPS && spanMax(box, n, o) > EPS;
    }

    /**
     * The PIN WINDOW — the crossing is in progress against this face: the box overlaps the
     * face's column (0.5 lateral margin, {@link #MAX_TRAIL} reach on the behind side) and has
     * not yet fully emerged on the front side. A superset of {@link #straddles}; the extra
     * reach is what protects the seeded arrival entry while the rebased visual is still
     * wholly behind the plane.
     */
    public static boolean pinned(Entity entity, Portal face) {
        AABB box = entity.getBoundingBox();
        Vec3 n = face.getNormal();
        if (!face.getBoundingBox().inflate(0.5)
            .expandTowards(n.scale(-MAX_TRAIL)).intersects(box)) {
            return false;
        }
        Vec3 o = face.getOriginPos();
        return spanMin(box, n, o) < -EPS && spanMax(box, n, o) > -MAX_TRAIL;
    }

    /** Is the entity's box WHOLLY on the face's behind side (nothing poked past the plane)? */
    public static boolean whollyBehind(Entity entity, Portal face) {
        AABB box = entity.getBoundingBox();
        return spanMax(box, face.getNormal(), face.getOriginPos()) < EPS;
    }

    /**
     * Does the entity's BACK PIECE exist against this face's plane (part of the box on the
     * {@code −n} side)? The engine's projection-existence primitive (design §0: a face projects
     * an entity iff the back piece exists — the piece the projection displays).
     */
    public static boolean backPieceExists(Entity entity, Portal face) {
        AABB box = entity.getBoundingBox();
        return spanMin(box, face.getNormal(), face.getOriginPos()) < -EPS;
    }

    // (The seed ThreadLocal bracket — beginSeed/endSeed/inSeed — was retired at engine stage
    // 2a: the arrival seed is now ANCHOR-AUTHORIZED (SeamCrossingRule.mayBook clause (a)).
    // Design §1.2: the ThreadLocal was one of the three costumes of the crossing's single
    // irreducible history bit; the SeamCrossing anchor is that bit in one costume.)

    /**
     * The PRUNE gate: keep this entry (bypassing the eye-side, staleness, and box-proximity
     * reasons — the caller consults this BEFORE its own box gate) while the entity's crossing
     * of this seam face is in progress.
     */
    public static boolean keeps(Entity entity, Portal face) {
        return SeamCartContinuity.isSeamContinuous(face) && pinned(entity, face);
    }

    /**
     * The REGISTER gate: refuse a candidate face whose CO-LOCATED OPPOSITE twin is currently
     * booked with a crossing in progress — the twin's clip keeps the wrong half mid-crossing.
     * Everything non-seam, non-co-located, or outside the pin window registers exactly as
     * before.
     */
    public static boolean refuses(
        Entity entity, PortalCollisionHandler handler, Portal candidate
    ) {
        if (handler == null || handler.portalCollisions.isEmpty()) {
            return false;
        }
        if (!SeamCartContinuity.isSeamContinuous(candidate)) {
            return false;
        }
        for (PortalCollisionEntry e : handler.portalCollisions) {
            Portal booked = e.portal;
            if (booked != candidate
                && booked.level() == candidate.level()
                && booked.getOriginPos().subtract(candidate.getOriginPos()).lengthSqr() < 0.01
                && booked.getNormal().dot(candidate.getNormal()) < -0.9
                && pinned(entity, booked)) {
                return true;
            }
        }
        return false;
    }
}
