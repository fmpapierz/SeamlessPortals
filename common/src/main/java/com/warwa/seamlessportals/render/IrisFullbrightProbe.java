package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * IS5-L — THE IN-PORTAL FULLBRIGHT PROBE (iris shaders-ON engagement; handoff
 * {@code migration/FULLBRIGHT_HANDOFF.md} §5, rev-2 recon; symbol table §8). <b>DIAGNOSTIC ONLY —
 * this is a PROBE, not a fix.</b>
 *
 * <p><b>The question it settles.</b> Under a shaderpack the DEST terrain seen through a SAME-DIM
 * portal is fullbright, and it is MAIN-CAMERA DIRECTION-DEPENDENT (panning toggles correct↔fullbright).
 * The bytecode-confirmed mechanism (§3) is iris PER_FRAME lighting-uniform staleness: the nested
 * same-dim dest render reuses the SAME cached {@code ExtendedShader}/{@code ProgramUniforms} whose
 * {@code lastFrame == SystemTimeUniforms.COUNTER} from the main pass, so
 * {@code ProgramUniforms.update()} SKIPS the perFrame re-upload for the dest draws and the dest
 * terrain is lit with the MAIN camera's uniforms. A counter-bump fix (advance {@code COUNTER} around
 * the dest render, default-ON via {@code IPGlobal.isIrisPerFrameRefreshActive()}) was
 * bytecode-confirmed + design-SOUND and STILL failed live — proving the confirming probe must run
 * FIRST. This probe reads, on the GPU, whether the dest draws actually carry the dest lighting
 * uniforms, and settles the three §4 hypotheses in ONE A/B run.
 *
 * <p><b>The core insight (rev-2 recon).</b> Same-dim reuses the SAME {@code ProgramUniforms}
 * instance across the main + dest passes, and {@code update()} re-uploads perFrame IFF {@code COUNTER}
 * advanced since that instance last updated. So "did the dest draw re-upload the perFrame lighting
 * uniforms?" ⇔ <b>{@code COUNTER(dest-pass) != COUNTER(main-pass)} in the SAME frame</b>. No need to
 * read {@code ProgramUniforms.lastFrame} (post-{@code update()} it is ALWAYS {@code ==COUNTER} —
 * useless). Both COUNTERs AND both GPU uniform value-sets are readable at the
 * {@code GlCommandEncoder.trySetup} RETURN seam: iris injects its {@code iris$setupState} (which calls
 * {@code update()}) at trySetup HEAD, so by RETURN {@code update()} has run and the GL program carries
 * the post-update uniforms.
 *
 * <p><b>The decisive, program-independent leg.</b> Beyond the cross-pass GPU compare, the DEST slot
 * also reflects the SOURCE iris WOULD upload — {@code CapturedRenderingState.getGbufferModelView()}
 * (dest-primed at the compat {@code render()} HEAD, which is why dest GEOMETRY is correct). Comparing
 * the dest program's GPU {@code gbufferModelView} against this SOURCE answers "did the dest value
 * reach the GPU?" without depending on which program the main slot captured.
 *
 * <p><b>Why {@code gbufferModelView} + celestial dirs are the strong discriminators.</b> For a
 * same-dim portal the world sun direction is IDENTICAL to the main pass, so a naive "sun should
 * differ" test would mislead — BUT {@code sunPosition}/{@code shadowLightPosition}/{@code upPosition}
 * are VIEW-space (transformed by {@code gbufferModelView}), so they DO differ between main-camera and
 * dest-camera framing even in same-dim. {@code gbufferModelView} (the view matrix) differs outright.
 * {@code cameraPosition} is WEAK (iris camera-relative fract coords — may be ≈equal both passes);
 * read it, but never decide on it alone.
 *
 * <p><b>The 3-way decision (settles §4 in ONE run — bump ON vs {@code -PdisableIrisPerFrameRefresh}):</b>
 * <ul>
 *   <li>dest GPU values DIFFER from main (dest-framed) yet still fullbright live ⇒ <b>H3 → per-vertex
 *       lmcoord</b>: the uniforms are fine; pivot to a stale sky=15 lmcoord in fresh dest sections.</li>
 *   <li>dest GPU values EQUAL main AND {@code C_dest != C_main} (re-upload happened) ⇒ <b>H1 →
 *       re-source (Option B)</b>: the re-upload ran but the SOURCE was still main-valued — push dest
 *       cameraPosition + celestial + shadow matrices before the dest render. Cross-check the reflected
 *       SOURCE: source==main ⇒ H1 firm; source==dest yet GPU==main ⇒ the upload isn't propagating that
 *       source (plumbing, closer to H2).</li>
 *   <li>dest GPU values EQUAL main AND {@code C_dest == C_main} (no re-upload) ⇒ <b>H2 → bump didn't
 *       reach</b>: fix reach/timing (bump inside {@code renderDestWorldFullPipeline}, or reset each
 *       reused {@code ExtendedShader.uniforms.lastFrame = -1}). This is also the expected bump-OFF
 *       baseline.</li>
 * </ul>
 *
 * <p><b>Binding discipline (clone of {@link ShadowEmptinessProbe}).</b> LOG-ONLY; ZERO behavior
 * change at the default. The lever {@code -Dseamlessportals.fullbrightProbe} is a RUNTIME-read
 * {@code static} ({@code Boolean.getBoolean} — not a javac compile-time constant, so the guards are
 * real short-circuit branches). Iris is reached ONLY reflectively — this class holds NO
 * {@code net.irisshaders.*} import (it lives in {@code com.warwa.seamlessportals.render}, not a compat
 * seam, so hard iris imports are forbidden per the IS5 binding rules); every reflected symbol was
 * {@code javap}-confirmed against {@code iris-1.11.2+26.2-fabric.jar} (§8). It DISARMS ITSELF on ANY
 * reflection/GL failure with one line — a diagnostic can never crash a render or mislead. 1Hz-latched
 * (one comparison block per second), render-thread only (the whole draw seam runs there). The GPU
 * queries used — {@code glGetInteger(GL_CURRENT_PROGRAM)}, {@code glGetUniformLocation},
 * {@code glGetUniformfv} — are all uncached program-object queries (26.2 GL-state invariant #1: never
 * raw-GL a {@code GlStateManager}-cached state; these touch none) and need no program bind.
 *
 * <p>Driven from {@code MixinSodiumFullbrightProbe_GlCommandEncoder} (a thin {@code @Inject} at
 * {@code com.mojang.blaze3d.opengl.GlCommandEncoder.trySetup(...)Z} RETURN — the P7b idiom, sibling of
 * {@code MixinSodiumProbe_GlCommandEncoder}). The pass discriminator is
 * {@link PortalRendering#isRendering()} (FALSE = the MAIN pass, TRUE = inside the compat nested dest
 * render via {@code pushPortalLayer}) — NOT {@code isDestExtracting} (that brackets only the vanilla
 * extract sub-phase, not the compat full-pipeline terrain draws).
 */
