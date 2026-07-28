package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * IS5-STAMP-EXEC — the stamp EXECUTED-STATE probe (2026-07-27; lever-gated
 * {@code -Dseamlessportals.stampExecProbe}, DEFAULT OFF — pure GL gets, no binds, no draws).
 *
 * <h2>Why (handoff §00a instrument 2, escalated)</h2>
 * The stage diff measured the stamp writing depth ≈0.65 into the deferred buffer at the hand
 * column — impossible under the DECLARED stamp state twice over: (a) the capped .vsh clamps
 * every fragment to ≤0.5 (per-vertex min, window-space-linear depth interpolation cannot
 * exceed the vertex max), and (b) GEQUAL-with-write can only ever RAISE a pixel's depth, yet
 * B→C mean depth FELL 0.98→0.65 on every painted bin. The once-only {@code vsh=capped} line
 * echoes the LEVER, not the program, so it proves nothing about the executing GLSL. This probe
 * reads the driver's own answer at the point of effect: called immediately after the stamp's
 * {@code drawIndexed} (state applied inside the draw), it queries the ACTUAL program id, the
 * attached vertex shader's SOURCE (cap substring present?), and the ACTUAL depth
 * test/func/mask/clamp/range + draw-FBO binding the draw executed under.
 *
 * <p>Emissions: one FULL block on the first stamp of the session (includes the entire vertex
 * shader source — the ground-truth dump the audit requires), then one compact line per second
 * while the camera is inside the crossing window (the frames whose measurements created the
 * anomaly). Never throws into the stamp; self-disarms on failure. Every query drains
 * {@code glGetError} so a failed get is a loud sentinel, never a silent zero.
 */
public final class StampExecStateProbe {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-STAMP-EXEC ";

    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.stampExecProbe");

    private static final double WINDOW = 0.35;

    private static boolean disarmed = false;
    private static boolean announced = false;
    private static boolean fullDumpDone = false;
    private static long lastWindowNanos = 0L;
    private static long lastAmbientNanos = 0L;

    private StampExecStateProbe() {}

