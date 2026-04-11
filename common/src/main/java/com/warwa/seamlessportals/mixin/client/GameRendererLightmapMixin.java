package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.warwa.seamlessportals.render.PortalContextSwitch;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts GameRenderer.lightmap() to return the portal-specific lightmap
 * during portal terrain rendering.
 *
 * ChunkSectionsToRender.renderGroup() calls minecraft.gameRenderer.lightmap()
 * at line 50 to bind the lightmap as Sampler2. Without this mixin, the current
 * dimension's lightmap is used, causing red tint when viewing overworld from nether.
 *
 * IP solves this by swapping lightmaps per dimension during context switch.
 * We achieve the same by intercepting the getter during the renderGroup() call.
 *
 * Target method (verified from GameRenderer.java line 828):
 *   public GpuTextureView lightmap()
 */
@Mixin(GameRenderer.class)
public class GameRendererLightmapMixin {

    @Inject(method = "lightmap()Lcom/mojang/blaze3d/textures/GpuTextureView;",
            at = @At("HEAD"), cancellable = true)
    private void seamlessportals$overrideLightmap(CallbackInfoReturnable<GpuTextureView> cir) {
        GpuTextureView override = PortalContextSwitch.portalLightmapOverride;
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
