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
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.3 FORGE-ONLY ({@code FORGE_ONLY_MIXINS}) loader-shape variant of {@link MixinRedStoneWireBlockSeamSignal} —
 * RS PASSTHROUGH (c) step 2 — <b>WIRE'S OTHER TWO READ FAMILIES CROSS THE SEAM</b> (spec §6.2
 * item 2 + the block-power intake). Everything that class's note says applies here unchanged.
 *
 * <p><b>Why a variant.</b> Vanilla 26.3 moved the neighbour-above / neighbour-below reads of the 4-arg
 * {@code getConnectingSide} into a new static {@code shouldConnectTo(BlockGetter, BlockPos)}, which is why the
 * vanilla-shape class wraps ONE {@code getBlockState} plus TWO {@code shouldConnectTo} calls. MinecraftForge
 * 26.3-66.0.2 patches that method back to reading the state in place and testing it through its own
 * {@code BlockState.canRedstoneConnectTo(BlockGetter, BlockPos, Direction)} — javap -c on the Forge jar, 4-arg
 * {@code getConnectingSide}: {@code BlockGetter.getBlockState} at offsets 10 (neighbour), 63 (neighbour-above) and 145
 * (neighbour-below), {@code canRedstoneConnectTo} at 75/110/154, and NO {@code shouldConnectTo(BlockGetter, BlockPos)}
 * call at all (audit vs the Forge jar: the vanilla-shape wraps match 3 > allow=1 and 0 < require=2). Those three
 * in-place reads are exactly the 26.2 shape (mc262-ref RedStoneWireBlock.java:240/:243/:253), so the connection wrap
 * below IS the 26.2 source's wrap, verbatim — {@code require = 3, allow = 3} included — and the substituted state is
 * then judged by Forge's own connect test, as 26.2 judged it by {@code shouldConnectTo(<substituted state>)}.
 * {@link MixinRedStoneWireBlockSeamSignal} is skipped on Forge ({@code NON_FORGE_MIXINS}); the block-power intake wrap
 * is that class's, verbatim (audit: 1 site on Forge, as everywhere).
 */
@Mixin(RedstoneWireBlock.class)
public abstract class MixinRedStoneWireBlockSeamSignalForge {

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