    /** Called right after the stamp's drawIndexed (RenderPass still open; state as-executed). */
    public static void afterStampDraw(String pipelineName) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            if (!announced) {
                // Liveness announce (adversarial-verify finding 5): lever ON + zero output must
                // be distinguishable from "call site never reached" — a silent leg is VOID, not
                // a refutation.
                announced = true;
                LOGGER.info(P + "ARMED (once-only): executed-state sampling live at the stamp"
                    + " draw (full dump on the first stamp, then 1 Hz inside the crossing"
                    + " window). A leg without this line never reached the stamp call site.");
            }
            boolean firstDump = !fullDumpDone;
            boolean windowSample = false;
            boolean ambientSample = false;
            if (!firstDump) {
                long now = System.nanoTime();
                if (now - lastWindowNanos >= 1_000_000_000L && isInCrossingWindow()) {
                    lastWindowNanos = now;
                    windowSample = true;
                }
                else if (now - lastAmbientNanos >= 10_000_000_000L) {
                    // AMBIENT (out-of-window) sample, ~0.1 Hz (instrument-every-branch: the
                    // 2026-07-28 leg sampled ONLY in-window stamps and could not say whether
                    // the LEQUAL leak is window-correlated or universal — this branch splits
                    // that on the next leg).
                    lastAmbientNanos = now;
                    ambientSample = true;
                }
            }
            if (!firstDump && !windowSample && !ambientSample) {
                return;
            }
            fullDumpDone = true;
            sample(pipelineName, firstDump, ambientSample);
        }
        catch (Throwable t) {
            disarmed = true;
            LOGGER.warn(P + "probe threw — DISARMED for this session (stamp unaffected)", t);
        }
    }

    private static boolean isInCrossingWindow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }
        Vec3 cam = CHelper.getCurrentCameraPos();
        List<Portal> portals = McHelper.getEntitiesNearby(mc.player, Portal.class, 2.0);
        for (Portal portal : portals) {
            if (portal instanceof Mirror) {
                continue;
            }
            try {
                if (portal.getDistanceToNearestPointInPortal(cam) < WINDOW) {
                    return true;
                }
            }
            catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void sample(String pipelineName, boolean fullDump, boolean ambient) {
        GL11.glGetError(); // drain pre-existing

        int prog = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean depthClamp = GL11.glIsEnabled(GL32.GL_DEPTH_CLAMP);
        FloatBuffer range = BufferUtils.createFloatBuffer(16); // driver may write 2; be generous
        GL11.glGetFloatv(GL11.GL_DEPTH_RANGE, range);
        int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        IntBuffer viewport = BufferUtils.createIntBuffer(16);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int stateErr = GL11.glGetError();

        String vshInfo = "prog=0";
        String vshSource = null;
        boolean capPresent = false;
        if (prog != 0) {
            IntBuffer countBuf = BufferUtils.createIntBuffer(1);
            IntBuffer shaders = BufferUtils.createIntBuffer(8);
            GL20.glGetAttachedShaders(prog, countBuf, shaders);
            int n = countBuf.get(0);
            int vshId = 0;
            for (int i = 0; i < n; i++) {
                int sh = shaders.get(i);
                if (GL20.glGetShaderi(sh, GL20.GL_SHADER_TYPE) == GL20.GL_VERTEX_SHADER) {
                    vshId = sh;
                    break;
                }
            }
            if (vshId != 0) {
                vshSource = GL20.glGetShaderSource(vshId);
                capPresent = vshSource != null && vshSource.contains("0.5 * gl_Position.w");
                vshInfo = "vshId=" + vshId + " srcLen="
                    + (vshSource == null ? "READ-FAILED" : vshSource.length())
                    + " CAP-IN-SOURCE=" + (vshSource == null ? "UNMEASURED" : capPresent);
            }
            else {
                vshInfo = "NO-VERTEX-SHADER-ATTACHED(n=" + n + ")";
            }
        }
        int shaderErr = GL11.glGetError();

        String line = P + (fullDump ? "FULL DUMP (first stamp of the session)"
                : (ambient ? "AMBIENT sample (out-of-window)" : "window sample"))
            + ": pipeline=" + pipelineName
            + " prog=" + prog + " " + vshInfo
            + " | depthTest=" + depthTest
            + " func=0x" + Integer.toHexString(depthFunc) + "(" + funcName(depthFunc) + ")"
            + " writeMask=" + depthMask
            + " clamp=" + depthClamp
            + String.format(" range=[%.4f,%.4f]", range.get(0), range.get(1))
            + " drawFbo=" + drawFbo
            + " viewport=" + viewport.get(0) + "," + viewport.get(1) + ","
            + viewport.get(2) + "x" + viewport.get(3)
            + " | glErr: state=0x" + Integer.toHexString(stateErr)
            + " shader=0x" + Integer.toHexString(shaderErr)
            + " | EXPECTED for the declared pipeline: func=GEQUAL writeMask=true clamp=true"
            + " range=[0,1] CAP-IN-SOURCE=true (lever off). ANY mismatch here is the anomaly's"
            + " mechanism, measured at the point of effect.";
        LOGGER.info(line);
        if (fullDump && vshSource != null) {
            LOGGER.info(P + "vertex shader SOURCE as compiled by the driver (prog=" + prog
                + "):\n" + vshSource);
        }
    }

    private static String funcName(int func) {
        return switch (func) {
            case GL11.GL_NEVER -> "NEVER";
            case GL11.GL_LESS -> "LESS";
            case GL11.GL_EQUAL -> "EQUAL";
            case GL11.GL_LEQUAL -> "LEQUAL";
            case GL11.GL_GREATER -> "GREATER";
            case GL11.GL_NOTEQUAL -> "NOTEQUAL";
            case GL11.GL_GEQUAL -> "GEQUAL";
            case GL11.GL_ALWAYS -> "ALWAYS";
            default -> "?";
        };
    }
}
