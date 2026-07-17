package com.warwa.seamlessportals.render;

/**
 * Stores the game's main FBO ID for stencil operations.
 *
 * The game's FBO has our DEPTH24_STENCIL8 attachment (via GlConst mixin)
 * with GL_DEPTH_STENCIL_ATTACHMENT (via RenderTargetMixin).
 *
 * Fabric render callbacks fire with FBO 0 bound (because GlCommandEncoder
 * finishRenderPass() unconditionally calls glBindFramebuffer(GL_FRAMEBUFFER, 0)).
 * We need this stored ID to manually bind the correct FBO before stencil ops.
 *
 * Set by: RenderTargetMixin.seamlessportals$fixStencilAttachment()
 * Used by: StencilPortalRenderer.renderSinglePortal()
 */
public class StencilState {
    /** The game's main FBO ID with DEPTH24_STENCIL8 stencil attachment.
     *
     * @deprecated S14.21: this last-writer-wins capture is UNSOUND as a "main FBO" identity — it
     * points at whatever depth FBO was created last and goes stale at resource-lifecycle events
     * (world teardown, GUI target churn), which produced the 51k/session GL_INVALID_OPERATION
     * flood and a broken per-frame stencil clear. The flag-ON path now resolves the LIVE main
     * FBO deterministically via {@code GlDevice.frameBufferCache().getFbo(...)}
     * (RendererUsingStencil.prepareRendering). Kept only for the flag-OFF block-era DIAG readers;
     * retire the field + the RenderTargetMixin store at S20. */
    @Deprecated
    public static int gameFboId = 0;

    /** The LAST FBO bound via glBindFramebuffer (captured by GlStateManagerMixin).
     *  After any RenderType.draw(), this is the FBO that was used for rendering.
     *  Updated on every glBindFramebuffer(GL_FRAMEBUFFER, fbo>0) call. */
    public static int lastBoundFbo = 0;
}
