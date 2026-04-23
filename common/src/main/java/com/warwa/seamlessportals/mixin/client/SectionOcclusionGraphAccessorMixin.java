package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Future;

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
}
