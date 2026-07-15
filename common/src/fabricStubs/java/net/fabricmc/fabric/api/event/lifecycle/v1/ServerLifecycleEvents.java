// S10-A loader-seam compileOnly stub (entity-portal migration; port-notes S10A-loader-seam.md,
// S12B-behavior.md §3, S13A-u11.md). NOT IP source, NOT shipped. Added at S12-B: the held tree's
// IPModInfoChecking.initDedicatedServer uses SERVER_STARTED.register(server -> ...) — a RUNTIME
// event registration that wires the REAL fabric-api event at S13 (this stub is compileOnly).
// S13 grows END_DATA_PACK_RELOAD: CustomPortalGenManager.init (:53) reloads datapack portal-gen on
// (server, resourceManager, success). Only the members the held tree consumes are shelled — the
// same "shell as far as the held tree consumes THIS type" discipline as the sibling
// ServerTickEvents stub; signatures are verbatim from the real fabric-lifecycle-events-v1
// ServerLifecycleEvents. :common compile classpath only. Removed at S20.
package net.fabricmc.fabric.api.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.CloseableResourceManager;

public final class ServerLifecycleEvents {
    public static final Event<ServerStarted> SERVER_STARTED = null;

    public static final Event<EndDataPackReload> END_DATA_PACK_RELOAD = null;

    @FunctionalInterface
    public interface ServerStarted {
        void onServerStarted(MinecraftServer server);
    }

    @FunctionalInterface
    public interface EndDataPackReload {
        void endDataPackReload(MinecraftServer server, CloseableResourceManager resourceManager, boolean success);
    }

    private ServerLifecycleEvents() {
    }
}
