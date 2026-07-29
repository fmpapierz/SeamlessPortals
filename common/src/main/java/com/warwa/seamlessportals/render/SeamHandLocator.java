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
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45C;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisTemporalTargetGuard;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * IS5-HAND-LOC — the HAND LOCATOR (2026-07-28; lever-gated
 * {@code -Dseamlessportals.handLocator}, DEFAULT OFF — heavy readbacks, diagnostic only).
 *
 * <h2>Why (the instrument-limitation reckoning)</h2>
 * Every prior hand probe sampled ONE fixed proportional column and only IN-WINDOW frames — so
 * "nothing painted" was never validated against a frame where the hand is KNOWN visible, and
 * the one leg that showed ct0 changes may have caught a coincident iris copy, not hand paint
 * (the reproduction-for-the-wrong-reason class). This instrument removes every spatial and
 * surface assumption at once: a COARSE FULL-FRAME GRID diff across {@code renderSolid} on
 * FOUR targets simultaneously — mainRT color, mainRT depth, colortex0 MAIN and colortex0 ALT
 * (ping-pong parity!) — sampled AMBIENT and WINDOW alike (0.5 Hz).
 *
 * <h2>Adjudication</h2>
 * AMBIENT rows are ground truth: the changed cells show WHERE the visible hand paints and
 * INTO WHICH target (if ambient shows NO footprint on any target, every prior "not painted"
 * reading was instrument-blind and the hand draws into yet another target). WINDOW rows then
 * show whether that same footprint vanishes in the crossing window. Grid: 12 cols x 8 rows,
 * row 0 = screen BOTTOM (GL origin); a cell is CHANGED on |lum|>0.06 or |depth|>0.02.
 * Read-only; PACK-bracketed; per-read glGetError sentinels; self-disarming.
 */
public final class SeamHandLocator {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-HAND-LOC ";

    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.handLocator");

    private static final double WINDOW = 0.35;
    private static final int COLS = 12;
    private static final int ROWS = 8;
    private static final double LUM_THRESHOLD = 0.06;
    private static final double DEPTH_THRESHOLD = 0.02;

    private static final boolean GET_TEX_SUB_IMAGE_SUPPORTED =
        GL.getCapabilities().glGetTextureSubImage != 0L;

    private static boolean disarmed = false;
    private static boolean announced = false;
    private static long lastSampleNanos = 0L;
    private static boolean frameArmed = false;
    private static boolean frameAmbient = false;
    private static double frameDist = Double.NaN;

    /** Stage names: 0=preSolid 1=postSolid 2=anchor(post-iris-finalize) 3=postBlit(final). */
    private static final String[] STAGE_NAMES = {"preSolid", "postSolid", "ANCHOR", "postBlit"};
    private static final int STAGES = 4;

    /** [target][stage][row][col] lum or depth mean; NaN = unread. Targets:
     *  0=mainRT-color 1=mainRT-depth 2=ct0-main 3=ct0-alt. */
    private static final double[][][][] grid = new double[4][STAGES][ROWS][COLS];
    private static final String[] targetStatus = new String[4];
    private static final String[] TARGET_NAMES = {"mainRT-color", "mainRT-depth",
        "ct0-MAIN", "ct0-ALT"};

    private SeamHandLocator() {}

