package com.warwa.seamlessportals.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Manages secondary ClientLevel instances for remote dimensions.
 * This is the foundation of the portal rendering system.
 *
 * Each remote dimension gets its own ClientLevel with its own
 * ClientChunkCache. Chunks received from the server are fed
 * into these levels using vanilla's deserialization pipeline.
 *
 * For rendering, we context-switch: temporarily swap Minecraft.level
 * to the remote level, render through the portal, then swap back.
 */
/**
 * Handles chunk deserialization and loading for remote dimensions.
 *
 * Following IP's architecture: ONE ClientLevel per dimension, managed by
 * PortalWorldManager. This class handles deserialization of chunk data
 * received from the server and feeds it into the unified level.
 */
public class PortalDimensionManager {
    private static boolean loggedCreation = false;

    /**
     * Load a chunk into the SINGLE ClientLevel for this dimension.
     *
     * Following IP's architecture: ONE ClientLevel per dimension, shared between
     * chunk loading and rendering. PortalWorldManager owns the level + renderer.
     * We deserialize sections + light, feed both into that single level,
     * and store in RemoteChunkManager for Phase 1 fallback + backfill.
     */
    public static void loadChunkIntoRemoteLevel(ResourceKey<Level> dimension,
                                                  int chunkX, int chunkZ,
                                                  byte[] sectionData) {
        // ACTIVE-DIM GUARD (mirrors RedirectedPacketApplier): a stale in-flight
        // snapshot for the dim the player JUST entered must not re-create a
        // duplicate level+renderer for the ACTIVE dim (promoteToMain removed the
        // map entry; getOrCreateRenderer below would silently fabricate one,
        // breaking the "active dim not in the levels map" invariant that the
        // block-mirror and entity appliers rely on for their stale-payload drop).
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null && mc.level.dimension().equals(dimension)) return;

        // Get the SINGLE level from PortalWorldManager (creates level + renderer if needed)
        PortalWorldManager.getOrCreateRenderer(dimension);
        ClientLevel destLevel = PortalWorldManager.getLevel(dimension);
        if (destLevel == null) return;

