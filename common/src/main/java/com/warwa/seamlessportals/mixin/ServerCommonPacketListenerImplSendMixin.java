package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.network.SeamlessPacketRedirection;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * E5 — Auto-wrap outgoing packets when a {@link
 * SeamlessPacketRedirection#withForceRedirect} block is active on the
 * sending thread.
 *
 * <p>Mirrors IP 1.19's {@code MixinServerGamePacketListenerImpl_E}.
 * The 1.19 mixin targets {@code ServerGamePacketListenerImpl.send(Packet,
 * PacketSendListener)}; in MC 26.1.2 the {@code send} methods live on
 * the parent class {@link ServerCommonPacketListenerImpl} and the
 * listener parameter type changed to {@link ChannelFutureListener}.
 *
 * <p>Without this auto-wrap, every callsite that wants to send a packet
 * to a cached {@link net.minecraft.client.multiplayer.ClientLevel}
 * would need to call
 * {@link SeamlessPacketRedirection#sendRedirected} explicitly — fine
 * for the chunk-data sync's primary send, but unworkable for the
 * cascade of side-effect packets fired by entity-tracker updates
 * inside {@code ChunkMap.ip_updateEntityTrackersAfterSendingChunkPacket}
 * (E7), where we have no control over which packets vanilla emits.
 *
 * <p>The thread-local-scoped redirect lets us write the chunk-data sync
 * as <pre>{@code withForceRedirect(destDim, () -> {
 *   connection.send(chunkPacket);
 *   ...entity tracker code that calls connection.send internally...
 * })}</pre> and have everything emitted during the block land on the
 * cached level on the client.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplSendMixin {

    @ModifyVariable(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
        at = @At("HEAD"),
        argsOnly = true,
        index = 1,
        require = 1
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Packet<?> seamlessportals$autoWrapForRedirect(Packet<?> originalPacket) {
        ResourceKey<Level> dim = SeamlessPacketRedirection.getForceRedirectDimension();
        if (dim == null) return originalPacket;

        // Don't double-wrap our own redirected payloads. Without this,
        // a force-redirect block could nest with sendRedirected and
        // produce a Payload-of-Payload-of-...-packet onion the client
        // can't decode.
        if (originalPacket instanceof ClientboundCustomPayloadPacket cp
                && cp.payload() instanceof SeamlessPacketRedirection.Payload) {
            return originalPacket;
        }

        // Only ClientGamePacketListener-typed packets are valid through
        // our payload. Other listener types (e.g. configuration) shouldn't
        // be sent through ServerCommonPacketListenerImpl.send during a
        // force-redirect block in the first place; bail safely if they
        // somehow appear.
        Packet inner = originalPacket;
        return SeamlessPacketRedirection.createRedirectedPacket(dim, (Packet<? extends ClientGamePacketListener>) inner);
    }
}
