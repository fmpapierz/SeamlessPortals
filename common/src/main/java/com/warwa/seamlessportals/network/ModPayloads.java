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

    /**
     * Server → Client: Full portal link data with REAL positions.
     * Following IP's approach: the server is the ONLY authority on portal positions
     * (via PortalForcer). The client NEVER computes destination positions.
     */
    public record PortalLinkPayload(
        String srcDimension,
        BlockPos srcOrigin,
        String srcAxis,
        int srcWidth,
        int srcHeight,
        String destDimension,
        BlockPos destOrigin,
        String destAxis,
        int destWidth,
        int destHeight
    ) implements CustomPacketPayload {
        public static final Type<PortalLinkPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "portal_link")
        );

        public static final StreamCodec<FriendlyByteBuf, PortalLinkPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PortalLinkPayload::srcDimension,
            BlockPos.STREAM_CODEC, PortalLinkPayload::srcOrigin,
            ByteBufCodecs.STRING_UTF8, PortalLinkPayload::srcAxis,
            ByteBufCodecs.VAR_INT, PortalLinkPayload::srcWidth,
            ByteBufCodecs.VAR_INT, PortalLinkPayload::srcHeight,
            ByteBufCodecs.STRING_UTF8, PortalLinkPayload::destDimension,
            BlockPos.STREAM_CODEC, PortalLinkPayload::destOrigin,
            ByteBufCodecs.STRING_UTF8, PortalLinkPayload::destAxis,
            ByteBufCodecs.VAR_INT, PortalLinkPayload::destWidth,
            ByteBufCodecs.VAR_INT, PortalLinkPayload::destHeight,
            PortalLinkPayload::new
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

    /**
     * Client → Server: Request portal data for a specific dimension.
     * Sent after dimension change to ensure client has all portal links.
     */
    public record RequestPortalDataPayload(
        String dimensionId
    ) implements CustomPacketPayload {
        public static final Type<RequestPortalDataPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "request_portal_data")
        );

        public static final StreamCodec<FriendlyByteBuf, RequestPortalDataPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RequestPortalDataPayload::dimensionId,
            RequestPortalDataPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Client → Server: "I crossed portal X client-side, please perform the server
     * teleport." IP-style client-initiated seamless teleport.
     *
     * The server validates that the player is actually near the source portal,
     * that the portal link exists, that the cooldown has expired, and then
     * performs its own cross-dim move via {@code SeamlessServerTeleport} —
     * which notably does NOT send {@code ClientboundRespawnPacket}. Instead
     * it replies with {@link ClientboundSeamlessMovePayload} so the client
     * can reconcile (or do a deferred visual swap if it never detected the
     * crossing).
     */
    public record ClientPortalCrossingPayload(
        String portalId
    ) implements CustomPacketPayload {
        public static final Type<ClientPortalCrossingPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "client_portal_crossing")
        );

        public static final StreamCodec<FriendlyByteBuf, ClientPortalCrossingPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ClientPortalCrossingPayload::portalId,
            ClientPortalCrossingPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server → Client: reconciliation after a seamless teleport completed
     * on the server side. Replaces {@code ClientboundRespawnPacket} for
     * portal crossings so the client never enters {@code handleRespawn}.
     *
     * Carries authoritative dest position/rotation/velocity + the portal link
     * id so the client can either:
     *   1. (hot path) confirm its own client-first swap matches server truth, OR
     *   2. (fallback) perform the visual swap now if it missed the crossing
     *      locally (e.g. portal link not yet synced when the eye crossed).
     */
    public record ClientboundSeamlessMovePayload(
        String portalId,
        String destDimension,
        double x, double y, double z,
        float yaw, float pitch,
        double vx, double vy, double vz
    ) implements CustomPacketPayload {
        public static final Type<ClientboundSeamlessMovePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "seamless_move")
        );

        public static final StreamCodec<FriendlyByteBuf, ClientboundSeamlessMovePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ClientboundSeamlessMovePayload::portalId,
            ByteBufCodecs.STRING_UTF8, ClientboundSeamlessMovePayload::destDimension,
            ByteBufCodecs.DOUBLE, ClientboundSeamlessMovePayload::x,
            ByteBufCodecs.DOUBLE, ClientboundSeamlessMovePayload::y,
            ByteBufCodecs.DOUBLE, ClientboundSeamlessMovePayload::z,
            ByteBufCodecs.FLOAT, ClientboundSeamlessMovePayload::yaw,
            ByteBufCodecs.FLOAT, ClientboundSeamlessMovePayload::pitch,
            ByteBufCodecs.DOUBLE, ClientboundSeamlessMovePayload::vx,
            ByteBufCodecs.DOUBLE, ClientboundSeamlessMovePayload::vy,
            ByteBufCodecs.DOUBLE, ClientboundSeamlessMovePayload::vz,
            ClientboundSeamlessMovePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
