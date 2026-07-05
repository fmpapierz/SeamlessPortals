package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.client.SeamlessClientTeleport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Absorbs {@code ClientboundForgetLevelChunkPacket} during the short window
 * after a seamless crossing.
 *
 * <p>Why: at the crossing, vanilla drops the player from the OLD dim's chunk
 * tracking, sending a forget packet for EVERY previously tracked chunk
 * (ChunkMap.applyChunkTrackingView(EMPTY) → PlayerChunkSender.dropChunk). The
 * client has already swapped to the NEW dim, so those forgets execute against
 * the ACTIVE level — and old-dim chunk coordinates can ALIAS new-dim positions
 * near the portal (overworld x/8 ≈ nether x), wrongly unloading
 * freshly-promoted chunks. Previously vanilla's full re-send immediately
 * repainted the damage (masking it inside the crossing stutter); with the
 * re-send now suppressed for client-held chunks
 * ({@code ChunkMapResendSuppressMixin}), a wrong drop would leave a lasting
 * hole — so the stale forgets are absorbed instead.
 *
 * <p>A legitimately-forgotten chunk (sprinting at the view edge inside the
 * window) self-heals: the server re-marks it pending-to-send as soon as its
 * tracking view re-enters it.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerForgetGuardMixin {

    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$absorbStaleForgets(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        if (SeamlessClientTeleport.isInPostSwapWindow()) {
            ci.cancel();
        }
    }
}
