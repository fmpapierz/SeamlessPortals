// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. ImmPtlNetworkConfig uses canSend(handler, TYPE),
// registerGlobalReceiver(TYPE, C2SConfigCompletePacket::handle) and Context.packetListener().
// NOTE: the held code calls handler.completeTask(...) on the VANILLA-typed listener returned by
// packetListener(); completeTask/addTask are Fabric INTERFACE-INJECTION methods that a compileOnly
// stub cannot add to the vanilla ServerConfigurationPacketListenerImpl — they remain irreducible
// residue on the :common probe and resolve at the S13 fabric-loader compile against real fabric-api
// (port-note §4). :common compile classpath only. Removed at S20.
package net.fabricmc.fabric.api.networking.v1;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

public final class ServerConfigurationNetworking {
    public static boolean canSend(
        ServerConfigurationPacketListenerImpl handler, CustomPacketPayload.Type<?> type) {
        return false;
    }

    public static <T extends CustomPacketPayload> void registerGlobalReceiver(
        CustomPacketPayload.Type<T> type, ConfigurationPacketHandler<T> handler) {
    }

    @FunctionalInterface
    public interface ConfigurationPacketHandler<T extends CustomPacketPayload> {
        void receive(T payload, Context context);
    }

    public interface Context {
        ServerConfigurationPacketListenerImpl packetListener();
    }

    private ServerConfigurationNetworking() {
    }
}
