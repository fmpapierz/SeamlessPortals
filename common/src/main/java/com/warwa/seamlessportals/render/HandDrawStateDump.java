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

/**
 * IS5-HAND-DRAW — the DRAW-TIME state dump (2026-07-28; lever-gated
 * {@code -Dseamlessportals.handDrawDump}, DEFAULT OFF).
 *
 * <h2>Why (the boundary-read reckoning)</h2>
 * The CLIP0 leg proved boundary reads cannot see the draws: the solid pass's internals re-set
 * the depth func between our HEAD force and the RETURN read (solid RETURN=LEQUAL, drawless
 * translucent RETURN=kept GEQUAL — the asymmetry proof). Every surviving hypothesis — func at
 * draw, range at draw, scissor, stencil, colorMask, GL_RASTERIZER_DISCARD — is only decidable
 * DURING an actual hand draw. This dump fires at {@code GlCommandEncoder.trySetup} RETURN
 * (state fully applied, immediately before the GL draw call) while iris's
 * {@code HandRenderer.INSTANCE.isActive()} — i.e. for REAL hand-feature draws only — and
 * reads the complete relevant GL state. One line per second, tagged [window/ambient]; the
 * window-vs-ambient diff of any field names the eater.
 */
public final class HandDrawStateDump {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-HAND-DRAW ";

    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.handDrawDump");

    private static final double WINDOW = 0.35;

    private static boolean disarmed = false;
    private static boolean announced = false;
    private static long lastNanos = 0L;

    private HandDrawStateDump() {}

    /** Called from the encoder mixin right after trySetup returned true, iff iris's
     *  HandRenderer is ACTIVE (the caller checks — this class stays iris-free). */
    public static void onHandDrawSetup(boolean renderingSolidPass) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (now - lastNanos < 1_000_000_000L) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            lastNanos = now;
            if (!announced) {
                announced = true;
                LOGGER.info(P + "ARMED (once-only): full GL state at REAL hand-feature draws"
                    + " (trySetup RETURN, pre-draw-call), 1 Hz, window and ambient. A leg"
                    + " without this line saw no hand draws at all while the lever was on —"
                    + " itself a datum (the dispatch emitted zero draws).");
            }

            double dist = distToNearestCrossablePortal(mc);
            String tag = dist < WINDOW ? String.format("[window d=%.2f]", dist) : "[AMBIENT]";

            for (int i = 0; i < 8 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
            }
            int prog = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            int func = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            FloatBuffer range = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloatv(GL11.GL_DEPTH_RANGE, range);
            boolean clip0 = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);
            boolean clip1 = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0 + 1);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            IntBuffer scissorBox = BufferUtils.createIntBuffer(16);
            if (scissor) {
                GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            }
            boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            int stencilFunc = stencil ? GL11.glGetInteger(GL11.GL_STENCIL_FUNC) : 0;
            boolean discard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
            boolean a2c = GL11.glIsEnabled(GL32.GL_SAMPLE_ALPHA_TO_COVERAGE);
            IntBuffer colorMask = BufferUtils.createIntBuffer(16);
            GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask);
            IntBuffer viewport = BufferUtils.createIntBuffer(16);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            int err = GL11.glGetError();

            String funcName = switch (func) {
                case GL11.GL_LEQUAL -> "LEQUAL";
                case GL11.GL_GEQUAL -> "GEQUAL";
                case GL11.GL_ALWAYS -> "ALWAYS";
                case GL11.GL_LESS -> "LESS";
                case GL11.GL_GREATER -> "GREATER";
                default -> "0x" + Integer.toHexString(func);
            };
            LOGGER.info(P + "{} pass={} prog={} | depth: test={} func={} write={}"
                    + " range=[{},{}] | clip0={} clip1={} | scissor={}{} | stencil={}{}"
                    + " | DISCARD={} a2c={} | colorMask={},{},{},{} | vp={},{},{}x{} fbo={}"
                    + " blend={} glErr=0x{} — this IS the executed state of a real hand draw;"
                    + " diff window vs AMBIENT rows field by field: the differing field is the"
                    + " eater. DISCARD=true would explain everything measured.",
                tag, renderingSolidPass ? "solid" : "translucent", prog,
                depthTest, funcName, depthMask,
                String.format("%.4f", range.get(0)), String.format("%.4f", range.get(1)),
                clip0, clip1,
                scissor, scissor ? (" box=" + scissorBox.get(0) + "," + scissorBox.get(1)
                    + "," + scissorBox.get(2) + "x" + scissorBox.get(3)) : "",
                stencil, stencil ? (" func=0x" + Integer.toHexString(stencilFunc)) : "",
                discard, a2c,
                colorMask.get(0), colorMask.get(1), colorMask.get(2), colorMask.get(3),
                viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3), drawFbo,
                blend, Integer.toHexString(err));
        }
        catch (Throwable t) {
            disarmed = true;
            LOGGER.warn(P + "dump threw — DISARMED for this session (draws unaffected)", t);
        }
    }

    private static double distToNearestCrossablePortal(Minecraft mc) {
        double best = Double.MAX_VALUE;
        Vec3 cam = CHelper.getCurrentCameraPos();
        for (Portal portal : McHelper.getEntitiesNearby(mc.player, Portal.class, 2.0)) {
            if (portal instanceof Mirror) {
                continue;
            }
            try {
                double d = portal.getDistanceToNearestPointInPortal(cam);
                if (d < best) {
                    best = d;
                }
            }
            catch (Throwable ignored) {
            }
        }
        return best;
    }
}
