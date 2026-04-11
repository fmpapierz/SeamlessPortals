package com.warwa.seamlessportals.chunk;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public class RemoteChunkData {
    private final int chunkX;
    private final int chunkZ;
    private final byte[] rawData;
    private boolean decoded = false;
    private boolean ready = false;

    // Block data decoded from the raw chunk bytes
    private int[][] heightmap;
    private short[][][] blockIds;

    public RemoteChunkData(int chunkX, int chunkZ, byte[] rawData) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.rawData = rawData;
        decode();
    }

    private void decode() {
        try {
            // Decode the chunk data from vanilla serialization format
            // The raw data contains section data with block states and biomes
            // For now, mark as decoded - full deserialization will use
            // vanilla's chunk codec when integrated with the network layer
            decoded = true;
            ready = true;
        } catch (Exception e) {
            decoded = false;
            ready = false;
        }
    }

    public void render(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, Vec3 cameraPos) {
        if (!ready) return;

        // Render this chunk's geometry
        // This will be implemented to either:
        // 1. Build a vertex buffer from block data and render it
        // 2. Use the vanilla chunk rendering system with our remote level
        // The actual implementation hooks into LevelRenderer's chunk compile system
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isDecoded() {
        return decoded;
    }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public byte[] getRawData() { return rawData; }

    public void cleanup() {
        // Release any GPU resources (vertex buffers etc)
        ready = false;
    }
}
