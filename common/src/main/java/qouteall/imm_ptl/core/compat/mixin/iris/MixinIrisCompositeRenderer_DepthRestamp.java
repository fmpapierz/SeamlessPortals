package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisStageConsistentComposite;

/**
 * IS5-RESTAMP ({@code migration/IS5_RESTAMP_DESIGN.md} §1.4) — the pass-boundary hook for the
 * depthtex1 CONTENT restamp + the IS5-XDIM-SG dest capture / source inject. A clone of the
 * {@link MixinIrisCompositeRenderer_BloomApertureMask} injection (the SAME bytecode-verified
 * {@code Program.unbind()} seam — offset 213, the ONLY occurrence in {@code renderAll}; the
 * tail cleanup uses {@code GlStateManager._glUseProgram(0)}, NOT Program.unbind — so this fires
 * exactly once per pass ITERATION, BEFORE pass i's mipmap regen / setupState / viewport / use /
 * draw). {@code @Local(ordinal = 0) int} resolves to the loop index {@code i} (slot 4, LVT range
 * covers the offset — the mask's javap note; re-verify on any iris bump per the
 * mixin-bytecode rule).
 *
 * <p>⟦J⟧ Co-injection note (closed-arcs judge): this and the BloomApertureMask hook now share
 * one seam with unspecified relative order. On Complementary their firing indices differ
 * (bloom-mask index vs the reprojection anchor) and both handlers are state-self-contained, so
 * order is immaterial; the disjoint-index assumption is recorded in the design doc.
 *
 * <p>{@code require = 0} — the same deliberate dormancy contract as the mask: iris BINARY drift
 * already crashes loudly at ClipInject's {@code require = 1}; same-version method-shape drift
 * leaves this dormant, which {@code IrisStageConsistentComposite}'s beginFrame orphan WARN +
 * {@code rstOrph} census make non-silent (depthtex1 stays PLANE — shipped-safe, attributable).
 *
 * <p>Gating: registered in {@code seamlessportals-ip-compat.mixins.json}; the simple name
 * contains {@code "Iris"} but not {@code "Sodium"} ⇒ the plugin routes to
 * {@code isIrisPresent()} + {@code EntityPortalsFlag.isOn()}. Iris-absent / flag-OFF / the
 * suite: unwoven, zero bytes. {@code @Pseudo} tolerates the absent target class.
 */
@Pseudo
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class MixinIrisCompositeRenderer_DepthRestamp {

    @Inject(
        method = "renderAll",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/program/Program;unbind()V"
        ),
        remap = false,
        require = 0
    )
    private void seamlessportals$restampBoundary(CallbackInfo ci, @Local(ordinal = 0) int i) {
        IrisStageConsistentComposite.onRestampBoundary(this, i);
    }
}
