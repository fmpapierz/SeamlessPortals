package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for GameRenderer — IP context switch fields.
 *
 * IP saves/swaps/restores:
 *   - fogRenderer (private final)
 *   - lightTexture / setLightmap (lightmap swap per dimension)
 *   - mainCamera / setCamera (camera swap per dimension)
 *   - doRenderHand / setRenderHand (disable hand during portal render)
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessorMixin {

    // fogRenderer — private final FogRenderer
    @Accessor("fogRenderer")
    FogRenderer seamlessportals$getFogRenderer();

    // lightmap — private final Lightmap. IP: save/swap/restore per dimension.
    @Accessor("lightmap")
    Lightmap seamlessportals$getLightmap();

    @Accessor("lightmap")
    @Mutable
    void seamlessportals$setLightmap(Lightmap lightmap);

    // mainCamera — private final Camera. IP: save/swap/restore per render.
    @Accessor("mainCamera")
    Camera seamlessportals$getMainCamera();

    @Accessor("mainCamera")
    @Mutable
    void seamlessportals$setMainCamera(Camera camera);
}
