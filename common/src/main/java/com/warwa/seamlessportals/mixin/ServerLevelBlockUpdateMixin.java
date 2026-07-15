package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 1 (blocks only) of the live-portal-view feature.
 *
 * <p>Vanilla {@code ServerLevel.sendBlockUpdated} notifies this level's own
 * chunk trackers + events. It does NOT notify players in other dimensions
 * who happen to be watching this block's chunk through a portal — the
 * player's {@code ChunkHolder} for this chunk only contains players in
 * this dim.
 *
 * <p>This mixin adds a second notification path: for every block update,
 * identify players in OTHER dims whose nearby portal's destination lies in
 * this dim within render-distance of the changed block, and send each of
 * them a {@link ModPayloads.RemoteBlockUpdatePayload} so their cached
 * {@code ClientLevel} for this dim picks up the change live.
 *
 * <p>Scoping: bounded by {@code portalRenderDistance} config around the
 * destination portal center. A block change outside any portal's render
 * radius is ignored — no payload sent. This keeps bandwidth O(nearby
 * portal chunks) rather than O(whole far-dim).
 *
 * <p>Filter: skips the update if no portal link has a destination in this
 * dim at all. The walk of {@code getPlayerList} is the only cost in that
 * common case (one map lookup per player), which is negligible.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBlockUpdateMixin {

    @Inject(
        method = "sendBlockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;I)V",
        at = @At("TAIL"),
        require = 1
    )
    private void seamlessportals$mirrorToPortalWatchers(
            BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        // D3 EXCLUSIVITY GATE (B3 — portal-destruction observation driving PortalManager). Flag ON →
        // IP's breakable-portal revalidation + native tracking own this; the block-era PortalManager
        // is inert. Flag OFF (default) → unchanged.
        if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) return;
        if (oldState == newState) return;
        ServerLevel self = (ServerLevel) (Object) this;
        MinecraftServer server = self.getServer();
        if (server == null) return;

        ResourceKey<Level> thisDim = self.dimension();

        // Portal-destroyed detection: when a nether_portal block transitions
        // to air (obsidian frame broken → cascading canSurvive() failure
        // pops the nether_portal block), tell every client and the server
        // tracker to forget the portal. Without this, our stencil view and
        // PortalManager would keep rendering a phantom portal at air.
        boolean oldIsPortal = oldState.getBlock() instanceof net.minecraft.world.level.block.NetherPortalBlock;
        if (oldIsPortal) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DESTROY-DIAG] nether_portal at {} in {} → {} (isAir={})",
                pos.toShortString(), thisDim.identifier(),
                newState.getBlock().getName().getString(), newState.isAir());
        }
        if (oldIsPortal && newState.isAir()) {
            seamlessportals$handlePortalDestroyed(self, pos, thisDim, server);
        }

        // NOTE (2026-04-26): the player-loop + RemoteBlockUpdatePayload send
        // path that used to live here was MOVED to
        // {@link LevelChunkSetBlockStateMixin}. Reason: vanilla's
        // {@code Level.markAndNotifyBlock} skips the {@code sendBlockUpdated}
        // call for ~99% of fluid-spread setBlock invocations (the
        // {@code chunk.getFullStatus().isOrAfter(BLOCK_TICKING)} gate, the
        // {@code newState == blockState} object-identity wrapper, and Forge
        // {@code captureBlockSnapshots} all filter calls before reaching
        // here). Hooking {@code LevelChunk.setBlockState} TAIL fires
        // unconditionally on every successful state change — including
        // every Air→Water spread. This mixin retains ONLY the portal-
        // destroy detection (above) and the diagnostic logs (above) since
        // those don't need to fire for every fluid spread.
    }

    /**
     * Handle nether_portal → air transition. Unregister the source portal
     * + its linked dest-side portal, and broadcast unregister payloads to
     * every online player so their client drops the stencil + view.
     */
    @org.spongepowered.asm.mixin.Unique
    private static void seamlessportals$handlePortalDestroyed(
            ServerLevel self, BlockPos pos, ResourceKey<Level> thisDim, MinecraftServer server) {
        PortalManager manager = PortalManager.getServerInstance();
        com.warwa.seamlessportals.portal.PortalTracker tracker =
            manager.getTracker(thisDim);
        PortalInfo portal = tracker.getPortalAt(pos).orElse(null);
        if (portal == null) {
            for (PortalInfo p : tracker.getAllPortals()) {
                if (p.containsPoint(net.minecraft.world.phys.Vec3.atCenterOf(pos))) {
                    portal = p;
                    break;
                }
            }
        }
        if (portal == null) return;

        PortalInfo linkedDest = null;
        java.util.Optional<PortalLink> linkOpt =
            manager.getLinkAt(thisDim, portal.getOrigin());
        if (linkOpt.isPresent()) {
            linkedDest = linkOpt.get().getDestination();
        }

        BlockPos originToDrop = portal.getOrigin();
        ResourceKey<Level> dropDim = portal.getDimension();
        manager.unregisterPortal(portal);

        String dropDimId = dropDim.identifier().toString();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlatformHelper.getInstance().sendToClient(p,
                new ModPayloads.PortalUnregisterPayload(dropDimId, originToDrop));
        }

        if (linkedDest != null && linkedDest.getDimension() != null) {
            BlockPos linkedOrigin = linkedDest.getOrigin();
            ResourceKey<Level> linkedDim = linkedDest.getDimension();
            manager.unregisterPortal(linkedDest);
            String linkedDimId = linkedDim.identifier().toString();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                PlatformHelper.getInstance().sendToClient(p,
                    new ModPayloads.PortalUnregisterPayload(linkedDimId, linkedOrigin));
            }
        }

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] Portal destroyed at {} in {} (linked dest {})",
            pos.toShortString(), thisDim.identifier(),
            linkedDest == null ? "none" : linkedDest.getOrigin().toShortString());
    }
}
