package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import com.warwa.seamlessportals.passthrough.SeamOccupancy;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ★ TWO-OBJECT PICK (user round 27: "the block that was placed second does not have correct
 * creative middle mouse button select, it selects the first block"). 26.2 resolves the creative
 * pick SERVER-side: {@code handlePickItemFromBlock} reads {@code ServerLevel.getBlockState(pos)}
 * — which is always the PRIMARY, because the chunk can hold only one state and the second object
 * lives in the seam side table. Redirect that one read: when the cell holds a secondary and the
 * player's actual crosshair ray struck the secondary's half (the same {@code Entity.pick} +
 * {@code halfFromHit} routing the break discriminators use), answer with the secondary's state —
 * everything downstream ({@code getCloneItemStack}, creative give/swap) then operates on the
 * block the player is looking at.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPickSecondaryMixin {

    @Shadow
    @Final
    public ServerPlayer player;

    @Redirect(
        method = "handlePickItemFromBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getBlockState("
                + "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    private BlockState seamlessportals$pickTargetedOccupant(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!SeamFractional.active()) {
            return state;
        }
        SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(level, pos);
        if (sec == null) {
            return state;
        }
        var seam = SeamRegistry.lookup(level, pos);
        if (seam == null) {
            return state;
        }
        for (SeamRegistry.SeamBinding binding : seam.bindings()) {
            if (binding == null || binding.cut() == null) {
                continue;
            }
            HitResult pick = this.player.pick(6.0, 1.0f, false);
            if (pick instanceof BlockHitResult bhr
                && bhr.getType() != HitResult.Type.MISS
                && bhr.getBlockPos().equals(pos)) {
                byte targetHalf = SeamOccupancy.halfFromHit(bhr.getLocation(), pos,
                    binding.srcFacing().getAxis(), binding.cut().srcPlaneOffset());
                if (targetHalf == sec.half()) {
                    return sec.state();
                }
            }
            return state;
        }
        return state;
    }
}
