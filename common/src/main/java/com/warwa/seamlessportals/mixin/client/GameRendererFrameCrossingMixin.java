package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.client.SeamlessClientTeleport;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame camera-crossing check at {@code GameRenderer.update} HEAD — BEFORE
 * {@code mainCamera.update(deltaTracker)} positions this frame's camera.
 *
 * <p>The 26.2 frame pipeline (Minecraft.renderFrame): {@code gameRenderer.update} (:1290,
 * positions the camera) → {@code gameRenderer.extract} (:1295, extracts camera + level state)
 * → {@code gameRenderer.render} (:1302, draws). Two prior placements were proven too late by
 * [SEAMLESS XTRACE] (the crossing frame rendered the DEST level with the STALE source-position
 * camera → a fog-coloured flash): renderLevel HEAD (after extract) and extract HEAD (after
 * update). Only HERE does the swap (level + renderer + player setPos) land before the frame's
 * camera exists, so the crossing frame renders the destination from the destination. This is
 * IP's placement translated to 26.2 (IP injects manageTeleportation(false) at render HEAD,
 * "far before rendering" — the first per-frame GameRenderer entry point).
 *
 * <p>No {@code require = 0}: if this target ever changes, fail loudly at load rather than
 * silently reintroducing the flash.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFrameCrossingMixin {

    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void seamlessportals$perFrameCrossingCheck(DeltaTracker deltaTracker, CallbackInfo ci) {
        SeamlessClientTeleport.checkCameraCrossingPerFrame();
    }
}
