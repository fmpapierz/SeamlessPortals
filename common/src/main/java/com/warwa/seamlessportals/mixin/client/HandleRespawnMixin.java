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
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void seamlessportals$beforeRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        ResourceKey<Level> destDim = packet.commonPlayerSpawnInfo().dimension();
        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null && mc.level != null) {
            ResourceKey<Level> currentDim = mc.level.dimension();
            boolean dimensionChanged = destDim != currentDim;

            if (dimensionChanged && RemoteChunkManager.getChunkCount(destDim) > 0) {
                seamlessportals$seamlessTransition = true;
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS] Seamless transition detected: {} → {} ({} pre-loaded chunks)",
                    currentDim.identifier(), destDim.identifier(),
                    RemoteChunkManager.getChunkCount(destDim));
            }
        }
    }

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

        // Feed pre-loaded chunks from RemoteChunkManager into the NEW ClientLevel
        // that vanilla just created. This gives immediate terrain visibility.
        var chunks = RemoteChunkManager.getChunks(destDim);
        if (chunks != null && !chunks.isEmpty()) {
            net.minecraft.client.multiplayer.ClientChunkCache cache = mc.level.getChunkSource();
            int fed = 0;

            // Set view center first (required for inRange check)
            // Use player position as center (vanilla would do this via chunk packets)
            if (mc.player != null) {
                int playerChunkX = mc.player.blockPosition().getX() >> 4;
                int playerChunkZ = mc.player.blockPosition().getZ() >> 4;
                cache.updateViewCenter(playerChunkX, playerChunkZ);
            }

            for (var entry : chunks.entrySet()) {
                net.minecraft.world.level.ChunkPos pos = entry.getKey();
                var sections = entry.getValue();

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

            if (fed > 0) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS] Pre-fed {} chunks into new {} level",
                    fed, destDim.identifier());
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
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] Requested portal data for dimension: {}", dimId);
    }
}
