package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.logging.LogUtils;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;

import java.lang.reflect.Field;
import java.util.List;

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

    /**
     * S3/S4a frame-start witness — proves the shift=BEFORE anchor dispatched into the compat
     * renderer, and reports the ARM DECISION's live status. CONTENT-KEYED (the C3-BLOOM lesson:
     * a boolean latch on a value that changes is a bug generator) — any change in the decision
     * string re-emits; an identical decision never repeats.
     */
    public static void noteFrameStartAnchorLive(String rendererName, int layer) {
        String decision = decideArmForFrame();
        String announcement = "renderer=" + rendererName + " layer=" + layer
            + " armDecision=" + (decision == null ? "ARMED" : ("OLD-PATH(" + decision + ")"));
        if (announcement.equals(lastFrameStartAnnouncement)) return;
        lastFrameStartAnnouncement = announcement;
        LOGGER.info("[Seamless Portals] [IS5-PRE] frame-start anchor LIVE — {}", announcement);
    }

    private static String lastFrameStartAnnouncement = null;

    // =============================================================================================
    // S4a — reflection surfaces + the mechanism-wide ARM DECISION (design §3.1)
    // =============================================================================================

    private static boolean reflectAttempted = false;
    private static boolean reflectReady = false;
    /** {@code IrisRenderingPipeline.compositeRenderer} (private final; javap-pinned 2026-08-04) —
     *  the stamp seam's identity discriminator AND the capture seam's pass-list source. */
    private static Field fPipelineCompositeRenderer;
    /** {@code IrisRenderingPipeline.renderTargets} (private final) — capture source textures. */
    private static Field fPipelineRenderTargets;
    /** {@code CompositeRenderer.passes} (private final ImmutableList&lt;Pass&gt;). */
    private static Field fPasses;
    /** {@code CompositeRenderer$Pass.stageReadsFromAlt} — pass-0 READ side = the stamp's WRITE
     *  side (judge: do NOT derive from flippedAfterTranslucent — composite_pre flips). */
    private static Field fPassReadsFromAlt;
    /** {@code SystemTimeUniforms$FrameCounter.count} (private int) — the §3.7 distant-offset
     *  counter bracket's write target. READ needs no reflection ({@code getAsInt()} is public). */
    private static Field fFrameCounterCount;

    /** Ternary probe state for the reflective counter WRITE: 0=untried, 1=proven, -1=failed. */
    private static int frameCounterWriteProbe = 0;

    private static boolean ensureReflection() {
        if (reflectReady) return true;
        if (reflectAttempted) return false;
        reflectAttempted = true;
        try {
            fPipelineCompositeRenderer =
                IrisRenderingPipeline.class.getDeclaredField("compositeRenderer");
            fPipelineCompositeRenderer.setAccessible(true);
            fPipelineRenderTargets = IrisRenderingPipeline.class.getDeclaredField("renderTargets");
            fPipelineRenderTargets.setAccessible(true);
            fPasses = CompositeRenderer.class.getDeclaredField("passes");
            fPasses.setAccessible(true);
            Class<?> passClass =
                Class.forName("net.irisshaders.iris.pipeline.CompositeRenderer$Pass");
            fPassReadsFromAlt = passClass.getDeclaredField("stageReadsFromAlt");
            fPassReadsFromAlt.setAccessible(true);
            fFrameCounterCount =
                SystemTimeUniforms.COUNTER.getClass().getDeclaredField("count");
            fFrameCounterCount.setAccessible(true);
            reflectReady = true;
        } catch (Throwable t) {
            LOGGER.warn("[Seamless Portals] [IS5-PRE] iris reflection failed — the new path is"
                + " DISARMED for the session (behavior = shipped old path)", t);
        }
        return reflectReady;
    }

    /**
     * The judged "new surface" risk made explicit: prove the reflective {@code FrameCounter.count}
     * WRITE on the live JVM (read → write same value → re-read) before the counter bracket is ever
     * trusted. Failure disarms the whole mechanism to the old path, loudly once.
     */
    private static boolean proveFrameCounterWrite() {
        if (frameCounterWriteProbe != 0) return frameCounterWriteProbe > 0;
        try {
            int before = SystemTimeUniforms.COUNTER.getAsInt();
            fFrameCounterCount.setInt(SystemTimeUniforms.COUNTER, before);
            int after = SystemTimeUniforms.COUNTER.getAsInt();
            if (after == before) {
                frameCounterWriteProbe = 1;
                LOGGER.info("[Seamless Portals] [IS5-PRE] FrameCounter.count reflective write"
                    + " PROVEN on the live JVM (value {} preserved)", before);
                return true;
            }
            frameCounterWriteProbe = -1;
            LOGGER.warn("[Seamless Portals] [IS5-PRE] FrameCounter.count write probe returned a"
                + " different value ({} -> {}) — DISARMED to the old path", before, after);
        } catch (Throwable t) {
            frameCounterWriteProbe = -1;
            LOGGER.warn("[Seamless Portals] [IS5-PRE] FrameCounter.count reflective write FAILED"
                + " — DISARMED to the old path", t);
        }
        return false;
    }

    /**
     * The ONE mechanism-wide arm decision (design §3.1), evaluated at the frame-start anchor
     * before any view renders. Returns {@code null} to ARM the new path for the frame, else the
     * fall-back reason. No half-armed states are possible: capture, cancel, and stamp all key off
     * the arm this method grants.
     */
    private static String decideArmForFrame() {
        if (!PATH_ACTIVE) return "path-flag-off";
        if (!ensureReflection()) return "reflection-unavailable";
        if (!proveFrameCounterWrite()) return "counter-write-unproven";
        // S4b lands: resolve the MAIN pipeline + its pass-0 read side here (disarm on
        // compute-only-pass-0 with no real pass / zero-composite packs), allocate the per-view
        // capture list, and grant the arm. Until the relocated loop exists, fall back.
        return "loop-not-landed(S4b)";
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
