package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.client.PortalWorldManager;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eliminates the loading screen during portal-based dimension changes.
 *
 * Following IP's approach: when the player teleports to a dimension we've already
 * pre-loaded chunks for (via PortalWorldManager/RemoteChunkManager), we skip the
 * loading screen entirely and notify the server immediately.
 *
 * Vanilla flow:
 * 1. handleRespawn() → creates new ClientLevel → setClientLoaded(false)
 * 2. startWaitingForNewLevel() → shows LevelLoadingScreen
 * 3. Server sends LEVEL_CHUNKS_LOAD_START → WaitingForServer → WaitingForPlayerChunk
 * 4. Chunks compile → isLevelReady() → notifyPlayerLoaded() → closes screen
 *
 * Our flow (when we have pre-loaded chunks):
 * 1. handleRespawn() → creates new ClientLevel → setClientLoaded(false)
 * 2. startWaitingForNewLevel() → CANCELLED (no loading screen)
 * 3. We immediately send ServerboundPlayerLoadedPacket and set clientLoaded = true
 * 4. After respawn completes, we feed pre-loaded chunks into the new level
 */
@Mixin(ClientPacketListener.class)
public abstract class HandleRespawnMixin {

    @Shadow private boolean clientLoaded;

    /**
     * Persistent ClientLevel cache, keyed by dimension. When the player
     * changes dimension vanilla normally allocates a brand-new ClientLevel
     * and the old one plus all its chunks and entity state is GC'd. We cache
     * the outgoing level here so the NEXT time we enter that dimension we
     * can hand vanilla the existing instance — chunks already loaded stay
     * loaded, meshes don't need to rebuild, and there's no multi-second
     * reload flood from the server.
     *
     * Static + process-lifetime because we want it to survive across
     * session-internal ClientPacketListener reconnects.
     */
    private static final Map<ResourceKey<Level>, ClientLevel> seamlessportals$cachedLevels =
        new ConcurrentHashMap<>();

    /**
     * Track whether we're doing a seamless transition (set before startWaitingForNewLevel,
     * read in the inject, cleared after handleRespawn).
     */
    private boolean seamlessportals$seamlessTransition = false;

