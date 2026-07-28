package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * IS5-HAND-FUNC — the hand depth-func fix (2026-07-28; DEFAULT ON, A/B
 * {@code -PdisableHandDepthFuncFix}).
 *
 * <h2>The measured mechanism (the whole hand arc's root cause)</h2>
 * The process carries a DEPTH-FUNC LEAK under iris: the driver's {@code GL_DEPTH_FUNC} reads
 * {@code LEQUAL} at every hand-pass boundary and at every stamp draw (IS5-STAMP-EXEC 15/15 +
 * 20/20; IS5-HAND-TAP depthAtRet on every row), while 26.2 reversed-Z declares {@code GEQUAL}.
 * The conviction A/B (2026-07-28): with the seam depth bracket ON the hand NEVER rasterizes
 * in-window (IS5-HAND-INLVL, 14/14 blocks — under LEQUAL the bracket's [0.999,1] remap loses
 * to everything); with the bracket OFF the hand paints colortex0 on EVERY in-window block
 * (6/6) but loses the depth test against far content = the visible slicing. Every prior fix
 * (the bracket, the stamp cap) was designed for GEQUAL semantics and INVERTED by the leak.
 * Gate/body/camera-identity/equip-height/transforms were all exonerated by the tap legs first.
 *
 * <h2>The fix</h2>
 * Bracket both iris hand passes: at HEAD save the driver's ACTUAL func (raw glGet — the
 * GlStateManager cache is the desync suspect, never trust it here), force {@code GL_GEQUAL}
 * with BOTH a raw {@code glDepthFunc} (driver truth) and {@code GlStateManager._depthFunc}
 * (cache coherence — so per-draw pipeline applies neither fight nor miss it); at RETURN
 * restore the saved raw func the same double-write way (the DRIVER is byte-identical after;
 * the CACHE converges to coherent-with-driver, so in the desync case the first post-pass
 * apply declaring GEQUAL now really writes it — an effect past RETURN, strictly in the
 * toward-declared direction). Scope: main-frame passes only (nested dest-pass hands
 * excluded via {@code PortalRendering.isRendering()}), shaders-ON only (the leak and the
 * symptom are compat-route-only). Composes with the bracket: under true GEQUAL the bracket's
 * remap finally BEATS the grazing shell as designed — together they close both halves of the
 * slicing (far-content loss and shell-vs-slice loss).
 *
 * <p>Self-reporting: a once-only ARMED line, plus a 1 Hz census of the func actually FOUND at
 * HEAD ({@code foundLEQUAL/foundGEQUAL/foundOther}) — the leak's evidence trail in every leg.
 * Never throws into iris; self-disarms on failure with one WARN.
 */
@Environment(EnvType.CLIENT)
public final class SeamHandDepthFuncFix {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-HAND-FUNC ";

    private static boolean armed = false;
    private static int savedFunc = GL11.GL_GEQUAL;
    private static boolean announced = false;
    private static boolean disarmedForSession = false;

    // 1 Hz found-func census (the leak evidence trail; latched to respect the
    // render-thread-logging rule).
    private static long lastCensusNanos = 0L;
    private static int lastCensusTotal = -1;
    private static int foundLequal = 0;
    private static int foundGequal = 0;
    private static int foundOther = 0;

    private SeamHandDepthFuncFix() {}

    /** HEAD of both iris hand passes (via the SubmitTap mixin — probe-first ordering). */
    public static void begin() {
        if (disarmedForSession || IPGlobal.HAND_DEPTH_FUNC_FIX_DISABLED_LEVER) {
            return;
        }
        if (armed) {
            // Stale bracket from an exceptional exit (RETURN inject never fired — the client
            // is normally dead by then; bracket-parity self-heal for the catch-and-continue
            // hypothetical). Drop the arm; the fresh begin below re-reads the live driver.
            armed = false;
        }
        boolean forced = false;
        try {
            if (PortalRendering.isRendering()) {
                return; // nested dest-pass hand — leave iris's own compositing alone
            }
            if (!IrisInterface.invoker.isShaders()) {
                return; // shaders-OFF hand is vanilla's post-anchor draw; the leak is compat-only
            }
            savedFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            if (savedFunc == GL11.GL_LEQUAL) {
                foundLequal++;
            }
            else if (savedFunc == GL11.GL_GEQUAL) {
                foundGequal++;
            }
            else {
                foundOther++;
            }
            // Double-write: raw first (driver truth), cache-coherent second (so the pipeline
            // applies' diffing sees reality). Order matters only for clarity — both end equal.
            GL11.glDepthFunc(GL11.GL_GEQUAL);
            forced = true;
            GlStateManager._depthFunc(GL11.GL_GEQUAL);
            armed = true;
            if (!announced) {
                announced = true;
                LOGGER.info(P + "ARMED (once-only): iris hand passes forced to the declared"
                    + " reversed-Z GEQUAL (driver func found at first arm: 0x{}) — the measured"
                    + " LEQUAL leak inverted the depth bracket and caused the seam hand"
                    + " slicing. A/B: -PdisableHandDepthFuncFix. A leg without this line never"
                    + " armed.", Integer.toHexString(savedFunc));
            }
            long now = System.nanoTime();
            if (now - lastCensusNanos >= 1_000_000_000L
                && foundLequal + foundGequal + foundOther != lastCensusTotal) {
                lastCensusNanos = now;
                lastCensusTotal = foundLequal + foundGequal + foundOther;
                LOGGER.info(P + "found-func census (1Hz, on-change): LEQUAL={} GEQUAL={}"
                    + " other={} — nonzero LEQUAL = the leak is live and this fix is doing"
                    + " real work.", foundLequal, foundGequal, foundOther);
            }
        }
        catch (Throwable t) {
            disarmedForSession = true;
            armed = false;
            // Best-effort restore if the throw landed between the force and the arm
            // (bracket-parity; realistically unreachable — all throwing calls precede it).
            if (forced) {
                try {
                    GL11.glDepthFunc(savedFunc);
                    GlStateManager._depthFunc(savedFunc);
                }
                catch (Throwable ignored) {
                }
            }
            LOGGER.warn(P + "fix threw — DISARMED for this session (hand keeps the leaked"
                + " func = the pre-fix slicing)", t);
        }
    }

    /** RETURN of both iris hand passes — restores the ambient func byte-identically. */
    public static void end() {
        if (!armed) {
            return;
        }
        armed = false;
        try {
            GL11.glDepthFunc(savedFunc);
            GlStateManager._depthFunc(savedFunc);
        }
        catch (Throwable t) {
            disarmedForSession = true;
            LOGGER.warn(P + "restore threw — DISARMED for this session", t);
        }
    }
}
