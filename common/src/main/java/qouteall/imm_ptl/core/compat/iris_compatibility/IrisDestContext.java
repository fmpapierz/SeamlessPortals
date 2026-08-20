package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;

import java.util.HashMap;

/**
 * IS5-DESTCTX (migration/IS5_DESTCTX_DESIGN.md §3 + the §6 panel folds, wf_ed89a27c-2de) —
 * the dest-context override for iris's viewer-state uniform sampling.
 *
 * <p>THE CONVICTION (the 0.55→0.01 decay curve, 2026-08-17): iris samples the pack's
 * shaders.properties custom uniforms' biome inputs from {@code Minecraft.getInstance()
 * .player} — BiomeUniforms' five per-uniform lambdas each call {@code player.level()
 * .getBiome(player.blockPosition())} — so a cross-dim dest chain's nether look (netherColor
 * via inNetherWastes etc.) decays toward the VIEWER's biome. The fix: while the nested
 * cross-dim dest render runs, those two reads resolve to the DEST level at the VIRTUAL
 * camera's block pos (the {@code MixinIrisBiomeUniforms_DestCtx} redirects). Iris's own
 * formulas and per-pipeline smoothing run unchanged on dest-valued inputs.
 *
 * <p>⟦J⟧ fold B2: the shell bracket runs for SAME-DIM views too and NESTS (A→B→A); the
 * ACTIVE predicate is {@code destDim != the REAL player's dim} decided by the CALLER at
 * push time, and push/pop carries per-invocation save/restore (a single-slot set/clear
 * would strip an outer context mid-window). The MAIN pipeline's update() moments are
 * structurally outside every bracket, so the main chain never samples an override.
 *
 * <p>⟦J⟧ fold B3: two windows into the SAME dest dim the same frame must feed the one
 * per-dim pipeline IDENTICAL inputs (symmetry by construction, never arbitration) — the
 * first bracket into a dest dim each frame captures the pos; later brackets reuse it.
 * Cleared per frame from the IS5 frame hook.
 *
 * <p>⟦J⟧ fold Q1 (liveness): require=0 on the redirects must not hide a dead fix — the
 * redirect body counts hits; sustained ACTIVE pushes with zero hits WARN once, loud.
 */
public final class IrisDestContext {

    private static final Logger LOGGER = LogUtils.getLogger();

    // The CURRENT innermost context (render thread only). null level = inactive.
    private static Level level = null;
    private static BlockPos pos = null;

    // Fold B3: per-frame sticky pos per dest dim.
    private static final HashMap<ResourceKey<Level>, BlockPos> frameSticky = new HashMap<>();

    // Fold Q1 liveness + the 1Hz raw meas line (detector-reads-raw).
    private static long redirectHits = 0;
    private static long activePushes = 0;
    private static long inactivePushes = 0;
    private static long sameDimActivePushes = 0;
    private static ResourceKey<Level> lastSrcDim = null;
    private static long lastHitsAtWarnCheck = 0;
    private static long activeSinceLastHit = 0;
    private static boolean deadWarned = false;
    private static long lastMeasMs = 0;

    private IrisDestContext() {
    }

    /** Per-frame lifecycle (called from the IS5 frame hook beside the slot clears). */
    public static void beginFrame() {
        frameSticky.clear();
    }

    /**
     * Push the bracket's context; returns the PREVIOUS state for the shell's finally.
     * {@code active} is the CALLER's B2 predicate (dest dim != the real player's dim,
     * player non-null); an inactive push still OVERWRITES (A→B→A depth-2 must not inherit
     * B's context) and the pop restores the outer state.
     */
    public static Object[] push(
        Level destLevel, ResourceKey<Level> destDim, Vec3 cameraPos, boolean active,
        ResourceKey<Level> srcDim
    ) {
        Object[] prev = {level, pos};
        // The same-dim witness (2026-08-19): an ACTIVE push whose src == dst would be the
        // main-pipeline poisoning (rain/darkness regression). Counted separately so one
        // leg's log settles it — the raw counter, never an interpretation.
        if (active && srcDim != null && srcDim.equals(destDim)) {
            sameDimActivePushes++;
        }
        lastSrcDim = srcDim;
        if (active && !IPGlobal.disableDestCtx
            && destLevel != null && destDim != null && cameraPos != null) {
            BlockPos p = frameSticky.computeIfAbsent(
                destDim, k -> BlockPos.containing(cameraPos));
            level = destLevel;
            pos = p;
            activePushes++;
            activeSinceLastHit++;
        } else {
            level = null;
            pos = null;
            inactivePushes++;
        }
        long now = System.currentTimeMillis();
        if (now - lastMeasMs >= 1000) {
            lastMeasMs = now;
            LOGGER.info("[Seamless Portals] [IS5-DESTCTX] 1Hz: pushA/I={}/{} SAMEDIM-ACTIVE={}"
                    + " hits={} src={} ctx={} sticky={}",
                activePushes, inactivePushes, sameDimActivePushes, redirectHits,
                lastSrcDim == null ? "?" : lastSrcDim.identifier().getPath(),
                level == null ? "-" : (destDim == null ? "?" : destDim.identifier().getPath())
                    + "@" + pos,
                frameSticky.keySet().size());
            // Fold Q1: a dead redirect (require=0 masked a shape drift) = ACTIVE pushes
            // accumulating with zero new hits. One-shot loud; the window then degrades to
            // today's viewer sampling — wrong-but-shipped, never a crash.
            if (!deadWarned && activeSinceLastHit > 300 && redirectHits == lastHitsAtWarnCheck) {
                deadWarned = true;
                LOGGER.warn("[Seamless Portals] [IS5-DESTCTX] the biome redirect NEVER FIRED"
                    + " across {} active pushes — the BiomeUniforms lambda shape has drifted"
                    + " (iris bump?); dest windows sample the viewer's biome again (the"
                    + " decay defect returns). Re-javap BiomeUniforms per the design.",
                    activeSinceLastHit);
            }
            lastHitsAtWarnCheck = redirectHits;
        }
        return prev;
    }

    /** The shell finally's restore half. */
    public static void pop(Object[] prev) {
        level = (Level) prev[0];
        pos = (BlockPos) prev[1];
    }

    public static boolean isActive() {
        return level != null;
    }

    public static Level level() {
        return level;
    }

    public static BlockPos pos() {
        return pos;
    }

    public static void noteRedirectHit() {
        redirectHits++;
        activeSinceLastHit = 0;
    }
}
