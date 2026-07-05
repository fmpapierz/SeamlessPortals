package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

/** Static invoker for the private {@code setValuesFromPositionPacket}, so
 *  {@link ClientPacketListenerTeleportToleranceMixin}'s redirect can call the
 *  vanilla behaviour through on the non-skip path. */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerPositionInvoker {

    @Invoker("setValuesFromPositionPacket")
    static boolean seamlessportals$invokeSetValues(
            PositionMoveRotation change, Set<Relative> relatives, Entity entity, boolean interpolate) {
        throw new AssertionError("mixin invoker not applied");
    }
}
