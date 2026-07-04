package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.client.SeamlessClientTeleport;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame camera-crossing check at {@code GameRenderer.extract} HEAD — BEFORE
 * {@code extractCamera} captures this frame's camera (GameRenderer.extract:~388) and before the
 * level extract. This is 26.2's equivalent of IP's placement ({@code MixinGameRenderer} injects
 * {@code manageTeleportation(false)} at {@code render} HEAD, "far before rendering").
 *
 * <p>Why HEAD-of-extract and not renderLevel HEAD: [SEAMLESS XTRACE] proved that swapping at
 * {@code renderLevel} HEAD produced one frame rendering the DEST level with the STALE pre-swap
 * camera (cam still at the source coords, pd=-39.9 in the trace) — the camera was extracted
 * before the swap ran → a fog-colored flash on 13/13 frame-detected crossings. Here the swap
 * (level + renderer + player setPos) lands before the frame's camera exists, so the crossing
 * frame renders the destination from the destination — seamless.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererExtractCrossingMixin {

    @Inject(method = "extract", at = @At("HEAD"), require = 0)
    private void seamlessportals$perFrameCrossingCheck(
            DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        SeamlessClientTeleport.checkCameraCrossingPerFrame();
    }
}
