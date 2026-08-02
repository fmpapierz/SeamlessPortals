package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatOn262Renderer;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.Arrays;
import java.util.Locale;

/**
 * TP-XDIM — THE FRAME CENSUS (2026-07-28; log-only, DEFAULT OFF, never throws into the render path).
 *
 * <h2>The open defect it exists to classify</h2>
 * Third person (F5) + iris shaders ON + the third-person camera on the FAR side of a portal from the
 * player ⇒ a "totally corrupted, whole-screen" render. Everything else about it is UNMEASURED —
 * shaders-only? cross-dim-only? during the camera crossing, the player crossing, or persisting after?
 * This instrument answers <b>which frames</b> are on the suspect path and <b>what actually ran</b> on
 * them. It deliberately does NOT answer what the corruption looks like (see §"NOT instrumented").
 *
 * <h2>What is MEASUREMENT here, and what is only AIM VALIDATION</h2>
 * This distinction is load-bearing and was corrected after an adversarial pass; read it before
 * writing any conclusion from this log.
 * <ul>
 *   <li><b>AIM VALIDATION / weave proof (NOT evidence):</b> {@code renderLevel=}, {@code is0=} and
 *       {@code f1=}. On a {@code RENDERED} frame all three reading NO is <i>entailed</i> by five
 *       lines of source — {@code MixinGameRenderer.seamlessportals$redirectRenderingWorld} returns
 *       without calling the wrapped {@code renderLevel} — and {@code is0}/{@code f1} both live
 *       INSIDE {@code renderLevel}, i.e. downstream of the very proposition {@code renderLevel=}
 *       tests. Weaving them three separate ways protects against one witness silently failing to
 *       WEAVE; it does not make them three independent tests of the hypothesis. A NO here confirms
 *       the census is on the real path. Any ONE reading YES on a {@code RENDERED} frame is still
 *       decisive — it would mean the premise is false — but a NO is not evidence <i>for</i> it.</li>
 *   <li><b>THE ACTUAL MEASUREMENT:</b> the 11-code frame classification (which of
 *       {@code renderCrossPortalView}'s exits fired, and when), {@code invoke=} (WHICH dest driver
 *       ran), the {@code iris*} columns (what iris state the dest render actually used), and the
 *       dim / side / camera columns (which crossing phase the frame belongs to). These are the
 *       things nobody knows yet.</li>
 * </ul>
 *
 * <h2>Why there are FOUR iris columns and not two</h2>
 * {@code irisPre}, {@code irisPost} and {@code irisNow} are all sampled while {@code client.level}
 * is the SOURCE level, so they can never disagree about the dimension no matter what the dest render
 * did — a probe pointed at nothing. {@code ActSeedProbe:354-360} already records this exact trap in
 * this repo. {@code irisDuring} / {@code irisDuringEnd} are therefore sampled INSIDE the dest world
 * swap ({@code MyGameRenderer:308} decomposed / {@code :595} full pipeline), which is the only window
 * in which the dest render's iris state is observable at all. {@code irisPre}/{@code irisPost} are
 * kept: they honestly answer the different question of whether the manager slot LEAKS across the
 * bracket.
 *
 * <p>The during-sample is taken on EVERY frame that renders a dest world, not only cross-view frames,
 * and is labelled {@code inCrossView=yes|no}. That is deliberate: a normal shaders-ON cross-dim
 * portal frame is a frame where a dimension change is KNOWN to happen, so its row is the control that
 * proves this probe can see a dimension change at all. Without it, "irisDuring shows the source dim
 * on cross-view frames" could not be told apart from "this probe cannot read a dim change".
 *
 * <h2>Sentinels — a failure is NEVER a value</h2>
 * No field ever prints {@code 0}, {@code ""}, {@code -1}, {@code null} or {@code ?} to mean "could
 * not read". {@code N-A(...)} = structurally not applicable on this frame. {@code UNREADABLE(...)} =
 * a read was attempted and failed. {@code NO-IRIS-FACADE(invoker=X)} = the compat invoker is the
 * no-op base. {@code is0=NO} carries the session anchor total so "the anchor did not fire this frame"
 * can never be confused with "the anchor was never woven". Zero is a legitimate count and means zero.
 *
 * <h2>Both directions of every branch</h2>
 * {@code invoke=} is fed from ALL THREE dest drivers — the compat renderer's D23 layer-0 fallback,
 * its full-pipeline branch, and the base {@code PortalRenderer} decomposed path used by the stencil
 * family shaders-OFF. A census that could only ever print {@code D23-FALLBACK} could not falsify the
 * hypothesis it exists to test.
 *
 * <h2>Cost, stated honestly</h2>
 * Classification and counting are unconditional and allocation-free (bare {@code int} increments).
 * Row STRINGS are built only for captured frames. But {@code irisPre}/{@code irisDuring}/
 * {@code irisDuringEnd}/{@code irisPost} are built on every frame that enters the render bracket, and
 * {@code glState} on every in-world frame under the GL lever, regardless of capture — order ~1 KB per
 * frame on a sustained {@code RENDERED} run. That is accepted: those frames are the ones under study
 * and the lever is off by default.
 *
 * <h2>Render-thread logging</h2>
 * EMISSION is exactly ONE {@link Logger#info} call per ~1 s window carrying header + env + the FULL
 * tally + the rows — per-frame log4j on the 26.2 render thread costs ~130 ms stalls.
 *
 * <h2>Self-proving (the anti-void-run machinery)</h2>
 * A one-shot {@code ARMED} line proves the lever reached the JVM and this class loaded. A one-shot
 * {@code TARGET ACQUIRED} line proves the census saw the real defect path — and it is keyed on
 * <b>third person AND cross-dim</b>, not merely on "a cross-portal view rendered": view bobbing
 * carries the camera through a SAME-DIM portal in FIRST person too
 * ({@code CrossPortalViewRendering}'s own header says so), and letting that disarm the alarm is how
 * a run that measured nothing would read as valid. An {@code AIM FAULT} warning fires if a whole
 * window never even entered {@code renderCrossPortalView}, and a re-arming {@code WATCHDOG} says IN
 * THE LOG that the leg measured nothing. <b>A leg without its {@code TARGET ACQUIRED} line is VOID,
 * not a refutation.</b>
 *
 * <h2>Deliberately NOT instrumented</h2>
 * No pixel/depth readback, no {@code glGetError}, no barriers, no fences, nothing inside a pass —
 * readbacks are pipeline sync points and would perturb the very frames under test. The optional
 * {@code -PtpXdimCensusGl} add-on contributes exactly two {@code glIsEnabled} STATE QUERIES per frame
 * at the frame boundary and is run as its OWN leg. What the corruption looks like stays with the
 * operator's eyes plus an F2 screenshot correlated to a window by timestamp; a frame capture
 * ({@code DrawCallTrace}) is the follow-up instrument, correctly ordered AFTER the frame class is
 * known.
 *
 * <p>Render-thread only; plain fields, no atomics. Byte-inert at the shipped default — the first
 * statement of every entry point is a folded {@code static final boolean} test.
 */
