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
    private static final boolean CUT_IMPLEMENTED = true;

    /**
     * ★ Cell-local cut planes for the OPEN-HALF outline shapes built during the outline extract,
     * keyed by the exact {@code VoxelShape} instance handed to the renderer.
     * {@code ShapeOutlineSeamEdgeMixin} consults this at the line emitter and drops every edge
     * lying IN the plane — a VoxelShape cannot express "a box with no lid", so the omission
     * happens at draw time. Weak keys: the shapes are per-extract temporaries and the entries
     * die with them; render-thread confined like the extract itself.
     */
    public static final java.util.Map<net.minecraft.world.phys.shapes.VoxelShape, OutlineCutPlane>
        OUTLINE_CUT_PLANES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** A cut plane in CELL-LOCAL shape coordinates: the axis and the offset in [0,1]. */
    public record OutlineCutPlane(Direction.Axis axis, double offset) {}

    /**
     * ★ REENTRANCY GUARD (round 20's live crash, StackOverflowError): fetching the SECONDARY's
     * shape inside {@link #outlineShape} calls {@code BlockState.getShape}, and in 26.2 the 2-arg
     * overload DELEGATES to the intercepted 3-arg one ({@code BlockBehaviour:1069→1073}) — so the
     * hook re-entered itself for the same cell, whose secondary is still there, forever. While
     * this flag is up, the hook answers vanilla; the nested fetch gets the secondary's plain model
     * shape, which is exactly what the half-box clip wants.
     */
    private static final ThreadLocal<Boolean> OUTLINE_REENTRY =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** The nested-safe secondary shape fetch — see {@link #OUTLINE_REENTRY}. */
    private static net.minecraft.world.phys.shapes.VoxelShape secondaryShapeOf(
        SeamOccupancy.Secondary sec, BlockGetter level, BlockPos pos
    ) {
        OUTLINE_REENTRY.set(Boolean.TRUE);
        try {
            return sec.state().getShape(level, pos);
        } finally {
            OUTLINE_REENTRY.set(Boolean.FALSE);
        }
    }

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

    // =============================================================================================
    // ★ TIER (i) — THE SHAPE THIS SIDE KEEPS. FRACTIONAL_DESIGN.md §4/§5.
    // =============================================================================================

    /**
     * ★ The shape a seam cell actually presents to THIS dimension: {@code original} clipped to the
     * part on this side of the plane. Returns {@code null} when the cell is not cut, which callers
     * must pass straight through — a null here means "ordinary block", not "empty".
     *
     * <p><b>Why this is the ONLY hook the soul-sand rule needs.</b> User decision (2026-08-02) is
     * that collision follows the cut while support, redstone conduction and suffocation report
     * WHOLE. Support and conduction read the per-blockstate {@code Cache}, which is position-blind
     * by construction ({@code BlockBehaviour.java:901-925}) — so they ALREADY answer "whole" with no
     * intervention at all. The cache-blindness the blast-radius map called a trap is, under this
     * rule, exactly the behaviour we want, and tier (ii) needs no interception. Only the
     * position-aware tier (i) — collision, outline, picking — has to be told about the cut.
     *
     * <p>Suffocation is the one deliberate exception and it goes the other way; see
     * {@link #suppressesSuffocation}.
     */
    @org.jetbrains.annotations.Nullable
    public static net.minecraft.world.phys.shapes.VoxelShape keptShape(
        BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.VoxelShape original
    ) {
        boolean probe = AperturePassthroughLever.SEAM_FRACTIONAL_PROBE;
        if (probe) {
            SeamFractionalProbe.onCall();
            SeamFractionalProbe.tickSummary();
        }
        if (!collisionActive() || original.isEmpty()) {
            return null;
        }
        // ★ PROBE THE DECISION, NOT JUST THE OUTCOME. When the answer is "no cut" the REASON is the
        // whole diagnostic — "did nothing" and "never ran" must not look the same in a live log.
        SeamRegistry.SeamCell seamForProbe = probe && level instanceof net.minecraft.world.level.Level lp
            ? SeamRegistry.lookup(lp, pos) : null;
        SeamRegistry.SeamBinding binding = cuttingBinding(level, pos);
        if (binding == null) {
            if (seamForProbe != null) {
                StringBuilder why = new StringBuilder();
                for (SeamRegistry.SeamBinding b : seamForProbe.bindings()) {
                    if (b == null) {
                        continue;
                    }
                    why.append("[facing=").append(b.srcFacing())
                        .append(" mirrorable=").append(b.isMirrorable())
                        .append(" phase=").append(b.phase())
                        .append(" cut=").append(b.cut() == null ? "NULL" : "present")
                        .append(" destPos=").append(b.destPos()).append("] ");
                }
                SeamFractionalProbe.onSeamCell(pos, "NO CUT",
                    "is a seam cell but no binding qualified. bindings: " + why);
            } else if (probe && level instanceof net.minecraft.world.level.Level) {
                // Not a seam cell at all — counted, never logged per-call (volume).
                return null;
            }
            return null;
        }
        SeamRegistry.SeamCut cut = binding.cut();
        double off = cut.srcPlaneOffset();
        Direction facing = binding.srcFacing();
        Direction.Axis axis = facing.getAxis();

        // ★ THE OWNER HALF COMES FROM THE OBJECT, NOT FROM THE BINDING — FRACTIONAL_DESIGN.md §2a.0.
        // An obsidian portal is BI-FACED, so this cell carries two bindings with opposite facings and
        // asking one of them "which side is ours" is meaningless. The first build did exactly that
        // (it took whichever binding was stored first) and the live round caught it at once: a block
        // placed from the north kept the SOUTH half. Occupancy is recorded at placement from the
        // crosshair hit point and is the only thing that can answer.
        byte owned = level instanceof net.minecraft.world.level.Level lvOwn
            ? SeamOccupancy.occupancyOf(lvOwn, pos) : 0;
        if (owned == 0) {
            // No recorded owner — a pre-existing block, or one placed before occupancy was tracked.
            // Leaving it WHOLE is the safe answer: it is what the player already sees, and guessing
            // a half is how the far-half-swap defect looked.
            if (probe) {
                SeamFractionalProbe.onSeamCell(pos, "NO CUT",
                    "no recorded owner half (occupancy=0) — pre-existing block, left whole rather"
                        + " than guessing a side");
            }
            return null;
        }
        if (owned == SeamOccupancy.BOTH) {
            // Two objects meeting at the plane: materially a whole cube again.
            if (probe) {
                SeamFractionalProbe.onSeamCell(pos, "NO CUT",
                    "BOTH halves owned — two objects meet at the plane, so the cell is whole");
            }
            return null;
        }
        boolean positive = owned == SeamOccupancy.HALF_POSITIVE;
        double kept = positive ? 1.0 - off : off;
        // Nothing to do at the degenerate ends: a whole cell stays whole, and an empty one would
        // make the block vanish rather than be cut, which is a different (and wrong) behaviour.
        if (kept >= 1.0 - EPS || kept <= EPS) {
            if (probe) {
                SeamFractionalProbe.onSeamCell(pos, "NO CUT",
                    "degenerate kept thickness " + kept + " (offset=" + off + " facing=" + facing
                        + ") — the plane is on a cell face, so nothing straddles");
            }
            return null;
        }
        if (probe) {
            SeamFractionalProbe.onSeamCell(pos, "CUT",
                "keeping " + kept + " on the " + (positive ? "POSITIVE" : "NEGATIVE") + " "
                    + axis + " side (owner-half from placement, planeOffset=" + off
                    + ", level=" + (level instanceof net.minecraft.world.level.Level lv2
                        ? (lv2.isClientSide() ? "CLIENT" : "SERVER") : "?") + ")");
            SeamFractionalProbe.onCut();
        }
        net.minecraft.world.phys.shapes.VoxelShape cutShape =
            net.minecraft.world.phys.shapes.Shapes.join(
                original, halfBox(axis, owned, off), net.minecraft.world.phys.shapes.BooleanOp.AND);
        // ★ THE SECONDARY OCCUPANT'S COLLISION, unioned in. Its base shape comes from the 2-ARG
        // getCollisionShape — the cached, position-blind accessor — DELIBERATELY: the 3-arg form is
        // the one this very hook intercepts, and calling it here would recurse. The cached whole
        // shape is exactly the right input anyway; the clip to its half happens here.
        if (level instanceof net.minecraft.world.level.Level lvl2) {
            SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(lvl2, pos);
            if (sec != null) {
                net.minecraft.world.phys.shapes.VoxelShape secShape =
                    net.minecraft.world.phys.shapes.Shapes.join(
                        sec.state().getCollisionShape(level, pos),
                        halfBox(axis, sec.half(), off),
                        net.minecraft.world.phys.shapes.BooleanOp.AND);
                cutShape = net.minecraft.world.phys.shapes.Shapes.or(cutShape, secShape);
            }
        }
        // Shapes.join handles arbitrary fractions exactly — Shapes.create falls back to
        // ArrayVoxelShape with literal coordinate lists when the bounds are not a power-of-two
        // fraction, so there is no quantization to 1/8ths or 1/16ths here.
        return cutShape;
    }

    /** The unit-cell box of one half, split at {@code off} along {@code axis}. */
    private static net.minecraft.world.phys.shapes.VoxelShape halfBox(
        Direction.Axis axis, byte half, double off
    ) {
        boolean positive = half == SeamOccupancy.HALF_POSITIVE;
        double lo = positive ? off : 0.0;
        double hi = positive ? 1.0 : off;
        return switch (axis) {
            case X -> net.minecraft.world.phys.shapes.Shapes.box(lo, 0.0, 0.0, hi, 1.0, 1.0);
            case Y -> net.minecraft.world.phys.shapes.Shapes.box(0.0, lo, 0.0, 1.0, hi, 1.0);
            case Z -> net.minecraft.world.phys.shapes.Shapes.box(0.0, 0.0, lo, 1.0, 1.0, hi);
        };
    }

    /**
     * ★ THE TARGETING RULE — an entity's OUTLINE shape for a seam cell includes ONLY the half on
     * ITS side of the plane. User non-negotiables, live round 8: no floating cut face from the
     * empty side ("there should be nothing"), and punching from the empty side must never touch the
     * original ("completely separate"). The half beyond the plane is the other dimension's
     * business, reachable only through the window.
     *
     * <p>This is viewer-dependent, and it CAN be: {@code ClipContext.Block.OUTLINE} routes through
     * the 3-arg {@code getShape} whose {@code CollisionContext} carries the picking entity
     * ({@code EntityCollisionContext.getEntity}, bytecode-verified). Vanilla built the plumbing;
     * this is the first rule in the mod to use it.
     *
     * <p>Consequences, all deliberate: from the empty side the ray passes straight through the cell
     * (no outline, no punch target — it lands on the window or terrain instead); from your own side
     * you target exactly your occupant, whichever of the two objects that is; a non-entity query
     * falls back to the collision union, which is viewer-independent physics.
     *
     * @return null = leave vanilla's shape untouched.
     */
    @org.jetbrains.annotations.Nullable
    public static net.minecraft.world.phys.shapes.VoxelShape outlineShape(
        BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.VoxelShape original,
        net.minecraft.world.phys.shapes.CollisionContext ctx
    ) {
        if (!collisionActive() || original.isEmpty()
            || !(level instanceof net.minecraft.world.level.Level lvl)) {
            return null;
        }
        if (OUTLINE_REENTRY.get()) {
            return null;   // nested secondary-shape fetch: answer vanilla, never recurse
        }
        // ★ OUTLINE DRAW ≠ TARGETING (user live round: "extra line in the outline at the seam").
        // While extractBlockOutline is capturing the DRAW shape, a seam cell reports FULL — the
        // near pass and the window pass then outline coincident whole cubes that merge into one
        // normal block box, with no cut-face rectangle at the plane. Rays never run inside the
        // extract, so the viewer-half targeting rule below is untouched.
        if (com.warwa.seamlessportals.render.SeamCounterpartOutline.extractingOutline) {
            // ★ THE OPEN HALF BOX — rounds 18/19's final geometry, viewpoint-INDEPENDENT:
            //
            // - WINDOW pass: unconditional full cube. The portal clip trims it to exactly the
            //   beyond-plane half, its own edges lie on the cell faces (never the interior
            //   plane), so through the window it contributes precisely the far half's silhouette
            //   with no line at the seam.
            // - MAIN pass: each occupant clipped to its own half, with the edges LYING IN the cut
            //   plane filtered out at the emitter (ShapeOutlineSeamEdgeMixin, keyed by this exact
            //   shape instance via OUTLINE_CUT_PLANES). From the front, the window's far-half
            //   silhouette completes it into one seamless box; from the side, the outline stops
            //   at the plane with no closing rectangle — round 19's "a sudden outline appears at
            //   the seam, cutting the block in half" was that rectangle, and round 18's earlier
            //   sight-line switch popped between whole geometries. An open half box needs
            //   neither: the same shape is correct from every angle.
            if (qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
                return null;
            }
            SeamRegistry.SeamBinding b0 = cuttingBinding(level, pos);
            byte owned0 = SeamOccupancy.occupancyOf(lvl, pos);
            if (b0 != null && b0.cut() != null
                && (owned0 == SeamOccupancy.HALF_POSITIVE
                    || owned0 == SeamOccupancy.HALF_NEGATIVE)) {
                Direction.Axis axis0 = b0.srcFacing().getAxis();
                double off0 = b0.cut().srcPlaneOffset();
                // ★ ONE OBJECT, ONE OUTLINE (user round 21: with both halves occupied, targeting
                // side A's block "also outlines the seam block on side b — this should never
                // happen"). Round 19's union outlined every occupant of the cell; vanilla
                // semantics outline the TARGETED block only, and which occupant is targeted is
                // the same crosshair-side rule everything else uses (halfFromHit on the actual
                // hit). Falls back to the primary when the current hit is not a genuine location
                // for this cell (the through-window counterpart swap synthesizes a cell-centre
                // hit; a centre point cannot pick a side honestly).
                SeamOccupancy.Secondary s0 = SeamOccupancy.secondaryOf(lvl, pos);
                byte targetHalf = owned0;
                if (net.minecraft.client.Minecraft.getInstance().hitResult
                        instanceof net.minecraft.world.phys.BlockHitResult bhr
                    && bhr.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                    && bhr.getBlockPos().equals(pos)) {
                    targetHalf = SeamOccupancy.halfFromHit(
                        bhr.getLocation(), pos, axis0, off0);
                }
                net.minecraft.world.phys.shapes.VoxelShape shape;
                if (targetHalf != owned0 && s0 != null && s0.half() == targetHalf) {
                    shape = net.minecraft.world.phys.shapes.Shapes.join(
                        secondaryShapeOf(s0, level, pos),
                        halfBox(axis0, s0.half(), off0),
                        net.minecraft.world.phys.shapes.BooleanOp.AND);
                } else {
                    shape = net.minecraft.world.phys.shapes.Shapes.join(
                        original, halfBox(axis0, owned0, off0),
                        net.minecraft.world.phys.shapes.BooleanOp.AND);
                }
                OUTLINE_CUT_PLANES.put(shape, new OutlineCutPlane(axis0, off0));
                return shape;
            }
            return null;
        }
        byte owned = SeamOccupancy.occupancyOf(lvl, pos);
        SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(lvl, pos);
        if ((owned != SeamOccupancy.HALF_POSITIVE && owned != SeamOccupancy.HALF_NEGATIVE)
            && sec == null) {
            return null;    // no owner recorded: vanilla cell, vanilla outline
        }
        SeamRegistry.SeamBinding binding = cuttingBinding(level, pos);
        if (binding == null || binding.cut() == null) {
            return null;
        }
        double off = binding.cut().srcPlaneOffset();
        Direction.Axis axis = binding.srcFacing().getAxis();
        if (!(ctx instanceof net.minecraft.world.phys.shapes.EntityCollisionContext ec)
            || ec.getEntity() == null) {
            // No viewer: fall back to the viewer-independent collision union.
            return keptShape(level, pos, original);
        }
        // ★ ONE RULE, LOCAL AND THROUGH-WINDOW — a local viewer targets their own side; a
        // through-window viewer targets only the half BEYOND the far plane (what the window
        // legitimately shows). A raw halfOfEye here with a foreign viewer's coordinates was live
        // round 11's bug: the far-side-only object was breakable from the empty side through the
        // window, because a viewer millions of blocks away in another dimension computed a garbage
        // half that coincided with the material.
        byte viewerHalf = viewerTargetableHalf(lvl, pos, binding, ec.getEntity());
        if (viewerHalf == 0) {
            // No legitimate line to this cell: nothing to see, nothing to hit.
            return net.minecraft.world.phys.shapes.Shapes.empty();
        }
        // viewerHalf is a MASK since round 22 (a side-on viewer may target BOTH halves). The
        // targeting shape is the union of every occupant the viewer may touch — the ray then
        // picks whichever sub-box it actually hits, which is vanilla semantics.
        net.minecraft.world.phys.shapes.VoxelShape out =
            net.minecraft.world.phys.shapes.Shapes.empty();
        if ((owned == SeamOccupancy.HALF_POSITIVE || owned == SeamOccupancy.HALF_NEGATIVE)
            && (owned & viewerHalf) != 0) {
            out = net.minecraft.world.phys.shapes.Shapes.join(
                original, halfBox(axis, owned, off),
                net.minecraft.world.phys.shapes.BooleanOp.AND);
        }
        if (sec != null && (sec.half() & viewerHalf) != 0) {
            out = net.minecraft.world.phys.shapes.Shapes.or(out,
                net.minecraft.world.phys.shapes.Shapes.join(
                    secondaryShapeOf(sec, level, pos), halfBox(axis, sec.half(), off),
                    net.minecraft.world.phys.shapes.BooleanOp.AND));
        }
        // Empty when every permitted half is unoccupied: nothing to see, the ray passes.
        return out;
    }

    /**
     * ★ RECORD THE OWNER HALF AT PLACEMENT — the only moment it is knowable.
     *
     * <p>User decision 2026-08-02: the half is decided by the side of the plane the <b>crosshair ray
     * hit point</b> falls on. Not the player's eyes (leaning through the portal would flip it), and
     * not the clicked block (the floor beneath an aperture and the frame itself both straddle the
     * plane, so neither can answer).
     *
     * <p>Called from the existing {@code BlockItem.place} bracket, which runs on the client
     * (prediction) as well as the server, so both sides record the same answer from the same hit
     * point with no packet. That is only true for placements this client made — occupancy for blocks
     * placed by others, or before joining, still needs the sync recorded as pending in §3.
     *
     * <p>No-ops for a cell that is not a mirror-admitted COINCIDENT seam cell.
     */
    public static byte recordPlacement(
        net.minecraft.world.level.Level level, BlockPos cell, net.minecraft.world.phys.Vec3 hit
    ) {
        if (!active()) {
            return 0;
        }
        SeamRegistry.SeamBinding binding = cuttingBinding(level, cell);
        if (binding == null || binding.cut() == null) {
            return 0;
        }
        double off = binding.cut().srcPlaneOffset();
        Direction.Axis axis = binding.srcFacing().getAxis();
        byte half = SeamOccupancy.halfFromHit(hit, cell, axis, off);
        SeamOccupancy.claim(level, cell, half);
        if (AperturePassthroughLever.SEAM_FRACTIONAL_PROBE) {
            SeamFractionalProbe.onSeamCell(cell, "CLAIM",
                "placement claimed the " + (half == SeamOccupancy.HALF_POSITIVE ? "POSITIVE"
                    : "NEGATIVE") + " " + axis + " half from hit " + hit + " (planeOffset=" + off
                    + ", now=" + SeamOccupancy.occupancyOf(level, cell)
                    + ", level=" + (level.isClientSide() ? "CLIENT" : "SERVER") + ")");
        }
        return half;
    }

    /**
     * ★ CLAIM THE CROSSING HALF IN THE DESTINATION — the other end of a divided object.
     *
     * <p>The source cell records which half the player claimed; this records where the rest of it
     * went. Together they are one object: the two halves sum to a block and no dimension holds a
     * whole one. Without this the destination cell has no owner, the shape hook leaves it WHOLE
     * (correctly — it never guesses), and you get the live 2026-08-02 report: the source half right,
     * the destination showing a full block from both of its sides.
     *
     * <p><b>Which half, and why it is derived rather than read off a facing.</b> The destination
     * portal is BI-FACED exactly like the source, so {@code cut.destFacing()} names one of two
     * opposite normals arbitrarily and cannot answer. What CAN answer is the placement: the source
     * keeps its material on the owned side, so what crosses extends from the plane in the
     * <em>opposite</em> direction, and mapping that direction through the portal's own rotation
     * ({@link SeamRegistry#mapDir}) names the destination side the material arrives on — the side you
     * emerge on walking through, which is the user's decision.
     *
     * <p>The resulting arrangement is symmetric and every view of it is consistent: source side A
     * holds material and destination side A is empty; source side B is empty and destination side B
     * holds material. Looking through the window from an empty side shows the far world's empty side,
     * so you see nothing — not because the window is blank, but because what is behind it genuinely
     * is not there.
     */
    public static void claimCrossingHalf(
        net.minecraft.world.level.Level sourceLevel, BlockPos sourcePos,
        net.minecraft.world.level.Level destLevel, BlockPos destPos,
        SeamRegistry.SeamBinding binding
    ) {
        if (!active() || sourceLevel == null || destLevel == null || destPos == null) {
            return;
        }
        byte sourceOwned = SeamOccupancy.occupancyOf(sourceLevel, sourcePos);
        // Only a single-half source object has a crossing half. Nothing owned means we do not know
        // whose material this is; BOTH owned means two objects already fill the cell and neither
        // has anything left to send.
        if (sourceOwned != SeamOccupancy.HALF_POSITIVE
            && sourceOwned != SeamOccupancy.HALF_NEGATIVE) {
            return;
        }
        Direction ownedDir = Direction.get(
            sourceOwned == SeamOccupancy.HALF_POSITIVE
                ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE,
            binding.srcFacing().getAxis());
        // What crosses extends the other way, and the portal's rotation carries that direction into
        // the destination's frame.
        Direction destDir = SeamRegistry.mapDir(binding, ownedDir.getOpposite());
        byte destHalf = SeamOccupancy.halfOf(destDir);
        SeamOccupancy.claim(destLevel, destPos, destHalf);
        // ★ AND TELL THE CLIENT. SeamMirror is server-only, so without this push the client never
        // learns which half of the destination holds material and the shape hook — correctly
        // refusing to guess — draws the cell WHOLE. Measured live 2026-08-02: one CROSS line on the
        // server thread, none on the client, and a full block visible from both destination sides.
        SeamOccupancy.broadcast(destLevel, destPos);
        // The source half is recorded on both sides already (BlockItem.place runs client-side for
        // prediction), but push it too so a second player watching through the portal agrees.
        SeamOccupancy.broadcast(sourceLevel, sourcePos);
        if (AperturePassthroughLever.SEAM_FRACTIONAL_PROBE) {
            SeamFractionalProbe.onSeamCell(destPos, "CROSS",
                "crossing half claimed " + (destHalf == SeamOccupancy.HALF_POSITIVE ? "POSITIVE"
                    : "NEGATIVE") + " " + destDir.getAxis() + " (source owned "
                    + (sourceOwned == SeamOccupancy.HALF_POSITIVE ? "POSITIVE" : "NEGATIVE") + " "
                    + ownedDir.getAxis() + " at " + sourcePos + ", mapped " + ownedDir.getOpposite()
                    + " -> " + destDir + ", now=" + SeamOccupancy.occupancyOf(destLevel, destPos)
                    + ")");
        }
    }

    /**
     * ★ THE TWO-OBJECT PLACEMENT — clicking the CUT FACE of a half-owned seam cell completes the
     * cell ({@code FRACTIONAL_DESIGN.md} §2a.0: "two independent objects share the cell").
     *
     * <p><b>Why vanilla cannot do this at all</b> (user live round 7: "i cannot place another block
     * in that spot that is empty"): the seam cell holds a real blockstate — merely shape-cut — so it
     * is not replaceable, and {@code BlockPlaceContext} offsets placement to the NEIGHBOUR cell
     * toward the player. There is no vanilla concept of "place into the empty half of an occupied
     * cell". And even with the target redirected, vanilla {@code place()} would fail: the cell
     * already holds the identical blockstate, and a same-state {@code setBlock} is a no-op.
     *
     * <p><b>What a second object actually is, in blockstate terms: nothing.</b> The cell's
     * blockstate already exists; the second object is pure bookkeeping — claim the empty half here,
     * claim the complementary half in the destination (its material crosses the other way), consume
     * one item. Complementarity is guaranteed, not hoped: the destination half comes from the same
     * {@code mapDir} rotation that placed the first object's crossing half (user-verified live),
     * and a rotation maps opposite directions to opposite directions.
     *
     * <p>SAME BLOCK TYPE ONLY — vanilla stores one blockstate per cell, so a different-type click
     * declines and falls through to vanilla's ordinary neighbour placement.
     *
     * @return SUCCESS when the gesture completed the cell; null when this is not that gesture and
     *         vanilla should proceed.
     */
    @org.jetbrains.annotations.Nullable
    public static net.minecraft.world.InteractionResult tryTwoObjectPlacement(
        net.minecraft.world.item.context.BlockPlaceContext ctx
    ) {
        if (!active() || ctx.getPlayer() == null) {
            return null;
        }
        net.minecraft.world.level.Level level = ctx.getLevel();
        // The cell the placement RESOLVES INTO — the seam cell is occupied and not replaceable, so
        // clicking any adjacent face (the sill below the opening, a side block, the frame) offsets
        // here. This is the natural vanilla gesture for "put a block in that spot", and it is the
        // one the user actually performs; the old build required clicking the cut face, which the
        // targeting rule (outlineShape) now correctly makes unhittable from the empty side.
        BlockPos target = ctx.getClickedPos();
        byte owned = SeamOccupancy.occupancyOf(level, target);
        if (owned != SeamOccupancy.HALF_POSITIVE && owned != SeamOccupancy.HALF_NEGATIVE) {
            return null;
        }
        if (SeamOccupancy.secondaryOf(level, target) != null) {
            return null;    // both halves already occupied
        }
        SeamRegistry.SeamBinding binding = cuttingBinding(level, target);
        if (binding == null || binding.destPos() == null || binding.cut() == null) {
            return null;
        }
        Direction.Axis axis = binding.srcFacing().getAxis();
        double off = binding.cut().srcPlaneOffset();
        byte emptyHalf = SeamOccupancy.otherHalf(owned);
        // The HIT POINT decides which half the player is building into — the same rule as first
        // placement. A click resolving into this cell but aimed at the occupied side is a mistake,
        // not a second object; decline and let vanilla refuse it.
        byte hitHalf = SeamOccupancy.halfFromHit(ctx.getClickLocation(), target, axis, off);
        if (hitHalf != emptyHalf) {
            return null;
        }
        net.minecraft.world.item.ItemStack stack = ctx.getItemInHand();
        if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) {
            return null;
        }
        // ★ ANY BLOCK TYPE — the user's non-negotiable. The second object's state lives in the side
        // table, not the chunk, so it does not need to match the vanilla occupant. Two refusals
        // remain, both storage facts rather than policy: block entities (the side table cannot host
        // inventory/NBT) and a null placement state (vanilla itself cannot place it here).
        net.minecraft.world.level.block.state.BlockState state =
            blockItem.getBlock().getStateForPlacement(ctx);
        if (state == null || state.hasBlockEntity()) {
            return null;
        }

        SeamOccupancy.setSecondary(level, target, new SeamOccupancy.Secondary(state, emptyHalf));
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            SeamOccupancy.broadcast(level, target);
            net.minecraft.server.level.ServerLevel dest =
                serverLevel.getServer().getLevel(binding.destDim());
            if (dest != null) {
                // The new object's crossing: its owned direction reflected through the plane and
                // carried through the portal rotation — the identical computation that placed the
                // first object's crossing half, so the two are complements by construction. The
                // crossing state is rotated exactly as the mirror rotates a primary write.
                Direction emptyDir = Direction.get(
                    emptyHalf == SeamOccupancy.HALF_POSITIVE
                        ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE,
                    axis);
                byte destHalf = SeamOccupancy.halfOf(
                    SeamRegistry.mapDir(binding, emptyDir.getOpposite()));
                SeamOccupancy.setSecondary(dest, binding.destPos(),
                    new SeamOccupancy.Secondary(
                        state.rotate(binding.stateRotation()), destHalf));
                SeamOccupancy.broadcast(dest, binding.destPos());
            }
        }
        if (!ctx.getPlayer().getAbilities().instabuild) {
            stack.shrink(1);
        }
        if (AperturePassthroughLever.SEAM_FRACTIONAL_PROBE) {
            SeamFractionalProbe.onSeamCell(target, "SECONDARY",
                "second object " + state.getBlock() + " placed into the "
                    + (emptyHalf == SeamOccupancy.HALF_POSITIVE ? "POSITIVE" : "NEGATIVE") + " "
                    + axis + " half (level=" + (level.isClientSide() ? "CLIENT" : "SERVER") + ")");
        }
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    /**
     * ★ THE VIEWER'S TARGETABLE HALF — one rule for outline, breaking and picking, LOCAL and
     * THROUGH-WINDOW (live round 11's bug, and features 2/3's foundation, in one).
     *
     * <p>A LOCAL viewer (same level as the cell) targets the half on THEIR side of the plane — they
     * are looking at the near face. A THROUGH-WINDOW viewer (foreign level, reaching this cell via
     * the portal) targets only the half BEYOND the far plane — exactly the region the window
     * legitimately shows, the same rule the renderer's inner clip enforces on pixels.
     *
     * <p><b>The bug this fixes:</b> {@code halfOfEye} with a foreign viewer's RAW coordinates —
     * another dimension, possibly millions of blocks away — computes garbage that can coincide with
     * the material half. Measured live: from the EMPTY side, through the window, the far-side-only
     * object was outlined and breakable, though the renderer (correctly) showed nothing there. The
     * transformed viewpoint sits BEFORE the far plane, in the region the inner clip hides; a
     * forward ray may only touch what lies BEYOND it.
     *
     * <p>The beyond-half is computed without any transform arithmetic: take the viewer's half at
     * their OWN counterpart cell (their dimension, their coordinates — meaningful), carry that
     * direction through the back-binding's rotation into this cell's frame, and take the OPPOSITE —
     * beyond the plane. Verified against the user-confirmed geometry: material-side viewer through
     * the window targets the object's far half (cross-dim selection working); empty-side viewer
     * targets the far EMPTY half (nothing to hit — the bug closed).
     *
     * @return the targetable half, or 0 when the viewer has no legitimate line to this cell (a
     *         foreign viewer from an unrelated dimension) — callers treat 0 as "nothing targetable".
     */
    /**
     * Interaction locality radius, squared. A genuine LOCAL interaction happens within reach
     * (~5 blocks) of the cell; a through-window interaction happens within reach of the cell's
     * COUNTERPART. 12 blocks of slack covers reach plus the aperture's extent.
     */
    private static final double NEAR_SQ = 12.0 * 12.0;

    public static byte viewerTargetableHalf(
        net.minecraft.world.level.Level level, BlockPos cell,
        SeamRegistry.SeamBinding binding, net.minecraft.world.entity.Entity viewer
    ) {
        if (binding.cut() == null) {
            return 0;
        }
        // ★ LOCALITY IS DISTANCE, NOT DIMENSION — live round 12's one-way break. For a SAME-DIM
        // portal pair, a through-window viewer satisfies viewer.level() == level while standing
        // millions of blocks away at the other portal; the "local" eye-side computation across
        // that distance is garbage that matches whichever material half faces their portal —
        // allowed one way, refused the other, exactly the reported asymmetry. Local means NEAR
        // THIS CELL; through-window means near its COUNTERPART; near neither means no line.
        if (viewer.level() == level
            && viewer.blockPosition().distSqr(cell) <= NEAR_SQ) {
            Direction.Axis axis = binding.srcFacing().getAxis();
            double off = binding.cut().srcPlaneOffset();
            byte eyeHalf = SeamOccupancy.halfOfEye(viewer, cell, axis, off);
            // ★ THE SIDE VIEW TARGETS BOTH HALVES (user round 22: "standing at side of portal but
            // more on side A, i cannot outline the seam block on side b even though i can see
            // it"). The eye-side-only rule is the far-side break protection, and it is only
            // justified where the window REPLACES the far half — i.e. when the sight line to the
            // far half passes through the aperture. From the side it does not: the segment from
            // the eye to the far half's centre crosses the cut plane laterally OUTSIDE this
            // cell's own cross-section, the far half is directly visible real geometry, and the
            // viewer may target it like any block face. Same aperture discrimination the outline
            // extract uses; the return is a MASK and every caller tests bitwise.
            net.minecraft.world.phys.Vec3 eye = viewer.getEyePosition();
            double planeCoord = off + (axis == Direction.Axis.X ? cell.getX()
                : axis == Direction.Axis.Y ? cell.getY() : cell.getZ());
            double sign = eyeHalf == SeamOccupancy.HALF_POSITIVE ? -1.0 : 1.0;
            net.minecraft.world.phys.Vec3 c = net.minecraft.world.phys.Vec3.atCenterOf(cell);
            net.minecraft.world.phys.Vec3 farCenter = new net.minecraft.world.phys.Vec3(
                axis == Direction.Axis.X ? planeCoord + sign * 0.25 : c.x,
                axis == Direction.Axis.Y ? planeCoord + sign * 0.25 : c.y,
                axis == Direction.Axis.Z ? planeCoord + sign * 0.25 : c.z);
            double eyeA = axis == Direction.Axis.X ? eye.x
                : axis == Direction.Axis.Y ? eye.y : eye.z;
            double farA = axis == Direction.Axis.X ? farCenter.x
                : axis == Direction.Axis.Y ? farCenter.y : farCenter.z;
            if (Math.abs(farA - eyeA) > 1.0e-6) {
                double t = (planeCoord - eyeA) / (farA - eyeA);
                if (t >= 0 && t <= 1) {
                    net.minecraft.world.phys.Vec3 hit =
                        eye.add(farCenter.subtract(eye).scale(t));
                    boolean throughAperture = switch (axis) {
                        case X -> hit.y >= cell.getY() && hit.y <= cell.getY() + 1
                            && hit.z >= cell.getZ() && hit.z <= cell.getZ() + 1;
                        case Y -> hit.x >= cell.getX() && hit.x <= cell.getX() + 1
                            && hit.z >= cell.getZ() && hit.z <= cell.getZ() + 1;
                        case Z -> hit.x >= cell.getX() && hit.x <= cell.getX() + 1
                            && hit.y >= cell.getY() && hit.y <= cell.getY() + 1;
                    };
                    if (!throughAperture) {
                        return SeamOccupancy.BOTH;   // side view: both halves directly visible
                    }
                }
            }
            return eyeHalf;
        }
        if (binding.destPos() == null || binding.destDim() == null
            || !viewer.level().dimension().equals(binding.destDim())
            || viewer.blockPosition().distSqr(binding.destPos()) > NEAR_SQ) {
            return 0;
        }
        SeamRegistry.SeamCell backCell = SeamRegistry.lookup(viewer.level(), binding.destPos());
        if (backCell == null) {
            return 0;
        }
        for (SeamRegistry.SeamBinding back : backCell.bindings()) {
            if (back == null || !back.isMirrorable() || back.cut() == null
                || !cell.equals(back.destPos())) {
                continue;
            }
            byte atCounterpart = SeamOccupancy.halfOfEye(viewer, binding.destPos(),
                back.srcFacing().getAxis(), back.cut().srcPlaneOffset());
            Direction dirThere = Direction.get(
                atCounterpart == SeamOccupancy.HALF_POSITIVE
                    ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE,
                back.srcFacing().getAxis());
            Direction dirHere = SeamRegistry.mapDir(back, dirThere);
            return SeamOccupancy.otherHalf(SeamOccupancy.halfOf(dirHere));
        }
        return 0;
    }

    /**
     * ★ BREAK ROUTING for the second object — the user's "completely separate" rule. Called from
     * the {@code ServerPlayerGameMode.destroyBlock} HEAD hook when the breaking player's eye-side
     * half is the SECONDARY's half: the whole second object goes (both dimensions), one item drops
     * on the breaker's side, and the vanilla destroy — which would have removed the PRIMARY's
     * blockstate — never runs.
     */
    public static boolean breakSecondary(
        net.minecraft.server.level.ServerLevel level, BlockPos pos,
        net.minecraft.server.level.ServerPlayer player
    ) {
        SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(level, pos);
        if (sec == null) {
            return false;
        }
        SeamRegistry.SeamBinding binding = cuttingBinding(level, pos);
        SeamOccupancy.setSecondary(level, pos, null);
        SeamOccupancy.broadcast(level, pos);
        if (binding != null && binding.destPos() != null) {
            net.minecraft.server.level.ServerLevel dest =
                level.getServer().getLevel(binding.destDim());
            if (dest != null) {
                SeamOccupancy.setSecondary(dest, binding.destPos(), null);
                SeamOccupancy.broadcast(dest, binding.destPos());
            }
        }
        if (!player.getAbilities().instabuild) {
            net.minecraft.world.level.block.Block.popResource(level, pos,
                new net.minecraft.world.item.ItemStack(sec.state().getBlock()));
        }
        if (AperturePassthroughLever.SEAM_FRACTIONAL_PROBE) {
            SeamFractionalProbe.onSeamCell(pos, "BREAK-SECONDARY",
                "second object " + sec.state().getBlock() + " removed (both dimensions), primary"
                    + " untouched");
        }
        return true;
    }

    /**
     * ★ PROMOTE ON BREAK — when the PRIMARY object's blockstate is removed (vanilla destroy, or the
     * mirror's break-either-breaks-both clearing the counterpart), a surviving second object
     * becomes the cell's vanilla occupant: its state moves from the side table into the chunk and
     * its half becomes the recorded owner. Called from the seam driver's air branch on BOTH sides —
     * the counterpart promotes itself when the break path airs it.
     *
     * @return true when a promote happened (the caller must then NOT clear occupancy — it was
     *         rewritten, not emptied).
     */
    public static boolean promoteSecondaryOnAir(
        net.minecraft.world.level.Level level, BlockPos pos
    ) {
        SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(level, pos);
        if (sec == null) {
            return false;
        }
        SeamOccupancy.setSecondary(level, pos, null);
        SeamOccupancy.set(level, pos, sec.half());
        SeamMirror.writeAsSeamInternal(level, pos, sec.state());
        SeamOccupancy.broadcast(level, pos);
        if (AperturePassthroughLever.SEAM_FRACTIONAL_PROBE) {
            SeamFractionalProbe.onSeamCell(pos, "PROMOTE",
                "primary broken; surviving second object " + sec.state().getBlock()
                    + " promoted to the cell's blockstate (half="
                    + (sec.half() == SeamOccupancy.HALF_POSITIVE ? "POSITIVE" : "NEGATIVE") + ")");
        }
        return true;
    }

    /** Forget a cell's owner halves — the object was broken or the cell replaced wholesale. */
    public static void forgetPlacement(net.minecraft.world.level.Level level, BlockPos cell) {
        if (level != null) {
            SeamOccupancy.clear(level, cell);
        }
    }

    /**
     * Whether a cut seam cell should stop suffocating. User decision 2026-08-02: suffocation follows
     * THE CUT, not the union — because the removed part is a doorway the player is meant to walk
     * through, and reporting whole would damage them for using the portal. Deliberately the one
     * place that diverges from soul sand, whose missing 2/16 is ordinary air in the same world.
     */
    public static boolean suppressesSuffocation(BlockGetter level, BlockPos pos) {
        return collisionActive() && cuttingBinding(level, pos) != null;
    }

    /**
     * The binding that actually cuts this cell, or null. Fast path first: the {@code BlockGetter}
     * is very often not a {@link net.minecraft.world.level.Level} at all (the compile path hands
     * section copies, and the per-blockstate cache uses {@code EmptyBlockGetter}), and every such
     * call must cost one instanceof.
     */
    @org.jetbrains.annotations.Nullable
    private static SeamRegistry.SeamBinding cuttingBinding(BlockGetter level, BlockPos pos) {
        if (!(level instanceof net.minecraft.world.level.Level lvl)) {
            return null;
        }
        SeamRegistry.SeamCell seam = SeamRegistry.lookup(lvl, pos);
        if (seam == null) {
            return null;
        }
        for (SeamRegistry.SeamBinding b : seam.bindings()) {
            if (b == null || !b.isMirrorable() || b.cut() == null) {
                continue;
            }
            // A DISJOINT binding does not straddle, so it cuts nothing — and that falls out of the
            // arithmetic rather than needing a phase branch here (kept == 1 ⇒ keptShape bails).
            if (b.phase() == SeamMap.SeamPhase.COINCIDENT) {
                return b;
            }
        }
        return null;
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