    /** HEAD of renderSolid — decides sampling (0.5 Hz, ambient AND window) and captures pre. */
    public static void preSolid() {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            if (PortalRendering.isRendering()) {
                return;
            }
            frameArmed = false;
            if (!IrisInterface.invoker.isShaders()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            long now = System.nanoTime();
            if (now - lastSampleNanos < 2_000_000_000L) {
                return;
            }
            lastSampleNanos = now;
            frameDist = distToNearestCrossablePortal(mc);
            frameAmbient = !(frameDist < WINDOW);
            frameArmed = true;
            for (int t = 0; t < 4; t++) {
                targetStatus[t] = "UNSET";
                for (int s = 0; s < STAGES; s++) {
                    for (int r = 0; r < ROWS; r++) {
                        for (int c = 0; c < COLS; c++) {
                            grid[t][s][r][c] = Double.NaN;
                        }
                    }
                }
            }
            if (!announced) {
                announced = true;
                LOGGER.info(P + "ARMED (once-only): full-frame {}x{} grid diff across"
                    + " renderSolid on mainRT color+depth and colortex0 MAIN+ALT, 0.5 Hz,"
                    + " AMBIENT and window. Ambient rows are the ground truth of where the"
                    + " visible hand paints. A leg without this line never sampled.",
                    COLS, ROWS);
            }
            captureAll(0);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** RETURN of renderSolid — captures the post-hand-paint stage (emit waits for postBlit). */
    public static void postSolid() {
        if (!ENABLED || disarmed || !frameArmed) {
            return;
        }
        try {
            if (PortalRendering.isRendering()) {
                return;
            }
            captureAll(1);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Compat anchor (onBeforeHandRendering entry) — mainRT as iris FINALIZED it. */
    public static void anchor() {
        if (!ENABLED || disarmed || !frameArmed) {
            return;
        }
        try {
            captureAll(2);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** After the compat blit-back — the frame that ships. Emits the whole table. */
    public static void postBlit() {
        if (!ENABLED || disarmed || !frameArmed) {
            return;
        }
        try {
            captureAll(3);
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
        LOGGER.warn(P + "locator threw — DISARMED for this session (render unaffected)", t);
    }

    private static double distToNearestCrossablePortal(Minecraft mc) {
        double best = Double.MAX_VALUE;
        Vec3 cam = CHelper.getCurrentCameraPos();
        List<Portal> portals = McHelper.getEntitiesNearby(mc.player, Portal.class, 2.0);
        for (Portal portal : portals) {
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

    private static void captureAll(int stage) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainRT = mc.gameRenderer.mainRenderTarget();
        if (mainRT == null || mainRT.getColorTextureView() == null
            || mainRT.getDepthTextureView() == null) {
            targetStatus[0] = targetStatus[1] = "no-mainRT";
        }
        else {
            captureMainRT(stage, mainRT);
        }
        // colortex0 is only meaningful during the gbuffer era (stages 0/1); post-composite
        // stages read mainRT only (cost + the ct0 ping-pong is irrelevant there).
        if (stage < 2) {
            captureColortex(stage);
        }
    }

    /** mainRT color+depth via its cached FBO, row-strip reads, PACK-bracketed (the proven
     *  SeamHandStageDiff shape), per-read error sentinels. */
    private static void captureMainRT(int stage, RenderTarget mainRT) {
        if (!(RenderSystem.getDevice().backend instanceof GlDevice glDevice)
            || !(mainRT.getColorTextureView() instanceof GlTextureView colorView)
            || !(mainRT.getDepthTextureView() instanceof GlTextureView depthView)) {
            targetStatus[0] = targetStatus[1] = "non-GL";
            return;
        }
        int fbo = glDevice.frameBufferCache().getFbo(
            glDevice.directStateAccess(), List.of(colorView), depthView);
        int w = mainRT.width;
        int h = mainRT.height;

        int prevRead = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        int prevPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int prevPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int prevPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
            drain();
            ByteBuffer colors = BufferUtils.createByteBuffer(w * 4);
            FloatBuffer depths = BufferUtils.createFloatBuffer(w);
            boolean colorOk = true;
            boolean depthOk = true;
            for (int r = 0; r < ROWS; r++) {
                int y = (int) ((r + 0.5) * h / (double) ROWS);
                GL11.glReadPixels(0, y, w, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colors);
                if (drain() != GL11.GL_NO_ERROR) {
                    colorOk = false;
                }
                else {
                    binRowColor(grid[0][stage][r], colors, w);
                }
                GL11.glReadPixels(0, y, w, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depths);
                if (drain() != GL11.GL_NO_ERROR) {
                    depthOk = false;
                }
                else {
                    binRowDepth(grid[1][stage][r], depths, w);
                }
            }
            targetStatus[0] = colorOk ? "ok" : "READ-FAILED";
            targetStatus[1] = depthOk ? "ok" : "READ-FAILED";
        }
        finally {
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevPackSkipRows);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevPackRowLength);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        }
    }

    /** colortex0 MAIN + ALT via glGetTextureSubImage (binds nothing), PACK-bracketed. */
    private static void captureColortex(int stage) {
        if (!GET_TEX_SUB_IMAGE_SUPPORTED) {
            targetStatus[2] = targetStatus[3] = "NO-GL45";
            return;
        }
        int[] pair = IrisTemporalTargetGuard.peekColortex0Pair();
        if (pair == null || pair[0] == 0) {
            targetStatus[2] = targetStatus[3] = "NO-COLORTEX0";
            return;
        }
        int w = pair[2];
        int h = pair[3];
        int prevPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int prevPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int prevPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
            FloatBuffer row = BufferUtils.createFloatBuffer(w * 4);
            for (int t = 0; t < 2; t++) {
                int tex = pair[t];
                boolean ok = tex != 0;
                if (ok) {
                    drain();
                    for (int r = 0; r < ROWS; r++) {
                        int y = (int) ((r + 0.5) * h / (double) ROWS);
                        GL45C.glGetTextureSubImage(tex, 0, 0, y, 0, w, 1, 1,
                            GL11.GL_RGBA, GL11.GL_FLOAT, row);
                        if (drain() != GL11.GL_NO_ERROR) {
                            ok = false;
                            break;
                        }
                        binRowColorF(grid[2 + t][stage][r], row, w);
                    }
                }
                targetStatus[2 + t] = ok ? ("ok tex=" + tex)
                    : (tex == 0 ? "NO-TEX" : "READ-FAILED");
            }
        }
        finally {
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevPackSkipRows);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevPackRowLength);
        }
    }

    private static int drain() {
        int first = GL11.glGetError();
        for (int i = 0; i < 8 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
        }
        return first;
    }

    private static void binRowColor(double[] out, ByteBuffer px, int w) {
        int per = Math.max(1, w / COLS);
        for (int c = 0; c < COLS; c++) {
            double lum = 0;
            int cnt = 0;
            for (int x = c * per; x < Math.min((c + 1) * per, w); x++) {
                lum += ((px.get(x * 4) & 0xFF) + (px.get(x * 4 + 1) & 0xFF)
                    + (px.get(x * 4 + 2) & 0xFF)) / (3.0 * 255.0);
                cnt++;
            }
            out[c] = cnt > 0 ? lum / cnt : Double.NaN;
        }
    }

    private static void binRowColorF(double[] out, FloatBuffer px, int w) {
        int per = Math.max(1, w / COLS);
        for (int c = 0; c < COLS; c++) {
            double lum = 0;
            int cnt = 0;
            for (int x = c * per; x < Math.min((c + 1) * per, w); x++) {
                lum += (px.get(x * 4) + px.get(x * 4 + 1) + px.get(x * 4 + 2)) / 3.0;
                cnt++;
            }
            out[c] = cnt > 0 ? lum / cnt : Double.NaN;
        }
    }

    private static void binRowDepth(double[] out, FloatBuffer px, int w) {
        int per = Math.max(1, w / COLS);
        for (int c = 0; c < COLS; c++) {
            double d = 0;
            int cnt = 0;
            for (int x = c * per; x < Math.min((c + 1) * per, w); x++) {
                d += px.get(x);
                cnt++;
            }
            out[c] = cnt > 0 ? d / cnt : Double.NaN;
        }
    }

    private static void emit() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(P).append(frameAmbient ? "[AMBIENT]"
            : String.format("[window d=%.2f]", frameDist));
        sb.append(" HAND-PAINT cells across renderSolid (grid ").append(COLS).append('x')
            .append(ROWS).append(", r0 = screen BOTTOM; lum>").append(LUM_THRESHOLD)
            .append(" / depth>").append(DEPTH_THRESHOLD).append("):");
        // Collect the hand footprint from the mainRT-depth paint diff (the reliable signal).
        boolean[][] footprint = new boolean[ROWS][COLS];
        for (int t = 0; t < 4; t++) {
            sb.append("\n  ").append(TARGET_NAMES[t]).append(" [").append(targetStatus[t])
                .append("]: ");
            double threshold = t == 1 ? DEPTH_THRESHOLD : LUM_THRESHOLD;
            int changed = 0;
            StringBuilder cells = new StringBuilder();
            boolean measurable = false;
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    double a = grid[t][0][r][c];
                    double b = grid[t][1][r][c];
                    if (Double.isNaN(a) || Double.isNaN(b)) {
                        continue;
                    }
                    measurable = true;
                    if (Math.abs(a - b) > threshold) {
                        changed++;
                        if (t == 1 || t == 2) {
                            footprint[r][c] = true;
                        }
                        if (changed <= 24) {
                            cells.append('r').append(r).append('c').append(c).append(' ');
                        }
                    }
                }
            }
            if (!measurable) {
                sb.append("UNMEASURED");
            }
            else if (changed == 0) {
                sb.append("none");
            }
            else {
                sb.append(changed).append(" cells: ").append(cells);
                if (changed > 24) {
                    sb.append("...");
                }
            }
        }

