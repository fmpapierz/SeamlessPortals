package com.warwa.seamlessportals.mixin.passthrough;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.warwa.seamlessportals.passthrough.SeamCartContinuity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * RS (d) — the cart's rail-resolution READ bridge, {@code OldMinecartBehavior} half (recon §4(d)
 * sites 1–3: the on-rails test in {@code tick}, the shape read in {@code moveAlongTrack}, and the
 * {@code getPos}/{@code getPosOffs} lane-snap lookahead). javap-verified 26.2: exactly SIX
 * {@code invokevirtual net/minecraft/world/level/Level.getBlockState} sites in this class —
 * tick 1, moveAlongTrack 1, getPosOffs 2, getPos 2 — all owner {@code Level} (the F2 owner rule).
 *
 * <p>Every read routes through {@link SeamCartContinuity#railAwareState}: LOCAL-FIRST, far answer
 * only for the through-image cell of an adjacent bound seam cell. See that class for the measured
 * defect this closes and the boundedness argument.
 */
@Mixin(OldMinecartBehavior.class)
public abstract class MixinOldMinecartBehaviorSeamRail {

    /**
     * The cart this behaviour drives, via {@link MinecartBehaviorAccessor} — the bridge needs it
     * for the straddle test. Not a {@code @Shadow}: the field is on the superclass, which Mixin
     * does not search for shadow fields (see the accessor's javadoc).
     */
    private AbstractMinecart seamlessportals$cart() {
        return ((MinecartBehaviorAccessor) this).seamlessportals$minecart();
    }

    @WrapOperation(
        method = {"tick", "moveAlongTrack", "getPosOffs", "getPos"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 6, allow = 6
    )
    private BlockState seamlessportals$railAwareRead(
        Level level, BlockPos pos, Operation<BlockState> original
    ) {
        BlockState local = original.call(level, pos);
        return SeamCartContinuity.railAwareState(level, pos, local, seamlessportals$cart());
    }

    /**
     * Scopes the bridged answer OUT of {@code tick}'s ACTIVATOR_RAIL action branch (adversarial
     * panel, 2026-07-28). The one wrapped read in {@code tick} feeds three consumers —
     * {@code setOnRails}, the {@code moveAlongTrack} gate, and
     * {@code if (state.is(ACTIVATOR_RAIL)) minecart.activateMinecart(level, x, y, z, POWERED)}.
     * The first two are the fix; the third would run a FAR rail's action in the NEAR level at a
     * locally-air cell during the stranded tick — ejecting a mob passenger on the wrong side of
     * the seam (so only the empty cart teleports), priming a TNT cart a tick early near-side, or
     * running a command-block cart's command with near-level context.
     *
     * <p>javap-verified 26.2: exactly ONE
     * {@code invokevirtual AbstractMinecart.activateMinecart:(Lnet/minecraft/server/level/ServerLevel;IIIZ)V}
     * in this class (in {@code tick}). The guard is a no-op under vanilla — that branch is only
     * reachable with the state read from that same cell, so a locally-railless cell there means
     * the state came from the bridge.
     */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;activateMinecart(Lnet/minecraft/server/level/ServerLevel;IIIZ)V"
        ),
        require = 1, allow = 1
    )
    private void seamlessportals$skipBridgedActivation(
        AbstractMinecart cart, ServerLevel level, int x, int y, int z, boolean powered,
        Operation<Void> original
    ) {
        if (SeamCartContinuity.actionWouldBeBridged(level, x, y, z)) {
            return;
        }
        original.call(cart, level, x, y, z, powered);
    }
}
