package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.PortalDimensionManager;
import com.warwa.seamlessportals.client.PortalWorldManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Client-side router for remote dimension chunk data.
 * Following IP architecture: NO intermediate storage.
 * Chunks go directly to the secondary ClientLevel via PortalDimensionManager.
 */
public class RemoteChunkManager {
    private static int totalChunksReceived = 0;

    public static void handleChunkData(String dimensionId, int chunkX, int chunkZ, byte[] data) {
        ResourceKey<Level> dim = parseDimensionKey(dimensionId);
        if (dim == null) {
            SeamlessPortalsConstants.LOGGER.warn("[SEAMLESS] Unknown dimension: {}", dimensionId);
            return;
        }

        try {
            // Route directly to secondary ClientLevel — no intermediate storage
            PortalDimensionManager.loadChunkIntoRemoteLevel(dim, chunkX, chunkZ, data);

            totalChunksReceived++;
            if (totalChunksReceived % 50 == 0) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS] Total remote chunks received: {}", totalChunksReceived);
            }
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error("[SEAMLESS] Failed to process chunk [{}, {}] from {}",
                chunkX, chunkZ, dimensionId, e);
        }
    }

    public static void handleChunkUnload(String dimensionId, int chunkX, int chunkZ) {
        // Chunk unloads are handled by the secondary ClientLevel's ChunkCache
        // No intermediate storage to clean up
    }

    /**
     * Check if chunks are available for a dimension by querying the secondary ClientLevel.
     */
    public static boolean hasDimensionData(ResourceKey<Level> dimension) {
        ClientLevel level = PortalWorldManager.getLevel(dimension);
        return level != null && level.getChunkSource().getLoadedChunksCount() > 0;
    }

    /**
     * Get chunk count from the secondary ClientLevel.
     */
    public static int getChunkCount(ResourceKey<Level> dimension) {
        ClientLevel level = PortalWorldManager.getLevel(dimension);
        return level != null ? level.getChunkSource().getLoadedChunksCount() : 0;
    }

    private static ResourceKey<Level> parseDimensionKey(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> Level.OVERWORLD;
            case "minecraft:the_nether" -> Level.NETHER;
            case "minecraft:the_end" -> Level.END;
            default -> null;
        };
    }

    public static void clearAll() {
        totalChunksReceived = 0;
    }
}