    /**
     * Detect seamless transitions: if we have pre-loaded chunks for the destination,
     * flag it so startWaitingForNewLevel can be skipped.
     *
     * We inject at the start of handleRespawn to check the destination dimension.
     */
    private long seamlessportals$respawnStartNanos = 0;

    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void seamlessportals$beforeRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        seamlessportals$respawnStartNanos = System.nanoTime();
        ResourceKey<Level> destDim = packet.commonPlayerSpawnInfo().dimension();
        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null && mc.level != null) {
            ResourceKey<Level> currentDim = mc.level.dimension();
            boolean dimensionChanged = destDim != currentDim;

            // Stash the OUTGOING ClientLevel so that when the player returns
            // to this dimension later, the Redirect below can hand it back
            // instead of letting vanilla allocate a fresh empty level.
            // Chunks already loaded, meshes already built — everything stays.
            if (dimensionChanged) {
                seamlessportals$cachedLevels.put(currentDim, mc.level);
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS LEVEL-CACHE] Stashed ClientLevel for {}",
                    currentDim.identifier());
            }

            if (dimensionChanged && RemoteChunkManager.getChunkCount(destDim) > 0) {
                seamlessportals$seamlessTransition = true;
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS TIMING] handleRespawn START: {} → {} ({} pre-loaded chunks)",
                    currentDim.identifier(), destDim.identifier(),
                    RemoteChunkManager.getChunkCount(destDim));
            }
        }
    }

    // NOTE: the ClientLevel-reuse @Redirect was removed. Reusing a cached
    // ClientLevel by itself does NOT solve the flash/reload:
    //
    //   Minecraft.setLevel(cachedLevel) → updateLevelInEngines →
    //     levelRenderer.setLevel(cachedLevel) → allChanged()
    //
    // allChanged() releases all section buffers and creates a fresh ViewArea,
    // so every chunk mesh has to recompile from scratch even though the
    // ClientLevel still holds the chunk data. That's the multi-second blank
    // terrain you see after teleport.
    //
    // IP-style "both dimensions loaded, no flash, no reload" requires:
    //   1. A LevelRenderer per dimension (not vanilla's single shared one)
    //   2. Swap mc.levelRenderer on teleport so compiled meshes persist
    //   3. Preserve the LocalPlayer across dim change (don't destroy+recreate)
    //   4. Suppress vanilla's chunk resend for dims we've already visited
    //
    // That's a multi-system refactor best done as a focused pass, not bolted
    // onto the respawn path. The `seamlessportals$cachedLevels` map stays
    // for when that work lands.

    /**
     * Skip the loading screen when we have pre-loaded chunks.
     *
     * startWaitingForNewLevel normally creates a LevelLoadTracker and shows
     * a LevelLoadingScreen. We cancel it entirely and send the player-loaded
     * packet immediately so the server knows we're ready.
     */
    @Inject(method = "startWaitingForNewLevel", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$skipLoadingScreen(LocalPlayer player, ClientLevel level,
            LevelLoadingScreen.Reason reason, CallbackInfo ci) {
        if (!seamlessportals$seamlessTransition) return;

        // Send player-loaded notification to server immediately
        // (vanilla would wait until chunks compile, but we already have them)
        ((ClientPacketListener)(Object) this).send(new ServerboundPlayerLoadedPacket());
        this.clientLoaded = true;

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] Skipped loading screen for {} — sent player-loaded immediately",
            level.dimension().identifier());

        ci.cancel();
    }

    /**
     * After handleRespawn completes, feed pre-loaded chunks into the new level
     * and clean up the transition state.
     *
     * The new ClientLevel created by vanilla starts empty. We feed chunks from
     * RemoteChunkManager to give immediate terrain visibility. The server will
     * also send chunks via the normal pipeline, which will update/replace ours.
     */
    @Inject(method = "handleRespawn", at = @At("RETURN"))
    private void seamlessportals$afterRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        if (!seamlessportals$seamlessTransition) return;
        seamlessportals$seamlessTransition = false;
        long respawnCoreNanos = System.nanoTime() - seamlessportals$respawnStartNanos;
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS TIMING] handleRespawn core took {}ms (before chunk-feed)",
            respawnCoreNanos / 1_000_000);
        long chunkFeedStart = System.nanoTime();

        ResourceKey<Level> destDim = packet.commonPlayerSpawnInfo().dimension();
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null) return;

        // Log actual teleport landing position vs our stored portal position
        if (mc.player != null) {
            var pm = com.warwa.seamlessportals.portal.PortalManager.getClientInstance();
            var links = pm.getLinksInRange(destDim, mc.player.blockPosition(), 64);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] TELEPORT LANDED: playerPos=({},{},{}) in {} nearbyLinks={}",
                String.format("%.1f", mc.player.getX()),
                String.format("%.1f", mc.player.getY()),
                String.format("%.1f", mc.player.getZ()),
                destDim.identifier(), links.size());
            for (var link : links) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS DEBUG]   portal link: src={}({}) dest={}({})",
                    link.getSource().getOrigin(), link.getSource().getDimension().identifier(),
                    link.getDestination().getOrigin(), link.getDestination().getDimension().identifier());
            }
        }

        // Feed a SMALL set of pre-loaded chunks into the new ClientLevel —
        // only those immediately around the destination portal. Previously we
        // fed ALL pre-loaded chunks (~289) synchronously on the render thread,
        // causing a 120-205ms hitch that was the single biggest source of
        // perceptible teleport lag.
        //
        // The player's LocalPlayer was just recreated, so mc.player.position()
        // is (0, 0, 0) at this point — we can't use it to find "nearby"
        // chunks. Instead we use the PortalLink's destination origin.
        //
        // The server will send its own chunk packets within a few ticks;
        // those will fill in the rest of the view distance.
        var chunks = RemoteChunkManager.getChunks(destDim);
        if (chunks != null && !chunks.isEmpty()) {
            net.minecraft.client.multiplayer.ClientChunkCache cache = mc.level.getChunkSource();

            // Find the destination portal position — the player should land
            // next to it, so centering the feed there gives immediate
            // visibility in the actual render area.
            var pm = com.warwa.seamlessportals.portal.PortalManager.getClientInstance();
            int centerChunkX = 0, centerChunkZ = 0;
            boolean haveCenter = false;
            var linksList = pm.getLinksInRange(destDim,
                new net.minecraft.core.BlockPos(0, 64, 0), Integer.MAX_VALUE / 2);
            if (!linksList.isEmpty()) {
                // There should typically only be one portal pair relevant here.
                // Picking the first is fine — if there were many, feeding a
                // slightly wrong region still doesn't hurt (server will fix it).
                var destOrigin = linksList.get(0).getDestination().getDimension() == destDim
                    ? linksList.get(0).getDestination().getOrigin()
                    : linksList.get(0).getSource().getOrigin();
                centerChunkX = destOrigin.getX() >> 4;
                centerChunkZ = destOrigin.getZ() >> 4;
                haveCenter = true;
            }

            if (haveCenter) {
                cache.updateViewCenter(centerChunkX, centerChunkZ);

                // Feed a tiny 3x3 chunk region (48 blocks) around the portal —
                // just the player's immediate surroundings. Going from 5x5 (25
                // chunks, 18-41ms) to 3x3 (9 chunks, ~10ms) brings the total
                // teleport hitch under a single render frame. The full 289-
                // chunk preload is still held in RemoteChunkManager for the
                // portal's "other-side" view, and the server sends its own
                // chunk packets within a few ticks to fill the view distance.
                final int radius = 1;
                int fed = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        net.minecraft.world.level.ChunkPos pos =
                            new net.minecraft.world.level.ChunkPos(centerChunkX + dx, centerChunkZ + dz);
                        var sections = chunks.get(pos);
                        if (sections == null) continue;
                        try {
                            io.netty.buffer.ByteBuf rawBuf = io.netty.buffer.Unpooled.buffer();
                            net.minecraft.network.FriendlyByteBuf buf =
                                new net.minecraft.network.FriendlyByteBuf(rawBuf);
                            for (var section : sections) {
                                section.write(buf);
                            }
                            cache.replaceWithPacketData(pos.x(), pos.z(), buf,
                                java.util.Collections.emptyMap(), tag -> {});
                            buf.release();
                            fed++;
                        } catch (Exception e) {
                            // Non-fatal: server will send chunks shortly anyway
                            SeamlessPortalsConstants.LOGGER.debug(
                                "[SEAMLESS] Failed to pre-feed chunk [{},{}]: {}",
                                pos.x(), pos.z(), e.getMessage());
                        }
                    }
                }
                if (fed > 0) {
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS] Pre-fed {} chunks around portal ({}, {}) into new {} level",
                        fed, centerChunkX, centerChunkZ, destDim.identifier());
                }
            }
        }

        // Clean up secondary renderer for the dimension we just arrived in
        // (we're now the primary level for this dimension)
        if (PortalWorldManager.hasRenderer(destDim)) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] Cleaning up secondary renderer for {} (now primary)",
                destDim.identifier());
            // Don't fully cleanup — just remove this dimension's secondary renderer.
            // The old dimension will need a secondary renderer set up when looking
            // back through portals.
            PortalWorldManager.removeRenderer(destDim);
        }

        // Reset chunk-fed state so the portal view system re-initializes
        // when looking at portals from the new dimension
        com.warwa.seamlessportals.render.PortalContextSwitch.resetChunkFedState(destDim);

        // Request server to send portal data for the new dimension
        // This ensures we have all portal links for rendering after dimension change
        String dimId;
        if (destDim == net.minecraft.world.level.Level.OVERWORLD) {
            dimId = "minecraft:overworld";
        } else if (destDim == net.minecraft.world.level.Level.NETHER) {
            dimId = "minecraft:the_nether";
        } else if (destDim == net.minecraft.world.level.Level.END) {
            dimId = "minecraft:the_end";
        } else {
            // Fallback for other dimensions
            dimId = "minecraft:overworld";
        }
        PlatformHelper.getInstance().sendToServer(new ModPayloads.RequestPortalDataPayload(dimId));
        long chunkFeedElapsed = System.nanoTime() - chunkFeedStart;
        long totalElapsed = System.nanoTime() - seamlessportals$respawnStartNanos;
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS TIMING] respawn complete: chunk-feed={}ms, TOTAL={}ms",
            chunkFeedElapsed / 1_000_000, totalElapsed / 1_000_000);
    }
}
