package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Makes the main render target mutable for portal FBO rendering.
 *
 * Following IP's IEMinecraftClient.setFrameBuffer() pattern:
 * IP swaps the main render target to a secondary FBO before calling
 * renderLevel(), so the framegraph renders to the portal FBO instead
 * of the screen. Restored in finally block after rendering.
 *
 * SEAMLESS-26.2-TODO: the {@code mainRenderTarget} field moved off
 * {@code Minecraft} (where it was {@code private final RenderTarget} in
 * 26.1.2) onto {@code GameRenderer} in 26.2 (field
 * {@code GameRenderer.mainRenderTarget} at GameRenderer:104; public getter
 * {@code GameRenderer.mainRenderTarget()}). The mutable setter accessor was
 * therefore RELOCATED to {@link GameRendererAccessorMixin} (method
 * {@code seamlessportals$setMainRenderTarget}), and this mixin was removed
 * from {@code seamlessportals-common.mixins.json} because its
 * {@code @Mixin(Minecraft.class)} accessor can no longer resolve. This file
 * is retained only for history; it is NOT registered and does nothing. The
 * portal FBO swap functionality is fully preserved via
 * {@code GameRendererAccessorMixin}.
 */
@Mixin(Minecraft.class)
public interface MinecraftRenderTargetMixin {

    @Accessor("mainRenderTarget")
    @Mutable
    void seamlessportals$setMainRenderTarget(RenderTarget target);
}
