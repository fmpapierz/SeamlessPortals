package com.warwa.seamlessportals.mixin.client.stencil;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.mojang.blaze3d.opengl.GlBackend;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds stencil buffer support to the GLFW window.
 * Vanilla does NOT request stencil bits. We add GLFW_STENCIL_BITS = 8
 * so the default framebuffer has an 8-bit stencil buffer.
 *
 * This is REQUIRED for the stencil-based portal rendering approach
 * used by Immersive Portals.
 */
@Mixin(GlBackend.class)
public abstract class GlBackendMixin {

    @Inject(method = "setWindowHints", at = @At("TAIL"))
    private void seamlessportals$addStencilBits(CallbackInfo ci) {
        // GLFW_STENCIL_BITS = 0x00021008 = 135176
        // Request 8 stencil bits for the window framebuffer
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        SeamlessPortalsConstants.LOGGER.info("[SEAMLESS STENCIL] Requested 8 stencil bits from GLFW");
    }
}
