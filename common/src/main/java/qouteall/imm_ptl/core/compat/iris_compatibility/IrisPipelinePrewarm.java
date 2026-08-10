package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;

import java.util.ArrayList;
import java.util.List;

/**
 * PERF-P2 — iris per-dimension pipeline PRE-WARM (user-picked fix, 2026-08-10).
 *
 * <p>THE FREEZE THIS KILLS (P1-measured): iris creates a dimension's pipeline lazily on the
 * FIRST portal view into that dimension — a full shaderpack compile on the render thread,
 * measured as a 3-second census gap (13:57:50-53) landing exactly on the "first look into a
 * nether portal" moment, mid-gameplay. The crossing code itself measured 1-2ms and is innocent.
 *
 * <p>THE MOVE: compile every server dimension's pipeline at the moment the MAIN pipeline
 * generation appears (world join / pack reload / K toggle) instead of on first portal look.
 * The stall still happens, but at a moment the player is already waiting.
 *
 * <p>MECHANISM (javap-proven on iris 1.11.2+26.2): {@code Iris.getCurrentDimension()} builds its
 * {@code NamespacedId} purely from the dimension KEY's identifier (namespace + path, bytecode
 * read 2026-08-10), so constructing the same id for every {@code connection.levels()} key hits
 * the exact map slots iris will later look up — the pre-warm cannot silently warm a wrong key.
 * {@code PipelineManager.preparePipeline(id)} is a cheap map hit when the pipeline already
 * exists (the ShaderpackViewsProbe precedent) and a real compile when it doesn't; it also
 * SWITCHES the manager slot, so the loop ends by re-preparing the CURRENT dimension — the same
 * un-restored-slot hazard the IS5-ACT heal retarget documented for nested renders.
 *
 * <p>GENERATION DETECTION: the current pipeline's object identity. It changes on world join,
 * pack reload, shader toggle, AND ordinary cross-dim teleports — the teleport case re-runs the
 * loop as pure map hits (sub-ms), which is why the announcement only prints when a real compile
 * happened (>50ms). Called from GameRendererMixin frame-end via the {@code IrisInterface.invoker}
 * facade (no-op when iris is absent). Escape hatch: {@code -PdisablePipelinePrewarm}.
 */
public final class IrisPipelinePrewarm {

    private static final Logger LOGGER = LogUtils.getLogger();

    private IrisPipelinePrewarm() {}

    /** Identity of the main-pipeline generation the last warm ran for; null = no generation. */
    private static Object warmedForPipeline = null;

    public static void tick() {
        if (IPGlobal.disablePipelinePrewarm || sessionDisabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.player.connection == null) {
            warmedForPipeline = null; // world left: next join is a fresh generation
            return;
        }
        try {
            if (!IrisInterface.invoker.isShaders()) {
                warmedForPipeline = null;
                return;
            }
            WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
            if (current == null || current == warmedForPipeline) {
                return; // main not created yet, or this generation already warmed
            }
            NamespacedId currentDim = Iris.getCurrentDimension();
            if (currentDim == null) {
                return;
            }
            long t0 = System.nanoTime();
            List<String> warmed = new ArrayList<>();
            for (ResourceKey<Level> key : mc.player.connection.levels()) {
                NamespacedId id = new NamespacedId(
                    key.identifier().getNamespace(), key.identifier().getPath());
                if (id.equals(currentDim)) {
                    continue; // the main pipeline IS this dim's pipeline
                }
                long dimT0 = System.nanoTime();
                Iris.getPipelineManager().preparePipeline(id);
                long dimMs = (System.nanoTime() - dimT0) / 1_000_000L;
                warmed.add(id.getNamespace() + ":" + id.getName() + "=" + dimMs + "ms");
            }
            // Slot restore — preparePipeline switched the manager slot per warmed dim; hand the
            // CURRENT dimension's pipeline back before the next frame renders anything.
            Iris.getPipelineManager().preparePipeline(currentDim);
            warmedForPipeline = Iris.getPipelineManager().getPipelineNullable();
            long totalMs = (System.nanoTime() - t0) / 1_000_000L;
            // Only a real compile is worth a line — teleport generations re-run as pure map
            // hits and would otherwise re-log a 0ms sweep at every crossing.
            if (totalMs > 50) {
                LOGGER.info(
                    "[Seamless Portals] [PERF-P2] iris pipeline pre-warm: {} total={}ms"
                        + " (the first-look-into-a-portal compile freeze, paid here instead;"
                        + " escape: -PdisablePipelinePrewarm)",
                    warmed, totalMs);
            }
        } catch (Throwable t) {
            // Never let the pre-warm hurt the frame loop; disable for the session on failure.
            warmedForPipeline = null;
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("[Seamless Portals] [PERF-P2] pipeline pre-warm failed — disabled"
                    + " for this session (first-look compiles revert to on-demand)", t);
            }
            sessionDisabled = true;
        }
    }

    private static boolean failureLogged = false;
    private static boolean sessionDisabled = false;
}
