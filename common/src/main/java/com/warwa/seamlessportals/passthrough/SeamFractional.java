package com.warwa.seamlessportals.passthrough;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

/**
 * THE FRACTIONAL SEAM MODEL — the single predicate every arm of the model gates on, plus the
 * geometry of "which half of this cell does this dimension own".
 *
 * <p>Spec: {@code migration/FRACTIONAL_DESIGN.md} (user decisions 2026-08-02). Sequencing decision
 * C is GATE → STORAGE → COLLISION → render flip LAST, so this class exists BEFORE the model does:
 * it is what {@code rsSeamCollisionGate} asks so the gate can be lever-aware and state which truth
 * it is asserting from its very first run.
 *
 * <h2>Why {@link #CUT_IMPLEMENTED} exists</h2>
 *
 * <p>A gate built before its fix has nothing to invert against. The two dishonest ways out are a
 * hardcoded expectation (which the house rule calls "actively wrong in whichever configuration it
 * was not written for") and a gate that quietly passes in every configuration (the vacuous-pass
 * family that has bitten this project five times — see the HAZARDS list). The honest way out is a
 * capability flag the gate can READ and REPORT: today {@code active()} is false in both lever
 * positions, the gate asserts the whole-cube truth, and its log line says which branch ran. When
 * front 3 lands, this flag flips and the SAME leg asserts the cut — that is what makes it a proof
 * rather than a hope.
 *
 * <p><b>Do not flip {@code CUT_IMPLEMENTED} until the shape override is actually wired</b>, or the
 * suite goes red for the right reason at the wrong time.
 */
public final class SeamFractional {

    private SeamFractional() {}

    /**
     * FRONT-3 CAPABILITY FLAG — false until the per-position shape override is wired.
     *
     * <p>This is not a lever and must never be exposed as one: it says what the BUILD can do, not
     * what the user asked for. {@link AperturePassthroughLever#DISABLE_SEAM_FRACTIONAL} is the
     * lever, and it is consulted independently below so that flipping this flag immediately makes
     * the master lever meaningful in both directions.
     */
    private static final boolean CUT_IMPLEMENTED = false;

    /**
     * Whether a mirror-admitted seam cell's block is genuinely divided by the portal plane.
     *
     * <p>Every arm of the model consults exactly this, so there is one place to A/B and one place
     * the gate can read. Today: always false — {@link #CUT_IMPLEMENTED} has not been flipped.
     */
    public static boolean active() {
        return CUT_IMPLEMENTED && !AperturePassthroughLever.DISABLE_SEAM_FRACTIONAL;
    }

    /** Whether tier (i) — movement, raytracing, block picking — reads the cut. */
    public static boolean collisionActive() {
        return active() && !AperturePassthroughLever.DISABLE_SEAM_FRACTIONAL_COLLISION;
    }

    /** Whether tier (ii) — support, redstone conduction, suffocation — reads the cut. */
    public static boolean supportActive() {
        return active() && !AperturePassthroughLever.DISABLE_SEAM_FRACTIONAL_SUPPORT;
    }

    /**
     * Human-readable state for a gate's log line, so a run that asserted the whole-cube branch says
     * so out loud instead of looking like a pass of the fractional branch.
     */
    public static String describe() {
        if (!CUT_IMPLEMENTED) {
            return "NOT-BUILT (front 3 pending; asserting the WHOLE-CUBE truth, lever irrelevant)";
        }
        if (AperturePassthroughLever.DISABLE_SEAM_FRACTIONAL) {
            return "DISABLED by -PdisableSeamFractional (asserting the WHOLE-CUBE truth)";
        }
        return "ACTIVE collision=" + collisionActive() + " support=" + supportActive();
    }

    /**
     * ★ THE GEOMETRY, defined once. The plane's offset INSIDE the cell along the binding's axis:
     * the plane's coordinate along {@code srcFacing.getAxis()} minus the cell's lower-corner
     * coordinate on that axis. A value in {@code (0, 1)}.
     *
     * <p>⚠ THE SHIPPED CLIP DOES NOT COMPUTE THIS — it hardcodes the cell centre
     * ({@code SeamClipRenderer.java:354-356}, {@code 2 * blockCoord + 1}), which is why a portal
     * plane at {@code blockCoord + 0.3} is admitted by the mirror policy, binned COINCIDENT by
     * {@link SeamMap#phaseOf} (whose window is the whole of {@code (0.25, 0.75)}) and then cut
     * 0.2 blocks away from where it actually is, with no assertion anywhere catching it. Closing
     * that silent mis-cut is what user decision A ("genuine arbitrary fraction") buys; see
     * {@code FRACTIONAL_DESIGN.md} §1.1.
     *
     * <p>Until {@link SeamRegistry.SeamBinding} carries the real offset (front 2), this returns the
     * cell centre — i.e. today's behaviour, stated explicitly rather than assumed.
     */
    public static double planeOffset(SeamRegistry.SeamBinding binding, BlockPos cell) {
        return 0.5;
    }

