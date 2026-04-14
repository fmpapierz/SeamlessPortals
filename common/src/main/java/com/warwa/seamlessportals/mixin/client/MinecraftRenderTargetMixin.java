package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Makes Minecraft.mainRenderTarget mutable for portal FBO rendering.
 *
 * Following IP's IEMinecraftClient.setFrameBuffer() pattern:
 * IP swaps the main render target to a secondary FBO before calling
 * renderLevel(), so the framegraph renders to the portal FBO instead
 * of the screen. Restored in finally block after rendering.
 *
 * Target field (verified from Minecraft.java line 326):
 *   private final RenderTarget mainRenderTarget;
 */
@Mixin(Minecraft.class)
public interface MinecraftRenderTargetMixin {

    @Accessor("mainRenderTarget")
    @Mutable
    void seamlessportals$setMainRenderTarget(RenderTarget target);
}
