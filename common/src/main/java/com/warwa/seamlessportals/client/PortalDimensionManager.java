package com.warwa.seamlessportals.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
public class PortalDimensionManager {
    private static final Map<ResourceKey<Level>, ClientLevel> remoteLevels = new ConcurrentHashMap<>();
    private static boolean loggedCreation = false;
    /** Track which dimensions have had their view center set (only set once per dimension) */
    private static final java.util.Set<ResourceKey<Level>> viewCenterSet = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Get or create a ClientLevel for a remote dimension.
     * The level is created with the same parameters as vanilla uses
     * during dimension changes.
     */
    public static ClientLevel getOrCreateRemoteLevel(ResourceKey<Level> dimension) {
        return remoteLevels.computeIfAbsent(dimension, PortalDimensionManager::createRemoteLevel);
    }

    public static ClientLevel getRemoteLevel(ResourceKey<Level> dimension) {
        return remoteLevels.get(dimension);
    }

    public static boolean hasRemoteLevel(ResourceKey<Level> dimension) {
        return remoteLevels.containsKey(dimension);
    }

    /**
     * Create a new ClientLevel for a remote dimension.
     * Uses the same constructor as vanilla's handleRespawn.
     */
    private static ClientLevel createRemoteLevel(ResourceKey<Level> dimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) {
            SeamlessPortalsConstants.LOGGER.error(
                "[SEAMLESS] Cannot create remote level - no active connection");
            return null;
        }

        // Get the DimensionType for this dimension from the registry
        Holder<DimensionType> dimensionType = mc.level.registryAccess()
            .lookupOrThrow(Registries.DIMENSION_TYPE)
            .getOrThrow(getDimensionTypeKey(dimension));

        // Create level data (minimal - we don't need full game state)
        ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(
            Difficulty.NORMAL, false, false
        );

