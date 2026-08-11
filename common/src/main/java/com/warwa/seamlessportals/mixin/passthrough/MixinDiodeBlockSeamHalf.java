package com.warwa.seamlessportals.mixin.passthrough;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.warwa.seamlessportals.passthrough.SeamWireBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * F8 — <b>REPEATER/COMPARATOR RAW WIRE READS RESPECT THE HALF</b> (panel finding, the D3 family:
 * two vanilla paths special-case {@code REDSTONE_WIRE} and read its {@code POWER} raw, bypassing
 * both the round-27 {@code getSignal} gate and the wire bridge).
 *
 * <ul>
 *   <li>{@code getInputSignal} — the main input: after the gated {@code getSignal} read, vanilla
 *       does one {@code Level.getBlockState} (javap offset 43) and maxes in raw wire POWER. The
 *       wrap substitutes {@link SeamWireBridge#halfGate}: a claimed seam wire read from its empty
 *       side answers as the Secondary (unpowered, v1) or air.</li>
 *   <li>{@code getAlternateSignal} — comparator side inputs: two
 *       {@code SignalGetter.getControlInputSignal} calls (javap offsets 43/59), each of which
 *       raw-reads wire POWER internally. The wrap zeroes the answer when the diode sits on the
 *       queried cell's empty side ({@link SeamWireBridge#readerOnEmptySide}).</li>
 * </ul>
 *
 * <p>Both are exception-proofed inside the bridge helpers and inert for unclaimed cells and
 * non-Level getters (worldgen).
 */
@Mixin(DiodeBlock.class)
public abstract class MixinDiodeBlockSeamHalf {

    @WrapOperation(
        method = "getInputSignal",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "getBlockState(Lnet/minecraft/core/BlockPos;)"
            + "Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 1, allow = 1
    )
    private BlockState seamlessportals$mainInputRead(
        Level level, BlockPos queryPos, Operation<BlockState> op,
        @Local(argsOnly = true) BlockPos diodePos
    ) {
        BlockState local = op.call(level, queryPos);
        return SeamWireBridge.halfGate(level, diodePos, queryPos, local);
    }

    @WrapOperation(
        method = "getAlternateSignal",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/SignalGetter;"
            + "getControlInputSignal(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/Direction;Z)I"),
        require = 2, allow = 2
    )
    private int seamlessportals$sideInputRead(
        SignalGetter getter, BlockPos queryPos, Direction dir, boolean diodesOnly,
        Operation<Integer> op,
        @Local(argsOnly = true) BlockPos diodePos
    ) {
        int vanilla = op.call(getter, queryPos, dir, diodesOnly);
        if (vanilla > 0 && getter instanceof Level level
            && SeamWireBridge.readerOnEmptySide(level, diodePos, queryPos)) {
            return 0;
        }
        return vanilla;
    }
}
