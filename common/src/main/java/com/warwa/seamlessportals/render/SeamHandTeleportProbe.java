package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.backend.opengl.GlDevice;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisHandSeamDepthBracket;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * 26.3 IS5-HAND-TP — the per-FRAME hand record around a client teleport (lever {@code -Dseamlessportals.handTeleportProbe},
 * DEFAULT OFF, log-only).
 *
 * <p><b>Why.</b> User report 2026-09-21 (Fabric+Sodium+Iris, Complementary ON, after the hand bracket's direction fix):
 * "on same dim portals … there is a tiny split second when telporting ow-ow where the hand reloads/disapear/reapear very
 * quickly" — cross-dim crossings are clean. Every existing hand instrument samples at 1 Hz inside the crossing window;
 * a one-to-three-frame blink AT the teleport falls between their samples. This records EVERY frame from
 * {@value #BEFORE} before to {@value #AFTER} after each client teleport and dumps them as one block.
 *
 * <p><b>What one frame records</b> (main view only; a nested dest-pass hand call is only counted): whether iris's
 * {@code HandRenderer.renderSolid} / {@code renderTranslucent} were CALLED, what {@code canRender} RETURNED for each,
 * whether each pass BODY ran ({@code setupGlState} reached = every outer gate passed), how many hand DRAWS were set up
 * ({@code GlCommandEncoder.setupDraw} while {@code HandRenderer.isActive()}), and the depth function / depth range /
 * bracket-armed state of the first of them; the main view's extracted first-person state (hands selection, hand height,
 * item-swap scale); and — the OUTCOME — the mean colour of the finished frame's hand region next to a control region,
 * read at {@code Minecraft.renderFrame} RETURN while the player is within 4 blocks of a portal. A frame where iris ran no
 * hand pass says so on its row; a frame with neither a hook nor a sample is listed as having no record.
 *
 * <p>Never throws into iris or the encoder: every entry catches {@code Throwable} and disarms the probe for the session.
 */
public final class SeamHandTeleportProbe {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-HAND-TP ";

    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.handTeleportProbe");

    private static final int BEFORE = 4;
    private static final int AFTER = 12;
    private static final int RING = 64;

    private static final class Rec {
        int frame = Integer.MIN_VALUE;
        boolean solidCalled;
        boolean translucentCalled;
        int canRenderSolid = -1;        // -1 unset / 0 false / 1 true
        int canRenderTranslucent = -1;
        boolean bodySolid;
        boolean bodyTranslucent;
        int handDraws;
        int firstFunc;
        float range0 = Float.NaN;
        float range1 = Float.NaN;
        boolean bracketArmedAtFirstDraw;
        int nestedHandCalls;
        boolean playerIsCameraEntity;
        boolean cameraDetached;
        boolean anyHandHook;
        // The main view's extracted first-person state, read at the first main hand pass of the frame.
        boolean hasPlayer;
        String handSelection = "-";
        float mainHandHeight = Float.NaN;
        float oldMainHandHeight = Float.NaN;
        float itemSwapScale = Float.NaN;
        // The OUTCOME: mean RGB of the final frame's hand region (bottom-right) and of a control region (top-left).
        boolean sampled;
        final int[] hand = new int[3];
        final int[] control = new int[3];

        void reset(int newFrame) {
            frame = newFrame;
            solidCalled = false;
            translucentCalled = false;
            canRenderSolid = -1;
            canRenderTranslucent = -1;
            bodySolid = false;
            bodyTranslucent = false;
            handDraws = 0;
            firstFunc = 0;
            range0 = Float.NaN;
            range1 = Float.NaN;
            bracketArmedAtFirstDraw = false;
            nestedHandCalls = 0;
            playerIsCameraEntity = false;
            cameraDetached = false;
            anyHandHook = false;
            hasPlayer = false;
            handSelection = "-";
            mainHandHeight = Float.NaN;
            oldMainHandHeight = Float.NaN;
            itemSwapScale = Float.NaN;
            sampled = false;
        }
    }

    private static final Rec[] ring = new Rec[RING];
    private static int ringHead = -1;
    private static boolean disarmed = false;
    private static boolean announced = false;

    /** -1 not in a pass; 0 solid; 1 translucent (main view only). */
    private static int passIdx = -1;

    private static int teleportFrame = Integer.MIN_VALUE;
    private static String teleportKind = "";
    private static int teleportSerial = 0;
    private static final FloatBuffer RANGE = BufferUtils.createFloatBuffer(16);

    private SeamHandTeleportProbe() {}

    private static Rec current() {
        int frame = RenderStates.frameIndex;
        if (ringHead >= 0 && ring[ringHead] != null && ring[ringHead].frame == frame) {
            return ring[ringHead];
        }
        maybeDump(frame);
        ringHead = (ringHead + 1) % RING;
        if (ring[ringHead] == null) {
            ring[ringHead] = new Rec();
        }
        ring[ringHead].reset(frame);
        return ring[ringHead];
    }

    /** {@code ClientTeleportationManager.teleportPlayer}, right after the crossing is applied. */
    public static void markTeleport(String kind) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            // A second crossing inside the first one's window: dump what there is, then start over.
            if (teleportFrame != Integer.MIN_VALUE) {
                dump(RenderStates.frameIndex, true);
            }
            teleportFrame = RenderStates.frameIndex;
            teleportKind = kind;
            teleportSerial++;
            if (!announced) {
                announced = true;
                LOGGER.info(P + "ARMED (once-only): every frame from {} before to {} after each client teleport is"
                    + " recorded (iris hand passes called / canRender / body / hand draws / depth state) and dumped as"
                    + " one block. A teleport without a block means no hand hook fired for {} frames after it.",
                    BEFORE, AFTER, AFTER);
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    private static void maybeDump(int nowFrame) {
        if (teleportFrame != Integer.MIN_VALUE && nowFrame > teleportFrame + AFTER) {
            dump(nowFrame, false);
        }
    }

    private static void dump(int nowFrame, boolean truncated) {
        int tp = teleportFrame;
        teleportFrame = Integer.MIN_VALUE;
        StringBuilder sb = new StringBuilder(1024);
        int from = tp - BEFORE;
        int to = truncated ? nowFrame : tp + AFTER;
        int rows = 0;
        StringBuilder missing = new StringBuilder();
        for (int frame = from; frame <= to; frame++) {
            Rec rec = find(frame);
            if (rec == null) {
                missing.append(missing.length() == 0 ? "" : ",").append(frame - tp >= 0 ? "+" : "").append(frame - tp);
                continue;
            }
            rows++;
            sb.append("\n  f").append(frame - tp >= 0 ? "+" : "").append(frame - tp);
            if (rec.sampled) {
                sb.append(" | PIXELS hand=").append(rec.hand[0]).append(',').append(rec.hand[1]).append(',').append(rec.hand[2])
                    .append(" control=").append(rec.control[0]).append(',').append(rec.control[1]).append(',')
                    .append(rec.control[2]);
            }
            if (!rec.anyHandHook) {
                sb.append(" | NO HAND HOOK FIRED THIS FRAME (iris ran neither hand pass)");
                continue;
            }
            sb.append(" | state: hasPlayer=").append(rec.hasPlayer)
                .append(" hands=").append(rec.handSelection)
                .append(" mainHandHeight=").append(rec.oldMainHandHeight).append("->").append(rec.mainHandHeight)
                .append(" itemSwapScale=").append(rec.itemSwapScale)
                .append(" | solid: called=").append(rec.solidCalled)
                .append(" canRender=").append(tri(rec.canRenderSolid))
                .append(" body=").append(rec.bodySolid)
                .append(" | translucent: called=").append(rec.translucentCalled)
                .append(" canRender=").append(tri(rec.canRenderTranslucent))
                .append(" body=").append(rec.bodyTranslucent)
                .append(" | handDraws=").append(rec.handDraws);
            if (rec.handDraws > 0) {
                sb.append(" firstDraw: func=0x").append(Integer.toHexString(rec.firstFunc))
                    .append(" range=[").append(rec.range0).append(',').append(rec.range1).append(']')
                    .append(" bracketArmed=").append(rec.bracketArmedAtFirstDraw);
            }
            sb.append(" | camEnt==player=").append(rec.playerIsCameraEntity)
                .append(" detached=").append(rec.cameraDetached)
                .append(" nestedHandCalls=").append(rec.nestedHandCalls);
        }
        LOGGER.info(P + "teleport #{} ({}){} — frames relative to the pump that applied the crossing (f+1 = the first frame"
            + " rendered at the destination); {} rows; frames with no record at all: [{}]{}", teleportSerial, teleportKind,
            truncated ? " TRUNCATED by the next teleport" : "", rows, missing, sb);
    }

    private static String tri(int v) {
        return v < 0 ? "-" : v == 1 ? "true" : "FALSE";
    }

    private static Rec find(int frame) {
        for (Rec rec : ring) {
            if (rec != null && rec.frame == frame) {
                return rec;
            }
        }
        return null;
    }

    /** HEAD of iris's renderSolid (pass 0) / renderTranslucent (pass 1). */
    public static void beginPass(int pass) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            Rec rec = current();
            rec.anyHandHook = true;
            if (PortalRendering.isRendering()) {
                rec.nestedHandCalls++;
                return;
            }
            passIdx = pass;
            if (pass == 0) {
                rec.solidCalled = true;
            } else {
                rec.translucentCalled = true;
            }
            Minecraft mc = Minecraft.getInstance();
            rec.playerIsCameraEntity = mc.player != null && mc.getCameraEntity() == mc.player;
            rec.cameraDetached = mc.gameRenderer.mainCamera().isDetached();
            if (Float.isNaN(rec.mainHandHeight)) {
                PlayerRenderState ps = mc.gameRenderer.gameRenderState().levelRenderState.playerRenderState;
                rec.hasPlayer = ps.hasPlayer;
                rec.handSelection = String.valueOf(ps.firstPersonHandsAndItems.handRenderSelection);
                rec.mainHandHeight = ps.firstPersonHandsAndItems.mainHandHeight;
                rec.oldMainHandHeight = ps.firstPersonHandsAndItems.oldMainHandHeight;
                rec.itemSwapScale = mc.player == null ? Float.NaN : mc.player.getItemSwapScale(1.0F);
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** RETURN of both passes. */
    public static void endPass() {
        if (!ENABLED || disarmed) {
            return;
        }
        if (!PortalRendering.isRendering()) {
            passIdx = -1;
        }
    }

    /** RETURN of iris's private canRender. */
    public static void onCanRender(boolean result) {
        if (!ENABLED || disarmed || passIdx < 0) {
            return;
        }
        try {
            if (PortalRendering.isRendering()) {
                return;
            }
            Rec rec = current();
            if (passIdx == 0) {
                rec.canRenderSolid = result ? 1 : 0;
            } else {
                rec.canRenderTranslucent = result ? 1 : 0;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** RETURN of iris's private setupGlState — reached only past every outer gate of the pass. */
    public static void onBodyEntered() {
        if (!ENABLED || disarmed || passIdx < 0) {
            return;
        }
        try {
            if (PortalRendering.isRendering()) {
                return;
            }
            Rec rec = current();
            if (passIdx == 0) {
                rec.bodySolid = true;
            } else {
                rec.bodyTranslucent = true;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** {@code GlCommandEncoder.setupDraw} RETURN while iris's HandRenderer is active: a real hand draw is about to issue. */
    public static void onHandDraw() {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            if (PortalRendering.isRendering()) {
                return;
            }
            Rec rec = current();
            rec.handDraws++;
            if (rec.handDraws == 1) {
                rec.firstFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
                RANGE.clear();
                GL11.glGetFloatv(GL11.GL_DEPTH_RANGE, RANGE);
                rec.range0 = RANGE.get(0);
                rec.range1 = RANGE.get(1);
                rec.bracketArmedAtFirstDraw = IrisHandSeamDepthBracket.isArmedNow();
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * {@code Minecraft.renderFrame} RETURN — the frame is complete (level + GUI) in the main render target. While the player
     * is within 4 blocks of any portal, read the mean colour of the hand region (bottom-right) and of a control region
     * (top-left) — the thing the player actually sees, not the requests that should have produced it.
     */
    public static void endOfFrame() {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null
                || McHelper.getEntitiesNearby(mc.player, Portal.class, 4.0).isEmpty()) {
                return;
            }
            RenderTarget mainRT = mc.gameRenderer.mainRenderTarget();
            if (mainRT == null
                || !(((com.mojang.renderpearl.frontend.FrontendGpuDevice) RenderSystem.getDevice()).backend
                    instanceof GlDevice glDevice)
                || !(mainRT.getColorTextureView() instanceof GlTextureView colorView)
                || !(mainRT.getDepthTextureView() instanceof GlTextureView depthView)) {
                return;
            }
            Rec rec = current();
            int fbo = glDevice.frameBufferCache().getFbo(glDevice.directStateAccess(), List.of(colorView), depthView);
            int w = mainRT.width;
            int h = mainRT.height;
            int prevRead = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
            int prevPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
            int prevPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
            int prevPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
            int prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            try {
                GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
                GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
                drain();
                // GL rows count from the BOTTOM: the hand sits in the bottom-right, the control patch in the top-left.
                boolean handOk = meanRgb((int) (w * 0.72), (int) (h * 0.02), (int) (w * 0.14), (int) (h * 0.22), rec.hand);
                boolean ctlOk = meanRgb((int) (w * 0.05), (int) (h * 0.76), (int) (w * 0.14), (int) (h * 0.22), rec.control);
                rec.sampled = handOk && ctlOk;
            }
            finally {
                GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevPackSkipRows);
                GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevPackRowLength);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    private static boolean meanRgb(int x, int y, int w, int h, int[] out) {
        if (w <= 0 || h <= 0) {
            return false;
        }
        ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels(x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        if (drain() != GL11.GL_NO_ERROR) {
            return false;
        }
        long r = 0;
        long g = 0;
        long b = 0;
        int n = w * h;
        for (int i = 0; i < n; i++) {
            r += px.get(i * 4) & 0xFF;
            g += px.get(i * 4 + 1) & 0xFF;
            b += px.get(i * 4 + 2) & 0xFF;
        }
        out[0] = (int) (r / n);
        out[1] = (int) (g / n);
        out[2] = (int) (b / n);
        return true;
    }

    private static int drain() {
        int first = GL11.glGetError();
        for (int i = 0; i < 8 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
            // consume queued flags
        }
        return first;
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        try {
            LOGGER.warn(P + "threw — DISARMED for this session (rendering unaffected)", t);
        }
        catch (Throwable ignored) {
            // never let the probe's own failure escape
        }
    }
}
