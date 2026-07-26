package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * IS5-MB — PER-DEST PREVIOUS-FRAME CAMERA STATE. The same-dim portal-window motion-blur smear fix.
 * DEFAULT-ON; A/B off via {@code -Dseamlessportals.disableIrisDestPrevCamera}.
 *
 * <h2>The defect (MEASURED, not theorised)</h2>
 * With the pack's Motion Blur on, a SAME-DIMENSION portal window smears at full saturation <b>even with
 * the player stationary</b>; cross-dim windows are clean. {@code MbGateProbe} read the composite4
 * program's OWN uniform storage via {@code glGetUniformfv} and found, with the player standing still:
 * <pre>
 *   cameraPosition         = (0.440, 19.620,  2.615)   <- the DEST camera
 *   previousCameraPosition = (112.940, 67.620, -26.885) <- the MAIN camera      |delta| = 125.82
 * </pre>
 * {@code composite4.glsl:120-128} builds {@code cameraOffset = cameraPosition - previousCameraPosition}
 * and soft-clamps {@code velocity/(1+|velocity|)*MOTION_BLURRING_STRENGTH} — so a 125-block offset
 * SATURATES the clamp, pinning |velocity| at the strength in a fixed direction. That is a full-strength
 * smear that does not require the player to move at all.
 *
 * <h2>Why (the tracker ticks three times on a portal frame)</h2>
 * {@code CameraUniforms.addCameraUniforms} builds ONE {@code CameraPositionTracker} per program, and it
 * advances on {@code FrameUpdateNotifier.onNewFrame()}:
 * <pre>
 *   tick1  main beginLevelRendering  ->  prev = main(N-1),  cur = main(N)
 *   tick2  dest beginLevelRendering  ->  prev = main(N),    cur = dest(N)   <- the dest pass reads THIS
 *   tick3  our own IS5-PH heal       ->  prev = dest(N),    cur = main(N)
 * </pre>
 * Cross-dim is clean because it runs on its own per-dimension pipeline whose {@code addCameraUniforms}
 * built a FRESH tracker that only ever sees dest cameras — so this class EXCLUDES cross-dim by
 * construction (see {@link #arm}); writing there would introduce a defect, not fix one.
 *
 * <h2>The mechanism</h2>
 * Write the dest's own previous-frame trio into the guarded composite program between its
 * {@code Program.use()} and its draw, then RESTORE iris's values before the next pass:
 * <ul>
 *   <li><b>S1 (write)</b> — {@code @Inject} at {@code INVOKE Program.use()V} {@code shift=AFTER}. That
 *       call is UNIQUE in {@code CompositeRenderer} (the one at offset 169 is
 *       {@code ComputeProgram.use()V}, a different descriptor), so no {@code ordinal} is needed, and
 *       AFTER lands past both {@code _glUseProgram} and {@code uniforms.update()} — the program is
 *       bound and iris has finished every upload for it.</li>
 *   <li><b>S4 (restore)</b> — {@code @Inject} at {@code INVOKE BlendModeOverride.restore()V}, likewise
 *       unique, immediately after the draw. 419 -> 455 -> 458 is straight-line, so write and restore
 *       pair 1:1 on the same iteration.</li>
 * </ul>
 * Because the program is already bound, the writes are <b>core GL 2.0</b>
 * ({@code glUniform3f}/{@code glUniformMatrix4fv}) — no ARB extension and no capability gate. That
 * matters: this project has twice lost a probe leg by gating GL features on core-version flags on a
 * context that reports {@code OpenGL42/43/45 = false} while the ARB extensions are present.
 *
 * <h2>Why the RESTORE is mandatory, not housekeeping</h2>
 * {@code Vector3Uniform.updateValue} and {@code MatrixUniform.updateValue} both early-return when their
 * Java-side {@code cachedValue} is unchanged. With a stationary player {@code previousCameraPosition} is
 * byte-stable frame to frame, so iris would NEVER overwrite our write — and the MAIN view would inherit
 * dest camera values on the next frame. Restoring keeps iris's cache truthful with zero reflection into
 * its uniform lists.
 *
 * <h2>Degenerate cases NEUTRALIZE rather than guess</h2>
 * Neutralize = write {@code previous := current} ⇒ velocity ≡ 0 ⇒ one blur-free frame in the window.
 * That is strictly better than the defect (which saturates), so the fallback can never regress. It is
 * used for: the first composite for a portal, a changed program id (pack reload / resize), a portal
 * that was off-screen, and any ambiguous regime.
 *
 * <h2>The write-enable latch</h2>
 * The FIRST armed guarded pass of a session writes NOTHING — it only proves the restore seam fires. If
 * it is never consumed, the feature permanently disarms with the guarantee that <b>zero writes were ever
 * made</b>, so the main view is provably untouched. Cost: one frame, which the first-frame neutralize
 * would have spent anyway.
 */
public final class IrisDestPrevCamera {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-MB ";

    private IrisDestPrevCamera() {
    }

    // =============================================================================================
    // State
    // =============================================================================================

    /** The per-dest record. Zero iris/GL imports; matrices are COPIED (iris hands them by reference). */
    private static final class DestPrevState {
        final double[] camUnshifted = new double[3];
        final float[] modelView = new float[16];
        final float[] projection = new float[16];
        int programId = 0;
        int frameStamp = -1;
        boolean valid = false;
    }

    /** The write pending a restore at S4. Cleared FIRST on consumption so a throw cannot wedge it. */
    private static final class Pending {
        int locPrevCam;
        int locPrevMv;
        int locPrevProj;
        final float[] savePrevCam = new float[4];
        final float[] savePrevMv = new float[16];
        final float[] savePrevProj = new float[16];
        boolean probeOnly;
        boolean wroteMatrices;
    }

    /** Keyed on the Portal instance: the value is "the camera THIS window was rendered from last frame".
     *  Portal extends Entity, so a removed portal's entry dies with it. */
    private static final Map<Portal, DestPrevState> MAP = new WeakHashMap<>();

    private static Portal armed = null;
    private static Pending pending = null;
    private static boolean consumedThisWindow = false;
    private static boolean broken = false;
    private static boolean restoreSeamProven = false;
    private static boolean liveLogged = false;
    private static boolean rosterLogged = false;
    private static final Set<String> warnedOnce = new HashSet<>();

    private static boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;
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
    private static final float[] TMP16 = new float[16];
    private static final float[] WRITE16 = new float[16];

    // =============================================================================================
    // Arm / disarm — mod side, decided BEFORE any GL call
    // =============================================================================================

    /**
     * Armed around ONE portal's nested dest render. SAME-DIM ONLY: cross-dim runs its own
     * per-dimension pipeline whose tracker is already correct, so it is excluded here — mod-side, with
     * zero iris symbols, which makes the cross-dim path byte-identical BY CONSTRUCTION.
     *
     * <p>NOTE {@code mc.level} is still the SOURCE level at this point: the level swap happens inside
     * {@code MyGameRenderer.switchAndRenderTheWorldFullPipeline}, which runs after this call.
     */
    public static void arm(@Nullable Portal portal) {
        armed = null;
        try {
            if (portal == null || broken || !IPGlobal.isIrisDestPrevCameraActive()) {
                return;
            }
            if (PortalRendering.getPortalLayer() != 1) {
                // Under recursion the correct key is the ORDERED PATH of portals (A seen directly and
                // A-through-B are different cameras). Rather than pretend, decline and say so — this
                // makes the one-layer assumption self-reporting if maxPortalLayer is ever raised here.
                warnOnce("recursion", P + "declined (once-only): portal layer "
                    + PortalRendering.getPortalLayer() + " != 1. Per-dest previous-camera keying is"
                    + " one-layer-only; under recursion the key would have to be the ordered portal"
                    + " path. The window keeps iris's values (today's behaviour).", null);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !portal.getDestDim().equals(mc.level.dimension())) {
                return; // cross-dim: already correct, must not be touched
            }
            armed = portal;
        }
        catch (Throwable t) {
            armed = null;
            disarm(t);
        }
    }

    /** First statement of the bracket's finally: the arm can never outlive the window. */
    public static void disarmAndReport() {
        Portal p = armed;
        armed = null;
        Pending q = pending;
        pending = null;
        try {
            if (q != null) {
                // A mid-chain throw left a write outstanding — restore it now rather than leaking
                // dest values into the next pass.
                restoreNow(q);
            }
            if (p != null && !consumedThisWindow && restoreSeamProven) {
                IPGlobal.irisDestPrevMissCount++;
                warnOnce("miss", P + "armed but never consumed (once-only): no guarded composite pass"
                    + " ran for this portal window. Expected pass name(s) " + TARGET_PASSES
                    + " — the once-only roster line names every pass actually seen; override with"
                    + " -Dseamlessportals.irisDestPrevCameraPass if the pack renames it.", null);
            }
            consumedThisWindow = false;
            sweepIfLarge();
            maybeProbe();
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // =============================================================================================
    // S1 — the write, after Program.use() and therefore after uniforms.update()
    // =============================================================================================

    public static void onPassProgramBound(Object renderer, int i) {
        if (armed == null || broken) {
            return; // THE byte-inert gate: one static read on every non-portal composite pass
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
            if (!PortalRendering.isRendering()) {
                return; // belt
            }
            Object prog = fPassProgram.get(pass);
            if (prog == null) {
                warnOnce("noprog", P + "skip (once-only): the guarded pass \"" + name + "\" has a null"
                    + " program — nothing to write.", null);
                return;
            }
            int pid = ((net.irisshaders.iris.gl.program.Program) prog).getProgramId();
            if (!resolveLocations(pid)) {
                return;
            }

            // --- the CURRENT trio, sourced MOD-SIDE so it is regime-independent -------------------
            // CapturedRenderingState is written by iris$setupPipeline at the head of the nested
            // destRenderer.render(...) REGARDLESS of the uniform-update gate, and mainCamera() is the
            // dest camera for the whole bracket. Reading these back from GL instead would be
            // regime-dependent (in the gated regime `cameraPosition` is main-valued, and we would
            // store the MAIN camera as if it were the dest's).
            Vector3d destUnshifted =
                net.irisshaders.iris.uniforms.CameraUniforms.getUnshiftedCameraPosition();
            Matrix4fc mvNow =
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getGbufferModelView();
            Matrix4fc projNow =
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getGbufferProjection();
            if (destUnshifted == null || mvNow == null || projNow == null) {
                warnOnce("nostate", P + "skip (once-only): iris CapturedRenderingState/camera is not"
                    + " populated at the guarded pass — no current trio to store.", null);
                return;
            }

            // --- regime detect + shift derive ----------------------------------------------------
            // The tracker stores SHIFTED positions (getShift fires on |pos|>30000 or |pos-prev|>1000).
            // We store UNSHIFTED and re-base by the CURRENT shift, which is exact across a shift epoch
            // because previousCameraPosition and cameraPosition live in the SAME shifted frame.
            GL20.glGetUniformfv(pid, locCam, TMP3);
            double dx = TMP3[0] - destUnshifted.x;
            double dy = TMP3[1] - destUnshifted.y;
            double dz = TMP3[2] - destUnshifted.z;
            double shiftX;
            double shiftY;
            double shiftZ;
            if (Math.abs(dx) < 1.0 && Math.abs(dy) < 1.0 && Math.abs(dz) < 1.0) {
                shiftX = 0.0;
                shiftY = 0.0;
                shiftZ = 0.0;
            }
            else if (Math.abs(dy) < 1.0 && isNearMultiple(dx, 30000.0) && isNearMultiple(dz, 30000.0)) {
                shiftX = Math.round(dx / 30000.0) * 30000.0;
                shiftY = 0.0;
                shiftZ = Math.round(dz / 30000.0) * 30000.0;
            }
            else {
                // The bound program's cameraPosition is neither the dest camera nor a shifted form of
                // it — we cannot say what frame it is in, so we WRITE NOTHING rather than guess.
                infoOnce("regime", P + "regime AMBIGUOUS (once-only): the guarded pass's"
                    + " cameraPosition=(" + fmt(TMP3[0]) + "," + fmt(TMP3[1]) + "," + fmt(TMP3[2])
                    + ") is not the dest camera (" + fmt(destUnshifted.x) + "," + fmt(destUnshifted.y)
                    + "," + fmt(destUnshifted.z) + ") nor a 30000-shifted form of it. Writing NOTHING;"
                    + " the window keeps iris's values.");
                return;
            }

            // --- save what we are about to overwrite ---------------------------------------------
            Pending q = new Pending();
            q.locPrevCam = locPrevCam;
            q.locPrevMv = locPrevMv;
            q.locPrevProj = locPrevProj;
            GL20.glGetUniformfv(pid, locPrevCam, q.savePrevCam);
            boolean matrices = !IPGlobal.IRIS_DEST_PREV_NO_MATRICES;
            if (matrices) {
                GL20.glGetUniformfv(pid, locPrevMv, q.savePrevMv);
                GL20.glGetUniformfv(pid, locPrevProj, q.savePrevProj);
            }
            q.wroteMatrices = matrices;

            // --- decide neutralize vs the stored previous ----------------------------------------
            DestPrevState rec = MAP.get(armed);
            int age = rec == null ? Integer.MAX_VALUE : (RenderStates.frameIndex - rec.frameStamp);
            boolean neutralize = rec == null || !rec.valid || rec.programId != pid
                || age < 1 || age > 4;

            // --- write (unless this is the seam-proving probe frame) ------------------------------
            q.probeOnly = !restoreSeamProven;
            if (!q.probeOnly) {
                if (neutralize) {
                    GL20.glUniform3f(locPrevCam, TMP3[0], TMP3[1], TMP3[2]);
                    if (matrices) {
                        mvNow.get(WRITE16);
                        GL20.glUniformMatrix4fv(locPrevMv, false, WRITE16);
                        projNow.get(WRITE16);
                        GL20.glUniformMatrix4fv(locPrevProj, false, WRITE16);
                    }
                    IPGlobal.irisDestPrevNeutralizeCount++;
                }
                else {
                    GL20.glUniform3f(locPrevCam,
                        (float) (rec.camUnshifted[0] + shiftX),
                        (float) (rec.camUnshifted[1] + shiftY),
                        (float) (rec.camUnshifted[2] + shiftZ));
                    if (matrices) {
                        GL20.glUniformMatrix4fv(locPrevMv, false, rec.modelView);
                        GL20.glUniformMatrix4fv(locPrevProj, false, rec.projection);
                    }
                    IPGlobal.irisDestPrevWriteCount++;
                }
            }
            pending = q;

            // --- store this frame's dest state for the next frame ---------------------------------
            if (rec == null) {
                rec = new DestPrevState();
                MAP.put(armed, rec);
            }
            rec.camUnshifted[0] = destUnshifted.x;
            rec.camUnshifted[1] = destUnshifted.y;
            rec.camUnshifted[2] = destUnshifted.z;
            mvNow.get(rec.modelView);
            projNow.get(rec.projection);
            rec.programId = pid;
            rec.frameStamp = RenderStates.frameIndex;
            rec.valid = true;
            consumedThisWindow = true;

            if (!liveLogged && !q.probeOnly && !neutralize) {
                liveLogged = true;
                LOGGER.info(P + "per-dest previous-camera ACTIVE (once-only liveness line): pass={}"
                        + " prog={} shift=({},{},{}) prevCam=({},{},{}) cur=({},{},{}) matrices={}"
                        + " (A/B lever -Dseamlessportals.disableIrisDestPrevCamera)",
                    name, pid, fmt(shiftX), fmt(shiftY), fmt(shiftZ),
                    fmt(rec.camUnshifted[0] + shiftX), fmt(rec.camUnshifted[1] + shiftY),
                    fmt(rec.camUnshifted[2] + shiftZ),
                    fmt(TMP3[0]), fmt(TMP3[1]), fmt(TMP3[2]), matrices ? "on" : "off");
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
                // Nothing was written; this frame existed only to prove the restore seam fires.
                restoreSeamProven = true;
                infoOnce("proven", P + "restore seam proven (once-only): the paired"
                    + " BlendModeOverride.restore injection fired for a guarded pass. Writes begin on"
                    + " the next dest composite; zero writes were made before this point.");
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

    private static boolean resolveLocations(int pid) {
        if (locCachePid != pid) {
            locCachePid = pid;
            locCam = GL20.glGetUniformLocation(pid, "cameraPosition");
            locPrevCam = GL20.glGetUniformLocation(pid, "previousCameraPosition");
            locPrevMv = GL20.glGetUniformLocation(pid, "gbufferPreviousModelView");
            locPrevProj = GL20.glGetUniformLocation(pid, "gbufferPreviousProjection");
        }
        // ALL-THREE GUARD: with Motion Blur OFF the pack's composite4 does not declare the previous
        // trio at all, so every location is -1 and this returns false => not one GL write is issued.
        // That is the MB-off byte-identity proof, by construction rather than by a flag.
        if (locPrevCam < 0 || locPrevMv < 0 || locPrevProj < 0) {
            infoOnce("noloc", P + "idle (once-only): the guarded pass does not declare the full"
                + " previous-camera trio (previousCameraPosition/gbufferPreviousModelView/"
                + "gbufferPreviousProjection) — nothing to correct. This is the expected state with"
                + " the pack's Motion Blur OFF.");
            return false;
        }
        if (locCam < 0) {
            infoOnce("nocam", P + "idle (once-only): the guarded pass has no active cameraPosition"
                + " uniform — the regime/shift cannot be derived, so nothing is written.");
            return false;
        }
        return true;
    }

    private static boolean isNearMultiple(double v, double m) {
        double k = Math.round(v / m);
        return Math.abs(v - k * m) < 1.0;
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
        LOGGER.info(P + "first armed composite chain roster: [{}] — guarding {} (override with"
            + " -Dseamlessportals.irisDestPrevCameraPass)", sb, TARGET_PASSES);
    }

    private static void sweepIfLarge() {
        if (MAP.size() <= 32) {
            return;
        }
        Iterator<Map.Entry<Portal, DestPrevState>> it = MAP.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Portal, DestPrevState> e = it.next();
            if (RenderStates.frameIndex - e.getValue().frameStamp > 300) {
                it.remove();
            }
        }
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
        LOGGER.info("[IS5-MB] writes={} neutralize={} miss={} tracked={} seamProven={}",
            IPGlobal.irisDestPrevWriteCount, IPGlobal.irisDestPrevNeutralizeCount,
            IPGlobal.irisDestPrevMissCount, MAP.size(), restoreSeamProven);
    }

    private static synchronized boolean ensureReflection() {
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
        armed = null;
        pending = null;
        consumedThisWindow = false;
        locCachePid = -1;
        MAP.clear();
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
        armed = null;
        pending = null;
        warnOnce("throw", P + "DISARMED after a throw — the same-dim portal-window motion-blur"
            + " correction is dead for this session (render otherwise unaffected).", t);
    }
}
