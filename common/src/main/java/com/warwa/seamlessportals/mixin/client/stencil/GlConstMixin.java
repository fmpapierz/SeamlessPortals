package com.warwa.seamlessportals.mixin.client.stencil;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.textures.TextureFormat;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Changes the depth texture format from DEPTH32F to DEPTH24_STENCIL8.
 * This gives us 8 stencil bits in every depth texture, which is required
 * for stencil-based portal rendering.
 *
 * GL_DEPTH_COMPONENT32F = 33191
 * GL_DEPTH24_STENCIL8 = 35056
 * GL_DEPTH_COMPONENT = 6402
 * GL_DEPTH_STENCIL = 34041
 * GL_UNSIGNED_INT_24_8 = 34042
 * GL_FLOAT = 5126
 */
@Mixin(GlConst.class)
public abstract class GlConstMixin {

    @Unique
    private static boolean seamlessportals$logged = false;

    /**
     * Change DEPTH32 internal format from GL_DEPTH_COMPONENT32F to GL_DEPTH24_STENCIL8
     */
    @Inject(method = "toGlInternalId", at = @At("HEAD"), cancellable = true)
    private static void seamlessportals$changeDepthFormat(TextureFormat format, CallbackInfoReturnable<Integer> cir) {
        if (format == TextureFormat.DEPTH32) {
            cir.setReturnValue(35056); // GL_DEPTH24_STENCIL8
            if (!seamlessportals$logged) {
                SeamlessPortalsConstants.LOGGER.info("[SEAMLESS STENCIL] Changed depth format: DEPTH32F -> DEPTH24_STENCIL8");
                seamlessportals$logged = true;
            }
        }
    }

    /**
     * Change DEPTH32 external format from GL_DEPTH_COMPONENT to GL_DEPTH_STENCIL
     */
    @Inject(method = "toGlExternalId", at = @At("HEAD"), cancellable = true)
    private static void seamlessportals$changeDepthExternalFormat(TextureFormat format, CallbackInfoReturnable<Integer> cir) {
        if (format == TextureFormat.DEPTH32) {
            cir.setReturnValue(34041); // GL_DEPTH_STENCIL
        }
    }

    /**
     * Change DEPTH32 type from GL_FLOAT to GL_UNSIGNED_INT_24_8
     */
    @Inject(method = "toGlType", at = @At("HEAD"), cancellable = true)
    private static void seamlessportals$changeDepthType(TextureFormat format, CallbackInfoReturnable<Integer> cir) {
        if (format == TextureFormat.DEPTH32) {
            cir.setReturnValue(34042); // GL_UNSIGNED_INT_24_8
        }
    }
}
