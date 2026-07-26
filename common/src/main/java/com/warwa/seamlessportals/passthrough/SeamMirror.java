package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * THE SEAM MIRROR — placement veto (step 5) and, later, the cross-seam write driver (step 6).
 *
 * <p><b>The rule this enforces</b> (user decision, {@code REDSTONE_RECON.md} §0.7): a block placed in
 * an aperture cell is mirrored into the destination's coincident cell, and <b>if that destination
 * cell is already occupied the placement is REFUSED OUTRIGHT</b> — no overwrite, no source-only half.
 * A seam is either whole or it does not happen.
 *
 * <p>The veto runs at {@code canPlace} time, before any world write, so a refusal costs the player
 * nothing: no block, no sound, no item consumed. Refusing after the fact would mean rolling back a
 * write, which is exactly the failure mode that makes non-item writes unfixable for now.
 *
 * <h2>What is refused outright, regardless of the destination</h2>
 * <ul>
 *   <li><b>Block entities</b> — a chest or hopper mirrored across a seam would need its contents
 *       mirrored too, and two independent inventories claiming to be one object is an item
 *       duplication route.</li>
 *   <li><b>Multi-cell blocks</b> (doors, beds, tall flowers) — their halves would land in different
 *       dimensions, and vanilla's own break logic assumes both halves are reachable in one level.</li>
 *   <li><b>Fluids</b> — handled separately by the {@code canBeReplaced(BlockState, Fluid)} override,
 *       which keeps the placeholder unfloodable.</li>
 * </ul>
 *
 * <h2>The unloaded-destination problem, and the answer taken</h2>
 * Refuse-on-conflict requires READING the destination world, which may not be loaded. The spec offered
 * no clean answer and listed three: force-load, refuse-while-unloaded, or place optimistically and
 * reconcile later. Optimistic placement is out — it violates the user's rule by construction, since a
 * conflict discovered later leaves exactly the source-only half the rule forbids. Refusing while
 * unloaded is honest but produces an unexplainable "I cannot place this block here" whenever the far
 * side happens to be cold.
 * <p><b>Taken: a synchronous destination chunk load</b> via {@code ServerLevel.getChunk(int, int)}.
 * A player placing a block is already an interactive, blocking action, and this only ever fires for a
 * cell that is genuinely bound to a seam — a vanishingly small fraction of placements. In practice the
 * destination is usually resident anyway, because a live portal keeps its far-side chunks loaded.
 * The cost is a possible one-off sync load at the moment of placement; the benefit is that the user's
 * rule holds unconditionally rather than "except when the other side is cold".
 */
public final class SeamMirror {

    private SeamMirror() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Counts refusals by reason, for the probe. */
    private static long refusedConflict = 0L;
    private static long refusedBlockEntity = 0L;
    private static long refusedMultiCell = 0L;
    private static long allowed = 0L;

