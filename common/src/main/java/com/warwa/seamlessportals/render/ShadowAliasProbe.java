package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;

import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * IS5-W — THE SHADOW-ALIAS PROBE (the fullbright-wave theory-restart's decisive instrument;
 * workflow {@code fullbright-theory-restart} wf_2d369c84-97c fold §3; memory
 * {@code portal-shaders-lighting-wave} ★★★★). <b>DIAGNOSTIC ONLY — this is a PROBE, not a fix.</b>
 *
 * <p><b>The adjudicated, 2/3-verifier-CONFIRMED mechanism it tests.</b> Iris isolates shadow-vs-
 * regular per-region draw state by PHYSICALLY SWAPPING {@code RenderRegion.renderList} +
 * {@code cachedBatches} at shadow-scope entry/exit ({@code iris$begin/endShadowRenderListScope}).
 * The mod's C2-1 per-portal-layer isolation ({@code MixinSodiumRenderRegion.getRenderList}
 * @Overwrite + {@code ip_getCachedBatch} HEAD-cancel) keys ONLY on {@code (isRendering, layer)} —
 * never the swapped fields, never {@code ShadowRenderingState} — so at portal layer >= 1 the dest
 * SHADOW pass and the dest CAMERA pass resolve to the SAME layer ChunkRenderList and the SAME layer
 * MultiDrawBatch: iris's scope isolation is structurally collapsed inside the portal layer (the
 * exact bug class ca6e93b fixed between portal-and-main, recreated one level down). Which plumbing
 * LEG carries the yaw-keyed shadow-erasing wave is the ONE unproven link: (a) cross-scope stale
 * batch reuse via the {@code isFilled} skip, (b) stale stored {@code shadowRenderLists} snapshot
 * aliasing live layer lists, (c) same-frame reset collision, (d) never-cleared stale batches (iris
 * @Redirects {@code clearAllCachedBatches} at uploadResults to {@code iris$forceClearAllBatches},
 * so the mod's HOOK 2 mirror NEVER fires under iris). This probe names the leg — or kills the map
 * leg entirely (the occupancy grid), pivoting the hunt to composite sampling.
 *
 * <p><b>Instruments (fold §3, legs 1/2/3/5; leg 4 deferred to a follow-up if leg-naming stays
 * ambiguous):</b>
 * <ol>
 *   <li><b>Batch provenance.</b> Fed per-access from {@code ip_getCachedBatch}: each layer batch is
 *       tagged (WeakHashMap) with {fillPassSerial, fillScope} when observed unfilled-on-entry (the
 *       consumer fills it next); a filled-on-entry access classifies as reuseSame / reuseCross /
 *       reuseStale(>1 pass) / reuseUnknown against the tag. Split per scope (shadow vs camera, via
 *       {@code IrisInterface.invoker.isRenderingShadowMap()} == ShadowRenderer.ACTIVE). A
 *       SHADOW-scope reuse of a CAMERA-scope fill (or v.v.) is the malignant cross-scope signal.</li>
 *   <li><b>In-scope roster.</b> Distinct regions + summed {@code getSectionsWithGeometryCount()}
 *       (reflected at emit) per scope, from OUR OWN hooks (never iris's post-restore debug string —
 *       that C-string was PROVEN a mixture: numerator=shadow list via iris's redirect, tag=main
 *       tree; banned as evidence by the theory-restart fold).</li>
 *   <li><b>Identity collision + RSM snapshot state.</b> Per sampled region: the layer list object
 *       returned under SHADOW scope vs CAMERA scope (identityHashCode — the collapse predicts SAME)
 *       + each side's {@code getLastVisibleFrame()} (same-frame collision = leg (c) evidence). At
 *       emit, the woven RSM fields {@code renderLists}/{@code regularRenderLists}/
 *       {@code shadowRenderLists} identities + {@code shadowNeedsRenderListUpdate}/
 *       {@code renderListStateIsShadow}/{@code shadowScopeActive} (iris
 *       MixinRenderSectionManagerShadow — javap-confirmed names, resolved exact-then-substring).</li>
 *   <li><b>Whole-map occupancy grid vs yaw.</b> A 12x12 grid of 4x4-texel glReadPixels tiles across
 *       the whole shadow depth map (replaces the center-only [4] read), ONE GL_PACK_* bracket around
 *       the whole grid (26.2 invariant #4), plus the MAIN player's yaw/pitch on the same line —
 *       fixing the yaw<->log alignment caveat. Decision rule: occupancy yaw-INVARIANT during washed
 *       holds => the map leg is DEAD (pivot to composite sampling); yaw-covariant + a leg indicator
 *       above => mechanism confirmed, leg named.</li>
 * </ol>
 *
 * <p><b>Binding discipline</b> (clone of {@link ShadowEmptinessProbe}). LOG-ONLY; lever
 * {@code -Dseamlessportals.shadowAliasProbe} (default-OFF, runtime-read static). NO sodium/iris
 * types in any signature — hooks pass Objects/primitives; sodium getters + iris pipeline internals
 * are reached reflection-only (javap-confirmed: {@code ChunkRenderList.getSectionsWithGeometryCount()
 * /getLastVisibleFrame()}, {@code SodiumWorldRenderer.instanceNullable()},
 * {@code RenderSectionManager} woven fields, {@code IrisRenderingPipeline.shadowRenderTargets} →
 * {@code getDepthSourceFb().getId()}/{@code getResolution()}). Self-disarms on any throw; 1Hz
 * armed-pass latch (hooks early-out when not capturing — near-zero overhead between samples);
 * render-thread only. {@code ENABLED} is public so the per-draw mixin sites can guard the call.
 */
public final class ShadowAliasProbe {

    private ShadowAliasProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String P = "[SHADOW-ALIAS-PROBE] ";

    /** The lever. Byte-inert unless {@code -Dseamlessportals.shadowAliasProbe=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.shadowAliasProbe");

    /** 1Hz rate limit between armed passes (ns). */
    private static final long RATE_LIMIT_NS = 1_000_000_000L;

    /** Occupancy grid geometry: GRID x GRID tiles, each TILE x TILE texels. */
    private static final int GRID = 12;
    private static final int TILE = 4;

    /** Max distinct lists tracked per scope per pass (bounded memory; plenty for one portal). */
    private static final int MAX_TRACKED_LISTS = 128;

    /** Max identity-collision sample rows logged. */
    private static final int MAX_ID_ROWS = 4;

    // ===== render-thread-only state =============================================================
    private static boolean disarmed = false;
    private static boolean capturing = false;
    private static long lastEmitNanos = 0L;
    private static long passSerial = 0L;
    private static double destCamX, destCamY, destCamZ;

    /** Batch tag: {fillPassSerial, fillScope(0=camera,1=shadow)} keyed weakly by the batch object. */
    private static final Map<Object, long[]> BATCH_TAGS = new WeakHashMap<>();

    /** Per-scope batch-access counters: [accesses, freshFill, reuseSame, reuseCross, reuseStalePrev, reuseUnknown]. */
    private static final long[] shadowBatchStats = new long[6];
    private static final long[] cameraBatchStats = new long[6];
    private static long shadowListAccesses = 0, cameraListAccesses = 0;

    /** Distinct layer lists seen per scope this pass: list -> region (identity semantics). */
    private static final IdentityHashMap<Object, Object> shadowLists = new IdentityHashMap<>();
    private static final IdentityHashMap<Object, Object> cameraLists = new IdentityHashMap<>();
    /** region -> list, per scope, for the identity join (identity semantics on regions). */
    private static final IdentityHashMap<Object, Object> shadowRegionToList = new IdentityHashMap<>();
    private static final IdentityHashMap<Object, Object> cameraRegionToList = new IdentityHashMap<>();

    // ===== reflection surface (resolved once; javap-confirmed) ==================================
    private static boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;
    // sodium (soft — each leg tolerates absence)
    private static java.lang.reflect.Method mSwrInstanceNullable;    // SodiumWorldRenderer.instanceNullable()
    private static java.lang.reflect.Field fSwrRsm;                  // SodiumWorldRenderer.renderSectionManager
    private static java.lang.reflect.Method mListGeomCount;          // ChunkRenderList.getSectionsWithGeometryCount()
    private static java.lang.reflect.Method mListLastVisibleFrame;   // ChunkRenderList.getLastVisibleFrame()
    private static java.lang.reflect.Field fRsmRenderLists;          // RenderSectionManager.renderLists (sodium's own)
    private static java.lang.reflect.Field fRsmRegularRenderLists;   // woven: regularRenderLists (iris save slot)
    private static java.lang.reflect.Field fRsmShadowRenderLists;    // woven: shadowRenderLists (iris shadow snapshot)
    private static java.lang.reflect.Field fRsmShadowNeedsUpdate;    // woven: shadowNeedsRenderListUpdate
    private static java.lang.reflect.Field fRsmStateIsShadow;        // woven: renderListStateIsShadow
    private static java.lang.reflect.Field fRsmShadowScopeActive;    // woven: shadowScopeActive
    // iris (for the occupancy grid; mirrors ShadowEmptinessProbe's resolution path)
    private static java.lang.reflect.Method mGetPipelineManager;     // Iris.getPipelineManager()
    private static java.lang.reflect.Method mGetPipelineNullable;    // PipelineManager.getPipelineNullable()
    private static Class<?> irisPipelineClass;                       // IrisRenderingPipeline
    private static java.lang.reflect.Field fShadowRenderTargets;     // IrisRenderingPipeline.shadowRenderTargets
    private static java.lang.reflect.Method mGetDepthSourceFb;       // ShadowRenderTargets.getDepthSourceFb()
    private static java.lang.reflect.Method mGetResolution;          // ShadowRenderTargets.getResolution()
    private static java.lang.reflect.Method mFbGetId;                // GlFramebuffer.getId()

    // =============================================================================================
    // HOOK ENTRIES (called from MixinSodiumRenderRegion — Objects/primitives only)
    // =============================================================================================

    /**
     * Layer-list access ({@code getRenderList} @Overwrite portal branch). Fires during BOTH the dest
     * shadow scope (collect via FallbackVisibleChunkCollector.visit + draw) and the dest camera
     * scope — the scope split is the whole point.
     */
    public static void onLayerListAccess(Object region, Object list, int layerIndex) {
        if (!capturing || disarmed) {
            return;
        }
        try {
            boolean shadow = IrisInterface.invoker.isRenderingShadowMap();
            if (shadow) {
                shadowListAccesses++;
                if (shadowLists.size() < MAX_TRACKED_LISTS) {
                    shadowLists.put(list, region);
                    shadowRegionToList.put(region, list);
                }
            }
            else {
                cameraListAccesses++;
                if (cameraLists.size() < MAX_TRACKED_LISTS) {
                    cameraLists.put(list, region);
                    cameraRegionToList.put(region, list);
                }
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * Layer-batch access ({@code ip_getCachedBatch} HEAD-cancel). {@code filledOnEntry} is the
     * batch's {@code isFilled} at hand-out: false => the consumer (DefaultChunkRenderer phase-1)
     * fills it next, so we TAG it {thisPass, thisScope}; true => a REUSE, classified against the tag.
     */
    public static void onLayerBatchAccess(Object batch, boolean created, boolean filledOnEntry, int layerIndex) {
        if (!capturing || disarmed) {
            return;
        }
        try {
            boolean shadow = IrisInterface.invoker.isRenderingShadowMap();
            long[] stats = shadow ? shadowBatchStats : cameraBatchStats;
            stats[0]++;
            if (!filledOnEntry) {
                stats[1]++; // freshFill (prospective — the consumer fills + sets isFilled next)
                BATCH_TAGS.put(batch, new long[]{passSerial, shadow ? 1L : 0L});
                return;
            }
            long[] tag = BATCH_TAGS.get(batch);
            if (tag == null) {
                stats[5]++; // reuseUnknown (filled before this armed window)
                return;
            }
            boolean sameScope = (tag[1] == 1L) == shadow;
            if (tag[0] < passSerial - 1) {
                stats[4]++; // reuseStalePrev (>1 pass old — the never-cleared leg (d) signature)
            }
            else if (sameScope) {
                stats[2]++; // reuseSame
            }
            else {
                stats[3]++; // reuseCross (THE malignant cross-scope signal, legs (a)/(d))
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // =============================================================================================
    // PASS BRACKETS (called from renderDestWorldFullPipeline, next to ShadowEmptinessProbe's)
    // =============================================================================================

    /** ARM a capture for this dest pass at most once per second. */
    public static void beginPass(Vec3 destCameraPos) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            long now = System.nanoTime();
            passSerial++; // count every pass (the stale-age classifier needs the true serial)
            if (now - lastEmitNanos < RATE_LIMIT_NS) {
                return;
            }
            capturing = true;
            resetPassState();
            if (destCameraPos != null) {
                destCamX = destCameraPos.x;
                destCamY = destCameraPos.y;
                destCamZ = destCameraPos.z;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** CAPTURE + EMIT one block for an armed pass (post-nested-render finally). */
    public static void endPass() {
        if (!ENABLED || disarmed || !capturing) {
            return;
        }
        capturing = false;
        lastEmitNanos = System.nanoTime();
        try {
            if (!ensureReflection()) {
                return; // iris absent (quiet) or structural miss (loud) — already handled
            }

            // --- main-player view direction (the wave's correlate; caveat-f fix) ----------------
            String viewStr;
            var player = Minecraft.getInstance().player;
            if (player != null) {
                viewStr = "yaw=" + player.getYRot() + " pitch=" + player.getXRot();
            }
            else {
                viewStr = "yaw=n/a";
            }

            // --- instrument 2: rosters (reflected reads at emit = post-pass-final values) -------
            String shadowRoster = rosterString(shadowLists);
            String cameraRoster = rosterString(cameraLists);

            // --- instrument 3a: per-region identity join ----------------------------------------
            StringBuilder idRows = new StringBuilder();
            int rows = 0;
            for (Map.Entry<Object, Object> e : shadowRegionToList.entrySet()) {
                if (rows >= MAX_ID_ROWS) {
                    break;
                }
                Object region = e.getKey();
                Object sList = e.getValue();
                Object cList = cameraRegionToList.get(region);
                if (cList == null) {
                    continue; // region not seen in camera scope this pass
                }
                rows++;
                idRows.append("\n      region@").append(idHex(region))
                    .append(": list(shadow)@").append(idHex(sList))
                    .append(" list(camera)@").append(idHex(cList))
                    .append(sList == cList ? "  SAME-OBJECT (scope collapse LIVE)" : "  different")
                    .append("  lastVisibleFrame s=").append(lastFrameOf(sList))
                    .append(" c=").append(lastFrameOf(cList));
            }
            if (rows == 0) {
                idRows.append("\n      (no region seen in BOTH scopes this pass)");
            }

            // --- instrument 3b: RSM woven snapshot state ----------------------------------------
            String rsmStr = rsmStateString();

            // --- IS5-W FIX-1 + IS5-G confirm counters (read + reset per capture) ----------------
            int clipSuppressed = qouteall.imm_ptl.core.IPGlobal.shadowScopeClipSuppressedCount;
            int clipArmedUp = qouteall.imm_ptl.core.IPGlobal.shadowScopeClipArmedUploadCount;
            int taaCleared = qouteall.imm_ptl.core.IPGlobal.irisDestTaaClearCount;
            int healed = qouteall.imm_ptl.core.IPGlobal.prevUniformHealCount;
            qouteall.imm_ptl.core.IPGlobal.shadowScopeClipSuppressedCount = 0;
            qouteall.imm_ptl.core.IPGlobal.shadowScopeClipArmedUploadCount = 0;
            qouteall.imm_ptl.core.IPGlobal.irisDestTaaClearCount = 0;
            qouteall.imm_ptl.core.IPGlobal.prevUniformHealCount = 0;

            // --- instrument 5: whole-map occupancy grid -----------------------------------------
            String grid = occupancyGrid();

            LOGGER.info(P + "PASS CAPTURE #" + passSerial + " (" + viewStr
                + ") destCam=(" + destCamX + ", " + destCamY + ", " + destCamZ + ")"
                + "\n  [1] batch provenance [accesses, freshFill, reuseSame, reuseCross, reuseStalePrev, reuseUnknown]:"
                + "\n      SHADOW-scope: " + statsString(shadowBatchStats)
                + "\n      CAMERA-scope: " + statsString(cameraBatchStats)
                + "\n      (reuseCross/reuseStalePrev in the SHADOW row = the malignant legs (a)/(d);"
                + " freshFill both rows every pass = honest refills, aliasing benign-direction-only)"
                + "\n  [2] roster (distinct layer lists via OUR hooks — NEVER iris's post-restore C-string):"
                + "\n      SHADOW-scope: listAccesses=" + shadowListAccesses + " " + shadowRoster
                + "\n      CAMERA-scope: listAccesses=" + cameraListAccesses + " " + cameraRoster
                + "\n  [3] identity collision (collapse predicts SAME-OBJECT):" + idRows
                + "\n      RSM: " + rsmStr
                + "\n  [FIX-1] shadow-scope clip uploads since last capture: suppressed=" + clipSuppressed
                + " armedUnsuppressed=" + clipArmedUp
                + " (fix ON => suppressed>0 + armed==0 while a portal is on screen; fix-OFF A/B =>"
                + " armed>0 = the wash carrier firing)"
                + "\n  [IS5-G] dest-pass TAA-history clears since last capture: " + taaCleared
                + " (fix ON + portal on screen => >0, expect 2*savedTargets*portals; 0 => the clear"
                + " is NOT firing — check CLEAR_SUPPORTED / the guard lever / savedList)"
                + "\n  [IS5-PH] prev-uniform heals since last capture: " + healed
                + " (fix ON + portal on screen => ~1/frame; 0 => the heal is NOT firing — see the"
                + " once-only SKIPPED/THREW warn for why)"
                + "\n  [5] shadow-map occupancy (" + GRID + "x" + GRID + " tiles of " + TILE + "x" + TILE
                + " texels; per-tile non-far count 0-" + (TILE * TILE) + " as .:hex):"
                + grid
                + "\n  READING: SHADOW-scope accesses==0 => dest shadow draws are NOT routing through the"
                + " layer hooks (re-examine the seam). Occupancy footprint co-varying with yaw across"
                + " washed-vs-correct holds + (reuseCross|reuseStalePrev|frame-collision|snapshot-identity"
                + " anomaly) => mechanism CONFIRMED, leg named. Occupancy yaw-INVARIANT during washed holds"
                + " => the map leg is DEAD => pivot to dest composite shadow-sampling.");
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // =============================================================================================
    // internals
    // =============================================================================================

    private static void resetPassState() {
        java.util.Arrays.fill(shadowBatchStats, 0L);
        java.util.Arrays.fill(cameraBatchStats, 0L);
        shadowListAccesses = 0;
        cameraListAccesses = 0;
        shadowLists.clear();
        cameraLists.clear();
        shadowRegionToList.clear();
        cameraRegionToList.clear();
    }

    private static String statsString(long[] s) {
        return "access=" + s[0] + " freshFill=" + s[1] + " reuseSame=" + s[2]
            + " reuseCross=" + s[3] + " reuseStalePrev=" + s[4] + " reuseUnknown=" + s[5];
    }

    /** Distinct regions + summed geometry-section count over the tracked lists (reflected). */
    private static String rosterString(IdentityHashMap<Object, Object> lists) {
        if (lists.isEmpty()) {
            return "regions=0 sumGeomSections=0";
        }
        IdentityHashMap<Object, Boolean> regions = new IdentityHashMap<>();
        long geomSum = 0;
        boolean geomOk = mListGeomCount != null;
        for (Map.Entry<Object, Object> e : lists.entrySet()) {
            regions.put(e.getValue(), Boolean.TRUE);
            if (geomOk) {
                try {
                    geomSum += (Integer) mListGeomCount.invoke(e.getKey());
                }
                catch (Throwable t) {
                    geomOk = false;
                }
            }
        }
        return "regions=" + regions.size() + " distinctLists=" + lists.size()
            + " sumGeomSections=" + (geomOk ? geomSum : "n/a")
            + (lists.size() >= MAX_TRACKED_LISTS ? " (capped)" : "");
    }

    private static String lastFrameOf(Object list) {
        if (mListLastVisibleFrame == null || list == null) {
            return "n/a";
        }
        try {
            return String.valueOf(mListLastVisibleFrame.invoke(list));
        }
        catch (Throwable t) {
            return "err";
        }
    }

    /** Woven-RSM snapshot/flag state (soft — any miss reports n/a, never disarms). */
    private static String rsmStateString() {
        try {
            if (mSwrInstanceNullable == null || fSwrRsm == null) {
                return "(sodium SWR handles unresolved — RSM leg skipped)";
            }
            Object swr = mSwrInstanceNullable.invoke(null);
            if (swr == null) {
                return "(SodiumWorldRenderer.instanceNullable()==null)";
            }
            Object rsm = fSwrRsm.get(swr);
            if (rsm == null) {
                return "(renderSectionManager==null)";
            }
            return "renderLists@" + idHexField(rsm, fRsmRenderLists)
                + " regularRenderLists@" + idHexField(rsm, fRsmRegularRenderLists)
                + " shadowRenderLists@" + idHexField(rsm, fRsmShadowRenderLists)
                + " shadowNeedsRenderListUpdate=" + boolField(rsm, fRsmShadowNeedsUpdate)
                + " renderListStateIsShadow=" + boolField(rsm, fRsmStateIsShadow)
                + " shadowScopeActive=" + boolField(rsm, fRsmShadowScopeActive);
        }
        catch (Throwable t) {
            return "(RSM read failed: " + t + ")";
        }
    }

    private static String idHexField(Object owner, java.lang.reflect.Field f) {
        if (f == null) {
            return "n/a";
        }
        try {
            return idHex(f.get(owner));
        }
        catch (Throwable t) {
            return "err";
        }
    }

    private static String boolField(Object owner, java.lang.reflect.Field f) {
        if (f == null) {
            return "n/a";
        }
        try {
            return String.valueOf(f.getBoolean(owner));
        }
        catch (Throwable t) {
            return "err";
        }
    }

    private static String idHex(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    /**
     * Instrument 5 — the whole-map occupancy grid. GRIDxGRID tiles of TILExTILE texels, evenly
     * spread over the shadow depth map; each tile logs the count of non-far texels (depth < ~1.0)
     * as one character ('.'=0, 1-9, a-g). ONE GL_PACK_* bracket around the whole grid (26.2
     * invariant #4 — the P-OQ4 stale-ROW_LENGTH landmine); read framebuffer saved/restored once.
     * Soft-fails to a diagnostic string.
     */
    private static String occupancyGrid() {
        int prevRead = -1;
        boolean bound = false;
        int prevPackRowLength = -1, prevPackSkipRows = -1, prevPackSkipPixels = -1, prevPackAlignment = -1;
        boolean packSaved = false;
        try {
            Object pipelineManager = mGetPipelineManager.invoke(null);
            if (pipelineManager == null) {
                return "\n      (no PipelineManager — grid skipped)";
            }
            Object pipeline = mGetPipelineNullable.invoke(pipelineManager);
            if (pipeline == null || !irisPipelineClass.isInstance(pipeline)) {
                return "\n      (no IrisRenderingPipeline — grid skipped)";
            }
            Object shadowTargets = fShadowRenderTargets.get(pipeline);
            if (shadowTargets == null) {
                return "\n      (shadowRenderTargets==null — grid skipped)";
            }
            Object fb = mGetDepthSourceFb.invoke(shadowTargets);
            if (fb == null) {
                return "\n      (getDepthSourceFb()==null — grid skipped)";
            }
            int fbId = (Integer) mFbGetId.invoke(fb);
            int resolution = (Integer) mGetResolution.invoke(shadowTargets);
            if (fbId <= 0 || resolution <= 0) {
                return "\n      (bad fbId=" + fbId + " res=" + resolution + " — grid skipped)";
            }

            prevRead = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbId);
            bound = true;

            prevPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
            prevPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
            prevPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
            prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            packSaved = true;
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);

            FloatBuffer tile = BufferUtils.createFloatBuffer(TILE * TILE);
            StringBuilder sb = new StringBuilder(GRID * (GRID + 12) + 64);
            int span = resolution / GRID;
            int off = Math.max(0, (span - TILE) / 2);
            int nonFarTotal = 0;
            // row 0 at the bottom of the map (GL origin) — logged top-down for readability
            for (int gy = GRID - 1; gy >= 0; gy--) {
                sb.append("\n      ");
                for (int gx = 0; gx < GRID; gx++) {
                    int x = Math.min(resolution - TILE, gx * span + off);
                    int y = Math.min(resolution - TILE, gy * span + off);
                    tile.clear();
                    GL11.glReadPixels(x, y, TILE, TILE, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, tile);
                    int nonFar = 0;
                    for (int i = 0; i < TILE * TILE; i++) {
                        float d = tile.get(i);
                        if (d < 0.9999f && d > 1.0e-6f) {
                            nonFar++;
                        }
                    }
                    nonFarTotal += nonFar;
                    sb.append(nonFar == 0 ? '.' : Character.forDigit(Math.min(nonFar, 16), 17));
                }
            }
            int glErr = GL11.glGetError();

            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevPackSkipRows);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevPackRowLength);
            packSaved = false;
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            bound = false;

            sb.append("\n      fbo=").append(fbId).append(" res=").append(resolution)
                .append(" nonFarTexels=").append(nonFarTotal).append('/').append(GRID * GRID * TILE * TILE)
                .append(" glGetError=").append(glErr)
                .append(prevPackRowLength != 0 ? " (GL_PACK_ROW_LENGTH was STALE=" + prevPackRowLength + ")" : "");
            return sb.toString();
        }
        catch (Throwable t) {
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
            return "\n      (occupancy grid FAILED: " + t + " — instruments [1]-[3] carry the pass)";
        }
    }

    /**
     * Resolve every handle once. Iris-absent => quiet disarm. Sodium legs resolve soft (n/a instead
     * of disarm). Woven-RSM fields resolve exact-name first, then a declared-field substring scan
     * (mixin conflict-renaming tolerance).
     */
    private static synchronized boolean ensureReflection() {
        if (reflectionReady) {
            return true;
        }
        if (reflectionAttempted) {
            return false;
        }
        reflectionAttempted = true;
        try {
            // iris (required for the grid; its absence makes the whole probe moot)
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            mGetPipelineManager = irisClass.getMethod("getPipelineManager");
            Class<?> pmClass = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
            mGetPipelineNullable = pmClass.getMethod("getPipelineNullable");
            irisPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            fShadowRenderTargets = irisPipelineClass.getDeclaredField("shadowRenderTargets");
            fShadowRenderTargets.setAccessible(true);
            Class<?> srtClass = Class.forName("net.irisshaders.iris.shadows.ShadowRenderTargets");
            mGetDepthSourceFb = srtClass.getMethod("getDepthSourceFb");
            mGetResolution = srtClass.getMethod("getResolution");
            Class<?> fbClass = Class.forName("net.irisshaders.iris.gl.framebuffer.GlFramebuffer");
            mFbGetId = fbClass.getMethod("getId");

            // sodium (soft legs)
            try {
                Class<?> swrClass =
                    Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
                mSwrInstanceNullable = swrClass.getMethod("instanceNullable");
                fSwrRsm = swrClass.getDeclaredField("renderSectionManager");
                fSwrRsm.setAccessible(true);
                Class<?> listClass =
                    Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList");
                mListGeomCount = listClass.getMethod("getSectionsWithGeometryCount");
                mListLastVisibleFrame = listClass.getMethod("getLastVisibleFrame");
                Class<?> rsmClass =
                    Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager");
                fRsmRenderLists = resolveField(rsmClass, "renderLists");
                fRsmRegularRenderLists = resolveField(rsmClass, "regularRenderLists");
                fRsmShadowRenderLists = resolveField(rsmClass, "shadowRenderLists");
                fRsmShadowNeedsUpdate = resolveField(rsmClass, "shadowNeedsRenderListUpdate");
                fRsmStateIsShadow = resolveField(rsmClass, "renderListStateIsShadow");
                fRsmShadowScopeActive = resolveField(rsmClass, "shadowScopeActive");
            }
            catch (Throwable softT) {
                LOGGER.info(P + "sodium reflection legs unresolved (soft — RSM/roster reads will show"
                    + " n/a; the batch/list counters + grid still run): " + softT);
            }

            reflectionReady = true;
            return true;
        }
        catch (ClassNotFoundException e) {
            disarmed = true; // iris not on the runtime — inert, quiet
            LOGGER.info(P + "iris classes not present — probe inert for this session");
            return false;
        }
        catch (Throwable t) {
            disarm(t);
            return false;
        }
    }

    /**
     * Exact-name resolve, then a substring scan over declared fields (iris's woven fields keep
     * their mixin names unless a weave conflict renames them — the scan tolerates decoration).
     */
    private static java.lang.reflect.Field resolveField(Class<?> owner, String name) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
        catch (NoSuchFieldException e) {
            for (java.lang.reflect.Field f : owner.getDeclaredFields()) {
                if (f.getName().contains(name)) {
                    f.setAccessible(true);
                    return f;
                }
            }
            LOGGER.info(P + "field '" + name + "' not found on " + owner.getSimpleName()
                + " (exact+substring) — that leg reads n/a");
            return null;
        }
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
