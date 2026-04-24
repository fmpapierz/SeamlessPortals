package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Guard the lambda queued inside {@code ClientPacketListener
 * .handleLevelChunkWithLight} against a null {@code this.level} when the
 * lambda finally runs (deferred via {@code ClientLevel.queueLightUpdate}
 * and drained in {@code ClientLevel.pollLightUpdates}).
 *
 * <p>Background: {@code handleLevelChunkWithLight} schedules a {@code Runnable}
 * that does
 * <pre>
 *   applyLightData(...);
 *   LevelChunk chunk = this.level.getChunkSource().getChunk(...);
 *   enableChunkLight(chunk, ...);
 *   minecraft.levelRenderer.onChunkReadyToRender(...);
 * </pre>
 * and queues it on {@code this.level}. If we're mid-teleport, the
 * listener's {@code this.level} can be transiently null during
 * {@link HandleRespawnMixin}'s {@code @Shadow} rewrites. When the queued
 * Runnable finally runs (next
 * {@code ClientLevel.pollLightUpdates}), it hits an NPE at the
 * {@code this.level.getChunkSource()} access just past the already-guarded
 * {@code applyLightData} call (see sibling
 * {@link ApplyLightDataGuardMixin}).
 *
 * <p>Fix: via {@code @ModifyArg} wrap the Runnable before it's passed to
 * {@code queueLightUpdate}. The wrapper bails out if {@code this.level}
 * is null at execution time — the light update targets a level that's
 * no longer current anyway, so dropping it is correct. Broad catch is
 * defensive: if a future vanilla change adds another null-dereference
 * path inside the lambda, we won't crash the game.
 */
@Mixin(ClientPacketListener.class)
public abstract class ChunkLightLambdaGuardMixin {

    @Shadow private ClientLevel level;

    @ModifyArg(
        method = "handleLevelChunkWithLight",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;queueLightUpdate(Ljava/lang/Runnable;)V"
        ),
        index = 0,
        require = 1
    )
    private Runnable seamlessportals$wrapLightUpdateLambda(Runnable original) {
        return () -> {
            if (this.level == null) {
                // Listener detached from a level — the queued light update
                // is stale. Drop it silently; server will resend if needed.
                return;
            }
            try {
                original.run();
            } catch (NullPointerException npe) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS GUARD] NPE in queued light-update lambda "
                        + "(listener.level transient null during teleport): {}",
                    npe.getMessage());
            }
        };
    }
}
