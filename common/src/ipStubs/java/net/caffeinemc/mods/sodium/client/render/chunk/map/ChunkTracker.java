// F21 compileOnly stub (entity-portal migration; S00-U0-decisions F21).
// Empty third-party shell — NOT IP source, NOT shipped. S04-compat.md §3 SODIUM S5
// (inferred: the return of ChunkTrackerHolder.get(...)). SodiumInterface calls the two
// onChunkStatus* methods chained on ChunkTrackerHolder.get(world).
package net.caffeinemc.mods.sodium.client.render.chunk.map;

public class ChunkTracker {
    public void onChunkStatusAdded(int x, int z, int flags) {
    }

    public void onChunkStatusRemoved(int x, int z, int flags) {
    }
}
