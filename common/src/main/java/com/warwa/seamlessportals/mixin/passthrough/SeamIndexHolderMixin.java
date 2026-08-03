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
public abstract class SeamIndexHolderMixin
    implements SeamIndexHolder, com.warwa.seamlessportals.passthrough.SeamOccupancy.SeamOccupancyHolder {

    @Unique
    private final LongOpenHashSet seamlessportals$sectionsWithSeams = new LongOpenHashSet();

    /**
     * ★ OWNER-HALF OCCUPANCY — which half of a seam cell each object owns
     * ({@code FRACTIONAL_DESIGN.md} §2a.0). Keyed by (cell, half) via a two-bit mask, because a cell
     * may hold TWO independent objects — one per half — and a bare per-cell set cannot say that.
     *
     * <p>Unlike the other three indices this is NOT derivable from portal geometry: it comes from a
     * placement, so it needs a packet and a {@code SavedData} to survive relog and reload. Neither
     * exists yet; until they do this is best-effort per-session state on whichever side saw the
     * placement, and it dies with the Level like the rest.
     */
    @Unique
    private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap seamlessportals$seamOccupancy =
        new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();

    @Override
    public it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap seamlessportals$seamOccupancy() {
        return seamlessportals$seamOccupancy;
    }

    /**
     * ★ SECONDARY OCCUPANTS — the second object in a shared seam cell (state + half), which vanilla's
     * one-blockstate-per-cell storage cannot hold. Same lifecycle as the rest: dies with the Level.
     */
    @Unique
    private final java.util.Map<Long, com.warwa.seamlessportals.passthrough.SeamOccupancy.Secondary>
        seamlessportals$seamSecondary = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public java.util.Map<Long, com.warwa.seamlessportals.passthrough.SeamOccupancy.Secondary>
        seamlessportals$seamSecondary() {
        return seamlessportals$seamSecondary;
    }

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
