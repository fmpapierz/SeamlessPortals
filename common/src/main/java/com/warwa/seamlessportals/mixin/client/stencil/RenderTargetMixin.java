package com.warwa.seamlessportals.mixin.client.stencil;

import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * After GlTextureView creates an FBO, re-attach the depth texture as
 * GL_DEPTH_STENCIL_ATTACHMENT instead of GL_DEPTH_ATTACHMENT.
 *
 * CRITICAL: The FBOs used for actual rendering are created by
 * GlTextureView.createFbo(), NOT GlTexture.createFbo().
 * GlCommandEncoder.createRenderPass() calls GlTextureView.getFbo()
 * which calls GlTextureView.createFbo(). This is the ONLY code path
 * that creates FBOs used for rendering.
 *
 * Target method (verified from GlTextureView.java):
 *   private int createFbo(DirectStateAccess dsa, int depthid)
 */
@Mixin(GlTextureView.class)
public abstract class RenderTargetMixin {

    @Inject(method = "createFbo", at = @At("RETURN"))
    private void seamlessportals$fixStencilAttachment(DirectStateAccess dsa, int depthId, CallbackInfoReturnable<Integer> cir) {
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
                "[SEAMLESS STENCIL] GlTextureView FBO {} NOT complete! Status: {}. Reverting.", fbo, status
            );
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_TEXTURE_2D, 0, 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_TEXTURE_2D, depthId, 0);
        } else {
            com.warwa.seamlessportals.render.StencilState.gameFboId = fbo;
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS STENCIL] GlTextureView FBO {} reattached as DEPTH_STENCIL (depthTex={})", fbo, depthId
            );
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, oldFbo);
    }
}
