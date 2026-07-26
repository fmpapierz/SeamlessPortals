package qouteall.imm_ptl.core.compat.mixin.iris;

import com.warwa.seamlessportals.render.IrisCompositeCensus;
import net.irisshaders.iris.gl.program.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * IS5-CEN (bind witness) — every {@code Program.use()} in the process. Log-only, default OFF
 * ({@code -Dseamlessportals.compositeCensus}). See {@link IrisCompositeCensus} for the measurement.
 *
 * <p><b>Why the census hooks HERE and not the composite loop.</b> The shipped IS5-MB seam identifies a
 * pass as {@code passes.get(i)} with {@code i} recovered as a {@code @Local} loop counter. That is a
 * blind spot of exactly the shape the open bug requires: if the local ever resolves to the wrong slot,
 * the pass name is wrong and a {@code composite4} bind is silently filed as something else. Keying on
 * the <b>program id</b> at the universal bind point removes the blind spot — this fires for every iris
 * program the driver is ever asked to bind, from any call path, composite loop or not.
 *
 * <p><b>Why TAIL.</b> {@code use()} is {@code memoryBarrier} → {@code _glUseProgram(getGlId())} →
 * {@code uniforms.update()} → {@code samplers.update()} → {@code images.update()} → {@code return}
 * (verified by javap on iris 1.11.2). TAIL therefore lands with the program CURRENT and every uniform
 * upload for it already done, which is precisely the state the fragment shader will execute in — so a
 * {@code glGetUniformfv} here reads the values the draw actually uses, not a pre-update leftover. An
 * earlier probe read at the pass BOUNDARY instead and its dest-camera reading turned out to be exactly
 * such a leftover.
 *
 * <p><b>Cost.</b> {@code Program.use()} runs on the order of a couple of hundred times a frame. The
 * handler's first statement is a {@code static final boolean} test, so at the shipped default the whole
 * body folds to dead code and only the call site remains. With the lever on, a bind whose program id is
 * not a composite-chain program costs one map lookup.
 *
 * <p>Behaviour-neutral: {@code @Inject} at TAIL, never {@code cancellable}, no {@code @Shadow}, no
 * {@code @Local}, no state written back into iris. {@code this} crosses as {@code Object} so the census
 * class — which is reached from {@code GameRenderer.render} TAIL on every runtime, iris or not — never
 * names an iris type in a signature.
 *
 * <p>{@code require = 0} because the config sets {@code defaultRequire = 1} and this target exists only
 * when iris is present; the 8-leg gametest suite runs iris-ABSENT, where {@code IPCompatMixinPlugin}
 * declines the class. Simple name contains "Iris" per that plugin's gating rule.
 */
@Pseudo
@Mixin(value = Program.class, remap = false)
public abstract class MixinIrisProgram_Census {

    @Inject(method = "use", at = @At("TAIL"), remap = false, require = 0)
    private void seamlessportals$censusProgramUsed(CallbackInfo ci) {
        IrisCompositeCensus.onProgramUsed(this);
    }
}
