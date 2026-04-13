package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.portal.PortalDetector;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Unique
    private static boolean seamlessportals$loggedChunkScan = false;
    @Unique
    private static ResourceKey<Level> seamlessportals$lastDimension = null;

    /**
     * When a chunk loads on the client, scan for portal blocks.
     * Portal data persists across dimension changes (following IP architecture).
     */
    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void seamlessportals$onChunkLoaded(ChunkPos chunkPos, CallbackInfo ci) {
        ClientLevel level = (ClientLevel)(Object) this;
        ResourceKey<Level> currentDim = level.dimension();

        // Detect dimension change — portals persist, no clearing
        if (seamlessportals$lastDimension != null && seamlessportals$lastDimension != currentDim) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] Dimension change: {} -> {}",
                seamlessportals$lastDimension.identifier(), currentDim.identifier()
            );
            seamlessportals$loggedChunkScan = false;
        }
        seamlessportals$lastDimension = currentDim;

        // Scan chunk for portal blocks
        LevelChunk chunk = level.getChunk(chunkPos.x(), chunkPos.z());
        if (chunk == null) return;

        for (int sectionIdx = 0; sectionIdx < chunk.getSectionsCount(); sectionIdx++) {
            LevelChunkSection section = chunk.getSection(sectionIdx);
            if (section == null || section.hasOnlyAir()) continue;

            int sectionY = chunk.getSectionYFromSectionIndex(sectionIdx);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.is(Blocks.NETHER_PORTAL)) {
                            BlockPos worldPos = new BlockPos(
                                chunkPos.x() * 16 + x,
                                sectionY * 16 + y,
                                chunkPos.z() * 16 + z
                            );
                            PortalDetector.onNetherPortalDetectedClient(level, worldPos);

                            // Log ALL portal blocks found (not just first)
                            SeamlessPortalsConstants.LOGGER.info(
                                "[SEAMLESS] Portal block at {} axis={} in {}",
                                worldPos,
                                state.getValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS),
                                currentDim.identifier()
                            );
                        }
                    }
                }
            }
        }
    }

    /**
     * ClientLevel.disconnect() fires on BOTH dimension changes AND server disconnect.
     * IP: secondary worlds persist across dimension changes. Only clean up on actual disconnect.
     * We detect actual disconnect by checking if the connection is still active.
     */
    @Inject(method = "disconnect", at = @At("HEAD"))
    private void seamlessportals$onDisconnect(CallbackInfo ci) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();

        // If the connection is gone, this is a real disconnect — clean up everything.
        // If the connection is still active, this is a dimension change — keep secondary worlds.
        if (mc.getConnection() == null) {
            com.warwa.seamlessportals.client.PortalWorldManager.cleanup();
            com.warwa.seamlessportals.client.PortalDimensionManager.cleanup();
            PortalManager.resetClient();
            seamlessportals$loggedChunkScan = false;
            seamlessportals$lastDimension = null;
            com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] Full cleanup on server disconnect");
        } else {
            com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] Dimension change — secondary worlds preserved (IP architecture)");
        }
    }
}