    /**
     * ★ THE REAL PLANE OFFSET, computed from the portal instead of assumed.
     *
     * <p><b>Derivation.</b> {@code Portal.getDistanceToPlane} returns the signed distance
     * {@code d = (p − planePoint)·n}, so the projection of {@code p} onto the plane is
     * {@code p − d·n}. Take {@code p} = the cell centre and read the component along the plane's own
     * axis; because the normal is a signed unit axis, {@code nA = ±1}:
     * <pre>{@code planeCoord = (cell + 0.5) − d·nA   ⇒   offset = 0.5 − d·nA}</pre>
     * Worked both ways: a plane at {@code cell+0.3} with {@code n = +A} gives {@code d = +0.2} and
     * {@code offset = 0.5 − 0.2 = 0.3}; with {@code n = −A} it gives {@code d = −0.2} and
     * {@code offset = 0.5 − (−0.2)(−1) = 0.3}. Same answer either orientation, which is the point.
     */
    public static double planeOffsetOf(qouteall.imm_ptl.core.portal.Portal portal, BlockPos cell) {
        Direction.Axis axis = axisOf(portal);
        net.minecraft.world.phys.Vec3 n = portal.getNormal();
        double nA = axis.choose(n.x, n.y, n.z);
        double d = portal.getDistanceToPlane(net.minecraft.world.phys.Vec3.atCenterOf(cell));
        return 0.5 - d * nA;
    }

    /** The portal's plane axis — the axis its (signed-unit) normal lies along. */
    public static Direction.Axis axisOf(qouteall.imm_ptl.core.portal.Portal portal) {
        net.minecraft.world.phys.Vec3 n = portal.getNormal();
        return Direction.getApproximateNearest(n.x, n.y, n.z).getAxis();
    }

    /**
     * The thickness of the part THIS dimension owns, for a cell cut at {@code planeOffset}.
     *
     * <p>The kept part lies on the {@code facing} side — the side the portal's viewers and its
     * aperture cell are on. So a POSITIVE-facing binding keeps the upper part of the axis span
     * ({@code [offset, 1]}) and a NEGATIVE-facing one keeps the lower part ({@code [0, offset]}).
     */
    public static double keptThickness(Direction srcFacing, double planeOffset) {
        return srcFacing.getAxisDirection() == Direction.AxisDirection.POSITIVE
            ? 1.0 - planeOffset
            : planeOffset;
    }

    /** What crosses into the far dimension: the complement of what this side keeps. */
    public static double crossingThickness(Direction srcFacing, double planeOffset) {
        return 1.0 - keptThickness(srcFacing, planeOffset);
    }

    // =============================================================================================
    // ★ THE FRAGMENT DECOMPOSITION — FRACTIONAL_DESIGN.md §2a
    // =============================================================================================

    /**
     * One piece of a divided block: the occupied interval {@code [lo, hi]} within {@code cell},
     * measured along the seam axis from the cell's lower corner. Both bounds lie in {@code [0, 1]}.
     */
    public record Fragment(BlockPos cell, double lo, double hi) {
        public double length() {
            return hi - lo;
        }
    }

