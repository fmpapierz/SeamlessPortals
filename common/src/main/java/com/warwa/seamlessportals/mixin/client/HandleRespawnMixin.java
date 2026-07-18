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
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.stats.StatsCounter;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
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
     * Shadow of {@code ClientPacketListener.level} — a field separate from
     * {@code mc.level}. Vanilla's {@code handleRespawn} only re-assigns it
     * inside its {@code if (dimensionChanged)} block. When the client-first
     * seamless teleport has already re-leveled {@code mc.player},
     * {@code dimensionChanged} is false and vanilla skips the assignment,
     * leaving {@code this.level} on the OLD dim. Every subsequent
     * {@code handleLevelChunkWithLight} then routes chunks into the stale
     * level's chunk source; the chunk's section count doesn't match the
     * encoded data and {@code LevelChunkSection.read} overruns the buffer.
     *
     * {@code beforeRespawn} writes this explicitly when
     * {@code alreadyClientSwapped} is set.
     */
    @Shadow private net.minecraft.client.multiplayer.ClientLevel level;

    /**
     * Shadow of {@code ClientPacketListener.levelData}. Same story —
     * vanilla only re-assigns it inside the dimensionChanged block. Updated
     * by {@code beforeRespawn} on the pre-swap path to mirror what vanilla
     * would have constructed, so downstream code that reads seaLevel /
     * isFlat / hardcore / difficulty sees the dest dim's values.
     */
    @Shadow private net.minecraft.client.multiplayer.ClientLevel.ClientLevelData levelData;

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
     * Set in {@code beforeRespawn} when the client-first path
     * ({@link com.warwa.seamlessportals.client.SeamlessClientTeleport#performCrossing})
     * already performed the visual swap before this respawn packet arrived.
     *
     * <p>In this state {@code mc.level} and {@code mc.levelRenderer} are
     * already the dest dim's values, so the redirects must NOT do another
     * swap. But we still let vanilla {@code handleRespawn} run so that
     * {@code ClientPacketListener.level} (a separate field from
     * {@code mc.level}) gets pointed at the same cached level — otherwise
     * subsequent {@code ClientboundLevelChunkWithLightPacket} decodes against
     * the stale old-dim level, hitting the chunk section count of the wrong
     * dim and overflowing the reader buffer.
     *
     * <p>The redirects check this flag: {@code redirectNewClientLevel}
     * returns the already-pre-swapped {@code mc.level} instead of promoting
     * again; {@code redirectSetLevel} returns without calling
     * {@code mc.setLevel}; {@code redirectCreatePlayer} returns the existing
     * {@code mc.player}; {@code redirectSetCameraEntity} elides the null as
     * before (via {@code rendererWasPromoted}).
     */
    private boolean seamlessportals$alreadyClientSwapped = false;

    /**
     * Non-null while handling a STALE (superseded-crossing) respawn: the cached
     * secondary ClientLevel for the packet's dimension that {@code this.level}
     * must be pointed at — the packet-stream framing follows the SERVER's dim
     * while the visuals stay on the client's newer dim. Set in
     * {@code beforeRespawn}'s stale branch, consumed by the new-ClientLevel
     * redirect and the player-reuse helper, cleared in {@code afterRespawn}.
     */
    private ClientLevel seamlessportals$staleRespawnLevel = null;

    /**
     * Detect seamless transitions: if we have pre-loaded chunks for the destination,
     * flag it so startWaitingForNewLevel can be skipped.
     *
     * We inject at the start of handleRespawn to check the destination dimension.
     */
    private long seamlessportals$respawnStartNanos = 0;

    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void seamlessportals$beforeRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        // NETTY PRE-PASS GUARD (root cause of the 20:04:41 floating-lava misfire):
        // handleRespawn is entered FIRST on the Netty thread; vanilla's
        // ensureRunningOnSameThread then re-queues it to the main thread by THROWING —
        // so this HEAD inject runs twice, and on the netty pass the @At("RETURN")
        // cleanup (afterRespawn) never runs. Acting here off-thread both (a) mutated
        // this.level/levelData + the transition flags concurrently with the render
        // thread and (b) leaked alreadyClientSwapped=true into the NEXT handleRespawn
        // execution — whose new-ClientLevel redirect then mislabeled the CURRENT
        // mc.level as the packet's dimension. ClientPacketListener.level pointed at
        // the overworld object while the server streamed nether packets; vanilla
        // per-block updates (dimension-less) painted nether lava into the overworld
        // at 1:1 aliased coords. Only the main-thread pass may touch our state.
        if (!mc.isSameThread()) return;
        // S17 sweep DEFENSIVE GATE (wf_5183007f-fee — supersedes the B4 "no gate" decision):
        // every flag-ON-live branch of this handler is judge-verified inert (block-era-keyed
        // writers all gated; the RemoteChunkDataPayload branch has NO sender anywhere), but it
        // is the single most powerful ungated handler (respawn hijack into block-era
        // promote/demote) and the inert-by-dormancy shape has failed 4x. Flag-ON, vanilla
        // respawns ride vanilla + IP's onSetWorld cleanup (S16.2-verified, crouch-hatch
        // live-proven); this mixin serves the BLOCK-ERA path only. Flag-OFF unchanged.
        if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) return;
        // Defensive re-init: any Throwable escaping a previous handleRespawn body
        // skips the @At("RETURN") cleanup (the same shape as the netty throw) —
        // never let stale transition state leak into this pass.
        seamlessportals$seamlessTransition = false;
        seamlessportals$rendererWasPromoted = false;
        seamlessportals$alreadyClientSwapped = false;
        seamlessportals$pendingPromotion = null;
        seamlessportals$staleRespawnLevel = null;
        seamlessportals$respawnStartNanos = System.nanoTime();
        ResourceKey<Level> destDim = packet.commonPlayerSpawnInfo().dimension();

        if (mc.player != null && mc.level != null) {
            ResourceKey<Level> currentDim = mc.level.dimension();

            // IP-style client-initiated seamless teleport: when the client's
            // LocalPlayerMixin detector fired first, SeamlessClientTeleport
            // has already swapped mc.level + mc.levelRenderer AND re-leveled
            // mc.player to the dest dim. The incoming respawn packet's dim
            // therefore matches mc.level.dimension().
            //
            // Vanilla handleRespawn (line 1258 of ClientPacketListener.java in
            // 1.21.2):
            //   boolean dimensionChanged = dimensionKey != oldDimensionKey;
            //   if (dimensionChanged) {
            //       this.levelData = new ClientLevel.ClientLevelData(...);
            //       this.level = new ClientLevel(...);
            //       mc.setLevel(this.level);
            //       ...
            //   }
            // Because oldPlayer.level().dimension() was just re-pointed to
            // destDim by our client-first swap, dimensionChanged is FALSE and
            // vanilla skips the whole block. this.level stays on the OLD dim.
            // Downstream chunk packets then decode against the stale level
            // and overrun.
            //
            // Fix: write this.level + this.levelData ourselves here, mirroring
            // what vanilla would have done. Then let vanilla handleRespawn
            // run normally — the rest of its logic (setCameraEntity(null),
            // createPlayer, addEntity, startWaitingForNewLevel) is handled by
            // our existing redirects + the rendererWasPromoted flag.
            // shouldKeep((byte)2) discriminates crossing teleports (server sends
            // Respawn((byte)3), keep-all-data) from DEATH respawns (byte 0):
            // justTeleportedClient is sticky, so without this a same-dim death
            // respawn after any crossing would take the idempotent path and
            // REUSE THE DEAD PLAYER instead of letting vanilla rebuild.
            if (currentDim.equals(destDim)
                    && packet.shouldKeep((byte) 2)
                    && com.warwa.seamlessportals.client.SeamlessClientTeleport.justTeleportedClient) {
                seamlessportals$alreadyClientSwapped = true;
                seamlessportals$seamlessTransition = true;
                seamlessportals$rendererWasPromoted = true;
                this.level = mc.level;
                this.levelData = new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(
                    this.levelData.getDifficulty(),
                    this.levelData.isHardcore(),
                    packet.commonPlayerSpawnInfo().isFlat());
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS TIMING] handleRespawn IDEMPOTENT: pre-swapped to {}, wrote this.level + this.levelData",
                    destDim.identifier());
                return;
            }

            // STALE RESPAWN (superseded crossing): a double-crossing race — this
            // respawn's crossing was already client-first swapped AND swapped BACK
            // before the packet got processed (currentDim != destDim, but the
            // client's latest swap targeted the current dim). The packet-stream
            // framing must still advance to the packet's dim: every vanilla play
            // packet between this respawn and the next one is dest-dim traffic, and
            // block updates carry NO dimension — they write blindly to
            // ClientPacketListener.level. Mislabeling it paints nether blocks into
            // the overworld (the floating-lava bug). So: point this.level at the
            // CACHED dest level (the demoted secondary — it exists because the
            // client crossed there and back), keep ALL visuals on the current dim,
            // and let the imminent next respawn realign via the idempotent branch.
            // The dest-dim traffic meanwhile lands in the very level the portal
            // view renders — correct on both counts.
            // Guards: shouldKeep((byte)2) = crossing teleport, not a death respawn
            // (a cross-dim DEATH respawn must take the full vanilla path — no later
            // respawn would come to realign the visuals); post-swap window = a
            // superseded crossing's respawn always arrives within ~RTT of the swap;
            // consumeSupersededSwapInto = the client ITSELF swapped into destDim and
            // has since swapped onward — the discriminator a /tp or other server-
            // initiated cross-dim teleport (identical Respawn((byte)3) on the wire)
            // does not satisfy, so those take the full promotion path and swap the
            // visuals for real. CONSUMING: each bounce entry matches exactly one
            // respawn, so even a /tp INTO a just-bounced dim only misfires if it
            // beats that bounce's own respawn — which then realigns everything.
            if (com.warwa.seamlessportals.client.SeamlessClientTeleport.justTeleportedClient
                    && packet.shouldKeep((byte) 2)
                    && com.warwa.seamlessportals.client.SeamlessClientTeleport.isInPostSwapWindow()
                    && !currentDim.equals(destDim)
                    && currentDim.equals(
                        com.warwa.seamlessportals.client.SeamlessClientTeleport.getLastClientSwapDim())
                    && com.warwa.seamlessportals.client.SeamlessClientTeleport
                        .consumeSupersededSwapInto(destDim)) {
                ClientLevel cached = PortalWorldManager.getLevel(destDim);
                if (cached != null) {
                    // The secondary's bounded cache (radius portalRenderDistance=8,
                    // storage 11) must accept the dest-dim deliveries of the stale
                    // window — a send discarded client-side but counted delivered
                    // server-side becomes a false "held" seed on the next crossing
                    // (the walking-limbo mechanism in miniature). No-op on the
                    // unbounded store and when already sized.
                    cached.getChunkSource().updateViewRadius(
                        ((ClientPacketListenerAccessorMixin) (Object) this)
                            .seamlessportals$getServerChunkRadius());
                    seamlessportals$staleRespawnLevel = cached;
                    seamlessportals$alreadyClientSwapped = true;  // setLevel skip + player reuse
                    seamlessportals$seamlessTransition = true;    // loading-screen skip + afterRespawn cleanup
                    seamlessportals$rendererWasPromoted = true;   // camera-null elide + removed-flag clear
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS TIMING] handleRespawn STALE: superseded crossing → this.level follows packet dim {} (visuals stay {})",
                        destDim.identifier(), currentDim.identifier());
                    return;
                }
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS TIMING] handleRespawn STALE but no cached level for {} — falling through to promotion path",
                    destDim.identifier());
            }

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
                + "IILnet/minecraft/client/renderer/extract/LevelExtractor;ZJI)"
                + "Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel seamlessportals$redirectNewClientLevel(
            ClientPacketListener connection,
            ClientLevel.ClientLevelData levelData,
            ResourceKey<Level> dimension,
            Holder<DimensionType> dimensionType,
            int serverChunkRadius,
            int serverSimulationDistance,
            LevelExtractor levelExtractor,
            boolean isDebug,
            long seed,
            int seaLevel) {

        if (seamlessportals$alreadyClientSwapped) {
            Minecraft mc = Minecraft.getInstance();
            // STALE respawn (superseded crossing): the stream framing must follow
            // the PACKET's dim — hand vanilla the cached secondary level for it,
            // never mc.level (which is on the client's newer dim). Dim-verified:
            // a leaked/stale field must never reintroduce the mislabel.
            if (seamlessportals$staleRespawnLevel != null
                    && seamlessportals$staleRespawnLevel.dimension().equals(dimension)) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS RENDERER-SWAP] STALE respawn → return cached secondary level for {}",
                    dimension.identifier());
                return seamlessportals$staleRespawnLevel;
            }
            if (seamlessportals$staleRespawnLevel != null) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS RENDERER-SWAP] staleRespawnLevel is {} ≠ packet {} — discarding it",
                    seamlessportals$staleRespawnLevel.dimension().identifier(), dimension.identifier());
                seamlessportals$staleRespawnLevel = null;
            }
            // DIMENSION VERIFY (belt-and-braces for the 20:04:41 class of bug):
            // returning mc.level is only ever correct when it IS the packet's dim.
            // A mismatch here means a flag leak/race — mislabeling this.level would
            // route the server's dest-dim packet stream (incl. dimension-less block
            // updates) into the wrong level. Resolve BY DIMENSION instead, with no
            // promotion side effects (setLevel stays skipped → visuals untouched):
            // the cached secondary if present, else a vanilla fresh level.
            if (mc.level != null && !mc.level.dimension().equals(dimension)) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS RENDERER-SWAP] alreadyClientSwapped but mc.level is {} ≠ packet {} — "
                        + "refusing the mislabel, resolving by dimension",
                    mc.level.dimension().identifier(), dimension.identifier());
                ClientLevel byDim = PortalWorldManager.getLevel(dimension);
                if (byDim != null) return byDim;
                return new ClientLevel(connection, levelData, dimension, dimensionType,
                    serverChunkRadius, serverSimulationDistance, levelExtractor,
                    isDebug, seed, seaLevel);
            } else {
                // Client-first path: SeamlessClientTeleport.performCrossing
                // already promoted the cached ClientLevel to mc.level. Returning
                // mc.level here tells vanilla handleRespawn to use the SAME
                // instance, so ClientPacketListener.level ends up pointed at our
                // already-swapped level (no fresh allocation, no allChanged(),
                // no mesh loss). Compiled chunk meshes stay alive.
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS RENDERER-SWAP] alreadyClientSwapped → return existing mc.level for {}",
                    dimension.identifier());
                return mc.level;
            }
        }
        if (seamlessportals$seamlessTransition) {
            Minecraft mc = Minecraft.getInstance();
            LevelRenderState sharedState =
                mc.gameRenderer.gameRenderState().levelRenderState;
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
            serverChunkRadius, serverSimulationDistance, levelExtractor,
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
        if (seamlessportals$alreadyClientSwapped) {
            // Client-first path: mc.level + mc.levelRenderer were already
            // swapped by SeamlessClientTeleport.performCrossing. Skip
            // mc.setLevel entirely — it would call levelRenderer.setLevel,
            // i.e. allChanged(), wiping every compiled section. The replayed
            // side-effects (particleEngine.setLevel, gameRenderer.setLevel)
            // were also already done by the client-first path.
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS RENDERER-SWAP] alreadyClientSwapped → skip mc.setLevel for {}",
                level.dimension().identifier());
            return;
        }
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

        // Resize the promoted level's chunk cache to the server view distance —
        // same latent-limbo fix as doVisualSwap step 1 (a mod-created secondary's
        // bounded cache radius is portalRenderDistance=8; as the ACTIVE level it
        // would silently discard every vanilla send beyond storage 11, unhealable
        // because the chunks stay inside the tracking view). No-op on the
        // unbounded SeamlessClientChunkMap.
        level.getChunkSource().updateViewRadius(
            ((ClientPacketListenerAccessorMixin) (Object) this).seamlessportals$getServerChunkRadius());

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
        // 26.2: the {@code lastCameraSectionX/Y/Z} fields were removed from
        // LevelRenderer; the camera-section gate now lives inside
        // {@code ViewArea.repositionCamera(SectionPos)}. Seed the promoted
        // renderer's ViewArea center directly to the preserved player's section
        // so the first vanilla repositionCamera is a no-op (returns false → no
        // slot relocation / mesh reset). See LevelRendererAccessorMixin and
        // SeamlessClientTeleport for the matching translation.
        //
        // SEAMLESS-26.2-TODO: unlike the old pure field-write seed, this WILL
        // relocate+reset slots if the cached ViewArea center differs from the
        // preserved player's section. Verify no first-frame mesh wipe at runtime
        // (viewarea_reposition_mesh_loss).
        LocalPlayer preservedPlayer = mc.player;
        if (preservedPlayer != null) {
            LevelRendererAccessorMixin accessor =
                (LevelRendererAccessorMixin) (Object) promotion.renderer();
            net.minecraft.client.renderer.ViewArea viewArea =
                accessor.seamlessportals$getViewArea();
            if (viewArea != null) {
                viewArea.repositionCamera(
                    net.minecraft.core.SectionPos.of(preservedPlayer.position()));
            }
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
        // STALE respawn: the player's VISUAL dim is ahead of this packet — its
        // level field already points at the newer (current) dim and must NOT be
        // dragged back to the superseded dest. Reuse the instance untouched; the
        // imminent next respawn's idempotent pass confirms the current dim.
        if (seamlessportals$staleRespawnLevel != null) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PLAYER-REUSE] Preserved LocalPlayer (id={}) — stale respawn, level untouched ({})",
                oldPlayer.getId(), oldPlayer.level().dimension().identifier());
            return oldPlayer;
        }
        // Dim-verify (defensive-path guard): only re-point the player's level
        // when the destination IS the visual level — on the dimension-verify
        // fallback path destLevel is a by-dim-resolved secondary while visuals
        // stay put; dragging the player's level field there would make
        // collision/sound resolve against a non-rendered level.
        if (mc.level != null && !destLevel.dimension().equals(mc.level.dimension())) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS PLAYER-REUSE] Preserved LocalPlayer (id={}) — dest {} ≠ visual {}, level untouched",
                oldPlayer.getId(), destLevel.dimension().identifier(),
                mc.level.dimension().identifier());
            return oldPlayer;
        }
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
     * Skip the input replacement on the REUSED player (same rationale as the
     * attribute self-copy skips): vanilla's {@code newPlayer.input = new
     * KeyboardInput(...)} makes sense for a fresh player, but on the reused
     * instance it blanks all held keys for one tick (forcing a spurious
     * EMPTY→held ServerboundPlayerInput resend and a fake jump edge on the
     * next tick) at every crossing. The existing KeyboardInput keeps its
     * held-key state; nothing else references the replaced object.
     */
    @Redirect(method = "handleRespawn",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/player/LocalPlayer;input:Lnet/minecraft/client/player/ClientInput;",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void seamlessportals$skipInputReplacementOnReuse(
            LocalPlayer player, net.minecraft.client.player.ClientInput value) {
        // The instanceof guard makes this safe even if a promoted transition ever
        // ships a genuinely FRESH player: its field-initializer base ClientInput
        // (whose tick() is a no-op — dead keyboard) would not satisfy it, so the
        // fresh KeyboardInput still gets assigned.
        if (seamlessportals$rendererWasPromoted
                && player == Minecraft.getInstance().player
                && player.input instanceof net.minecraft.client.player.KeyboardInput) {
            return;
        }
        player.input = value;
    }

    /**
     * STALE-pass guard for vanilla's {@code this.level.addEntity(newPlayer)}
     * (ClientPacketListener.java:1322). On a stale respawn {@code this.level} is
     * the superseded dim's SECONDARY level and {@code newPlayer} is the live,
     * visually-elsewhere reused LocalPlayer — registering it there would (a)
     * rebind the player's single levelCallback to the secondary's section
     * storage, (b) add it to that level's tickingEntities (players are
     * always-ticking), double-ticking the local player via tickRemoteWorlds
     * (double physics + duplicate move packets — violates the crossing rule
     * that nothing may disturb rotation/velocity), and (c) arm a
     * DISCARDED-flag trap for the dim's next promotion (its addEntity's
     * internal removeEntity would flag the live player AFTER doVisualSwap's
     * unsetRemoved — a frozen player). The packet framing does not need the
     * player inside the dest storage; skip the add entirely.
     */
    @Redirect(method = "handleRespawn",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;addEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private void seamlessportals$skipStaleAddEntity(ClientLevel level, Entity entity) {
        // Symmetric condition (covers the stale branch AND the defensive
        // dimension-verify fallbacks, where staleRespawnLevel was discarded):
        // never register the LIVE local player into a level that is not the
        // visual one.
        Minecraft mc = Minecraft.getInstance();
        if (seamlessportals$staleRespawnLevel != null
                || (entity == mc.player && level != mc.level)) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PLAYER-REUSE] Skip addEntity of the live player into non-visual level {}",
                level.dimension().identifier());
            return;
        }
        level.addEntity(entity);
    }

    /**
     * ROOT CAUSE of the post-crossing sprint-modifier theft (and the FOV pulse
     * it produced): after createPlayer, vanilla handleRespawn copies the old
     * player's attributes onto the new one —
     * {@code newPlayer.getAttributes().assignAllValues(oldPlayer.getAttributes())}
     * (shouldKeep((byte)1) is true for our Respawn((byte)3) teleport packet).
     * With the player-reuse redirect above, newPlayer == oldPlayer, so this is
     * a SELF-copy — and {@code AttributeInstance.replaceFrom} implements the
     * copy as {@code modifierById.clear(); modifierById.putAll(other.modifierById)}.
     * When other == this, the clear empties the very map it then copies from:
     * every modifier on every attribute is silently destroyed, including the
     * {@code minecraft:sprinting} speed modifier — walk-speed FOV for one tick
     * until the sprint keeper repaired it (the visible FOV dip).
     *
     * A self-assign is never meaningful (the copy's purpose is old→new transfer,
     * and identity reuse already IS the transfer), so both assignment overloads
     * are skipped on identity. Distinct instances (vanilla fresh-player path)
     * call through untouched.
     */
    @Redirect(method = "handleRespawn",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/attributes/AttributeMap;"
                + "assignAllValues(Lnet/minecraft/world/entity/ai/attributes/AttributeMap;)V"))
    private void seamlessportals$skipSelfAssignAllValues(AttributeMap self, AttributeMap other) {
        if (self == other) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PLAYER-REUSE] Skipped self assignAllValues (would wipe all attribute modifiers)");
            return;
        }
        self.assignAllValues(other);
    }

    @Redirect(method = "handleRespawn",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/attributes/AttributeMap;"
                + "assignBaseValues(Lnet/minecraft/world/entity/ai/attributes/AttributeMap;)V"))
    private void seamlessportals$skipSelfAssignBaseValues(AttributeMap self, AttributeMap other) {
        if (self == other) return; // self-copy of base values is a semantic no-op; skip for symmetry
        self.assignBaseValues(other);
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

        SeamlessPortalsConstants.rlog(
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
        boolean didClientSwap = seamlessportals$alreadyClientSwapped;
        boolean didStale = seamlessportals$staleRespawnLevel != null;
        seamlessportals$rendererWasPromoted = false;
        seamlessportals$pendingPromotion = null;
        seamlessportals$alreadyClientSwapped = false;
        seamlessportals$staleRespawnLevel = null;
        if (didStale) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS TIMING] handleRespawn STALE finished ({}ms) — stream framing follows packet dim, visuals untouched",
                (System.nanoTime() - seamlessportals$respawnStartNanos) / 1_000_000);
        } else if (didClientSwap) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS TIMING] handleRespawn IDEMPOTENT finished ({}ms) — client-first swap still intact",
                (System.nanoTime() - seamlessportals$respawnStartNanos) / 1_000_000);
        }

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
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS DEBUG] TELEPORT LANDED: playerPos=({},{},{}) in {} nearbyLinks={}",
                String.format("%.1f", mc.player.getX()),
                String.format("%.1f", mc.player.getY()),
                String.format("%.1f", mc.player.getZ()),
                destDim.identifier(), links.size());
            for (var link : links) {
                SeamlessPortalsConstants.rlog(
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
                    SeamlessPortalsConstants.rlog(
                        "[SEAMLESS] Pre-fed {} chunks around portal ({}, {}) into new {} level",
                        fed, centerChunkX, centerChunkZ, destDim.identifier());
                }
            }
        }

        // Subphase 1 (2026-04-17): no longer needed. After the unified-map
        // refactor of PortalWorldManager, promoteToMain already removes the
        // incoming dim's entry from {@code renderers}/{@code levels} before
        // we get here (it has to — the renderer is now {@code mc.levelRenderer}
        // and the level is {@code mc.level}). So at this point
        // {@code hasRenderer(destDim)} is guaranteed false for a seamless
        // transition. The original call here dated to an earlier architecture
        // where promoteToMain used a "dormant" bucket and a stale secondary
        // in {@code renderers} could linger until this cleanup ran.

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
