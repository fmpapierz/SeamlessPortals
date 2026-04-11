package com.warwa.seamlessportals.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.ServiceLoader;

public interface PlatformHelper {

    void sendToClient(ServerPlayer player, CustomPacketPayload payload);

    void sendToServer(CustomPacketPayload payload);

    void registerPayloads();

    static PlatformHelper getInstance() {
        return Holder.INSTANCE;
    }

    class Holder {
        private static final PlatformHelper INSTANCE = ServiceLoader.load(PlatformHelper.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No PlatformHelper implementation found"));
    }
}
