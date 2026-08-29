package com.warwa.seamlessportals.passthrough;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A LOCAL SHADOW COORDINATE SYSTEM for one seam crossing — the (b) consumer's view of the
 * {@link SeamRegistry.SeamBinding} primitive.
 *
 * <p>Vanilla single-level logic ({@code RailState}) is handed LOCAL positions around
 * {@code ownerCell}; this record translates them into the destination level behind its back. That is
 * how {@code RailState.hasConnection}'s X/Z-only equality (REF RailState.java:119) is satisfied
 * without touching it: both sides of every comparison stay source-dimension coordinates, and
 * translation happens only at the {@code getBlockState}/{@code setBlock} boundary.
 *
 * <p>NOTHING HERE IS RAIL-SPECIFIC. (c) redstone and (d) minecarts consume it unchanged; in
 * particular there is no "which side wins" policy in this class — that is the caller's decision
 * (rails are local-first, R1&#x2032;), because redstone will need an unconditional two-sided view.
 *
 * <p>{@code crossStep} is the direction (in SOURCE coordinates) a probe stepped FROM {@code ownerCell}
 * to enter this shadow. On a boundary-phase seam only the true crossing direction has a shadow; on a
 * mid-block (coincident) seam both directions along the seam axis do — see
 * {@link SeamRegistry.SeamBinding#continuationToward}.
 */
public record SeamShadow(
    ServerLevel sourceLevel,
    ServerLevel farLevel,
    BlockPos ownerCell,
    Direction crossStep,
    BlockPos localAnchor,      // = ownerCell.relative(crossStep) — depth 1 in local coordinates
    BlockPos farAnchor,        // = binding.continuationToward(crossStep) — depth 1 in the far level
    Rotation srcToDst,
    Rotation dstToSrc
) {

    /** How far past {@code ownerCell} along {@code crossStep} a local position lies. &le;0 = source side. */
    public int depth(BlockPos localPos) {
        Vec3i u = crossStep.getUnitVec3i();
        return (localPos.getX() - ownerCell.getX()) * u.getX()
            + (localPos.getY() - ownerCell.getY()) * u.getY()
            + (localPos.getZ() - ownerCell.getZ()) * u.getZ();
    }

    /**
     * THE MAP. Yaw quarter-turns only ({@code SeamBinding} guarantees it via {@code blockRotationOf}
     * and {@code seamContinuous}); {@code dy} passes through untouched because traversable bindings
     * require the transform to carry +Y to +Y. Convention read out of the game, not assumed:
     * {@code Rotation.CLOCKWISE_90.rotate(Direction.EAST) == SOUTH}, i.e. CW90 carries (+X) to (+Z).
     */
    public BlockPos toFar(BlockPos localPos) {
        int dx = localPos.getX() - localAnchor.getX();
        int dy = localPos.getY() - localAnchor.getY();
        int dz = localPos.getZ() - localAnchor.getZ();
        int rx;
        int rz;
        switch (srcToDst) {
            case CLOCKWISE_90 -> {
                rx = -dz;
                rz = dx;
            }
            case CLOCKWISE_180 -> {
                rx = -dx;
                rz = -dz;
            }
            case COUNTERCLOCKWISE_90 -> {
                rx = dz;
                rz = -dx;
            }
            default -> {
                rx = dx;
                rz = dz;
            }
        }
        return farAnchor.offset(rx, dy, rz);
    }

    /**
     * True when the far counterpart is safe to touch WITHOUT a blocking chunk load or a bounds
     * fault. Checked PER CALL, not once at shadow creation: children probe above, below and beyond
     * the anchor, which can map into a different far chunk than the anchor's.
     */
    public boolean farResident(BlockPos localPos) {
        BlockPos t = toFar(localPos);
        return farLevel.isInsideBuildHeight(t) && farLevel.hasChunkAt(t);
    }

    /**
     * Count a cold decline, and queue a warm-up retry ONLY when the miss is the far CHUNK. An
     * out-of-build-height mapping is permanent — queueing it would re-serve the entry every tick
     * forever once the chunk loaded, since resolution would decline again on height each time.
     */
    private void declineCold(BlockPos localPos) {
        BlockPos t = toFar(localPos);
        SeamRailContinuity.declinedCold(sourceLevel, ownerCell,
            farLevel.isInsideBuildHeight(t)
                ? net.minecraft.core.GlobalPos.of(farLevel.dimension(), t)
                : null);
    }

    /**
     * Read a local position, routing past-the-plane positions to the far level.
     *
     * <p>THE WALK-BACK (depth &le; 0 reads the SOURCE, unrotated) is required, not tidy: a proxy at
     * the anchor probing BACK toward the seam must see the source rail, not the far cell behind the
     * far plane.
     *
     * <p>A cold or out-of-bounds far chunk reads as AIR rather than force-loading — exactly the
     * pre-(b) vanilla answer — and enqueues the owner cell for a warm-up retry
     * ({@link SeamRailContinuity#onServerTickEnd}). {@code SeamMirror} force-loads because placement
     * is a rare player-driven act; rail resolution is neither.
     */
    public BlockState readLocal(BlockPos localPos) {
        if (depth(localPos) <= 0) {
            return sourceLevel.getBlockState(localPos);
        }
        if (!farResident(localPos)) {
            declineCold(localPos);
            return Blocks.AIR.defaultBlockState();
        }
        return farLevel.getBlockState(toFar(localPos)).rotate(dstToSrc);
    }

    /**
     * Write a local position, routing past-the-plane positions to the far level under the write
     * budget. (b) only ever reaches this with a SHAPE change of an already-existing far rail —
     * {@code RailState.connectTo:204-205} and {@code place:331-333} write {@code this.state} with a
     * new shape property — so this never creates or removes a block and never touches a non-rail
     * cell; the budget is defence in depth, not the termination argument.
     */
    public boolean writeLocal(BlockPos localPos, BlockState localState, int flags) {
        if (depth(localPos) <= 0) {
            return sourceLevel.setBlock(localPos, localState, flags);
        }
        if (!farResident(localPos)) {
            declineCold(localPos);
            return false;
        }
        if (!SeamRailContinuity.enterWrite()) {
            return false;
        }
        try {
            return farLevel.setBlock(toFar(localPos), localState.rotate(srcToDst), flags);
        }
        finally {
            SeamRailContinuity.exitWrite();
        }
    }
}
