package com.warwa.seamlessportals.neoforge.network;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
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
        SeamlessPortalsConstants.LOGGER.info("NeoForge network payloads will be registered via event");
    }

    public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(SeamlessPortalsConstants.MOD_ID);
        PENDING_CLIENTBOUND.forEach((type, codec) -> registerQueuedClientbound(registrar, type, codec));
        PENDING_SERVERBOUND.forEach((type, codec) -> registerQueuedServerbound(registrar, type, codec));

        SeamlessPortalsConstants.LOGGER.info("NeoForge network payloads registered");
    }

}