public final class TpXdimFrameCensus {

    private TpXdimFrameCensus() {
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[TP-XDIM] ";

    // ===== outcome codes — EVERY exit of renderCrossPortalView is distinguishable ==============
    public static final int X_NOT_ENTERED = 0;               // the @WrapOperation never called it
    public static final int X_GATE_OFF = 1;                  // !IPGlobal.enableCrossPortalView
    public static final int X_PRE_PLAYER_NULL = 2;           // \
    public static final int X_PRE_LEVEL_NULL = 3;            //  |  the 5 sub-conditions of the ONE
    public static final int X_PRE_PLAYER_LEVEL_MISMATCH = 4; //  |- compound precondition guard,
    public static final int X_PRE_NO_CAMERA_ENTITY = 5;      //  |  in its short-circuit order
    public static final int X_PRE_NO_ORIGINAL_CAMERA = 6;    // /
    public static final int X_NO_PORTAL_HIT = 7;             // raytracePortals returned empty
    public static final int X_PORTAL_REJECTS_CAMERA = 8;     // !portal.canTeleportEntity(camera)
    public static final int X_RENDERED = 9;                  // returned true (it rendered the frame)
    /**
     * RESERVED — not reachable today. All five real exits above are instrumented, so the only other
     * way to leave {@code renderCrossPortalView} is a THROW; and an uncaught throw propagates past
     * the {@code @WrapOperation} and out of {@code GameRenderer.render}, so the TAIL row-builder
     * never runs and the frame produces NO row at all (the pending window is lost with it). This
     * code becomes live only if a future caller CATCHES the cross-view throw.
     */
    public static final int X_ENTERED_NO_EXIT = 10;
    /** -PcrossViewSuppressUnderPack declined the cross view because a shaderpack is running. */
    public static final int X_SUPPRESSED_UNDER_PACK = 11;

    private static final String[] X_NAME = {
        "NOT_ENTERED", "GATE_OFF", "PRE_PLAYER_NULL", "PRE_LEVEL_NULL",
        "PRE_PLAYER_LEVEL_MISMATCH", "PRE_NO_CAMERA_ENTITY", "PRE_NO_ORIGINAL_CAMERA",
        "NO_PORTAL_HIT", "PORTAL_REJECTS_CAMERA", "RENDERED", "ENTERED_NO_EXIT_RECORDED",
        "SUPPRESSED_UNDER_PACK"
    };

    // ===== sampling policy ====================================================================
    private static final long WINDOW_NS = 1_000_000_000L;
    private static final int MAX_ROWS_PER_WINDOW = 40;
    /** >= MAX_ROWS_PER_WINDOW * worst-case row (~1.1k with the four iris columns). If this bound
     *  were tighter than the row cap, the row cap would be dead and the overflow NOTE would name
     *  the limit that did not fire. */
    private static final int MAX_CHARS = 46_000;
    private static final int BURST_FRAMES = 8;   // captured after any class change
    private static final int FIRST_N_PER_CLASS = 8;   // per-class session escalation
    private static final int WATCHDOG_FRAMES = 600; // ~10 s at 60 fps
    private static final int WATCHDOG_REARM = 1800;

    // ===== session state ======================================================================
    private static boolean disarmed = false;
    private static boolean armLogged = false;
    private static boolean targetLogged = false;
    private static boolean offTargetLogged = false;
    private static boolean aimFaultLogged = false;
    private static long frameSeq = 0L;
    private static long renderedEver = 0L;
    /** Third person AND cross-dim: the actual defect path. The void-leg alarm keys on THIS, never
     *  on renderedEver — a first-person same-dim view-bob crossing must not disarm it. */
    private static long targetRenderedEver = 0L;
    private static long sessionNonWorldFrames = 0L;
    private static int droughtFrames = 0;
    private static int nextWatchdogAt = WATCHDOG_FRAMES;
    private static int windowIndex = 0;
    private static int lastIs0Seen = 0;
    private static int lastClass = -1;
    private static int burst = 0;
    private static int framesStartedWithoutEnd = 0;   // integrity: render threw before TAIL
    private static final int[] sessionSeen = new int[X_NAME.length];

    // ===== window state =======================================================================
    private static long windowStartNanos = 0L;
    private static int windowFrames = 0;
    private static int windowRows = 0;
    private static int windowDropped = 0;
    private static int windowDroppedByRowCap = 0;
    private static int windowDroppedByCharCap = 0;
    private static int windowSkippedNoLevel = 0;
    private static int windowEntered = 0;
    private static final int[] tally = new int[X_NAME.length];
    private static final StringBuilder sb = new StringBuilder(8192);

    // ===== per-frame state (cleared by onFrameStart AND onFrameEnd) ===========================
    private static boolean frameOpen = false;
    private static boolean entered = false;
    private static int enterCount = 0;
    private static int exitCode = -1;
    private static Portal portal = null;               // reference only; string built at capture
    private static ResourceKey<Level> destDim = null;
    private static double headSide = 0.0;
    private static double camSide = 0.0;
    private static boolean sideValid = false;
    private static Vec3 renderCamPos = null;           // non-null == the render bracket was entered
    private static boolean thirdPersonAtRender = false;
    private static String irisPre = null;
    private static String irisDuring = null;           // INSIDE the dest level swap (first wins)
    private static String irisDuringEnd = null;        // last read while still swapped
    private static boolean irisDuringInCrossView = false;
    private static String irisPost = null;
    /** A COUNTER, not a boolean: on a RENDERED frame "YES(x1)" would mean a NESTED renderLevel,
     *  which is a different fact from "the main renderLevel ran". A boolean erases that. */
    private static int renderLevelEnters = 0;
    private static String f1FirstFamily = null;
    private static int f1Fired = 0;
    private static int f1SkippedReentrant = 0;
    private static int invokeD23 = 0;
    private static int invokeFull = 0;
    private static int invokeBase = 0;
    /** Counted SEPARATELY from invokeFull: folding them would make the TP-XDIM fix route
     *  indistinguishable from the pre-existing own-portal-loop branch in exactly the rows that
     *  prove the fix took effect. */
    private static int invokeXview = 0;
    /** XWIN: how many times the cross-view portal pass was entered this frame. >1 would mean a
     *  recursion guard failed, so this is a COUNT and not a boolean on purpose. */
    private static int xwinPasses = 0;
    private static boolean invokeInsideCrossView = false;
    private static boolean insideCrossViewNow = false;
    private static String glState = null;

    // ==========================================================================================
    // ENTRY POINTS — every one opens with the folded lever test; every body is try/catch(disarm)
    // ==========================================================================================

    /** {@code GameRenderer.render} HEAD (the com.warwa GameRendererMixin — ALWAYS woven). */
    public static void onFrameStart() {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            if (frameOpen) {
                // The previous frame started and never reached the TAIL — GameRenderer.render threw
                // or was short-circuited. Without this bracket that frame's per-frame state would
                // silently merge into this one and mis-attribute every witness on the row.
                framesStartedWithoutEnd++;
            }
            resetFrame();
            frameOpen = true;
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** {@code GameRenderer.renderLevel} HEAD (the com.warwa GameRendererMixin — ALWAYS woven). */
    public static void noteRenderLevelEntered() {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            renderLevelEnters++;
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * The Fabric AFTER_TRANSLUCENT_TERRAIN driver(s). {@code which} is a compile-time literal.
     * Counted, not concatenated: this listener re-enters once per full-pipeline dest render, so a
     * string accumulator would be O(N^2) and would bloat the row past the window's char budget.
     */
    public static void noteF1Driver(String which) {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            if (f1FirstFamily == null) {
                f1FirstFamily = which;
            }
            if ("skipped-reentrant".equals(which)) {
                f1SkippedReentrant++;
            }
            else {
                f1Fired++;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** FIRST statement of {@code renderCrossPortalView}. */
    public static void noteEnter() {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            entered = true;
            enterCount++;
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Every simple early return, and the RENDERED return. First writer wins. */
    public static void noteExit(int code) {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            if (exitCode < 0) {
                exitCode = code;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * The COMPOUND precondition guard. The census re-derives which sub-condition tripped, in the
     * guard's own short-circuit order — the production guard is deliberately NOT split into five
     * ifs (zero behavior risk; a drift here can only mis-LABEL a row, never mis-render a frame).
     */
    public static void noteExitPrecondition() {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            int code;
            if (mc == null || mc.player == null) {
                code = X_PRE_PLAYER_NULL;
            }
            else if (mc.level == null) {
                code = X_PRE_LEVEL_NULL;
            }
            else if (mc.player.level() != mc.level) {
                code = X_PRE_PLAYER_LEVEL_MISMATCH;
            }
            else if (mc.getCameraEntity() == null) {
                code = X_PRE_NO_CAMERA_ENTITY;
            }
            else {
                code = X_PRE_NO_ORIGINAL_CAMERA;
            }
            noteExit(code);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * After the portal hit resolves. Stores REFERENCES and PRIMITIVES only — no string and no Vec3
     * is allocated on the hot path (the signed distances are computed componentwise).
     *
     * <p>{@code headPos} and {@code realCamPos} are BOTH in SOURCE space here, which is the only
     * space in which a signed distance to the SOURCE portal plane means anything (the
     * {@code renderingCameraPos} derived later is already dest-transformed — measuring the side
     * from it would have produced a confident, meaningless number).
     */
    public static void notePortalHit(Portal p, Vec3 headPos, Vec3 realCamPos) {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            portal = p;
            destDim = (p == null ? null : p.getDestDim());
            if (p != null && headPos != null && realCamPos != null) {
                Vec3 n = p.getNormal();
                Vec3 o = p.getOriginPos();
                headSide = n.x * (headPos.x - o.x) + n.y * (headPos.y - o.y)
                    + n.z * (headPos.z - o.z);
                camSide = n.x * (realCamPos.x - o.x) + n.y * (realCamPos.y - o.y)
                    + n.z * (realCamPos.z - o.z);
                sideValid = true;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Immediately before {@code switchToCorrectRenderer} — the render bracket is being entered. */
    public static void notePreRender(Vec3 renderingCameraPos, boolean thirdPerson) {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            renderCamPos = renderingCameraPos;   // non-null == the render bracket WAS entered
            thirdPersonAtRender = thirdPerson;
            insideCrossViewNow = true;
            irisPre = describeIris();
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * LAST statement of the cross-view {@code finally}. {@code irisPost} is captured here even when
     * the cross-view render throws — but note that an UNCAUGHT throw also escapes
     * {@code GameRenderer.render}, so the TAIL row-builder never runs and this frame emits NO ROW
     * AT ALL (and the up-to-1 s of rows already buffered are lost with it). This capture becomes
     * visible only on the normal, non-throwing path.
     */
    public static void notePostRender() {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            insideCrossViewNow = false;
            irisPost = describeIris();
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * Sampled INSIDE the dest world swap — immediately after {@code client.level = newWorld} in both
     * {@code MyGameRenderer} drivers. This is the ONLY window in which the dest render's iris state
     * is observable: {@code irisPre}/{@code irisPost}/{@code irisNow} are all taken while
     * {@code client.level} is the SOURCE and therefore cannot disagree about the dimension no matter
     * what the dest render did ({@code ActSeedProbe:354-360} records this exact trap).
     *
     * <p>Deliberately NOT gated on the cross-view bracket: a normal cross-dim portal frame is a
     * frame where a dimension change is KNOWN to happen, so its row is the control that proves this
     * probe can see one at all. The {@code inCrossView=} label separates the two populations.
     * First dest render of the frame wins, so a many-portal frame pays one sample, not k.
     */
    public static void noteDestPipeline() {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            if (irisDuring == null) {
                irisDuring = describeIris();
                irisDuringInCrossView = insideCrossViewNow;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * The LAST read taken while {@code client.level} is still the DEST (the swap's restore block,
     * before the level is put back) — answers whether the dest render itself ended up on a
     * dest-keyed pipeline, as opposed to rasterizing the dest world through the source pipeline.
     */
    public static void noteDestPipelineEnd() {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            irisDuringEnd = describeIris();
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * XWIN: the cross-view portal pass (the full-pipeline core's Step-10.10 twin) was entered.
     * Counted, not flagged — more than one per frame means a recursion guard failed, and that is
     * the single cheapest tell for the whole mechanism.
     */
    public static void noteXWinPass() {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            xwinPasses++;
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * @param kind 0 = the IrisCompat D23 layer-0 decomposed fallback, 1 = the IrisCompat
     *             full-pipeline branch, 2 = the base {@code PortalRenderer} decomposed driver
     *             (the stencil family's path, live shaders-OFF), 3 = the CROSS-VIEW layer-0
     *             full-pipeline route (the TP-XDIM fix). The dormant iris renderers held in
     *             the tree are NOT in {@code switchToCorrectRenderer}'s reachable set and are
     *             therefore not instrumented; if one is ever routed to, its dest renders would show
     *             up here as {@code BASE-DECOMPOSED} or {@code NOT-CALLED}.
     */
    public static void noteInvokeWorldRendering(int kind) {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            if (kind == 0) {
                invokeD23++;
            }
            else if (kind == 1) {
                invokeFull++;
            }
            // NOT a raw else: an unknown kind falling into invokeBase would print the fix route as
            // BASE-DECOMPOSED, i.e. "the route was never taken" — the exact reading the fix leg
            // exists to test.
            else if (kind == 2) {
                invokeBase++;
            }
            else {
                invokeXview++;
            }
            if (insideCrossViewNow) {
                invokeInsideCrossView = true;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // ==========================================================================================
    // THE FRAME BOUNDARY — GameRenderer.render TAIL (the com.warwa GameRendererMixin, always woven)
    // ==========================================================================================

    public static void onFrameEnd() {
        if (!IPGlobal.TP_XDIM_CENSUS_LEVER || disarmed) {
            return;
        }
        try {
            armOnce();

            // A COUNTER delta, not a clearable flag: the anchor increments and this reads the
            // difference, so a throw between the two can never strand a "cleared" state and print
            // a false NO.
            int a = IPGlobal.getTpXdimCensusIs0AnchorFrames();
            int is0Delta = a - lastIs0Seen;
            lastIs0Seen = a;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) {
                // Title/loading screens must not dilute the denominator. The window must still OPEN
                // here: otherwise the whole pre-first-join period accumulates with
                // windowStartNanos==0 (maybeFlush hard-returns on that) and is then printed inside
                // the FIRST in-world window, as if minutes of menu frames had occurred in 1001 ms.
                if (windowStartNanos == 0L) {
                    windowStartNanos = System.nanoTime();
                }
                windowSkippedNoLevel++;
                sessionNonWorldFrames++;
                resetFrame();
                maybeFlush();
                return;
            }
            if (windowStartNanos == 0L) {
                windowStartNanos = System.nanoTime();
            }

            if (IPGlobal.TP_XDIM_CENSUS_GL_LEVER) {
                glState = sampleGl();
            }

            frameSeq++;
            windowFrames++;
            if (entered) {
                windowEntered++;
            }

            int cls = !entered ? X_NOT_ENTERED : (exitCode < 0 ? X_ENTERED_NO_EXIT : exitCode);
            tally[cls]++;
            sessionSeen[cls]++;

            if (cls == X_RENDERED) {
                renderedEver++;
                // "A cross-portal view rendered" is NOT "the defect path rendered": view bobbing
                // carries the camera through a SAME-DIM portal in FIRST person too. Keying the
                // void-leg alarm on the generic predicate would let an incidental bob crossing
                // permanently disarm the only line that says this leg measured nothing.
                boolean onTarget = thirdPersonAtRender && destDim != null
                    && !destDim.equals(mc.level.dimension());
                if (onTarget) {
                    targetRenderedEver++;
                    droughtFrames = 0;
                    targetAcquiredOnce(mc);
                }
                else {
                    droughtFrames++;
                    offTargetRenderOnce(mc);
                }
            }
            else {
                droughtFrames++;
            }

            boolean changed = (cls != lastClass);
            if (changed) {
                burst = BURST_FRAMES;
            }
            boolean integrity = (cls == X_ENTERED_NO_EXIT) || enterCount > 1
                || framesStartedWithoutEnd > 0;
            // The transition rule is what catches the crossing (the crossing IS the transition);
            // the per-class first-8 guarantees a rare class is never crowded out by thousands of
            // NO_PORTAL_HIT frames; integrity events are never throttled.
            boolean capture = changed || burst > 0 || windowRows == 0 || integrity
                || sessionSeen[cls] <= FIRST_N_PER_CLASS;
            if (burst > 0) {
                burst--;
            }
            lastClass = cls;

            if (capture) {
                captureRow(mc, cls, is0Delta);
            }

            watchdog();
            resetFrame();
            maybeFlush();
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // ===== row + window emission ==============================================================

    private static void captureRow(Minecraft mc, int cls, int is0Delta) {
        if (windowRows >= MAX_ROWS_PER_WINDOW) {
            windowDropped++;
            windowDroppedByRowCap++;
            return;
        }
        if (sb.length() >= MAX_CHARS) {
            windowDropped++;
            windowDroppedByCharCap++;
            return;
        }
        windowRows++;
        sb.append("\n  #").append(windowRows)
            .append(" f=").append(frameSeq)
            .append(" t=+").append((System.nanoTime() - windowStartNanos) / 1_000_000L).append("ms")
            .append(" | class=").append(X_NAME[cls])
            .append(" | enter=").append(entered ? "YES" : "NO")
            .append(enterCount > 1 ? ("(x" + enterCount + " ANOMALY)") : "")
            .append(" ret=").append(cls == X_RENDERED ? "true"
                : (entered ? (cls == X_ENTERED_NO_EXIT ? "THREW?" : "false") : "N-A(not entered)"))
            .append(" reachedBracket=").append(renderCamPos == null ? "NO" : "YES")
            .append(" | cam=").append(cameraType(mc))
            .append(" thirdPerson=").append(cls == X_RENDERED
                ? String.valueOf(thirdPersonAtRender) : thirdPersonNow(mc))
            .append(" | dims mc=").append(dim(mc.level))
            .append(" player=").append(mc.player == null ? "NO-PLAYER" : dim(mc.player.level()))
            .append(" camEnt=").append(camEntDim(mc))
            .append(" dest=").append(destDim == null ? "N-A(no portal hit)" : key(destDim))
            .append(" sameDim=").append(destDim == null ? "N-A"
                : (destDim.equals(mc.level.dimension()) ? "YES" : "NO"))
            .append(" | portal=").append(portalStr())
            .append(" headSide=").append(sideValid ? fmt(headSide) : "N-A(no portal hit)")
            .append(" camSide=").append(sideValid ? fmt(camSide) : "N-A(no portal hit)")
            .append(" cameraPastAperture=").append(pastAperture())
            .append(" renderCamPos=").append(renderCamPos == null
                ? "N-A(returned before the render bracket)" : vec(renderCamPos))
            // --- AIM VALIDATION / weave proof (see the class javadoc: a NO here is entailed on a
            // --- RENDERED frame, so it confirms aim; it is not evidence for the hypothesis).
            .append(" | renderLevel=").append(renderLevelEnters == 0 ? "NO"
                : ("YES(x" + renderLevelEnters + ")"))
            .append(" is0=").append(is0Delta > 0 ? ("YES(x" + is0Delta + ")")
                : ("NO(sessionAnchorFrames=" + lastIs0Seen + ")"))
            .append(" f1=").append(f1FirstFamily == null ? "NO"
                : ("YES:" + f1FirstFamily + "(fired=" + f1Fired
                    + " reentrantSkips=" + f1SkippedReentrant + ")"))
            // --- THE MEASUREMENT
            .append(" | invoke=").append(invokeStr())
            .append(" xwin=").append(xwinPasses == 0 ? "NO"
                : (xwinPasses == 1 ? "YES" : ("YES(x" + xwinPasses + " RECURSION-ANOMALY)")))
            .append(" | renderer=").append(rendererName())
            .append(" swActive=").append(shaderpackViewsActive())
            .append(" shaders=").append(shadersOn())
            .append(" portalLayerAtEnd=").append(portalLayer())
            // THE RECURSION-DEPTH MEASUREMENT. portalLayerAtEnd above is a LEAK detector and reads 0
            // on every healthy frame; this is the field that answers "how deep did we actually go".
            .append(" maxPortalDepth=").append(maxPortalDepth())
            .append(" deferredPeak=").append(deferredPeak())
            .append(" bobbedProj=").append(
                RenderStates.capturedMainPassBobbedProjection == null ? "NULLED" : "PRESENT")
            .append(" | irisPre=").append(irisPre == null ? "N-A(no cross-view render)" : irisPre)
            .append(" irisDuring=").append(irisDuring == null
                ? "N-A(no dest world swap this frame)"
                : (irisDuring + "(inCrossView=" + (irisDuringInCrossView ? "yes" : "no") + ")"))
            .append(" irisDuringEnd=").append(irisDuringEnd == null
                ? "N-A(no dest world swap this frame)" : irisDuringEnd)
            .append(" irisPost=").append(irisPost == null ? "N-A(no cross-view render)" : irisPost)
            .append(" irisNow=").append(describeIris())
            .append(" | gl=").append(glState == null
                ? "<not sampled: -PtpXdimCensusGl off>" : glState);
        if (framesStartedWithoutEnd > 0) {
            sb.append("\n     ^^ INTEGRITY: ").append(framesStartedWithoutEnd)
                .append(" earlier frame(s) started and never reached GameRenderer.render TAIL"
                    + " (render threw or was short-circuited). Their rows are LOST, not merged —"
                    + " the tally's denominator is short by that many.");
            framesStartedWithoutEnd = 0;
        }
    }

    private static void maybeFlush() {
        long now = System.nanoTime();
        if (windowStartNanos == 0L || now - windowStartNanos < WINDOW_NS) {
            return;
        }
        flush(now);
    }

    private static void flush(long now) {
        // A window with NO in-world frame has nothing to report, and emitting it would print a
        // header + env + an 11-entry zero tally once per second forever while the client sits on
        // the title screen. captureRow runs strictly after windowFrames++, so sb cannot be
        // non-empty here — this return can never discard a buffered row.
        if (windowFrames == 0) {
            resetWindow(now);
            return;
        }
        windowIndex++;
        StringBuilder out = new StringBuilder(sb.length() + 1024);
        out.append('\n').append(P).append("window #").append(windowIndex)
            .append(" — ").append(windowFrames).append(" in-world frames in ")
            .append((now - windowStartNanos) / 1_000_000L).append(" ms")
            .append(" (through f=").append(frameSeq).append(')')
            .append(", rowsEmitted=").append(windowRows)
            .append(" rowsDropped=").append(windowDropped)
            .append(" nonWorldFramesSkipped=").append(windowSkippedNoLevel)
            .append(" (sessionTotal=").append(sessionNonWorldFrames).append(')')
            .append(" | crossViewEnteredOn=").append(windowEntered).append('/').append(windowFrames)
            .append(" | RENDERED-so-far-this-session=").append(renderedEver)
            .append(" (onTarget-3p-xdim=").append(targetRenderedEver).append(')');
        out.append("\n  env: entityPortalsFlag=")
            .append(entityPortalsFlag())
            .append(" enableCrossPortalView=").append(IPGlobal.enableCrossPortalView)
            .append(" renderer=").append(rendererName())
            .append(" invoker=").append(invokerName())
            .append(" irisPresent=").append(irisPresent())
            .append(" shaders=").append(shadersOn())
            .append(" pack=").append(packName())
            .append(" glLever=").append(IPGlobal.TP_XDIM_CENSUS_GL_LEVER)
            // The fix route's running total, live per window — the RUN CONFIG block only ever
            // snapshots it once, near session start, so it is near-zero there by construction.
            .append(" xviewFullPipelineTotal=").append(IPGlobal.crossViewFullPipelineCount)
            .append(" xwinPassTotal=").append(IPGlobal.getCrossViewReverseWindowPasses())
            .append(" xwinLever=").append(
                IPGlobal.CROSS_VIEW_REVERSE_WINDOW_DISABLED_LEVER ? "DISABLED(no-window repro)" : "ON")
            .append(" xviewRouteLever=").append(
                IPGlobal.CROSS_VIEW_FULL_PIPELINE_DISABLED_LEVER ? "DISABLED(pre-fix route)" : "ON");
        out.append("\n  tally:");
        for (int i = 0; i < X_NAME.length; i++) {
            out.append(' ').append(X_NAME[i]).append('=').append(tally[i]);
        }
        if (windowDropped > 0) {
            out.append("\n  NOTE: ").append(windowDropped).append(" frame(s) matched the capture"
                    + " rule but were NOT emitted as rows (rowCap=").append(windowDroppedByRowCap)
                .append(" charCap=").append(windowDroppedByCharCap)
                .append("). They ARE counted in the tally above. Nothing was silently dropped.");
        }
        out.append(sb);
        LOGGER.info(out.toString());   // <-- exactly ONE log4j call per window
        aimFault();
        resetWindow(now);
    }

    private static void resetWindow(long now) {
        sb.setLength(0);
        windowStartNanos = now;
        windowFrames = 0;
        windowRows = 0;
        windowDropped = 0;
        windowDroppedByRowCap = 0;
        windowDroppedByCharCap = 0;
        windowSkippedNoLevel = 0;
        windowEntered = 0;
        Arrays.fill(tally, 0);
    }

    private static void resetFrame() {
        frameOpen = false;
        entered = false;
        enterCount = 0;
        exitCode = -1;
        portal = null;
        destDim = null;
        headSide = 0.0;
        camSide = 0.0;
        sideValid = false;
        renderCamPos = null;
        thirdPersonAtRender = false;
        irisPre = null;
        irisDuring = null;
        irisDuringEnd = null;
        irisDuringInCrossView = false;
        irisPost = null;
        glState = null;
        renderLevelEnters = 0;
        f1FirstFamily = null;
        f1Fired = 0;
        f1SkippedReentrant = 0;
        invokeD23 = 0;
        invokeFull = 0;
        invokeBase = 0;
        invokeXview = 0;
        xwinPasses = 0;
        invokeInsideCrossView = false;
        // A throw between notePreRender and notePostRender must not leak into the next frame.
        insideCrossViewNow = false;
    }

    // ===== the self-proving one-shots =========================================================

    private static void armOnce() {
        if (armLogged) {
            return;
        }
        armLogged = true;
        LOGGER.info(P + "ARMED — lever seamlessportals.tpXdimCensus=true"
            + " (IPGlobal.TP_XDIM_CENSUS_LEVER). Classifying EVERY in-world frame; one batched log"
            + " call per ~1 s window. IF YOU NEVER SEE A \"TARGET ACQUIRED\" LINE BELOW, THIS RUN"
            + " MEASURED NOTHING ABOUT THE THIRD-PERSON CROSS-DIMENSION DEFECT AND THE LEG IS VOID.");
    }

    private static void targetAcquiredOnce(Minecraft mc) {
        if (targetLogged) {
            return;
        }
        targetLogged = true;
        LOGGER.info(P + "TARGET ACQUIRED at f=" + frameSeq
            + " — renderCrossPortalView() returned TRUE on a THIRD-PERSON CROSS-DIM frame: it"
            + " rendered this frame itself and vanilla renderLevel was skipped. dest="
            + (destDim == null ? "UNREADABLE" : key(destDim))
            + " thirdPerson=" + thirdPersonAtRender
            + " shaders=" + shadersOn()
            + " renderLevelEntersThisFrame=" + renderLevelEnters
            + ". The census is AIMED CORRECTLY — every row from here is about the real defect path.");
    }

    private static void offTargetRenderOnce(Minecraft mc) {
        if (offTargetLogged) {
            return;
        }
        offTargetLogged = true;
        LOGGER.info(P + "CROSS-VIEW RENDERED at f=" + frameSeq + " but NOT on the defect path"
            + " (thirdPerson=" + thirdPersonAtRender
            + " dest=" + (destDim == null ? "NO-PORTAL-HIT" : key(destDim))
            + " sameDim=" + (destDim == null ? "N-A"
                : (destDim.equals(mc.level.dimension()) ? "YES" : "NO"))
            + "). This is a view-bob and/or same-dim cross view. It does NOT count as TARGET"
            + " ACQUIRED and does NOT silence the VOID-LEG watchdog.");
    }

    private static void watchdog() {
        if (targetRenderedEver > 0 || droughtFrames < nextWatchdogAt) {
            return;
        }
        nextWatchdogAt = droughtFrames + WATCHDOG_REARM;
        LOGGER.warn(P + "WATCHDOG: " + droughtFrames + " consecutive IN-WORLD frames and"
            + " renderCrossPortalView() has NEVER rendered a THIRD-PERSON CROSS-DIM frame (total"
            + " cross-view renders this session, including first-person/same-dim bob crossings: "
            + renderedEver + "). If this leg was supposed to measure the third-person"
            + " cross-dimension corruption, IT MEASURED NOTHING — THE LEG IS VOID."
            + " Read the rows: enter=NO everywhere => the @WrapOperation is not woven (check"
            + " entityPortalsFlag in the env line); enter=YES with class=NO_PORTAL_HIT => the segment"
            + " from your head to the F5 camera is not crossing a portal — back further through it;"
            + " class=RENDERED with sameDim=YES => that is a same-dim portal, not the defect path.");
    }

    private static void aimFault() {
        if (aimFaultLogged || windowFrames == 0 || windowEntered > 0) {
            return;
        }
        aimFaultLogged = true;
        LOGGER.warn(P + "AIM FAULT (one-shot): a full window of in-world frames entered"
            + " renderCrossPortalView() ZERO times. The @WrapOperation on"
            + " GameRenderer.render->renderLevel did not run: seamlessportals-ip-client.mixins.json"
            + " is weave-gated on the entity-portals flag (SeamlessMixinConfigPlugin.shouldApplyMixin)."
            + " Until this stops, the census is pointed at nothing and NO conclusion may be drawn.");
    }

    // ===== formatting helpers — LOUD sentinels only ===========================================

    private static String describeIris() {
        try {
            String s = IrisInterface.invoker.describePipelineAndDim();
            if (s == null) {
                return "NO-IRIS-FACADE(invoker=" + invokerName() + ")";
            }
            return s;
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Two pure GL STATE QUERIES. No readback, no barrier, no fence, no glGetError, never inside a
     * pass — sampled once per frame at the census frame boundary, behind its own opt-in lever.
     * On-lead because the cross-view path explicitly disables GL_STENCIL_TEST in its finally
     * precisely because leaking it corrupts the GUI pass. NOTE the weak-instrument caveat: on a
     * cross-view frame that disable has already run by the time this samples, so stencilTest=false
     * there is near-certain and says little.
     */
    private static String sampleGl() {
        try {
            return "stencilTest=" + org.lwjgl.opengl.GL11.glIsEnabled(
                org.lwjgl.opengl.GL11.GL_STENCIL_TEST)
                + " scissorTest=" + org.lwjgl.opengl.GL11.glIsEnabled(
                org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String pastAperture() {
        if (!sideValid) {
            return "N-A(no portal hit)";
        }
        if (headSide > 0 && camSide <= 0) {
            return "YES";
        }
        if (headSide <= 0 && camSide > 0) {
            return "YES(inverted-normal)";
        }
        return "NO";
    }

    private static String invokeStr() {
        if (invokeD23 == 0 && invokeFull == 0 && invokeBase == 0 && invokeXview == 0) {
            return "NOT-CALLED";
        }
        StringBuilder b = new StringBuilder(64);
        if (invokeXview > 0) {
            b.append("XVIEW-FULL x").append(invokeXview).append(' ');
        }
        if (invokeD23 > 0) {
            b.append("D23-FALLBACK x").append(invokeD23).append(' ');
        }
        if (invokeFull > 0) {
            b.append("FULL-PIPELINE x").append(invokeFull).append(' ');
        }
        if (invokeBase > 0) {
            b.append("BASE-DECOMPOSED x").append(invokeBase).append(' ');
        }
        b.append(invokeInsideCrossView ? "(some-in-crossview)" : "(none-in-crossview)");
        return b.toString();
    }

    private static String portalStr() {
        Portal p = portal;
        if (p == null) {
            return "N-A(no portal hit)";
        }
        try {
            return p.getClass().getSimpleName() + "#" + p.getId()
                + " src=" + dim(p.level()) + " dst=" + key(p.getDestDim())
                + " @" + vec(p.position());
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String camEntDim(Minecraft mc) {
        try {
            Entity e = mc.getCameraEntity();
            return e == null ? "NO-CAMERA-ENTITY" : dim(e.level());
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String cameraType(Minecraft mc) {
        try {
            return mc.options == null ? "UNREADABLE(no-options)"
                : mc.options.getCameraType().name();
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String thirdPersonNow(Minecraft mc) {
        try {
            return mc.options == null ? "UNREADABLE(no-options)"
                : String.valueOf(!mc.options.getCameraType().isFirstPerson());
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String rendererName() {
        try {
            Object r = IPCGlobal.renderer;
            return r == null ? "NULL-RENDERER" : r.getClass().getSimpleName();
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String invokerName() {
        try {
            return IrisInterface.invoker.getClass().getSimpleName();
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String shadersOn() {
        try {
            return IrisInterface.invoker.isShaders() ? "ON" : "OFF";
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String irisPresent() {
        try {
            return String.valueOf(IrisInterface.invoker.isIrisPresent());
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String shaderpackViewsActive() {
        try {
            return String.valueOf(IPGlobal.isShaderpackPortalViewsActive(
                IrisInterface.invoker.isShaders()));
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String entityPortalsFlag() {
        try {
            return String.valueOf(
                com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals());
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * The layer stack size at the census sample point — {@code GameRenderer.render} TAIL.
     *
     * <p><b>THIS IS A LEAK DETECTOR, NOT A DEPTH GAUGE — read the name literally.</b> Every
     * {@code pushPortalLayer} in the mod is matched by a {@code popPortalLayer} in a {@code finally}
     * (IrisCompatOn262Renderer.doRenderPortal ~:398-405, RendererUsingStencil likewise), and this is
     * sampled after the whole frame has unwound, so on ANY healthy frame it reads <b>0</b> — shaders
     * ON and shaders OFF alike, one layer or five. A non-zero value here means a layer was STRANDED
     * (the silent-portal-death mode doRenderPortal's finally exists to prevent), which is worth
     * knowing but is a different question.
     *
     * <p>For "how deep did this frame actually recurse", use {@link #maxPortalDepth()} below. This
     * distinction was found the expensive way: the RECURSION_SHADERS_ON handoff instructed taking a
     * baseline as "portalLayerAtEnd should read 1 shaders-ON and >1 shaders-OFF", which this
     * instrument can never print. Ask of every probe what input would make it print the guilty
     * answer.
     */
    private static String portalLayer() {
        try {
            return Integer.toString(PortalRendering.getPortalLayer());
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * THE ACTUAL RECURSION-DEPTH GAUGE: the deepest layer stack any dest render reached this frame.
     *
     * <p>{@code PortalRendering.onBeginPortalWorldRendering} (PortalRendering.java:94-98) appends a
     * snapshot of the CURRENT layer stack to {@code RenderStates.portalRenderInfos} on every dest
     * render, and {@code doRenderPortal} pushes the portal BEFORE calling {@code renderPortalContent}
     * — so each entry's size IS the depth that render ran at (layer-1 dest render => size 1, a portal
     * inside a portal => size 2). {@code renderPortalContent} early-returns on
     * {@code getPortalLayer() > getMaxPortalLayer()} BEFORE recording, so this counts only depths
     * that actually RENDERED, which is exactly the number the recursion work has to move.
     *
     * <p>Live at this sample point: {@code RenderStates.updatePreRenderInfo} resets the list PRE-render
     * (MinecraftFramePumpMixin:90), so at {@code GameRenderer.render} TAIL it still holds this frame's
     * records. CAVEAT, stated rather than hidden: that reset is SKIPPED on null-level and mid-packet
     * player/level-mismatch frames (MinecraftFramePumpMixin:67/81), so across such a frame the list can
     * carry over and this reads the max of the merged pair. Those frames are transient and the census
     * classifies them independently, so a carried-over max cannot manufacture depth that never rendered
     * — it can only attribute real depth to the adjacent frame.
     *
     * <p>Emitted as {@code maxPortalDepth=N(destRenders=M)}. Expected baseline: shaders-ON N=1,
     * shaders-OFF N>1 in front of nested portals. If shaders-OFF also reads 1, the premise that
     * shaders-OFF already recurses is wrong and the whole engagement needs re-scoping.
     */
    private static String maxPortalDepth() {
        try {
            // Only the STACK DEPTH of each entry is read, never the portals — a wildcard keeps this
            // decoupled from the WeakReference element type (and cannot resurrect a cleared referent).
            java.util.List<? extends java.util.List<?>> infos = RenderStates.portalRenderInfos;
            if (infos == null || infos.isEmpty()) {
                return "0(destRenders=0)";
            }
            int max = 0;
            for (java.util.List<?> e : infos) {
                if (e != null && e.size() > max) {
                    max = e.size();
                }
            }
            return max + "(destRenders=" + infos.size() + ")";
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * IS5-REC: the highest deferred-buffer LAYER index the iris compat renderer has materialised
     * this session. The witness for the Stage-2 refactor's inertness claim (per-layer array must
     * still allocate exactly one buffer until recursion is armed) and, from Stage 3 on, the gauge
     * that says the nested pass really used its OWN snapshot rather than clobbering layer 0's.
     *
     * <p>Reads "N-A" for every other renderer. The {@code instanceof} gate is LOAD-BEARING, not
     * defensive: {@code IrisCompatOn262Renderer}'s {@code <clinit>} registers a client-cleanup
     * handler and its own class comment relies on that running "only when the lever first routes
     * here". An unconditional static call from the census would class-initialize it on every
     * census-armed run and perturb sessions that never route to the compat renderer at all.
     * {@code instanceof} does NOT trigger class initialization — the same reasoning
     * {@code PortalRenderer.switchRenderer} already documents for its teardown branch.
     */
    private static String deferredPeak() {
        try {
            if (IPCGlobal.renderer instanceof IrisCompatOn262Renderer) {
                return Integer.toString(IrisCompatOn262Renderer.getDeferredPeak());
            }
            return "N-A(not the compat renderer)";
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String packName() {
        try {
            String n = IrisInterface.invoker.getShaderpackName();
            return n == null ? "NO-PACK" : n;
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String dim(Level l) {
        try {
            return l == null ? "NULL-LEVEL" : key(l.dimension());
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String key(ResourceKey<Level> k) {
        try {
            return k == null ? "NULL-KEY" : k.identifier().toString();
        }
        catch (Throwable t) {
            return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String vec(Vec3 v) {
        try {
            return v == null ? "NULL-VEC"
                : String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
        }
        catch (Throwable t) {
            return "UNREADABLE";
        }
    }

    private static String fmt(double d) {
        try {
            return String.format(Locale.ROOT, "%+.2f", d);
        }
        catch (Throwable t) {
            return "UNREADABLE";
        }
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        try {
            LOGGER.warn(P + "DISARMED after a throw. Diagnostic only — the render path is"
                + " unaffected, but every row after this point is MISSING. Treat this run as"
                + " incomplete.", t);
        }
        catch (Throwable ignored) {
            // never let the census's own failure escape
        }
    }
}
