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

    // =============================================================================================
    // F4 — THE SECOND OBJECT PARTICIPATES (user ruling: "SA to DB should carry signal normally,
    // and SB to DA ... at the same time even if they overlap each other on the seam"; live
    // 2026-08-11: "if side a is already powered, the side b seam does not send power or get
    // powered"). A side-table Secondary is invisible to vanilla redstone — it has no evaluator
    // and nothing ever rewrote its state. This is its power lifecycle: evaluate the fragment
    // from BOTH of its circuit's sides (its own-side local neighbours + the far continuation
    // through its half-matched binding), store the result in the side table, sync the object's
    // fragment at the counterpart cell, broadcast (the payload carries full state ids — powered
    // fragments render lit), and fan neighbour updates so each side's circuit re-derives.
    // Loop-safe by construction: setSecondary writes no chunk state (no driver re-entry), the
    // fan-out only fires on a value CHANGE, and wire decay is strictly decreasing.
    // =============================================================================================

    /** Re-entrancy bound: a refresh's fan-out may poke back, but never deeper than this. */
    private static int refreshDepth = 0;

    /** Re-derive a cell's side-table fragment's power, if it is a redstone participant. */
    public static void refreshSecondary(ServerLevel level, BlockPos pos) {
        if (refreshDepth >= 4) {
            return;
        }
        refreshDepth++;
        try {
            refreshSecondaryInner(level, pos);
        }
        finally {
            refreshDepth--;
        }
    }

    private static void refreshSecondaryInner(ServerLevel level, BlockPos pos) {
        try {
            if (AperturePassthroughLever.DISABLED
                || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
                || AperturePassthroughLever.DISABLE_SEAM_WIRE
                || AperturePassthroughLever.DISABLE_SEAM_HALF_SCOPE
                || !SeamlessPortalsConfig.isEntityPortals()) {
                return;
            }
            SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(level, pos);
            if (sec == null) {
                return;
            }
            boolean wire = sec.state().is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE);
            boolean rail = sec.state().hasProperty(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)
                && sec.state().getBlock()
                    instanceof net.minecraft.world.level.block.BaseRailBlock;
            if (!wire && !rail) {
                refreshProbe(level, pos, sec, "EXIT not-a-redstone-participant "
                    + sec.state().getBlock());
                return;
            }
            // The binding facing the fragment's matter names its axis and its far continuation.
            SeamRegistry.SeamCell cell = SeamRegistry.lookup(level, pos);
            if (cell == null) {
                refreshProbe(level, pos, sec, "EXIT no-seam-cell");
                return;
            }
            SeamRegistry.SeamBinding b = null;
            for (SeamRegistry.SeamBinding cand : cell.bindings()) {
                if (cand.isMirrorable() && cand.seamContinuous() && cand.cut() != null
                    && SeamOccupancy.halfOf(cand.srcFacing()) == sec.half()) {
                    b = cand;
                    break;
                }
            }
            if (b == null) {
                refreshProbe(level, pos, sec, "EXIT no-half-matched-binding candidates="
                    + describeBindings(cell));
                return;
            }
            Direction.Axis axis = b.srcFacing().getAxis();
            Direction secDir = Direction.get(
                sec.half() == SeamOccupancy.HALF_POSITIVE
                    ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE,
                axis);
            Direction primaryDir = secDir.getOpposite();
            // Both of the fragment's circuit sides: own-side locals + the far continuation.
            // Wire neighbours take the DECAY path, never the block-power path — a raw getSignal
            // outside the shouldSignal latch answers a wire's FULL power (vanilla's latch exists
            // exactly to exclude that; this scan replicates its effect by splitting on block).
            // Conductor strong-power fan-in is a recorded v1 scope cut (direct sources only).
            int block = 0;
            int wireIn = 0;
            for (Direction d : Direction.values()) {
                if (d == primaryDir) {
                    continue;
                }
                BlockPos nPos = pos.relative(d);
                BlockState nState = level.getBlockState(nPos);
                if (nState.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE)) {
                    wireIn = Math.max(wireIn, wireSignalOf(nState));
                }
                else {
                    block = Math.max(block, nState.getSignal(level, nPos, d));
                }
            }
            // The object's OTHER end: the counterpart cell's own-side neighbours, scanned with
            // the same wire/block split. Both ends therefore evaluate over the IDENTICAL physical
            // set — symmetric by construction, so the mutual sync below is idempotent and cannot
            // fight. (The first build read one far continuation cell per end — different cells
            // per end — and the two ends' unequal answers ping-ponged through the sync at tick
            // speed: secondaryRefreshes=2M in one gate run before the ceiling caught it.)
            MinecraftServer server = level.getServer();
            ServerLevel far = server == null ? null : server.getLevel(b.destDim());
            if (far != null && far.hasChunkAt(b.destPos())) {
                // THE HALF FLIPS ACROSS THE SEAM: the counterpart fragment occupies the MIRRORED
                // half of its cell, so the mapped secDir names the far PRIMARY side — the far
                // fragment's own side is its opposite. The 2026-08-11 probe run caught the
                // unflipped version: the far scan read the OTHER object's circuit, and the sync
                // below stamped the counterpart onto the primary's claimed half, which the
                // empty-side read then correctly ignored — behindNear stayed dark for 200 ticks.
                Direction farPrimaryDir = SeamRegistry.mapDir(b, secDir);
                for (Direction d : Direction.values()) {
                    if (d == farPrimaryDir) {
                        continue;
                    }
                    BlockPos nPos = b.destPos().relative(d);
                    if (!far.isInsideBuildHeight(nPos) || !far.hasChunkAt(nPos)) {
                        continue;
                    }
                    BlockState nState = far.getBlockState(nPos);
                    if (nState.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE)) {
                        wireIn = Math.max(wireIn, wireSignalOf(nState));
                    }
                    else {
                        block = Math.max(block, nState.getSignal(far, nPos, d));
                    }
                }
            }
            BlockState updated;
            if (wire) {
                int target = Math.max(block, Math.max(0, wireIn - 1));
                var POWER = net.minecraft.world.level.block.state.properties
                    .BlockStateProperties.POWER;
                if (sec.state().getValue(POWER) == target) {
                    refreshProbe(level, pos, sec, "EXIT no-change power=" + target
                        + " (block=" + block + " wireIn=" + wireIn + ") via srcFacing="
                        + b.srcFacing());
                    return;
                }
                updated = sec.state().setValue(POWER, target);
            }
            else {
                // ★ RAIL FRAGMENT = ITS OWN CIRCUIT'S STATE (RS-XTALK live rounds 3-4). Rails
                // power via the WALK, not adjacency, so the original adjacency target was almost
                // always false (permanently dark seam rail). Round 3 mirrored the chunk PRIMARY
                // instead — but the fragment is the SECOND path's rail, and the primary carries
                // the pair's shared bit, so path 1's power held the fragment lit ("won't power
                // off if the first set is on"). The fragment sits between its two REAL rails on
                // its own stitched line — the own-side neighbour on its half's side, and the
                // counterpart's fragment-side neighbour across the seam — and power only reaches
                // either through its own path's severed-door walks, so their OR is the
                // fragment's vanilla-consistent state under every mix of the two paths. Inputs
                // flow one way (real rails → fragment; their walks read the chunk primary, never
                // the fragment), the write is change-gated: acyclic, cannot ping-pong (the round
                // 3.5 two-writer loop burned 1.9M flips through this method's own update fans).
                var POWERED = net.minecraft.world.level.block.state.properties
                    .BlockStateProperties.POWERED;
                boolean target = false;
                BlockState ownSide = level.getBlockState(pos.relative(secDir));
                if (ownSide.hasProperty(POWERED) && ownSide.getBlock()
                        instanceof net.minecraft.world.level.block.BaseRailBlock) {
                    target = ownSide.getValue(POWERED);
                }
                if (!target && far != null) {
                    Direction farFragDir = SeamRegistry.mapDir(b, secDir).getOpposite();
                    BlockPos farN = b.destPos().relative(farFragDir);
                    if (far.isInsideBuildHeight(farN) && far.hasChunkAt(farN)) {
                        BlockState farSide = far.getBlockState(farN);
                        if (farSide.hasProperty(POWERED) && farSide.getBlock()
                                instanceof net.minecraft.world.level.block.BaseRailBlock) {
                            target = farSide.getValue(POWERED);
                        }
                    }
                }
                if (sec.state().getValue(POWERED) == target) {
                    refreshProbe(level, pos, sec, "EXIT no-change powered=" + target
                        + " (own+far rail OR) via srcFacing=" + b.srcFacing());
                    return;
                }
                updated = sec.state().setValue(POWERED, target);
            }
            refreshProbe(level, pos, sec, "WROTE " + updated + " (block=" + block
                + " wireIn=" + wireIn + ") via srcFacing=" + b.srcFacing());
            SeamOccupancy.setSecondary(level, pos, new SeamOccupancy.Secondary(updated, sec.half()));
            SeamOccupancy.broadcast(level, pos);
            secondaryRefreshes++;
            // The object's fragment at the counterpart cell carries the same power (one stitched
            // object, one value) — sync + broadcast + wake ITS side's neighbours.
            if (far == null || !far.hasChunkAt(b.destPos())) {
                refreshProbe(level, pos, sec, "SYNC-SKIP far unavailable dest=" + b.destPos());
            }
            if (far != null && far.hasChunkAt(b.destPos())) {
                // Mirrored half, same flip as the far scan above.
                byte farHalf = SeamOccupancy.halfOf(SeamRegistry.mapDir(b, secDir).getOpposite());
                SeamOccupancy.Secondary farSec = SeamOccupancy.secondaryOf(far, b.destPos());
                if (farSec == null || !farSec.state().is(sec.state().getBlock())) {
                    refreshProbe(level, pos, sec, "SYNC-SKIP counterpart " + (farSec == null
                        ? "absent" : "different-block " + farSec.state().getBlock())
                        + " at " + b.destPos());
                }
                if (farSec != null && farSec.state().is(sec.state().getBlock())) {
                    SeamOccupancy.setSecondary(far, b.destPos(), new SeamOccupancy.Secondary(
                        updated.rotate(b.stateRotation()), farHalf));
                    SeamOccupancy.broadcast(far, b.destPos());
                    // Wake the far circuit. updateNeighborsAt(P) notifies the six cells AROUND P,
                    // never P itself — so the cell's OWN entry is what wakes its face-adjacent
                    // wires, and the relative shell covers the wire graph's diagonals (vanilla's
                    // updatePowerStrength fans {pos} ∪ pos.relative(6); the first build dropped
                    // the {pos} element and no face neighbour ever re-evaluated). The skip is the
                    // far fragment's PRIMARY side (the mapped secDir — the seam-axis flip again).
                    far.updateNeighborsAt(b.destPos(), updated.getBlock());
                    for (Direction d : Direction.values()) {
                        if (d == SeamRegistry.mapDir(b, secDir)) {
                            continue;
                        }
                        far.updateNeighborsAt(b.destPos().relative(d), updated.getBlock());
                    }
                }
            }
            // Same shape as the far fan: the cell's own entry wakes face neighbours, the shell
            // covers diagonals.
            level.updateNeighborsAt(pos, updated.getBlock());
            for (Direction d : Direction.values()) {
                if (d == primaryDir) {
                    continue;
                }
                level.updateNeighborsAt(pos.relative(d), updated.getBlock());
            }
        }
        catch (Throwable t) {
            fault(t);
        }
    }

    private static long secondaryRefreshes;

    /**
     * F4 diagnosis channel, probe-only: names the branch a secondary refresh exited through.
     * Volume-bounded by construction — it only fires for cells that HAVE a side-table fragment,
     * and only under {@code -Dseamlessportals.seamSignalProbe=true}.
     */
    private static void refreshProbe(
        ServerLevel level, BlockPos pos, SeamOccupancy.Secondary sec, String what
    ) {
        if (AperturePassthroughLever.SEAM_SIGNAL_PROBE) {
            LOGGER.info("[F4-REFRESH] {} in {} secHalf={} — {}",
                pos, level.dimension().identifier(), sec.half(), what);
        }
    }

    private static String describeBindings(SeamRegistry.SeamCell cell) {
        StringBuilder sb = new StringBuilder();
        for (SeamRegistry.SeamBinding cand : cell.bindings()) {
            sb.append("[srcFacing=").append(cand.srcFacing())
                .append(" halfOf=").append(SeamOccupancy.halfOf(cand.srcFacing()))
                .append(" mirrorable=").append(cand.isMirrorable())
                .append(" continuous=").append(cand.seamContinuous())
                .append(" cut=").append(cand.cut() != null)
                .append(" phase=").append(cand.phase()).append(']');
        }
        return sb.length() == 0 ? "NONE" : sb.toString();
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

    public static long secondaryRefreshCount() {
        return secondaryRefreshes;
    }

    public static String counters() {
        return "decayReads=" + decayReads + " decayHits=" + decayHits
            + " connReads=" + connReads + " connHits=" + connHits + " halfGated=" + halfGated
            + " secondaryRefreshes=" + secondaryRefreshes;
    }
}