    /**
     * The veto. Returns false to refuse the placement entirely.
     *
     * @param level the level the player is placing in
     * @param pos   the cell about to be written
     * @param state the state about to be placed, or null when not yet known (context-level check)
     */
    public static boolean mayPlace(@Nullable Level level, @Nullable BlockPos pos, @Nullable BlockState state) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_MIRROR) {
            return true;
        }
        if (level == null || pos == null || level.isClientSide()) {
            return true;    // server is authoritative; the client predicts and is corrected
        }
        try {
            // Fast path: the overwhelming majority of placements are nowhere near a seam.
            if (!SeamRegistry.sectionHasSeam(level, pos)) {
                return true;
            }
            SeamRegistry.SeamCell cell = SeamRegistry.lookup(level, pos);
            if (cell == null) {
                return true;
            }

            if (state != null && !state.getFluidState().isEmpty()) {
                return false;   // belt-and-braces; the Fluid overload is the real guard
            }
            if (state != null && state.hasBlockEntity()) {
                refusedBlockEntity++;
                logRefusal(pos, "block entity", state);
                return false;
            }
            if (state != null && isMultiCell(state)) {
                refusedMultiCell++;
                logRefusal(pos, "multi-cell block", state);
                return false;
            }

            // Refuse-on-conflict: every mirrorable binding at this cell must have a FREE counterpart.
            for (SeamRegistry.SeamBinding binding : cell.bindings()) {
                if (!binding.isMirrorable()) {
                    continue;   // query-only seam (scaled/rotated portal) — nothing to mirror into
                }
                if (!destinationIsFree(level, binding)) {
                    refusedConflict++;
                    logRefusal(pos, "destination cell " + binding.destPos() + " in "
                        + binding.destDim().identifier() + " is occupied", state);
                    return false;
                }
            }
            allowed++;
            return true;
        }
        catch (Throwable t) {
            // A veto failure must never block ordinary building. Fail OPEN: the worst case is a
            // seam that does not mirror, which is strictly better than a player who cannot place
            // blocks because a diagnostic threw.
            LOGGER.warn("[RS-SEAM-MIRROR] mayPlace failed at {} — allowing the placement", pos, t);
            return true;
        }
    }

    /**
     * Whether the destination cell can accept a mirrored block.
     *
     * <p>Loads the destination chunk synchronously if needed — see the class note on why this is
     * preferred to refusing while cold.
     */
    private static boolean destinationIsFree(Level sourceLevel, SeamRegistry.SeamBinding binding) {
        MinecraftServer server = sourceLevel.getServer();
        if (server == null) {
            return true;    // no server view: cannot check, do not block the player
        }
        ResourceKey<Level> destKey = binding.destDim();
        ServerLevel dest = server.getLevel(destKey);
        if (dest == null) {
            return true;
        }
        BlockPos destPos = binding.destPos();
        // Synchronous load. getChunk(int,int) blocks until the chunk is available.
        dest.getChunk(destPos.getX() >> 4, destPos.getZ() >> 4);
        BlockState destState = dest.getBlockState(destPos);
        return destState.isAir()
            || destState.getBlock() == qouteall.imm_ptl.core.portal.PortalPlaceholderBlock.instance;
    }

    /**
     * Multi-cell blocks: those whose placement writes more than the clicked cell. Detected by the
     * vanilla property that marks the second half, rather than by an enumerated block list, so
     * modded doors and beds are covered too.
     */
    private static boolean isMultiCell(BlockState state) {
        return state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
            || state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART);
    }

    private static void logRefusal(BlockPos pos, String reason, @Nullable BlockState state) {
        if (!AperturePassthroughLever.SEAM_MIRROR_PROBE) {
            return;
        }
        LOGGER.info("[RS-SEAM-MIRROR] REFUSED placement at {} — {} (state={})",
            pos, reason, state == null ? "(unknown)" : state.getBlock());
    }

    // =============================================================================================
    // STEP 6 — THE MIRROR DRIVER
    // =============================================================================================

    /**
     * Re-entrancy guard. A mirrored write is itself a {@code setBlockState}, so without this the
     * driver would observe its own write, mirror it back, observe THAT, and so on. Thread-confined to
     * the server thread by the caller's {@code isSameThread} check.
     */
    private static boolean applying = false;

    private static long mirroredWrites = 0L;
    private static long clearedMirrors = 0L;

    public static boolean isApplying() {
        return applying;
    }

    /**
     * Called from the {@code LevelChunk.setBlockState} driver for a cell known to be bound.
     * Propagates the change to every mirrorable counterpart.
     *
     * <p><b>Applied immediately, not deferred.</b> The spec proposed an end-of-tick flush via
     * {@code ServerTaskList}. That is right for writes that must not re-enter vanilla mid-update, but
     * it opens a window in which the two halves disagree — and a break arriving in that window would
     * consult provenance that has not been written yet. Applying inline under the {@code applying}
     * guard keeps the pair consistent at every observable instant. The guard, not deferral, is what
     * prevents recursion.
     *
     * @param level    the level that changed
     * @param pos      the changed cell
     * @param newState the state now at {@code pos}
     */
    public static void onSeamCellChanged(Level level, BlockPos pos, BlockState newState) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_MIRROR) {
            return;
        }
        if (applying) {
            return;   // our own write, observed. Not an error — this is the guard doing its job.
        }
        SeamRegistry.SeamCell cell = SeamRegistry.lookup(level, pos);
        if (cell == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }

        applying = true;
        try {
            // CLUSTER DEDUPE. An obsidian frame yields FOUR portal entities, two coincident per side,
            // so a cell carries up to two bindings that resolve to the SAME destination cell. Writing
            // per-binding would write the destination twice — harmless for a plain block, but it
            // double-counts provenance and would double-fire any future neighbour notification.
            BlockPos lastDest = null;
            ResourceKey<Level> lastDim = null;
            for (SeamRegistry.SeamBinding binding : cell.bindings()) {
                if (!binding.isMirrorable()) {
                    continue;
                }
                if (binding.destPos().equals(lastDest) && binding.destDim().equals(lastDim)) {
                    continue;   // same target as the other face of this frame
                }
                lastDest = binding.destPos();
                lastDim = binding.destDim();

                ServerLevel dest = server.getLevel(binding.destDim());
                if (dest == null) {
                    continue;
                }
                applyToDestination(dest, binding, newState, level, pos);
            }
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-SEAM-MIRROR] mirror write failed at {} (seam left unmirrored)", pos, t);
        }
        finally {
            applying = false;
        }
    }

    private static void applyToDestination(
        ServerLevel dest, SeamRegistry.SeamBinding binding, BlockState newState,
        Level sourceLevel, BlockPos sourcePos
    ) {
        BlockPos destPos = binding.destPos();
        // LOAD THE DESTINATION, exactly as the veto does.
        //
        // Dropping the write when the far side is cold looked conservative and was in fact a
        // CORRECTNESS BUG, caught by the steps 5+6 gate: mayPlace() force-loads the destination to
        // confirm it is free and APPROVES the placement, and then this method refused to write
        // because the chunk was not resident — leaving precisely the source-only half that
        // refuse-on-conflict exists to prevent. The veto and the driver must agree about loading, or
        // the rule is violated by the two of them disagreeing.
        //
        // Cost is bounded: this only ever runs for a cell genuinely bound to a seam, and a live
        // portal usually keeps its far-side chunks resident anyway.
        try {
            dest.getChunk(destPos.getX() >> 4, destPos.getZ() >> 4);
        }
        catch (Throwable t) {
            // Loading refused or failed. A CLEAR must not be lost — a stale block left behind with no
            // counterpart is the permanent half-seam the journal exists to prevent. A PLACE is safe
            // to drop: the source is authoritative and re-mirrors on its next change.
            if (newState.isAir()) {
                SeamJournal.enqueue(dest, destPos, true);
            }
            LOGGER.warn("[RS-SEAM-MIRROR] could not load destination {} in {} — {}",
                destPos, dest.dimension().identifier(),
                newState.isAir() ? "clear JOURNALLED" : "place dropped (source will re-mirror)", t);
            return;
        }

        SeamIndexHolder holder = (SeamIndexHolder) dest;
        long destKey = destPos.asLong();

        if (newState.isAir()) {
            // BREAKING EITHER HALF BREAKS BOTH — unconditionally, NOT gated on provenance.
            //
            // User-reported defect (live round #2): with the clear gated on
            // mirrorCreatedCells.contains(dest), breaking the MIRRORED half looked for provenance on
            // the counterpart cell, did not find it (the player placed that one by hand), and so left
            // the source half standing. The player then could not replace the block they had just
            // broken — the placement was refused because the surviving source half occupied the
            // seam. It presented as "the rail places and instantly disappears".
            //
            // Provenance belongs to the FRAME-BREAK rule (§0.8 "frame break clears the destination
            // half"), which must distinguish the player's block from the mirror's. A manual break is
            // a different act: the player is removing the seam, and both halves go.
            //
            // Item drops come out right for free: setBlockAndUpdate does NOT drop, so only the side
            // the player actually broke yields an item — exactly "the rail drops on whatever side it
            // was broken on", with no duplication.
            BlockState existing = dest.getBlockState(destPos);
            if (!existing.isAir()) {
                dest.setBlockAndUpdate(destPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                clearedMirrors++;
                probe("cleared counterpart at", destPos, dest, sourcePos, sourceLevel);
            }
            holder.seamlessportals$mirrorCreatedCells().remove(destKey);
            return;
        }

        BlockState rotated = newState.rotate(binding.stateRotation());
        dest.setBlockAndUpdate(destPos, rotated);
        // PROVENANCE: this cell's occupant was created by mirroring, not placed by a player. The
        // user's break rule ("frame break clears the destination half") is undecidable without it.
        holder.seamlessportals$mirrorCreatedCells().add(destKey);
        mirroredWrites++;
        probe("mirrored to", destPos, dest, sourcePos, sourceLevel);
    }

    private static void probe(
        String what, BlockPos destPos, ServerLevel dest, BlockPos sourcePos, Level sourceLevel
    ) {
        if (!AperturePassthroughLever.SEAM_MIRROR_PROBE) {
            return;
        }
        LOGGER.info("[RS-SEAM-MIRROR] {} {} in {} (from {} in {})",
            what, destPos, dest.dimension().identifier(),
            sourcePos, sourceLevel.dimension().identifier());
    }

    // =============================================================================================
    // FRAME MIRRORING
    // =============================================================================================

    private static long frameMirrored = 0L;

    /**
     * Mirror an obsidian (frame) change to its partner cell — the user's frame rule: breaking one
     * side breaks the other, repairing one side repairs the other.
     *
     * <p><b>Driven by the persisted {@link SeamFrameLink}, deliberately NOT by live portal
     * geometry.</b> The interesting case is precisely the one where no portal exists: both were torn
     * down when the frame broke, and the player is now repairing. Live bindings are gone by then; the
     * dormant link is the only thing that still knows which obsidian belongs to which.
     *
     * <p>Once both frames are whole again, re-lighting either side is ordinary portal generation: the
     * frame-match search finds the intact partner and links to it. No special re-ignition path is
     * needed — repairing the frames is what makes the pair findable again.
     */
    public static void onFrameCellChanged(ServerLevel level, BlockPos pos, BlockState newState) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_FRAME_MIRROR) {
            return;
        }
        if (applying) {
            return;
        }
        SeamFrameLink.Link link = SeamFrameLink.lookup(level, pos);
        if (link == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        ServerLevel far = server.getLevel(link.toDim());
        if (far == null) {
            return;
        }

        applying = true;
        try {
            far.getChunk(link.to().getX() >> 4, link.to().getZ() >> 4);
            BlockState farState = far.getBlockState(link.to());
            boolean nowAir = newState.isAir();

            // Only act when the two sides actually differ, so a mirrored write cannot ping-pong and
            // an unrelated edit that already matches costs nothing.
            // BREAKS mirror IMMEDIATELY. REPAIRS DO NOT — they are staged until the portal is re-lit
            // (user decision 2026-07-26). Rationale: a half-rebuilt frame is a construction site, and
            // silently reaching into another dimension to place blocks the player has not asked for
            // yet is surprising. Ignition is the moment the player declares the frame finished, so
            // that is when the far side is brought up to match — see repairFarFrameOnIgnition.
            if (nowAir && !farState.isAir()) {
                far.setBlockAndUpdate(link.to(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                frameMirrored++;
                probe("frame break mirrored to", link.to(), far, pos, level);
            }
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-SEAM-MIRROR] frame mirror failed at {} -> {} in {}",
                pos, link.to(), link.toDim().identifier(), t);
        }
        finally {
            applying = false;
        }
    }

    /**
     * Bring the FAR frame up to match this one, at IGNITION time.
     *
     * <p>The other half of the user's frame rule, and deliberately deferred to here rather than
     * firing on each block placed: repairing a frame is a construction site, and reaching into
     * another dimension to place blocks mid-build is surprising. Lighting the portal is the moment
     * the player declares the frame finished, so that is when the far side is restored.
     *
     * <p><b>Ordering is load-bearing.</b> This must run BEFORE the destination frame-match search, or
     * the far frame is still broken when the search looks at it, no match is found, and generation
     * fabricates a NEW portal somewhere else — which is exactly the symptom the user reported
     * ("a new dest portal gets created because the old portal is still in that position").
     *
     * <p>Each near frame cell's own block is copied to its partner, so the far frame comes back as
     * whatever the near one is actually built from rather than an assumed obsidian.
     */
    public static void repairFarFrameOnIgnition(
        ServerLevel level, qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape shape
    ) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_FRAME_MIRROR) {
            return;
        }
        if (!SeamFrameLink.hasAny(level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null || applying) {
            return;
        }
        applying = true;
        int repaired = 0;
        try {
            for (BlockPos nearFrame : shape.frameAreaWithoutCorner) {
                SeamFrameLink.Link link = SeamFrameLink.lookup(level, nearFrame);
                if (link == null) {
                    continue;
                }
                ServerLevel far = server.getLevel(link.toDim());
                if (far == null) {
                    continue;
                }
                far.getChunk(link.to().getX() >> 4, link.to().getZ() >> 4);
                if (!far.getBlockState(link.to()).isAir()) {
                    continue;   // already intact
                }
                BlockState nearState = level.getBlockState(nearFrame);
                if (nearState.isAir()) {
                    continue;   // this side is not repaired either — nothing to copy
                }
                far.setBlockAndUpdate(link.to(), nearState);
                frameMirrored++;
                repaired++;
            }
            if (repaired > 0 && AperturePassthroughLever.SEAM_MIRROR_PROBE) {
                LOGGER.info("[RS-SEAM-MIRROR] ignition in {} restored {} far frame block(s) from the"
                        + " dormant link — the far frame is now matchable again",
                    level.dimension().identifier(), repaired);
            }
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-SEAM-MIRROR] far-frame repair at ignition failed", t);
        }
        finally {
            applying = false;
        }
    }

    // =============================================================================================
    // STEP 7 — THE FRAME-BREAK RULE
    // =============================================================================================

    private static long frameBreakCleared = 0L;

    /**
     * Called when a portal tears down, BEFORE its bindings are dropped.
     *
     * <p><b>The rule</b> (user decision, {@code REDSTONE_RECON.md} §0.8): on a frame break the
     * originally-placed block survives in its own dimension and its MIRROR is removed. Not
     * keep-both — that turns one block the player placed into two, which is a duplication route.
     *
     * <p><b>This is what provenance is for, and the only thing it is for.</b> After a break, the two
     * halves of a seam are indistinguishable by inspection: same block, same state, one on each side.
     * Only {@link SeamIndexHolder#seamlessportals$mirrorCreatedCells()} records which one this level
     * received from a mirror rather than from a player. Without it the rule is undecidable and a
     * break would sometimes delete the half the player actually built.
     *
     * <p><b>Ordering is load-bearing.</b> It must run before {@code SeamRegistry.unbind}, because
     * after unbinding there is no mapping left to find a counterpart with — teardown destroys the
     * very structure the cleanup depends on. (The same constraint governs frame mirroring's dormant
     * link.) Cells are read from the portal's own geometry rather than the registry so the pass is
     * self-sufficient even if bindings are already partly gone.
     */
    public static void onPortalTornDown(qouteall.imm_ptl.core.portal.Portal portal) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_MIRROR) {
            return;
        }
        Level level = portal.level();
        if (level == null || level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (applying) {
            return;
        }
        applying = true;
        try {
            SeamIndexHolder holder = (SeamIndexHolder) level;
            for (net.minecraft.world.phys.Vec3 column : SeamMap.enumerateColumns(portal)) {
                BlockPos cellPos = SeamMap.seamCell(portal, column);
                long key = cellPos.asLong();
                if (!holder.seamlessportals$mirrorCreatedCells().contains(key)) {
                    continue;   // the player built this one — it survives, per §0.4
                }
                BlockState state = serverLevel.getBlockState(cellPos);
                if (!state.isAir()) {
                    serverLevel.setBlockAndUpdate(cellPos,
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    frameBreakCleared++;
                }
                holder.seamlessportals$mirrorCreatedCells().remove(key);
            }
            if (AperturePassthroughLever.SEAM_MIRROR_PROBE) {
                StringBuilder cells = new StringBuilder();
                for (net.minecraft.world.phys.Vec3 col : SeamMap.enumerateColumns(portal)) {
                    BlockPos p = SeamMap.seamCell(portal, col);
                    cells.append("\n    ").append(p)
                        .append(" state=").append(serverLevel.getBlockState(p).getBlock())
                        .append(" mirrorCreated=")
                        .append(holder.seamlessportals$mirrorCreatedCells().contains(p.asLong()));
                }
                LOGGER.info("[RS-SEAM-MIRROR] frame break on portal {} in {} — cleared {} mirrored"
                        + " half(s); provenance set size={} ; cells:{}",
                    portal.getId(), level.dimension().identifier(), frameBreakCleared,
                    holder.seamlessportals$mirrorCreatedCells().size(), cells);
            }
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-SEAM-MIRROR] frame-break cleanup failed for portal {}", portal.getId(), t);
        }
        finally {
            applying = false;
        }
    }

    /** Probe/test accounting. */
    public static String counters() {
        return "allowed=" + allowed + " refusedConflict=" + refusedConflict
            + " refusedBlockEntity=" + refusedBlockEntity + " refusedMultiCell=" + refusedMultiCell
            + " mirroredWrites=" + mirroredWrites + " clearedMirrors=" + clearedMirrors
            + " frameBreakCleared=" + frameBreakCleared;
    }
}
