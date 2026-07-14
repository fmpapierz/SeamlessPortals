// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. ImmPtlNetworkConfig.initClient registers INIT.register((handler,
// client) -> ...); both callback params are unused by the held lambda. :common compile classpath
// only. Removed at S20.
package net.fabricmc.fabric.api.client.networking.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;

public final class ClientLoginConnectionEvents {
    public static final Event<Init> INIT = null;

    @FunctionalInterface
    public interface Init {
        void onLoginStart(ClientHandshakePacketListenerImpl handler, Minecraft client);
    }

    private ClientLoginConnectionEvents() {
    }
}
