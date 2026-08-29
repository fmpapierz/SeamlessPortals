package com.warwa.seamlessportals.mixin.passthrough;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.warwa.seamlessportals.passthrough.SeamWireBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * RS PASSTHROUGH (c) step 2 — <b>DUST-TO-DUST DECAY CROSSES THE SEAM</b> (spec §6.2 item 1, the
 * user-visible case).
 *
 * <p>Spec F11: wire-to-wire decay never touches the {@code SignalGetter} family step 1 bridged —
 * {@code getIncomingWireSignal} reads neighbour {@code getBlockState} + {@code POWER} RAW. This
 * wraps those reads at their source. The base evaluator hosts the method for BOTH evaluators
 * (javap: {@code DefaultRedstoneWireEvaluator} inherits it; the experimental one overrides only
 * {@code getWireSignal}), so one mixin covers the default path and the experimental scan alike.
 *
 * <p>Bytecode facts the target stands on (javap'd this session, raw dump in the session
 * scratchpad): exactly FOUR {@code invokevirtual Level.getBlockState} sites at offsets 43
 * (neighbour), 81 (own-above conductor check), 107 (neighbour-above), 145 (neighbour-below).
 * {@link SeamWireBridge#decayRead} classifies by offset-from-the-wire, so the own-above read
 * passes through untouched; the three neighbour geometries substitute the far continuation (and
 * its above/below) when the step crosses a seam — LOCAL-FIRST, never-load, F8-exception-proof.
 */
@Mixin(RedstoneWireEvaluator.class)
public abstract class MixinRedstoneWireEvaluatorSeam {

    @WrapOperation(
        method = "getIncomingWireSignal",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "getBlockState(Lnet/minecraft/core/BlockPos;)"
            + "Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 4, allow = 4
    )
    private BlockState seamlessportals$decayRead(
        Level level, BlockPos queryPos, Operation<BlockState> op,
        @Local(argsOnly = true) BlockPos wirePos
    ) {
        BlockState local = op.call(level, queryPos);
        return SeamWireBridge.decayRead(level, wirePos, queryPos, local);
    }
}