public final class IrisFullbrightProbe {

    private IrisFullbrightProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String P = "[IS5-FULLBRIGHT-PROBE] ";

    /** The lever. Byte-inert unless {@code -Dseamlessportals.fullbrightProbe=true}. */
    private static final boolean ENABLED = Boolean.getBoolean("seamlessportals.fullbrightProbe");

    /** 1Hz rate limit between emitted comparison blocks (ns). */
    private static final long RATE_LIMIT_NS = 1_000_000_000L;

    /** The gate uniform: only an iris {@code ExtendedShader} declares it — filters the seam to iris programs. */
    private static final String GATE_UNIFORM = "cameraPosition";

    /**
     * The PER_FRAME lighting uniforms read on the GPU, with component counts. gbufferModelView (view
     * matrix) + the view-space celestial dirs are the STRONG direction-dependent discriminators;
     * cameraPosition is the WEAK gate uniform (read last, never decisive alone).
     */
    private static final String[] UNIFORM_NAMES = {
        "gbufferModelView", "gbufferModelViewInverse",
        "shadowLightPosition", "sunPosition", "upPosition",
        "shadowModelView", "cameraPosition"
    };
    private static final int[] UNIFORM_SIZES = {16, 16, 3, 3, 3, 16, 3};

    /** Element-wise tolerance for the EQUAL(main)/DIFFER(dest) verdict on matrices/vectors. */
    private static final float EPS = 1.0e-4f;

    // ===== render-thread-only state machine ======================================================
    private static boolean disarmed = false;
    private static long lastEmitNanos = 0L;

    // MAIN slot (captured on the first armed iris draw of a frame; re-captured when COUNTER changes)
    private static boolean mainSlotValid = false;
    private static int mainCounter = Integer.MIN_VALUE;
    private static int mainProgramId = -1;
    private static float[][] mainVals = null; // one float[] per UNIFORM_NAMES entry, or null if absent

