package com.warwa.seamlessportals.network;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public class ModPayloads {

    public static void registerCommon() {
        SeamlessPortalsConstants.LOGGER.info("Registering Seamless Portals network payloads");
    }

    public record PortalSyncPayload(
        String portalId,
        BlockPos origin,
        String portalType,
        String axis,
        int width,
        int height,
        String linkedPortalId
    ) implements CustomPacketPayload {
        public static final Type<PortalSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "portal_sync")
        );

        public static final StreamCodec<FriendlyByteBuf, PortalSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PortalSyncPayload::portalId,
            BlockPos.STREAM_CODEC, PortalSyncPayload::origin,
            ByteBufCodecs.STRING_UTF8, PortalSyncPayload::portalType,
            ByteBufCodecs.STRING_UTF8, PortalSyncPayload::axis,
            ByteBufCodecs.VAR_INT, PortalSyncPayload::width,
            ByteBufCodecs.VAR_INT, PortalSyncPayload::height,
            ByteBufCodecs.STRING_UTF8, PortalSyncPayload::linkedPortalId,
            PortalSyncPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RemoteChunkDataPayload(
        String dimensionId,
        int chunkX,
        int chunkZ,
        byte[] chunkData
    ) implements CustomPacketPayload {
        public static final Type<RemoteChunkDataPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "remote_chunk")
        );

        public static final StreamCodec<FriendlyByteBuf, RemoteChunkDataPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RemoteChunkDataPayload::dimensionId,
            ByteBufCodecs.VAR_INT, RemoteChunkDataPayload::chunkX,
            ByteBufCodecs.VAR_INT, RemoteChunkDataPayload::chunkZ,
            ByteBufCodecs.BYTE_ARRAY, RemoteChunkDataPayload::chunkData,
            RemoteChunkDataPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RemoteChunkUnloadPayload(
        String dimensionId,
        int chunkX,
        int chunkZ
    ) implements CustomPacketPayload {
        public static final Type<RemoteChunkUnloadPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "remote_chunk_unload")
        );

        public static final StreamCodec<FriendlyByteBuf, RemoteChunkUnloadPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RemoteChunkUnloadPayload::dimensionId,
            ByteBufCodecs.VAR_INT, RemoteChunkUnloadPayload::chunkX,
            ByteBufCodecs.VAR_INT, RemoteChunkUnloadPayload::chunkZ,
            RemoteChunkUnloadPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record PortalTeleportPayload(
        String portalId,
        double x, double y, double z
    ) implements CustomPacketPayload {
        public static final Type<PortalTeleportPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "portal_teleport")
        );

        public static final StreamCodec<FriendlyByteBuf, PortalTeleportPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PortalTeleportPayload::portalId,
            ByteBufCodecs.DOUBLE, PortalTeleportPayload::x,
            ByteBufCodecs.DOUBLE, PortalTeleportPayload::y,
            ByteBufCodecs.DOUBLE, PortalTeleportPayload::z,
            PortalTeleportPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
