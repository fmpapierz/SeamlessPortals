package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for GameRenderer private fields.
 *
 * Needed for Phase 2 context-switch rendering to obtain fog data
 * for the destination dimension's render call.
 *
 * Target field (verified from GameRenderer.java):
 *   private final FogRenderer fogRenderer — line 132
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessorMixin {

    // GameRenderer.fogRenderer — private final FogRenderer, line 132
    @Accessor("fogRenderer")
    FogRenderer seamlessportals$getFogRenderer();
}
