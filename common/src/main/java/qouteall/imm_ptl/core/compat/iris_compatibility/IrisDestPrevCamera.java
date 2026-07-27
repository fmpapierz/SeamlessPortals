package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IS5-MB — PER-CHAIN PREVIOUS-FRAME CAMERA CORRECTION for the same-dim portal-window motion-blur
 * smear. DEFAULT ON; A/B off via {@code -Dseamlessportals.disableIrisDestPrevCamera}.
 *
 * <h2>THE MEASURED DEFECT (IS5-CEN census, 2026-07-26, two runs, 22 consecutive stationary seconds)</h2>
 * With the pack's Motion Blur on and a SAME-DIM portal in view there are exactly <b>two</b>
 * {@code composite4} binds per frame, both on the <b>same</b> program id — same-dim source and
 * destination share a dimension, so iris hands them ONE pipeline and therefore ONE
 * {@code CameraPositionTracker}:
 * <pre>
 *   bind[1]  cam=(-2.473,10.370,500.031)  prev=(13.027,102.370,-2.469)  |cam-prev| = 511.088
 *            idMV=0.00000 idP=0.00000     BLUR SPAN = 265.8 px      <- the DEST content's chain
 *   bind[2]  cam=(13.027,102.370,-2.469)  prev=(13.027,102.370,-2.469) |cam-prev| =   0.000
 *            idMV=0.00000 idP=0.00000     BLUR SPAN =   0.0 px      <- the MAIN frame's chain
 * </pre>
 * bind[1]'s {@code cameraPosition} is the portal DESTINATION camera and its
 * {@code previousCameraPosition} is the PLAYER's — a constant 511-block offset that saturates the pack's
 * {@code velocity/(1+|velocity|)} clamp, giving a full-strength smear that needs no player motion. That
 * blurred image is what gets stamped into the window; the main chain has velocity 0, so the main view
 * stays sharp. Cross-dim is clean because it runs on its own per-dimension pipeline (a different program
 * id, measured) whose camera pair is self-consistent.
 *
 * <p>{@code idMV = idP = 0.00000} on every steady-state block: the four matrices in composite4's
 * reprojection cancel <b>exactly</b>. The velocity is 100 % {@code cameraOffset}. <b>The previous
 * MATRICES must therefore not be touched</b> — writing them would break a cancellation that is already
 * correct. This class writes {@code previousCameraPosition} and nothing else.
 *
 * <h2>Why the key is the SLOT, not the portal</h2>
 * The previous implementation keyed the history on the {@code Portal} and recorded the dest camera from
 * inside the armed portal bracket. It reported {@code writes=1243} and changed nothing, because
 * <b>the bind inside that bracket is the one carrying the MAIN camera</b> — the dest content's chain
 * runs OUTSIDE it (measured: labelled {@code win=MAIN layer=0} while holding the dest camera). So the
 * map recorded the main camera as if it were the dest camera, the value match then hit the already
 * innocent bind[2] 1243 times, and bind[1] — the 511-block one — was never touched at all.
 *
 * <p>The fix drops portal bookkeeping entirely and identifies a chain by <b>the camera it carries</b>.
 * Every guarded bind's {@code cameraPosition} is remembered for one frame; the next frame, a bind takes
 * its {@code previousCameraPosition} from the <b>nearest</b> camera the same program held last frame,
 * accepted only if it is within one frame's plausible camera travel ({@link #maxDelta()} blocks). See
 * {@link #nearest} for why proximity is the right identifier and why an earlier
 * {@code (programId, bind ordinal)} key was <b>wrong and dangerous</b>.
 * <ul>
 *   <li>player stationary ⇒ the chain's camera is unchanged ⇒ {@code prev == cur} ⇒ velocity 0 ⇒ a
 *       sharp window, which is the whole point;</li>
 *   <li>player moving ⇒ that chain's own true frame-to-frame delta ⇒ correct, real motion blur in the
 *       window, rather than either a smear or a blanket suppression;</li>
 *   <li>no candidate within the limit (a portal just came into view, a teleport, iris re-basing its
 *       position past 30000 blocks) ⇒ NEUTRALIZE ({@code prev := cur} ⇒ velocity 0 ⇒ one blur-free
 *       frame), which can never be worse than the saturating defect. That fallback is also why this
 *       class needs no shift arithmetic at all — the predecessor's entire regime-detect/derive-shift
 *       block is gone.</li>
 * </ul>
 *
 * <h2>Seams</h2>
 * <ul>
 *   <li><b>S1 write</b> — {@code @Inject} at {@code INVOKE Program.use()V shift=AFTER} in
 *       {@code CompositeRenderer.renderAll} (offset 422): the program is bound and iris has finished
 *       every upload for it, so the write cannot be clobbered by {@code update()}.</li>
 *   <li><b>S4 restore</b> — {@code @Inject} at {@code INVOKE BlendModeOverride.restore()V} (offset 458),
 *       just past the draw at 455. Straight-line code, so write and restore pair 1:1.</li>
 *   <li><b>frame boundary</b> — {@code GameRenderer.render} TAIL promotes this frame's slot cameras to
 *       "last frame". That anchor fires exactly once per rendered frame;
 *       {@code RenderStates.frameIndex} deliberately does NOT (the frame pump skips it on mid-packet
 *       mismatch frames), and using it would silently merge two frames' slots.</li>
 * </ul>
 *
 * <p>The RESTORE is mandatory rather than housekeeping: {@code Vector3Uniform.updateValue} early-returns
 * when its Java-side cache is unchanged, so with a stationary player iris would never overwrite our
 * write and the next frame's main view would inherit it.
 *
 * <p><b>Verifying it live:</b> {@code -PcompositeCensus=true} prints a POST-WRITE line per bind, sampled
 * between the write and the draw, which is the state the fragment shader actually executes with. A
 * working correction shows {@code |cam-prev|} 511 → 0 and the blur span collapsing to ~0 on bind[1].
 */
public final class IrisDestPrevCamera {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-MB ";

    private IrisDestPrevCamera() {
    }

    // =============================================================================================
    // State
    // =============================================================================================

    /** The write pending a restore at S4. Cleared FIRST on consumption so a throw cannot wedge it. */
    private static final class Pending {
        int locPrevCam;
        final float[] savePrevCam = new float[4];
        boolean probeOnly;
    }

    /** programId -> every cameraPosition that program's guarded binds held LAST frame. */
    private static Map<Integer, List<float[]>> camsPrev = new HashMap<>();
    /** programId -> the cameras seen THIS frame. Becomes {@link #camsPrev} at the frame boundary. */
    private static Map<Integer, List<float[]>> camsCur = new HashMap<>();

    private static Pending pending = null;
    private static boolean broken = false;
    private static boolean restoreSeamProven = false;
    private static boolean liveLogged = false;
    private static boolean rosterLogged = false;
    private static final Set<String> warnedOnce = new HashSet<>();

    private static boolean reflectionAttempted = false;
    private static volatile boolean reflectionReady = false;
    private static Field fPasses;
    private static Field fPassName;
    private static Field fPassProgram;

    /** One-entry location cache keyed by program id — a pack reload / resize mints a new id. */
    private static int locCachePid = -1;
    private static int locCam = -1;
    private static int locPrevCam = -1;
    private static int locPrevMv = -1;
    private static int locPrevProj = -1;

    private static final List<String> TARGET_PASSES = new ArrayList<>();

    static {
        for (String s : IPGlobal.IRIS_DEST_PREV_PASSES.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                TARGET_PASSES.add(t);
            }
        }
    }

    private static long lastProbeNanos = 0L;
    private static final float[] TMP3 = new float[4];

    private static double maxDelta() {
        return IPGlobal.IRIS_DEST_PREV_MAX_DELTA;
    }

    // =============================================================================================
    // Bracket hooks — now DIAGNOSTIC ONLY
    // =============================================================================================

    /**
     * Kept because {@code IrisCompatOn262Renderer} calls it, but the correction no longer uses the
     * portal bracket for anything: the census measured that the smearing chain runs OUTSIDE it, which
     * is exactly why the bracket-keyed predecessor never fired on the guilty bind.
     */
    public static void arm(@Nullable Portal portal) {
        // intentionally empty — slot keying needs no portal context
    }

    /** Kept for the same reason as {@link #arm}. Emits the 1 Hz probe counters when levered on. */
    public static void disarmAndReport() {
        if (broken) {
            return;
        }
        try {
            maybeProbe();
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // =============================================================================================
    // Frame boundary — promote this frame's slot cameras to "last frame"
    // =============================================================================================

    /** Called from {@code GameRenderer.render} TAIL. Swap, never copy. */
    public static void onFrameEnd() {
        if (broken) {
            return;
        }
        try {
            Map<Integer, List<float[]>> completed = camsCur;
            camsCur = camsPrev;
            camsCur.clear();
            camsPrev = completed;
            // A write left outstanding here means the paired restore never fired. It CANNOT be repaired
            // from this anchor: glUniform3f writes to the currently bound program, and renderAll ends
            // with _glUseProgram(0) with the entire GUI drawn since — the call would go nowhere (or to
            // program 0) and silently leave iris's uniform holding our value. Since iris's Java-side
            // cache still holds ITS value, Vector3Uniform.updateValue will early-return and never
            // re-upload, so the corruption would persist. Disarm instead: loud, honest, and bounded.
            // Reachability: 419→455→458 is straight-line, so this needs a mid-frame throw (which
            // already disarms) or the require=0 restore mixin failing to apply — and that case is
            // caught first by the probeOnly latch, which never lets a single write happen.
            Pending q = pending;
            pending = null;
            if (q != null && !q.probeOnly) {
                broken = true;
                warnOnce("leak", P + "DISARMED: a previousCameraPosition write reached the frame"
                    + " boundary without its paired restore. It cannot be undone from here (no program"
                    + " is bound), so the correction stops for this session rather than leave iris's"
                    + " uniform silently holding a portal-destination camera.", null);
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // =============================================================================================
    // S1 — the write, after Program.use() and therefore after uniforms.update()
    // =============================================================================================

    public static void onPassProgramBound(Object renderer, int i) {
        if (broken || !IPGlobal.isIrisDestPrevCameraActive()) {
            return;
        }
        try {
            if (!ensureReflection()) {
                return;
            }
            Object passesObj = fPasses.get(renderer);
            if (!(passesObj instanceof List<?> passes) || i < 0 || i >= passes.size()) {
                return;
            }
            Object pass = passes.get(i);
            String name = String.valueOf(fPassName.get(pass));
            logRosterOnce(passes);
            if (!TARGET_PASSES.contains(name)) {
                return;
            }
            Object prog = fPassProgram.get(pass);
            if (prog == null) {
                return;
            }
            int pid = ((net.irisshaders.iris.gl.program.Program) prog).getProgramId();
            if (!resolveLocations(pid)) {
                return;
            }

            GL20.glGetUniformfv(pid, locCam, TMP3);

            // Record for next frame FIRST, so an early return below cannot leave a hole in the history.
            camsCur.computeIfAbsent(pid, k -> new ArrayList<>())
                .add(new float[] {TMP3[0], TMP3[1], TMP3[2]});

            Pending q = new Pending();
            q.locPrevCam = locPrevCam;
            GL20.glGetUniformfv(pid, locPrevCam, q.savePrevCam);
            // The FIRST guarded pass of a session writes nothing — it exists only to prove the restore
            // seam fires. If it never does, the feature permanently disarms having provably made zero
            // writes, so the main view is untouched by construction.
            q.probeOnly = !restoreSeamProven;

            // THE MATCH: the NEAREST camera this program held last frame, accepted only if it is within
            // one frame's plausible camera travel. See #nearest for why this replaced ordinal keying.
            float[] history = nearest(camsPrev.get(pid), TMP3, maxDelta());
            if (!q.probeOnly) {
                if (history != null) {
                    GL20.glUniform3f(locPrevCam, history[0], history[1], history[2]);
                    IPGlobal.irisDestPrevWriteCount++;
                }
                else {
                    // Neutralize: prev := cur => velocity 0 => one blur-free frame. Strictly better than
                    // the defect (which saturates), so the fallback can never regress.
                    GL20.glUniform3f(locPrevCam, TMP3[0], TMP3[1], TMP3[2]);
                    IPGlobal.irisDestPrevNeutralizeCount++;
                }
            }
            pending = q;

            if (!liveLogged && !q.probeOnly) {
                liveLogged = true;
                double was = dist(q.savePrevCam, TMP3);
                LOGGER.info(P + "correction ACTIVE (once-only liveness line): pass={}"
                        + " prog={} cam=({},{},{}) iris had prev=({},{},{}) |cam-prev|={} ->"
                        + " wrote prev=({},{},{}) via {}. A large \"|cam-prev|\" here is the smearing"
                        + " chain being corrected; ~0 means this chain was already clean.",
                    name, pid, f(TMP3[0]), f(TMP3[1]), f(TMP3[2]),
                    f(q.savePrevCam[0]), f(q.savePrevCam[1]), f(q.savePrevCam[2]), f(was),
                    f(history != null ? history[0] : TMP3[0]),
                    f(history != null ? history[1] : TMP3[1]),
                    f(history != null ? history[2] : TMP3[2]),
                    history != null ? "nearest-camera match against last frame"
                        : "NEUTRALIZE (no last-frame camera within the match limit)");
            }
        }
        catch (Throwable t) {
            Pending q = pending;
            pending = null;
            try {
                if (q != null) {
                    restoreNow(q);
                }
            }
            catch (Throwable ignored) {
                // best effort
            }
            disarm(t);
        }
    }

    // =============================================================================================
    // S4 — the restore, immediately after the guarded pass's draw
    // =============================================================================================

    public static void onPassDrawn() {
        if (pending == null) {
            return; // byte-inert gate
        }
        Pending q = pending;
        pending = null; // CLEAR FIRST — a throw can never wedge the slot
        try {
            if (q.probeOnly) {
                restoreSeamProven = true;
                infoOnce("proven", P + "restore seam proven (once-only): the paired"
                    + " BlendModeOverride.restore injection fired for a guarded pass. Writes begin on"
                    + " the next bind; zero writes were made before this point.");
                return;
            }
            restoreNow(q);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    private static void restoreNow(Pending q) {
        if (q.probeOnly) {
            return;
        }
        GL20.glUniform3f(q.locPrevCam, q.savePrevCam[0], q.savePrevCam[1], q.savePrevCam[2]);
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    /**
     * THE CHAIN MATCH: of everything this program rendered last frame, which camera is THIS one?
     *
     * <p>Answer: the nearest, provided it is within one frame's plausible camera travel. A chain's
     * camera moves a little between frames; two different chains' cameras are separated by the portal's
     * source→destination offset, which is a fixed property of the portal and does not shrink to zero.
     * So proximity identifies the chain and a tight limit rejects a cross-chain match.
     *
     * <p><b>Why this replaced keying on (programId, bind ordinal).</b> The ordinal is not stable in the
     * way that design assumed. Measured: bind ordinal 1 is the same chain every frame, but the
     * <b>camera it carries flips</b> — the destination camera on frames where a dest render ran, the
     * player's camera on frames where none did (a portal failing {@code testShouldRenderPortal}, which
     * is a per-frame GL occlusion query and genuinely flaps: the census recorded
     * {@code binds per frame: min=1 max=2} inside a single second). Ordinal keying therefore hands slot
     * 1 the destination camera as "history" on the frame it flips back to the player's, and a
     * fixed 16-block guard <b>passes</b> that for any portal whose destination is within 16 blocks —
     * i.e. an ordinary doorway portal. The result would have been a full-screen motion-blur flash on
     * every visibility flap: strictly worse than the bug being fixed, on the most common portal shape
     * there is. Nearest-match is immune, because on that frame the player's camera from last frame is
     * the nearer candidate by exactly the portal offset.
     *
     * <p>Residual, bounded and documented: if two chains' cameras are within the limit of each other —
     * a portal whose destination is a few blocks from its source — the match can pick the wrong one.
     * The error is then bounded by their separation, i.e. a few blocks of blur rather than the 511-block
     * saturation, and it self-heals on the next frame.
     */
    @Nullable
    private static float[] nearest(@Nullable List<float[]> candidates, float[] cam, double limit) {
        if (candidates == null) {
            return null;
        }
        float[] best = null;
        double bestD = Double.MAX_VALUE;
        for (float[] c : candidates) {
            double dx = c[0] - cam[0];
            double dy = c[1] - cam[1];
            double dz = c[2] - cam[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best == null || bestD > limit) {
            if (best != null) {
                IPGlobal.irisDestPrevUnmatchedCount++;
            }
            return null;
        }
        return best;
    }

    private static double dist(float[] a, float[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean resolveLocations(int pid) {
        if (locCachePid != pid) {
            locCachePid = pid;
            locCam = GL20.glGetUniformLocation(pid, "cameraPosition");
            locPrevCam = GL20.glGetUniformLocation(pid, "previousCameraPosition");
            locPrevMv = GL20.glGetUniformLocation(pid, "gbufferPreviousModelView");
            locPrevProj = GL20.glGetUniformLocation(pid, "gbufferPreviousProjection");
        }
        // THE MOTION-BLUR DISCRIMINATOR, and the MB-off byte-identity proof by construction rather than
        // by a flag: with Motion Blur OFF the pack's composite4 references none of the previous-frame
        // trio, so every one of these locations is -1 and not a single GL write is issued.
        // NOTE gbufferModelView/gbufferProjection are deliberately NOT consulted — they are INACTIVE in
        // composite4 even with Motion Blur ON (measured: loc -1), and requiring them was the defect that
        // would have voided the entire census.
        if (locPrevCam < 0 || locPrevMv < 0 || locPrevProj < 0) {
            infoOnce("noloc", P + "idle (once-only): the guarded pass does not declare the full"
                + " previous-frame trio (previousCameraPosition=" + locPrevCam
                + " gbufferPreviousModelView=" + locPrevMv + " gbufferPreviousProjection=" + locPrevProj
                + "; -1 means the GLSL linker stripped it as unused). With the pack's Motion Blur OFF"
                + " all three go inactive together and this is the EXPECTED state — but it is not the"
                + " only cause, so read the locations rather than assuming the toggle.");
            return false;
        }
        if (locCam < 0) {
            infoOnce("nocam", P + "idle (once-only): the guarded pass has no active cameraPosition"
                + " uniform, so no slot camera can be recorded and nothing is written.");
            return false;
        }
        return true;
    }

    private static void logRosterOnce(List<?> passes) {
        if (rosterLogged) {
            return;
        }
        rosterLogged = true;
        StringBuilder sb = new StringBuilder();
        try {
            for (Object p : passes) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(fPassName.get(p));
            }
        }
        catch (Throwable ignored) {
            sb.append("<unreadable>");
        }
        LOGGER.info(P + "first composite chain roster: [{}] — guarding {} (override with"
            + " -Dseamlessportals.irisDestPrevCameraPass)", sb, TARGET_PASSES);
    }

    private static void maybeProbe() {
        if (!IPGlobal.DEST_PREV_CAMERA_PROBE) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastProbeNanos < 1_000_000_000L) {
            return;
        }
        lastProbeNanos = now;
        LOGGER.info("[IS5-MB] writes={} neutralize={} unmatched={} programsTracked={} seamProven={}",
            IPGlobal.irisDestPrevWriteCount, IPGlobal.irisDestPrevNeutralizeCount,
            IPGlobal.irisDestPrevUnmatchedCount, camsPrev.size(), restoreSeamProven);
    }

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
            reflectionReady = true;
            return true;
        }
        catch (Throwable t) {
            broken = true;
            warnOnce("resolve", P + "DISARMED: could not resolve CompositeRenderer.passes /"
                + " Pass.name / Pass.program on this Iris build. The same-dim portal-window motion-blur"
                + " correction is INACTIVE for this session (the window keeps today's behaviour).", t);
            return false;
        }
    }

    /** Registered beside the other iris-compat teardowns; also safe to call repeatedly. */
    public static void teardown() {
        pending = null;
        locCachePid = -1;
        camsPrev.clear();
        camsCur.clear();
    }

    private static String f(double d) {
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
            // never let the feature's own logging escape
        }
    }

    private static void infoOnce(String key, String msg) {
        if (!warnedOnce.add(key)) {
            return;
        }
        try {
            LOGGER.info(msg);
        }
        catch (Throwable ignored) {
            // never let the feature's own logging escape
        }
    }

    private static void disarm(Throwable t) {
        broken = true;
        pending = null;
        warnOnce("throw", P + "DISARMED after a throw — the same-dim portal-window motion-blur"
            + " correction is dead for this session (render otherwise unaffected).", t);
    }
}
