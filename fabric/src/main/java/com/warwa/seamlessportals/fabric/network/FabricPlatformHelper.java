package com.warwa.seamlessportals.fabric.network;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
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
        // S20: the bespoke BLOCK-ERA payload set is gone with the block era — 15 clientbound + 4
        // serverbound ModPayloads type registrations used to live here, and the two static handler
        // installers (registerServerHandlers / registerClientHandlers, ~250 lines) went with them;
        // their sole callers were inside the flag-OFF else arms of SeamlessPortalsModFabric and
        // SeamlessPortalsClientFabric, both deleted in this same commit.
        //
        // THE GENERIC SEAM ABOVE IS UNTOUCHED AND IS WHAT MATTERS NOW: IP's own networking registers
        // through registerClientboundPayload / registerServerboundPayload /
        // registerServerPayloadHandler / registerClientPayloadHandler on this same class, so this
        // method having no body does NOT mean the mod registers no payloads. Mirrors the shape the
        // NeoForge side already carries (NeoForgePlatformHelper.registerPayloads has always been a
        // log-only stub, with its real work done from the RegisterPayloadHandlersEvent + the
        // PENDING_CLIENTBOUND/PENDING_SERVERBOUND queues).
        //
        // Kept as a method rather than removed from the PlatformHelper interface: the interface is a
        // SURVIVOR (four ported qouteall importers) and its unconditional call site at
        // SeamlessPortalsModFabric survives, so an empty implementation is the smaller change.
        SeamlessPortalsConstants.LOGGER.info(
            "Seamless Portals: Fabric payload seam ready (block-era payload set retired at S20; "
                + "IP payloads register through the generic PlatformHelper seam)");
    }

}
