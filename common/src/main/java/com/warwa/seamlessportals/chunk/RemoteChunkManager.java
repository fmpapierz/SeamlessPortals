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

import net.minecraft.world.level.chunk.DataLayer;

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
    // Light data stored alongside sections (for backfill when secondary renderer is created later)
    private static final Map<ResourceKey<Level>, Map<ChunkPos, DataLayer[]>> remoteSkyLight = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Map<ChunkPos, DataLayer[]>> remoteBlockLight = new ConcurrentHashMap<>();
    private static boolean loggedFirstReceive = false;
    private static int totalChunksReceived = 0;

    /**
     * Queue of pending chunk packets to process. After a dimension change the
     * server bursts ~289 chunks per portal within a single tick; processing
     * them all synchronously in handleChunkData used to freeze the render
     * thread for 4-5 seconds. We enqueue here and drain a small batch per
     * client tick via {@link #drainPending}.
     */
    private record PendingChunk(ResourceKey<Level> dim, int chunkX, int chunkZ, byte[] data) {}
    private static final java.util.concurrent.ConcurrentLinkedQueue<PendingChunk> pendingChunks =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Max chunks processed per client tick. ~5ms per chunk → 8 = ~40ms/tick worst case. */
    private static final int CHUNKS_PER_TICK = 8;

    public static void handleChunkData(String dimensionId, int chunkX, int chunkZ, byte[] data) {
        ResourceKey<Level> dim = parseDimensionKey(dimensionId);
        if (dim == null) {
            SeamlessPortalsConstants.LOGGER.warn("[SEAMLESS] Unknown dimension: {}", dimensionId);
            return;
        }

        // Enqueue — DON'T process here. Processing is deferred to drainPending()
        // which runs in ClientTickEvents, spreading the work across multiple
        // frames so no single frame exceeds the 16ms target.
        pendingChunks.add(new PendingChunk(dim, chunkX, chunkZ, data));
    }

    /**
     * Called once per client tick. Processes up to {@link #CHUNKS_PER_TICK}
     * pending chunks, OR until 6ms of wall-clock time has been spent — whichever
     * comes first. Keeps individual tick costs well under the render budget.
     */
    public static void drainPending() {
        long drainStart = System.nanoTime();
        final long BUDGET_NS = 6_000_000L;
        int processed = 0;
        while (processed < CHUNKS_PER_TICK && (System.nanoTime() - drainStart) < BUDGET_NS) {
            PendingChunk pc = pendingChunks.poll();
            if (pc == null) break;
            try {
                com.warwa.seamlessportals.client.PortalDimensionManager.loadChunkIntoRemoteLevel(
                    pc.dim, pc.chunkX, pc.chunkZ, pc.data);
                totalChunksReceived++;
                if (totalChunksReceived % 50 == 0) {
                    SeamlessPortalsConstants.rlog(
                        "[SEAMLESS] Remote chunks received: {} for {} (pending: {})",
                        getChunkCount(pc.dim), pc.dim.identifier(), pendingChunks.size()
                    );
                }
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error(
                    "[SEAMLESS] Failed to process chunk [{}, {}] from {}",
                    pc.chunkX, pc.chunkZ, pc.dim.identifier(), e);
            }
            processed++;
        }
    }

    /**
     * Store pre-deserialized sections and light data (called from PortalDimensionManager).
     */
    public static void storeDeserializedSections(ResourceKey<Level> dimension,
                                                   int chunkX, int chunkZ,
                                                   LevelChunkSection[] sections) {
        Map<ChunkPos, LevelChunkSection[]> dimChunks = remoteSections.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        dimChunks.put(new ChunkPos(chunkX, chunkZ), sections);
    }

    /**
     * Store light DataLayers for a chunk. Used for backfill when the secondary
     * renderer is created after chunks have already arrived.
     */
    public static void storeLightData(ResourceKey<Level> dimension,
                                       int chunkX, int chunkZ,
                                       DataLayer[] skyLight, DataLayer[] blockLight) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        if (skyLight != null) {
            remoteSkyLight.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>()).put(pos, skyLight);
        }
        if (blockLight != null) {
            remoteBlockLight.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>()).put(pos, blockLight);
        }
    }

    public static DataLayer[] getSkyLight(ResourceKey<Level> dimension, ChunkPos pos) {
        Map<ChunkPos, DataLayer[]> map = remoteSkyLight.get(dimension);
        return map != null ? map.get(pos) : null;
    }

    public static DataLayer[] getBlockLight(ResourceKey<Level> dimension, ChunkPos pos) {
        Map<ChunkPos, DataLayer[]> map = remoteBlockLight.get(dimension);
        return map != null ? map.get(pos) : null;
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
                SeamlessPortalsConstants.rlog("[SEAMLESS DEBUG] {}", sb.toString());
                return; // Only log first non-empty section
            }
        }
    }

    /**
     * Get the block state at a specific world position in the remote dimension.
     */
    public static BlockState getRemoteBlockState(ResourceKey<Level> dimension, BlockPos pos) {
        // Phase 4c: the dest chunks now live in the real secondary ClientLevel
        // (landed by the redirected vanilla packet handler), not the snapshot
        // store. Query the live level directly.
        net.minecraft.client.multiplayer.ClientLevel level =
            com.warwa.seamlessportals.client.PortalWorldManager.getLevel(dimension);
        if (level == null) return null;
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;
        return level.getBlockState(pos);
    }

    /**
     * Check if we have chunks loaded for a given dimension.
     * Phase 4c: queries the live secondary ClientLevel's chunk cache.
     */
    public static boolean hasDimensionData(ResourceKey<Level> dimension) {
        net.minecraft.client.multiplayer.ClientLevel level =
            com.warwa.seamlessportals.client.PortalWorldManager.getLevel(dimension);
        return level != null && level.getChunkSource().getLoadedChunksCount() > 0;
    }

    /**
     * Get all loaded chunks for a dimension (legacy snapshot store; empty under
     * Phase 4c — kept only for the dormant feed methods).
     */
    public static Map<ChunkPos, LevelChunkSection[]> getChunks(ResourceKey<Level> dimension) {
        return remoteSections.getOrDefault(dimension, Collections.emptyMap());
    }

    /**
     * Get the number of loaded chunks for a dimension.
     * Phase 4c: the live secondary ClientLevel's loaded-chunk count.
     */
    public static int getChunkCount(ResourceKey<Level> dimension) {
        net.minecraft.client.multiplayer.ClientLevel level =
            com.warwa.seamlessportals.client.PortalWorldManager.getLevel(dimension);
        return level != null ? level.getChunkSource().getLoadedChunksCount() : 0;
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
        remoteSkyLight.clear();
        remoteBlockLight.clear();
        loggedFirstReceive = false;
        totalChunksReceived = 0;
    }

    public static RemoteClientLevel getRemoteLevel(ResourceKey<Level> dimension) {
        // For now, return null - we'll implement this later when we need full ClientLevel
        return null;
    }
}
