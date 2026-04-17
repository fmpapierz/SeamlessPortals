package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.client.PortalWorldManager;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.stats.StatsCounter;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
     * Track whether we're doing a seamless transition (set at handleRespawn HEAD,
     * consumed by the renderer-swap @Redirects, cleared at RETURN).
     */
    private boolean seamlessportals$seamlessTransition = false;

    /**
     * Set by the {@code new ClientLevel} redirect if a cached renderer+level
     * exists for the destination dimension; consumed by the {@code mc.setLevel}
     * redirect to swap {@code mc.levelRenderer} without triggering
     * {@code allChanged()}. Cleared either way on handleRespawn RETURN.
     *
     * Kept as a field (not thread-local) because handleRespawn runs entirely
     * on the client packet thread in a single call.
     */
    private PortalWorldManager.Promotion seamlessportals$pendingPromotion = null;

    /**
     * True once the {@code mc.setLevel} redirect actually swapped
     * {@code mc.levelRenderer} to a promoted renderer. Used by afterRespawn
     * to skip the post-respawn chunk re-feed: the cached ClientLevel already
     * holds the chunks + compiled meshes, so re-feeding would mark sections
     * dirty and force them to recompile, defeating the whole Step 1 purpose.
     */
    private boolean seamlessportals$rendererWasPromoted = false;

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

            if (dimensionChanged && RemoteChunkManager.getChunkCount(destDim) > 0) {
                seamlessportals$seamlessTransition = true;
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS TIMING] handleRespawn START: {} → {} ({} pre-loaded chunks)",
                    currentDim.identifier(), destDim.identifier(),
                    RemoteChunkManager.getChunkCount(destDim));
            }
        }
    }

    /**
     * Intercept the {@code new ClientLevel(...)} construction inside
     * {@code handleRespawn}. When the destination dimension has a cached
     * renderer + level in {@link PortalWorldManager}, return the cached
     * ClientLevel instead of allocating a fresh empty one.
     *
     * This pairs with {@link #seamlessportals$redirectSetLevel} — the
     * cached level's compiled chunk meshes only survive if {@code mc.setLevel}
     * is intercepted to skip {@code levelRenderer.setLevel(newLevel)} (which
     * calls {@code allChanged()} and releases every ViewArea buffer).
     *
     * Also mutates {@link #seamlessportals$pendingPromotion} so the setLevel
     * redirect knows which renderer to install.
     */
    @Redirect(method = "handleRespawn",
        at = @At(value = "NEW",
            target = "(Lnet/minecraft/client/multiplayer/ClientPacketListener;"
                + "Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;"
                + "Lnet/minecraft/resources/ResourceKey;"
                + "Lnet/minecraft/core/Holder;"
                + "IILnet/minecraft/client/renderer/LevelRenderer;ZJI)"
                + "Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel seamlessportals$redirectNewClientLevel(
            ClientPacketListener connection,
            ClientLevel.ClientLevelData levelData,
            ResourceKey<Level> dimension,
            Holder<DimensionType> dimensionType,
            int serverChunkRadius,
            int serverSimulationDistance,
            LevelRenderer levelRenderer,
            boolean isDebug,
            long seed,
            int seaLevel) {

        if (seamlessportals$seamlessTransition) {
            Minecraft mc = Minecraft.getInstance();
            LevelRenderState sharedState =
                mc.gameRenderer.getGameRenderState().levelRenderState;
            PortalWorldManager.Promotion promotion =
                PortalWorldManager.promoteToMain(dimension, sharedState);
            if (promotion != null) {
                seamlessportals$pendingPromotion = promotion;
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS RENDERER-SWAP] Reusing cached ClientLevel for {} — skip vanilla ctor",
                    dimension.identifier());
                return promotion.level();
            }
            // No cache → vanilla ctor, vanilla allChanged(). Not a bug; just
            // means the player entered this dim without prior portal-view
            // warmup. Next round-trip will preserve via demoteFromMain.
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS RENDERER-SWAP] No cache for {} — falling through to vanilla ClientLevel ctor",
                dimension.identifier());
        }
        return new ClientLevel(connection, levelData, dimension, dimensionType,
            serverChunkRadius, serverSimulationDistance, levelRenderer,
            isDebug, seed, seaLevel);
    }

    /**
     * Intercept the {@code this.minecraft.setLevel(this.level)} call inside
     * {@code handleRespawn}. When a pending promotion exists, perform our own
     * context swap that assigns {@code mc.level} + {@code mc.levelRenderer}
     * directly and drives only the side effects of vanilla's
     * {@code updateLevelInEngines} that don't trash compiled meshes.
     *
     * Specifically we DO NOT call {@code mc.levelRenderer.setLevel(level)} —
     * that is the source of {@code allChanged()}, which releases every
     * ViewArea buffer and forces every chunk to recompile from scratch.
     * Instead we swap {@code mc.levelRenderer} to the already-bound primary
     * renderer; it is already on the right level, so no rebuild is needed.
     *
     * The outgoing primary gets handed back to
     * {@link PortalWorldManager#demoteFromMain} so its meshes survive for a
     * future return to its dim.
     *
     * Step 1 scope: keeps {@code setCameraEntity(null)} intact (that black
     * frame is Step 2's job).
     */
    @Redirect(method = "handleRespawn",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V"))
    private void seamlessportals$redirectSetLevel(Minecraft mc, ClientLevel level) {
        PortalWorldManager.Promotion promotion = seamlessportals$pendingPromotion;
        if (!seamlessportals$seamlessTransition || promotion == null) {
            mc.setLevel(level);
            return;
        }
        seamlessportals$pendingPromotion = null;

        LevelRenderer oldRenderer = mc.levelRenderer;
        ClientLevel oldLevel = mc.level;
        ResourceKey<Level> oldDim = oldLevel != null ? oldLevel.dimension() : null;

        // ===== Install promoted renderer + level as the new primary =====
        ((com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin) mc)
            .seamlessportals$setLevelRenderer(promotion.renderer());
        mc.level = level;
        seamlessportals$rendererWasPromoted = true;

        // Phase B (2026-04-17): seed the promoted renderer's
        // {@code lastCameraSection*} bookkeeping fields to the section the
        // player currently lives in, so the very first frame's
        // {@code cullTerrain} sees "no change" and does NOT call
        // {@code viewArea.repositionCamera}. Without this, the ViewArea's
        // stale camera-section (from the last time this dim was active)
        // differs from the reused-player-position section on frame 1, so
        // cullTerrain would wipe all relocated slot meshes via
        // {@code setSectionNode → reset}. OW terrain then renders as empty
        // for the 1-2 frames needed to re-compile → visible flash.
        //
        // The player position used here is the outgoing player's preserved
        // position (Step 3 identity-reuse). The server's post-teleport
        // position packet will move the player shortly; if that subsequent
        // movement crosses a section boundary, cullTerrain will reposition
        // normally but only a small strip of slots — small enough that
        // async compile (Phase A) catches up within the same frame.
        //
        // See memory: viewarea_reposition_mesh_loss.md,
        // step1_5_viewarea_sync_radius_fix.md.
        LocalPlayer preservedPlayer = mc.player;
        if (preservedPlayer != null) {
            int csx = net.minecraft.core.SectionPos.posToSectionCoord(preservedPlayer.getX());
            int csy = net.minecraft.core.SectionPos.posToSectionCoord(preservedPlayer.getY());
            int csz = net.minecraft.core.SectionPos.posToSectionCoord(preservedPlayer.getZ());
            LevelRendererAccessorMixin accessor =
                (LevelRendererAccessorMixin) (Object) promotion.renderer();
            accessor.seamlessportals$setLastCameraSectionX(csx);
            accessor.seamlessportals$setLastCameraSectionY(csy);
            accessor.seamlessportals$setLastCameraSectionZ(csz);
        }

        // ===== Demote outgoing primary (stash meshes for future return) =====
        if (oldRenderer != null && oldLevel != null && oldDim != null
                && oldRenderer != promotion.renderer()) {
            PortalWorldManager.demoteFromMain(oldDim, oldRenderer, oldLevel);
        }

        // ===== Replay the rest of Minecraft.updateLevelInEngines side-effects =====
        // These are the non-mesh-wiping parts of vanilla's setLevel.
        // Deliberately omitted:
        //   - soundManager.stop() — cosmetic; keeps music continuity
        //   - levelRenderer.setLevel(level) — the allChanged() source (Step 1)
        //   - setCameraEntity(null) — Step 2: avoids the "camera briefly points
        //     at a nulled entity, Camera.update re-attaches to a just-created
        //     LocalPlayer at (0,0,0)" flash. See also redirectSetCameraEntity
        //     below — the explicit call in handleRespawn is also elided.
        //   - pendingConnection nulling — not ours to touch.
        mc.particleEngine.setLevel(level);
        mc.gameRenderer.setLevel(level);

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS RENDERER-SWAP] Installed primary for {} (old primary {} demoted)",
            level.dimension().identifier(),
            oldDim != null ? oldDim.identifier() : "null");
    }

    /**
     * Step 2: skip the explicit {@code mc.setCameraEntity(null)} that vanilla
     * issues between {@code setLevel} and the creation of the new
     * {@code LocalPlayer}.
     *
     * With Step 1 in place, the renderer + level are swapped in-place on the
     * same thread — no frame is drawn between {@code setLevel} and
     * {@code setCameraEntity(newPlayer)}. The intermediate null is therefore
     * invisible in principle, but leaving it in has a subtler cost:
     * {@code Camera.update()} runs on the next render and sees
     * {@code entity == null}, which triggers its "re-attach to
     * {@code mc.player}" fallback. By that point {@code mc.player} is already
     * the freshly-spawned {@code LocalPlayer} at {@code (0, 0, 0)} (the server's
     * teleport packet hasn't been processed yet) — so the camera snaps to the
     * origin for a frame. Visible as a flash.
     *
     * Keeping the camera pointed at the outgoing player right up until
     * {@code setCameraEntity(newPlayer)} runs means {@code Camera.update()}
     * never sees a null, never re-attaches, and the camera position stays
     * coherent through the swap.
     *
     * Non-null calls (the subsequent {@code setCameraEntity(newPlayer)}, and
     * any call outside a seamless transition) pass through unchanged.
     */
    @Redirect(method = "handleRespawn",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;setCameraEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private void seamlessportals$redirectSetCameraEntity(Minecraft mc, Entity cameraEntity) {
        if (seamlessportals$rendererWasPromoted && cameraEntity == null) {
            // Elide the null-set; the camera keeps pointing at the outgoing
            // player until the explicit setCameraEntity(newPlayer) below
            // replaces it.
            return;
        }
        mc.setCameraEntity(cameraEntity);
    }

    /**
     * Step 3: preserve the outgoing {@link LocalPlayer} instance across the
     * dim change instead of allocating a fresh one.
     *
     * Vanilla's {@code MultiPlayerGameMode.createPlayer(...)} allocates a
     * brand-new player tied to the destination level. Subsequent handleRespawn
     * code transfers id, delta movement, rotation, and entity-data from the
     * outgoing player to the new one, but the new player's position starts at
     * {@code (0, 0, 0)} until the server's post-teleport position packet
     * arrives. That one-frame origin-snap is what produces the final visible
     * teleport flash (confirmed by the {@code TELEPORT LANDED: (0.0, 0.0, 0.0)}
     * log line in earlier sessions).
     *
     * Reusing the outgoing player instance avoids all of that:
     * <ul>
     *   <li>Position, velocity, rotation, inventory, stats, recipe book,
     *       entity data, arm animation state, input tracking, ambient sound
     *       handlers — all preserved by identity.</li>
     *   <li>The subsequent {@code newPlayer.setId(oldPlayer.getId())} /
     *       {@code mc.player = newPlayer} / state-copy lines in handleRespawn
     *       become self-assignments (no-ops).</li>
     *   <li>The player's {@code level} field is re-pointed to the destination
     *       ClientLevel via {@link EntityLevelAccessorMixin} so that
     *       {@code level()}-dependent code (collision, sound, sky brightness,
     *       etc.) resolves against the new dim.</li>
     * </ul>
     *
     * Both overloads of {@code createPlayer} appear in handleRespawn (a 5-arg
     * path for {@code shouldKeep((byte)2)} true, a 3-arg path otherwise).
     * Both are redirected here so either branch triggers the same reuse.
     *
     * Outside of a seamless transition (same-dim respawn, first login, etc.)
     * the vanilla allocation is preserved untouched.
     */
    @Redirect(method = "handleRespawn",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;"
                + "createPlayer(Lnet/minecraft/client/multiplayer/ClientLevel;"
                + "Lnet/minecraft/stats/StatsCounter;"
                + "Lnet/minecraft/client/ClientRecipeBook;"
                + "Lnet/minecraft/world/entity/player/Input;Z)"
                + "Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer seamlessportals$redirectCreatePlayerFull(
            MultiPlayerGameMode gameMode,
            ClientLevel level,
            StatsCounter stats,
            ClientRecipeBook recipeBook,
            Input lastSentInput,
            boolean wasSprinting) {
        LocalPlayer reused = seamlessportals$maybeReuseOldPlayer(level);
        if (reused != null) return reused;
        return gameMode.createPlayer(level, stats, recipeBook, lastSentInput, wasSprinting);
    }

    @Redirect(method = "handleRespawn",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;"
                + "createPlayer(Lnet/minecraft/client/multiplayer/ClientLevel;"
                + "Lnet/minecraft/stats/StatsCounter;"
                + "Lnet/minecraft/client/ClientRecipeBook;)"
                + "Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer seamlessportals$redirectCreatePlayerShort(
            MultiPlayerGameMode gameMode,
            ClientLevel level,
            StatsCounter stats,
            ClientRecipeBook recipeBook) {
        LocalPlayer reused = seamlessportals$maybeReuseOldPlayer(level);
        if (reused != null) return reused;
        return gameMode.createPlayer(level, stats, recipeBook);
    }

    /**
     * Shared helper for both createPlayer redirects. When the swap is a
     * seamless promotion, return the current {@code mc.player} with its level
     * re-pointed at the destination ClientLevel. Otherwise return null and
     * let the caller fall through to vanilla allocation.
     */
    private LocalPlayer seamlessportals$maybeReuseOldPlayer(ClientLevel destLevel) {
        if (!seamlessportals$rendererWasPromoted) return null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer oldPlayer = mc.player;
        if (oldPlayer == null) return null;
        // Re-point the player's level field to the destination level. The
        // old level still contains the player in its storage + players list;
        // that's tolerated because the old level has been demoted to dormant
        // or secondary — it's not actively rendering or ticking the player.
        // The server's post-teleport entity packets will reconcile membership
        // within a few ticks.
        ((com.warwa.seamlessportals.mixin.EntityLevelAccessorMixin) oldPlayer)
            .seamlessportals$invokeSetLevel(destLevel);
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS PLAYER-REUSE] Preserved LocalPlayer (id={}) across dim change → {}",
            oldPlayer.getId(), destLevel.dimension().identifier());
        return oldPlayer;
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
        // Snapshot + clear the promotion flag so stale state doesn't leak
        // into a non-promoted transition later.
        boolean didPromote = seamlessportals$rendererWasPromoted;
        seamlessportals$rendererWasPromoted = false;
        seamlessportals$pendingPromotion = null;

        // Step 3 cleanup: if we reused the LocalPlayer, the subsequent
        // `this.level.addEntity(newPlayer)` in handleRespawn hit the reused
        // instance with its own internal `removeEntity(id, DISCARDED)` first.
        // That leaves the preserved player flagged removed=DISCARDED, which
        // triggers a "Duplicate entity UUID" warn and would cause the entity
        // to be ignored by level-side iteration paths (tickEntities, collision
        // lookups that filter by isRemoved, etc.). Clear the flag now so the
        // player is usable in its new dim. `onAddedToLevel` already ran as
        // part of addEntity, so section/tracking state is correct.
        Minecraft mc = Minecraft.getInstance();
        if (didPromote && mc.player != null) {
            ((com.warwa.seamlessportals.mixin.EntityLevelAccessorMixin) mc.player)
                .seamlessportals$invokeUnsetRemoved();
        }
        long respawnCoreNanos = System.nanoTime() - seamlessportals$respawnStartNanos;
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS TIMING] handleRespawn core took {}ms (before chunk-feed)",
            respawnCoreNanos / 1_000_000);
        long chunkFeedStart = System.nanoTime();

        ResourceKey<Level> destDim = packet.commonPlayerSpawnInfo().dimension();

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
        //
        // SKIP this block when the renderer was promoted: the cached ClientLevel
        // (now mc.level) already contains the chunks, and re-feeding them
        // would call setSectionDirtyWithNeighbors via onSectionBecomingNonEmpty
        // on the promoted renderer — which would force a recompile of the
        // exact meshes we just preserved by skipping allChanged(). The server
        // will still sync any genuinely-stale chunks through its normal flow.
        var chunks = RemoteChunkManager.getChunks(destDim);
        if (!didPromote && chunks != null && !chunks.isEmpty()) {
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
