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
        // F8 — a claimed rail's LOCAL intake skips its empty-half direction (this side's
        // behind-plane region belongs to the other stitching); unclaimed cells stay vanilla.
        net.minecraft.core.Direction emptyDir =
            l instanceof net.minecraft.server.level.ServerLevel
                ? com.warwa.seamlessportals.passthrough.SeamFractional.emptyHalfDir(l, p)
                : null;
        boolean local = emptyDir == null
            ? op.call(l, p)
            : SeamSignalContinuity.hasLocalNeighborSignalSkippingEmptyHalf(l, p, emptyDir);
        return local || SeamSignalContinuity.hasNeighborSignalAcross(l, p);
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
            // ── CROSSING-FIRST (RS-XTALK fix, 2026-08-22): a step that leaves a bound seam cell
            // through the plane never consults the raw stepped cell — in raw coordinates that
            // cell holds the OTHER stitching (the opposite through-path), and the pre-fix
            // local-first read there let the unpowered path's walk continue raw into the powered
            // path's approach and find its power source (the two-through-paths crosstalk).
            // Which exits cross is phase- and depth-dependent (a passed-through cell severs both
            // exits; the cell's own depth-0 evaluation only its claimed empty half — see
            // walkStepCrossesSeam). Non-crossing steps keep the local-first order, so all-local
            // circuits stay byte-identical to vanilla; under -PdisableSeamWalkSever (or any
            // bridge-disabling lever) walkStepCrossesSeam answers false and the pre-fix order
            // returns. ──
            // ── ARRIVING-SIDE GATE (RS-XTALK round 5): a probe INTO a claimed seam cell from
            // its empty half asks the FRAGMENT (the second path's rail), never the chunk
            // primary (the other path's). passable+quiet+door-resolved → the walk continues in
            // the far level through the fragment's own door, at depth+1 (the cell consumed a
            // step); cold far → severed with the retry queued. Primary-side arrivals and
            // unclaimed cells return null and take the vanilla probe below. ──
            SeamSignalContinuity.IntoProbe ip = SeamSignalContinuity.probeIntoFragmentHalf(
                level, currentPos, stepped, dirShape, self);
            if (ip != null) {
                if (!ip.passable()) {
                    return false;
                }
                if (ip.localSignal()) {
                    return true;
                }
                if (ip.farDoor() == null) {
                    return false;
                }
                boolean fragCrossed = op.call(self, ip.farDoor().farLevel(),
                    ip.farDoor().farPos(), ip.farDoor().forward(), depth + 1,
                    ip.farDoor().dirShape());
                if (!fragCrossed) {
                    SeamSignalContinuity.probeWalkDied(ip.farDoor().farLevel(),
                        ip.farDoor().farPos(), ip.farDoor().dirShape(), self);
                }
                return fragCrossed;
            }
            boolean crossing =
                SeamSignalContinuity.walkStepCrossesSeam(level, currentPos, stepped, depth);
            if (!crossing && op.call(self, level, stepped, forward, depth, dirShape)) {
                return true;                               // ── LOCAL (non-crossing steps) ──
            }
            if (!crossing && SeamSignalContinuity.walkStepIsClaimedOwnSide(
                level, currentPos, stepped, depth)) {
                // A claimed cell's own-side probe is STRICTLY local: the additive redirect for
                // this direction is the co-located far cell — the OTHER path's territory (the
                // ARM 3 "path-2-only lit the pair" repro). Its far continuation is the
                // empty-half door, handled by the crossing rule above.
                return false;
            }
            SeamSignalContinuity.WalkRedirect r =
                SeamSignalContinuity.walkRedirect(level, currentPos, stepped, dirShape);
            if (r == null) {
                // A crossing step lands here only when the far side is unresolvable (cold far —
                // walkRedirect queued the warm-up retry): SEVERED, never the raw fallback.
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
