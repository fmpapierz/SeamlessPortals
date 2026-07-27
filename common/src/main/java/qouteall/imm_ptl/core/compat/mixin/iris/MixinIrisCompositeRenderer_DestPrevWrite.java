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
 * IS5-MB (S1) — the WRITE seam for the per-chain previous-frame state correction. The feature is
 * <b>DEFAULT OFF</b> pending live proof; arm it with
 * {@code -Dseamlessportals.enableIrisDestPrevCamera} ({@code -Dseamlessportals.disableIrisDestPrevCamera}
 * always wins). See {@link IrisDestPrevCamera} for the measured defect — a 511-block camera offset
 * producing a 265.8 px blur span, plus a second matrix-driven driver worth 57–102 px — and the
 * mechanism.
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

    /**
     * MOVED (2026-07-26, round 4) from {@code INVOKE Program.use()V shift=AFTER} (offset 422) to here,
     * offset 447. <b>The write was being clobbered before the draw.</b> Measured: IS5-MB wrote
     * {@code prev=(66.700,75.620,0.254)} and the sample taken immediately before {@code _drawElements}
     * read {@code prev=(66.700,75.620,0.263)} — a value we never wrote. Between 422 and 455 sit
     * {@code CustomUniforms.push} (431) and the index-buffer bind (444), and the pack declares 29
     * custom uniforms, three of which read the camera pair. Rather than identify the exact culprit,
     * this moves the write PAST all of them: nothing at all executes between {@code _glBindBuffer} and
     * {@code _drawElements}, so the value written here is necessarily the value the shader draws with.
     *
     * <p>{@code GlStateManager._glBindBuffer(II)V} occurs <b>exactly once</b> in {@code renderAll}
     * (javap-counted), so no {@code ordinal} is needed — unlike {@code CustomUniforms.push}, which
     * occurs twice (the compute-side call at 178 and the graphics one at 431) and would have required a
     * drift-fragile ordinal. {@code @Local(ordinal = 0) int i} still resolves: the LVT entry for slot 4
     * spans 76..470, which covers 447.
     */
    @Inject(
        method = "renderAll",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glBindBuffer(II)V",
            shift = At.Shift.AFTER
        ),
        remap = false,
        require = 0
    )
    private void seamlessportals$writeDestPrev(CallbackInfo ci, @Local(ordinal = 0) int i) {
        IrisDestPrevCamera.onPassProgramBound(this, i);
    }
}
