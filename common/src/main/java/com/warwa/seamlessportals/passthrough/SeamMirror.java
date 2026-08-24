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
                if (isPhaseGated(binding)) {
                    continue;   // DISJOINT: distinct cells, unmirrored — and this veto must not
                                // refuse the player joining track to the far side's own rail.
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
     * ★ THE PHASE GATE — a positively-classified DISJOINT (boundary-phase) seam does not mirror.
     *
     * <p>On a COINCIDENT seam (every obsidian frame) the two aperture cells are ONE physical slot
     * and the mirror is what makes the two clipped halves read as one block. On a DISJOINT seam the
     * plane lies on the cell boundary: source and destination cells are distinct, face-to-face
     * WHOLE blocks in two worlds. Mirroring there (1) duplicates every placement into a cell the
     * player did not build, (2) makes refuse-on-conflict deny the exact gesture (b) exists for —
     * laying track up to the plane that joins the far side's own track — and (3) turns (b)'s far
     * shape write into a mirror-back loop, because the far aperture cell is itself a bound seam
     * cell whose counterpart is this one.
     *
     * <p>Applied identically by the veto, the driver, bind-time reconciliation and the client
     * prediction, so all four agree by construction. (a)'s user-verified behaviour is untouched:
     * every obsidian binding is COINCIDENT. ✅ USER-CONFIRMED 2026-07-27.
     */
    static boolean isPhaseGated(SeamRegistry.SeamBinding binding) {
        return binding.phase() == SeamMap.SeamPhase.DISJOINT
            && !AperturePassthroughLever.DISABLE_SEAM_PHASE_GATE;
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
    /** Same-block refinements of an existing pair re-mirrored past the source policy (shape sync). */
    private static long shapeSynced = 0L;
    /** Mirror halves reverted to the player half's state after an un-bracketed rewrite. */
    private static long shapeReverted = 0L;
    /** Writes that reached a bound seam cell but were declined by policy — see {@link SeamMirrorPolicy}. */
    private static long declinedBySource = 0L;
    private static long declinedByAlignment = 0L;

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
        onSeamCellChanged(level, pos, newState, false);
    }

    public static void onSeamCellChanged(
        Level level, BlockPos pos, BlockState newState, boolean sameBlockRefinement
    ) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_MIRROR) {
            return;
        }
        if (applying) {
            return;   // our own write, observed. Not an error — this is the guard doing its job.
        }
        // ★ BREAK RELEASES OCCUPANCY (FRACTIONAL_DESIGN.md §2a.0). Without this, breaking a seam
        // block leaves its owner half claimed, and the NEXT block placed in the cell inherits a
        // stale cut — found while writing the end-to-end gate (forgetPlacement existed with zero
        // callers). Runs before the policy gates on purpose: whoever removed the block (player,
        // piston, teardown sweep), a cell that is now AIR holds no object and its occupancy is a
        // dangling record. The mirrored counterpart clears itself the same way — the break path's
        // dest.setBlockAndUpdate(AIR) fires this driver on the destination level. Broadcast so
        // every client's copy clears too; v1 clears the WHOLE cell (per-half breaking of a
        // two-object cell is front-4 work — vanilla removes the entire blockstate on break).
        if (newState.isAir() && level instanceof net.minecraft.server.level.ServerLevel) {
            // ★ PROMOTE FIRST. If a second object survives in this cell, the break removed only the
            // PRIMARY: the survivor's state moves from the side table into the chunk and its half
            // becomes the owner. Deliberately FALLS THROUGH afterwards (no return): the flow below
            // still owes the broken primary its break-either-breaks-both counterpart clear, and the
            // counterpart's own air event runs its own promote symmetrically. The promote's setBlock
            // re-enters this driver under the applying guard, which swallows it.
            if (!SeamFractional.promoteSecondaryOnAir(level, pos)) {
                if (SeamOccupancy.occupancyOf(level, pos) != 0) {
                    SeamOccupancy.clear(level, pos);
                    SeamOccupancy.broadcast(level, pos);
                }
            }
        }
        // ★ WHO WROTE THIS? (user decision 2026-07-26 — players only.)
        //
        // Asked here rather than in the mixin because this is the one place that already knows the
        // write is seam-relevant, and because the classification must be visible to the counters: a
        // declined machine write is a decision, not an absence, and reads as one in the probe.
        //
        // ★ EXCEPTION — SAME-BLOCK REFINEMENTS SYNC REGARDLESS OF SOURCE (shape sync). Vanilla rail
        // resolution rewrites NEIGHBOURS directly: laying a second rail beside a seam rail runs
        // RailState.place -> neighbor.connectTo, whose setBlock at the seam cell carries no player
        // bracket and classifies UNKNOWN. Declining it leaves the two halves of a mirrored pair
        // holding different shapes — the residue of the user's "sometimes not curving" report, and
        // a deterministic failure for (b), where every join re-derives the seam rail's shape. The
        // policy's job is to keep MACHINES from creating or removing mirrored blocks; a same-block
        // state refinement neither creates nor removes, and the pair's byte-identity is the (a)
        // invariant. Lever: -Dseamlessportals.disableSeamShapeSync.
        //
        // ★★ THE AUTHORITY RULE (adversarial panel, 2026-07-27 — three lenses found the first
        // build's defect independently). A refinement must decide WHICH HALF IS THE AUTHORITY, and
        // must NEVER touch provenance:
        //   - refinement at the PLAYER half  -> propagate to the mirror half (which must already be
        //     provenance-marked; a cell whose counterpart is independently owned is NOT a mirror
        //     pair and must not be clobbered — two players may each have built their own half, a
        //     state bind-time reconciliation explicitly preserves);
        //   - refinement at the MIRROR half  -> REVERT it from the player half. The derived-state
        //     rule ("a mirrored cell's shape is the SOURCE cell's") makes the un-bracketed rewrite
        //     illegitimate: propagating it would let any far-side machine write overwrite the
        //     player's block, and the first build's fall-through additionally stamped
        //     mirror-created provenance onto the PLAYER's half — after which a frame break deleted
        //     the player's own rail (violating the pinned break rule) and mirror authority
        //     suppressed its support pops.
        // ★ STALE-PROVENANCE FIX (2026-07-28 — the user's polarity correction). A break at a seam
        // cell must clear THAT CELL'S OWN mirror-created mark: the block is gone, and provenance
        // describes an occupant, not a cell. The clear path below removes only the COUNTERPART's
        // mark, so a place-from-far → break → re-place-from-near cycle left the near cell marked
        // "mirror-created" forever — after which the authority rule suppressed the PLAYER'S OWN
        // freshly placed rail there (including its placement-time self-notification, which is the
        // power evaluation), producing "the player-placed source half sits dark and only placing
        // on the dest half works". Unconditional on write source: whoever removed the block,
        // the provenance of the vanished occupant is void.
        if (newState.isAir() && !AperturePassthroughLever.DISABLE_SEAM_BREAK_UNMARK) {
            ((SeamIndexHolder) level).seamlessportals$mirrorCreatedCells().remove(pos.asLong());
            SeamOccupancySavedData.persistMirrorCreated(level, pos.asLong(), false);
        }

        SeamWriteSource source = SeamWriteContext.sourceFor(pos);
        boolean sourceMirrors = SeamMirrorPolicy.mirrors(source);
        boolean refinementSync = sameBlockRefinement
            && !AperturePassthroughLever.DISABLE_SEAM_SHAPE_SYNC;
        if (!sourceMirrors && !refinementSync) {
            declinedBySource++;
            return;
        }
        boolean refinementOnly = !sourceMirrors;
        SeamRegistry.SeamCell cell = SeamRegistry.lookup(level, pos);
        if (cell == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        if (refinementOnly
            && ((SeamIndexHolder) level).seamlessportals$mirrorCreatedCells().contains(pos.asLong())) {
            revertMirrorHalf(level, pos, cell, server);
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
                if (isPhaseGated(binding)) {
                    continue;   // DISJOINT: two distinct whole blocks; mirroring would duplicate
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
                applyToDestination(dest, binding, newState, level, pos, refinementOnly);
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
        applyToDestination(dest, binding, newState, sourceLevel, sourcePos, false);
    }

    /**
     * ★ A seam-internal write: setBlock under the {@code applying} guard, so the driver observes it
     * and swallows it — no mirror fires, no break-release fires, no policy consulted. Used by the
     * promote path, whose write is bookkeeping (moving a surviving second object's state from the
     * side table into the chunk), not a player action.
     */
    public static void writeAsSeamInternal(
        Level level, BlockPos pos, BlockState state
    ) {
        boolean prev = applying;
        applying = true;
        try {
            level.setBlock(pos, state,
                net.minecraft.world.level.block.Block.UPDATE_ALL
                    | net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
        } finally {
            applying = prev;
        }
    }

    /**
     * A MIRROR half was refined by an un-bracketed write (vanilla {@code connectTo} from a far-side
     * placement, most commonly): re-assert the player half's state over it. The player half is the
     * authority — see the AUTHORITY RULE note in {@code onSeamCellChanged}.
     */
    private static void revertMirrorHalf(
        Level level, BlockPos pos, SeamRegistry.SeamCell cell, MinecraftServer server
    ) {
        applying = true;
        try {
            for (SeamRegistry.SeamBinding binding : cell.bindings()) {
                if (!binding.isMirrorable() || isPhaseGated(binding)) {
                    continue;
                }
                ServerLevel authority = server.getLevel(binding.destDim());
                if (authority == null) {
                    continue;
                }
                BlockPos authorityPos = binding.destPos();
                if (!authority.hasChunkAt(authorityPos)) {
                    continue;   // cannot read the authority; leave the refinement standing
                }
                BlockState authorityState = authority.getBlockState(authorityPos);
                BlockState current = level.getBlockState(pos);
                if (authorityState.getBlock() != current.getBlock()) {
                    continue;   // not a live pair (orphaned mirror half); nothing to re-assert
                }
                // binding.stateRotation() carries THIS cell's frame into the authority's; the
                // revert travels the other way.
                BlockState reverted = authorityState.rotate(
                    SeamShadowBridge.inverse(binding.stateRotation()));
                if (reverted != current) {
                    SeamSignalContinuity.beginMirrorWrite(level, pos);
                    try {
                        level.setBlock(pos, reverted,
                            net.minecraft.world.level.block.Block.UPDATE_ALL
                                | net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
                    }
                    finally {
                        SeamSignalContinuity.endMirrorWrite(level);
                    }
                    if (level instanceof ServerLevel serverLevel) {
                        forceClientSync(authority, serverLevel, pos);
                        probe("reverted refined mirror half at", pos, serverLevel,
                            authorityPos, authority);
                    }
                    shapeReverted++;
                }
                return;   // one authority per pair; the cluster's second binding names the same one
            }
        }
        finally {
            applying = false;
        }
    }

    private static void applyToDestination(
        ServerLevel dest, SeamRegistry.SeamBinding binding, BlockState newState,
        Level sourceLevel, BlockPos sourcePos, boolean refinementOnly
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

        if (refinementOnly) {
            // SHAPE SYNC ONLY: the destination must be a PROVENANCE-MARKED mirror half holding the
            // same block. Same-block alone is not enough — two players may each have built their
            // own half (bind-time reconciliation explicitly preserves that), and a refinement on
            // one side must not clobber the other's independently-owned block. And a non-player
            // write at a cell whose counterpart is empty is a plain declined write, not a
            // refinement of a mirrored pair — without these tests a /setblock-placed block would
            // gain a mirror the moment anything refined it, quietly widening the player-only
            // decision. (Panel finding, 2026-07-27.)
            BlockState existingDest = dest.getBlockState(destPos);
            if (newState.isAir() || existingDest.getBlock() != newState.getBlock()
                || !holder.seamlessportals$mirrorCreatedCells().contains(destKey)) {
                return;
            }
            shapeSynced++;
        }

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
                BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                traceBegin(dest, destPos, air, sourceLevel, sourcePos, "aperture-clear");
                SeamSignalContinuity.beginMirrorWrite(dest, destPos);
                boolean cleared;
                try {
                    cleared = dest.setBlockAndUpdate(destPos, air);
                }
                finally {
                    SeamSignalContinuity.endMirrorWrite(dest);
                }
                traceEnd(dest, destPos, cleared, air, sourceLevel, sourcePos, "aperture-clear");
                forceClientSync(sourceLevel, dest, destPos);
                clearedMirrors++;
                // ★ F1 (2026-08-11, user ruling): the counterpart's end fires its OWN destroy
                // burst — the silent setBlockAndUpdate clear never showed one, so a broken
                // pair's far half just blinked out. PARTICLES ONLY (live round 9, user order
                // "the break sound plays twice — fix this"): levelEvent 2001 carries the break
                // SOUND too, and with both ends in earshot the one break sounded twice; the
                // breaking end's own vanilla sound is the one true sound. The client-side
                // TerrainParticle birth cull half-scopes each end's burst to its own side.
                if (cleared) {
                    dest.sendParticles(
                        new net.minecraft.core.particles.BlockParticleOption(
                            net.minecraft.core.particles.ParticleTypes.BLOCK, existing),
                        destPos.getX() + 0.5, destPos.getY() + 0.5, destPos.getZ() + 0.5,
                        20, 0.25, 0.25, 0.25, 0.05);
                }
                probe("cleared counterpart at", destPos, dest, sourcePos, sourceLevel);
                // ★ THE COUNTERPART'S OCCUPANCY BOOKKEEPING MUST HAPPEN HERE — its own driver never
                // runs it. This whole block executes under `applying = true`, and the counterpart's
                // onSeamCellChanged returns at that guard before reaching the air branch. So without
                // this, the counterpart cell goes to air with a STALE owner mask and an UNPROMOTED
                // secondary — object records diverge, and the next placement is adjudicated against
                // ghosts (live round 10's cascade). Promote-or-clear, exactly as the driver's own
                // air branch would have.
                if (!com.warwa.seamlessportals.passthrough.SeamFractional
                        .promoteSecondaryOnAir(dest, destPos)) {
                    if (SeamOccupancy.occupancyOf(dest, destPos) != 0) {
                        SeamOccupancy.clear(dest, destPos);
                        SeamOccupancy.broadcast(dest, destPos);
                    }
                }
            }
            holder.seamlessportals$mirrorCreatedCells().remove(destKey);
            SeamOccupancySavedData.persistMirrorCreated(dest, destKey, false);
            return;
        }

        BlockState rotated = newState.rotate(binding.stateRotation());
        // WRITE WITH UPDATE_SKIP_ON_PLACE. The mirror is AUTHORITATIVE for the destination cell: its
        // state is defined as the source's state, rotated. Writing with plain setBlockAndUpdate
        // (flags 3) runs onPlace on the destination (REF LevelChunk.java:326-327), and for a rail
        // that immediately RE-RESOLVES the mirrored copy against the DESTINATION dimension's own
        // neighbours — which are different — so the two halves diverge.
        //
        // User-observed symptom, and it names the bug exactly: on an obsidian portal the two seam
        // cells are COINCIDENT, so the player sees their own half plus the far half through the
        // window. Divergent shapes therefore render as "a straight rail AND a curved rail texture at
        // the same time on the same rail". It also explains why breaking and re-placing fixed it (a
        // fresh mirror) and why it was intermittent (dest-side resolution sometimes happens to pick
        // the same shape).
        //
        // UPDATE_NEIGHBORS is deliberately KEPT: the destination's neighbours must still be notified,
        // so a track on the far side reacts. Only the mirrored block's own self-resolution is
        // suppressed.
        traceBegin(dest, destPos, rotated, sourceLevel, sourcePos, "aperture-mirror");
        // (c) D1 IDENTITY: name the exact cell this write targets, so the cross-seam dispatch
        // skips only OUR write's driver echo — cascade flips at other seam cells inside this
        // write's inline far fan-out must still dispatch. (Panel BLOCKER, 2026-07-27.)
        SeamSignalContinuity.beginMirrorWrite(dest, destPos);
        boolean written;
        try {
            written = dest.setBlock(destPos, rotated,
                net.minecraft.world.level.block.Block.UPDATE_ALL
                    | net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
        }
        finally {
            SeamSignalContinuity.endMirrorWrite(dest);
        }
        traceEnd(dest, destPos, written, rotated, sourceLevel, sourcePos, "aperture-mirror");
        // ★ D1 — DEST FIRE LIVES (user order 2026-08-10: "the fire spreads as normal from that
        // dest seam to dest blocks"). UPDATE_SKIP_ON_PLACE above skips FireBlock.onPlace, whose
        // scheduleTick is vanilla's ONLY initial fire scheduler — so a mirrored fire never
        // ticked: no spread, no aging, in any topology (bytecode-verified 2026-08-10 research).
        // Schedule it explicitly, with vanilla's own delay. Spread-fire the DEST tick then
        // writes goes through plain setBlock → onPlace → schedules itself normally.
        if (written && rotated.getBlock() instanceof net.minecraft.world.level.block.FireBlock) {
            dest.scheduleTick(destPos, rotated.getBlock(),
                com.warwa.seamlessportals.mixin.passthrough.FireBlockInvoker
                    .seamlessportals$getFireTickDelay(dest.getRandom()));
        }
        forceClientSync(sourceLevel, dest, destPos);
        // RS-XTALK round 3: the stamp is the ONLY writer of a marked half's primary, and it
        // notifies the cell's NEIGHBOURS, never the cell — so a fragment sharing the stamped
        // cell would keep a stale POWERED until some unrelated poke. Re-derive it here, at the
        // write that changes the primary, through the fragment's ONE writer (change-gated, so a
        // no-op when nothing moved; a second writer outside it ping-ponged 1.9M flips through
        // its own update fans in one live session).
        SeamWireBridge.refreshSecondary(dest, destPos);
        // PROVENANCE: this cell's occupant was created by mirroring, not placed by a player. The
        // user's break rule ("frame break clears the destination half") is undecidable without it.
        // PROVENANCE IS NEVER TOUCHED BY A REFINEMENT — a refinement neither creates nor removes,
        // so it must not change which half owns the block. The first build's unconditional add here
        // stamped mirror-created onto the PLAYER's half whenever a far-side rewrite synced back,
        // after which a frame break deleted the player's own rail. (Panel finding, 2026-07-27;
        // three lenses independently.) On this path the destination is already provenance-marked —
        // the refinement guard above requires it — so skipping the add loses nothing.
        if (!refinementOnly) {
            holder.seamlessportals$mirrorCreatedCells().add(destKey);
            SeamOccupancySavedData.persistMirrorCreated(dest, destKey, true);
            // ★ THE CROSSING HALF (FRACTIONAL_DESIGN.md §2a.0). The mirror writes a whole BlockState
            // — Minecraft has no other way to put material in a cell — so "half a block" is
            // expressed by recording WHICH half this object owns here. Without this claim the
            // destination cell has no owner and the shape hook correctly leaves it WHOLE, which is
            // exactly the live 2026-08-02 report: the source half was right while the destination
            // showed a full block from both of its sides.
            //
            // WHICH half: the one that makes the object CONTINUOUS. The source keeps the material on
            // its owned side, so what crosses extends from the plane in the OPPOSITE direction; map
            // that direction through the portal's own rotation and it names the destination side the
            // material arrives on — the side you emerge on walking through, per the user's decision.
            SeamFractional.claimCrossingHalf(sourceLevel, sourcePos, dest, destPos, binding);
        }
        mirroredWrites++;
        probe("mirrored to", destPos, dest, sourcePos, sourceLevel);
    }

    /**
     * Push a mirrored write into per-player block tracking explicitly.
     *
     * <p><b>Why the ordinary update flags are not enough.</b> The write already carries
     * {@code UPDATE_CLIENTS}, but that only reaches the client if {@code Level.markAndNotifyBlock}
     * decides to call {@code sendBlockUpdated} ({@code REF Level.java:247}) — and it applies its own
     * conditions on the way there. This project has been bitten by exactly that before: cross-dimension
     * fluid flow was invisible because vanilla filtered out the great majority of fluid-spread writes
     * before they ever reached {@code sendBlockUpdated}, and the fix was to notify the tracking layer
     * directly rather than hope the write survived the filters.
     *
     * <p><b>The symptom this removes.</b> User-observed: with the player standing on the SOURCE side,
     * blocks mirrored into the destination were written correctly and persisted — the world data was
     * always right — but did not RENDER until the player teleported there and the region meshed
     * normally. Changes made while standing on the destination side appeared instantly, because those
     * were near the player. That asymmetry is the signature of a write that lands in world state but
     * never reaches the viewer.
     *
     * <p>{@code ServerChunkCache.blockChanged} ({@code REF ServerChunkCache.java:458}) is the direct
     * route into per-player tracking, so a mirrored write is broadcast on the same terms as any other
     * block change regardless of which filters the write itself passed.
     */
    // NOTE (RS-XTALK round 6, kept as a warning): a "doubly-marked pair self-heal" lived here
    // for one suite run and was REFUTED by its first firing — bind-time reconciliation marks
    // the carried copy by DESIGN, so a pair with both halves marked is a legitimate transient,
    // not always a relic; the heal unmarked a lawful mirror half and the provenance-gated
    // frame-break rule then had nothing to clear ("the seam does not exist"). Any future
    // deafness fix must distinguish reconciliation marks from stale ones — do not re-add a
    // counterpart-marked check alone.

    private static void forceClientSync(Level sourceLevel, ServerLevel dest, BlockPos pos) {
        // ★ CROSS-DIMENSION ONLY. User-reported regression, and the discriminator was exact:
        // obsidian portals fine, man-made CROSS-dim fine, man-made SAME-dim broken ("sometimes only
        // works 1 way"). That maps precisely onto this push.
        //
        // When the destination is the SAME level the player is standing in, vanilla already
        // broadcasts the change by the ordinary route. The push is then redundant AND harmful: it
        // forces a second entry into the same ChunkHolder's per-tick change set for a write that is
        // usually in the SAME CHUNK as the source write, because same-dimension portals sit close
        // together. Cross-dimension is the only case that needs it — there the player is not in the
        // destination level at all, which is exactly the case that was invisible before.
        if (sourceLevel == dest) {
            return;
        }
        try {
            dest.getChunkSource().blockChanged(pos);
        }
        catch (Throwable t) {
            // Never let a display concern break the write that already succeeded.
            LOGGER.warn("[RS-SEAM-MIRROR] client sync push failed for {} in {} — the block IS written,"
                + " it may just not render until the region is remeshed",
                pos, dest.dimension().identifier(), t);
        }
    }

    /**
     * Open a {@link SeamDeliveryProbe} trace for a write we just issued.
     *
     * <p>Everything the probe needs to tell a FAILED WRITE from a BLOCKED NOTIFY is captured here, at
     * the write, rather than reconstructed later: whether {@code setBlock} returned true, the state
     * actually read back afterwards, and the destination chunk's {@code FullChunkStatus} — the three
     * values {@code Level.markAndNotifyBlock} (REF {@code Level.java:238-248}) tests before it will
     * call {@code sendBlockUpdated}. Also records whether source and destination are the SAME level,
     * which is the exact discriminator the user's three live observations turn on.
     */
    private static void traceBegin(
        ServerLevel dest, BlockPos destPos, BlockState wanted,
        Level sourceLevel, BlockPos sourcePos, String origin
    ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        try {
            SeamDeliveryProbe.beginWrite(dest, destPos, String.valueOf(wanted),
                fullStatusOf(dest, destPos), sourceLevel == dest,
                origin + " from " + sourcePos + " in " + sourceLevel.dimension().identifier());
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-DELIVERY] trace open failed at {} (the write is unaffected)", destPos, t);
        }
    }

    private static void traceEnd(
        ServerLevel dest, BlockPos destPos, boolean setBlockReturned, BlockState wanted,
        Level sourceLevel, BlockPos sourcePos, String origin
    ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        try {
            SeamDeliveryProbe.endWrite(
                dest, destPos, setBlockReturned,
                String.valueOf(wanted), String.valueOf(dest.getBlockState(destPos)),
                fullStatusOf(dest, destPos), sourceLevel == dest,
                origin + " from " + sourcePos + " in " + sourceLevel.dimension().identifier());
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-DELIVERY] trace close failed at {} (the write is unaffected)", destPos, t);
        }
    }

    private static String fullStatusOf(ServerLevel dest, BlockPos pos) {
        try {
            return String.valueOf(dest.getChunk(pos.getX() >> 4, pos.getZ() >> 4).getFullStatus());
        }
        catch (Throwable t) {
            return "(unavailable: " + t + ")";
        }
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
    // BIND-TIME RECONCILIATION
    // =============================================================================================

    private static long reconciled = 0L;

    /**
     * Mirror aperture contents that are ALREADY PRESENT when a portal binds.
     *
     * <p><b>The gap this closes.</b> Mirroring is CHANGE-DRIVEN — the driver only observes
     * {@code setBlockState}. A block that is already sitting in the aperture when a portal comes into
     * existence never changes, so it is never mirrored, and the seam has one half and not the other.
     *
     * <p>User-reported: break a frame with a rail on the portal floor (the rail survives, per §0.4),
     * repair and re-light, and *"half the rail gets cut off and does not mirror"*. Exactly this: the
     * surviving rail was never re-mirrored because nothing wrote to it.
     *
     * <p><b>Conflict rule is unchanged.</b> A destination cell that already holds a real block is
     * LEFT ALONE — that block is the far side's own, and overwriting it would destroy a player's work
     * to satisfy a mirror. Only air and placeholder cells receive. So two players who each built their
     * own half keep both, and the common case (one surviving half, an empty counterpart) is restored.
     *
     * <p>Idempotent, and cheap: it runs only when a portal's geometry fingerprint changes, which for a
     * stable portal is once.
     */
    public static void reconcileApertureOnBind(qouteall.imm_ptl.core.portal.Portal portal) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_MIRROR) {
            return;
        }
        Level level = portal.level();
        if (!(level instanceof ServerLevel serverLevel) || applying) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            return;
        }
        applying = true;
        int done = 0;
        try {
            for (net.minecraft.world.phys.Vec3 column : SeamMap.enumerateColumns(portal)) {
                BlockPos src = SeamMap.seamCell(portal, column);
                BlockState srcState = serverLevel.getBlockState(src);
                if (srcState.isAir()
                    || srcState.getBlock() == qouteall.imm_ptl.core.portal.PortalPlaceholderBlock.instance) {
                    continue;   // nothing to carry
                }
                SeamRegistry.SeamCell cell = SeamRegistry.lookup(serverLevel, src);
                if (cell == null) {
                    continue;
                }
                for (SeamRegistry.SeamBinding binding : cell.bindings()) {
                    if (!binding.isMirrorable()) {
                        continue;
                    }
                    if (isPhaseGated(binding)) {
                        continue;   // DISJOINT seams carry nothing across at bind either
                    }
                    ServerLevel dest = server.getLevel(binding.destDim());
                    if (dest == null) {
                        continue;
                    }
                    BlockPos destPos = binding.destPos();
                    dest.getChunk(destPos.getX() >> 4, destPos.getZ() >> 4);
                    BlockState destState = dest.getBlockState(destPos);
                    boolean free = destState.isAir()
                        || destState.getBlock() == qouteall.imm_ptl.core.portal.PortalPlaceholderBlock.instance;
                    if (!free) {
                        continue;   // the far side's own block — never clobber it
                    }
                    BlockState carried = srcState.rotate(binding.stateRotation());
                    traceBegin(dest, destPos, carried, serverLevel, src, "bind-reconcile");
                    boolean written = dest.setBlock(destPos, carried,
                        net.minecraft.world.level.block.Block.UPDATE_ALL
                            | net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
                    traceEnd(dest, destPos, written, carried, serverLevel, src, "bind-reconcile");
                    forceClientSync(serverLevel, dest, destPos);
                    ((SeamIndexHolder) dest).seamlessportals$mirrorCreatedCells().add(destPos.asLong());
                    SeamOccupancySavedData.persistMirrorCreated(dest, destPos.asLong(), true);
                    reconciled++;
                    done++;
                    break;   // one write per cell; the faces share a destination
                }
            }
            if (done > 0 && AperturePassthroughLever.SEAM_MIRROR_PROBE) {
                LOGGER.info("[RS-SEAM-MIRROR] bind reconciliation carried {} pre-existing aperture"
                        + " block(s) across for portal {} in {}",
                    done, portal.getId(), serverLevel.dimension().identifier());
            }
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-SEAM-MIRROR] bind reconciliation failed for portal {}", portal.getId(), t);
        }
        finally {
            applying = false;
        }
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
                forceClientSync(level, far, link.to());
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
                forceClientSync(level, far, link.to());
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
                SeamOccupancySavedData.persistMirrorCreated(serverLevel, key, false);
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
            + " frameBreakCleared=" + frameBreakCleared
            + " declinedBySource=" + declinedBySource
            + " declinedByAlignment=" + declinedByAlignment
            + " shapeSynced=" + shapeSynced
            + " shapeReverted=" + shapeReverted;
    }

    /**
     * The revert counter alone, numeric — the RS-WIRE gate's volume ceiling asserts a bounded
     * delta of it (the 2026-08-10 dust loop was ~one revert per re-wake, thousands per tick;
     * a healthy leg produces a handful).
     */
    public static long shapeRevertedCount() {
        return shapeReverted;
    }
}
