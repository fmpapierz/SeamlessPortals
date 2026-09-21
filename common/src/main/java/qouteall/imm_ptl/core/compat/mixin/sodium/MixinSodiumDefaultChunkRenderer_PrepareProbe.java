package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.Iterator;

/**
 * 26.3 / sodium 0.9.2 PROBE (LOG-ONLY, lever {@code -Dseamlessportals.sodiumPrepareProbe=true}) — does an OUTER view's
 * terrain draw run on an INNER view's prepare?
 *
 * <p><b>Why this can happen now and could not before.</b> javap, sodium-mc26.2-0.9.1 vs sodium-mc26.3-0.9.2,
 * {@code DefaultChunkRenderer}: 0.9.1's {@code render(..)} did BOTH halves per call — phase 1 filled every listed region's
 * cached {@code MultiDrawBatch} that was not {@code isFilled} (offsets 114-151), phase 2 drew them. 0.9.2 SPLIT it:
 * <ul>
 *   <li>new {@code prepare(ChunkRenderListIterable, CameraTransform, boolean)} — the fill loop, for ALL THREE passes at once,
 *       plus a new per-renderer {@code boolean[] shouldDraw} ("did this pass end up with anything") written at 212-220;</li>
 *   <li>{@code render(..)} — starts with {@code if (!shouldDraw[passIndex]) return;} (offsets 0-12) and then only DRAWS the
 *       cached batches; it never fills.</li>
 * </ul>
 * {@code prepare} is reached from sodium's own {@code LevelRendererMixin.getRenderState} wrap of vanilla's
 * {@code prepareChunkRenders} call (once per frame, before the frame graph runs) and, for a portal's dest pass, from
 * {@code SodiumInterface.OnSodiumPresent.ip_armDestChunkRenders}. On a SHARED renderer (same-dim portal, or A-&gt;B-&gt;A
 * nesting) both write the SAME {@code shouldDraw[]}, and a portal-layer {@code ChunkRenderList.prepareForRender} that
 * detects a changed list calls {@code RenderRegion.clearAllCachedBatches()}, which also empties the region-own batches the
 * outer view prepared. Any outer-view draw that comes AFTER the inner prepare then (M1) obeys the inner view's
 * {@code shouldDraw[]} and (M2) finds cleared batches it no longer refills. On the improved-transparency path that is the
 * main view's whole translucent terrain (the portal driver runs at {@code executeOit} HEAD, before it).
 *
 * <p><b>What it reports (1 Hz, one line).</b> Per sodium pass, for draws whose portal layer differs from the layer of the
 * most recent {@code prepare}: how many there were, how many of them were SKIPPED although the drawing view's own prepare
 * said draw (M1), how many went ahead, and how many listed regions held a batch that was no longer {@code isFilled} (M2).
 * A healthy frame arrangement reports all zeros.
 *
 * <p>{@code require = 0}, {@code remap = false} (mod-class target). The class name carries {@code Sodium} for the compat
 * plugin's substring gate.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinSodiumDefaultChunkRenderer_PrepareProbe {

    private static final boolean seamlessportals$probe = Boolean.getBoolean("seamlessportals.sodiumPrepareProbe");

    private static final int seamlessportals$MAX_LAYERS = 8;
    private static final int seamlessportals$PASSES = 3;

    @Shadow
    @Final
    private boolean[] shouldDraw;

    /** Portal layer (0 = main view) of the most recent prepare on ANY DefaultChunkRenderer, and the renderer it ran on. */
    private static int seamlessportals$lastPrepareLayer = 0;
    private static Object seamlessportals$lastPrepareRenderer = null;

    /** What each layer's OWN most recent prepare decided, per pass (index = layer, clamped). */
    private static final boolean[][] seamlessportals$ownShouldDraw =
        new boolean[seamlessportals$MAX_LAYERS][seamlessportals$PASSES];
    private static final Object[] seamlessportals$ownRenderer = new Object[seamlessportals$MAX_LAYERS];

    private static long seamlessportals$windowStart = 0L;
    private static long seamlessportals$prepares = 0L;
    private static long seamlessportals$draws = 0L;
    private static final long[] seamlessportals$foreignDraws = new long[seamlessportals$PASSES];
    private static final long[] seamlessportals$wronglySkipped = new long[seamlessportals$PASSES];
    private static final long[] seamlessportals$wronglyForced = new long[seamlessportals$PASSES];
    private static final long[] seamlessportals$clearedBatches = new long[seamlessportals$PASSES];
    private static final long[] seamlessportals$listedBatches = new long[seamlessportals$PASSES];
    /** Camera-scope draws: listed regions / of those with an unfilled batch. Index 0 = main view, 1 = portal views. */
    private static final long[] seamlessportals$camListed = new long[2];
    private static final long[] seamlessportals$camHoles = new long[2];

    private static int seamlessportals$layer() {
        int layer = PortalRendering.isRendering() ? PortalRendering.getPortalLayer() : 0;
        return Math.min(Math.max(layer, 0), seamlessportals$MAX_LAYERS - 1);
    }

    @Inject(method = "prepare", at = @At("RETURN"), require = 0, remap = false)
    private void seamlessportals$probePrepare(CallbackInfo ci) {
        if (!seamlessportals$probe) {
            return;
        }
        int layer = seamlessportals$layer();
        seamlessportals$lastPrepareLayer = layer;
        seamlessportals$lastPrepareRenderer = this;
        seamlessportals$ownRenderer[layer] = this;
        int n = Math.min(this.shouldDraw.length, seamlessportals$PASSES);
        System.arraycopy(this.shouldDraw, 0, seamlessportals$ownShouldDraw[layer], 0, n);
        seamlessportals$prepares++;
    }

    @Inject(method = "render", at = @At("HEAD"), require = 0, remap = false)
    private void seamlessportals$probeRender(
        CallbackInfo ci,
        @Local(argsOnly = true) ChunkRenderListIterable lists,
        @Local(argsOnly = true) TerrainRenderPass pass
    ) {
        if (!seamlessportals$probe) {
            return;
        }
        seamlessportals$draws++;
        int layer = seamlessportals$layer();
        int passIndex = DefaultTerrainRenderPasses.getPassIndex(pass);
        if (passIndex >= 0 && passIndex < seamlessportals$PASSES
            && seamlessportals$lastPrepareRenderer == this
            && seamlessportals$ownRenderer[layer] == this
            && seamlessportals$lastPrepareLayer != layer) {
            // This draw belongs to `layer`, but the renderer's prepared state was last written by another layer.
            seamlessportals$foreignDraws[passIndex]++;
            boolean own = seamlessportals$ownShouldDraw[layer][passIndex];
            boolean now = this.shouldDraw[passIndex];
            if (own && !now) {
                seamlessportals$wronglySkipped[passIndex]++;
            } else if (!own && now) {
                seamlessportals$wronglyForced[passIndex]++;
            }
            // M2: batches this view prepared that something cleared since. getCachedBatch resolves to this layer's own batch
            // (MixinSodiumRenderRegion HOOK 1), i.e. exactly the object the draw below is about to read.
            Iterator<ChunkRenderList> it = lists.iterator(pass.isTranslucent());
            while (it.hasNext()) {
                RenderRegion region = it.next().getRegion();
                if (region.getStorage(pass) == null || region.getResources() == null) {
                    continue;
                }
                MultiDrawBatch batch = region.getCachedBatch(pass);
                seamlessportals$listedBatches[passIndex]++;
                if (!batch.isFilled) {
                    seamlessportals$clearedBatches[passIndex]++;
                }
            }
        }

        // HOLES (the step the player can see): a CAMERA-scope draw whose listed region holds a batch that is not filled draws
        // NOTHING for that region — render() never fills on 0.9.2. Counted for every camera-scope draw, split main view vs
        // portal views; iris's shadow-map draws are left out (their holes are shadow holes, not x-ray).
        if (passIndex >= 0 && passIndex < seamlessportals$PASSES
            && !qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface.invoker.isRenderingShadowMap()) {
            int who = layer == 0 ? 0 : 1;
            Iterator<ChunkRenderList> holeIt = lists.iterator(pass.isTranslucent());
            while (holeIt.hasNext()) {
                RenderRegion region = holeIt.next().getRegion();
                if (region.getStorage(pass) == null || region.getResources() == null) {
                    continue;
                }
                seamlessportals$camListed[who]++;
                if (!region.getCachedBatch(pass).isFilled) {
                    seamlessportals$camHoles[who]++;
                }
            }
        }

        long nowNs = System.nanoTime();
        if (seamlessportals$windowStart == 0L) {
            seamlessportals$windowStart = nowNs;
            return;
        }
        if (nowNs - seamlessportals$windowStart < 1_000_000_000L) {
            return;
        }
        seamlessportals$windowStart = nowNs;
        com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
            "[SODIUM PREPARE PROBE] last 1s: prepares={} draws={} | draws on ANOTHER layer's prepare (solid/cutout/translucent)="
                + "{}/{}/{} | of those WRONGLY SKIPPED (own prepare said draw, shouldDraw now false)={}/{}/{} | wrongly forced="
                + "{}/{}/{} | listed batches no longer filled={}/{}/{} of {}/{}/{} | CAMERA-DRAW HOLES (listed region, batch not "
                + "filled at draw time) main view={} of {} portal views={} of {} | SESSION TOTALS: outer re-prepares={} "
                + "arena moves -> still-filled portal-layer batches cleared={} LEFT STALE (lever off)={} | list change in one "
                + "scope -> filled other-scope portal-layer batches spared={} WIPED (lever off)={}",
            seamlessportals$prepares, seamlessportals$draws,
            seamlessportals$foreignDraws[0], seamlessportals$foreignDraws[1], seamlessportals$foreignDraws[2],
            seamlessportals$wronglySkipped[0], seamlessportals$wronglySkipped[1], seamlessportals$wronglySkipped[2],
            seamlessportals$wronglyForced[0], seamlessportals$wronglyForced[1], seamlessportals$wronglyForced[2],
            seamlessportals$clearedBatches[0], seamlessportals$clearedBatches[1], seamlessportals$clearedBatches[2],
            seamlessportals$listedBatches[0], seamlessportals$listedBatches[1], seamlessportals$listedBatches[2],
            seamlessportals$camHoles[0], seamlessportals$camListed[0], seamlessportals$camHoles[1], seamlessportals$camListed[1],
            qouteall.imm_ptl.core.IPGlobal.sodiumOuterReprepareCount,
            qouteall.imm_ptl.core.IPGlobal.sodiumArenaLayerBatchesClearedCount,
            qouteall.imm_ptl.core.IPGlobal.sodiumArenaLayerBatchesLeftStaleCount,
            qouteall.imm_ptl.core.IPGlobal.sodiumOtherScopeLayerBatchesSparedCount,
            qouteall.imm_ptl.core.IPGlobal.sodiumOtherScopeLayerBatchesWipedCount);
        seamlessportals$prepares = 0L;
        seamlessportals$draws = 0L;
        seamlessportals$camListed[0] = 0L;
        seamlessportals$camListed[1] = 0L;
        seamlessportals$camHoles[0] = 0L;
        seamlessportals$camHoles[1] = 0L;
        for (int i = 0; i < seamlessportals$PASSES; i++) {
            seamlessportals$foreignDraws[i] = 0L;
            seamlessportals$wronglySkipped[i] = 0L;
            seamlessportals$wronglyForced[i] = 0L;
            seamlessportals$clearedBatches[i] = 0L;
            seamlessportals$listedBatches[i] = 0L;
        }
    }
}
