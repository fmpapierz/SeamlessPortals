package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.SeamlessClientTeleport;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only mixin: logs LevelRenderer.update's pre- and post-
 * visibleSections count for a short window after a client-first teleport
 * swap. Armed by {@link SeamlessClientTeleport#diagLogUpdatesRemaining}
 * (set to 12 at the end of doVisualSwap). Decrements per call.
 *
 * <p>Purpose: diagnose why the 2 frames after swap render as a solid
 * sky-color background with zero terrain. Hypothesis is that
 * sectionOcclusionGraph takes several update() passes to propagate for
 * the new camera direction (which is often ~180° from the portal-view
 * direction that last populated it). This log will confirm.
 *
 * <p>Safe to leave in; zero cost when not armed.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererDiagMixin {

    @Inject(method = "update",
        at = @At("HEAD"))
    private void seamlessportals$diagBefore(Camera camera, CallbackInfo ci) {
        if (SeamlessClientTeleport.diagLogUpdatesRemaining <= 0) return;
        LevelRendererAccessorMixin self = (LevelRendererAccessorMixin) (Object) this;
        int before = self.seamlessportals$getVisibleSections().size();
        // Tag is the remaining-count, counting down from 12 (so first post-swap
        // frame is #12, then 11, etc.)
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS DIAG update#{}] HEAD: visibleSections={}",
            SeamlessClientTeleport.diagLogUpdatesRemaining,
            before);
    }

    @Inject(method = "update",
        at = @At("RETURN"))
    private void seamlessportals$diagAfter(Camera camera, CallbackInfo ci) {
        if (SeamlessClientTeleport.diagLogUpdatesRemaining <= 0) return;
        LevelRendererAccessorMixin self = (LevelRendererAccessorMixin) (Object) this;
        int after = self.seamlessportals$getVisibleSections().size();
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS DIAG update#{}] RETURN: visibleSections={}",
            SeamlessClientTeleport.diagLogUpdatesRemaining,
            after);
        SeamlessClientTeleport.diagLogUpdatesRemaining--;
    }
}
