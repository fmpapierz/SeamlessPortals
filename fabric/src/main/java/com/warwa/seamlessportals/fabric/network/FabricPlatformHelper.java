package com.warwa.seamlessportals.fabric.network;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

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
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.PortalLinkPayload.TYPE,
            ModPayloads.PortalLinkPayload.STREAM_CODEC
        );

        // Register client -> server payloads
        PayloadTypeRegistry.serverboundPlay().register(
            ModPayloads.PortalTeleportPayload.TYPE,
            ModPayloads.PortalTeleportPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.serverboundPlay().register(
            ModPayloads.RequestPortalDataPayload.TYPE,
            ModPayloads.RequestPortalDataPayload.STREAM_CODEC
        );

        SeamlessPortalsConstants.LOGGER.info("Fabric network payloads registered");
    }

    public static void registerServerHandlers() {
        // Handle client request for portal data after dimension change
        ServerPlayNetworking.registerGlobalReceiver(
            ModPayloads.RequestPortalDataPayload.TYPE,
            (payload, context) -> {
                ServerPlayer player = context.player();
                context.server().execute(() -> {
                    com.warwa.seamlessportals.portal.PortalManager manager =
                        com.warwa.seamlessportals.portal.PortalManager.getServerInstance();

                    // Parse dimension ID (format: "minecraft:overworld")
                    ResourceKey<Level> dimension;
                    String dimId = payload.dimensionId();
                    if (dimId.equals("minecraft:overworld")) {
                        dimension = Level.OVERWORLD;
                    } else if (dimId.equals("minecraft:the_nether")) {
                        dimension = Level.NETHER;
                    } else if (dimId.equals("minecraft:the_end")) {
                        dimension = Level.END;
                    } else {
                        // For unknown dimensions, we can't create the ResourceKey without ResourceLocation
                        // Just skip the request for now
                        SeamlessPortalsConstants.LOGGER.warn(
                            "[SEAMLESS] Unknown dimension requested: {}", dimId);
                        return;
                    }

                    // Send all portal links for this dimension to the requesting player
                    manager.sendDimensionLinksToPlayer(dimension, player);
                });
            }
        );
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

        // Handle server-authoritative portal link data.
        // Following IP's architecture: only the SERVER knows the actual destination
        // position (via PortalForcer). Client NEVER computes destinations.
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.PortalLinkPayload.TYPE,
            (payload, context) -> {
                context.client().execute(() -> {
                    com.warwa.seamlessportals.portal.PortalDetector.handlePortalLinkFromServer(payload);
                });
            }
        );
    }
}
