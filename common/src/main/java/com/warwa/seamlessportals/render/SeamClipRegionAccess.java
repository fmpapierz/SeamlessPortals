package com.warwa.seamlessportals.render;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jetbrains.annotations.Nullable;

/**
 * Duck interface implemented onto {@code net.minecraft.client.renderer.chunk.RenderSectionRegion}
 * by {@code RenderSectionRegionSeamClipMixin}: the SEAM CLIP's per-compile exclusion snapshot.
 *
 * <p>Stashed ONCE, on the main thread, by {@code RenderRegionCacheSeamClipMixin} at
 * {@code RenderRegionCache.createRegion} RETURN — compile WORKER threads then read it immutably
 * (the live seam index is main-thread-mutated and must never be consulted off-thread; the region
 * snapshot is the happens-before hand-off, same as the region's own SectionCopy data). Null on
 * regions with no qualifying cells — the overwhelmingly common case, one field read.
 *
 * <p>See {@code SeamClipRenderer} / {@code migration/SEAM_CLIP_DESIGN.md} §2.
 */
public interface SeamClipRegionAccess {

    @Nullable
    LongOpenHashSet seamlessportals$seamClipExclusions();

    void seamlessportals$setSeamClipExclusions(@Nullable LongOpenHashSet cells);
}
