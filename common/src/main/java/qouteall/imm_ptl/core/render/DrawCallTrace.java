package qouteall.imm_ptl.core.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import qouteall.q_misc_util.Helper;

/**
 * S14.31 — the ONE-FRAME DRAW-CALL TRACER (round-5 decisive instrument). Seven adversarial hunt
 * rounds exonerated every theorized painter of the rung-2 sky wedges; this stops theorizing and
 * RECORDS one frame: every render pass (debug label), every pipeline bind (RenderPipeline
 * location), and the mod's own choreography markers, in order. The wedge painter is necessarily
 * one of these lines.
 *
 * <p>Usage: {@code /imm_ptl_client_debug debug_capture_frame_enable} arms ONE capture; the next
 * frame is recorded start-to-end and dumped as a SINGLE log write at the frame tail (one log4j
 * call — the render-thread-logging rule is respected; this is a one-shot diagnostic, S20-removal
 * ledgered like the other levers).
 */
@Environment(EnvType.CLIENT)
public class DrawCallTrace {

    /** Set by the debug command; consumed at the next frame start. */
    public static volatile boolean armed = false;

    /** True for exactly one frame. Readers are render-thread-only. */
    public static boolean capturing = false;

    private static final int MAX_CHARS = 400_000;
    private static final StringBuilder SB = new StringBuilder(1 << 16);

    public static void onFrameStart() {
        if (armed) {
            armed = false;
            capturing = true;
            SB.setLength(0);
            SB.append("=== DRAW TRACE (one frame) ===\n");
        }
    }

    public static void onFrameEnd() {
        if (capturing) {
            capturing = false;
            SB.append("=== DRAW TRACE end ===");
            Helper.log("\n" + SB);
            SB.setLength(0);
        }
    }

    public static void record(String line) {
        if (capturing && SB.length() < MAX_CHARS) {
            SB.append(line).append('\n');
        }
    }

    /**
     * S14.33 (v3, the NO-GUESSING rule): dump the given SkyRenderState's full numeric contents +
     * the current level's gameTime — called at every sky-family pass creation (main state) and at
     * the dest-pass marker (dest state). The wedge frame's numbers vs the control frame's ARE the
     * answer to which value is polluted.
     */
    public static void recordSkyState(String tag, net.minecraft.client.renderer.state.level.SkyRenderState s) {
        if (!capturing) {
            return;
        }
        if (s == null) {
            record(tag + " skyState=null");
            return;
        }
        net.minecraft.client.multiplayer.ClientLevel level =
            net.minecraft.client.Minecraft.getInstance().level;
        record(tag
            + " skybox=" + s.skybox
            + " sunAngle=" + s.sunAngle
            + " moonAngle=" + s.moonAngle
            + " starAngle=" + s.starAngle
            + " moonPhase=" + s.moonPhase
            + " rainBrightness=" + s.rainBrightness
            + " starBrightness=" + s.starBrightness
            + " skyColor=" + Integer.toHexString(s.skyColor)
            + " sunriseSunsetColor=" + Integer.toHexString(s.sunriseAndSunsetColor)
            + " darkDisc=" + s.shouldRenderDarkDisc
            + " levelGameTime=" + (level != null ? level.getGameTime() : -1)
            + " levelDim=" + (level != null ? level.dimension().identifier() : "null"));
    }
}
