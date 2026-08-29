package qouteall.imm_ptl.core.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * SAME-DIM WATER/CLOUDS stage census (2026-08-29 arc, round 1) — the diagnose-first instrument
 * that convicted the stage-target routing bug. For every portal dest pass (decomposed route) it
 * accounts:
 * <ul>
 *   <li>the prepared draw lists per chunk layer (SOLID/CUTOUT/TRANSLUCENT draw counts) —
 *       distinguishes "translucent never prepared" from "prepared but discarded";</li>
 *   <li>a section-level walk of the pass's visibleSections: how many carry translucent geometry,
 *       and where each drops out of {@code prepareChunkRenders}' emit path (null SectionDraw /
 *       null dispatcher slice / custom-index-required-but-null — the bytecode-verified skip gate
 *       at ChunkSectionsToRender build time);</li>
 *   <li>raw-GL stage snapshots (stencil/depth/blend/scissor/color-mask) at the four stage
 *       boundaries 10.6→10.8→10.9→10.10→10.11, change-logged — surfaces a state leak from the
 *       same-dim entity scratch pass sitting between the working opaque draw and the missing
 *       translucent/clouds draws;</li>
 *   <li>every {@code renderPortalClouds} call's outcome: which early-out fired (incl. the
 *       once-per-dim-per-frame cap — the nested-pass-consumes-the-budget hypothesis) or DREW,
 *       plus any swallowed Throwable (previously invisible).</li>
 * </ul>
 * Lever: {@code -PstageCensus=true} → {@code -Dseamlessportals.stageCensus=true}. Byte-inert
 * without the lever (the house probe pattern: every site guards on {@code ENABLED}, a static
 * final false). Flood-safe by design: change-logged per key, 120-frame heartbeat, and a global
 * 80-lines/second cap with a suppression note.
 */
public final class StageCensusProbe {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[STAGE CENSUS] ";

    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.stageCensus");

    private static long frameIndex = 0;
    private static int passOrdinal = 0;
    private static int cloudOrdinal = 0;

    private static final Map<String, String> lastByKey = new HashMap<>();
    private static final Map<String, Long> lastHeartbeatFrame = new HashMap<>();
    private static final Set<String> oneShotErrors = new HashSet<>();
    private static int linesThisSecond = 0;
    private static int suppressedThisSecond = 0;
    private static long secondStamp = 0;
    private static boolean announced = false;

    private static final ByteBuffer COLOR_MASK_BUF = BufferUtils.createByteBuffer(16);

    private StageCensusProbe() {}

    /** Frame boundary — driven from endCloudFrames (MyGameRenderer.endFramePooled, per frame). */
    public static void endFrame() {
        if (!ENABLED) {
            return;
        }
        frameIndex++;
        passOrdinal = 0;
        cloudOrdinal = 0;
    }

    /** The per-pass census line. Call AFTER prepareChunkRenders + canDraw are computed. */
    public static void passStats(
        ResourceKey<Level> destDim, boolean sharedState, int layer,
        ChunkSectionsToRender destChunks, LevelRenderer destRenderer,
        boolean canDraw, boolean sodiumArmed, boolean samplerOk, Vec3 destCameraPos
    ) {
        if (!ENABLED) {
            return;
        }
        try {
            announceOnce();
            int ord = passOrdinal++;
            int solidDraws = countDraws(destChunks, ChunkSectionLayer.SOLID);
            int cutoutDraws = countDraws(destChunks, ChunkSectionLayer.CUTOUT);
            int translDraws = countDraws(destChunks, ChunkSectionLayer.TRANSLUCENT);

            int total = 0;
            int renderable = 0;
            int withTransl = 0;
            int tDrawNull = 0;
            int sliceNull = 0;
            int custom = 0;
            int idxNull = 0;
            int drawable = 0;
            double nearestTranslSq = -1;
            var list = ((IEWorldRenderer) destRenderer).portal_getChunkInfoList();
            SectionRenderDispatcher dispatcher = destRenderer.sectionRenderDispatcher();
            if (dispatcher != null) {
                dispatcher.lock();
            }
            try {
                total = list.size();
                for (SectionRenderDispatcher.RenderSection section : list) {
                    SectionMesh mesh = section.getSectionMesh();
                    if (mesh == null) {
                        continue;
                    }
                    if (mesh.hasRenderableLayers()) {
                        renderable++;
                    }
                    if (!mesh.hasTranslucentGeometry()) {
                        continue;
                    }
                    withTransl++;
                    if (destCameraPos != null) {
                        double d = section.getRenderOrigin().distToCenterSqr(
                            destCameraPos.x, destCameraPos.y, destCameraPos.z);
                        if (nearestTranslSq < 0 || d < nearestTranslSq) {
                            nearestTranslSq = d;
                        }
                    }
                    SectionMesh.SectionDraw draw = mesh.getSectionDraw(ChunkSectionLayer.TRANSLUCENT);
                    if (draw == null) {
                        tDrawNull++;
                        continue;
                    }
                    var slice = dispatcher == null
                        ? null : dispatcher.getRenderSectionSlice(mesh, ChunkSectionLayer.TRANSLUCENT);
                    if (slice == null) {
                        sliceNull++;
                        continue;
                    }
                    if (draw.hasCustomIndexBuffer()) {
                        custom++;
                        if (slice.indexBuffer() == null) {
                            idxNull++;
                            continue;
                        }
                    }
                    drawable++;
                }
            } finally {
                if (dispatcher != null) {
                    dispatcher.unlock();
                }
            }

            Minecraft mc = Minecraft.getInstance();
            boolean fabulous = mc.gameRenderer.gameRenderState().useShaderTransparency();
            boolean translTargetLive = mc.levelRenderer != null
                && mc.levelRenderer.translucentTarget() != null;

            String key = "pass " + shortDim(destDim) + " L" + layer + (sharedState ? " SD" : " XD");
            String content = "ord=" + ord
                + " canDraw=" + canDraw + " sodiumArmed=" + sodiumArmed + " sampler=" + samplerOk
                + " maxIdx=" + destChunks.maxIndicesRequired()
                + " draws[s/c/t]=" + solidDraws + "/" + cutoutDraws + "/" + translDraws
                + " sect[total=" + total + " renderable=" + renderable + " transl=" + withTransl
                + " tDrawNull=" + tDrawNull + " sliceNull=" + sliceNull + " custom=" + custom
                + " idxNull=" + idxNull + " drawable=" + drawable
                + " nearT=" + (nearestTranslSq < 0 ? "-" : String.valueOf(Math.round(Math.sqrt(nearestTranslSq))))
                + "] fabulous=" + fabulous + " translTarget=" + translTargetLive;
            emit(key, content);
        } catch (Throwable t) {
            oneShotError("passStats", t);
        }
    }

