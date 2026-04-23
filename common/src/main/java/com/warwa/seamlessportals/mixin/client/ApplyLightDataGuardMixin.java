package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Guard against NPE in {@code ClientPacketListener.applyLightData} when the
 * listener's {@code level} field is null.
 *
 * <p>Background: {@code ClientLevel.update} per-frame drains queued light
 * updates via {@code pollLightUpdates}. Lambdas queued by
 * {@code handleLightUpdatePacket} / {@code handleLevelChunkWithLight} run at
 * that time and call {@code applyLightData}, which reads
 * {@code this.level.getChunkSource()}. During our client-first portal
 * crossing, {@link HandleRespawnMixin} briefly rewrites
 * {@code ClientPacketListener.level} via {@code @Shadow} as part of the
 * idempotent respawn handling. If a queued light lambda runs inside that
 * window, {@code this.level} can be null — unhandled by vanilla, so it
 * crashes the game.
 *
 * <p>Sister mixin to
 * {@link com.warwa.seamlessportals.mixin.client.ChunkPacketGuardMixin},
 * which guards the analogous chunk-apply path. Cheap no-op on the one
 * race-losing frame; no effect in normal operation.
 */
@Mixin(ClientPacketListener.class)
public abstract class ApplyLightDataGuardMixin {

    @Shadow private net.minecraft.client.multiplayer.ClientLevel level;

    @Inject(
        method = "applyLightData(IILnet/minecraft/network/protocol/game/ClientboundLightUpdatePacketData;Z)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void seamlessportals$guardNullLevel(
            int x, int z, ClientboundLightUpdatePacketData lightData, boolean scheduleRebuild, CallbackInfo ci) {
        if (this.level == null) {
            // Listener is mid-respawn — the cached ClientLevel has been
            // temporarily detached. Skip this queued light update; it
            // targets a level that's no longer current anyway.
            ci.cancel();
        }
    }
}
