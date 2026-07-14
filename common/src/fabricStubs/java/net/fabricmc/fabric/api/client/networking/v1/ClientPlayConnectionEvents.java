// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. ImmPtlNetworkConfig.initClient registers JOIN.register((handler,
// sender, client) -> ...); all three callback params are unused by the held lambda. The middle
// `sender` param (real fabric-api: PacketSender) is typed Object here since the held lambda ignores
// it. :common compile classpath only. Removed at S20.
package net.fabricmc.fabric.api.client.networking.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

public final class ClientPlayConnectionEvents {
    public static final Event<Join> JOIN = null;

    @FunctionalInterface
    public interface Join {
        void onPlayReady(ClientPacketListener handler, Object sender, Minecraft client);
    }

    private ClientPlayConnectionEvents() {
    }
}
