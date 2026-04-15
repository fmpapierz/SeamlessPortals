package com.warwa.seamlessportals.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages secondary LevelRenderers and ClientLevels for portal rendering.
 *
 * Following the Immersive Portals architecture:
 * - Each remote dimension gets its own LevelRenderer + ClientLevel
 * - LevelRenderer has its own SectionRenderDispatcher (chunk mesh compilation)
 * - RenderBuffers is SHARED (sequential access via context switch)
 * - FeatureRenderDispatcher has SEPARATE instance (has mutable per-frame state)
 * - EntityRenderDispatcher/BlockEntityRenderDispatcher are SHARED
 *
 * Verified constructor signatures from MC 26.1.2 source:
 * - LevelRenderer(Minecraft, EntityRenderDispatcher, BlockEntityRenderDispatcher,
 *                  RenderBuffers, GameRenderState, FeatureRenderDispatcher)
 * - FeatureRenderDispatcher(SubmitNodeStorage, ModelManager, BufferSource,
 *                            AtlasManager, OutlineBufferSource, BufferSource, Font, GameRenderState)
 */
public class PortalWorldManager {

    private static final Map<ResourceKey<Level>, LevelRenderer> renderers = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, ClientLevel> levels = new ConcurrentHashMap<>();

    /**
     * Get or create a LevelRenderer + ClientLevel for the given dimension.
     * Creates the secondary rendering pipeline on first call.
     */
    public static LevelRenderer getOrCreateRenderer(ResourceKey<Level> dimension) {
        return renderers.computeIfAbsent(dimension, PortalWorldManager::createRenderer);
    }

    public static ClientLevel getLevel(ResourceKey<Level> dimension) {
        return levels.get(dimension);
    }

    public static boolean hasRenderer(ResourceKey<Level> dimension) {
        return renderers.containsKey(dimension);
    }

    /**
     * Create a secondary LevelRenderer for a dimension.
     * Follows IP's ClientWorldLoader.createSecondaryClientWorld() pattern.
     */
    private static LevelRenderer createRenderer(ResourceKey<Level> dimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) {
            SeamlessPortalsConstants.LOGGER.error("[SEAMLESS PHASE2] Cannot create renderer - no active game");
            return null;
        }

        SeamlessPortalsConstants.LOGGER.info("[SEAMLESS PHASE2] Creating secondary renderer for {}", dimension.identifier());

