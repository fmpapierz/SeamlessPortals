// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. O_O.postClientChunkLoadEvent/UnloadEvent call
// CHUNK_LOAD.invoker().onChunkLoad(ClientLevel, LevelChunk) and the CHUNK_UNLOAD counterpart.
// :common compile classpath only. Removed at S20.
package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ClientChunkEvents {
    public static final Event<Load> CHUNK_LOAD = null;
    public static final Event<Unload> CHUNK_UNLOAD = null;

    @FunctionalInterface
    public interface Load {
        void onChunkLoad(ClientLevel world, LevelChunk chunk);
    }

    @FunctionalInterface
    public interface Unload {
        void onChunkUnload(ClientLevel world, LevelChunk chunk);
    }

    private ClientChunkEvents() {
    }
}
