// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. ImmPtlNetworkConfig.init registers CONFIGURE.register((handler,
// server) -> ...). The handler is the vanilla ServerConfigurationPacketListenerImpl (matching real
// fabric-api's callback), so handler.addTask(...) inside the lambda is Fabric interface-injection
// residue that resolves at S13 (port-note §4). :common compile classpath only. Removed at S20.
package net.fabricmc.fabric.api.networking.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

public final class ServerConfigurationConnectionEvents {
    public static final Event<Configure> CONFIGURE = null;

    @FunctionalInterface
    public interface Configure {
        void onSendConfiguration(ServerConfigurationPacketListenerImpl handler, MinecraftServer server);
    }

    private ServerConfigurationConnectionEvents() {
    }
}
