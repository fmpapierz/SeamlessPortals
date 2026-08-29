package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamDeliveryProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DELIVERY PROBE STAGE 2 — did the mirrored write reach {@code sendBlockUpdated} at all?
 *
 * <p>{@code Level.markAndNotifyBlock} (REF {@code Level.java:238-248}) guards the call with three
 * separate conditions — the state read back must be {@code ==} the state asked for, flag 2 must be
 * set, and the chunk's {@code FullChunkStatus} must be at or after {@code BLOCK_TICKING}. All three
 * fail SILENTLY. Probing here rather than inside {@code markAndNotifyBlock} keeps the instrument off
 * the hottest method in the block system: the stage-1 record already carries the read-back state and
 * the chunk status, so "stage 2 NOT-REACHED" plus those two values names which clause blocked it.
 *
 * <p>Default-off: one static-final boolean read when disarmed.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelDeliveryProbeMixin {

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void seamlessportals$noteDeliveryNotify(
        BlockPos pos, BlockState old, BlockState current, int updateFlags, CallbackInfo ci
    ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        SeamDeliveryProbe.noteNotify((ServerLevel) (Object) this, pos, updateFlags);
    }
}
