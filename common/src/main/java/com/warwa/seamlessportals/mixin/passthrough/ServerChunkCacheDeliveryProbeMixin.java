package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamDeliveryProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DELIVERY PROBE STAGE 3a — did {@code ServerChunkCache.blockChanged} run for the destination cell?
 *
 * <p>{@code ServerChunkCache.blockChanged} (REF {@code :458-465}) resolves a {@code ChunkHolder} and,
 * when it is absent, returns having done nothing. That is one of four independent points at which a
 * mirrored write vanishes while every call in the mirror path still reports success.
 *
 * <p>This records only that the call HAPPENED. Whether the holder existed is read from stage 3b —
 * {@code ChunkHolder.blockChanged} either fires or it does not. Deriving the holder's presence a
 * second way here would be a re-computation the measured code never performs, and a probe that
 * computes its own answer is how an instrument comes to disagree with the thing it measures.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheDeliveryProbeMixin {

    @Shadow @Final private ServerLevel level;

    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void seamlessportals$noteDeliveryChunkCacheCall(BlockPos pos, CallbackInfo ci) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        SeamDeliveryProbe.noteChunkCacheCalled(this.level, pos);
    }
}
