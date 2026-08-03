package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisBloomApertureMask;

/**
 * C3-BLOOM §2f — the per-pass-iteration hook for the aperture mask (adjudicated spec
 * {@code migration/C3BLOOM_ADJUDICATED_SPEC.md} §2.A; the bloom-RING fix's ONLY iris mixin —
 * the 2nd in-tree after {@link MixinIrisSodiumTransformPatcher_ClipInject}, the priced D9
 * amendment).
 *
 * <h2>Injection site (javap -c -l on the release jar — verified, do not re-derive)</h2>
 * {@code Program.unbind()} invokestatic at bytecode offset 213 is the ONLY occurrence in
 * {@code CompositeRenderer.renderAll} (the tail cleanup uses {@code GlStateManager
 * ._glUseProgram(0)}, NOT Program.unbind), so the un-ordinaled INVOKE match fires exactly once
 * per pass ITERATION (~10-15 armed-null checks/frame across the 4 stage instances under
 * Complementary — byte-inert; verifier-1 F4). The pass loop is INDEXED with LVT present:
 * {@code i} slot 4 {@code I} (range covers 213), {@code passesSize} slot 5, {@code ranCompute}
 * slot 7 {@code Z} (excluded from int-ordinal counting), {@code index} slot 9 out of scope at
 * the offset — {@code @Local(ordinal = 0) int} resolves to {@code i} under both LVT and
 * frame-analysis fallback. Iteration order (bytecode): computes → memoryBarrier →
 * <b>unbind [this hook]</b> → ComputeOnlyPass skip → mipmap regen (same alt/main side the plan
 * masks) → setupState → viewport/scissor → use → draw. So a mask fired at iteration
 * {@code lastC0Writer + 1} lands AFTER the last c0 writer's draw and BEFORE the bloom pass's own
 * mip regeneration — iris itself rebuilds the mips from the masked lod0; no manual mip work.
 * {@code ShadowCompositeRenderer} is a SEPARATE class in {@code net.irisshaders.iris.shadows}
 * (verifier-1 F2) — never matched by this target.
 *
 * <h2>require = 0 — the DELIBERATE override of the config's defaultRequire = 1</h2>
 * A documented deviation from the D7 loud-crash norm: iris BINARY drift is already a loud boot
 * crash via ClipInject's {@code require = 1} on this same jar, so dormancy here cannot mask a
 * version mismatch — it covers only same-version method-shape drift (renderAll reshaped / the
 * unbind call removed), and {@code IrisBloomApertureMask.disarmAndReport()}'s
 * armed-but-never-consumed once-only WARN + miss-counter make that dormancy non-silent (the
 * ring returns, attributably; NO crash).
 *
 * <h2>The one crash corner (verifier-2 FIX5 — honest)</h2>
 * {@code require = 0} covers injection-point COUNT only. A MixinExtras {@code @Local}
 * RESOLUTION failure on a drifted iris (no in-scope int at a still-matching unbind site, or an
 * ambiguity the discriminator cannot settle) is a hard APPLY error — a loud boot crash, not
 * dormancy. Accepted under the ClipInject same-jar version-pin argument (any iris jar that
 * boots past ClipInject is the bytecode-verified 1.11.2+26.2 shape). The documented escape if
 * that ever fires: replace {@code @Local} with an {@code @Unique} int cursor — a HEAD inject
 * resetting it to 0 plus a post-increment in this handler (the unbind seam is once per
 * iteration, so the cursor tracks {@code i} exactly).
 *
 * <h2>Gating</h2>
 * Registered in {@code seamlessportals-ip-compat.mixins.json}; the simple name contains
 * {@code "Iris"} but not {@code "IrisSodium"}/{@code "Sodium"} ⇒ {@code IPCompatMixinPlugin}
 * gate-1 routes to {@code isIrisPresent()} alone (the plugin's substring footgun satisfied), and
 * gate-2 {@code EntityPortalsFlag.isOn()} composes. Iris-absent / flag-OFF / the suite: unwoven,
 * zero bytes. {@code @Pseudo} tolerates the absent target class at apply time.
 */
@Pseudo
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class MixinIrisCompositeRenderer_BloomApertureMask {

    @Inject(
        method = "renderAll",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/program/Program;unbind()V"
        ),
        remap = false,
        require = 0
    )
    private void seamlessportals$maskBloomAperture(CallbackInfo ci, @Local(ordinal = 0) int i) {
        IrisBloomApertureMask.onCompositePassBoundary((CompositeRenderer) (Object) this, i);
    }
}
