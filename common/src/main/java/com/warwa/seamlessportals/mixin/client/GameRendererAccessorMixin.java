package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

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

    /**
     * The {@code globalSettingsUniform} private field has no public getter in
     * 26.2 (only {@code update(...)}/{@code close()} are called internally).
     * Portal rendering re-drives {@code globalSettingsUniform.update(...)} with
     * the destination camera/time inside the switched-world render, so it needs
     * to reach the instance directly.
     */
    @Accessor("globalSettingsUniform")
    GlobalSettingsUniform seamlessportals$getGlobalSettingsUniform();

    @Accessor("renderBuffers")
    RenderBuffers seamlessportals$getRenderBuffers();

    @Accessor("renderBuffers")
    @Mutable
    void seamlessportals$setRenderBuffers(RenderBuffers renderBuffers);

    /**
     * 26.2: {@code mainRenderTarget} moved off {@code Minecraft} onto
     * {@code GameRenderer} (field {@code GameRenderer.mainRenderTarget} at
     * GameRenderer:104; public getter {@code GameRenderer.mainRenderTarget()}).
     * Portal FBO rendering swaps it to the secondary FBO before the
     * switched-world render and restores it after (see
     * {@link com.warwa.seamlessportals.render.PortalContextSwitch#withSwitchedWorld}).
     * The getter side uses the public {@code mc.gameRenderer.mainRenderTarget()};
     * only the setter needs an accessor.
     */
    @Accessor("mainRenderTarget")
    @Mutable
    void seamlessportals$setMainRenderTarget(RenderTarget target);

    /**
     * S18.5: vanilla's per-frame targeted-block-outline predicate (HUD hidden, spectator
     * menu-provider, adventure-mode can-break/can-place checks — 26.2:GameRenderer.java:501-523).
     * The dest pass recomputes it per pass exactly as IP's nested renderLevel did; it reads
     * {@code minecraft.hitResult}, which the shell has already swapped to the REMOTE hit (and
     * nulled under shouldRenderHitResult) — so the dest-pass answer is remote-hit-correct.
     */
    @Invoker("shouldRenderBlockOutline")
    boolean seamlessportals$invokeShouldRenderBlockOutline();
}
