package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * LOCAL-PLAYER RESOLUTION FALLBACK for entity-addressed packets (2026-07-06, the
 * stuck-elytra-glide root cause).
 *
 * <p>Vanilla resolves the target of {@code ClientboundSetEntityDataPacket} and
 * {@code ClientboundUpdateAttributesPacket} via {@code this.level.getEntity(id)}
 * and SILENTLY DROPS the packet on a miss (ClientPacketListener.java:631-637,
 * :2341-2364). Around every client-first crossing there is a window where the
 * LOCAL PLAYER is not resolvable in {@code ClientPacketListener.level}:
 * {@code doVisualSwap} removes it from the outgoing level ~1 RTT before the
 * respawn packet realigns the field, and on the STALE-respawn path
 * {@code this.level} is deliberately a secondary level the live player is never
 * added to. A one-shot server state sync dying in that window is unrecoverable —
 * the proven case: the server's fall-flying clear (shared flag 7). 26.2 has NO
 * client-side glide cancel (no stop packet; the client never self-clears; jump
 * mid-glide sends nothing), so a lost clear = glide stuck on forever, exactly
 * the "can't cancel the elytra glide" bug. The same hole eats sprint/sneak/fire
 * flag bits and attribute syncs.
 *
 * <p>Fix: when the level lookup misses AND the id is the local player's, resolve
 * to {@code mc.player} directly (IP-parity: IP resolves entities across its
 * per-dim client worlds instead of a single level field). Remote entities keep
 * vanilla's drop semantics untouched.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerLocalPlayerFallbackMixin {

    @Redirect(method = "handleSetEntityData",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getEntity(I)Lnet/minecraft/world/entity/Entity;"))
    private Entity seamlessportals$resolveLocalPlayerForEntityData(ClientLevel level, int id) {
        return seamlessportals$resolveWithLocalPlayerFallback(level, id);
    }

    @Redirect(method = "handleUpdateAttributes",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getEntity(I)Lnet/minecraft/world/entity/Entity;"))
    private Entity seamlessportals$resolveLocalPlayerForAttributes(ClientLevel level, int id) {
        return seamlessportals$resolveWithLocalPlayerFallback(level, id);
    }

    private static Entity seamlessportals$resolveWithLocalPlayerFallback(ClientLevel level, int id) {
        Entity entity = level.getEntity(id);
        if (entity != null) return entity;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getId() == id) {
            return player;
        }
        return null;
    }
}
