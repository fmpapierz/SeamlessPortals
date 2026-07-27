package qouteall.imm_ptl.core.compat.mixin.iris;

import com.warwa.seamlessportals.render.IrisCompositeCensus;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * IS5-CEN (post-write sample) — reads the guarded pass's camera pair AFTER any IS5-MB correction and
 * BEFORE the draw. Log-only, default OFF ({@code -Dseamlessportals.compositeCensus}).
 *
 * <p><b>Why a second sample exists.</b> The census's primary read is at {@code Program.use()} TAIL,
 * bytecode offset 422 of {@code renderAll}. {@link
 * qouteall.imm_ptl.core.compat.iris_compatibility.IrisDestPrevCamera} writes at that same offset, from
 * the caller side ({@code INVOKE Program.use()V}, {@code shift = AFTER}). A census that samples only
 * there therefore reports <b>iris's</b> values and is structurally incapable of confirming that a
 * correction landed — it would print the uncorrected 511-block offset even on a perfectly working fix,
 * and that would be read as the fix having failed. This hook closes that gap: the pair printed here is
 * the pair the fragment shader actually executes with.
 *
 * <p><b>Why {@code _drawElements} and why no pass lookup.</b> {@code renderAll} runs
 * 419 {@code Program.use} → 422 (write seam) → 455 {@code _drawElements} → 458
 * {@code BlendModeOverride.restore} → 464 loop increment, straight-line with no branch (javap, iris
 * 1.11.2). Injecting BEFORE the {@code _drawElements} call therefore lands with the same program still
 * bound as at the primary sample, so the census pairs this reading with the row it just created rather
 * than re-resolving the pass — one fewer reflective walk on the render path, and no {@code @Local}
 * index to mis-resolve. The restore at 458 undoes the write immediately after, which is precisely why
 * the sample has to be here and not at the loop tail.
 *
 * <p>Behaviour-neutral: {@code @Inject}, never {@code cancellable}, no {@code @Shadow}, no
 * {@code @Local}, no state written back into iris. {@code require = 0} and an "Iris" simple name for the
 * same reasons as its sibling mixins.
 */
@Pseudo
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class MixinIrisCompositeRenderer_CensusPostWrite {

    @Inject(
        method = "renderAll",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_drawElements(IIIJ)V"
        ),
        remap = false,
        require = 0
    )
    private void seamlessportals$censusPostWrite(CallbackInfo ci) {
        IrisCompositeCensus.onPreDraw();
    }
}
