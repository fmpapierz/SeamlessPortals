package com.warwa.seamlessportals.mixin.client.stencil;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.FrameBufferAttachment;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * After an FBO with a depth attachment is created, re-attach the depth texture
 * as GL_DEPTH_STENCIL_ATTACHMENT instead of GL_DEPTH_ATTACHMENT so the stencil
 * buffer is usable for portal stencil masking.
 *
 * <p>26.2: FBO creation moved out of {@code GlTextureView.createFbo(dsa,
 * depthId)} into {@code FrameBufferCache.createFbo(CacheKey, DirectStateAccess,
 * List&lt;FrameBufferAttachment&gt; colorAttachments, FrameBufferAttachment
 * depthAttachment)}. {@code FrameBufferCache} is now the single code path that
 * allocates an FBO (via {@code dsa.createFrameBufferObject()}) and binds its
 * attachments (via {@code dsa.bindFrameBufferTextures(...)}); every render FBO,
 * including the ones {@code GlCommandEncoder.createRenderPass()} consumes,
 * routes through {@code FrameBufferCache.getFbo()} → {@code createFbo()}. The
 * depth texture's GL id is now obtained from the {@code depthAttachment}
 * parameter ({@link FrameBufferAttachment#glId()}) instead of the old raw
 * {@code depthId} int.
 *
 * Target method (verified from FrameBufferCache.java / javap):
 *   private int createFbo(FrameBufferCache.CacheKey, DirectStateAccess,
 *                         List&lt;FrameBufferAttachment&gt;, FrameBufferAttachment)
 */
@Mixin(FrameBufferCache.class)
public abstract class RenderTargetMixin {

    @Inject(method = "createFbo", at = @At("RETURN"))
    private void seamlessportals$fixStencilAttachment(
            FrameBufferCache.CacheKey key,
            DirectStateAccess dsa,
            List<FrameBufferAttachment> colorAttachments,
            FrameBufferAttachment depthAttachment,
            CallbackInfoReturnable<Integer> cir) {
        if (depthAttachment == null) return;
        int depthId = depthAttachment.glId();
        if (depthId == 0) return;

        int fbo = cir.getReturnValue();

        int oldFbo = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        // Detach from GL_DEPTH_ATTACHMENT and reattach as GL_DEPTH_STENCIL_ATTACHMENT
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_TEXTURE_2D, 0, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_TEXTURE_2D, depthId, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            SeamlessPortalsConstants.LOGGER.error(
                "[SEAMLESS STENCIL] FrameBufferCache FBO {} NOT complete! Status: {}. Reverting.", fbo, status
            );
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_TEXTURE_2D, 0, 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_TEXTURE_2D, depthId, 0);
        } else {
            com.warwa.seamlessportals.render.StencilState.gameFboId = fbo;
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, oldFbo);
    }
}
