package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.PortalWorldManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client-side applier for {@link com.warwa.seamlessportals.network.ModPayloads.RemoteBlockUpdatePayload}.
 *
 * <p>Applies a block-state change to a dim's cached {@link ClientLevel}
 * (managed by {@link PortalWorldManager}) and marks the matching cached
 * {@link LevelRenderer}'s section dirty so the portal-view FBO render picks
 * up the change on its next frame. This is the client half of the Phase 1
 * live-portal-view feature (blocks only; entities deferred).
 *
 * <p>Silently ignores updates for dims we have no cached level/renderer for
 * — if the player never rendered a portal into that dim, we have nothing
 * to update.
 */
public final class RemoteBlockUpdater {

    private RemoteBlockUpdater() {}

    private static int applyCount = 0;

    public static void apply(String dimensionId, long packedPos, int blockStateId) {
        ResourceKey<Level> dim = parseDimensionKey(dimensionId);
        if (dim == null) {
            return;
        }

        ClientLevel cachedLevel = PortalWorldManager.getLevel(dim);
        if (cachedLevel == null) {
            // Dim not cached client-side; nothing visible through a portal.
            return;
        }

        BlockPos pos = BlockPos.of(packedPos);
        BlockState newState = Block.stateById(blockStateId);
        if (newState == null) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS LIVE] Unknown block state id {} for dim {}", blockStateId, dimensionId);
            return;
        }

        // Chunk must be loaded on the client for the update to hit anything
        // visible. If not, we drop silently — the next full chunk-data
        // payload (PortalChunkTracker) will carry the fresh state.
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        if (!cachedLevel.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return;
        }

        // Apply to the cached level. Using flag 0 = no neighbor updates, no
        // rerender scheduling by the vanilla event chain (we drive the
        // renderer mark-dirty ourselves below, and skip simulation since
        // this level is dormant).
        cachedLevel.setBlock(pos, newState, 0);

        // Drive the cached LevelRenderer (if any) to rebuild the section
        // mesh covering pos + its neighbors. This is how the block shows
        // up in the next portal-view FBO render.
        if (PortalWorldManager.hasRenderer(dim)) {
            LevelRenderer renderer = PortalWorldManager.getOrCreateRenderer(dim);
            if (renderer != null) {
                int sx = SectionPos.blockToSectionCoord(pos.getX());
                int sy = SectionPos.blockToSectionCoord(pos.getY());
                int sz = SectionPos.blockToSectionCoord(pos.getZ());
                renderer.setSectionDirtyWithNeighbors(sx, sy, sz);
            }
        }

        // Mirror the change into the RemoteChunkManager snapshot so
        // subsequent code paths that read from that snapshot (e.g. portal
        // view reads, remote block queries for oblique clipping, etc.)
        // see the updated state.
        updateRemoteSection(dim, pos, newState);

        applyCount++;
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS LIVE CLIENT] apply #{}: {} → {} at {} in {} (rendererDirty={})",
            applyCount,
            // Read the state we just wrote, to confirm the setBlock stuck.
            cachedLevel.getBlockState(pos).getBlock().getName().getString(),
            newState.getBlock().getName().getString(),
            pos.toShortString(), dimensionId,
            PortalWorldManager.hasRenderer(dim));
    }

    /**
     * Patch the {@link RemoteChunkManager} section snapshot at {@code pos}
     * to {@code newState}. Keeps the snapshot consistent with the cached
     * {@link ClientLevel} so callers of
     * {@link RemoteChunkManager#getRemoteBlockState} see live values.
     */
    private static void updateRemoteSection(
            ResourceKey<Level> dim, BlockPos pos, BlockState newState) {
        var chunks = RemoteChunkManager.getChunks(dim);
        if (chunks == null || chunks.isEmpty()) return;
        var sections = chunks.get(
            new net.minecraft.world.level.ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        if (sections == null) return;

        int minSectionY = computeMinSectionY(dim, sections.length);
        int sectionIndex = (pos.getY() >> 4) - minSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return;

        int lx = pos.getX() & 15;
        int ly = pos.getY() & 15;
        int lz = pos.getZ() & 15;
        sections[sectionIndex].setBlockState(lx, ly, lz, newState);
    }

    private static int computeMinSectionY(ResourceKey<Level> dim, int sectionCount) {
        if (dim == Level.NETHER || dim == Level.END) return 0;
        if (dim == Level.OVERWORLD) return -4;
        return sectionCount == 24 ? -4 : 0;
    }

    private static ResourceKey<Level> parseDimensionKey(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> Level.OVERWORLD;
            case "minecraft:the_nether" -> Level.NETHER;
            case "minecraft:the_end" -> Level.END;
            default -> null;
        };
    }
}
