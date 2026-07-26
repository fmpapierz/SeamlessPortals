package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * IS5-MB — the DEST COMPOSITE GATE PROBE. Log-only, DEFAULT OFF
 * ({@code -Dseamlessportals.mbGateProbe}).
 *
 * <p><b>The question.</b> With the pack's Motion Blur on, a SAME-DIMENSION portal window is smeared
 * across its whole area — and the user confirms it is smeared <b>with their hand off the mouse</b>.
 * CROSS-dimension windows are clean. That split is itself informative and was predicted: cross-dim runs
 * on its own pipeline whose composite programs bind fresh each frame, so their PER_FRAME uniform stage
 * uploads; same-dim re-binds the SAME programs the main composite chain already used this frame, so
 * {@code ProgramUniforms.update()} sees {@code lastFrame == SystemTimeUniforms.COUNTER} and <b>skips the
 * PER_FRAME stage entirely</b> — the dest composite then runs on the MAIN camera's uniforms.
 *
 * <p><b>Why a probe rather than a fix.</b> The static chain says those inherited uniforms are
 * self-consistent, and a self-consistent pair TELESCOPES:
 * {@code P_prev·MV_prev·MV_cur⁻¹·P_cur⁻¹ = I} whenever {@code prev == cur}, for ANY depth buffer. So
 * the model predicts velocity ≈ 0 at rest and therefore NO smear while stationary — which the user's
 * observation contradicts. A premise in that chain is wrong, and two prior attempts to reason it out
 * from the armchair were both refuted by measurement. This reads the actual numbers instead.
 *
 * <p><b>What it measures</b>, at the {@code composite4} pass boundary (the motion-blur pass), for BOTH
 * roles — the MAIN control row is what makes the DEST row interpretable:
 * <ul>
 *   <li>{@code gated} — {@code ProgramUniforms.lastFrame} vs {@code SystemTimeUniforms.COUNTER}.
 *       TRUE means the PER_FRAME upload was skipped for this bind, i.e. the pass runs on whatever the
 *       previous binder left in the program's uniform storage.</li>
 *   <li>{@code cameraPosition} / {@code previousCameraPosition} — read from the program object's OWN
 *       uniform storage via {@code glGetUniformfv}, so these are the exact bits the fragment shader
 *       will read, not a proxy. {@code delta} is what drives {@code cameraOffset} in
 *       {@code composite4.glsl:120}; if it is non-zero while the player is stationary, the velocity
 *       cannot be zero and the telescoping premise is dead.</li>
 * </ul>
 *
 * <p><b>DECISION RULE.</b> Stationary player, same-dim window:
 * {@code gated=true} + {@code delta≈0} on the DEST row ⇒ the uniforms are innocent and the smear comes
 * from somewhere else entirely (next suspects: the matrices, or the pass's own inputs).
 * {@code delta} materially non-zero ⇒ the inherited pair is NOT self-consistent, the telescoping
 * argument is refuted, and the fix is a dest-scoped PER_FRAME re-upload (which is exactly what cross-dim
 * already does correctly — and why cross-dim looks fine).
 *
 * <p>Behaviour-neutral: no state written, no GL state changed ({@code glGetUniformfv} on a program
 * object is a pure query), no {@code glGetError} (it would drain the queue {@code CHelper.checkGlError}
 * depends on), ≤1 Hz per role, and every silent-skip path logs once.
 */
public final class MbGateProbe {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[IS5-MB] ";

    private static final boolean ENABLED = Boolean.getBoolean("seamlessportals.mbGateProbe");
    /** The pack's motion-blur pass. Complementary-lineage name; if absent the probe says so once. */
    private static final String TARGET_PASS = "composite4";

    private MbGateProbe() {
    }

    private static boolean broken = false;
    private static boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;
    private static final Set<String> warnedOnce = new HashSet<>();
    private static boolean sawTargetPass = false;
    private static long firstCallNanos = 0L;

    private static long lastMainNanos = 0L;
    private static long lastDestNanos = 0L;

    private static Field fPasses;        // CompositeRenderer.passes
    private static Field fPassName;      // Pass.name
    private static Field fPassProgram;   // Pass.program
    private static Method mGetProgramId; // Program.getProgramId()
    private static Field fProgUniforms;  // Program.uniforms
    private static Field fLastFrame;     // ProgramUniforms.lastFrame
    private static Field fCounter;       // SystemTimeUniforms.COUNTER
    private static Method mCounterGet;   // FrameCounter.getAsInt()

    /**
     * Called at the top of {@code IrisBloomApertureMask.onCompositePassBoundary} — deliberately ABOVE
     * that method's armed-window early return, so the MAIN composite chain is sampled too and the DEST
     * row has a control to be read against.
     */
    public static void onPass(Object renderer, int i) {
        if (!ENABLED || broken) {
            return;
        }
        try {
            if (firstCallNanos == 0L) {
                firstCallNanos = System.nanoTime();
            }
            if (!ensureReflection()) {
                return;
            }
            Object passesObj = fPasses.get(renderer);
            if (!(passesObj instanceof java.util.List<?> passes) || i < 0 || i >= passes.size()) {
                return;
            }
            Object pass = passes.get(i);
            String name = String.valueOf(fPassName.get(pass));
            if (!TARGET_PASS.equals(name)) {
                watchdog();
                return;
            }
            sawTargetPass = true;

            boolean inPortal = PortalRendering.isRendering();
            long now = System.nanoTime();
            if (inPortal) {
                if (now - lastDestNanos < 1_000_000_000L) {
                    return;
                }
                lastDestNanos = now;
            }
            else {
                if (now - lastMainNanos < 1_000_000_000L) {
                    return;
                }
                lastMainNanos = now;
            }

            Object program = fPassProgram.get(pass);
            if (program == null) {
                warnOnce("noprog", P + "skip (once-only): the " + TARGET_PASS + " pass has a null"
                    + " program — no uniform storage to read; this run answers nothing.", null);
                return;
            }
            int pid = (Integer) mGetProgramId.invoke(program);

            int lastFrame = -1;
            int counter = -1;
            try {
                Object uni = fProgUniforms.get(program);
                if (uni != null && fLastFrame != null) {
                    lastFrame = fLastFrame.getInt(uni);
                }
                Object c = fCounter.get(null);
                if (c != null) {
                    counter = (Integer) mCounterGet.invoke(c);
                }
            }
            catch (Throwable t) {
                warnOnce("gateread", P + "could not read lastFrame/COUNTER — the GATE verdict is"
                    + " UNAVAILABLE this run (the uniform values below still stand).", t);
            }
            String gated = (lastFrame < 0 || counter < 0) ? "n/a"
                : String.valueOf(lastFrame == counter);

            float[] cam = readVec3(pid, "cameraPosition");
            float[] prev = readVec3(pid, "previousCameraPosition");
            String delta = "n/a";
            if (cam != null && prev != null) {
                double dx = cam[0] - prev[0];
                double dy = cam[1] - prev[1];
                double dz = cam[2] - prev[2];
                delta = String.format("(%.4f,%.4f,%.4f) |d|=%.4f", dx, dy, dz,
                    Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            else {
                warnOnce("noloc", P + "cameraPosition/previousCameraPosition are not active uniforms"
                    + " on " + TARGET_PASS + " (loc=-1) — the delta verdict is UNAVAILABLE; decide on"
                    + " the gate bit alone.", null);
            }

            LOGGER.info(P + "role={} pass={} idx={} prog={} lastFrame={} COUNTER={} gated={}"
                    + "  cam={} prev={}  delta={}",
                inPortal ? "DEST" : "MAIN", name, i, pid, lastFrame, counter, gated,
                fmt3(cam), fmt3(prev), delta);
        }
        catch (Throwable t) {
            broken = true;
            try {
                LOGGER.warn(P + "DISARMED after a throw (diagnostic only, render unaffected)", t);
            }
            catch (Throwable ignored) {
                // never let the probe's own failure escape
            }
        }
    }

    /** A silent probe must never be read as "no defect" — say so if the target pass never appears. */
    private static void watchdog() {
        if (sawTargetPass || firstCallNanos == 0L) {
            return;
        }
        if (System.nanoTime() - firstCallNanos < 30_000_000_000L) {
            return;
        }
        warnOnce("nopass", P + "WATCHDOG: no composite pass named \"" + TARGET_PASS + "\" was seen in"
            + " 30s of composite boundaries. Either the pack renames it or the chain differs —"
            + " this run CANNOT answer the motion-blur gate question; do NOT read the silence as"
            + " \"no defect\".", null);
    }

    private static float[] readVec3(int pid, String name) {
        try {
            int loc = GL20.glGetUniformLocation(pid, name);
            if (loc < 0) {
                return null;
            }
            float[] v = new float[4];
            GL20.glGetUniformfv(pid, loc, v);
            return v;
        }
        catch (Throwable t) {
            return null;
        }
    }

    private static String fmt3(float[] v) {
        return v == null ? "n/a"
            : String.format("(%.3f,%.3f,%.3f)", v[0], v[1], v[2]);
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

            Class<?> passClass = Class.forName("net.irisshaders.iris.pipeline.CompositeRenderer$Pass");
            fPassName = passClass.getDeclaredField("name");
            fPassName.setAccessible(true);
            fPassProgram = passClass.getDeclaredField("program");
            fPassProgram.setAccessible(true);

            Class<?> prog = Class.forName("net.irisshaders.iris.gl.program.Program");
            mGetProgramId = prog.getMethod("getProgramId");
            fProgUniforms = prog.getDeclaredField("uniforms");
            fProgUniforms.setAccessible(true);

            Class<?> pu = Class.forName("net.irisshaders.iris.gl.program.ProgramUniforms");
            fLastFrame = pu.getDeclaredField("lastFrame");
            fLastFrame.setAccessible(true);

            Class<?> stu = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms");
            fCounter = stu.getField("COUNTER");
            Object c = fCounter.get(null);
            if (c != null) {
                mCounterGet = c.getClass().getMethod("getAsInt");
                mCounterGet.setAccessible(true);
            }

            reflectionReady = true;
            return true;
        }
        catch (ClassNotFoundException e) {
            broken = true;
            LOGGER.info(P + "iris classes not present on the runtime — gate probe inert");
            return false;
        }
        catch (Throwable t) {
            broken = true;
            warnOnce("resolve", P + "DISARMED: could not resolve the iris CompositeRenderer/Program/"
                + "ProgramUniforms symbols on this Iris build. The motion-blur gate question CANNOT be"
                + " answered from this run — do NOT read a silent log as \"no defect\".", t);
            return false;
        }
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
}
