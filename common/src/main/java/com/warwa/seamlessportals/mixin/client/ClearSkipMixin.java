package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Previously skipped framebuffer clears during portal rendering to protect
 * the main world's pixels. This was WRONG: since PortalContextSwitch swaps
 * mc.mainRenderTarget to the secondary FBO before calling renderLevel(),
 * the clear pass only targets the SECONDARY FBO (which we WANT cleared).
 *
 * Skipping the clear left stale depth values in the secondary FBO's depth
 * buffer. With depth values at 0.0 (near plane), all terrain fragments
 * failed LEQUAL depth test → invisible terrain, only sky visible.
 *
 * FIX: Do nothing. The clear proceeds normally on the secondary FBO.
 * The main FBO is never touched during portal renderLevel() because
 * mc.mainRenderTarget points elsewhere.
 *
 * This mixin is kept as a no-op placeholder to document the fix and
 * prevent re-introduction of the bug.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public class ClearSkipMixin {
    // Intentionally empty. The previous @Inject that cancelled
    // clearColorAndDepthTextures during portal rendering has been
    // removed because it was the root cause of invisible terrain.
}
