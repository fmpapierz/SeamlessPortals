package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.pipeline.CompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisDestPrevCamera;

/**
 * IS5-MB (S4) — the RESTORE seam, paired with {@link MixinIrisCompositeRenderer_DestPrevWrite}.
 *
 * <p><b>Why a restore is MANDATORY, not housekeeping.</b> Iris skips the GL upload when its Java-side
 * value is unchanged — {@code Vector3Uniform.updateValue} and {@code MatrixUniform.updateValue} both
 * early-return on {@code cachedValue.equals(newValue)}. With a stationary player
 * {@code previousCameraPosition} is byte-stable frame to frame, so iris would NEVER overwrite the value
 * we wrote for the dest pass — and the MAIN view would inherit dest camera values on the following
 * frame. Writing then restoring keeps iris's cache truthful without reflecting into its uniform lists.
 *
 * <p><b>Why this exact target.</b>
 * {@code Lnet/irisshaders/iris/gl/blending/BlendModeOverride;restore()V} occurs <b>exactly once</b> in
 * the whole class (offset 458 in {@code renderAll}), immediately after
 * {@code GlStateManager._drawElements} at 455 — so the guarded pass has finished drawing with our values
 * and nothing else has been bound yet. No {@code ordinal} needed, and deliberately <b>no
 * {@code @Local}</b>: the pending slot identifies the pass, which keeps this injection off the
 * MixinExtras {@code @Local} apply-crash corner that the C3-BLOOM class documents.
 *
 * <p>{@code _drawElements} itself was rejected as a target: {@code GlStateManager} is a Mojang-owned
 * member, and inside a {@code remap = false} mixin its descriptor is taken verbatim and would not
 * resolve against remapped production names.
 *
 * <p>Behaviour-neutral when idle: the handler's first statement is a static null check on the pending
 * slot. {@code require = 0}; simple name contains "Iris" per the {@code IPCompatMixinPlugin} gating rule.
 */
@Pseudo
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class MixinIrisCompositeRenderer_DestPrevRestore {

    @Inject(
        method = "renderAll",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/blending/BlendModeOverride;restore()V"
        ),
        remap = false,
        require = 0
    )
    private void seamlessportals$restoreDestPrev(CallbackInfo ci) {
        IrisDestPrevCamera.onPassDrawn();
    }
}