        // THE SURVIVAL TABLE: raw mainRT color+depth at each hand-footprint cell, at all four
        // stages. Absolute values (no diffing, no fixed column) — this is what answers "where
        // between the hand draw and the shipped frame did the hand go".
        sb.append("\n  SURVIVAL TABLE (mainRT lum/depth at each hand-paint cell; stages:");
        for (int s = 0; s < STAGES; s++) {
            sb.append(' ').append(s).append('=').append(STAGE_NAMES[s]);
        }
        sb.append("):");
        int printed = 0;
        for (int r = 0; r < ROWS && printed < 8; r++) {
            for (int c = 0; c < COLS && printed < 8; c++) {
                if (!footprint[r][c]) {
                    continue;
                }
                printed++;
                sb.append("\n    r").append(r).append('c').append(c).append(": ");
                for (int s = 0; s < STAGES; s++) {
                    sb.append(String.format("[%d l=%s d=%s]", s,
                        fmt(grid[0][s][r][c], "%.3f"), fmt(grid[1][s][r][c], "%.4f")));
                }
            }
        }
        if (printed == 0) {
            sb.append(" NO HAND-PAINT CELLS THIS FRAME (the hand did not draw at all — see the"
                + " paint rows above; nothing to trace).");
        }
        sb.append("\n  READ: the hand's own depth is NEAR (small d) vs scene/shell (d~0.96+)."
            + " Follow each cell across the stages: d stays near through stage 3 ⇒ the hand"
            + " survives the whole pipeline (the loss is elsewhere/GUI-era). d flips to the"
            + " scene value (or lum jumps to the window content) between stage 1 and 2 ⇒ IRIS'S"
            + " OWN composite/finalize discards it. Between 2 and 3 ⇒ THE COMPAT PASS (stamp"
            + " overpaint / blit) — and the stamp's depth test is the lever. AMBIENT rows are"
            + " the working-hand control: whatever pattern they show IS 'healthy'.");
        LOGGER.info(sb.toString());
    }

    private static String fmt(double v, String f) {
        return Double.isNaN(v) ? "n/a" : String.format(f, v);
    }
}
