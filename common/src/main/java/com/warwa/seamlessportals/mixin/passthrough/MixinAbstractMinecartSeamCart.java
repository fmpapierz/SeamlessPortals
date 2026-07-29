package com.warwa.seamlessportals.mixin.passthrough;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamCartContinuity;
import com.warwa.seamlessportals.passthrough.SeamCartProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RS (d) — the {@code AbstractMinecart} half of the cart's seam hooks:
 * <ul>
 *   <li><b>Instrument</b> (probe-gated, observation only): {@code comeOffTrack} HEAD →
 *       {@link SeamCartProbe}. javap-verified 26.2:
 *       {@code protected void comeOffTrack(net.minecraft.server.level.ServerLevel)} — the sole
 *       derail sink for {@code OldMinecartBehavior.tick}'s not-on-rails branch.</li>
 *   <li><b>Fix</b> (recon §4(d) site 4): {@code getCurrentBlockPosOrRailBelow}'s rail-below tag
 *       reads route through {@link SeamCartContinuity#railAwareState}. javap-verified 26.2:
 *       exactly TWO {@code invokevirtual Level.getBlockState} sites in that method (the
 *       experimental-movement branch and the old-behaviour branch), owner {@code Level}.</li>
 * </ul>
 */
@Mixin(AbstractMinecart.class)
public abstract class MixinAbstractMinecartSeamCart {

    @Inject(method = "comeOffTrack", at = @At("HEAD"), require = 1)
    private void seamlessportals$probeComeOffTrack(ServerLevel level, CallbackInfo ci) {
        if (!AperturePassthroughLever.SEAM_CART_PROBE) {
            return;
        }
        SeamCartProbe.onComeOffTrack((AbstractMinecart) (Object) this, level);
    }

    @WrapOperation(
        method = "getCurrentBlockPosOrRailBelow",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 2, allow = 2
    )
    private BlockState seamlessportals$railAwareRead(
        Level level, BlockPos pos, Operation<BlockState> original
    ) {
        BlockState local = original.call(level, pos);
        return SeamCartContinuity.railAwareState(
            level, pos, local, (AbstractMinecart) (Object) this);
    }
}
