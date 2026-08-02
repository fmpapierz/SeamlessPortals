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
     * The thickness of the half THIS dimension owns, for a cell cut at {@code planeOffset}.
     *
     * <p>The kept half lies on the {@code srcFacing} side — the side the portal's viewers and its
     * aperture cell are on. So a POSITIVE-facing binding keeps the upper part of the axis span and
     * a NEGATIVE-facing one keeps the lower part.
     */
    public static double keptThickness(Direction srcFacing, double planeOffset) {
        return srcFacing.getAxisDirection() == Direction.AxisDirection.POSITIVE
            ? 1.0 - planeOffset
            : planeOffset;
    }

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
