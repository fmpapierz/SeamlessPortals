package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mutable accessor for {@link ClientPacketListener}'s private {@code level}
 * field (declared {@code private ClientLevel level;} in 26.2).
 *
 * <p>Phase 4c (IP {@code PacketRedirection} architecture): a redirected vanilla
 * {@code ClientboundLevelChunkWithLightPacket} routes ALL of its work through
 * {@code this.level} ({@code updateLevelChunk → this.level.getChunkSource()
 * .replaceWithPacketData} and {@code this.level.queueLightUpdate}). To make that
 * handler apply to a DESTINATION dimension's {@code ClientLevel} instead of the
 * active one, we temporarily swap this field around {@code packet.handle(...)} —
 * exactly IP's {@code ClientWorldLoader.withSwitchedWorld} →
 * {@code IEClientPlayNetworkHandler.ip_setWorld}. The field is non-final, so a
 * plain {@code @Accessor} get/set suffices (no {@code @Mutable} needed).
 */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessorMixin {

    @Accessor("level")
    ClientLevel seamlessportals$getLevel();

    @Accessor("level")
    void seamlessportals$setLevel(ClientLevel level);

    /** The server's view distance as last told to this client (drives the vanilla
     *  ClientChunkCache storage radius at level construction). Promotion of a
     *  mod-created secondary level must resize its chunk cache to this — the
     *  secondary was built at portalRenderDistance (default 8), and a smaller
     *  storage silently discards every vanilla chunk send beyond it. */
    @Accessor("serverChunkRadius")
    int seamlessportals$getServerChunkRadius();
}
