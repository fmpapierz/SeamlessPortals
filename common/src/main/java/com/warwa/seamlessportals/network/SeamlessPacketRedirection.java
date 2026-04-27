package com.warwa.seamlessportals.network;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.PortalWorldManager;
import com.warwa.seamlessportals.mixin.EntityLevelAccessorMixin;
import com.warwa.seamlessportals.mixin.client.ClientPacketListenerLevelAccessorMixin;
import com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin;
import com.warwa.seamlessportals.mixin.client.ParticleEngineAccessorMixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Stage 2 of IP-architecture parity: generic packet redirection.
 *
 * <p>Server side: wrap any clientbound game packet inside a
 * {@link Payload} carrying the target dimension. The wrapping packet is
 * a vanilla {@link ClientboundCustomPayloadPacket} so it travels through
 * the normal connection without special routing.
 *
 * <p>Client side: when the wrapped payload arrives, look up the cached
 * {@link ClientLevel} for the target dim, temporarily swap
 * {@code mc.level + mc.levelRenderer + mc.particleEngine.level +
 * connection.level} to point at it, dispatch the inner packet through
 * the vanilla handler, restore. The handler reads {@code this.level} /
 * {@code mc.level} as if it were the active level — chunks, light
 * updates, entity packets, block updates all land in the correct cached
 * world.
 *
 * <p>This is the missing routing layer that lets us populate cached
 * worlds with arbitrary server data (chunk packets in particular)
 * BEFORE the player teleports — so when they cross, the world is
 * already complete.
 *
 * <p>Mirrors IP's {@code PacketRedirection} +
 * {@code PacketRedirectionClient} + {@code MixinServerGamePacketListenerImpl}
 * combined. Differences: we send via an explicit
 * {@link #sendRedirected} helper (Stage 3 callers invoke it directly)
 * rather than a thread-local + auto-wrapping mixin on
 * {@code ServerGamePacketListenerImpl.send}. Same effect, less magic.
 */
public final class SeamlessPacketRedirection {

    private SeamlessPacketRedirection() {}

    /**
     * Bound clientbound game-protocol info, used to encode/decode
     * arbitrary packets inside our {@link Payload}. Mirrors IP's
     * {@code PLACEHOLDER_PROTOCOL_INFO}. The {@code bind} adapter is a
     * type cast — the codec consumes {@link RegistryFriendlyByteBuf}
     * but our codec gets a plain {@link FriendlyByteBuf} on the read
     * side; the cast is safe because Fabric's networking always passes
     * a {@code RegistryFriendlyByteBuf} for play-protocol payloads.
     */
    public static final ProtocolInfo<ClientGamePacketListener> PROTOCOL_INFO =
        GameProtocols.CLIENTBOUND_TEMPLATE.bind(
            buf -> (RegistryFriendlyByteBuf) buf
        );

    /** Resource location for our redirection custom-payload. */
    public static final Identifier PAYLOAD_ID =
        Identifier.fromNamespaceAndPath(
            SeamlessPortalsConstants.MOD_ID, "redirected");

    /**
     * The wrapped packet payload. Format: {@code (utf dimensionId,
     * encoded clientbound packet)}.
     */
    public record Payload(
            String dimensionId,
            Packet<? extends ClientGamePacketListener> packet
    ) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Payload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC =
            StreamCodec.of(
                (b, p) -> p.write(b),
                Payload::read
            );

        @SuppressWarnings("unchecked")
        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(dimensionId);
            PROTOCOL_INFO.codec()
                .encode(buf, (Packet<? super ClientGamePacketListener>) packet);
        }

        @SuppressWarnings("unchecked")
        public static Payload read(RegistryFriendlyByteBuf buf) {
            String dimId = buf.readUtf();
            Packet<ClientGamePacketListener> innerPacket =
                (Packet<ClientGamePacketListener>) PROTOCOL_INFO.codec().decode(buf);
            return new Payload(dimId, innerPacket);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server-side: send a packet to {@code player} that will be applied
     * to their cached {@link ClientLevel} for {@code dim}.
     *
     * <p>The packet is wrapped in a {@link Payload} and sent via
     * {@link ClientboundCustomPayloadPacket}.
     */
    public static void sendRedirected(
            ServerPlayer player,
            ResourceKey<Level> dim,
            Packet<? extends ClientGamePacketListener> packet) {
        Payload payload = new Payload(dim.identifier().toString(), packet);
        player.connection.send(new ClientboundCustomPayloadPacket(payload));
    }

    /**
     * Client-side: look up the cached {@link ClientLevel} for the named
     * dimension and dispatch {@code innerPacket} through the vanilla
     * handler with all "what level?" reads pointing at the cached level.
     *
     * <p>If no cached level exists for the dim (we've never seen a portal
     * pointing into it), the packet is dropped silently — there's nothing
     * to apply it to yet.
     *
     * <p>Must run on the client main thread.
     */
    public static void handleRedirectedPacket(
            String dimensionId,
            Packet<ClientGamePacketListener> innerPacket) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(() -> handleRedirectedPacket(dimensionId, innerPacket));
            return;
        }
        ResourceKey<Level> dim = parseDim(dimensionId);
        if (dim == null) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS REDIRECT] Unknown dimension id in redirected packet: {}",
                dimensionId);
            return;
        }
        if (mc.level != null && mc.level.dimension().equals(dim)) {
            // Target is the active dim — no swap needed; just dispatch.
            // (This case shouldn't normally happen since the server
            // wouldn't redirect to the active dim, but be safe.)
            ClientPacketListener handler = mc.getConnection();
            if (handler != null) {
                innerPacket.handle(handler);
            }
            return;
        }
        ClientLevel cached = PortalWorldManager.getLevel(dim);
        if (cached == null) {
            // No cached level — drop the packet. A future portal-link
            // payload from the server will cause the cache to be
            // created, and subsequent redirected packets will start
            // applying.
            return;
        }
        ClientPacketListener handler = mc.getConnection();
        if (handler == null) return;

        withSwitchedWorld(cached, () -> innerPacket.handle(handler));
    }

    /**
     * Temporarily swap {@code mc.level + mc.levelRenderer +
     * mc.particleEngine.level + connection.level} to the cached level,
     * run the runnable, then restore. The cached renderer is fetched
     * lazily and may be created if not yet cached.
     *
     * <p>Mirrors IP's {@code ClientWorldLoader.withSwitchedWorld}.
     *
     * <p>Single-threaded (client render/network thread). No re-entrancy
     * lock — recursive calls will save/restore in nested fashion.
     */
    public static void withSwitchedWorld(ClientLevel newLevel, Runnable runnable) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener handler = mc.getConnection();
        if (handler == null) {
            runnable.run();
            return;
        }
        LevelRenderer newRenderer =
            PortalWorldManager.getOrCreateRenderer(newLevel.dimension());

        ClientLevel oldLevel = mc.level;
        LevelRenderer oldRenderer = mc.levelRenderer;
        ClientLevel oldHandlerLevel =
            ((ClientPacketListenerLevelAccessorMixin)
                (Object) handler).seamlessportals$getLevel();
        ParticleEngine particleEngine = mc.particleEngine;
        ClientLevel oldParticleLevel = particleEngine == null ? null
            : ((ParticleEngineAccessorMixin) particleEngine)
                .seamlessportals$getLevel();

        mc.level = newLevel;
        if (newRenderer != null) {
            ((MinecraftAccessorMixin) mc)
                .seamlessportals$setLevelRenderer(newRenderer);
        }
        ((ClientPacketListenerLevelAccessorMixin)
            (Object) handler).seamlessportals$setLevel(newLevel);
        if (particleEngine != null) {
            ((ParticleEngineAccessorMixin) particleEngine)
                .seamlessportals$setLevel(newLevel);
        }

        try {
            runnable.run();
        } finally {
            mc.level = oldLevel;
            if (oldRenderer != null) {
                ((MinecraftAccessorMixin) mc)
                    .seamlessportals$setLevelRenderer(oldRenderer);
            }
            ((ClientPacketListenerLevelAccessorMixin)
                (Object) handler).seamlessportals$setLevel(oldHandlerLevel);
            if (particleEngine != null && oldParticleLevel != null) {
                ((ParticleEngineAccessorMixin) particleEngine)
                    .seamlessportals$setLevel(oldParticleLevel);
            }
        }
    }

    private static ResourceKey<Level> parseDim(String id) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) return null;
        return ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION, rl);
    }
}
