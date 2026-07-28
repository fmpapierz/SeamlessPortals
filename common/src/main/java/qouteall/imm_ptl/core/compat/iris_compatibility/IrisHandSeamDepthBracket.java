package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.List;

/**
 * IS5-HAND V2 — the crossing-window hand depth bracket (2026-07-27; DEFAULT ON, A/B
 * {@code -PdisableHandSeamDepthBracket}).
 *
 * <h2>The measured mechanism (probe-adjudicated, two refuted fixes deep)</h2>
 * Shaders-ON, iris draws the first-person hand ITSELF inside {@code LevelRenderer.render}
 * ({@code HandRenderer.renderSolid/renderTranslucent}) with a compressed depth slice MEASURED at
 * 0.5544..0.5560 (reversed-Z), depth-tested against the scene. During a portal crossing the
 * seam's 5–10 cm grazing shell (depth 0.56..1.0) BEATS that slice, so the hand is shredded IN
 * THE MAIN FRAME before any compat machinery runs (hand-region probe: snapshot bins alternating
 * hand-slice depth with shell depth at world luminance on deep-crossing seconds). The clip
 * family was exonerated live ({@code front_clipping disable} ⇒ unchanged); the stamp depth cap
 * was refuted as the dominant carrier (armed and byte-proven, symptom identical). Shaders-off is
 * immune because vanilla wipes depth before its (post-anchor) hand draw.
 *
 * <h2>The fix</h2>
 * While the camera is inside the crossing window of a crossable portal, bracket iris's two hand
 * passes with {@code glDepthRange(0.999, 1.0)}: the hand's slice remaps into the top 0.1% of the
 * depth range, beating the shell everywhere except a ~0.05 mm-equivalent hairline. Intra-hand
 * ordering compresses to ~27 representable depth levels for the window frames (verify-fold:
 * slice span 1.6e-3 × 0.001 vs 2^-24 spacing) — deltas finer than ~4% of the hand's depth span
 * can z-fight; if that shows live, widen {@code NEAR_LO} to 0.99 (~270 levels, hairline grows to
 * ~0.23 mm-equivalent). The stamp's own depth cap (≤ 0.5) can never overpaint the remapped hand
 * — the two fixes compose. Depth range is raw GL that NOTHING else in MC/iris/sodium ever sets
 * (verify-fold: zero {@code glDepthRange} references in all three jars), so the restore in
 * {@code end()} is the only writer; the ACCEPTED RISK is a throw inside iris's pass between our
 * HEAD and RETURN — the RETURN inject never fires and the range stays remapped, but such a throw
 * propagates out of {@code LevelRenderer.render} (client terminates) and {@code begin()}
 * self-heals a stale bracket at the next pass regardless. Nested (dest-pass) hands are excluded
 * via {@code PortalRendering.isRendering()}; shaders-OFF frames early-out before the entity
 * query (iris calls these passes unconditionally, its own pack gate sits after our HEAD).
 * Pack-side cost while armed: depth-linked pack effects (DOF/fog) see the hand at near-plane
 * depth for crossing-window frames only.
 *
 * <p>Never throws (our code); a failure disarms for the session with one WARN. The first arm
 * prints a once-only INFO — a leg whose log lacks it while the lever is ON never bracketed
 * anything (the mixin did not land) and must not be adjudicated as a refutation.
 */
@Environment(EnvType.CLIENT)
public final class IrisHandSeamDepthBracket {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Same crossing-window scale as the V2 clip suspension (FrontClipping.SUSPEND_ZONE). */
    private static final double WINDOW = 0.35;

    /** The hand slice (0.5544..0.5560 measured) remaps into [NEAR_LO, 1.0]. */
    private static final double NEAR_LO = 0.999;

    private static boolean armed = false;
    private static boolean announced = false;
    private static boolean disarmedForSession = false;

    private IrisHandSeamDepthBracket() {}

    /** HEAD of both iris hand passes (via the mixin). */
    public static void begin() {
        if (disarmedForSession || IPGlobal.HAND_SEAM_DEPTH_BRACKET_DISABLED_LEVER) {
            return;
        }
        if (armed) {
            // Stale bracket from an exceptional exit inside iris's pass (our RETURN inject never
            // fired). Self-heal NOW rather than early-returning on it, then re-evaluate fresh.
            armed = false;
            try {
                GL11.glDepthRange(0.0, 1.0);
            }
            catch (Throwable ignored) {
            }
        }
        try {
            if (PortalRendering.isRendering()) {
                return; // nested dest-pass hand: leave iris's own compositing alone
            }
            if (!IrisInterface.invoker.isShaders()) {
                return; // iris calls these passes even shaders-OFF (its pack gate sits after our
                        // HEAD); the shaders-OFF hand is vanilla's post-anchor draw, not this one
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            Vec3 cam = CHelper.getCurrentCameraPos();
            List<Portal> portals = McHelper.getEntitiesNearby(mc.player, Portal.class, 2.0);
            boolean inWindow = false;
            for (Portal portal : portals) {
                if (portal instanceof Mirror) {
                    continue; // never crossed — same exclusion as the clip-window gates
                }
                try {
                    if (portal.getDistanceToNearestPointInPortal(cam) < WINDOW) {
                        inWindow = true;
                        break;
                    }
                }
                catch (Throwable ignored) {
                    // treated as "not in window" — the safe direction
                }
            }
            if (!inWindow) {
                return;
            }
            GL11.glDepthRange(NEAR_LO, 1.0);
            armed = true;
            if (!announced) {
                announced = true;
                LOGGER.info("[Seamless Portals] IS5-HAND depth bracket ARMED (once-only): iris"
                    + " hand passes draw at glDepthRange({}, 1.0) while the camera is within {}"
                    + " of a crossable portal — the measured hand slice (0.554..0.556) now beats"
                    + " the seam's grazing shell. A/B: -PdisableHandSeamDepthBracket.",
                    NEAR_LO, WINDOW);
            }
        }
        catch (Throwable t) {
            disarmedForSession = true;
            // If the range was set before the throw, put it back — never leak the bracket.
            try {
                GL11.glDepthRange(0.0, 1.0);
            }
            catch (Throwable ignored) {
            }
            armed = false;
            LOGGER.warn("[Seamless Portals] IS5-HAND depth bracket threw — DISARMED for this"
                + " session (hand rendering unaffected beyond losing the fix)", t);
        }
    }

    /** RETURN of both iris hand passes (via the mixin). Restores unconditionally when armed. */
    public static void end() {
        if (armed) {
            armed = false;
            try {
                GL11.glDepthRange(0.0, 1.0);
            }
            catch (Throwable t) {
                disarmedForSession = true;
                LOGGER.warn("[Seamless Portals] IS5-HAND depth bracket restore threw —"
                    + " DISARMED for this session", t);
            }
        }
    }
}
