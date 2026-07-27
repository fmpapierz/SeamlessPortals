package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * IS5-CEN — THE PER-FRAME COMPOSITE BIND CENSUS. Diagnostic, DEFAULT OFF
 * ({@code -Dseamlessportals.compositeCensus}).
 *
 * <h2>The question this exists to answer</h2>
 * With the pack's Motion Blur ON a SAME-DIM portal window is uniformly blurred, constantly, whether the
 * player moves or stands still; cross-dim windows are clean; MB off is clean. The blur SCALES with
 * {@code MOTION_BLURRING_STRENGTH}, so the shader's {@code velocity} is nonzero. Yet every
 * {@code composite4} bind ever sampled read {@code |cameraPosition - previousCameraPosition| = 0.000}
 * and {@code maxAbsDiff(gbufferModelView, gbufferPreviousModelView) = 0.00000}.
 *
 * <h2>Why the old probes could be simultaneously right and useless</h2>
 * They sampled <b>at most one bind per second per role</b>, and they measured only <b>two</b> of the
 * <b>six</b> matrices the velocity actually depends on. {@code program/composite4.glsl:99-127} is:
 * <pre>
 *   currentPosition = vec4(texCoord, z, 1) * 2 - 1          // NDC
 *   viewPos  = gbufferProjectionInverse  * currentPosition  // (1) P^-1
 *   viewPos  = gbufferModelViewInverse   * viewPos          // (2) MV^-1
 *   viewPos /= viewPos.w
 *   prev     = viewPos + vec4(cameraPosition - previousCameraPosition, 0)
 *   prev     = gbufferPreviousModelView  * prev             // (3) MVprev
 *   prev     = gbufferPreviousProjection * prev             // (4) Pprev
 *   prev    /= prev.w
 *   velocity = (currentPosition - prev).xy
 * </pre>
 * The "it telescopes to exactly zero for any depth" argument needs all four matrices to agree — it needs
 * {@code gbufferModelViewInverse} to be the inverse of the matrix {@code gbufferPreviousModelView}
 * carries, and likewise for the projection pair. <b>Nobody has ever measured the inverse pair.</b> If
 * {@code MV^-1} belongs to a different camera than {@code MVprev}, the chain does not cancel, and the
 * residual is a per-PIXEL, depth-dependent velocity — constant, requiring no player motion, scaling with
 * strength — while {@code |cam-prev|} and {@code maxAbsDiff(MV, MVprev)} both still read exactly zero.
 * That is every observed fact at once, and it is invisible to every probe built so far.
 *
 * <h2>What this measures, per bind</h2>
 * All six matrices and both camera vectors are read from the bound program's own storage, and then the
 * shader's arithmetic is <b>replayed on the CPU</b> over a grid of screen positions and depths. The
 * headline column is {@code maxSpanPx} — the blur span in pixels this bind would produce at
 * {@code MOTION_BLURRING_STRENGTH = 1}. It collapses all eight inputs into the one number that decides
 * guilt, and it cannot be fooled by any of the individual inputs reading zero.
 *
 * <p>The grid is deliberately <b>off-centre as well as centred</b>: a pure rotational mismatch produces
 * zero velocity at the screen centre and grows towards the edges, so a centre-only sample would report
 * "clean" for exactly the defect shape most likely to be present.
 *
 * <h2>Why the hook is {@code Program.use()} and not the composite loop</h2>
 * The shipped IS5-MB seam identifies a pass by {@code passes.get(i)} with {@code i} recovered as a
 * {@code @Local} loop counter. If that resolution is ever wrong, the pass name is wrong and a
 * {@code composite4} bind is silently classified as something else — a blind spot of exactly the shape
 * that would explain an unsampled invocation. This census instead witnesses <b>every</b>
 * {@code Program.use()} in the process and keys on the <b>program id</b>, which is the same integer the
 * driver binds. The name comes from a roster harvested at {@code CompositeRenderer.renderAll} HEAD.
 *
 * <p>Only rostered programs are deep-measured, so coverage is itself a claim — and claims get measured
 * here, not asserted. {@link #auditCoverageOnce} probes <b>every</b> program id the first time it is
 * bound and emits a WARN for any that declares {@code previousCameraPosition} (i.e. can reproject
 * against the previous frame, i.e. could produce this smear) while being in no roster. If the deep rows
 * all read zero and no such WARN appears, the census has affirmatively established that no
 * velocity-capable program escaped it.
 *
 * <h2>Reading the output</h2>
 * <ul>
 *   <li><b>Some bind shows a large {@code maxSpanPx}</b> ⇒ that bind is the smearing pass; its
 *       {@code win=}/{@code layer=} columns say which render phase it belongs to and its {@code idMV}/
 *       {@code idP}/{@code dMV}/{@code dP}/{@code |cam-prev|} columns say which of the eight inputs is
 *       responsible.</li>
 *   <li><b>Every bind in every frame shows {@code maxSpanPx ≈ 0}</b> ⇒ motion blur genuinely cannot be
 *       the source, and the MB toggle's OTHER effect is — the extra {@code colortex0} write
 *       ({@code DRAWBUFFERS:3} → {@code :30}) and the ping-pong parity flip it causes. The
 *       {@code drawBuf=}/{@code readsAlt=} columns are printed on every row precisely so that verdict
 *       arrives with its evidence already in hand.</li>
 * </ul>
 *
 * <p>Log-only and allocation-bounded. Never throws: one failure disarms the census for the session and
 * says so. Byte-inert at the shipped default — the first statement of every entry point is a
 * {@code static final boolean} test.
 */
public final class IrisCompositeCensus {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-CEN ";

    /** Master lever. DEFAULT OFF. */
    public static final boolean ENABLED =
        Boolean.getBoolean("seamlessportals.compositeCensus");

    /** Passes given the FULL uniform treatment, comma-separated. Others are counted only. */
    private static final Set<String> DEEP_PASSES = new LinkedHashSet<>();

    /** Hard cap on rows retained per frame; a breach is reported, never silently truncated. */
    private static final int MAX_ROWS = 64;

    static {
        for (String s : System.getProperty("seamlessportals.compositeCensusPasses", "composite4")
            .split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                DEEP_PASSES.add(t);
            }
        }
    }

    private IrisCompositeCensus() {
    }

    // =============================================================================================
    // Roster — program id -> what iris calls it, harvested at CompositeRenderer.renderAll HEAD
    // =============================================================================================

    private static final class PassInfo {
        String name = "?";
        String rendererId = "?";
        String drawBuffers = "?";
        String readsAlt = "?";
        int viewWidth = 0;
        int viewHeight = 0;
        boolean deep;
    }

    /** Small and bounded: one entry per composite program the session ever binds. */
    private static final java.util.Map<Integer, PassInfo> ROSTER = new java.util.HashMap<>();
    /** rendererId -> nanoTime of its last roster walk; drives the 1 Hz re-harvest. */
    private static final java.util.Map<String, Long> LAST_HARVEST = new java.util.HashMap<>();
    /** Program ids already probed for motion-blur capability (the coverage audit). Bounded by the
     *  number of distinct iris programs in the session, a few dozen. */
    private static final Set<Integer> AUDITED_PIDS = new HashSet<>();

    // =============================================================================================
    // Per-frame accumulation
    // =============================================================================================

    /** One measured bind. Reused across frames — the list is cleared, not reallocated. */
    private static final class Row {
        int seq;
        String name;
        int pid;
        String rendererId;
        String window;
        int layer;
        String drawBuffers;
        String readsAlt;
        int viewWidth;
        int viewHeight;
        final float[] cam = new float[4];
        final float[] prevCam = new float[4];
        double camDelta;
        double dMv;
        double dProj;
        double idMv;
        double idProj;
        boolean haveCurrentMatrices;
        double centreSpanPx;
        // The POST-WRITE sample, taken between the IS5-MB write and the draw.
        boolean postSampled;
        final float[] postCam = new float[4];
        final float[] postPrev = new float[4];
        double postCamDelta;
        double postMaxSpanPx;
        double maxSpanPx;
        double maxU;
        double maxV;
        double maxZ;
        boolean uniformsReadable;
        String note = "";
    }

    // Two lists, SWAPPED at the frame boundary rather than copied. A copy would alias: the "last
    // frame" list would hold the very Row objects the next frame is about to overwrite, so the
    // emitted block would describe a frame that never existed. Swapping gives the emitter sole
    // ownership of the completed frame's rows for as long as it needs them.
    private static List<Row> rows = new ArrayList<>();
    private static List<Row> lastFrameRows = new ArrayList<>();
    private static int frameDeepBinds = 0;
    private static int frameCompositeBinds = 0;
    private static int frameAllProgramBinds = 0;
    private static int frameTruncated = 0;
    private static int lastFrameDeepBinds = 0;
    private static int lastFrameCompositeBinds = 0;
    private static int lastFrameAllProgramBinds = 0;
    private static int lastFrameTruncated = 0;
    private static int frameCounter = 0;

    /** Per-second aggregation — the "never generalize from one sampled block" guard. */
    private static long lastEmitNanos = 0L;
    private static int secondFrames = 0;
    private static int secondMinDeep = Integer.MAX_VALUE;
    private static int secondMaxDeep = 0;
    private static double secondMaxSpanPx = 0.0;
    private static String secondMaxSpanWhere = "n/a";
    private static int secondTruncated = 0;
    private static double secondMaxPostSpanPx = 0.0;
    /** The row awaiting its post-write sample at the draw, and the program it belongs to. */
    private static Row pendingRow = null;
    private static int pendingRowPid = -1;
    private static final Set<String> SECOND_PIDS = new LinkedHashSet<>();

    private static boolean broken = false;
    private static boolean announced = false;

    // =============================================================================================
    // Portal-window bracket — set from IrisCompatOn262Renderer, the SAME wide bracket IS5-MB uses
    // =============================================================================================

    private static String windowLabel = "MAIN";

    /**
     * Armed pre-push around one portal's dest render, and cleared post-pop. Deliberately the WIDE
     * bracket: the smearing invocation was measured firing OUTSIDE the pushed portal layer, so the
     * {@code layer=} column alone would have mislabelled it. Both are printed on every row.
     */
    public static void armWindow(Portal portal) {
        if (!ENABLED || broken) {
            return;
        }
        try {
            // Computed HERE, inside the lever gate and inside the try, rather than at the call site:
            // the call site sits between two arm() calls and the try/finally that disarms them, so a
            // throw there would strand both arms. This mirrors IrisDestPrevCamera.arm()'s own test
            // (mc.level's dimension vs portal.getDestDim()), which is the definition the rest of the
            // IS5 work already uses.
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            boolean sameDim = mc.level != null && portal.getDestDim().equals(mc.level.dimension());
            windowLabel = "DEST(" + (sameDim ? "same-dim" : "CROSS-dim") + ",portal="
                + portal.getId() + ")";
        }
        catch (Throwable t) {
            windowLabel = "DEST(?)";
        }
    }

    public static void disarmWindow() {
        if (!ENABLED || broken) {
            return;
        }
        windowLabel = "MAIN";
    }

    // =============================================================================================
    // Hook 1 — the roster harvest (CompositeRenderer.renderAll HEAD)
    // =============================================================================================

    public static void onRenderAllHead(Object renderer) {
        if (!ENABLED || broken) {
            return;
        }
        try {
            String rid = renderer.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(renderer));
            // RE-HARVEST AT MOST ONCE A SECOND, rather than latching once per renderer identity.
            // Latching was wrong three ways: (1) Pass.viewWidth/viewHeight are MUTATED IN PLACE by
            // CompositeRenderer.recalculateSizes() on every render-target resize, and viewWidth is the
            // scale factor for the headline pixel column — latching it is a once-only sample of a
            // per-run-varying value, the exact discipline this project has already been burned by;
            // (2) drawBuffers/stageReadsFromAlt likewise describe CURRENT state; (3) GL reuses program
            // NAMES freed by glDeleteProgram, so a roster entry that outlives its pipeline can alias an
            // unrelated program as a DEEP pass and fabricate a measured row. Re-walking a dozen passes
            // once a second is free beside a per-frame render and bounds all three windows to 1 s.
            long nowNs = System.nanoTime();
            Long last = LAST_HARVEST.get(rid);
            if (last != null && nowNs - last < 1_000_000_000L) {
                return;
            }
            boolean firstTime = last == null;
            LAST_HARVEST.put(rid, nowNs);
            if (!ensureReflection()) {
                return;
            }
            Object passesObj = fPasses.get(renderer);
            if (!(passesObj instanceof List<?> passes)) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Object pass : passes) {
                Object prog = fPassProgram.get(pass);
                if (prog == null) {
                    continue; // ComputeOnlyPass — renderAll skips it before Program.use()
                }
                int pid = programId(prog);
                if (pid <= 0) {
                    continue;
                }
                PassInfo info = new PassInfo();
                info.name = String.valueOf(fPassName.get(pass));
                info.rendererId = rid;
                info.deep = DEEP_PASSES.contains(info.name);
                info.drawBuffers = safeIntArray(fPassDrawBuffers.get(pass));
                info.readsAlt = String.valueOf(fPassReadsAlt.get(pass));
                info.viewWidth = fPassViewWidth.getInt(pass);
                info.viewHeight = fPassViewHeight.getInt(pass);
                ROSTER.put(pid, info);
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(info.name).append("#").append(pid);
                if (info.deep) {
                    sb.append("<DEEP>");
                }
            }
            // Only the FIRST harvest of a renderer logs. The once-a-second refreshes are silent: they
            // exist to keep the values current, not to fill the log at 1 Hz with an unchanged roster.
            if (firstTime) {
                LOGGER.info(P + "roster harvested for {} : [{}]", rid, sb);
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // =============================================================================================
    // Hook 2 — the bind witness (Program.use() TAIL, i.e. after uniforms/samplers/images update)
    // =============================================================================================

    public static void onProgramUsed(Object program) {
        if (!ENABLED || broken) {
            return;
        }
        try {
            frameAllProgramBinds++;
            int pid = programId(program);
            PassInfo info = ROSTER.get(pid);
            auditCoverageOnce(pid, info);
            if (info == null) {
                return; // not a composite-chain program (gbuffers/shadow/final); see the audit above
            }
            frameCompositeBinds++;
            if (!info.deep) {
                return;
            }
            frameDeepBinds++;
            Row r = new Row();
            r.seq = frameDeepBinds;
            r.name = info.name;
            r.pid = pid;
            r.rendererId = info.rendererId;
            r.drawBuffers = info.drawBuffers;
            r.readsAlt = info.readsAlt;
            r.viewWidth = info.viewWidth;
            r.viewHeight = info.viewHeight;
            r.window = windowLabel;
            r.layer = PortalRendering.getPortalLayer();
            r.note = "";
            // MEASURE FIRST, RETAIN SECOND. The per-second worst-span headline must see every bind,
            // including ones past the row cap: dropping a bind from the measurement as well as from
            // the printed list would let the guilty pass hide behind 64 innocent ones.
            measure(r, pid);
            SECOND_PIDS.add(info.name + "#" + pid + "@" + info.rendererId);
            if (Double.isNaN(r.maxSpanPx) || r.maxSpanPx > secondMaxSpanPx) {
                secondMaxSpanPx = r.maxSpanPx;
                secondMaxSpanWhere = "bind#" + r.seq + " " + r.name + "#" + pid + " " + r.window;
            }
            // The cap removes a row from the PRINTED list only. The row object still gets its
            // post-write sample, because secondMaxPostSpanPx is the headline that adjudicates the fix
            // and a capped bind hiding from it would be the same "guilty pass hides behind 64 innocent
            // ones" failure the PRE side already guards against.
            pendingRow = r;
            pendingRowPid = pid;
            if (rows.size() >= MAX_ROWS) {
                frameTruncated++;
                secondTruncated++;
                return;
            }
            rows.add(r);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * THE POST-WRITE VERIFICATION SAMPLE — hooked immediately before {@code _drawElements}.
     *
     * <p>{@link #onProgramUsed} reads at {@code Program.use()} TAIL, which in {@code renderAll} is
     * bytecode offset 422. The IS5-MB correction writes at that same offset via the caller-side
     * {@code shift = AFTER} injection, and the draw is at 455. A sample taken only at 422 therefore
     * reads <b>iris's</b> values and can say nothing about whether a correction landed — it would report
     * the uncorrected 511-block offset even on a perfectly working fix, and be read as a failure.
     *
     * <p>This sample sits at 455, after any write and before the draw, so it is the state the fragment
     * shader actually executes with. Comparing the two lines is the whole verification: the PRE line
     * shows what iris supplied, the POST line shows what the shader used.
     *
     * <p>Straight-line code between 422 and 455 (no branch, verified by javap), so the program bound at
     * the pre-sample is still bound here — which is why this needs no pass lookup of its own.
     */
    public static void onPreDraw() {
        if (!ENABLED || broken) {
            return;
        }
        Row r = pendingRow;
        pendingRow = null; // consume FIRST: a throw must not attach this sample to a later pass
        if (r == null) {
            return;
        }
        try {
            measurePost(r, pendingRowPid);
            if (Double.isNaN(r.postMaxSpanPx) || r.postMaxSpanPx > secondMaxPostSpanPx) {
                secondMaxPostSpanPx = r.postMaxSpanPx;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * THE COVERAGE AUDIT — one line per distinct program id, the first time it is ever bound.
     *
     * <p>The census only deep-measures programs it can name from a {@code CompositeRenderer} roster. If
     * a motion-blur-capable program were bound from anywhere else — a chain this census does not hook,
     * or a renderer whose roster walk failed — its binds would be counted and then dropped, and a reader
     * seeing no such rows would wrongly conclude every bind was accounted for. That is a coverage claim,
     * and a coverage claim has to be measured like any other.
     *
     * <p>So: for every program id the first time it is bound, ask the driver whether it has BOTH
     * {@code previousCameraPosition} and {@code gbufferPreviousProjection} active. That pair is the
     * signature of reprojecting into the PREVIOUS FRAME'S SCREEN SPACE, which is what produces a
     * screen-space smear. The pair matters: {@code previousCameraPosition} alone is far too broad —
     * Complementary references it from {@code reflections.glsl}, {@code mainLighting.glsl} and the
     * reflection-voxelization includes, so ordinary gbuffers programs would trip a one-uniform test and
     * bury the real signal in expected warnings. Any program matching the full signature that is NOT in
     * the roster is named loudly, and is the next thing to widen onto with
     * {@code -PcompositeCensusPasses}. Cost: at most two {@code glGetUniformLocation} calls once per
     * distinct program per session.
     */
    private static void auditCoverageOnce(int pid, PassInfo info) {
        if (pid <= 0 || !AUDITED_PIDS.add(pid)) {
            return;
        }
        int lPrevCam = GL20.glGetUniformLocation(pid, "previousCameraPosition");
        if (lPrevCam < 0) {
            return; // cannot reproject against the previous frame; cannot be the smear source
        }
        int lPrevProj = GL20.glGetUniformLocation(pid, "gbufferPreviousProjection");
        if (lPrevProj < 0) {
            return; // reprojects world-space only (reflections/voxelisation), not into prev screen space
        }
        if (info != null) {
            LOGGER.info(P + "COVERAGE AUDIT: program #{} = \"{}\" ({}) declares"
                    + " previousCameraPosition (loc {}) / gbufferPreviousProjection (loc {}) — velocity"
                    + "-capable and IN the roster, so it is {}.",
                pid, info.name, info.rendererId, lPrevCam, lPrevProj,
                info.deep ? "DEEP-MEASURED" : "counted but NOT deep-measured; add it with"
                    + " -PcompositeCensusPasses if a bind of it is ever suspected");
            return;
        }
        LOGGER.warn(P + "COVERAGE AUDIT !! program #{} is VELOCITY-CAPABLE (previousCameraPosition"
            + " loc {}, gbufferPreviousProjection loc {}) but is in NO CompositeRenderer roster, so it"
            + " is NOT being measured. It was bound from a chain this census does not harvest. If the"
            + " deep rows all read zero, THIS is the next place to look — identify it and widen with"
            + " -PcompositeCensusPasses, or extend the roster harvest to its renderer.", pid,
            lPrevCam, lPrevProj);
    }

    // =============================================================================================
    // The measurement — read the six ACTIVE inputs, then replay the shader's own arithmetic
    // =============================================================================================

    private static final float[] MV = new float[16];
    private static final float[] MV_INV = new float[16];
    private static final float[] MV_PREV = new float[16];
    private static final float[] PROJ = new float[16];
    private static final float[] PROJ_INV = new float[16];
    private static final float[] PROJ_PREV = new float[16];
    private static final float[] PRODUCT = new float[16];

    /** Screen positions: centred AND off-centre. A rotational mismatch is ZERO at the centre. */
    private static final double[] GRID_U = {0.15, 0.50, 0.85};
    private static final double[] GRID_V = {0.15, 0.50, 0.85};
    /** Depths above the shader's own {@code z <= 0.56} early-out, spanning near to far terrain. */
    private static final double[] GRID_Z = {0.60, 0.95, 0.999};

    private static void measure(Row r, int pid) {
        r.uniformsReadable = false;
        int lCam = GL20.glGetUniformLocation(pid, "cameraPosition");
        int lPrevCam = GL20.glGetUniformLocation(pid, "previousCameraPosition");
        int lMv = GL20.glGetUniformLocation(pid, "gbufferModelView");
        int lMvInv = GL20.glGetUniformLocation(pid, "gbufferModelViewInverse");
        int lMvPrev = GL20.glGetUniformLocation(pid, "gbufferPreviousModelView");
        int lProj = GL20.glGetUniformLocation(pid, "gbufferProjection");
        int lProjInv = GL20.glGetUniformLocation(pid, "gbufferProjectionInverse");
        int lProjPrev = GL20.glGetUniformLocation(pid, "gbufferPreviousProjection");

        // ONLY THE SIX UNIFORMS THE SHADER ACTUALLY USES ARE REQUIRED.
        //
        // gbufferModelView and gbufferProjection are deliberately NOT in this guard. They are declared
        // in the pack (lib/uniforms.glsl:66,125) but composite4 never references them, so the GLSL
        // linker marks them INACTIVE and glGetUniformLocation returns -1 for both. This is not a
        // prediction: fabric/runs/client-sodium/logs/latest.log:867 of this very worktree reads
        //   maxAbsDiff(gbufferModelView, gbufferPreviousModelView)=n/a(loc -1/4)
        //   maxAbsDiff(gbufferProjection, gbufferPreviousProjection)=n/a(loc -1/5)
        // Requiring them would have aborted EVERY composite4 measurement and printed a clean "no blur"
        // acquittal on an instrument that measured nothing — the exact false negative this census
        // exists to rule out.
        //
        // TWO CONSEQUENCES WORTH RECORDING. First, the handoff's "matrix maxAbsDiff = 0.00000" is NOT a
        // measurement; the log says n/a(loc -1/...). The matrix half of the central contradiction was
        // never measured. Second, since composite4 holds no copy of the CURRENT matrices, nothing in
        // that program constrains gbufferPreviousModelView to agree with gbufferModelViewInverse — so
        // the "it telescopes to exactly zero for any depth" argument was never verified either.
        if (lCam < 0 || lPrevCam < 0 || lMvInv < 0 || lMvPrev < 0 || lProjInv < 0 || lProjPrev < 0) {
            // With Motion Blur OFF the pack's composite4 references none of the previous-frame trio, so
            // ALL of these go inactive at once. That is the expected shape of an MB-off control run and
            // proves by construction that the pass computes no velocity — report it, never infer it.
            r.note = "VELOCITY UNIFORMS ABSENT (this pass computes no motion-blur velocity): cam="
                + lCam + " prevCam=" + lPrevCam + " mvInv=" + lMvInv + " mvPrev=" + lMvPrev
                + " projInv=" + lProjInv + " projPrev=" + lProjPrev
                + " ; optional extras: mv=" + lMv + " proj=" + lProj
                + " (-1 = the GLSL linker stripped it as UNUSED). With Motion Blur OFF this is the"
                + " EXPECTED row. With Motion Blur ON it is a finding — report it verbatim.";
            return;
        }

        GL20.glGetUniformfv(pid, lCam, r.cam);
        GL20.glGetUniformfv(pid, lPrevCam, r.prevCam);
        GL20.glGetUniformfv(pid, lMvInv, MV_INV);
        GL20.glGetUniformfv(pid, lMvPrev, MV_PREV);
        GL20.glGetUniformfv(pid, lProjInv, PROJ_INV);
        GL20.glGetUniformfv(pid, lProjPrev, PROJ_PREV);
        r.uniformsReadable = true;

        double dx = r.cam[0] - r.prevCam[0];
        double dy = r.cam[1] - r.prevCam[1];
        double dz = r.cam[2] - r.prevCam[2];
        r.camDelta = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // The CURRENT-matrix diff columns, kept only because they are what every previous probe
        // reported. They are OPTIONAL: on composite4 both locations are -1 and the columns read n/a.
        r.haveCurrentMatrices = lMv >= 0 && lProj >= 0;
        if (r.haveCurrentMatrices) {
            GL20.glGetUniformfv(pid, lMv, MV);
            GL20.glGetUniformfv(pid, lProj, PROJ);
            r.dMv = maxAbsDiff(MV, MV_PREV);
            r.dProj = maxAbsDiff(PROJ, PROJ_PREV);
        }

        // THE CANCELLATION CONDITION, stated over the matrices the shader ACTUALLY holds.
        // composite4 unprojects with MV_INV / PROJ_INV and reprojects with MV_PREV / PROJ_PREV. The
        // chain cancels — giving velocity == 0 for every depth — if and only if MV_PREV is the inverse
        // of MV_INV and PROJ_PREV is the inverse of PROJ_INV. So the identity to test is
        // MV_PREV * MV_INV == I, not MV * MV_INV == I: the latter is uncomputable here (no MV) and
        // would in any case test a matrix the shader never reads.
        mul(MV_PREV, MV_INV, PRODUCT);
        r.idMv = maxAbsDiffFromIdentity(PRODUCT);
        mul(PROJ_PREV, PROJ_INV, PRODUCT);
        r.idProj = maxAbsDiffFromIdentity(PRODUCT);

        // Replay composite4.glsl:99-139 exactly, over the grid.
        r.maxSpanPx = 0.0;
        int width = r.viewWidth > 0 ? r.viewWidth : 1920;
        for (double u : GRID_U) {
            for (double v : GRID_V) {
                for (double z : GRID_Z) {
                    double span = spanPixels(u, v, z, dx, dy, dz, width);
                    // NaN marks a degenerate reprojection. EVERY comparison against NaN is false, so a
                    // plain `span > max` would silently discard it and report the clean verdict — test
                    // for it explicitly and let it win, because a degenerate w is a finding, not a zero.
                    if (Double.isNaN(span)) {
                        r.maxSpanPx = Double.NaN;
                        r.maxU = u;
                        r.maxV = v;
                        r.maxZ = z;
                        r.note = "DEGENERATE REPROJECTION (w == 0 or non-finite) at the marked grid"
                            + " point — the real shader divides anyway and samples at inf/NaN"
                            + " coordinates. This is a FINDING, not a zero.";
                        break;
                    }
                    if (span > r.maxSpanPx) {
                        r.maxSpanPx = span;
                        r.maxU = u;
                        r.maxV = v;
                        r.maxZ = z;
                    }
                }
            }
        }
        r.centreSpanPx = spanPixels(0.5, 0.5, 0.95, dx, dy, dz, width);
    }

    /**
     * Re-read only what a correction can have changed — {@code cameraPosition} and
     * {@code previousCameraPosition} — and re-run the replay. The six matrices are NOT re-read: nothing
     * writes them (IS5-MB is position-only by design, because the census measured idMV=idP=0.00000, so
     * the matrix chain already cancels), and the scratch arrays still hold this bind's values from the
     * pre-sample microseconds earlier.
     */
    private static void measurePost(Row r, int pid) {
        if (!r.uniformsReadable || pid <= 0) {
            return;
        }
        int lCam = GL20.glGetUniformLocation(pid, "cameraPosition");
        int lPrevCam = GL20.glGetUniformLocation(pid, "previousCameraPosition");
        if (lCam < 0 || lPrevCam < 0) {
            return;
        }
        GL20.glGetUniformfv(pid, lCam, r.postCam);
        GL20.glGetUniformfv(pid, lPrevCam, r.postPrev);
        r.postSampled = true;
        double dx = r.postCam[0] - r.postPrev[0];
        double dy = r.postCam[1] - r.postPrev[1];
        double dz = r.postCam[2] - r.postPrev[2];
        r.postCamDelta = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int width = r.viewWidth > 0 ? r.viewWidth : 1920;
        r.postMaxSpanPx = 0.0;
        for (double u : GRID_U) {
            for (double v : GRID_V) {
                for (double z : GRID_Z) {
                    double span = spanPixels(u, v, z, dx, dy, dz, width);
                    if (Double.isNaN(span)) {
                        r.postMaxSpanPx = Double.NaN;
                        return;
                    }
                    if (span > r.postMaxSpanPx) {
                        r.postMaxSpanPx = span;
                    }
                }
            }
        }
    }

    /**
     * The pack's own arithmetic, verbatim, for one screen sample — returning the blur span in PIXELS
     * at {@code MOTION_BLURRING_STRENGTH = 1.0}.
     *
     * <p>{@code composite4.glsl:128-139}: the clamped velocity is scaled by the strength, then by
     * {@code 0.02}, and the loop walks {@code sampleCount = 9} steps of it across the texture. The span
     * is therefore {@code |clamped| * strength * 0.02 * 9}, in texture-coordinate units, which this
     * converts to pixels. Strength is a compile-time {@code #define} the mod cannot read, so the value
     * is reported at strength 1.0 and scales linearly with the user's setting.
     */
    private static double spanPixels(double u, double v, double z,
                                     double offX, double offY, double offZ, int width) {
        // currentPosition = vec4(texCoord, z, 1.0) * 2.0 - 1.0
        double[] cur = {u * 2.0 - 1.0, v * 2.0 - 1.0, z * 2.0 - 1.0, 1.0};

        double[] view = transform(PROJ_INV, cur);
        view = transform(MV_INV, view);
        // A degenerate w is NOT "no blur". Returning 0.0 here would report the innocent verdict for an
        // input that is maximally broken in the real shader (which divides anyway and produces inf/NaN
        // texture coordinates). NaN propagates to maxSpanPx and prints as "NaN", which is unmissable.
        if (view[3] == 0.0 || !isFinite(view[3])) {
            return Double.NaN;
        }
        view[0] /= view[3];
        view[1] /= view[3];
        view[2] /= view[3];
        view[3] = 1.0;

        double[] prev = {view[0] + offX, view[1] + offY, view[2] + offZ, view[3]};
        prev = transform(MV_PREV, prev);
        prev = transform(PROJ_PREV, prev);
        if (prev[3] == 0.0 || !isFinite(prev[3])) {
            return Double.NaN;
        }
        prev[0] /= prev[3];
        prev[1] /= prev[3];

        double vx = cur[0] - prev[0];
        double vy = cur[1] - prev[1];
        double len = Math.sqrt(vx * vx + vy * vy);
        if (!isFinite(len)) {
            return Double.NaN;
        }
        double clamped = len / (1.0 + len); // * MOTION_BLURRING_STRENGTH (reported at 1.0)
        // composite4.glsl:138-139 starts the walk at texCoord - velocity*(sampleCount/2 - 1 + dither)
        // and does sampleCount increments across sampleCount samples, so the FIRST and LAST samples are
        // (sampleCount - 1) steps apart. Using sampleCount would overstate the extent by 12.5%.
        // sampleCount is 9 unless the pack was built with LOW_QUALITY_MOTION_BLUR, where it is 3 and
        // the per-step scale is 0.06 instead of 0.02 — that variant is NOT modelled here, so on such a
        // pack this column is a lower bound (0.06*2 vs 0.02*8 = one third).
        return clamped * 0.02 * (9.0 - 1.0) * width;
    }

    /** Column-major (GL layout): {@code m[col * 4 + row]}. Written out rather than delegated to JOML
     *  so the storage convention is unambiguous at the point of use. */
    private static double[] transform(float[] m, double[] v) {
        double[] o = new double[4];
        for (int row = 0; row < 4; row++) {
            o[row] = m[row] * v[0] + m[4 + row] * v[1] + m[8 + row] * v[2] + m[12 + row] * v[3];
        }
        return o;
    }

    /** {@code out = a * b}, both column-major. */
    private static void mul(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + row] * b[col * 4 + k];
                }
                out[col * 4 + row] = s;
            }
        }
    }

    private static double maxAbsDiff(float[] a, float[] b) {
        double max = 0.0;
        for (int i = 0; i < 16; i++) {
            double d = Math.abs(a[i] - b[i]);
            if (d > max) {
                max = d;
            }
        }
        return max;
    }

    private static double maxAbsDiffFromIdentity(float[] m) {
        double max = 0.0;
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                double expected = col == row ? 1.0 : 0.0;
                double d = Math.abs(m[col * 4 + row] - expected);
                if (d > max) {
                    max = d;
                }
            }
        }
        return max;
    }

    private static boolean isFinite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    // =============================================================================================
    // Hook 3 — frame boundary (GameRenderer.render TAIL) + the 1 Hz emit
    // =============================================================================================

    public static void onFrameEnd() {
        if (!ENABLED || broken) {
            return;
        }
        try {
            frameCounter++;
            if (!announced) {
                announced = true;
                LOGGER.info(P + "ARMED. Deep passes = {} (override with"
                        + " -Dseamlessportals.compositeCensusPasses). Every Program.use() in the"
                        + " process is witnessed; deep passes additionally get all six matrices, both"
                        + " camera vectors, the two NEVER-BEFORE-MEASURED inverse-consistency checks"
                        + " (idMV/idP), and a CPU replay of composite4's own velocity arithmetic over a"
                        + " 3x3x3 screen/depth grid reported as maxSpanPx (blur span in pixels at"
                        + " MOTION_BLURRING_STRENGTH=1).",
                    DEEP_PASSES);
            }

            // roll the frame — SWAP, never copy (see the field comment)
            List<Row> completed = rows;
            rows = lastFrameRows;
            rows.clear();
            lastFrameRows = completed;
            lastFrameDeepBinds = frameDeepBinds;
            lastFrameCompositeBinds = frameCompositeBinds;
            lastFrameAllProgramBinds = frameAllProgramBinds;
            lastFrameTruncated = frameTruncated;

            secondFrames++;
            if (frameDeepBinds < secondMinDeep) {
                secondMinDeep = frameDeepBinds;
            }
            if (frameDeepBinds > secondMaxDeep) {
                secondMaxDeep = frameDeepBinds;
            }

            // A row that never reached its draw must not collect a later pass's post-sample.
            pendingRow = null;
            frameDeepBinds = 0;
            frameCompositeBinds = 0;
            frameAllProgramBinds = 0;
            frameTruncated = 0;

            long now = System.nanoTime();
            if (now - lastEmitNanos < 1_000_000_000L) {
                return;
            }
            lastEmitNanos = now;
            emit();
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    private static void emit() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append('\n').append(P).append("1s SUMMARY: frames=").append(secondFrames)
            .append(" | deep-pass binds per frame: min=")
            .append(secondMinDeep == Integer.MAX_VALUE ? 0 : secondMinDeep)
            .append(" max=").append(secondMaxDeep)
            .append(" | worst blur span over the second: PRE-write = ")
            .append(String.format("%.3f", secondMaxSpanPx)).append(" px @ ")
            .append(secondMaxSpanWhere)
            .append(" , POST-write = ").append(String.format("%.3f", secondMaxPostSpanPx))
            .append(" px (POST is what the shader executed; a large PRE with a ~0 POST is the IS5-MB"
                + " correction working)")
            .append(" | distinct deep programs seen: ").append(SECOND_PIDS);
        if (secondTruncated > 0) {
            // The cap drops rows from the PRINTED list only — every bind is still measured and still
            // feeds the worst-span headline above. Say so, so the number is not read as under-counted.
            sb.append("\n").append(P).append("  note: ").append(secondTruncated)
                .append(" bind(s) exceeded the per-frame print cap of ").append(MAX_ROWS)
                .append(" this second. They were MEASURED and are included in the worst-span figure;")
                .append(" only their per-bind rows were omitted from the LAST FRAME listing.");
        }
        if (secondMaxDeep == 0) {
            sb.append("\n").append(P).append("  !! ZERO deep-pass binds in the last second. Either the")
                .append(" pack does not name the motion-blur pass ")
                .append(DEEP_PASSES)
                .append(" (check the roster line above for the real names) or no composite chain ran.")
                .append(" THIS RUN MEASURED NOTHING — do not adjudicate from it.");
        }

        sb.append('\n').append(P).append("LAST FRAME (#").append(frameCounter)
            .append("): deepBinds=").append(lastFrameDeepBinds)
            .append(" compositeBinds=").append(lastFrameCompositeBinds)
            .append(" allProgramBinds=").append(lastFrameAllProgramBinds);
        if (lastFrameTruncated > 0) {
            sb.append(" !! ").append(lastFrameTruncated)
                .append(" ROWS DROPPED (cap ").append(MAX_ROWS).append(") — the list below is INCOMPLETE");
        }
        if (lastFrameRows.isEmpty()) {
            sb.append("\n    (no deep-pass binds this frame)");
        }
        for (Row r : lastFrameRows) {
            sb.append("\n    [").append(r.seq).append("] ").append(r.name).append(" pid=").append(r.pid)
                .append(" rend=").append(r.rendererId)
                .append(" win=").append(r.window)
                .append(" layer=").append(r.layer)
                .append(" drawBuf=").append(r.drawBuffers)
                .append(" readsAlt=").append(r.readsAlt)
                .append(" vp=").append(r.viewWidth).append('x').append(r.viewHeight);
            if (!r.uniformsReadable) {
                sb.append("\n         ").append(r.note);
                continue;
            }
            sb.append("\n         cam=(").append(f(r.cam[0])).append(',').append(f(r.cam[1]))
                .append(',').append(f(r.cam[2])).append(") prev=(").append(f(r.prevCam[0]))
                .append(',').append(f(r.prevCam[1])).append(',').append(f(r.prevCam[2]))
                .append(") |cam-prev|=").append(f(r.camDelta));
            sb.append("\n         dMV=").append(r.haveCurrentMatrices ? f5(r.dMv) : "n/a(inactive)")
                .append(" dP=").append(r.haveCurrentMatrices ? f5(r.dProj) : "n/a(inactive)")
                .append("  idMV=").append(f5(r.idMv)).append(" idP=").append(f5(r.idProj))
                .append("   <- idMV = max|MVprev*MVinv - I|, idP = max|Pprev*Pinv - I|: the EXACT"
                    + " condition for composite4's unproject/reproject chain to cancel. NONZERO means"
                    + " it does NOT cancel, so velocity is nonzero PER PIXEL and depth-dependent even"
                    + " with |cam-prev| exactly 0. dMV/dP read n/a on composite4 because"
                    + " gbufferModelView/gbufferProjection are INACTIVE there — which is also why no"
                    + " previous probe ever actually measured them");
            sb.append("\n         BLUR SPAN @STRENGTH=1: centre=").append(f(r.centreSpanPx))
                .append(" px, MAX=").append(f(r.maxSpanPx)).append(" px at (u=").append(r.maxU)
                .append(",v=").append(r.maxV).append(",z=").append(r.maxZ).append(')');
            if (r.postSampled) {
                sb.append("\n         POST-WRITE (sampled between the IS5-MB write and the draw — this"
                        + " is what the shader ACTUALLY executed with): prev=(")
                    .append(f(r.postPrev[0])).append(',').append(f(r.postPrev[1])).append(',')
                    .append(f(r.postPrev[2])).append(") |cam-prev|=").append(f(r.postCamDelta))
                    .append(" BLUR SPAN MAX=").append(f(r.postMaxSpanPx)).append(" px  [")
                    .append(verdict(r)).append(']');
            }
            else {
                sb.append("\n         POST-WRITE: not sampled (no draw followed this bind)");
            }
            if (!r.note.isEmpty()) {
                sb.append("\n         !! ").append(r.note);
            }
        }
        LOGGER.info(sb.toString());

        secondFrames = 0;
        secondMinDeep = Integer.MAX_VALUE;
        secondMaxDeep = 0;
        secondMaxSpanPx = 0.0;
        secondMaxPostSpanPx = 0.0;
        secondMaxSpanWhere = "n/a";
        secondTruncated = 0;
        SECOND_PIDS.clear();
    }

    // =============================================================================================
    // Plumbing
    // =============================================================================================

    private static volatile boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;
    private static Field fPasses;
    private static Field fPassName;
    private static Field fPassProgram;
    private static Field fPassDrawBuffers;
    private static Field fPassReadsAlt;
    private static Field fPassViewWidth;
    private static Field fPassViewHeight;
    private static Method mProgramId;

    /** Fast path OUTSIDE the monitor: this runs on every {@code Program.use()} in the process —
     *  roughly 200 times a frame — and acquiring a lock that many times to re-read a settled flag is
     *  pure waste. The volatile read is the publication edge; the monitor only guards the one-time
     *  resolution below. */
    private static boolean ensureReflection() {
        return reflectionReady || resolveReflection();
    }

    private static synchronized boolean resolveReflection() {
        if (reflectionReady) {
            return true;
        }
        if (reflectionAttempted) {
            return false;
        }
        reflectionAttempted = true;
        try {
            Class<?> cr = Class.forName("net.irisshaders.iris.pipeline.CompositeRenderer");
            fPasses = cr.getDeclaredField("passes");
            fPasses.setAccessible(true);
            Class<?> pc = Class.forName("net.irisshaders.iris.pipeline.CompositeRenderer$Pass");
            fPassName = pc.getDeclaredField("name");
            fPassName.setAccessible(true);
            fPassProgram = pc.getDeclaredField("program");
            fPassProgram.setAccessible(true);
            fPassDrawBuffers = pc.getDeclaredField("drawBuffers");
            fPassDrawBuffers.setAccessible(true);
            fPassReadsAlt = pc.getDeclaredField("stageReadsFromAlt");
            fPassReadsAlt.setAccessible(true);
            fPassViewWidth = pc.getDeclaredField("viewWidth");
            fPassViewWidth.setAccessible(true);
            fPassViewHeight = pc.getDeclaredField("viewHeight");
            fPassViewHeight.setAccessible(true);
            Class<?> pg = Class.forName("net.irisshaders.iris.gl.program.Program");
            mProgramId = pg.getMethod("getProgramId");
            mProgramId.setAccessible(true);
            reflectionReady = true;
            return true;
        }
        catch (Throwable t) {
            broken = true;
            LOGGER.warn(P + "DISARMED: could not resolve the iris CompositeRenderer/Pass/Program"
                + " members this census reads. No measurement will be produced this session.", t);
            return false;
        }
    }

    private static int programId(Object program) {
        try {
            if (!ensureReflection()) {
                return -1;
            }
            Object v = mProgramId.invoke(program);
            return v instanceof Integer n ? n : -1;
        }
        catch (Throwable t) {
            return -1;
        }
    }

    private static String safeIntArray(Object o) {
        try {
            return o instanceof int[] a ? Arrays.toString(a) : String.valueOf(o);
        }
        catch (Throwable t) {
            return "?";
        }
    }

    /**
     * The one-word adjudication of a bind, from the PRE/POST pair. Written out so a log reader does not
     * have to hold the decision rule in their head — the whole point of the post-write sample is that
     * "pre says 511, post says 0" and "pre says 511, post says 511" look nearly identical at a glance
     * and mean opposite things.
     */
    private static String verdict(Row r) {
        if (Double.isNaN(r.maxSpanPx) || Double.isNaN(r.postMaxSpanPx)) {
            return "DEGENERATE — a reprojection produced a non-finite result; read the row, not this"
                + " label";
        }
        boolean preBad = r.maxSpanPx > 1.0;
        boolean postBad = r.postMaxSpanPx > 1.0;
        if (!preBad && !postBad) {
            return "CLEAN — this bind was never blurring";
        }
        if (preBad && !postBad) {
            return "CORRECTED — iris supplied a smearing pair and the IS5-MB write neutralised it";
        }
        if (!preBad) {
            return "!! REGRESSION — this bind was clean before the write and is smearing after it";
        }
        // BOTH exceed the threshold. That is NOT automatically a failure, and an earlier version of
        // this method wrongly called it one. The correction is not dest-only — it applies to every
        // guarded bind — so whenever the PLAYER IS MOVING the main view's chain legitimately carries
        // real motion blur before AND after the write. Judging on the PRE→POST ratio separates
        // "the write did nothing" from "this bind is supposed to be blurring".
        double ratio = r.postMaxSpanPx / Math.max(r.maxSpanPx, 1e-9);
        if (ratio < 0.5) {
            return "REDUCED — the write cut the span to " + String.format("%.0f%%", ratio * 100)
                + " of what iris supplied; residual blur here is expected if the camera is moving";
        }
        if (ratio > 1.5) {
            return "!! WORSENED — the write INCREASED the span; suspect a wrong chain match";
        }
        return "UNCHANGED — PRE and POST agree, so the write did not alter this bind. Expected on a"
            + " moving camera (real blur, correctly preserved); a finding only if the camera is"
            + " STATIONARY and the span is large, which would mean the correction did not take";
    }

    private static String f(double d) {
        return String.format("%.3f", d);
    }

    private static String f5(double d) {
        return String.format("%.5f", d);
    }

    private static void disarm(Throwable t) {
        broken = true;
        try {
            LOGGER.warn(P + "DISARMED after a throw — no further census output this session"
                + " (rendering otherwise unaffected).", t);
        }
        catch (Throwable ignored) {
            // never let the probe's own logging escape
        }
    }

    /** Registered beside the other iris-compat teardowns. A pack reload mints NEW program ids, so the
     *  roster must go with it — a stale id could otherwise alias a different pass. */
    public static void teardown() {
        ROSTER.clear();
        LAST_HARVEST.clear();
        AUDITED_PIDS.clear();
        rows.clear();
        lastFrameRows.clear();
        windowLabel = "MAIN";
    }
}
