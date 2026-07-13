package com.warwa.seamlessportals.fabric.network;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class FabricPlatformHelper implements PlatformHelper {

    @Override
    public void sendToClient(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    // ------------------------------------------------------------------
    // Entity-portal migration, S0 loader seams (EXECUTION_PLAN §3 S0(a);
    // seam inventory: migration/port-notes/S00-seam-inventory.md). Fabric
    // implementations of the seam surface consumed by the ported IP code
    // from S7 (payloads/receivers) and S13 (registrations) onward. Nothing
    // block-era calls these — current behavior unchanged.
    // ------------------------------------------------------------------

    @Override
    public <T extends CustomPacketPayload> void registerClientboundPayload(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        // Fabric API v6 rename absorbed here: playS2C() -> clientboundPlay() (R13h).
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerboundPayload(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerPayloadHandler(
            CustomPacketPayload.Type<T> type, ServerPayloadHandler<T> handler) {
        // Fabric v6 play-payload handlers already run on the server main
        // thread (ServerPlayNetworking.PlayPayloadHandler contract) — no
        // re-scheduling here; the seam's threading promise is met directly.
        ServerPlayNetworking.registerGlobalReceiver(
            type, (payload, context) -> handler.handle(payload, context.player()));
    }

    @Override
    public <T extends CustomPacketPayload> void registerClientPayloadHandler(
            CustomPacketPayload.Type<T> type, ClientPayloadHandler<T> handler) {
        // Client main (render) thread, same v6 contract. Client dist only.
        ClientPlayNetworking.registerGlobalReceiver(
            type, (payload, context) -> handler.handle(payload, context.client()));
    }

    @Override
    public void registerEntityTypes(Consumer<BiConsumer<Identifier, EntityType<?>>> registrationSource) {
        // Mirrors IP's Fabric entrypoint exactly (IP IPModEntry.java:18-20):
        // direct vanilla registry writes during mod init.
        registrationSource.accept(
            (id, entityType) -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, entityType));
    }

    @Override
    public <E extends Entity> void registerEntityRenderer(
            EntityType<? extends E> entityType, EntityRendererProvider<E> rendererProvider) {
        // Mirrors IP IPModEntryClient.initPortalRenderers (:41-61). The Fabric
        // API class is deprecated in favor of vanilla EntityRenderers, but it
        // is exactly what IP calls and it handles registration both before and
        // after the EntityRenderDispatcher initializes. Client dist only.
        EntityRendererRegistry.register(entityType, rendererProvider);
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
        // Phase 4c: redirected vanilla chunk packet (replaces the custom snapshot feed).
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.RedirectedChunkPayload.TYPE,
            ModPayloads.RedirectedChunkPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.RemoteChunkUnloadPayload.TYPE,
            ModPayloads.RemoteChunkUnloadPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.PortalLinkPayload.TYPE,
            ModPayloads.PortalLinkPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.ClientboundSeamlessMovePayload.TYPE,
            ModPayloads.ClientboundSeamlessMovePayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.SpeculativePrewarmScopePayload.TYPE,
            ModPayloads.SpeculativePrewarmScopePayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.RemoteBlockUpdatePayload.TYPE,
            ModPayloads.RemoteBlockUpdatePayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.RemoteBlockUpdateBatchPayload.TYPE,
            ModPayloads.RemoteBlockUpdateBatchPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.RemoteEntityAddPayload.TYPE,
            ModPayloads.RemoteEntityAddPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.RemoteEntityMovePayload.TYPE,
            ModPayloads.RemoteEntityMovePayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.RemoteEntityRemovePayload.TYPE,
            ModPayloads.RemoteEntityRemovePayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.RemoteEntityDataPayload.TYPE,
            ModPayloads.RemoteEntityDataPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.RemoteEntityEquipmentPayload.TYPE,
            ModPayloads.RemoteEntityEquipmentPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
            ModPayloads.PortalUnregisterPayload.TYPE,
            ModPayloads.PortalUnregisterPayload.STREAM_CODEC
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
        PayloadTypeRegistry.serverboundPlay().register(
            ModPayloads.ClientPortalCrossingPayload.TYPE,
            ModPayloads.ClientPortalCrossingPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.serverboundPlay().register(
            ModPayloads.RedirectedChunkAckPayload.TYPE,
            ModPayloads.RedirectedChunkAckPayload.STREAM_CODEC
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

        // IP-style client-initiated seamless teleport: client detected the
        // crossing locally and already did its visual swap; the server now
        // performs the authoritative teleport (without sending a respawn
        // packet) and replies with ClientboundSeamlessMovePayload.
        // Redirected-chunk ACK: the client confirms which redirected chunks it
        // actually APPLIED; the tracker's ledger records them as client-held only
        // now (arming the crossing suppression with anything less than applied
        // truth voids chunks permanently).
        ServerPlayNetworking.registerGlobalReceiver(
            ModPayloads.RedirectedChunkAckPayload.TYPE,
            (payload, context) -> {
                ServerPlayer ackPlayer = context.player();
                context.server().execute(() -> {
                    net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim =
                        switch (payload.dimensionId()) {
                            case "minecraft:overworld" -> net.minecraft.world.level.Level.OVERWORLD;
                            case "minecraft:the_nether" -> net.minecraft.world.level.Level.NETHER;
                            case "minecraft:the_end" -> net.minecraft.world.level.Level.END;
                            default -> null;
                        };
                    if (dim != null) {
                        com.warwa.seamlessportals.chunk.PortalChunkTracker.handleChunkAcks(
                            ackPlayer, dim, payload.packedPositions());
                    }
                });
            }
        );

        ServerPlayNetworking.registerGlobalReceiver(
            ModPayloads.ClientPortalCrossingPayload.TYPE,
            (payload, context) -> {
                ServerPlayer player = context.player();
                context.server().execute(() -> {
                    com.warwa.seamlessportals.entity.SeamlessServerTeleport
                        .handleClientInitiatedCrossing(player, payload.portalId(), payload.swapSeq(),
                            payload.exitDepthSign());
                });
            }
        );
    }

    public static void registerClientHandlers() {
        // Phase 4c: redirected vanilla chunk packet — run the packet's own handler
        // against the dest ClientLevel under a world-switch so the engine drives
        // the dest occlusion graph. Replaces the RemoteChunkDataPayload snapshot.
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.RedirectedChunkPayload.TYPE,
            (payload, context) -> {
                // T2: enqueue (thread-safe) instead of applying inline. The full
                // synchronous handleLevelChunkWithLight runs in a budgeted per-tick
                // drain (RedirectedPacketApplier.drainPending, from the client tick),
                // so a burst of pre-warm chunks spreads over ticks instead of freezing
                // the render thread for ~155ms.
                com.warwa.seamlessportals.chunk.RedirectedPacketApplier.enqueue(payload);
            }
        );

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

        // Seamless reconciliation / fallback-swap payload.
        // Hot path: the client already performed its visual swap; this packet
        // confirms authoritative dest position + velocity from the server.
        // Fallback: the client never detected the crossing (e.g. link wasn't
        // synced in time); this packet carries enough info to trigger the
        // deferred visual swap now.
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.ClientboundSeamlessMovePayload.TYPE,
            (payload, context) -> {
                context.client().execute(() -> {
                    com.warwa.seamlessportals.client.SeamlessClientTeleport
                        .handleServerReconcile(payload);
                });
            }
        );

        // Speculative pre-warm: hold the expected dest region scope-live + pre-mesh it.
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.SpeculativePrewarmScopePayload.TYPE,
            (payload, context) -> {
                context.client().execute(() -> {
                    com.warwa.seamlessportals.client.PortalWorldManager
                        .addSpeculativeScope(payload.dimensionId(),
                            new net.minecraft.core.BlockPos(payload.x(), payload.y(), payload.z()));
                });
            }
        );

        // Phase 1 live-portal-view: apply block updates from watched dims
        // to the cached ClientLevel + kick the cached renderer to rebuild
        // the affected section on its next portal-view FBO render.
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.RemoteBlockUpdatePayload.TYPE,
            (payload, context) -> {
                context.client().execute(() -> {
                    com.warwa.seamlessportals.chunk.RemoteBlockUpdater.apply(
                        payload.dimensionId(), payload.packedPos(), payload.blockStateId());
                });
            }
        );
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.RemoteBlockUpdateBatchPayload.TYPE,
            (payload, context) -> {
                context.client().execute(() ->
                    com.warwa.seamlessportals.chunk.RemoteBlockUpdater.applyBatch(
                        payload.dimensionId(), payload.positions(), payload.blockStateIds()));
            }
        );

        // Phase 2a live-portal-view: entity mirroring. Add/move/remove
        // entities in the cached ClientLevel so they render through
        // portals and participate in the client's entity-getter for later
        // cross-portal raycast work.
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.RemoteEntityAddPayload.TYPE,
            (payload, context) -> {
                context.client().execute(() ->
                    com.warwa.seamlessportals.chunk.RemoteEntityApplier.applyAdd(payload));
            }
        );
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.RemoteEntityMovePayload.TYPE,
            (payload, context) -> {
                context.client().execute(() ->
                    com.warwa.seamlessportals.chunk.RemoteEntityApplier.applyMove(payload));
            }
        );
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.RemoteEntityRemovePayload.TYPE,
            (payload, context) -> {
                context.client().execute(() ->
                    com.warwa.seamlessportals.chunk.RemoteEntityApplier.applyRemove(payload));
            }
        );
        // Phase 2b: apply SynchedEntityData (pose / glow / baby / custom
        // name) from server mirror to cached ClientLevel entity.
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.RemoteEntityDataPayload.TYPE,
            (payload, context) -> {
                context.client().execute(() ->
                    com.warwa.seamlessportals.chunk.RemoteEntityApplier.applyData(payload));
            }
        );
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.RemoteEntityEquipmentPayload.TYPE,
            (payload, context) -> {
                context.client().execute(() ->
                    com.warwa.seamlessportals.chunk.RemoteEntityApplier.applyEquipment(payload));
            }
        );
        // Portal destroyed on server — drop the client-side PortalInfo
        // so the stencil/view stops rendering.
        ClientPlayNetworking.registerGlobalReceiver(
            ModPayloads.PortalUnregisterPayload.TYPE,
            (payload, context) -> {
                context.client().execute(() -> {
                    com.warwa.seamlessportals.portal.PortalManager cm =
                        com.warwa.seamlessportals.portal.PortalManager.getClientInstance();
                    net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim;
                    switch (payload.dimensionId()) {
                        case "minecraft:overworld" ->
                            dim = net.minecraft.world.level.Level.OVERWORLD;
                        case "minecraft:the_nether" ->
                            dim = net.minecraft.world.level.Level.NETHER;
                        case "minecraft:the_end" ->
                            dim = net.minecraft.world.level.Level.END;
                        default -> { return; }
                    }
                    cm.getTracker(dim).getPortalAt(payload.origin()).ifPresent(cm::unregisterPortal);
                    com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS] Client dropped portal at {} in {}",
                        payload.origin().toShortString(), payload.dimensionId());
                });
            }
        );
    }
}
