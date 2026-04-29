package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.network.SeamlessPacketRedirection;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * E6 — Translates {@link SeamlessChunkTrackingGraph} watch signals
 * into actual chunk-data + forget packets sent via
 * {@link SeamlessPacketRedirection}.
 *
 * <p>Mirrors IP 1.19's {@code ChunkDataSyncManager}, trimmed:
 * <ul>
 *   <li>{@code onBeginWatch} — for indirect (cross-dim) records only,
 *       fetch the chunk from the destination
 *       {@link ServerLevel#getChunkSource} and send a
 *       {@link ClientboundLevelChunkWithLightPacket} wrapped via
 *       {@link SeamlessPacketRedirection#withForceRedirect}. If the
 *       chunk hasn't generated yet (the loading ticket was just added
 *       and gen takes time), defer the send to the next tick via
 *       {@link #pendingDelivery}.</li>
 *   <li>{@code onEndWatch} — for indirect records, send a redirected
 *       {@link ClientboundForgetLevelChunkPacket} so the cached
 *       {@link net.minecraft.client.multiplayer.ClientLevel} drops
 *       the chunk.</li>
 *   <li>{@link #tick} — drain {@link #pendingDelivery} once per server
 *       tick. For each pending entry, retry the chunk-data send. If
 *       still not ready after {@link #PENDING_TIMEOUT_TICKS}, drop the
 *       entry — the next watch refresh will re-queue.</li>
 * </ul>
 *
 * <p><b>Why not also send for direct loaders?</b> Direct loaders are
 * own-dim chunks, owned end-to-end by vanilla's
 * {@link net.minecraft.server.network.PlayerChunkSender}. Vanilla
 * decides "this chunk is in player's view distance" and sends. If we
 * also sent (via redirection), the client would receive two chunk
 * packets for the same position — vanilla's normal one and our
 * wrapped one. The wrapped one would land on the cached level (via
 * handleRedirectedPacket dispatching to cached) and cause a
 * section-count mismatch when the cached and active dims have
 * different section counts.
 */
public final class SeamlessChunkDataSync {

    private SeamlessChunkDataSync() {}

    /** Ticks before a deferred-delivery entry is dropped as failed. */
    public static final int PENDING_TIMEOUT_TICKS = 600; // 30 s

    /**
     * Per-player pending deliveries: {@code playerUUID → (dimChunkPos →
     * lastAttemptTick)}. Entries are added when the chunk wasn't ready
     * at begin-watch time. {@link #tick} retries each tick — but only
     * up to {@link #MAX_DEFERRED_RETRIES_PER_TICK} entries to avoid
     * the runaway-iteration symptom that produced the 27-second
     * freeze (every tick was iterating 27,000 deferred entries).
     */
    private static final Map<UUID, Map<DimChunkPos, Long>> pendingDelivery = new HashMap<>();

    /**
     * Cap on deferred-delivery retries per tick. Empirical: with
     * radius=12, 28 teleports stacked 27k deferred entries; per-tick
     * iteration over all of them was the dominant cost in the
     * 27-second freeze. With this cap, server-thread work for the
     * deferred drain is bounded regardless of pending-set size.
     */
    private static final int MAX_DEFERRED_RETRIES_PER_TICK = 200;

    private static volatile boolean registered = false;

    /**
     * Register signal listeners with {@link SeamlessChunkTrackingGraph}.
     * Idempotent — call from mod init.
     */
    public static void init() {
        if (registered) return;
        registered = true;
        SeamlessChunkTrackingGraph.beginWatchChunkSignal.add(SeamlessChunkDataSync::onBeginWatch);
        SeamlessChunkTrackingGraph.endWatchChunkSignal.add(SeamlessChunkDataSync::onEndWatch);
        SeamlessPortalsConstants.LOGGER.info("[SEAMLESS DATA-SYNC] Listeners registered");
    }

    private static void onBeginWatch(ServerPlayer player, DimChunkPos pos, boolean isDirect) {
        // FULL IP PORT: send for both direct + indirect. Vanilla's
        // per-tick updateChunkTracking is suppressed by
        // ChunkMapTickMixin's surgical redirect, but the JOIN call
        // (from updatePlayerStatus) still fires — so vanilla
        // sends the initial RD chunks via PlayerChunkSender. Our
        // graph then ALSO sends them via redirect (with active-dim
        // dispatch on the client), which would double-send except
        // PlayerChunkSenderMixin suppresses vanilla's redundant
        // resends after our graph's latch is set.
        //
        // For chunks beyond the initial-join radius (player walks),
        // vanilla can't queue new chunks (the per-tick updateChunkTracking
        // is no-op'd), so our graph IS the only source. Direct send
        // is required.
        attemptSend(player, pos, /* fromDeferredDrain= */ false);
    }

    private static void onEndWatch(ServerPlayer player, DimChunkPos pos, boolean isDirect) {
        // FULL IP PORT: send forget for both direct + indirect.
        try {
            SeamlessPacketRedirection.sendRedirected(
                player, pos.dimension(),
                new ClientboundForgetLevelChunkPacket(pos.getChunkPos()));
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.debug(
                "[SEAMLESS DATA-SYNC] forget send failed for {} {}: {}",
                player.getName().getString(), pos, t.toString());
        }

        // Also drop any pending-delivery entry — no need to retry.
        Map<DimChunkPos, Long> playerPending = pendingDelivery.get(player.getUUID());
        if (playerPending != null) {
            playerPending.remove(pos);
            if (playerPending.isEmpty()) pendingDelivery.remove(player.getUUID());
        }
    }

    /**
     * Attempt to fetch + send the chunk for {@code pos} to {@code player}.
     * Returns true if the send succeeded. If the chunk isn't ready,
     * adds a deferred entry (unless we're already in the deferred drain).
     */
    private static boolean attemptSend(ServerPlayer player, DimChunkPos pos, boolean fromDeferredDrain) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return false;
        ServerLevel destLevel = server.getLevel(pos.dimension());
        if (destLevel == null) return false;

        LevelChunk chunk = destLevel.getChunkSource().getChunkNow(pos.x(), pos.z());
        if (chunk == null) {
            if (!fromDeferredDrain) {
                Map<DimChunkPos, Long> playerPending = pendingDelivery.computeIfAbsent(
                    player.getUUID(), u -> new HashMap<>());
                playerPending.put(pos, server.overworld().getGameTime());
            }
            return false;
        }

        try {
            SeamlessPacketRedirection.withForceRedirect(pos.dimension(), () -> {
                player.connection.send(new ClientboundLevelChunkWithLightPacket(
                    chunk, destLevel.getLightEngine(), null, null));
                // E7 will hook entity-tracker updates here so cross-dim
                // entities also get spawn packets in the cached level.
                // For now, the chunk's terrain travels alone.
            });
            // Latch the record with the current tick so
            // PlayerChunkSenderMixin (E11) can suppress vanilla's
            // redundant post-teleport re-send for the configured
            // window. After the window elapses, vanilla is allowed
            // to re-send for recovery from any client-side drops.
            long gameTime = server.overworld().getGameTime();
            SeamlessChunkTrackingGraph.markChunkRedirectSent(
                player, pos.dimension(), pos.packedPos(), gameTime);
            return true;
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.debug(
                "[SEAMLESS DATA-SYNC] chunk send failed for {} {}: {}",
                player.getName().getString(), pos, t.toString());
            return false;
        }
    }

    /**
     * Per-server-tick drain of pending-delivery entries. For each entry
     * whose chunk has now generated, send. For entries timed out, drop.
     *
     * <p>Caps work at {@link #MAX_DEFERRED_RETRIES_PER_TICK} retries
     * per tick — without this cap, deferred entries iterate at O(N)
     * per tick where N can grow into the tens of thousands during
     * heavy teleport play, dominating the server thread.
     */
    public static void tick(MinecraftServer server) {
        if (server == null || pendingDelivery.isEmpty()) return;
        long gameTime = server.overworld().getGameTime();
        int retries = 0;

        Iterator<Map.Entry<UUID, Map<DimChunkPos, Long>>> playerIt =
            pendingDelivery.entrySet().iterator();
        while (playerIt.hasNext() && retries < MAX_DEFERRED_RETRIES_PER_TICK) {
            Map.Entry<UUID, Map<DimChunkPos, Long>> e = playerIt.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                playerIt.remove();
                continue;
            }
            Map<DimChunkPos, Long> entries = e.getValue();
            Iterator<Map.Entry<DimChunkPos, Long>> entryIt = entries.entrySet().iterator();
            while (entryIt.hasNext() && retries < MAX_DEFERRED_RETRIES_PER_TICK) {
                Map.Entry<DimChunkPos, Long> entry = entryIt.next();
                DimChunkPos pos = entry.getKey();
                long firstAttempt = entry.getValue();
                retries++;

                if (player.level().dimension().equals(pos.dimension())) {
                    entryIt.remove();
                    continue;
                }

                if (gameTime - firstAttempt > PENDING_TIMEOUT_TICKS) {
                    entryIt.remove();
                    continue;
                }
                if (attemptSend(player, pos, true)) {
                    entryIt.remove();
                }
            }
            if (entries.isEmpty()) playerIt.remove();
        }
    }

    public static void clear() {
        pendingDelivery.clear();
    }
}
