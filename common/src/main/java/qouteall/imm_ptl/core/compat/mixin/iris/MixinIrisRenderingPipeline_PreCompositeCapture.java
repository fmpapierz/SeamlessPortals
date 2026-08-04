package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisStageConsistentComposite;

/**
 * IS5-PRE — the CAPTURE+CANCEL seam ({@code migration/IS5_PRE_DESIGN.md} §1.2 / §3.4).
 *
 * <h2>Injection site (javap -c on the pinned release jar, 2026-08-04 — verified, do not re-derive)</h2>
 * {@code finalizeLevelRendering} is exactly four operations (24 bytecode bytes):
 * {@code isRenderingWorld=false; removePhaseIfNeeded(); compositeRenderer.renderAll();
 * finalPassRenderer.renderFinalPass();}. The {@code CompositeRenderer.renderAll()} INVOKE at
 * offset 13 is the ONLY occurrence in the method ({@code beginRenderer.renderAll} lives in
 * {@code beginLevelRendering}, {@code deferredRenderer.renderAll} in {@code beginTranslucents}),
 * so cancelling HERE skips exactly composites+final with ZERO bookkeeping replication — the
 * {@code isRenderingWorld=false} store and {@code removePhaseIfNeeded()} have already executed
 * naturally (the verifier-judged reason this beats a HEAD cancel).
 *
 * <h2>Arming — the cross-view (XWIN) discriminator is the ARM FLAG, not isRendering()</h2>
 * The handler consumes {@link IrisStageConsistentComposite}'s per-view arm, set ONLY by the
 * frame-start loop. Cross-view-dispatched nested views (which also push portal layers) are never
 * armed and keep the old post-composite path — judge-mandated: bare
 * {@code PortalRendering.isRendering()} would cancel their composites with no stamp point left in
 * the frame, shipping blank windows.
 *
 * <h2>require = 1 (config defaultRequire)</h2>
 * This hook is behaviour-critical in BOTH directions: silently unwoven = the new path captures
 * nothing and every armed view double-composites; wrongly woven = the presentation frame loses
 * its composites. Any iris jar that boots past ClipInject's {@code require = 1} is the
 * bytecode-verified 1.11.2+26.2 shape, so a loud apply failure here means real drift.
 * {@code @Pseudo} tolerates iris-absent (unwoven, zero bytes; the gametest suite runs
 * iris-ABSENT). First-launch weave witness: the once-only "[IS5-PRE] capture seam WOVEN" line
 * emitted from {@link IrisStageConsistentComposite#onFinalizeAboutToComposite}.
 */
@Pseudo
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class MixinIrisRenderingPipeline_PreCompositeCapture {

    @Inject(
        method = "finalizeLevelRendering",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"
        ),
        remap = false,
        cancellable = true
    )
    private void seamlessportals$captureAndCancelForStageConsistentComposite(CallbackInfo ci) {
        IrisStageConsistentComposite.onFinalizeAboutToComposite(this, ci);
    }
}
