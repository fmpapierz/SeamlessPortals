package com.warwa.seamlessportals.chunk;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Objects;

/**
 * E2 — Dimension-tagged chunk position. Direct port of IP 1.19's
 * {@code DimensionalChunkPos}.
 *
 * <p>Used as the "chunk identity across dimensions" key in the watch
 * graph signals (begin/end watch). The watcher graph maps
 * {@code (player) → (dim → (chunkPos → record))}, so the dim part
 * lives one level up; this class only travels with signal events.
 */
public record DimChunkPos(ResourceKey<Level> dimension, int x, int z) {

    public DimChunkPos(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        this(dimension, chunkPos.x(), chunkPos.z());
    }

    public ChunkPos getChunkPos() {
        return new ChunkPos(x, z);
    }

    public long packedPos() {
        return ChunkPos.pack(x, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DimChunkPos that)) return false;
        return x == that.x && z == that.z && dimension.equals(that.dimension);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimension, x, z);
    }

    @Override
    public String toString() {
        return "DimChunkPos{" + dimension.identifier() + "," + x + "," + z + '}';
    }
}
