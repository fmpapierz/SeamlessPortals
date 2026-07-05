package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.chunk.PortalChunkTracker;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels vanilla's post-teleport re-send of chunks the client ALREADY holds.
 *
 * <p>After a seamless crossing, {@code ServerPlayer.teleportTo} rebuilds the
 * player's chunk tracking from EMPTY (ChunkMap.updatePlayerStatus →
 * applyChunkTrackingView diffs against nothing), marking the entire view
 * distance pending-to-send — even though {@code PortalChunkTracker} already
 * streamed the near-field via redirected packets and the block mirror kept it
 * live (the promoted client level is current). Decoding that re-send burst on
 * the render thread was ~85% of the per-crossing ~150-300ms freeze
 * ([SEAMLESS STUCK]: PalettedContainer reads + light-engine init).
 *
 * <p>{@code consumeVanillaResendSuppression} is ONE-SHOT per chunk and armed
 * only at a crossing (SeamlessServerTeleport → PortalChunkTracker
 * .onPlayerCrossing) for exactly the redirected chunk set — later legitimate
 * sends (view re-enter after a real forget) pass through untouched. Targets
 * the {@code (ServerPlayer, ChunkPos)} overload used by the tracking-view
 * diff; the {@code (ServerPlayer, LevelChunk)} broadcast overload (chunks that
 * FINISH loading while in view — chunks we never sent) is deliberately left
 * alone.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapResendSuppressMixin {

    @Inject(
        method = "markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void seamlessportals$suppressClientHeldChunks(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
        if (PortalChunkTracker.consumeVanillaResendSuppression(player, pos)) {
            ci.cancel();
        }
    }
}
