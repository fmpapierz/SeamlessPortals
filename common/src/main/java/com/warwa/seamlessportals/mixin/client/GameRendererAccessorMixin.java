package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for GameRenderer private fields.
 *
 * Target fields (verified from GameRenderer bytecode in 26.1.2):
 *   private final FogRenderer fogRenderer
 *   private final ProjectionMatrixBuffer levelProjectionMatrixBuffer
 *   private final Camera mainCamera        — swapped during portal context switch
 *   private final Lightmap lightmap        — swapped to per-dim Lightmap during portal context switch
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessorMixin {

    @Accessor("fogRenderer")
    FogRenderer seamlessportals$getFogRenderer();

    @Accessor("levelProjectionMatrixBuffer")
    ProjectionMatrixBuffer seamlessportals$getLevelProjectionMatrixBuffer();

    @Accessor("mainCamera")
    Camera seamlessportals$getMainCamera();

    @Accessor("mainCamera")
    @Mutable
    void seamlessportals$setMainCamera(Camera camera);

    @Accessor("lightmap")
    Lightmap seamlessportals$getLightmap();

    @Accessor("lightmap")
    @Mutable
    void seamlessportals$setLightmap(Lightmap lightmap);
}
