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
 * IS5-MB — PER-CHAIN PREVIOUS-FRAME STATE CORRECTION for the same-dim portal-window motion-blur smear.
 *
 * <p><b>DEFAULT ON — user-confirmed live 2026-07-26</b> ("FINALLY NOT BLURRY"). A/B off via
 * {@code -Dseamlessportals.disableIrisDestPrevCamera}.
 *
 * <p>Three earlier rounds shipped a version that did NOT work, and the history is worth keeping because
 * each failure was a different class: round 1 keyed the history on the portal bracket and so recorded
 * the wrong camera entirely; round 2 keyed it on the bind ordinal, which a panel showed would flash the
 * whole screen on any doorway portal; round 3 keyed it correctly but had its write silently clobbered
 * between the seam and the draw. All three were adjudicated with a census that was itself defective in
 * two ways. What finally worked was moving the write past every other writer AND correcting <b>both</b>
 * blur drivers rather than only the camera.
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
 * <h2>TWO drivers, both corrected</h2>
 * An earlier round of this class concluded from one run's {@code idMV = idP = 0.00000} that the four
 * matrices always cancel and that only the camera needed correcting. <b>That generalised from a single
 * sample and was wrong.</b> A later 56-row census found:
 * <ul>
 *   <li>32 rows with {@code idMV = 0} — all of them with a LARGE camera offset (34–505), blurring
 *       166–273 px. <b>Driver A</b>, the camera pair.</li>
 *   <li>every row whose camera offset was ~zero had {@code idMV} between 0.131 and 0.246 and still
 *       blurred 57–102 px. <b>Driver B</b>, the matrix pair: {@code gbufferPreviousModelView} is not
 *       the inverse of {@code gbufferModelViewInverse}, so the chain does not cancel and velocity is
 *       nonzero per pixel with the camera offset at zero.</li>
 * </ul>
 * So this class writes {@code previousCameraPosition} <b>and</b> the two previous matrices, always from
 * the same record. {@code composite4} holds the INVERSE matrices but not the forward ones (both
 * measured inactive, {@code loc -1}), so the current modelview/projection are reconstructed by
 * inverting the inverses — and the inversion is verified against the identity rather than assumed,
 * because JOML's {@code invert()} returns NaN rather than throwing on a degenerate input.
 * {@code -Dseamlessportals.irisDestPrevCameraNoMatrices} writes the camera half only, isolating A from B.
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
 *   <li><b>S1 write</b> — {@code @Inject} at {@code INVOKE GlStateManager._glBindBuffer(II)V}
 *       {@code shift = AFTER} in {@code CompositeRenderer.renderAll} (offset 447). It was at
 *       {@code Program.use()V shift=AFTER} (422) on the reasoning that iris had finished uploading by
 *       then; <b>live measurement disproved that</b> — the correction wrote
 *       {@code prev=(66.700,75.620,0.254)} and a sample taken just before the draw read
 *       {@code 0.263}, a value we never wrote. Something between 422 and 455 overwrote it every time.
 *       447 sits past all of it with nothing between it and {@code _drawElements} (455), and
 *       {@code _glBindBuffer} is javap-unique in the method so no {@code ordinal} is needed.</li>
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
        int locPrevMv;
        int locPrevProj;
        final float[] savePrevCam = new float[4];
        final float[] savePrevMv = new float[16];
        final float[] savePrevProj = new float[16];
        boolean wroteMatrices;
        boolean probeOnly;
    }

    /**
     * One composite chain's state for one frame: the camera it drew from, plus the CURRENT modelview
     * and projection that go with it.
     *
     * <p>The matrices are stored because the camera pair is only <b>one</b> of the two things that make
     * this pass smear. Measured over 56 census rows: every row whose camera offset was ~zero still had
     * {@code idMV = max|MVprev·MVinv − I|} between 0.13 and 0.25 and a blur span of 57–102 px, while the
     * 32 rows with {@code idMV = 0} were exactly the ones with a large camera offset. Two independent
     * drivers, and correcting only the camera leaves the other one painting the window.
     */
    private static final class ChainState {
        final float[] cam = new float[3];
        final float[] mv = new float[16];
        final float[] proj = new float[16];
        boolean matricesValid;
    }

    /** programId -> every chain state that program's guarded binds held LAST frame. */
    private static Map<Integer, List<ChainState>> camsPrev = new HashMap<>();
    /** programId -> the chain states seen THIS frame. Becomes {@link #camsPrev} at the boundary. */
    private static Map<Integer, List<ChainState>> camsCur = new HashMap<>();

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
    private static int locMvInv = -1;
    private static int locProjInv = -1;

    private static final float[] TMP16 = new float[16];
    private static final org.joml.Matrix4f MAT = new org.joml.Matrix4f();

    private static boolean writeMatrices() {
        return !IPGlobal.IRIS_DEST_PREV_NO_MATRICES;
    }

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

    // ---- last-action record, read back by IS5-CEN so a census row can say what THIS class did ----
    // Without it a POST sample can only show the resulting uniform, which is ambiguous between "the
    // correction wrote the wrong value", "the correction wrote nothing" and "the sample is mispaired".
    private static int laPid = -1;
    private static final float[] laCam = new float[3];
    private static final float[] laWrote = new float[3];
    private static String laHow = "nothing yet";
    private static int laCandidates = 0;
    private static double laMatchDist = -1.0;

    /** Times the INJECTION fired at all — counted before any pass/uniform filtering, so it measures
     *  the mixin resolving rather than the feature finding work to do. That distinction matters: with
     *  the pack's Motion Blur OFF the injection fires constantly and writes nothing, and a watchdog
     *  keyed on writes would call that a dead seam. */
    private static int seamHitCount = 0;
    /** Guarded binds that reached a write or a deliberate skip. */
    private static int seamFireCount = 0;
    private static int framesActive = 0;
    private static boolean seamWatchdogFired = false;

    public static String describeLastAction() {
        if (laPid < 0) {
            return "nothing yet (no guarded bind has been written this session)";
        }
        return "prog=" + laPid + " cam=(" + f(laCam[0]) + "," + f(laCam[1]) + "," + f(laCam[2]) + ")"
            + " -> wrote prev=(" + f(laWrote[0]) + "," + f(laWrote[1]) + "," + f(laWrote[2]) + ")"
            + " via " + laHow + " (candidates last frame=" + laCandidates
            + ", match distance=" + (laMatchDist < 0 ? "n/a" : f(laMatchDist)) + ")";
    }

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
            // INJECTION WATCHDOG. The write seam targets GlStateManager._glBindBuffer from a
            // remap = false mixin with require = 0 — the sibling restore mixin's javadoc rejects
            // exactly that construct as unresolvable under remapped names. It resolves here because
            // 26.2 ships UNOBFUSCATED (the census's own _drawElements hook proves it live), but
            // require = 0 means a future failure would be SILENT: the feature would report itself
            // active and simply never run. This session has lost three live rounds to levers that did
            // not self-report, so the seam reports its own liveness.
            // COUNT ONLY IN-WORLD FRAMES. Counting every frame made this watchdog cry VOID on a run
            // that was working perfectly: it burned its 300 frames on the title and loading screens,
            // where no composite chain runs at all, and fired 22 seconds in while the very same run
            // went on to make 3579 writes. That is the identical defect the IS5-RC watchdog had, and
            // the lesson is the same — a watchdog must count only the frames in which the event it is
            // waiting for is even possible.
            boolean inWorld = net.minecraft.client.Minecraft.getInstance() != null
                && net.minecraft.client.Minecraft.getInstance().level != null;
            if (!inWorld) {
                framesActive = 0;
            }
            else if (IPGlobal.isIrisDestPrevCameraActive() && !seamWatchdogFired
                && ++framesActive > 300) {
                seamWatchdogFired = true;
                if (seamHitCount == 0) {
                    warnOnce("seamdead", P + "THE WRITE SEAM NEVER FIRED in 300 active frames. The"
                        + " feature reports itself ACTIVE but its @Inject on"
                        + " GlStateManager._glBindBuffer did not resolve (require = 0 makes that"
                        + " silent). Nothing has been corrected — treat this run as VOID for IS5-MB.",
                        null);
                }
                else {
                    LOGGER.info(P + "write seam live: the injection fired {} times in the first 300"
                        + " IN-WORLD frames ({} of them reached a write).",
                        seamHitCount, seamFireCount);
                }
            }
            Map<Integer, List<ChainState>> completed = camsCur;
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
            seamHitCount++; // the injection resolved and ran — counted before every other filter
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
            // The CURRENT matrices are derived by inverting the inverse uniforms the pass actually
            // holds: composite4 has gbufferModelViewInverse / gbufferProjectionInverse but NOT
            // gbufferModelView / gbufferProjection (both measured inactive, loc -1), so the forward
            // matrices are not readable and must be reconstructed. JOML reads and writes column-major,
            // which is exactly glGetUniformfv's layout, so no transpose is involved.
            ChainState cur = new ChainState();
            cur.cam[0] = TMP3[0];
            cur.cam[1] = TMP3[1];
            cur.cam[2] = TMP3[2];
            cur.matricesValid = false;
            if (writeMatrices() && locMvInv >= 0 && locProjInv >= 0) {
                try {
                    GL20.glGetUniformfv(pid, locMvInv, TMP16);
                    // VERIFY THE INVERSION, do not assume it. JOML's invert() does NOT throw on a
                    // singular or degenerate input — it returns a matrix full of NaN/Inf — so a
                    // try/catch alone would happily store garbage and then upload it as the shader's
                    // previous matrix, which is a far worse artifact than the blur being fixed. The
                    // check is the same quantity the census reports as idMV: round-trip the product
                    // back to the identity and require it.
                    boolean ok = invertChecked(TMP16, cur.mv);
                    if (ok) {
                        GL20.glGetUniformfv(pid, locProjInv, TMP16);
                        ok = invertChecked(TMP16, cur.proj);
                    }
                    cur.matricesValid = ok;
                    if (!ok) {
                        warnOnce("noninv", P + "matrix half idle (once-only): inverting the pass's"
                            + " gbufferModelViewInverse/gbufferProjectionInverse did not round-trip to"
                            + " the identity, so the reconstructed forward matrix cannot be trusted."
                            + " The camera half still applies; the matrix half neutralizes.", null);
                    }
                }
                catch (Throwable t) {
                    cur.matricesValid = false; // never disarm the whole feature over one bad matrix
                }
            }
            camsCur.computeIfAbsent(pid, k -> new ArrayList<>()).add(cur);

            Pending q = new Pending();
            q.locPrevCam = locPrevCam;
            q.locPrevMv = locPrevMv;
            q.locPrevProj = locPrevProj;
            GL20.glGetUniformfv(pid, locPrevCam, q.savePrevCam);
            // DO NOT TOUCH A PASS IRIS HAS NOT INITIALISED YET. If previousCameraPosition is still
            // exactly (0,0,0) then iris has not uploaded it — its Vector3Uniform cache and the GL
            // state are both at their initial value. Writing here means the paired restore puts that
            // ZERO back, and because updateValue early-returns whenever its cache already matches what
            // it wants to upload, iris then NEVER re-uploads and the uniform stays zero for the whole
            // session. That is the "restore is mandatory" hazard running in reverse, and it is the
            // leading explanation for the second live round reading prev=(0,0,0) on 100 of 100 census
            // rows with this feature ON, where the feature-OFF run showed real cameras throughout.
            // Skipping costs nothing: an uninitialised previous camera means there is no history to
            // restore anyway.
            if (q.savePrevCam[0] == 0f && q.savePrevCam[1] == 0f && q.savePrevCam[2] == 0f) {
                infoOnce("uninit", P + "skipping (once-only): the guarded pass's"
                    + " previousCameraPosition is still exactly (0,0,0), i.e. iris has not uploaded it"
                    + " yet. Writing now would make our paired restore put that zero back permanently,"
                    + " because iris's uniform cache would then never differ from what it wants to"
                    + " upload. Waiting until iris initialises the uniform.");
                // Mark the record, do not leave the PREVIOUS bind's action in it — the census attaches
                // this slot to the bind it is describing, so a stale value would attribute another
                // bind's write to a bind that deliberately did nothing.
                laPid = pid;
                laCam[0] = TMP3[0];
                laCam[1] = TMP3[1];
                laCam[2] = TMP3[2];
                laWrote[0] = 0f;
                laWrote[1] = 0f;
                laWrote[2] = 0f;
                laCandidates = 0;
                laMatchDist = -1.0;
                laHow = "NOTHING (iris has not initialised previousCameraPosition yet)";
                pending = null;
                seamFireCount++;
                return;
            }
            // The FIRST guarded pass of a session writes nothing — it exists only to prove the restore
            // seam fires. If it never does, the feature permanently disarms having provably made zero
            // writes, so the main view is untouched by construction.
            q.probeOnly = !restoreSeamProven;

            // THE MATCH: the NEAREST camera this program held last frame, accepted only if it is within
            // one frame's plausible camera travel. See #nearest for why this replaced ordinal keying.
            List<ChainState> candidates = camsPrev.get(pid);
            ChainState history = nearest(candidates, TMP3, maxDelta());
            laPid = pid;
            laCam[0] = TMP3[0];
            laCam[1] = TMP3[1];
            laCam[2] = TMP3[2];
            laCandidates = candidates == null ? 0 : candidates.size();
            laMatchDist = history == null ? -1.0 : dist3(history.cam, TMP3);

            // BOTH HALVES OR NEITHER — enforced, not merely intended. The camera pair and the matrix
            // pair are separate blur drivers (measured: rows with a zero camera offset still blurred
            // 57-102 px on a nonzero idMV). Writing a previous CAMERA from one frame while leaving a
            // previous MATRIX from another produces a third combination that is nobody's actual state.
            // So if the matrices are in play but unusable, this bind writes NOTHING and leaves iris
            // alone — an earlier draft wrote the camera anyway, which is exactly that forbidden case.
            boolean doMatrices = writeMatrices() && locPrevMv >= 0 && locPrevProj >= 0;
            boolean useHistory = history != null && (!doMatrices || history.matricesValid);
            boolean canNeutralize = !doMatrices || cur.matricesValid;
            if (!useHistory && !canNeutralize) {
                laHow = "NOTHING (matrices unusable — refusing a camera-only write)";
                laWrote[0] = q.savePrevCam[0];
                laWrote[1] = q.savePrevCam[1];
                laWrote[2] = q.savePrevCam[2];
                pending = null;
                return;
            }

            // The action record must describe the branch ACTUALLY taken. Deriving it from
            // `history != null` was wrong: the branch is gated on useHistory, so a bind with a valid
            // camera match but unusable matrices would have reported a match it never performed.
            float[] written = useHistory ? history.cam : TMP3;
            laWrote[0] = written[0];
            laWrote[1] = written[1];
            laWrote[2] = written[2];
            laHow = q.probeOnly ? "NOTHING (seam-proving frame)"
                : useHistory ? "nearest-camera match" + (doMatrices ? " + matrices" : " (camera only)")
                    : "NEUTRALIZE (no usable candidate in range)";

            if (doMatrices) {
                GL20.glGetUniformfv(pid, locPrevMv, q.savePrevMv);
                GL20.glGetUniformfv(pid, locPrevProj, q.savePrevProj);
            }
            // PUBLISH THE PENDING BEFORE THE FIRST GL WRITE. The catch block below restores from the
            // `pending` FIELD; assigning it only after the writes meant a throw mid-write left the
            // uniform overwritten with nothing to restore it from.
            pending = q;
            if (!q.probeOnly) {
                if (useHistory) {
                    GL20.glUniform3f(locPrevCam, history.cam[0], history.cam[1], history.cam[2]);
                    if (doMatrices) {
                        q.wroteMatrices = true;
                        GL20.glUniformMatrix4fv(locPrevMv, false, history.mv);
                        GL20.glUniformMatrix4fv(locPrevProj, false, history.proj);
                    }
                    IPGlobal.irisDestPrevWriteCount++;
                }
                else {
                    // Neutralize: previous := current, for the camera AND the matrices. The shader's
                    // chain then telescopes exactly (velocity 0 for every depth) => one blur-free
                    // frame. Strictly better than either defect, so the fallback can never regress.
                    GL20.glUniform3f(locPrevCam, TMP3[0], TMP3[1], TMP3[2]);
                    if (doMatrices) {
                        q.wroteMatrices = true;
                        GL20.glUniformMatrix4fv(locPrevMv, false, cur.mv);
                        GL20.glUniformMatrix4fv(locPrevProj, false, cur.proj);
                    }
                    IPGlobal.irisDestPrevNeutralizeCount++;
                }
            }
            seamFireCount++;

            if (!liveLogged && !q.probeOnly) {
                liveLogged = true;
                double was = dist(q.savePrevCam, TMP3);
                LOGGER.info(P + "correction ACTIVE (once-only liveness line): pass={}"
                        + " prog={} cam=({},{},{}) iris had prev=({},{},{}) |cam-prev|={} ->"
                        + " wrote prev=({},{},{}) via {}. A large \"|cam-prev|\" here is the smearing"
                        + " chain being corrected; ~0 means this chain was already clean.",
                    name, pid, f(TMP3[0]), f(TMP3[1]), f(TMP3[2]),
                    f(q.savePrevCam[0]), f(q.savePrevCam[1]), f(q.savePrevCam[2]), f(was),
                    f(history != null ? history.cam[0] : TMP3[0]),
                    f(history != null ? history.cam[1] : TMP3[1]),
                    f(history != null ? history.cam[2] : TMP3[2]),
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
        if (q.wroteMatrices) {
            GL20.glUniformMatrix4fv(q.locPrevMv, false, q.savePrevMv);
            GL20.glUniformMatrix4fv(q.locPrevProj, false, q.savePrevProj);
        }
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
    private static ChainState nearest(@Nullable List<ChainState> candidates, float[] cam,
                                      double limit) {
        if (candidates == null) {
            return null;
        }
        ChainState best = null;
        double bestD = Double.MAX_VALUE;
        for (ChainState c : candidates) {
            double dx = c.cam[0] - cam[0];
            double dy = c.cam[1] - cam[1];
            double dz = c.cam[2] - cam[2];
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

    /**
     * Invert {@code src} (column-major, as {@code glGetUniformfv} returns and JOML consumes) into
     * {@code dst}, and return false unless {@code src · dst} really is the identity.
     *
     * <p>Tolerance 1e-3: these are float uniforms round-tripped through a float inverse, so exact
     * equality is unreachable, while a genuinely failed inversion misses by whole units or by NaN
     * (every comparison against NaN is false, so the {@code > TOL} test rejects it — deliberately
     * written so NaN takes the failure branch rather than sliding through).
     */
    private static boolean invertChecked(float[] src, float[] dst) {
        MAT.set(src).invert().get(dst);
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) {
                    s += src[k * 4 + row] * dst[col * 4 + k];
                }
                float expected = col == row ? 1f : 0f;
                if (!(Math.abs(s - expected) <= 1.0e-3f)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double dist3(float[] a, float[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
            locMvInv = GL20.glGetUniformLocation(pid, "gbufferModelViewInverse");
            locProjInv = GL20.glGetUniformLocation(pid, "gbufferProjectionInverse");
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
