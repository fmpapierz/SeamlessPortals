package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * IS5 — THE SHADOW-EMPTINESS PROBE (iris shaders-ON engagement; port-note
 * {@code migration/port-notes/IS-iris-shaders-on.md} §5 / shadow diagnose wf_cfc9bf90-075).
 *
 * <p><b>The question it settles.</b> Under an active shaderpack the cross-dim portal view is
 * full-bright and shadowless. The diagnose REFUTED stale-nether-reuse (bytecode-proven: iris
 * re-renders the shadow pass targeted at the OW dest in the nested full-pipeline pass) and
 * localized the cause to an EMPTY OW shadow depth map: our shell drives the dest CAMERA-frustum
 * terrain but likely NOT iris's SHADOW-frustum terrain, so the shadow render-list is empty, the
 * pack's shadow test returns "lit" everywhere, and the result is full-bright + no shadows. This
 * probe answers the ONE decisive question: <b>is the OW-dest shadow depth map EMPTY in the nested
 * full-pipeline pass?</b> That verdict decides Option 1 (drive the shadow-scope terrain in our
 * code) vs the D21 deep end.
 *
 * <p><b>Meaningful only for a CROSS-dim portal (source != dest).</b> iris caches ONE pipeline +
 * one set of {@code ShadowRenderTargets} per dimension, so for a SAME-dim OW-&gt;OW-dest pass the
 * nested pass and the (populated) main-frame OW shadow alias the same targets, and neither the [3]
 * cross-check (both read OW) nor the [4] depth readback can separate the nested dest shadow from
 * the main-frame one — the probe would report "populated" and NOT answer the emptiness question.
 * Run it standing at a portal whose dest is the Overworld from a DIFFERENT source dim (e.g.
 * nether-&gt;OW); the [3] {@code getCurrentDimension} then reveals which pipeline was read.
 *
 * <p><b>What it captures</b> (steps [1]-[4] of the diagnose's {@code probeIfNeeded}), around the
 * nested {@code render()} inside {@code SecondaryWorldRenderCore.renderDestWorldFullPipeline} for a
 * SUN-WORLD dest (gated on {@code destLevel.dimensionType().hasSkyLight()} so nether/end passes are
 * skipped):
 * <ol>
 *   <li><b>[1] Did the shadow pass fire?</b> {@code ShadowRenderer.ACTIVE} and
 *       {@code ShadowRenderingState.areShadowsCurrentlyBeingRendered()} sampled at the capture
 *       instant. HONEST LIMITATION: the capture is post-{@code render()} (the probe brackets the
 *       call, it does not hook inside iris's {@code renderShadows}), so both read FALSE here —
 *       {@code renderShadows} sets {@code ACTIVE=false} at its own tail (jar offset 1424). The
 *       "fired" evidence is the diagnose's bytecode proof PLUS the per-pass counters below, which
 *       are read from the OW pipeline's OWN {@code ShadowRenderer} instance and are RESET each pass
 *       (jar: {@code renderedShadowEntities}/{@code BlockEntities} assigned, not accumulated —
 *       offsets 1057/1133), so a non-stale reading reflects the OW nested pass specifically.</li>
 *   <li><b>[2] THE CRUX — what was drawn into the OW shadow depth buffer.</b> Terrain section
 *       count: iris delegates shadow terrain to sodium and exposes NO reflectively-clean int; the
 *       reachable proxy is {@code ShadowRenderer.debugStringTerrain} (private String, ASSIGNED
 *       during the pass at jar offset 1301 — the sodium shadow chunk-count HUD text), logged
 *       VERBATIM (not parsed). PLUS the reachable exact counts {@code renderedShadowEntities},
 *       {@code renderedShadowBlockEntities} (private instance ints on the OW ShadowRenderer,
 *       ASSIGNED once per pass and NOT re-zeroed at the tail — jar offsets 1057/1133 — so they hold
 *       live post-render values). All-zero corroborates an empty shadow render. NOTE: the public
 *       static {@code visibleBlockEntities} is NOT usable as a post-render count — iris nulls it
 *       unconditionally at the {@code renderShadows} tail (jar offset 1420, straight-line before
 *       {@code ACTIVE=false}@1424), so this probe (reading in the post-render finally) always sees
 *       null =&gt; it is logged only as a {@code -1} tombstone (a positive confirmation the pass ran
 *       and cleaned up); the LIVE block-entity count is {@code renderedShadowBlockEntities}.</li>
 *   <li><b>[3] CROSS-CHECK (rules out a stale global).</b>
 *       {@code Iris.getPipelineManager().getPipelineNullable()} identity + class,
 *       {@code Iris.getCurrentDimension()}, and {@code ShadowRenderer.getSunAngle(true)} — confirms
 *       we are reading the OW pipeline / OW dim / OW sun (in the finally the shell has not yet
 *       restored {@code mc.level}, so these resolve to the dest), not a stale nether pipeline.</li>
 *   <li><b>[4] FALLBACK / CORROBORATION — the direct answer.</b> A small centered
 *       {@code glReadPixels} DEPTH_COMPONENT readback of the OW pipeline's shadow depth texture
 *       (resolved LIVE: pipeline -&gt; {@code shadowRenderTargets} -&gt; {@code getDepthSourceFb()}
 *       -&gt; {@code getId()} — never a captured id). All-cleared (conventional far=1.0, or
 *       reversed 0.0) =&gt; EMPTY MAP CONFIRMED, independent of how terrain is counted. This is the
 *       decisive signal. It MUST bracket {@code GL_PACK_*} state (the P-OQ4/copyTextureToBuffer
 *       stale-ROW_LENGTH landmine, 26.2 invariant #4): save, force tight, read, restore.</li>
 * </ol>
 *
 * <p><b>§7 DIAGNOSE-FIRST EXTENSION — [5] + [6] (the shadow-cull-input reality test).</b> The
 * §7 recon REFUTED the naive "our shell fails to drive the shadow-scope terrain" fix: iris
 * self-drives its own shadow {@code setupTerrain} on the SAME (repointed) dest renderer, so a
 * mirror drive would be dead code. The surviving root-cause lead (recon-B) is a STALE
 * {@code gbufferProjection} feeding {@code AdvancedShadowCullingFrustum} — sodium's cached
 * projection is never refreshed for the dest pass (our {@code writeProjectionSlice} bypasses
 * {@code ProjectionMatrixBuffer.getBuffer}, sodium's sole {@code sodium$setProjection} writer).
 * BUT for an UNSCALED portal the stale value ~= the correct dest projection, so static analysis
 * cannot prove it is <i>degenerate</i> (vs merely mismatched). These two legs settle it at
 * runtime before any fix ships (the standing NO-GUESSING gate):
 * <ol start="5">
 *   <li><b>[5] PROJECTION DUMP + COMPARE.</b> Dump the 16 floats + {@code identityHashCode} of
 *       {@code CapturedRenderingState.INSTANCE.getGbufferProjection()} (the exact matrix iris
 *       copies into the shadow cull frustum at {@code iris$setupPipeline}) and of sodium's cache
 *       {@code GameRendererStorage.sodium$getProjectionMatrix()}, COMPARED (max abs element diff)
 *       against {@code destDrawProjection} (the Step-7 installed draw projection, passed in) and
 *       {@code mainCameraState.projectionMatrix} (passed in). VERDICT: {@code gbufferProjection
 *       DIVERGES from destDrawProjection} (stale =&gt; the fix target) vs {@code ~=} (fix benign /
 *       unnecessary for this unscaled portal). NAMING-BAN CATCH: the task named the duck
 *       {@code net.irisshaders...GameRendererStorage}; javap CORRECTS it to
 *       {@code net.caffeinemc.mods.sodium.client.util.GameRendererStorage} (a SODIUM interface,
 *       returning {@code org.joml.Matrix4fc}) — reflected as the auxiliary/tolerated-absent
 *       handle so a sodium-shape drift never nukes the iris legs.</li>
 *   <li><b>[6] SHADOW-FRUSTUM REALITY TEST (decisive).</b> Reflect BOTH retained shadow-cull
 *       frustums — {@code ShadowRenderer.terrainFrustumHolder} (the {@code C: 0/0} terrain half)
 *       AND {@code entityFrustumHolder} (the {@code renderedShadowEntities=0} half; NOTE-1 fold) —
 *       (private {@code FrustumHolder}s, re-assigned each pass, javap-confirmed NOT tail-nulled, so
 *       each holds its last shadow frustum post-render) -&gt; {@code getFrustum()} (returns a vanilla
 *       {@code net.minecraft.client.renderer.culling.Frustum} — the
 *       {@code AdvancedShadowCullingFrustum} subclass), then {@code isVisible(AABB)} on the AABB of
 *       the dest camera's OWN section (definitely in view, drawn by the camera pass; derived from the
 *       dest camera pos passed in — absolute world coords, per the jar-confirmed BoxCuller/plane
 *       test). VERDICT: {@code isVisible==false} on either =&gt; that frustum is DEGENERATE =&gt;
 *       stale-projection cause CONFIRMED (pair with [5]) =&gt; the §7.1 repoint fix is right;
 *       {@code isVisible==true} on both but terrain still {@code C: 0/0} ([2]) =&gt; the fault is
 *       DOWNSTREAM in the sodium shadow SectionTree (deep-end, iris-internal mixin, USER DECISION);
 *       a TERRAIN-passes / ENTITY-rejects split points at the entity-only cull path, not the shared
 *       projection. Both are built by {@code createShadowFrustum} from the same
 *       {@code gbufferProjection}, so terrain alone is a valid degeneracy proxy — the entity leg
 *       covers the case where the two frustums diverge. Also dumps {@code getDistanceInfo()}/{@code
 *       getCullingInfo()} to confirm the Advanced arm (vs a pack {@code CullEverythingFrustum} — a
 *       separate cause).</li>
 * </ol>
 *
 * <p><b>Binding discipline.</b> LOG-ONLY; ZERO behavior change at the default. The lever
 * {@code -Dseamlessportals.shadowProbe} is a RUNTIME-read {@code static} ({@code Boolean.getBoolean}
 * — not a javac compile-time constant, so the guards are real short-circuit branches), and this
 * class is referenced from {@code renderDestWorldFullPipeline} by two calls that both short-circuit
 * on the static lever, so with the property absent nothing here runs. Iris is reached ONLY
 * reflectively — this class holds NO {@code net.irisshaders.*} import (it lives in
 * {@code com.warwa.seamlessportals.render}, not a compat seam, so hard iris imports are forbidden
 * per the IS5 binding rules); every symbol below was {@code javap}-confirmed against
 * {@code iris-1.11.2+26.2-fabric.jar} before use (the naming-assumption ban — e.g. the CRUX counters
 * are {@code renderedShadowEntities}/{@code renderedShadowBlockEntities}, NOT the "renderedShadow*"
 * that a guess would reach for on a static; they are PRIVATE INSTANCE fields on ShadowRenderer, and
 * the block-entity list is {@code visibleBlockEntities}, not "renderedShadowBlockEntities"). It
 * DISARMS ITSELF on ANY reflection/GL failure with one line — a diagnostic can never crash a render
 * or mislead. 1Hz-latched, one-shot-per-armed-pass, render-thread only (the whole pass runs there).
 */
public final class ShadowEmptinessProbe {

    private ShadowEmptinessProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String P = "[IS5-SHADOW-PROBE] ";

    /** The lever. Byte-inert unless {@code -Dseamlessportals.shadowProbe=true}. */
    private static final boolean ENABLED = Boolean.getBoolean("seamlessportals.shadowProbe");

    /** 1Hz rate limit between armed captures (ns). */
    private static final long RATE_LIMIT_NS = 1_000_000_000L;

    /** Centered readback patch edge (texels) — clamped to the shadow map resolution. */
    private static final int PATCH = 16;

    /** Element-wise tolerance for the [5] gbufferProjection ~=/DIVERGES verdict (abs, per element). */
    private static final float PROJ_EPS = 1.0e-4f;

    // render-thread-only latches
    private static boolean disarmed = false;
    private static boolean capturing = false;
    private static long lastCaptureNanos = 0L;
    private static String passDim = "";

    // §7 [5]/[6] pass inputs, deep-copied at beginPass (arm time) so a later mutation of the caller's
    // locals cannot alias the dump. All render-thread-only (the whole pass runs there).
    private static Matrix4f stashedDestDrawProjection = null;   // Step-7 installed draw projection
    private static Matrix4f stashedMainCameraProjection = null; // mainCameraState.projectionMatrix
    private static double camX, camY, camZ;                     // dest camera pos (for the [6] section AABB)
    private static boolean camPosValid = false;

    // ===== reflection surface (resolved once; all symbols javap-confirmed) ======================
    private static boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;

    private static java.lang.reflect.Method mGetPipelineManager;     // Iris.getPipelineManager()
    private static java.lang.reflect.Method mGetCurrentDimension;    // Iris.getCurrentDimension()
    private static java.lang.reflect.Method mGetPipelineNullable;    // PipelineManager.getPipelineNullable()
    private static Class<?> irisPipelineClass;                        // IrisRenderingPipeline
    private static java.lang.reflect.Field fShadowRenderer;          // IrisRenderingPipeline.shadowRenderer
    private static java.lang.reflect.Field fShadowRenderTargets;     // IrisRenderingPipeline.shadowRenderTargets
    private static java.lang.reflect.Field fActive;                  // ShadowRenderer.ACTIVE (static)
    private static java.lang.reflect.Field fVisibleBlockEntities;    // ShadowRenderer.visibleBlockEntities (static)
    private static java.lang.reflect.Field fRenderedShadowEntities;  // ShadowRenderer.renderedShadowEntities
    private static java.lang.reflect.Field fRenderedShadowBEs;       // ShadowRenderer.renderedShadowBlockEntities
    private static java.lang.reflect.Field fDebugStringTerrain;      // ShadowRenderer.debugStringTerrain
    private static java.lang.reflect.Method mGetSunAngle;            // ShadowRenderer.getSunAngle(boolean)
    private static java.lang.reflect.Method mAreShadowsRendered;     // ShadowRenderingState.areShadowsCurrentlyBeingRendered()
    private static java.lang.reflect.Method mGetDepthSourceFb;       // ShadowRenderTargets.getDepthSourceFb()
    private static java.lang.reflect.Method mGetResolution;          // ShadowRenderTargets.getResolution()
    private static java.lang.reflect.Method mFbGetId;                // GlFramebuffer.getId()
    private static java.lang.reflect.Method mFbHasDepthAttachment;   // GlFramebuffer.hasDepthAttachment()

    // ===== §7 [5] projection-dump handles (all javap-confirmed vs iris-1.11.2+26.2-fabric) =======
    private static java.lang.reflect.Field fCapturedInstance;        // CapturedRenderingState.INSTANCE (static)
    private static java.lang.reflect.Method mGetGbufferProjection;   // CapturedRenderingState.getGbufferProjection():Matrix4fc
    private static java.lang.reflect.Method mGetGbufferModelView;    // CapturedRenderingState.getGbufferModelView():Matrix4fc
    // ===== §7 [6] shadow-frustum-holder handles =================================================
    private static java.lang.reflect.Field fTerrainFrustumHolder;    // ShadowRenderer.terrainFrustumHolder (private FrustumHolder — the 'C: 0/0' terrain half)
    private static java.lang.reflect.Field fEntityFrustumHolder;     // ShadowRenderer.entityFrustumHolder (private FrustumHolder — the renderedShadowEntities=0 half; NOTE-1 fold)
    private static java.lang.reflect.Method mHolderGetFrustum;       // FrustumHolder.getFrustum():net.minecraft...Frustum
    private static java.lang.reflect.Method mHolderGetDistanceInfo;  // FrustumHolder.getDistanceInfo():String
    private static java.lang.reflect.Method mHolderGetCullingInfo;   // FrustumHolder.getCullingInfo():String
    // ===== §7 [5] sodium duck (AUXILIARY — tolerated-absent; never disarms the iris legs) =======
    private static java.lang.reflect.Method mSodiumGetProjection;    // GameRendererStorage.sodium$getProjectionMatrix():Matrix4fc (SODIUM interface)

    /**
     * ARM a capture window at most once per second, for a SUN-WORLD dest only. Called from
     * {@code renderDestWorldFullPipeline} immediately before the nested {@code render()} (mirrors
     * {@code ClipDiscriminatorProbe.beginPass}). No iris work happens here — the read is all in
     * {@link #endPass()} after the nested render, when the pipeline manager slot / current dim /
     * shadow counters reflect the OW nested pass.
     *
     * @param dim                  the dest dimension id (for the log)
     * @param destHasSkyLight      {@code destLevel.dimensionType().hasSkyLight()} — the sun-world
     *                             gate; false (nether/end) skips arming entirely
     * @param destDrawProjection   the Step-7 installed dest draw projection ({@code base*bob*spin} of
     *                             the dest projection — the exact matrix the dest rasterizes with);
     *                             the [5] compare target. Deep-copied at arm time.
     * @param mainCameraProjection {@code mainCameraState.projectionMatrix} — the main-frame player
     *                             projection; the second [5] compare target. Deep-copied at arm time.
     * @param destCameraPos        the dest camera world position — the [6] reality-test AABB is this
     *                             camera's own 16³ section (definitely drawn by the camera pass).
     */
    public static void beginPass(String dim, boolean destHasSkyLight,
                                 Matrix4f destDrawProjection, Matrix4f mainCameraProjection,
                                 Vec3 destCameraPos) {
        if (!ENABLED || disarmed || !destHasSkyLight) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (now - lastCaptureNanos < RATE_LIMIT_NS) {
                return;
            }
            lastCaptureNanos = now;
            capturing = true;
            passDim = dim;
            // Deep-copy the projection inputs so a later mutation of the caller's Matrix4f locals
            // cannot retroactively change what [5] dumps (destDrawProjection is a live local in the
            // core). Camera pos is a value snapshot for the [6] section AABB.
            stashedDestDrawProjection = destDrawProjection == null ? null : new Matrix4f(destDrawProjection);
            stashedMainCameraProjection = mainCameraProjection == null ? null : new Matrix4f(mainCameraProjection);
            if (destCameraPos == null) {
                camPosValid = false;
            }
            else {
                camX = destCameraPos.x;
                camY = destCameraPos.y;
                camZ = destCameraPos.z;
                camPosValid = true;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * CAPTURE + DUMP. Called from the outermost {@code finally} of
     * {@code renderDestWorldFullPipeline}, after the nested {@code render()} (mirrors
     * {@code ClipDiscriminatorProbe.endPass}). Reads steps [1]-[4] reflectively off the CURRENT
     * (post-render = OW) pipeline and does the shadow-depth readback, then logs one block. The shell
     * has not yet restored {@code mc.level} in this finally, so {@code getCurrentDimension} / sun
     * angle resolve to the dest — the [3] cross-check.
     */
    public static void endPass() {
        if (!ENABLED || disarmed || !capturing) {
            return;
        }
        capturing = false;
        try {
            if (!ensureReflection()) {
                // ensureReflection already logged + disarmed on failure; if iris is simply absent
                // it disarms quietly. Nothing more to do.
                return;
            }

            // --- resolve the live OW pipeline (the [3] cross-check anchor) --------------------
            Object pipelineManager = mGetPipelineManager.invoke(null);
            if (pipelineManager == null) {
                LOGGER.info(P + "no PipelineManager (iris not initialized) — skip. dim=" + passDim);
                return;
            }
            Object pipeline = mGetPipelineNullable.invoke(pipelineManager);
            if (pipeline == null) {
                LOGGER.info(P + "getPipelineNullable()==null (no active pipeline this frame) — skip."
                    + " dim=" + passDim);
                return;
            }
            if (!irisPipelineClass.isInstance(pipeline)) {
                // e.g. a fixed-function / vanilla fallback pipeline (no pack) — no shadow fields.
                LOGGER.info(P + "active pipeline is not IrisRenderingPipeline (no shadow render;"
                    + " likely no active pack): class=" + pipeline.getClass().getName()
                    + " dim=" + passDim);
                return;
            }
            Object currentDim = mGetCurrentDimension.invoke(null);
            float sunAngle = (Float) mGetSunAngle.invoke(null, Boolean.TRUE);

            // --- [1] pass-fired flags (post-render: expected false — see javadoc) ------------
            boolean activeStatic = fActive.getBoolean(null);
            boolean shadowsRenderingNow = (Boolean) mAreShadowsRendered.invoke(null);

            // --- [2] the CRUX: what the OW shadow pass drew ----------------------------------
            Object shadowRenderer = fShadowRenderer.get(pipeline);
            String terrainDebug;
            String entityCounts;
            if (shadowRenderer == null) {
                terrainDebug = "(shadowRenderer==null on the pipeline)";
                entityCounts = "renderedShadowEntities=n/a renderedShadowBlockEntities=n/a";
            }
            else {
                Object td = fDebugStringTerrain.get(shadowRenderer);
                terrainDebug = td == null ? "(debugStringTerrain==null — terrain shadow render never"
                    + " set it this session)" : ("\"" + td + "\"");
                int rse = fRenderedShadowEntities.getInt(shadowRenderer);
                int rsbe = fRenderedShadowBEs.getInt(shadowRenderer);
                entityCounts = "renderedShadowEntities=" + rse + " renderedShadowBlockEntities=" + rsbe;
            }
            // visibleBlockEntities is nulled unconditionally at the renderShadows tail (jar off.1420),
            // and this probe reads in the post-render finally — so this is ALWAYS -1 (a tombstone that
            // confirms the pass ran + cleaned up), NOT a live count. The live BE count is
            // renderedShadowBlockEntities (above). Kept only as that confirmation.
            int visibleBECount;
            Object vbe = fVisibleBlockEntities.get(null);
            visibleBECount = (vbe instanceof List<?> list) ? list.size() : -1;

            // --- [4] the decisive shadow-depth readback --------------------------------------
            Object shadowTargets = fShadowRenderTargets.get(pipeline);
            String depthVerdict = readbackShadowDepth(shadowTargets);

            LOGGER.info(P + "OW-DEST SHADOW CAPTURE (nested full-pipeline pass, post-render):"
                + "\n  dim=" + passDim
                + "\n  [3] cross-check: pipeline=" + identityString(pipeline)
                    + " getCurrentDimension=" + currentDim
                    + " getSunAngle(true)=" + sunAngle
                + "\n  [1] shadowPassFired(sampled post-render, expected false; 'fired' is the"
                    + " diagnose's bytecode proof + the reset-per-pass counters below):"
                    + " ShadowRenderer.ACTIVE=" + activeStatic
                    + " areShadowsCurrentlyBeingRendered=" + shadowsRenderingNow
                + "\n  [2] THE CRUX (drawn into the OW shadow depth buffer):"
                    + "\n      terrain(sodium shadow HUD text, verbatim)=" + terrainDebug
                    + "\n      " + entityCounts
                    + " (renderedShadowBlockEntities is the LIVE BE count;"
                    + " visibleBlockEntities.size=" + visibleBECount
                    + " is ALWAYS -1 post-render — iris nulls it at the renderShadows tail — a"
                    + " tombstone, NOT a count)"
                + "\n  [4] " + depthVerdict
                + "\n  READING: all-cleared depth ([4]) with zero counts ([2]) => OW shadow map"
                    + " EMPTY CONFIRMED (Option 1: drive the shadow-scope terrain in our code);"
                    + " populated depth / non-zero counts => the map is NOT empty and the"
                    + " full-bright cause lies elsewhere (re-open the diagnose).");

            // --- §7 [5]+[6] the shadow-CULL-INPUT diagnose (the diagnose-first GATE) -----------
            // Logged as its OWN block; a reflection miss in here returns a diagnostic string rather
            // than throwing, so [1]-[4] above always stand even if the cull-input reads fail.
            LOGGER.info(P + "SHADOW-CULL INPUTS (§7 diagnose-first gate — is gbufferProjection the"
                + " degenerate frustum input?):"
                + "\n" + dumpProjection5()
                + "\n" + frustumRealityTest6(shadowRenderer)
                + "\n" + edgeFrustumTest6b(shadowRenderer));
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * §7 [5] — dump {@code gbufferProjection} (the exact matrix iris copied into the shadow cull
     * frustum) + sodium's cache, and compare (max abs element diff) against the passed-in dest draw
     * projection and main-camera projection. Returns a multi-line human string; never throws
     * (returns a diagnostic on any reflective failure so the outer block still logs).
     */
    private static String dumpProjection5() {
        try {
            Object capturedInstance = fCapturedInstance.get(null);
            if (capturedInstance == null) {
                return "  [5] gbufferProjection: CapturedRenderingState.INSTANCE==null — skip";
            }
            Object gbufProjObj = mGetGbufferProjection.invoke(capturedInstance);
            Object gbufMvObj = mGetGbufferModelView.invoke(capturedInstance);
            float[] gbuf = toFloats(gbufProjObj);
            float[] gbufMv = toFloats(gbufMvObj);

            // sodium cache (auxiliary): the value iris seeds gbufferProjection FROM at setupPipeline.
            String sodiumStr;
            float[] sod = null;
            if (mSodiumGetProjection == null) {
                sodiumStr = "(sodium GameRendererStorage duck not resolved — auxiliary, skipped)";
            }
            else {
                Object gr = Minecraft.getInstance().gameRenderer;
                Object sodObj = mSodiumGetProjection.invoke(gr);
                sod = toFloats(sodObj);
                sodiumStr = sodObj == null ? "null"
                    : (identityString(sodObj) + " " + fmt(sod));
            }

            float[] destDraw = stashedDestDrawProjection == null ? null
                : stashedDestDrawProjection.get(new float[16]);
            float[] mainCam = stashedMainCameraProjection == null ? null
                : stashedMainCameraProjection.get(new float[16]);

            String diffDraw = maxAbsDiffStr(gbuf, destDraw);
            String diffMain = maxAbsDiffStr(gbuf, mainCam);
            Float dDraw = maxAbsDiff(gbuf, destDraw);
            String verdict;
            if (gbuf == null || destDraw == null) {
                verdict = "INDETERMINATE (a compare input was null — see values above)";
            }
            else if (dDraw <= PROJ_EPS) {
                verdict = "gbufferProjection ~= destDrawProjection (maxAbsDiff=" + dDraw
                    + " <= " + PROJ_EPS + ") — the stale value ~matches the correct dest projection"
                    + " for THIS (unscaled) portal: the §7.1 repoint would be BENIGN/near-no-op here."
                    + " If [4] is still ALL-CLEARED with this verdict => the cause is NOT the"
                    + " projection (deep-end, per §7.4 tree).";
            }
            else {
                verdict = "gbufferProjection DIVERGES from destDrawProjection (maxAbsDiff=" + dDraw
                    + " > " + PROJ_EPS + ") — STALE projection confirmed as the shadow cull frustum"
                    + " input: the §7.1 repoint fix targets exactly this. (Pair with [6] below.)";
            }

            return "  [5] gbufferProjection " + (gbufProjObj == null ? "null"
                        : identityString(gbufProjObj) + " " + fmt(gbuf))
                + "\n      gbufferModelView " + (gbufMvObj == null ? "null"
                        : identityString(gbufMvObj) + " " + fmt(gbufMv))
                        + "  (= destViewMatrix — the correct half, for reference)"
                + "\n      sodium$getProjectionMatrix (the seed) = " + sodiumStr
                + "\n      destDrawProjection (Step-7 installed) = "
                        + (destDraw == null ? "n/a (not passed)" : fmt(destDraw))
                + "\n      mainCameraState.projectionMatrix       = "
                        + (mainCam == null ? "n/a (not passed)" : fmt(mainCam))
                + "\n      maxAbsDiff(gbuffer, destDraw)=" + diffDraw
                        + "  maxAbsDiff(gbuffer, mainCam)=" + diffMain
                + "\n      VERDICT[5]: " + verdict;
        }
        catch (Throwable t) {
            return "  [5] gbufferProjection dump FAILED (" + t + ") — [6] carries the verdict";
        }
    }

    /**
     * §7 [6] — the decisive reality test: fetch the retained terrain shadow frustum and ask whether a
     * KNOWN-in-view section (the dest camera's own 16³ section, absolute world coords) is visible.
     * false => degenerate frustum => the stale-projection cause is CONFIRMED and §7.1 is the fix;
     * true => the frustum is fine and the fault is downstream in the sodium shadow SectionTree (the
     * deep end). Never throws.
     */
    private static String frustumRealityTest6(Object shadowRenderer) {
        try {
            if (shadowRenderer == null) {
                return "  [6] frustum reality test SKIPPED — shadowRenderer==null on the pipeline";
            }
            if (!camPosValid) {
                return "  [6] frustum reality test SKIPPED — no dest camera pos was passed (cannot"
                    + " build the known-in-view section AABB)";
            }

            // The dest camera's OWN 16³ section, in ABSOLUTE world coords (the BoxCuller + plane
            // test both work absolute — jar-confirmed AdvancedShadowCullingFrustum.isVisible(AABB)).
            // Floor-to-16 via a mask (correct for negative coords in two's complement).
            int bx = (int) Math.floor(camX) & ~15;
            int by = (int) Math.floor(camY) & ~15;
            int bz = (int) Math.floor(camZ) & ~15;
            AABB camSection = new AABB(bx, by, bz, bx + 16.0, by + 16.0, bz + 16.0);
            String sectionStr = "camSection AABB=[" + bx + "," + by + "," + bz + " .. " + (bx + 16)
                + "," + (by + 16) + "," + (bz + 16) + "] (dest camera's own section)";

            // NOTE-1 fold: test BOTH shadow-cull frustums independently. The TERRAIN frustum feeds the
            // shadow terrain viewport (the 'C: 0/0' half); the ENTITY frustum feeds extractVisibleEntities
            // (the renderedShadowEntities=0 half). Both are built by createShadowFrustum from the SAME
            // gbufferProjection, so a degenerate projection degenerates BOTH — terrain remains a valid
            // proxy for 'is the projection input degenerate'. But they are SEPARATE FrustumHolder
            // instances, and an entity-only culling multiplier could make the entity cull stricter; a
            // TERRAIN-passes / ENTITY-rejects split would be invisible to the terrain leg alone. Testing
            // both keeps the two defect halves independently characterized.
            String terrain = oneFrustumTest(shadowRenderer, fTerrainFrustumHolder, "TERRAIN", camSection);
            String entity = oneFrustumTest(shadowRenderer, fEntityFrustumHolder, "ENTITY", camSection);

            return "  [6] frustum reality test (" + sectionStr + "):"
                + "\n" + terrain
                + "\n" + entity
                + "\n      READING[6]: isVisible=FALSE on a section the camera pass provably drew =>"
                + " that shadow-cull frustum is DEGENERATE => stale-gbufferProjection CONFIRMED as the"
                + " frustum input (pair with the [5] gbufferProjection dump + the §7.4 decision tree) =>"
                + " the §7.1 repoint fix is right (re-run, expect [4] to flip to POPULATED). isVisible="
                + "TRUE on BOTH yet terrain still 'C: 0/0' ([2]) => the fault is DOWNSTREAM in the sodium"
                + " shadow SectionTree / render-list => DEEP-END (iris-internal mixin — USER DECISION,"
                + " §7.5). A TERRAIN-passes / ENTITY-rejects split points at the entity-only cull path,"
                + " NOT the shared projection."
                + "\n      (cullingInfo names the arm: an 'AdvancedShadowCullingFrustum' confirms the"
                + " default arm; a 'CullEverythingFrustum' would be a separate pack-config cause.)";
        }
        catch (Throwable t) {
            return "  [6] frustum reality test FAILED (" + t + ") — [5] carries the verdict";
        }
    }

    /**
     * §7 [6b] — the EDGE-CULL geometry test (the pan-wave discriminator). The center-only [6] proved
     * the shadow terrain frustum accepts the dest camera's OWN section; the reported bug is a
     * pan-dependent full-bright WAVE at the view EDGES. This samples sections at the periphery of the
     * dest VIEW frustum (derived from gbufferProjection's fov half-angles + gbufferModelView's camera
     * basis, both CapturedRenderingState-live) and asks the shadow terrain cull frustum whether each is
     * visible. A rejected in-view edge section => the advanced-shadow-cull cone is NARROWER than the
     * view frustum at that instant => the wave is iris culling the edges out of the shadow scope.
     *
     * Terrain-INDEPENDENT (pure frustum geometry) — so it discriminates on ANY dest (mid-air harness or
     * ground), unlike the [2]/[4] readback which need real terrain. A STATIC capture that rejects proves
     * a SPATIAL cull-cone gap; rejects that appear ONLY while the camera rotates prove a TEMPORAL lag
     * (shadow frustum built one value behind the gbuffer draw). All-TRUE static + persistent wave =>
     * cause is downstream (sodium shadow SectionTree population lag) — a rotating capture separates them.
     */
    private static String edgeFrustumTest6b(Object shadowRenderer) {
        try {
            if (shadowRenderer == null || !camPosValid) {
                return "  [6b] edge-cull test SKIPPED (shadowRenderer==null or no dest camera pos)";
            }
            Object capturedInstance = fCapturedInstance.get(null);
            if (capturedInstance == null) {
                return "  [6b] edge-cull test SKIPPED — CapturedRenderingState.INSTANCE==null";
            }
            float[] mv = toFloats(mGetGbufferModelView.invoke(capturedInstance));
            float[] pr = toFloats(mGetGbufferProjection.invoke(capturedInstance));
            if (mv == null || pr == null) {
                return "  [6b] edge-cull test SKIPPED — gbufferModelView/Projection not readable as Matrix4fc";
            }
            // Column-major float[16] (toFloats == Matrix4fc.get). View matrix = world->view; the world-
            // space camera axes are the ROWS of the 3x3 rotation: right=(m00,m01,m02)=(f0,f4,f8),
            // up=(m10,m11,m12)=(f1,f5,f9), forward=-(m20,m21,m22)=-(f2,f6,f10).
            double rx = mv[0], ry = mv[4], rz = mv[8];
            double ux = mv[1], uy = mv[5], uz = mv[9];
            double fx = -mv[2], fy = -mv[6], fz = -mv[10];
            // Perspective proj: m00=1/(aspect*tan(fovy/2)), m11=1/tan(fovy/2).
            double tanx = (pr[0] != 0.0f) ? 1.0 / Math.abs(pr[0]) : 1.0;   // tan(fovx/2)
            double tany = (pr[5] != 0.0f) ? 1.0 / Math.abs(pr[5]) : 1.0;   // tan(fovy/2)

            Object holder = fTerrainFrustumHolder.get(shadowRenderer);
            if (holder == null) {
                return "  [6b] edge-cull test SKIPPED — terrainFrustumHolder==null (no shadow frustum this pass)";
            }
            Object frObj = mHolderGetFrustum.invoke(holder);
            if (!(frObj instanceof Frustum fr)) {
                return "  [6b] edge-cull test SKIPPED — terrain getFrustum() not a net.minecraft...Frustum ("
                    + (frObj == null ? "null" : frObj.getClass().getName()) + ")";
            }

            double[] ds = {24.0, 48.0, 96.0};
            // fractions of the view half-angle: center, the 4 edge midpoints, the 4 corners (0.9 = just
            // inside the view-frustum boundary — sections the dest camera provably rasterizes).
            double[][] cells = {{0,0},{-0.9,0},{0.9,0},{0,0.9},{0,-0.9},{-0.9,0.9},{0.9,0.9},{-0.9,-0.9},{0.9,-0.9}};
            String[] names = {"C","L","R","U","D","LU","RU","LD","RD"};
            StringBuilder grid = new StringBuilder();
            StringBuilder rejList = new StringBuilder();
            int rejects = 0, total = 0;
            for (double D : ds) {
                grid.append(" D").append((int) D).append("[");
                for (int i = 0; i < cells.length; i++) {
                    double h = cells[i][0] * tanx * D;
                    double v = cells[i][1] * tany * D;
                    double px = camX + fx * D + rx * h + ux * v;
                    double py = camY + fy * D + ry * h + uy * v;
                    double pz = camZ + fz * D + rz * h + uz * v;
                    int bx = (int) Math.floor(px) & ~15;
                    int by = (int) Math.floor(py) & ~15;
                    int bz = (int) Math.floor(pz) & ~15;
                    AABB s = new AABB(bx, by, bz, bx + 16.0, by + 16.0, bz + 16.0);
                    boolean vis = fr.isVisible(s);
                    total++;
                    if (!vis) {
                        rejects++;
                        rejList.append(' ').append(names[i]).append("@D").append((int) D);
                    }
                    grid.append(names[i]).append(':').append(vis ? 'T' : 'F').append(i < cells.length - 1 ? " " : "");
                }
                grid.append("]");
            }
            int fovxDeg = (int) Math.round(Math.toDegrees(Math.atan(tanx)));
            int fovyDeg = (int) Math.round(Math.toDegrees(Math.atan(tany)));
            return "  [6b] EDGE-CULL geometry test (shadow TERRAIN frustum vs dest VIEW-frustum edges;"
                + " fovx/2~=" + fovxDeg + "deg fovy/2~=" + fovyDeg + "deg):" + grid
                + "\n      EDGE-VERDICT: rejects=" + rejects + "/" + total
                + (rejects > 0
                    ? " => CULL-CONE TOO NARROW: the shadow terrain frustum REJECTS in-view edge sections ["
                        + rejList + " ] the dest camera rasterizes => the pan full-bright wave = iris advanced"
                        + " shadow culling excluding the view edges from the shadow scope. Fix targets"
                        + " createShadowFrustum / the shadow cull scope (iris-internal, §7.5). STATIC reject"
                        + " = SPATIAL gap; reject only while ROTATING = TEMPORAL lag."
                    : " => the shadow frustum CONTAINS every sampled view-edge section AT THIS INSTANT (no"
                        + " spatial cull-cone gap). If the wave persists, the cause is TEMPORAL (frustum lags"
                        + " the pan by a frame) or DOWNSTREAM (sodium shadow SectionTree population lag) —"
                        + " compare a STATIC vs a ROTATING capture to separate them.");
        }
        catch (Throwable t) {
            return "  [6b] edge-cull test FAILED (" + t + ")";
        }
    }

    /**
     * §7 [6] helper — one holder's reality test. Reads the retained shadow frustum from {@code
     * holderField} and asks whether the KNOWN-in-view {@code camSection} is visible. Never throws
     * (returns a diagnostic string on any reflective failure so the sibling leg / [5] still log).
     */
    private static String oneFrustumTest(Object shadowRenderer, java.lang.reflect.Field holderField,
                                         String label, AABB camSection) {
        try {
            Object holder = holderField.get(shadowRenderer);
            if (holder == null) {
                return "      [6-" + label + "] SKIPPED — " + label.toLowerCase() + "FrustumHolder==null"
                    + " (frustum never built this pass; corroborates an empty shadow render)";
            }
            String distInfo = String.valueOf(mHolderGetDistanceInfo.invoke(holder));
            String cullInfo = String.valueOf(mHolderGetCullingInfo.invoke(holder));
            Object frustumObj = mHolderGetFrustum.invoke(holder);
            if (frustumObj == null) {
                return "      [6-" + label + "] SKIPPED — getFrustum()==null (distanceInfo=" + distInfo
                    + " cullingInfo=" + cullInfo + ")";
            }
            if (!(frustumObj instanceof Frustum frustum)) {
                return "      [6-" + label + "] SKIPPED — getFrustum() returned "
                    + frustumObj.getClass().getName() + ", not a net.minecraft...Frustum (cullingInfo="
                    + cullInfo + ")";
            }
            boolean visible = frustum.isVisible(camSection);
            return "      [6-" + label + "] frustum=" + identityString(frustumObj)
                + " cullingInfo=" + cullInfo + " distanceInfo=" + distInfo
                + " isVisible(known camera-section)=" + (visible ? "TRUE" : "FALSE")
                + (visible ? " (frustum OK)" : " (DEGENERATE — rejects an in-view section)");
        }
        catch (Throwable t) {
            return "      [6-" + label + "] FAILED (" + t + ")";
        }
    }

    /** Cast an {@code org.joml.Matrix4fc} to a fresh {@code float[16]} (column-major), or null. */
    private static float[] toFloats(Object m) {
        if (!(m instanceof Matrix4fc mat)) {
            return null;
        }
        return mat.get(new float[16]);
    }

    /** Max absolute per-element difference of two column-major 16-float matrices, or null if either. */
    private static Float maxAbsDiff(float[] a, float[] b) {
        if (a == null || b == null || a.length != 16 || b.length != 16) {
            return null;
        }
        float m = 0.0f;
        for (int i = 0; i < 16; i++) {
            m = Math.max(m, Math.abs(a[i] - b[i]));
        }
        return m;
    }

    private static String maxAbsDiffStr(float[] a, float[] b) {
        Float d = maxAbsDiff(a, b);
        return d == null ? "n/a" : d.toString();
    }

    /** Compact column-major 16-float format for a log line. */
    private static String fmt(float[] f) {
        if (f == null || f.length != 16) {
            return "(no matrix)";
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append('[');
        for (int i = 0; i < 16; i++) {
            if (i > 0) {
                sb.append(i % 4 == 0 ? " | " : ", ");
            }
            sb.append(f[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Step [4]: read a centered patch of the OW pipeline's shadow depth texture via the depth-source
     * FBO (resolved LIVE). Returns a human verdict string. Brackets {@code GL_PACK_*} state (the
     * 26.2 invariant #4 stale-ROW_LENGTH landmine) exactly like {@code ShaderpackViewsProbe} P-OQ4.
     * Never throws (any failure returns a diagnostic string and the outer endPass keeps the block).
     */
    private static String readbackShadowDepth(Object shadowTargets) {
        if (shadowTargets == null) {
            return "depth readback SKIPPED — shadowRenderTargets==null (pack has no shadow pass)";
        }
        int prevRead = -1;
        boolean bound = false;
        // saved pack state (restored in reverse)
        int prevPackRowLength = -1, prevPackSkipRows = -1, prevPackSkipPixels = -1, prevPackAlignment = -1;
        boolean packSaved = false;
        try {
            Object fb = mGetDepthSourceFb.invoke(shadowTargets);
            if (fb == null) {
                return "depth readback SKIPPED — getDepthSourceFb()==null";
            }
            boolean hasDepth = (Boolean) mFbHasDepthAttachment.invoke(fb);
            if (!hasDepth) {
                return "depth readback SKIPPED — depth-source FBO reports no depth attachment";
            }
            int fbId = (Integer) mFbGetId.invoke(fb);
            int resolution = (Integer) mGetResolution.invoke(shadowTargets);
            if (fbId <= 0 || resolution <= 0) {
                return "depth readback SKIPPED — bad fbId=" + fbId + " resolution=" + resolution;
            }

            int rw = Math.min(PATCH, resolution);
            int rh = Math.min(PATCH, resolution);
            int rx = Math.max(0, resolution / 2 - rw / 2);
            int ry = Math.max(0, resolution / 2 - rh / 2);

            prevRead = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbId);
            bound = true;

            // PACK-STATE BRACKET (26.2 invariant #4 / P-OQ4): glReadPixels into CLIENT memory obeys
            // GL_PACK_* state, and copyTextureToBuffer leaves GL_PACK_ROW_LENGTH stale — a stale row
            // length > rw strides rh rows past the FloatBuffer (native heap corruption). Save, force
            // tight, read, restore in exact reverse. The stale value is logged as in-run evidence.
            prevPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
            prevPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
            prevPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
            prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            packSaved = true;
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);

            FloatBuffer depths = BufferUtils.createFloatBuffer(rw * rh);
            GL11.glReadPixels(rx, ry, rw, rh, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depths);
            int glErr = GL11.glGetError();

            // restore pack state (exact reverse)
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevPackSkipRows);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevPackRowLength);
            packSaved = false;

            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            bound = false;

            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            double sum = 0.0;
            int n = rw * rh;
            for (int i = 0; i < n; i++) {
                float d = depths.get(i);
                min = Math.min(min, d);
                max = Math.max(max, d);
                sum += d;
            }
            // Empty shadow map = every texel at the depth-clear value. Iris shadow uses an
            // orthographic projection with conventional depth (clear 1.0); the reversed-Z case
            // (clear 0.0) is also tested so the verdict holds regardless of the pack's convention.
            boolean allFarConventional = min >= 1.0f - 1.0e-5f;
            boolean allNearReversed = max <= 1.0e-6f;
            boolean empty = allFarConventional || allNearReversed;
            String staleNote = (prevPackRowLength != 0)
                ? " (GL_PACK_ROW_LENGTH was STALE=" + prevPackRowLength + " — bracket prevented the"
                    + " P-OQ4 native-heap corruption)"
                : "";
            return "depth readback (" + rw + "x" + rh + " centered, fbo=" + fbId
                + ", res=" + resolution + "): min=" + min + " max=" + max + " mean=" + (sum / n)
                + " glGetError=" + glErr + staleNote + " -> "
                + (empty
                    ? "ALL-CLEARED: OW SHADOW DEPTH MAP EMPTY (no terrain/geometry drawn into the"
                        + " shadow buffer) — the shadowless/full-bright cause CONFIRMED"
                    : "POPULATED: the shadow depth map HAS geometry (min<far) — the map is NOT"
                        + " empty; the full-bright cause is elsewhere");
        }
        catch (Throwable t) {
            // best-effort restore of anything left dangling, then report (do not rethrow)
            try {
                if (packSaved) {
                    GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
                    GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
                    GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevPackSkipRows);
                    GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevPackRowLength);
                }
                if (bound && prevRead >= 0) {
                    GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
                }
            }
            catch (Throwable ignored) {
                // never let cleanup escape
            }
            return "depth readback FAILED (" + t + ") — [2] counts carry the verdict this run";
        }
    }

    /**
     * Resolve every reflective handle once. On success sets {@link #reflectionReady}. On iris-absent
     * (ClassNotFound) or any structural miss (NoSuchField/Method — a symbol the jar renamed), logs
     * ONE line and DISARMS the probe so it never retries or crashes. Returns readiness.
     */
    private static synchronized boolean ensureReflection() {
        if (reflectionReady) {
            return true;
        }
        if (reflectionAttempted) {
            return false; // already tried and failed/disarmed
        }
        reflectionAttempted = true;
        try {
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            mGetPipelineManager = irisClass.getMethod("getPipelineManager");
            mGetCurrentDimension = irisClass.getMethod("getCurrentDimension");

            Class<?> pmClass = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
            mGetPipelineNullable = pmClass.getMethod("getPipelineNullable");

            irisPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            fShadowRenderer = irisPipelineClass.getDeclaredField("shadowRenderer");
            fShadowRenderer.setAccessible(true);
            fShadowRenderTargets = irisPipelineClass.getDeclaredField("shadowRenderTargets");
            fShadowRenderTargets.setAccessible(true);

            Class<?> srClass = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
            fActive = srClass.getField("ACTIVE");
            fVisibleBlockEntities = srClass.getField("visibleBlockEntities");
            fRenderedShadowEntities = srClass.getDeclaredField("renderedShadowEntities");
            fRenderedShadowEntities.setAccessible(true);
            fRenderedShadowBEs = srClass.getDeclaredField("renderedShadowBlockEntities");
            fRenderedShadowBEs.setAccessible(true);
            fDebugStringTerrain = srClass.getDeclaredField("debugStringTerrain");
            fDebugStringTerrain.setAccessible(true);
            mGetSunAngle = srClass.getMethod("getSunAngle", boolean.class);

            Class<?> srsClass = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState");
            mAreShadowsRendered = srsClass.getMethod("areShadowsCurrentlyBeingRendered");

            Class<?> srtClass = Class.forName("net.irisshaders.iris.shadows.ShadowRenderTargets");
            mGetDepthSourceFb = srtClass.getMethod("getDepthSourceFb");
            mGetResolution = srtClass.getMethod("getResolution");

            Class<?> fbClass = Class.forName("net.irisshaders.iris.gl.framebuffer.GlFramebuffer");
            mFbGetId = fbClass.getMethod("getId");
            mFbHasDepthAttachment = fbClass.getMethod("hasDepthAttachment");

            // ===== §7 [5] CapturedRenderingState (javap: public static final INSTANCE;
            //             public Matrix4fc getGbufferProjection(); public Matrix4fc getGbufferModelView()) ==
            Class<?> crsClass = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            fCapturedInstance = crsClass.getField("INSTANCE");
            mGetGbufferProjection = crsClass.getMethod("getGbufferProjection");
            mGetGbufferModelView = crsClass.getMethod("getGbufferModelView");

            // ===== §7 [6] ShadowRenderer.terrainFrustumHolder (private FrustumHolder) + FrustumHolder ==
            fTerrainFrustumHolder = srClass.getDeclaredField("terrainFrustumHolder");
            fTerrainFrustumHolder.setAccessible(true);
            // NOTE-1 fold — the SEPARATE entity-cull frustum (the renderedShadowEntities=0 half of the
            // defect travels through this holder, NOT terrainFrustumHolder). javap-confirmed private
            // FrustumHolder on ShadowRenderer, putfield-reused (offs. 233/852), NEVER tail-nulled.
            fEntityFrustumHolder = srClass.getDeclaredField("entityFrustumHolder");
            fEntityFrustumHolder.setAccessible(true);
            Class<?> fhClass = Class.forName("net.irisshaders.iris.shadows.frustum.FrustumHolder");
            mHolderGetFrustum = fhClass.getMethod("getFrustum");
            mHolderGetDistanceInfo = fhClass.getMethod("getDistanceInfo");
            mHolderGetCullingInfo = fhClass.getMethod("getCullingInfo");

            // ===== §7 [5] sodium duck — AUXILIARY, tolerated-absent. Resolve in a nested try so a
            // sodium-shape drift (or the unreachable iris-without-sodium row) leaves mSodiumGetProjection
            // null and [5] logs "n/a" for the sodium seed, WITHOUT disarming the iris legs above.
            // NAMING-BAN CATCH: the task named this "net.irisshaders...GameRendererStorage"; javap
            // confirms it is net.caffeinemc.mods.sodium.client.util.GameRendererStorage (a sodium
            // interface merged into GameRenderer; returns org.joml.Matrix4fc). ==========================
            try {
                Class<?> grsClass =
                    Class.forName("net.caffeinemc.mods.sodium.client.util.GameRendererStorage");
                mSodiumGetProjection = grsClass.getMethod("sodium$getProjectionMatrix");
            }
            catch (Throwable auxT) {
                mSodiumGetProjection = null;
                LOGGER.info(P + "sodium GameRendererStorage duck not resolved (auxiliary — the [5]"
                    + " sodium-seed dump will read 'n/a'; iris legs unaffected): " + auxT);
            }

            reflectionReady = true;
            return true;
        }
        catch (ClassNotFoundException e) {
            disarmed = true; // iris not on the runtime — inert, quiet (expected in most runs)
            LOGGER.info(P + "iris classes not present on the runtime — probe inert for this session");
            return false;
        }
        catch (Throwable t) {
            disarm(t); // a real structural mismatch (jar renamed a symbol) — loud, one-shot
            return false;
        }
    }

    private static String identityString(Object o) {
        return o == null ? "null"
            : (o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o)));
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        capturing = false;
        try {
            LOGGER.warn(P + "disarmed after a throw (diagnostic only, render unaffected)", t);
        }
        catch (Throwable ignored) {
            // never let the probe's own failure escape
        }
    }
}
