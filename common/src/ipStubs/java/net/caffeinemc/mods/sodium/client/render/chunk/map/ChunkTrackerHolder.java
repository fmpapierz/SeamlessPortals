// F21 compileOnly stub (entity-portal migration; S00-U0-decisions F21).
// Empty third-party shell — NOT IP source, NOT shipped. S04-compat.md §3 SODIUM S4:
// SodiumInterface calls the static ChunkTrackerHolder.get(world) (world is a ClientLevel,
// a subtype of Level) and chains onChunkStatus* on the returned ChunkTracker.
package net.caffeinemc.mods.sodium.client.render.chunk.map;

import net.minecraft.world.level.Level;

public class ChunkTrackerHolder {
    public static ChunkTracker get(Level world) {
        return null;
    }
}
