package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.warwa.seamlessportals.render.IrisFullbrightProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * IS5-L in-portal-fullbright PROBE seam ({@code migration/FULLBRIGHT_HANDOFF.md} §5). SIBLING of
 * {@code MixinSodiumProbe_GlCommandEncoder} — same seam ({@code GlCommandEncoder.trySetup(GlRenderPass,
 * Collection)} at RETURN), LOG-ONLY, but forwards to {@link IrisFullbrightProbe} which runs the
 * main-vs-dest PER_FRAME lighting-uniform compare (the cross-pass COUNTER + {@code glGetUniformfv} of
 * {@code gbufferModelView}/celestial + the reflected {@code CapturedRenderingState} source). All the
 * logic — the enable lever ({@code -Dseamlessportals.fullbrightProbe}), the 1Hz latch, the pass
 * discriminator ({@code PortalRendering.isRendering()}), and the reflective iris reads — lives in the
 * probe; this mixin is a thin per-draw forwarder ({@code require = 0}, so a trySetup-signature drift
 * never fails the weave). The class name carries {@code Sodium} so the IPCompatMixinPlugin gate weaves
 * it only when Sodium is present (iris always bundles Sodium, so this covers every iris run).
 *
 * <p>Coexists with {@code MixinSodiumProbe_GlCommandEncoder}: two independent {@code @Inject}s into the
 * one target method is supported, and each short-circuits on its own lever (this on
 * {@code fullbrightProbe}, the sibling on {@code compatProbe}) — with neither property set, both are
 * inert.
 */
@Mixin(targets = "com/mojang/renderpearl/backend/opengl/GlCommandEncoder")
public abstract class MixinSodiumFullbrightProbe_GlCommandEncoder {

    // 26.3: trySetup(GlRenderPass, Collection)Z -> setupDraw(GlRenderPass)V — the same per-draw seam, now void (see the full
    // citation on com.warwa.seamlessportals.mixin.client.GlCommandEncoderClipMixin). 26.2's `false` return (no draw follows)
    // cannot occur any more, so the probe sees every setup as the successful one it always filtered for.
    @Inject(
        method = "setupDraw(Lcom/mojang/renderpearl/backend/opengl/GlRenderPass;)V",
        at = @At("RETURN"),
        require = 0
    )
    private void seamlessportals$fullbrightProbeTrySetup(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        IrisFullbrightProbe.onDrawSetup(true); // 26.3: setupDraw is void — every return is the "setup succeeded" case
    }
}
