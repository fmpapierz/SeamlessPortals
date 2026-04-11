package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side manager for remote dimension chunk data.
 * Receives serialized chunk sections from the server and stores them
 * for rendering through portals.
 *
 * DEBUG: Logs all chunk reception, deserialization, and access.
 */
public class RemoteChunkManager {
    // Dimension -> ChunkPos -> sections array
    private static final Map<ResourceKey<Level>, Map<ChunkPos, LevelChunkSection[]>> remoteSections = new ConcurrentHashMap<>();
    private static boolean loggedFirstReceive = false;
    private static int totalChunksReceived = 0;

    public static void handleChunkData(String dimensionId, int chunkX, int chunkZ, byte[] data) {
        ResourceKey<Level> dim = parseDimensionKey(dimensionId);
        if (dim == null) {
            SeamlessPortalsConstants.LOGGER.warn("[SEAMLESS] Unknown dimension: {}", dimensionId);
            return;
        }

        try {
            // Route through PortalDimensionManager to load into a real ClientLevel
            com.warwa.seamlessportals.client.PortalDimensionManager.loadChunkIntoRemoteLevel(
                dim, chunkX, chunkZ, data);

            totalChunksReceived++;
            if (totalChunksReceived % 50 == 0) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS] Remote chunks received: {} for {}",
                    getChunkCount(dim), dimensionId
                );
            }
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error("[SEAMLESS] Failed to process chunk [{}, {}] from {}",
                chunkX, chunkZ, dimensionId, e);
        }
    }

    /**
     * Store pre-deserialized sections (called from PortalDimensionManager).
     */
    public static void storeDeserializedSections(ResourceKey<Level> dimension,
                                                   int chunkX, int chunkZ,
                                                   LevelChunkSection[] sections) {
        Map<ChunkPos, LevelChunkSection[]> dimChunks = remoteSections.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        dimChunks.put(new ChunkPos(chunkX, chunkZ), sections);
    }

    /**
     * Deserialize chunk sections using vanilla's exact deserialization format.
     */
    private static LevelChunkSection[] deserializeSections(byte[] data) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            int sectionCount = buf.readVarInt();
            LevelChunkSection[] sections = new LevelChunkSection[sectionCount];

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return null;

            // Use vanilla's PalettedContainerFactory to create sections with correct registries
            PalettedContainerFactory factory = PalettedContainerFactory.create(mc.level.registryAccess());

            for (int i = 0; i < sectionCount; i++) {
                sections[i] = new LevelChunkSection(factory);
                sections[i].read(buf);
            }

            return sections;
        } finally {
            buf.release();
        }
    }

    /**
     * Debug: log the contents of the first non-empty section.
     */
    private static void debugLogSectionContents(LevelChunkSection[] sections) {
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!section.hasOnlyAir()) {
                // Sample a few blocks from this section
                StringBuilder sb = new StringBuilder();
                sb.append("  Section ").append(i).append(" (non-empty, blocks: ").append(section.getSerializedSize()).append(" bytes): ");
                int sampled = 0;
                for (int x = 0; x < 16 && sampled < 5; x++) {
                    for (int y = 0; y < 16 && sampled < 5; y++) {
                        for (int z = 0; z < 16 && sampled < 5; z++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (!state.isAir()) {
                                sb.append(state.getBlock().getName().getString()).append(", ");
                                sampled++;
                            }
                        }
                    }
                }
                SeamlessPortalsConstants.LOGGER.info("[SEAMLESS DEBUG] {}", sb.toString());
                return; // Only log first non-empty section
            }
        }
    }

    /**
     * Get the block state at a specific world position in the remote dimension.
     */
    public static BlockState getRemoteBlockState(ResourceKey<Level> dimension, BlockPos pos) {
        Map<ChunkPos, LevelChunkSection[]> dimChunks = remoteSections.get(dimension);
        if (dimChunks == null) return null;

        ChunkPos chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        LevelChunkSection[] sections = dimChunks.get(chunkPos);
        if (sections == null) return null;

        // Calculate section index using the REMOTE dimension's min Y, not current world's.
        // Nether/End: 16 sections starting at Y=0 (minSectionY=0)
        // Overworld: 24 sections starting at Y=-64 (minSectionY=-4)
        int minSectionY = getMinSectionY(dimension, sections.length);
        int sectionIndex = (pos.getY() >> 4) - minSectionY;

        if (sectionIndex < 0 || sectionIndex >= sections.length) return null;

        LevelChunkSection section = sections[sectionIndex];
        int localX = pos.getX() & 15;
        int localY = pos.getY() & 15;
        int localZ = pos.getZ() & 15;

        return section.getBlockState(localX, localY, localZ);
    }

    /**
     * Check if we have chunks loaded for a given dimension.
     */
    public static boolean hasDimensionData(ResourceKey<Level> dimension) {
        Map<ChunkPos, LevelChunkSection[]> dimChunks = remoteSections.get(dimension);
        return dimChunks != null && !dimChunks.isEmpty();
    }

    /**
     * Get all loaded chunks for a dimension.
     */
    public static Map<ChunkPos, LevelChunkSection[]> getChunks(ResourceKey<Level> dimension) {
        return remoteSections.getOrDefault(dimension, Collections.emptyMap());
    }

    /**
     * Get the number of loaded chunks for a dimension.
     */
    public static int getChunkCount(ResourceKey<Level> dimension) {
        Map<ChunkPos, LevelChunkSection[]> dimChunks = remoteSections.get(dimension);
        return dimChunks != null ? dimChunks.size() : 0;
    }

    public static void handleChunkUnload(String dimensionId, int chunkX, int chunkZ) {
        ResourceKey<Level> dim = parseDimensionKey(dimensionId);
        if (dim == null) return;

        Map<ChunkPos, LevelChunkSection[]> dimChunks = remoteSections.get(dim);
        if (dimChunks != null) {
            dimChunks.remove(new ChunkPos(chunkX, chunkZ));
        }
    }

    /**
     * Compute the min section Y for a remote dimension based on section count.
     * Overworld: 24 sections, minY=-64, minSectionY=-4
     * Nether/End: 16 sections, minY=0, minSectionY=0
     */
    private static int getMinSectionY(ResourceKey<Level> dimension, int sectionCount) {
        if (dimension == Level.NETHER || dimension == Level.END) {
            return 0; // Y starts at 0
        }
        if (dimension == Level.OVERWORLD) {
            return -4; // Y starts at -64
        }
        // For unknown dimensions, guess based on section count
        if (sectionCount == 24) return -4; // Overworld-like
        return 0; // Default
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
        remoteSections.clear();
        loggedFirstReceive = false;
        totalChunksReceived = 0;
    }

    public static RemoteClientLevel getRemoteLevel(ResourceKey<Level> dimension) {
        // For now, return null - we'll implement this later when we need full ClientLevel
        return null;
    }
}
