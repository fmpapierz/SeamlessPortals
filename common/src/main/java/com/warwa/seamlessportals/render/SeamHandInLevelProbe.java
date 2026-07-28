package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
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
 * IS5-HAND-INLVL — the IN-renderLevel stage points (2026-07-27 follow-up; lever-gated
 * {@code -Dseamlessportals.handInLevelProbe}, DEFAULT OFF — five blocking readbacks per sampled
 * frame, crossing-window only).
 *
 * <h2>Why (handoff §00a instrument 1)</h2>
 * The four-point stage diff proved the hand is ALREADY ABSENT at the compat anchor (stage A) on
 * the slicing frames — the eater acts INSIDE iris's {@code renderLevel}, upstream of the whole
 * compat pass, and (per the refuted depth bracket) it is NOT a depth-test loss. This instrument
 * extends the stage points inward using the ALREADY-LANDED
 * {@code MixinIrisHandRenderer_SeamDepthBracket} hooks: it reads the hand-region pixels of the
 * LIVE DRAW TARGET at the boundaries of iris's two hand passes, then at the compat anchor,
 * splitting the two open branches by construction:
 * <ul>
 *   <li><b>hand painted at postSolid/postTranslucent but gone at anchor</b> ⇒ COMPOSITE-EATEN —
 *       bisect iris's composite/final chain;</li>
 *   <li><b>nothing painted by either hand pass</b> (pre==post at both hops) ⇒ NEVER DRAWN —
 *       instrument HandRenderer's submit path / canRender / scissor-viewport.</li>
 * </ul>
 *
 * <h2>The five stages</h2>
 * <ol>
 *   <li><b>preSolid</b> — HEAD of {@code HandRenderer.renderSolid} (arms the frame);</li>
 *   <li><b>postSolid</b> — RETURN of {@code renderSolid};</li>
 *   <li><b>preTranslucent</b> — HEAD of {@code renderTranslucent};</li>
 *   <li><b>postTranslucent</b> — RETURN of {@code renderTranslucent};</li>
 *   <li><b>anchor</b> — {@code onBeforeHandRendering} entry (mainRT, the stage-A twin).</li>
 * </ol>
 * The in-level stages read the CURRENT GL draw framebuffer (the hand draws into iris's gbuffer
 * targets there, NOT mainRT — resolving the live binding avoids guessing which); each capture
 * ALSO reads iris colortex0 directly ({@code glGetTextureSubImage}, binds nothing) so the two
 * views cross-check. Per-read {@code glGetError} drains make a failed read a LOUD sentinel row,
 * never a tabulatable zero (the "failure sentinel is not a measurement" rule).
 *
 * <p>Chassis: the SeamHandStageDiff pack-state bracket, copied exactly (an un-bracketed
 * readback under a pack's PACK-state corrupts the native heap). Self-disarming; never throws
 * into iris's pass. NOTE the compat anchor runs with the depth bracket DEFAULT ON, so a DRAWN
 * hand in the crossing window carries depth ≥0.999 (the remap) — the depth-band counters
 * classify hand-band [0.9985,1.0] vs shell-band [0.95,0.9985) accordingly; the primary verdict
 * signal is the per-hop CHANGED-BINS luminance diff, which is depth-convention-free.
 */
public final class SeamHandInLevelProbe {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-HAND-INLVL ";

    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.handInLevelProbe");

    private static final double WINDOW = 0.35;
    private static final int BINS = 8;
    private static final double CHANGE_LUM = 0.08;
    /** With the seam depth bracket DEFAULT ON, a drawn in-window hand writes ≥ NEAR_LO=0.999. */
    private static final double HAND_BAND_LO = 0.9985;
    private static final double SHELL_BAND_LO = 0.95;

    private static final boolean GET_TEX_SUB_IMAGE_SUPPORTED =
        GL.getCapabilities().glGetTextureSubImage != 0L;
    private static final boolean GET_TEX_LEVEL_PARAM_SUPPORTED =
        GL.getCapabilities().glGetTextureLevelParameteriv != 0L;

    private static final int STAGES = 5;
    private static final String[] STAGE_NAMES = {
        "preSolid", "postSolid", "preTranslucent", "postTranslucent", "anchor-mainRT"};

    private static boolean disarmed = false;
    private static boolean announced = false;
    private static long lastFrameNanos = 0L;
    private static boolean frameArmed = false;

    /** [stage][bin][0=lum,1=depth]; NaN = not measured (loud in the emit). */
    private static final double[][][] stages = new double[STAGES][BINS][2];
    /** [stage][bin] colortex0 luminance; NaN = not measured. */
    private static final double[][] ctx = new double[STAGES][BINS];
    private static final boolean[] captured = new boolean[STAGES];
    private static final int[] fboIds = new int[STAGES];
    /** color-attachment-0 TEXTURE id per stage (0 = default FB / renderbuffer / unknown) —
     *  the cross-target hop guard and the ct0 wrong-surface comparison both key on it. */
    private static final int[] att0Tex = new int[STAGES];
    private static final String[] colorStatus = new String[STAGES];
    private static final String[] depthStatus = new String[STAGES];
    private static final String[] ctxStatus = new String[STAGES];
    /** depth-band row counts per stage: [0]=hand-band, [1]=shell-band, [2]=rows read. */
    private static final int[][] bandCounts = new int[STAGES][3];
    /** The seam depth bracket's live armed state at the two post-pass captures (finding 6:
     *  the hand-band classification is only meaningful when the bracket actually remapped). */
    private static boolean bracketArmedAtPostSolid = false;
    private static boolean bracketArmedAtPostTranslucent = false;

    private SeamHandInLevelProbe() {}

    /** HEAD of renderSolid — decides arming for this frame, then captures stage 0. */
    public static void preSolid() {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            if (frameArmed) {
                // The previous armed frame never reached the anchor (compat pass skipped /
                // isRendering / pipelines missing). Say so loudly — that outcome is itself
                // informative (the anchor is where the stage-A comparison lives).
                LOGGER.info(P + "previous armed frame ended WITHOUT the anchor stage — the"
                    + " compat pass did not run that frame; partial stages discarded.");
                frameArmed = false;
            }
            long now = System.nanoTime();
            if (now - lastFrameNanos < 1_000_000_000L) {
                return;
            }
            if (PortalRendering.isRendering()) {
                return; // nested dest-pass hand call — not the main-frame pass we instrument
            }
            if (!IrisInterface.invoker.isShaders()) {
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
            // Sentinel-fill EVERYTHING at arming (final-diff finding 1): a stage that never
            // fires this frame must read as NaN/unset in the emit, never as the previous
            // frame's data or a zero-filled "measurement" — the failure-sentinel rule.
            for (int s = 0; s < STAGES; s++) {
                captured[s] = false;
                fboIds[s] = -1;
                att0Tex[s] = 0;
                colorStatus[s] = "UNSET";
                depthStatus[s] = "UNSET";
                ctxStatus[s] = "UNSET";
                bandCounts[s][0] = bandCounts[s][1] = bandCounts[s][2] = 0;
                for (int b = 0; b < BINS; b++) {
                    stages[s][b][0] = Double.NaN;
                    stages[s][b][1] = Double.NaN;
                    ctx[s][b] = Double.NaN;
                }
            }
            bracketArmedAtPostSolid = false;
            bracketArmedAtPostTranslucent = false;
            if (!announced) {
                announced = true;
                LOGGER.info(P + "ARMED (once-only): capturing the hand-region column at"
                    + " preSolid/postSolid/preTranslucent/postTranslucent (LIVE draw FBO +"
                    + " iris colortex0) and at the compat anchor (mainRT), 1 Hz inside the"
                    + " {}-block crossing window. A leg without this line never sampled.",
                    WINDOW);
            }
            captureInLevel(0);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** RETURN of renderSolid (runs BEFORE the bracket's end(), so armed-state is still live). */
    public static void postSolid() {
        if (ENABLED && !disarmed && frameArmed) {
            bracketArmedAtPostSolid =
                qouteall.imm_ptl.core.compat.iris_compatibility.IrisHandSeamDepthBracket
                    .isArmedNow();
        }
        captureInLevelSafe(1);
    }

    /** HEAD of renderTranslucent. */
    public static void preTranslucent() {
        captureInLevelSafe(2);
    }

    /** RETURN of renderTranslucent (before the bracket's end() — armed-state still live). */
    public static void postTranslucent() {
        if (ENABLED && !disarmed && frameArmed) {
            bracketArmedAtPostTranslucent =
                qouteall.imm_ptl.core.compat.iris_compatibility.IrisHandSeamDepthBracket
                    .isArmedNow();
        }
        captureInLevelSafe(3);
    }

    /** The compat anchor (onBeforeHandRendering entry) — captures mainRT and emits the block. */
    public static void anchor(RenderTarget mainRT) {
        if (!ENABLED || disarmed || !frameArmed) {
            return;
        }
        try {
            captureAnchor(mainRT);
            emit();
        }
        catch (Throwable t) {
            disarm(t);
        }
        finally {
            frameArmed = false;
        }
    }

    private static void captureInLevelSafe(int stage) {
        if (!ENABLED || disarmed || !frameArmed) {
            return;
        }
        try {
            captureInLevel(stage);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        frameArmed = false;
        LOGGER.warn(P + "probe threw — DISARMED for this session (render unaffected)", t);
    }

    /**
     * Read the hand-region column from the LIVE draw framebuffer (whatever iris has bound at
     * this pass boundary) + iris colortex0. Read-only: binds the live FBO as READ (restored),
     * pack state bracketed exactly like SeamHandStageDiff (the S14.21/IS0 shape).
     */
    private static void captureInLevel(int stage) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainRT = mc.gameRenderer.mainRenderTarget();
        if (mainRT == null) {
            markUnread(stage, "no-mainRT");
            return;
        }
        int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        fboIds[stage] = drawFbo;
        readColumnFromFbo(stage, drawFbo, mainRT.width, mainRT.height);
        readColortex0(stage);
        captured[stage] = true;
    }

    private static void captureAnchor(RenderTarget mainRT) {
        // The anchor reads mainRT explicitly (the stage-A twin), NOT the live binding: the
        // question here is what iris FINALIZED into the main frame.
        if (!(mainRT.getColorTextureView() instanceof com.mojang.blaze3d.opengl.GlTextureView colorView)
            || !(mainRT.getDepthTextureView() instanceof com.mojang.blaze3d.opengl.GlTextureView depthView)
            || !(com.mojang.blaze3d.systems.RenderSystem.getDevice().backend
                instanceof com.mojang.blaze3d.opengl.GlDevice glDevice)) {
            markUnread(4, "non-GL backend");
            return;
        }
        int fbo = glDevice.frameBufferCache().getFbo(
            glDevice.directStateAccess(), List.of(colorView), depthView
        );
        fboIds[4] = fbo;
        readColumnFromFbo(4, fbo, mainRT.width, mainRT.height);
        readColortex0(4);
        captured[4] = true;
    }

    private static void markUnread(int stage, String why) {
        colorStatus[stage] = why;
        depthStatus[stage] = why;
        ctxStatus[stage] = why;
        // Self-contained sentinel reset (adversarial-verify finding 2): never rely on the
        // arm-time fill alone — an unread stage must carry NO residual measurement anywhere.
        fboIds[stage] = -1;
        att0Tex[stage] = 0;
        bandCounts[stage][0] = bandCounts[stage][1] = bandCounts[stage][2] = 0;
        for (int b = 0; b < BINS; b++) {
            stages[stage][b][0] = Double.NaN;
            stages[stage][b][1] = Double.NaN;
            ctx[stage][b] = Double.NaN;
        }
        captured[stage] = true;
    }

    /** First error (the verdict) + a bounded drain of any further stacked flags (finding 10). */
    private static int getErrorAndDrain() {
        int first = GL11.glGetError();
        for (int i = 0; i < 8 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
        }
        return first;
    }

    /** The exact SeamHandStageDiff pack-state bracket around the two glReadPixels. */
    private static void readColumnFromFbo(int stage, int fbo, int basisW, int basisH) {
        int x = (int) (basisW * 0.72);
        int y0 = (int) (basisH * 0.05);
        int stripH = Math.max(BINS, (int) (basisH * 0.17));
        if (y0 + stripH > basisH) {
            stripH = basisH - y0;
        }

        int prevRead = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        int prevPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int prevPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int prevPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        ByteBuffer colors;
        FloatBuffer depths;
        boolean colorOk;
        boolean depthOk = false;
        int stripHRead = stripH;
        try {
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);

            // Attachment identification (FBO-only queries; the default framebuffer takes
            // neither). att0Tex feeds the cross-target hop guard + the ct0 surface comparison.
            String colorNote;
            boolean hasDepthAttachment = true;
            if (fbo != 0) {
                int colorType = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                int colorName = colorType == GL11.GL_TEXTURE
                    ? GL30.glGetFramebufferAttachmentParameteri(
                        GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
                    : 0;
                int depthType = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                hasDepthAttachment = depthType != GL11.GL_NONE;
                att0Tex[stage] = colorName;
                colorNote = "att0tex=" + colorName;
                // Strip-geometry sanity (finding 8): the column coords are computed from the
                // mainRT basis — if the live attachment is a differently-sized texture
                // (pack render-scale), a read outside it returns UNDEFINED values with no
                // error. Flag loudly; do not silently rescale.
                if (colorName != 0 && GET_TEX_LEVEL_PARAM_SUPPORTED) {
                    int tw = GL45C.glGetTextureLevelParameteri(
                        colorName, 0, GL11.GL_TEXTURE_WIDTH);
                    int th = GL45C.glGetTextureLevelParameteri(
                        colorName, 0, GL11.GL_TEXTURE_HEIGHT);
                    if (tw != basisW || th != basisH) {
                        colorNote += " DIMS-MISMATCH(" + tw + "x" + th
                            + " vs basis " + basisW + "x" + basisH + " — values UNRELIABLE)";
                    }
                }
            }
            else {
                att0Tex[stage] = 0;
                colorNote = "DEFAULT-FB";
            }
            getErrorAndDrain(); // drain the attachment queries + any pre-existing error

            colors = BufferUtils.createByteBuffer(stripH * 4);
            depths = BufferUtils.createFloatBuffer(stripH);
            GL11.glReadPixels(x, y0, 1, stripH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colors);
            int colorErr = getErrorAndDrain();
            colorOk = colorErr == GL11.GL_NO_ERROR;
            colorStatus[stage] = (colorOk ? "ok " : "READ-FAILED(0x"
                + Integer.toHexString(colorErr) + ") ") + colorNote;

            if (hasDepthAttachment) {
                GL11.glReadPixels(
                    x, y0, 1, stripH, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depths);
                int depthErr = getErrorAndDrain();
                depthOk = depthErr == GL11.GL_NO_ERROR;
                depthStatus[stage] = depthOk ? "ok"
                    : "READ-FAILED(0x" + Integer.toHexString(depthErr) + ")";
            }
            else {
                depthStatus[stage] = "NO-DEPTH-ATTACHMENT";
            }
        }
        finally {
            // Finding 9: the restore must survive a throw (e.g. buffer-alloc OOM) — a leaked
            // READ binding / PACK state would alter the FRAME, not just the probe.
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevPackSkipRows);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevPackRowLength);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        }

        binify(stage, colors, colorOk, depths, depthOk, stripHRead);
    }

    private static void binify(
        int stage, ByteBuffer colors, boolean colorOk,
        FloatBuffer depths, boolean depthOk, int stripH
    ) {
        bandCounts[stage][0] = 0;
        bandCounts[stage][1] = 0;
        bandCounts[stage][2] = depthOk ? stripH : 0;
        if (depthOk) {
            for (int i = 0; i < stripH; i++) {
                float d = depths.get(i);
                if (d >= HAND_BAND_LO) {
                    bandCounts[stage][0]++;
                }
                else if (d >= SHELL_BAND_LO) {
                    bandCounts[stage][1]++;
                }
            }
        }
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
            stages[stage][b][0] = (colorOk && cnt > 0) ? lum / cnt : Double.NaN;
            stages[stage][b][1] = (depthOk && cnt > 0) ? dep / cnt : Double.NaN;
        }
    }

    /** iris colortex0 via glGetTextureSubImage — binds nothing; the cross-check view. */
    private static void readColortex0(int stage) {
        for (int b = 0; b < BINS; b++) {
            ctx[stage][b] = Double.NaN;
        }
        if (!GET_TEX_SUB_IMAGE_SUPPORTED) {
            ctxStatus[stage] = "NO-GL45";
            return;
        }
        int[] ct = IrisTemporalTargetGuard.peekColortex0();
        if (ct == null || ct[0] == 0) {
            ctxStatus[stage] = "NO-COLORTEX0";
            return;
        }
        int w = ct[1];
        int h = ct[2];
        int x = (int) (w * 0.72);
        int y0 = (int) (h * 0.05);
        int stripH = Math.max(BINS, (int) (h * 0.17));
        if (y0 + stripH > h) {
            stripH = h - y0;
        }
        // colortex0 is float-format (RGBA16F etc.) — read as FLOAT, tolerate >1 HDR values.
        // PACK-bracketed (finding 7): glGetTextureSubImage honors PACK state; a nonzero
        // ambient PACK_SKIP_* would shift the data inside the buffer while reporting "ok".
        FloatBuffer buf = BufferUtils.createFloatBuffer(stripH * 4);
        int prevPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int prevPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int prevPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int err;
        try {
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
            getErrorAndDrain();
            GL45C.glGetTextureSubImage(
                ct[0], 0, x, y0, 0, 1, stripH, 1,
                GL11.GL_RGBA, GL11.GL_FLOAT, buf
            );
            err = getErrorAndDrain();
        }
        finally {
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevPackSkipRows);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevPackRowLength);
        }
        if (err != GL11.GL_NO_ERROR) {
            ctxStatus[stage] = "READ-FAILED(0x" + Integer.toHexString(err) + ")";
            return;
        }
        // Wrong-surface detection (finding 4): under ping-pong flipping the hand pass may have
        // written colortex0's ALT surface — this read (the fixed MAIN id) then legitimately
        // misses it. For the in-level stages compare against the live FBO's attachment0: on
        // mismatch the ct0 column is CORROBORATIVE ONLY, never proof of never-drawn. (The
        // anchor's live target is mainRT, which never matches ct0 — no flag there.)
        String surfaceNote = "";
        if (stage < 4 && att0Tex[stage] != 0 && att0Tex[stage] != ct[0]) {
            surfaceNote = " WRONG-SURFACE?(live att0=" + att0Tex[stage] + ")";
        }
        ctxStatus[stage] = "ok tex=" + ct[0] + " " + w + "x" + h + surfaceNote;
        int per = Math.max(1, stripH / BINS);
        for (int b = 0; b < BINS; b++) {
            double lum = 0;
            int cnt = 0;
            for (int i = b * per; i < Math.min((b + 1) * per, stripH); i++) {
                lum += (buf.get(i * 4) + buf.get(i * 4 + 1) + buf.get(i * 4 + 2)) / 3.0;
                cnt++;
            }
            ctx[stage][b] = cnt > 0 ? lum / cnt : Double.NaN;
        }
    }

    private static void emit() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(P).append("IN-RENDERLEVEL STAGE POINTS (one frame, hand-region column):");
        for (int s = 0; s < STAGES; s++) {
            sb.append("\n  ").append(STAGE_NAMES[s]).append(" [fbo=").append(fboIds[s])
                .append(" color:").append(colorStatus[s])
                .append(" depth:").append(depthStatus[s])
                .append(" ct0:").append(ctxStatus[s]).append("]");
            if (!captured[s]) {
                sb.append(" NOT CAPTURED");
                continue;
            }
            sb.append("\n    fb : ");
            for (int b = 0; b < BINS; b++) {
                sb.append(String.format("[%d l=%s d=%s]", b,
                    fmt(stages[s][b][0], "%.2f"), fmt(stages[s][b][1], "%.4f")));
            }
            sb.append(String.format(" bands(hand>=%.4f/shell>=%.2f of %d rows): hand=%d shell=%d",
                HAND_BAND_LO, SHELL_BAND_LO, bandCounts[s][2],
                bandCounts[s][0], bandCounts[s][1]));
            sb.append("\n    ct0: ");
            for (int b = 0; b < BINS; b++) {
                sb.append(String.format("[%d l=%s]", b, fmt(ctx[s][b], "%.2f")));
            }
        }
        sb.append("\n  depth bracket at captures: postSolid armed=")
            .append(bracketArmedAtPostSolid)
            .append(" postTranslucent armed=").append(bracketArmedAtPostTranslucent)
            .append(" (").append(qouteall.imm_ptl.core.compat.iris_compatibility
                .IrisHandSeamDepthBracket.statusForProbe())
            .append(") — the hand-band [").append(HAND_BAND_LO)
            .append(",1.0] classification is only meaningful when armed=true (the bracket"
                + " remaps a drawn in-window hand to >=0.999); armed=false there ⇒ bands are"
                + " VOID for the hand question, use the luminance hops.");
        sb.append("\n  CHANGED BINS (fb lum, |delta| > ").append(CHANGE_LUM).append("): ");
        String[] hops = {"preSolid->postSolid", "postSolid->preTrans",
            "preTrans->postTrans", "postTrans->anchor"};
        for (int h = 0; h < 4; h++) {
            // Chassis-precedent gate (SeamHandStageDiff.emit) ON TOP of the NaN sentinels: a
            // hop with an uncaptured endpoint is UNMEASURED, never diffed. Cross-target guard
            // (adversarial-verify finding 3): a hop whose two stages read DIFFERENT live
            // targets (fbo or attachment0) diffs two different surfaces — flagged, not diffed.
            if (!(captured[h] && captured[h + 1])) {
                sb.append(hops[h]).append("=[UNMEASURED] ");
            }
            else if (h < 3 && (fboIds[h] != fboIds[h + 1] || att0Tex[h] != att0Tex[h + 1])) {
                sb.append(hops[h]).append("=[CROSS-TARGET fbo ").append(fboIds[h])
                    .append("->").append(fboIds[h + 1]).append(" att0 ").append(att0Tex[h])
                    .append("->").append(att0Tex[h + 1])
                    .append(" — different surfaces, diff withheld; use the ct0 row] ");
            }
            else {
                appendHop(sb, hops[h], stages[h], stages[h + 1], 0);
            }
        }
        sb.append("\n  CHANGED BINS (ct0 lum — CORROBORATIVE ONLY: under ping-pong flipping"
            + " the hand pass may write colortex0's ALT surface, see WRONG-SURFACE? flags): ");
        for (int h = 0; h < 4; h++) {
            if (captured[h] && captured[h + 1]) {
                appendCtxHop(sb, hops[h], ctx[h], ctx[h + 1]);
            }
            else {
                sb.append(hops[h]).append("=[UNMEASURED] ");
            }
        }
        sb.append("\n  READ: hand painted at postSolid/postTranslucent (changed bins on the"
            + " pre->post hops, hand-band depth rows appearing) but gone at the anchor ⇒"
            + " COMPOSITE-EATEN inside iris's composite/final chain (postTrans->anchor names"
            + " it). Pre->post hops clean at BOTH passes ⇒ the hand was NEVER DRAWN at those"
            + " pixels — next split: iris's canRender GATE (F1/spectator/sleeping/no-item)"
            + " vs the submit/scissor path; a gate-suppressed hand is also 'never drawn'."
            + " NaN/READ-FAILED/UNMEASURED/CROSS-TARGET rows are not measurements; adjudicate"
            + " only measured rows. postSolid->preTrans spans ALL translucent terrain/"
            + " particles (iris calls renderSolid at beginTranslucents, renderTranslucent at"
            + " endLevelRender) — changes there are NOT hand evidence. postTrans->anchor also"
            + " spans iris's compositing INTO mainRT — luminance change there is expected from"
            + " tonemapping alone; the verdict signal is the hand-SHAPED structure (bands +"
            + " which bins), not absolute values.");
        LOGGER.info(sb.toString());
    }

    private static void appendHop(StringBuilder sb, String name,
        double[][] a, double[][] b, int idx) {
        sb.append(name).append("=[");
        boolean any = false;
        boolean measurable = false;
        for (int bin = 0; bin < BINS; bin++) {
            double d0 = a[bin][idx];
            double d1 = b[bin][idx];
            if (Double.isNaN(d0) || Double.isNaN(d1)) {
                continue;
            }
            measurable = true;
            if (Math.abs(d0 - d1) > CHANGE_LUM) {
                sb.append(bin).append(' ');
                any = true;
            }
        }
        if (!measurable) {
            sb.append("UNMEASURED");
        }
        else if (!any) {
            sb.append("none");
        }
        sb.append("] ");
    }

    private static void appendCtxHop(StringBuilder sb, String name, double[] a, double[] b) {
        sb.append(name).append("=[");
        boolean any = false;
        boolean measurable = false;
        for (int bin = 0; bin < BINS; bin++) {
            if (Double.isNaN(a[bin]) || Double.isNaN(b[bin])) {
                continue;
            }
            measurable = true;
            if (Math.abs(a[bin] - b[bin]) > CHANGE_LUM) {
                sb.append(bin).append(' ');
                any = true;
            }
        }
        if (!measurable) {
            sb.append("UNMEASURED");
        }
        else if (!any) {
            sb.append("none");
        }
        sb.append("] ");
    }

    private static String fmt(double v, String f) {
        return Double.isNaN(v) ? "n/a" : String.format(f, v);
    }
}