    // DEST-seam self-verification (the §5 RISK): the distinct (programId, hasCameraPosition) pairs the
    // dest pass binds at this seam. If no iris (hasCameraPosition) program ever appears, terrain isn't
    // routing through trySetup here and the pivot is to hook sodium's terrain draw path directly.
    private static final Set<String> destSeamPairs = Collections.synchronizedSet(new HashSet<>());
    private static final int DEST_SEAM_LOG_CAP = 16;

    // ===== reflection surface (resolved once; all symbols javap-confirmed vs iris-1.11.2+26.2) =====
    private static boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;
    private static Object counterObj;                                 // SystemTimeUniforms.COUNTER (FrameCounter)
    private static java.lang.reflect.Method mCounterGetAsInt;         // FrameCounter.getAsInt()
    private static Object capturedInstance;                           // CapturedRenderingState.INSTANCE
    private static java.lang.reflect.Method mGetGbufferModelView;     // CapturedRenderingState.getGbufferModelView():Matrix4fc
    private static java.lang.reflect.Method mGetGbufferProjection;    // CapturedRenderingState.getGbufferProjection():Matrix4fc

    /**
     * The trySetup-RETURN entry. Called per-draw on the render thread from
     * {@code MixinSodiumFullbrightProbe_GlCommandEncoder}. Runs the main/dest state machine, gated on:
     * enabled + a successful setup + an iris program bound (has {@code cameraPosition}).
     *
     * @param setupSucceeded {@code cir.getReturnValue()} of {@code trySetup} — only a true setup
     *                       precedes a draw with the program's uniforms live.
     */
    public static void onDrawSetup(boolean setupSucceeded) {
        if (!ENABLED || disarmed || !setupSucceeded) {
            return;
        }
        try {
            int prog = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            if (prog <= 0) {
                return;
            }
            boolean destPass = PortalRendering.isRendering();
            long now = System.nanoTime();

            if (!destPass) {
                // ---- MAIN pass: capture a reference slot once per armed frame -------------------
                if (now - lastEmitNanos < RATE_LIMIT_NS) {
                    return; // not armed (throttle)
                }
                int camLoc = GlStateManager._glGetUniformLocation(prog, GATE_UNIFORM);
                if (camLoc < 0) {
                    return; // not an iris ExtendedShader (no cameraPosition) — skip
                }
                if (!ensureReflection()) {
                    return; // iris absent / structural miss — already logged + disarmed
                }
                int c = readCounter();
                if (mainSlotValid && c == mainCounter) {
                    return; // already captured this frame (COUNTER unchanged)
                }
                mainProgramId = prog;
                mainCounter = c;
                mainVals = readUniforms(prog);
                mainSlotValid = true;
            }
            else {
                // ---- DEST pass: self-verify the seam, then capture + emit when we have a main slot
                recordDestSeam(prog);
                if (!mainSlotValid) {
                    return; // nothing to compare against yet
                }
                int camLoc = GlStateManager._glGetUniformLocation(prog, GATE_UNIFORM);
                if (camLoc < 0) {
                    return; // dest draw is not an iris program (e.g. a non-terrain pass) — keep waiting
                }
                if (!ensureReflection()) {
                    return;
                }
                int cDest = readCounter();
                float[][] destVals = readUniforms(prog);
                float[] srcModelView = readSourceMatrix(mGetGbufferModelView);
                float[] srcProjection = readSourceMatrix(mGetGbufferProjection);
                emit(prog, cDest, destVals, srcModelView, srcProjection);

                mainSlotValid = false;
                lastEmitNanos = now;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * §5 RISK self-check: log the first {@value #DEST_SEAM_LOG_CAP} distinct
     * {@code (programId, hasCameraPosition)} pairs seen at this seam DURING the dest pass, so a run
     * always shows whether sodium terrain (an iris {@code cameraPosition} program) routes through
     * {@code GlCommandEncoder.trySetup} here. Bounded so a pathological run can't flood the log.
     */
    private static void recordDestSeam(int prog) {
        if (destSeamPairs.size() >= DEST_SEAM_LOG_CAP) {
            return;
        }
        int camLoc = GlStateManager._glGetUniformLocation(prog, GATE_UNIFORM);
        boolean hasCam = camLoc >= 0;
        String pair = prog + ":" + hasCam;
        if (destSeamPairs.add(pair)) {
            LOGGER.info(P + "dest-seam draw: program={} hasCameraPosition={} (loc={}) — "
                + "if NO true ever appears, sodium terrain is NOT routing through trySetup in the "
                + "dest pass => hook sodium's terrain draw path directly (§5 RISK)", prog, hasCam, camLoc);
        }
    }

    /** Read {@code SystemTimeUniforms.COUNTER.getAsInt()} reflectively. */
    private static int readCounter() throws Exception {
        return (Integer) mCounterGetAsInt.invoke(counterObj);
    }

    /**
     * Read the PER_FRAME uniforms off the bound GL program. Returns a {@code float[][]} parallel to
     * {@link #UNIFORM_NAMES} — each entry is the read floats (length = its component count) or
     * {@code null} if the uniform is absent from this program. {@code glGetUniformfv} takes the program
     * object explicitly, so no bind is needed.
     */
    private static float[][] readUniforms(int prog) {
        float[][] out = new float[UNIFORM_NAMES.length][];
        for (int i = 0; i < UNIFORM_NAMES.length; i++) {
            int loc = GlStateManager._glGetUniformLocation(prog, UNIFORM_NAMES[i]);
            if (loc < 0) {
                out[i] = null;
                continue;
            }
            float[] buf = new float[16]; // fresh; large enough for mat4, holds vec3/vec4 in [0..n)
            GL20.glGetUniformfv(prog, loc, buf);
            int n = UNIFORM_SIZES[i];
            float[] vals = new float[n];
            System.arraycopy(buf, 0, vals, 0, n);
            out[i] = vals;
        }
        return out;
    }

    /** Read a {@code CapturedRenderingState} matrix getter reflectively into a column-major float[16], or null. */
    private static float[] readSourceMatrix(java.lang.reflect.Method getter) {
        try {
            Object m = getter.invoke(capturedInstance);
            if (m instanceof Matrix4fc mat) {
                return mat.get(new float[16]);
            }
        }
        catch (Throwable ignored) {
            // best-effort context — a miss just prints n/a
        }
        return null;
    }

    /** Emit ONE comparison block (main slot vs this dest draw) + the §4 3-way suggested reading. */
    private static void emit(int destProg, int cDest, float[][] destVals,
                             float[] srcModelView, float[] srcProjection) {
        boolean reuploaded = cDest != mainCounter;

        StringBuilder sb = new StringBuilder(1024);
        sb.append(P).append("PER-FRAME UNIFORM COMPARE (main pass vs nested same-dim dest pass):");
        sb.append("\n  COUNTER: main=").append(mainCounter).append(" dest=").append(cDest)
            .append(reuploaded
                ? "  => C_dest != C_main => the dest draw's ProgramUniforms.update() RE-UPLOADED perFrame"
                : "  => C_dest == C_main => NO re-upload (dest kept the main pass's perFrame uniforms)");
        sb.append("\n  program: main=").append(mainProgramId).append(" dest=").append(destProg)
            .append(mainProgramId == destProg ? " (SAME program — clean per-program compare)"
                : " (different programs — PER_FRAME uniforms are global, so the compare still holds)");

        // per-uniform GPU main-vs-dest compare
        boolean anyStrongDiffer = false;
        for (int i = 0; i < UNIFORM_NAMES.length; i++) {
            float[] m = mainVals == null ? null : mainVals[i];
            float[] d = destVals == null ? null : destVals[i];
            boolean strong = i <= 4; // gbufferModelView + celestial dirs (indices 0..4)
            sb.append("\n  ").append(strong ? "[STRONG] " : "[weak]   ").append(UNIFORM_NAMES[i])
                .append(": ");
            if (m == null && d == null) {
                sb.append("absent in both programs");
                continue;
            }
            if (m == null || d == null) {
                sb.append("absent in ").append(m == null ? "MAIN" : "DEST")
                    .append(" program (programs differ) — main=").append(fmt(m))
                    .append(" dest=").append(fmt(d));
                continue;
            }
            Float diff = maxAbsDiff(m, d);
            boolean differ = diff != null && diff > EPS;
            if (strong && differ) {
                anyStrongDiffer = true;
            }
            sb.append(differ ? "DIFFER (dest-framed)" : "EQUAL (dest==main => STALE)")
                .append(" maxAbsDiff=").append(diff)
                .append("\n      main=").append(fmt(m))
                .append("\n      dest=").append(fmt(d));
        }

        // the decisive program-independent leg: dest GPU gbufferModelView vs the reflected dest SOURCE
        float[] destModelViewGpu = destVals == null ? null : destVals[0];
        Float gpuVsSrc = maxAbsDiff(destModelViewGpu, srcModelView);
        Float mainVsSrc = maxAbsDiff(mainVals == null ? null : mainVals[0], srcModelView);
        sb.append("\n  SOURCE (CapturedRenderingState, dest pass — what iris WOULD upload):")
            .append("\n      getGbufferModelView = ").append(fmt(srcModelView))
            .append("\n      getGbufferProjection= ").append(fmt(srcProjection))
            .append("\n      maxAbsDiff(destGPU.gbufferModelView, source)=").append(gpuVsSrc)
            .append("  (~0 => the dest value REACHED the GPU)")
            .append("\n      maxAbsDiff(mainGPU.gbufferModelView, source)=").append(mainVsSrc)
            .append("  (~0 => the SOURCE itself is still MAIN-valued => re-source needed)");

        // context: the live dest camera position (weak — informational)
        sb.append("\n  dest camera pos (informational): ").append(destCameraPosString());

        // the §4 3-way suggested reading
        sb.append("\n  SUGGESTED READING (confirm against the LIVE visual — trust the eye over this):");
        if (anyStrongDiffer) {
            sb.append("\n    => strong uniforms DIFFER (dest-framed) on the GPU. If the terrain STILL"
                + " looks fullbright live => H3: the lighting uniforms are correct; pivot to per-vertex"
                + " lmcoord (stale sky=15 baked into fresh dest sections).");
        }
        else {
            sb.append("\n    => strong uniforms EQUAL main (dest carries the MAIN camera's lighting = the"
                + " fullbright mechanism). ");
            if (reuploaded) {
                boolean srcIsDest = gpuVsSrc != null && mainVsSrc != null && mainVsSrc > EPS;
                sb.append("C_dest != C_main (re-upload ran) => H1: re-source (Option B). ")
                    .append(srcIsDest
                        ? "The SOURCE is DEST-valued yet the GPU is MAIN => the perFrame upload isn't"
                            + " propagating the source (plumbing, closer to H2)."
                        : "The SOURCE reads MAIN-valued => H1 firm: push dest cameraPosition + celestial"
                            + " sun/shadowLight/up + shadow matrices into iris's sources before the dest render.");
            }
            else {
                sb.append("C_dest == C_main (no re-upload) => H2: the bump didn't reach these draws (or"
                    + " this is the bump-OFF baseline). Fix reach/timing, or reset the reused programs'"
                    + " lastFrame.");
            }
        }

        LOGGER.info(sb.toString());
    }

    private static String destCameraPosString() {
        try {
            // 26.2: GameRenderer.getMainCamera() -> mainCamera(); Camera.getPosition() -> position().
            var pos = Minecraft.getInstance().gameRenderer.mainCamera().position();
            return "(" + pos.x + ", " + pos.y + ", " + pos.z + ")";
        }
        catch (Throwable t) {
            return "(unavailable: " + t + ")";
        }
    }

    /**
     * Resolve every reflective handle once. On iris-absent (ClassNotFound) disarms QUIETLY (expected in
     * most runs); on a structural miss (a symbol the jar renamed) disarms LOUDLY, one-shot. Returns
     * readiness. Mirrors {@link ShadowEmptinessProbe#ensureReflection()}.
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
            // SystemTimeUniforms.COUNTER (public static final FrameCounter) -> getAsInt()
            Class<?> stuClass = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms");
            java.lang.reflect.Field fCounter = stuClass.getField("COUNTER");
            counterObj = fCounter.get(null);
            if (counterObj == null) {
                disarm(new IllegalStateException("SystemTimeUniforms.COUNTER is null"));
                return false;
            }
            mCounterGetAsInt = counterObj.getClass().getMethod("getAsInt");

            // CapturedRenderingState.INSTANCE -> getGbufferModelView() / getGbufferProjection()
            Class<?> crsClass = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            capturedInstance = crsClass.getField("INSTANCE").get(null);
            mGetGbufferModelView = crsClass.getMethod("getGbufferModelView");
            mGetGbufferProjection = crsClass.getMethod("getGbufferProjection");

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

    /** Max absolute per-element difference of two equal-length float arrays, or null if either/mismatched. */
    private static Float maxAbsDiff(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return null;
        }
        float m = 0.0f;
        for (int i = 0; i < a.length; i++) {
            m = Math.max(m, Math.abs(a[i] - b[i]));
        }
        return m;
    }

    /** Compact column-major float format for a log line (mat4 grouped in 4s; vec3/vec4 inline). */
    private static String fmt(float[] f) {
        if (f == null) {
            return "n/a";
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append('[');
        for (int i = 0; i < f.length; i++) {
            if (i > 0) {
                sb.append(f.length == 16 && i % 4 == 0 ? " | " : ", ");
            }
            sb.append(f[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        mainSlotValid = false;
        try {
            LOGGER.warn(P + "disarmed after a throw (diagnostic only, render unaffected)", t);
        }
        catch (Throwable ignored) {
            // never let the probe's own failure escape
        }
    }
}
