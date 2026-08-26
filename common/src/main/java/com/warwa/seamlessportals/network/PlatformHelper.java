package com.warwa.seamlessportals.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.ServiceLoader;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface PlatformHelper {

    void sendToClient(ServerPlayer player, CustomPacketPayload payload);

    void sendToServer(CustomPacketPayload payload);

    void registerPayloads();

    // ------------------------------------------------------------------
    // Entity-portal migration, S0 loader seams (migration/EXECUTION_PLAN.md
    // §3 S0(a); forced-deviation F12; API_RISKS R13h "never Fabric types in
    // common"). Inventory + wiring stages: migration/port-notes/
    // S00-seam-inventory.md. The methods below are SEAMS for the ported IP
    // code (consumed from S7 network / S13 registration onward); nothing in
    // the block-portal era calls them, so current behavior is unchanged.
    // ------------------------------------------------------------------

    /**
     * Registers a server-to-client (clientbound play) payload type + codec.
     * Seam for IP's {@code ImmPtlNetworking.init}/{@code MiscNetworking.init}
     * registrations (consumed at S7). Absorbs the Fabric API v6 rename
     * {@code PayloadTypeRegistry.playS2C()} → {@code clientboundPlay()}
     * (R13h; api-map/network.md headline 5). Call during mod init, both dists.
     */
    <T extends CustomPacketPayload> void registerClientboundPayload(
        CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec);

    /**
     * Registers a client-to-server (serverbound play) payload type + codec.
     * Counterpart of {@link #registerClientboundPayload} ({@code playC2S()} →
     * {@code serverboundPlay()}). Call during mod init, both dists.
     */
    <T extends CustomPacketPayload> void registerServerboundPayload(
        CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec);

    /**
     * Registers the server-side receiver for a serverbound payload. The
     * handler is invoked on the SERVER main thread (the Fabric v6 play-payload
     * contract; the NeoForge implementation restores parity via
     * {@code enqueueWork}). Seam for IP's
     * {@code ServerPlayNetworking.registerGlobalReceiver} calls (S7).
     */
    <T extends CustomPacketPayload> void registerServerPayloadHandler(
        CustomPacketPayload.Type<T> type, ServerPayloadHandler<T> handler);

    /**
     * Registers the client-side receiver for a clientbound payload. The
     * handler is invoked on the CLIENT main (render) thread. Client dist only —
     * call from client init. Seam for IP's
     * {@code ClientPlayNetworking.registerGlobalReceiver} calls (S7
     * {@code ImmPtlNetworking.initClient}/{@code MiscNetworking.initClient}).
     */
    <T extends CustomPacketPayload> void registerClientPayloadHandler(
        CustomPacketPayload.Type<T> type, ClientPayloadHandler<T> handler);

    /**
     * Entity-type registration callback plumbing, mirroring how IP's Fabric
     * entrypoint consumes {@code IPModMain.registerEntityTypes(BiConsumer)}
     * (IP IPModEntry.java:18-20: {@code Registry.register(BuiltInRegistries
     * .ENTITY_TYPE, id, entityType)}; api-map/world-loader-root.md §5;
     * api-map/portal-core.md F1). The registration SOURCE is passed so each
     * loader can invoke it inside its own registration window (Fabric:
     * immediately during mod init; NeoForge: replayed in its registry event).
     * Wired at S13 step 5 — registrations are UNCONDITIONAL in both flag
     * states (D3 registries-unconditional rule).
     */
    void registerEntityTypes(Consumer<BiConsumer<Identifier, EntityType<?>>> registrationSource);

    // ==== NF-PARITY W12 (2026-08-25): configuration-phase networking seams ====
    // The only consumer is ImmPtlNetworkConfig (IP's version handshake). Contract: call all
    // three during mod init. Fabric registers immediately (PayloadTypeRegistry +
    // Client/ServerConfigurationNetworking + ServerConfigurationConnectionEvents.CONFIGURE);
    // NeoForge queues and drains inside RegisterPayloadHandlersEvent (payloads, NETWORK-thread
    // handlers to match Fabric's config-receiver threading, .optional() so a modless client
    // reaches IP's own polite reject/warn path instead of NeoForge's blunt one) and
    // RegisterConfigurationTasksEvent (the configure hook — MOD bus, wired by the mod class).

    /** Registers a clientbound CONFIGURATION-phase payload + its client receiver. */
    <T extends CustomPacketPayload> void registerConfigClientboundPayload(
        CustomPacketPayload.Type<T> type,
        StreamCodec<? super FriendlyByteBuf, T> codec,
        ClientConfigPayloadHandler<T> handler);

    /** Registers a serverbound CONFIGURATION-phase payload + its server receiver. */
    <T extends CustomPacketPayload> void registerConfigServerboundPayload(
        CustomPacketPayload.Type<T> type,
        StreamCodec<? super FriendlyByteBuf, T> codec,
        ServerConfigPayloadHandler<T> handler);

    /**
     * Runs when a player's server-side CONFIGURATION phase starts (Fabric:
     * {@code ServerConfigurationConnectionEvents.CONFIGURE}, after channel sync; NeoForge:
     * {@code RegisterConfigurationTasksEvent}, after the modded-network negotiation — both
     * points can already answer {@code canSend}).
     */
    void onServerConfigurationStart(ServerConfigurationStartHandler handler);

    @FunctionalInterface
    interface ClientConfigPayloadHandler<T extends CustomPacketPayload> {
        /** {@code replySender} sends a serverbound configuration payload back on the same connection. */
        void handle(T payload, Consumer<CustomPacketPayload> replySender);
    }

    @FunctionalInterface
    interface ServerConfigPayloadHandler<T extends CustomPacketPayload> {
        void handle(T payload, ServerConfigContext context);
    }

    /** Per-connection server-side configuration context. */
    interface ServerConfigContext {
        com.mojang.authlib.GameProfile gameProfile();
        void finishTask(net.minecraft.server.network.ConfigurationTask.Type type);
        void disconnect(net.minecraft.network.chat.Component reason);
    }

    @FunctionalInterface
    interface ServerConfigurationStartHandler {
        void onConfigure(ServerConfigStartControl control, net.minecraft.server.MinecraftServer server);
    }

    /** Controls available while a player's configuration phase is being assembled. */
    interface ServerConfigStartControl {
        boolean canSend(CustomPacketPayload.Type<?> type);
        void addTask(net.minecraft.server.network.ConfigurationTask task);
        void disconnect(net.minecraft.network.chat.Component reason);
        com.mojang.authlib.GameProfile gameProfile();
    }

    /**
     * Entity-RENDERER registration seam, mirroring the Fabric
     * {@code EntityRendererRegistry.register(entityType, provider)} calls in
     * IP's {@code IPModEntryClient.initPortalRenderers} (IP
     * IPModEntryClient.java:41-61; api-map/portal-core.md F2). Client dist
     * only — call from client init. Wired at S13 step 5 for
     * {@code PortalEntityRenderer} (the Portal entity-type family) +
     * {@code LoadingIndicatorRenderer} (EXECUTION_PLAN §3 S13; Appendix A.9 —
     * IP's entrypoint classes are not ported, so this seam carries their
     * renderer-registration cargo).
     */
    <E extends Entity> void registerEntityRenderer(
        EntityType<? extends E> entityType, EntityRendererProvider<E> rendererProvider);

    /**
     * Server-side payload receiver (main-thread — see
     * {@link #registerServerPayloadHandler}).
     */
    @FunctionalInterface
    interface ServerPayloadHandler<T extends CustomPacketPayload> {
        void handle(T payload, ServerPlayer sender);
    }

    /**
     * Client-side payload receiver (main-thread — see
     * {@link #registerClientPayloadHandler}).
     */
    @FunctionalInterface
    interface ClientPayloadHandler<T extends CustomPacketPayload> {
        void handle(T payload, Minecraft client);
    }

    static PlatformHelper getInstance() {
        return Holder.INSTANCE;
    }

    class Holder {
        private static final PlatformHelper INSTANCE = ServiceLoader.load(PlatformHelper.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No PlatformHelper implementation found"));
    }
}
