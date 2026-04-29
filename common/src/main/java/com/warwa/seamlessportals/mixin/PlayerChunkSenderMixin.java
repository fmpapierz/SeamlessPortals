package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.chunk.SeamlessChunkTrackingGraph;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E11 — Suppress vanilla's redundant chunk-send for chunks that
 * {@link SeamlessChunkTrackingGraph} has already delivered to the
 * player via {@link com.warwa.seamlessportals.network.SeamlessPacketRedirection}.
 *
 * <p><b>Why this exists.</b> When a player approaches an OW→nether
 * portal, the chunk-tracking graph indirectly loads + redirects the
 * destination nether chunks to the player's cached nether
 * {@link net.minecraft.client.multiplayer.ClientLevel}. The client
 * processes the chunks on the cached level and builds meshes there.
 * On teleport, the cached nether is promoted to {@code mc.level}
 * (via {@code SeamlessClientTeleport}); the meshes are intact and
 * rendering correctly.
 *
 * <p>Then vanilla's {@link PlayerChunkSender} sees the player in the
 * new dim (nether) and queues the entire RD-radius worth of nether
 * chunks for delivery — including all the chunks we already sent. Each
 * delivery causes
 * {@link net.minecraft.client.multiplayer.ClientChunkCache#replaceWithPacketData}
 * to overwrite the existing chunk → fires {@code setSectionDirty} for
 * every section → invalidates all of the meshes the player was just
 * rendering. The cached-promoted level effectively re-loads from
 * scratch:
 * <ul>
 *   <li>X-ray cave-vision while the async mesh rebuild catches up.</li>
 *   <li>10-second freezes when 200+ sections rebuild concurrently
 *       and saturate the render thread's GL upload bandwidth.</li>
 *   <li>Network bytes for chunks the client already has.</li>
 * </ul>
 *
 * <p>The fix: at the bottom of {@code PlayerChunkSender.sendChunk}'s
 * static helper, before vanilla actually serializes + sends the chunk
 * packet, check whether our graph has a {@code wasSentViaRedirect}
 * latch for this {@code (player, dim, chunkPos)} triple. If yes, we
 * already delivered it — drop vanilla's redundant send.
 *
 * <p>Note we DON'T also drop the {@code markChunkPendingToSend} call —
 * the chunk stays in the {@code pendingChunks} {@link
 * it.unimi.dsi.fastutil.longs.LongSet}; vanilla's {@code sendNextChunks}
 * batches will simply skip it via this mixin and remove it from the
 * set normally. Keeps the rest of vanilla's chunk-send bookkeeping
 * intact.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {

    @Inject(
        method = "sendChunk(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private static void seamlessportals$skipIfAlreadyRedirected(
            ServerGamePacketListenerImpl connection,
            ServerLevel level,
            LevelChunk chunk,
            CallbackInfo ci) {
        ServerPlayer player = connection.player;
        if (player == null) return;
        ChunkPos pos = chunk.getPos();
        long gameTime = level.getServer() == null
            ? 0L
            : level.getServer().overworld().getGameTime();
        if (SeamlessChunkTrackingGraph.wasChunkSentViaRedirect(
                player, level.dimension(), pos.x(), pos.z(), gameTime)) {
            // Player's client already has this chunk via redirected
            // delivery within the suppression window. Sending again
            // would invalidate the existing meshes — skip.
            ci.cancel();
        }
    }
}
