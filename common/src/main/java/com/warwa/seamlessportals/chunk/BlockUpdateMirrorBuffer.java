package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side per-tick coalescing buffer for the live-portal-view block mirror.
 *
 * <p>Vanilla never sends one packet per block change — it batches a section's changes
 * into one {@code ClientboundSectionBlocksUpdatePacket} per tick. The mod's mirror
 * ({@link com.warwa.seamlessportals.mixin.LevelChunkSetBlockStateMixin}) previously sent
 * one {@link ModPayloads.RemoteBlockUpdatePayload} per block change per watching player,
 * so flowing lava/fluid produced ~1000 tiny packets/sec (and ~1000 per-block section
 * rebuilds on the client render thread) — the persistent post-teleport stutter.
 *
 * <p>This buffers each update by (player, dim, packedPos) during the tick — the map
 * dedups repeated changes to the same block (fluid level oscillation) to the LATEST
 * state — then {@link #flush(MinecraftServer)} (called at server tick end) sends ONE
 * {@link ModPayloads.RemoteBlockUpdateBatchPayload} per player per dim and clears.
 *
 * <p>Single-threaded: {@link #add} is only called from {@code LevelChunk.setBlockState}
 * on the server main thread (the mixin guards {@code server.isSameThread()}), and
 * {@link #flush} runs on the same thread at tick end — so no synchronization is needed.
 */
public final class BlockUpdateMirrorBuffer {

    private BlockUpdateMirrorBuffer() {}

    // player UUID -> dim -> (packedPos -> blockStateId). Long2Int dedups by position.
    private static final Map<UUID, Map<ResourceKey<Level>, Long2IntLinkedOpenHashMap>> buffer =
        new HashMap<>();

    /** Queue a block-state change to mirror to {@code player}'s cached view of {@code dim}. */
    public static void add(ServerPlayer player, ResourceKey<Level> dim, long packedPos, int blockStateId) {
        buffer.computeIfAbsent(player.getUUID(), k -> new HashMap<>())
              .computeIfAbsent(dim, k -> new Long2IntLinkedOpenHashMap())
              .put(packedPos, blockStateId);
    }

    /** Send each player's coalesced updates as one batch per dim, then clear. Server-thread only. */
    public static void flush(MinecraftServer server) {
        if (buffer.isEmpty()) return;
        for (Map.Entry<UUID, Map<ResourceKey<Level>, Long2IntLinkedOpenHashMap>> playerEntry : buffer.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
            if (player == null) continue;
            for (Map.Entry<ResourceKey<Level>, Long2IntLinkedOpenHashMap> dimEntry : playerEntry.getValue().entrySet()) {
                Long2IntLinkedOpenHashMap updates = dimEntry.getValue();
                if (updates.isEmpty()) continue;
                List<Long> positions = new ArrayList<>(updates.size());
                List<Integer> stateIds = new ArrayList<>(updates.size());
                for (Long2IntLinkedOpenHashMap.Entry e : updates.long2IntEntrySet()) {
                    positions.add(e.getLongKey());
                    stateIds.add(e.getIntValue());
                }
                PlatformHelper.getInstance().sendToClient(player,
                    new ModPayloads.RemoteBlockUpdateBatchPayload(
                        dimEntry.getKey().identifier().toString(), positions, stateIds));
            }
        }
        buffer.clear();
    }

    /** Drop a disconnected player's pending updates. */
    public static void clearPlayer(UUID playerId) {
        buffer.remove(playerId);
    }

    public static void clear() {
        buffer.clear();
    }
}
