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
        dispatchDeduped, dispatchDropped, declinedCold, budgetTrips, wakeQueued, wakeDelivered;
    private static int dispatchedThisTick = 0;
    private static boolean readFaultWarned = false;

    /**
     * One queued cross-seam re-evaluation. {@code waitFor} non-null = a cold-far retry: hold the
     * entry until that chunk warms rather than force-loading (the (b) rule — and the retry waits on
     * the chunk that was actually cold, the (b) panel's fix, not on the owner's own).
     * {@code wakeAround} = deliver {@code updateNeighborsAt(target)} (the six cells AROUND the
     * counterpart, never the counterpart itself) instead of {@code neighborChanged(target)} — the
     * shared-pair wake ({@link #onSeamRailPoked}).
     */
    private record Dispatch(
        GlobalPos target, Block sourceBlock, @Nullable GlobalPos waitFor, boolean wakeAround
    ) {}

    private static final ArrayDeque<Dispatch> QUEUE = new ArrayDeque<>();
    /** Per-tick enqueue dedupe — one delivery per cell per tick bounds any cross-seam cycle. */
    private static final Set<GlobalPos> DEDUPE = new HashSet<>();
    /** The wake kind dedupes separately — a wake is not a poke and must not shadow one. */
    private static final Set<GlobalPos> WAKE_DEDUPE = new HashSet<>();

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
                    // F8: the counterpart's behind-plane neighbour belongs to the OTHER stitched
                    // space — skip it when the counterpart is half-claimed (panel finding #2).
                    Direction farEmpty = far.hasChunkAt(target)
                        ? SeamFractional.emptyHalfDir(far, target) : null;
                    for (Direction d : Direction.values()) {
                        if (d == farEmpty) {
                            continue;
                        }
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
     * The far side's contribution to a WIRE's block-power intake
     * ({@code RedStoneWireBlock.getBlockSignal → getBestNeighborSignal}), strength-valued, or 0.
     * Callers use {@code max(local, this)} — additive only. Same geometry and guards as
     * {@link #hasNeighborSignalAcross}; a separate entry because wire needs the STRENGTH (decay
     * arithmetic), not a boolean, and because it runs inside the {@code shouldSignal} window —
     * far-side wire correctly answers 0 there (the latch lives on the shared block singleton), so
     * this carries block power only; wire-to-wire decay is {@link SeamWireBridge}'s read family.
     */
    public static int neighborSignalStrengthAcross(Level level, BlockPos pos) {
        try {
            if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
                || AperturePassthroughLever.DISABLE_SEAM_WIRE
                || !SeamlessPortalsConfig.isEntityPortals()
                || !(level instanceof ServerLevel src)
                || !SeamRegistry.sectionHasSeam(level, pos)) {
                return 0;
            }
            MinecraftServer server = src.getServer();
            if (server == null || !server.isSameThread()) {
                return 0;
            }
            SeamRegistry.SeamCell cell = SeamRegistry.lookup(level, pos);
            if (cell == null) {
                return 0;
            }
            unionReads++;
            int best = 0;
            BlockPos lastTarget = null;
            ResourceKey<Level> lastDim = null;
            for (SeamRegistry.SeamBinding b : cell.bindings()) {
                if (!b.isMirrorable() || !b.seamContinuous()) {
                    continue;
                }
                boolean coincident = b.phase() == SeamMap.SeamPhase.COINCIDENT;
                BlockPos target = coincident ? b.destPos() : b.continuationToward(b.crossDir());
                if (target == null || (target.equals(lastTarget) && b.destDim().equals(lastDim))) {
                    continue;
                }
                lastTarget = target;
                lastDim = b.destDim();
                ServerLevel far = server.getLevel(b.destDim());
                if (far == null) {
                    continue;
                }
                if (coincident) {
                    // F8: skip the counterpart's behind-plane neighbour — the other stitching.
                    Direction farEmpty = far.hasChunkAt(target)
                        ? SeamFractional.emptyHalfDir(far, target) : null;
                    for (Direction d : Direction.values()) {
                        if (d == farEmpty) {
                            continue;
                        }
                        BlockPos n = target.relative(d);
                        if (!far.isInsideBuildHeight(n) || !far.hasChunkAt(n)) {
                            declineCold(src, pos, far, n);
                            continue;
                        }
                        best = Math.max(best, guardedFarSignal(far, n, d, src, pos));
                        if (best >= 15) {
                            unionHits++;
                            return best;
                        }
                    }
                }
                else {
                    if (!far.isInsideBuildHeight(target) || !far.hasChunkAt(target)) {
                        declineCold(src, pos, far, target);
                        continue;
                    }
                    best = Math.max(best,
                        guardedFarSignal(far, target, SeamRegistry.mapDir(b, b.crossDir()), src, pos));
                    if (best >= 15) {
                        unionHits++;
                        return best;
                    }
                }
            }
            if (best > 0) {
                unionHits++;
                probeLog("wire strength union {} at {} in {}",
                    best, pos, src.dimension().identifier());
            }
            return best;
        }
        catch (Throwable t) {
            readFault(t);
            return 0;
        }
    }

    /** {@link SeamWireBridge}'s door into {@link #declineCold} — same retry semantics as any read. */
    public static void wireDeclineCold(Level level, BlockPos wirePos, ServerLevel far, BlockPos farPos) {
        if (level instanceof ServerLevel src) {
            declineCold(src, wirePos, far, farPos);
        }
    }

    /**
     * F8 — the LOCAL half of a claimed cell's own intake: vanilla's six-direction best-neighbour
     * scan minus the empty-half direction (that neighbour is this side's behind-plane region —
     * the other stitched space). Callers resolve {@code emptyDir} themselves via
     * {@link SeamFractional#emptyHalfDir} and fall through to the vanilla operation when it is
     * null; boolean and strength forms for the two consumer families.
     */
    public static boolean hasLocalNeighborSignalSkippingEmptyHalf(
        Level level, BlockPos pos, Direction emptyDir
    ) {
        for (Direction d : Direction.values()) {
            if (d == emptyDir) {
                continue;
            }
            if (level.getSignal(pos.relative(d), d) > 0) {
                return true;
            }
        }
        return false;
    }

    public static int localNeighborSignalSkippingEmptyHalf(
        Level level, BlockPos pos, Direction emptyDir
    ) {
        int best = 0;
        for (Direction d : Direction.values()) {
            if (d == emptyDir) {
                continue;
            }
            best = Math.max(best, level.getSignal(pos.relative(d), d));
            if (best >= 15) {
                return best;
            }
        }
        return best;
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

    /**
     * True when this walk step LEAVES a bound seam cell through the plane with live machinery —
     * so in raw per-dimension coordinates the stepped cell holds the OTHER stitching (the
     * opposite through-path) and must not be consulted (RS-XTALK, user contract 2026-08-22: the
     * two through-paths never interact, even sharing a seam cell or adjacent cells).
     *
     * <p>WHERE the plane sits decides WHICH exits cross, and both refinements came from a red
     * run each (first build severed both axis exits of a COINCIDENT cell unconditionally —
     * killing the seam rail's own depth-0 walk back into its OWN approach, RS-SIGNAL-A red;
     * second build severed a claimed cell's depth-0 step into its empty half — killing the
     * seam rail powering FROM the second path's approach, the user's 2026-08-22 live round):
     * <ul>
     *   <li><b>DISJOINT</b> — the plane is flush with the cell face on the mapped side: every
     *       exit through it crosses, at any depth.</li>
     *   <li><b>COINCIDENT, {@code depth > 0}</b> — the plane bisects the cell and the walk is
     *       monotone, so a walk still running at this cell ENTERED from the opposite side and
     *       crossed the plane inside it: the exit crosses.</li>
     *   <li><b>COINCIDENT, {@code depth == 0}</b> — the cell's own rail is evaluating. An
     *       UNCLAIMED byte-identical pair (command-staged fixtures) is one shared slot serving
     *       both lines: its own two-direction probes stay local. A CLAIMED cell's rail is ONE
     *       path's object (live round 5, "the first set's seam rail won't turn off"): its step
     *       into the empty half crosses (its line continues through its own door in the far
     *       level, and the raw cell there is the other path's territory) — safe now because the
     *       second path's representative at the cell is the side-table FRAGMENT, which walks
     *       resolve via {@link #probeIntoFragmentHalf} and whose state tracks its own circuit.</li>
     * </ul>
     *
     * <p>The M1 wrap asks this BEFORE its local probe: a crossing step is redirect-or-severed,
     * never raw — a cold/unresolvable far side answers false with the retry {@link #walkRedirect}
     * already queued, where the pre-fix order fell back to the raw read (the leak). The gate
     * chain mirrors {@code walkRedirect}+{@code shadowFor} exactly — plus this fix's own lever —
     * so any lever that disables the bridge also restores the pre-fix local-first order.
     */
    public static boolean walkStepCrossesSeam(
        Level level, BlockPos currentPos, BlockPos steppedPos, int depth
    ) {
        try {
            if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
                || AperturePassthroughLever.DISABLE_SEAM_SHADOW
                || AperturePassthroughLever.DISABLE_SEAM_WALK_SEVER
                || !SeamlessPortalsConfig.isEntityPortals()
                || !(level instanceof ServerLevel src)
                || !SeamRegistry.sectionHasSeam(level, currentPos)) {
                return false;
            }
            MinecraftServer server = src.getServer();
            if (server == null || !server.isSameThread()) {
                return false;
            }
            SeamRegistry.SeamCell owner = SeamRegistry.lookup(level, currentPos);
            if (owner == null) {
                return false;
            }
            Direction step = seamlessportals$stepOf(currentPos, steppedPos);
            if (step == Direction.UP) {
                return false;
            }
            for (SeamRegistry.SeamBinding b : owner.bindings()) {
                if (!b.isMirrorable() || !b.seamContinuous()
                    || b.continuationToward(step) == null) {
                    continue;
                }
                if (b.phase() == SeamMap.SeamPhase.DISJOINT || depth > 0) {
                    return true;
                }
                if (SeamFractional.emptyHalfDir(level, currentPos) == step) {
                    return true;   // claimed cell's own step into its empty half — its line
                                   // crosses; the raw cell there is the other path's territory
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
     * True when this is a claimed cell's OWN depth-0 step toward its CLAIMED side — a probe that
     * must be strictly LOCAL (ARM 3's deterministic repro, 2026-08-23): after the local probe
     * fails, the wrap's additive walkRedirect fallback maps this direction to the CO-LOCATED far
     * cell — the OTHER path's far territory — and a far source there lit the pair from the wrong
     * circuit ("path-2-only: S=true D=true"). The claimed cell's line on this side is its local
     * approach and nothing else; its far continuation is the EMPTY-half step's door, which the
     * depth-0 crossing rule already routes. Unclaimed cells keep the additive fallback.
     */
    public static boolean walkStepIsClaimedOwnSide(
        Level level, BlockPos currentPos, BlockPos steppedPos, int depth
    ) {
        try {
            if (depth > 0 || AperturePassthroughLever.DISABLED
                || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
                || AperturePassthroughLever.DISABLE_SEAM_SHADOW
                || AperturePassthroughLever.DISABLE_SEAM_WALK_SEVER
                || !SeamlessPortalsConfig.isEntityPortals()
                || !(level instanceof ServerLevel)
                || !SeamRegistry.sectionHasSeam(level, currentPos)
                || SeamRegistry.lookup(level, currentPos) == null) {
                return false;
            }
            Direction step = seamlessportals$stepOf(currentPos, steppedPos);
            if (step == Direction.UP) {
                return false;
            }
            Direction empty = SeamFractional.emptyHalfDir(level, currentPos);
            return empty != null && empty == step.getOpposite();
        }
        catch (Throwable t) {
            readFault(t);
            return false;
        }
    }

    /** The arriving-side resolution of a walk probe into a claimed seam cell — see {@link #probeIntoFragmentHalf}. */
    public record IntoProbe(boolean passable, boolean localSignal, @Nullable WalkRedirect farDoor) {}

    /**
     * ★ THE ARRIVING-SIDE GATE (RS-XTALK live round 5 — full per-path model). A walk probing
     * INTO a claimed seam cell must ask the occupant of the half it ARRIVES on: from the
     * primary's side, the chunk rail (vanilla probe — return null); from the empty half, the
     * side-table FRAGMENT — the second path's rail — which the chunk-reading vanilla probe
     * cannot see. Without this, the second path's passability rode the FIRST path's POWERED
     * bit, which forced the primary to carry a shared OR of both circuits — the "first set's
     * seam rail won't turn off while the second set is powered" complaint.
     *
     * <p>The emulated probe mirrors {@code isSameRailWithPower} minus the POWERED gate: the
     * fragment's bit is DERIVED from its circuit (its lifecycle in {@code SeamWireBridge}), so
     * gating on it would only add an update-ordering deadlock — chain continuity is enforced by
     * the real rails behind the doors. {@code passable} = same block + axis-compatible shape;
     * {@code localSignal} = the cell's neighbor scan skipping the PRIMARY's side (the
     * fragment's behind-plane); {@code farDoor} = the walk's continuation through the
     * fragment's own door (null = cold far, severed with the retry queued). Returns null for
     * unclaimed cells, primary-side arrivals, or dead machinery — the caller falls through to
     * the vanilla probe.
     */
    @Nullable
    public static IntoProbe probeIntoFragmentHalf(
        Level level, BlockPos currentPos, BlockPos steppedPos, RailShape dirShape, Block walkBlock
    ) {
        try {
            if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
                || AperturePassthroughLever.DISABLE_SEAM_SHADOW
                || AperturePassthroughLever.DISABLE_SEAM_WALK_SEVER
                || !SeamlessPortalsConfig.isEntityPortals()
                || !(level instanceof ServerLevel src)
                || !SeamRegistry.sectionHasSeam(level, steppedPos)) {
                return null;
            }
            MinecraftServer server = src.getServer();
            if (server == null || !server.isSameThread()) {
                return null;
            }
            if (SeamRegistry.lookup(level, steppedPos) == null) {
                return null;
            }
            Direction step = seamlessportals$stepOf(currentPos, steppedPos);
            if (step == Direction.UP) {
                return null;
            }
            Direction empty = SeamFractional.emptyHalfDir(level, steppedPos);
            if (empty == null || empty != step.getOpposite()) {
                return null;    // unclaimed, or arriving on the primary's own half
            }
            SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(level, steppedPos);
            if (sec == null || !sec.state().is(walkBlock)) {
                // The arriving half is genuinely empty, or holds a different block: the walk
                // dies here exactly as it would at a gap or a foreign rail type in vanilla.
                return new IntoProbe(false, false, null);
            }
            RailShape fragShape = sec.state().getValue(
                ((net.minecraft.world.level.block.BaseRailBlock) sec.state().getBlock())
                    .getShapeProperty());
            if (isAxisIncompatible(fragShape, dirShape)) {
                return new IntoProbe(false, false, null);
            }
            boolean localSignal = hasLocalNeighborSignalSkippingEmptyHalf(
                level, steppedPos, empty.getOpposite());
            WalkRedirect farDoor = walkRedirect(
                level, steppedPos, steppedPos.relative(step), dirShape);
            probeLog("fragment-half probe at {} from {} step {}: shape={} localSignal={} farDoor={}",
                steppedPos, currentPos, step, fragShape, localSignal, farDoor != null);
            return new IntoProbe(true, localSignal, farDoor);
        }
        catch (Throwable t) {
            readFault(t);
            return null;
        }
    }

    /** The horizontal axis step from a walk's current cell to its stepped target. */
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

    /**
     * ★ THE SHARED-PAIR WAKE (RS-XTALK live round 2, 2026-08-22). Called for every
     * {@code neighborChanged} DELIVERED at a bound seam rail cell — whether or not the rail's own
     * state flips. Queues a tick-end {@code updateNeighborsAt(counterpart)} in the far level,
     * waking the counterpart's ADJACENT rails (never the counterpart itself — no authority
     * re-entry; {@code updateNeighborsAt} notifies AROUND P, never P).
     *
     * <p>The gap it closes: the pair's POWERED bit is the OR of the two through-paths. A path
     * transition that does not change the OR — powering the second path while the first already
     * lights the pair, or un-powering it while the first still does — produces NO state change at
     * the pair, so no refinement, no shape-sync stamp, no far-side notification: the second
     * path's far rails were never told to look (live log: the seam pair powered, the walk route
     * through the paired door was live, and the dest-side rail never re-evaluated). The D1
     * dispatch cannot see these transitions because it keys on seam-cell STATE CHANGES; this
     * wake keys on DELIVERIES. Volume: per-tick deduped per counterpart (one wake per cell per
     * tick), budgeted by the shared flush, and silent at rest — pokes only exist while something
     * is actually changing near the pair.
     */
    public static void onSeamRailPoked(ServerLevel level, BlockPos pos) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
            || AperturePassthroughLever.DISABLE_SEAM_SIGNAL_DISPATCH
            || AperturePassthroughLever.DISABLE_SEAM_WALK_SEVER
            || !SeamlessPortalsConfig.isEntityPortals()
            || !SeamRegistry.sectionHasSeam(level, pos)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return;
        }
        SeamRegistry.SeamCell cell = SeamRegistry.lookup(level, pos);
        if (cell == null) {
            return;
        }
        Block source = level.getBlockState(pos).getBlock();
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
            // PAIR-TRUTH probe (live round 3, "seam rail looks dark"): print the SERVER's view of
            // both halves on every poke, so a dark-looking rail can be attributed to the server
            // state or to the client sync/render layer in one glance at the log.
            if (AperturePassthroughLever.SEAM_SIGNAL_PROBE && server.getLevel(b.destDim()) != null
                && server.getLevel(b.destDim()).hasChunkAt(target)) {
                BlockState near = level.getBlockState(pos);
                BlockState farSt = server.getLevel(b.destDim()).getBlockState(target);
                var POWERED = net.minecraft.world.level.block.state.properties
                    .BlockStateProperties.POWERED;
                probeLog("pair truth: near {} {} powered={} mask={} secondary={} | far {} {} powered={}",
                    pos, near.getBlock(),
                    near.hasProperty(POWERED) ? near.getValue(POWERED) : "n/a",
                    SeamOccupancy.occupancyOf(level, pos),
                    SeamOccupancy.secondaryOf(level, pos) != null,
                    target, farSt.getBlock(),
                    farSt.hasProperty(POWERED) ? farSt.getValue(POWERED) : "n/a");
            }
            queue(GlobalPos.of(b.destDim(), target.immutable()), source, null, true);
        }
    }

    private static void queue(GlobalPos target, Block sourceBlock, @Nullable GlobalPos waitFor) {
        queue(target, sourceBlock, waitFor, false);
    }

    private static void queue(
        GlobalPos target, Block sourceBlock, @Nullable GlobalPos waitFor, boolean wakeAround
    ) {
        Set<GlobalPos> dedupe = wakeAround ? WAKE_DEDUPE : DEDUPE;
        if (waitFor == null && !dedupe.add(target)) {
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
                (evicted.wakeAround() ? WAKE_DEDUPE : DEDUPE).remove(evicted.target());
            }
        }
        QUEUE.addLast(new Dispatch(target, sourceBlock, waitFor, wakeAround));
        if (wakeAround) {
            wakeQueued++;
        }
        else {
            dispatchQueued++;
        }
        probeLog("dispatch queued -> {} {} (waitFor={} wake={})", target.dimension().identifier(),
            target.pos(), waitFor, wakeAround);
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
        WAKE_DEDUPE.clear();
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
                QUEUE.addLast(new Dispatch(d.target(), d.sourceBlock(), d.target(), d.wakeAround()));
                continue;   // target itself went cold; wait on it
            }
            if (dispatchedThisTick >= MAX_DISPATCH_PER_TICK) {
                QUEUE.addFirst(d);
                budgetTrips++;
                break;   // leftover flushes next tick — delayed, never lost
            }
            dispatchedThisTick++;
            if (d.wakeAround()) {
                wakeDelivered++;
            }
            else {
                dispatchDelivered++;
            }
            probeLog("dispatch delivered -> {} {} (wake={})", lvl.dimension().identifier(),
                d.target().pos(), d.wakeAround());
            try {
                if (d.wakeAround()) {
                    lvl.updateNeighborsAt(d.target().pos(), d.sourceBlock());
                    // The counterpart's FRAGMENT derives from rails in BOTH dimensions, and
                    // this wake is the only signal that the far input changed: the wake pokes
                    // AROUND the cell (never the cell — authority), so nothing re-runs the
                    // cell's own fragment refresh when only the far side moved — the fragment
                    // stayed stale-lit ~12s live (round 7). Re-derive it here directly: the
                    // ONE writer, side-table only, change-gated, loop-safe.
                    SeamWireBridge.refreshSecondary(lvl, d.target().pos());
                }
                else {
                    lvl.neighborChanged(d.target().pos(), d.sourceBlock(), null);
                }
            }
            catch (Throwable t) {
                LOGGER.warn("[RS-SIGNAL] cross-seam {} failed at {} in {}",
                    d.wakeAround() ? "updateNeighborsAt" : "neighborChanged",
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
            + " budgetTrips=" + budgetTrips
            + " wakeQueued=" + wakeQueued
            + " wakeDelivered=" + wakeDelivered;
    }

    public static long wakeDeliveredCount() {
        return wakeDelivered;
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
