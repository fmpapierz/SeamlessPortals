package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;

/**
 * IS5-PRE coordinator — stage-consistent portal compositing ({@code migration/IS5_PRE_DESIGN.md}
 * v3). Owns the per-frame state shared by the three seams:
 *
 * <ol>
 *   <li>the FRAME-START loop (S3) arms each nested view before rendering it;</li>
 *   <li>{@code MixinIrisRenderingPipeline_PreCompositeCapture} consumes the arm at the view's
 *       {@code finalizeLevelRendering} → {@code renderAll} INVOKE: capture colortex0+depthtex0,
 *       mip hygiene, cancel (S4);</li>
 *   <li>{@code MixinIrisCompositeRenderer_PreCompositeStamp} pastes the pending captures at the
 *       MAIN chain's composite {@code renderAll} HEAD (S4), behind the judge-mandated triple
 *       discriminator (identity vs the main pipeline's {@code compositeRenderer} field,
 *       {@code !PortalRendering.isRendering()}, consume-once-per-frame).</li>
 * </ol>
 *
 * <b>STAGE S2 STATE: dormant skeleton.</b> Both handlers early-return unless
 * {@link IPGlobal#STAGE_CONSISTENT_COMPOSITE} is on AND the frame-start loop armed a view —
 * and nothing arms until S3 lands. The only live behaviour in S2 is the once-only WEAVE
 * WITNESSES: the V2-verifier flagged "runtime weave of a new cancellable inject on
 * IrisRenderingPipeline" as the one untested mixin precedent, so the first shaders-ON launch
 * must prove both weaves from the log before any leg is trusted.
 *
 * Cross-view (XWIN) frames: never armed (design §2) — they keep the old post-composite path and
 * the ring, disclosed. The arm flag, not {@code PortalRendering.isRendering()}, is the
 * discriminator precisely for this reason.
 */
public final class IrisStageConsistentComposite {
    private static final Logger LOGGER = LogUtils.getLogger();

    private IrisStageConsistentComposite() {}

    /** Mirror of the immutable path flag; every seam gates on this exact value. */
    public static final boolean PATH_ACTIVE = IPGlobal.STAGE_CONSISTENT_COMPOSITE;

    // ---- weave witnesses (once-only; the log line is the proof the mixin applied) ----
    private static boolean captureSeamWitnessed = false;
    private static boolean stampSeamWitnessed = false;
    private static boolean frameStartWitnessed = false;

    /**
     * S3 frame-start witness — proves the shift=BEFORE anchor dispatched into the compat
     * renderer on the live JVM. Content is invariant, so a plain once-only latch is safe.
     */
    public static void noteFrameStartAnchorLive(String rendererName, int layer) {
        if (frameStartWitnessed) return;
        frameStartWitnessed = true;
        LOGGER.info(
            "[Seamless Portals] [IS5-PRE] frame-start anchor LIVE (renderer={}, layer={}) — "
                + "S3 skeleton: arm decision falls back to OLD path until S4 lands the loop",
            rendererName, layer
        );
    }

    // ---- per-view arm (set by the frame-start loop in S3; consumed by the capture seam) ----
    /** Non-null while a frame-start-loop-initiated nested view is rendering. S3 sets/clears it. */
    private static Object armedViewToken = null;

    /**
     * Capture seam handler. S4 lands the body (capture colortex0 pass-0-read side + depthtex0
     * into the armed view's capture buffer, turnOffMips hygiene on the cancelled instance,
     * {@code ci.cancel()}). S2: weave witness + dormant.
     */
    public static void onFinalizeAboutToComposite(Object irisRenderingPipeline, CallbackInfo ci) {
        if (!captureSeamWitnessed) {
            captureSeamWitnessed = true;
            LOGGER.info(
                "[Seamless Portals] [IS5-PRE] capture seam WOVEN (finalizeLevelRendering -> "
                    + "renderAll INVOKE, cancellable) — path={} armed={}",
                PATH_ACTIVE ? "NEW(stage-consistent)" : "OLD(post-final stamp)",
                armedViewToken != null
            );
        }
        if (!PATH_ACTIVE) return;
        if (armedViewToken == null) return;
        // S4: capture + hygiene + ci.cancel(). Unreachable in S2/S3 dev states by construction
        // (nothing arms before S3; S3 arms only behind PATH_ACTIVE).
    }

    /**
     * Stamp seam handler. S4 lands the body (triple discriminator, then paste every pending
     * capture in order). S2: weave witness + dormant.
     */
    public static void onCompositeRenderAllHead(Object compositeRenderer) {
        if (!stampSeamWitnessed) {
            stampSeamWitnessed = true;
            LOGGER.info(
                "[Seamless Portals] [IS5-PRE] stamp seam WOVEN (CompositeRenderer.renderAll HEAD)"
                    + " — path={}",
                PATH_ACTIVE ? "NEW(stage-consistent)" : "OLD(post-final stamp)"
            );
        }
        if (!PATH_ACTIVE) return;
        // S4: if (pendingCaptures.isEmpty()) return; discriminate; stamp; consume-once-per-frame.
    }
}
