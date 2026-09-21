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
import net.minecraft.world.level.block.RedstoneWireBlock; // 26.3: vanilla renamed RedStoneWireBlock -> RedstoneWireBlock (capitalisation only, same package)
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
@Mixin(RedstoneWireBlock.class)
public abstract class MixinRedStoneWireBlockSeamSignal {

    // ── getBlockSignal:7 — the wire's block-power intake, inside the shouldSignal window. ──
    @WrapOperation(
        method = "getBlockSignal",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "getBestNeighborSignal(Lnet/minecraft/core/BlockPos;)I"),
        require = 1, allow = 1
    )
    private int seamlessportals$blockPowerIntake(Level level, BlockPos pos, Operation<Integer> op) {
        // F8: a claimed cell's own intake skips its empty-half direction — that neighbour is
        // this side's behind-plane region (the other stitched space). Unclaimed → vanilla.
        net.minecraft.core.Direction emptyDir =
            level instanceof net.minecraft.server.level.ServerLevel
                ? com.warwa.seamlessportals.passthrough.SeamFractional.emptyHalfDir(level, pos)
                : null;
        int local = emptyDir == null
            ? op.call(level, pos)
            : SeamSignalContinuity.localNeighborSignalSkippingEmptyHalf(level, pos, emptyDir);
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
        // 26.3: 3 -> 1. Of the three neighbour reads (mc262-ref RedStoneWireBlock.java:240 relativePos, :243 relativePos.above(),
        // :253 relativePos.below()) only the first is still a getBlockState INVOKE in this method (mc263-ref RedstoneWireBlock.java
        // :233; javap 26.3 4-arg getConnectingSide: ONE BlockGetter.getBlockState at offset 10). The other two moved inside the
        // new static shouldConnectTo(BlockGetter, BlockPos) — see seamlessportals$connectionReadInShouldConnectTo below.
        require = 1, allow = 1
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

    // 26.3: the above()/below() neighbour reads. 26.2 read the state HERE and tested it:
    // `shouldConnectTo(level.getBlockState(relativePos.above()))` / `..below()` (mc262-ref :243,:253 -> shouldConnectTo(BlockState)
    // :381-383). 26.3 moved the read into a new helper, `shouldConnectTo(level, relativePos.above())` / `..below()` (mc263-ref
    // :236,:246), whose whole body is `return shouldConnectTo(level.getBlockState(pos), level, pos, null);` (:379-381; javap 26.3:
    // two invokestatic shouldConnectTo:(BlockGetter;BlockPos;)Z at offsets 63 and 121 of the 4-arg getConnectingSide, and these
    // are its ONLY callers). The read can no longer be wrapped where it happens — the helper is static and has no wire position —
    // so the two CALLS are wrapped here, where wirePos is still in scope, and the helper's one-line body is evaluated with the
    // seam-substituted state exactly as 26.2 evaluated shouldConnectTo(<substituted state>). No substitution -> the untouched
    // vanilla call. Scope is unchanged: only the 4-arg overload's three neighbour reads.
    @WrapOperation(
        method = "getConnectingSide(Lnet/minecraft/world/level/BlockGetter;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)"
            + "Lnet/minecraft/world/level/block/state/properties/RedstoneSide;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/RedstoneWireBlock;"
            + "shouldConnectTo(Lnet/minecraft/world/level/BlockGetter;"
            + "Lnet/minecraft/core/BlockPos;)Z"),
        require = 2, allow = 2
    )
    private boolean seamlessportals$connectionReadInShouldConnectTo(
        BlockGetter getter, BlockPos queryPos, Operation<Boolean> op,
        @Local(argsOnly = true) BlockPos wirePos,
        @Local(argsOnly = true) Direction dir
    ) {
        if (getter instanceof Level level) {
            BlockState local = getter.getBlockState(queryPos);
            BlockState read = SeamWireBridge.connectionRead(level, wirePos, queryPos, local);
            if (read != local) {
                return shouldConnectTo(read, getter, queryPos, null);
            }
        }
        return op.call(getter, queryPos);
    }

    // 26.3: the helper's delegate (mc263-ref RedstoneWireBlock.java:383-385; javap: protected static
    // shouldConnectTo:(BlockState;BlockGetter;BlockPos;Direction;)Z) — shadowed so the substituted state runs vanilla's own test.
    @org.spongepowered.asm.mixin.Shadow
    protected static boolean shouldConnectTo(
        BlockState state, BlockGetter level, BlockPos pos, @org.jetbrains.annotations.Nullable Direction direction
    ) {
        throw new AssertionError();
    }
}
