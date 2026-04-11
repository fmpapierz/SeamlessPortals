package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RemoteClientLevel {
    private final ResourceKey<Level> dimension;
    private final Map<ChunkPos, RemoteChunkData> chunks = new ConcurrentHashMap<>();
    private long dayTime = 0;
    private boolean needsRebuild = true;

    public RemoteClientLevel(ResourceKey<Level> dimension) {
        this.dimension = dimension;
        SeamlessPortalsConstants.LOGGER.debug("Created remote client level for {}", dimension.identifier());
    }

    public void loadChunk(int chunkX, int chunkZ, byte[] data) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        RemoteChunkData chunkData = new RemoteChunkData(chunkX, chunkZ, data);
        chunks.put(pos, chunkData);
        needsRebuild = true;

        SeamlessPortalsConstants.LOGGER.trace("Loaded remote chunk [{}, {}] in {}",
            chunkX, chunkZ, dimension.identifier());
    }

    public void unloadChunk(int chunkX, int chunkZ) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        RemoteChunkData removed = chunks.remove(pos);
        if (removed != null) {
            removed.cleanup();
            needsRebuild = true;
        }
    }

    public boolean hasChunks() {
        return !chunks.isEmpty();
    }

    public boolean hasChunk(int chunkX, int chunkZ) {
        return chunks.containsKey(new ChunkPos(chunkX, chunkZ));
    }

    public RemoteChunkData getChunk(int chunkX, int chunkZ) {
        return chunks.get(new ChunkPos(chunkX, chunkZ));
    }

    public void renderChunks(Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                              Vec3 cameraPos, float partialTick) {
        for (RemoteChunkData chunk : chunks.values()) {
            if (chunk.isReady()) {
                chunk.render(modelViewMatrix, projectionMatrix, cameraPos);
            }
        }
        needsRebuild = false;
    }

    public long getDayTime() {
        return dayTime;
    }

    public void setDayTime(long dayTime) {
        this.dayTime = dayTime;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public int getChunkCount() {
        return chunks.size();
    }

    public void cleanup() {
        chunks.values().forEach(RemoteChunkData::cleanup);
        chunks.clear();
    }
}
