package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * RS PASSTHROUGH (c) step 2 — <b>REDSTONE WIRE (dust) CROSSES THE SEAM</b> (spec §6.2, the recorded
 * deferral; unblocked by the user's 2026-08-10 ruling: full continuity, and "the lit dust should
 * also propagate down the stream").
 *
 * <h2>Why step 1's bridge could not carry dust (spec F11)</h2>
 * Wire-to-wire decay never touches the {@code SignalGetter} family step 1 wrapped per-consumer:
 * {@code RedstoneWireEvaluator.getIncomingWireSignal} reads the neighbour's {@code getBlockState} +
 * {@code POWER} property RAW. Connection shape ({@code getConnectingSide}) reads raw states the same
 * way. So dust needs its own {@code getBlockState}-level substitution, which lives here.
 *
 * <h2>The read model — UNION plus F8 half-scoping (two live rounds' lessons)</h2>
 * A wire at {@code P} evaluating in horizontal direction {@code D} whose step {@code P → P+D}
 * crosses a seam gets a FAR CANDIDATE beside its local read: the far continuation cell (or its
 * above/below for the wire-on-a-step diagonals), never-load-guarded, rotated into the near frame.
 * Two hard-won rules govern the choice:
 * <ol>
 *   <li><b>UNION, never replacement</b> (2026-08-10 RS-WIRE dump): a COINCIDENT binding's
 *       {@code continuationToward} answers BOTH directions along the seam axis, so replacing the
 *       local read clobbered the real approach-side neighbour (A1=14) with behind-portal air.</li>
 *   <li><b>HALF-SCOPING at claimed cells</b> (F8, the user's 2026-08-11 live leak: "seam redstone
 *       powers too broadly"): for a cell with a claimed primary half
 *       ({@link SeamFractional#primaryHalf}), each seam-axis direction has exactly ONE legitimate
 *       candidate — the OWNED direction is local-only (the far cell there is the other stitched
 *       space's behind-plane region), the EMPTY direction is far-only (the local cell there is
 *       this side's behind-plane region), and reads OF the cell from its empty side see the
 *       Secondary's state or air ({@link SeamFractional#readCellFromSide}). Unclaimed cells
 *       (mask 0 — every command-staged fixture) stay whole-cell vanilla with the plain union.
 *       Verified by the three-lens panel wf_bcc95ef6-045 before implementation.</li>
 * </ol>
 *
 * <h2>The two hard rules (spec F8-hazard — the {@code RedStoneWireBlock.shouldSignal} singleton)</h2>
 * Every entry point here is side-effect-free and exception-proof: a Throwable is caught, warned
 * once, and answered with the vanilla value — an escape inside a wire bracket would latch
 * {@code shouldSignal=false} and mute all wire globally. No far-level EVALUATION runs inside a
 * read; cold far chunks answer "nothing there" and queue a warm-up retry via
 * {@link SeamSignalContinuity} — never a chunk load.
 */
public final class SeamWireBridge {

    private SeamWireBridge() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static long decayReads, decayHits, connReads, connHits, halfGated;
    private static boolean faultWarned = false;

    /**
     * Substitute one {@code getIncomingWireSignal} neighbour read. {@code wirePos} is the evaluating
     * wire's own cell (the method's argument); {@code queryPos} the position vanilla asked for;
     * {@code local} vanilla's answer.
     */
    public static BlockState decayRead(
        Level level, BlockPos wirePos, BlockPos queryPos, BlockState local
    ) {
        try {
            if (globallyInert(level)) {
                return local;
            }
            // F8 reader gate FIRST, keyed on the QUERY cell — the panel's section-edge finding:
            // a behind-plane reader can live in a section with no seam cells of its own, so the
            // reader-section fold below must not shield this.
            BlockState seen = halfGate(level, wirePos, queryPos, local);
            if (!SeamRegistry.sectionHasSeam(level, wirePos)) {
                return seen;
            }
            Direction readerEmptyDir = SeamFractional.emptyHalfDir(level, wirePos);
            Choice c = classify(level, wirePos, queryPos, readerEmptyDir);
            if (c == null) {
                return seen;
            }
            decayReads++;
            if (c.localOnly()) {
                return seen;
            }
            BlockState base = c.farOnly()
                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : seen;
            BlockState far = c.far();
            if (far == null) {
                return base;
            }
            // UNION on the surviving candidates: the far state wins only on MORE wire signal.
            int baseSig = wireSignalOf(base);
            int farSig = wireSignalOf(far);
            if (farSig > baseSig) {
                decayHits++;
                return far;
            }
            return base;
        }
        catch (Throwable t) {
            fault(t);
            return local;
        }
    }

    /**
     * Substitute one {@code getConnectingSide} neighbour read — same geometry as
     * {@link #decayRead}, connectability instead of signal strength, separate counters.
     */
    public static BlockState connectionRead(
        Level level, BlockPos wirePos, BlockPos queryPos, BlockState local
    ) {
        try {
            if (globallyInert(level)) {
                return local;
            }
            BlockState seen = halfGate(level, wirePos, queryPos, local);
            if (!SeamRegistry.sectionHasSeam(level, wirePos)) {
                return seen;
            }
            Direction readerEmptyDir = SeamFractional.emptyHalfDir(level, wirePos);
            Choice c = classify(level, wirePos, queryPos, readerEmptyDir);
            if (c == null) {
                return seen;
            }
            connReads++;
            if (c.localOnly()) {
                return seen;
            }
            BlockState base = c.farOnly()
                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : seen;
            BlockState far = c.far();
            if (far == null) {
                return base;
            }
            boolean baseConn = connectable(base);
            boolean farConn = connectable(far);
            if (!baseConn && farConn) {
                connHits++;
                return far;
            }
            return base;
        }
        catch (Throwable t) {
            fault(t);
            return local;
        }
    }

    /**
     * F8 — a raw read of a claimed seam cell answers per-half ({@code SeamFractional
     * .readCellFromSide}): the Secondary's state or air from the empty side, the real state from
     * the primary's side. In-plane readers (no offset along the cut axis) pass through — the
     * lateral opposite-half adjacency is a recorded v1 scope cut. Public for the diode mixin
     * (repeater/comparator raw wire reads, the panel's D3 family).
     */
    public static BlockState halfGate(
        Level level, BlockPos readerPos, BlockPos queryPos, BlockState actual
    ) {
        Direction emptyDir = SeamFractional.emptyHalfDir(level, queryPos);
        if (emptyDir == null) {
            return actual;
        }
        int delta = readerPos.get(emptyDir.getAxis()) - queryPos.get(emptyDir.getAxis());
        if (delta == 0) {
            return actual;   // in-plane reader — v1 scope cut, recorded
        }
        Direction sideTowardReader = Direction.get(
            delta > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE,
            emptyDir.getAxis());
        BlockState seen = SeamFractional.readCellFromSide(level, queryPos, sideTowardReader, actual);
        if (seen != actual) {
            halfGated++;
        }
        return seen;
    }

    /** True when the reader's side of a claimed cut cell holds nothing that can carry signal. */
    public static boolean readerOnEmptySide(Level level, BlockPos readerPos, BlockPos queryPos) {
        BlockState actual = level.getBlockState(queryPos);
        BlockState seen = halfGate(level, readerPos, queryPos, actual);
        return seen != actual && !connectable(seen);
    }

    /**
     * Classify one read of {@code queryPos} by the wire at {@code wirePos}: null = not a seam-axis
     * step (own column, in-plane lateral beyond one step, |dy|&gt;1); otherwise the per-direction
     * candidate policy plus the resolved far state.
     */
    @Nullable
    private static Choice classify(
        Level level, BlockPos wirePos, BlockPos queryPos, @Nullable Direction readerEmptyDir
    ) {
        int dx = queryPos.getX() - wirePos.getX();
        int dy = queryPos.getY() - wirePos.getY();
        int dz = queryPos.getZ() - wirePos.getZ();
        if ((dx == 0 && dz == 0) || Math.abs(dx) + Math.abs(dz) != 1 || Math.abs(dy) > 1) {
            return null;
        }
        Direction step = dx != 0
            ? (dx > 0 ? Direction.EAST : Direction.WEST)
            : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        // F8 claimed-reader policy: exactly one candidate per seam-axis direction.
        if (readerEmptyDir != null) {
            if (step == readerEmptyDir.getOpposite()) {
                return Choice.LOCAL_ONLY;   // own half's side: the far cell there is the other stitching
            }
            if (step == readerEmptyDir) {
                // Empty side: the local cell there is this side's behind-plane region. Far only,
                // through the binding that faces the occupant (bi-faced half-filter).
                return new Choice(false, true,
                    resolveFar(level, wirePos, step, dy,
                        SeamFractional.primaryHalf(level, wirePos)));
            }
        }
        return new Choice(false, false, resolveFar(level, wirePos, step, dy, (byte) 0));
    }

    /** The far continuation candidate for a step, or null (no binding / cold / unresolvable). */
    @Nullable
    private static BlockState resolveFar(
        Level level, BlockPos wirePos, Direction step, int dy, byte requiredHalf
    ) {
        SeamRegistry.SeamCell cell = SeamRegistry.lookup(level, wirePos);
        if (cell == null) {
            return null;
        }
        SeamRegistry.SeamBinding b = null;
        for (SeamRegistry.SeamBinding cand : cell.bindings()) {
            if (!cand.isMirrorable() || !cand.seamContinuous()
                || cand.continuationToward(step) == null) {
                continue;
            }
            if (requiredHalf != 0
                && SeamOccupancy.halfOf(cand.srcFacing()) != requiredHalf) {
                continue;   // bi-faced twin facing the wrong half (panel correction #3)
            }
            b = cand;
            break;
        }
        if (b == null) {
            return null;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return null;
        }
        ServerLevel far = server.getLevel(b.destDim());
        BlockPos continuation = b.continuationToward(step);
        if (far == null || continuation == null) {
            return null;
        }
        BlockPos target = dy == 0 ? continuation
            : (dy > 0 ? continuation.above() : continuation.below());
        if (!far.isInsideBuildHeight(target) || !far.hasChunkAt(target)) {
            SeamSignalContinuity.wireDeclineCold(level, wirePos, far, target);
            return null;
        }
        BlockState state = far.getBlockState(target);
        return state.rotate(SeamShadowBridge.inverse(b.stateRotation()));
    }

    /** One read's candidate policy. LOCAL_ONLY carries no far state by construction. */
    private record Choice(boolean localOnly, boolean farOnly, @Nullable BlockState far) {
        private static final Choice LOCAL_ONLY = new Choice(true, false, null);
    }

    private static int wireSignalOf(BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE)
            ? state.getValue(net.minecraft.world.level.block.state.properties
                .BlockStateProperties.POWER)
            : 0;
    }

    private static boolean connectable(BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE)
            || state.isSignalSource();
    }

    /** Levers, config and server-thread only — position-independent gates. */
    private static boolean globallyInert(Level level) {
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
            || AperturePassthroughLever.DISABLE_SEAM_WIRE
            || !SeamlessPortalsConfig.isEntityPortals()
            || !(level instanceof ServerLevel src)) {
            return true;
        }
        MinecraftServer server = src.getServer();
        return server == null || !server.isSameThread();
    }

    /** Read-path fault: never rethrow (spec F8 — an escape inside a wire bracket mutes all wire). */
    private static void fault(Throwable t) {
        if (!faultWarned) {
            faultWarned = true;
            LOGGER.warn("[RS-WIRE] bridged wire read faulted — answering vanilla-only from now"
                + " until the next report (first fault below)", t);
        }
    }

    public static long decayReadsCount() {
        return decayReads;
    }

    public static long connReadsCount() {
        return connReads;
    }

    public static String counters() {
        return "decayReads=" + decayReads + " decayHits=" + decayHits
            + " connReads=" + connReads + " connHits=" + connHits + " halfGated=" + halfGated;
    }
}
