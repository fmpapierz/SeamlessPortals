package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.nio.IntBuffer;
import java.util.List;

/**
 * IS5-HAND-TAP — the iris HandRenderer canRender-vs-submit tap (2026-07-28; lever-gated
 * {@code -Dseamlessportals.handSubmitTap}, DEFAULT OFF — log-only; the sole GL side effect is
 * consuming queued glGetError flags around the sampled viewport read, reported as drained=N).
 *
 * <h2>Why (handoff §00c next split)</h2>
 * The in-renderLevel stage points proved the hand is NEVER RASTERIZED at the seam pixels
 * in-window (colortex0 untouched by both hand passes; zero hand-band depth at the anchor with
 * the bracket proven armed). The remaining split is WHY iris's HandRenderer emits nothing:
 * <ul>
 *   <li><b>gate</b> — {@code canRender} declines the pass (bytecode-verified conditions, iris
 *       1.11.2+26.2: {@code !camera.isDetached() && camera.entity() instanceof Player &&
 *       !camera.isPanoramicMode() && !hudHidden && !sleeping && gameMode != SPECTATOR}) or the
 *       remaining outer gates kill the body — PER-PASS, bytecode-verified: solid =
 *       {@code iris$isAnyHandSolid && isPackInUseQuick}; translucent = {@code isPackInUseQuick}
 *       ONLY (this iris build has NO translucent held-item gate);</li>
 *   <li><b>submit/dispatch</b> — the body runs ({@code setupGlState} reached, hands submitted,
 *       {@code renderAllFeatures} dispatched) yet nothing rasterizes at those pixels.</li>
 * </ul>
 * The PRIME SUSPECT is {@code camera.entity() instanceof Player} / {@code isDetached()}: the
 * seamless-crossing machinery re-levels and swaps the player and camera focus in exactly the
 * crossing window this symptom lives in.
 *
 * <h2>What one sampled frame records</h2>
 * Per pass (solid, translucent): the {@code canRender} RESULT as iris computed it (from the
 * RETURN callback — ground truth), the six conditions RECOMPUTED same-frame via the SAME
 * members iris reads (incl. the literal {@code mc.gui.hud.isHidden()}), whether the pass BODY
 * was entered ({@code setupGlState} HEAD — proves all three outer gates), and at first body
 * entry the GL viewport + scissor state. Plus the camera-entity identity (class, == mc.player)
 * and the held item. Emitted at translucent RETURN: 1 Hz inside the crossing window, 0.1 Hz
 * AMBIENT — the ambient rows are the CONTROL: they must show canRender=true body=true while
 * the hand is visibly on screen, or the tap itself is not seeing (a leg without healthy
 * ambient rows is VOID, not evidence).
 *
 * <p>Never throws into iris (every public entry catches Throwable and self-disarms); nested
 * dest-pass hands excluded ({@code PortalRendering.isRendering()}).
 */
public final class SeamHandSubmitTap {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-HAND-TAP ";

    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.handSubmitTap");

    private static final double WINDOW = 0.35;

    private static boolean disarmed = false;
    private static boolean announced = false;
    private static long lastWindowNanos = 0L;
    private static long lastAmbientNanos = 0L;

    /** -1 = not in a pass; 0 = solid; 1 = translucent. */
    private static int passIdx = -1;
    private static boolean sampling = false;
    private static boolean ambientSample = false;
    private static double windowDist = Double.NaN;

    /** Per-pass records: -1 unset / 0 false / 1 true. */
    private static final int[] canRenderResult = new int[2];
    private static final String[] failedConds = new String[2];
    private static final boolean[] bodyRan = new boolean[2];
    private static String camEntDesc = "UNSET";
    private static String heldDesc = "UNSET";
    private static String vpDesc = "UNSET";

    private SeamHandSubmitTap() {}

