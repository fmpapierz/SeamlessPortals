package com.warwa.seamlessportals.passthrough;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.List;

/**
 * THE SEAM MAPPING PRIMITIVE — sub-feature (a) of the redstone/rail/minecart passthrough, and the
 * shared foundation (b) rail connection, (c) redstone signal bridging and (d) minecart traversal all
 * consume. Spec: {@code migration/REDSTONE_A_SPEC.md} §2. Decisions: {@code REDSTONE_RECON.md} §0.
 *
 * <p><b>The problem it solves.</b> A portal plane sits at the MIDDLE of the aperture blocks, not on a
 * block boundary ({@code BlockPortalShape.java:360-364} via {@code IntBox.getCenterVec} =
 * {@code (l+h+1)/2}). So no block face ever coincides with the plane, and a block built through a
 * portal is ALWAYS bisected by it. Given a cell on one side, this class answers: which cell on the
 * other side is its counterpart?
 *
 * <p><b>The rule.</b> Take any point in the cell's column, project it onto the plane, then step
 * {@link #STEP} along the normal (source side) or along the content direction (destination side) and
 * take the containing block. Stepping less than half a block from the plane lands in whichever cell
 * has the GREATEST OVERLAP with the half-block adjoining the plane — which is the user's pinned
 * phase-offset rule (REDSTONE_RECON.md §0.7). 3-D overlap factorises per axis, so an argmax taken
 * componentwise is the global argmax.
 *
 * <p><b>Why it is phase-agnostic.</b> Nothing here assumes the plane is mid-block. For an obsidian
 * pair (both planes mid-block) the source and destination aperture cells come out COINCIDENT; for a
 * wand/custom portal whose plane sits on a block boundary they come out offset by half a block. Both
 * fall out of the same arithmetic, which is what the user asked for ("general sub-block phase from
 * day one").
 *
 * <p><b>The involution property.</b> A bi-way portal pair {@code P}/{@code Q} satisfies
 * {@code n_Q = contentDirection(P)} — that is exactly the pairing test IP itself uses
 * ({@code BreakablePortalEntity.findReversePortals}: {@code getContentDirection().dot(getNormal()) > 0.6}).
 * Therefore {@code mirrorCell_P(x) == seamCell_Q(transformPoint_P(x))} and
 * {@code mirror_Q ∘ mirror_P == identity} in every phase. Both adversarial verifiers re-derived this
 * independently and neither found a defect in it.
 *
 * <p><b>Grid gate.</b> Only portals that map the block lattice onto itself can be mirrored at all —
 * see {@link #isMirrorable}. A scaled portal, an off-axis normal, or a rotation that is not a
 * multiple of 90° about a coordinate axis has no well-defined cell counterpart, and such portals are
 * bound query-only rather than mirrored.
 *
 * <p>Pure arithmetic: no world access, no side-effects, no state. Safe from any thread.
 */
public final class SeamMap {

    private SeamMap() {}

    /**
     * How far to step off the plane when choosing the adjoining cell. Any value in (0, 0.5) picks the
     * same cell — it is the midpoint of the half-block adjoining the plane that decides greatest
     * overlap, and 0.25 is that midpoint for a full unit cell.
     */
    public static final double STEP = 0.25;

    /** Minimum fraction of a unit face a candidate column must cover to be bound. See §2.3. */
    public static final double MIN_OVERLAP = 0.5;

    private static final double EPS = 1.0e-9;

    // =============================================================================================
    // CORE ARITHMETIC
    // =============================================================================================

    /** Project a point onto the portal plane along the normal. {@code Portal.java:1157-1159}. */
    public static Vec3 onPlane(Portal portal, Vec3 anyPointInColumn) {
        Vec3 normal = portal.getNormal();
        return anyPointInColumn.subtract(normal.scale(portal.getDistanceToPlane(anyPointInColumn)));
    }

    /**
     * The SOURCE-side cell adjoining the plane in the given column — the aperture cell on this side.
     * Steps along the normal, which points out of the portal's front face.
     */
    public static BlockPos seamCell(Portal portal, Vec3 pointOnPlane) {
        return BlockPos.containing(pointOnPlane.add(portal.getNormal().scale(STEP)));
    }

