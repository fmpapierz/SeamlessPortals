package com.warwa.seamlessportals.mixin.passthrough;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.warwa.seamlessportals.passthrough.SeamSignalContinuity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * RS PASSTHROUGH (c) — THE POWERED-RAIL CHAIN CROSSES THE SEAM ({@code REDSTONE_C_SPEC.md} §2.2,
 * §3 M1). The first consumer of the (c) signal primitives, exactly as {@code MixinRailStateSeam}
 * was (b)'s.
 *
 * <p>Three read families, all LOCAL-FIRST (vanilla answers first; the seam only ever ADDS power):
 * <ul>
 *   <li>{@code updateState}'s own-power intake and {@code isSameRailWithPower}'s per-visited-rail
 *       intake: {@code hasNeighborSignal} at a bound seam cell unions the far image's neighborhood
 *       ({@link SeamSignalContinuity#hasNeighborSignalAcross}).</li>
 *   <li>{@code findPoweredRailSignal}'s two step probes: a step leaving a bound seam cell through
 *       the plane re-enters the SAME vanilla walk in the far level with translated position,
 *       rotated axis shape and re-derived forward flag
 *       ({@link SeamSignalContinuity#walkRedirect}). Vanilla's {@code searchDepth} rides along, so
 *       the global 8-rail cap holds across dimensions, and a chain crossing several portals
 *       re-enters this wrap at each seam.</li>
 * </ul>
 *
 * <p>Bytecode facts the targets stand on (javap'd this session, spec F2/F3): both
 * {@code hasNeighborSignal} sites are {@code invokevirtual} owner {@code Level} (updateState
 * offset 17, isSameRailWithPower offset 118); {@code findPoweredRailSignal} contains exactly TWO
 * {@code isSameRailWithPower} self-calls (offsets 252, 288) and ZERO Level reads of its own — the
 * 26.2 walk reads live in {@code isSameRailWithPower}, so the step probe is the one site where
 * both the current cell (via {@code @Local}) and the stepped target are in scope.
 */
@Mixin(PoweredRailBlock.class)
public abstract class MixinPoweredRailBlockSeamSignal {

    // ── updateState:130 — the evaluating rail's own hasNeighborSignal. ──
    @WrapOperation(
        method = "updateState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "hasNeighborSignal(Lnet/minecraft/core/BlockPos;)Z"),
        require = 1, allow = 1
    )
    private boolean seamlessportals$ownIntake(Level l, BlockPos p, Operation<Boolean> op) {
        return seamlessportals$union(l, p, op);
    }

    // ── isSameRailWithPower:117 — each visited rail's hasNeighborSignal during a walk. ──
    @WrapOperation(
        method = "isSameRailWithPower",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "hasNeighborSignal(Lnet/minecraft/core/BlockPos;)Z"),
        require = 1, allow = 1
    )
    private boolean seamlessportals$walkIntake(Level l, BlockPos p, Operation<Boolean> op) {
        return seamlessportals$union(l, p, op);
    }

    @Unique
    private boolean seamlessportals$union(Level l, BlockPos p, Operation<Boolean> op) {
        return op.call(l, p) || SeamSignalContinuity.hasNeighborSignalAcross(l, p);
    }

    // ── findPoweredRailSignal:100-102 — the two step probes (stepped cell, then one below). A
    //    probe answered false locally is retried across the seam when the step leaves a bound seam
    //    cell through the plane. @Local(argsOnly): the walk's current cell is the method's only
    //    BlockPos parameter (the stepped targets are stack temporaries). ──
    @WrapOperation(
        method = "findPoweredRailSignal",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/PoweredRailBlock;"
            + "isSameRailWithPower(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "ZILnet/minecraft/world/level/block/state/properties/RailShape;)Z"),
        require = 2, allow = 2
    )
    private boolean seamlessportals$walkStep(
        PoweredRailBlock self, Level level, BlockPos stepped, boolean forward, int depth,
        RailShape dirShape, Operation<Boolean> op,
        @Local(argsOnly = true) BlockPos currentPos
    ) {
        // The OUTERMOST wrap invocation of a walk is the evaluating rail's own first step, so its
        // currentPos IS the walk origin — recorded so a cold-far decline can retry the rail that
        // actually needed the answer, not the (already-powered, never-flipping) seam cell.
        boolean owned = SeamSignalContinuity.walkOriginBegin(level, currentPos);
        try {
            if (op.call(self, level, stepped, forward, depth, dirShape)) {
                return true;                               // ── LOCAL FIRST ──
            }
            SeamSignalContinuity.WalkRedirect r =
                SeamSignalContinuity.walkRedirect(level, currentPos, stepped, dirShape);
            if (r == null) {
                return false;
            }
            boolean crossed = op.call(self, r.farLevel(), r.farPos(), r.forward(), depth, r.dirShape());
            if (!crossed) {
                // The crossed walk DIED — name the reason at the landing cell (live diagnosis:
                // wrong block type / not powered / incompatible shape are indistinguishable from
                // the outside, and each has a different fix).
                SeamSignalContinuity.probeWalkDied(r.farLevel(), r.farPos(), r.dirShape(), self);
            }
            return crossed;
        }
        finally {
            if (owned) {
                SeamSignalContinuity.walkOriginEnd();
            }
        }
    }
}
