package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for GameRenderer private fields.
 *
 * Target fields (verified from GameRenderer.java):
 *   private final FogRenderer fogRenderer — line 132
 *   private final ProjectionMatrixBuffer levelProjectionMatrixBuffer — line 142
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessorMixin {

    @Accessor("fogRenderer")
    FogRenderer seamlessportals$getFogRenderer();

    @Accessor("levelProjectionMatrixBuffer")
    ProjectionMatrixBuffer seamlessportals$getLevelProjectionMatrixBuffer();
}