    /**
     * The DESTINATION-side counterpart of {@link #seamCell} — the cell across the seam. Transforms
     * the plane point through the portal, then steps along the content direction, which is the
     * destination-side outward normal ({@code Portal.java:537-542}).
     */
    public static BlockPos mirrorCell(Portal portal, Vec3 pointOnPlane) {
        return BlockPos.containing(
            portal.transformPoint(pointOnPlane).add(portal.getContentDirection().scale(STEP)));
    }

    // =============================================================================================
    // COLUMN ENUMERATION — which cells the aperture actually binds
    // =============================================================================================

    /**
     * One plane point per bound aperture column, each at the CENTROID of that column's overlap with
     * the aperture rectangle.
     *
     * <p><b>Why centroid-of-overlap and not cell-centre</b> (adversarial verifier finding F7, adopted):
     * a naive lattice sample binds a cell that the aperture clips by a sliver on exactly the same
     * footing as one it covers fully. The user's rule is an OVERLAP rule, and it has to apply on all
     * three axes — not just along the normal. So a candidate column is bound only when its face
     * overlaps the aperture rectangle by at least {@link #MIN_OVERLAP} of a unit face, and the point
     * emitted is the centroid of that overlap region, which is what makes {@link #seamCell} and
     * {@link #mirrorCell} select the greatest-overlap cell on the in-plane axes too.
     *
     * <p>Grid-aligned portals (every obsidian frame) reduce to the obvious answer: each aperture cell
     * overlaps exactly one column by exactly 1.0, and the centroid is the cell centre.
     */
    public static List<Vec3> enumerateColumns(Portal portal) {
        List<Vec3> out = new ArrayList<>();

        double halfW = portal.getWidth() / 2.0;
        double halfH = portal.getHeight() / 2.0;

        // The aperture rectangle in the portal's own (axisW, axisH) basis, expressed in world units
        // offset from the portal centre. To find which integer cells it touches we need the
        // rectangle's extent along each in-plane axis in WORLD lattice coordinates. For a
        // grid-aligned portal each in-plane axis is a signed unit axis, so the rectangle's world
        // extent along that axis is [-half, +half] about the centre's component.
        Vec3 centre = portal.getOriginPos();
        Vec3 axisW = portal.getAxisW();
        Vec3 axisH = portal.getAxisH();

        // Where the aperture centre sits along each in-plane axis in WORLD lattice coordinates.
        // Without this the overlap test silently assumes the portal is centred on a lattice point,
        // which is exactly the phase assumption the user forbade.
        double phaseU = phaseAlong(centre, axisW);
        double phaseV = phaseAlong(centre, axisH);

        // Loop bounds MUST be in the same space as the overlap test — world lattice, not portal-local.
        // (Computing them from the local extent alone makes the two spaces disjoint whenever the
        // portal is not at the origin, and every column then fails the overlap test.)
        int uLo = (int) Math.floor(-halfW + phaseU - EPS);
        int uHi = (int) Math.ceil(halfW + phaseU + EPS);
        int vLo = (int) Math.floor(-halfH + phaseV - EPS);
        int vHi = (int) Math.ceil(halfH + phaseV + EPS);

        for (int i = uLo; i <= uHi; i++) {
            // Cell i spans [i, i+1) in lattice space; the aperture spans [-halfW, +halfW) shifted by
            // the centre's phase.
            double ovU = overlapLength(-halfW + phaseU, halfW + phaseU, i, i + 1);
            if (ovU <= 0) {
                continue;
            }
            for (int j = vLo; j <= vHi; j++) {
                double ovV = overlapLength(-halfH + phaseV, halfH + phaseV, j, j + 1);
                if (ovV <= 0) {
                    continue;
                }
                if (ovU * ovV + EPS < MIN_OVERLAP) {
                    continue;   // a sliver — not this column's cell
                }
                // Centroid of the overlap region, back in portal-local (u, v) coordinates.
                double cu = midOfOverlap(-halfW + phaseU, halfW + phaseU, i, i + 1) - phaseU;
                double cv = midOfOverlap(-halfH + phaseV, halfH + phaseV, j, j + 1) - phaseV;
                out.add(portal.getPointInPlane(cu, cv));
            }
        }
        return out;
    }

    /** The component of a world position along a signed unit in-plane axis, as a lattice offset. */
    private static double phaseAlong(Vec3 worldPos, Vec3 unitAxis) {
        return worldPos.x * unitAxis.x + worldPos.y * unitAxis.y + worldPos.z * unitAxis.z;
    }

