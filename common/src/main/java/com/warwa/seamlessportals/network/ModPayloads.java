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

    /**
     * Server → Client: a REDIRECTED VANILLA chunk packet for a destination
     * dimension (Phase 4c, IP {@code PacketRedirectionClient} architecture).
     *
     * <p>Wraps the real {@link net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket}
     * (chunk + light in one) plus the target dimension id, so the client can run
     * the vanilla packet's OWN handler against the destination {@code ClientLevel}
     * under a world-switch. That drives the engine's native chunk-load tracking on
     * the dest renderer (the occlusion graph + {@code onChunkReadyToRender}),
     * replacing the custom snapshot feed + the O(all-sections) manual scan.
     *
     * <p>The codec is parameterized over {@link net.minecraft.network.RegistryFriendlyByteBuf}
     * (not plain {@code FriendlyByteBuf}) because the embedded vanilla packet's
     * {@code ClientboundLevelChunkPacketData} needs registry access for
     * block-entity serialization. The {@code String} dim field is widened into the
     * registry-aware composite via {@code .cast()} — the {@link RemoteEntityDataPayload}
     * pattern, which already embeds a vanilla packet this way.
     */
    public record RedirectedChunkPayload(
        String dimensionId,
        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket innerPacket
    ) implements CustomPacketPayload {
        public static final Type<RedirectedChunkPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "redirected_chunk")
        );

        public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, RedirectedChunkPayload> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.cast(),
                RedirectedChunkPayload::dimensionId,
                net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket.STREAM_CODEC,
                RedirectedChunkPayload::innerPacket,
                RedirectedChunkPayload::new
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
        String portalId,
        int swapSeq,
        /* Crossing-direction sign on the source portal's depth axis (±1), from the
         * client's detection segment. Keys the motion-continuous exit side on the
         * server (the server's own position/velocity lag the crossing by a
         * round-trip). The server sanitizes it and treats it as a hint. */
        double exitDepthSign
    ) implements CustomPacketPayload {
        public static final Type<ClientPortalCrossingPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "client_portal_crossing")
        );

        public static final StreamCodec<FriendlyByteBuf, ClientPortalCrossingPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ClientPortalCrossingPayload::portalId,
            ByteBufCodecs.VAR_INT, ClientPortalCrossingPayload::swapSeq,
            ByteBufCodecs.DOUBLE, ClientPortalCrossingPayload::exitDepthSign,
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
    /**
     * Server → Client: add a mirrored entity to the client's cached
     * ClientLevel for {@code dimensionId}, so it renders in the portal view.
     *
     * <p>Phase 2a.1 of the live-portal-view feature: entity presence. A
     * companion to {@link RemoteBlockUpdatePayload} — that covers blocks,
     * this covers mobs/items/projectiles/xp-orbs/everything that is an
     * {@link net.minecraft.world.entity.Entity}. Dispatched by
     * {@code PortalEntityTracker} on the server as entities enter the
     * portal-view radius around a player's nearby portal destination.
     *
     * <p>Same wire shape as {@code ClientboundAddEntityPacket}, kept as a
     * custom payload rather than wrapping the vanilla packet so the client
     * handler can target the cached {@code ClientLevel} directly without
     * swapping {@code ClientPacketListener.level} thread-locally.
     */
    /**
     * Server → Client: the portal at {@code origin} in {@code dimensionId}
     * has been destroyed (its nether_portal block turned to air, typically
     * because a surrounding obsidian block was broken). Client removes the
     * portal + link from its {@link com.warwa.seamlessportals.portal.PortalManager}
     * so the portal view stops rendering.
     */
    public record PortalUnregisterPayload(
        String dimensionId,
        BlockPos origin
    ) implements CustomPacketPayload {
        public static final Type<PortalUnregisterPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "portal_unregister")
        );

        public static final StreamCodec<FriendlyByteBuf, PortalUnregisterPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PortalUnregisterPayload::dimensionId,
            BlockPos.STREAM_CODEC, PortalUnregisterPayload::origin,
            PortalUnregisterPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RemoteEntityAddPayload(
        String dimensionId,
        int entityId,
        UUID uuid,
        int entityTypeId,
        double x, double y, double z,
        float yRot, float xRot, float yHeadRot,
        double vx, double vy, double vz,
        int data
    ) implements CustomPacketPayload {
        public static final Type<RemoteEntityAddPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "remote_entity_add")
        );

        // composite() tops out at 12 fields and we have 15; roll a manual
        // codec that writes/reads the fields in order.
        public static final StreamCodec<FriendlyByteBuf, RemoteEntityAddPayload> STREAM_CODEC =
            StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.dimensionId);
                    buf.writeVarInt(p.entityId);
                    buf.writeUUID(p.uuid);
                    buf.writeVarInt(p.entityTypeId);
                    buf.writeDouble(p.x);
                    buf.writeDouble(p.y);
                    buf.writeDouble(p.z);
                    buf.writeFloat(p.yRot);
                    buf.writeFloat(p.xRot);
                    buf.writeFloat(p.yHeadRot);
                    buf.writeDouble(p.vx);
                    buf.writeDouble(p.vy);
                    buf.writeDouble(p.vz);
                    buf.writeVarInt(p.data);
                },
                buf -> new RemoteEntityAddPayload(
                    buf.readUtf(),
                    buf.readVarInt(),
                    buf.readUUID(),
                    buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readVarInt()
                )
            );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server → Client: absolute-position tick update for a previously
     * added mirrored entity. Sent every server tick (20 Hz) per entity in
     * the portal-view radius, matching vanilla's tracking cadence.
     *
     * <p>Uses absolute position rather than delta-encoded
     * {@code ClientboundMoveEntityPacket.Pos} shorts — simpler, no prev-pos
     * state needed server-side, cost is ~a couple of bytes per packet on a
     * 60Hz network. Consider compressing in a later optimization pass if
     * bandwidth becomes a concern.
     */
    public record RemoteEntityMovePayload(
        String dimensionId,
        int entityId,
        double x, double y, double z,
        float yRot, float xRot, float yHeadRot,
        boolean onGround
    ) implements CustomPacketPayload {
        public static final Type<RemoteEntityMovePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "remote_entity_move")
        );

        public static final StreamCodec<FriendlyByteBuf, RemoteEntityMovePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RemoteEntityMovePayload::dimensionId,
            ByteBufCodecs.VAR_INT, RemoteEntityMovePayload::entityId,
            ByteBufCodecs.DOUBLE, RemoteEntityMovePayload::x,
            ByteBufCodecs.DOUBLE, RemoteEntityMovePayload::y,
            ByteBufCodecs.DOUBLE, RemoteEntityMovePayload::z,
            ByteBufCodecs.FLOAT, RemoteEntityMovePayload::yRot,
            ByteBufCodecs.FLOAT, RemoteEntityMovePayload::xRot,
            ByteBufCodecs.FLOAT, RemoteEntityMovePayload::yHeadRot,
            ByteBufCodecs.BOOL, RemoteEntityMovePayload::onGround,
            RemoteEntityMovePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server → Client: replicate a mirrored entity's
     * {@link net.minecraft.network.syncher.SynchedEntityData} so its
     * visual state (pose, glow, baby flag, sneaking, sitting, aiming,
     * custom name, etc.) matches the authoritative server entity.
     *
     * <p>Phase 2b of the live-portal-view feature. Wraps vanilla's
     * {@link net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket}
     * so we inherit its codec for free — that packet's serializer uses
     * {@link net.minecraft.network.RegistryFriendlyByteBuf} because some
     * data values (item stacks, particle options) need registry access.
     *
     * <p>Sent per entity right after
     * {@link RemoteEntityAddPayload} with the full non-default data set,
     * and again per tick only when the entity reports dirty data (via
     * {@code SynchedEntityData.packDirty()}) so bandwidth scales with
     * change rate, not mob count.
     */
    public record RemoteEntityDataPayload(
        String dimensionId,
        net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket innerPacket
    ) implements CustomPacketPayload {
        public static final Type<RemoteEntityDataPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "remote_entity_data")
        );

        public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, RemoteEntityDataPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.cast(),
            RemoteEntityDataPayload::dimensionId,
            net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket.STREAM_CODEC,
            RemoteEntityDataPayload::innerPacket,
            RemoteEntityDataPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server → Client: replicate a living mirrored entity's equipment
     * slots (main hand, off hand, helmet, chestplate, leggings, boots,
     * horse armor). Wraps vanilla
     * {@link net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket}
     * for its codec — slots carry full ItemStack which needs registry
     * access via {@link net.minecraft.network.RegistryFriendlyByteBuf}.
     */
    public record RemoteEntityEquipmentPayload(
        String dimensionId,
        net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket innerPacket
    ) implements CustomPacketPayload {
        public static final Type<RemoteEntityEquipmentPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "remote_entity_equipment")
        );

        public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, RemoteEntityEquipmentPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.cast(),
            RemoteEntityEquipmentPayload::dimensionId,
            net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket.STREAM_CODEC,
            RemoteEntityEquipmentPayload::innerPacket,
            RemoteEntityEquipmentPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server → Client: remove one or more mirrored entities from the
     * client's cached ClientLevel for {@code dimensionId}. Sent when an
     * entity leaves the portal-view radius, dies, or gets despawned.
     */
    public record RemoteEntityRemovePayload(
        String dimensionId,
        java.util.List<Integer> entityIds
    ) implements CustomPacketPayload {
        public static final Type<RemoteEntityRemovePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "remote_entity_remove")
        );

        public static final StreamCodec<FriendlyByteBuf, RemoteEntityRemovePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RemoteEntityRemovePayload::dimensionId,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), RemoteEntityRemovePayload::entityIds,
            RemoteEntityRemovePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server → Client: live block update for a chunk currently watched
     * through a portal.
     *
     * <p>Mirrors the effect of {@code ClientboundBlockUpdatePacket} but routed
     * to the client's cached {@code ClientLevel} for {@code dimensionId}
     * rather than {@code mc.level}. Enables the "live portal view" feature:
     * as blocks change in the destination dimension, the portal's rendered
     * view updates without the player having to cross through.
     *
     * <p>Dispatched from
     * {@code com.warwa.seamlessportals.mixin.ServerLevelBlockUpdateMixin}
     * (server-side hook on {@code ServerLevel.sendBlockUpdated}) to each
     * player whose nearby portal has a destination in this dim within
     * render-distance of {@code pos}.
     *
     * <p>Wire format:
     * <ul>
     *   <li>{@code dimensionId} — e.g. "minecraft:the_nether"</li>
     *   <li>{@code packedPos} — {@link net.minecraft.core.BlockPos#asLong()}</li>
     *   <li>{@code blockStateId} —
     *     {@link net.minecraft.world.level.block.Block#getId(net.minecraft.world.level.block.state.BlockState)}
     *     — compact integer id into the block-state registry
     *   </li>
     * </ul>
     */
    public record RemoteBlockUpdatePayload(
        String dimensionId,
        long packedPos,
        int blockStateId
    ) implements CustomPacketPayload {
        public static final Type<RemoteBlockUpdatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "remote_block_update")
        );

        public static final StreamCodec<FriendlyByteBuf, RemoteBlockUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RemoteBlockUpdatePayload::dimensionId,
            ByteBufCodecs.VAR_LONG, RemoteBlockUpdatePayload::packedPos,
            ByteBufCodecs.VAR_INT, RemoteBlockUpdatePayload::blockStateId,
            RemoteBlockUpdatePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Batched variant of {@link RemoteBlockUpdatePayload}: many block-state changes for
     * ONE destination dim, coalesced per server tick into a SINGLE packet (mirrors
     * vanilla's per-section {@code ClientboundSectionBlocksUpdatePacket}). {@code
     * positions[i]} pairs with {@code blockStateIds[i]}. The server buffers updates per
     * player per tick ({@code BlockUpdateMirrorBuffer}), deduped by position, and flushes
     * one batch at tick end — replacing the per-block packet + per-block section-rebuild
     * flood (flowing lava/fluid was ~1000 single-block packets/sec).
     */
    public record RemoteBlockUpdateBatchPayload(
        String dimensionId,
        java.util.List<Long> positions,
        java.util.List<Integer> blockStateIds
    ) implements CustomPacketPayload {
        public static final Type<RemoteBlockUpdateBatchPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "remote_block_update_batch")
        );

        public static final StreamCodec<FriendlyByteBuf, RemoteBlockUpdateBatchPayload> STREAM_CODEC =
            StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.dimensionId());
                    int n = Math.min(p.positions().size(), p.blockStateIds().size());
                    buf.writeVarInt(n);
                    for (int i = 0; i < n; i++) {
                        buf.writeVarLong(p.positions().get(i));
                        buf.writeVarInt(p.blockStateIds().get(i));
                    }
                },
                (buf) -> {
                    String dim = buf.readUtf();
                    int n = buf.readVarInt();
                    java.util.List<Long> pos = new java.util.ArrayList<>(n);
                    java.util.List<Integer> ids = new java.util.ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        pos.add(buf.readVarLong());
                        ids.add(buf.readVarInt());
                    }
                    return new RemoteBlockUpdateBatchPayload(dim, pos, ids);
                }
            );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server → Client: hold {@code dimensionId}'s region around {@code (x,y,z)} scope-live for
     * SPECULATIVE PRE-WARMING (an unlit valid frame near the player) — the client keeps the
     * cached level resident, ticks it, and pre-compiles meshes around the expected destination
     * so ignition reveals an already-prepared portal view. Sent throttled (~every 2s) while the
     * player stays near the unlit frame; the client scope expires on its own if it stops coming.
     */
    public record SpeculativePrewarmScopePayload(
        String dimensionId,
        int x, int y, int z
    ) implements CustomPacketPayload {
        public static final Type<SpeculativePrewarmScopePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "speculative_prewarm_scope")
        );

        public static final StreamCodec<FriendlyByteBuf, SpeculativePrewarmScopePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SpeculativePrewarmScopePayload::dimensionId,
            ByteBufCodecs.VAR_INT, SpeculativePrewarmScopePayload::x,
            ByteBufCodecs.VAR_INT, SpeculativePrewarmScopePayload::y,
            ByteBufCodecs.VAR_INT, SpeculativePrewarmScopePayload::z,
            SpeculativePrewarmScopePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClientboundSeamlessMovePayload(
        String portalId,
        String destDimension,
        double x, double y, double z,
        float yaw, float pitch,
        double vx, double vy, double vz,
        int swapSeq
    ) implements CustomPacketPayload {
        public static final Type<ClientboundSeamlessMovePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SeamlessPortalsConstants.MOD_ID, "seamless_move")
        );

        // 11 fields — beyond StreamCodec.composite's arity; manual codec.
        // swapSeq: echo of the client's crossing sequence number (-1 = server-initiated
        // teleport). The client ignores reconciles whose swapSeq is older than its latest
        // client-first swap — the sequence-hardening that replaced the post-swap cooldown.
        public static final StreamCodec<FriendlyByteBuf, ClientboundSeamlessMovePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.portalId());
                buf.writeUtf(p.destDimension());
                buf.writeDouble(p.x());
                buf.writeDouble(p.y());
                buf.writeDouble(p.z());
                buf.writeFloat(p.yaw());
                buf.writeFloat(p.pitch());
                buf.writeDouble(p.vx());
                buf.writeDouble(p.vy());
                buf.writeDouble(p.vz());
                buf.writeVarInt(p.swapSeq());
            },
            buf -> new ClientboundSeamlessMovePayload(
                buf.readUtf(),
                buf.readUtf(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readVarInt()
            )
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
