package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
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
        // Capture the context at QUEUE time. For a Phase-4c REDIRECTED chunk, the
        // queue happens inside RedirectedPacketApplier's world-switch, so this.level
        // is the DESTINATION ClientLevel and mc.levelRenderer is the dest renderer
        // here. For a normal active-world chunk, these are just the active world.
        final ClientLevel capturedLevel = this.level;
        final Minecraft mc = Minecraft.getInstance();
        final LevelRenderer capturedRenderer =
            ((MinecraftAccessorMixin) mc).seamlessportals$getLevelRenderer();
        return () -> {
            if (capturedLevel == null) {
                // Listener was detached from a level when this was queued — the
                // light update is stale. Drop it silently; server resends if needed.
                return;
            }
            // The lambda reads this.level (applyLightData / getChunkSource) AND
            // mc.levelRenderer (the chunk-ready → occlusion-graph signal). For a
            // redirected chunk it now drains on the DEST's pollLightUpdates while
            // the listener already points back at the active world — so temporarily
            // re-establish the captured (dest) context. For a normal chunk
            // captured == current, so this is a pure no-op (no swap).
            final MinecraftAccessorMixin macc = (MinecraftAccessorMixin) mc;
            final ClientLevel currentLevel = this.level;
            final boolean redirect = (capturedLevel != currentLevel);
            final LevelRenderer savedRenderer = redirect ? macc.seamlessportals$getLevelRenderer() : null;
            if (redirect) {
                this.level = capturedLevel;
                macc.seamlessportals$setLevelRenderer(capturedRenderer);
            }
            try {
                original.run();
            } catch (NullPointerException npe) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS GUARD] NPE in queued light-update lambda "
                        + "(listener.level transient null during teleport): {}",
                    npe.getMessage());
            } finally {
                if (redirect) {
                    this.level = currentLevel;
                    macc.seamlessportals$setLevelRenderer(savedRenderer);
                }
            }
        };
    }
}