    private static double overlapLength(double aLo, double aHi, double bLo, double bHi) {
        return Math.min(aHi, bHi) - Math.max(aLo, bLo);
    }

    private static double midOfOverlap(double aLo, double aHi, double bLo, double bHi) {
        return (Math.min(aHi, bHi) + Math.max(aLo, bLo)) / 2.0;
    }

    // =============================================================================================
    // THE GRID GATE
    // =============================================================================================

    /**
     * Whether this portal maps the block lattice onto itself, and can therefore have a well-defined
     * cell counterpart at all. A portal failing this is still USEFUL — (b)/(c)/(d) may query across
     * it — but nothing may be mirrored through it, because there is no cell to mirror into.
     *
     * <p>Rejects: any scaling other than 1 ({@code Portal.java:1530}), an off-axis normal, and any
     * rotation that does not carry each coordinate axis to a signed coordinate axis.
     */
    public static boolean isMirrorable(Portal portal) {
        if (Math.abs(portal.getScaling() - 1.0) > 1.0e-9) {
            return false;
        }
        if (!isSignedUnitAxis(portal.getNormal())) {
            return false;
        }
        DQuaternion rotation = portal.getRotation();
        if (rotation != null) {
            if (!isSignedUnitAxis(portal.transformLocalVecNonScale(new Vec3(1, 0, 0)))
                || !isSignedUnitAxis(portal.transformLocalVecNonScale(new Vec3(0, 1, 0)))
                || !isSignedUnitAxis(portal.transformLocalVecNonScale(new Vec3(0, 0, 1)))) {
                return false;
            }
        }
        // THE TRANSLATION TERM — everything above tests only the LINEAR part.
        // transformPoint is AFFINE: p -> R*p + (destPos - R*originPos) (Portal.java:508-512). The
        // checks above constrain R alone and never touch getOriginPos()/getDestPos(), so a portal
        // pair with a half-block offset between their planes passes them all while mapping a source
        // cell onto a span STRADDLING TWO destination cells — a geometry with no well-defined mirror
        // target at all. An earlier comment here claimed a null rotation was "trivially
        // lattice-preserving"; that was false, because identity rotation with a non-integral
        // translation still shifts the lattice off itself.
        return latticeAligned(portal, BlockPos.containing(portal.getOriginPos()));
    }

    /**
     * The two seam TOPOLOGIES, decided by where the plane falls relative to the aperture cell.
     *
     * <p>This distinction is the whole of sub-feature (b), and it is NOT cosmetic:
     * <ul>
     *   <li>{@link #COINCIDENT} — the plane BISECTS the aperture cell (obsidian frames, whose planes
     *       are always mid-block). The source and destination aperture cells occupy the same span:
     *       one physical slot seen from two sides, which (a) keeps mirrored. A block here straddles
     *       the plane and each side draws its own half. The track's next cell through the plane is
     *       therefore the one BEYOND the destination aperture cell, not the destination cell itself —
     *       that cell is already this cell.</li>
     *   <li>{@link #DISJOINT} — the plane lies ON the cell boundary (custom/wand portals). Nothing
     *       straddles: source and destination cells are DISTINCT, face-to-face neighbours in two
     *       dimensions, and are NOT mirrored. Here the destination aperture cell IS the neighbour.</li>
     * </ul>
     *
     * <p>Getting this backwards silently produces an off-by-one track: connecting to the wrong cell
     * in topology A, or skipping a real cell in topology B.
     */
    public enum SeamPhase { COINCIDENT, DISJOINT }

    /**
     * Which topology this cell sits in, derived from the plane's position rather than assumed from
     * the portal kind.
     *
     * <p>The discriminator is the distance from the plane to the CELL CENTRE: the plane bisects the
     * cell (distance ≈ 0) or lies on its face (distance ≈ 0.5). Nothing here inspects whether the
     * portal came from obsidian or a wand — a custom portal that happens to be mid-block is
     * COINCIDENT and behaves as one, which is the correct answer.
     */
    public static SeamPhase phaseOf(Portal portal, BlockPos cell) {
        double d = Math.abs(portal.getDistanceToPlane(Vec3.atCenterOf(cell)));
        // Mid-block ⇒ ~0. Boundary ⇒ ~0.5. Anything between is a phase this build does not model;
        // treat it as DISJOINT, the conservative reading (distinct cells, no mirroring).
        return d < 0.25 ? SeamPhase.COINCIDENT : SeamPhase.DISJOINT;
    }

