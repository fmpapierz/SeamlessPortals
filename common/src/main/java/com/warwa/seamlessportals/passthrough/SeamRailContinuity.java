package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayDeque;

/**
 * Sub-feature (b) accounting, budgets and the reseed/retry machinery — the parts of rail
 * continuation that are not per-query.
 *
 * <h2>Budgets (update-loop safety T5)</h2>
 * Cross-seam WRITES are bounded twice: a per-stack depth cap ({@link #MAX_DEPTH}) and a per-tick
 * budget ({@link #MAX_CROSS_WRITES_PER_TICK}), both enforced in {@link #enterWrite}/{@link #exitWrite}
 * around the actual {@code setBlock}. Tripping either counter is evidence of a design fault, not a
 * licence to run — the gametest legs assert both stay ZERO. The structural termination argument is
 * elsewhere ((b) only ever rewrites the SHAPE of an existing rail, and a shape-only write cannot
 * re-enter {@code onPlace} — {@code BaseRailBlock.onPlace:65} guards on the block changing); the
 * budgets exist so that if that argument is ever wrong, a tick degrades a track instead of the server.
 *
 * <h2>Reseed (the one dispatch (b) actually needs)</h2>
 * Both propagation directions are already self-driving through vanilla's own resolution — a rail
 * placed on either side runs {@code RailState.place}, whose probes the shadow answers. The genuine
 * gap is a portal LIT OVER AN EXISTING TRACK (or torn down across one): no block changed, so no
 * resolution fires. {@link #reseedOnBind} re-runs vanilla shape resolution at every bound rail cell
 * and its cross counterparts when a portal binds. (a) explicitly supports re-lighting a frame over a
 * surviving rail, so this is a live path, not an edge case.
 *
 * <h2>Retry (cold far chunk)</h2>
 * {@link SeamShadow} declines a cold far chunk rather than force-loading mid-resolution — correct at
 * that instant, permanently wrong afterwards if nothing revisits: the shape settled from a half-view
 * of the world and no later event re-resolves it. Declines enqueue the OWNER cell here (bounded,
 * dropping oldest) and {@link #onServerTickEnd} re-resolves entries whose chunk has warmed up.
 *
 * <p>All mutable state is server-thread confined: {@code SeamShadowBridge.shadowFor} refuses
 * off-thread callers, and the tick-end hook runs on the server thread.
 */
public final class SeamRailContinuity {

    private SeamRailContinuity() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    static final int MAX_DEPTH = 4;
    static final int MAX_CROSS_WRITES_PER_TICK = 512;
    private static final int RETRY_CAP = 256;

    private static int depth = 0;
    private static int writesThisTick = 0;

    private static long crossReads, crossHits, crossWrites, declinedCold,
        depthCapTrips, budgetTrips, reseeds, retriesQueued, retriesServed, retriesDropped;

    /**
     * A queued re-resolution: {@code owner} is what to re-resolve; {@code waitFor} is the chunk
     * whose absence caused the decline. They differ for a cold FAR side — gating the retry on the
     * owner's own chunk (which is loaded by construction, a resolution was just running there)
     * busy-served every cold entry once per tick until the far side happened to load.
     * (Panel finding, 2026-07-27.)
     */
    private record Retry(GlobalPos owner, GlobalPos waitFor) {}

    private static final ArrayDeque<Retry> RETRY = new ArrayDeque<>();

    // =============================================================================================
    // BUDGETS
    // =============================================================================================

    static boolean enterWrite() {
        if (depth >= MAX_DEPTH) {
            depthCapTrips++;
            return false;
        }
        if (writesThisTick >= MAX_CROSS_WRITES_PER_TICK) {
            budgetTrips++;
            return false;
        }
        depth++;
        writesThisTick++;
        crossWrites++;
        return true;
    }

    static void exitWrite() {
        depth--;
    }

    // =============================================================================================
    // COUNTERS
    // =============================================================================================

    static void crossRead() {
        crossReads++;
    }

    /** Public because the consumer mixin lives in {@code mixin.passthrough}, a different package. */
    public static void crossHit() {
        crossHits++;
    }

    /** {@code coldFar} null = the decline is permanent (out of build height); count, never queue. */
    static void declinedCold(
        ServerLevel sourceLevel, BlockPos ownerCell,
        @org.jetbrains.annotations.Nullable GlobalPos coldFar
    ) {
        declinedCold++;
        if (coldFar == null) {
            return;
        }
        queueRetry(
            GlobalPos.of(sourceLevel.dimension(), ownerCell.immutable()),
            coldFar);
    }

    public static String counters() {
        return "crossReads=" + crossReads
            + " crossHits=" + crossHits
            + " crossWrites=" + crossWrites
            + " declinedCold=" + declinedCold
            + " depthCapTrips=" + depthCapTrips
            + " budgetTrips=" + budgetTrips
            + " reseeds=" + reseeds
            + " retriesQueued=" + retriesQueued
            + " retriesServed=" + retriesServed
            + " retriesDropped=" + retriesDropped;
    }

    public static long depthCapTrips() {
        return depthCapTrips;
    }

