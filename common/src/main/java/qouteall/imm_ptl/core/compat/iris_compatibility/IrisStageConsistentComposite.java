package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
        if (mechanismBroken) return "mechanism-broken(" + mechanismBreakReason + ")";
        if (!ensureReflection()) return "reflection-unavailable";
        if (!proveFrameCounterWrite()) return "counter-write-unproven";
        // S4b lands: resolve the MAIN pipeline + its pass-0 read side here (disarm on
        // compute-only-pass-0 with no real pass / zero-composite packs), allocate the per-view
        // capture list, and grant the arm. Until the relocated loop exists, fall back.
        return "loop-not-landed(S4b)";
    }

    // =============================================================================================
    // S4b-part1 — per-VIEW capture slots (design §3.5) + the capture/cancel body (design §1.2)
    // =============================================================================================

    /** Hard bound on simultaneous captures. The §3.3 speculative cap + the nested budget keep real
     *  counts far below this; the bound exists so a pathological frame cannot allocate unbounded
     *  full-res GPU memory (~24MB/view at 1080p — stated per design §3.5). */
    private static final int MAX_CAPTURE_SLOTS = 16;

    /** One captured portal view: two mod-owned GL textures (colour in colortex0's exact queried
     *  format, depth in depthtex0's) + the stamp parameters registered at arm time. Slots are
     *  pooled and recycled after the stamp pass consumes them (consume-once-per-frame). */
    static final class CaptureSlot {
        int colorTex = 0;
        int depthTex = 0;
        int w = -1, h = -1, colorFmt = 0, depthFmt = 0;
        boolean pending = false;
        Portal portal;
        Matrix4f modelView;
        Matrix4f projection;
        int layer;
    }

    private static final ArrayList<CaptureSlot> captureSlots = new ArrayList<>();
    private static int capturesPendingThisFrame = 0;

    /** The one armed view (set by the loop fork just before renderPortalContent; consumed by
     *  exactly one nested finalize — success or failure, one finalize clears one arm). */
    private static CaptureSlot armedCapture = null;

    /** Permanent session disarm on any runtime capture/stamp failure (the mask's `broken` idiom).
     *  Pending captures already taken remain valid and still stamp; FUTURE frames fall back to the
     *  old path via {@link #decideArmForFrame}. */
    private static boolean mechanismBroken = false;
    private static String mechanismBreakReason = null;

    private static void breakMechanism(String reason, Throwable t) {
        mechanismBroken = true;
        mechanismBreakReason = reason;
        LOGGER.warn("[Seamless Portals] [IS5-PRE] mechanism BROKEN — falling back to the old"
            + " path from the next frame on. Reason: {}", reason, t);
    }

    /**
     * Arm the capture for one view (the loop fork calls this immediately before
     * {@code renderPortalContent}). Returns false when no slot is available (caller skips the
     * view this frame — bounded pop-in, never a half-armed state).
     */
    public static boolean armCaptureForView(
        Portal portal, Matrix4f modelView, Matrix4f projection, int layer
    ) {
        if (!PATH_ACTIVE || mechanismBroken) return false;
        if (armedCapture != null) {
            // A previous arm was never consumed — a nested render that never reached finalize
            // (throw inside render()). Loud once via break: the invariant "one arm, one finalize"
            // is load-bearing for the whole seam.
            breakMechanism("previous arm never consumed by a finalize (nested render threw?)", null);
            armedCapture = null;
            return false;
        }
        CaptureSlot slot = null;
        for (CaptureSlot s : captureSlots) {
            if (!s.pending) { slot = s; break; }
        }
        if (slot == null) {
            if (captureSlots.size() >= MAX_CAPTURE_SLOTS) return false;
            slot = new CaptureSlot();
            captureSlots.add(slot);
        }
        slot.portal = portal;
        slot.modelView = new Matrix4f(modelView);
        slot.projection = new Matrix4f(projection);
        slot.layer = layer;
        armedCapture = slot;
        return true;
    }

    /** True while the frame-start loop's current view is armed — the loop fork's discriminator
     *  for skipping old-path per-view work (bloom-mask arm, the post-pop stamp, old probes). */
    public static boolean isViewArmed() {
        return armedCapture != null;
    }

    /**
     * THE CAPTURE+CANCEL BODY (design §1.2), at the nested finalize's renderAll INVOKE.
     * Bookkeeping (isRenderingWorld=false, removePhaseIfNeeded) already ran — cancelling skips
     * exactly composites+final. Mutate-last discipline per the mask: every fallible read happens
     * before the copies; any failure breaks the mechanism loudly and does NOT cancel (the view's
     * composites run on into the main target, which the main render then clears — a lost view for
     * one frame, never a corrupted frame).
     */
    public static void onFinalizeAboutToComposite(Object irisRenderingPipeline, CallbackInfo ci) {
        if (!captureSeamWitnessed) {
            captureSeamWitnessed = true;
            LOGGER.info(
                "[Seamless Portals] [IS5-PRE] capture seam WOVEN (finalizeLevelRendering -> "
                    + "renderAll INVOKE, cancellable) — path={} armed={}",
                PATH_ACTIVE ? "NEW(stage-consistent)" : "OLD(post-final stamp)",
                armedCapture != null
            );
        }
        if (!PATH_ACTIVE) return;
        CaptureSlot slot = armedCapture;
        if (slot == null) return;
        armedCapture = null; // one finalize consumes one arm, success or failure
        if (mechanismBroken) return;
        try {
            Object cr = fPipelineCompositeRenderer.get(irisRenderingPipeline);
            if (!(cr instanceof CompositeRenderer)) {
                breakMechanism("pipeline.compositeRenderer is not a CompositeRenderer", null);
                return;
            }
            Boolean readAlt = resolvePassZeroReadsAlt((List<?>) fPasses.get(cr));
            if (readAlt == null) {
                breakMechanism("no real composite pass 0 on the dest pipeline (compute-only or"
                    + " zero-composite pack)", null);
                return;
            }
            RenderTargets rts = (RenderTargets) fPipelineRenderTargets.get(irisRenderingPipeline);
            RenderTarget c0 = rts.get(0);
            if (c0 == null) {
                breakMechanism("colortex0 RenderTarget is null at capture", null);
                return;
            }
            int srcColor = readAlt ? c0.getAltTexture() : c0.getMainTexture();
            int w = c0.getWidth();
            int h = c0.getHeight();
            int colorFmt = GL45C.glGetTextureLevelParameteri(
                srcColor, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            GpuTexture depthGpu = rts.getDepthTexture();
            if (!(depthGpu instanceof GlTexture depthGl)) {
                breakMechanism("depthtex0 is not a GlTexture (backend drift)", null);
                return;
            }
            int srcDepth = depthGl.glId();
            int depthFmt = GL45C.glGetTextureLevelParameteri(
                srcDepth, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (!ensureSlotStorage(slot, w, h, colorFmt, depthFmt)) {
                breakMechanism("capture texture allocation rejected (colorFmt=0x"
                    + Integer.toHexString(colorFmt) + " depthFmt=0x"
                    + Integer.toHexString(depthFmt) + ")", null);
                return;
            }
            GL43C.glCopyImageSubData(
                srcColor, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                slot.colorTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
            GL43C.glCopyImageSubData(
                srcDepth, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                slot.depthTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
            // Capture-geometry witness (design §3.15 — the snapshot-witness discipline retargeted;
            // always on, content-keyed, WARN on any dimension oddity is impossible here by
            // construction since both copies use the SAME queried w/h — the announcement is the
            // record that this frame's capture pair really is one geometry).
            noteCaptureGeometry(w, h, colorFmt, depthFmt);
            // Cross-dim mip hygiene, UNCONDITIONAL (judge-sharpened design §1.2): with composites
            // AND final cancelled, neither renderAll's mip regen nor renderFinalPass's
            // resetRenderTarget(turnOffMips) ever runs on this pipeline instance — stale
            // mip-enabled filtering would persist into its next frame's view otherwise.
            int n = rts.getRenderTargetCount();
            for (int i = 0; i < n; i++) {
                RenderTarget rt = rts.get(i);
                if (rt == null) continue;
                GL45C.glTextureParameteri(
                    rt.getMainTexture(), GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL45C.glTextureParameteri(
                    rt.getAltTexture(), GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            }
            slot.pending = true;
            capturesPendingThisFrame++;
            ci.cancel();
        } catch (Throwable t) {
            breakMechanism("capture threw", t);
        }
    }

    /** Pass-0 read side: first pass whose {@code stageReadsFromAlt} is non-null (a
     *  ComputeOnlyPass never assigns it — V3); null when no real pass exists. */
    private static Boolean resolvePassZeroReadsAlt(List<?> passes) throws IllegalAccessException {
        for (Object pass : passes) {
            Object set = fPassReadsFromAlt.get(pass);
            if (set != null) {
                return ((Set<?>) set).contains(0);
            }
        }
        return null;
    }

    private static boolean ensureSlotStorage(
        CaptureSlot slot, int w, int h, int colorFmt, int depthFmt
    ) {
        if (slot.colorTex != 0 && slot.w == w && slot.h == h
            && slot.colorFmt == colorFmt && slot.depthFmt == depthFmt) {
            return true;
        }
        if (slot.colorTex != 0) {
            GL11.glDeleteTextures(slot.colorTex);
            slot.colorTex = 0;
        }
        if (slot.depthTex != 0) {
            GL11.glDeleteTextures(slot.depthTex);
            slot.depthTex = 0;
        }
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate for the alloc check */ }
        int color = GL45C.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(color, 1, colorFmt, w, h);
        int depth = GL45C.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(depth, 1, depthFmt, w, h);
        if (GL11.glGetError() != GL11.GL_NO_ERROR) {
            GL11.glDeleteTextures(color);
            GL11.glDeleteTextures(depth);
            return false;
        }
        slot.colorTex = color;
        slot.depthTex = depth;
        slot.w = w;
        slot.h = h;
        slot.colorFmt = colorFmt;
        slot.depthFmt = depthFmt;
        return true;
    }

    private static String lastCaptureGeometry = null;

    private static void noteCaptureGeometry(int w, int h, int colorFmt, int depthFmt) {
        String g = w + "x" + h + " color=0x" + Integer.toHexString(colorFmt)
            + " depth=0x" + Integer.toHexString(depthFmt);
        if (g.equals(lastCaptureGeometry)) return;
        lastCaptureGeometry = g;
        LOGGER.info("[Seamless Portals] [IS5-PRE] capture geometry {}", g);
    }

    /** Frame-start safety recycle: a pending slot surviving into a new frame means the stamp
     *  never consumed it (main renderAll never ran, or the discriminator failed) — recycle and
     *  say so once per occurrence pattern. Called by the loop entry (S4b-part3). */
    public static void beginFrame() {
        if (capturesPendingThisFrame > 0) {
            LOGGER.warn("[Seamless Portals] [IS5-PRE] {} capture(s) from the previous frame were"
                + " never stamped — recycled (main composite renderAll missing or discriminator"
                + " mismatch?)", capturesPendingThisFrame);
        }
        for (CaptureSlot s : captureSlots) {
            s.pending = false;
        }
        capturesPendingThisFrame = 0;
        armedCapture = null;
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
        if (capturesPendingThisFrame == 0) return;
        // S4b-part2 lands the body: triple discriminator (identity vs the MAIN pipeline's
        // compositeRenderer + !PortalRendering.isRendering() + consume-once), then the raw-GL
        // stamp draw per the runMask pattern (target = colortex0 pass-0-READ side +
        // addDepthAttachment(depthtex0); depth test shipped-compare-family + WRITE; sampler = the
        // capture; C4-SEAM depth clamp; no clear, no dilation), consuming every pending slot in
        // capture order.
    }
}
