// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. ImmPtlNetworkConfig.initClient uses registerGlobalReceiver(TYPE,
// S2CConfigStartPacket::handle) and S2CConfigStartPacket.handle uses Context.responseSender()
// .sendPacket(payload). :common compile classpath only. Removed at S20.
package net.fabricmc.fabric.api.client.networking.v1;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class ClientConfigurationNetworking {
    public static <T extends CustomPacketPayload> void registerGlobalReceiver(
        CustomPacketPayload.Type<T> type, ConfigurationPacketHandler<T> handler) {
    }

    @FunctionalInterface
    public interface ConfigurationPacketHandler<T extends CustomPacketPayload> {
        void receive(T payload, Context context);
    }

    public interface Context {
        PacketSender responseSender();
    }

    public interface PacketSender {
        void sendPacket(CustomPacketPayload payload);
    }

    private ClientConfigurationNetworking() {
    }
}
