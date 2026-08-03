package qouteall.imm_ptl.core.compat.mixin.iris;

import com.warwa.seamlessportals.render.ActDispatchProbe;
import net.irisshaders.iris.shadows.ShadowCompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * IS5-ACT ROUND-2 (M2) — the RENDER-ALL WITNESS. Log-only, default OFF
 * ({@code -Dseamlessportals.actDispatchProbe}).
 *
 * <p><b>What it buys, and why it is not redundant.</b> The shipped {@code ActSeedProbe} proves a shadow
 * pass ran inside the nested dest render via the {@code ShadowRenderer.MODELVIEW} store at offset 189,
 * but {@code compositeRenderer.renderAll()} is called at offset <b>1405</b> — the 189&rarr;1405 gap is
 * unmeasured. Without this hook, "renderAll was never reached" and "renderAll ran but dispatched
 * nothing" are indistinguishable, and they point at completely different round-3 investigations
 * (bisect {@code renderShadows} vs inspect the pass roster).
 *
 * <p>It cannot be replaced by the existing roster read: {@code ActSeedProbe}'s {@code withComputes}
 * counts array CAPACITY (iris always allocates 27 {@code ComputeSource} slots), so it can never report
 * zero — a defect this round also fixes.
 *
 * <p><b>No interaction with the shipped IS5-FF suppression.</b> {@code NoopShadowCompositeRenderer}
 * (IrisShadowCompositeSuppressor) OVERRIDES {@code renderAll} and never calls {@code super}, so the
 * woven base method is off the no-op path entirely. That is a feature: a same-dim window therefore
 * reads {@code renderAll=0} and acts as a free negative control, while
 * {@code IPGlobal.nestedShadowCompositeNoopHits} still counts the suppressed call.
 *
 * <p>Behaviour-neutral: HEAD {@code @Inject}, not cancellable, no {@code @Shadow}, no state, one
 * {@code int++} behind a {@code static final} lever. {@code require = 0} because the config sets
 * {@code defaultRequire = 1} and this target exists only when iris is on the runtime. Simple name
 * contains "Iris" per the {@code IPCompatMixinPlugin} gating rule.
 */
@Pseudo
@Mixin(value = ShadowCompositeRenderer.class, remap = false)
public abstract class MixinIrisShadowCompositeRenderer_ActRenderAll {

    @Inject(method = "renderAll()V", at = @At("HEAD"), remap = false, require = 0)
    private void seamlessportals$onShadowCompositeRenderAll(CallbackInfo ci) {
        ActDispatchProbe.onShadowCompositeRenderAll();
    }
}
