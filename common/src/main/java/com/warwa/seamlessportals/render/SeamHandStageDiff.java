package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * IS5-HAND-STAGE — the four-point STAGE DIFF (2026-07-27; lever-gated
 * {@code -Dseamlessportals.handStageDiff}, DEFAULT OFF — four blocking readbacks per sampled
 * frame, crossing-window only).
 *
 * <h2>Why hypothesis-free</h2>
 * THREE config-proven interventions were refuted on the seam hand-slicing with byte-identical
 * symptom (clip family · stamp depth cap · iris-hand depth bracket — handoff §00a), and the one
 * measurement that pointed into the snapshot rested on a single mixed probe row. This instrument
 * stops theorizing: it captures the SAME hand-region pixels (color + depth) at four pipeline
 * stages inside ONE frame and diffs them — the stage where the hand pixels change names the
 * eater by construction.
 *
 * <h2>The four stages</h2>
 * <ol>
 *   <li><b>A anchor-mainRT</b> — {@code onBeforeHandRendering} entry: the main frame exactly as
 *       iris finalized it (the hand should be baked here);</li>
 *   <li><b>B snapshot-deferred</b> — right after the snapshot copy (depth {@code copyDepthFrom}
 *       + color straight-copy): what the compat pass preserved;</li>
 *   <li><b>C post-stamp-deferred</b> — after the last portal's stamp: what the stamps painted
 *       over the snapshot;</li>
 *   <li><b>D post-blit-mainRT</b> — after the blit-back: what the frame ships to the (no-op'd
 *       vanilla hand slot and the) GUI.</li>
 * </ol>
 * One log block per sampled frame (1 Hz, camera within {@link #WINDOW} of a crossable portal)
 * with per-stage 8-bin luminance+depth summaries and A→B / B→C / C→D changed-bin flags.
 * Chassis: the SeamDestContentProbe FBO resolver + the S14.21/IS0 pack-state bracket.
 */
public final class SeamHandStageDiff {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-HAND-STAGE ";

    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.handStageDiff");

    private static final double WINDOW = 0.35;
    private static final int BINS = 8;
    /** Luminance delta that marks a bin as CHANGED between consecutive stages. */
    private static final double CHANGE_LUM = 0.08;

    private static boolean disarmed = false;
    private static long lastFrameNanos = 0L;
    private static boolean frameArmed = false;
    /** Per-stage summaries for the armed frame: [stage][bin][0=lum,1=depth]; -1 = not captured. */
    private static final double[][][] stages = new double[4][BINS][2];
    private static final boolean[] captured = new boolean[4];
    private static int stampCaptures = 0;

    private SeamHandStageDiff() {}

    /** Stage A — onBeforeHandRendering entry (mainRT valid, pre-snapshot). Decides arming. */
    public static void stageA(RenderTarget mainRT) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            frameArmed = false;
            long now = System.nanoTime();
            if (now - lastFrameNanos < 1_000_000_000L) {
                return;
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
                    continue;
                }
                try {
                    if (portal.getDistanceToNearestPointInPortal(cam) < WINDOW) {
                        inWindow = true;
                        break;
                    }
                }
                catch (Throwable ignored) {
                }
            }
            if (!inWindow) {
                return;
            }
            lastFrameNanos = now;
            frameArmed = true;
            captured[0] = captured[1] = captured[2] = captured[3] = false;
            stampCaptures = 0;
            capture(0, mainRT);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Stage B — right after the snapshot copy into the deferred buffer. */
    public static void stageB(RenderTarget deferred) {
        if (!frameArmed || disarmed) {
            return;
        }
        try {
            capture(1, deferred);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Stage C — after a stamp (per portal; the LAST capture of the frame wins). */
    public static void stageC(RenderTarget deferred) {
        if (!frameArmed || disarmed) {
            return;
        }
        try {
            stampCaptures++;
            capture(2, deferred);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Stage D — after the blit-back; emits the diff block and closes the frame. */
    public static void stageD(RenderTarget mainRT) {
        if (!frameArmed || disarmed) {
            return;
        }
        try {
            capture(3, mainRT);
            emit();
        }
        catch (Throwable t) {
            disarm(t);
        }
        finally {
            frameArmed = false;
        }
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        frameArmed = false;
        LOGGER.warn(P + "probe threw — DISARMED for this session (render unaffected)", t);
    }

    private static void capture(int stage, RenderTarget rt) {
        if (!(RenderSystem.getDevice().backend instanceof GlDevice glDevice)
            || !(rt.getColorTextureView() instanceof GlTextureView colorView)
            || !(rt.getDepthTextureView() instanceof GlTextureView depthView)
        ) {
            disarmed = true;
            LOGGER.info(P + "non-GL backend or missing texture views — DISARMED");
            return;
        }
        int fbo = glDevice.frameBufferCache().getFbo(
            glDevice.directStateAccess(), List.of(colorView), depthView
        );
        int x = (int) (rt.width * 0.72);
        int y0 = (int) (rt.height * 0.05);
        int stripH = Math.max(BINS, (int) (rt.height * 0.17));
        if (y0 + stripH > rt.height) {
            stripH = rt.height - y0;
        }
        int prevRead = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
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
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevPackSkipRows);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevPackRowLength);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);

        int per = Math.max(1, stripH / BINS);
        for (int b = 0; b < BINS; b++) {
            double lum = 0;
            double dep = 0;
            int cnt = 0;
            for (int i = b * per; i < Math.min((b + 1) * per, stripH); i++) {
                int r = colors.get(i * 4) & 0xFF;
                int g = colors.get(i * 4 + 1) & 0xFF;
                int bl = colors.get(i * 4 + 2) & 0xFF;
                lum += (r + g + bl) / (3.0 * 255.0);
                dep += depths.get(i);
                cnt++;
            }
            stages[stage][b][0] = cnt > 0 ? lum / cnt : -1;
            stages[stage][b][1] = cnt > 0 ? dep / cnt : -1;
        }
        captured[stage] = true;
    }

    private static void emit() {
        String[] names = {"A anchor-mainRT", "B snapshot-deferred", "C post-stamp-deferred",
            "D post-blit-mainRT"};
        StringBuilder sb = new StringBuilder(1024);
        sb.append(P).append("STAGE DIFF (one frame, hand-region column; stampCaptures=")
            .append(stampCaptures).append("):");
        for (int s = 0; s < 4; s++) {
            sb.append("\n  ").append(names[s]).append(": ");
            if (!captured[s]) {
                sb.append("NOT CAPTURED");
                continue;
            }
            for (int b = 0; b < BINS; b++) {
                sb.append(String.format("[%d l=%.2f d=%.4f]", b,
                    stages[s][b][0], stages[s][b][1]));
            }
        }
        sb.append("\n  CHANGED BINS (|lum delta| > ").append(CHANGE_LUM).append("): ");
        String[] hops = {"A->B", "B->C", "C->D"};
        for (int h = 0; h < 3; h++) {
            sb.append(hops[h]).append("=[");
            if (captured[h] && captured[h + 1]) {
                boolean any = false;
                for (int b = 0; b < BINS; b++) {
                    if (Math.abs(stages[h][b][0] - stages[h + 1][b][0]) > CHANGE_LUM) {
                        sb.append(b).append(' ');
                        any = true;
                    }
                }
                if (!any) {
                    sb.append("none");
                }
            }
            else {
                sb.append("n/a");
            }
            sb.append("] ");
        }
        sb.append("— the FIRST hop with changed bins names the eater's stage: A->B = the"
            + " snapshot copy; B->C = a stamp; C->D = the blit-back. If ALL hops are clean while"
            + " the hand visibly slices, the eater acts BEFORE stage A (inside iris's own"
            + " renderLevel) or AFTER stage D (GUI-era) — both outside the compat pass.");
        LOGGER.info(sb.toString());
    }
}
