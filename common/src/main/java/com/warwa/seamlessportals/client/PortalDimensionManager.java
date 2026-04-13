package com.warwa.seamlessportals.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.portal.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads remote chunk data directly into secondary ClientLevels.
 *
 * View center management (matching IP's ClientWorldLoader):
 * - handlePortalSync sets view center at REAL portal position (authoritative)
 * - If sync hasn't arrived, compute from client-side portal link destination
 * - PortalContextSwitch updates view center at camera position each frame
 */
public class PortalDimensionManager {
    private static final Set<ResourceKey<Level>> loggedDimensions = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceKey<Level>> viewCenterInitialized = ConcurrentHashMap.newKeySet();

    /**
     * Called from handlePortalSync to authoritatively set view center from real positions.
     */
    public static void setViewCenterFromSync(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        ClientLevel level = PortalWorldManager.getLevel(dimension);
        if (level != null) {
            level.getChunkSource().updateViewCenter(chunkX, chunkZ);
            viewCenterInitialized.add(dimension);
            SeamlessPortalsConstants.LOGGER.info(
                "[VIEWCENTER] Set at [{},{}] for {} (from portal sync — authoritative)",
                chunkX, chunkZ, dimension.identifier());
        }
    }

    public static void loadChunkIntoRemoteLevel(ResourceKey<Level> dimension,
                                                  int chunkX, int chunkZ,
                                                  byte[] sectionData) {
        LevelRenderer destRenderer = PortalWorldManager.getOrCreateRenderer(dimension);
        ClientLevel destLevel = PortalWorldManager.getLevel(dimension);
        if (destRenderer == null || destLevel == null) return;

        try {
            // Ensure view center is set BEFORE feeding any chunk.
            // IP: ClientWorldLoader sets view center at portal destination.
            if (!viewCenterInitialized.contains(dimension)) {
                // Sync hasn't arrived yet. Use the portal link destination position
                // from the client's PortalManager (set by either sync or chunk-scan detection).
                BlockPos destPos = findPortalDestPosition(dimension);
                if (destPos != null) {
                    destLevel.getChunkSource().updateViewCenter(destPos.getX() >> 4, destPos.getZ() >> 4);
                    viewCenterInitialized.add(dimension);
                    SeamlessPortalsConstants.LOGGER.info(
                        "[VIEWCENTER] Set at [{},{}] for {} (from portal link destination)",
                        destPos.getX() >> 4, destPos.getZ() >> 4, dimension.identifier());
                } else {
                    // No link exists yet. Use the chunk position as last resort.
                    // Server sends chunks centered at portal dest, so this is close.
                    destLevel.getChunkSource().updateViewCenter(chunkX, chunkZ);
                    viewCenterInitialized.add(dimension);
                    SeamlessPortalsConstants.LOGGER.info(
                        "[VIEWCENTER] Set at [{},{}] for {} (from first chunk — no link yet)",
                        chunkX, chunkZ, dimension.identifier());
                }
            }

            // Deserialize sections from server packet
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

            // Read light data
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

            // Apply light data
            net.minecraft.world.level.lighting.LevelLightEngine lightEngine = destLevel.getLightEngine();
            net.minecraft.world.level.ChunkPos chunkPos = new net.minecraft.world.level.ChunkPos(chunkX, chunkZ);
            lightEngine.retainData(chunkPos, true);

            for (int i = 0; i < sectionCount; i++) {
                net.minecraft.core.SectionPos sectionPos =
                    net.minecraft.core.SectionPos.of(chunkX, minSectionY + i, chunkZ);
                lightEngine.updateSectionStatus(sectionPos, false);
                if (skyLightData[i] != null)
                    lightEngine.queueSectionData(net.minecraft.world.level.LightLayer.SKY, sectionPos, skyLightData[i]);
                if (blockLightData[i] != null)
                    lightEngine.queueSectionData(net.minecraft.world.level.LightLayer.BLOCK, sectionPos, blockLightData[i]);
            }
            lightEngine.runLightUpdates();

            // Feed into ClientChunkCache
            net.minecraft.client.multiplayer.ClientChunkCache cache = destLevel.getChunkSource();
            io.netty.buffer.ByteBuf reserBuf = io.netty.buffer.Unpooled.buffer();
            net.minecraft.network.FriendlyByteBuf reserFbuf = new net.minecraft.network.FriendlyByteBuf(reserBuf);
            for (net.minecraft.world.level.chunk.LevelChunkSection section : sections)
                section.write(reserFbuf);
            cache.replaceWithPacketData(chunkX, chunkZ, reserFbuf, java.util.Collections.emptyMap(), tag -> {});
            reserFbuf.release();

            // Mark dirty on secondary renderer
            for (int sy = 0; sy < sections.length; sy++)
                destRenderer.setSectionDirtyWithNeighbors(chunkX, minSectionY + sy, chunkZ);

            if (!loggedDimensions.contains(dimension)) {
                loggedDimensions.add(dimension);
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS] First chunk loaded into {} [{},{}] ({} sections, loaded={})",
                    dimension.identifier(), chunkX, chunkZ, sectionCount,
                    destLevel.getChunkSource().getLoadedChunksCount());
            }
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error(
                "[SEAMLESS] Failed to load chunk [{},{}] into {}", chunkX, chunkZ, dimension.identifier(), e);
        }
    }

    /**
     * Find the portal destination position for a dimension from the client's portal links.
     * Searches all links that point INTO this dimension.
     */
    private static BlockPos findPortalDestPosition(ResourceKey<Level> dimension) {
        PortalManager pm = PortalManager.getClientInstance();
        PortalTracker tracker = pm.getTracker(dimension);
        for (PortalInfo portal : tracker.getAllPortals()) {
            return portal.getOrigin(); // First portal in this dimension = the destination
        }
        return null;
    }

    public static void cleanup() {
        loggedDimensions.clear();
        viewCenterInitialized.clear();
    }
}
