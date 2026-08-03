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
@Mixin(targets = "com/mojang/blaze3d/opengl/GlCommandEncoder")
public abstract class MixinSodiumFullbrightProbe_GlCommandEncoder {

    @Inject(
        method = "trySetup(Lcom/mojang/blaze3d/opengl/GlRenderPass;Ljava/util/Collection;)Z",
        at = @At("RETURN"),
        require = 0
    )
    private void seamlessportals$fullbrightProbeTrySetup(CallbackInfoReturnable<Boolean> cir) {
        IrisFullbrightProbe.onDrawSetup(Boolean.TRUE.equals(cir.getReturnValue()));
    }
}
