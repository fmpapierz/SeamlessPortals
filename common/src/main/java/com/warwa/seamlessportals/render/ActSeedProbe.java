package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL45;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IS5-ACT — the DEST ACT-VOLUME SEEDING GATE PROBE. Log-only, default OFF, behaviour-neutral.
 *
 * <p><b>Why this exists.</b> Complementary Reimagined's "Advanced Color Tracing" (ACT,
 * {@code COLORED_LIGHTING=512}) keeps a persistent 3D flood-fill light volume — {@code floodfill_img}
 * + {@code floodfill_img_copy} (clear=false, 512 MiB each) fed from {@code voxel_img} (clear=true).
 * Through a portal window the colored light is wrong: SAME-dimension windows show the SOURCE world's
 * colored light, and CROSS-dimension windows show NO colored light at all (vanilla-lit only).
 *
 * <p>A prior engagement proposed giving each destination its own volume. That was <b>refuted before
 * any code</b>: iris keeps one pipeline per dimension ({@code PipelineManager.pipelinesPerDimension})
 * and {@code IrisRenderingPipeline} owns {@code customImages} as INSTANCE state, so a cross-dim dest
 * ALREADY has its own volume — and still shows no colored light. Storage was never the blocker. The
 * open question this kit answers is therefore: <b>why is the destination's ACT volume never
 * seeded?</b>
 *
 * <p><b>The four hypotheses, and the legs that separate them:</b>
 * <ul>
 *   <li><b>H-A</b> the nested dest render never reaches a shadow pass at all (Complementary's
 *       flood-fill compute lives in the SHADOWCOMP stage) — leg [3] {@code shadowRan=NO} + {@code why=};</li>
 *   <li><b>H-B</b> the shadow pass runs but rasterises nothing (cull frustum) — {@code shadowRan=YES}
 *       + DEST voxel {@code nz=0} while the MAIN control reads {@code nz>0};</li>
 *   <li><b>H-C</b> voxelised but never flood-filled — DEST voxel {@code nz>0}, DEST ff {@code nz=0};</li>
 *   <li><b>H-D</b> the volume IS seeded and the defect is on the READ side — DEST voxel and ff both
 *       {@code nz>0}. This would refute the premise of the whole engagement, which is why it is an
 *       explicit outcome rather than an unconsidered one.</li>
 * </ul>
 *
 * <p><b>Lever discipline.</b> {@code -Dseamlessportals.actProbe} arms legs [1]-[3] (pure reads, ZERO
 * GL). {@code -Dseamlessportals.actVolumeProbe} additionally arms leg [4], the only GL in the kit (a
 * 1 Hz DSA readback; it costs a visible ~1 Hz hitch and must never be left on for gameplay). Both are
 * read via {@link Boolean#getBoolean} into {@code static final} fields at class-init: the JVM flag is
 * a RUNTIME read, so the constant folds to {@code false} in every shipped run and the entire body is
 * dead code at the default — the same shape as {@link ShadowEmptinessProbe}.
 *
 * <p><b>Behaviour neutrality.</b> No iris type crosses this seam (everything is reflection, so an
 * iris-absent runtime — e.g. the gametest suite — disarms quietly instead of raising
 * NoClassDefFoundError). No field is ever WRITTEN. {@code ComputeProgram.getWorkGroups} is never
 * called (it mutates a cache); the raw {@code absoluteWorkGroups}/{@code relativeWorkGroups} are read
 * instead. {@code glGetError} is never called — it would drain the queue that
 * {@code CHelper.checkGlError()} depends on. Every public entry is wrapped so the probe can never
 * throw into the render path.
 *
 * <p><b>Render-thread logging.</b> Per-frame LOGGER on the 26.2 render thread costs ~130 ms stalls,
 * so counting is unconditional (bare int increments) while CAPTURE and EMISSION happen at most once
 * per second, as EXACTLY ONE {@link Logger#info} call carrying the whole multi-line block.
 */
public final class ActSeedProbe {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[IS5-ACT] ";

    private static final boolean ENABLED = Boolean.getBoolean("seamlessportals.actProbe");
    private static final boolean VOLUME = Boolean.getBoolean("seamlessportals.actVolumeProbe");
    private static final long RATE_LIMIT_NS = 1_000_000_000L;

    /** Readback box edges. voxel 64^3 = +/-32 blocks (wide net); floodfill 32^3 = +/-16 blocks. */
    private static final int VOXEL_BOX = 64;
    private static final int FF_BOX = 32;
    /** 64^3 * 2B (r16ui) = 512 KiB; 32^3 * 4 * 4B (rgba float) = 512 KiB. One size fits both. */
    private static final int READBACK_BYTES = 512 * 1024;

    private ActSeedProbe() {
    }

    // =============================================================================================
    // Latches
    // =============================================================================================

    private static boolean disarmed = false;
    private static boolean volumeDisarmed = false;
    private static boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;
    private static final Set<String> warnedOnce = new HashSet<>();
    private static boolean liveLogged = false;

    // =============================================================================================
    // Reflective handles — resolved once. No net.irisshaders.* type appears in a signature.
    // =============================================================================================

    private static Method mGetPipelineManager;      // Iris.getPipelineManager()
    private static Method mGetCurrentDimension;     // Iris.getCurrentDimension()
    private static Method mIsPackInUseQuick;        // Iris.isPackInUseQuick()
    private static Method mGetPipelineNullable;     // PipelineManager.getPipelineNullable()
    private static Field fPipelinesPerDimension;    // PipelineManager.pipelinesPerDimension
    private static Class<?> irisPipelineClass;      // IrisRenderingPipeline
    private static Field fShadowRenderer;           // IrisRenderingPipeline.shadowRenderer
    private static Field fCustomImages;             // IrisRenderingPipeline.customImages

    private static Field fModelView;                // ShadowRenderer.MODELVIEW  (public static)
    private static Field fProjection;               // ShadowRenderer.PROJECTION (public static)
    private static Field fFrustum;                  // ShadowRenderer.FRUSTUM    (public static)
    private static Field fResolution;               // ShadowRenderer.RESOLUTION (public static)
    private static Field fRenderedShadowEntities;
    private static Field fRenderedShadowBEs;
    private static Field fDebugStringTerrain;
    private static Field fTerrainFrustumHolder;
    private static Field fEntityFrustumHolder;
    private static Field fPackHasVoxelization;      // tolerated-absent
    private static Field fPackCullingState;         // tolerated-absent
    private static Field fCompositeRenderer;        // ShadowRenderer.compositeRenderer (read only)
    private static Field fPasses;                   // ShadowCompositeRenderer.passes
    private static Method mHolderGetFrustum;
    private static Method mHolderGetDistanceInfo;
    private static Method mHolderGetCullingInfo;

    private static Method mImgGetName;
    private static Method mImgGetId;
    private static Method mImgGetTarget;
    private static Method mImgShouldClear;
    private static Method mImgGetInternalFormat;

    private static Field fShadowDistance;              // IrisVideoSettings.shadowDistance
    private static Method mGetOverriddenShadowDistance; // IrisVideoSettings.getOverriddenShadowDistance(int)

    // =============================================================================================
    // Frame / portal state
    // =============================================================================================

    private static boolean inFrame = false;
    private static boolean inPortal = false;
    private static boolean armed = false;
    private static long frameSerial = 0L;
    private static long lastEmitNanos = 0L;
    private static final long CLASS_INIT_NANOS = System.nanoTime();

    private static int kThisFrame = 0;
    private static int listedThisFrame = 0;
    private static int sameIdThisFrame = 0;
    private static int crossIdThisFrame = 0;
    private static int rowsThisFrame = 0;
    private static final Set<String> destNsIdsThisFrame = new HashSet<>();

    private static long listedTotal = 0L;
    private static long renderedTotal = 0L;
    private static long emittedBlocks = 0L;

    /** 60-slot per-second ring => a true rolling 60 s maximum (not a tumbling window). */
    private static final int[] ringListed = new int[60];
    private static final int[] ringRendered = new int[60];
    private static final int[] ringDistinct = new int[60];
    private static long lastEpochSec = -1L;

    private static StringBuilder block;

    /** Per-portal captured references (identity compared; hashes collide across GC). */
    private static Object preModelView;
    private static Object preProjection;
    private static Object preFrustum;
    private static String pDestDim = "?";
    private static boolean pSharedState = false;
    private static Vec3 pDestCam = Vec3.ZERO;
    private static long pNoopHits = 0L;
    private static long pInstalls = 0L;

    /** MAIN-anchor snapshot, taken at beginFrame (BEFORE IS5-FF install). */
    private static String mainNsId = "?";
    private static Object mainPipeline;
    private static Object mainShadowRenderer;
    private static int mainVoxelId = -1;
    private static int mainFfId = -1;
    private static int mainFfCopyId = -1;

    private static ByteBuffer readbackBuf;
    private static boolean gl45Checked = false;
    private static boolean gl45Ok = false;

    /** Round-2: the current portal's dest floodfill ids, published for {@link ActDispatchProbe}'s
     *  R-4 binding check. Set in {@code appendPortalRow}; -1 outside a captured portal row. */
    private static int destFfId = -1;
    private static int destFfCopyId = -1;

    static Vec3 currentDestCam() {
        return pDestCam;
    }

    /**
     * Resolved LIVE, not from the cached per-row fields: the round-2 dispatch capture fires DURING the
     * nested dest render, i.e. BEFORE {@code appendPortalRow} runs, so the cached ids would be the
     * previous portal's. At capture time {@code getPipelineNullable()} already resolves to the dest
     * pipeline (the level swap is in force), so a fresh lookup is both correct and cheap enough for a
     * once-per-armed-window call.
     */
    static int currentDestFfId() {
        int id = liveImageId("floodfill_img");
        return id > 0 ? id : destFfId;
    }

    static int currentDestFfCopyId() {
        int id = liveImageId("floodfill_img_copy");
        return id > 0 ? id : destFfCopyId;
    }

    private static int liveImageId(String name) {
        try {
            if (!reflectionReady) {
                return -1;
            }
            Object pm = mGetPipelineManager.invoke(null);
            if (pm == null) {
                return -1;
            }
            Object pipeline = mGetPipelineNullable.invoke(pm);
            if (pipeline == null || !irisPipelineClass.isInstance(pipeline)) {
                return -1;
            }
            return imageId(customImages(pipeline), name);
        }
        catch (Throwable t) {
            return -1;
        }
    }

    // =============================================================================================
    // Public entries — every one is total: it never throws into the render path.
    // =============================================================================================

    /**
     * FRAME bracket open. MUST be called BEFORE {@code IrisShadowCompositeSuppressor.install()}:
     * after the install the MAIN pipeline's {@code ShadowRenderer.compositeRenderer} is the no-op
     * with an EMPTY passes list, and leg [2]'s shadowcomp roster would read a lie.
     */
    public static void beginFrame() {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            if (inFrame) {
                warnOnce("desync", P + "frame/portal bracket DESYNC (re-entrant beginFrame) —"
                    + " records may be mis-attributed. Treat this session's census as unreliable.", null);
            }
            inFrame = true;
            inPortal = false;
            frameSerial++;
            kThisFrame = 0;
            listedThisFrame = 0;
            sameIdThisFrame = 0;
            crossIdThisFrame = 0;
            rowsThisFrame = 0;
            destNsIdsThisFrame.clear();

            if (VOLUME && !ENABLED) {
                warnOnce("lever", P + "-Dseamlessportals.actVolumeProbe requires"
                    + " -Dseamlessportals.actProbe — the volume leg is INERT.", null);
            }

            long now = System.nanoTime();
            armed = (now - lastEmitNanos) >= RATE_LIMIT_NS;
            // Round-2: the dispatch witness shares this frame clock (its MAIN capture keeps its own,
            // because the MAIN shadowcomp has already dispatched by the time this anchor runs).
            ActDispatchProbe.checkLeverPairing(ENABLED);
            ActDispatchProbe.onFrameArmed(armed);
            if (!armed) {
                return;
            }
            lastEmitNanos = now;
            block = new StringBuilder(4096);
            block.append(P).append("FRAME #").append(frameSerial).append(" (armed, 1Hz)\n");
            captureMainBlock();
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Census: the LISTED count (pre-occlusion) — splits "no portal on screen" from "all rejected". */
    public static void onPortalListSize(int listed) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            listedThisFrame += listed;
            listedTotal += listed;
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * PORTAL bracket open, called from inside the level swap. Snapshots the iris ShadowRenderer
     * statics so {@link #endPortal()} can prove a shadow pass ran INSIDE this nested dest render.
     */
    public static void beginPortal(ResourceKey<Level> destDim, boolean sharedState, Vec3 destCameraPos) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            if (!inFrame) {
                warnOnce("desync", P + "frame/portal bracket DESYNC (beginPortal outside a frame) —"
                    + " records may be mis-attributed. Treat this session's census as unreliable.", null);
            }
            inPortal = true;
            ActDispatchProbe.beginWindow();
            kThisFrame++;
            renderedTotal++;
            pDestDim = destDim == null ? "null" : destDim.identifier().toString();
            pSharedState = sharedState;
            pDestCam = destCameraPos == null ? Vec3.ZERO : destCameraPos;
            destNsIdsThisFrame.add(pDestDim);

            if (!ensureReflection()) {
                return;
            }
            // Reference snapshot (NOT identity hashes — those collide across GC).
            preModelView = fModelView.get(null);
            preProjection = fProjection.get(null);
            preFrustum = fFrustum.get(null);

            pNoopHits = IPGlobal.nestedShadowCompositeNoopHits;
            pInstalls = IPGlobal.nestedShadowCompositeSuppressCount;

            if (!PortalRendering.isRendering()) {
                warnOnce("noportal", P + "skip (once-only): capture fired OUTSIDE a pushed portal"
                    + " layer — the hook slot has moved; portal identity is n/a for this block.", null);
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * PORTAL bracket close + capture. THIS SLOT IS LOAD-BEARING: {@code client.level} is still the
     * DEST here ({@code MyGameRenderer:595} sets it, {@code :669} restores it in
     * {@code switchAndRenderTheWorldFullPipeline}'s own finally, which has not run yet), so
     * {@code Iris.getCurrentDimension()} and {@code getPipelineNullable()} both resolve to the DEST.
     * The same read taken in {@code IrisCompatOn262Renderer.invokeWorldRendering}'s finally would see
     * the SOURCE dimension and silently produce a false "same pipeline" verdict.
     */
    public static void endPortal() {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            if (!inPortal) {
                warnOnce("desync", P + "frame/portal bracket DESYNC (endPortal without beginPortal) —"
                    + " records may be mis-attributed. Treat this session's census as unreliable.", null);
            }
            inPortal = false;
            ActDispatchProbe.endWindow();
            if (!reflectionReady) {
                return;
            }

            Object postModelView = fModelView.get(null);
            Object postProjection = fProjection.get(null);
            Object postFrustum = fFrustum.get(null);
            boolean mvChanged = postModelView != preModelView;
            boolean projChanged = postProjection != preProjection;
            boolean frusChanged = postFrustum != preFrustum;
            // Decision variable = MODELVIEW (offset 189, the earliest unconditional store past the
            // offset-9 early return). PROJECTION/FRUSTUM are corroboration only.
            boolean shadowRan = mvChanged;
            if (!(mvChanged == projChanged && mvChanged == frusChanged)) {
                warnOnce("proxy-mismatch", P + "shadowRan proxy MISMATCH (MV=" + mvChanged
                    + " PROJ=" + projChanged + " FRUS=" + frusChanged + ") — renderShadows is not"
                    + " storing all three unconditionally on this build. shadowRan is decided on"
                    + " MODELVIEW; treat it as provisional and report this line.", null);
            }
            preModelView = null;
            preProjection = null;
            preFrustum = null;

            String destNsId = safeNsId();
            boolean crossId = !destNsId.equals(mainNsId);
            if (crossId) {
                crossIdThisFrame++;
            }
            else {
                sameIdThisFrame++;
            }
            // Feeds the round-2 VOID-RUN guard: a session of only same-dim windows measures nothing.
            ActDispatchProbe.noteWindowClass(crossId);

            if (!liveLogged) {
                liveLogged = true;
                LOGGER.info(P + "LIVE (once-only): first armed capture of a nested dest render —"
                    + " destNsId={} CLASS={} — the kit is reaching the seam.",
                    destNsId, crossId ? "CROSS-ID" : "SAME-ID");
            }

            if (!armed || block == null || rowsThisFrame >= 4) {
                return;
            }
            rowsThisFrame++;
            appendPortalRow(shadowRan, mvChanged, projChanged, frusChanged, destNsId, crossId);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** FRAME bracket close: fold the census ring, emit exactly ONE LOGGER.info, run the watchdogs. */
    public static void endFrame() {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            inFrame = false;
            foldRing();
            if (armed && block != null) {
                // Round-2: the MAIN ACT-DISPATCH positive control (its own 1 Hz clock — the main
                // shadowcomp has already dispatched by the time beginFrame runs).
                ActDispatchProbe.appendMainSection(block);
                appendCensus();
                emittedBlocks++;
                LOGGER.info(block.toString());
                block = null;
            }
            armed = false;
            watchdogs();
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // =============================================================================================
    // Leg [2] — the MAIN anchor block
    // =============================================================================================

    private static void captureMainBlock() throws Exception {
        if (!ensureReflection()) {
            return;
        }
        block.append("  [2] MAIN (frame anchor, PRE-IS5-FF-install)\n");
        Object pm = mGetPipelineManager.invoke(null);
        if (pm == null) {
            warnOnce("pmnull", P + "skip (once-only): PipelineManager==null during a portal frame —"
                + " iris uninitialised; every leg reads n/a.", null);
            block.append("      pipeline=n/a (PipelineManager==null)\n");
            return;
        }
        Object pipeline = mGetPipelineNullable.invoke(pm);
        mainPipeline = pipeline;
        mainNsId = safeNsId();
        block.append("      pipeline=").append(identityString(pipeline))
            .append("  getCurrentDimension=").append(mainNsId).append('\n');

        if (pipeline == null || !irisPipelineClass.isInstance(pipeline)) {
            warnOnce("vanilla", P + "idle (once-only): active pipeline is "
                + (pipeline == null ? "null" : pipeline.getClass().getName())
                + " (vanilla fallback / no pack) — this RUN IS VOID for the ACT question; enable the"
                + " shaderpack and re-run.", null);
            block.append("      [VOID RUN: not an IrisRenderingPipeline]\n");
            return;
        }

        Object sr = fShadowRenderer.get(pipeline);
        mainShadowRenderer = sr;
        block.append("      shadowRenderer=").append(identityString(sr))
            .append("  packHasVoxelization=").append(readBool(fPackHasVoxelization, sr))
            .append("  packCullingState=").append(readObj(fPackCullingState, sr)).append('\n');
        block.append("      shadowDistanceEffective=").append(effectiveShadowDistance())
            .append("   (0 => renderShadows early-returns at off.9)\n");
        block.append("      shadowcompConfig: ").append(describeShadowcomp("MAIN", sr)).append('\n');

        List<Object> imgs = customImages(pipeline);
        block.append("      images: ").append(describeImages(imgs)).append('\n');
        mainVoxelId = imageId(imgs, "voxel_img");
        mainFfId = imageId(imgs, "floodfill_img");
        mainFfCopyId = imageId(imgs, "floodfill_img_copy");
        if (mainFfId < 0) {
            warnOnce("noimages", P + "FINDING (once-only): customImages holds no \"floodfill_img\""
                + " (names seen: " + imageNames(imgs) + ") — the shaderpack profile is NOT running"
                + " Advanced Color Tracing (COLORED_LIGHTING=0). RE-RUN AT ULTRA; every ACT verdict"
                + " from this session is void.", null);
        }

        block.append("      shadowStatics(pre): MODELVIEW").append(identityString(fModelView.get(null)))
            .append(" PROJECTION").append(identityString(fProjection.get(null)))
            .append(" FRUSTUM").append(identityString(fFrustum.get(null)))
            .append(" RESOLUTION=").append(readInt(fResolution, null)).append('\n');
        block.append("      manager: ").append(describeManager(pm)).append('\n');

        if (VOLUME) {
            block.append("  [4] VOLUME READBACK  boxes: voxel=").append(VOXEL_BOX)
                .append("^3 ff=").append(FF_BOX).append("^3 (centred)\n");
            block.append("      MAIN  ").append(readbackTriple(mainVoxelId, mainFfId, mainFfCopyId))
                .append("   [POSITIVE CONTROL]\n");
        }
    }

    // =============================================================================================
    // Leg [3] — the per-portal row
    // =============================================================================================

    private static void appendPortalRow(boolean shadowRan, boolean mv, boolean pj, boolean fr,
                                        String destNsId, boolean crossId) throws Exception {
        int layer = PortalRendering.isRendering() ? PortalRendering.getPortalLayer() : -1;
        int portalHash = PortalRendering.isRendering()
            ? System.identityHashCode(PortalRendering.getRenderingPortal()) : 0;

        block.append("  [3] PORTAL ").append(rowsThisFrame).append('/').append(kThisFrame)
            .append("  portal@").append(Integer.toHexString(portalHash))
            .append(" layer=").append(layer)
            .append(" destDim=").append(pDestDim)
            .append(" sharedState=").append(pSharedState).append('\n');
        block.append("      CLASS=").append(crossId ? "CROSS-ID" : "SAME-ID")
            .append("  mainNsId=").append(mainNsId).append("  destNsId=").append(destNsId);

        Object pm = mGetPipelineManager.invoke(null);
        Object destPipeline = pm == null ? null : mGetPipelineNullable.invoke(pm);
        block.append("  pipelineIdentity=")
            .append(destPipeline == mainPipeline ? "SAME-OBJECT" : "DIFFERENT").append('\n');

        block.append("      *** shadowRan=").append(shadowRan ? "YES" : "NO").append(" ***")
            .append("  (MV ").append(mv).append(" PROJ ").append(pj)
            .append(" FRUS ").append(fr).append(")\n");
        block.append("      why=").append(shadowRan ? "-" : whyNotShadow(destPipeline)).append('\n');

        Object destSr = null;
        if (destPipeline != null && irisPipelineClass.isInstance(destPipeline)) {
            destSr = fShadowRenderer.get(destPipeline);
        }
        if (destPipeline == null) {
            warnOnce("plnull", P + "FINDING (once-only): the nested dest render ended with NO active"
                + " iris pipeline — that alone explains an unseeded dest volume. Report this line.", null);
        }
        if (destSr == null && destPipeline != null) {
            warnOnce("srnull", P + "FINDING (once-only): dest pipeline has shadowRenderer==null — the"
                + " pack's shadow program is disabled for this dimension (SHADOW_QUALITY=-1?)."
                + " Voxelisation can never run. Check the profile and re-run.", null);
        }

        block.append("      destPipeline=").append(identityString(destPipeline))
            .append("  destShadowRenderer=").append(identityString(destSr))
            .append(' ').append(destSr == mainShadowRenderer ? "SAME-AS-MAIN" : "DIFFERENT").append('\n');
        block.append("      comp=").append(identityString(readObj2(fCompositeRenderer, destSr)))
            .append("  destShadowcomp: ").append(describeShadowcomp("DEST", destSr)).append('\n');

        List<Object> destImgs = destPipeline == null ? new ArrayList<>() : customImages(destPipeline);
        int dVox = imageId(destImgs, "voxel_img");
        int dFf = imageId(destImgs, "floodfill_img");
        int dFfC = imageId(destImgs, "floodfill_img_copy");
        destFfId = dFf;
        destFfCopyId = dFfC;
        block.append("      destImages: voxel=").append(dVox)
            .append(dVox == mainVoxelId ? " SAME-ID-AS-MAIN" : " DIFFERENT")
            .append("  ff=").append(dFf).append('/').append(dFfC)
            .append(dFf == mainFfId ? " SAME" : " DIFFERENT").append('\n');

        block.append("      shadowCounts: ents=").append(readInt(fRenderedShadowEntities, destSr))
            .append(" bes=").append(readInt(fRenderedShadowBEs, destSr))
            .append("   ~terrainDbg=\"").append(readObj(fDebugStringTerrain, destSr)).append('"')
            .append("  [26.2 CAVEAT: mc.levelExtractor.sectionStatistics(), NOT shadow-scoped —"
                + " corroboration only]\n");

        appendFrustumLine("terrain", fTerrainFrustumHolder, destSr);
        appendFrustumLine("entity ", fEntityFrustumHolder, destSr);

        block.append("      IS5-FF delta over THIS portal: noopHits=+")
            .append(IPGlobal.nestedShadowCompositeNoopHits - pNoopHits)
            .append(" installs=+").append(IPGlobal.nestedShadowCompositeSuppressCount - pInstalls)
            .append('\n');

        if (VOLUME && dVox != mainVoxelId && dVox > 0) {
            // Skipped when the ids match (same-dim => same object): the MAIN row already covers it.
            block.append("      DEST  ").append(readbackTriple(dVox, dFf, dFfC))
                .append("  destCam=(").append(fmt(pDestCam.x)).append(',').append(fmt(pDestCam.y))
                .append(',').append(fmt(pDestCam.z)).append(")\n");
        }
        else if (VOLUME) {
            block.append("      DEST  readback SKIPPED (dest voxel id ").append(dVox)
                .append(" == main ").append(mainVoxelId)
                .append(" => shared volume; the MAIN row already describes it)\n");
        }

        // Round-2: the per-window dispatch census + the [5] DEST ACT-DISPATCH section.
        ActDispatchProbe.appendDestSection(block);
    }

    private static void appendFrustumLine(String which, Field holderField, Object destSr) {
        try {
            Object holder = readObj2(holderField, destSr);
            if (holder == null) {
                block.append("      frustum(").append(which).append("): n/a\n");
                return;
            }
            Object frustum = mHolderGetFrustum.invoke(holder);
            String vis = "n/a";
            if (frustum instanceof Frustum f) {
                // The dest camera's own 16^3 section: if THIS is culled, nothing of the dest can draw.
                double bx = Math.floor(pDestCam.x / 16.0) * 16.0;
                double by = Math.floor(pDestCam.y / 16.0) * 16.0;
                double bz = Math.floor(pDestCam.z / 16.0) * 16.0;
                vis = String.valueOf(f.isVisible(new AABB(bx, by, bz, bx + 16, by + 16, bz + 16)));
            }
            block.append("      frustum(").append(which).append("): culling=\"")
                .append(mHolderGetCullingInfo.invoke(holder)).append("\" distance=\"")
                .append(mHolderGetDistanceInfo.invoke(holder)).append("\" isVisible(destCamSection)=")
                .append(vis).append('\n');
        }
        catch (Throwable t) {
            block.append("      frustum(").append(which).append("): READ FAILED ").append(t).append('\n');
        }
    }

    /** Reproduces iris's own guard expressions, first hit wins. */
    private static String whyNotShadow(Object destPipeline) {
        try {
            if (!(Boolean) mIsPackInUseQuick.invoke(null)) {
                return "NO_PACK";
            }
            if (destPipeline == null || !irisPipelineClass.isInstance(destPipeline)) {
                return "VANILLA_PIPE";
            }
            if (fShadowRenderer.get(destPipeline) == null) {
                return "SR_NULL";
            }
            if (effectiveShadowDistance() == 0) {
                return "SDIST_0";
            }
            return "UNEXPLAINED";
        }
        catch (Throwable t) {
            return "why-eval-failed(" + t + ")";
        }
    }

    private static int effectiveShadowDistance() {
        try {
            int raw = fShadowDistance.getInt(null);
            return (Integer) mGetOverriddenShadowDistance.invoke(null, raw);
        }
        catch (Throwable t) {
            return -1;
        }
    }

    // =============================================================================================
    // Leg [4] — the volume readback (the ONLY GL in the kit)
    // =============================================================================================

    private static String readbackTriple(int voxelId, int ffId, int ffCopyId) {
        if (volumeDisarmed) {
            return "(leg [4] disarmed)";
        }
        if (!ensureGl45()) {
            return "(GL 4.5 unavailable)";
        }
        try {
            if (GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING) != 0) {
                warnOnce("packbuf", P + "readback SKIPPED (once-only): a pixel-pack buffer is bound at"
                    + " the capture slot; rebinding it would violate behaviour-neutrality. If this"
                    + " recurs every capture, the slot must move.", null);
                return "packBuf!=0 SKIPPED";
            }
            // Make prior image stores visible to the readback. Writes no state, changes no result.
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
            return "voxel@" + voxelId + ": " + readVoxel(voxelId)
                + " | ff@" + ffId + ": " + readFloodfill(ffId)
                + " | ffCopy@" + ffCopyId + ": " + readFloodfill(ffCopyId);
        }
        catch (Throwable t) {
            volumeDisarmed = true;
            warnOnce("volthrew", P + "volume readback THREW — leg [4] disarmed for the session"
                + " (legs [1]-[3] unaffected; render unaffected)", t);
            return "(readback threw)";
        }
    }

    private static String readVoxel(int id) {
        if (id <= 0) {
            return "n/a";
        }
        int[] dim = texDims(id);
        if (dim == null) {
            return "dims?";
        }
        int box = Math.min(VOXEL_BOX, Math.min(dim[0], Math.min(dim[1], dim[2])));
        ByteBuffer buf = buffer();
        int nz = 0;
        int max = 0;
        packBracket(() -> GL45.glGetTextureSubImage(id, 0,
            (dim[0] - box) / 2, (dim[1] - box) / 2, (dim[2] - box) / 2, box, box, box,
            GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, buf));
        int n = box * box * box;
        for (int i = 0; i < n; i++) {
            int v = buf.getShort(i * 2) & 0xFFFF;
            if (v != 0) {
                nz++;
                if (v > max) {
                    max = v;
                }
            }
        }
        return "nz=" + nz + "/" + n + " max=" + max + dimNote(dim);
    }

    private static String readFloodfill(int id) {
        if (id <= 0) {
            return "n/a";
        }
        int[] dim = texDims(id);
        if (dim == null) {
            return "dims?";
        }
        int box = Math.min(FF_BOX, Math.min(dim[0], Math.min(dim[1], dim[2])));
        ByteBuffer buf = buffer();
        int nz = 0;
        float maxLum = 0f;
        // GL_FLOAT (not half): the driver converts the rgba16f for us — no half decoding in Java.
        packBracket(() -> GL45.glGetTextureSubImage(id, 0,
            (dim[0] - box) / 2, (dim[1] - box) / 2, (dim[2] - box) / 2, box, box, box,
            GL11.GL_RGBA, GL11.GL_FLOAT, buf));
        int n = box * box * box;
        for (int i = 0; i < n; i++) {
            float r = buf.getFloat(i * 16);
            float g = buf.getFloat(i * 16 + 4);
            float b = buf.getFloat(i * 16 + 8);
            float s = r + g + b;
            if (s > 1.0e-4f) {
                nz++;
                if (s > maxLum) {
                    maxLum = s;
                }
            }
        }
        return "nz=" + nz + "/" + n + " maxLum=" + fmt(maxLum);
    }

    /**
     * GL_PACK_* bracket — MANDATORY (26.2 invariant #4). A readback into CLIENT memory obeys the
     * GL_PACK_* state, and copyTextureToBuffer leaves GL_PACK_ROW_LENGTH stale; a stale row length
     * strides past the buffer = native heap corruption masquerading as a JIT crash. All EIGHT are
     * saved and restored (3D readback also reads IMAGE_HEIGHT / SKIP_IMAGES).
     */
    private static void packBracket(Runnable body) {
        int aAlign = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int aRow = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int aImgH = GL11.glGetInteger(GL12.GL_PACK_IMAGE_HEIGHT);
        int aSkipP = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int aSkipR = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int aSkipI = GL11.glGetInteger(GL12.GL_PACK_SKIP_IMAGES);
        int aSwap = GL11.glGetInteger(GL11.GL_PACK_SWAP_BYTES);
        int aLsb = GL11.glGetInteger(GL11.GL_PACK_LSB_FIRST);
        try {
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL12.GL_PACK_IMAGE_HEIGHT, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL12.GL_PACK_SKIP_IMAGES, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SWAP_BYTES, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_LSB_FIRST, 0);
            body.run();
        }
        finally {
            GlStateManager._pixelStore(GL11.GL_PACK_LSB_FIRST, aLsb);
            GlStateManager._pixelStore(GL11.GL_PACK_SWAP_BYTES, aSwap);
            GlStateManager._pixelStore(GL12.GL_PACK_SKIP_IMAGES, aSkipI);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, aSkipR);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, aSkipP);
            GlStateManager._pixelStore(GL12.GL_PACK_IMAGE_HEIGHT, aImgH);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, aRow);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, aAlign);
        }
    }

    /** DSA level query — binds nothing. 512x256x512 also confirms COLORED_LIGHTING=512 (ULTRA). */
    private static int[] texDims(int id) {
        try {
            int w = GL45.glGetTextureLevelParameteri(id, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL45.glGetTextureLevelParameteri(id, 0, GL11.GL_TEXTURE_HEIGHT);
            int d = GL45.glGetTextureLevelParameteri(id, 0, GL12.GL_TEXTURE_DEPTH);
            if (w <= 0 || h <= 0 || d <= 0) {
                return null;
            }
            return new int[]{w, h, d};
        }
        catch (Throwable t) {
            return null;
        }
    }

    private static String dimNote(int[] dim) {
        boolean ultra = dim[0] == 512 && dim[1] == 256 && dim[2] == 512;
        return " [" + dim[0] + "x" + dim[1] + "x" + dim[2] + (ultra ? "" : " !! NOT 512x256x512 —"
            + " profile is not ULTRA/512; the ACT verdict from this run is VOID") + "]";
    }

    private static ByteBuffer buffer() {
        if (readbackBuf == null) {
            readbackBuf = BufferUtils.createByteBuffer(READBACK_BYTES).order(ByteOrder.nativeOrder());
        }
        readbackBuf.clear();
        return readbackBuf;
    }

    /**
     * RUN-1 DEFECT FIX. The first live run gated leg [4] on {@code caps.OpenGL45} and lost the whole
     * volume verdict: MC 26.2 / Sodium create a <b>3.3 core context</b> ("OpenGL Version: 3.3.0
     * NVIDIA"), so the CORE-VERSION flag is false on a card that plainly supports 4.6 — iris itself
     * runs compute shaders on that same context via ARB extensions. Gate on the FUNCTION's
     * availability (core OR ARB) instead, and log every relevant capability so the verdict is
     * evidence rather than inference on the next run.
     */
    private static boolean ensureGl45() {
        if (!gl45Checked) {
            gl45Checked = true;
            String caps = "unavailable";
            try {
                org.lwjgl.opengl.GLCapabilities c = GL.getCapabilities();
                // LWJGL populates the GL45 entry points from the ARB extension too, so either route
                // makes the call legal; both are reported so a future failure is self-diagnosing.
                gl45Ok = c.OpenGL45
                    || (c.GL_ARB_get_texture_sub_image && c.GL_ARB_direct_state_access);
                caps = "OpenGL45=" + c.OpenGL45
                    + " OpenGL43=" + c.OpenGL43
                    + " OpenGL30=" + c.OpenGL30
                    + " ARB_get_texture_sub_image=" + c.GL_ARB_get_texture_sub_image
                    + " ARB_direct_state_access=" + c.GL_ARB_direct_state_access;
            }
            catch (Throwable t) {
                gl45Ok = false;
            }
            LOGGER.info(P + "readback capability: {} => leg [4] {}", caps, gl45Ok ? "ARMED" : "OFF");
            if (!gl45Ok) {
                warnOnce("gl45", P + "neither core GL 4.5 nor ARB_get_texture_sub_image +"
                    + " ARB_direct_state_access is available (" + caps + ") — leg [4] is PERMANENTLY"
                    + " OFF this session. Legs [1]-[3] remain valid, but the voxel/floodfill verdict"
                    + " CANNOT be made from this run.", null);
            }
        }
        return gl45Ok;
    }

    // =============================================================================================
    // Leg [1] — census
    // =============================================================================================

    private static void foldRing() {
        long sec = System.nanoTime() / 1_000_000_000L;
        if (sec != lastEpochSec) {
            lastEpochSec = sec;
            int slot = (int) (sec % 60);
            ringListed[slot] = 0;
            ringRendered[slot] = 0;
            ringDistinct[slot] = 0;
        }
        int slot = (int) (sec % 60);
        ringListed[slot] = Math.max(ringListed[slot], listedThisFrame);
        ringRendered[slot] = Math.max(ringRendered[slot], kThisFrame);
        ringDistinct[slot] = Math.max(ringDistinct[slot], destNsIdsThisFrame.size());
    }

    private static void appendCensus() {
        block.append("  [1] CENSUS  frame: listed=").append(listedThisFrame)
            .append(" rendered=").append(kThisFrame)
            .append(" sameId=").append(sameIdThisFrame)
            .append(" crossId=").append(crossIdThisFrame)
            .append(" distinctDestNsId=").append(destNsIdsThisFrame.size()).append('\n');
        block.append("              60s max: listed=").append(max(ringListed))
            .append(" rendered=").append(max(ringRendered))
            .append(" distinctDestNsId=").append(max(ringDistinct)).append('\n');
        block.append("  READING: shadowRan=NO                                      => H-A  (never ran; read why=)\n");
        block.append("           shadowRan=YES + DEST voxel nz==0 + MAIN voxel nz>0 => H-B  (ran, EMPTY; cull frustum)\n");
        block.append("           DEST voxel>0 + DEST ff nz==0                       => H-C  (voxelised, not flood-filled)\n");
        block.append("           DEST voxel>0 + DEST ff nz>0                        => H-D  (\"never seeded\" REFUTED)\n");
        block.append("           MAIN voxel nz==0                                   => leg [4] INVALID this run\n");
        block.append("           CROSS-ID + pipelineIdentity=SAME-OBJECT            => H-E  (NamespacedId collapse)\n");
    }

    private static int max(int[] a) {
        int m = 0;
        for (int v : a) {
            if (v > m) {
                m = v;
            }
        }
        return m;
    }

    private static void watchdogs() {
        long upNs = System.nanoTime() - CLASS_INIT_NANOS;
        if (upNs < 60_000_000_000L) {
            return;
        }
        if (listedTotal == 0L) {
            warnOnce("wd-noportal", P + "WATCHDOG: armed 60s ago and NO portal was ever listed for"
                + " rendering. Either no portal was on screen, or the COMPAT renderer is not active —"
                + " confirm the shaderpack is ON and renderMode != none.", null);
        }
        else if (renderedTotal == 0L) {
            warnOnce("wd-norender", P + "WATCHDOG: " + listedTotal + " portals listed but ZERO reached"
                + " the nested dest render — every one was occlusion-rejected at"
                + " testShouldRenderPortal. Stand closer / face the window squarely.", null);
        }
        else if (emittedBlocks == 0L) {
            warnOnce("wd-noemit", P + "WATCHDOG: " + renderedTotal + " portal renders seen, zero"
                + " captures emitted — the 1Hz arming path is broken; census counters are still"
                + " valid.", null);
        }
    }

    // =============================================================================================
    // Shadowcomp / image description helpers
    // =============================================================================================

    /**
     * RUN-1 DEFECT FIX (two of them). (1) {@code pass.getClass().getDeclaredField("computes")} threw
     * {@code NoSuchFieldException} on every element: the declared generic type
     * {@code ImmutableList<ShadowCompositeRenderer$Pass>} is ERASED, so it never proved the runtime
     * element class. Walk the hierarchy and, on a miss, log the ACTUAL class and its field names so
     * the next run diagnoses itself instead of guessing again. (2) the old code then fired the
     * "pack ships no shadowcomp compute" FINDING off that unavailable {@code withComputes=0} — a
     * FALSE ALARM on missing data. A count that could not be measured now reads {@code n/a} and
     * asserts nothing.
     */
    private static String describeShadowcomp(String role, Object shadowRenderer) {
        if (shadowRenderer == null) {
            return "n/a (shadowRenderer==null)";
        }
        try {
            Object comp = readObj2(fCompositeRenderer, shadowRenderer);
            if (comp == null) {
                warnOnce("compnull-" + role, P + "FINDING (once-only): " + role
                    + " ShadowCompositeRenderer is null — the pack ships no shadowcomp for this"
                    + " dimension. That volume is unseeded BY PACK CONFIG, not by our nested render.",
                    null);
                return "compositeRenderer==null";
            }
            Object passesObj = fPasses == null ? null : readObj2(fPasses, comp);
            if (!(passesObj instanceof Iterable<?> passes)) {
                return "passes=n/a";
            }
            int n = 0;
            int withComputes = 0;
            int computes = 0;
            int nonNullComputes = 0;
            boolean computesKnown = true;
            for (Object pass : passes) {
                n++;
                Field fc = findFieldInHierarchy(pass.getClass(), "computes");
                if (fc == null) {
                    computesKnown = false;
                    warnOnce("computesfield", P + "pass element class "
                        + pass.getClass().getName() + " has no 'computes' field in its hierarchy"
                        + " (declared fields: " + declaredFieldNames(pass.getClass()) + ") —"
                        + " withComputes reads n/a and asserts NOTHING; the dispatch verdict is"
                        + " UNAVAILABLE (legs [3]/[4] still valid).", null);
                    continue;
                }
                try {
                    Object arr = fc.get(pass);
                    if (arr instanceof Object[] a && a.length > 0) {
                        withComputes++;
                        computes += a.length;
                        // E1 (round-2): `a.length` is iris's fixed CAPACITY (27 ComputeSource slots),
                        // so `withComputes` can NEVER read 0 and the old zero-test was unreachable.
                        // Only the count of NON-NULL entries says whether a compute was actually built.
                        for (Object c : a) {
                            if (c != null) {
                                nonNullComputes++;
                            }
                        }
                    }
                }
                catch (Throwable t) {
                    computesKnown = false;
                    warnOnce("computesread", P + "pass 'computes' field present but unreadable —"
                        + " withComputes reads n/a and asserts NOTHING.", t);
                }
            }
            // Only a MEASURED zero is evidence. An unmeasurable one is not. E1: the meaningful zero is
            // nonNullComputes, NOT withComputes (which counts iris's fixed 27-slot capacity).
            if (computesKnown && n > 0 && nonNullComputes == 0) {
                warnOnce("compnone-" + role, P + "FINDING (once-only): the " + role
                    + " ShadowCompositeRenderer has passes=" + n + " but MEASURED nonNullComputes=0 —"
                    + " no shadowcomp compute program was built for this dimension. That volume is"
                    + " unseeded UPSTREAM of dispatch (pipeline/ProgramSet construction), not by our"
                    + " nested render.", null);
            }
            return "passes=" + n
                + " withComputes=" + (computesKnown ? String.valueOf(withComputes) : "n/a")
                + " computes=" + (computesKnown ? String.valueOf(computes) : "n/a")
                + " nonNullComputes=" + (computesKnown ? String.valueOf(nonNullComputes) : "n/a");
        }
        catch (Throwable t) {
            return "read-failed(" + t + ")";
        }
    }

    private static Field findFieldInHierarchy(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            }
            catch (Throwable ignored) {
                // keep walking up
            }
        }
        return null;
    }

    private static String declaredFieldNames(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : c.getDeclaredFields()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(f.getName());
            }
        }
        catch (Throwable ignored) {
            sb.append("?");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> customImages(Object pipeline) {
        List<Object> out = new ArrayList<>();
        try {
            if (pipeline == null || fCustomImages == null || !irisPipelineClass.isInstance(pipeline)) {
                return out;
            }
            Object set = fCustomImages.get(pipeline);
            if (set instanceof Iterable<?> it) {
                for (Object o : it) {
                    out.add(o);
                }
            }
        }
        catch (Throwable ignored) {
            // tolerated — the caller renders "n/a"
        }
        return out;
    }

    private static int imageId(List<Object> imgs, String name) {
        try {
            for (Object img : imgs) {
                if (name.equals(mImgGetName.invoke(img))) {
                    return (Integer) mImgGetId.invoke(img);
                }
            }
        }
        catch (Throwable ignored) {
            // fall through
        }
        return -1;
    }

    private static String imageNames(List<Object> imgs) {
        StringBuilder sb = new StringBuilder("[");
        try {
            for (Object img : imgs) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(mImgGetName.invoke(img));
            }
        }
        catch (Throwable ignored) {
            // partial list is still evidence
        }
        return sb.append(']').toString();
    }

    private static String describeImages(List<Object> imgs) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Object img : imgs) {
                String name = String.valueOf(mImgGetName.invoke(img));
                if (!name.startsWith("voxel_img") && !name.startsWith("floodfill_img")) {
                    continue;
                }
                int id = (Integer) mImgGetId.invoke(img);
                int[] dim = texDims(id);
                sb.append(name).append('=').append(id);
                if (dim != null) {
                    sb.append(' ').append(dim[0]).append('x').append(dim[1]).append('x').append(dim[2]);
                }
                sb.append(" fmt=").append(mImgGetInternalFormat.invoke(img))
                    .append(" clear=").append(mImgShouldClear.invoke(img))
                    .append(" target=").append(mImgGetTarget.invoke(img)).append(" | ");
            }
        }
        catch (Throwable t) {
            sb.append("read-failed(").append(t).append(')');
        }
        return sb + "(names: " + imageNames(imgs) + ")";
    }

    private static String describeManager(Object pm) {
        try {
            if (fPipelinesPerDimension == null) {
                return "n/a";
            }
            Object m = fPipelinesPerDimension.get(pm);
            if (!(m instanceof Map<?, ?> map)) {
                return "n/a";
            }
            StringBuilder sb = new StringBuilder("pipelinesPerDimension.size=" + map.size() + " [");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(e.getKey()).append(" -> ").append(identityString(e.getValue()));
            }
            return sb.append(']').toString();
        }
        catch (Throwable t) {
            return "read-failed(" + t + ")";
        }
    }

    // =============================================================================================
    // Reflection resolution
    // =============================================================================================

    private static synchronized boolean ensureReflection() {
        if (reflectionReady) {
            return true;
        }
        if (reflectionAttempted) {
            return false;
        }
        reflectionAttempted = true;
        try {
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            mGetPipelineManager = irisClass.getMethod("getPipelineManager");
            mGetCurrentDimension = irisClass.getMethod("getCurrentDimension");
            mIsPackInUseQuick = irisClass.getMethod("isPackInUseQuick");

            Class<?> pmClass = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
            mGetPipelineNullable = pmClass.getMethod("getPipelineNullable");
            fPipelinesPerDimension = optField(pmClass, "pipelinesPerDimension");

            irisPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            fShadowRenderer = irisPipelineClass.getDeclaredField("shadowRenderer");
            fShadowRenderer.setAccessible(true);
            fCustomImages = optField(irisPipelineClass, "customImages");

            Class<?> srClass = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
            fModelView = srClass.getField("MODELVIEW");
            fProjection = srClass.getField("PROJECTION");
            fFrustum = srClass.getField("FRUSTUM");
            fResolution = srClass.getField("RESOLUTION");
            fRenderedShadowEntities = optField(srClass, "renderedShadowEntities");
            fRenderedShadowBEs = optField(srClass, "renderedShadowBlockEntities");
            fDebugStringTerrain = optField(srClass, "debugStringTerrain");
            fTerrainFrustumHolder = optField(srClass, "terrainFrustumHolder");
            fEntityFrustumHolder = optField(srClass, "entityFrustumHolder");
            fPackHasVoxelization = optField(srClass, "packHasVoxelization");
            fPackCullingState = optField(srClass, "packCullingState");
            fCompositeRenderer = optField(srClass, "compositeRenderer");

            Class<?> scrClass = Class.forName("net.irisshaders.iris.shadows.ShadowCompositeRenderer");
            fPasses = optField(scrClass, "passes");

            Class<?> fhClass = Class.forName("net.irisshaders.iris.shadows.frustum.FrustumHolder");
            mHolderGetFrustum = fhClass.getMethod("getFrustum");
            mHolderGetDistanceInfo = fhClass.getMethod("getDistanceInfo");
            mHolderGetCullingInfo = fhClass.getMethod("getCullingInfo");

            Class<?> imgClass = Class.forName("net.irisshaders.iris.gl.image.GlImage");
            mImgGetName = imgClass.getMethod("getName");
            mImgGetId = imgClass.getMethod("getId");
            mImgGetTarget = imgClass.getMethod("getTarget");
            mImgShouldClear = imgClass.getMethod("shouldClear");
            mImgGetInternalFormat = imgClass.getMethod("getInternalFormat");

            Class<?> ivsClass = Class.forName("net.irisshaders.iris.gui.option.IrisVideoSettings");
            fShadowDistance = ivsClass.getField("shadowDistance");
            mGetOverriddenShadowDistance =
                ivsClass.getMethod("getOverriddenShadowDistance", int.class);

            reflectionReady = true;
            return true;
        }
        catch (ClassNotFoundException e) {
            // Expected on any iris-absent runtime (the 8-leg gametest suite runs iris-ABSENT).
            disarmed = true;
            LOGGER.info(P + "iris classes not present on the runtime — probe inert for this session");
            return false;
        }
        catch (Throwable t) {
            disarmed = true;
            warnOnce("resolve", P + "DISARMED: could not resolve the iris pipeline/shadow/image"
                + " symbols on this Iris build. The ACT-seeding diagnosis CANNOT be made from this"
                + " run — do NOT read a silent log as \"no defect\".", t);
            return false;
        }
    }

    /** Tolerated-absent resolver: a renamed private symbol degrades one field to n/a, not the kit. */
    private static Field optField(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
        catch (Throwable t) {
            LOGGER.info(P + "optional symbol {}.{} not present on this Iris build — that field reads"
                + " n/a (the rest of the kit is unaffected)", owner.getSimpleName(), name);
            return null;
        }
    }

    // =============================================================================================
    // Small helpers
    // =============================================================================================

    private static String identityString(Object o) {
        return o == null ? "null"
            : ("@" + o.getClass().getSimpleName() + "#" + Integer.toHexString(System.identityHashCode(o)));
    }

    private static String safeNsId() {
        try {
            Object id = mGetCurrentDimension.invoke(null);
            return String.valueOf(id);
        }
        catch (Throwable t) {
            return "?";
        }
    }

    private static String readObj(Field f, Object owner) {
        try {
            return f == null ? "n/a" : String.valueOf(f.get(owner));
        }
        catch (Throwable t) {
            return "n/a";
        }
    }

    private static Object readObj2(Field f, Object owner) {
        try {
            return f == null || owner == null ? null : f.get(owner);
        }
        catch (Throwable t) {
            return null;
        }
    }

    private static String readBool(Field f, Object owner) {
        try {
            return f == null || owner == null ? "n/a" : String.valueOf(f.getBoolean(owner));
        }
        catch (Throwable t) {
            return "n/a";
        }
    }

    private static String readInt(Field f, Object owner) {
        try {
            return f == null ? "n/a" : String.valueOf(f.getInt(owner));
        }
        catch (Throwable t) {
            return "n/a";
        }
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }

    private static void warnOnce(String key, String msg, Throwable t) {
        if (!warnedOnce.add(key)) {
            return;
        }
        try {
            if (t != null) {
                LOGGER.warn(msg, t);
            }
            else {
                LOGGER.warn(msg);
            }
        }
        catch (Throwable ignored) {
            // never let the probe's own logging escape
        }
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        inFrame = false;
        inPortal = false;
        armed = false;
        block = null;
        try {
            LOGGER.warn(P + "disarmed after a throw (diagnostic only, render unaffected)", t);
        }
        catch (Throwable ignored) {
            // never let the probe's own failure escape
        }
    }
}