    /**
     * Whether this portal maps the BLOCK LATTICE onto itself — the test {@link #isMirrorable}'s other
     * clauses do NOT make.
     *
     * <p>Those clauses constrain only the LINEAR part of the transform: scale 1, a signed-unit normal,
     * and every axis carried to a signed axis. A signed axis permutation already carries ℤ³ onto ℤ³,
     * so the whole affine map preserves the lattice <b>iff its translation is integral</b> — and
     * because the linear part is a permutation, ONE lattice corner settles it for all of ℤ³. This is a
     * proof, not an enumeration of degenerate cases.
     *
     * <p>Worked: planes at (Z0+0.5, Z1+0.5) → integral ✔; (Z0, Z1) → integral ✔;
     * (Z0+0.3, Z1+0.7) → integral ✔; <b>(Z0, Z1+0.5) → NOT integral ✘</b>, correctly refused —
     * that pair's image of a cell straddles two cells, so no mirror target exists.
     *
     * <p>Tolerance is 1e-4, not 1e-6: {@code transformPoint} runs through {@link DQuaternion}, and a
     * rotated portal accumulates more error than 1e-6 allows.
     */
    public static boolean latticeAligned(Portal portal, BlockPos anyLocalCell) {
        Vec3 image = portal.transformPoint(Vec3.atLowerCornerOf(anyLocalCell));
        return nearInt(image.x) && nearInt(image.y) && nearInt(image.z);
    }

    private static boolean nearInt(double v) {
        return Math.abs(v - Math.round(v)) < 1.0e-4;
    }

    /** True when the vector is (±1,0,0), (0,±1,0) or (0,0,±1) to within rounding. */
    public static boolean isSignedUnitAxis(Vec3 v) {
        double ax = Math.abs(v.x), ay = Math.abs(v.y), az = Math.abs(v.z);
        return (nearly(ax, 1) && nearly(ay, 0) && nearly(az, 0))
            || (nearly(ax, 0) && nearly(ay, 1) && nearly(az, 0))
            || (nearly(ax, 0) && nearly(ay, 0) && nearly(az, 1));
    }

    private static boolean nearly(double a, double b) {
        return Math.abs(a - b) < 1.0e-6;
    }

    // =============================================================================================
    // BLOCK-STATE ROTATION
    // =============================================================================================

    /**
     * The {@link Rotation} a block state must undergo when mirrored through this portal, or
     * {@code null} when the portal's rotation is not a yaw multiple of 90° (in which case block
     * states cannot be carried faithfully and the caller must refuse to mirror).
     *
     * <p><b>Derived, not asserted.</b> The spec flagged this as its single most likely arithmetic
     * error and made it the step-1 falsifier for exactly that reason, so it is computed from the
     * portal's own transform rather than from a hand-written table: rotate world +X through the
     * portal and read off which signed axis it lands on. Vanilla {@link Rotation} only expresses
     * rotation about the Y axis, so a transform that tilts +X out of the XZ plane returns
     * {@code null}.
     */
    @Nullable
    public static Rotation blockRotationOf(Portal portal) {
        DQuaternion rotation = portal.getRotation();
        if (rotation == null) {
            return Rotation.NONE;
        }
        Vec3 mappedX = portal.transformLocalVecNonScale(new Vec3(1, 0, 0));
        if (!isSignedUnitAxis(mappedX) || !nearly(Math.abs(mappedX.y), 0)) {
            return null;    // tilts out of the horizontal plane — not expressible as a Rotation
        }
        if (nearly(mappedX.x, 1)) {
            return Rotation.NONE;
        }
        if (nearly(mappedX.x, -1)) {
            return Rotation.CLOCKWISE_180;
        }
        // +X maps onto ±Z. Vanilla CLOCKWISE_90 carries +X to +Z; verify against Direction itself so
        // the convention is read out of the game rather than assumed.
        // 26.2 renamed Direction.getNearest -> getApproximateNearest (REF Direction.java:303).
        Direction mapped = Direction.getApproximateNearest(mappedX.x, mappedX.y, mappedX.z);
        Direction underCw90 = Rotation.CLOCKWISE_90.rotate(Direction.EAST);
        return mapped == underCw90 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
    }
}