        try {
            GameRenderState gameRenderState = mc.gameRenderer.getGameRenderState();

            // Create SEPARATE RenderBuffers for the secondary renderer.
            // The main renderer's buffers are in use during AFTER_TRANSLUCENT_TERRAIN
            // (when our portal rendering runs). Sharing them causes
            // "Buffer source must not be empty" crashes when the 1:1 camera
            // position triggers entity rendering.
            RenderBuffers destRenderBuffers = new RenderBuffers(4);

            // Create SEPARATE FeatureRenderDispatcher (has mutable per-frame state)
            SubmitNodeStorage destSubmitNodes = new SubmitNodeStorage();
            FeatureRenderDispatcher destFeatureDispatcher = new FeatureRenderDispatcher(
                destSubmitNodes,
                mc.getModelManager(),
                destRenderBuffers.bufferSource(),
                mc.getAtlasManager(),
                destRenderBuffers.outlineBufferSource(),
                destRenderBuffers.crumblingBufferSource(),
                mc.font,
                gameRenderState
            );

            // Create secondary LevelRenderer with its OWN RenderBuffers
            LevelRenderer destRenderer = new LevelRenderer(
                mc,
                mc.getEntityRenderDispatcher(),
                mc.getBlockEntityRenderDispatcher(),
                destRenderBuffers,       // SEPARATE — avoids buffer conflicts
                gameRenderState,         // SHARED
                destFeatureDispatcher    // SEPARATE
            );

            // Create secondary ClientLevel
            Holder<DimensionType> dimensionType = mc.level.registryAccess()
                .lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(getDimensionTypeKey(dimension));

            ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(
                Difficulty.NORMAL, false, false
            );

            ClientLevel destLevel = new ClientLevel(
                mc.getConnection(),
                levelData,
                dimension,
                dimensionType,
                8,  // render distance for portal view (matches portalRenderDistance)
                8,  // simulation distance
                destRenderer,
                false,
                0L,
                mc.level.getSeaLevel()
            );

            // Give the secondary renderer its OWN LevelRenderState so that
            // extractLevel() doesn't corrupt the main renderer's shared state.
            // MC 26.1.2 shares LevelRenderState via GameRenderState (line 190),
            // but IP's architecture requires each renderer to have isolated state.
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$setLevelRenderState(
                new LevelRenderState());

            // Connect renderer to level (triggers chunk infrastructure creation)
            destRenderer.setLevel(destLevel);

            // Initialize sky renderer + entity outline target.
            // onResourceManagerReload() creates SkyRenderer (line 218 in LevelRenderer.java).
            // Without this, extractLevel() crashes with NPE on skyRenderer.extractRenderState().
            destRenderer.onResourceManagerReload(mc.getResourceManager());

            levels.put(dimension, destLevel);

            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PHASE2] Secondary renderer created for {} (sections={})",
                dimension.identifier(), destLevel.getSectionsCount()
            );

            return destRenderer;

        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error("[SEAMLESS PHASE2] Failed to create secondary renderer", e);
            return null;
        }
    }

    private static ResourceKey<DimensionType> getDimensionTypeKey(ResourceKey<Level> dimension) {
        if (dimension == Level.NETHER) {
            return ResourceKey.create(Registries.DIMENSION_TYPE,
                Identifier.withDefaultNamespace("the_nether"));
        }
        if (dimension == Level.END) {
            return ResourceKey.create(Registries.DIMENSION_TYPE,
                Identifier.withDefaultNamespace("the_end"));
        }
        return ResourceKey.create(Registries.DIMENSION_TYPE,
            Identifier.withDefaultNamespace("overworld"));
    }

    /**
     * Feed all existing chunks from RemoteChunkManager into the secondary ClientLevel.
     * Called when the secondary renderer is first created, to feed chunks that
     * were received before the renderer existed.
     */
    public static void feedExistingChunks(ResourceKey<Level> dimension) {
        ClientLevel destLevel = levels.get(dimension);
        if (destLevel == null) return;

        var chunks = com.warwa.seamlessportals.chunk.RemoteChunkManager.getChunks(dimension);
        if (chunks == null || chunks.isEmpty()) return;

        net.minecraft.client.multiplayer.ClientChunkCache cache = destLevel.getChunkSource();
        int fed = 0;

        for (var entry : chunks.entrySet()) {
            net.minecraft.world.level.ChunkPos pos = entry.getKey();
            int chunkX = pos.x();
            int chunkZ = pos.z();
            var sections = entry.getValue();

            try {
                // Do NOT call updateViewCenter per-chunk here!
                // View center is set ONCE at the portal destination by tryPhase2Render().
                // Per-chunk updates cause the center to jump, dropping previous chunks.

                io.netty.buffer.ByteBuf rawBuf = io.netty.buffer.Unpooled.buffer();
                net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(rawBuf);
                for (var section : sections) {
                    section.write(buf);
                }
                cache.replaceWithPacketData(chunkX, chunkZ, buf,
                    java.util.Collections.emptyMap(), tag -> {});
                buf.release();
                fed++;
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error(
                    "[SEAMLESS PHASE2] feedExistingChunks: Failed chunk [{},{}]", chunkX, chunkZ, e);
            }
        }

        if (fed > 0) {
            // Apply stored light data to the level (fixes "blue box" / invisible terrain)
            for (var entry : chunks.entrySet()) {
                net.minecraft.world.level.ChunkPos pos = entry.getKey();
                int sectionCount = entry.getValue().length;
                net.minecraft.world.level.chunk.DataLayer[] skyLight =
                    com.warwa.seamlessportals.chunk.RemoteChunkManager.getSkyLight(dimension, pos);
                net.minecraft.world.level.chunk.DataLayer[] blockLight =
                    com.warwa.seamlessportals.chunk.RemoteChunkManager.getBlockLight(dimension, pos);
                if (skyLight != null || blockLight != null) {
                    PortalDimensionManager.applyLightToLevel(
                        destLevel, pos.x(), pos.z(), skyLight, blockLight, sectionCount);
                }
            }

            // Mark ALL sections dirty on the secondary renderer so
            // SectionRenderDispatcher compiles them. ClientChunkCache events
            // go to mc.levelRenderer (main), not our secondary renderer.
            LevelRenderer destRenderer = renderers.get(dimension);
            if (destRenderer != null) {
                for (var entry : chunks.entrySet()) {
                    net.minecraft.world.level.ChunkPos pos = entry.getKey();
                    int sectionCount = entry.getValue().length;
                    int minSectionY = destLevel.getMinSectionY();
                    for (int sy = 0; sy < sectionCount; sy++) {
                        destRenderer.setSectionDirtyWithNeighbors(
                            pos.x(), minSectionY + sy, pos.z());
                    }
                }
            }

            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PHASE2] Fed {} existing chunks + light to level {}", fed, dimension.identifier());
        }
    }

    /**
     * Remove a specific dimension's secondary renderer and level.
     * Called when the player transitions to that dimension (it becomes primary).
     */
    public static void removeRenderer(ResourceKey<Level> dimension) {
        LevelRenderer renderer = renderers.remove(dimension);
        ClientLevel level = levels.remove(dimension);

        if (renderer != null) {
            try {
                renderer.setLevel(null);
                renderer.close();
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error(
                    "[SEAMLESS PHASE2] Error removing renderer for {}", dimension.identifier(), e);
            }
        }
    }

    /**
     * Clean up all secondary renderers and levels.
     */
    public static void cleanup() {
        for (LevelRenderer renderer : renderers.values()) {
            try {
                renderer.setLevel(null);
                renderer.close();
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error("[SEAMLESS PHASE2] Error cleaning up renderer", e);
            }
        }
        renderers.clear();
        levels.clear();
    }
}