    /** HEAD of renderSolid — decides sampling for this frame and opens the solid record. */
    public static void beginSolid() {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            if (PortalRendering.isRendering()) {
                return; // nested dest-pass hand call — touch NOTHING (final-diff finding 2:
                        // a reset here would wipe an in-progress main-frame sample)
            }
            sampling = false;
            passIdx = -1;
            if (!IrisInterface.invoker.isShaders()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            long now = System.nanoTime();
            double dist = distToNearestCrossablePortal(mc);
            boolean inWindow = dist < WINDOW;
            if (inWindow && now - lastWindowNanos >= 1_000_000_000L) {
                lastWindowNanos = now;
                ambientSample = false;
            }
            else if (!inWindow && now - lastAmbientNanos >= 10_000_000_000L) {
                lastAmbientNanos = now;
                ambientSample = true;
            }
            else {
                return;
            }
            sampling = true;
            windowDist = dist;
            passIdx = 0;
            canRenderResult[0] = canRenderResult[1] = -1;
            failedConds[0] = failedConds[1] = "UNSET";
            bodyRan[0] = bodyRan[1] = false;
            camEntDesc = "UNSET";
            heldDesc = "UNSET";
            vpDesc = "UNSET";
            if (!announced) {
                announced = true;
                LOGGER.info(P + "ARMED (once-only): per-pass canRender result + six-condition"
                    + " decomposition + body-entry proof + viewport/scissor, 1 Hz inside the"
                    + " {}-block crossing window and 0.1 Hz AMBIENT (the control rows — a leg"
                    + " whose ambient rows do not show canRender=true body=true with a visible"
                    + " hand is VOID: the tap is not seeing). A leg without this line never"
                    + " sampled.", WINDOW);
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** RETURN of renderSolid (nested-pass-guarded like every boundary — see beginSolid). */
    public static void endSolid() {
        if (!ENABLED || disarmed || !sampling) {
            return;
        }
        try {
            if (PortalRendering.isRendering()) {
                return;
            }
            passIdx = -1;
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** HEAD of renderTranslucent — opens the translucent record (same sampled frame). */
    public static void beginTranslucent() {
        if (!ENABLED || disarmed || !sampling) {
            return;
        }
        try {
            if (PortalRendering.isRendering()) {
                return; // a NESTED translucent pass must not attach to an armed main sample
            }
            passIdx = 1;
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** RETURN of renderTranslucent — emits the frame's row. */
    public static void endTranslucent() {
        if (!ENABLED || disarmed || !sampling) {
            return;
        }
        try {
            if (PortalRendering.isRendering()) {
                return; // nested pass: leave the (already-emitted or dead) sample alone
            }
            passIdx = -1;
            emit();
            sampling = false;
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * RETURN of iris's private canRender — records the RESULT iris computed (ground truth)
     * and decomposes the six conditions same-frame (interpretation).
     */
    public static void onCanRender(Camera camera, boolean result) {
        if (!ENABLED || disarmed || !sampling || passIdx < 0) {
            return;
        }
        try {
            if (PortalRendering.isRendering()) {
                return; // belt: a nested pass can never write into an armed main sample
            }
            canRenderResult[passIdx] = result ? 1 : 0;
            StringBuilder failed = new StringBuilder();
            Minecraft mc = Minecraft.getInstance();
            Entity ent = null;
            boolean detached = false;
            try {
                detached = camera.isDetached();
                ent = camera.entity();
                if (detached) {
                    failed.append("detached ");
                }
                if (!(ent instanceof Player)) {
                    failed.append("entityNotPlayer ");
                }
                if (camera.isPanoramicMode()) {
                    failed.append("panoramic ");
                }
                // The literal call iris makes (bytecode-verified).
                if (mc.gui.hud.isHidden()) {
                    failed.append("hudHidden ");
                }
                if (ent instanceof LivingEntity le && le.isSleeping()) {
                    failed.append("sleeping ");
                }
                if (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
                    failed.append("spectator ");
                }
            }
            catch (Throwable t) {
                failed.append("DECOMP-FAILED(").append(t.getClass().getSimpleName()).append(") ");
            }
            failedConds[passIdx] = failed.isEmpty() ? "none" : failed.toString().trim();
            // Camera-entity identity — the prime suspect's direct reading.
            camEntDesc = (ent == null ? "null"
                : ent.getClass().getSimpleName()
                    + (ent == mc.player ? "==mc.player" : "!=mc.player(!)"));
            try {
                heldDesc = mc.player == null ? "no-player"
                    : String.valueOf(mc.player.getMainHandItem());
            }
            catch (Throwable ignored) {
                heldDesc = "held-read-failed";
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** HEAD of setupGlState — reached ONLY when all three outer gates passed for this pass. */
    public static void onBodyEntered() {
        if (!ENABLED || disarmed || !sampling || passIdx < 0) {
            return;
        }
        try {
            if (PortalRendering.isRendering()) {
                return; // belt: a nested pass can never write into an armed main sample
            }
            bodyRan[passIdx] = true;
            if ("UNSET".equals(vpDesc)) {
                int drained = 0;
                while (drained < 8 && GL11.glGetError() != GL11.GL_NO_ERROR) {
                    drained++;
                }
                IntBuffer vp = BufferUtils.createIntBuffer(16);
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
                boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
                String box = "";
                if (scissor) {
                    IntBuffer sb = BufferUtils.createIntBuffer(16);
                    GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, sb);
                    box = " box=" + sb.get(0) + "," + sb.get(1) + ","
                        + sb.get(2) + "x" + sb.get(3);
                }
                int err = GL11.glGetError();
                vpDesc = vp.get(0) + "," + vp.get(1) + "," + vp.get(2) + "x" + vp.get(3)
                    + " scissor=" + (scissor ? "ON" + box : "off")
                    + (drained > 0 ? " drained=" + drained : "")
                    + (err != GL11.GL_NO_ERROR ? " glErr=0x" + Integer.toHexString(err) : "");
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        sampling = false;
        passIdx = -1;
        LOGGER.warn(P + "tap threw — DISARMED for this session (hand rendering unaffected)", t);
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

    private static int windowRows = 0;
    private static int ambientRows = 0;

    /** Lazy reflection into vanilla ItemInHandRenderer's equip-animation state (2026-07-28
     *  extension: gate + body + camera-entity all exonerated by the first tap leg, so the
     *  remaining candidates are the SUBMITTED GEOMETRY's transforms — and the prime suspect
     *  for "instant vanish, part-by-part return" is the hand-HEIGHT/equip animation being
     *  reset by the seamless teleports; mainHandHeight 0 = fully lowered = the arm translated
     *  below the viewport = submitted-but-never-rasterized at the probe pixels, exactly the
     *  measured signature). Field names javap-verified against the 26.2 merged jar. */
    private static boolean handReflAttempted = false;
    private static java.lang.reflect.Field fMainHandHeight;
    private static java.lang.reflect.Field fOMainHandHeight;
    private static java.lang.reflect.Field fOffHandHeight;
    private static java.lang.reflect.Field fOOffHandHeight;
    private static java.lang.reflect.Field fMainHandItem;

    private static String readHandAnimState() {
        try {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.client.renderer.ItemInHandRenderer ihr =
                mc.gameRenderer.itemInHandRenderer;
            if (ihr == null) {
                return "NO-IHR";
            }
            if (!handReflAttempted) {
                handReflAttempted = true;
                try {
                    Class<?> c = net.minecraft.client.renderer.ItemInHandRenderer.class;
                    fMainHandHeight = c.getDeclaredField("mainHandHeight");
                    fOMainHandHeight = c.getDeclaredField("oMainHandHeight");
                    fOffHandHeight = c.getDeclaredField("offHandHeight");
                    fOOffHandHeight = c.getDeclaredField("oOffHandHeight");
                    fMainHandItem = c.getDeclaredField("mainHandItem");
                    fMainHandHeight.setAccessible(true);
                    fOMainHandHeight.setAccessible(true);
                    fOffHandHeight.setAccessible(true);
                    fOOffHandHeight.setAccessible(true);
                    fMainHandItem.setAccessible(true);
                }
                catch (Throwable t) {
                    fMainHandHeight = null;
                }
            }
            if (fMainHandHeight == null) {
                return "REFL-FAILED"; // loud, un-tabulatable
            }
            String ihrItem = String.valueOf(fMainHandItem.get(ihr));
            String playerItem = mc.player == null ? "no-player"
                : String.valueOf(mc.player.getMainHandItem());
            return String.format("mainH=%.2f oMainH=%.2f offH=%.2f oOffH=%.2f ihrItem=%s"
                    + " playerItem=%s%s",
                fMainHandHeight.getFloat(ihr), fOMainHandHeight.getFloat(ihr),
                fOffHandHeight.getFloat(ihr), fOOffHandHeight.getFloat(ihr),
                ihrItem, playerItem,
                ihrItem.equals(playerItem) ? "" : " ITEM-MISMATCH(re-equip trigger)");
        }
        catch (Throwable t) {
            return "READ-FAILED(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static void emit() {
        String label;
        if (ambientSample) {
            ambientRows++;
            label = "[AMBIENT #" + ambientRows + "]";
        }
        else {
            windowRows++;
            label = String.format("[window #%d d=%.2f]", windowRows, windowDist);
        }
        LOGGER.info(P + "{} solid: canRender={} failed=[{}] body={} | translucent: canRender={}"
                + " failed=[{}] body={} | camEnt={} held={} vp={} | handAnim: {} — READ:"
                + " canRender=false names the gate (failed[] lists iris's six conditions"
                + " recomputed same-frame; entityNotPlayer/detached are the crossing-machinery"
                + " suspects). canRender=true body=false ⇒ the remaining outer gate — PER PASS"
                + " (bytecode): solid = isAnyHandSolid|packInUse (held item relevant);"
                + " translucent = packInUse ONLY (no held-item gate — body=false there with"
                + " shaders on is itself anomalous). canRender=true body=true on window rows"
                + " while the INLVL verdict says never-rasterized ⇒ the submitted GEOMETRY:"
                + " handAnim mainH near 0 on window rows with near 1 on AMBIENT rows convicts"
                + " the equip-lower animation (teleport-reset hand height; ITEM-MISMATCH names"
                + " the re-equip trigger); mainH healthy on both ⇒ the pose/matrix path"
                + " (IS-BOB) is next. vp= is the GL state at body entry, BEFORE iris's own"
                + " pass setup — corroborating only. AMBIENT rows are the control: they must"
                + " show canRender=true body=true with a visible hand or this tap is blind and"
                + " the leg is VOID; zero AMBIENT rows ⇒ no control ⇒ not adjudicable.",
            label,
            fmt(canRenderResult[0]), failedConds[0], bodyRan[0],
            fmt(canRenderResult[1]), failedConds[1], bodyRan[1],
            camEntDesc, heldDesc, vpDesc, readHandAnimState());
    }

    private static String fmt(int v) {
        return v == -1 ? "NEVER-CALLED" : (v == 1 ? "true" : "false");
    }
}
