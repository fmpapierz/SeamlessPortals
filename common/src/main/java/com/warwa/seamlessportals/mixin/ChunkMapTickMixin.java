package com.warwa.seamlessportals.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Surgical replacement for the per-tick {@code updateChunkTracking}
 * call inside {@link ChunkMap#tick()}. Replaces it with a no-op so
 * our {@link com.warwa.seamlessportals.chunk.SeamlessChunkTrackingGraph}
 * is the sole source of per-tick chunk-tracking refresh — but leaves
 * the rest of {@code tick()} untouched, including:
 *
 * <ul>
 *   <li>The per-entity tracker section-pos-change loop (lines 1205-1222
 *       in vanilla 26.1.2 ChunkMap), which fires
 *       {@code trackedEntity.serverEntity.sendChanges()} per tick to
 *       keep entity positions synced to clients.</li>
 *   <li>The {@code movedPlayers} fan-out (lines 1224-1228) which
 *       updates entity trackers when a player moves to a new section.</li>
 * </ul>
 *
 * <p>Cancelling those broke world join (loading-terrain hang) because
 * entity sync stops working for the local player at spawn. Surgical
 * redirect keeps them alive.
 *
 * <p><b>Critical: the {@code updateChunkTracking} call from
 * {@code updatePlayerStatus(player, true)} (player join / dim-change)
 * is NOT redirected.</b> That call needs to fire normally so:
 * <ul>
 *   <li>{@code applyChunkTrackingView} runs → sends
 *       {@code ClientboundSetChunkCacheCenterPacket} (without which the
 *       client's chunk-cache view-center stays at default; vanilla's
 *       {@code ClientChunkCache.replaceWithPacketData} would reject all
 *       chunks via {@code inRange} — though our SeamlessClientChunkMap
 *       overrides updateViewCenter to no-op so this is moot in
 *       practice).</li>
 *   <li>{@code markChunkPendingToSend} fills vanilla's
 *       {@link net.minecraft.server.network.PlayerChunkSender} pending
 *       queue with the player's initial RD-radius chunk set, so vanilla
 *       handles the join-flood and the "loading terrain" screen
 *       dismisses promptly.</li>
 * </ul>
 *
 * <p>This redirect targets the specific INVOKE inside the tick loop;
 * vanilla's only other call site is the {@code updatePlayerStatus}
 * branch which is unaffected. After this mixin lands, our graph can
 * safely take over per-tick chunk-tracking work + adaptive radius
 * pre-loading without competing with vanilla.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapTickMixin {

    @Redirect(
        method = "tick()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;)V"
        ),
        require = 1
    )
    private void seamlessportals$skipPerTickUpdateChunkTracking(
            ChunkMap self, ServerPlayer player) {
        // No-op. Our graph (SeamlessChunkTrackingGraph) handles
        // per-tick chunk-tracking refresh. This redirect ONLY affects
        // the tick-loop call site; the join-time call from
        // updatePlayerStatus runs normally.
    }
}