    public static long budgetTrips() {
        return budgetTrips;
    }

    public static long crossHitsCount() {
        return crossHits;
    }

    // =============================================================================================
    // RESEED
    // =============================================================================================

    /**
     * Re-run vanilla shape resolution at one cell, exactly as a neighbour change would have.
     *
     * <p>SERVER ONLY, and not merely by convention: this deliberately bypasses
     * {@code BaseRailBlock.updateDir}, whose {@code isClientSide} early-return is the ONLY client
     * guard in the vanilla rail system — {@code RailState.place} itself writes unguarded. A client
     * reseed would mutate shapes with no server counterpart and no correcting packet.
     */
    public static void reresolve(ServerLevel lvl, BlockPos p) {
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_SHADOW
            || AperturePassthroughLever.DISABLE_SEAM_RAIL_RESEED) {
            return;
        }
        if (!lvl.hasChunkAt(p)) {
            GlobalPos self = GlobalPos.of(lvl.dimension(), p.immutable());
            queueRetry(self, self);   // owner chunk itself is what must warm up
            return;
        }
        BlockState st = lvl.getBlockState(p);
        if (!BaseRailBlock.isRail(st)) {
            return;
        }
        BaseRailBlock block = (BaseRailBlock) st.getBlock();
        RailShape shape = st.getValue(block.getShapeProperty());
        // first=false keeps RailState.place's idempotence guard (:332) live — an already-correct
        // shape writes nothing and notifies nobody.
        new net.minecraft.world.level.block.RailState(lvl, p, st)
            .place(lvl.hasNeighborSignal(p), false, shape);
        reseeds++;
        if (AperturePassthroughLever.SEAM_RAIL_PROBE) {
            LOGGER.info("[RS-RAIL] reseed at {} in {} -> {}", p, lvl.dimension().identifier(),
                lvl.getBlockState(p));
        }
    }

    /**
     * Called after a portal (re)binds, from the same server-side hook as bind-time reconciliation.
     * Re-resolves every bound cell that holds a rail, plus each binding's continuation cells, so a
     * track that predates the portal joins up without any block having changed.
     */
    public static void reseedOnBind(Portal portal) {
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_SHADOW
            || AperturePassthroughLever.DISABLE_SEAM_RAIL_RESEED) {
            return;
        }
        if (!(portal.level() instanceof ServerLevel srcLevel)) {
            return;
        }
        MinecraftServer server = srcLevel.getServer();
        if (server == null) {
            return;
        }
        try {
            for (net.minecraft.world.phys.Vec3 column : SeamMap.enumerateColumns(portal)) {
                BlockPos src = SeamMap.seamCell(portal, column);
                SeamRegistry.SeamCell cell = SeamRegistry.lookup(srcLevel, src);
                if (cell == null) {
                    continue;
                }
                reresolve(srcLevel, src);
                for (SeamRegistry.SeamBinding b : cell.bindings()) {
                    if (!b.seamContinuous() || !b.isMirrorable()) {
                        continue;
                    }
                    ServerLevel far = server.getLevel(b.destDim());
                    if (far == null) {
                        continue;
                    }
                    BlockPos cross = b.continuationToward(b.crossDir());
                    if (cross != null) {
                        reresolve(far, cross);
                    }
                    BlockPos fallback = b.continuationToward(b.srcFacing());
                    if (fallback != null) {
                        reresolve(far, fallback);
                    }
                }
            }
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-RAIL] reseed failed for portal {} (tracks keep their current shapes)",
                portal.getUUID(), t);
        }
    }

    // =============================================================================================
    // RETRY
    // =============================================================================================

    private static void queueRetry(GlobalPos owner, GlobalPos waitFor) {
        if (AperturePassthroughLever.DISABLE_SEAM_RAIL_RESEED) {
            return;
        }
        Retry entry = new Retry(owner, waitFor);
        if (RETRY.contains(entry)) {
            return;
        }
        if (RETRY.size() >= RETRY_CAP) {
            RETRY.pollFirst();
            retriesDropped++;
        }
        RETRY.addLast(entry);
        retriesQueued++;
    }

    /** Reset the per-tick budget and serve retries whose awaited chunk has warmed up. */
    public static void onServerTickEnd(MinecraftServer server) {
        writesThisTick = 0;
        depth = 0;
        if (RETRY.isEmpty()) {
            return;
        }
        int size = RETRY.size();
        for (int i = 0; i < size; i++) {
            Retry entry = RETRY.pollFirst();
            if (entry == null) {
                break;
            }
            ServerLevel waitLvl = server.getLevel(entry.waitFor().dimension());
            if (waitLvl == null) {
                continue;   // dimension gone; drop
            }
            if (!waitLvl.hasChunkAt(entry.waitFor().pos())) {
                RETRY.addLast(entry);   // still cold; keep waiting, never force-load
                continue;
            }
            ServerLevel ownerLvl = server.getLevel(entry.owner().dimension());
            if (ownerLvl == null) {
                continue;
            }
            retriesServed++;
            reresolve(ownerLvl, entry.owner().pos());
        }
    }
}
