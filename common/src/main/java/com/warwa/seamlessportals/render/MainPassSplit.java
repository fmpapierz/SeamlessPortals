package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import net.minecraft.client.Minecraft;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * 26.3 SUBSTRATE — keeps the mod's two main-pass timing slots PASS-FREE, exactly as they were on 26.2.
 *
 * <p><b>What changed.</b> On 26.2 every step of the main-pass lambda opened and closed its OWN render pass
 * ({@code renderGroup}, {@code executeSolid/Translucent/Outline}, mc262-ref LevelRenderer.java:408-440), so when
 * Fabric's {@code BEFORE/AFTER_TRANSLUCENT_TERRAIN} (and NeoForge's {@code AfterTranslucentBlocks}) fired, NO pass
 * was open and the portal driver was free to open passes, clear, copy and upload. On 26.3 vanilla holds ONE "Main"
 * pass open across opaque terrain, solid features, translucent features, translucent terrain, particles, clouds,
 * weather and the world border (mc263-ref LevelRenderer.java main-pass lambda -> executeSolid / executeClassicTransparency,
 * every draw taking that pass as an argument), both loaders fire those events INSIDE it (Fabric API 0.161
 * LevelRendererMixin wraps the {@code renderGroup(TRANSLUCENT, renderPass, ..)} call; NeoForge posts
 * {@code AfterTranslucentBlocks(.., renderPass)} right after it), and the renderpearl front end refuses almost
 * everything while a pass is open — FrontendCommandEncoder.java:99-102 "Close the existing render pass before creating
 * a new one!", :231-343 the same for clears, buffer writes and copies. The portal driver does all of those.
 *
 * <p><b>The port.</b> The ORDER of the two slots relative to every draw is unchanged between the versions; only the
 * pass lifetime differs. So the pass is split AT the two slots: close it, run the handlers with no pass open (the
 * 26.2 contract), reopen an identical pass — same attachments, NO clears, default uniforms re-bound — and hand that
 * to the rest of vanilla's method. Closing and reopening a pass on the same attachments without a clear is
 * state-neutral on the GL backend (an FBO unbind/rebind, GlCommandEncoder.submitRenderPass / createRenderPass).
 * Iris 1.11.6 does the same thing to the same pass with the same descriptor (javap MixinLevelRenderer
 * iris$beginTranslucents: {@code renderPass.close()} ... {@code createRenderPass(.., mainRenderTarget().getColorTextureView(),
 * Optional.empty(), mainRenderTarget().getDepthTextureView(), OptionalDouble.empty())}), which is the precedent this
 * mirrors. {@link RenderPass#close()} is idempotent (FrontendRenderPass.java:496-505), so vanilla's own
 * try-with-resources close of the pass it opened stays harmless.
 *
 * <p>Wired by {@code com.warwa.seamlessportals.mixin.client.LevelRendererMainPassSplitMixin} (both loaders) and, on
 * NeoForge, by the three {@code AfterTranslucentBlocks} listeners, which {@link #suspend} the event's pass first.
 */
public final class MainPassSplit {

    private MainPassSplit() {}

    /** Close the pass standing in for vanilla's "Main" pass. Idempotent; a pass that is already closed is left alone. */
    public static void suspend(RenderPass pass) {
        if (pass != null) {
            pass.close();
        }
    }

    /** True while ANY render pass is open on the device's encoder (the front end's own single-pass latch). */
    public static boolean isPassOpen() {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        return encoder instanceof FrontendCommandEncoder frontend && frontend.isInRenderPass();
    }

    /**
     * Reopen vanilla's main pass: the current main render target's colour + depth, no clear of either, default
     * uniforms bound — the descriptor of the pass vanilla opened (mc263-ref LevelRenderer.java main-pass lambda:
     * {@code createRenderPass(() -> .. "Main", mainTarget.getColorTextureView(), Optional.empty(),
     * mainTarget.getDepthTextureView(), OptionalDouble.empty())} then {@code RenderSystem.bindDefaultUniforms}).
     */
    public static RenderPass open() {
        RenderTarget mainRenderTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "Main",
            mainRenderTarget.getColorTextureView(), Optional.empty(),
            mainRenderTarget.getDepthTextureView(), OptionalDouble.empty());
        RenderSystem.bindDefaultUniforms(renderPass);
        return renderPass;
    }
}