    /**
     * ★ Lay {@code crossThickness} of material into the destination, starting AT the destination
     * plane and running away from the destination's own kept side.
     *
     * <p><b>The user's worked example</b> ({@code FRACTIONAL_DESIGN.md} §2a), which
     * {@code rsFragmentArithmeticGate} pins exactly: a source plane at 0.3 keeping {@code [0, 0.3]}
     * sends 0.7 across; the destination plane sits at 0.79 in D0 keeping {@code [0, 0.79]}, so the
     * material runs POSITIVE and occupies {@code D0[0.79, 1.0]} (0.21) then {@code D1[0.0, 0.49]}
     * (0.49). Total 0.7 — conserved.
     *
     * <p><b>★ AT MOST TWO FRAGMENTS, and it is a proof rather than an observation.</b> The remainder
     * in the first cell is {@code r ∈ (0, 1)} and the crossing thickness is {@code t < 1}, so the
     * overflow {@code t − r} is strictly less than 1 and cannot reach a third cell. The model is
     * 1-to-≤2, not 1-to-N — which is why this returns a two-element list and not a stream.
     *
     * @param destFacing the DESTINATION portal's facing — the side the far world keeps
     * @param destPlaneOffset the destination plane's offset inside {@code destCell}, in {@code (0,1)}
     */
    public static java.util.List<Fragment> decomposeDestination(
        BlockPos destCell, Direction destFacing, double destPlaneOffset, double crossThickness
    ) {
        if (crossThickness <= EPS) {
            return java.util.List.of();
        }
        Direction.Axis axis = destFacing.getAxis();
        boolean positiveFacing = destFacing.getAxisDirection() == Direction.AxisDirection.POSITIVE;

        // The far world keeps the side its facing points to, so the material fills the complement:
        // a POSITIVE-facing destination keeps [p, 1] and the material runs DOWN from p; a
        // NEGATIVE-facing one keeps [0, p] and it runs UP from p.
        double remainder = positiveFacing ? destPlaneOffset : 1.0 - destPlaneOffset;
        double first = Math.min(crossThickness, remainder);
        double overflow = crossThickness - first;

        java.util.List<Fragment> out = new java.util.ArrayList<>(2);
        if (first > EPS) {
            out.add(positiveFacing
                ? new Fragment(destCell, destPlaneOffset - first, destPlaneOffset)
                : new Fragment(destCell, destPlaneOffset, destPlaneOffset + first));
        }
        if (overflow > EPS) {
            // One cell further along the direction the material is running.
            BlockPos next = destCell.relative(Direction.get(
                positiveFacing ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE,
                axis));
            out.add(positiveFacing
                ? new Fragment(next, 1.0 - overflow, 1.0)
                : new Fragment(next, 0.0, overflow));
        }
        return out;
    }

    /**
     * ★ The bridge from a real binding to real fragments — what a block in {@code srcCell} leaves
     * behind on this side, and where its remainder lands on the far side.
     *
     * <p>Returns an empty list when the binding cannot answer: no cut, no destination cell, or no
     * reverse portal to say where the far plane sits. Callers must treat empty as "this seam cannot
     * be divided", NOT as "nothing crosses" — the two are different and conflating them is how a
     * block would silently duplicate instead of dividing.
     */
    public static java.util.List<Fragment> destinationFragments(SeamRegistry.SeamBinding binding) {
        SeamRegistry.SeamCut cut = binding.cut();
        if (cut == null || !cut.hasDestination() || binding.destPos() == null) {
            return java.util.List.of();
        }
        double cross = crossingThickness(binding.srcFacing(), cut.srcPlaneOffset());
        return decomposeDestination(
            binding.destPos(), cut.destFacing(), cut.destPlaneOffset(), cross);
    }

    /** What THIS side keeps of a block in a seam cell, or NaN when the binding carries no cut. */
    public static double keptThickness(SeamRegistry.SeamBinding binding) {
        SeamRegistry.SeamCut cut = binding.cut();
        return cut == null ? Double.NaN : keptThickness(binding.srcFacing(), cut.srcPlaneOffset());
    }

    /** Total material in a fragment list — the conservation quantity the gate asserts on. */
    public static double totalLength(java.util.List<Fragment> fragments) {
        double sum = 0.0;
        for (Fragment f : fragments) {
            sum += f.length();
        }
        return sum;
    }

    private static final double EPS = 1.0e-9;

    /**
     * Whether this cell is one the model would cut: it carries a mirror-admitted binding whose
     * plane passes through the cell's interior.
     *
     * <p>Query-only bindings are excluded on purpose and for the same reason the clip excludes them
     * ({@code SEAM_CLIP_DESIGN.md} §1): an exact-only-declined portal has NO mirrored counterpart
     * anywhere, so cutting it saws a block in half with no supplier of the other half.
     */
    public static boolean cuts(BlockGetter level, BlockPos cell) {
        if (!(level instanceof net.minecraft.world.level.Level lvl)) {
            return false;
        }
        SeamRegistry.SeamCell seam = SeamRegistry.lookup(lvl, cell);
        if (seam == null) {
            return false;
        }
        for (SeamRegistry.SeamBinding b : seam.bindings()) {
            if (b != null && b.isMirrorable() && b.phase() == SeamMap.SeamPhase.COINCIDENT) {
                return true;
            }
        }
        return false;
    }
}