        try {
            // Deserialize: section count + section data + light data
            io.netty.buffer.ByteBuf rawBuf = io.netty.buffer.Unpooled.wrappedBuffer(sectionData);
            net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(rawBuf);

            int sectionCount = buf.readVarInt();
            net.minecraft.world.level.chunk.LevelChunkSection[] sections =
                new net.minecraft.world.level.chunk.LevelChunkSection[sectionCount];

            net.minecraft.world.level.chunk.PalettedContainerFactory factory =
                net.minecraft.world.level.chunk.PalettedContainerFactory.create(destLevel.registryAccess());

            for (int i = 0; i < sectionCount; i++) {
                sections[i] = new net.minecraft.world.level.chunk.LevelChunkSection(factory);
                sections[i].read(buf);
            }

            // Read sky + block light DataLayers (sent by PortalChunkTracker)
            int minSectionY = destLevel.getMinSectionY();
            net.minecraft.world.level.chunk.DataLayer[] skyLightData =
                new net.minecraft.world.level.chunk.DataLayer[sectionCount];
            net.minecraft.world.level.chunk.DataLayer[] blockLightData =
                new net.minecraft.world.level.chunk.DataLayer[sectionCount];

            for (int i = 0; i < sectionCount; i++) {
                if (buf.isReadable() && buf.readBoolean()) {
                    byte[] data = new byte[2048];
                    buf.readBytes(data);
                    skyLightData[i] = new net.minecraft.world.level.chunk.DataLayer(data);
                }
            }
            for (int i = 0; i < sectionCount; i++) {
                if (buf.isReadable() && buf.readBoolean()) {
                    byte[] data = new byte[2048];
                    buf.readBytes(data);
                    blockLightData[i] = new net.minecraft.world.level.chunk.DataLayer(data);
                }
            }

            buf.release();

            // Store sections + light in RemoteChunkManager (for Phase 1 fallback + backfill)
            com.warwa.seamlessportals.chunk.RemoteChunkManager.storeDeserializedSections(
                dimension, chunkX, chunkZ, sections);
            com.warwa.seamlessportals.chunk.RemoteChunkManager.storeLightData(
                dimension, chunkX, chunkZ, skyLightData, blockLightData);

            // Feed chunk data into the level's ClientChunkCache
            net.minecraft.client.multiplayer.ClientChunkCache cache = destLevel.getChunkSource();
            io.netty.buffer.ByteBuf chunkBuf = io.netty.buffer.Unpooled.buffer();
            net.minecraft.network.FriendlyByteBuf chunkPacket =
                new net.minecraft.network.FriendlyByteBuf(chunkBuf);
            for (net.minecraft.world.level.chunk.LevelChunkSection section : sections) {
                section.write(chunkPacket);
            }
            cache.replaceWithPacketData(chunkX, chunkZ, chunkPacket,
                java.util.Collections.emptyMap(), tag -> {});
            chunkPacket.release();

            // Apply light data to the SAME level's LightEngine
            // Following vanilla's ClientPacketListener flow:
            // queueSectionData + runLightUpdates to force-apply.
            applyLightToLevel(destLevel, chunkX, chunkZ, skyLightData, blockLightData, sectionCount);

            // Mark sections dirty on the secondary renderer for mesh compilation.
            // ClientChunkCache events go to mc.levelRenderer (main), not ours.
            // 26.2: setSectionDirtyWithNeighbors moved off LevelRenderer onto the
            // dimension's LevelExtractor (D3).
            PortalWorldManager.getOrCreateRenderer(dimension);
            net.minecraft.client.renderer.extract.LevelExtractor destExtractor =
                PortalWorldManager.getExtractor(dimension);
            if (destExtractor != null) {
                for (int sy = 0; sy < sectionCount; sy++) {
                    destExtractor.setSectionDirtyWithNeighbors(chunkX, minSectionY + sy, chunkZ);
                }
            }

            if (!loggedCreation) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS] Chunk loaded into unified level {}: [{},{}] ({} sections, light applied)",
                    dimension.identifier(), chunkX, chunkZ, sectionCount);
                loggedCreation = true;
            }
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error(
                "[SEAMLESS] Failed to load chunk [{},{}] into level {}",
                chunkX, chunkZ, dimension.identifier(), e);
        }
    }

    /**
     * Apply light DataLayers to a level's LightEngine.
     * Used both during initial chunk load and during backfill.
     */
    public static void applyLightToLevel(ClientLevel level, int chunkX, int chunkZ,
                                          net.minecraft.world.level.chunk.DataLayer[] skyLight,
                                          net.minecraft.world.level.chunk.DataLayer[] blockLight,
                                          int sectionCount) {
        net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
        net.minecraft.world.level.ChunkPos chunkPos = new net.minecraft.world.level.ChunkPos(chunkX, chunkZ);
        int minSectionY = level.getMinSectionY();

        lightEngine.retainData(chunkPos, true);

        for (int i = 0; i < sectionCount; i++) {
            net.minecraft.core.SectionPos sectionPos =
                net.minecraft.core.SectionPos.of(chunkX, minSectionY + i, chunkZ);
            lightEngine.updateSectionStatus(sectionPos, false);

            if (skyLight != null && i < skyLight.length && skyLight[i] != null) {
                lightEngine.queueSectionData(
                    net.minecraft.world.level.LightLayer.SKY, sectionPos, skyLight[i]);
            }
            if (blockLight != null && i < blockLight.length && blockLight[i] != null) {
                lightEngine.queueSectionData(
                    net.minecraft.world.level.LightLayer.BLOCK, sectionPos, blockLight[i]);
            }
        }

        lightEngine.runLightUpdates();
    }

    /**
     * Clean up state. Actual level cleanup is handled by PortalWorldManager.
     */
    public static void cleanup() {
        loggedCreation = false;
    }
}
