package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Accessor for {@link SectionOcclusionGraph}'s private {@code fullUpdateTask}.
 *
 * <p>Used by {@link LevelRendererCullTerrainMixin} to synchronously block on
 * the async graph-build task after the first post-promote
 * {@code SectionOcclusionGraph.update} call. Blocking there means
 * {@code currentGraph} is populated before vanilla's
 * {@code consumeFrustumUpdate}/{@code applyFrustum} path runs, so the first
 * rendered frame in the promoted dim has a non-empty
 * {@code visibleSections} list instead of flashing blank terrain.
 */
@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphAccessorMixin {

    @Accessor("fullUpdateTask")
    @Nullable Future<?> seamlessportals$getFullUpdateTask();

    /**
     * The {@code needsFrustumUpdate} flag. Setting it {@code true} makes the next
     * {@code LevelExtractor.extract → applyFrustum} repopulate {@code visibleSections}
     * from the EXISTING (warm) {@code currentGraph} — without a full async rebuild.
     * Used on portal promote to repaint a demote-preserved warm graph INSTANTLY
     * (no blank-second), while {@code invalidate()}'s rebuild refines in the
     * background. {@code consumeFrustumUpdate()} ({@code compareAndSet(true,false)})
     * is the consumer (LevelExtractor.java:128).
     */
    @Accessor("needsFrustumUpdate")
    AtomicBoolean seamlessportals$getNeedsFrustumUpdate();

    /**
     * S14.42 (render-chain probe): the graph's chunk-presence gate ({@code loadedChunks},
     * SOG:60). The far-walk terrain wipe = this set poisoned UNDER-inclusive (a chunk absent
     * here parks the occlusion BFS with no propagation, SOG:271-272) — the probe logs its size
     * against the chunk source's live loaded count; {@code sogLoaded << ldChunks} is the
     * poison signature.
     */
    @Accessor("loadedChunks")
    it.unimi.dsi.fastutil.longs.LongOpenHashSet seamlessportals$getLoadedChunks();

    /**
     * S14.48 BLOCKER fold: the graph's last BFS-origin camera cell (floor(pos/8), doubles).
     * The warm-gate keeps vanilla's applyFrustum fill only when the tree was built from near
     * the current camera. NOTE: the never-updated sentinel is Double.MIN_VALUE = the smallest
     * POSITIVE double (~0.0) — NOT far; near the origin it can read as "near". Safe today only
     * because a never-updated graph has an empty octree (yield ~0 fails the gate's yield check
     * first) — see the caution at the gate site before relying on these as a cold test.
     */
    @Accessor("prevCamX")
    double seamlessportals$getPrevCamX();

    @Accessor("prevCamY")
    double seamlessportals$getPrevCamY();

    @Accessor("prevCamZ")
    double seamlessportals$getPrevCamZ();

    /** S14.42 verify-fold: the graph's viewArea — NULL until the renderer's FIRST extract runs
     *  the deferred build + waitAndReset(viewArea). updateEmptySections dereferences it
     *  unguarded (SOG:396-399), so the delta pump must not apply to a SOG whose viewArea is
     *  still null (verify BLOCKER: NPE on never-viewed dest dims, wf_723f7b39-bf4). */
    @Accessor("viewArea")
    net.minecraft.client.renderer.@Nullable ViewArea seamlessportals$getViewArea();
}
