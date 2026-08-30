package qouteall.imm_ptl.core.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;

import java.util.HashSet;
import java.util.Set;

/**
 * ROUND-TRIP CHUNK CENSUS (2026-08-29 arc 1, round 0 — diagnose-first, read-only). The vanilla
 * (no-sodium) path visibly re-meshes the returned-to dimension on quick portal round trips while
 * sodium stays clean. This probe discriminates the three candidate mechanisms in ONE run:
 * <ul>
 *   <li>(a) MESH WIPE — compiled meshes actually lost: logged via every
 *       {@code invalidateCompiledGeometry} (the ViewArea-replacing event, with attribution stack),
 *       {@code releaseAllBuffers}, purge drops (preset/column TTL evictions), and lossy preset
 *       swaps; promote/demote censuses show compiled counts across the swap.</li>
 *   <li>(b) REVEAL LAG — meshes intact but visibility repopulates slowly: the post-swap sampler's
 *       compiled count stays high while visibleSections climbs from ~0.</li>
 *   <li>(c) RE-SEND RE-MESH — server re-sends chunks on return and dirty re-meshing repaints:
 *       loaded-chunk count climbs in the sampler while compiled dips/holds.</li>
 * </ul>
 * Lever: {@code -ProundTripProbe} → {@code -Dseamlessportals.roundTripProbe=true}. Byte-inert
 * without it (every site guards on {@code ENABLED}). Log tag {@code [ROUND TRIP]}. Event-driven —
 * no per-frame lines except the 12x1s post-swap sampler.
 */
public final class RoundTripProbe {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[ROUND TRIP] ";

    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.roundTripProbe");

    private static long frameIndex = 0;
    private static long samplerStartFrame = -1;
    private static long lastSampleFrame = -1;
    private static String samplerLabel = "";
    // Window counters (reset when the sampler arms).
    private static int invalidatesInWindow = 0;
    private static int presetSwapsInWindow = 0;
    private static int purgeColumnsInWindow = 0;
    private static final Set<String> oneShotErrors = new HashSet<>();

    private RoundTripProbe() {}

    /** Frame driver — called from endCloudFrames (per-frame flag-ON walk). */
    public static void endFrame() {
        if (!ENABLED) {
            return;
        }
        frameIndex++;
        if (samplerStartFrame < 0) {
            return;
        }
        try {
            if (frameIndex - samplerStartFrame > 12L * 60L) {
                samplerStartFrame = -1;
                log("sampler done " + samplerLabel
                    + " (window: invalidates=" + invalidatesInWindow
                    + " presetSwaps=" + presetSwapsInWindow
                    + " purgedColumns=" + purgeColumnsInWindow + ")");
                return;
            }
            if (frameIndex - lastSampleFrame >= 60) {
                lastSampleFrame = frameIndex;
                Minecraft mc = Minecraft.getInstance();
                if (mc.levelRenderer != null && mc.level != null) {
                    log("sample +" + ((frameIndex - samplerStartFrame) / 60) + "s " + samplerLabel
                        + " main[" + census(mc.levelRenderer) + "]"
                        + " chunks=" + mc.level.getChunkSource().getLoadedChunksCount()
                        + " inv=" + invalidatesInWindow
                        + " pswap=" + presetSwapsInWindow
                        + " purgedCols=" + purgeColumnsInWindow);
                }
            }
        } catch (Throwable t) {
            oneShotError("endFrame", t);
        }
    }

    public static void onPromote(ResourceKey<Level> dim, LevelRenderer renderer) {
        if (!ENABLED) {
            return;
        }
        try {
            log("PROMOTE " + dim.identifier().getPath() + " [" + census(renderer) + "]");
        } catch (Throwable t) {
            oneShotError("onPromote", t);
        }
    }

    public static void onDemote(ResourceKey<Level> dim, LevelRenderer renderer) {
        if (!ENABLED) {
            return;
        }
        try {
            log("DEMOTE " + dim.identifier().getPath() + " [" + census(renderer) + "]");
        } catch (Throwable t) {
            oneShotError("onDemote", t);
        }
    }

