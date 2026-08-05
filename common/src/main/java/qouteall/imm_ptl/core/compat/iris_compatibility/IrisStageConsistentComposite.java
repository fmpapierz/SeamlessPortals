package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.SecondaryWorldRenderCore;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
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

    /** Content-keyed frame-start announcement state (the C3-BLOOM lesson: a boolean latch on a
     *  value that changes is a bug generator) — any change in the decision string re-emits. */
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
        // Pipeline-shape checks (pass-0 side, real composite pass 0) are deliberately NOT here:
        // the manager slot holds LAST frame's pipeline at frame start (V5-measured), so per-view
        // resolution happens at the capture seam and MAIN resolution at the stamp seam — each at
        // the moment its pipeline is authoritative. A shape failure there breaks the mechanism,
        // which this decision reports from the next frame on.
        return null; // ARMED
    }

    // =============================================================================================
    // S4b-part3 — the frame-arm lifecycle, counter bracket, speculative gate, and bob machinery
    // =============================================================================================

    private static boolean frameArmed = false;

    /**
     * Called by the compat renderer's frame-start hook. Runs the §3.1 arm decision, announces it
     * content-keyed, and on ARM: recycles the capture pool and opens the frame. Returns whether
     * the frame-start loop should run (false ⇒ the shipped old path runs at the post anchor).
     */
    public static boolean tryArmFrame(String rendererName) {
        String decision = decideArmForFrame();
        String announcement = "renderer=" + rendererName
            + " armDecision=" + (decision == null ? "ARMED" : ("OLD-PATH(" + decision + ")"));
        if (!announcement.equals(lastFrameStartAnnouncement)) {
            lastFrameStartAnnouncement = announcement;
            LOGGER.info("[Seamless Portals] [IS5-PRE] frame-start — {}", announcement);
        }
        if (decision != null) return false;
        beginFrame();
        frameArmed = true;
        // IS5-PRE 1Hz stage census (always-on while armed, log-only): the S6 leg-3 lesson — the
        // content-keyed announcements are silent on INTERMITTENT stage drops (a frame with zero
        // captures prints nothing and no WARN), so a flicker's failing stage was unreadable.
        // One line per second with per-stage counts makes any drop attributable.
        censusArmedFrames++;
        long now = System.currentTimeMillis();
        if (censusLastEmitMs == 0) censusLastEmitMs = now;
        if (now - censusLastEmitMs >= 1000) {
            censusLastEmitMs = now;
            LOGGER.info("[Seamless Portals] [IS5-PRE] 1Hz: frames={} consumeT/F={}/{} specR/S={}/{}"
                    + " armG/D={}/{} capt={} stampPass={} views={}",
                censusArmedFrames, censusConsumeTrue, censusConsumeFalse,
                censusSpecRendered, censusSpecSkipped, censusArmGranted, censusArmDenied,
                censusCaptures, censusStampPasses, censusStampedViews);
            censusArmedFrames = 0;
            censusConsumeTrue = 0;
            censusConsumeFalse = 0;
            censusSpecRendered = 0;
            censusSpecSkipped = 0;
            censusArmGranted = 0;
            censusArmDenied = 0;
            censusCaptures = 0;
            censusStampPasses = 0;
            censusStampedViews = 0;
        }
        return true;
    }

    private static int censusArmedFrames, censusConsumeTrue, censusConsumeFalse,
        censusSpecRendered, censusSpecSkipped, censusArmGranted, censusArmDenied,
        censusCaptures, censusStampPasses, censusStampedViews;
    private static long censusLastEmitMs = 0;

    /** True while this frame's portal loop runs on the new path — the doRenderPortal forks'
     *  discriminator. NOT consumed by the stamp (which keys on pending captures). */
    public static boolean isFrameArmed() {
        return frameArmed;
    }

    /** The post-main anchor's discriminator: true exactly once per armed frame (consumed), so the
     *  workhorse can switch to its query-only mode without running the old snapshot/blit path. */
    public static boolean consumeFrameRanNewPath() {
        boolean r = frameArmed;
        frameArmed = false;
        return r;
    }

    // ---- §3.3 speculative gate (render-if-unknown, capped) ----

    /** Per-frame cap on layer-0 render-if-unknown speculative renders; beyond it unknown portals
     *  are skipped for the frame (bounded pop-in). After a frame-index gap wipes ALL query
     *  history, this is what prevents a render storm (judge-mandated). */
    private static final int SPECULATIVE_CAP = 4;
    private static int speculativeRendersThisFrame = 0;
    private static int speculativeSkipsThisFrame = 0;

    /** The armed-path replacement for testShouldRenderPortal: consume-only + capped default. */
    public static boolean consumeVisibilityForArmedFrame(Portal portal) {
        Boolean known = qouteall.imm_ptl.core.portal.PortalRenderInfo
            .consumeLastFrameVisibility(portal);
        if (known != null) {
            if (known) censusConsumeTrue++; else censusConsumeFalse++;
            return known;
        }
        if (speculativeRendersThisFrame < SPECULATIVE_CAP) {
            speculativeRendersThisFrame++;
            censusSpecRendered++;
            return true;
        }
        speculativeSkipsThisFrame++;
        censusSpecSkipped++;
        return false;
    }

    // ---- §3.7 counter bracket (distant-offset — the judged +1 bump ALIASES; see the design) ----

    private static int savedCounterValue = -1;

    /** Reflectively offset FrameCounter.count by 360360 (≡0 mod 8 — dest framemod parity kept;
     *  unreachable by the natural counter for ~360k frames) so every program the nested renders
     *  bind records a lastFrame value the MAIN render's binds cannot equal — main re-uploads its
     *  perFrame uniforms after the loop. No beginFrame() call — TIMER untouched. */
    public static boolean counterBracketBegin() {
        if (frameCounterWriteProbe <= 0) return false;
        try {
            int n = SystemTimeUniforms.COUNTER.getAsInt();
            savedCounterValue = n;
            fFrameCounterCount.setInt(SystemTimeUniforms.COUNTER, (n + 360360) % 720720);
            return true;
        } catch (Throwable t) {
            breakMechanism("counter bracket begin failed", t);
            return false;
        }
    }

    public static void counterBracketEnd() {
        try {
            fFrameCounterCount.setInt(SystemTimeUniforms.COUNTER, savedCounterValue);
        } catch (Throwable t) {
            breakMechanism("counter bracket end failed", t);
        }
    }

    // ---- §3.2 bob recompute (iris bobStack field) + the post-anchor witness ----

    private static boolean bobStackSearched = false;
    private static Field fBobStack = null;
    private static final Matrix4f lastUnbobbed = new Matrix4f();
    private static final Matrix4f lastComputedBobbed = new Matrix4f();
    private static boolean bobComputedThisFrame = false;

    /** bobbedView = bobStack × unbobbedView (iris's mulLocal semantics). The woven @Unique field
     *  is found by name-contains scan (mixin renaming tolerance); absent/null ⇒ unbobbed
     *  passthrough. Feeds ONLY IrisBobSync's dest-pose derive — stamp geometry never depends on
     *  it (stamp matrices resolve at stamp time from the F1-captured passingModelView). */
    public static Matrix4f computeBobbedView(Matrix4f unbobbed) {
        lastUnbobbed.set(unbobbed);
        bobComputedThisFrame = true;
        if (!bobStackSearched) {
            bobStackSearched = true;
            try {
                for (Field f : net.minecraft.client.Minecraft.getInstance()
                    .gameRenderer.getClass().getDeclaredFields()) {
                    if (f.getName().contains("bobStack")) {
                        f.setAccessible(true);
                        fBobStack = f;
                        break;
                    }
                }
                if (fBobStack == null) {
                    LOGGER.warn("[Seamless Portals] [IS5-PRE] no bobStack field on the woven"
                        + " GameRenderer — dest bob-lock uses the unbobbed view (coverage"
                        + " unaffected)");
                }
            } catch (Throwable t) {
                fBobStack = null;
                LOGGER.warn("[Seamless Portals] [IS5-PRE] bobStack reflection failed — dest"
                    + " bob-lock uses the unbobbed view", t);
            }
        }
        Matrix4f result = new Matrix4f(unbobbed);
        if (fBobStack != null) {
            try {
                Object stack = fBobStack.get(net.minecraft.client.Minecraft.getInstance().gameRenderer);
                if (stack instanceof org.joml.Matrix4fc bobStack) {
                    result = new Matrix4f(bobStack).mul(unbobbed);
                }
            } catch (Throwable ignored) {
            }
        }
        lastComputedBobbed.set(result);
        return result;
    }

    private static String lastBobWitness = null;

    /** Post-anchor witness (the AFTER anchor's modelView is the true post-mulLocal product):
     *  classifies the frame as MUL_APPLIED (recompute proven), MUL_SKIPPED (field==unbobbed —
     *  iris skipped the mul; the recompute over-applied a stale stack), or MISMATCH. Log-only,
     *  content-keyed — bob affects the dest-pose nicety, never coverage, so no disarm. */
    public static void bobWitnessPostAnchor(Matrix4f postMulField) {
        if (!bobComputedThisFrame) return;
        bobComputedThisFrame = false;
        String status;
        if (postMulField.equals(lastComputedBobbed, 1e-3f)) {
            status = "MUL_APPLIED(recompute proven)";
        } else if (postMulField.equals(lastUnbobbed, 1e-3f)) {
            status = "MUL_SKIPPED(field unbobbed; recompute over-applied a stale stack)";
        } else {
            status = "MISMATCH(neither candidate matches — bob source needs re-derivation)";
        }
        if (status.equals(lastBobWitness)) return;
        lastBobWitness = status;
        LOGGER.info("[Seamless Portals] [IS5-PRE] bob witness: {}", status);
    }

    private static boolean nestedLayerDeferredNoted = false;

    /** Part3 scope cut, disclosed: nested portal layers (portal-in-portal) are DEFERRED on the
     *  new path until the part4 capture-to-capture re-aim — the dispatch returns early when the
     *  frame is armed. Announced once so a live leg can never mistake it for a regression. */
    public static void noteNestedLayerDeferred() {
        if (nestedLayerDeferredNoted) return;
        nestedLayerDeferredNoted = true;
        LOGGER.info("[Seamless Portals] [IS5-PRE] nested portal layer requested on an armed frame"
            + " — DEFERRED until the part4 capture-to-capture re-aim (single-layer new path)");
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
        Vec3 cameraPos;
        float partialTick;
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
            if (captureSlots.size() >= MAX_CAPTURE_SLOTS) {
                censusArmDenied++;
                return false;
            }
            slot = new CaptureSlot();
            captureSlots.add(slot);
        }
        slot.portal = portal;
        slot.modelView = new Matrix4f(modelView);
        slot.projection = new Matrix4f(projection);
        slot.cameraPos = CHelper.getCurrentCameraPos();
        slot.partialTick = RenderStates.getPartialTick();
        slot.layer = layer;
        armedCapture = slot;
        censusArmGranted++;
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
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate for the copy check */ }
            GL43C.glCopyImageSubData(
                srcColor, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                slot.colorTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
            GL43C.glCopyImageSubData(
                srcDepth, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                slot.depthTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
            // S6 leg-6 hardening: an unchecked copy failure leaves the capture texture UNWRITTEN
            // and the stamp's own clear-slate drain would swallow the queued error silently — the
            // stamp would then faithfully paint garbage. A copy failure is a mechanism break, not
            // a per-frame maybe.
            int copyErr = GL11.glGetError();
            if (copyErr != GL11.GL_NO_ERROR) {
                breakMechanism("capture glCopyImageSubData failed (0x"
                    + Integer.toHexString(copyErr) + ", srcColor=" + srcColor
                    + " srcDepth=" + srcDepth + " " + w + "x" + h + ")", null);
                return;
            }
            // 1Hz capture-content readback (log-only): one center pixel of the freshly-copied
            // capture. Makes "what does the capture HOLD" log-readable — black ⇒ unwritten or
            // cleared source side; scene-like ⇒ real content. The leg-6 magenta result proved the
            // WRITE path, so content is the open question and it must not need eyes to answer.
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastCaptureReadbackMs >= 1000) {
                lastCaptureReadbackMs = nowMs;
                try {
                    java.nio.FloatBuffer px = BufferUtils.createFloatBuffer(3);
                    GL45C.glGetTextureSubImage(
                        slot.colorTex, 0, w / 2, h / 2, 0, 1, 1, 1,
                        GL11.GL_RGB, GL11.GL_FLOAT, px);
                    LOGGER.info(
                        "[Seamless Portals] [IS5-PRE] capture center px rgb=({}, {}, {})"
                            + " layer={} readAlt={}",
                        String.format("%.4f", px.get(0)), String.format("%.4f", px.get(1)),
                        String.format("%.4f", px.get(2)), slot.layer, readAlt
                    );
                } catch (Throwable readbackErr) {
                    LOGGER.info("[Seamless Portals] [IS5-PRE] capture readback unavailable: {}",
                        readbackErr.toString());
                }
                while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* never poison the pass */ }
            }
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
            censusCaptures++;
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
    private static long lastCaptureReadbackMs = 0;

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
        stampConsumedThisFrame = false;
        frameArmed = false;
        speculativeRendersThisFrame = 0;
        speculativeSkipsThisFrame = 0;
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
        if (mechanismBroken) return;
        if (stampConsumedThisFrame) return; // consume-once-per-frame (judge-mandated)
        // ---- THE TRIPLE DISCRIMINATOR (design §1.3, judge-mandated — do NOT simplify) --------
        // beginRenderer.renderAll (inside beginLevelRendering) and deferredRenderer.renderAll
        // (inside beginTranslucents) invoke this SAME method; a stamp there is wiped by the
        // gbuffer pass and the mechanism dies silently.
        if (PortalRendering.isRendering()) return; // never inside a nested view
        try {
            Object mainPipeline = Iris.getPipelineManager().getPipelineNullable();
            if (!(mainPipeline instanceof IrisRenderingPipeline)) return;
            Object mainCompositeRenderer = fPipelineCompositeRenderer.get(mainPipeline);
            if (mainCompositeRenderer != compositeRenderer) {
                // Begin/prepare/deferred instance, or a foreign pipeline's composite. MUST NOT
                // consume the slots: the main render's own beginRenderer.renderAll (and every
                // nested begin/deferred invocation) reaches this point AFTER captures exist and
                // BEFORE the main composite chain runs — the S6 smoke leg's adjudicated defect
                // (2026-08-04 02:37 log: capture geometry printed, then NEITHER the stamp line
                // NOR the never-stamped WARN — a mis-scoped finally here ate the slots silently).
                return;
            }
            stampConsumedThisFrame = true;
            try {
                runStampPass(
                    (IrisRenderingPipeline) mainPipeline, (CompositeRenderer) compositeRenderer);
            } finally {
                // Consumption is scoped to the MATCHED main-chain invocation only — success or
                // break, never on a discriminator mismatch (see the comment above).
                for (CaptureSlot s : captureSlots) s.pending = false;
                capturesPendingThisFrame = 0;
            }
        } catch (Throwable t) {
            breakMechanism("stamp discriminator/pass threw", t);
        }
    }

    private static boolean stampConsumedThisFrame = false;

    // =============================================================================================
    // S4b-part2 — THE STAMP PASS (design §1.3 / §3.6): paste every captured view into the main
    // chain's colortex0 (pass-0-READ side) + depthtex0 at renderAll HEAD, before any composite.
    // The draw is the mask's runMask pattern; the depth semantics are the SHIPPED stamp's
    // (nocap vsh + per-fragment floor fsh — IS5-XCUT: a per-vertex floor TILTS the interpolated
    // plane; gl_FragDepth = max(gl_FragCoord.z, 0.001) is a true clamp), declared compare
    // GEQUAL + depth WRITE, under the C4-SEAM depth clamp bracket.
    // =============================================================================================

    private static final String STAMP_VERTEX_SRC = """
        #version 330 core
        layout(location = 0) in vec3 Position;
        layout(location = 1) in vec4 Color;
        uniform mat4 u_combined;
        out vec4 vertexColor;
        void main() {
            gl_Position = u_combined * vec4(Position, 1.0);
            vertexColor = Color;
        }
        """;
    /** Fragment = the shipped portal_area_sample_floor.fsh semantics, verbatim constants:
     *  texelFetch at gl_FragCoord * vertexColor (the mesh tint carries -PdebugTintStamp magenta);
     *  u_solid=1 replaces the sample with the vertex colour (-PdebugStampSolid — keeps the
     *  StampCoverageProbe classifier levers working identically on the new path);
     *  gl_FragDepth = max(gl_FragCoord.z, 0.001) — the IS5-HAND floor, per fragment. */
    private static final String STAMP_FRAGMENT_SRC = """
        #version 330 core
        uniform sampler2D u_capture;
        uniform float u_solid;
        in vec4 vertexColor;
        out vec4 fragColor;
        void main() {
            vec4 sampled = vec4(texelFetch(u_capture, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);
            fragColor = mix(sampled * vertexColor, vertexColor, u_solid);
            gl_FragDepth = max(gl_FragCoord.z, 0.001);
        }
        """;

    private static int stampProgram = 0;
    private static int locCombined = -1;
    private static int locCapture = -1;
    private static int locSolid = -1;
    private static final FloatBuffer matBuf = BufferUtils.createFloatBuffer(16);

    private static boolean ensureStampProgram() {
        if (stampProgram != 0) return true;
        int vs = GL20C.glCreateShader(GL20C.GL_VERTEX_SHADER);
        GL20C.glShaderSource(vs, STAMP_VERTEX_SRC);
        GL20C.glCompileShader(vs);
        int fs = GL20C.glCreateShader(GL20C.GL_FRAGMENT_SHADER);
        GL20C.glShaderSource(fs, STAMP_FRAGMENT_SRC);
        GL20C.glCompileShader(fs);
        if (GL20C.glGetShaderi(vs, GL20C.GL_COMPILE_STATUS) == GL11.GL_FALSE
            || GL20C.glGetShaderi(fs, GL20C.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            breakMechanism("stamp shader compile failed: vs='"
                + GL20C.glGetShaderInfoLog(vs) + "' fs='" + GL20C.glGetShaderInfoLog(fs) + "'", null);
            GL20C.glDeleteShader(vs);
            GL20C.glDeleteShader(fs);
            return false;
        }
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == GL11.GL_FALSE) {
            breakMechanism("stamp program link failed: " + GL20C.glGetProgramInfoLog(prog), null);
            GL20C.glDeleteProgram(prog);
            return false;
        }
        stampProgram = prog;
        locCombined = GL20C.glGetUniformLocation(prog, "u_combined");
        locCapture = GL20C.glGetUniformLocation(prog, "u_capture");
        locSolid = GL20C.glGetUniformLocation(prog, "u_solid");
        return true;
    }

    /** Stamp-target FBO cache: keyed on (colorTex, depthTex); nuked when either id changes
     *  (resize/reload allocate new textures, so ids are the natural key). */
    private static GlFramebuffer stampFbo = null;
    private static int stampFboColorTex = 0;
    private static int stampFboDepthTex = 0;

    private static GlFramebuffer ensureStampFbo(int colorTex, int depthTex) {
        if (stampFbo != null && stampFboColorTex == colorTex && stampFboDepthTex == depthTex) {
            return stampFbo;
        }
        if (stampFbo != null) {
            try { stampFbo.destroy(); } catch (Throwable ignored) {}
            stampFbo = null;
        }
        GlFramebuffer fbo = new GlFramebuffer();
        fbo.addColorAttachment(0, colorTex);
        // javap-pinned: addDepthAttachment takes a GpuTexture; the raw-id variant is the Bypass.
        fbo.addDepthAttachmentBypass(depthTex);
        fbo.drawBuffers(new int[]{0});
        stampFbo = fbo;
        stampFboColorTex = colorTex;
        stampFboDepthTex = depthTex;
        return fbo;
    }

    private static void runStampPass(
        IrisRenderingPipeline mainPipeline, CompositeRenderer mainCompositeRenderer
    ) throws IllegalAccessException {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate */ }
        Boolean writeAlt = resolvePassZeroReadsAlt((List<?>) fPasses.get(mainCompositeRenderer));
        if (writeAlt == null) {
            breakMechanism("no real composite pass 0 on the MAIN pipeline", null);
            return;
        }
        RenderTargets rts = (RenderTargets) fPipelineRenderTargets.get(mainPipeline);
        RenderTarget c0 = rts.get(0);
        if (c0 == null) {
            breakMechanism("MAIN colortex0 null at stamp", null);
            return;
        }
        int targetColor = writeAlt ? c0.getAltTexture() : c0.getMainTexture();
        int w = c0.getWidth();
        int h = c0.getHeight();
        GpuTexture depthGpu = rts.getDepthTexture();
        if (!(depthGpu instanceof GlTexture depthGl)) {
            breakMechanism("MAIN depthtex0 not a GlTexture at stamp", null);
            return;
        }
        if (!ensureStampProgram()) return;
        GlFramebuffer fbo = ensureStampFbo(targetColor, depthGl.glId());

        boolean cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean solid = IPGlobal.debugStampSolid;
        boolean tint = IPGlobal.debugTintStamp;
        Vec3 meshTint = tint ? new Vec3(1.0, 0.0, 1.0) : new Vec3(1.0, 1.0, 1.0);

        try {
            fbo.bind();
            GlStateManager._viewport(0, 0, w, h);
            GlStateManager._disableScissorTest();
            GlStateManager._disableBlend(0);
            GlStateManager._disableCull();
            // COLOR MASK, asserted explicitly — the S6 leg-4 defect. A HEAD-position draw is the
            // FIRST draw of the composite stage: nothing has re-established write state for it
            // (renderAll's own _colorMask(15) runs AFTER HEAD; the bloom mask mid-chain could
            // lean on each pass's setupState — we cannot). The previous frame's tail is the
            // query-only loop whose pipeline is deliberately colour/depth-write-free, so the
            // leftover mask was OFF: stamps drew nothing, zero GL errors, census all-green —
            // leg 4's persistent invisibility and leg 3's HUD-raced flashing in one mechanism.
            GlStateManager._colorMask(15);
            // Depth compare: LEQUAL — derived from the MEASURED buffer convention, not the
            // shipped stamp's declaration. S6 leg 2 (2026-08-04 02:47) proved the declared-GEQUAL
            // trap live: stamped=1/2 with zero GL errors while the window was COMPLETELY
            // INVISIBLE — draws issued, every window fragment depth-rejected (plane ~0.5 >= far
            // scene ~0.98 is false under the measured small-is-near buffer, 39/39
            // clipDepthMode=NEGATIVE_ONE_TO_ONE). The shipped stamp's GEQUAL goes through the
            // vanilla RenderPass abstraction, whose EXECUTED func demonstrably differs from the
            // declaration (OCCLUDER_RING_HANDOFF §6: "never design from the declaration") — raw
            // GL gets no such translation. Under small-is-near: plane <= far-scene passes (the
            // window paints), plane <= nearer-occluder fails (occlusion correct).
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._depthMask(true);
            if (!IPGlobal.debugNoStampDepthClamp) {
                CHelper.enableDepthClamp();
            }
            GlStateManager._glUseProgram(stampProgram);
            GL20C.glUniform1i(locCapture, 0);
            GL20C.glUniform1f(locSolid, solid ? 1.0f : 0.0f);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);

            // STAMP-TIME MATRICES (design §3.2 note): by renderAll HEAD, THIS frame's true
            // post-mulLocal passingModelView (the F1 driver's main-render fire is the last
            // writer before this point — judge-verified last-writer invariant) and the captured
            // projection are both fresh. The arm-time matrices in the slot are frame-start
            // values (unbobbed) — using them here would misregister the stamp by the bob offset.
            Matrix4f mainMv = IrisCompatOn262Renderer.ip_stampModelViewOrNull();
            Matrix4f mainProj = IrisCompatOn262Renderer.ip_stampProjectionOrNull();
            if (mainMv == null || mainProj == null) {
                breakMechanism("stamp-time matrices unavailable (renderer not live?)", null);
                return;
            }
            for (CaptureSlot slot : captureSlots) {
                if (!slot.pending || slot.layer != 0) continue;
                try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(
                    256 * DefaultVertexFormat.POSITION_COLOR.getVertexSize()
                )) {
                    MeshData mesh = ViewAreaRenderer.buildPortalViewAreaMesh(
                        meshTint, slot.portal, slot.cameraPos, slot.partialTick,
                        mainMv, byteBuffer
                    );
                    if (mesh == null) continue; // fully near-plane-clipped — skip, like the stamp
                    int vertexCount;
                    GpuBufferSlice vertexSlice;
                    try (mesh) {
                        vertexCount = mesh.drawState().vertexCount();
                        vertexSlice = SecondaryWorldRenderCore.registerFrameTransientUbo(
                            RenderSystem.getDevice().createBuffer(
                                () -> "seamlessportals_is5pre_stamp_mesh",
                                GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer()
                            )
                        );
                    }
                    GlStateManager._bindTexture(slot.colorTex);
                    ((GlDevice) ((GpuDeviceAccessor) RenderSystem.getDevice()).getBackend())
                        .vertexArrayCache().bindVertexArray(
                            new VertexFormat[]{DefaultVertexFormat.POSITION_COLOR},
                            new GpuBufferSlice[]{vertexSlice},
                            null
                        );
                    matBuf.clear();
                    new Matrix4f(mainProj).mul(mainMv).get(matBuf);
                    GL20C.glUniformMatrix4fv(locCombined, false, matBuf);
                    GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
                }
            }
        } finally {
            if (!IPGlobal.debugNoStampDepthClamp) {
                CHelper.disableDepthClamp();
            }
            // Depth state back to the composite-time default (composite FBOs carry no depth
            // attachment — V3 — but the tracked cache must not think depth is still on).
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._disableDepthTest();
            // VAO back through the same cache iris rides (the mask's restore idiom); renderAll
            // re-binds the quad after HEAD anyway — belt for exception paths.
            FullScreenQuadRenderer.INSTANCE.bind();
            if (cullWasEnabled) {
                GlStateManager._enableCull();
            }
        }
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            breakMechanism("stamp pass left GL error 0x" + Integer.toHexString(err), null);
        } else {
            censusStampPasses++;
            censusStampedViews += capturesPendingThisFrame;
            noteStampPass(writeAlt);
        }
    }

    private static String lastStampAnnouncement = null;

    private static void noteStampPass(boolean writeAlt) {
        String a = "stamped=" + capturesPendingThisFrame
            + " writeAlt=" + writeAlt
            + " solid=" + IPGlobal.debugStampSolid + " tint=" + IPGlobal.debugTintStamp;
        if (a.equals(lastStampAnnouncement)) return;
        lastStampAnnouncement = a;
        LOGGER.info("[Seamless Portals] [IS5-PRE] stamp pass ran at main renderAll HEAD — {}", a);
    }
}