    /** Raw-GL stage snapshot (externally-owned state: stencil/scissor + depth/blend/mask). */
    public static String glSnap() {
        if (!ENABLED) {
            return null;
        }
        try {
            COLOR_MASK_BUF.clear();
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, COLOR_MASK_BUF);
            String cm = "" + COLOR_MASK_BUF.get(0) + COLOR_MASK_BUF.get(1)
                + COLOR_MASK_BUF.get(2) + COLOR_MASK_BUF.get(3);
            return "st=" + (GL11.glIsEnabled(GL11.GL_STENCIL_TEST) ? 1 : 0)
                + " sf=" + GL11.glGetInteger(GL11.GL_STENCIL_FUNC)
                + "/" + GL11.glGetInteger(GL11.GL_STENCIL_REF)
                + "/" + Integer.toHexString(GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK))
                + " swm=" + Integer.toHexString(GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK))
                + " dt=" + (GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 1 : 0)
                + " dm=" + (GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) ? 1 : 0)
                + " df=" + GL11.glGetInteger(GL11.GL_DEPTH_FUNC)
                + " bl=" + (GL11.glIsEnabled(GL11.GL_BLEND) ? 1 : 0)
                + " sc=" + (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) ? 1 : 0)
                + " cm=" + cm;
        } catch (Throwable t) {
            oneShotError("glSnap", t);
            return "err";
        }
    }

    /** Change-logged snapshot line per pass stage boundary. */
    public static void glStage(
        ResourceKey<Level> destDim, boolean sharedState, int layer, String stage, String snap
    ) {
        if (!ENABLED || snap == null) {
            return;
        }
        emit("gl " + shortDim(destDim) + " L" + layer + (sharedState ? " SD " : " XD ") + stage, snap);
    }

    /** renderPortalClouds outcome — every call logs which gate fired (or DREW/THREW). */
    public static void cloud(ResourceKey<Level> destDim, int layer, String outcome, String extra) {
        if (!ENABLED) {
            return;
        }
        int ord = cloudOrdinal++;
        emit("cloud " + shortDim(destDim) + " L" + layer,
            "ord=" + ord + " " + outcome + (extra == null ? "" : " " + extra));
    }

    private static void announceOnce() {
        if (announced) {
            return;
        }
        announced = true;
        LOGGER.info(P + "ACTIVE (lever -Dseamlessportals.stageCensus). Schema: pass <dim> L<layer> "
            + "SD|XD = same/cross-dim dest pass; draws[s/c/t] = prepared draw-list sizes per layer; "
            + "sect[...] = translucent section accounting (idxNull = custom-index-required-but-null "
            + "→ prepareChunkRenders SKIPS the draw); gl <stage> = raw stencil/depth/blend/scissor "
            + "snapshot; cloud ord=N <outcome> = renderPortalClouds gate. Change-logged + 120f "
            + "heartbeat + 80 lines/s cap.");
    }

    private static int countDraws(ChunkSectionsToRender chunks, ChunkSectionLayer layer) {
        var byMergeKey = chunks.drawGroupsPerLayer().get(layer);
        if (byMergeKey == null) {
            return 0;
        }
        int n = 0;
        for (var drawList : byMergeKey.values()) {
            n += drawList.size();
        }
        return n;
    }

    private static String shortDim(ResourceKey<Level> dim) {
        return dim == null ? "null" : dim.identifier().getPath();
    }

    private static void emit(String key, String content) {
        long nowSec = System.currentTimeMillis() / 1000L;
        if (nowSec != secondStamp) {
            if (suppressedThisSecond > 0) {
                LOGGER.info(P + "(" + suppressedThisSecond + " lines rate-capped)");
            }
            secondStamp = nowSec;
            suppressedThisSecond = 0;
            linesThisSecond = 0;
        }
        String last = lastByKey.get(key);
        Long lastHb = lastHeartbeatFrame.get(key);
        boolean changed = !content.equals(last);
        boolean heartbeat = lastHb == null || frameIndex - lastHb >= 120;
        if (!changed && !heartbeat) {
            return;
        }
        if (linesThisSecond >= 80) {
            suppressedThisSecond++;
            return;
        }
        linesThisSecond++;
        lastByKey.put(key, content);
        lastHeartbeatFrame.put(key, frameIndex);
        LOGGER.info(P + "f" + frameIndex + " " + key + " " + content + (changed ? "" : " (hb)"));
    }

    private static void oneShotError(String site, Throwable t) {
        if (oneShotErrors.add(site)) {
            LOGGER.info(P + "probe error at " + site + ": " + t, t);
        }
    }
}
