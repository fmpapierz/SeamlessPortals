package qouteall.imm_ptl.core.compat.mixin.iris;

import com.warwa.seamlessportals.render.ActDispatchProbe;
import net.irisshaders.iris.gl.program.ComputeProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * IS5-ACT ROUND-2 (M1) — the DISPATCH WITNESS. Log-only, default OFF
 * ({@code -Dseamlessportals.actDispatchProbe}).
 *
 * <p><b>Why this mixin exists.</b> Live run 2 measured, with a controlled positive control, that a
 * portal destination's ACT volume is VOXELISED but NEVER FLOOD-FILLED: the same nether volume reads
 * {@code ff nz=2522..9905} when that dimension is the main view, and {@code ff nz=0/32768} in 0-of-~80
 * captures when it is a portal destination. Reflection took that as far as it goes — it can read the
 * compute ROSTER but not whether {@code dispatch()} actually executes, nor what uniform values the
 * invocation sees. This hook is the pre-registered escalation for exactly that gap, and was added on
 * the user's explicit sign-off.
 *
 * <p><b>Behaviour-neutral by construction.</b> {@code @Inject} at HEAD, never {@code cancellable},
 * no {@code @Shadow}, no {@code @Local}, no state, no return-value influence — the original
 * {@code dispatch} runs identically whether or not this fires. The handler's first statement is a
 * {@code static final boolean} lever test, so at the shipped default the whole body is JIT dead code.
 *
 * <p><b>{@code require = 0} is mandatory</b>: the mixin config sets {@code defaultRequire = 1}, and
 * this target only exists when iris is on the runtime. The 8-leg gametest suite runs iris-ABSENT, where
 * {@code IPCompatMixinPlugin} declines to apply the class entirely.
 *
 * <p>NOTE the simple name contains "Iris" — a hard requirement of {@link
 * qouteall.imm_ptl.core.compat.IPCompatMixinPlugin}, which string-matches the simple name to decide
 * iris-gating. {@code ComputeProgram} being {@code public final} is irrelevant: Mixin merges into the
 * target's ClassNode rather than subclassing it (in-tree precedent: sodium's {@code final Viewport} is
 * targeted twice).
 *
 * <p>The probe deliberately never calls {@code ComputeProgram.getWorkGroups(float,float)} — that method
 * MUTATES {@code cachedWorkGroups}/{@code cachedWidth}/{@code cachedHeight} (and returns null when an
 * indirect pointer is set). The work-group arithmetic is reproduced read-only instead.
 */
@Pseudo
@Mixin(value = ComputeProgram.class, remap = false)
public abstract class MixinIrisComputeProgram_ActDispatch {

    @Inject(method = "dispatch(FF)V", at = @At("HEAD"), remap = false, require = 0)
    private void seamlessportals$onActDispatch(float width, float height, CallbackInfo ci) {
        // `this` crosses as Object: no iris type appears in a mod-side signature, so an iris-absent
        // runtime disarms the probe via Class.forName rather than raising NoClassDefFoundError.
        ActDispatchProbe.onDispatch(this, width, height);
    }
}
