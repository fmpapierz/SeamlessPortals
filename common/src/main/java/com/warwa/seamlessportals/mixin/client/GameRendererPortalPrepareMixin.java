package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.StencilPortalRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 1 of the two-phase portal render: render the destination world into
 * the secondary FBO BEFORE the main frame's level render begins.
 *
 * <p><b>Why HEAD of {@code renderLevel}.</b> 26.2 made world rendering deferred
 * via a {@code FrameGraphBuilder}. {@code GameRenderer.renderLevel(DeltaTracker)}
 * (GameRenderer.java:525) sets up matrices/fog and then, at line ~563, calls
 * {@code minecraft.levelRenderer.render(...)} which BUILDS and EXECUTES a
 * self-contained framegraph for the main world. Fabric's
 * {@code LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN} fires from INSIDE that
 * framegraph's main pass ({@code LevelRenderer.lambda$addMainPass$0}, the
 * translucent {@code ChunkSectionsToRender.renderGroup} wrap — confirmed in
 * fabric-rendering-v1 25.1.6). Issuing the heavy nested
 * {@code destRenderer.render(...)} (another full deferred framegraph) from that
 * event therefore re-enters {@code render} WHILE the main framegraph is in
 * flight, disrupting its "main" target import → the overworld blanks to the
 * destination world's empty-terrain sky.
 *
 * <p>At HEAD of {@code renderLevel} we are AFTER {@code GameRenderer.extract(...)}
 * (so {@code gameRenderState().levelRenderState.cameraRenderState} — including the
 * projection matrix the portal-view render reads — is fully populated) but BEFORE
 * the main {@code levelRenderer.render(...)} framegraph is built/executed. A
 * standalone {@code destRenderer.render(...)} issued here is NOT nested inside any
 * framegraph; it builds and executes its own framegraph to cleanly, targeting the
 * swapped-in secondary FBO (via {@code GameRendererAccessorMixin.seamlessportals$setMainRenderTarget}),
 * then fully restores state before returning. The main frame's framegraph then
 * builds and executes normally against the real screen target.
 *
 * <p>Phase 2 (the lightweight stencil mask + textured-quad composite of that FBO
 * onto the screen through the portal shape) still runs in
 * {@code AFTER_TRANSLUCENT_TERRAIN} via {@link StencilPortalRenderer#renderPortals()}.
 *
 * <p>Re-entry: when {@code destRenderer.render(...)} runs in phase 1, the
 * DESTINATION world's own framegraph also fires {@code AFTER_TRANSLUCENT_TERRAIN}.
 * {@link com.warwa.seamlessportals.render.PortalContextSwitch#isRenderingPortal}
 * is held {@code true} for the duration of the heavy render, so the phase-2
 * listener (and this prepare hook) short-circuit during it.
 *
 * <p>Target verified: {@code renderLevel(Lnet/minecraft/client/DeltaTracker;)V}
 * is the same method {@code MainProjectionBobMixin} / {@code GameRendererObliqueClipMixin}
 * inject into. HEAD runs before those {@code @Redirect}s (which sit deeper in the
 * method body), so ordering is unaffected.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererPortalPrepareMixin {

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void seamlessportals$prepareDestinationRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        StencilPortalRenderer.prepareDestinationRender();
    }
}
