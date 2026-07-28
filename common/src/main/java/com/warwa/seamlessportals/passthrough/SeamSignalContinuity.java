package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Sub-feature (c) — REDSTONE SIGNAL ACROSS THE SEAM. Spec: {@code migration/REDSTONE_C_SPEC.md}.
 *
 * <p>Three mechanisms, none of which mirrors a block (§0.1 — signal is not a machine write):
 * <ul>
 *   <li><b>R-UNION</b> {@link #hasNeighborSignalAcross} — a seam cell's neighbor-signal test is the
 *       union of BOTH images' neighborhoods. Purely additive: it can only add power vanilla would
 *       miss, never remove any, so vanilla non-regression for all-local circuits is a theorem (the
 *       (b) R1&#x2032; argument).</li>
 *   <li><b>R-WALK</b> {@link #walkRedirect} — the powered-rail chain walk
 *       ({@code PoweredRailBlock.findPoweredRailSignal}) continues in the far level when a step
 *       leaves a bound seam cell through the plane. The far remainder is VANILLA code running
 *       natively in the far level; vanilla's own {@code searchDepth} carries the global 8-cap
 *       across dimensions for free, and further seams re-enter the wrap (multi-portal chains).</li>
 *   <li><b>D1 DISPATCH</b> {@link #onSeamCellChanged} — a state change AT a bound seam cell queues
 *       a {@code neighborChanged} for the counterpart cell across each binding, flushed at END OF
 *       TICK. This is what wakes the far side when the mirror does not (DISJOINT seams are
 *       phase-gated out of mirroring; shape sync declines independently-built pairs; the authority
 *       revert needs the player half re-evaluated). Where the mirror DOES write, its flags-515 far
 *       write already notifies far neighbors — D1 there is a deduped, idempotent echo.</li>
 * </ul>
 *
 * <h2>The two hard rules (spec F8 — the {@code RedStoneWireBlock.shouldSignal} singleton)</h2>
 * The wire singleton's {@code shouldSignal} bracket has no try/finally and an unconditional
 * restore, and the flag is shared by every level. Therefore: (1) every read entry here is
 * side-effect-free and exception-proof — a Throwable is caught, warned once, and answered with the
 * vanilla value; (2) dispatch NEVER runs inline with the triggering write — it is queued and
 * flushed from the server-tick-end hook, where no evaluation bracket is open.
 *
 * <p>All mutable state is server-thread confined: every entry point refuses non-{@code ServerLevel}
 * receivers and off-thread callers, and the flush runs on the server thread.
 */
public final class SeamSignalContinuity {

    private SeamSignalContinuity() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int QUEUE_CAP = 256;
    private static final int MAX_DISPATCH_PER_TICK = 64;

    private static long unionReads, unionHits, walkCrossed, dispatchQueued, dispatchDelivered,
        dispatchDeduped, dispatchDropped, declinedCold, budgetTrips;
    private static int dispatchedThisTick = 0;
    private static boolean readFaultWarned = false;

    /**
     * One queued cross-seam re-evaluation. {@code waitFor} non-null = a cold-far retry: hold the
     * entry until that chunk warms rather than force-loading (the (b) rule — and the retry waits on
     * the chunk that was actually cold, the (b) panel's fix, not on the owner's own).
     */
    private record Dispatch(GlobalPos target, Block sourceBlock, @Nullable GlobalPos waitFor) {}

    private static final ArrayDeque<Dispatch> QUEUE = new ArrayDeque<>();
    /** Per-tick enqueue dedupe — one delivery per cell per tick bounds any cross-seam cycle. */
    private static final Set<GlobalPos> DEDUPE = new HashSet<>();

    /**
     * ★ THE IDENTITY OF THE CELL THE MIRROR IS CURRENTLY WRITING (adversarial panel, 2026-07-27 —
     * the round's BLOCKER). D1's first build skipped on {@code SeamMirror.isApplying()} — a GLOBAL
     * flag whose bracket spans the mirror's far write INCLUDING the far level's inline neighbor
     * cascade ({@code CollectingNeighborUpdater.addAndRun} drains immediately when that level's
     * updater is idle, which a cross-dim destination's always is). A cascade-induced flip at some
     * OTHER far seam cell inside that window was swallowed by the global skip — its counterpart
     * never notified, the pair permanently divergent. The skip must exclude only the mirror's OWN
     * write target, so it is keyed by identity: {@code SeamMirror} brackets each of its writes with
     * {@link #beginMirrorWrite}/{@link #endMirrorWrite}, and D1 skips exactly that cell.
     */
    private static final ArrayDeque<GlobalPos> MIRROR_WRITES = new ArrayDeque<>();

    /**
     * The ORIGIN of the powered-rail walk currently on the stack (the rail whose {@code updateState}
     * started it) — set by the outermost M1 walk wrap, server-thread confined. A cold-far decline
     * must retry the ORIGIN, not the seam cell the walk crossed at: the seam cell is already
     * POWERED (the walk only crosses through powered rails), so re-evaluating it on warm-up writes
     * nothing and notifies nobody (F5), leaving the origin stale forever. (Panel finding.)
     */
    @Nullable
    private static GlobalPos walkOrigin;

    public static void beginMirrorWrite(Level level, BlockPos pos) {
        if (level instanceof ServerLevel sl) {
            MIRROR_WRITES.push(GlobalPos.of(sl.dimension(), pos.immutable()));
        }
    }

    public static void endMirrorWrite(Level level) {
        if (level instanceof ServerLevel) {
            MIRROR_WRITES.poll();
        }
    }

    private static boolean isMirrorWriteTarget(ServerLevel level, BlockPos pos) {
        return !MIRROR_WRITES.isEmpty()
            && MIRROR_WRITES.contains(GlobalPos.of(level.dimension(), pos));
    }

    /** @return true when this call OWNS the origin slot and must call {@link #walkOriginEnd}. */
    public static boolean walkOriginBegin(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl) || walkOrigin != null) {
            return false;
        }
        walkOrigin = GlobalPos.of(sl.dimension(), pos.immutable());
        return true;
    }

    public static void walkOriginEnd() {
        walkOrigin = null;
    }

    /** The result of translating one powered-rail walk step through a seam. */
    public record WalkRedirect(ServerLevel farLevel, BlockPos farPos, boolean forward, RailShape dirShape) {}

    // =============================================================================================
    // R-UNION
    // =============================================================================================

    /**
     * The far side's contribution to {@code hasNeighborSignal(pos)} at a bound seam cell, or false
     * when none. Callers use {@code local || this} — additive only.
     *
     * <p>COINCIDENT: the pair is one logical block; the contribution is the far image's own
     * six-neighbor scan, hand-rolled with a per-neighbor chunk-residency guard (vanilla
     * {@code hasNeighborSignal} would blocking-load a cold border chunk — the (b) B-4b hazard). A
     * cold neighbor contributes 0 and queues a warm-up retry that re-evaluates THIS cell.
     *
     * <p>DISJOINT: the far flush counterpart stands in for the (unreachable) neighbor across the
     * plane — exactly the one directional probe vanilla would make if it were adjacent, with the
     * query direction carried through the binding's rotation.
     */
    public static boolean hasNeighborSignalAcross(Level level, BlockPos pos) {
        try {
            if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
                || !SeamlessPortalsConfig.isEntityPortals()
                || !(level instanceof ServerLevel src)
                || !SeamRegistry.sectionHasSeam(level, pos)) {
                return false;
            }
            MinecraftServer server = src.getServer();
            if (server == null || !server.isSameThread()) {
                return false;
            }
            SeamRegistry.SeamCell cell = SeamRegistry.lookup(level, pos);
            if (cell == null) {
                return false;
            }
            unionReads++;
            BlockPos lastTarget = null;
            ResourceKey<Level> lastDim = null;
            for (SeamRegistry.SeamBinding b : cell.bindings()) {
                if (!b.isMirrorable() || !b.seamContinuous()) {
                    continue;
                }
                boolean coincident = b.phase() == SeamMap.SeamPhase.COINCIDENT;
                BlockPos target = coincident ? b.destPos() : b.continuationToward(b.crossDir());
                if (target == null || (target.equals(lastTarget) && b.destDim().equals(lastDim))) {
                    continue;   // cluster twin resolves to the same cells — probe once
                }
                lastTarget = target;
                lastDim = b.destDim();
                ServerLevel far = server.getLevel(b.destDim());
                if (far == null) {
                    continue;
                }
                if (coincident) {
                    for (Direction d : Direction.values()) {
                        BlockPos n = target.relative(d);
                        if (!far.isInsideBuildHeight(n) || !far.hasChunkAt(n)) {
                            declineCold(src, pos, far, n);
                            continue;
                        }
                        if (guardedFarSignal(far, n, d, src, pos) > 0) {
                            unionHits++;
                            probeLog("union hit at {} in {} via far neighbor {} {}",
                                pos, src.dimension().identifier(), n, d);
                            return true;
                        }
                    }
                }
                else {
                    if (!far.isInsideBuildHeight(target) || !far.hasChunkAt(target)) {
                        declineCold(src, pos, far, target);
                        continue;
                    }
                    if (guardedFarSignal(far, target, SeamRegistry.mapDir(b, b.crossDir()), src, pos) > 0) {
                        unionHits++;
                        probeLog("union hit at {} in {} via disjoint counterpart {}",
                            pos, src.dimension().identifier(), target);
                        return true;
                    }
                }
            }
            return false;
        }
        catch (Throwable t) {
            readFault(t);
            return false;
        }
    }

    /**
     * Vanilla {@code SignalGetter.getSignal} hand-rolled with per-read chunk-residency guards
     * (panel finding: the one-hop guard was not enough — a CONDUCTOR neighbor fans the read out to
     * ITS six neighbors via {@code getDirectSignalTo}, and any of those can sit in a cold chunk,
     * where vanilla's read blocking-loads it on the server thread — the (b) B-4b hazard). The
     * caller guarantees {@code n} itself is resident; cold second-hop neighbors contribute 0 and
     * queue a warm-up retry.
     */
    private static int guardedFarSignal(
        ServerLevel far, BlockPos n, Direction d, ServerLevel ctxLevel, BlockPos ctxPos
    ) {
        BlockState st = far.getBlockState(n);
        int sig = st.getSignal(far, n, d);
        if (sig < 15 && st.isRedstoneConductor(far, n)) {
            for (Direction dd : Direction.values()) {
                BlockPos m = n.relative(dd);
                if (!far.isInsideBuildHeight(m) || !far.hasChunkAt(m)) {
                    declineCold(ctxLevel, ctxPos, far, m);
                    continue;
                }
                sig = Math.max(sig, far.getBlockState(m).getDirectSignal(far, m, dd));
                if (sig >= 15) {
                    break;
                }
            }
        }
        return sig;
    }

    // =============================================================================================
    // R-WALK
    // =============================================================================================

    /**
     * Translate one powered-rail walk step through the seam, or null when this step does not cross
     * one (the overwhelmingly common answer, decided by one section-set read). Called by the
     * {@code findPoweredRailSignal} wrap ONLY after the local probe answered false — local-first.
     *
     * <p>{@code forward} is derived from vanilla's own stepping convention (spec F4:
     * NORTH_SOUTH forward = +Z/SOUTH, EAST_WEST forward = −X/WEST), applied to the step direction
     * carried through the binding's rotation. {@code dirShape} is always a straight shape here —
     * {@code findPoweredRailSignal} normalizes ascending shapes before stepping.
     */
    @Nullable
    public static WalkRedirect walkRedirect(
        Level level, BlockPos currentPos, BlockPos steppedPos, RailShape dirShape
    ) {
        try {
            if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
                || !SeamlessPortalsConfig.isEntityPortals()
                || !(level instanceof ServerLevel src)
                || !SeamRegistry.sectionHasSeam(level, currentPos)) {
                return null;
            }
            SeamRegistry.SeamCell owner = SeamRegistry.lookup(level, currentPos);
            if (owner == null) {
                return null;
            }
            SeamShadow s = SeamShadowBridge.shadowFor(level, owner, currentPos, steppedPos, 1);
            if (s == null) {
                // Instrument-gap closure (live round): a step that SHOULD cross (some binding maps
                // it) but got no shadow is a silent seam-stop — the exact class the walk-DIED
                // probe cannot see. Name it, with the per-binding reason.
                if (AperturePassthroughLever.SEAM_SIGNAL_PROBE) {
                    for (SeamRegistry.SeamBinding b : owner.bindings()) {
                        if (b.continuationToward(seamlessportals$stepOf(currentPos, steppedPos)) != null) {
                            probeLog("walk step at seam cell {} toward {} did NOT redirect —"
                                    + " binding declined (continuous={} mirrorable={} phase={})",
                                currentPos, steppedPos, b.seamContinuous(), b.isMirrorable(),
                                b.phase());
                            break;
                        }
                    }
                }
                return null;
            }
            if (!s.farResident(steppedPos)) {
                declineCold(src, currentPos, s.farLevel(), s.toFar(steppedPos));
                return null;
            }
            BlockPos farPos = s.toFar(steppedPos);
            Direction stepDirFar = s.srcToDst().rotate(s.crossStep());
            // ★ ENVELOPE RESIDENCY PRE-GATE (panel finding): once redirected, the remainder of the
            // walk is VANILLA code whose reads are unguarded — up to 8 cells along the far axis,
            // each with a 6-neighbor scan. Golden rails are straight-only, so the whole reach fits
            // an 8×3 plan box from farPos along the far step; if any of its (≤4) chunk columns is
            // cold, decline + retry rather than let vanilla blocking-load it mid-walk.
            ServerLevel farLevel = s.farLevel();
            BlockPos envEnd = farPos.relative(stepDirFar, 7);
            int minX = Math.min(farPos.getX(), envEnd.getX()) - 1;
            int maxX = Math.max(farPos.getX(), envEnd.getX()) + 1;
            int minZ = Math.min(farPos.getZ(), envEnd.getZ()) - 1;
            int maxZ = Math.max(farPos.getZ(), envEnd.getZ()) + 1;
            int y = farPos.getY();
            for (BlockPos corner : new BlockPos[] {
                new BlockPos(minX, y, minZ), new BlockPos(minX, y, maxZ),
                new BlockPos(maxX, y, minZ), new BlockPos(maxX, y, maxZ)
            }) {
                if (!farLevel.hasChunkAt(corner)) {
                    declineCold(src, currentPos, farLevel, corner);
                    return null;
                }
            }
            RailShape farDirShape = rotateStraightShape(dirShape, s.srcToDst());
            boolean farForward = farDirShape == RailShape.NORTH_SOUTH
                ? stepDirFar == Direction.SOUTH
                : stepDirFar == Direction.WEST;
            walkCrossed++;
            probeLog("walk crossed at {} step {} -> {} in {} (forward={} shape={})",
                currentPos, s.crossStep(), farPos, farLevel.dimension().identifier(),
                farForward, farDirShape);
            return new WalkRedirect(farLevel, farPos, farForward, farDirShape);
        }
        catch (Throwable t) {
            readFault(t);
            return null;
        }
    }

    /** The horizontal axis step from a walk's current cell to its stepped target (probe use only). */
    private static Direction seamlessportals$stepOf(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dz == 0 && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dx == 0 && dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return Direction.UP;   // not an axis step; continuationToward(UP) is null for every binding
    }

    /** Quarter turns swap the two straight axes; NONE/180 preserve them. Only straight shapes reach here. */
    private static RailShape rotateStraightShape(RailShape shape, net.minecraft.world.level.block.Rotation r) {
        boolean quarter = r == net.minecraft.world.level.block.Rotation.CLOCKWISE_90
            || r == net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
        if (!quarter) {
            return shape;
        }
        return shape == RailShape.NORTH_SOUTH ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
    }

    // =============================================================================================
    // D1 DISPATCH
    // =============================================================================================

    /**
     * Called from the {@code LevelChunkSetBlockStateMixin} driver (server branch, beside the mirror
     * driver) for every settled state change at any position; seam relevance is decided here.
     * Queues a cross-seam re-evaluation for each binding's counterpart cell.
     *
     * <p>Skipped while {@link SeamMirror#isApplying()}: the mirror's own writes (including the
     * authority revert) are echoes of a change whose originating side already queued what is
     * needed, and its far write carries UPDATE_NEIGHBORS itself.
     */
    public static void onSeamCellChanged(ServerLevel level, BlockPos pos, BlockState settled) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
            || AperturePassthroughLever.DISABLE_SEAM_SIGNAL_DISPATCH
            || !SeamlessPortalsConfig.isEntityPortals()
            || isMirrorWriteTarget(level, pos)) {
            return;   // the mirror's OWN write target only — cascade flips at OTHER seam cells
                      // inside the applying bracket MUST still dispatch (panel BLOCKER)
        }
        SeamRegistry.SeamCell cell = SeamRegistry.lookup(level, pos);
        if (cell == null) {
            return;
        }
        Block source = settled.getBlock();
        BlockPos lastTarget = null;
        ResourceKey<Level> lastDim = null;
        for (SeamRegistry.SeamBinding b : cell.bindings()) {
            if (!b.isMirrorable() || !b.seamContinuous()) {
                continue;
            }
            BlockPos target = b.phase() == SeamMap.SeamPhase.COINCIDENT
                ? b.destPos()
                : b.continuationToward(b.crossDir());
            if (target == null || (target.equals(lastTarget) && b.destDim().equals(lastDim))) {
                continue;
            }
            lastTarget = target;
            lastDim = b.destDim();
            queue(GlobalPos.of(b.destDim(), target.immutable()), source, null);
        }
    }

    private static void queue(GlobalPos target, Block sourceBlock, @Nullable GlobalPos waitFor) {
        if (waitFor == null && !DEDUPE.add(target)) {
            dispatchDeduped++;
            return;
        }
        if (QUEUE.size() >= QUEUE_CAP) {
            Dispatch evicted = QUEUE.pollFirst();
            dispatchDropped++;
            if (evicted != null) {
                // Un-dedupe the evicted target so a later same-tick change can re-queue it —
                // without this the dedupe entry pins the loss for the rest of the tick. A drop is
                // still a real (counted, gate-asserted-zero) loss for one-shot events.
                DEDUPE.remove(evicted.target());
            }
        }
        QUEUE.addLast(new Dispatch(target, sourceBlock, waitFor));
        dispatchQueued++;
        probeLog("dispatch queued -> {} {} (waitFor={})", target.dimension().identifier(),
            target.pos(), waitFor);
    }

    /**
     * A cold far chunk answered a read with "nothing there" — correct now, stale forever if nothing
     * revisits. Queue a re-evaluation of the OWNER cell gated on the chunk that was actually cold.
     * Out-of-build-height mappings are permanent: count, never queue (the (b) panel's rule).
     */
    private static void declineCold(ServerLevel contextLevel, BlockPos contextPos,
                                    ServerLevel farLevel, BlockPos farPos) {
        declinedCold++;
        if (!farLevel.isInsideBuildHeight(farPos)) {
            return;
        }
        if (AperturePassthroughLever.DISABLE_SEAM_SIGNAL_DISPATCH) {
            return;   // retries are deliveries; the dispatch lever owns them
        }
        // The retry owner is the WALK ORIGIN when a walk is on the stack (panel finding — the
        // context cell there is the seam cell, already powered, whose re-evaluation would write
        // nothing and wake nobody); otherwise the evaluating cell itself.
        GlobalPos owner = walkOrigin != null
            ? walkOrigin
            : GlobalPos.of(contextLevel.dimension(), contextPos.immutable());
        ServerLevel ownerLevel = contextLevel.getServer() == null
            ? null : contextLevel.getServer().getLevel(owner.dimension());
        Block hint = ownerLevel != null && ownerLevel.hasChunkAt(owner.pos())
            ? ownerLevel.getBlockState(owner.pos()).getBlock()
            : net.minecraft.world.level.block.Blocks.AIR;
        queue(owner, hint, GlobalPos.of(farLevel.dimension(), farPos.immutable()));
    }

    /**
     * Flush: reset the per-tick budget, then deliver queued re-evaluations as plain vanilla
     * {@code neighborChanged(pos, block, null)} — null Orientation is exactly what the default
     * (non-experimental) pipeline carries everywhere (spec F7), and the far level's own
     * {@code CollectingNeighborUpdater} owns re-entrancy and chain limits from there. Registered in
     * {@code AperturePassthroughInit} beside {@link SeamRailContinuity#onServerTickEnd}.
     */
    public static void onServerTickEnd(MinecraftServer server) {
        dispatchedThisTick = 0;
        DEDUPE.clear();
        if (QUEUE.isEmpty()) {
            return;
        }
        int size = QUEUE.size();
        for (int i = 0; i < size; i++) {
            Dispatch d = QUEUE.pollFirst();
            if (d == null) {
                break;
            }
            ServerLevel lvl = server.getLevel(d.target().dimension());
            if (lvl == null) {
                continue;   // dimension gone; drop
            }
            if (d.waitFor() != null) {
                ServerLevel waitLvl = server.getLevel(d.waitFor().dimension());
                if (waitLvl == null) {
                    continue;
                }
                if (!waitLvl.hasChunkAt(d.waitFor().pos())) {
                    QUEUE.addLast(d);   // still cold; keep waiting, never force-load
                    continue;
                }
            }
            if (!lvl.hasChunkAt(d.target().pos())) {
                QUEUE.addLast(new Dispatch(d.target(), d.sourceBlock(), d.target()));
                continue;   // target itself went cold; wait on it
            }
            if (dispatchedThisTick >= MAX_DISPATCH_PER_TICK) {
                QUEUE.addFirst(d);
                budgetTrips++;
                break;   // leftover flushes next tick — delayed, never lost
            }
            dispatchedThisTick++;
            dispatchDelivered++;
            probeLog("dispatch delivered -> {} {}", lvl.dimension().identifier(), d.target().pos());
            try {
                lvl.neighborChanged(d.target().pos(), d.sourceBlock(), null);
            }
            catch (Throwable t) {
                LOGGER.warn("[RS-SIGNAL] cross-seam neighborChanged failed at {} in {}",
                    d.target().pos(), lvl.dimension().identifier(), t);
            }
        }
    }

    // =============================================================================================
    // ACCOUNTING
    // =============================================================================================

    /** Read-path fault: never rethrow (spec F8 — an escape inside a wire bracket mutes all wire). */
    private static void readFault(Throwable t) {
        if (!readFaultWarned) {
            readFaultWarned = true;
            LOGGER.warn("[RS-SIGNAL] cross-seam read fault (answering vanilla; logged once)", t);
        }
    }

    private static void probeLog(String msg, Object... args) {
        if (AperturePassthroughLever.SEAM_SIGNAL_PROBE) {
            LOGGER.info("[RS-SIGNAL] " + msg, args);
        }
    }

    /**
     * A redirected walk returned FALSE — log WHAT the landing cell held, so a live log names the
     * kill reason directly: wrong block (golden chains die on plain/detector rails — vanilla),
     * not POWERED (the pair's powered state diverged — shape-sync/provenance family), or an
     * incompatible SHAPE (the pair's shapes diverged — the independent-pair §6.9 family).
     */
    public static void probeWalkDied(
        ServerLevel farLevel, BlockPos farPos, RailShape expectedAxis, Block walkingBlock
    ) {
        if (!AperturePassthroughLever.SEAM_SIGNAL_PROBE) {
            return;
        }
        try {
            BlockState st = farLevel.getBlockState(farPos);
            String detail;
            if (!st.is(walkingBlock)) {
                detail = "WRONG BLOCK (walk carries " + walkingBlock + ", cell holds "
                    + st.getBlock() + " — golden chains die on other rail types, vanilla rule)";
            }
            else {
                var shapeProp = ((net.minecraft.world.level.block.BaseRailBlock) st.getBlock())
                    .getShapeProperty();
                RailShape shape = st.getValue(shapeProp);
                boolean powered = st.hasProperty(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)
                    && st.getValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.POWERED);
                detail = "shape=" + shape + " (walk axis " + expectedAxis + ") powered=" + powered
                    + (!powered ? " — UNPOWERED (powered-state divergence family)" : "")
                    + (isAxisIncompatible(shape, expectedAxis)
                        ? " — SHAPE INCOMPATIBLE (shape divergence family, spec §6.9)" : "");
            }
            LOGGER.info("[RS-SIGNAL] walk DIED at {} in {}: {}",
                farPos, farLevel.dimension().identifier(), detail);
        }
        catch (Throwable t) {
            readFault(t);
        }
    }

    /** Mirrors isSameRailWithPower's shape-compat test (PoweredRailBlock.java:112-113). */
    private static boolean isAxisIncompatible(RailShape shape, RailShape dir) {
        if (dir == RailShape.EAST_WEST) {
            return shape == RailShape.NORTH_SOUTH || shape == RailShape.ASCENDING_NORTH
                || shape == RailShape.ASCENDING_SOUTH;
        }
        if (dir == RailShape.NORTH_SOUTH) {
            return shape == RailShape.EAST_WEST || shape == RailShape.ASCENDING_EAST
                || shape == RailShape.ASCENDING_WEST;
        }
        return false;
    }

    public static String counters() {
        return "unionReads=" + unionReads
            + " unionHits=" + unionHits
            + " walkCrossed=" + walkCrossed
            + " dispatchQueued=" + dispatchQueued
            + " dispatchDelivered=" + dispatchDelivered
            + " dispatchDeduped=" + dispatchDeduped
            + " dispatchDropped=" + dispatchDropped
            + " declinedCold=" + declinedCold
            + " budgetTrips=" + budgetTrips;
    }

    public static long walkCrossedCount() {
        return walkCrossed;
    }

    public static long unionHitsCount() {
        return unionHits;
    }

    public static long dispatchDeliveredCount() {
        return dispatchDelivered;
    }

    public static long dispatchDroppedCount() {
        return dispatchDropped;
    }
}
