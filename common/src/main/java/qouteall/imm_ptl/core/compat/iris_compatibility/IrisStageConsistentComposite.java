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
import com.warwa.seamlessportals.render.PerfTimers;
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
    /** IS5-RESTAMP: {@code CompositeRenderer$Pass.program} — the measurement's program-handle
     *  source ({@code null} on a ComputeOnlyPass = the skip discriminator; the DestPrevCamera
     *  recipe). {@code Program.getProgramId()} itself is public — no method reflection. */
    private static Field fPassProgram;
    /** IS5-RESTAMP/SG: {@code CompositeRenderer$Pass.drawBuffers} (int[]) — the anchor pass's
     *  image target ({@code drawBuffers[0]}) for the SG boundary capture/inject. */
    private static Field fPassDrawBuffers;
    /** IS5-WASH (OPTIONAL — bound in its own try like the mask's): {@code
     *  CompositeRenderer$Pass.mipmappedBuffers} (ImmutableSet&lt;Integer&gt;) — the bloom
     *  GATHERER is the pass that mipmap-regens colortex0 (the BLOOMMB-proven discriminator).
     *  null ⇒ the wash bracket is unavailable (meas line says so; washout persists, disclosed). */
    private static Field fPassMipmapped;
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
            fPassProgram = passClass.getDeclaredField("program");
            fPassProgram.setAccessible(true);
            fPassDrawBuffers = passClass.getDeclaredField("drawBuffers");
            fPassDrawBuffers.setAccessible(true);
            try {
                fPassMipmapped = passClass.getDeclaredField("mipmappedBuffers");
                fPassMipmapped.setAccessible(true);
            } catch (Throwable optional) {
                fPassMipmapped = null; // IS5-WASH degrades to unavailable, never fatal
            }
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
                    + " armG/D={}/{} capt={} cPost={} stampPass={} views={} nest={} hys={}"
                    + " rst={} rstOrph={} sgC/I/F={}/{}/{}",
                censusArmedFrames, censusConsumeTrue, censusConsumeFalse,
                censusSpecRendered, censusSpecSkipped, censusArmGranted, censusArmDenied,
                censusCaptures, censusCapturesPost, censusStampPasses, censusStampedViews,
                censusNestedStamps, censusHysteresisRenders,
                censusRst, censusRstOrph, censusSgC, censusSgI, censusSgF);
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
            censusNestedStamps = 0;
            censusHysteresisRenders = 0;
            censusCapturesPost = 0;
            censusRst = 0;
            censusRstOrph = 0;
            censusSgC = 0;
            censusSgI = 0;
            censusSgF = 0;
            censusWashB = 0;
            censusWashR = 0;
            censusFwB = 0;
            censusFwF = 0;
        }
        return true;
    }

    private static int censusArmedFrames, censusConsumeTrue, censusConsumeFalse,
        censusSpecRendered, censusSpecSkipped, censusArmGranted, censusArmDenied,
        censusCaptures, censusStampPasses, censusStampedViews, censusNestedStamps,
        censusHysteresisRenders, censusCapturesPost;
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
    // IS5-BLINK (2026-08-10): the one-frame visibility-dropout detector + hysteresis fix.
    // The new path consumes LAST frame's query (structural: views render at frame start,
    // before any depth exists), so a single zero-sample query — occlusion-edge noise, jitter,
    // a query-skipped frame — is consumed as a confident "not visible" and the window goes
    // UNSTAMPED for one frame: raw terrain where the dest should be (the user's flicker,
    // "even when far away sometimes"; the old path decides same-frame and cannot blink).
    // Detector: log T→F→T transitions with gap ≤2 (reads the RAW query values — independent
    // of the fix, so one leg carries both). Fix: invisible only after 2 consecutive FALSE;
    // a single FALSE renders on credit (census hys=). -PdisableQueryHysteresis = B direction.
    private static final java.util.WeakHashMap<Portal, int[]> visBlinkState =
        new java.util.WeakHashMap<>();
    private static long blinkLogSecond = 0;
    private static int blinkLogsThisSecond = 0;

    public static boolean consumeVisibilityForArmedFrame(Portal portal) {
        // IS5-ARRIVE tp-frame arrival classification (design §2.3, judge-folded): computed ONCE,
        // BEFORE the query consume — same-dim crossings have no query wipe, so the reverse portal
        // can arrive KNOWN (or hysteresis-credited) and return before the XFLICK block; the mark
        // is about arrival GEOMETRY, not query state (judge B required-change 1). Layer 0 only
        // (nested lookups never took the XFLICK path either). The classification feeds both the
        // sideways MARK (suspension withhold, read by shouldSuspendInnerClipForCrossing) and the
        // XFLICK skip decision below. Shipped cells preserved exactly: throw ⇒ dPl=MAX ⇒ the
        // render path (C3); camEnt-null ⇒ readable=false ⇒ the skip cell.
        boolean tpFrame =
            qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.isTeleportingFrame;
        double tpDPl = Double.MAX_VALUE;
        double tpDot = 0;
        boolean tpReadable = false;
        boolean tpSideways = false;
        if (tpFrame && PortalRendering.getPortalLayer() == 0) {
            try {
                Vec3 camPos = CHelper.getCurrentCameraPos();
                tpDPl = portal.getDistanceToNearestPointInPortal(camPos);
                // 26.2: Minecraft.cameraEntity FIELD is gone; only getCameraEntity() remains.
                var camEnt = net.minecraft.client.Minecraft.getInstance().getCameraEntity();
                if (camEnt != null) {
                    Vec3 look = camEnt.getViewVector(RenderStates.getPartialTick());
                    tpDot = look.dot(portal.getNormal());
                    tpReadable = true;
                }
            } catch (Throwable t) {
                tpDPl = Double.MAX_VALUE; // unreadable geometry: keep the shipped behavior
                tpReadable = false;
            }
            tpSideways = !IPGlobal.disableSeamArrivalScope
                && tpReadable && tpDPl < 1.0 && Math.abs(tpDot) <= 0.2;
            if (tpSideways) {
                // Mark regardless of query state — a marked portal that ends up NOT rendered
                // (query-false skip, spec-cap skip) leaves the mark unread: harmless.
                com.warwa.seamlessportals.render.SeamArrivalScope.markSidewaysArrival(portal);
            }
            if (IPGlobal.is5CrossingTrace && traceWindowFrames > 0) {
                char cls = !tpReadable ? 'u' : (tpDot < -0.2 ? 'b' : (tpSideways ? 's' : 'f'));
                traceSlots.append(String.format("consume:P%03d cls=%c%s dPl=%.2f; ",
                    System.identityHashCode(portal) % 1000, cls,
                    tpSideways ? "+mark" : "", tpDPl));
            }
        }
        Boolean known = qouteall.imm_ptl.core.portal.PortalRenderInfo
            .consumeLastFrameVisibility(portal);
        if (known != null) {
            int[] st = visBlinkState.computeIfAbsent(portal, k -> new int[]{0});
            if (known) {
                if (st[0] >= 1 && st[0] <= 2) {
                    long sec = System.currentTimeMillis() / 1000L;
                    if (sec != blinkLogSecond) { blinkLogSecond = sec; blinkLogsThisSecond = 0; }
                    if (blinkLogsThisSecond < 5) {
                        blinkLogsThisSecond++;
                        double dPl;
                        try {
                            dPl = portal.getDistanceToNearestPointInPortal(
                                CHelper.getCurrentCameraPos());
                        } catch (Throwable t) { dPl = -1; }
                        LOGGER.info("[Seamless Portals] [IS5-BLINK] portal P{} query blinked"
                                + " FALSE for {} frame(s) then TRUE (dPl={}) — the one-frame"
                                + " window-dropout signature{}",
                            System.identityHashCode(portal) % 1000, st[0],
                            String.format("%.2f", dPl),
                            IPGlobal.disableQueryHysteresis
                                ? " (hysteresis DISABLED — this blink was visible)"
                                : " (hysteresis rendered frame 1 on credit)");
                    }
                }
                st[0] = 0;
                censusConsumeTrue++;
                return true;
            }
            st[0]++;
            censusConsumeFalse++;
            if (!IPGlobal.disableQueryHysteresis && st[0] == 1) {
                // Render on credit: one extra view for one frame per true-occlusion event.
                censusHysteresisRenders++;
                return true;
            }
            return false;
        }
        // §3.3 (PART4): the speculative cap counts LAYER 0 ONLY. Nested lookups are ALWAYS
        // unknown — the layer-0 loop consumes each portal's query once per frame, and the query
        // ISSUE cannot run at the nested slot on the new path (post-cancel FBO state) — so
        // counting them would starve the cap every recursion frame. Nested render-if-unknown is
        // budget-bounded downstream instead (irisMaxDestRenders + effectiveIrisMaxPortalLayer).
        if (PortalRendering.getPortalLayer() > 0) {
            return true;
        }
        // IS5-XFLICK (2026-08-10, XTRACE-adjudicated): on the TELEPORT frame the dim change has
        // wiped query history, so the just-exited reverse portal arrives here UNKNOWN and the
        // speculative render paints it from a camera sitting ON its plane (trace: tp=true
        // specR=1 dPl=0.00-0.11 on all 29 crossings; dCam=0 everywhere — the arm/stamp camera
        // hypothesis is refuted). Under the crossing-window clip suspension that is one
        // full-screen frame of the SOURCE world — the user's "flicker of wrong dest". The old
        // path's post-main depth-tested stamp rejects the same paint, which is why the B leg is
        // clean. Fix: on teleport frames, an unknown portal NEAR the camera (the reverse portal;
        // <1 block) is skip-if-unknown — it is behind the player and pops in next frame (the §5
        // bounded pop-in class). DISTANT unknowns still render so arrival-frame windows ahead do
        // not pop. -PdisableTeleportSpecSkip reproduces the flicker on command.
        // IS5-XFLICK (round 3, IS5-ARRIVE-folded): per-direction arrival disposition using the
        // hoisted classification above. FORWARD (dot > +0.2) keeps the shipped skip (its mesh is
        // null anyway — facts-derived); BACKWARD (dot < -0.2) keeps the shipped render UNDER
        // suspension (user-clean; V1 there is the measured-band geometry); SIDEWAYS (|dot| ≤ 0.2,
        // marked above) falls through to RENDER — its dest pass runs under the ARMED V1 clip via
        // the suspension withhold, giving the correct half-split instead of the round-2 hole or
        // the round-1 wrong-content paint. camEnt-null/unreadable keeps the shipped skip cell.
        // -PdisableTeleportSpecSkip = render-everything (the original flicker reproduction);
        // -PdisableSeamArrivalScope = shipped round-2 exactly (sideways hole reproduction).
        if (!IPGlobal.disableTeleportSpecSkip && tpFrame && !tpSideways) {
            boolean clearlyLookingIntoFace = tpReadable && tpDot < -0.2;
            if (tpDPl < 1.0 && !clearlyLookingIntoFace) {
                speculativeSkipsThisFrame++;
                censusSpecSkipped++;
                noteTeleportSpecSkipOnce();
                return false;
            }
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

    private static boolean teleportSpecSkipNoted = false;

    /** IS5-XFLICK once-note (monotonic latch — the bouncing-key rule): the first arrival-frame
     *  near-plane speculative skip announces the fix is live and consuming. */
    private static void noteTeleportSpecSkipOnce() {
        if (!teleportSpecSkipNoted) {
            teleportSpecSkipNoted = true;
            LOGGER.info("[Seamless Portals] [IS5-XFLICK] teleport-frame near-plane speculative"
                + " skip LIVE (the crossing wrong-dest flicker fix; first consume this session;"
                + " reproduce the flicker: -PdisableTeleportSpecSkip)");
        }
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

    // =============================================================================================
    // §3.8 FALLBACK — the pre-registered check FAILED (ghost-double leg, 2026-08-05): the shared
    // notifier's CameraPositionTracker is saved/restored around the frame-start loop, so the MAIN
    // frame's TAA/MB reproject with the true main camera delta instead of cameraOffset ≈ the
    // portal offset (the "terrain ghost double containing a faint magenta window" — the previous
    // frame reprojected at the portal offset, position-driven, portal-visible-gated). The
    // draw-time DestPrevCamera correction did NOT cover the main chain here: its nearest-match
    // "writes back what iris already holds" — the judged caveat, now measured. Tracker located by
    // LISTENER-CAPTURE SCAN (the tracker is a lambda-captured local; its five Vector3d fields are
    // javap-pinned). Rotated-portal caveat: prev MATRICES are not bracketed — for non-rotated
    // portals the rotation part is unpoisoned; revisit if a rotated-portal ghost appears.
    // =============================================================================================

    private static Object trackedNotifier = null;
    private static Object cameraTracker = null;
    private static Field[] trackerVecFields = null;
    private static final org.joml.Vector3d[] trackerSaved =
        {new org.joml.Vector3d(), new org.joml.Vector3d(), new org.joml.Vector3d(),
         new org.joml.Vector3d(), new org.joml.Vector3d()};
    private static boolean trackerBracketNoted = false;

    private static boolean locateCameraTracker() {
        try {
            Object pipeline = Iris.getPipelineManager().getPipelineNullable();
            if (!(pipeline instanceof IrisRenderingPipeline irp)) return false;
            Object notifier = irp.getFrameUpdateNotifier();
            if (notifier == trackedNotifier && cameraTracker != null) return true;
            trackedNotifier = notifier;
            cameraTracker = null;
            trackerVecFields = null;
            Field fListeners = notifier.getClass().getDeclaredField("listeners");
            fListeners.setAccessible(true);
            for (Object listener : (List<?>) fListeners.get(notifier)) {
                for (Field f : listener.getClass().getDeclaredFields()) {
                    if (f.getType().getName().endsWith("CameraPositionTracker")) {
                        f.setAccessible(true);
                        Object tracker = f.get(listener);
                        if (tracker == null) continue;
                        Field[] vecs = new Field[5];
                        String[] names = {"previousCameraPosition", "currentCameraPosition",
                            "previousCameraPositionUnshifted", "currentCameraPositionUnshifted",
                            "shift"};
                        for (int i = 0; i < 5; i++) {
                            vecs[i] = tracker.getClass().getDeclaredField(names[i]);
                            vecs[i].setAccessible(true);
                        }
                        cameraTracker = tracker;
                        trackerVecFields = vecs;
                        if (!trackerBracketNoted) {
                            trackerBracketNoted = true;
                            LOGGER.info("[Seamless Portals] [IS5-PRE] camera tracker located via"
                                + " listener-capture scan — the §3.8 fallback bracket is LIVE");
                        }
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            noteAuxDropOnce("camera tracker scan failed: " + t);
        }
        return cameraTracker != null;
    }

    /** Save the tracker's five vectors before the frame-start loop. Returns false (no restore
     *  needed) when the tracker cannot be located — the ghost-double returns in that case, which
     *  the once-only scan-failed note makes attributable. */
    public static boolean cameraTrackerSave() {
        if (!PATH_ACTIVE || mechanismBroken) return false;
        if (!locateCameraTracker()) return false;
        try {
            for (int i = 0; i < 5; i++) {
                trackerSaved[i].set((org.joml.Vector3d) trackerVecFields[i].get(cameraTracker));
            }
            return true;
        } catch (Throwable t) {
            noteAuxDropOnce("camera tracker save failed: " + t);
            return false;
        }
    }

    public static void cameraTrackerRestore() {
        try {
            for (int i = 0; i < 5; i++) {
                ((org.joml.Vector3d) trackerVecFields[i].get(cameraTracker)).set(trackerSaved[i]);
            }
        } catch (Throwable t) {
            noteAuxDropOnce("camera tracker restore failed: " + t);
        }
    }

    // =============================================================================================
    // PART4 — nested capture-to-capture re-aim (design §1 Recursion; landed 2026-08-05).
    // On an armed frame the parent view's content lives in its OWN capture slot (filled by the
    // cancelled finalize BEFORE the tail dispatch runs), which nested renders never touch — so
    // the old nested trio collapses: the snapshot has nothing to protect, the blit-back has
    // nothing to deliver, and the stamp re-aims to child-capture → PARENT-capture: unfiltered
    // on both sides, exact at every depth. Ordering is inherent: fork (c) is post-pop, so
    // innermost stamps complete before their parent's own stamp consumes them.
    // =============================================================================================

    /** The armed-view stack: pushed at {@link #armCaptureForView}, popped at
     *  {@link #completeArmedView} (doRenderPortal fork (c)). Mirrors the portal-layer stack;
     *  cleared every frame at {@link #beginFrame} so a throw can never poison the next frame. */
    private static final java.util.ArrayDeque<CaptureSlot> viewSlotStack =
        new java.util.ArrayDeque<>();

    private static int nestedStampDeepestNoted = 0;
    private static int nestedStampAuxNoted = -1;

    /**
     * Fork (c) for EVERY armed view, at doRenderPortal's post-pop point. Pops this view's slot;
     * a layer-0 slot stays pending for the main renderAll HEAD stamp, a nested slot is stamped
     * into the PARENT view's capture buffer here and consumed.
     */
    public static void completeArmedView() {
        CaptureSlot child = viewSlotStack.pollLast();
        if (child == null || mechanismBroken) return;
        if (child.layer == 0) return; // the stamp pass consumes it at main renderAll HEAD
        CaptureSlot parent = viewSlotStack.peekLast();
        if (parent == null || !parent.pending || !child.pending) {
            // A failed capture on either side: the nested view is lost for this frame. The
            // capture path already broke loudly if it was a mechanism failure — here we only
            // make sure a half-taken child slot cannot leak into the main stamp's accounting.
            if (child.pending) {
                child.pending = false;
                capturesPendingThisFrame--;
            }
            return;
        }
        runNestedStamp(child, parent);
        // IS5-XDIM-SG R11-MASK: when the parent is SG-captured, the nested stamp above wrote
        // alpha 0 at the child's pixels (u_zeroAlpha) — the inject discards exactly there, so
        // the child keeps its source-graded look and the parent face still gets the clean
        // single-graded inject (the near-portal dim-window fix).
        child.pending = false;
        capturesPendingThisFrame--;
    }

    /**
     * The capture-to-capture stamp: draw the child portal's view-area mesh into the PARENT's
     * capture buffer, sampling the child's capture. Same program + depth semantics as the main
     * stamp (LEQUAL + write + the per-fragment floor, C4-SEAM clamp bracket, every write-enable
     * asserted — a mid-loop draw has no upstream re-establisher any more than a HEAD one does).
     * The mesh matrices are the slot's ARM-TIME values, which for a nested view are exactly the
     * old-path stamp's inputs (modelView is the doRenderPortal argument; the projection is
     * unscaled pre-push == post-pop; the camera context at arm time IS the parent dest view).
     * NO FBO caching (two latches paid for the rule) — build and destroy per call.
     */
    private static void runNestedStamp(CaptureSlot child, CaptureSlot parent) {
        if (child.w != parent.w || child.h != parent.h) {
            noteAuxDropOnce("nested stamp size mismatch (child " + child.w + "x" + child.h
                + " vs parent " + parent.w + "x" + parent.h + ") — view skipped");
            return;
        }
        if (!ensureStampProgram()) return;
        long nestedT0 = System.nanoTime();
        try {
            runNestedStampBody(child, parent);
        } finally {
            PerfTimers.add("is5.nestedStamp", System.nanoTime() - nestedT0);
        }
    }

    private static void runNestedStampBody(CaptureSlot child, CaptureSlot parent) {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate */ }
        boolean cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean solid = IPGlobal.debugStampSolid;
        Vec3 meshTint = IPGlobal.debugTintStamp ? new Vec3(1.0, 0.0, 1.0) : new Vec3(1.0, 1.0, 1.0);
        // Aux (materialMask) rides along only when BOTH sides have a valid aux this frame —
        // out1 maps to drawBuffers[1]; out2 (history) has no attachment here and is dropped
        // (history is a main-chain concern; a capture buffer IS current content).
        boolean aux = child.auxValid.length > 0 && child.auxValid[0]
            && parent.auxValid.length > 0 && parent.auxValid[0] && parent.auxTex[0] != 0;
        GlFramebuffer fbo = new GlFramebuffer();
        fbo.addColorAttachment(0, parent.colorTex);
        if (aux) {
            fbo.addColorAttachment(1, parent.auxTex[0]);
        }
        fbo.addDepthAttachmentBypass(parent.depthTex);
        fbo.drawBuffers(aux ? new int[]{0, 1} : new int[]{0});
        try {
            fbo.bind();
            GlStateManager._viewport(0, 0, parent.w, parent.h);
            GlStateManager._disableScissorTest();
            GlStateManager._disableBlend(0);
            GlStateManager._disableCull();
            GlStateManager._colorMask(15);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._depthMask(true);
            if (!IPGlobal.debugNoStampDepthClamp) {
                CHelper.enableDepthClamp();
            }
            GlStateManager._glUseProgram(stampProgram);
            GL20C.glUniform1i(locCapture, 0);
            GL20C.glUniform1i(locCaptureAux, 1);
            GL20C.glUniform1f(locSolid, solid ? 1.0f : 0.0f);
            // R11-MASK: a nested stamp into an SG-captured parent writes alpha 0 at exactly
            // its pixels; the SG inject discards there (source-graded child content survives —
            // the near-portal dim-window fix). Asserted per site.
            GL20C.glUniform1f(locZeroAlpha, parent.sgCaptured ? 1.0f : 0.0f);
            GL20C.glUniform1f(locFadeW, 1.0f); // FARFADE F10: asserted per site
            GL20C.glUniform1i(locPrevGraded, 5);
            if (aux) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE1);
                GlStateManager._bindTexture(child.auxTex[0]);
            }
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(child.colorTex);
            try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(
                256 * DefaultVertexFormat.POSITION_COLOR.getVertexSize()
            )) {
                MeshData mesh = ViewAreaRenderer.buildPortalViewAreaMesh(
                    meshTint, child.portal, child.cameraPos, child.partialTick,
                    child.modelView, byteBuffer
                );
                if (mesh == null) return; // fully near-plane-clipped
                int vertexCount;
                GpuBufferSlice vertexSlice;
                try (mesh) {
                    vertexCount = mesh.drawState().vertexCount();
                    vertexSlice = SecondaryWorldRenderCore.registerFrameTransientUbo(
                        RenderSystem.getDevice().createBuffer(
                            () -> "seamlessportals_is5pre_nested_stamp_mesh",
                            GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer()
                        )
                    );
                }
                ((GlDevice) ((GpuDeviceAccessor) RenderSystem.getDevice()).getBackend())
                    .vertexArrayCache().bindVertexArray(
                        new VertexFormat[]{DefaultVertexFormat.POSITION_COLOR},
                        new GpuBufferSlice[]{vertexSlice},
                        null
                    );
                matBuf.clear();
                new Matrix4f(child.projection).mul(child.modelView).get(matBuf);
                GL20C.glUniformMatrix4fv(locCombined, false, matBuf);
                GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
                // IS5-FARFADE (§5 F7): a PRE-captured parent needs the child in preColorTex
                // too, or an A-B-A corridor at d>D0 loses the sub-window (the blend keeps
                // the graded parent BACKDROP at discarded pixels — an R11-class regression).
                // Same VAO/uniforms/depth semantics, color-only re-attach. Disclosed: a
                // cross-dim child stamps SG-class content into the scene-referred PRE tex
                // (the inverse nested-rim class). u_zeroAlpha's write is inert on an
                // alpha-less PRE format and never read from it (§5 F6).
                if (parent.preCaptured && parent.preColorTex != 0
                    && parent.preW == parent.w && parent.preH == parent.h) {
                    GlFramebuffer preFbo = new GlFramebuffer();
                    preFbo.addColorAttachment(0, parent.preColorTex);
                    preFbo.addDepthAttachmentBypass(parent.depthTex);
                    preFbo.drawBuffers(new int[]{0});
                    try {
                        preFbo.bind();
                        GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
                    } finally {
                        try { preFbo.destroy(); } catch (Throwable ignored) {}
                    }
                }
            }
        } finally {
            if (!IPGlobal.debugNoStampDepthClamp) {
                CHelper.disableDepthClamp();
            }
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._disableDepthTest();
            if (cullWasEnabled) {
                GlStateManager._enableCull();
            }
            try { fbo.destroy(); } catch (Throwable ignored) {}
        }
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            breakMechanism("nested stamp left GL error 0x" + Integer.toHexString(err), null);
        } else {
            censusNestedStamps++;
            // Content-key on STATE, not the per-stamp layer: a recursion corridor stamps
            // layers 3→2→1 EVERY FRAME, so keying on the current layer re-emits three times a
            // frame (the stamped=1↔2 trap re-walked live, 2026-08-05 — ~180 render-thread log
            // lines/sec). The DEEPEST-layer latch is monotonic: a handful of emissions per
            // session, each one a real state change; per-frame volume lives in the census nest=.
            if (child.layer > nestedStampDeepestNoted
                || (aux ? 1 : 0) != nestedStampAuxNoted) {
                nestedStampDeepestNoted = Math.max(nestedStampDeepestNoted, child.layer);
                nestedStampAuxNoted = aux ? 1 : 0;
                LOGGER.info("[Seamless Portals] [IS5-PRE] part4 nested capture-to-capture stamp"
                    + " LIVE — deepest child layer so far={} aux={}",
                    nestedStampDeepestNoted, aux);
            }
        }
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
        /** PART5 aux capture (one per {@link #AUX_TARGETS} entry). Textures are POOLED
         *  (auxTex != 0 persists across frames); auxValid marks whether THIS frame's copy
         *  succeeded — the stamp writes aux only when valid. */
        int[] auxTex = new int[AUX_TARGETS.length];
        int[] auxFmt = new int[AUX_TARGETS.length];
        int[] auxW = new int[AUX_TARGETS.length];
        int[] auxH = new int[AUX_TARGETS.length];
        boolean[] auxValid = new boolean[AUX_TARGETS.length];
        int w = -1, h = -1, colorFmt = 0, depthFmt = 0;
        boolean pending = false;
        // IS5-XDIM: POST_FINAL mode — this view's dest chain runs to completion (composites +
        // final) and the capture reads MC mainRT at the finalize TAIL instead of colortex0 at
        // the renderAll INVOKE. Decided at ARM time from mod-owned dims; PRE views are
        // byte-identical to the shipped path.
        boolean postFinalMode = false;
        // IS5-FARFADE (IS5_FARFADE_DESIGN.md §5 F4/F5): fade weight computed at arm time
        // (1 = pure SG = the shipped byte path; <1 engages the PRE copy + anchor blend).
        // preColorTex = the pend-time dest colortex0 copy (PRE class), pooled color-only,
        // allocated only when fadeW < 1; preCaptured marks THIS frame's copy and clears with
        // the pending lifecycle.
        float fadeW = 1f;
        boolean preCaptured = false;
        int preColorTex = 0;
        int preW = -1, preH = -1, preFmt = 0;
        // IS5-XDIM-SG (IS5_RESTAMP_DESIGN.md §2.2): this frame's DEST-boundary capture landed —
        // colorTex/depthTex hold the dest anchor-boundary image (single-graded, pre-AA); the
        // TAIL then skips its mainRT copy and the source-anchor inject fires. Cleared at
        // beginFrame with pending.
        boolean sgCaptured = false;
        // ⟦J⟧ R11 lineage: a whole-slot sgNestedContent inject-skip lived here for one commit
        // and WAS the user-reported near-portal dim window (nest>0 collapsed sgI in the census).
        // Replaced by the per-pixel R11-MASK: capture alpha 1-initialized at SG capture, zeroed
        // by nested stamps, discarded by the inject — child pixels keep their source-graded
        // anchor-time content, the parent face gets the clean inject.
        Portal portal;
        Matrix4f modelView;
        Matrix4f projection;
        Vec3 cameraPos;
        float partialTick;
        int layer;
    }

    /**
     * PART5 (S6 leg-8 residuals): aux colour targets captured from the dest chain and stamped
     * alongside colortex0, so the MAIN chain's composites read DEST per-pixel data inside the
     * window instead of source-gbuffer leftovers. PACK-TUNED, MEASURED for Complementary r5.8.1:
     * `composite.glsl:96` reads the materialMask from colortex6.g — the carrier of the
     * water-wobble residual (source water behind the portal wobbling the dest view) and part of
     * the lighting keying. Per-target read/write parity comes from the SAME
     * `stageReadsFromAlt` set as colortex0 (contains(t)).
     */
    private static final int[] AUX_TARGETS = {6};

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
        // IS5-WASH ⟦J⟧ B1: a break BETWEEN the blackout and the restore must not strand a
        // blacked-out c0 into this frame's bloom-apply — best-effort full copy-back from the
        // still-valid scratch (strictly stronger than the mesh restore; nothing else wrote c0
        // between the save and any reachable failure point). Never throws out of here.
        RestampArm strandedArm = restampArm;
        if (strandedArm != null && strandedArm.washSaveDone && !strandedArm.washRestoreDone
            && washScratchId != 0 && strandedArm.washTexId != 0) {
            try {
                GL43C.glCopyImageSubData(
                    washScratchId, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                    strandedArm.washTexId, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                    strandedArm.w, strandedArm.h, 1);
            } catch (Throwable ignored) {
            }
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* best-effort, never poison */ }
        }
        // IS5-RESTAMP ⟦J⟧: a broken frame must neither fire a stale boundary draw nor read as
        // a next-frame orphan; the measurement cache dies with the mechanism (weak keys already
        // cover pipeline death — this covers the mechanism's own death).
        restampArm = null;
        restampMeasCache.clear();
        postPendingDestRenderer = null;
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
        // IS5-XDIM mode decision (design §shape): POST_FINAL only for CROSS-DIM views (the dest
        // chain owns the dimension's pack look — the missing nether storm), at most ONE per dest
        // dimension per frame (two views into one dest dim share a pipeline instance; a second
        // renderAll would write its TAA history twice with two cameras — the judge's mutual-
        // shimmer fold; the second window falls back PRE = storm absent there, census-visible).
        // Same-dim views (viewDim == the player's dim, incl. A→B→A nested layers) stay PRE
        // byte-identically — running composites on the MAIN pipeline instance mid-frame would
        // double-run history writes on shared targets.
        slot.postFinalMode = false;
        if (IPGlobal.crossDimDestChain && !mechanismBroken) {
            var mcLevel = net.minecraft.client.Minecraft.getInstance().level;
            var destDim = portal.getDestDim();
            if (mcLevel != null && destDim != null && destDim != mcLevel.dimension()
                && postDimsThisFrame.add(destDim)) {
                slot.postFinalMode = true;
            }
        }
        // IS5-FARFADE (§5 F1/F4): fade weight from the camera↔NEAREST-POINT-in-portal
        // distance (never origin — a wide portal would fade at point-blank range). Only
        // POST/SG views fade; same-dim PRE stays 1. Any failure ⇒ 1 = the shipped path.
        slot.fadeW = 1f;
        slot.preCaptured = false;
        if (slot.postFinalMode && !IPGlobal.disableFarFade) {
            try {
                double d = portal.getDistanceToNearestPointInPortal(slot.cameraPos);
                double d0 = IPGlobal.farFadeD0;
                double d1 = Math.max(IPGlobal.farFadeD1, d0 + 0.001); // D1>D0 or a step pops
                double t = Math.min(1.0, Math.max(0.0, (d - d0) / (d1 - d0)));
                t = t * t * (3.0 - 2.0 * t); // smoothstep — continuous in d (C-F1)
                slot.fadeW = (float) (IPGlobal.farFadeWMin
                    + (1.0 - IPGlobal.farFadeWMin) * (1.0 - t));
            } catch (Throwable ignored) {
                slot.fadeW = 1f;
            }
        }
        armedCapture = slot;
        viewSlotStack.addLast(slot); // popped by completeArmedView at fork (c)
        censusArmGranted++;
        return true;
    }

    // IS5-XDIM state: the slot pended between the INVOKE handler (which declines to cancel for
    // POST views) and the TAIL handler (which captures mainRT after the dest chain completed),
    // plus the pipeline identity it was pended against and the per-frame one-POST-per-dest-dim
    // set. All cleared at beginFrame.
    private static CaptureSlot postPendingSlot = null;
    private static Object postPendingPipeline = null;
    private static final java.util.HashSet<Object> postDimsThisFrame = new java.util.HashSet<>();
    private static boolean postFinalSeamWitnessed = false;
    private static boolean xdimLiveNoted = false;
    // IS5-XDIM-SG: the pend record's dest-side measurement (branch (a) of the boundary hook
    // keys on THESE, never on restampArm — ⟦J⟧ both judges: the dest chain runs INSIDE the
    // portal view render where isRendering=true and restampArm is structurally null). null
    // destRenderer = SG not armed for this pend (lever off, dependency unmet, anchor missing).
    private static Object postPendingDestRenderer = null;
    private static int postPendingDestAnchor = -1;
    private static int postPendingImgTgt = -1;
    private static boolean postPendingImgReadsAlt = false;
    /** The MAIN renderer's most recent effective restamp mode (set every runStampPass). The SG
     *  dependency check reads it at pend time — the first armed SG frame finds null and falls
     *  back POST silently (⟦J⟧ first-frame race: sgF++, no WARN). */
    private static String lastMainRestampMode = null;

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
        // IS5-XDIM entry leak-check: a pended POST slot still here means the PREVIOUS finalize
        // threw between the INVOKE and TAIL (the TAIL inject does not run on exceptional exit).
        if (postPendingSlot != null) {
            breakMechanism("post-final pending slot never consumed (previous finalize threw"
                + " between renderAll and TAIL?)", null);
            postPendingSlot = null;
            postPendingPipeline = null;
            postPendingDestRenderer = null;
            return;
        }
        CaptureSlot slot = armedCapture;
        if (slot == null) return;
        armedCapture = null; // one finalize consumes one arm, success or failure
        if (mechanismBroken) return;
        // IS5-XDIM fork (design §shape): a POST view pends here WITHOUT cancelling — the dest
        // chain runs to completion (composites + final: the dimension's own pack look) and the
        // TAIL handler captures the finished image from MC mainRT. PRE views: shipped body below,
        // byte-identical.
        if (slot.postFinalMode) {
            postPendingSlot = slot;
            postPendingPipeline = irisRenderingPipeline;
            // IS5-XDIM-SG (IS5_RESTAMP_DESIGN.md §2.2): resolve + measure the DEST chain's
            // anchor NOW so boundary branch (a) can capture the single-graded image at
            // i==destAnchor while the dest renderAll runs (it starts right after this return).
            // Dependency: the SOURCE renderer's effective mode must be RESTAMP/HEAD-CONTENT
            // (injected content + PLANE depth at TAA = re-ghosted window) — unknowable on the
            // first armed frame (⟦J⟧): fall back POST silently, sgF-visible, no WARN. An
            // anchor-missing dest chain (R6) falls back the same way.
            postPendingDestRenderer = null;
            if (IPGlobal.is5XdimSingleGrade) {
                String mm = lastMainRestampMode;
                if (mm == null || !(mm.equals("RESTAMP") || mm.startsWith("HEAD-CONTENT"))) {
                    censusSgF++;
                } else {
                    try {
                        Object destCr = fPipelineCompositeRenderer.get(irisRenderingPipeline);
                        RestampMeasurement dm = destCr instanceof CompositeRenderer cr
                            ? measureRestamp(cr) : null;
                        if (dm != null && dm.anchor >= 0 && dm.imageTarget >= 0) {
                            postPendingDestRenderer = destCr;
                            postPendingDestAnchor = dm.anchor;
                            postPendingImgTgt = dm.imageTarget;
                            postPendingImgReadsAlt = dm.anchorReadsAlt;
                        } else {
                            censusSgF++;
                        }
                    } catch (Throwable sgMeasErr) {
                        censusSgF++;
                    }
                }
            }
            // IS5-FARFADE (§5 F2/F4/F5): pend-time PRE copy — the dest colortex0 pass-0
            // READ side (byte-identical to the pass-0 boundary; NO second dispatch index,
            // which would collide on destAnchor==0 packs). Gated on the SG arm existing AND
            // w<1: at w==1 this issues ZERO GL commands. EVERY failure collapses THIS slot
            // to w=1 (the shipped SG path), fwF-census-visible, never breakMechanism.
            if (postPendingDestRenderer != null && slot.fadeW < 1f) {
                try {
                    Set<?> preFlip = resolvePassZeroFlipSet(
                        (List<?>) fPasses.get(postPendingDestRenderer));
                    RenderTargets preRts =
                        (RenderTargets) fPipelineRenderTargets.get(irisRenderingPipeline);
                    RenderTarget preC0 = preRts == null ? null : preRts.get(0);
                    if (preFlip == null || preC0 == null) {
                        slot.fadeW = 1f;
                        censusFwF++;
                    } else {
                        int preSrc = preFlip.contains(0)
                            ? preC0.getAltTexture() : preC0.getMainTexture();
                        int pw = preC0.getWidth();
                        int ph = preC0.getHeight();
                        int pFmt = GL45C.glGetTextureLevelParameteri(
                            preSrc, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                        if (!ensurePreStorage(slot, pw, ph, pFmt)) {
                            slot.fadeW = 1f;
                            censusFwF++;
                        } else {
                            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate */ }
                            GL43C.glCopyImageSubData(
                                preSrc, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                                slot.preColorTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                                pw, ph, 1);
                            if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                                slot.fadeW = 1f;
                                censusFwF++;
                            } else {
                                slot.preCaptured = true;
                            }
                        }
                    }
                } catch (Throwable preErr) {
                    slot.fadeW = 1f;
                    censusFwF++;
                }
            }
            return; // no ci.cancel()
        }
        long captureT0 = System.nanoTime();
        long readbackNs = 0;
        try {
            Object cr = fPipelineCompositeRenderer.get(irisRenderingPipeline);
            if (!(cr instanceof CompositeRenderer)) {
                breakMechanism("pipeline.compositeRenderer is not a CompositeRenderer", null);
                return;
            }
            Set<?> flipSet = resolvePassZeroFlipSet((List<?>) fPasses.get(cr));
            if (flipSet == null) {
                breakMechanism("no real composite pass 0 on the dest pipeline (compute-only or"
                    + " zero-composite pack)", null);
                return;
            }
            boolean readAlt = flipSet.contains(0);
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
            // PART5 aux capture (enhancement-grade: a failure DROPS the aux for this view, noted
            // once, never breaks the mechanism — the window must not die for a data mask).
            for (int i = 0; i < AUX_TARGETS.length; i++) {
                slot.auxValid[i] = false;
                int t = AUX_TARGETS[i];
                RenderTarget auxRt = rts.get(t);
                if (auxRt == null) continue;
                int srcAux = flipSet.contains(t)
                    ? auxRt.getAltTexture() : auxRt.getMainTexture();
                int auxFmt = GL45C.glGetTextureLevelParameteri(
                    srcAux, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                if (!ensureAuxStorage(slot, i, w, h, auxFmt)) {
                    noteAuxDropOnce("aux" + t + " storage alloc rejected");
                    continue;
                }
                GL43C.glCopyImageSubData(
                    srcAux, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                    slot.auxTex[i], GL11.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
                int auxErr = GL11.glGetError();
                if (auxErr != GL11.GL_NO_ERROR) {
                    noteAuxDropOnce("aux" + t + " copy failed 0x" + Integer.toHexString(auxErr));
                } else {
                    slot.auxValid[i] = true;
                }
            }
            // 1Hz capture-content readback (log-only): one center pixel of the freshly-copied
            // capture. Makes "what does the capture HOLD" log-readable — black ⇒ unwritten or
            // cleared source side; scene-like ⇒ real content. The leg-6 magenta result proved the
            // WRITE path, so content is the open question and it must not need eyes to answer.
            // PERF-P1: a glGetTextureSubImage is a synchronous pipeline stall — lever-gated
            // (-Pis5LiveReadbacks) with its drain, OFF by default; the arc it served is closed.
            if (IPGlobal.is5LiveReadbacks) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastCaptureReadbackMs >= 1000) {
                    lastCaptureReadbackMs = nowMs;
                    long rbT0 = System.nanoTime();
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
                    readbackNs = System.nanoTime() - rbT0;
                    PerfTimers.add("is5.readbackCapturePx", readbackNs);
                }
            }
            // Capture-geometry witness (design §3.15 — the snapshot-witness discipline retargeted;
            // always on, content-keyed, WARN on any dimension oddity is impossible here by
            // construction since both copies use the SAME queried w/h — the announcement is the
            // record that this frame's capture pair really is one geometry).
            noteCaptureGeometry("c0", w, h, colorFmt, depthFmt);
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
            PerfTimers.add("is5.capture", System.nanoTime() - captureT0 - readbackNs);
        } catch (Throwable t) {
            breakMechanism("capture threw", t);
        }
    }

    /**
     * IS5-XDIM — THE POST_FINAL CAPTURE BODY (design {@code migration/IS5_XDIM_DESIGN.md} §1),
     * at the finalize TAIL: the dest chain ran to completion, final deposited the finished image
     * into MC mainRenderTarget (both iris branches — javap-pinned), and depthtex0 was READ-ONLY
     * throughout (no composite FBO carries a depth attachment) so it still pairs with the image
     * exactly as the PRE capture's depth does. Iris's own resetRenderTarget + swap passes ran —
     * no mip/side hygiene replication. Mutate-last discipline as the PRE body; failure breaks
     * loudly (a lost view for one frame, never a corrupted frame).
     */
    public static void onFinalizeCompleted(Object irisRenderingPipeline) {
        if (!postFinalSeamWitnessed) {
            postFinalSeamWitnessed = true;
            LOGGER.info("[Seamless Portals] [IS5-XDIM] post-final seam WOVEN"
                + " (finalizeLevelRendering TAIL) — crossDimDestChain={}",
                IPGlobal.crossDimDestChain);
        }
        CaptureSlot slot = postPendingSlot;
        if (slot == null) return;
        postPendingSlot = null;
        Object pendedPipeline = postPendingPipeline;
        postPendingPipeline = null;
        postPendingDestRenderer = null; // SG branch (a) can no longer fire for this pend
        if (mechanismBroken) return;
        if (irisRenderingPipeline != pendedPipeline) {
            breakMechanism("post-final capture pipeline identity mismatch (pended vs TAIL)", null);
            return;
        }
        long captureT0 = System.nanoTime();
        try {
            RenderTargets rts = (RenderTargets) fPipelineRenderTargets.get(irisRenderingPipeline);
            int w;
            int h;
            if (slot.sgCaptured) {
                // IS5-XDIM-SG: the boundary capture already holds the single-graded image
                // (pre-AA) + dest depthtex0 — the mainRT copy is SKIPPED (it would overwrite
                // the capture with the double-processed final image). All bookkeeping below
                // (aux, pend/leak, pending=true) runs unchanged.
                w = slot.w;
                h = slot.h;
            } else {
                com.mojang.blaze3d.pipeline.RenderTarget mainRT =
                    net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
                if (mainRT == null
                    || !(mainRT.getColorTexture() instanceof GlTexture mainColorGl)) {
                    breakMechanism("post-final capture: mainRT color is not a GlTexture", null);
                    return;
                }
                int srcColor = mainColorGl.glId();
                w = mainRT.width;
                h = mainRT.height;
                int colorFmt = GL45C.glGetTextureLevelParameteri(
                    srcColor, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                GpuTexture depthGpu = rts.getDepthTexture();
                if (!(depthGpu instanceof GlTexture depthGl)) {
                    breakMechanism("post-final capture: dest depthtex0 is not a GlTexture", null);
                    return;
                }
                int srcDepth = depthGl.glId();
                int depthFmt = GL45C.glGetTextureLevelParameteri(
                    srcDepth, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                if (!ensureSlotStorage(slot, w, h, colorFmt, depthFmt)) {
                    breakMechanism("post-final capture storage alloc rejected (colorFmt=0x"
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
                int copyErr = GL11.glGetError();
                if (copyErr != GL11.GL_NO_ERROR) {
                    breakMechanism("post-final capture glCopyImageSubData failed (0x"
                        + Integer.toHexString(copyErr) + ", srcColor=" + srcColor
                        + " srcDepth=" + srcDepth + " " + w + "x" + h + ")", null);
                    return;
                }
            }
            // Aux (design §2.9 caveat honored): the pass-0 flip-set read is valid ONLY under the
            // no-composite-writes-aux premise (measured true for Complementary c6). Enhancement-
            // grade: a failure drops the aux, never the window.
            Set<?> flipSet = resolvePassZeroFlipSet(
                (List<?>) fPasses.get(fPipelineCompositeRenderer.get(irisRenderingPipeline)));
            for (int i = 0; i < AUX_TARGETS.length; i++) {
                slot.auxValid[i] = false;
                if (flipSet == null) continue;
                int t = AUX_TARGETS[i];
                RenderTarget auxRt = rts.get(t);
                if (auxRt == null) continue;
                int srcAux = flipSet.contains(t)
                    ? auxRt.getAltTexture() : auxRt.getMainTexture();
                int auxFmt = GL45C.glGetTextureLevelParameteri(
                    srcAux, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                if (!ensureAuxStorage(slot, i, w, h, auxFmt)) {
                    noteAuxDropOnce("post-final aux" + t + " storage alloc rejected");
                    continue;
                }
                GL43C.glCopyImageSubData(
                    srcAux, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                    slot.auxTex[i], GL11.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
                int auxErr = GL11.glGetError();
                if (auxErr != GL11.GL_NO_ERROR) {
                    noteAuxDropOnce("post-final aux" + t + " copy failed 0x"
                        + Integer.toHexString(auxErr));
                } else {
                    slot.auxValid[i] = true;
                }
            }
            noteCaptureGeometry(slot.sgCaptured ? "sgBoundary" : "mainRT",
                w, h, slot.colorFmt, slot.depthFmt);
            if (!xdimLiveNoted) {
                xdimLiveNoted = true;
                LOGGER.info("[Seamless Portals] [IS5-XDIM] cross-dim POST_FINAL capture LIVE"
                    + " (dest chain ran composites+final; capture=mainRT; storm-class dest"
                    + " composite effects now present in this window)");
            }
            slot.pending = true;
            capturesPendingThisFrame++;
            censusCaptures++;
            censusCapturesPost++;
            PerfTimers.add("is5.capturePost", System.nanoTime() - captureT0);
        } catch (Throwable t) {
            breakMechanism("post-final capture threw", t);
        }
    }

    /** Pass-0 flip set: first pass whose {@code stageReadsFromAlt} is non-null (a
     *  ComputeOnlyPass never assigns it — V3); null when no real pass exists. `contains(t)` =
     *  target t's content sits on ALT at composite entry — the per-target read/write parity for
     *  colortex0 AND the PART5 aux targets alike. */
    private static Set<?> resolvePassZeroFlipSet(List<?> passes) throws IllegalAccessException {
        for (Object pass : passes) {
            Object set = fPassReadsFromAlt.get(pass);
            if (set != null) {
                return (Set<?>) set;
            }
        }
        return null;
    }

    private static Boolean resolvePassZeroReadsAlt(List<?> passes) throws IllegalAccessException {
        Set<?> set = resolvePassZeroFlipSet(passes);
        return set == null ? null : set.contains(0);
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

    /** IS5-FARFADE (§5 F2/F5-vi): pooled color-only storage for the pend-time PRE copy —
     *  the ensureSlotStorage idiom (size+format keyed, recycle-on-mismatch). */
    private static boolean ensurePreStorage(CaptureSlot slot, int w, int h, int fmt) {
        if (slot.preColorTex != 0 && slot.preW == w && slot.preH == h && slot.preFmt == fmt) {
            return true;
        }
        if (slot.preColorTex != 0) {
            GL11.glDeleteTextures(slot.preColorTex);
            slot.preColorTex = 0;
        }
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate for the alloc check */ }
        int t = GL45C.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(t, 1, fmt, w, h);
        if (GL11.glGetError() != GL11.GL_NO_ERROR) {
            GL11.glDeleteTextures(t);
            return false;
        }
        slot.preColorTex = t;
        slot.preW = w;
        slot.preH = h;
        slot.preFmt = fmt;
        return true;
    }

    private static String lastCaptureGeometry = null;
    private static long lastCaptureReadbackMs = 0;
    private static long lastDepthReadbackMs = 0;
    private static final Set<String> auxDropNotes = new java.util.HashSet<>();

    private static void noteAuxDropOnce(String what) {
        if (auxDropNotes.add(what)) {
            LOGGER.info("[Seamless Portals] [IS5-PRE] aux capture dropped: {} (window unaffected;"
                + " the aux-coherence enhancement is absent for such frames)", what);
        }
    }

    private static boolean ensureAuxStorage(CaptureSlot slot, int i, int w, int h, int fmt) {
        if (slot.auxTex[i] != 0 && slot.auxW[i] == w && slot.auxH[i] == h
            && slot.auxFmt[i] == fmt) {
            return true;
        }
        if (slot.auxTex[i] != 0) {
            GL11.glDeleteTextures(slot.auxTex[i]);
            slot.auxTex[i] = 0;
        }
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate for the alloc check */ }
        int tex = GL45C.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(tex, 1, fmt, w, h);
        if (GL11.glGetError() != GL11.GL_NO_ERROR) {
            GL11.glDeleteTextures(tex);
            return false;
        }
        slot.auxTex[i] = tex;
        slot.auxW[i] = w;
        slot.auxH[i] = h;
        slot.auxFmt[i] = fmt;
        return true;
    }

    // IS5-XDIM: latched PER SOURCE (c0 | mainRT) — with both modes live in one frame a single
    // latch would bounce c0<->mainRT every frame (the stamped=1<->2 re-emission trap).
    private static final java.util.HashMap<String, String> lastCaptureGeometryBySrc =
        new java.util.HashMap<>();

    private static void noteCaptureGeometry(String src, int w, int h, int colorFmt, int depthFmt) {
        String g = w + "x" + h + " color=0x" + Integer.toHexString(colorFmt)
            + " depth=0x" + Integer.toHexString(depthFmt);
        if (g.equals(lastCaptureGeometryBySrc.get(src))) return;
        lastCaptureGeometryBySrc.put(src, g);
        LOGGER.info("[Seamless Portals] [IS5-PRE] capture geometry src={} {}", src, g);
    }

    // ---- IS5-XTRACE (lever-gated, log-only): the crossing-flicker discriminator ----------------
    // One line per frame for ±8 frames around every teleport, emitted at the NEXT beginFrame so
    // no-view and no-stamp frames still get their row (the flicker frame may be exactly one of
    // those). Slot details are appended by the stamp loop; dim/tp are recorded at frame entry.
    private static int traceWindowFrames = 0;
    private static final StringBuilder traceSlots = new StringBuilder();
    private static String traceDim = "?";
    private static boolean traceTp = false;

    /** Frame-start safety recycle: a pending slot surviving into a new frame means the stamp
     *  never consumed it (main renderAll never ran, or the discriminator failed) — recycle and
     *  say so once per occurrence pattern. Called by the loop entry (S4b-part3). */
    public static void beginFrame() {
        if (IPGlobal.is5CrossingTrace) {
            if (traceWindowFrames > 0) {
                // Emit the PREVIOUS frame's row (its counters have not been reset yet).
                LOGGER.info("[Seamless Portals] [IS5-XTRACE] dim={} tp={} stampRan={}"
                        + " specR/S={}/{} pendingAtEntry={} slots=[{}]",
                    traceDim, traceTp, stampConsumedThisFrame,
                    speculativeRendersThisFrame, speculativeSkipsThisFrame,
                    capturesPendingThisFrame, traceSlots);
                traceWindowFrames--;
                traceSlots.setLength(0);
            }
            if (qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.isTeleportingFrame) {
                traceWindowFrames = Math.max(traceWindowFrames, 8);
            }
            var lvl = net.minecraft.client.Minecraft.getInstance().level;
            traceDim = lvl == null ? "null" : lvl.dimension().identifier().getPath();
            traceTp =
                qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.isTeleportingFrame;
        }
        if (capturesPendingThisFrame > 0) {
            LOGGER.warn("[Seamless Portals] [IS5-PRE] {} capture(s) from the previous frame were"
                + " never stamped — recycled (main composite renderAll missing or discriminator"
                + " mismatch?)", capturesPendingThisFrame);
        }
        for (CaptureSlot s : captureSlots) {
            s.pending = false;
            s.sgCaptured = false;
            // IS5-FARFADE (§5 F5-vi): fade state clears with the pending lifecycle — slots
            // are reused across portals (the cache-lives-on-the-subject rule).
            s.preCaptured = false;
            s.fadeW = 1f;
        }
        capturesPendingThisFrame = 0;
        armedCapture = null;
        // IS5-HIST eviction (render thread, GL current): removed portals' prev-capture textures
        // are deleted explicitly (strong keys — a silent WeakHashMap GC would leak the GL ids);
        // the map is bounded at 16, eldest first.
        if (!prevCaptureByPortal.isEmpty()) {
            var it = prevCaptureByPortal.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getKey().isRemoved()
                    || prevCaptureByPortal.size() > 16) {
                    if (e.getValue()[0] != 0) GL11.glDeleteTextures(e.getValue()[0]);
                    it.remove();
                }
            }
        }
        // IS5-XDIM frame hygiene: the one-POST-per-dest-dim set resets; an orphaned pended slot
        // (finalize threw after pend, entry-check never reached) is recycled with the same
        // never-stamped semantics as above.
        postDimsThisFrame.clear();
        if (postPendingSlot != null) {
            LOGGER.warn("[Seamless Portals] [IS5-XDIM] a pended post-final capture survived into"
                + " a new frame — recycled (finalize threw between renderAll and TAIL?)");
            postPendingSlot = null;
            postPendingPipeline = null;
        }
        postPendingDestRenderer = null;
        // IS5-RESTAMP orphan check (⟦J⟧: keyed on !mechanismBroken — breakMechanism clears the
        // arm itself, and a legit mid-frame break must not read as a dormant boundary mixin;
        // 1Hz-limited so a genuinely dormant hook cannot flood the render-thread log).
        if (restampArm != null) {
            if (!mechanismBroken) {
                censusRstOrph++;
                long orphNow = System.currentTimeMillis();
                if (orphNow - lastRestampOrphanWarnMs >= 1000) {
                    lastRestampOrphanWarnMs = orphNow;
                    LOGGER.warn("[Seamless Portals] [IS5-RESTAMP] armed at HEAD but the anchor"
                        + " boundary was never reached — depthtex1 stayed PLANE that frame (the"
                        + " boundary mixin dormant, or the renderer rebuilt mid-frame?)"
                        + " rstOrph={}", censusRstOrph);
                }
            }
            restampArm = null;
        }
        viewSlotStack.clear(); // PART4: a mid-loop throw must not poison the next frame's stack
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
            long stampT0 = System.nanoTime();
            stampReadbackNsThisPass = 0;
            try {
                runStampPass(
                    (IrisRenderingPipeline) mainPipeline, (CompositeRenderer) compositeRenderer);
            } finally {
                // Consumption is scoped to the MATCHED main-chain invocation only — success or
                // break, never on a discriminator mismatch (see the comment above).
                for (CaptureSlot s : captureSlots) s.pending = false;
                capturesPendingThisFrame = 0;
                PerfTimers.add("is5.stampPass",
                    System.nanoTime() - stampT0 - stampReadbackNsThisPass);
            }
        } catch (Throwable t) {
            breakMechanism("stamp discriminator/pass threw", t);
        }
    }

    private static boolean stampConsumedThisFrame = false;
    // PERF-P1: the 1Hz depth readback's elapsed inside runStampPass, subtracted from the
    // is5.stampPass bucket so a lever-enabled readback's stall never pollutes the stamp cost.
    private static long stampReadbackNsThisPass = 0;

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
    /** PART5: out location 1 → drawBuffers[1] = the aux attachment; out location 2 →
     *  drawBuffers[2] = the TAA HISTORY attachment (colortex2's READ side — the ghost fix: TAA's
     *  translation-parallax reprojection at window pixels finds only CURRENT dest content in
     *  history, nothing stale to drag; TAA-attributed by the user's one-variable A/B, both
     *  directions). On the PLAIN fbo (drawBuffers {0}) the extra outputs have no buffer and are
     *  dropped by GL — one program serves all configs. The aux is a DATA target (materialMask):
     *  never tinted, never solid-replaced; the history gets the SAME value as fragColor (under
     *  the debug levers a magenta history is consistent and diagnosable). Depth-only draws
     *  (depthtex1/2 FBOs, noDrawBuffers) drop all colour outs and keep the gl_FragDepth floor. */
    // IS5-HIST (2026-08-10): histColor = the PREVIOUS frame's capture when one exists
    // (u_havePrev=1). history=current left TAA's reprojected history read one frame WRONG at
    // window pixels — the user's "ugly blur on approach with MB off" (jitter=0 leg refuted the
    // jitter-only story; the blend's wrong-time history is the surviving mechanism, and MB-on
    // merely masks it into the kept cool look). With prev content the window gets REAL temporal
    // accumulation: jitter averages, the blend resolves. u_capturePrev is the raw previous
    // capture (untinted — under -PdebugTintStamp the history is deliberately untinted prev
    // content; diagnosable, documented divergence from the magenta-consistency note).
    private static final String STAMP_FRAGMENT_SRC = """
        #version 330 core
        uniform sampler2D u_capture;
        uniform sampler2D u_captureAux;
        uniform sampler2D u_capturePrev;
        uniform sampler2D u_captureDepth;
        uniform sampler2D u_stampedDepth0;
        uniform float u_solid;
        uniform float u_havePrev;
        uniform int u_depthMode;
        // IS5-RESTAMP R11-MASK: 1.0 only during a nested stamp into an SG-captured parent —
        // writes alpha 0 at exactly the child's pixels; the SG inject (mode 2) discards there,
        // so the nested sub-window keeps its source-graded anchor-time content instead of the
        // parent's dest-graded inject (the near-portal dim-window fix, log-adjudicated:
        // nest>0 windows showed sgI collapsing while sgC held).
        uniform float u_zeroAlpha;
        // IS5-FARFADE (IS5_FARFADE_DESIGN.md §1.4 + §5 F10): the anchor BLEND — u_fadeW=1
        // is byte-identical to the pure inject (the branch below never samples u_prevGraded
        // then); <1 mixes the pre-inject anchor image (the source-graded PRE face, snapshot
        // on unit 5) toward the SG capture. Asserted at every stamp-program draw site.
        uniform sampler2D u_prevGraded;
        uniform float u_fadeW;
        in vec4 vertexColor;
        layout(location = 0) out vec4 fragColor;
        layout(location = 1) out vec4 auxColor;
        layout(location = 2) out vec4 histColor;
        void main() {
            vec4 sampled = vec4(texelFetch(u_capture, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);
            fragColor = mix(sampled * vertexColor, vertexColor, u_solid);
            fragColor.a = 1.0 - u_zeroAlpha;
            auxColor = texelFetch(u_captureAux, ivec2(gl_FragCoord.xy), 0);
            histColor = mix(fragColor,
                vec4(texelFetch(u_capturePrev, ivec2(gl_FragCoord.xy), 0).rgb, 1.0), u_havePrev);
            // IS5-DEPTHFORK: u_depthMode=1 (the CONTENT-depth replay into depthtex1 only, lever
            // -Pis5WindowContentDepth): the captured dest depth IS the main-view clip depth of
            // the virtual content (the same screen-alignment invariant as the 1:1 color fetch —
            // slot matrices are SOURCE-side, dCam=0 XTRACE-proven; the judged remap chain
            // collapses to IDENTITY). Test-on-plane-write-content: the color draw already left
            // PLANE depth in depthtex0 exactly where the window won; discard where a nearer
            // occluder won (eps ~2^-24, format-derived — a 1e-6 band silently content-stamps
            // plane-hugging occluders). Never nearer than plane: the hand floor + C4-SEAM bound
            // ride planeZ. Mode 0 = the exact shipped expression (byte-identical PLANE path).
            float planeZ = max(gl_FragCoord.z, 0.001);
            if (u_depthMode == 1) {
                ivec2 tc = ivec2(gl_FragCoord.xy);
                if (texelFetch(u_stampedDepth0, tc, 0).r < planeZ - 6.0e-8) discard;
                gl_FragDepth = max(texelFetch(u_captureDepth, tc, 0).r, planeZ);
            } else if (u_depthMode == 3) {
                // IS5-WASH blackout: zero the window footprint in c0 before the bloom gather
                // (visibility-clipped — never blacks an occluder's pixels).
                if (texelFetch(u_stampedDepth0, ivec2(gl_FragCoord.xy), 0).r
                    < planeZ - 6.0e-8) discard;
                fragColor = vec4(0.0, 0.0, 0.0, 1.0);
                gl_FragDepth = planeZ;
            } else if (u_depthMode == 4) {
                // IS5-WASH restore: repaint the same footprint from the scratch copy before
                // bloom-apply reads c0. Full texel (alpha included).
                ivec2 tcw = ivec2(gl_FragCoord.xy);
                if (texelFetch(u_stampedDepth0, tcw, 0).r < planeZ - 6.0e-8) discard;
                fragColor = texelFetch(u_capture, tcw, 0);
                gl_FragDepth = planeZ;
            } else if (u_depthMode == 2) {
                // IS5-XDIM-SG inject (IS5_RESTAMP_DESIGN.md §2.2.3): mode-1's visibility
                // discard against the HEAD-stamped depthtex0 (plane + occluders), colour =
                // the single-graded boundary capture (fragColor above already holds it). The
                // inject FBO is colour-only — gl_FragDepth is inert here. R11-MASK: capture
                // alpha 0 marks nested-child pixels (alpha initialized to 1 at SG capture,
                // zeroed by the nested stamp) — discard keeps the source-graded anchor-time
                // content there.
                ivec2 tc2 = ivec2(gl_FragCoord.xy);
                if (texelFetch(u_stampedDepth0, tc2, 0).r < planeZ - 6.0e-8) discard;
                if (texelFetch(u_capture, tc2, 0).a < 0.5) discard;
                // IS5-FARFADE blend (§5 F6: the discard above keys EXCLUSIVELY on the SG
                // capture's alpha — u_prevGraded/preColorTex alpha is NEVER read; a c0-class
                // alpha-less format reads a constant 1.0 and would kill the R11 discard).
                if (u_fadeW < 1.0) {
                    fragColor = vec4(mix(
                        texelFetch(u_prevGraded, tc2, 0).rgb, fragColor.rgb, u_fadeW), 1.0);
                }
                gl_FragDepth = planeZ;
            } else {
                gl_FragDepth = planeZ;
            }
        }
        """;

    /** The pack's TAA history colour target — MEASURED for Complementary r5.8.1: composite6
     *  `DRAWBUFFERS:32` writes history to colortex2 and NO earlier composite writes it (pass-0
     *  parity therefore holds at the TAA pass). IS5-RESTAMP ⟦J⟧ disclosed: the anchor rule
     *  hard-depends on this constant (runtime-measured for PASS indices, NOT for the history
     *  target index); a pack with a different history colortex misses the anchor and degrades
     *  fail-safe to PLANE(no-anchor) — attributable via the meas line's hist=[]. */
    private static final int HISTORY_TARGET = 2;

    // =============================================================================================
    // IS5-RESTAMP (migration/IS5_RESTAMP_DESIGN.md §1.2-§1.6 + Part 2) — per-renderer reader
    // measurement, the boundary arm, and the two-branch boundary handler.
    // =============================================================================================

    /** One renderer's measured depthtex1 topology. Holds NO GL ids — pure measured ints — so
     *  weak cache keys are safe (⟦J⟧ the cache-outlives-subject rule; the mask's plan-cache
     *  precedent). mode ∈ RESTAMP | HEAD-CONTENT(collapse) | PLANE(no-anchor) | PLANE(meas-fail). */
    static final class RestampMeasurement {
        int passCount = 0;
        int[] d1Readers = new int[0];
        int[] histReaders = new int[0];
        /** IS5-RESTAMP §1.9: d1 readers whose program also actively references
         *  previousCameraPosition — the MB-velocity class (velocity needs prev-frame camera
         *  state; measured active on Complementary composite4 by the DestPrevCamera arc).
         *  The cool-MB-OFF depth boundary = min(anchor, first of these). Heuristic disclosed:
         *  a pack with a prev-camera-consuming VL pass would move the boundary early there —
         *  the setting itself (ON) is the escape. */
        int[] prevCamReaders = new int[0];
        int anchor = -1;
        int imageTarget = -1;
        boolean anchorReadsAlt = false;
        String mode = "PLANE(meas-fail)";
        // IS5-WASH: the bloom GATHERER = the pass that mipmap-regens colortex0 (BLOOMMB
        // discriminator). -1 = none found / field unavailable. gatherWritesC0 = the MB-on
        // shape (gatherer also rewrites c0) — the v1 bracket GUARDS OFF there (a blackout
        // would MB-smear black past the footprint); washout persists in that config,
        // disclosed + meas-visible. gatherReadsAlt = c0's read side at the gatherer.
        int gatherIdx = -1;
        boolean gatherReadsAlt = false;
        boolean gatherWritesC0 = false;
        // IS5-WASHPROBE v3: the gatherer's drawBuffers[0] = where the bloom tiles land
        // (colortex3 on this pack). Measured, never hard-coded; -1 = unavailable.
        int gatherWriteTgt = -1;
        String washState = "SKIP(no-gatherer)";
    }

    /** Keyed on the CompositeRenderer OBJECT (identity equals — CompositeRenderer does not
     *  override equals; never texture/program NAMES). A rebuilt pipeline mints a new renderer
     *  instance → automatic re-measure; the dead pipeline's entry is GC'd with it. */
    private static final java.util.WeakHashMap<Object, RestampMeasurement> restampMeasCache =
        new java.util.WeakHashMap<>();

    /**
     * Measure which composite passes actively sample depthtex1 / the history colortex.
     * glGetUniformLocation needs no bind; the GLSL linker strips unreferenced uniforms, which is
     * exactly the wanted "actively samples" discriminator (⟦J⟧ O1: implementations MAY keep a
     * declared-but-dead uniform active — kill-check 1 adjudicates the RAW sets against the pack
     * walk, never the mode token alone). Failure caches PLANE(meas-fail) — once per renderer by
     * construction — and the HEAD stamp stays shipped-correct.
     */
    private static RestampMeasurement measureRestamp(CompositeRenderer renderer) {
        RestampMeasurement cached = restampMeasCache.get(renderer);
        if (cached != null) return cached;
        RestampMeasurement m = new RestampMeasurement();
        try {
            List<?> passes = (List<?>) fPasses.get(renderer);
            java.util.ArrayList<Integer> d1 = new java.util.ArrayList<>();
            java.util.ArrayList<Integer> hist = new java.util.ArrayList<>();
            java.util.ArrayList<Integer> prevCam = new java.util.ArrayList<>();
            m.passCount = passes.size();
            for (int i = 0; i < passes.size(); i++) {
                Object pass = passes.get(i);
                Object prog = fPassProgram.get(pass);
                if (prog == null) continue; // ComputeOnlyPass
                int pid = ((net.irisshaders.iris.gl.program.Program) prog).getProgramId();
                boolean readsD1 =
                    GL20C.glGetUniformLocation(pid, "depthtex1") != -1;
                boolean readsHist =
                    GL20C.glGetUniformLocation(pid, "colortex" + HISTORY_TARGET) != -1;
                if (readsD1) {
                    d1.add(i);
                    if (GL20C.glGetUniformLocation(pid, "previousCameraPosition") != -1) {
                        prevCam.add(i);
                    }
                }
                if (readsD1 && readsHist) {
                    hist.add(i);
                    if (m.anchor < 0) {
                        m.anchor = i;
                        int[] db = (int[]) fPassDrawBuffers.get(pass);
                        m.imageTarget = (db != null && db.length > 0) ? db[0] : -1;
                        Object readsAlt = fPassReadsFromAlt.get(pass);
                        m.anchorReadsAlt = readsAlt instanceof Set<?> s
                            && m.imageTarget >= 0 && s.contains(m.imageTarget);
                    }
                }
            }
            m.d1Readers = d1.stream().mapToInt(Integer::intValue).toArray();
            m.histReaders = hist.stream().mapToInt(Integer::intValue).toArray();
            m.prevCamReaders = prevCam.stream().mapToInt(Integer::intValue).toArray();
            // IS5-WASH gatherer measurement — its OWN try (⟦J⟧ note 2: an optional feature's
            // measurement throw must degrade to SKIP, never demote the renderer to
            // PLANE(meas-fail) and lose the C1 fix).
            try {
                if (fPassMipmapped == null) {
                    m.washState = "SKIP(no-mipmap-field)";
                } else {
                    for (int i = 0; i < passes.size(); i++) {
                        Object pass = passes.get(i);
                        Object mip = fPassMipmapped.get(pass);
                        if (mip instanceof Set<?> ms && ms.contains(0)) {
                            m.gatherIdx = i;
                            Object readsAlt = fPassReadsFromAlt.get(pass);
                            m.gatherReadsAlt = readsAlt instanceof Set<?> rs && rs.contains(0);
                            int[] db = (int[]) fPassDrawBuffers.get(pass);
                            if (db != null) {
                                for (int t : db) {
                                    if (t == 0) m.gatherWritesC0 = true;
                                }
                                if (db.length > 0) m.gatherWriteTgt = db[0];
                            }
                            break;
                        }
                    }
                    // Token honesty (⟦J⟧ note 3): the writes-c0 skip names the SHAPE, not a
                    // cause — MB-on is the common Complementary cause, WORLD_BLUR another.
                    if (m.gatherIdx < 0) m.washState = "SKIP(no-gatherer)";
                    else if (m.anchor < 0 || m.gatherIdx >= m.anchor) m.washState = "SKIP(gather>=anchor)";
                    else if (m.gatherWritesC0) m.washState = "SKIP(gatherer-writes-c0)";
                    else m.washState = "ON";
                }
            } catch (Throwable washMeasErr) {
                m.washState = "SKIP(meas-threw)";
            }
            if (m.anchor < 0) {
                m.mode = "PLANE(no-anchor)";
            } else {
                boolean readerBeforeAnchor = false;
                for (int idx : m.d1Readers) {
                    if (idx < m.anchor) readerBeforeAnchor = true;
                }
                // No reader before the anchor ⇒ CONTENT at HEAD is byte-equivalent and strictly
                // less machinery (the single-reader-pack answer, design §1.2).
                m.mode = readerBeforeAnchor ? "RESTAMP" : "HEAD-CONTENT(collapse)";
            }
        } catch (Throwable t) {
            m.mode = "PLANE(meas-fail)";
            LOGGER.warn("[Seamless Portals] [IS5-RESTAMP] measurement failed for a composite"
                + " renderer — restamp disabled there (mode=PLANE(meas-fail); HEAD semantics"
                + " remain shipped)", t);
        }
        restampMeasCache.put(renderer, m);
        return m;
    }

    /** One retained HEAD-replay entry: the slot + the EXACT vertex slice the HEAD stamp drew
     *  (frame-transient GpuBuffer, drained at GameRenderer.render TAIL — alive through every
     *  composite pass of this frame) ⇒ bit-identical rasterization at the boundary by
     *  construction, no mesh rebuild, no matrix re-derivation. */
    static final class RestampEntry {
        CaptureSlot slot;
        GpuBufferSlice vertexSlice;
        int vertexCount;
        // IS5-FARFADE (§5 F8): the HEAD stamp's source side THIS frame. PRE-stamped entries
        // are NEVER wash-blacked (§1.10's rule verbatim: the source gather is a PRE stamp's
        // only bloom source) and take the anchor BLEND instead of the pure inject.
        boolean stampedPre;
    }

    /** The per-frame boundary arm (design §1.5). Built by runStampPass in RESTAMP mode; consumed
     *  once at the anchor boundary; cleared at beginFrame (orphan WARN) and by breakMechanism. */
    static final class RestampArm {
        CompositeRenderer renderer;
        RestampMeasurement meas;
        final java.util.ArrayList<RestampEntry> entries = new java.util.ArrayList<>();
        final Matrix4f combined = new Matrix4f(); // the exact HEAD-stamp P·MV (defensive copy)
        int mainDepth0Id;   // visibility-discard reference — still HEAD-plane at the anchor
        int depth1Id;       // depthtex1 (getDepthTextureNoTranslucents)
        int injectTexId;    // SG: the source anchor's image READ side (0 = SG inert)
        int w, h;
        /** IS5-RESTAMP §1.9: where the depth restamp fires. Cool-MB ON = the anchor (MB pass
         *  sees PLANE = the whip); OFF (default) = min(anchor, first prev-camera d1 reader)
         *  so the MB pass sees CONTENT. The SG inject ALWAYS fires at the anchor (it must stay
         *  post-tonemap — an earlier inject would re-expose the source grade = double-grade). */
        int depthBoundary;
        boolean depthDone;
        boolean injectDone;
        // IS5-WASH: the gather-exclusion bracket. washNeeded iff meas.washState==ON, the
        // lever allows, and at least one POST/SG entry exists (dest-baked bloom present —
        // same-dim PRE windows are NEVER blacked out: the source gather is their only bloom
        // source). Fires at gatherIdx (save c0 → scratch, blackout the eligible footprints)
        // and gatherIdx+1 (restore the footprints from scratch, before bloom-apply reads c0).
        boolean washNeeded;
        boolean washSaveDone;
        boolean washRestoreDone;
        int washTexId; // c0's read side at the gatherer
        // IS5-WASHPROBE v3 (probe-only, populated iff -PwashProbe): BOTH physical sides of c0
        // and of the gatherer's tile target, so the bracket-adjudicator readback can prove
        // which side the blackout landed on and whether window energy entered the tiles.
        int wpC0MainId, wpC0AltId, wpTileMainId, wpTileAltId;
    }

    private static RestampArm restampArm = null;
    private static boolean restampSeamWitnessed = false;
    private static long lastRestampMeasLogMs = 0;
    private static long lastRestampOrphanWarnMs = 0;
    private static long lastBoundaryReadbackMs = 0;
    private static int censusRst, censusRstOrph, censusSgC, censusSgI, censusSgF;

    /**
     * THE BOUNDARY HOOK HANDLER — fired by {@code MixinIrisCompositeRenderer_DepthRestamp} at
     * the {@code Program.unbind()} seam once per pass iteration, BEFORE pass {@code i}'s mipmap
     * regen / setupState / viewport / use / draw (the BloomApertureMask-verified ordering, so
     * pass i re-establishes its own state and our draws need no FBO/viewport save).
     *
     * <p>⟦J⟧ TWO INDEPENDENT DISPATCH BRANCHES (both judges, blocking — a single gate list
     * structurally kills the SG dest capture: the dest chain's renderAll runs INSIDE the portal
     * view render, where {@code PortalRendering.isRendering()} is TRUE and {@code restampArm}
     * is necessarily null):
     * branch (a) = the SG DEST-CAPTURE, keyed ONLY on the pend record's renderer identity +
     * dest anchor index, evaluated FIRST and expressly NOT gated on isRendering;
     * branch (b) = the source restamp + SG inject, with the full gate list incl. the
     * isRendering belt (valid there: the main chain never runs inside a view).
     */
    public static void onRestampBoundary(Object compositeRenderer, int i) {
        if (!restampSeamWitnessed) {
            restampSeamWitnessed = true;
            LOGGER.info("[Seamless Portals] [IS5-RESTAMP] boundary seam WOVEN"
                    + " (CompositeRenderer.renderAll pre-pass INVOKE) — restampDefault={} sg={}",
                IPGlobal.is5DepthRestamp, IPGlobal.is5XdimSingleGrade);
        }
        if (!PATH_ACTIVE || mechanismBroken) return;
        try {
            // Branch (a) — SG dest-boundary capture (isRendering EXPECTED true here).
            CaptureSlot pendSlot = postPendingSlot;
            if (pendSlot != null && postPendingDestRenderer == compositeRenderer
                && i == postPendingDestAnchor && !pendSlot.sgCaptured) {
                runSgDestBoundaryCapture(pendSlot);
                return;
            }
            // Branch (b) — the source restamp + inject. §1.9: two independent firing indices —
            // the depth restamp at arm.depthBoundary (== the anchor when cool-MB is ON or no
            // prev-camera reader precedes it), the SG inject ALWAYS at the anchor. Each half
            // consumes once; the arm clears when both are done.
            RestampArm arm = restampArm;
            if (arm == null) return;
            if (compositeRenderer != arm.renderer) return;
            if (PortalRendering.isRendering()) return; // belt — main chain only
            // IS5-WASH bracket halves fire strictly before/at the depth/inject indices
            // (gatherIdx < anchor guaranteed by the eligibility rule; within one invocation
            // the wash runs FIRST so a gatherIdx+1==anchor collision still restores before
            // the inject reads/writes anything).
            boolean doWashSave = arm.washNeeded && !arm.washSaveDone
                && i == arm.meas.gatherIdx;
            boolean doWashRestore = arm.washNeeded && arm.washSaveDone && !arm.washRestoreDone
                && i == arm.meas.gatherIdx + 1;
            boolean doDepth = !arm.depthDone && i == arm.depthBoundary;
            boolean doInject = !arm.injectDone && i == arm.meas.anchor;
            if (!doWashSave && !doWashRestore && !doDepth && !doInject) return;
            if (doWashSave) arm.washSaveDone = true;
            if (doWashRestore) arm.washRestoreDone = true;
            if (doDepth) arm.depthDone = true;
            if (doInject) arm.injectDone = true;
            // IS5-WASHPROBE v3 — the bracket-adjudicator (1Hz, probe-only). Fires at the
            // restore boundary AFTER the gather pass consumed c0 and BEFORE the restore
            // repaints: reads screen-centre from BOTH physical c0 sides plus the lod-2 bloom
            // tile of screen-centre ((w/8, h/8); rescale=(1,1) below 1920x1080) from BOTH tile
            // sides. Tile encoding: linear = raw^4 * 128. Adjudication (aim: lava-red window
            // at the crosshair): blackedSide~0 + otherSide bright + tiles hot => the gather
            // read the UN-blacked side (flip-parity defect); blackedSide~0 + tiles dark =>
            // bracket effective, the wash rides the face/capture; blackedSide bright =>
            // the blackout footprint missed.
            if (doWashRestore && IPGlobal.washProbe && IPGlobal.is5LiveReadbacks
                && arm.wpC0MainId != 0 && arm.wpC0AltId != 0
                && System.currentTimeMillis() - lastWashAdjudicatorMs >= 1000) {
                lastWashAdjudicatorMs = System.currentTimeMillis();
                try {
                    java.nio.FloatBuffer m0 = BufferUtils.createFloatBuffer(4);
                    java.nio.FloatBuffer a0 = BufferUtils.createFloatBuffer(4);
                    GL45C.glGetTextureSubImage(arm.wpC0MainId, 0, arm.w / 2, arm.h / 2, 0,
                        1, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, m0);
                    GL45C.glGetTextureSubImage(arm.wpC0AltId, 0, arm.w / 2, arm.h / 2, 0,
                        1, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, a0);
                    String tiles = "tile2=n/a";
                    if (arm.wpTileMainId != 0 && arm.wpTileAltId != 0) {
                        java.nio.FloatBuffer tm = BufferUtils.createFloatBuffer(4);
                        java.nio.FloatBuffer ta = BufferUtils.createFloatBuffer(4);
                        GL45C.glGetTextureSubImage(arm.wpTileMainId, 0, arm.w / 8, arm.h / 8,
                            0, 1, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, tm);
                        GL45C.glGetTextureSubImage(arm.wpTileAltId, 0, arm.w / 8, arm.h / 8,
                            0, 1, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, ta);
                        tiles = String.format(
                            "tile2main=(%.4f, %.4f, %.4f) tile2alt=(%.4f, %.4f, %.4f)",
                            tm.get(0), tm.get(1), tm.get(2), ta.get(0), ta.get(1), ta.get(2));
                    }
                    LOGGER.info("[Seamless Portals] [IS5-WASHPROBE] bracket-adjudicator"
                            + " @gather+1 pre-restore: blackedSide={} c0main=({}, {}, {})"
                            + " c0alt=({}, {}, {}) {} (tile lin=raw^4*128; adjudicate only"
                            + " with a lava-red window at centre)",
                        arm.washTexId == arm.wpC0AltId ? "alt" : "main",
                        String.format("%.4f", m0.get(0)), String.format("%.4f", m0.get(1)),
                        String.format("%.4f", m0.get(2)),
                        String.format("%.4f", a0.get(0)), String.format("%.4f", a0.get(1)),
                        String.format("%.4f", a0.get(2)), tiles);
                } catch (Throwable ignored) {
                }
                while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* never poison */ }
            }
            if (doWashSave || doWashRestore) {
                runWashBracket(arm, doWashSave, doWashRestore);
            }
            if (doDepth || doInject) {
                runRestampBoundaryDraws(arm, doDepth, doInject);
            }
            // A blacked-out-but-unrestored c0 must keep the arm alive to gatherIdx+1; a
            // never-fired wash (nothing destructive) falls through to the orphan WARN.
            if (arm.depthDone && arm.injectDone
                && (!arm.washNeeded || arm.washRestoreDone)) {
                restampArm = null;
            }
        } catch (Throwable t) {
            breakMechanism("restamp boundary threw", t);
        }
    }

    /**
     * SG branch (a): copy the dest chain's image (the anchor pass's READ side of the measured
     * image target — post-tonemap, pre-AA: graded exactly once) + dest depthtex0 into the pended
     * slot. Read-only copies — the dest chain never observes them. ⟦J⟧ EVERY failure here routes
     * to the sgF POST fallback (census-visible), NEVER breakMechanism: the TAIL capture and the
     * HEAD stamp semantics remain intact. ensureSlotStorage receives the MEASURED boundary
     * format (⟦J⟧ format compatibility — colortex-class, not mainRT-class).
     */
    private static void runSgDestBoundaryCapture(CaptureSlot slot) {
        try {
            Object destPipeline = postPendingPipeline;
            if (destPipeline == null) { censusSgF++; return; }
            RenderTargets rts = (RenderTargets) fPipelineRenderTargets.get(destPipeline);
            RenderTarget imgRt = rts.get(postPendingImgTgt);
            if (imgRt == null) { censusSgF++; return; }
            int srcColor = postPendingImgReadsAlt
                ? imgRt.getAltTexture() : imgRt.getMainTexture();
            int w = imgRt.getWidth();
            int h = imgRt.getHeight();
            int colorFmt = GL45C.glGetTextureLevelParameteri(
                srcColor, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            GpuTexture depthGpu = rts.getDepthTexture();
            if (!(depthGpu instanceof GlTexture depthGl)) { censusSgF++; return; }
            int srcDepth = depthGl.glId();
            int depthFmt = GL45C.glGetTextureLevelParameteri(
                srcDepth, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (!ensureSlotStorage(slot, w, h, colorFmt, depthFmt)) {
                censusSgF++;
                noteAuxDropOnce("SG boundary storage alloc rejected — POST fallback");
                return;
            }
            // ⟦J⟧ B1 (post-land judge, blocking): the R11-MASK sentinel rides the capture's
            // ALPHA channel. On an alpha-less anchor image format (R11F_G11F_B10F-class —
            // real in this pack family) the alpha clear no-ops silently, texelFetch(.a)
            // returns the spec constant 1.0, and the inject would deliver NEVER-GRADED nested
            // child pixels with zero census signal. Runtime-measured gate, no format list.
            if (GL45C.glGetTextureLevelParameteri(
                slot.colorTex, 0, GL11.GL_TEXTURE_ALPHA_SIZE) == 0) {
                censusSgF++;
                noteAuxDropOnce("SG capture format has no alpha channel — R11-MASK sentinel"
                    + " unavailable, POST fallback");
                return;
            }
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate for the copy check */ }
            GL43C.glCopyImageSubData(
                srcColor, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                slot.colorTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
            GL43C.glCopyImageSubData(
                srcDepth, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                slot.depthTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
            int copyErr = GL11.glGetError();
            if (copyErr != GL11.GL_NO_ERROR) {
                censusSgF++;
                noteAuxDropOnce("SG boundary copy failed 0x" + Integer.toHexString(copyErr)
                    + " — POST fallback (TAIL re-allocs to its own format)");
                return;
            }
            // R11-MASK alpha init: force capture alpha = 1 everywhere (the dest image's own
            // alpha is pack-arbitrary; the inject's discard needs a clean sentinel). Channel
            // bits javap-verified on the 26.2 jar: _colorMask int = R1|G2|B4|A8. The clear
            // respects scissor — assert it off. Pass i re-establishes its own FBO/clearColor
            // downstream (the boundary-seam contract); the FBO is transient, never cached.
            GlFramebuffer alphaFbo = new GlFramebuffer();
            try {
                alphaFbo.addColorAttachment(0, slot.colorTex);
                alphaFbo.drawBuffers(new int[]{0});
                alphaFbo.bind();
                GlStateManager._disableScissorTest();
                GlStateManager._colorMask(8); // alpha only (javap: R1|G2|B4|A8)
                // glClearBufferfv(GL_COLOR, 0, ...) — javap-pinned; respects the colour mask,
                // touches no global clear-colour state.
                GlStateManager._clearBuffer(0, new org.joml.Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
                GlStateManager._colorMask(15);
            } finally {
                try { alphaFbo.destroy(); } catch (Throwable ignored) {}
            }
            int alphaErr = GL11.glGetError();
            if (alphaErr != GL11.GL_NO_ERROR) {
                censusSgF++;
                noteAuxDropOnce("SG alpha init failed 0x" + Integer.toHexString(alphaErr)
                    + " — POST fallback");
                return;
            }
            slot.sgCaptured = true;
            censusSgC++;
            // IS5-WASHPROBE: the SG capture's OWN center-pixel readback (the PRE-path readback
            // never fires for POST/SG views — an aim gap that would let a same-dim window's
            // number masquerade as the washout window's). 1Hz, atomic drain, probe-lever only.
            if (IPGlobal.washProbe && IPGlobal.is5LiveReadbacks) {
                long sgRbNow = System.currentTimeMillis();
                if (sgRbNow - lastSgCaptureReadbackMs >= 1000) {
                    lastSgCaptureReadbackMs = sgRbNow;
                    try {
                        java.nio.FloatBuffer px = BufferUtils.createFloatBuffer(3);
                        GL45C.glGetTextureSubImage(
                            slot.colorTex, 0, w / 2, h / 2, 0, 1, 1, 1,
                            GL11.GL_RGB, GL11.GL_FLOAT, px);
                        LOGGER.info("[Seamless Portals] [IS5-WASHPROBE] SG capture center px"
                                + " rgb=({}, {}, {}) (the washout window's OWN capture,"
                                + " post-dest-c5 pre-AA)",
                            String.format("%.4f", px.get(0)), String.format("%.4f", px.get(1)),
                            String.format("%.4f", px.get(2)));
                    } catch (Throwable ignored) {
                    }
                    while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* never poison */ }
                }
            }
            // IS5-WASHPROBE dest half: read the DEST bloom-apply pass's executed uniforms
            // (c5 = destGather+1) right after it ran; paired with the main half at the next
            // meas emit.
            if (IPGlobal.washProbe && postPendingDestRenderer instanceof CompositeRenderer dcr) {
                long wpNow = System.currentTimeMillis();
                if (wpNow - lastWashProbeMs >= 1000) {
                    // (the 1Hz latch advances on the MAIN half so both lines pair per window)
                    RestampMeasurement dm = measureRestamp(dcr);
                    // v3 (the v2 comparator was INVERTED and burned a session: slot.cameraPos
                    // is the arm-time MAIN camera by construction — aperture-mesh registration
                    // — so "camP1 != vcam" was the HEALTHY state. Adjudicated 2026-08-16:
                    // destP1 - armCam == the portal offset bit-exactly, camera path CLEAN.)
                    // The honest reference is the portal-TRANSFORMED arm camera: camPN should
                    // EQUAL xform; equalling armCam instead would be the wrong-camera defect.
                    String camRef;
                    if (slot.cameraPos == null) {
                        camRef = "armCam=? xform=?";
                    } else if (slot.portal != null) {
                        net.minecraft.world.phys.Vec3 xf =
                            slot.portal.transformPoint(slot.cameraPos);
                        camRef = String.format(
                            "armCam=(%.1f,%.1f,%.1f) xform=(%.1f,%.1f,%.1f)",
                            slot.cameraPos.x, slot.cameraPos.y, slot.cameraPos.z,
                            xf.x, xf.y, xf.z);
                    } else {
                        camRef = String.format("armCam=(%.1f,%.1f,%.1f) xform=?",
                            slot.cameraPos.x, slot.cameraPos.y, slot.cameraPos.z);
                    }
                    washProbeDestLine = washProbeReadPass(dcr, dm.gatherIdx + 1, "destC5")
                        + " | " + washProbeCamScan(dcr, "dest") + " vs " + camRef
                        + " | " + washProbeRdScan(dcr, "dest")
                        + " | " + washProbeResProj(dcr, "dest");
                }
            }
        } catch (Throwable t) {
            censusSgF++;
            noteAuxDropOnce("SG boundary capture threw: " + t + " — POST fallback");
        }
    }

    // IS5-WASHPROBE (lever -PwashProbe, log-only): dest-vs-main bloom-apply uniform diff.
    // Reads the EXECUTED uniform state off the pass program (the measure-at-the-draw rule);
    // loc -1 prints a LOUD n/a, never a tabulatable zero (the sentinel discipline).
    private static long lastWashProbeMs = 0;
    private static long lastSgCaptureReadbackMs = 0;
    private static long lastInjectReadbackMs = 0;
    private static long lastWashAdjudicatorMs = 0;
    private static long lastMainPreInjectMs = 0;
    private static String washProbeDestLine = null;

    private static String washProbeReadPass(Object renderer, int passIdx, String tag) {
        try {
            List<?> passes = (List<?>) fPasses.get(renderer);
            if (passIdx < 0 || passIdx >= passes.size()) return tag + ": pass-oob(" + passIdx + ")";
            Object prog = fPassProgram.get(passes.get(passIdx));
            if (prog == null) return tag + ": compute-only";
            int pid = ((net.irisshaders.iris.gl.program.Program) prog).getProgramId();
            StringBuilder s = new StringBuilder(tag).append('[').append(passIdx).append("]:");
            int locRd = GL20C.glGetUniformLocation(pid, "renderDistance");
            if (locRd >= 0) {
                float[] rd = new float[1];
                GL20C.glGetUniformfv(pid, locRd, rd);
                s.append(" rd=").append(String.format("%.1f", rd[0]));
            } else s.append(" rd=n/a(loc-1)");
            int locFar = GL20C.glGetUniformLocation(pid, "far");
            if (locFar >= 0) {
                float[] fr = new float[1];
                GL20C.glGetUniformfv(pid, locFar, fr);
                s.append(" far=").append(String.format("%.1f", fr[0]));
            } else s.append(" far=n/a(loc-1)");
            int locEye = GL20C.glGetUniformLocation(pid, "isEyeInWater");
            if (locEye >= 0) {
                int[] eye = new int[1];
                GL20C.glGetUniformiv(pid, locEye, eye);
                s.append(" eye=").append(eye[0]);
            } else s.append(" eye=n/a(loc-1)");
            int locCam = GL20C.glGetUniformLocation(pid, "cameraPosition");
            if (locCam >= 0) {
                float[] cam = new float[3];
                GL20C.glGetUniformfv(pid, locCam, cam);
                s.append(String.format(" cam=(%.1f,%.1f,%.1f)", cam[0], cam[1], cam[2]));
            } else s.append(" cam=n/a(loc-1)");
            return s.toString();
        } catch (Throwable t) {
            return tag + ": read-failed(" + t + ")";
        }
    }

    /** v3: per-pass renderDistance/far sweep — the (lViewPos/clamp(min(renderDistance,LIMIT),
     *  96,512))^3 multiply lives in composite1; a shrunken dest renderDistance there is a
     *  64x-class over-multiply nothing downstream cancels. '-' = inactive (stripped). */
    private static String washProbeRdScan(Object renderer, String tag) {
        try {
            List<?> passes = (List<?>) fPasses.get(renderer);
            StringBuilder s = new StringBuilder(tag).append(" rdScan:");
            for (int i = 0; i < passes.size(); i++) {
                Object prog = fPassProgram.get(passes.get(i));
                if (prog == null) {
                    s.append(" P").append(i).append("=comp");
                    continue;
                }
                int pid = ((net.irisshaders.iris.gl.program.Program) prog).getProgramId();
                int locRd = GL20C.glGetUniformLocation(pid, "renderDistance");
                int locFar = GL20C.glGetUniformLocation(pid, "far");
                s.append(" P").append(i).append('=');
                if (locRd >= 0) {
                    float[] v = new float[1];
                    GL20C.glGetUniformfv(pid, locRd, v);
                    s.append("rd").append(String.format("%.0f", v[0]));
                } else s.append('-');
                s.append('/');
                if (locFar >= 0) {
                    float[] v = new float[1];
                    GL20C.glGetUniformfv(pid, locFar, v);
                    s.append("far").append(String.format("%.0f", v[0]));
                } else s.append('-');
            }
            return s.toString();
        } catch (Throwable t) {
            return tag + " rdScan=read-failed(" + t + ")";
        }
    }

    /** v3b: resolution + projection fingerprint from the first pass holding each —
     *  viewWidth/viewHeight scale the bloom tile coordinates (a mismatch mis-samples the
     *  pyramid); projInv[0][0]/[1][1] are the FOV terms driving every depth→distance
     *  reconstruction. */
    private static String washProbeResProj(Object renderer, String tag) {
        try {
            List<?> passes = (List<?>) fPasses.get(renderer);
            String res = null;
            String proj = null;
            for (int i = 0; i < passes.size() && (res == null || proj == null); i++) {
                Object prog = fPassProgram.get(passes.get(i));
                if (prog == null) continue;
                int pid = ((net.irisshaders.iris.gl.program.Program) prog).getProgramId();
                if (res == null) {
                    int locW = GL20C.glGetUniformLocation(pid, "viewWidth");
                    int locH = GL20C.glGetUniformLocation(pid, "viewHeight");
                    if (locW >= 0 && locH >= 0) {
                        float[] w = new float[1];
                        float[] h = new float[1];
                        GL20C.glGetUniformfv(pid, locW, w);
                        GL20C.glGetUniformfv(pid, locH, h);
                        res = String.format("view%d=%.0fx%.0f", i, w[0], h[0]);
                    }
                }
                if (proj == null) {
                    int locPi = GL20C.glGetUniformLocation(pid, "gbufferProjectionInverse");
                    if (locPi >= 0) {
                        float[] m = new float[16];
                        GL20C.glGetUniformfv(pid, locPi, m);
                        proj = String.format("projInv%d=[%.4f,%.4f]", i, m[0], m[5]);
                    }
                }
            }
            return tag + " " + (res == null ? "view=NOWHERE" : res)
                + " " + (proj == null ? "projInv=NOWHERE" : proj);
        } catch (Throwable t) {
            return tag + " resProj=read-failed(" + t + ")";
        }
    }

    /** v3: scan ALL passes for the first ACTIVE cameraPosition and report it with its pass
     *  index — c5 strips it on both chains; the storm/multiply pass (c1) is the expected
     *  holder. The caller pairs it with "armCam" (slot.cameraPos = the arm-time MAIN camera,
     *  aperture-mesh registration) AND "xform" (the portal-transformed arm camera): the dest
     *  chain is HEALTHY when camPN ≈ xform, and wrong-camera-defective when camPN ≈ armCam.
     *  (The v2 comment had this inverted — it called slot.cameraPos "the KNOWN virtual
     *  camera" and read a mismatch as the culprit; adjudicated clean 2026-08-16.) */
    private static String washProbeCamScan(Object renderer, String tag) {
        try {
            List<?> passes = (List<?>) fPasses.get(renderer);
            for (int i = 0; i < passes.size(); i++) {
                Object prog = fPassProgram.get(passes.get(i));
                if (prog == null) continue;
                int pid = ((net.irisshaders.iris.gl.program.Program) prog).getProgramId();
                int locCam = GL20C.glGetUniformLocation(pid, "cameraPosition");
                if (locCam < 0) continue;
                float[] cam = new float[3];
                GL20C.glGetUniformfv(pid, locCam, cam);
                return String.format("%s camP%d=(%.1f,%.1f,%.1f)", tag, i, cam[0], cam[1], cam[2]);
            }
            return tag + " cam=NOWHERE-ACTIVE";
        } catch (Throwable t) {
            return tag + " cam=read-failed(" + t + ")";
        }
    }

    // IS5-WASH state: one pooled scratch (size/format-keyed — the ensureSlotStorage idiom;
    // holds a full c0 copy across the two bracket halves of ONE renderAll) + the permanent
    // wash disarm (a restore failure after a blackout must not recur — the mask's `broken`
    // idiom; the window face under SG is repainted by the inject regardless, POST-fallback
    // frames would show the blackout otherwise).
    private static int washScratchId = 0;
    private static int washScratchW = -1, washScratchH = -1, washScratchFmt = 0;
    private static boolean washBroken = false;
    private static int censusWashB, censusWashR;

    /**
     * IS5-WASH §1.10 — the gather-exclusion bracket. Save half: copy c0's gather-read side to
     * the scratch, then black out the POST/SG entries' window footprints (visibility-clipped,
     * mode 3) so the source bloom gather harvests ZERO energy from dest-window content (the
     * user-adjudicated lava/high-angle washout: ambient control clean + bloom-off vanishes it
     * = the source gather is the carrier; dest-baked bloom in the capture is the correct glow
     * and stays). Restore half (next boundary, before bloom-apply reads c0): repaint the same
     * footprints from the scratch (mode 4). MUTATE-LAST: the scratch copy precedes the first
     * destructive draw; a save-half failure skips the blackout entirely (washout persists one
     * frame, census-visible); a restore failure permanently disarms the wash (never
     * breakMechanism — the stamp semantics are intact).
     */
    private static void runWashBracket(RestampArm arm, boolean doSave, boolean doRestore) {
        if (washBroken || arm.washTexId == 0) return;
        if (!ensureStampProgram()) return;
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate */ }
        boolean cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean clampOn = false;
        GlFramebuffer fbo = null;
        try {
            if (doSave) {
                int fmt = GL45C.glGetTextureLevelParameteri(
                    arm.washTexId, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                if (washScratchId == 0 || washScratchW != arm.w || washScratchH != arm.h
                    || washScratchFmt != fmt) {
                    if (washScratchId != 0) GL11.glDeleteTextures(washScratchId);
                    washScratchId = 0;
                    int t = GL45C.glCreateTextures(GL11.GL_TEXTURE_2D);
                    GL45C.glTextureStorage2D(t, 1, fmt, arm.w, arm.h);
                    if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                        GL11.glDeleteTextures(t);
                        arm.washRestoreDone = true; // nothing blacked out, nothing to restore
                        noteAuxDropOnce("IS5-WASH scratch alloc rejected — bracket skipped");
                        return;
                    }
                    washScratchId = t;
                    washScratchW = arm.w;
                    washScratchH = arm.h;
                    washScratchFmt = fmt;
                }
                GL43C.glCopyImageSubData(
                    arm.washTexId, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                    washScratchId, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, arm.w, arm.h, 1);
                if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                    arm.washRestoreDone = true; // MUTATE-LAST: no blackout without the save
                    noteAuxDropOnce("IS5-WASH scratch copy failed — bracket skipped");
                    return;
                }
            }
            fbo = new GlFramebuffer();
            fbo.addColorAttachment(0, arm.washTexId);
            fbo.drawBuffers(new int[]{0});
            fbo.bind();
            GlStateManager._viewport(0, 0, arm.w, arm.h);
            GlStateManager._disableScissorTest();
            GlStateManager._disableBlend(0);
            GlStateManager._disableCull();
            GlStateManager._colorMask(15);
            GlStateManager._disableDepthTest(); // colour-only FBO; the discard is in-shader
            if (!IPGlobal.debugNoStampDepthClamp) {
                CHelper.enableDepthClamp(); // footprint parity with the stamp's rasterization
                clampOn = true;
            }
            GlStateManager._glUseProgram(stampProgram);
            GL20C.glUniform1i(locCapture, 0);
            GL20C.glUniform1i(locStampedDepth0, 4);
            GL20C.glUniform1f(locSolid, 0.0f);
            GL20C.glUniform1f(locHavePrev, 0.0f);
            GL20C.glUniform1f(locZeroAlpha, 0.0f); // asserted per site
            GL20C.glUniform1f(locFadeW, 1.0f); // FARFADE F10: asserted per site
            GL20C.glUniform1i(locPrevGraded, 5);
            GL20C.glUniform1i(locDepthMode, doSave ? 3 : 4);
            matBuf.clear();
            arm.combined.get(matBuf);
            GL20C.glUniformMatrix4fv(locCombined, false, matBuf);
            GlStateManager._activeTexture(GL13.GL_TEXTURE4);
            GlStateManager._bindTexture(arm.mainDepth0Id);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(washScratchId); // mode 4 samples it; mode 3 ignores
            for (RestampEntry e : arm.entries) {
                if (!e.slot.postFinalMode) continue; // PRE windows keep their source glow
                if (e.stampedPre) continue; // §5 F8: PRE-stamped this frame = same rule
                ((GlDevice) ((GpuDeviceAccessor) RenderSystem.getDevice()).getBackend())
                    .vertexArrayCache().bindVertexArray(
                        new VertexFormat[]{DefaultVertexFormat.POSITION_COLOR},
                        new GpuBufferSlice[]{e.vertexSlice},
                        null
                    );
                GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, e.vertexCount);
                if (doSave) censusWashB++; else censusWashR++;
            }
        } finally {
            if (clampOn) {
                CHelper.disableDepthClamp();
            }
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._disableDepthTest();
            FullScreenQuadRenderer.INSTANCE.bind();
            if (cullWasEnabled) {
                GlStateManager._enableCull();
            }
            if (fbo != null) {
                try { fbo.destroy(); } catch (Throwable ignored) {}
            }
        }
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            if (doRestore) {
                washBroken = true;
                LOGGER.warn("[Seamless Portals] [IS5-WASH] restore half left GL error 0x{}"
                    + " — the wash bracket is DISARMED for the session (washout returns;"
                    + " window faces under SG stay correct via the inject)",
                    Integer.toHexString(err));
            } else {
                // The blackout may have partially executed — the scratch save succeeded
                // BEFORE any draw (mutate-last), so let the restore half run and self-heal;
                // do NOT mark it done.
                noteAuxDropOnce("IS5-WASH blackout half left GL error 0x"
                    + Integer.toHexString(err) + " — restore half will repaint from scratch");
            }
        }
    }

    /**
     * Branch (b): the depthtex1 CONTENT restamp (mode 1, one depth-only draw per retained
     * entry) + the SG inject (mode 2, colour-only, for sgCaptured non-nested slots). Failure ⇒
     * breakMechanism — the HEAD PLANE stays = a shipped-safe degraded frame (design §1.6).
     * Setup obligations ⟦J⟧: our draws assert their OWN viewport/scissor (pass i re-establishes
     * its own AFTER us, but we inherit whatever pass i-1 left) and carry their OWN depth-clamp
     * bracket (runStampPass's bracket is scoped to its try/finally).
     */
    private static void runRestampBoundaryDraws(RestampArm arm, boolean doDepth, boolean doInject) {
        doDepth = doDepth && arm.depth1Id != 0;
        doInject = doInject && arm.injectTexId != 0;
        if (arm.entries.isEmpty() || (!doDepth && !doInject)) return;
        if (!ensureStampProgram()) return;
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate */ }
        boolean cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean clampOn = false;
        GlFramebuffer d1Fbo = null;
        GlFramebuffer injFbo = null;
        long t0 = System.nanoTime();
        try {
            if (doDepth) {
                d1Fbo = new GlFramebuffer();
                d1Fbo.addDepthAttachmentBypass(arm.depth1Id);
                d1Fbo.noDrawBuffers();
            }
            GlStateManager._viewport(0, 0, arm.w, arm.h);
            GlStateManager._disableScissorTest();
            GlStateManager._disableBlend(0);
            GlStateManager._disableCull();
            GlStateManager._colorMask(15);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_ALWAYS); // the CONTENT replay's executed func
            GlStateManager._depthMask(true);
            if (!IPGlobal.debugNoStampDepthClamp) {
                CHelper.enableDepthClamp();
                clampOn = true;
            }
            GlStateManager._glUseProgram(stampProgram);
            GL20C.glUniform1i(locCapture, 0);
            GL20C.glUniform1i(locCaptureAux, 1);
            GL20C.glUniform1i(locCapturePrev, 2);
            GL20C.glUniform1i(locCaptureDepth, 3);
            GL20C.glUniform1i(locStampedDepth0, 4);
            GL20C.glUniform1f(locSolid, IPGlobal.debugStampSolid ? 1.0f : 0.0f);
            GL20C.glUniform1f(locHavePrev, 0.0f);
            GL20C.glUniform1f(locZeroAlpha, 0.0f); // asserted per site
            GL20C.glUniform1f(locFadeW, 1.0f); // FARFADE F10: asserted per site
            GL20C.glUniform1i(locPrevGraded, 5);
            matBuf.clear();
            arm.combined.get(matBuf);
            GL20C.glUniformMatrix4fv(locCombined, false, matBuf);
            GlStateManager._activeTexture(GL13.GL_TEXTURE4);
            GlStateManager._bindTexture(arm.mainDepth0Id);
            // --- mode 1: the CONTENT depth restamp into depthtex1, per entry ---
            if (doDepth) {
                GL20C.glUniform1i(locDepthMode, 1);
                d1Fbo.bind();
                for (RestampEntry e : arm.entries) {
                    if (e.slot.depthTex == 0) continue;
                    GlStateManager._activeTexture(GL13.GL_TEXTURE3);
                    GlStateManager._bindTexture(e.slot.depthTex);
                    GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                    ((GlDevice) ((GpuDeviceAccessor) RenderSystem.getDevice()).getBackend())
                        .vertexArrayCache().bindVertexArray(
                            new VertexFormat[]{DefaultVertexFormat.POSITION_COLOR},
                            new GpuBufferSlice[]{e.vertexSlice},
                            null
                        );
                    GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, e.vertexCount);
                    censusRst++;
                }
            }
            // --- mode 2: the SG inject into the source anchor's image READ side ---
            if (doInject) {
                // IS5-WASHPROBE v3 — capture-vs-main comparator, the MAIN half: the anchor
                // image's centre pixel BEFORE any inject draw = the main chain's own post-c5
                // display-referred value (same processing stage as the SG capture). With the
                // player IN the dest dim aiming at the lava directly (no window at centre),
                // this is the ground-truth main render of the exact content the capture shows
                // from the other side. Compare vs "SG capture center px" at the mirrored aim
                // across a crossing: capture >> main at matched aim = the capture itself is
                // over-bright (the post-bracket-exoneration standing suspect).
                if (IPGlobal.washProbe && IPGlobal.is5LiveReadbacks && arm.injectTexId != 0
                    && System.currentTimeMillis() - lastMainPreInjectMs >= 1000) {
                    lastMainPreInjectMs = System.currentTimeMillis();
                    try {
                        java.nio.FloatBuffer mp = BufferUtils.createFloatBuffer(4);
                        GL45C.glGetTextureSubImage(
                            arm.injectTexId, 0, arm.w / 2, arm.h / 2, 0, 1, 1, 1,
                            GL11.GL_RGBA, GL11.GL_FLOAT, mp);
                        LOGGER.info("[Seamless Portals] [IS5-WASHPROBE] main-preinject center"
                                + " px rgb=({}, {}, {}) (main post-c5 display-referred, BEFORE"
                                + " the inject — pair with 'SG capture center px' at the"
                                + " mirrored aim across the crossing)",
                            String.format("%.4f", mp.get(0)), String.format("%.4f", mp.get(1)),
                            String.format("%.4f", mp.get(2)));
                    } catch (Throwable ignored) {
                    }
                    while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* never poison */ }
                }
                boolean blendSnapDone = false;
                boolean blendSnapOk = false;
                for (RestampEntry e : arm.entries) {
                    // R11-MASK: nested-child pixels are excluded per-PIXEL by the alpha
                    // discard in the shader — no whole-slot skip (the skip was the
                    // log-adjudicated near-portal dim-window: nest>0 collapsed sgI).
                    if (!e.slot.sgCaptured) continue;
                    if (injFbo == null) {
                        injFbo = new GlFramebuffer();
                        injFbo.addColorAttachment(0, arm.injectTexId);
                        injFbo.drawBuffers(new int[]{0});
                        GL20C.glUniform1i(locDepthMode, 2);
                        injFbo.bind();
                    }
                    // IS5-FARFADE (§5 F3/F5-v/F10): PRE-stamped entries take the BLEND —
                    // one anchor-image snapshot per invocation into the blend's own scratch
                    // (taken lazily before the FIRST blended entry: the pre-inject state is
                    // identical for all entries — windows are per-pixel disjoint under the
                    // visibility discard). Snapshot failure ⇒ pure inject for this frame
                    // (shipped SG face, mutate-last), fwF-visible. u_fadeW is set PER ENTRY
                    // (mixed near/far frames — two portals at different distances).
                    float fw = 1f;
                    if (e.stampedPre) {
                        if (!blendSnapDone) {
                            blendSnapDone = true;
                            blendSnapOk = snapshotAnchorForBlend(arm);
                            if (!blendSnapOk) censusFwF++;
                        }
                        if (blendSnapOk) {
                            fw = e.slot.fadeW;
                        }
                    }
                    GL20C.glUniform1f(locFadeW, fw);
                    if (fw < 1f) {
                        GlStateManager._activeTexture(GL13.GL_TEXTURE5);
                        GlStateManager._bindTexture(blendScratchId);
                        censusFwB++;
                    }
                    GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                    GlStateManager._bindTexture(e.slot.colorTex);
                    ((GlDevice) ((GpuDeviceAccessor) RenderSystem.getDevice()).getBackend())
                        .vertexArrayCache().bindVertexArray(
                            new VertexFormat[]{DefaultVertexFormat.POSITION_COLOR},
                            new GpuBufferSlice[]{e.vertexSlice},
                            null
                        );
                    GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, e.vertexCount);
                    censusSgI++;
                    // IS5-WASHPROBE inject-landing comparator (declared-vs-executed): the
                    // TARGET's centre pixel right after EACH entry's draw vs THAT entry's
                    // capture — per-entry with the portal id (the first-entry-only version
                    // silently read a DIFFERENT portal's grey capture — the aim lesson again).
                    // A DIFF on a lava-red capture = the inject silently failing at centre; a
                    // DIFF on a non-window centre pixel proves nothing (the mesh only covers
                    // window pixels). 1Hz per entry, probe-only.
                    if (IPGlobal.washProbe && IPGlobal.is5LiveReadbacks
                        && System.currentTimeMillis() - lastInjectReadbackMs >= 1000) {
                        lastInjectReadbackMs = System.currentTimeMillis(); // the dropped latch
                        try {
                            java.nio.FloatBuffer tp = BufferUtils.createFloatBuffer(4);
                            java.nio.FloatBuffer cp = BufferUtils.createFloatBuffer(4);
                            GL45C.glGetTextureSubImage(
                                arm.injectTexId, 0, arm.w / 2, arm.h / 2, 0, 1, 1, 1,
                                GL11.GL_RGBA, GL11.GL_FLOAT, tp);
                            GL45C.glGetTextureSubImage(
                                e.slot.colorTex, 0, arm.w / 2, arm.h / 2, 0, 1, 1, 1,
                                GL11.GL_RGBA, GL11.GL_FLOAT, cp);
                            boolean match = Math.abs(tp.get(0) - cp.get(0)) < 0.02f
                                && Math.abs(tp.get(1) - cp.get(1)) < 0.02f
                                && Math.abs(tp.get(2) - cp.get(2)) < 0.02f;
                            // capA is THE adjudicator: 0 = the R11-MASK nested footprint at
                            // centre (the inject DISCARDS there by design — if DIFF frames all
                            // show capA=0, the veil IS the mask's discard region); 1 = the
                            // discard is innocent and a DIFF is a true landing failure.
                            // FARFADE F11 (instrument honesty): fw printed; adjudicate MATCH
                            // only at fw=1.00 — a w<1 target!=capture is the BLEND working.
                            LOGGER.info("[Seamless Portals] [IS5-WASHPROBE] inject-landing"
                                    + " P{}: target=({}, {}, {}) capture=({}, {}, {})"
                                    + " capA={} fw={} => {}",
                                e.slot.portal == null ? 0
                                    : (System.identityHashCode(e.slot.portal) % 1000),
                                String.format("%.4f", tp.get(0)),
                                String.format("%.4f", tp.get(1)),
                                String.format("%.4f", tp.get(2)),
                                String.format("%.4f", cp.get(0)),
                                String.format("%.4f", cp.get(1)),
                                String.format("%.4f", cp.get(2)),
                                String.format("%.2f", cp.get(3)),
                                String.format("%.2f", fw),
                                match ? "MATCH (inject lands)"
                                    : "DIFF (not landing OR centre not this window OR blended)");
                        } catch (Throwable ignored) {
                        }
                        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* never poison */ }
                    }
                }
            }
            // 1Hz post-restamp comparator (kill-check 6): centre texel of depthtex1 AFTER the
            // boundary draw — DIFF vs plane expected against an open dest vista. Gated
            // ATOMICALLY with its glGetError drain (the IPGlobal readback rule).
            if (IPGlobal.is5LiveReadbacks && doDepth) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastBoundaryReadbackMs >= 1000) {
                    lastBoundaryReadbackMs = nowMs;
                    try {
                        java.nio.FloatBuffer d1px = BufferUtils.createFloatBuffer(1);
                        GL45C.glGetTextureSubImage(
                            arm.depth1Id, 0, arm.w / 2, arm.h / 2, 0, 1, 1, 1,
                            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, d1px);
                        LOGGER.info("[Seamless Portals] [IS5-RESTAMP] boundary d1 center={}"
                                + " (CONTENT semantics: DIFF vs plane expected at an open"
                                + " window centre)",
                            String.format("%.6f", d1px.get(0)));
                    } catch (Throwable ignored) {
                    }
                    while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* never poison */ }
                }
            }
        } finally {
            if (clampOn) {
                CHelper.disableDepthClamp();
            }
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._disableDepthTest();
            FullScreenQuadRenderer.INSTANCE.bind();
            if (cullWasEnabled) {
                GlStateManager._enableCull();
            }
            if (d1Fbo != null) {
                try { d1Fbo.destroy(); } catch (Throwable ignored) {}
            }
            if (injFbo != null) {
                try { injFbo.destroy(); } catch (Throwable ignored) {}
            }
            PerfTimers.add("is5.restampBoundary", System.nanoTime() - t0);
        }
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            breakMechanism("restamp boundary draw left GL error 0x"
                + Integer.toHexString(err), null);
        }
    }

    private static int stampProgram = 0;
    private static int locCombined = -1;
    private static int locCapture = -1;
    private static int locCaptureAux = -1;
    private static int locCapturePrev = -1;
    private static int locCaptureDepth = -1;
    private static int locStampedDepth0 = -1;
    private static int locSolid = -1;
    private static int locHavePrev = -1;
    private static int locDepthMode = -1;
    private static int locZeroAlpha = -1;
    // IS5-FARFADE (§5 F3/F10/F11): the blend uniforms, the blend's OWN pooled snapshot
    // scratch (keyed to the measured anchor-image format — NEVER washScratch: format thrash
    // on mixed frames + a shared scratch between wash-save and wash-restore would destroy
    // the saved c0), and the fw census (fwB = blend draws, fwF = per-slot w:=1 collapses;
    // fwMin/MaxSeen = the meas line's fw= range, reset at each 1Hz emit).
    private static int locPrevGraded = -1;
    private static int locFadeW = -1;
    private static int blendScratchId = 0;
    private static int blendScratchW = -1, blendScratchH = -1, blendScratchFmt = 0;
    private static int censusFwB = 0, censusFwF = 0;
    private static float fwMinSeen = 2f, fwMaxSeen = -1f;
    private static final FloatBuffer matBuf = BufferUtils.createFloatBuffer(16);

    /** IS5-FARFADE (§5 F3/F5-v): one anchor-image snapshot per boundary invocation, into the
     *  blend's own pooled scratch. Any failure ⇒ false ⇒ the caller falls back to the pure
     *  inject for this invocation (mutate-last: nothing destructive happened). */
    private static boolean snapshotAnchorForBlend(RestampArm arm) {
        try {
            int fmt = GL45C.glGetTextureLevelParameteri(
                arm.injectTexId, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (blendScratchId == 0 || blendScratchW != arm.w || blendScratchH != arm.h
                || blendScratchFmt != fmt) {
                if (blendScratchId != 0) GL11.glDeleteTextures(blendScratchId);
                blendScratchId = 0;
                while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate */ }
                int t = GL45C.glCreateTextures(GL11.GL_TEXTURE_2D);
                GL45C.glTextureStorage2D(t, 1, fmt, arm.w, arm.h);
                if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                    GL11.glDeleteTextures(t);
                    return false;
                }
                blendScratchId = t;
                blendScratchW = arm.w;
                blendScratchH = arm.h;
                blendScratchFmt = fmt;
            }
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate */ }
            GL43C.glCopyImageSubData(
                arm.injectTexId, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                blendScratchId, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, arm.w, arm.h, 1);
            return GL11.glGetError() == GL11.GL_NO_ERROR;
        } catch (Throwable t) {
            return false;
        }
    }

    // IS5-HIST: per-portal PREVIOUS-frame capture store {texId, w, h, fmt}. STRONG keys with
    // explicit eviction (a WeakHashMap would GC entries silently and LEAK the GL textures —
    // the §8-20 teardown class): beginFrame evicts removed portals and bounds the map at 16
    // (eldest first), deleting textures on the render thread. Updated AFTER each slot's stamp
    // draw (the draw reads the OLD prev = last frame's content; then prev ← this frame's).
    private static final java.util.LinkedHashMap<Portal, int[]> prevCaptureByPortal =
        new java.util.LinkedHashMap<>();

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
        locCaptureAux = GL20C.glGetUniformLocation(prog, "u_captureAux");
        locCapturePrev = GL20C.glGetUniformLocation(prog, "u_capturePrev");
        locCaptureDepth = GL20C.glGetUniformLocation(prog, "u_captureDepth");
        locStampedDepth0 = GL20C.glGetUniformLocation(prog, "u_stampedDepth0");
        locSolid = GL20C.glGetUniformLocation(prog, "u_solid");
        locHavePrev = GL20C.glGetUniformLocation(prog, "u_havePrev");
        locDepthMode = GL20C.glGetUniformLocation(prog, "u_depthMode");
        locZeroAlpha = GL20C.glGetUniformLocation(prog, "u_zeroAlpha");
        locPrevGraded = GL20C.glGetUniformLocation(prog, "u_prevGraded");
        locFadeW = GL20C.glGetUniformLocation(prog, "u_fadeW");
        return true;
    }

    /** Stamp-target FBO cache — keyed on PIPELINE IDENTITY + texture names, never names alone.
     *  THE S6 LENS-FLARE LATCH (leg 5, user: window "disappeared and turning lens flare off did
     *  not bring portal back"): a pack-option rebuild deletes and recreates iris's textures, the
     *  driver RECYCLES the freed GL names, a name-only key then keeps the OLD GlFramebuffer —
     *  whose attachment still references the ORPHANED old texture object (kept alive by the
     *  attachment reference; the FBO stays COMPLETE). The stamp then writes into the orphan:
     *  valid GL, zero errors, census all green, window invisible, latched until restart. The
     *  bloom mask dodges this exact trap by nuking its fboCache on every plan rebuild; the
     *  pipeline-identity key is the same discipline (textures cannot be recycled WITHIN one
     *  pipeline's lifetime). */
    private static GlFramebuffer stampFbo = null;        // colortex0 + depthtex0, drawBuffers {0}
    private static GlFramebuffer stampFboAux = null;     // + aux target(s); null when main aux absent
    private static GlFramebuffer stampFboDepth1 = null;  // depthtex1 only (PART5 MB-ghost fix)
    private static GlFramebuffer stampFboDepth2 = null;  // depthtex2 only

    private static void destroyStampFbos() {
        for (GlFramebuffer f : new GlFramebuffer[]{stampFbo, stampFboAux, stampFboDepth1, stampFboDepth2}) {
            if (f != null) {
                try { f.destroy(); } catch (Throwable ignored) {}
            }
        }
        stampFbo = null;
        stampFboAux = null;
        stampFboDepth1 = null;
        stampFboDepth2 = null;
    }

    /** Rebuilds the FBO family EVERY STAMP PASS — no cross-frame caching AT ALL. Two latches
     *  taught this: the lens-flare rebuild (pipeline changes, names recycled — fixed by identity
     *  keying) and then the part5 leg's RESIZE/fullscreen latch (pipeline UNCHANGED, textures
     *  recreated, names recycled ⇒ identity+name key HITS on an FBO attached to the ORPHANED
     *  old-size textures; stamps valid, GL-clean, census green, window invisible, fresh world
     *  irrelevant). Attachment references cannot be validated cheaply, so the only bulletproof
     *  key is NO key: four small FBO builds per frame, no storage allocation — negligible.
     *  auxTex entries of 0 or a missing depth1/2 id simply omit that FBO — never fatal. */
    private static void ensureStampFbos(
        Object pipeline, int colorTex, int depthTex, int[] mainAuxTex, int histTex,
        int depth1Id, int depth2Id
    ) {
        destroyStampFbos();
        GlFramebuffer plain = new GlFramebuffer();
        plain.addColorAttachment(0, colorTex);
        // javap-pinned: addDepthAttachment takes a GpuTexture; the raw-id variant is the Bypass.
        plain.addDepthAttachmentBypass(depthTex);
        plain.drawBuffers(new int[]{0});
        stampFbo = plain;
        boolean allAuxPresent = histTex != 0;
        for (int t : mainAuxTex) {
            if (t == 0) allAuxPresent = false;
        }
        if (allAuxPresent && mainAuxTex.length > 0) {
            // out0→att0 (colortex0), out1→att[AUX] (materialMask), out2→att[HISTORY] (the TAA
            // ghost fix). All-or-nothing by design: a partial MRT would need GL_NONE drawBuffers
            // entries whose support in GlFramebuffer.drawBuffers is unverified — Complementary
            // always resolves all three; other packs fall back to the plain stamp (window intact,
            // enhancements absent).
            GlFramebuffer aux = new GlFramebuffer();
            aux.addColorAttachment(0, colorTex);
            int[] db = new int[2 + AUX_TARGETS.length];
            db[0] = 0;
            for (int i = 0; i < AUX_TARGETS.length; i++) {
                aux.addColorAttachment(AUX_TARGETS[i], mainAuxTex[i]);
                db[1 + i] = AUX_TARGETS[i];
            }
            aux.addColorAttachment(HISTORY_TARGET, histTex);
            db[1 + AUX_TARGETS.length] = HISTORY_TARGET;
            aux.addDepthAttachmentBypass(depthTex);
            aux.drawBuffers(db);
            stampFboAux = aux;
        }
        if (depth1Id != 0) {
            GlFramebuffer d1 = new GlFramebuffer();
            d1.addDepthAttachmentBypass(depth1Id);
            d1.noDrawBuffers();
            stampFboDepth1 = d1;
        }
        if (depth2Id != 0) {
            GlFramebuffer d2 = new GlFramebuffer();
            d2.addDepthAttachmentBypass(depth2Id);
            d2.noDrawBuffers();
            stampFboDepth2 = d2;
        }
    }

    private static void runStampPass(
        IrisRenderingPipeline mainPipeline, CompositeRenderer mainCompositeRenderer
    ) throws IllegalAccessException {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate */ }
        Set<?> flipSet = resolvePassZeroFlipSet((List<?>) fPasses.get(mainCompositeRenderer));
        if (flipSet == null) {
            breakMechanism("no real composite pass 0 on the MAIN pipeline", null);
            return;
        }
        boolean writeAlt = flipSet.contains(0);
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
        // PART5: main-side aux write textures (same flip-set parity as c0; 0 = absent) and the
        // depthtex1/2 snapshot ids (the MB-ghost fix targets; 0 = absent, draws skipped).
        int[] mainAuxTex = new int[AUX_TARGETS.length];
        for (int i = 0; i < AUX_TARGETS.length; i++) {
            RenderTarget auxRt = rts.get(AUX_TARGETS[i]);
            if (auxRt != null) {
                mainAuxTex[i] = flipSet.contains(AUX_TARGETS[i])
                    ? auxRt.getAltTexture() : auxRt.getMainTexture();
            }
        }
        int depth1Id = rts.getDepthTextureNoTranslucents() instanceof GlTexture d1 ? d1.glId() : 0;
        int depth2Id = rts.getDepthTextureNoHand() instanceof GlTexture d2 ? d2.glId() : 0;
        // TAA history (colortex2) READ side — pass-0 parity holds at the TAA pass (no earlier
        // composite writes it, measured).
        int histTex = 0;
        RenderTarget histRt = rts.get(HISTORY_TARGET);
        if (histRt != null) {
            histTex = flipSet.contains(HISTORY_TARGET)
                ? histRt.getAltTexture() : histRt.getMainTexture();
        }
        if (!ensureStampProgram()) return;
        ensureStampFbos(
            mainPipeline, targetColor, depthGl.glId(), mainAuxTex, histTex, depth1Id, depth2Id);
        GlFramebuffer fbo = stampFbo;

        // IS5-RESTAMP mode decision (IS5_RESTAMP_DESIGN.md §1.2-§1.3). The MEASUREMENT runs
        // regardless of all levers — detector-reads-raw, one leg proves mechanism + fix. Lever
        // precedence: explicit CONTENT-HEAD > disable(PLANE) > the measured mode.
        RestampMeasurement meas = measureRestamp(mainCompositeRenderer);
        String effMode;
        if (IPGlobal.is5WindowContentDepth) {
            effMode = "HEAD-CONTENT(lever)";
        } else if (!IPGlobal.is5DepthRestamp) {
            effMode = "PLANE(lever)";
        } else {
            effMode = meas.mode;
        }
        lastMainRestampMode = effMode;
        boolean headContent = effMode.startsWith("HEAD-CONTENT");
        if (effMode.equals("RESTAMP")) {
            RestampArm arm = new RestampArm();
            arm.renderer = mainCompositeRenderer;
            arm.meas = meas;
            arm.mainDepth0Id = depthGl.glId();
            arm.depth1Id = depth1Id;
            arm.w = w;
            arm.h = h;
            if (IPGlobal.is5XdimSingleGrade && meas.imageTarget >= 0) {
                RenderTarget imgRt = rts.get(meas.imageTarget);
                if (imgRt != null) {
                    arm.injectTexId = meas.anchorReadsAlt
                        ? imgRt.getAltTexture() : imgRt.getMainTexture();
                }
            }
            // §1.9 (final form — the cool-MB setting was removed the same day it was added, on
            // the user's word; the whip is future-polish material): the DEPTH boundary is
            // ALWAYS min(anchor, first prev-camera-consuming d1 reader) — the MB-class pass
            // sees CONTENT depth = ordinary content-correct blur; storm/reflection readers
            // before it keep PLANE. The inject index stays pinned to the anchor (post-tonemap).
            arm.depthBoundary = meas.anchor;
            for (int idx : meas.prevCamReaders) {
                if (idx < arm.depthBoundary) {
                    arm.depthBoundary = idx;
                    break; // ascending order — the first is the min
                }
            }
            arm.injectDone = arm.injectTexId == 0; // nothing to inject = that half is done
            // IS5-WASH arm: eligibility decided per §1.10; washNeeded is finalized after the
            // slot loop (it needs to know whether any POST/SG entry landed).
            if (IPGlobal.is5WindowBloomExclude && meas.washState.equals("ON")) {
                RenderTarget c0Rt = rts.get(0);
                if (c0Rt != null) {
                    arm.washTexId = meas.gatherReadsAlt
                        ? c0Rt.getAltTexture() : c0Rt.getMainTexture();
                    // IS5-WASHPROBE v3: both physical sides for the bracket-adjudicator.
                    if (IPGlobal.washProbe) {
                        arm.wpC0MainId = c0Rt.getMainTexture();
                        arm.wpC0AltId = c0Rt.getAltTexture();
                        if (meas.gatherWriteTgt >= 0) {
                            RenderTarget tileRt = rts.get(meas.gatherWriteTgt);
                            if (tileRt != null) {
                                arm.wpTileMainId = tileRt.getMainTexture();
                                arm.wpTileAltId = tileRt.getAltTexture();
                            }
                        }
                    }
                }
            }
            restampArm = arm; // entries appended per slot below; combined set with the matrices
        } else {
            restampArm = null;
        }
        // The 1Hz detector line (design §1.8): raw sets printed uninterpreted; the mode token
        // states the decision. ⟦J⟧ legs adjudicate on the RAW sets vs the pack walk, never the
        // token alone (a dead-code-active colortex sampler could move the anchor).
        long measNow = System.currentTimeMillis();
        if (measNow - lastRestampMeasLogMs >= 1000) {
            lastRestampMeasLogMs = measNow;
            LOGGER.info("[Seamless Portals] IS5-RESTAMP meas: passes={} d1={} hist={} prevCam={}"
                    + " anchor={} dBnd={} imgTgt={} mode={} gath={} wash={} wB/R={}/{}"
                    + " rst={} rstOrph={} sgC/I/F={}/{}/{} fw={} fwB/F={}/{}",
                meas.passCount, java.util.Arrays.toString(meas.d1Readers),
                java.util.Arrays.toString(meas.histReaders),
                java.util.Arrays.toString(meas.prevCamReaders), meas.anchor,
                restampArm != null ? restampArm.depthBoundary : meas.anchor, meas.imageTarget,
                effMode, meas.gatherIdx,
                washBroken ? "BROKEN"
                    : (!IPGlobal.is5WindowBloomExclude ? "OFF(lever)"
                        // ⟦J⟧ B2: never print ON when the mode cannot arm the bracket.
                        : (meas.washState.equals("ON") && !effMode.equals("RESTAMP")
                            ? "SKIP(mode=" + effMode + ")" : meas.washState)),
                censusWashB, censusWashR, censusRst, censusRstOrph,
                censusSgC, censusSgI, censusSgF,
                // FARFADE F11: the last window's PRE-stamped fw range (- = no fading slot).
                fwMaxSeen < 0f ? "-"
                    : String.format("%.2f/%.2f", fwMinSeen, fwMaxSeen),
                censusFwB, censusFwF);
            fwMinSeen = 2f;
            fwMaxSeen = -1f;
            // IS5-WASHPROBE main half + the paired emit (dest line captured at the SG
            // boundary this window; main read here at the same 1Hz cadence).
            if (IPGlobal.washProbe) {
                lastWashProbeMs = measNow;
                String mainLine = washProbeReadPass(
                    mainCompositeRenderer, meas.gatherIdx + 1, "mainC5")
                    + " | " + washProbeRdScan(mainCompositeRenderer, "main")
                    + " | " + washProbeResProj(mainCompositeRenderer, "main");
                LOGGER.info("[Seamless Portals] [IS5-WASHPROBE] {} || {}",
                    mainLine, washProbeDestLine == null ? "destC5: <no SG capture yet>"
                        : washProbeDestLine);
                washProbeDestLine = null;
            }
        }

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
            GL20C.glUniform1i(locCaptureAux, 1);
            GL20C.glUniform1i(locCapturePrev, 2);
            GL20C.glUniform1i(locCaptureDepth, 3);
            GL20C.glUniform1i(locStampedDepth0, 4);
            GL20C.glUniform1f(locSolid, solid ? 1.0f : 0.0f);
            // IS5-DEPTHFORK: mode 0 asserted explicitly per pass — never rely on defaults.
            GL20C.glUniform1i(locDepthMode, 0);
            GL20C.glUniform1f(locZeroAlpha, 0.0f); // asserted per site (uniforms persist)
            GL20C.glUniform1f(locFadeW, 1.0f); // FARFADE F10: asserted per site
            GL20C.glUniform1i(locPrevGraded, 5);
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
            if (restampArm != null) {
                // The EXACT HEAD combined matrix (defensive copy) — same vertices + same
                // matrix ⇒ bit-identical boundary rasterization by construction.
                restampArm.combined.set(mainProj).mul(mainMv);
            }
            for (CaptureSlot slot : captureSlots) {
                if (!slot.pending || slot.layer != 0) continue;
                if (IPGlobal.is5CrossingTrace && traceWindowFrames > 0) {
                    // IS5-XTRACE per-slot row: the ARM-camera vs STAMP-camera delta is the H4
                    // discriminator (a nonzero here on the flicker frame = the mesh registration
                    // and the captured viewpoint disagree by exactly this many blocks); the plane
                    // distance is the clip-suspension correlate.
                    try {
                        Vec3 stampCam = CHelper.getCurrentCameraPos();
                        double dCam = slot.cameraPos == null ? -1 : stampCam.distanceTo(slot.cameraPos);
                        double dPl = slot.portal == null ? -1
                            : slot.portal.getDistanceToNearestPointInPortal(stampCam);
                        traceSlots.append(String.format("P%03d L%d dCam=%.3f dPl=%.2f; ",
                            slot.portal == null ? 0 : (System.identityHashCode(slot.portal) % 1000),
                            slot.layer, dCam, dPl));
                    } catch (Throwable ignored) {
                        traceSlots.append("slot-read-failed; ");
                    }
                }
                if (slot.w != w || slot.h != h) {
                    // Mid-frame resize: the capture predates the new geometry; a 1:1 texelFetch
                    // against a stale-size capture reads out of bounds. One skipped view for one
                    // frame, noted content-keyed.
                    noteAuxDropOnce("stale-size capture skipped (" + slot.w + "x" + slot.h
                        + " vs main " + w + "x" + h + ")");
                    continue;
                }
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
                    // PART5: slots with a valid aux capture use the MRT fbo (writes the aux data
                    // target inside the window); others use the plain one — never write an aux
                    // attachment from an undefined sampler.
                    boolean useAux = stampFboAux != null && slot.auxValid.length > 0
                        && slot.auxValid[0];
                    (useAux ? stampFboAux : fbo).bind();
                    if (useAux) {
                        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
                        GlStateManager._bindTexture(slot.auxTex[0]);
                        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                    }
                    // IS5-HIST: bind the portal's PREVIOUS-frame capture for the history out
                    // (size/format-matched; first frame or mismatch ⇒ u_havePrev=0 = the old
                    // history=current behavior for that one frame). -PdisableWindowHistoryPrev
                    // reproduces the ugly MB-off approach-blur on command.
                    int[] prev = IPGlobal.disableWindowHistoryPrev ? null
                        : prevCaptureByPortal.get(slot.portal);
                    boolean havePrev = prev != null && prev[0] != 0
                        && prev[1] == w && prev[2] == h && prev[3] == slot.colorFmt;
                    if (havePrev) {
                        GlStateManager._activeTexture(GL13.GL_TEXTURE2);
                        GlStateManager._bindTexture(prev[0]);
                        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                    }
                    GL20C.glUniform1f(locHavePrev, havePrev ? 1.0f : 0.0f);
                    // IS5-FARFADE (§5 F5): the stamp-time gate — PRE source iff the WHOLE
                    // w<1 chain is intact (all flags final by HEAD time: pend copy landed,
                    // SG capture landed, the anchor inject exists). Anything missing ⇒
                    // slot.colorTex = today's exact fallback ladder (an sgF frame must
                    // never HEAD-stamp PRE with no blend coming — a 100% storm-less face).
                    boolean stampPre = slot.fadeW < 1f && slot.preCaptured && slot.sgCaptured
                        && restampArm != null && restampArm.injectTexId != 0
                        && slot.preW == w && slot.preH == h;
                    if (stampPre) {
                        fwMinSeen = Math.min(fwMinSeen, slot.fadeW);
                        fwMaxSeen = Math.max(fwMaxSeen, slot.fadeW);
                    }
                    GlStateManager._bindTexture(stampPre ? slot.preColorTex : slot.colorTex);
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
                    // PART5 MB-ghost fix: replay the same mesh depth-only into the depthtex1/2
                    // SNAPSHOTS (copied mid-render BEFORE the stamp existed — at window pixels
                    // they held SOURCE geometry, and the pack's MB reprojects along it = the
                    // measured ghost outline). Same LEQUAL + gl_FragDepth floor; colour outs are
                    // dropped (noDrawBuffers); depthtex1's solid-hand content survives the test.
                    if (stampFboDepth1 != null) {
                        stampFboDepth1.bind();
                        // IS5-DEPTHFORK (the MB-off translation-ghost fork, judge-corrected to
                        // the IDENTITY remap): in CONTENT mode the depthtex1 replay writes the
                        // captured dest depth (TAA reprojects window content by its TRUE
                        // parallax — the ghost dies) under GL_ALWAYS + the plane-visibility
                        // discard (the shader's u_depthMode=1 block). PLANE mode (default) is
                        // byte-identical to shipped — the KEPT MB-on look lives here. The pack
                        // reads depthtex1 for BOTH MB velocity and TAA reprojection (measured:
                        // composite4:90 / composite6:39) — no clean split exists; the lever is
                        // the user's fork. depthtex0 + depthtex2 stay PLANE in both modes.
                        boolean contentDepth = headContent && slot.depthTex != 0;
                        if (contentDepth) {
                            GlStateManager._activeTexture(GL13.GL_TEXTURE3);
                            GlStateManager._bindTexture(slot.depthTex);
                            GlStateManager._activeTexture(GL13.GL_TEXTURE4);
                            GlStateManager._bindTexture(depthGl.glId());
                            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                            GL20C.glUniform1i(locDepthMode, 1);
                            GlStateManager._depthFunc(GL11.GL_ALWAYS);
                        }
                        GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
                        if (contentDepth) {
                            GL20C.glUniform1i(locDepthMode, 0);
                            GlStateManager._depthFunc(GL11.GL_LEQUAL);
                        }
                    }
                    if (stampFboDepth2 != null) {
                        stampFboDepth2.bind();
                        GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
                    }
                    // IS5-RESTAMP: retain this slot's EXACT vertex slice for the boundary draws
                    // (frame-transient buffer — drained at GameRenderer.render TAIL, alive
                    // through every composite pass of this frame).
                    if (restampArm != null) {
                        RestampEntry re = new RestampEntry();
                        re.slot = slot;
                        re.vertexSlice = vertexSlice;
                        re.vertexCount = vertexCount;
                        re.stampedPre = stampPre;
                        restampArm.entries.add(re);
                    }
                    // IS5-HIST prev-store update — AFTER the draw consumed the OLD prev: this
                    // frame's capture becomes next frame's history. Enhancement-grade: a failed
                    // alloc/copy drops prev for that portal (one frame of history=current),
                    // never the window.
                    if (!IPGlobal.disableWindowHistoryPrev && slot.portal != null) {
                        try {
                            int[] p = prevCaptureByPortal.get(slot.portal);
                            if (p == null) {
                                p = new int[]{0, -1, -1, 0};
                                prevCaptureByPortal.put(slot.portal, p);
                            }
                            if (p[0] == 0 || p[1] != w || p[2] != h || p[3] != slot.colorFmt) {
                                if (p[0] != 0) GL11.glDeleteTextures(p[0]);
                                while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate */ }
                                int t = GL45C.glCreateTextures(GL11.GL_TEXTURE_2D);
                                GL45C.glTextureStorage2D(t, 1, slot.colorFmt, w, h);
                                if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                                    GL11.glDeleteTextures(t);
                                    p[0] = 0;
                                } else {
                                    p[0] = t; p[1] = w; p[2] = h; p[3] = slot.colorFmt;
                                }
                            }
                            if (p[0] != 0) {
                                GL43C.glCopyImageSubData(
                                    slot.colorTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                                    p[0], GL11.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
                                if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                                    GL11.glDeleteTextures(p[0]);
                                    p[0] = 0;
                                }
                            }
                        } catch (Throwable histErr) {
                            noteAuxDropOnce("prev-capture store failed: " + histErr);
                        }
                    }
                    // 1Hz depth-stamp verification (log-only): centre texel of depthtex1 AFTER
                    // the depth-only draw. Plane depth (~0.5) = the stamp lands and a persisting
                    // MB ghost rides another path; scene depth (~0.98) = the depth-only draw is a
                    // silent no-op (completeness/test) — the retest leg's discriminator.
                    // PERF-P1: TWO synchronous glGetTextureSubImage stalls mid-stamp — lever-gated
                    // (-Pis5LiveReadbacks) ATOMICALLY with the drain below: the drain protects this
                    // method's final glGetError adjudication, whose failure breaks the mechanism.
                    long nowMs = System.currentTimeMillis();
                    if (IPGlobal.is5LiveReadbacks
                        && depth1Id != 0 && nowMs - lastDepthReadbackMs >= 1000) {
                        lastDepthReadbackMs = nowMs;
                        long rbT0 = System.nanoTime();
                        try {
                            // AIM-INDEPENDENT comparator (the first version printed depthtex1
                            // alone and was unreadable: at these projections plane-vs-scene
                            // differs in the THIRD decimal and the aim wasn't printed). depthtex0
                            // is KNOWN-stamped; both received identical scene content and
                            // identical stamp treatment, so ANY sustained d1!=d0 = the depthtex1
                            // depth-only draw failing; equality = it lands.
                            java.nio.FloatBuffer d0px = BufferUtils.createFloatBuffer(1);
                            java.nio.FloatBuffer d1px = BufferUtils.createFloatBuffer(1);
                            GL45C.glGetTextureSubImage(
                                depthGl.glId(), 0, w / 2, h / 2, 0, 1, 1, 1,
                                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, d0px);
                            GL45C.glGetTextureSubImage(
                                depth1Id, 0, w / 2, h / 2, 0, 1, 1, 1,
                                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, d1px);
                            float d0 = d0px.get(0);
                            float d1 = d1px.get(0);
                            // IS5-DEPTHFORK (judge change 3): the discriminator is MODE-AWARE —
                            // PLANE mode: EQUAL = the depth1 stamp lands (shipped semantics);
                            // CONTENT mode: DIFF at a window-center pixel is EXPECTED (content
                            // vs plane) and EQUAL means the content depth degenerated to plane.
                            LOGGER.info("[Seamless Portals] [IS5-PRE] depth center after stamp:"
                                    + " d0={} d1={} {} (mode={})",
                                String.format("%.6f", d0), String.format("%.6f", d1),
                                Math.abs(d0 - d1) < 1e-6 ? "EQUAL" : "DIFF",
                                headContent
                                    ? "CONTENT: DIFF expected at window pixels"
                                    : (effMode.equals("RESTAMP")
                                        ? "RESTAMP-HEAD: EQUAL = the PLANE head stamp lands"
                                            + " (CONTENT arrives at the anchor)"
                                        : "PLANE: EQUAL = stamp lands"));
                        } catch (Throwable ignored) {
                        }
                        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* never poison */ }
                        long rbNs = System.nanoTime() - rbT0;
                        stampReadbackNsThisPass += rbNs;
                        PerfTimers.add("is5.readbackDepthCenter", rbNs);
                    }
                }
            }
            // IS5-WASH: the bracket arms only when a POST/SG entry exists (dest-baked bloom
            // in the capture). Same-dim PRE entries never black out — the source gather is
            // their only bloom source and killing it would un-glow their windows.
            if (restampArm != null && restampArm.washTexId != 0) {
                for (RestampEntry e : restampArm.entries) {
                    // IS5-FARFADE (§5 F8): only SG-STAMPED entries carry dest-baked bloom
                    // in c0 — a PRE-stamped entry's only bloom source IS the gather.
                    if (e.slot.postFinalMode && !e.stampedPre) {
                        restampArm.washNeeded = true;
                        break;
                    }
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
        // The per-frame VIEW COUNT lives in the 1Hz census (views=), NOT in this content key —
        // S6 leg 8 showed a two-portal scene bouncing stamped=1↔2 several times a second,
        // re-emitting this line constantly. Content-key only what indicates a STATE change.
        String a = "writeAlt=" + writeAlt
            + " solid=" + IPGlobal.debugStampSolid + " tint=" + IPGlobal.debugTintStamp;
        if (a.equals(lastStampAnnouncement)) return;
        lastStampAnnouncement = a;
        LOGGER.info("[Seamless Portals] [IS5-PRE] stamp pass live at main renderAll HEAD — {}", a);
    }
}
