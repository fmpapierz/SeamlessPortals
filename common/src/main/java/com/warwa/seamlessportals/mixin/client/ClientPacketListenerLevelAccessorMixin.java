package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read/write access to the private {@code level} field on
 * {@link ClientPacketListener}. Used by
 * {@link com.warwa.seamlessportals.network.SeamlessPacketRedirection#withSwitchedWorld}
 * to swap the network handler's level reference around vanilla-packet
 * dispatch — many packet handlers read {@code this.level} and would
 * otherwise apply changes to the active main level instead of the
 * cached secondary level we want to target.
 *
 * <p>Mirrors IP's {@code IEClientPlayNetworkHandler.ip_setWorld(ClientLevel)}
 * duck interface.
 */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerLevelAccessorMixin {

    @Accessor("level")
    ClientLevel seamlessportals$getLevel();

    @Accessor("level")
    @Mutable
    void seamlessportals$setLevel(ClientLevel level);
}
