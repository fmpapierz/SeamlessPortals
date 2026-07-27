package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisDestPrevCamera;

/**
 * IS5-MB (S1) — the WRITE seam for the per-chain previous-frame camera correction. <b>DEFAULT ON</b>;
 * A/B off with {@code -Dseamlessportals.disableIrisDestPrevCamera}. See {@link IrisDestPrevCamera} for
 * the measured defect (a 511-block camera offset producing a 265.8 px blur span) and the mechanism.
 *
 * <p><b>Why this exact target.</b> {@code Lnet/irisshaders/iris/gl/program/Program;use()V} occurs
 * <b>exactly once</b> in the whole of {@code CompositeRenderer} (bytecode offset 419 in
 * {@code renderAll}); the other {@code use()V} at offset 169 belongs to {@code ComputeProgram} and is a
 * different descriptor, so it can never be matched. That uniqueness is why <b>no {@code ordinal} is
 * needed</b> — an ordinal here would be drift-fragile against the compute-side sibling.
 *
 * <p><b>Why {@code shift = AFTER}.</b> {@code Program.use()} itself does
 * {@code _glUseProgram(getGlId())} then {@code uniforms.update()} / {@code samplers.update()} /
 * {@code images.update()}. Landing AFTER (offset 422) therefore guarantees two things at once: the pass
 * program is CURRENT (so plain bound-program {@code glUniform*} works — core GL 2.0, no ARB extension,
 * no capability gate, no re-bind) and iris has ALREADY finished every uniform upload for it, so our
 * write cannot be clobbered by {@code update()}.
 *
 * <p>Paired 1:1 with {@link MixinIrisCompositeRenderer_DestPrevRestore} — offsets 419 → 455
 * ({@code _drawElements}) → 458 ({@code BlendModeOverride.restore}) are straight-line with no branch, so
 * a write at S1 is always followed by its restore at S4 in the same loop iteration.
 *
 * <p>{@code @Local(ordinal = 0) int i} resolves to the loop counter: {@code LocalVariableTable} gives
 * {@code slot 4 = i : I, start 76, length 394}, which covers offset 422 (the next {@code i} at slot 4
 * starts at 483, out of range). This is the same resolution the shipped C3-BLOOM mixin already relies on
 * at offset 213 in this method.
 *
 * <p>Behaviour-neutral when idle: the handler's first statement tests the feature lever, and the pass is
 * then filtered by NAME, so a non-guarded composite pass costs one static read and one list check. With
 * the pack's Motion Blur OFF the guarded pass declares no previous-frame trio, every uniform location
 * comes back -1, and not one GL write is issued — inertness by construction rather than by a flag. {@code require = 0} because the
 * mixin config sets {@code defaultRequire = 1} and this target exists only when iris is present; the
 * 8-leg gametest suite runs iris-ABSENT, where {@code IPCompatMixinPlugin} declines the class outright.
 * Simple name contains "Iris" per that plugin's gating rule.
 */
@Pseudo
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class MixinIrisCompositeRenderer_DestPrevWrite {

    @Inject(
        method = "renderAll",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/program/Program;use()V",
            shift = At.Shift.AFTER
        ),
        remap = false,
        require = 0
    )
    private void seamlessportals$writeDestPrev(CallbackInfo ci, @Local(ordinal = 0) int i) {
        IrisDestPrevCamera.onPassProgramBound(this, i);
    }
}
