package com.warwa.seamlessportals.mixin.client.stencil;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.warwa.seamlessportals.render.StencilState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the FBO ID whenever glBindFramebuffer is called.
 * This lets us know which FBO the game is actually using for rendering,
 * so we can bind the same one for stencil operations.
 *
 * Target method (verified from GlStateManager.java line 249):
 * public static void _glBindFramebuffer(int target, int framebuffer)
 *
 * When framebuffer > 0 and target is GL_FRAMEBUFFER (36160),
 * it's binding a real FBO for rendering. We store this as the last known game FBO.
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerMixin {

    // Target: public static void _glBindFramebuffer(int target, int framebuffer)
    @Inject(method = "_glBindFramebuffer", at = @At("HEAD"))
    private static void seamlessportals$captureFramebufferBind(int target, int framebuffer, CallbackInfo ci) {
        // 36160 = GL_FRAMEBUFFER (binds both read and draw)
        // Only capture real FBO binds (not FBO 0 which is the default)
        if (framebuffer > 0 && target == 36160) {
            StencilState.lastBoundFbo = framebuffer;
        }
    }
}
