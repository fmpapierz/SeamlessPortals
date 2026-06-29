package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.portal.PortalDetector;
import com.warwa.seamlessportals.portal.PortalManager;
import com.warwa.seamlessportals.render.StencilPortalRenderer;
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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    /**
     * Bypass {@code ClientLevel.doAddParticle}'s main-camera distance gate while
     * spawning a cached destination dimension's ambient particles.
     *
     * <p>Vanilla only creates a non-override particle when it is within 32 blocks
     * of {@code mc.gameRenderer.mainCamera()}
     * ({@code camera.position().distanceToSqr(x,y,z) > 1024.0} → skip). During
     * {@link com.warwa.seamlessportals.client.PortalWorldManager#tickCachedParticles}
     * the main camera is the SOURCE world's, but the particles spawn at the
     * DESTINATION world's coordinates — always far away (or another dimension) —
     * so every flame/lava/portal/fog particle would be culled before creation.
     * The {@code animateTick} ±32 radius already bounds them around the dest view,
     * so we report distance 0 (in range) for those spawns only. The main world's
     * spawns ({@code spawningDestParticles == false}) keep vanilla behavior.
     */
    @Redirect(
        method = "doAddParticle",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(DDD)D"
        )
    )
    private double seamlessportals$bypassDistanceForDestParticles(
            net.minecraft.world.phys.Vec3 cameraPos, double x, double y, double z) {
        if (com.warwa.seamlessportals.client.PortalWorldManager.spawningDestParticles) {
            return 0.0;
        }
        return cameraPos.distanceToSqr(x, y, z);
    }

    @Unique
    private static boolean seamlessportals$loggedChunkScan = false;
    @Unique
    private static ResourceKey<Level> seamlessportals$lastDimension = null;

    /**
     * When a chunk loads on the PRIMARY client level, scan for portal blocks.
     *
     * CRITICAL: Only runs on the PRIMARY level (mc.level). Secondary levels
     * (created by PortalWorldManager for portal rendering) MUST be ignored.
     * IP doesn't have this problem because IP manages secondary levels through
     * a completely separate path. Without this guard, feeding nether chunks to
     * the secondary level would trigger "dimension change detected" → clear
     * the portal links that the server just sent → broken portal view.
     */
    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void seamlessportals$onChunkLoaded(ChunkPos chunkPos, CallbackInfo ci) {
        ClientLevel level = (ClientLevel)(Object) this;

        // ONLY process events from the PRIMARY level.
        // Secondary levels (PortalWorldManager) fire onChunkLoaded too, but they
        // are NOT dimension changes — they're portal rendering data.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != level) return;

        ResourceKey<Level> currentDim = level.dimension();

        // Detect dimension change - just track it, don't clear
        // Server sends fresh portal data for new dimension, so clearing would break it
        if (seamlessportals$lastDimension != null && seamlessportals$lastDimension != currentDim) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] Dimension change detected: {} -> {}. Tracking dimension change.",
                seamlessportals$lastDimension.identifier(), currentDim.identifier()
            );
            StencilPortalRenderer.cleanup();
            seamlessportals$loggedChunkScan = false;
        }
        seamlessportals$lastDimension = currentDim;

        // Scan chunk for portal blocks in the PRIMARY level only
        LevelChunk chunk = level.getChunk(chunkPos.x(), chunkPos.z());
        if (chunk == null) return;

        for (int sectionIdx = 0; sectionIdx < chunk.getSectionsCount(); sectionIdx++) {
            LevelChunkSection section = chunk.getSection(sectionIdx);
            if (section == null || section.hasOnlyAir()) continue;

            // Palette pre-check: skip the full 16x16x16 scan unless this section's
            // palette actually contains nether portal. maybeHas is O(palette) and never
            // false-negatives, so detection is identical — but it skips the 4096-block
            // sweep for the ~99% of sections that have no portal block. This per-chunk
            // scan (on the render thread, every chunk load at RD 32) was a top stall
            // source; IP scans zero blocks. (T1)
            if (!section.maybeHas(s -> s.is(Blocks.NETHER_PORTAL))) continue;

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
                        }
                    }
                }
            }
        }
    }

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void seamlessportals$onDisconnect(CallbackInfo ci) {
        com.warwa.seamlessportals.chunk.RedirectedPacketApplier.clearPending();
        RemoteChunkManager.clearAll();
        PortalManager.resetClient();
        seamlessportals$loggedChunkScan = false;
        seamlessportals$lastDimension = null;
    }
}
