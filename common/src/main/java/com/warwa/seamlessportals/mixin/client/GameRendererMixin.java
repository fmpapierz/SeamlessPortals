package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.CameraTransitionHandler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRendererMixin - camera transition ticking (HEAD) + secondary render-frame
 * end (TAIL). Portal rendering is now done via Fabric's LevelRenderEvents API.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void seamlessportals$beforeRender(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        CameraTransitionHandler.tick();
    }

    /**
     * The missing 1:1 counterpart of vanilla's {@code this.renderBuffers.endFrame()}
     * (GameRenderer.render:447, which covers ONLY the GameRenderer's own buffers) for
     * every RenderBuffers the mod created: promoted/secondary renderers + the portal
     * sub-render pool. Without it their StagedVertexBuffer pools never fence-recycle —
     * a GPU-buffer leak of tens of MB/s while a portal is in view, ending in
     * multi-second driver paging stalls inside arbitrary GL calls (the [SEAMLESS
     * STUCK]/[SEAMLESS FREEZE] nglDrawElementsInstancedBaseVertex signatures,
     * 2026-07-05). TAIL = right after vanilla's own endFrame, the same lifecycle point.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void seamlessportals$endSecondaryFrames(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        long t0 = System.nanoTime();
        com.warwa.seamlessportals.client.PortalWorldManager.endSecondaryRenderFrames();
        com.warwa.seamlessportals.render.PortalRenderBuffersPool.endFramePooled();
        com.warwa.seamlessportals.render.PerfTimers.add("endSecondaryFrames", System.nanoTime() - t0);
    }
}
