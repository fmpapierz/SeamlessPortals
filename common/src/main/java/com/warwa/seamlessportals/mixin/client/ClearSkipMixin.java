package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.textures.GpuTexture;
import com.warwa.seamlessportals.render.PortalContextSwitch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the framebuffer clear during portal context-switch rendering.
 *
 * MC 26.1.2's LevelRenderer.renderLevel() unconditionally adds a clear pass
 * that calls clearColorAndDepthTextures() with scissors disabled. In OpenGL,
 * glClear() does NOT respect stencil test. Without this mixin, the nested
 * renderLevel() call for the destination world would destroy the main world's
 * pixels outside the stencil mask.
 *
 * IP (older MC) didn't need this because pre-framegraph MC didn't have a
 * dedicated clear pass inside renderLevel(). This mixin compensates for
 * MC 26.1.2's framegraph architecture.
 *
 * Target: GlCommandEncoder.clearColorAndDepthTextures(GpuTexture, int, GpuTexture, double)
 *   — the full-screen clear variant (line 114 in GlCommandEncoder.java)
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public class ClearSkipMixin {

    /**
     * Skip the clear when rendering the destination world through a portal.
     * PortalContextSwitch.isRenderingPortal is set true during the nested
     * renderLevel() call and reset to false immediately after.
     */
    @Inject(
        method = "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;ILcom/mojang/blaze3d/textures/GpuTexture;D)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void seamlessportals$skipClearDuringPortalRender(
            GpuTexture colorTexture, int clearColor,
            GpuTexture depthTexture, double clearDepth,
            CallbackInfo ci) {
        if (PortalContextSwitch.isRenderingPortal) {
            ci.cancel();
        }
    }
}
