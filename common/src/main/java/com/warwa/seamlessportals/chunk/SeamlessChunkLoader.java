package com.warwa.seamlessportals.chunk;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;

/**
 * E2 — A chunk-loader as defined by the watch-graph: a square area
 * around a center chunk in a particular dimension, with a flag
 * marking whether it represents the player's own-dim direct view
 * (vanilla owns delivery) or an indirect cross-dim view (we own
 * delivery via redirected packets).
 *
 * <p>Direct port of IP 1.19's {@code ChunkLoader}, minus the
 * {@code loadChunksAndDo} async helper (we don't need it for the MVP)
 * and {@code createChunkRegion} (renderer-side, not server-side).
 *
 * <p>Equality uses {@code center + radius}; the {@code .distinct()} on
 * a Stream of loaders deduplicates so two portals pointing at the same
 * destination chunk-area don't double-add records to the graph.
 */
public final class SeamlessChunkLoader {

    public final DimChunkPos center;
    public final int radius;
    public final boolean isDirectLoader;

    public SeamlessChunkLoader(DimChunkPos center, int radius) {
        this(center, radius, false);
    }

    public SeamlessChunkLoader(DimChunkPos center, int radius, boolean isDirectLoader) {
        this.center = center;
        this.radius = radius;
        this.isDirectLoader = isDirectLoader;
    }

    public int getChunkCount() {
        return (radius * 2 + 1) * (radius * 2 + 1);
    }

    /**
     * Iterate every chunk pos in the loader's square area. Consumer
     * receives {@code (dim, x, z, distanceToSource)} where
     * {@code distanceToSource = max(|dx|, |dz|)} — chebyshev distance
     * in chunks from the center. The graph uses this distance to
     * priority-order pending chunk sends (closest first).
     */
    public void foreachChunkPos(ChunkPosConsumer func) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                func.consume(
                    center.dimension(),
                    center.x() + dx,
                    center.z() + dz,
                    Math.max(Math.abs(dx), Math.abs(dz))
                );
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SeamlessChunkLoader that)) return false;
        // isDirectLoader intentionally NOT in equals — IP merges
        // direct/indirect via "isDirectLoading |= chunkLoader.isDirectLoader"
        // in updatePlayerForChunkLoader so the same (center, radius) in
        // both flavors should collapse into a single record entry.
        return radius == that.radius && center.equals(that.center);
    }

    @Override
    public int hashCode() {
        return Objects.hash(center, radius);
    }

    @Override
    public String toString() {
        return "SeamlessChunkLoader{center=" + center + ",radius=" + radius
            + ",direct=" + isDirectLoader + '}';
    }

    @FunctionalInterface
    public interface ChunkPosConsumer {
        void consume(ResourceKey<Level> dimension, int x, int z, int distanceToSource);
    }
}