    /** Called at the end of the visual swap; arms the 12-second per-second sampler. */
    public static void onSwapComplete(
        ResourceKey<Level> fromDim, ResourceKey<Level> toDim, LevelRenderer mainRenderer
    ) {
        if (!ENABLED) {
            return;
        }
        try {
            samplerLabel = fromDim.identifier().getPath() + "->" + toDim.identifier().getPath();
            samplerStartFrame = frameIndex;
            lastSampleFrame = frameIndex;
            invalidatesInWindow = 0;
            presetSwapsInWindow = 0;
            purgeColumnsInWindow = 0;
            log("SWAP COMPLETE " + samplerLabel + " main[" + census(mainRenderer) + "] — sampling 12s");
        } catch (Throwable t) {
            oneShotError("onSwapComplete", t);
        }
    }

    /** Fired from the ViewArea-install redirect = every invalidateCompiledGeometry execution. */
    public static void onInvalidate(ClientLevel level, ViewArea oldViewArea) {
        if (!ENABLED) {
            return;
        }
        try {
            invalidatesInWindow++;
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder attribution = new StringBuilder();
            for (int i = 4; i < Math.min(st.length, 10); i++) {
                String cls = st[i].getClassName();
                attribution.append(cls.substring(cls.lastIndexOf('.') + 1))
                    .append('.').append(st[i].getMethodName()).append(':')
                    .append(st[i].getLineNumber()).append(" <- ");
            }
            String old = oldViewArea == null ? "null"
                : oldViewArea instanceof ImmPtlViewArea ip
                    ? "ImmPtl{" + ip.ip_probeCensus() + "}"
                    : oldViewArea.getClass().getSimpleName();
            log("INVALIDATE (ViewArea replaced) dim=" + dimName(level)
                + " old=" + old + " via " + attribution);
        } catch (Throwable t) {
            oneShotError("onInvalidate", t);
        }
    }

    public static void onViewAreaCreated(Level world, int renderDistance) {
        if (!ENABLED) {
            return;
        }
        try {
            log("NEW VIEWAREA dim=" + dimName(world) + " rd=" + renderDistance);
        } catch (Throwable t) {
            oneShotError("onViewAreaCreated", t);
        }
    }

    public static void onReleaseAllBuffers(Level level, String census) {
        if (!ENABLED) {
            return;
        }
        log("RELEASE ALL dim=" + dimName(level) + " [" + census + "]");
    }

    public static void onPresetSwap(
        Level level, SectionPos newCenter, boolean created, ImmPtlViewArea viewArea
    ) {
        if (!ENABLED) {
            return;
        }
        try {
            presetSwapsInWindow++;
            log("PRESET " + (created ? "CREATED" : "SWAP") + " dim=" + dimName(level)
                + " center=" + newCenter.x() + "," + newCenter.y() + "," + newCenter.z()
                + " [" + viewArea.ip_probeCensus() + "]");
        } catch (Throwable t) {
            oneShotError("onPresetSwap", t);
        }
    }

    public static void onPurge(
        Level level, boolean memPressure, int presetsDropped, int columnsDropped,
        int sectionsQueuedForReset, String census
    ) {
        if (!ENABLED) {
            return;
        }
        purgeColumnsInWindow += columnsDropped;
        log("PURGE dim=" + dimName(level) + (memPressure ? " MEM-PRESSURE" : "")
            + " presetsDropped=" + presetsDropped + " columnsDropped=" + columnsDropped
            + " sectionResetsQueued=" + sectionsQueuedForReset + " [" + census + "]");
    }

    /** Renderer census: viewArea kind + compiled-mesh accounting + visibleSections size. */
    public static String census(LevelRenderer renderer) {
        try {
            ViewArea va = ((IEWorldRenderer) renderer).ip_getBuiltChunkStorage();
            int vs = ((IEWorldRenderer) renderer).portal_getChunkInfoList().size();
            if (va instanceof ImmPtlViewArea ip) {
                return ip.ip_probeCensus() + " vs=" + vs;
            }
            return "va=" + (va == null ? "null" : va.getClass().getSimpleName()) + " vs=" + vs;
        } catch (Throwable t) {
            oneShotError("census", t);
            return "err";
        }
    }

    private static String dimName(Level level) {
        return level == null ? "null" : level.dimension().identifier().getPath();
    }

    private static void log(String s) {
        LOGGER.info(P + "f" + frameIndex + " " + s);
    }

    private static void oneShotError(String site, Throwable t) {
        if (oneShotErrors.add(site)) {
            LOGGER.info(P + "probe error at " + site + ": " + t, t);
        }
    }
}
