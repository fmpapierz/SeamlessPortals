// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. Held tree uses END_SERVER_TICK.register(server -> ...) in
// CollisionHelper, ImmPtlChunkTracking, ServerTaskList, ServerPerformanceMonitor, WorldInfoSender,
// ServerTeleportationManager, GlobalPortalStorage. These are RUNTIME event registrations that wire
// the REAL fabric-api events at S13 (the stub is compileOnly). :common compile classpath only.
// Removed at S20.
package net.fabricmc.fabric.api.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.MinecraftServer;

public final class ServerTickEvents {
    public static final Event<StartTick> START_SERVER_TICK = null;
    public static final Event<EndTick> END_SERVER_TICK = null;

    @FunctionalInterface
    public interface StartTick {
        void onStartTick(MinecraftServer server);
    }

    @FunctionalInterface
    public interface EndTick {
        void onEndTick(MinecraftServer server);
    }

    private ServerTickEvents() {
    }
}
