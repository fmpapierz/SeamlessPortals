package com.warwa.seamlessportals.fabric.network;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public class FabricPlatformHelper implements PlatformHelper {

    @Override
    public void sendToClient(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    @Override
    public void registerPayloads() {
        // Register server -> client payloads
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.PortalSyncPayload.TYPE,
            ModPayloads.PortalSyncPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.RemoteChunkDataPayload.TYPE,
            ModPayloads.RemoteChunkDataPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.RemoteChunkUnloadPayload.TYPE,
            ModPayloads.RemoteChunkUnloadPayload.STREAM_CODEC
        );

        // Register client -> server payloads
        PayloadTypeRegistry.serverboundPlay().register(
            ModPayloads.PortalTeleportPayload.TYPE,
            ModPayloads.PortalTeleportPayload.STREAM_CODEC
        );

        SeamlessPortalsConstants.LOGGER.info("Fabric network payloads registered");
    }

    public static void registerClientHandlers() {
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.RemoteChunkDataPayload.TYPE,
            (payload, context) -> {
                context.client().execute(() -> {
                    RemoteChunkManager.handleChunkData(
                        payload.dimensionId(), payload.chunkX(), payload.chunkZ(), payload.chunkData()
                    );
                });
            }
        );

        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.RemoteChunkUnloadPayload.TYPE,
            (payload, context) -> {
                context.client().execute(() -> {
                    RemoteChunkManager.handleChunkUnload(
                        payload.dimensionId(), payload.chunkX(), payload.chunkZ()
                    );
                });
            }
        );

        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.PortalSyncPayload.TYPE,
            (payload, context) -> {
                context.client().execute(() -> {
                    SeamlessPortalsConstants.LOGGER.debug("Received portal sync: {}", payload.portalId());
                });
            }
        );
    }
}
