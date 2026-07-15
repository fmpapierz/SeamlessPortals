// S10-A loader-seam compileOnly stub (entity-portal migration; port-notes S10A-loader-seam.md,
// S12B-behavior.md §3). NOT IP source, NOT shipped. Added at S12-B: the held tree's
// IPModInfoChecking.initDedicatedServer uses SERVER_STARTED.register(server -> ...) — a RUNTIME
// event registration that wires the REAL fabric-api event at S13 (this stub is compileOnly).
// Only SERVER_STARTED is consumed by the held tree (grep-verified), so only it is shelled — the
// same "shell as far as the held tree consumes THIS type" discipline as the sibling
// ServerTickEvents stub. :common compile classpath only. Removed at S20.
package net.fabricmc.fabric.api.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.MinecraftServer;

public final class ServerLifecycleEvents {
    public static final Event<ServerStarted> SERVER_STARTED = null;

    @FunctionalInterface
    public interface ServerStarted {
        void onServerStarted(MinecraftServer server);
    }

    private ServerLifecycleEvents() {
    }
}
