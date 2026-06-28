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
}
