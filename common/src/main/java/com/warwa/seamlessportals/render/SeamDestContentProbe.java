package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.portal.Portal;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * IS5-SEAM-CONTENT — the dest-frame band-content probe (2026-07-27; lever-gated
 * {@code -Dseamlessportals.seamContentProbe}, DEFAULT OFF — it issues a blocking 1 Hz readback
 * while the camera is inside the crossing window, a visible hitch class).
 *
 * <h2>What question it answers</h2>
 * With the crossing-window clip relax ACTIVE and proven armed (IS5-SEAM-ARM: armedVoidRisk=0,
 * corr to −0.49, feedErr=0.0000) the user still sees the black seam band, and the solid-stamp leg
 * proves the band pixels ARE stamped — so the dest frame in {@code mainRT} is still black there.
 * The discriminating fact is the band pixels' DEST DEPTH:
 * <ul>
 *   <li><b>depth ≈ 0.0 (reversed-Z FAR)</b> ⇒ NOTHING drew those rays — they pass through the
 *       relax's kept slab and the still-clipped region behind it without hitting geometry (the
 *       relax clearance too shallow for the wall-embedded doorway, or genuine void that IP's
 *       unclipped sky would fill);</li>
 *   <li><b>depth substantially &gt; 0</b> ⇒ geometry DREW there and something painted it black —
 *       hunt the pack's processing at extreme-near depth, not the clip.</li>
 * </ul>
 *
 * <h2>How</h2>
 * Right before the stamp (mainRT = the finished dest frame), read ONE center COLUMN
 * (x = width/2, y from 30% to 70% of height — the seam line crosses mid-screen during a level
 * crossing), color + depth, and log 16 bins (mean luminance + mean depth) plus the longest
 * BLACK RUN (lum &lt; {@link #BLACK_LUM}) with that run's depth min/mean/max. The FBO is resolved
 * live via frameBufferCache (ids never captured — mining §8-8) and the readback sits inside the
 * S14.21/IS0 PACK-STATE BRACKET (stale GL_PACK_ROW_LENGTH from copyTextureToBuffer once caused
 * native heap corruption — the double-crash mechanism; save, force tight, restore in reverse).
 */
public final class SeamDestContentProbe {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-SEAM-CONTENT ";

    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.seamContentProbe");

    /** Only sample while the camera is genuinely in the crossing window. */
    private static final double GATE_DIST = 0.5;
    /** Luminance below which a row counts as "black" (8-bit mean of RGB / 255). */
    private static final double BLACK_LUM = 0.02;
    private static final int BINS = 16;

    private static long lastSampleNanos = 0L;
    private static boolean disarmed = false;

    private SeamDestContentProbe() {}

    /** Called from doRenderPortal right before the stamp. Never throws; self-disarms on failure. */
    public static void sample(Portal portal, RenderTarget mainRT) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            Vec3 cam = CHelper.getCurrentCameraPos();
            double dist;
            try {
                dist = portal.getDistanceToNearestPointInPortal(cam);
            }
            catch (Throwable t) {
                return;
            }
            if (dist > GATE_DIST) {
                return;
            }
            long now = System.nanoTime();
            if (now - lastSampleNanos < 1_000_000_000L) {
                return;
            }
            lastSampleNanos = now;
            readAndReport(mainRT, dist);
        }
        catch (Throwable t) {
            disarmed = true;
            LOGGER.warn(P + "probe threw — DISARMED for this session (render unaffected)", t);
        }
    }

    private static void readAndReport(RenderTarget mainRT, double distToAperture) {
        if (!(RenderSystem.getDevice().backend instanceof GlDevice glDevice)
            || !(mainRT.getColorTextureView() instanceof GlTextureView colorView)
            || !(mainRT.getDepthTextureView() instanceof GlTextureView depthView)
        ) {
            disarmed = true;
            LOGGER.info(P + "non-GL backend or missing main texture views — DISARMED");
            return;
        }
        int fbo = glDevice.frameBufferCache().getFbo(
            glDevice.directStateAccess(), List.of(colorView), depthView
        );
        int x = mainRT.width / 2;
        int y0 = (int) (mainRT.height * 0.30);
        int stripH = Math.max(BINS, (int) (mainRT.height * 0.40));
        if (y0 + stripH > mainRT.height) {
            stripH = mainRT.height - y0;
        }

        int prevRead = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);

        // PACK-STATE BRACKET (the IS0 double-crash fix — see class javadoc): save, force tight,
        // read, restore in exact reverse.
        int prevPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int prevPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int prevPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
        ByteBuffer colors = BufferUtils.createByteBuffer(stripH * 4);
        FloatBuffer depths = BufferUtils.createFloatBuffer(stripH);
        GL11.glReadPixels(x, y0, 1, stripH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colors);
        GL11.glReadPixels(x, y0, 1, stripH, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depths);
        int glErr = GL11.glGetError();
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevPackSkipRows);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevPackRowLength);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);

        // 16 bins bottom→top: mean luminance + mean depth per bin.
        StringBuilder bins = new StringBuilder();
        int per = stripH / BINS;
        for (int b = 0; b < BINS; b++) {
            double lum = 0;
            double dep = 0;
            for (int i = b * per; i < (b + 1) * per; i++) {
                int r = colors.get(i * 4) & 0xFF;
                int g = colors.get(i * 4 + 1) & 0xFF;
                int bl = colors.get(i * 4 + 2) & 0xFF;
                lum += (r + g + bl) / (3.0 * 255.0);
                dep += depths.get(i);
            }
            bins.append(String.format("[%d l=%.2f d=%.4f]", b, lum / per, dep / per));
        }

        // The longest black run and ITS depth stats — the discriminating numbers.
        int bestStart = -1;
        int bestLen = 0;
        int curStart = -1;
        int curLen = 0;
        for (int i = 0; i < stripH; i++) {
            int r = colors.get(i * 4) & 0xFF;
            int g = colors.get(i * 4 + 1) & 0xFF;
            int bl = colors.get(i * 4 + 2) & 0xFF;
            boolean black = ((r + g + bl) / (3.0 * 255.0)) < BLACK_LUM;
            if (black) {
                if (curStart < 0) {
                    curStart = i;
                    curLen = 0;
                }
                curLen++;
                if (curLen > bestLen) {
                    bestLen = curLen;
                    bestStart = curStart;
                }
            }
            else {
                curStart = -1;
            }
        }
        String runReport;
        if (bestLen == 0) {
            runReport = "NO black run (lum >= " + BLACK_LUM + " everywhere in the strip)";
        }
        else {
            float dMin = Float.POSITIVE_INFINITY;
            float dMax = Float.NEGATIVE_INFINITY;
            double dSum = 0;
            for (int i = bestStart; i < bestStart + bestLen; i++) {
                float d = depths.get(i);
                dMin = Math.min(dMin, d);
                dMax = Math.max(dMax, d);
                dSum += d;
            }
            runReport = "BLACK RUN rows " + bestStart + ".." + (bestStart + bestLen - 1)
                + " (" + bestLen + "px of " + stripH + ") depth min=" + dMin
                + " mean=" + (dSum / bestLen) + " max=" + dMax
                + " -> depth~0.0 = NOTHING DREW (void class: rays pass everything; reversed-Z"
                + " FAR=0.0); depth substantially >0 = geometry drew and was PAINTED black"
                + " (pack-pass class)";
        }

        LOGGER.info(P + "(1Hz, distToAperture=" + String.format("%.4f", distToAperture)
            + ", column x=" + x + " y=" + y0 + "..+" + stripH + ", fbo=" + fbo
            + ", glErr=" + glErr + ", packRowLenWasStale=" + (prevPackRowLength != 0) + ") "
            + runReport + " | bins b0(bottom)..b15(top): " + bins);
    }
}