        // Create the ClientLevel with vanilla's constructor
        // We pass the existing LevelRenderer - we won't use it for rendering
        // (we'll context-switch the main one instead)
        ClientLevel remoteLevel = new ClientLevel(
            mc.getConnection(),
            levelData,
            dimension,
            dimensionType,
            8,    // serverChunkRadius - matches portalRenderDistance
            8,    // serverSimulationDistance
            mc.levelRenderer,  // shared - won't use directly
            false,  // isDebug
            0L,  // biomeZoomSeed - not critical for portal rendering
            mc.level.getSeaLevel()
        );

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] Created remote ClientLevel for dimension: {}, sections: {}",
            dimension.identifier(), remoteLevel.getSectionsCount()
        );

        return remoteLevel;
    }

    /**
     * Map dimension ResourceKey to its DimensionType ResourceKey.
     */
    private static ResourceKey<DimensionType> getDimensionTypeKey(ResourceKey<Level> dimension) {
        if (dimension == Level.NETHER) {
            return ResourceKey.create(Registries.DIMENSION_TYPE,
                net.minecraft.resources.Identifier.withDefaultNamespace("the_nether"));
        }
        if (dimension == Level.END) {
            return ResourceKey.create(Registries.DIMENSION_TYPE,
                net.minecraft.resources.Identifier.withDefaultNamespace("the_end"));
        }
        // Default to overworld
        return ResourceKey.create(Registries.DIMENSION_TYPE,
            net.minecraft.resources.Identifier.withDefaultNamespace("overworld"));
    }

    /**
     * Feed a chunk into the remote level using vanilla's chunk deserialization.
     * This creates a proper LevelChunk with correct block states, biomes, etc.
     */
    public static void loadChunkIntoRemoteLevel(ResourceKey<Level> dimension,
                                                  int chunkX, int chunkZ,
                                                  byte[] sectionData) {
        ClientLevel remoteLevel = getOrCreateRemoteLevel(dimension);
        if (remoteLevel == null) return;

        try {
            // Deserialize: section count + section data + light data
            io.netty.buffer.ByteBuf rawBuf = io.netty.buffer.Unpooled.wrappedBuffer(sectionData);
            net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(rawBuf);

            int sectionCount = buf.readVarInt();
            net.minecraft.world.level.chunk.LevelChunkSection[] sections =
                new net.minecraft.world.level.chunk.LevelChunkSection[sectionCount];

            net.minecraft.world.level.chunk.PalettedContainerFactory factory =
                net.minecraft.world.level.chunk.PalettedContainerFactory.create(remoteLevel.registryAccess());

            for (int i = 0; i < sectionCount; i++) {
                sections[i] = new net.minecraft.world.level.chunk.LevelChunkSection(factory);
                sections[i].read(buf);
            }

            // Read sky + block light DataLayers (sent by PortalChunkTracker)
            // Following IP architecture: full light data ensures correct rendering
            int minSectionY = remoteLevel.getMinSectionY();
            net.minecraft.world.level.chunk.DataLayer[] skyLightData =
                new net.minecraft.world.level.chunk.DataLayer[sectionCount];
            net.minecraft.world.level.chunk.DataLayer[] blockLightData =
                new net.minecraft.world.level.chunk.DataLayer[sectionCount];

            // Read sky light
            for (int i = 0; i < sectionCount; i++) {
                if (buf.isReadable() && buf.readBoolean()) {
                    byte[] data = new byte[2048];
                    buf.readBytes(data);
                    skyLightData[i] = new net.minecraft.world.level.chunk.DataLayer(data);
                }
            }
            // Read block light
            for (int i = 0; i < sectionCount; i++) {
                if (buf.isReadable() && buf.readBoolean()) {
                    byte[] data = new byte[2048];
                    buf.readBytes(data);
                    blockLightData[i] = new net.minecraft.world.level.chunk.DataLayer(data);
                }
            }

            buf.release();

            // Apply light data to the remote level's LightEngine.
            // Following vanilla's ClientPacketListener flow (lines 920-931):
            // 1. Queue section data for each light layer
            // 2. Call runLightUpdates() to force-apply queued DataLayers
            //    (queueSectionData only puts into queuedSections map;
            //     runLightUpdates → swapSectionMap applies them to storage)
            // Without runLightUpdates(), light data sits in queue forever
            // because the secondary level's LightEngine is never ticked.
            net.minecraft.world.level.lighting.LevelLightEngine lightEngine =
                remoteLevel.getLightEngine();
            net.minecraft.world.level.ChunkPos chunkPos =
                new net.minecraft.world.level.ChunkPos(chunkX, chunkZ);

            // Enable light processing for this chunk
            lightEngine.retainData(chunkPos, true);

            for (int i = 0; i < sectionCount; i++) {
                net.minecraft.core.SectionPos sectionPos =
                    net.minecraft.core.SectionPos.of(chunkX, minSectionY + i, chunkZ);

                // Update section status so light engine knows section exists
                lightEngine.updateSectionStatus(sectionPos, false);

                if (skyLightData[i] != null) {
                    lightEngine.queueSectionData(
                        net.minecraft.world.level.LightLayer.SKY, sectionPos, skyLightData[i]);
                }
                if (blockLightData[i] != null) {
                    lightEngine.queueSectionData(
                        net.minecraft.world.level.LightLayer.BLOCK, sectionPos, blockLightData[i]);
                }
            }

            // Force-apply queued light data (processes queue → swapSectionMap)
            lightEngine.runLightUpdates();

            // Set view center ONCE per dimension (first chunk sets the center)
            // All subsequent chunks load relative to this center.
            if (!viewCenterSet.contains(dimension)) {
                ClientLevel secondaryLevel = PortalWorldManager.getLevel(dimension);
                if (secondaryLevel != null) {
                    secondaryLevel.getChunkSource().updateViewCenter(chunkX, chunkZ);
                    viewCenterSet.add(dimension);
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS PHASE2] View center set to [{},{}] for {}",
                        chunkX, chunkZ, dimension.identifier());
                }
            }

            // Store in our RemoteChunkManager (for the colored-block renderer / Phase 1)
            com.warwa.seamlessportals.chunk.RemoteChunkManager.storeDeserializedSections(
                dimension, chunkX, chunkZ, sections);

            // Phase 2: Also feed into the secondary ClientLevel's chunk cache
            feedSectionsToSecondaryLevel(dimension, chunkX, chunkZ, sections);

            if (!loggedCreation) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS] First chunk loaded into remote level {}: [{},{}] ({} sections)",
                    dimension.identifier(), chunkX, chunkZ, sectionCount
                );
                loggedCreation = true;
            }
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error(
                "[SEAMLESS] Failed to load chunk [{},{}] into remote level {}",
                chunkX, chunkZ, dimension.identifier(), e);
        }
    }

    /**
     * Feed chunk sections into the secondary ClientLevel's ClientChunkCache.
     * This allows the secondary LevelRenderer's SectionRenderDispatcher to
     * compile chunk meshes for Phase 2 rendering.
     *
     * The sections are re-serialized to FriendlyByteBuf format, which is what
     * ClientChunkCache.replaceWithPacketData() expects (same as vanilla chunk packets).
     */
    private static void feedSectionsToSecondaryLevel(
            ResourceKey<Level> dimension, int chunkX, int chunkZ,
            net.minecraft.world.level.chunk.LevelChunkSection[] sections) {
        ClientLevel destLevel = PortalWorldManager.getLevel(dimension);
        if (destLevel == null) return;

        try {
            net.minecraft.client.multiplayer.ClientChunkCache cache = destLevel.getChunkSource();

            // Do NOT call updateViewCenter per-chunk — it moves the center each time,
            // causing previously loaded chunks to fall out of range.
            // The view center is set ONCE in doRenderGroupRender() at the portal destination.

            // Re-serialize sections to FriendlyByteBuf (vanilla chunk packet format)
            io.netty.buffer.ByteBuf rawBuf = io.netty.buffer.Unpooled.buffer();
            net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(rawBuf);

            for (net.minecraft.world.level.chunk.LevelChunkSection section : sections) {
                section.write(buf);
            }

            // Feed into the secondary level's chunk cache
            cache.replaceWithPacketData(
                chunkX, chunkZ, buf,
                java.util.Collections.emptyMap(),
                tag -> {} // no block entities for now
            );

            buf.release();

            // Mark sections dirty on the secondary renderer so it compiles chunk meshes.
            // ClientChunkCache events go to mc.levelRenderer (main), not ours.
            net.minecraft.client.renderer.LevelRenderer destRenderer =
                PortalWorldManager.getOrCreateRenderer(dimension);
            if (destRenderer != null) {
                int minSectionY = destLevel.getMinSectionY();
                for (int sy = 0; sy < sections.length; sy++) {
                    destRenderer.setSectionDirtyWithNeighbors(
                        chunkX, minSectionY + sy, chunkZ);
                }
            }
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error(
                "[SEAMLESS PHASE2] Failed to feed chunk [{},{}] to secondary level {}",
                chunkX, chunkZ, dimension.identifier(), e);
        }
    }

    /**
     * Clean up all remote levels.
     */
    public static void cleanup() {
        for (ClientLevel level : remoteLevels.values()) {
            level.disconnect(net.minecraft.network.chat.Component.literal("Portal cleanup"));
        }
        remoteLevels.clear();
        viewCenterSet.clear();
        loggedCreation = false;
    }
}
