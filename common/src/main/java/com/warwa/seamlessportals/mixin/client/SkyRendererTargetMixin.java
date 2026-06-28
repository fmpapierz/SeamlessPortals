package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * THE CURTAIN FIX.
 *
 * <p>Vanilla {@link SkyRenderer} CAPTURES the {@code RenderTarget} it is
 * constructed with — {@code LevelRenderer.addSkyPass} does
 * {@code new SkyRenderer(.., this.gameRenderer.mainRenderTarget())} — and from
 * then on renders the sky disc / sun / moon / stars straight into THAT captured
 * target ({@code renderSkyDisc}: {@code this.renderTarget.getColorTextureView()}).
 *
 * <p>This mod reuses one {@link net.minecraft.client.renderer.LevelRenderer}
 * instance for BOTH the portal-view destination render (where
 * {@code mc.gameRenderer.mainRenderTarget} is temporarily swapped to the mod's
 * {@code secondaryFbo}) AND the normal main-world render (the real window
 * target). So a {@code SkyRenderer} first created during a destination render
 * captures the SECONDARY FBO; when that same renderer later drives the MAIN
 * overworld render, its sky pass writes the overworld sky disc INTO the secondary
 * FBO, corrupting the destination (nether) FBO's above-horizon region with
 * overworld sky.
 *
 * <p>That is the "curtain that follows the eye level": proven by glReadPixels —
 * the secondary FBO's color texture (same GL id) is all-nether right after the
 * dest render (DIAG-FBO) but, at composite time (DIAG-FBO2), its UPPER bands are
 * full-screen-width overworld-sky blue while the lower bands stay nether — exactly
 * the overworld sky/terrain horizon. It only appears once a renderer has been used
 * for both dimensions ("this wasn't always here") and only for an overworld source
 * (a nether source has no sky disc, {@code skybox == NONE}).
 *
 * <p>Fix: every read of {@code SkyRenderer.renderTarget} in the rendering methods
 * returns the CURRENT {@code mc.gameRenderer.mainRenderTarget()} instead of the
 * stale captured one. During a dest render that is the secondary FBO (correct —
 * the dest sky belongs in the FBO); during the main render it is the real window
 * target (correct — fixes the leak). Vanilla single-target rendering is unaffected
 * (current == captured).
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererTargetMixin {

    @Shadow @Final private RenderTarget renderTarget;

    @Redirect(
        method = {
            "renderSkyDisc", "renderDarkDisc", "renderSun", "renderMoon",
            "renderStars", "renderSunriseAndSunset", "renderEndSky", "renderEndFlash"
        },
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;",
            opcode = Opcodes.GETFIELD
        )
    )
    private RenderTarget seamlessportals$useCurrentMainTarget(SkyRenderer instance) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget current = mc.gameRenderer != null ? mc.gameRenderer.mainRenderTarget() : null;
        return current != null ? current : this.renderTarget;
    }
}
