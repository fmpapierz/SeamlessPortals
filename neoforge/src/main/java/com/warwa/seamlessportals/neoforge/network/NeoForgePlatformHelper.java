package com.warwa.seamlessportals.neoforge.network;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class NeoForgePlatformHelper implements PlatformHelper {

    // ------------------------------------------------------------------
    // Entity-portal migration, S0 loader seams — NeoForge skeleton parity
    // (EXECUTION_PLAN §3 S0(a); seam inventory: migration/port-notes/
    // S00-seam-inventory.md). NeoForge registers payloads inside
    // RegisterPayloadHandlersEvent and registries inside its registry
    // events, so the seam calls (which arrive at mod-init time from common
    // code) are QUEUED here and drained in the proper event window. The
    // payload queues drain in onRegisterPayloadHandlers below (already
    // subscribed); the entity-type/renderer queues expose drain methods for
    // the stage that first wires NeoForge registry/client events (S13) —
    // consistent with this module's existing state (client integration is
    // maintained on Fabric; see SeamlessPortalsModNeoForge.onClientSetup).
    // Nothing block-era calls these seams — current behavior unchanged.
    // ------------------------------------------------------------------

    private static final Map<CustomPacketPayload.Type<?>, StreamCodec<? super RegistryFriendlyByteBuf, ?>>
        PENDING_CLIENTBOUND = new LinkedHashMap<>();
    private static final Map<CustomPacketPayload.Type<?>, StreamCodec<? super RegistryFriendlyByteBuf, ?>>
        PENDING_SERVERBOUND = new LinkedHashMap<>();
    private static final Map<CustomPacketPayload.Type<?>, ServerPayloadHandler<?>>
        SERVER_HANDLERS = new ConcurrentHashMap<>();
    private static final Map<CustomPacketPayload.Type<?>, ClientPayloadHandler<?>>
        CLIENT_HANDLERS = new ConcurrentHashMap<>();
    private static final List<Consumer<BiConsumer<Identifier, EntityType<?>>>>
        PENDING_ENTITY_TYPE_SOURCES = new ArrayList<>();
    private static final List<Consumer<EntityRendererSink>>
        PENDING_ENTITY_RENDERERS = new ArrayList<>();

    // NF-PARITY W12 (2026-08-25): configuration-phase queues. Payload registrations drain in
    // onRegisterPayloadHandlers below (NETWORK-thread + optional, see the drain); the
    // configuration-start handlers drain in onRegisterConfigurationTasks (MOD-bus event,
    // subscribed by SeamlessPortalsModNeoForge).
    private static final List<Consumer<PayloadRegistrar>>
        PENDING_CONFIG_PAYLOADS = new ArrayList<>();
    private static final List<PlatformHelper.ServerConfigurationStartHandler>
        CONFIG_START_HANDLERS = new ArrayList<>();

    /** Renderer-registration sink for {@link #drainEntityRendererRegistrations}. */
    @FunctionalInterface
    public interface EntityRendererSink {
        <E extends Entity> void accept(EntityType<? extends E> entityType, EntityRendererProvider<E> provider);
    }

    @Override
    public void sendToClient(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }

    @Override
    public <T extends CustomPacketPayload> void registerClientboundPayload(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PENDING_CLIENTBOUND.put(type, codec);
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerboundPayload(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PENDING_SERVERBOUND.put(type, codec);
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerPayloadHandler(
            CustomPacketPayload.Type<T> type, ServerPayloadHandler<T> handler) {
        // Looked up at receive time, so handlers may be registered before or
        // after the RegisterPayloadHandlersEvent window.
        SERVER_HANDLERS.put(type, handler);
    }

    @Override
    public <T extends CustomPacketPayload> void registerClientPayloadHandler(
            CustomPacketPayload.Type<T> type, ClientPayloadHandler<T> handler) {
        CLIENT_HANDLERS.put(type, handler);
    }

    @Override
    public void registerEntityTypes(Consumer<BiConsumer<Identifier, EntityType<?>>> registrationSource) {
        PENDING_ENTITY_TYPE_SOURCES.add(registrationSource);
    }

    // ==== NF-PARITY W13 (2026-08-25): chunk-sent seams, NeoForge binding ================

    @Override
    public net.minecraft.network.protocol.Packet<?> decorateChunkPacket(
            net.minecraft.world.level.chunk.LevelChunk chunk,
            net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet) {
        // NeoForge's own sendChunk wrap (NF PlayerChunkSender.java:77-79): attaches the
        // auxiliary block-light data dynamic-light mods rely on.
        return chunk.getAuxLightManager(chunk.getPos()).sendLightDataTo(packet);
    }

    @Override
    public void onChunkSentToPlayer(
            net.minecraft.server.network.ServerGamePacketListenerImpl listener,
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.chunk.LevelChunk chunk) {
        // C6 guard: NeoForge's chunk-attachment receiver resolves against player.level(), so
        // an event for a REMOTE-dimension chunk would mis-apply attachments to the
        // same-coordinate chunk in the player's current dimension. Remote-dim chunk
        // attachments are simply not synced — narrow, documented deviation.
        if (level.dimension() != listener.player.level().dimension()) {
            return;
        }
        try {
            // Posts ChunkWatchEvent.Sent ("may be used to send additional chunk-related data
            // to the client"). This fires from inside IP's own send loop
            // (PacketRedirection.withForceRedirect); a throwing subscriber must not abort it.
            net.neoforged.neoforge.event.EventHooks.fireChunkSent(listener.player, chunk, level);
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.error(
                "[NF-PARITY W13] a ChunkWatchEvent.Sent subscriber threw during an IP chunk send", t);
        }
    }

    // ==== NF-PARITY W12 (2026-08-25): configuration-phase seams, NeoForge binding ========

    @Override
    public <T extends CustomPacketPayload> void registerConfigClientboundPayload(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super net.minecraft.network.FriendlyByteBuf, T> codec,
            ClientConfigPayloadHandler<T> handler) {
        PENDING_CONFIG_PAYLOADS.add(registrar ->
            registrar.configurationToClient(type, codec,
                (payload, context) -> handler.handle(payload, context::reply)));
    }

    @Override
    public <T extends CustomPacketPayload> void registerConfigServerboundPayload(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super net.minecraft.network.FriendlyByteBuf, T> codec,
            ServerConfigPayloadHandler<T> handler) {
        PENDING_CONFIG_PAYLOADS.add(registrar ->
            registrar.configurationToServer(type, codec, (payload, context) ->
                handler.handle(payload, new ServerConfigContext() {
                    @Override
                    public com.mojang.authlib.GameProfile gameProfile() {
                        // getOwner() is a NeoForge-public on ServerCommonPacketListenerImpl (:229).
                        return ((net.minecraft.server.network.ServerConfigurationPacketListenerImpl)
                            context.listener()).getOwner();
                    }

                    @Override
                    public void finishTask(net.minecraft.server.network.ConfigurationTask.Type taskType) {
                        context.finishCurrentTask(taskType);
                    }

                    @Override
                    public void disconnect(net.minecraft.network.chat.Component reason) {
                        context.disconnect(reason);
                    }
                })));
    }

    @Override
    public void onServerConfigurationStart(ServerConfigurationStartHandler handler) {
        CONFIG_START_HANDLERS.add(handler);
    }

    /**
     * MOD-BUS listener (subscribed by {@code SeamlessPortalsModNeoForge}). Fires per
     * connection when the server assembles its configuration tasks — AFTER NeoForge's
     * modded-network negotiation ({@code startConfiguration} runs the query first, so
     * {@code hasChannel} answers correctly here; NeoForge's own
     * {@code ConfigurationInitialization} relies on the same ordering).
     */
    public static void onRegisterConfigurationTasks(
            net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent event) {
        var listener = event.getListener();
        PlatformHelper.ServerConfigStartControl control = new PlatformHelper.ServerConfigStartControl() {
            @Override
            public boolean canSend(CustomPacketPayload.Type<?> type) {
                return listener.hasChannel(type);
            }

            @Override
            public void addTask(net.minecraft.server.network.ConfigurationTask task) {
                event.register(task);
            }

            @Override
            public void disconnect(net.minecraft.network.chat.Component reason) {
                listener.disconnect(reason);
            }

            @Override
            public com.mojang.authlib.GameProfile gameProfile() {
                return ((net.minecraft.server.network.ServerConfigurationPacketListenerImpl) listener).getOwner();
            }
        };
        net.minecraft.server.MinecraftServer server =
            net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        for (PlatformHelper.ServerConfigurationStartHandler handler : CONFIG_START_HANDLERS) {
            handler.onConfigure(control, server);
        }
    }

    @Override
    public <E extends Entity> void registerEntityRenderer(
            EntityType<? extends E> entityType, EntityRendererProvider<E> rendererProvider) {
        PENDING_ENTITY_RENDERERS.add(sink -> sink.accept(entityType, rendererProvider));
    }

    /**
     * Replays every queued entity-type registration source into {@code sink}.
     * To be called from the NeoForge registry event (entity-type window) by
     * the stage that wires NeoForge registrations (S13 step 5).
     */
    public static void drainEntityTypeRegistrations(BiConsumer<Identifier, EntityType<?>> sink) {
        PENDING_ENTITY_TYPE_SOURCES.forEach(source -> source.accept(sink));
    }

    /**
     * Replays every queued entity-renderer registration into {@code sink}.
     * To be called from the NeoForge client renderer-registration event
     * ({@code EntityRenderersEvent.RegisterRenderers}) when the NeoForge
     * client path is wired (S13 step 5 / the module's client-wiring work).
     */
    public static void drainEntityRendererRegistrations(EntityRendererSink sink) {
        PENDING_ENTITY_RENDERERS.forEach(reg -> reg.accept(sink));
    }

    private static <T extends CustomPacketPayload> void registerQueuedClientbound(
            PayloadRegistrar registrar,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, ?> codec) {
        @SuppressWarnings("unchecked")
        StreamCodec<? super RegistryFriendlyByteBuf, T> typedCodec =
            (StreamCodec<? super RegistryFriendlyByteBuf, T>) codec;
        registrar.playToClient(type, typedCodec, (payload, context) -> {
            @SuppressWarnings("unchecked")
            ClientPayloadHandler<T> handler = (ClientPayloadHandler<T>) CLIENT_HANDLERS.get(type);
            if (handler != null) {
                // Fabric-parity threading: handlers run on the client main thread.
                context.enqueueWork(() -> handler.handle(payload, Minecraft.getInstance()));
            }
        });
    }

    private static <T extends CustomPacketPayload> void registerQueuedServerbound(
            PayloadRegistrar registrar,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, ?> codec) {
        @SuppressWarnings("unchecked")
        StreamCodec<? super RegistryFriendlyByteBuf, T> typedCodec =
            (StreamCodec<? super RegistryFriendlyByteBuf, T>) codec;
        registrar.playToServer(type, typedCodec, (payload, context) -> {
            @SuppressWarnings("unchecked")
            ServerPayloadHandler<T> handler = (ServerPayloadHandler<T>) SERVER_HANDLERS.get(type);
            if (handler != null) {
                // Fabric-parity threading: handlers run on the server main thread.
                context.enqueueWork(() -> handler.handle(payload, (ServerPlayer) context.player()));
            }
        });
    }

    @Override
    public void registerPayloads() {
        // NF-PARITY W7 (2026-08-25): the COMPLETE block-era payload TYPE set, mirroring
        // FabricPlatformHelper.registerPayloads' 19 registrations. The old NeoForge module
        // registered only 5 — the missing 14 were the June-2026 server crash
        // ("UnsupportedOperationException: Payload seamlessportals:portal_link may not be
        // sent to the client" — NeoForge's checkPacket THROWS where Fabric silently drops).
        // Queued through the seams; drained at RegisterPayloadHandlersEvent with map-lookup
        // handlers (no-op until a handler is registered — the block-era handler SETS remain
        // Fabric-only, the recorded flag-OFF deviation). The 4 payloads with live NeoForge
        // handlers keep their direct registrations in onRegisterPayloadHandlers and are NOT
        // duplicated here.
        registerClientboundPayload(ModPayloads.RedirectedChunkPayload.TYPE, ModPayloads.RedirectedChunkPayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.PortalLinkPayload.TYPE, ModPayloads.PortalLinkPayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.ClientboundSeamlessMovePayload.TYPE, ModPayloads.ClientboundSeamlessMovePayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.SpeculativePrewarmScopePayload.TYPE, ModPayloads.SpeculativePrewarmScopePayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteBlockUpdatePayload.TYPE, ModPayloads.RemoteBlockUpdatePayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteEntityAddPayload.TYPE, ModPayloads.RemoteEntityAddPayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteEntityMovePayload.TYPE, ModPayloads.RemoteEntityMovePayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteEntityRemovePayload.TYPE, ModPayloads.RemoteEntityRemovePayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteEntityDataPayload.TYPE, ModPayloads.RemoteEntityDataPayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.RemoteEntityEquipmentPayload.TYPE, ModPayloads.RemoteEntityEquipmentPayload.STREAM_CODEC);
        registerClientboundPayload(ModPayloads.PortalUnregisterPayload.TYPE, ModPayloads.PortalUnregisterPayload.STREAM_CODEC);
        registerServerboundPayload(ModPayloads.RequestPortalDataPayload.TYPE, ModPayloads.RequestPortalDataPayload.STREAM_CODEC);
        registerServerboundPayload(ModPayloads.ClientPortalCrossingPayload.TYPE, ModPayloads.ClientPortalCrossingPayload.STREAM_CODEC);
        registerServerboundPayload(ModPayloads.RedirectedChunkAckPayload.TYPE, ModPayloads.RedirectedChunkAckPayload.STREAM_CODEC);

        SeamlessPortalsConstants.LOGGER.info(
            "NeoForge network payloads queued (19 block-era types; drained at RegisterPayloadHandlersEvent)");
    }

    public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(SeamlessPortalsConstants.MOD_ID);

        // Server -> Client
        registrar.playToClient(
            ModPayloads.PortalSyncPayload.TYPE,
            ModPayloads.PortalSyncPayload.STREAM_CODEC,
            NeoForgePlatformHelper::handlePortalSync
        );
        registrar.playToClient(
            ModPayloads.RemoteChunkDataPayload.TYPE,
            ModPayloads.RemoteChunkDataPayload.STREAM_CODEC,
            NeoForgePlatformHelper::handleRemoteChunkData
        );
        registrar.playToClient(
            ModPayloads.RemoteChunkUnloadPayload.TYPE,
            ModPayloads.RemoteChunkUnloadPayload.STREAM_CODEC,
            NeoForgePlatformHelper::handleRemoteChunkUnload
        );
        registrar.playToClient(
            ModPayloads.RemoteBlockUpdateBatchPayload.TYPE,
            ModPayloads.RemoteBlockUpdateBatchPayload.STREAM_CODEC,
            NeoForgePlatformHelper::handleRemoteBlockUpdateBatch
        );

        // Client -> Server
        registrar.playToServer(
            ModPayloads.PortalTeleportPayload.TYPE,
            ModPayloads.PortalTeleportPayload.STREAM_CODEC,
            NeoForgePlatformHelper::handlePortalTeleport
        );

        // S0 seam drain: payload types queued through the loader-neutral
        // registerClientboundPayload/registerServerboundPayload seams (empty
        // until the ported IP network stage, S7, starts feeding them).
        PENDING_CLIENTBOUND.forEach((type, codec) -> registerQueuedClientbound(registrar, type, codec));
        PENDING_SERVERBOUND.forEach((type, codec) -> registerQueuedServerbound(registrar, type, codec));

        // NF-PARITY W12: configuration-phase payload drain. NETWORK thread = Fabric's config-
        // receiver threading (the handshake replies mid-configuration); .optional() so a client
        // WITHOUT the mod still connects far enough for IP's own reject/warn policy
        // (serverRejectClientWithoutImmPtl) to decide — NeoForge's default non-optional gating
        // would disconnect first and make that config a silent no-op (recon C5.1).
        PayloadRegistrar configRegistrar = registrar
            .executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK)
            .optional();
        PENDING_CONFIG_PAYLOADS.forEach(reg -> reg.accept(configRegistrar));

        SeamlessPortalsConstants.LOGGER.info("NeoForge network payloads registered");
    }

    private static void handlePortalSync(ModPayloads.PortalSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            SeamlessPortalsConstants.LOGGER.debug("Received portal sync: {}", payload.portalId());
        });
    }

    private static void handleRemoteChunkData(ModPayloads.RemoteChunkDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            RemoteChunkManager.handleChunkData(
                payload.dimensionId(), payload.chunkX(), payload.chunkZ(), payload.chunkData()
            );
        });
    }

    private static void handleRemoteChunkUnload(ModPayloads.RemoteChunkUnloadPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            RemoteChunkManager.handleChunkUnload(
                payload.dimensionId(), payload.chunkX(), payload.chunkZ()
            );
        });
    }

    private static void handlePortalTeleport(ModPayloads.PortalTeleportPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            SeamlessPortalsConstants.LOGGER.debug("Received teleport confirmation from client: {}", payload.portalId());
        });
    }

    private static void handleRemoteBlockUpdateBatch(
            ModPayloads.RemoteBlockUpdateBatchPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.warwa.seamlessportals.chunk.RemoteBlockUpdater.applyBatch(
                payload.dimensionId(), payload.positions(), payload.blockStateIds()));
    }
}
