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

    /**
     * ★ ROUND 31 — THE MODEL MARGIN (the residual rider clip).
     *
     * <p><b>The mismatch.</b> The seam clip is a HARDWARE PLANE cutting the drawn MODEL's
     * triangles, but every predicate in this class measures the COLLISION BOX. Those are not the
     * same shape. A cow's model reaches <b>0.9375</b> blocks ahead of its position at the muzzle
     * and 0.625 behind at the rump, against a box half-extent of 0.45 — overhangs of
     * <b>+0.4875 / +0.175</b>. A minecart's shell overhangs by only <b>0.135</b> (0.625 vs 0.49).
     * That 3.6x ratio is exactly why the residual cut lands <b>ON THE RIDER</b>: at the measured
     * 0.4 blocks/tick the cow's leading overhang spans 1.22 tick steps, so it reliably contains
     * at least one tick, while the cart's spans 0.34 and usually misses. The user's "front of the
     * cow's face cut off" and "tail end cut off" are the same defect on opposite edges.
     *
     * <p><b>Why it is yaw-free.</b> A cow's head part rotates by {@code yHeadRot − yBodyRot}
     * independently of the body, so even with the body perpendicular to the rail a 30° head turn
     * still projects {@code 0.9375·sin(30°) = 0.469} along the plane normal — above the 0.45 box
     * half. There is essentially no heading at which the cow is safe, which is why the user sees
     * it as intermittent by tick phase rather than by direction. The margin is therefore
     * projected on the NORMAL and carries no yaw term.
     *
     * <p><b>Why 1.0 and why this is safe.</b> The consumers are a short, checkable list:
     * {@code frontPieceExists} is read only by the DEFAULT-OFF band painter (zero live blast
     * radius); {@code backPieceExists} only by {@code mainPassProjectionAdmitted}, whose own
     * round-10 argument is that a pre-crossing projection is fully clipped anyway — so admitting
     * one early is pixel-neutral; and {@code pinnedForDraw} only holds the crossing open a little
     * longer. Every direction of error is "draw a piece that is already clipped to nothing",
     * never "cull a piece that should paint". 1.0 covers the cow's 0.4875 with real headroom and
     * also covers the shadow decal's reach (shadowRadius ≈ 0.7 vs box half 0.45).
     * Lever {@code -PdisableSeamModelMargin}.
     *
     * <p><b>★ ROUND 34 — DO NOT RAISE THIS TO CHASE THE FACE CUT. It was tried and REFUTED.</b>
     * Raising it to 2.0 moved the admission gate exactly as designed (gating relocated from the
     * 1.5-2.0 bucket out to 2.5-3.0; main-pass projection draws rose 6109 → 9824) and the face
     * cut SURVIVED UNCHANGED — at very low crossing speed, where tick-lag and the sweep are both
     * negligible. That is a clean disproof: the face cut is <b>not an admission problem</b>. The
     * projection DRAWS and its pixels simply do not cover the head, so the defect lives in the
     * CLIP or the coverage, not in any gate this constant can widen. Reverted to the
     * geometry-derived 1.0, which is what the tail-clip and locality fixes were verified on.
     *
     * <p>Tint attribution for that remaining defect, kept here because it is what rules this
     * constant out: while cut, the body reads GREEN — in-pass ambient content, which is
     * stencil-confined to the window rectangle and therefore physically cannot paint past the
     * window's edge; when correct, the head reads ORANGE — the main-pass projection, the only
     * painter that can reach there. So the head's pixels are the projection's job, the
     * projection is being admitted, and something downstream of admission is removing them.
     */
    private static final double DRAW_MODEL_MARGIN = 1.0;

    private static double drawMargin() {
        return AperturePassthroughLever.DISABLE_SEAM_MODEL_MARGIN ? 0.0 : DRAW_MODEL_MARGIN;
    }

    /**
     * ★ ROUND 32 — the model margin, exposed for the LOCALITY gate.
     *
     * <p>The span predicates in this class are not the only place the box stands in for the
     * model. {@code CrossPortalEntityRenderer}'s in-pass locality gate
     * ({@code isOtherSideBoxInside(transformedBoundingBox, renderingPortal)}) culls a projection
     * once the entity's transformed BOX no longer reaches the shown side of the window — while
     * the drawn MODEL still pokes back through it.
     *
     * <p><b>Measured, tint lap 2026-08-20:</b> the in-pass projection draws only within 0.75
     * blocks of the plane and is dead beyond it, while
     * {@code PROJ skipped (outside rendering portal N)} takes over at exactly that distance
     * (0.00-0.25: 200 drew / 24 culled · 0.25-0.50: 124 / 30 · <b>0.50-0.75: 18 / 164</b> ·
     * 0.75+: 0 / 92+). A cow's model reaches 0.9375, so between ~0.5 and ~0.94 blocks past the
     * plane the muzzle still needs painting and the gate has already culled it — the user's
     * "cuts off from dest and grows from seam", the cow being worse than the cart (0.9375 vs
     * 0.625 reach), and the yaw dependence they photographed (head yaw changes the reach along
     * the normal, which also falsifies the earlier "no yaw is safe" claim).
     */
    public static double modelMargin() {
        return drawMargin();
    }

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
     * ★ ROUND 30 — THE TICK-BOX / VISUAL-BOX SWEEP (the tail-clip fix).
     *
     * <p><b>The defect this closes.</b> Every predicate here reads {@code getBoundingBox()} —
     * the POST-TICK box. But design §1.1 rule 1 specifies the verdicts are functions of
     * "<b>B</b>, the entity's <i>interpolated</i> box", and the renderer draws the INTERPOLATED
     * visual, which lags the post-tick box by up to one tick of movement. At the user's measured
     * crossing speed of <b>0.489 blocks/tick</b> that lag is half a block, so for the frames of
     * the tick in which the tick-box clears the plane, the draw gates say "no back piece — stop
     * projecting" while up to 0.29 blocks of the RENDERED body is still behind it. Nothing
     * paints that piece: the trailing ~25–29% of the cart+cow vanishes for those frames. That is
     * the 29-round "tail-end sliver", and it was never the ±ADJUSTMENT band at all — it is one
     * tick of movement, which is why it scaled with speed and never responded to any epsilon.
     *
     * <p><b>Arithmetic from the user's own log (2026-08-20, tick 123664):</b> at
     * {@code x=-657.295} the box straddles the plane at {@code x=-657.5} and the projection
     * draws; one tick later at {@code x=-656.806} the box has cleared (min {@code -657.296}) and
     * the projection stops — but that tick's frames interpolate FROM {@code -657.295}, whose
     * trailing edge sits at {@code -657.785}, i.e. <b>0.285 blocks behind the plane, unpainted</b>.
     * Away crossings compute to 0.238. Every one of the 14 measured dropouts sat at
     * 0.400–0.510 blocks/tick.
     *
     * <p><b>The rule.</b> The rendered visual always lies between the last-tick box and the
     * current box, so a DRAW-side existence predicate must test the SWEEP of the two. That is
     * exact for every partial tick without needing the render thread's partialTick, so it is
     * safe to call from either domain — unlike a true interpolated box, which would be
     * render-thread-only. Being a superset it can only ever ADMIT a draw one tick early, never
     * cull one late; an early projection is harmless by the round-10 argument (pre-crossing the
     * image is fully clipped anyway).
     *
     * <p><b>Scope discipline (load-bearing).</b> The sweep is for DRAW existence and for the
     * crossing's CLOSE only. Booking, pruning and collision stay on the post-tick box — they are
     * tick-domain by nature, and feeding them a sweep would widen the physics substrate.
     * Lever {@code -PdisableSeamVisualSweep} restores the post-tick box everywhere.
     */
    private static AABB sweptBox(Entity entity) {
        // ★ ROUND 35 — the base is now the per-entity RENDER ENVELOPE, not the collision box.
        // The clip cuts the DRAWN MODEL and the box-to-model gap is per entity TYPE, so the
        // correction must be derived at runtime (vanilla's own frustum-cull box, a contractual
        // upper bound on drawn extent for vanilla and modded entities alike) rather than taken
        // from DRAW_MODEL_MARGIN, which was sized off one cow. The margin below is retained as
        // additional slack and as the A/B lever; the envelope is what makes this general.
        AABB box = SeamRenderExtent.envelope(entity);
        if (AperturePassthroughLever.DISABLE_SEAM_VISUAL_SWEEP) {
            return box;
        }
        Vec3 cur = entity.position();
        Vec3 last = qouteall.imm_ptl.core.McHelper.lastTickPosOf(entity);
        double dx = last.x - cur.x;
        double dy = last.y - cur.y;
        double dz = last.z - cur.z;
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            return box;
        }
        // The union of the box with itself displaced to the last-tick position — the exact
        // region the interpolated visual can occupy this tick. Built from the constructor and
        // the public min/max fields rather than AABB.minmax, which this tree cannot verify.
        AABB moved = box.move(dx, dy, dz);
        return new AABB(
            Math.min(box.minX, moved.minX),
            Math.min(box.minY, moved.minY),
            Math.min(box.minZ, moved.minZ),
            Math.max(box.maxX, moved.maxX),
            Math.max(box.maxY, moved.maxY),
            Math.max(box.maxZ, moved.maxZ)
        );
    }

    /**
     * Does the entity's BACK PIECE exist against this face's plane (part of the box on the
     * {@code −n} side)? The engine's projection-existence primitive (design §0: a face projects
     * an entity iff the back piece exists — the piece the projection displays).
     *
     * <p>★ ROUND 30: evaluated on the SWEPT box (see {@link #sweptBox}), because this is a DRAW
     * verdict and the renderer draws the interpolated visual, not the post-tick box.
     */
    public static boolean backPieceExists(Entity entity, Portal face) {
        AABB box = sweptBox(entity);
        // ★ R31: minus the model margin — the drawn muzzle/rump reaches past the box.
        return spanMin(box, face.getNormal(), face.getOriginPos()) - drawMargin() < -EPS;
    }

    /**
     * Does the entity's FRONT piece exist against this face's plane ({@code +n} side)?
     * ★ ROUND 30: swept, same reason as {@link #backPieceExists}.
     */
    public static boolean frontPieceExists(Entity entity, Portal face) {
        AABB box = sweptBox(entity);
        // ★ R31: plus the model margin — same mismatch, opposite edge.
        return spanMax(box, face.getNormal(), face.getOriginPos()) + drawMargin() > EPS;
    }

    /**
     * ★ ROUND 30 — the CLOSE guard. {@link #pinned} decides when a crossing is over, and it
     * reads the post-tick box; closing on the tick the box clears prunes the entry while the
     * rendered visual is still straddling, killing every painter for that tick's frames (the
     * other half of the tail-clip mechanism — both projections died together in the log at the
     * same tick as {@code ANCHOR-CLOSE}). Evaluated on the swept box, the crossing survives
     * exactly one extra tick, by which point the visual has reached the cleared position and
     * nothing is owed.
     */
    /**
     * ★ ROUND 35 — does the entity's DRAWN model straddle this face's plane? The acquire-side
     * predicate behind {@link SeamCrossingRule#mustBook}: a literal straddle of the render
     * envelope (front and back pieces both nonempty), which is true for the ~0.23 blocks during
     * which a cow's muzzle has crossed but the collision-box booking has not yet fired.
     */
    public static boolean straddlesForDraw(Entity entity, Portal face) {
        AABB box = sweptBox(entity);
        if (!face.getBoundingBox().inflate(0.5).intersects(box)) {
            return false;
        }
        Vec3 n = face.getNormal();
        Vec3 o = face.getOriginPos();
        double m = drawMargin();
        return spanMin(box, n, o) - m < -EPS && spanMax(box, n, o) + m > EPS;
    }

    /**
     * ★ XDIM GHOST (2026-08-24) — does the entity's drawn model straddle this face's plane
     * RIGHT NOW: the per-type render envelope at the current visual position, with NO velocity
     * sweep and NO extra margin. The swept predicates above deliberately over-admit ("an
     * over-admitted projection clips to nothing"), which is sound exactly while the projection's
     * clip layer can be trusted — the cross-dim main-pass projection is the painter where it
     * cannot (the standing bleed family), and the sweep (a full tick of travel at rail speed)
     * plus margin admitted the approaching cart's image ~2 blocks before any drawn vertex had
     * crossed (live 2026-08-24: "render-booked face 107" from z=-60.28 against a plane at
     * -58.5, the user's "appears about 2-3 blocks before hitting portal" ghost). For that
     * painter, admission must coincide with actual visual emergence; the envelope is already
     * the contractual upper bound on drawn extent, so no slack is needed on top.
     */
    public static boolean straddlesForDrawInstant(Entity entity, Portal face) {
        AABB box = SeamRenderExtent.envelope(entity);
        if (!face.getBoundingBox().inflate(0.5).intersects(box)) {
            return false;
        }
        Vec3 n = face.getNormal();
        Vec3 o = face.getOriginPos();
        return spanMin(box, n, o) < -EPS && spanMax(box, n, o) > EPS;
    }

    public static boolean pinnedForDraw(Entity entity, Portal face) {
        AABB box = sweptBox(entity);
        Vec3 n = face.getNormal();
        double m = drawMargin();
        // ★ ROUND 31 — THE PRECONDITION MUST CARRY THE MARGIN TOO. This gate's reach on the +n
        // side is inflate(0.5), so once the entity is more than ~0.5 blocks past the plane it
        // returns false REGARDLESS of DRAW_MODEL_MARGIN — silently clamping the effective hold to
        // ≈0.5 against the cow's 0.4875 requirement, a 0.012-block budget on the exact edge the
        // defect lives on. That is a coin flip, not a margin, and it is the "vacuous leg" shape
        // this arc has paid for repeatedly. Extended along the NORMAL ONLY, so the lateral
        // aperture gate stays exactly as round 30 shipped it.
        if (!face.getBoundingBox().inflate(0.5)
            .expandTowards(n.scale(m))
            .expandTowards(n.scale(-MAX_TRAIL)).intersects(box)) {
            return false;
        }
        Vec3 o = face.getOriginPos();
        return spanMin(box, n, o) - m < -EPS && spanMax(box, n, o) + m > -MAX_TRAIL;
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
        // ★ ROUND 37 REVERTED 2026-08-22, at the user's direction ("revert to when we had no
        // bleed and a tiny face cut"). Round 37 widened this LIFETIME predicate from the raw
        // post-tick collision box to pinnedForDraw (swept box + per-entity render envelope).
        //
        // It was never validated. The one lap that appeared to confirm it was later PROVEN to be
        // the per-entity tint MASKING the artifact: round 41's neutral mode runs the identical
        // pipeline with the tint alpha at 0 — same shader source, same program, same snapshots,
        // same clip planes, one float different — and the cut came straight back. A fragment
        // colour blend cannot move geometry, so the "fix" was a false negative in the instrument.
        //
        // Unproven AND load-bearing on physics: portalCollisions is the substrate collision
        // handling consumes, and round 30 deliberately kept it on the post-tick box. An unvalidated
        // widening of it does not ride along. The DISABLE_SEAM_KEEP_MODEL_EXTENT lever and its
        // documentation are retained so the experiment can be re-run deliberately, against a
        // measurement that cannot be masked (RenderDoc pixel history).
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
