package com.warwa.seamlessportals.mixin.passthrough;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.warwa.seamlessportals.passthrough.SeamSignalContinuity;
import com.warwa.seamlessportals.passthrough.SeamWireBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * RS PASSTHROUGH (c) step 2 — <b>WIRE'S OTHER TWO READ FAMILIES CROSS THE SEAM</b> (spec §6.2
 * item 2 + the block-power intake).
 *
 * <ul>
 *   <li><b>Block power</b> — {@code getBlockSignal}'s one {@code Level.getBestNeighborSignal} call
 *       (javap: invokevirtual owner {@code Level}, offset 7, inside the {@code shouldSignal=false}
 *       window) unions the far side's strength
 *       ({@link SeamSignalContinuity#neighborSignalStrengthAcross}) — a far lever/torch/repeater
 *       powers dust across the plane. Far-side WIRE answers 0 inside the window (the latch lives on
 *       the shared block singleton — vanilla-correct for pretended adjacency); wire-to-wire goes
 *       through the decay bridge instead.</li>
 *   <li><b>Connection shape</b> — the 4-arg {@code getConnectingSide} overload's THREE
 *       {@code BlockGetter.getBlockState} reads (javap offsets 10/63/123: neighbour,
 *       neighbour-above, neighbour-below) see the far continuation when the step crosses a seam:
 *       the dust arm visually points into the plane, and — load-bearing for EMISSION — {@code
 *       getSignal}'s sideways-connected check passes, so step 1's far consumers (lamp, rail) can
 *       actually hear near dust. Non-Level getters (worldgen regions) pass through vanilla.</li>
 * </ul>
 *
 * <p>All LOCAL-FIRST, all additive, all F8-exception-proof ({@link SeamWireBridge}). The marked
 * mirror half never reaches these reads for its own evaluation — its {@code neighborChanged} is
 * cancelled by {@link MixinRedStoneWireBlockSeamAuthority} — but its passive {@code getSignal}
 * answers (serving far consumers) do, which is exactly right: the synced POWER is authoritative.
 */
@Mixin(RedStoneWireBlock.class)
public abstract class MixinRedStoneWireBlockSeamSignal {

    // ── getBlockSignal:7 — the wire's block-power intake, inside the shouldSignal window. ──
    @WrapOperation(
        method = "getBlockSignal",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "getBestNeighborSignal(Lnet/minecraft/core/BlockPos;)I"),
        require = 1, allow = 1
    )
    private int seamlessportals$blockPowerIntake(Level level, BlockPos pos, Operation<Integer> op) {
        int local = op.call(level, pos);
        if (local >= 15) {
            return local;               // ── LOCAL FIRST ──
        }
        return Math.max(local, SeamSignalContinuity.neighborSignalStrengthAcross(level, pos));
    }

    // ── getConnectingSide (4-arg overload only): the three neighbour reads. The 3-arg overload's
    //    own read and getMissingConnections' single read are both the wire's OWN above — never a
    //    crossing read — and stay untouched (the overload pin below is what scopes that). ──
    @WrapOperation(
        method = "getConnectingSide(Lnet/minecraft/world/level/BlockGetter;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)"
            + "Lnet/minecraft/world/level/block/state/properties/RedstoneSide;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;"
            + "getBlockState(Lnet/minecraft/core/BlockPos;)"
            + "Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 3, allow = 3
    )
    private BlockState seamlessportals$connectionRead(
        BlockGetter getter, BlockPos queryPos, Operation<BlockState> op,
        @Local(argsOnly = true) BlockPos wirePos,
        @Local(argsOnly = true) Direction dir
    ) {
        BlockState local = op.call(getter, queryPos);
        if (getter instanceof Level level) {
            return SeamWireBridge.connectionRead(level, wirePos, queryPos, local);
        }
        return local;
    }
}
