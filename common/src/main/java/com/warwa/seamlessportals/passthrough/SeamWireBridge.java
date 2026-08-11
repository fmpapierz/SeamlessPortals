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
 * <h2>The read model — UNION, never replacement (the first build's defect)</h2>
 * A wire at {@code P} evaluating in horizontal direction {@code D} whose step {@code P → P+D}
 * crosses a seam gets a FAR CANDIDATE beside its local read: the far continuation cell (or its
 * above/below for the wire-on-a-step diagonals), never-load-guarded, rotated into the near frame
 * ({@code binding.stateRotation()} inverse) so direction-sensitive sources answer
 * {@code shouldConnectTo} correctly. The two candidates then UNION — the far state is returned
 * only when it carries something the local one does not (more wire signal for decay; a
 * connectable block for shape). The first build REPLACED local with far and went dark on its own
 * gate: a COINCIDENT binding's {@code continuationToward} answers for BOTH directions along the
 * seam axis (the plane bisects the cell), so the approach-side read — the real, local, powered
 * neighbour — was clobbered by the far world's behind-the-portal air (RS-WIRE dump, 2026-08-10:
 * A1=14 beside S=0). Union makes both axis directions correct by construction: on the approach
 * side the far candidate is behind-portal air and loses; on the crossing side the local candidate
 * is behind-portal air and loses.
 *
 * <h2>The two hard rules (spec F8 — the {@code RedStoneWireBlock.shouldSignal} singleton)</h2>
 * Every entry point here is side-effect-free and exception-proof: a Throwable is caught, warned
 * once, and answered with the vanilla value — an escape inside a wire bracket would latch
 * {@code shouldSignal=false} and mute all wire globally. No far-level EVALUATION runs inside a
 * read; cold far chunks answer "nothing there" and queue a warm-up retry via
 * {@link SeamSignalContinuity} — never a chunk load.
 */
public final class SeamWireBridge {

    private SeamWireBridge() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static long decayReads, decayHits, connReads, connHits;
    private static boolean faultWarned = false;

    /**
     * Substitute one {@code getIncomingWireSignal} neighbour read. {@code wirePos} is the evaluating
     * wire's own cell (the method's argument); {@code queryPos} the position vanilla asked for;
     * {@code local} vanilla's answer. Returns the far-side state when the query steps through a
     * seam, else {@code local}.
     */
    public static BlockState decayRead(
        Level level, BlockPos wirePos, BlockPos queryPos, BlockState local
    ) {
        try {
            if (inert(level, wirePos)) {
                return local;
            }
            BlockState far = acrossRead(level, wirePos, queryPos);
            if (far != null) {
                decayReads++;
                // UNION: the far candidate wins only when it carries MORE wire signal — a far
                // behind-portal air must never clobber a real local neighbour (class note).
                int localSig = local.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE)
                    ? local.getValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.POWER)
                    : 0;
                int farSig = far.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE)
                    ? far.getValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.POWER)
                    : 0;
                if (farSig > localSig) {
                    decayHits++;
                    return far;
                }
            }
            return local;
        }
        catch (Throwable t) {
            fault(t);
            return local;
        }
    }

    /**
     * Substitute one {@code getConnectingSide}/{@code getMissingConnections} neighbour read — same
     * geometry as {@link #decayRead}, separate counters (shape reads run on both sides and far more
     * often than decay reads; one number would hide a runaway in the other).
     */
    public static BlockState connectionRead(
        Level level, BlockPos wirePos, BlockPos queryPos, BlockState local
    ) {
        try {
            if (inert(level, wirePos)) {
                return local;
            }
            BlockState far = acrossRead(level, wirePos, queryPos);
            if (far != null) {
                connReads++;
                // UNION: prefer local whenever it is itself connectable; the far candidate serves
                // only the direction whose local side is genuinely empty (class note).
                boolean localConn = local.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE)
                    || local.isSignalSource();
                boolean farConn = far.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE)
                    || far.isSignalSource();
                if (!localConn && farConn) {
                    connHits++;
                    return far;
                }
            }
            return local;
        }
        catch (Throwable t) {
            fault(t);
            return local;
        }
    }

    /**
     * The shared geometry: classify {@code queryPos} against {@code wirePos}, resolve the crossing
     * binding, read the far cell (or its above/below) with the never-load guard, and rotate the
     * answer into the near frame. Null = "not a crossing read; use the local answer".
     */
    @Nullable
    private static BlockState acrossRead(Level level, BlockPos wirePos, BlockPos queryPos) {
        int dx = queryPos.getX() - wirePos.getX();
        int dy = queryPos.getY() - wirePos.getY();
        int dz = queryPos.getZ() - wirePos.getZ();
        // Own-column reads (P, P.above()) and anything beyond one step in-plane are never bridged.
        if ((dx == 0 && dz == 0) || Math.abs(dx) + Math.abs(dz) != 1 || Math.abs(dy) > 1) {
            return null;
        }
        Direction step = dx != 0
            ? (dx > 0 ? Direction.EAST : Direction.WEST)
            : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        SeamRegistry.SeamBinding b = SeamRegistry.bindingAcross(level, wirePos, step);
        if (b == null || !b.seamContinuous()) {
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
            // Cold far side: report vanilla's local answer now, retry the wire when it warms.
            SeamSignalContinuity.wireDeclineCold(level, wirePos, far, target);
            return null;
        }
        BlockState state = far.getBlockState(target);
        // Into the near frame — wire is rotation-invariant, repeaters/observers are not.
        return state.rotate(SeamShadowBridge.inverse(b.stateRotation()));
    }

    /** The common gate: levers, config, server thread, and the one-field hot-path fold. */
    private static boolean inert(Level level, BlockPos wirePos) {
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
            || AperturePassthroughLever.DISABLE_SEAM_WIRE
            || !SeamlessPortalsConfig.isEntityPortals()
            || !(level instanceof ServerLevel src)) {
            return true;
        }
        MinecraftServer server = src.getServer();
        if (server == null || !server.isSameThread()) {
            return true;
        }
        return !SeamRegistry.sectionHasSeam(level, wirePos);
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
            + " connReads=" + connReads + " connHits=" + connHits;
    }
}
