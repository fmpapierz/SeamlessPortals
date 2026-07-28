package com.warwa.seamlessportals.mixin.passthrough;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.warwa.seamlessportals.passthrough.SeamSignalContinuity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneLampBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * RS PASSTHROUGH (c) — A SEAM LAMP LIGHTS FROM FAR SIGNAL ({@code REDSTONE_C_SPEC.md} §3 M3).
 * The cheapest non-rail consumer: a lamp pair at a seam cell unions both images' neighborhoods in
 * all three of its intake sites (placement-time initial state, neighborChanged, and the 4-tick
 * scheduled turn-off).
 *
 * <p>⚠ OWNER DRIFT, caught by javap before first launch (the standing bytecode rule): the
 * {@code tick} method's parameter is typed {@code ServerLevel}, so ITS {@code hasNeighborSignal}
 * invocation has owner {@code ServerLevel} — the other two are owner {@code Level}. Three wraps,
 * two target strings.
 *
 * <p>The client-side {@code getStateForPlacement} probe answers vanilla (the union helper refuses
 * non-ServerLevel receivers); the server's own placement resolution carries the far contribution
 * and the ack corrects the one-frame client prediction.
 */
@Mixin(RedstoneLampBlock.class)
public abstract class MixinRedstoneLampBlockSeamSignal {

    @WrapOperation(
        method = "getStateForPlacement",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "hasNeighborSignal(Lnet/minecraft/core/BlockPos;)Z"),
        require = 1, allow = 1
    )
    private boolean seamlessportals$placementIntake(Level l, BlockPos p, Operation<Boolean> op) {
        return op.call(l, p) || SeamSignalContinuity.hasNeighborSignalAcross(l, p);
    }

    @WrapOperation(
        method = "neighborChanged",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "hasNeighborSignal(Lnet/minecraft/core/BlockPos;)Z"),
        require = 1, allow = 1
    )
    private boolean seamlessportals$neighborIntake(Level l, BlockPos p, Operation<Boolean> op) {
        return op.call(l, p) || SeamSignalContinuity.hasNeighborSignalAcross(l, p);
    }

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;"
            + "hasNeighborSignal(Lnet/minecraft/core/BlockPos;)Z"),
        require = 1, allow = 1
    )
    private boolean seamlessportals$tickIntake(ServerLevel l, BlockPos p, Operation<Boolean> op) {
        return op.call(l, p) || SeamSignalContinuity.hasNeighborSignalAcross(l, p);
    }
}
