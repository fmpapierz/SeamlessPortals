package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;

// S12-A Iris compile-shell. IP's body drove raw-GL FBO-id blits (glBindFramebuffer(from.frameBufferId) +
// glBlitFramebuffer) and glCopyImageSubData on GL texture ids (from.getDepthTextureId/getColorTextureId) —
// ALL GONE on 26.2's GpuTexture/GpuTextureView abstraction (render-sub G8; render-core G12). Consumed ONLY by
// IrisCompatibilityPortalRenderer, which never loads on 26.2 (no Iris build; IrisInterface.isIrisPresent()
// = false forever). Re-expressed onto the 26.2 analogs that exist — RenderTarget.copyDepthFrom (the vanilla
// depth copy, RenderTarget.java:65) for depth, blitAndBlendToTexture (the vanilla FBO->texture color blit,
// RenderTarget.java:97) for color; the raw-GL stencil copy has no 26.2 core-profile analog and is the
// documented S18/never-run Iris gap. Held/inert until S13.
public class IPIrisHelper {

    public static void copyDepthStencil(
        RenderTarget from, RenderTarget to,
        boolean copyDepth, boolean copyStencil
    ) {
        // 26.2: RenderTarget has no frameBufferId / unbindWrite / glBlitFramebuffer — copyDepthFrom is the
        // vanilla depth copy. The stencil-only copy has no 26.2 analog (stencil is absent from the GPU
        // abstraction, render-sub G8) — documented S18/never-run Iris gap.
        if (copyDepth) {
            to.copyDepthFrom(from);
        }
    }

    public static void newCopyDepthStencil(
        RenderTarget from, RenderTarget to
    ) {
        // 26.2: glCopyImageSubData on the GL depth-texture id is GONE -> RenderTarget.copyDepthFrom (depth copy).
        to.copyDepthFrom(from);
    }

    public static void copyColor(
        RenderTarget from, RenderTarget to
    ) {
        // 26.2: glCopyImageSubData on the GL color-texture id is GONE -> blitAndBlendToTexture (vanilla
        // FBO->texture color blit, RenderTarget.java:97). Requires non-null color+depth views on the target.
        if (from.getColorTextureView() != null
            && to.getColorTextureView() != null
            && to.getDepthTextureView() != null) {
            from.blitAndBlendToTexture(to.getColorTextureView(), to.getDepthTextureView());
        }
    }

}
