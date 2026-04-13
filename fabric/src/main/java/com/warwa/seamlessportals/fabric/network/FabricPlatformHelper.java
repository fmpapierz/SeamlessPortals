package com.warwa.seamlessportals.fabric.network;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.client.PortalWorldManager;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.portal.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
                    // Server synced REAL portal link positions.
                    // Replace any expected/wrong links with exact positions.
                    handlePortalSync(payload);
                });
            }
        );
    }

    /**
     * Handle server→client portal link sync.
     * Server sends REAL positions after PortalForcer creates the destination.
     * Client creates/replaces links with exact positions.
     * Also pre-creates secondary level and sets view center at real dest position.
     */
    private static void handlePortalSync(ModPayloads.PortalSyncPayload payload) {
        ResourceKey<Level> srcDim = parseDimensionKey(payload.srcDimId());
        ResourceKey<Level> destDim = parseDimensionKey(payload.destDimId());
        if (srcDim == null || destDim == null) return;

        Direction.Axis axis = Direction.Axis.valueOf(payload.axis().toUpperCase());
        int width = payload.width();
        int height = payload.height();

        PortalManager clientManager = PortalManager.getClientInstance();

        // Register REAL portals on client (replaces any expected ones at same origin)
        PortalInfo srcPortal = new PortalInfo(PortalType.NETHER, srcDim, payload.srcOrigin(), axis, width, height);
        PortalInfo destPortal = new PortalInfo(PortalType.NETHER, destDim, payload.destOrigin(), axis, width, height);
        clientManager.registerPortal(srcPortal);
        clientManager.registerPortal(destPortal);
        clientManager.createLink(srcPortal, destPortal);

        // Pre-create secondary levels and set view centers at REAL positions (authoritative).
        // This overrides any first-chunk fallback that may have set a wrong center.
        PortalWorldManager.getOrCreateRenderer(destDim);
        com.warwa.seamlessportals.client.PortalDimensionManager.setViewCenterFromSync(
            destDim, payload.destOrigin().getX() >> 4, payload.destOrigin().getZ() >> 4);

        PortalWorldManager.getOrCreateRenderer(srcDim);
        com.warwa.seamlessportals.client.PortalDimensionManager.setViewCenterFromSync(
            srcDim, payload.srcOrigin().getX() >> 4, payload.srcOrigin().getZ() >> 4);

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] Client received REAL link sync: {} in {} <-> {} in {}",
            payload.srcOrigin(), payload.srcDimId(),
            payload.destOrigin(), payload.destDimId()
        );
    }

    private static ResourceKey<Level> parseDimensionKey(String dimId) {
        return switch (dimId) {
            case "minecraft:overworld" -> Level.OVERWORLD;
            case "minecraft:the_nether" -> Level.NETHER;
            case "minecraft:the_end" -> Level.END;
            default -> null;
        };
    }
}
