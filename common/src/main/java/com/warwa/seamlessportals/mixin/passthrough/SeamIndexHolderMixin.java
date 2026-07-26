package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.SeamIndexHolder;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Hangs the per-level seam index off {@code Level} — see {@link SeamIndexHolder} for why the index
 * lives here rather than in a static map (the hot-path folding argument).
 *
 * <p>Storage only: no injections, no behaviour. The fields are allocated lazily-empty per level and
 * die with it, so no cleanup event is needed for them and a stale index cannot outlive a world.
 */
@Mixin(Level.class)
public abstract class SeamIndexHolderMixin implements SeamIndexHolder {

    @Unique
    private final LongOpenHashSet seamlessportals$sectionsWithSeams = new LongOpenHashSet();

    @Unique
    private final Long2ObjectOpenHashMap<SeamRegistry.SeamCell> seamlessportals$seamCells =
        new Long2ObjectOpenHashMap<>();

    @Unique
    private final LongOpenHashSet seamlessportals$mirrorCreatedCells = new LongOpenHashSet();

    @Override
    public LongOpenHashSet seamlessportals$sectionsWithSeams() {
        return seamlessportals$sectionsWithSeams;
    }

    @Override
    public Long2ObjectOpenHashMap<SeamRegistry.SeamCell> seamlessportals$seamCells() {
        return seamlessportals$seamCells;
    }

    @Override
    public LongOpenHashSet seamlessportals$mirrorCreatedCells() {
        return seamlessportals$mirrorCreatedCells;
    }
}
