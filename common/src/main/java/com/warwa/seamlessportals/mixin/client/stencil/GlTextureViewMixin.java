package com.warwa.seamlessportals.mixin.client.stencil;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes stencil support for command encoder render passes.
 *
 * MC 26.1.2's GlTextureView.createFbo() creates FBOs with depth attached as
 * GL_DEPTH_ATTACHMENT (36096). Since our GlConstMixin converts ALL depth textures
 * to DEPTH24_STENCIL8 format, the attachment must be GL_DEPTH_STENCIL_ATTACHMENT
 * (33306) for stencil testing to work.
 *
 * Without this fix, render passes created by the command encoder (e.g., for FBO
 * compositing) have stencil-capable textures but can't actually test stencil
 * because the attachment type is wrong. Our RenderTargetMixin fixes the game's
 * own FBO but doesn't cover command encoder FBOs.
 *
 * This mixin makes the command encoder consistent with GlConstMixin's depth format.
 *
 * SEAMLESS-26.2-TODO: this mixin is RETAINED FOR HISTORY ONLY and is no longer
 * registered in {@code seamlessportals-common.mixins.json}. Its target
 * {@code GlTextureView.createFbo(DirectStateAccess, int)} was REMOVED in 26.2 —
 * FBO creation was unified into {@code FrameBufferCache.createFbo(CacheKey,
 * DirectStateAccess, List, FrameBufferAttachment)}. In 26.2 the command-encoder
 * render-pass path that this mixin used to cover ALSO routes through that single
 * method: {@code GlCommandEncoder.createRenderPass(...)} obtains its FBO via
 * {@code frameBufferCache().getFbo(...)} → {@code FrameBufferCache.createFbo}
 * (GlCommandEncoder:145-149), and {@code clearColorAndDepthTextures(...)} does
 * the same (GlCommandEncoder:209-211). The only remaining non-cache depth
 * attach is the transient {@code clearDepthTexture(...)} depth clear
 * (GlCommandEncoder:242), which detaches immediately and needs no stencil.
 * Therefore the depth→depth-stencil reattach is now fully provided for BOTH the
 * game FBO and the command-encoder render-pass FBO by {@link
 * com.warwa.seamlessportals.mixin.client.stencil.RenderTargetMixin} (re-pointed
 * at {@code FrameBufferCache.createFbo}). No functionality is dropped.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlTextureView")
public class GlTextureViewMixin {

    /**
     * After createFbo() creates a new FBO, re-attach the depth texture as
     * DEPTH_STENCIL_ATTACHMENT so stencil test works in render passes.
     */
    /**
     * After createFbo() creates a new FBO, re-attach the depth texture as
     * DEPTH_STENCIL_ATTACHMENT so stencil test works in render passes.
     *
     * createFbo signature: private int createFbo(DirectStateAccess dsa, int depthid)
     */
    @Inject(method = "createFbo(Lcom/mojang/blaze3d/opengl/DirectStateAccess;I)I",
            at = @At("RETURN"))
    private void seamlessportals$fixDepthStencilAttachment(
            DirectStateAccess dsa, int depthId, CallbackInfoReturnable<Integer> cir) {
        if (depthId != 0) {
            int fbo = cir.getReturnValue();
            // Re-attach depth texture as DEPTH_STENCIL_ATTACHMENT (0x821A = 33306)
            // instead of DEPTH_ATTACHMENT (0x8D00 = 36096) set by bindFrameBufferTextures().
            // Safe because GlConstMixin already made all depth textures DEPTH24_STENCIL8.
            ARBDirectStateAccess.glNamedFramebufferTexture(fbo, 33306, depthId, 0);
        }
    }
}
